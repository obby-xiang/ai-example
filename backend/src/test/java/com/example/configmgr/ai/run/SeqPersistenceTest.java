package com.example.configmgr.ai.run;

import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T3-2 序号持久化（{@code ai:seq:<runId>}）与 delta 跳过归档的机制单测。
 *
 * <ul>
 * <li><b>重号场景（设计卡验收）</b>：发放 3 帧、落档第 2/3 帧失败（进程内模拟 Redis
 * 部分失败）→ 新实例续跑，新帧必须续到 seq=4 —— 续号锚点是
 * {@code max(归档末帧, ai:seq)}，不再只是归档末帧；</li>
 * <li><b>delta 不进归档</b>：{@code LLEN}（归档帧数）只计状态帧，但 delta 照常占用
 * 业务序号并推进 {@code ai:seq}（前端 lastSeq 口径不变）；</li>
 * <li><b>发放即登记</b>：每帧 {@code recordIssuedSeq}，跨实例可读（InMemoryRunStore
 * 替身与 Redis 实现同语义）。</li>
 * </ul>
 */
class SeqPersistenceTest {

	private static final String RUN_ID = "run-seq";

	/** 落档失败注入：按 seq 丢弃归档写入（{@code ai:seq} 登记不受影响 —— 正是 S2-4 的口径）。 */
	private static final class FlakyArchiveStore extends InMemoryRunStore {

		private final Set<Long> dropSeqs;

		private final List<Long> dropped = new CopyOnWriteArrayList<>();

		FlakyArchiveStore(Set<Long> dropSeqs) {
			this.dropSeqs = dropSeqs;
		}

		@Override
		public void appendEvent(String runId, Map<String, Object> frame) {
			Object seq = frame.get("seq");
			if (seq instanceof Number number && this.dropSeqs.contains(number.longValue())) {
				touchActivity(runId);
				this.dropped.add(number.longValue());
				return;
			}
			super.appendEvent(runId, frame);
		}

	}

	/**
	 * 设计卡 T3-2 验收场景：发放 3 帧后落档第 2、3 帧失败 →（重启 ⇒ ）新实例续跑，
	 * 新帧 seq=4 无重号。
	 *
	 * <p>
	 * kill：把 {@code nextSeq()} 的锚点改回只看归档末帧 ⇒ 新实例会从 2 起号，
	 * 与前端 lastSeq=3 覆盖范围重叠，差量重挂把新帧当孤儿丢掉 —— 本用例的 seq=4 断言变红。
	 */
	@Test
	void newInstanceContinuesSeqAfterPartialArchiveFailures() {
		FlakyArchiveStore store = new FlakyArchiveStore(Set.of(2L, 3L));

		SseChatEmitter first = new SseChatEmitter(RUN_ID, store, new ObjectMapper());
		first.start("s-1");
		first.suspended(List.of(pending("c-1")), "k1", "k2", "i1");
		first.done(Map.of(), "model-x", Map.of("cancelled", false));

		assertThat(store.archivedSeqs(RUN_ID)).as("落档失败留下的洞：归档只剩 seq 1").containsExactly(1L);
		assertThat(store.dropped).containsExactly(2L, 3L);
		assertThat(store.lastIssuedSeq(RUN_ID)).as("发放即登记：ai:seq 已推进到 3").isEqualTo(3L);

		// 进程重启：续跑的新写出器（同一外置状态）
		SseChatEmitter resumed = new SseChatEmitter(RUN_ID, store, new ObjectMapper());
		CapturingEmitter wire = new CapturingEmitter();
		resumed.attach(wire.emitter);
		resumed.suspended(List.of(pending("c-2")), "k1", "k2", "i2");

		assertThat(wire.seqs()).containsExactly(4L);
		assertThat(store.archivedSeqs(RUN_ID)).as("归档仍有序无重号").containsExactly(1L, 4L);
		assertThat(store.lastIssuedSeq(RUN_ID)).isEqualTo(4L);
	}

	/** delta 不进归档：归档帧数（LLEN 口径）只计状态帧，业务序号仍连续推进。 */
	@Test
	void deltaFramesAreNeverArchivedButStillConsumeBusinessSeq() {
		InMemoryRunStore store = new InMemoryRunStore();
		SseChatEmitter out = new SseChatEmitter(RUN_ID, store, new ObjectMapper());

		out.start("s-1");
		out.textStart();
		out.delta("第一段");
		out.delta("第二段");
		out.textEnd(6);
		out.heartbeat(Map.of("phase", "streaming"));
		out.done(Map.of(), "model-x", Map.of("cancelled", false));

		assertThat(store.archivedTypes(RUN_ID)).as("LLEN 只计状态帧：delta/heartbeat 都不入档")
				.containsExactly("start", "message_start", "message_end", "done");
		assertThat(store.archivedSeqs(RUN_ID)).containsExactly(1L, 2L, 5L, 6L);
		assertThat(store.lastEventSeq(RUN_ID)).as("归档末帧不再是续号锚点（中间隔着未入档的 delta）")
				.isEqualTo(6L);
		assertThat(store.lastIssuedSeq(RUN_ID)).as("ai:seq = 已发放的最大业务号（delta 占号）").isEqualTo(6L);
		assertThat(store.lastBeatAtMs(RUN_ID)).as("delta/心跳的活动戳仍在（僵尸判据输入）").isPositive();
	}

	/** 终态轮重挂回放与 ai:seq 协同：回放只给状态帧，序号口径不乱。 */
	@Test
	void replaySeesOnlyStateFramesWithSeqPersistenceIntact() {
		InMemoryRunStore store = new InMemoryRunStore();
		SseChatEmitter out = new SseChatEmitter(RUN_ID, store, new ObjectMapper());
		out.start("s-1");
		out.delta("正文");
		out.suspended(List.of(pending("c-1")), "k1", "k2", "i1");

		CapturingEmitter wire = new CapturingEmitter();
		int replayed = out.replayTo(wire.emitter, null);

		assertThat(replayed).isEqualTo(2);
		assertThat(wire.seqs()).containsExactly(1L, 3L);
		assertThat(store.lastIssuedSeq(RUN_ID)).isEqualTo(3L);
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	/** 直接录 send 原文的轻量订阅者（FrameWire 的进程内简化版，seq 断言够用）。 */
	private static final class CapturingEmitter {

		private final org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter =
				org.mockito.Mockito.mock(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.class);

		private final List<String> payloads = new CopyOnWriteArrayList<>();

		private final ObjectMapper mapper = new ObjectMapper();

		CapturingEmitter() {
			try {
				org.mockito.Mockito.doAnswer(invocation -> {
					// 与 FrameWire 同一坑：getArgument 泛型 + String.valueOf 重载会推出 char[]，必须先落 Object
					Object argument = invocation.getArgument(0);
					this.payloads.add(String.valueOf(argument));
					return null;
				}).when(this.emitter).send(org.mockito.ArgumentMatchers.anyString());
			}
			catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
		}

		List<Long> seqs() {
			List<Long> out = new java.util.ArrayList<>();
			for (String payload : this.payloads) {
				try {
					Map<String, Object> frame = this.mapper.readValue(payload, Map.class);
					Object seq = frame.get("seq");
					if (seq instanceof Number number) {
						out.add(number.longValue());
					}
				}
				catch (Exception ex) {
					throw new IllegalStateException(ex);
				}
			}
			return out;
		}

	}

	private static PendingToolCall pending(String toolCallId) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(toolCallId);
		pending.setName("start_export");
		pending.setKind(PendingToolCall.KIND_CONFIRM);
		pending.setArguments("{\"taskId\":7}");
		return pending;
	}

}
