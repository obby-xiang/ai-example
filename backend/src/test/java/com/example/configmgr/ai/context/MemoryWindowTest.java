package com.example.configmgr.ai.context;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 记忆窗口（token 预算 + 条数兜底）的纯函数用例。
 *
 * <h2>红线（每条一个用例，改坏即红）</h2>
 * <ol>
 * <li><b>预算内一条不动</b>：同引用返回 —— "没发生"不能被实现成"发生但值相等"；</li>
 * <li><b>token 预算为主</b>：越预算时淘汰最旧（不是砍最新，也不是砍中间）；</li>
 * <li><b>不改写消息内容</b>：B1 起工具结果清除的唯一 mutator 是 {@code ContextCompactor}，
 * 窗口只做整轮淘汰；越预算到下限仍不够时只上报 {@code stillOverBudget}；</li>
 * <li><b>条数下限不击穿</b>：预算再紧，淘汰后也不少于 {@code min-messages} 条；</li>
 * <li><b>turn 边界整轮淘汰</b>：切口永远落在 {@code UserMessage} 上，
 * {@code tool_call} ↔ {@code tool_result} 配对不得被拆散（上游 400 的经典成因）；</li>
 * <li><b>预算口径 = 历史口径</b>：{@code SYSTEM} 不计入 token 预算（装配点单列）。</li>
 * </ol>
 */
class MemoryWindowTest {

	/** 默认预算（与 {@code app.ai.memory.*} 的缺省一致；单例改数值时只动这一处）。 */
	private static final MemoryWindow.Budget DEFAULTS = new MemoryWindow.Budget(48_000, 40, 40, 5);

	private static MemoryWindow.Budget budget(int tokenBudget, int minMessages, int maxMessages, int keepToolResults) {
		return new MemoryWindow.Budget(tokenBudget, minMessages, maxMessages, keepToolResults);
	}

