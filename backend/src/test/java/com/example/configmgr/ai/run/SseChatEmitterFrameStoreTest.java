package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.common.sse.SseWriteBudget;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
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

	@Test
	void emitAppendsThenPublishesThenFansOut() {
		InMemoryRunStore store = new InMemoryRunStore();
		SseChatEmitter out = emitter(store);
		FrameWire wire = new FrameWire(payload -> this.journal.add("send"));
		out.attach(wire.emitter());
		this.journal.clear();

		out.delta("正文");

		assertThat(this.journal)
				.as("顺序固定：取号 → 落档 → 发布唤醒 → 本地扇出（落档必须早于发布，否则被唤醒者读不到该帧）")
				.containsExactly("next:" + RUN_ID, "append:" + RUN_ID, "publish:" + RUN_ID, "send");
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
		assertThat(this.bus.published).containsExactly(RUN_ID + "@1", RUN_ID + "@2", RUN_ID + "@3", RUN_ID + "@4");
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
		when(frames.readFromWithStatus(RUN_ID, 0L, 0)).thenReturn(new FrameStore.ReadResult(List.of(), true));
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
		when(frames.readFromWithStatus(RUN_ID, 0L, 0)).thenReturn(new FrameStore.ReadResult(List.of(), true));
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
		when(frames.readFromWithStatus(RUN_ID, 0L, 0)).thenReturn(new FrameStore.ReadResult(
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
		when(frames.readFromWithStatus(RUN_ID, 0L, 0)).thenReturn(new FrameStore.ReadResult(
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

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private SseChatEmitter emitter(RunStore store, FrameStore frames) {
		return new SseChatEmitter(RUN_ID, store, frames, this.mapper, Runnable::run,
				AiProperties.Sse.DEFAULT_DELIVERY_QUEUE_CAPACITY, AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES,
				SseWriteBudget.direct(), this.bus, null);
	}

	private SseChatEmitter emitter(RunStore store) {
		return new SseChatEmitter(RUN_ID, store, this.frames, this.mapper, Runnable::run,
				AiProperties.Sse.DEFAULT_DELIVERY_QUEUE_CAPACITY, AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES,
				SseWriteBudget.direct(), this.bus, null);
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
