package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.common.sse.SseWriteBudget;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B23L1 批① · 出帧链路（M6/M7）：<b>取号 → 落档 → 唤醒发布 → 本地扇出</b>。
 *
 * <p>
 * 断言的是流水顺序与可观测结果（journal 记 next/append/publish/send），
 * 不是"某个方法被调用过"——顺序错了 SSE 的语义就错了（订阅者先看到帧、日志后落档 ⇒ 唤醒者读不到）。
 */
class SseChatEmitterFrameStoreTest {

	private static final String RUN_ID = "run-emit";

	private final List<String> journal = new CopyOnWriteArrayList<>();

	private final InMemoryFrameStore frames = new InMemoryFrameStore(this.journal);

	private final FakeWakeBus bus = new FakeWakeBus(this.journal);

	private final ObjectMapper mapper = new ObjectMapper();

	/** 投递身份作用域：写出器按身份解析后才能归档/取号。 */
	private RequestContextUtils.SafeCloseable identity;

	@BeforeEach
	void bindIdentity() {
		this.identity = RequestContextUtils.useContext(1L, 101L);
	}

	@AfterEach
	void closeIdentity() {
		if (this.identity != null) {
			this.identity.close();
		}
	}

	@Test
	void emitAppendsThenFansOutThenPublishes() {
		InMemoryRunStore store = new InMemoryRunStore();
		SseChatEmitter out = emitter(store);
		FrameWire wire = new FrameWire(payload -> this.journal.add("send"));
		out.attach(wire.emitter());
		this.journal.clear();

		out.delta("正文");

		assertThat(this.journal)
				.as("顺序固定：取号 → 落档 → 本地扇出 → 发布唤醒。"
						+ "资源修复批：发布挪到 emitLock 之外（锁内只累计上界），因此它排在本地扇出之后 —— "
						+ "落档仍早于发布（否则被唤醒者读不到该帧），而 PUBLISH 的网络往返不再占着出帧锁")
				.containsExactly("next:" + RUN_ID, "append:" + RUN_ID, "send", "publish:" + RUN_ID);
		assertThat(this.bus.published).as("唤醒载荷只带上界 seq，不传帧体").containsExactly(RUN_ID + "@1");
		assertThat(seqs(wire.frames())).containsExactly(1L);
		assertThat(this.frames.readFrom(RUN_ID, 0L, 0)).as("帧已落进事件日志").hasSize(1);
	}

	@Test
	void subscriberSeesContinuousSeqAcrossManyFrames() {
		SseChatEmitter out = emitter(new InMemoryRunStore());
		FrameWire wire = FrameWire.attachTo(out);

		out.start("s-1");
		out.delta("一");
		out.delta("二");
		out.done(Map.of(), "deepseek-flash", Map.of("cancelled", false));

		assertThat(seqs(wire.frames())).containsExactly(1L, 2L, 3L, 4L);
		assertThat(seqs(this.frames.readFrom(RUN_ID, 0L, 0))).as("日志与实时流同号同序").containsExactly(1L, 2L, 3L, 4L);
		assertThat(this.bus.published).containsExactly(RUN_ID + "@1", RUN_ID + "@2", RUN_ID + "@3",
				RUN_ID + "@4:terminal");
	}

	/**
	 * M7：心跳<b>不进事件日志、不取业务号、不发唤醒</b>，但仍实时投递且带独立 {@code heartbeatSeq}。
	 *
	 * <p>
	 * kill：把心跳分支并回业务分支（或让 append 收心跳）⇒ "日志为空 / 无 next / 无 publish"变红。
	 */
	@Test
	void heartbeatNeverEntersTheEventLog() {
		InMemoryRunStore store = new InMemoryRunStore();
		SseChatEmitter out = emitter(store);
		FrameWire wire = FrameWire.attachTo(out);

		out.delta("正文");
		this.journal.clear();
		this.bus.published.clear();
		out.heartbeat(Map.of("phase", "suspended"));

		assertThat(this.frames.readFrom(RUN_ID, 0L, 0)).as("心跳不落事件日志").hasSize(1);
		assertThat(this.journal).as("心跳既不取号也不落档").isEmpty();
		assertThat(this.bus.published).as("心跳不触发唤醒（没有新帧可补）").isEmpty();

		List<Map<String, Object>> received = FrameWire.normalize(wire.frames());
		assertThat(received).hasSize(2);
		assertThat(received.get(1)).containsEntry("type", "heartbeat")
				.containsEntry("heartbeatSeq", 1L)
				.doesNotContainKey("seq");
	}