	/**
	 * 造 n 轮"用户提问 → 助手回答"，每段正文恰好 {@code cjkChars} 个 CJK 字符。
	 *
	 * <p>取整数值便于断言精确 token：1700 个 CJK 字符 = 1000 token（{@code ceil(1700/1.7)}），
	 * 于是"每轮 2000 token"是可手算的既定事实，用例不必靠区间猜。
	 */
	private static List<Message> turns(int count, int cjkChars) {
		String text = "问" + "长".repeat(cjkChars - 1);
		List<Message> messages = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			messages.add(new UserMessage(text));
			messages.add(new AssistantMessage(text));
		}
		return messages;
	}

	/** 造 n 轮"用户提问 → 工具调用 → 工具结果"，结果正文 {@code resultChars} 个 CJK 字符。 */
	private static List<Message> toolTurns(int pairs, int resultChars) {
		List<Message> messages = new ArrayList<>();
		for (int i = 1; i <= pairs; i++) {
			messages.add(new UserMessage("第 " + i + " 个问题"));
			messages.add(AssistantMessage.builder()
				.content("")
				.toolCalls(List.of(new AssistantMessage.ToolCall("c-" + i, "function", "list_tasks", "{}")))
				.build());
			messages.add(ToolResponseMessage.builder()
				.responses(List.of(new ToolResponseMessage.ToolResponse("c-" + i, "list_tasks",
						"任务列表" + "长".repeat(resultChars))))
				.build());
		}
		return messages;
	}

	private static List<String> toolData(List<Message> messages) {
		List<String> out = new ArrayList<>();
		for (Message message : messages) {
			if (message instanceof ToolResponseMessage tool) {
				tool.getResponses().forEach(response -> out.add(response.responseData()));
			}
		}
		return out;
	}

	/**
	 * 配对完整性：每个 {@code tool} 响应都能在同一份列表里找到它对应的 {@code assistant(tool_calls)}，
	 * 反过来每个 {@code tool_call} 也都有响应（两个方向都断言 —— 只查一个方向会漏掉"响应被留下、
	 * 调用被剪掉"这一半）。
	 */
	private static void assertPairing(List<Message> messages) {
		Set<String> calls = new HashSet<>();
		for (Message message : messages) {
			if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
				assistant.getToolCalls().forEach(call -> calls.add(call.id()));
			}
			if (message instanceof ToolResponseMessage tool) {
				for (ToolResponseMessage.ToolResponse response : tool.getResponses()) {
					assertThat(calls).as("孤儿工具结果 " + response.id() + "（没有对应的 tool_call）").contains(response.id());
				}
			}
		}
		Set<String> responses = new HashSet<>();
		for (Message message : messages) {
			if (message instanceof ToolResponseMessage tool) {
				tool.getResponses().forEach(response -> responses.add(response.id()));
			}
		}
		for (Message message : messages) {
			if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
				for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
					assertThat(responses).as("孤儿 tool_call " + call.id() + "（没有对应的工具结果）").contains(call.id());
				}
			}
		}
	}

	// ── ① 预算内一条不动 ──────────────────────────────────────────────────

	@Test
	void underBudgetReturnsTheSameListUntouched() {
		List<Message> messages = turns(2, 50);

		MemoryWindow.Plan plan = MemoryWindow.plan(messages, DEFAULTS, Set.of());

		assertThat(plan.changed()).isFalse();
		assertThat(plan.messages()).as("未越预算 ⇒ 必须同引用（不能实现成「复制一份但相等」）")
			.isSameAs(messages);
		assertThat(plan.reason()).isEqualTo(MemoryWindow.REASON_UNDER_BUDGET);
		assertThat(plan.droppedTurns()).isZero();
		assertThat(plan.stillOverBudget()).isFalse();
	}

	// ── ② token 预算为主：淘汰最旧 ────────────────────────────────────────

	@Test
	void tokenBudgetDropsTheOldestTurnsUntilUnderBudget() {
		// 每段 1700 字 ≈ 1000 token，每轮 2000 token，共 20000
		List<Message> messages = turns(10, 1700);
		assertThat(ContextEstimator.estimateMessages(messages)).isEqualTo(20_000);

		MemoryWindow.Plan plan = MemoryWindow.plan(messages, budget(6_000, 2, 40, 5), Set.of());

		assertThat(plan.changed()).isTrue();
		assertThat(plan.droppedTurns()).as("20000 → 6000 恰好淘汰 7 轮").isEqualTo(7);
		assertThat(plan.afterTokens()).isEqualTo(6_000);
		assertThat(plan.afterCount()).isEqualTo(6);
		assertThat(plan.stillOverBudget()).isFalse();
		assertThat(plan.reason()).isEqualTo(MemoryWindow.REASON_TURN_TRIM);
		assertThat(plan.messages().get(0).getMessageType()).as("切口必须落在 UserMessage 上")
			.isEqualTo(MessageType.USER);
		// 淘汰的是最旧的 7 轮（14 条）：剩下的第一条就是原列表第 15 条
		assertThat(plan.messages().get(0)).as("留下的必须是被淘汰前缀之后的那一条（整轮边界）")
			.isSameAs(messages.get(14));
	}

	// ── ③ 窗口不改写消息内容（工具结果清理的唯一 mutator 是 ContextCompactor） ──

	@Test
	void tokenBudgetNeverTouchesToolResults() {
		List<Message> messages = toolTurns(6, 1700);
		List<String> original = toolData(messages);
		assertThat(ContextEstimator.estimateMessages(messages)).isGreaterThan(6_000);

		// 预算 3000、下限 8 条：只能整轮淘汰；到下限仍越线 ⇒ 如实上报给压缩器做 L1
		MemoryWindow.Plan plan = MemoryWindow.plan(messages, budget(3_000, 8, 40, 1), Set.of());

		assertThat(plan.droppedTurns()).isPositive();
		assertThat(plan.stillOverBudget()).as("淘汰到下限仍越预算 ⇒ 交压缩谱系，不静默继续砍").isTrue();
		assertThat(toolData(plan.messages())).as("窗口不得替换任何工具结果（否则与 L1 重复清/重复记账）")
			.containsExactlyElementsOf(original.subList(plan.droppedTurns(), original.size()));
		assertPairing(plan.messages());
	}

	@Test
	void windowDropsWholeTurnsWithoutRewritingKeptContent() {
		List<Message> messages = toolTurns(4, 1700);

		MemoryWindow.Plan plan = MemoryWindow.plan(messages, budget(3_000, 8, 40, 1), Set.of("c-1"));

		// 窗口只做整轮淘汰：留下的结果必须逐字原文（HITL 保护不在本类，真覆盖见
		// SpToolCallingManagerTurnBudgetTest#pendingHitlToolResultIsNeverCleared）
		assertThat(toolData(plan.messages()))
			.containsExactlyElementsOf(toolData(messages).subList(plan.droppedTurns(), messages.size() / 3));
	}

	// ── ④ 预算口径 = 历史口径（SYSTEM 单列） ─────────────────────────────

	@Test
	void systemTokensNotCountedInBudget() {
		String body = "问" + "长".repeat(1699);
		List<Message> messages = List.of(
				new org.springframework.ai.chat.messages.SystemMessage("人设".repeat(2_000)),
				new UserMessage(body),
				new AssistantMessage(body));

		MemoryWindow.Plan plan = MemoryWindow.plan(messages, budget(2_500, 1, 40, 5), Set.of());

		assertThat(plan.reason()).as("SYSTEM 不可裁剪，不得计入窗口预算（否则永久 over-budget）")
			.isEqualTo(MemoryWindow.REASON_UNDER_BUDGET);
		assertThat(plan.changed()).isFalse();
		assertThat(plan.messages()).isSameAs(messages);
	}

	// ── ⑤ 条数下限不击穿 ─────────────────────────────────────────────────

	@Test
	void minMessagesFloorStopsBudgetTrimming() {
		List<Message> messages = turns(10, 1700);

		// 预算 1000（几乎必然越线），下限 8 条 ⇒ 最多淘汰 6 轮，剩 4 轮 = 8 条
		MemoryWindow.Plan plan = MemoryWindow.plan(messages, budget(1_000, 8, 40, 5), Set.of());

		assertThat(plan.afterCount()).as("下限 8 条不得被预算击穿").isEqualTo(8);
		assertThat(plan.droppedTurns()).isEqualTo(6);
		assertThat(plan.stillOverBudget()).as("淘汰到下限仍越线 ⇒ 如实上报（让压缩谱系接手，不静默砍）").isTrue();
		assertThat(plan.reason()).isEqualTo(MemoryWindow.REASON_TURN_TRIM);
	}

	// ── ⑥ turn 边界：配对不拆 ────────────────────────────────────────────

	@Test
	void aggressiveBudgetKeepsToolCallPairsIntact() {
		List<Message> messages = toolTurns(6, 1700);
		assertThat(ContextEstimator.estimateMessages(messages)).isGreaterThan(6_000);

		MemoryWindow.Plan plan = MemoryWindow.plan(messages, budget(2_500, 2, 40, 5), Set.of());

		assertThat(plan.droppedTurns()).isPositive();
		assertPairing(plan.messages());
		assertThat(plan.messages().get(0).getMessageType()).isEqualTo(MessageType.USER);
	}

	@Test
	void theLastTurnIsNeverDroppedEvenWhenItAloneExceedsTheBudget() {
		List<Message> messages = turns(1, 5000);
		int before = ContextEstimator.estimateMessages(messages);

		MemoryWindow.Plan plan = MemoryWindow.plan(messages, budget(100, 2, 40, 5), Set.of());

		assertThat(plan.messages()).hasSameSizeAs(messages);
		assertThat(plan.droppedTurns()).isZero();
		assertThat(plan.afterTokens()).isEqualTo(before);
		assertThat(plan.stillOverBudget()).isTrue();
		assertThat(plan.reason()).as("没有可淘汰的轮次 ⇒ 理由不得谎报成「已淘汰」").isEqualTo(MemoryWindow.REASON_NOTHING_TO_TRIM);
	}

	// ── ⑦ 条数上限（max-messages 兜底仍在） ──────────────────────────────

	@Test
	void countCapStillAppliesWhenTokenBudgetIsDisabled() {
		List<Message> messages = turns(5, 100);

		MemoryWindow.Plan plan = MemoryWindow.plan(messages, budget(0, 2, 4, 5), Set.of());

		assertThat(plan.afterCount()).isEqualTo(4);
		assertThat(plan.droppedTurns()).isEqualTo(3);
		assertThat(plan.reason()).isEqualTo(MemoryWindow.REASON_TURN_TRIM);
		assertThat(plan.messages().get(0).getMessageType()).isEqualTo(MessageType.USER);
	}

	// ── ⑧ 默认口径 min = max = 40（2026-10-22 用户裁「业界主流的来吧，40」）────

	/**
	 * 默认配置下条数是<b>硬地板也是硬天花板</b>：窗口恰好 40 条时，超预算也一条消息都不许删
	 * （再删就击穿下限）；只有条数 <b>&gt; 40</b> 时才按 turn 淘汰到 40。
	 *
	 * <p>这条锁的是 min=max=40 的实际语义 —— 没有它，"下限 40" 与 "上限 40" 各自为政时多删一轮
	 * 也不会被任何断言发现。B1 起"清工具结果"不再由窗口承担，故 40 条时只如实上报
	 * {@code stillOverBudget}（由调用方传给压缩器做 L1）。
	 */
	@Test
	void defaultFortyNeverTrimsBelowFortyAndTrimsTurnsOnlyWhenOverForty() {
		// 每段 2400 CJK ≈ 1412 token ⇒ 40 条 = 56480 token > 48k 预算
		List<Message> messages = turns(20, 2400);
		assertThat(messages).hasSize(40);
		assertThat(ContextEstimator.estimateMessages(messages)).isGreaterThan(48_000);

		MemoryWindow.Plan atFloor = MemoryWindow.plan(messages, DEFAULTS, Set.of());

		assertThat(atFloor.droppedTurns()).as("40 条 = 下限：预算不得把它淘汰到 39 条").isZero();
		assertThat(atFloor.afterCount()).isEqualTo(40);
		assertThat(atFloor.stillOverBudget()).as("清无可清仍越线 ⇒ 如实上报（交压缩谱系，不静默砍）").isTrue();

		// 42 条（> 40）⇒ 才允许按 turn 淘汰到 40
		MemoryWindow.Plan overCount = MemoryWindow.plan(turns(21, 2400), DEFAULTS, Set.of());

		assertThat(overCount.afterCount()).isEqualTo(40);
		assertThat(overCount.droppedTurns()).isEqualTo(1);
	}
}
