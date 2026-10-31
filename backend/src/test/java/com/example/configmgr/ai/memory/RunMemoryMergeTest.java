package com.example.configmgr.ai.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话记忆窗口的按轮合并（纯函数）：<b>挂起轮 A 与新轮 B 的整窗覆盖互删</b>。
 *
 * <h2>被钉住的缺陷</h2>
 * 会话只有一份 Redis 窗口，每个 run 持有自己的快照历史并在终局<b>整窗覆盖</b>：
 * <ul>
 * <li>A 挂起（等人确认）→ 用户开了 B → B 成功落窗；之后 A 续跑收敛 ⇒ <b>B 的整轮从 /history 消失</b>；</li>
 * <li>反序（B 先收敛、A 后收敛）同理删掉 A 的回答；取消轮也一样（取消同样是"一轮终局"）。</li>
 * </ul>
 * 规格明确允许"挂起期间开新轮"（不退回跨 park 持锁，也不恢复数据库历史），因此唯一可行的口径是：
 * 窗口按 run 分块、只替换/追加<b>本 run 的块</b>、块间按本轮起点有序，裁剪不拆 tool 配对。
 *
 * <p>本用例只断言<b>最终窗口内容</b>（消息正文、顺序、归属与 metadata），不断言内部调用。
 */
class RunMemoryMergeTest {

	private static final MemoryTurn A = new MemoryTurn("run-a", 1_000L);

	private static final MemoryTurn B = new MemoryTurn("run-b", 2_000L);

	private static final int WINDOW = 40;

	@Test
	void earlierRunConvergingAfterTheLaterOneKeepsBothBlocksInStartOrder() {
		List<Message> window = RunMemoryMerge.merge(List.of(), history("B问", "B答"), B, true, WINDOW);

		window = RunMemoryMerge.merge(window, history("A问", "A答"), A, true, WINDOW);

		assertThat(texts(window)).as("A 后收敛不得删掉 B 的整轮，且按本轮起点排在 B 之前")
			.containsExactly("A问", "A答", "B问", "B答");
	}

	@Test
	void laterRunConvergingLastKeepsTheEarlierRunsAnswer() {
		List<Message> window = RunMemoryMerge.merge(List.of(), history("A问", "A答"), A, true, WINDOW);

		window = RunMemoryMerge.merge(window, history("B问", "B答"), B, true, WINDOW);

		assertThat(texts(window)).as("B 后收敛不得删掉 A 的回答").containsExactly("A问", "A答", "B问", "B答");
	}

	@Test
	void cancelledRunAndSuccessfulRunBothStayInTheWindow() {
		List<Message> cancelled = List.of(user("A问"),
				AssistantMessage.builder().content("A 的半截").properties(Map.of("stopped", true)).build());
		List<Message> success = history("B问", "B答");

		List<Message> window = RunMemoryMerge.merge(
				RunMemoryMerge.merge(List.of(), cancelled, A, true, WINDOW), success, B, true, WINDOW);

		assertThat(texts(window)).containsExactly("A问", "A 的半截", "B问", "B答");
		assertThat(window.get(1).getMetadata()).as("取消轮的 stopped 标记不得被合并吃掉").containsEntry("stopped", true);
		assertThat(RunMemoryMerge.runIdOf(window.get(1))).as("取消轮仍是 A 的块").isEqualTo(A.runId());
		assertThat(RunMemoryMerge.runIdOf(window.get(3))).isEqualTo(B.runId());
	}

	@Test
	void advisorAppendOfOneRunNeverOverwritesAnotherRun() {
		List<Message> window = RunMemoryMerge.merge(List.of(), history("A问", "A答"), A, true, WINDOW);

		window = RunMemoryMerge.merge(window, List.of(user("B问")), B, false, WINDOW);

		assertThat(texts(window)).as("advisor 的增量写只能追加到 B 的块").containsExactly("A问", "A答", "B问");
	}

	@Test
	void replacingARunBlockDoesNotTruncateAnotherRunsNewerMessages() {
		// B 的块在 A 读到它之后又长了一条（续跑继续写）：A 的历史里只有 B 的前两条。
		List<Message> bBlock = new ArrayList<>(history("B问", "B答"));
		bBlock.add(assistant("B续"));
		List<Message> window = RunMemoryMerge.merge(List.of(), bBlock, B, true, WINDOW);
		List<Message> aStaleHistory = new ArrayList<>(List.of(user("A问")));
		aStaleHistory.addAll(stamped(history("B问", "B答"), B));

		window = RunMemoryMerge.merge(window, aStaleHistory, A, true, WINDOW);

		assertThat(texts(window)).as("别的 run 的块以当前窗口为准（A 手里的旧副本不得截断 B）")
			.containsExactly("A问", "B问", "B答", "B续");
	}

