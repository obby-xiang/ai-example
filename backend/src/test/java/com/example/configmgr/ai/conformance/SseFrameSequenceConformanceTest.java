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
 * <li><b>① seq 单调递增无缺口</b>：单次运行内，订阅者收到的每一帧都带 {@code seq} 且自 1 起连续。
 * <b>S5c-1 修复后</b>：只有<b>业务帧</b>占用业务序号 —— 心跳带独立的 {@code heartbeatSeq}，
 * 不占号、不落归档，于是订阅者视角与归档视角的 seq 完全一致（不再有"心跳留下的洞"）；</li>
 * <li><b>② delta 顺序与拼接一致</b>：{@code message_start → delta×N → message_end}，
 * {@code message_end.chars} 必须等于 delta 文本长度之和；</li>
 * <li><b>④ 终态帧之后不再有业务帧</b>：{@code done}/{@code error} 是流上最后一帧，且只出现一次。</li>
 * </ul>
 *
 * <p>
 * 本类另有两条<b>修复后回归</b>（原先是 {@code pins...} 缺口用例，S5c-1 / S5c-3 修复后转为
 * 断言修复后的行为）：心跳不占业务号 ⇒ 跨进程续号不会重号；出帧串行 ⇒ 多线程并发出帧的
 * 到达顺序与 seq 顺序一致。这正是 ag-ui 把"承认的缺口"当 fixture 钉住的做法 ——
 * 缺口被<b>有意</b>关闭时，那条用例转正。
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
	 * <b>【S5c-5 修复后】</b>失败路径由服务层（{@code ResilientChatService#fail}）在 {@code error}
	 * 之前补 {@code message_end}；写出器本身只提供幂等的段边界原语（未开过段则不发 end），
	 * 因此"只发 error 不发 message_end"这种流只能由调用方造成。这里钉住这两个事实：
	 * ① 调用方补了 end ⇒ 三段式完整、可对账；② 幂等性（重复 end 不再出帧）。
	 *
	 * <p>
	 * 服务层的真实回归见 {@code ChatTurnFrameSequenceConformanceTest}：
	 * {@code partialContentThenUpstreamFailureClosesTheTextSegmentBeforeTheErrorTerminal}。
	 */
	@Test
	void errorPathClosesTheTextSegmentExactlyOnceWhenTheCallerAsksForIt() {
		FrameWire wire = FrameWire.attachTo(this.out);

		// 生产 fail() 的帧序（S5c-5 修复后）：先收正文段（chars = 已产出长度），再发终帧
		this.out.start("s-1");
		this.out.textStart();
		this.out.delta("半截");
		assertThat(this.out.textEnd(2)).isTrue();
		assertThat(this.out.textEnd(2)).as("段已收，重复调用不再出帧（幂等）").isFalse();
		this.out.error("UPSTREAM_SILENT_TIMEOUT_AFTER_PARTIAL", "上游事件间静默 90s");

		List<Map<String, Object>> frames = wire.frames();
		assertThat(FrameContract.types(frames)).containsExactly("start", "message_start", "delta", "message_end",
				"error");
		FrameContract.assertConformant(frames);
		assertThat(FrameContract.oneOfType(frames, "message_end")).containsEntry("chars", 2L);
	}

	// ── ① 心跳走独立序号空间，且不落归档（S5c-1 + T7） ───────────────────────

	/**
	 * <b>【S5c-1 修复后】</b>心跳<b>不占业务序号</b>：它在独立的 {@code heartbeatSeq} 空间里计数，
	 * 业务 {@code seq} 只发给真正落归档的帧；心跳仍不落归档（T7），只留一个活动戳。
	 *
	 * <p>
	 * 为什么必须这样：心跳不落归档 ⇒ 若它占用业务序号，"归档末帧"这个跨进程续号锚点就会
	 * 落后于已发放的号，重启后的新写出器会重号，而该号已在前端 {@code lastSeq} 覆盖范围内
	 * ⇒ 新帧被判为孤儿而永久丢弃（DC-14 §3.6 实测形态，见下一条用例）。
	 *
	 * <p>
	 * kill：把 {@code SseChatEmitter#emit} 的心跳分支改回 {@code frame.put("seq", nextSeq())}
	 * ⇒ 业务序号出现缺口、归档与订阅者视角不再一致，本用例变红。
	 */
	@Test
	void heartbeatKeepsItsOwnSequenceSpaceAndNeverTouchesTheBusinessAnchor() {
		FrameWire wire = FrameWire.attachTo(this.out);

		this.out.start("s-1");
		this.out.heartbeat(Map.of("suspendKind", "confirm", "waitedSeconds", 2));
		this.out.heartbeat(Map.of("suspendKind", "confirm", "waitedSeconds", 4));
		this.out.delta("正文");

		List<Map<String, Object>> frames = wire.frames();
		assertThat(FrameContract.types(frames)).containsExactly("start", "heartbeat", "heartbeat", "delta");
		// 心跳：独立序号空间 + 不带业务 seq
		assertThat(FrameContract.ofType(frames, "heartbeat")).extracting(frame -> frame.get("heartbeatSeq"))
				.containsExactly(1L, 2L);
		assertThat(FrameContract.ofType(frames, "heartbeat")).allSatisfy(
				frame -> assertThat(frame).doesNotContainKey("seq"));
		// 业务帧：自 1 起连续（心跳不插号）
		List<Map<String, Object>> business = frames.stream()
				.filter(frame -> !FrameContract.HEARTBEAT.equals(FrameContract.type(frame))).toList();
		assertThat(FrameContract.types(business)).containsExactly("start", "delta");
		assertThat(FrameContract.seqContract(business)).as("业务序号自 1 起连续无缺口").isEmpty();
		assertThat(this.store.archivedSeqs(RUN_ID)).as("归档与订阅者视角的 seq 完全一致（心跳不占号）")
				.containsExactly(1L, 2L);
		assertThat(this.store.archivedTypes(RUN_ID)).containsExactly("start", "delta");
		assertThat(this.store.lastEventSeq(RUN_ID)).as("续号锚点 = 已发放过的最大业务号").isEqualTo(2L);
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

	// ── ① 跨进程续号回归（S5c-1）：心跳不再消耗可被"孤儿过滤"吃掉的号 ────────

	/**
	 * <b>【S5c-1 回归】</b>DC-14 §3.6 的孤儿丢帧场景：挂起期心跳吃掉了若干号（不落归档），
	 * 前端把收到的最大号记进 {@code lastSeq}；进程死亡后新实例从"归档末帧"续号 ——
	 * 若心跳占号，新首帧就会重用一个已被前端 {@code lastSeq} 覆盖的号，
	 * 于是它在差量重挂里被判为孤儿而<b>永久丢弃</b>（静默丢帧）。
	 *
	 * <p>
	 * 修复后（心跳走独立序号空间）这个场景不可能成立：
	 * ① 前端的 {@code lastSeq} 只被业务帧抬高 ⇒ 等于归档末帧；
	 * ② 新实例续号 = 归档末帧 + 1 ⇒ 严格大于前端已知号 ⇒ 回放必发、不丢帧。
	 *
	 * <p>
	 * kill：让心跳回到业务序号空间（或把续号锚点从归档末帧改成别的更小值）⇒
	 * 第一条断言（新首帧号 &gt; 前端 lastSeq）变红。
	 */
	@Test
	void heartbeatNeverBurnsABusinessSeqSoTheFirstResumedFrameSurvivesDeltaReplay() {
		// 旧进程：跑完挂起前的状态帧（归档 seq 1..4），随后进入挂起等待并发三帧心跳
		SseChatEmitter old = new SseChatEmitter(RUN_ID, this.store, new ObjectMapper());
		FrameWire oldWire = FrameWire.attachTo(old);
		old.start("s-1");
		old.suspended(List.of(pending("c-1")), this.store.runKey(RUN_ID), this.store.pendingKey(RUN_ID),
				this.store.instanceId());
		old.toolStart(pending("c-1"));
		old.toolResult(pending("c-1"), true, false, "导出作业已启动");
		old.heartbeat(Map.of("phase", "suspend", "waitedSeconds", 2));
		old.heartbeat(Map.of("phase", "suspend", "waitedSeconds", 4));
		old.heartbeat(Map.of("phase", "suspend", "waitedSeconds", 6));

		assertThat(this.store.archivedSeqs(RUN_ID)).containsExactly(1L, 2L, 3L, 4L);
		long clientLastSeq = maxSeq(oldWire.frames());
		assertThat(clientLastSeq).as("客户端已知的最大业务序号（心跳不抬高它）").isEqualTo(4L);
		assertThat(this.store.lastEventSeq(RUN_ID)).as("续号锚点 = 归档末帧 = 已发放的最大业务号").isEqualTo(4L);

		// 进程重启：续跑用的新写出器从归档末帧（4）续号
		SseChatEmitter resumed = new SseChatEmitter(RUN_ID, this.store, new ObjectMapper());
		FrameWire live = FrameWire.attachTo(resumed);
		resumed.start("s-1");

		Map<String, Object> firstResumedFrame = live.frames().get(0);
		assertThat(firstResumedFrame).containsEntry("seq", 5L);
		assertThat(((Number) firstResumedFrame.get("seq")).longValue())
				.as("新帧的号必须严格大于客户端已知号，否则会被当作孤儿丢掉")
				.isGreaterThan(clientLastSeq);

		// 差量重挂：seq 5 必须被补发（DC-14 §3.6 的丢帧场景在此关闭）
		FrameWire reattached = new FrameWire();
		int replayed = resumed.replayTo(reattached.emitter(), false, clientLastSeq);

		assertThat(replayed).as("重启后的真实新帧不得被判为孤儿").isEqualTo(1);
		assertThat(reattached.frames()).singleElement()
				.satisfies(frame -> assertThat(FrameContract.type(frame)).isEqualTo("start"))
				.satisfies(frame -> assertThat(frame).containsEntry("seq", 5L));
	}

	// ── ③ 出帧串行回归（S5c-3）：两线程并发出帧，到达顺序与 seq 顺序一致 ──────

	/**
	 * <b>【S5c-3 回归】</b>两根线程同时出帧（HTTP 线程的确认门结局帧 / 运行线程的挂起心跳），
	 * 且其中一条被"慢订阅者"卡在写出上：后来的那条必须<b>排队等锁</b>，不得带着更大的号超车。
	 *
	 * <p>
	 * 修复前（取号、落归档、写出三段无锁）：先取号的线程可能后写出，订阅者看到的到达顺序
	 * 与 seq 顺序相反（本机实测已复现）。
	 *
	 * <p>
	 * kill：把 {@code SseChatEmitter#emit} 的 {@code synchronized (emitLock)} 去掉 ⇒
	 * 心跳在工具结局帧仍卡在写出时就到达，本用例的 {@code arrival} 断言变红。
	 */
	@Test
	void twoThreadsEmittingConcurrentlyArriveInSeqOrder() throws Exception {
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

		// HTTP 线程先出帧（取号 1）并卡在写出上；运行线程在此刻发心跳 —— 它必须等锁
		Thread httpThread = new Thread(() -> emitter.toolResult(pending("c-1"), true, false, "结果"), "http-confirm");
		Thread runThread = new Thread(
				() -> emitter.heartbeat(Map.of("suspendKind", "confirm", "waitedSeconds", 2)), "run-heartbeat");
		httpThread.start();
		assertThat(firstFrameInSend.await(5, TimeUnit.SECONDS)).as("HTTP 线程已取号并卡在写出").isTrue();
		runThread.start();
		releaseFirstFrame.countDown();
		httpThread.join(5000);
		runThread.join(5000);

		List<Map<String, Object>> frames = wire.frames();
		assertThat(arrival).as("到达顺序 = 取号顺序（心跳不得在工具结局帧之前到达）")
				.containsExactly("tool_result", "heartbeat");
		assertThat(frames.get(0)).containsEntry("seq", 1L);
		assertThat(frames.get(1)).containsEntry("heartbeatSeq", 1L).doesNotContainKey("seq");
		assertThat(FrameContract.seqContract(List.of(frames.get(0)))).as("业务帧序号自 1 起，无缺口").isEmpty();
		assertThat(this.store.archivedSeqs(RUN_ID)).containsExactly(1L);
		assertThat(FrameContract.archiveSeqContract(this.store.events(RUN_ID))).isEmpty();
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	/** 客户端已知的最大业务序号（等于"前端把收到的任何带 seq 的帧记进 lastSeq"）。无业务帧时返回 0。 */
	private static long maxSeq(List<Map<String, Object>> frames) {
		long max = 0L;
		for (Map<String, Object> frame : frames) {
			Object seq = frame.get("seq");
			if (seq instanceof Number number) {
				max = Math.max(max, number.longValue());
			}
		}
		return max;
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
