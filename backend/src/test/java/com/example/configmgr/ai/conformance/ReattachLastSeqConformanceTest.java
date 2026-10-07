package com.example.configmgr.ai.conformance;

import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 不变量 <b>⑤ {@code ?lastSeq=} 差量重挂</b>：只补发 lastSeq 之后的帧，补发帧的 seq 连续、
 * 与原流<b>既不重复也不丢失</b>（{@code GET /api/ai/events/{runId}?lastSeq=N} 的服务端口径）。
 *
 * <p>
 * 重挂的真实动机（ADR-5 补记 CH-P4）：同 sessionId 第二轮会拿到 409，前端据此改走重挂端点；
 * 因此"重挂拿到的帧"与"一直在线的那个订阅者拿到的帧"必须能拼成完整的一轮 ——
 * 拼不起来就是丢消息，而丢消息在 SSE 里不会报错，只会静默少一段。
 *
 * <p>
 * 这里用的是<b>真实</b>的 {@link SseChatEmitter} 与真实语义的外置状态（{@link InMemoryRunStore}），
 * 只把订阅者做成帧线（{@link FrameWire}）。
 */
class ReattachLastSeqConformanceTest {

	private static final String RUN_ID = "run-reattach";

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final SseChatEmitter out = new SseChatEmitter(RUN_ID, this.store, new ObjectMapper());

	// ── ⑤ 差量补发的完整性：不重复、不丢失、保序 ─────────────────────────────

	@Test
	void deltaReplaySendsOnlyFramesAfterLastSeqWithoutLossOrDuplication() {
		FrameWire online = FrameWire.attachTo(this.out);
		emitArchivedTurn();

		List<Map<String, Object>> beforeAttach = online.frames();
		assertThat(seqs(beforeAttach)).containsExactly(1L, 2L, 3L, 4L, 5L);

		// 前端已知到 seq 2（外置帧 1..2）：重挂只应补 3、4、5
		FrameWire reattached = new FrameWire();
		int replayed = this.out.replayTo(reattached.emitter(), false, 2L);

		List<Long> replayedSeqs = seqs(reattached.frames());
		assertThat(replayed).isEqualTo(3);
		assertThat(replayedSeqs).containsExactly(3L, 4L, 5L);
		assertThat(FrameContract.archiveSeqContract(reattached.frames())).as("补发帧自身有序且不重号").isEmpty();

		// 拼起来 = 完整一轮：本地已有的 1..2 ∪ 补发的 3..5，无重复、无缺口
		List<Long> union = new ArrayList<>();
		union.addAll(seqs(beforeAttach).subList(0, 2));
		union.addAll(replayedSeqs);
		assertThat(union).containsExactly(1L, 2L, 3L, 4L, 5L);
		FrameContract.assertConformant(beforeAttach);
	}

	/**
	 * 前端给出的 {@code lastSeq} 可能落在"归档里不存在"的号上（升级期的老帧、或某次落档失败
	 * 留下的洞）。补发必须照旧给出所有更大的归档帧，不能因为"归档里没有 seq=N"而少发或错发。
	 *
	 * <p>
	 * 注：S5c-1 修复后，心跳不再占业务号 ⇒ 心跳不会再制造这种洞；本用例改用"落档失败留下的洞"
	 * 构造同一输入形态（seq 3 缺席），守住同一条规则。
	 *
	 * <p>
	 * kill：把 {@code isOrphan} 的判据从 {@code seq > lastSeq} 改成"从归档里找 seq == lastSeq 的位置再截断"
	 * ⇒ 找不到该号就返回空补发，本用例立刻变红。
	 */
	@Test
	void replayFromALastSeqThatIsNotInTheArchiveLosesNothing() {
		// 归档：1、2、4、5（seq 3 缺席 —— 例如那一帧落档失败）
		this.store.seedArchive(RUN_ID, FrameContract.fixture("start", 1L), FrameContract.fixture("suspended", 2L),
				FrameContract.fixture("tool_start", 4L, "toolCallId", "c-1"),
				FrameContract.fixture("tool_result", 5L, "toolCallId", "c-1"));

		FrameWire reattached = new FrameWire();
		int replayed = this.out.replayTo(reattached.emitter(), false, 3L);

		assertThat(replayed).isEqualTo(2);
		assertThat(seqs(reattached.frames())).containsExactly(4L, 5L);
		assertThat(FrameContract.types(reattached.frames())).containsExactly("tool_start", "tool_result");
	}