	@Test
	void businessSeqIsUnaffectedByInterleavedHeartbeats() {
		SseChatEmitter out = emitter(new InMemoryRunStore());
		FrameWire wire = FrameWire.attachTo(out);

		out.delta("一");
		out.heartbeat(Map.of("phase", "streaming"));
		out.delta("二");

		assertThat(seqs(FrameWire.normalize(wire.frames()))).containsExactly(1L, 2L);
		assertThat(this.frames.lastSeq(RUN_ID)).isEqualTo(2L);
	}


	@Test
	void terminalDegradedReplaySendsTypedErrorThenCompletes() {
		InMemoryRunStore store = new InMemoryRunStore();
		FrameStore frames = mock(FrameStore.class);
		when(frames.readFromWithStatus(eq(RUN_ID), eq(0L), anyInt())).thenReturn(new FrameStore.ReadResult(List.of(), true));
		SseChatEmitter out = emitter(store, frames);
		FrameWire wire = new FrameWire();

		SseChatEmitter.ReplayResult result = out.replayAndAttachWithStatus(wire.emitter(), null, true);

		assertThat(result.degraded()).isTrue();
		assertThat(result.staged()).isZero();
		List<Map<String, Object>> sent = FrameWire.normalize(wire.frames());
		assertThat(sent).hasSize(1);
		assertThat(sent.get(0)).containsEntry("type", "error")
			.containsEntry("code", "AI_REDIS_UNAVAILABLE")
			.containsEntry("retryable", true)
			.containsEntry("degraded", true);
		verify(wire.emitter()).complete();
	}

	@Test
	void nonTerminalDegradedReplayAlsoCompletesWithTypedError() {
		InMemoryRunStore store = new InMemoryRunStore();
		FrameStore frames = mock(FrameStore.class);
		when(frames.readFromWithStatus(eq(RUN_ID), eq(0L), anyInt())).thenReturn(new FrameStore.ReadResult(List.of(), true));
		SseChatEmitter out = emitter(store, frames);
		FrameWire wire = new FrameWire();

		SseChatEmitter.ReplayResult result = out.replayAndAttachWithStatus(wire.emitter(), null, false);

		assertThat(result.degraded()).isTrue();
		assertThat(FrameWire.normalize(wire.frames())).anySatisfy(frame ->
			assertThat(frame).containsEntry("type", "error").containsEntry("code", "AI_REDIS_UNAVAILABLE"));
		verify(wire.emitter()).complete();
	}

	@Test
	void degradedReplayDoesNotDuplicateAnAlreadyReadDone() {
		InMemoryRunStore store = new InMemoryRunStore();
		FrameStore frames = mock(FrameStore.class);
		when(frames.readFromWithStatus(eq(RUN_ID), eq(0L), anyInt())).thenReturn(new FrameStore.ReadResult(
				List.of(Map.of("type", "done", "seq", 1L)), true));
		SseChatEmitter out = emitter(store, frames);
		FrameWire wire = new FrameWire();

		SseChatEmitter.ReplayResult result = out.replayAndAttachWithStatus(wire.emitter(), null, true);

		assertThat(result.terminalFrame()).isTrue();
		List<Map<String, Object>> sent = FrameWire.normalize(wire.frames());
		assertThat(sent).hasSize(1);
		assertThat(sent.get(0)).containsEntry("type", "done");
		assertThat(sent).noneMatch(frame -> "error".equals(frame.get("type")));
		verify(wire.emitter()).complete();
	}

	@Test
	void degradedReplayDoesNotDuplicateAnAlreadyReadError() {
		InMemoryRunStore store = new InMemoryRunStore();
		FrameStore frames = mock(FrameStore.class);
		when(frames.readFromWithStatus(eq(RUN_ID), eq(0L), anyInt())).thenReturn(new FrameStore.ReadResult(
				List.of(Map.of("type", "error", "code", "UPSTREAM_STREAM_ERROR", "seq", 1L)), true));
		SseChatEmitter out = emitter(store, frames);
		FrameWire wire = new FrameWire();

		SseChatEmitter.ReplayResult result = out.replayAndAttachWithStatus(wire.emitter(), null, true);

		assertThat(result.terminalFrame()).isTrue();
		List<Map<String, Object>> sent = FrameWire.normalize(wire.frames());
		assertThat(sent).hasSize(1);
		assertThat(sent.get(0)).containsEntry("type", "error").containsEntry("code", "UPSTREAM_STREAM_ERROR");
		verify(wire.emitter()).complete();
	}

