package com.example.configmgr.ai.conformance;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.example.configmgr.ai.conformance.FrameContract.fixture;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验器自身的<b>负向 fixture</b>：每一条不变量都必须能被一条构造出来的违规流打红。
 *
 * <p>
 * 依据（ag-ui conformance 方法论，见 {@code spec/1.0/conformance/README.md}）：
 * "每个 fixture 必填 {@code kill} —— 会让它变红的那一行实现改动"，
 * 因为"一个不可能失败的 fixture 比没有 fixture 更糟：它读起来像覆盖率，却什么也没证明"。
 * 本类把这些 kill 反过来用：不改实现，直接<b>把违规帧流喂给校验器</b>，
 * 证明每条规则都有牙齿；正向后，各用例里的绿灯才有意义。
 *
 * <p>
 * 每个用例的 kill 都写在名字里（{@code killsXxx} = 让这条规则失效的那类改动）。
 */
class FrameContractFalsificationTest {

	// ── 正控：合规流必须是安静的 ─────────────────────────────────────────────

	/**
	 * 一条完全合规的一轮（含工具、确认门、正文三段式、终帧）→ 零违规。
	 * kill：让任何规则对合法流量报警（例如把"归档有洞"也当违规），本用例立刻变红 ——
	 * 这是"容忍度回归"的唯一守卫（对应 ag-ui 的 {@code conformant-run-is-quiet}）。
	 */
	@Test
	void acceptsAConformantTurn() {
		List<Map<String, Object>> frames = new ArrayList<>();
		frames.add(fixture("start", 1, "runId", "r-1", "sessionId", "s-1"));
		frames.add(fixture("message_start", 2, "messageId", "assistant-r-1", "kind", "text"));
		frames.add(fixture("delta", 3, "messageId", "assistant-r-1", "text", "你好"));
		frames.add(fixture("suspended", 4, "runId", "r-1"));
		frames.add(fixture("tool_start", 5, "toolCallId", "c-1", "name", "start_export"));
		frames.add(fixture("confirm_request", 6, "toolCallId", "c-1", "name", "start_export"));
		frames.add(fixture("confirm_decision", 7, "toolCallId", "c-1", "decision", "approve"));
		frames.add(fixture("tool_result", 8, "toolCallId", "c-1", "ok", true, "executed", true));
		frames.add(fixture("delta", 9, "messageId", "assistant-r-1", "text", "，已启动"));
		frames.add(fixture("message_end", 10, "messageId", "assistant-r-1", "kind", "text", "chars", 6));
		frames.add(fixture("done", 11, "runId", "r-1", "cancelled", false));

		assertThat(FrameContract.violations(frames)).isEmpty();
	}

	// ── 规则 1：seq ─────────────────────────────────────────────────────────

	/** kill：把 {@code nextSeq()} 改成"每次 +2"（或让某类帧不写 seq）⇒ 出现缺口。 */
	@Test
	void killsSeqGapAndMissingSeq() {
		List<Map<String, Object>> gap = List.of(fixture("start", 1), fixture("delta", 3, "messageId", "m-1", "text", "x"),
				fixture("done", 4, "cancelled", false));
		assertThat(FrameContract.seqContract(gap)).anySatisfy(violation -> assertThat(violation).contains("期望 2"));

		List<Map<String, Object>> withoutSeq = List.of(Map.of("type", "start"));
		assertThat(FrameContract.seqContract(withoutSeq)).anySatisfy(violation -> assertThat(violation).contains("缺 seq"));
	}

	/** kill：让运行线程与 HTTP 线程各自持有一个计数器 ⇒ 序号重复。 */
	@Test
	void killsSeqRegressionAndDuplicate() {
		List<Map<String, Object>> frames = List.of(fixture("start", 1), fixture("delta", 1, "text", "x"));
		assertThat(FrameContract.seqContract(frames)).anySatisfy(violation -> assertThat(violation).contains("期望 2"));

		List<Map<String, Object>> archived = new ArrayList<>();
		archived.add(fixture("start", 5));
		archived.add(fixture("delta", 3));
		archived.add(fixture("delta", 3));
		List<String> violations = FrameContract.archiveSeqContract(archived);
		assertThat(violations).anySatisfy(v -> assertThat(v).contains("非严格递增"));
		assertThat(violations).anySatisfy(v -> assertThat(v).contains("重复"));
	}

	// ── 规则 2：终态 ────────────────────────────────────────────────────────