	@Test
	void replayWithLastSeqAtTheArchiveTailSendsNothing() {
		FrameWire online = FrameWire.attachTo(this.out);
		emitArchivedTurn();
		assertThat(this.store.lastEventSeq(RUN_ID)).as("归档末帧号 = 前端已知号 ⇒ 无需补发").isEqualTo(5L);

		FrameWire reattached = new FrameWire();
		assertThat(this.out.replayTo(reattached.emitter(), false, 5L)).isZero();
		assertThat(reattached.frames()).isEmpty();
		assertThat(online.frames()).isNotEmpty();
	}

	// ── ⑤ 老客户端口径（不传 lastSeq）与升级期老帧 ───────────────────────────

	/**
	 * 不传 {@code lastSeq} 时保持 S4.2 时期的口径：全量回放<b>状态类帧</b>，
	 * 跳过 {@code delta}/{@code heartbeat}（避免"回放 + 实时"把同一段正文渲染两遍）。
	 *
	 * <p>
	 * kill：把 {@code includeDelta} 的默认口径改成 true ⇒ 回放里出现 delta，本用例变红
	 * （前端会重复渲染正文）。
	 */
	@Test
	void replayWithoutLastSeqKeepsTheLegacyStateOnlyShape() {
		this.out.start("s-1");
		this.out.delta("正文一");
		this.out.heartbeat(Map.of("phase", "streaming"));
		this.out.suspended(List.of(pending("c-1")), this.store.runKey(RUN_ID), this.store.pendingKey(RUN_ID),
				this.store.instanceId());
		this.out.done(Map.of(), "deepseek-flash", Map.of("cancelled", false));

		FrameWire reattached = new FrameWire();
		int replayed = this.out.replayTo(reattached.emitter(), false, null);

		assertThat(replayed).isEqualTo(3);
		assertThat(FrameContract.types(reattached.frames())).containsExactly("start", "suspended", "done");
	}

	/**
	 * 升级期的老帧（T6 之前写入归档、<b>没有 seq</b>）无法判定孤儿，按"宁可不发、不可丢帧"处理：
	 * 一律照发。
	 *
	 * <p>
	 * kill：把 {@code isOrphan} 的兜底改成"没有 seq 就当孤儿丢弃" ⇒ 升级中的那一轮会丢帧。
	 */
	@Test
	void legacyFramesWithoutSeqAreNeverTreatedAsOrphans() {
		Map<String, Object> legacy = new LinkedHashMap<>();
		legacy.put("type", "suspended");
		this.store.seedArchive(RUN_ID, FrameContract.fixture("start", 1L), legacy,
				FrameContract.fixture("tool_start", 3L, "toolCallId", "c-1"),
				FrameContract.fixture("tool_result", 4L, "toolCallId", "c-1"));

		FrameWire reattached = new FrameWire();
		int replayed = this.out.replayTo(reattached.emitter(), false, 3L);

		// 老帧（无 seq，照发）+ seq 4（> lastSeq）；seq 1、3 是孤儿
		assertThat(replayed).isEqualTo(2);
		assertThat(FrameContract.types(reattached.frames())).containsExactly("suspended", "tool_result");
	}

	// ── ⑤ 跨进程续号（重挂的相邻口径） ───────────────────────────────────────

