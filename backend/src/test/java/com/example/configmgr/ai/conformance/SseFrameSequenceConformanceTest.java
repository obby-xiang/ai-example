package com.example.configmgr.ai.conformance;

import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 帧序不变量 ①②④在 {@link SseChatEmitter} 这一层的一致性用例（不需要模型、不需要 Redis、不需要 Spring）。
 *
 * <ul>
 * <li><b>① seq 单调递增无缺口</b>：单次运行内，订阅者收到的每一帧都带 {@code seq} 且自 1 起连续 ——
 * 心跳也占号（它同样到达订阅者），因此"缺口"不在订阅者视角出现，只出现在归档视角（T7）；</li>
 * <li><b>② delta 顺序与拼接一致</b>：{@code message_start → delta×N → message_end}，
 * {@code message_end.chars} 必须等于 delta 文本长度之和；</li>
 * <li><b>④ 终态帧之后不再有业务帧</b>：{@code done}/{@code error} 是流上最后一帧，且只出现一次。</li>
 * </ul>
 *
 * <p>
 * 另有两处<b>钉住现行为的 admitted-gap 用例</b>（{@code pins...}，S5c-1 / S5c-3）：
 * 它们断言的是当前实现的既有缺口，不是"应当如此"。修好之后必须改写这两条用例 ——
 * 这正是 ag-ui 把"承认的缺口"也当成一条 fixture 来钉的做法（缺口必须被<b>有意</b>关闭，
 * 而不是悄悄消失）。
 */
class SseFrameSequenceConformanceTest {

	private static final String RUN_ID = "run-frame-sequence";

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final SseChatEmitter out = new SseChatEmitter(RUN_ID, this.store, new ObjectMapper());

	// ── ① seq 连续 + ④ 终帧收尾 ─────────────────────────────────────────────