	/** kill：在 {@code out.done(...)} 之后再补一帧（心跳或状态帧）⇒ 终帧之后还有帧。 */
	@Test
	void killsFramesAfterTheTerminalFrame() {
		List<Map<String, Object>> frames = List.of(fixture("start", 1), fixture("done", 2, "cancelled", false),
				fixture("delta", 3, "messageId", "m-1", "text", "迟到"));
		List<String> violations = FrameContract.terminalContract(frames);
		assertThat(violations).anySatisfy(violation -> assertThat(violation).contains("之后还有 1 帧"));
	}

	/** kill：让 {@code fail()} 与 {@code finish()} 都发终帧 ⇒ 一轮两个终帧。 */
	@Test
	void killsDuplicateTerminalFrames() {
		List<Map<String, Object>> frames = List.of(fixture("start", 1), fixture("done", 2, "cancelled", false),
				fixture("error", 3, "code", "X"));
		assertThat(FrameContract.terminalContract(frames))
				.anySatisfy(violation -> assertThat(violation).contains("终帧出现 2 次"));
	}

	/** kill：去掉首包（不调 {@code out.start(...)}）⇒ 首帧不是 start，前端拿不到 runId。 */
	@Test
	void killsMissingStartFrame() {
		List<Map<String, Object>> frames = List.of(fixture("delta", 1, "messageId", "m-1", "text", "x"),
				fixture("done", 2, "cancelled", false));
		assertThat(FrameContract.terminalContract(frames))
				.anySatisfy(violation -> assertThat(violation).contains("首帧不是 start"));
	}

	// ── 规则 3：工具配对 ────────────────────────────────────────────────────

	/** kill：删掉拒绝/超时路径的 {@code toolResult(...)} ⇒ 有 tool_start 无结局帧。 */
	@Test
	void killsUnpairedToolStart() {
		List<Map<String, Object>> frames = List.of(fixture("start", 1), fixture("suspended", 2),
				fixture("tool_start", 3, "toolCallId", "c-1"),
				fixture("confirm_request", 4, "toolCallId", "c-1"),
				fixture("confirm_decision", 5, "toolCallId", "c-1"), fixture("done", 6, "cancelled", false));
		assertThat(FrameContract.toolPairingContract(frames))
				.anySatisfy(violation -> assertThat(violation).contains("没有配对结局帧"));
	}

	/** kill：让前端工具挂起后仍走后端结局帧（发两次结果）⇒ 一个 start 两个结局。 */
	@Test
	void killsTwoOutcomeFramesForOneToolStart() {
		List<Map<String, Object>> frames = List.of(fixture("start", 1), fixture("suspended", 2),
				fixture("tool_start", 3, "toolCallId", "c-1"),
				fixture("frontend_tool_request", 4, "toolCallId", "c-1"),
				fixture("frontend_tool_result", 5, "toolCallId", "c-1"),
				fixture("tool_result", 6, "toolCallId", "c-1"), fixture("done", 7, "cancelled", false));
		assertThat(FrameContract.toolPairingContract(frames))
				.anySatisfy(violation -> assertThat(violation).contains("出现两个结局帧"));
	}

	// ── 规则 4：正文三段式与拼接 ────────────────────────────────────────────

	/** kill：砍掉 {@code finish()} 里的 {@code out.textEnd(...)} ⇒ 有 delta 但无 message_end。 */
	@Test
	void killsMissingMessageEnd() {
		List<Map<String, Object>> frames = List.of(fixture("start", 1),
				fixture("message_start", 2, "messageId", "m-1", "kind", "text"),
				fixture("delta", 3, "messageId", "m-1", "text", "你好"), fixture("done", 4, "cancelled", false));
		assertThat(FrameContract.textSegmentContract(frames))
				.anySatisfy(violation -> assertThat(violation).contains("没有 message_end"));
	}

	/** kill：把 chars 换成"上游分片总长"（含未发 delta 的空白分片）⇒ 见 S5c-4 的真实差值。 */
	@Test
	void killsCharsMismatchAgainstDeltaSum() {
		List<Map<String, Object>> frames = List.of(fixture("start", 1),
				fixture("message_start", 2, "messageId", "m-1", "kind", "text"),
				fixture("delta", 3, "messageId", "m-1", "text", "你好"),
				fixture("message_end", 4, "messageId", "m-1", "kind", "text", "chars", 5),
				fixture("done", 5, "cancelled", false));
		assertThat(FrameContract.textSegmentContract(frames)).anySatisfy(
				violation -> assertThat(violation).contains("delta 文本长度之和=2"));
	}