	/**
	 * <b>【S5c-2 回归】</b>重挂的"回放 + 挂订阅"必须是一次原子交接：回放期间发出的实时帧
	 * 不能被交接窗口吞掉。
	 *
	 * <p>
	 * 事实链：老写法 {@code replayTo(...)} 之后才 {@code attach(...)}；两次调用之间若有实时帧写出，
	 * 该帧既不在回放快照里（快照已读完）、也还没被这个订阅者接住 ⇒ 静默丢帧。
	 *
	 * <p>
	 * 本用例把窗口确定化：让回放的第一帧卡在"慢订阅者"上（此时交接尚未完成），
	 * 与此同时另一线程发一帧实时 {@code suspended}。修复后（同一把出帧锁内完成回放 + 挂订阅）
	 * 实时帧必然排到交接之后 ⇒ 新订阅者收得到；老写法下它落在窗口里 ⇒ 新订阅者永远收不到。
	 *
	 * <p>
	 * kill：把 {@code AiController#events} 改回 {@code replayTo} + {@code attach} 两次调用
	 * （或让 {@code replayAndAttach} 不持锁）⇒ 本用例的"实时帧必须到达"断言变红。
	 */
	@Test
	void replayAndAttachIsAtomicSoNoFrameEscapesTheHandoffWindow() throws Exception {
		this.out.start("s-1");
		this.out.suspended(List.of(pending("c-1")), this.store.runKey(RUN_ID), this.store.pendingKey(RUN_ID),
				this.store.instanceId());

		CountDownLatch firstReplayFrameInSend = new CountDownLatch(1);
		CountDownLatch releaseReplay = new CountDownLatch(1);
		AtomicBoolean blocked = new AtomicBoolean(false);
		FrameWire reattached = new FrameWire(payload -> {
			if (blocked.compareAndSet(false, true)) {
				firstReplayFrameInSend.countDown();
				try {
					releaseReplay.await(5, TimeUnit.SECONDS);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			}
		});

		AtomicInteger replayed = new AtomicInteger(-1);
		Thread reattaching = new Thread(() -> replayed.set(this.out.replayAndAttach(reattached.emitter(), 0L, false)),
				"reattach");
		reattaching.start();
		assertThat(firstReplayFrameInSend.await(5, TimeUnit.SECONDS)).as("回放已开始（仍卡在第一帧的写出上）").isTrue();

		// 交接窗口内发出的实时帧：修复前它既不在回放快照里、订阅也还没挂上
		Thread live = new Thread(() -> this.out.suspended(List.of(pending("c-2")), this.store.runKey(RUN_ID),
				this.store.pendingKey(RUN_ID), this.store.instanceId()), "live-frame");
		live.start();
		releaseReplay.countDown();
		reattaching.join(5000);
		live.join(5000);

		List<Map<String, Object>> received = reattached.frames();
		assertThat(seqs(received)).as("回放帧 + 交接窗口内的实时帧都必须在（不丢帧、不重号）")
				.containsExactly(1L, 2L, 3L);
		assertThat(received.get(received.size() - 1)).as("最后一帧是交接之后发出的实时帧（toolCallId=c-2）")
				.containsEntry("seq", 3L)
				.satisfies(frame -> assertThat(String.valueOf(frame.get("toolCalls"))).contains("c-2"));
		assertThat(FrameContract.archiveSeqContract(this.store.events(RUN_ID))).as("归档不得乱序或重号").isEmpty();
	}

	/**
	 * 进程重启后，续跑的新写出器从归档末帧续号（{@code RunStore#lastEventSeq}）——
	 * 否则归档里会出现两段重叠序号，前端的 lastSeq 差量会漏帧。
	 *
	 * <p>
	 * kill：把 {@code nextSeq()} 的初值改成 0（不从归档续号）⇒ 新帧从 1 起号，
	 * 与旧帧重号，本用例变红。
	 */
	@Test
	void seqContinuesFromTheArchiveTailAcrossAProcessRestart() {
		for (long seq = 1; seq <= 41; seq++) {
			this.store.seedArchive(RUN_ID, FrameContract.fixture("delta", seq, "messageId", "m-1", "text", "x"));
		}

		SseChatEmitter resumed = new SseChatEmitter(RUN_ID, this.store, new ObjectMapper());
		FrameWire live = FrameWire.attachTo(resumed);
		resumed.suspended(List.of(pending("c-2")), this.store.runKey(RUN_ID), this.store.pendingKey(RUN_ID),
				this.store.instanceId());

		assertThat(seqs(live.frames())).containsExactly(42L);

		// 前端 lastSeq = 41（它收到了旧进程的全部归档帧）⇒ 补发恰好是这一帧，不重不漏
		FrameWire reattached = new FrameWire();
		assertThat(resumed.replayTo(reattached.emitter(), false, 41L)).isEqualTo(1);
		assertThat(seqs(reattached.frames())).containsExactly(42L);
		assertThat(FrameContract.archiveSeqContract(this.store.events(RUN_ID)))
				.as("归档里不得出现两段重叠序号").isEmpty();
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	/** 一轮"状态帧齐全"的语料：start(1) → suspended(2) → tool_start(3) → tool_result(4) → done(5)。 */
	private void emitArchivedTurn() {
		this.out.start("s-1");
		this.out.suspended(List.of(pending("c-1")), this.store.runKey(RUN_ID), this.store.pendingKey(RUN_ID),
				this.store.instanceId());
		this.out.toolStart(pending("c-1"));
		this.out.toolResult(pending("c-1"), true, false, "导出作业已启动（作业 #4242）");
		this.out.done(Map.of(), "deepseek-flash", Map.of("cancelled", false));
	}

	/** 帧的 seq（归一成 Long；缺 seq 的帧跳过）。 */
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

	private static PendingToolCall pending(String toolCallId) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(toolCallId);
		pending.setName("start_export");
		pending.setKind(PendingToolCall.KIND_CONFIRM);
		pending.setArguments("{\"taskId\":7}");
		return pending;
	}

}