	@Test
	void unownedMessagesGetTheWritingRunsOwnershipOnce() {
		List<Message> window = RunMemoryMerge.merge(List.of(), List.of(user("旧的无归属消息")), A, true, WINDOW);

		assertThat(RunMemoryMerge.runIdOf(window.get(0))).isEqualTo(A.runId());
		assertThat(RunMemoryMerge.turnStartedAtOf(window.get(0))).isEqualTo(A.turnStartedAtMs());

		List<Message> again = RunMemoryMerge.merge(window, List.of(window.get(0)), A, true, WINDOW);
		assertThat(RunMemoryMerge.runIdOf(again.get(0))).as("已经带归属的消息不得被重新盖章").isEqualTo(A.runId());
	}

	@Test
	void emptyHistoryRemovesOnlyThisRunsBlock() {
		List<Message> window = RunMemoryMerge.merge(List.of(), history("A问", "A答"), A, true, WINDOW);
		window = RunMemoryMerge.merge(window, history("B问", "B答"), B, true, WINDOW);

		List<Message> afterEmptyConverge = RunMemoryMerge.merge(window, List.of(), A, true, WINDOW);

		assertThat(texts(afterEmptyConverge)).as("空窗收敛只清本 run（B 的成功轮不得被删）")
			.containsExactly("B问", "B答");
	}

	@Test
	void windowTrimNeverStartsWithAnOrphanToolResultAndKeepsThePair() {
		// [u1, a1(tool), t1, a2(tool), t2, a3]，窗口上限 4 ⇒ 丢弃前 2 条会落在 t1（TOOL）上。
		List<Message> history = List.of(user("u1"), toolCall("a1", "c1"), toolResult("c1", "r1"), toolCall("a2", "c2"),
				toolResult("c2", "r2"), assistant("a3"));

		List<Message> window = RunMemoryMerge.merge(List.of(), history, A, true, 4);

		assertThat(window.size()).isLessThanOrEqualTo(4);
		assertThat(window.get(0).getMessageType()).as("窗口首条不得是孤立 TOOL（上游会 400）")
			.isNotEqualTo(MessageType.TOOL);
		assertThat(window.get(0)).isInstanceOf(AssistantMessage.class);
		assertThat(((AssistantMessage) window.get(0)).getToolCalls()).as("裁剪不得拆散 tool 配对").hasSize(1);
		assertThat(window.get(1).getMessageType()).isEqualTo(MessageType.TOOL);
		assertThat(window.get(1)).isInstanceOf(ToolResponseMessage.class);
		assertThat(((ToolResponseMessage) window.get(1)).getResponses())
			.as("tool 结果与它的 tool_calls 一起留在窗口里")
			.singleElement()
			.satisfies(response -> assertThat(response.responseData()).isEqualTo("r2"));
		assertThat(texts(window)).containsExactly("a2", "", "a3");
	}

	@Test
	void unknownTurnStartIsOrderedAsNewRatherThanAsTheOldest() {
		List<Message> window = RunMemoryMerge.merge(List.of(), history("A问", "A答"), A, true, WINDOW);

		window = RunMemoryMerge.merge(window, history("B问", "B答"), new MemoryTurn("run-b", 0L), true, WINDOW);

		assertThat(texts(window)).as("起点未知（旧信封/无归属）不得把新块插到最前面")
			.containsExactly("A问", "A答", "B问", "B答");
	}

	@Test
	void blockOrderIsDeterministicOnEqualTurnStarts() {
		MemoryTurn sameA = new MemoryTurn("run-a", 5_000L);
		MemoryTurn sameB = new MemoryTurn("run-b", 5_000L);

		List<Message> first = RunMemoryMerge.merge(
				RunMemoryMerge.merge(List.of(), history("A问", "A答"), sameA, true, WINDOW), history("B问", "B答"), sameB,
				true, WINDOW);
		List<Message> second = RunMemoryMerge.merge(
				RunMemoryMerge.merge(List.of(), history("B问", "B答"), sameB, true, WINDOW), history("A问", "A答"), sameA,
				true, WINDOW);

		assertThat(texts(first)).as("同一毫秒起步也要有唯一顺序（按 runId 定序）").isEqualTo(texts(second));
	}

	// ───────────────────────── 辅助 ─────────────────────────

	private static List<Message> history(String question, String answer) {
		return List.of(user(question), assistant(answer));
	}

	private static List<Message> stamped(List<Message> messages, MemoryTurn turn) {
		List<Message> out = new ArrayList<>(messages.size());
		for (Message message : messages) {
			out.add(RunMemoryMerge.stamp(message, turn));
		}
		return out;
	}

	private static List<String> texts(List<Message> messages) {
		return messages.stream().map(Message::getText).toList();
	}

	private static Message user(String text) {
		return new UserMessage(text);
	}

	private static Message assistant(String text) {
		return AssistantMessage.builder().content(text).build();
	}

	private static Message toolCall(String text, String callId) {
		return AssistantMessage.builder()
			.content(text)
			.toolCalls(List.of(new AssistantMessage.ToolCall(callId, "function", "start_export", "{}")))
			.build();
	}

	private static Message toolResult(String callId, String text) {
		return ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse(callId, "start_export", text)))
			.build();
	}

}