	/** kill：中途重开一次正文段（第二次 {@code textStart()} 未被幂等挡住）⇒ message_start 出现两次。 */
	@Test
	void killsReopenedTextSegment() {
		List<Map<String, Object>> frames = List.of(fixture("start", 1),
				fixture("message_start", 2, "messageId", "m-1", "kind", "text"),
				fixture("delta", 3, "messageId", "m-1", "text", "a"),
				fixture("message_end", 4, "messageId", "m-1", "kind", "text", "chars", 1),
				fixture("message_start", 5, "messageId", "m-1", "kind", "text"),
				fixture("delta", 6, "messageId", "m-1", "text", "b"),
				fixture("message_end", 7, "messageId", "m-1", "kind", "text", "chars", 1),
				fixture("done", 8, "cancelled", false));
		assertThat(FrameContract.textSegmentContract(frames))
				.anySatisfy(violation -> assertThat(violation).contains("message_start 出现 2 次"));
	}

	// ── 规则 5：因果顺序 ────────────────────────────────────────────────────

	/** kill：让决策回执先于请求发出（或干脆不回执）⇒ 确认门顺序倒置 / 缺回执。 */
	@Test
	void killsConfirmOrderingInversion() {
		List<Map<String, Object>> inverted = List.of(fixture("start", 1), fixture("suspended", 2),
				fixture("tool_start", 3, "toolCallId", "c-1"),
				fixture("confirm_decision", 4, "toolCallId", "c-1"),
				fixture("confirm_request", 5, "toolCallId", "c-1"),
				fixture("tool_result", 6, "toolCallId", "c-1"), fixture("done", 7, "cancelled", false));
		assertThat(FrameContract.orderingContract(inverted))
				.anySatisfy(violation -> assertThat(violation).contains("confirm_decision 早于 confirm_request"));

		List<Map<String, Object>> noDecision = List.of(fixture("start", 1), fixture("suspended", 2),
				fixture("tool_start", 3, "toolCallId", "c-1"),
				fixture("confirm_request", 4, "toolCallId", "c-1"),
				fixture("tool_result", 5, "toolCallId", "c-1"), fixture("done", 6, "cancelled", false));
		assertThat(FrameContract.orderingContract(noDecision))
				.anySatisfy(violation -> assertThat(violation).contains("缺 confirm_decision"));
	}

	/** kill：去掉 {@code SpToolCallingManager} 里挂起公告的先后（先 tool_start 再 suspended）⇒ 违反硬规范①投影。 */
	@Test
	void killsSuspendedAnnouncedAfterTheToolStarts() {
		List<Map<String, Object>> frames = List.of(fixture("start", 1),
				fixture("tool_start", 2, "toolCallId", "c-1"), fixture("suspended", 3),
				fixture("tool_result", 4, "toolCallId", "c-1"), fixture("done", 5, "cancelled", false));
		assertThat(FrameContract.orderingContract(frames))
				.anySatisfy(violation -> assertThat(violation).contains("挂起态外置公告必须先于工具出场"));
	}

	/** kill：去掉重试三条件里的"未产出内容"（已产出也重试）⇒ retry 出现在 delta 之后。 */
	@Test
	void killsRetryAfterContentWasEmitted() {
		List<Map<String, Object>> frames = List.of(fixture("start", 1),
				fixture("message_start", 2, "messageId", "m-1", "kind", "text"),
				fixture("delta", 3, "messageId", "m-1", "text", "半截"),
				fixture("retry", 4, "nextAttempt", 2), fixture("message_end", 5, "messageId", "m-1", "kind", "text",
						"chars", 2),
				fixture("done", 6, "cancelled", false));
		assertThat(FrameContract.orderingContract(frames)).anySatisfy(
				violation -> assertThat(violation).contains("已产出内容不得重试"));
	}

	// ── 规则 6：终帧与终态同向 ──────────────────────────────────────────────

	/** kill：把 {@code cancelled} 写成字符串（"true"）⇒ 前端 {@code === true} 判定失效。 */
	@Test
	void killsNonBooleanCancelledFlag() {
		List<Map<String, Object>> frames = List.of(fixture("start", 1), fixture("done", 2, "cancelled", "true"));
		assertThat(FrameContract.terminalConsistencyContract(frames))
				.anySatisfy(violation -> assertThat(violation).contains("cancelled 不是布尔"));
	}

}