	@Test
	void replayReadFailureDegradesAndDoesNotLeakTheSubscription() {
		InMemoryRunStore store = new InMemoryRunStore();
		FrameStore frames = mock(FrameStore.class);
		IllegalStateException boom = new IllegalStateException("read boom");
		when(frames.readFromWithStatus(eq(RUN_ID), eq(0L), anyInt())).thenThrow(boom);
		SseChatEmitter out = emitter(store, frames);
		FrameWire wire = new FrameWire();

		SseChatEmitter.ReplayResult plan = out.replayAndAttachWithStatus(wire.emitter(), null, false);

		assertThat(plan.degraded()).as("读失败 ⇒ 明确降级（typed error + 收流），不冒充空回放").isTrue();
		assertThat(FrameWire.normalize(wire.frames())).anySatisfy(frame ->
				assertThat(frame).containsEntry("type", "error").containsEntry("code", "AI_REDIS_UNAVAILABLE"));
		assertThat(out.subscriberCount()).as("回放读异常后不得留下已挂订阅（否则订阅表/唤醒订阅泄漏）").isZero();
	}

	@Test
	void terminalConflictDeliversTheWinningArchiveOnceAndCompletesWithoutSyntheticFailure() {
		InMemoryRunStore store = new InMemoryRunStore();
		store.seedRun(RUN_ID, "s-emit");
		FrameStore frames = mock(FrameStore.class);
		byte[] winner = "{\"type\":\"done\",\"seq\":1,\"cancelled\":false}".getBytes();
		when(frames.appendAndAllocateSeq(eq(RUN_ID), org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong()))
			.thenReturn(new FrameStore.AppendResult(1L, winner, FrameStore.AppendOutcome.TERMINAL_CONFLICT));
		SseChatEmitter out = emitter(store, frames);
		FrameWire wire = FrameWire.attachTo(out);

		out.error("AI_UPSTREAM_ERROR", "failed");
		out.error("AI_UPSTREAM_ERROR", "failed");

		assertThat(FrameWire.normalize(wire.frames())).as("本地未收 winner 时只补投胜出原文一次")
				.hasSize(1)
				.first()
				.satisfies(frame -> {
					assertThat(frame).containsEntry("type", "done").containsEntry("seq", 1L);
				});
		verify(wire.emitter()).complete();
	}

	@Test
	void terminalAfterDeliversTheWinningArchiveAndCompletes() {
		InMemoryRunStore store = new InMemoryRunStore();
		store.seedRun(RUN_ID, "s-emit");
		FrameStore frames = mock(FrameStore.class);
		byte[] winner = "{\"type\":\"done\",\"seq\":1,\"cancelled\":false}".getBytes();
		when(frames.appendAndAllocateSeq(eq(RUN_ID), org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong()))
			.thenReturn(new FrameStore.AppendResult(1L, winner, FrameStore.AppendOutcome.TERMINAL_AFTER));
		SseChatEmitter out = emitter(store, frames);
		FrameWire wire = FrameWire.attachTo(out);

		out.delta("late");

		assertThat(FrameWire.normalize(wire.frames())).hasSize(1)
				.first().satisfies(frame -> assertThat(frame).containsEntry("type", "done"));
		verify(wire.emitter()).complete();
	}

	@Test
	void terminalWinnerAboveOneCompletesOnlyAfterContinuousCatchUp() {
		InMemoryRunStore store = new InMemoryRunStore();
		store.seedRun(RUN_ID, "s-emit");
		FrameStore frames = mock(FrameStore.class);
		byte[] winner = "{\"type\":\"done\",\"seq\":3,\"cancelled\":false}".getBytes();
		when(frames.appendAndAllocateSeq(eq(RUN_ID), org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong()))
			.thenReturn(new FrameStore.AppendResult(3L, winner, FrameStore.AppendOutcome.TERMINAL_AFTER));
		when(frames.readRawFrom(eq(RUN_ID), eq(0L), anyInt())).thenReturn(List.of(
				new FrameStore.RawFrame(1L, "{\"type\":\"delta\",\"seq\":1}"),
				new FrameStore.RawFrame(2L, "{\"type\":\"delta\",\"seq\":2}"),
				new FrameStore.RawFrame(3L, new String(winner))));
		SseChatEmitter out = emitter(store, frames);
		FrameWire wire = FrameWire.attachTo(out);

		out.delta("late");

		assertThat(seqs(FrameWire.normalize(wire.frames()))).containsExactly(1L, 2L, 3L);
		assertThat(FrameWire.normalize(wire.frames()).getLast().get("type")).isEqualTo("done");
		verify(wire.emitter()).complete();
	}