	@Test
	void singleSubscriberSeesContiguousSeqAndNothingAfterTheTerminalFrame() {
		FrameWire wire = FrameWire.attachTo(this.out);

		this.out.start("s-1");
		this.out.textStart();
		this.out.delta("你好");
		this.out.suspended(List.of(pending("c-1")), this.store.runKey(RUN_ID), this.store.pendingKey(RUN_ID),
				this.store.instanceId());
		this.out.toolStart(pending("c-1"));
		this.out.confirmRequest(pending("c-1"), 120);
		this.out.confirmDecision(pending("c-1"), "approve", "用户批准", 1234L);
		this.out.toolResult(pending("c-1"), true, false, "导出作业已启动");
		this.out.delta("已启动");
		this.out.textEnd(5);
		this.out.done(Map.of(), "deepseek-flash", Map.of("cancelled", false));

		List<Map<String, Object>> frames = wire.frames();
		assertThat(frames).extracting(frame -> frame.get("seq")).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L,
				9L, 10L, 11L);
		// 整条流（含四条不变量）一致；终帧是最后一帧
		FrameContract.assertConformant(frames);
		assertThat(FrameContract.type(frames.get(frames.size() - 1))).isEqualTo("done");
		assertThat(FrameContract.ofType(frames, "done")).hasSize(1);
	}

	@Test
	void errorTerminalIsAlsoTheLastFrame() {
		FrameWire wire = FrameWire.attachTo(this.out);

		this.out.start("s-1");
		this.out.textStart();
		this.out.delta("半截");
		this.out.textEnd(2);
		this.out.error("UPSTREAM_SILENT_TIMEOUT_AFTER_PARTIAL", "上游事件间静默 90s");

		List<Map<String, Object>> frames = wire.frames();
		assertThat(FrameContract.types(frames)).containsExactly("start", "message_start", "delta", "message_end",
				"error");
		FrameContract.assertConformant(frames);
	}

	/**
	 * <b>【待裁决 S5c-5】</b>钉住失败路径上"终帧发在正文段收口之前"这一现行为。
	 *
	 * <p>
	 * 事实链：{@code ResilientChatService} 的三条终局里，只有 {@code finish()}
	 * （{@code out.textEnd(...)} → {@code done}）与 {@code cancelTerminal()}（同）会关正文段；
	 * {@code fail()} 直接 {@code out.error(...)}，<b>不发</b> {@code message_end}。于是"已产出半截正文后
	 * 上游报错"这一形态下，运行以"仍开着的文本消息"收尾。
	 *
	 * <p>
	 * 借来的判据（ag-ui {@code open-message-at-run-finished-fatal}）："运行关闭前，它打开的一切都必须先关闭"。
	 * 修复方向：{@code fail()} 在 {@code out.error(...)} 前补一次 {@code out.textEnd(accumulator.text().length())}
	 * （与另两条终局同口径）。本用例届时必须改写 —— 它钉的是缺陷，不是契约。
	 */
	@Test
	void pinsOpenTextSegmentOnTheErrorPath() {
		FrameWire wire = FrameWire.attachTo(this.out);

		// 生产 fail() 的帧序：没有 textEnd
		this.out.start("s-1");
		this.out.textStart();
		this.out.delta("半截");
		this.out.error("UPSTREAM_SILENT_TIMEOUT_AFTER_PARTIAL", "上游事件间静默 90s");

		List<Map<String, Object>> frames = wire.frames();
		assertThat(FrameContract.types(frames)).containsExactly("start", "message_start", "delta", "error");
		assertThat(FrameContract.terminalContract(frames)).as("终帧本身仍是最后一帧").isEmpty();
		assertThat(FrameContract.textSegmentContract(frames))
				.as("失败路径不关正文段 ⇒ 违反\"终帧前必须关闭已打开的文本段\"（本用例钉住的缺陷）")
				.anySatisfy(violation -> assertThat(violation).contains("没有 message_end"));
	}

	// ── ① 心跳占号但不落归档（T7） ──────────────────────────────────────────

	/**
	 * 心跳<b>占号</b>（它是一条到达订阅者的帧）但<b>不落归档</b>（T7：避免挤掉有信息量的状态帧）。
	 * 于是同一次运行有两套序号事实：订阅者视角连续、归档视角有洞。
	 *
	 * <p>
	 * kill：让心跳也走 {@code appendEvent}（去掉 {@code SseChatEmitter#emit} 的心跳分支）⇒
	 * 归档不再有洞、活动戳不再被写。
	 */
	@Test
	void heartbeatConsumesSeqButLeavesOnlyAnActivityStampInTheArchive() {
		FrameWire wire = FrameWire.attachTo(this.out);

		this.out.start("s-1");
		this.out.heartbeat(Map.of("suspendKind", "confirm", "waitedSeconds", 2));
		this.out.heartbeat(Map.of("suspendKind", "confirm", "waitedSeconds", 4));
		this.out.delta("正文");

		List<Map<String, Object>> frames = wire.frames();
		assertThat(FrameContract.types(frames)).containsExactly("start", "heartbeat", "heartbeat", "delta");
		assertThat(FrameContract.seqContract(frames)).as("订阅者视角：心跳占号 ⇒ 序号连续无缺口").isEmpty();
		assertThat(this.store.archivedSeqs(RUN_ID)).as("归档视角：心跳不落档 ⇒ seq 1、4 有洞")
				.containsExactly(1L, 4L);
		assertThat(this.store.archivedTypes(RUN_ID)).containsExactly("start", "delta");
		assertThat(this.store.lastBeatAtMs(RUN_ID)).as("心跳留下活动戳（僵尸判据的输入）").isPositive();
		assertThat(FrameContract.archiveSeqContract(this.store.events(RUN_ID))).as("归档本身不得乱序或重号")
				.isEmpty();
	}

	// ── ② delta 拼接一致性（emitter 层：口径就是 chars 必须对着 delta 求和） ──

	@Test
	void textSegmentBoundariesBracketTheDeltasExactlyOnce() {
		FrameWire wire = FrameWire.attachTo(this.out);

		this.out.start("s-1");
		assertThat(this.out.textStart()).isTrue();
		assertThat(this.out.textStart()).as("同一段边界只开一次（幂等）").isFalse();
		this.out.delta("第一段");
		this.out.delta("第二段");
		assertThat(this.out.textEnd(6)).isTrue();
		assertThat(this.out.textEnd(6)).as("收过的段不再收（幂等）").isFalse();
		this.out.done(Map.of(), null, Map.of("cancelled", false));

		List<Map<String, Object>> frames = wire.frames();
		assertThat(FrameContract.types(frames)).containsExactly("start", "message_start", "delta", "delta",
				"message_end", "done");
		FrameContract.assertConformant(frames);
		assertThat(FrameContract.oneOfType(frames, "message_end")).containsEntry("chars", 6L);
		assertThat(FrameContract.ofType(frames, "delta")).allSatisfy(
				frame -> assertThat(frame).containsEntry("messageId", "assistant-" + RUN_ID));
	}

	// ── 钉住现行为（S5c-1）：心跳占号 + 续号锚点 = 归档末帧 ────────────────

	/**
	 * <b>【待裁决 S5c-1】</b>钉住一个<u>跨进程续号的缺口</u>，不是应有行为。
	 *
	 * <p>
	 * 事实链：① 心跳占号但不落归档；② 重启后的新写出器从 {@code RunStore#lastEventSeq}（<b>归档末帧</b>）
	 * 续号；③ 前端把收到的<b>任何</b>帧的 seq 计入 {@code lastSeq}（心跳也算，
	 * 见 {@code scripts/verify-e2e.ps1#Invoke-AiTurn} 与前端 {@code runSeq[runId]}）。
	 * 于是：旧进程在归档末帧之后又发过若干心跳（序号 5..7），新进程续号从 4+1=5 开始 →
	 * <b>重用了前端 lastSeq 已覆盖的序号</b>；该帧在随后的差量重挂里被判为孤儿而<b>丢弃</b>。
	 *
	 * <p>
	 * 真实证据面：{@code docs/evidence/DC14-微调实施验证.md} §3.6 的实测原文
	 * （归档 seq 1..4，实时心跳拿到 14/15/16 ⇒ 挂起期已有 5..13 这 9 个头号被心跳用掉且没落档）。
	 *
	 * <p>
	 * 修复方向（不在本棒修改生产代码）：让心跳<b>不占号</b>，或续号锚点改成"已发放过的最大号"
	 * （与归档解耦）。本用例届时必须改写 —— 它钉的是缺陷，不是契约。
	 */
	@Test
	void pinsHeartbeatSeqReuseAcrossEmitterRestartWhichCanOrphanTheFirstResumedFrame() {
		// 旧进程：归档 seq 1..4（逐号落档）
		this.store.seedArchive(RUN_ID, FrameContract.fixture("start", 1L), FrameContract.fixture("suspended", 2L),
				FrameContract.fixture("tool_start", 3L, "toolCallId", "c-1"),
				FrameContract.fixture("tool_result", 4L, "toolCallId", "c-1"));
		// 旧进程随后发了三帧心跳（占号 5、6、7，不落档）—— 前端把 7 记进 lastSeq
		long frontendLastSeq = 7L;

		// 进程重启：续跑用的新写出器从归档末帧（4）续号
		SseChatEmitter resumed = new SseChatEmitter(RUN_ID, this.store, new ObjectMapper());
		FrameWire live = FrameWire.attachTo(resumed);
		resumed.start("s-1");

		assertThat(FrameContract.ofType(live.frames(), "start").get(0))
				.as("重启后的首帧重用了心跳用过的序号（本用例钉住的缺陷）")
				.containsEntry("seq", 5L);

		FrameWire reattached = new FrameWire();
		int replayed = resumed.replayTo(reattached.emitter(), false, frontendLastSeq);

		assertThat(replayed).as("seq 5 被当孤儿丢掉 ⇒ 差量重挂漏了这个真实帧").isZero();
		assertThat(reattached.frames()).isEmpty();
	}

	// ── 钉住现行为（S5c-3）：两线程并发出帧时到达顺序可能逆序 ──────────────

	/**
	 * <b>【待裁决 S5c-3】</b>钉住"序号唯一且无缺口，但<b>到达顺序</b>可能逆序"这一现行为。
	 *
	 * <p>
	 * 事实链：{@code SseChatEmitter#emit} 的取号（原子）与写出（{@code emitter.send}）不是一次
	 * 不可分割的动作，而<b>出帧的线程不唯一</b>：运行线程在挂起等待里每 2s 发一次心跳
	 * （{@code ConfirmGate#await}），HTTP 线程在 {@code POST /api/ai/confirm|frontend-tool-result}
	 * 里发决策帧。两者撞在同一瞬间时，先取号的线程可能后写出。
	 *
	 * <p>
	 * 影响面：① 客户端 {@code lastSeq} 取最大值，故不影响差量重挂的完整性；
	 * ② 但"seq 严格递增到达"这条更强的口径不成立，回放/实时混流时前端若按 seq 排序渲染，
	 * 可能把两帧的先后画反。修复方向：单写点（帧队列）或对写出加同一把序号锁。
	 * 本用例钉住现行为，修复后必须改写。
	 */
	@Test
	void pinsOutOfOrderArrivalWhenTwoThreadsEmitConcurrently() throws Exception {
		SseChatEmitter emitter = new SseChatEmitter(RUN_ID, this.store, new ObjectMapper());
		CountDownLatch firstFrameInSend = new CountDownLatch(1);
		CountDownLatch releaseFirstFrame = new CountDownLatch(1);
		List<String> arrival = new CopyOnWriteArrayList<>();
		FrameWire wire = new FrameWire(payload -> {
			if (payload.contains("\"tool_result\"")) {
				firstFrameInSend.countDown();
				try {
					releaseFirstFrame.await(5, TimeUnit.SECONDS);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			}
			arrival.add(payload.contains("\"tool_result\"") ? "tool_result" : "heartbeat");
		});
		emitter.attach(wire.emitter());

		// HTTP 线程（确认门决策）先取号=1，但卡在写出上；运行线程（挂起心跳）取号=2 并先到达
		Thread httpThread = new Thread(() -> emitter.toolResult(pending("c-1"), true, false, "结果"), "http-confirm");
		httpThread.start();
		assertThat(firstFrameInSend.await(5, TimeUnit.SECONDS)).as("HTTP 线程已取号并卡在写出").isTrue();
		emitter.heartbeat(Map.of("suspendKind", "confirm", "waitedSeconds", 2));
		releaseFirstFrame.countDown();
		httpThread.join(5000);

		List<Map<String, Object>> frames = wire.frames();
		assertThat(arrival).containsExactly("heartbeat", "tool_result");
		assertThat(frames).extracting(frame -> frame.get("seq")).as("序号本身唯一、无缺口（取号是原子的）")
				.containsExactly(2L, 1L);
		assertThat(FrameContract.seqContract(frames)).as("到达顺序逆序 ⇒ 严格递增口径被打破（本用例钉住的缺陷）")
				.anySatisfy(violation -> assertThat(violation).contains("seq=2，期望 1"));
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private static PendingToolCall pending(String toolCallId) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(toolCallId);
		pending.setName("start_export");
		pending.setKind(PendingToolCall.KIND_CONFIRM);
		pending.setArguments("{\"taskId\":7}");
		return pending;
	}

}