	@Test
	void terminalWinnerAboveOneDoesNotCompleteWhenCatchUpPageIsInsufficient() {
		InMemoryRunStore store = new InMemoryRunStore();
		store.seedRun(RUN_ID, "s-emit");
		FrameStore frames = mock(FrameStore.class);
		byte[] winner = "{\"type\":\"done\",\"seq\":3,\"cancelled\":false}".getBytes();
		when(frames.appendAndAllocateSeq(eq(RUN_ID), org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong()))
			.thenReturn(new FrameStore.AppendResult(3L, winner, FrameStore.AppendOutcome.TERMINAL_AFTER));
		when(frames.readRawFrom(eq(RUN_ID), eq(0L), anyInt()))
			.thenReturn(List.of(new FrameStore.RawFrame(1L, "{\"type\":\"delta\",\"seq\":1}")));
		SseChatEmitter out = emitter(store, frames);
		FrameWire wire = FrameWire.attachTo(out);

		out.delta("late");

		assertThat(FrameWire.normalize(wire.frames())).noneMatch(frame -> "done".equals(frame.get("type")));
		verify(wire.emitter(), org.mockito.Mockito.never()).complete();
		assertThat(this.bus.published).contains(RUN_ID + "@3:terminal");
	}

	@Test
	void terminalWinnerAboveOneDoesNotCompleteWhenCatchUpReadFails() {
		InMemoryRunStore store = new InMemoryRunStore();
		store.seedRun(RUN_ID, "s-emit");
		FrameStore frames = mock(FrameStore.class);
		byte[] winner = "{\"type\":\"done\",\"seq\":3,\"cancelled\":false}".getBytes();
		when(frames.appendAndAllocateSeq(eq(RUN_ID), org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong()))
			.thenReturn(new FrameStore.AppendResult(3L, winner, FrameStore.AppendOutcome.TERMINAL_AFTER));
		when(frames.readRawFrom(eq(RUN_ID), eq(0L), anyInt())).thenReturn(List.of());
		when(frames.readFrom(eq(RUN_ID), eq(0L), anyInt())).thenReturn(List.of());
		SseChatEmitter out = emitter(store, frames);
		FrameWire wire = FrameWire.attachTo(out);

		out.delta("late");

		assertThat(FrameWire.normalize(wire.frames())).noneMatch(frame -> "done".equals(frame.get("type")));
		verify(wire.emitter(), org.mockito.Mockito.never()).complete();
		assertThat(this.bus.published).contains(RUN_ID + "@3:terminal");
	}

	@Test
	void initialInsufficientCatchUpCompletesExactlyOnceAfterWakePaintsTheWinner() {
		InMemoryRunStore store = new InMemoryRunStore();
		store.seedRun(RUN_ID, "s-emit");
		FrameStore frames = mock(FrameStore.class);
		byte[] winner = "{\"type\":\"done\",\"seq\":3,\"cancelled\":false}".getBytes();
		when(frames.appendAndAllocateSeq(eq(RUN_ID), org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong()))
			.thenReturn(new FrameStore.AppendResult(3L, winner, FrameStore.AppendOutcome.TERMINAL_AFTER));
		when(frames.readRawFrom(eq(RUN_ID), eq(0L), anyInt()))
			.thenReturn(List.of(new FrameStore.RawFrame(1L, "{\"type\":\"delta\",\"seq\":1}")));
		SseChatEmitter out = emitter(store, frames);
		FrameWire wire = FrameWire.attachTo(out);
		out.delta("late");
		verify(wire.emitter(), org.mockito.Mockito.never()).complete();
		when(frames.readRawFrom(eq(RUN_ID), org.mockito.ArgumentMatchers.anyLong(), anyInt())).thenReturn(List.of(
				new FrameStore.RawFrame(1L, "{\"type\":\"delta\",\"seq\":1}"),
				new FrameStore.RawFrame(2L, "{\"type\":\"delta\",\"seq\":2}"),
				new FrameStore.RawFrame(3L, new String(winner))));

		out.onWake(RUN_ID, 3L);

		assertThat(seqs(FrameWire.normalize(wire.frames()))).containsExactly(1L, 2L, 3L);
		assertThat(FrameWire.normalize(wire.frames()).getLast().get("type")).isEqualTo("done");
		verify(wire.emitter()).complete();
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private SseChatEmitter emitter(RunStore store, FrameStore frames) {
		return new SseChatEmitter(RUN_ID, store, frames, this.mapper, Runnable::run,
				AiProperties.Sse.DEFAULT_DELIVERY_QUEUE_CAPACITY, AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES,
				SseWriteBudget.direct(), this.bus, null, null);
	}

	private SseChatEmitter emitter(RunStore store) {
		return new SseChatEmitter(RUN_ID, store, this.frames, this.mapper, Runnable::run,
				AiProperties.Sse.DEFAULT_DELIVERY_QUEUE_CAPACITY, AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES,
				SseWriteBudget.direct(), this.bus, null, null);
	}

	private static List<Long> seqs(List<Map<String, Object>> frames) {
		List<Long> out = new ArrayList<>();
		for (Map<String, Object> frame : frames) {
			Object seq = frame.get("seq");
			if (seq instanceof Number number) {
				out.add(number.longValue());
			}
		}
		return out;
	}

}
