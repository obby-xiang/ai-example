package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.context.ContextCompactor;
import com.example.configmgr.ai.context.ContextEstimator;
import com.example.configmgr.ai.context.ContextLimitResolver;
import com.example.configmgr.ai.context.ContextState;
import com.example.configmgr.ai.context.ContextTrigger;
import com.example.configmgr.ai.context.InMemoryContextStateStore;
import com.example.configmgr.ai.context.ToolResultClearing;
import com.example.configmgr.ai.context.TurnBudgetPlan;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.StreamViolationException;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolResultEnvelope;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolExecutionResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 轮内上下文预算的挂点行为（工具轮内上下文预算批）。
 *
 * <h2>固定的三档语料</h2>
 * {@code limit=10000 / reserve=0 / l1=70 / l3=95}，用<b>上游真值 usage</b>把下一发请求的估算
 * 精确放到三档里（{@code nextEst = promptTokens + completionTokens + 本批新增}）：
 * <ul>
 * <li>7200 档 ⇒ L1：只清旧工具结果；</li>
 * <li>9600 档 ⇒ 收尾：追加指令 + armed；</li>
 * <li>10000 档 ⇒ 越窗：先 settle 已执行记录，再 {@code CONTEXT_OVERFLOW}。</li>
 * </ul>
 *
 * <h2>为什么用真值而不是塞长文本</h2>
 * 真值路径的 {@code nextEst} 是<b>可算的</b>：{@code prompt + completion + 本批新增}。
 * 塞长文本只能逼近阈值（工具信封的字数会随实现漂移），断言会变成"大约"。
 * 另有一组用例专测"无 usage 时按估算"与"真值优先于估算"。
 */
class SpToolCallingManagerTurnBudgetTest {

	private static final String RUN_ID = "run-turn-budget";

	private static final String SESSION_ID = "s-turn-budget";

	private static final String TOOL = "list_tasks";

	private static final int LIMIT = 10000;

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final InMemoryContextStateStore contextStore = new InMemoryContextStateStore();

	private final RunRegistry registry = mock(RunRegistry.class);

	private final ConfirmGate gate = mock(ConfirmGate.class);

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final MessageJsonCodec codec = new MessageJsonCodec(new ObjectMapper());

	private final ToolActivityBeacon beacon = new ToolActivityBeacon();

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final SseChatEmitter emitter = mock(SseChatEmitter.class);

	private final AiProperties properties = new AiProperties();

	private final ToolResultLimiter limiter = new ToolResultLimiter(this.properties);

	/** 执行替身调用次数（断言「armed 之后真的没执行」）。 */
	private final List<String> executed = new ArrayList<>();

	private final SpToolCallingManager manager = new SpToolCallingManager(this.store, this.registry, this.gate,
			this.toolRegistry, this.codec, this.beacon, this.cancellations, this.limiter, List.of(), null, null, null,
			null, this.properties) {

		@Override
		String delegateSingle(Prompt prompt, PendingToolCall pending) {
			SpToolCallingManagerTurnBudgetTest.this.executed.add(pending.getToolCallId());
			return "执行成功：" + pending.getToolCallId();
		}
	};

	@BeforeEach
	void setUp() {
		this.properties.getContext().setLimitTokens(LIMIT);
		this.properties.getContext().setReserveTokens(0);
		this.properties.getContext().setL1Percent(70);
		this.properties.getContext().setL3Percent(95);
		// pin 最近 1 对：旧结果可清、本批结果留存（与生产默认 keep=5 同语义，只是窗口更小）
		this.properties.getContext().setKeepToolPairs(1);
		this.manager.setContextLimitResolver(new ContextLimitResolver(this.properties));
		this.manager.setContextStateStore(this.contextStore);

		this.store.seedRun(RUN_ID, SESSION_ID);
		when(this.cancellations.isCancelled(RUN_ID)).thenReturn(false);
		when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
		when(this.toolRegistry.matchesContext(anyString(), any(AiContext.class))).thenReturn(true);
		when(this.toolRegistry.channelOf(TOOL)).thenReturn(ToolMeta.Channel.BACKEND);
		when(this.toolRegistry.riskOf(TOOL)).thenReturn(ToolMeta.RiskLevel.READ);
		when(this.toolRegistry.categoryOf(TOOL)).thenReturn(ToolMeta.Category.R);
		when(this.gate.awaitDecision(eq(RUN_ID), any(PendingToolCall.class))).thenAnswer(invocation -> {
			PendingToolCall pending = invocation.getArgument(1);
			pending.setStatus(PendingToolCall.APPROVED);
			return pending;
		});
	}

	// ── 70 档：只清旧工具结果 ────────────────────────────────────────────────

	@Test
	void l1TierClearsOnlyOldToolResultsAndKeepsPairing() {
		ToolExecutionResult result = this.manager.executeToolCalls(prompt(historyWithOldResults()), response(7200, 0, "c-1"));

		List<Message> history = result.conversationHistory();
		assertThat(history).as("清结果不删消息").hasSize(historyWithOldResults().size() + 2);
		assertThat(oldResultText(history, "old-1")).startsWith(ToolResultClearing.PLACEHOLDER_PREFIX);
		assertThat(oldResultText(history, "old-2")).startsWith(ToolResultClearing.PLACEHOLDER_PREFIX);
		assertThat(newResultText(history)).as("本批结果在 pin 窗口内，不被清除").contains("执行成功：c-1");
		assertPairing(history);
	}

	@Test
	void pendingHitlToolResultIsNeverCleared() {
		PendingToolCall unresolved = new PendingToolCall();
		unresolved.setToolCallId("old-1");
		unresolved.setName(TOOL);
		unresolved.setKind(PendingToolCall.KIND_BACKEND);
		unresolved.setStatus(PendingToolCall.PENDING);
		this.store.putPending(RUN_ID, unresolved);

		ToolExecutionResult result = this.manager.executeToolCalls(prompt(historyWithOldResults()), response(7200, 0, "c-1"));

		List<Message> history = result.conversationHistory();
		assertThat(oldResultText(history, "old-1")).as("未决 HITL 条目的结果不清除")
			.isEqualTo("旧结果一：任务列表 A");
		assertThat(oldResultText(history, "old-2")).as("其余旧结果照常清除")
			.startsWith(ToolResultClearing.PLACEHOLDER_PREFIX);
		assertPairing(history);
	}

	@Test
	void l1ClearingIsIdempotentAcrossBatches() {
		List<Message> firstHistory = this.manager
			.executeToolCalls(prompt(historyWithOldResults()), response(7200, 0, "c-1"))
			.conversationHistory();

		ToolExecutionResult second = this.manager.executeToolCalls(prompt(firstHistory), response(7200, 0, "c-2"));

		List<Message> history = second.conversationHistory();
		// 第二跳的 pin 窗口前移到 c-2：c-1 离开 pin、被清一次；old-1/old-2 已是占位符，不再重清
		assertThat(countPlaceholders(history)).isEqualTo(3);
		assertThat(oldResultText(history, "old-1")).as("已清除的占位符原样保留（不重复清除/改写）")
			.isEqualTo(oldResultText(firstHistory, "old-1"));
		assertThat(history).hasSize(firstHistory.size() + 2);
		assertPairing(history);
	}

	@Test
	void belowL1NothingChanges() {
		ToolExecutionResult result = this.manager.executeToolCalls(prompt(historyWithOldResults()), response(1000, 0, "c-1"));

		List<Message> history = result.conversationHistory();
		assertThat(oldResultText(history, "old-1")).isEqualTo("旧结果一：任务列表 A");
		assertThat(history).noneMatch(message -> message.getText() != null
				&& message.getText().contains("上下文已接近模型窗口上限"));
	}

	// ── 95 档：收尾 + armed ──────────────────────────────────────────────────

	@Test
	void wrapUpTierAppendsInstructionAndArmsTheTurn() {
		ToolExecutionResult result = this.manager.executeToolCalls(prompt(historyWithOldResults()), response(9600, 0, "c-1"));

		List<Message> history = result.conversationHistory();
		assertThat(history.get(history.size() - 1).getText()).contains("上下文已接近模型窗口上限")
			.contains("不要再次调用工具");
		assertThat(this.beacon.budgetArmed(RUN_ID)).isTrue();
		assertThat(this.executed).containsExactly("c-1");
	}

	@Test
	void armedBatchDoesNotExecuteNewTools() {
		List<Message> armedHistory = this.manager
			.executeToolCalls(prompt(historyWithOldResults()), response(9600, 0, "c-1"))
			.conversationHistory();

		ToolExecutionResult second = this.manager.executeToolCalls(prompt(armedHistory), response(9600, 0, "c-2"));

		assertThat(this.executed).as("armed 之后新工具不执行").containsExactly("c-1");
		ToolResponseMessage.ToolResponse response = responseById(second.conversationHistory(), "c-2");
		ToolResultEnvelope envelope = ToolResultEnvelope.fromJson(response.responseData());
		assertThat(envelope.outcome()).isEqualTo(ToolOutcome.SKIPPED.wire());
		assertThat(envelope.code()).isEqualTo(ToolOutcome.CODE_TOOL_BUDGET_EXHAUSTED);
		PendingToolCall entry = this.store.pending(RUN_ID, "c-2");
		assertThat(entry.getStatus()).isEqualTo(PendingToolCall.SKIPPED);
		assertThat(entry.isExecuted()).isFalse();
		assertThat(second.conversationHistory().stream()
			.filter(message -> message.getText() != null && message.getText().contains("不要再次调用工具")))
			.as("收尾指令不重复追加")
			.hasSize(1);
		assertPairing(second.conversationHistory());
	}

	/**
	 * C13 第 5 项：{@code WRAP_UP_IGNORED}（收尾指令被连续无视）必须用<b>自己的</b>错误码收尾。
	 *
	 * <p>旧实现与本条共用 {@code CONTEXT_OVERFLOW}：用户看到"对话内容过长，请开启新会话"，
	 * 运维看 {@code CONTEXT_OVERFLOW_AFTER_TOOL_SIDE_EFFECT} 去查窗口 —— 而真实原因是"模型在被要求收尾后
	 * 仍在要工具"。同一个码说两件事，排障就被带偏。本用例锁死：码 = {@code TOOL_BUDGET_EXHAUSTED}，
	 * 原因 = {@code turn_wrap_up_ignored}，且真越窗仍报 {@code CONTEXT_OVERFLOW}（见下面那条用例）。
	 */
	@Test
	void ignoredWrapUpReportsItsOwnBudgetCodeAndReason() {
		List<Message> armedHistory = this.manager
			.executeToolCalls(prompt(historyWithOldResults()), response(9600, 0, "c-1"))
			.conversationHistory();
		List<Message> skippedHistory = this.manager.executeToolCalls(prompt(armedHistory), response(9600, 0, "c-2"))
			.conversationHistory();

		assertThatThrownBy(() -> this.manager.executeToolCalls(prompt(skippedHistory), response(9600, 0, "c-3")))
			.isInstanceOfSatisfying(StreamViolationException.class, ex -> {
				assertThat(ex.getCode()).isEqualTo(com.example.configmgr.common.ErrorCodes.TOOL_BUDGET_EXHAUSTED);
				assertThat(ex.getCode()).isNotEqualTo(StreamViolationException.CONTEXT_OVERFLOW);
				assertThat(ex.getMessage()).contains(TurnBudgetPlan.REASON_WRAP_UP_IGNORED);
			});

		assertThat(this.executed).as("硬停之前一个工具都没有再执行").containsExactly("c-1");
	}

	@Test
	void repeatedArmedBatchesStopWithOverflowAfterSettle() {
		List<Message> armedHistory = this.manager
			.executeToolCalls(prompt(historyWithOldResults()), response(9600, 0, "c-1"))
			.conversationHistory();
		List<Message> skippedHistory = this.manager.executeToolCalls(prompt(armedHistory), response(9600, 0, "c-2"))
			.conversationHistory();

		// C13 第 5 项：收尾被无视有自己的码（不再是 CONTEXT_OVERFLOW）——"先落定再失败"的语义不变
		assertThatThrownBy(() -> this.manager.executeToolCalls(prompt(skippedHistory), response(9600, 0, "c-3")))
			.isInstanceOfSatisfying(StreamViolationException.class,
					ex -> assertThat(ex.getCode()).isEqualTo(com.example.configmgr.common.ErrorCodes.TOOL_BUDGET_EXHAUSTED));

		assertThat(this.executed).as("硬停之前一个工具都没有再执行").containsExactly("c-1");
		RunSnapshot snapshot = this.store.get(RUN_ID);
		assertThat(String.join("\n", snapshot.getMessageJson())).as("先 settle：被拦下的调用也写进了快照").contains("c-3");
		assertThat(snapshot.getInFlightToolCalls()).isEmpty();
	}

	// ── C13：模型侧按剩余窗口收缩（小窗口 + 多份大结果）─────────────────────────

	/**
	 * C13：15000 窗口下，一批四份各 20000 字符的工具结果必须按<b>剩余窗口</b>收缩。
	 *
	 * <p>缺陷现场：模型侧只按配置档（20000 字符/份）裁 —— 四份就是 8 万字符，而 {@code keep-tool-pairs}
	 * 把最近 5 对钉住，轮内 L1 清不掉，下一跳必然越窗（真机表现为
	 * {@code CONTEXT_OVERFLOW_AFTER_TOOL_SIDE_EFFECT}，而模型连"结果被截过"都不知道）。
	 *
	 * <p>本用例同时锁死三条契约：① 每份按 {@code 剩余窗口 / 本批调用数} 收缩；
	 * ② 截断元数据（{@code truncated/originalChars/returnedChars/notice}）如实写进信封；
	 * ③ 帧/台账仍是<b>全文</b>（双通道对齐不变，模型侧收缩不改变"前端看全文"）。
	 */
	@Test
	void smallWindowShrinksEachToolResultToTheRemainingWindow() {
		this.properties.getContext().setLimitTokens(15000);
		this.properties.getContext().setReserveTokens(3000);
		this.manager.setContextLimitResolver(new ContextLimitResolver(this.properties));
		SpToolCallingManager big = new SpToolCallingManager(this.store, this.registry, this.gate, this.toolRegistry,
				this.codec, this.beacon, this.cancellations, this.limiter, List.of(), null, null, null, null,
				this.properties) {

			@Override
			String delegateSingle(Prompt prompt, PendingToolCall pending) {
				SpToolCallingManagerTurnBudgetTest.this.executed.add(pending.getToolCallId());
				return "大结果 " + pending.getToolCallId() + "：" + "大".repeat(19_990);
			}
		};
		big.setContextLimitResolver(new ContextLimitResolver(this.properties));
		big.setContextStateStore(this.contextStore);

		ToolExecutionResult result = big.executeToolCalls(prompt(historyWithOldResults()),
				response(1000, 0, "big-1", "big-2", "big-3", "big-4"));

		int perResultChars = ToolResultLimiter.charsForRemainingTokens((15_000 - 3_000) / 4);
		for (String id : List.of("big-1", "big-2", "big-3", "big-4")) {
			ToolResultEnvelope envelope = ToolResultEnvelope.fromJson(responseById(result.conversationHistory(), id).responseData());
			assertThat(envelope.truncated()).as("%s 必须被窗口收缩", id).isTrue();
			assertThat(envelope.originalChars()).isEqualTo(20_000);
			assertThat(envelope.message().length()).as("%s 按剩余窗口收缩（不是配置档 20000）", id)
				.isLessThanOrEqualTo(perResultChars);
			assertThat(envelope.notice()).contains("完整结果见本轮工具调用记录");
		}
		assertThat(ContextEstimator.estimateMessages(result.conversationHistory())).as("收缩后整批仍在窗口内")
			.isLessThan(15_000);

		// ③ 帧仍是全文：模型侧收缩不得让"前端看到的"跟着变短
		org.mockito.ArgumentCaptor<String> frameText = org.mockito.ArgumentCaptor.forClass(String.class);
		org.mockito.Mockito.verify(this.emitter, org.mockito.Mockito.atLeast(1))
			.toolResult(any(PendingToolCall.class), org.mockito.ArgumentMatchers.anyBoolean(),
					org.mockito.ArgumentMatchers.anyBoolean(), frameText.capture());
		assertThat(frameText.getAllValues()).as("至少一份帧是 20000 字全文").anyMatch(text -> text.length() == 20_000);
		assertPairing(result.conversationHistory());
	}

	// ── C13：越窗后收缩最新未保护结果 / 压缩固定历史（不重跑工具）────────────────

	/**
	 * C13 第 3 项：L1 清不掉的<b>最近几对</b>（pin 窗口内）在仍越窗时被<b>收窄</b>，未决 HITL 不动。
	 *
	 * <p>语料：pin 最近 5 对 ⇒ 三条大结果全在 pin 窗口内，L1 一个都清不掉；
	 * 若没有这一刀，这一轮只能以 {@code CONTEXT_OVERFLOW} 收场（真机表现 = 工具已产生副作用却整轮失败）。
	 */
	@Test
	void pinnedResultsAreShrunkInRoundWhileHitlEntriesStayIntact() {
		this.properties.getContext().setLimitTokens(10_000);
		this.properties.getContext().setReserveTokens(1_000);
		this.properties.getContext().setKeepToolPairs(5);
		this.manager.setContextLimitResolver(new ContextLimitResolver(this.properties));
		PendingToolCall hitl = new PendingToolCall();
		hitl.setToolCallId("old-3");
		hitl.setName(TOOL);
		hitl.setKind(PendingToolCall.KIND_BACKEND);
		hitl.setStatus(PendingToolCall.PENDING);
		this.store.putPending(RUN_ID, hitl);

		List<Message> pinned = historyWithPinnedBigResults();
		// 真值 = 这份历史本身的估算（上一跳已经把窗口吃满）—— 这正是 C13 的现场：
		// 工具已产生副作用、下一跳发不出去，而 keep-tool-pairs 让 L1 一条都清不掉。
		ToolExecutionResult result = this.manager.executeToolCalls(prompt(pinned),
				response(ContextEstimator.estimateMessages(pinned), 0, "c-1"));

		List<Message> history = result.conversationHistory();
		assertThat(this.executed).as("工具只执行一次（收缩不重跑）").containsExactly("c-1");
		assertThat(ToolResultEnvelope.fromJson(oldResultText(history, "old-3")).truncated())
			.as("未决 HITL 条目一个字都不动").isFalse();
		assertThat(ToolResultEnvelope.fromJson(oldResultText(history, "old-2")).truncated())
			.as("最新未保护结果最先收窄").isTrue();
		assertThat(history).as("不丢轮次：只收窄（+ 可能的收尾指令）")
			.hasSizeBetween(pinned.size() + 2, pinned.size() + 3);
		assertPairing(history);
	}

	/**
	 * C13 第 4 项：没有可收缩的工具结果时，越窗恢复<b>只压缩固定历史</b>（丢最旧轮）并继续这一跳 ——
	 * 已执行的工具一次都不重跑，也不会以 {@code CONTEXT_OVERFLOW_AFTER_TOOL_SIDE_EFFECT} 收场。
	 */
	@Test
	void overflowRecoveryTrimsFixedHistoryAndContinuesWithoutReRunningTools() {
		this.properties.getContext().setLimitTokens(10_000);
		this.properties.getContext().setReserveTokens(0);
		this.properties.getContext().setKeepToolPairs(5);
		this.manager.setContextLimitResolver(new ContextLimitResolver(this.properties));
		List<Message> longHistory = historyWithLongTurns();

		ToolExecutionResult result = this.manager.executeToolCalls(prompt(longHistory),
				response(ContextEstimator.estimateMessages(longHistory), 0, "c-1"));

		List<Message> history = result.conversationHistory();
		assertThat(this.executed).as("已执行工具绝不重跑").containsExactly("c-1");
		assertThat(history.size()).as("固定历史被压缩（丢最旧轮），这一跳继续").isLessThan(longHistory.size() + 2)
			.isGreaterThan(2);
		assertThat(oldResultText(history, "c-1")).as("本批结果在").isNotNull();
		assertPairing(history);
		RunSnapshot snapshot = this.store.get(RUN_ID);
		assertThat(this.codec.deserializeAll(snapshot.getMessageJson()))
			.as("落定的是同一份压缩后的历史（模型看到的 = 快照里的）")
			.hasSameSizeAs(history);
	}

	// ── 100 档：先 settle，再 CONTEXT_OVERFLOW ───────────────────────────────
	@Test
	void overflowSettlesExecutedRecordsBeforeFailing() {
		List<Message> history = historyWithOldResults();

		assertThatThrownBy(() -> this.manager.executeToolCalls(prompt(history), response(LIMIT, 0, "c-1")))
			.isInstanceOfSatisfying(StreamViolationException.class,
					ex -> assertThat(ex.getCode()).isEqualTo(StreamViolationException.CONTEXT_OVERFLOW));

		assertThat(this.executed).as("工具真的执行过（副作用已发生），只是下一发不发").containsExactly("c-1");
		RunSnapshot snapshot = this.store.get(RUN_ID);
		assertThat(String.join("\n", snapshot.getMessageJson())).as("settle 先于失败：已执行记录在快照里")
			.contains("c-1");
		assertThat(snapshot.getStatus()).isEqualTo(RunSnapshot.RUNNING);
		assertThat(snapshot.getInFlightToolCalls()).isEmpty();
	}

	// ── 取消优先：settle 之后的取消不再驱动下一跳 ────────────────────────────

	/**
	 * 取消落在"本批工具已结算"之后：不得再把可驱动下一跳的历史交回 Spring AI。
	 *
	 * <p>取消读口以"本轮是否已 settle"（{@code round} 已推进）为界：执行前/认领前/执行后三处边界
	 * 读到的都是"未取消"，只有 settle 之后那一次读到取消 —— 因此本用例不依赖读口被调用的次数。
	 */
	@Test
	void cancelAfterToolSettlementStopsBeforeNextModelTurn() {
		when(this.cancellations.cancelState(RUN_ID)).thenAnswer(invocation -> {
			RunSnapshot snapshot = this.store.get(RUN_ID);
			return snapshot != null && snapshot.getRound() > 0
					? CancellationRegistry.CancelState.CANCELLED
					: CancellationRegistry.CancelState.NOT_CANCELLED;
		});

		assertThatThrownBy(() -> this.manager.executeToolCalls(prompt(historyWithOldResults()),
				response(1000, 0, "c-1")))
			.isInstanceOfSatisfying(StreamViolationException.class,
					ex -> assertThat(ex.getCode()).isEqualTo(StreamViolationException.CANCELLED));

		assertThat(this.executed).as("工具只执行一次（取消不撤回已发生的副作用）").containsExactly("c-1");
		RunSnapshot snapshot = this.store.get(RUN_ID);
		assertThat(String.join("\n", snapshot.getMessageJson())).as("历史先 settle：工具结果已写进快照")
			.contains("c-1");
		assertThat(snapshot.getInFlightToolCalls()).isEmpty();
		PendingToolCall entry = this.store.pending(RUN_ID, "c-1");
		assertThat(entry.getStatus()).isEqualTo(PendingToolCall.EXECUTED);
		assertThat(entry.effectiveOutcome()).isEqualTo(ToolOutcome.OK);
	}

	/**
	 * 取消读口在"本批工具已结算"之后读不出来（{@code UNKNOWN}）：按 fail-closed 报
	 * {@code STATE_UNAVAILABLE}，既不放行下一跳，也不冒充用户取消（{@code CANCELLED} 会被
	 * 取消收尾记成"已停止"）。
	 *
	 * <p>读口仍以"本轮是否已 settle"（{@code round} 已推进）为界：认领前、执行后读到的都是
	 * "未取消"，只有 settle 之后那一次读到 {@code UNKNOWN} —— 因此工具确实执行且只执行一次。
	 */
	@Test
	void unknownCancelStateAfterToolSettlementFailsClosedWithStateUnavailable() {
		when(this.cancellations.cancelState(RUN_ID)).thenAnswer(invocation -> {
			RunSnapshot snapshot = this.store.get(RUN_ID);
			return snapshot != null && snapshot.getRound() > 0
					? CancellationRegistry.CancelState.UNKNOWN
					: CancellationRegistry.CancelState.NOT_CANCELLED;
		});

		assertThatThrownBy(() -> this.manager.executeToolCalls(prompt(historyWithOldResults()),
				response(1000, 0, "c-1")))
			.isInstanceOfSatisfying(StreamViolationException.class,
					ex -> assertThat(ex.getCode()).isEqualTo(StreamViolationException.STATE_UNAVAILABLE));

		assertThat(this.executed).as("工具只执行一次（UNKNOWN 不撤回已发生的副作用，也不重放）").containsExactly("c-1");
		RunSnapshot snapshot = this.store.get(RUN_ID);
		assertThat(String.join("\n", snapshot.getMessageJson())).as("历史先 settle：工具结果已写进快照")
			.contains("c-1");
		assertThat(snapshot.getInFlightToolCalls()).isEmpty();
		PendingToolCall entry = this.store.pending(RUN_ID, "c-1");
		assertThat(entry.getStatus()).isEqualTo(PendingToolCall.EXECUTED);
		assertThat(entry.effectiveOutcome()).isEqualTo(ToolOutcome.OK);
	}

	// ── 真值 usage 优先 / 无 usage 估算 ──────────────────────────────────────

	@Test
	void realUsageWinsOverTheEstimate() {
		// 历史估算 ≈ 7000 token（会把估算口径判成 L1），但真值说 prompt 只有 3000
		List<Message> longHistory = historyWithOldResults();
		longHistory.add(0, new UserMessage("a".repeat(28000)));

		ToolExecutionResult result = this.manager.executeToolCalls(prompt(longHistory), response(3000, 0, "c-1"));

		List<Message> history = result.conversationHistory();
		assertThat(oldResultText(history, "old-1")).as("真值未达 L1 ⇒ 不清结果").isEqualTo("旧结果一：任务列表 A");
		assertThat(history).noneMatch(message -> message.getText() != null
				&& message.getText().contains("上下文已接近模型窗口上限"));
	}

	@Test
	void withoutUsageTheEstimateDecides() {
		// 同一份长历史，但上游没给 usage（流式聚合常见 0/0/0）⇒ 按估算判 L1
		List<Message> longHistory = historyWithOldResults();
		longHistory.add(0, new UserMessage("a".repeat(28000)));

		ToolExecutionResult result = this.manager.executeToolCalls(prompt(longHistory), responseWithoutUsage("c-1"));

		assertThat(oldResultText(result.conversationHistory(), "old-1"))
			.startsWith(ToolResultClearing.PLACEHOLDER_PREFIX);
	}

	/**
	 * 反证复审意见 C1（"delta 把旧工具结果重复计入"）：<b>不成立</b>。
	 *
	 * <p>公式 = {@code estimateMessages(history) − estimateMessages(prompt.instructions) − estimateMessage(assistant)}；
	 * {@code history = instructions + 本批 assistant + 本批 tool}，所以旧批次（含早先的大批工具结果）
	 * 在减法里被整段减掉，delta 只剩本批新增。本用例用两批真值 usage 锁死这一点：
	 * 旧结果 ≈4000 token、真值 prompt 5000 ⇒ 正确 nextEst≈5060（未达 70%，不清）；
	 * 若把旧结果重复加一遍则 ≈9060（越过 70% ⇒ 必然清结果）—— "不清"即反证。
	 */
	@Test
	void secondBatchDoesNotRecountEarlierLargeToolResultsWithRealUsage() {
		String hugeOld = "旧工具结果：" + "a".repeat(16000);
		assertThat(ContextEstimator.estimate(hugeOld)).as("旧结果必须足够大：重复计入必然越线").isGreaterThan(3000);

		// 第一跳：上游真值 5000（这 ≈4000 token 的旧结果已经算在 promptTokens 里）
		List<Message> afterFirst = this.manager
			.executeToolCalls(prompt(historyWithLargeOldResult(hugeOld)), response(5000, 0, "c-1"))
			.conversationHistory();
		assertThat(oldResultText(afterFirst, "old-1")).as("70% 线未到 ⇒ 不清").isEqualTo(hugeOld);

		// 第二跳：同一真值。若 delta 重复计入旧结果 ⇒ nextEst≈9000 > 7000（L1 线）⇒ 会清结果
		ToolExecutionResult second = this.manager.executeToolCalls(prompt(afterFirst), response(5000, 0, "c-2"));

		List<Message> history = second.conversationHistory();
		assertThat(oldResultText(history, "old-1")).as("早先的大批结果不得被重复计入 nextEst").isEqualTo(hugeOld);
		assertThat(countPlaceholders(history)).isZero();
		assertThat(history).noneMatch(message -> message.getText() != null
				&& message.getText().contains("上下文已接近模型窗口上限"));

		// 反事实算术：C1 描述的公式会把旧结果再加一遍，正好越过 70% 线（本用例因此能识别它）
		int counterfactualDoubleCount = 5000 + ContextEstimator.estimate(hugeOld);
		assertThat(counterfactualDoubleCount).as("错误的 delta 会越过 L1 线").isGreaterThan(7000);

		// 同一份历史的 delta 算术（旧批次整段在 instructions 里 ⇒ 被减掉，只剩本批新增）
		int instructionsTokens = ContextEstimator.estimateMessages(afterFirst);
		AssistantMessage batchAssistant = (AssistantMessage) history.get(history.size() - 2);
		int delta = ContextEstimator.estimateMessages(history) - instructionsTokens
				- ContextEstimator.estimateMessage(batchAssistant);
		assertThat(instructionsTokens).as("旧工具结果确实在历史里（≈4000 token）").isGreaterThan(3000);
		assertThat(delta).as("delta 只剩本批新增（旧批次已被减掉）").isBetween(1, 200);
	}

	@Test
	void withoutResolverTheIntraTurnBudgetDoesNotRun() {
		// 轻装配面（既有单测/未注入分母）必须一字不变：不判比例、不清结果、不加收尾指令
		ToolActivityBeacon bareBeacon = new ToolActivityBeacon();
		SpToolCallingManager bare = new SpToolCallingManager(this.store, this.registry, this.gate,
				this.toolRegistry, this.codec, bareBeacon, this.cancellations, this.limiter, List.of()) {

			@Override
			String delegateSingle(Prompt prompt, PendingToolCall pending) {
				return "执行成功：" + pending.getToolCallId();
			}
		};

		ToolExecutionResult result = bare.executeToolCalls(prompt(historyWithOldResults()),
				response(7200, 0, "c-9"));

		List<Message> history = result.conversationHistory();
		assertThat(oldResultText(history, "old-1")).isEqualTo("旧结果一：任务列表 A");
		assertThat(history).noneMatch(message -> message.getText() != null
				&& message.getText().contains("上下文已接近模型窗口上限"));
		assertThat(bareBeacon.budgetArmed(RUN_ID)).isFalse();
	}

	// ── 轮内 L1 与轮首同账（台账 / 归档 / 状态，产品一致性批）──────────────────

	/**
	 * 轮内 L1 清除必须与轮首压缩同账：一条 {@code level=L1} 台账事件，字段口径与
	 * {@code ContextCompactor#ledgerEvent} 逐键一致（{@code turns/lostTokens} 对 L1 恒 0）。
	 */
	@Test
	void l1ClearWritesCompactionLedgerWithRealAccounting() {
		ToolExecutionResult result = this.manager.executeToolCalls(prompt(historyWithOldResults()),
				response(7200, 0, "c-1"));

		assertThat(this.contextStore.contextLedger(SESSION_ID)).as("轮内 L1 清除必须留账").hasSize(1);
		Map<String, Object> event = this.contextStore.contextLedger(SESSION_ID).get(0);
		assertThat(event.get("level")).isEqualTo("L1");
		assertThat(event.get("reason")).isEqualTo(TurnBudgetPlan.REASON_L1);
		assertThat(event.get("turns")).as("L1 不折叠轮次").isEqualTo(0);
		assertThat(event.get("lostTokens")).as("L1 不丢 token（省下的量看 before-after）").isEqualTo(0);
		assertThat(event.get("summaryChars")).isEqualTo(0);
		assertThat(event.get("clearedPairs")).isEqualTo(2);
		assertThat(idsOf(event)).containsExactly("old-1", "old-2");
		assertThat(event.get("circuit")).isEqualTo("closed");
		// before/after = 消息估算（与轮首同一口径），不是含 system/tools 的整包估算：
		// after 必须等于"清除后那一段历史"的估算；before = after + 两条被清结果省下的量
		List<Message> postClear = result.conversationHistory();
		int after = ContextEstimator.estimateMessages(postClear);
		int saved = 0;
		for (String id : List.of("old-1", "old-2")) {
			saved += ContextEstimator.estimate(oldResultText(historyWithOldResults(), id))
					- ContextEstimator.estimate(oldResultText(postClear, id));
		}
		// 语料里的工具结果很小，占位符反而更长 ⇒ saved 可能为负：记账如实相告，不粉饰"省了"
		assertThat(event.get("after")).isEqualTo(after);
		assertThat(event.get("before")).isEqualTo(after + saved);
	}

	/** 归档的是"清除前模型看到的那份内容"（不是上游截断前的无限大原文）。 */
	@Test
	void l1ClearArchivesTheContentTheModelActuallyHad() {
		this.manager.executeToolCalls(prompt(historyWithOldResults()), response(7200, 0, "c-1"));

		assertThat(this.contextStore.archiveCount()).as("一次 L1 清除恰一次归档").isEqualTo(1);
		List<String> archived = this.contextStore.archived(SESSION_ID, 1);
		assertThat(archived).as("归档键 = ai:ctx:archive:<sid>:<轮号>（本会话首轮 ⇒ 1）").isNotNull();
		assertThat(String.join("\n", archived)).contains("旧结果一：任务列表 A").contains("旧结果二：任务列表 B");
		assertThat(String.join("\n", archived)).as("归档是清除前的内容").doesNotContain(ToolResultClearing.PLACEHOLDER_PREFIX);
	}

	/** `/context` 快照的 level 必须能看到这次轮内 L1（否则"清了但显示没压过"）。 */
	@Test
	void l1ClearMarksTheCompactionStateForSnapshotReaders() {
		this.manager.executeToolCalls(prompt(historyWithOldResults()), response(7200, 0, "c-1"));

		ContextState state = this.contextStore.read(SESSION_ID);
		assertThat(state.level()).isEqualTo("L1");
		assertThat(state.reason()).isEqualTo(TurnBudgetPlan.REASON_L1);
		assertThat(state.compactedTurns()).isZero();
		assertThat(state.lostTokens()).isZero();
		assertThat(state.turnNo()).as("轮号由轮末 recordTurn 推进，轮内不改").isZero();
	}

	/**
	 * 轮内 L1 必须按轮首口径重写 usage 基线：只写 {@code level=L1} 而留着旧的高 used，会让下一轮的
	 * 轮首压缩被<b>滞回锁</b>整轮挡掉（L1/L2/L3 都不再做，而 L2/L3 只有轮首能做）。
	 *
	 * <p>判据用的是真实 {@link ContextCompactor} + {@link ContextTrigger}（同一份 AiProperties/分母），
	 * 不是只对几个字段做形状断言：① 先反证"level=L1 + 高真值"确实锁死；② 再断言轮内清除写下的
	 * 基线把同一判据放回台面（不是锁死），且基线是估算（{@code est=true}）、轮号不被轮内改写。
	 */
	@Test
	void inRoundClearRewritesTheUsageBaselineSoTheNextRoundIsNotHysteresisLocked() {
		// ① 反证机制：72% 的真值 + level=L1 ⇒ 轮首压缩 hysteresis_locked（整轮一次都不跑）
		String legacySession = SESSION_ID + "-legacy";
		this.contextStore.write(legacySession, ContextState.EMPTY
			.withCompaction(ContextTrigger.Level.L1, 0, 0, "l1_tool_result_clearing", 1_000L)
			.withUsage(7200, 100, 3, false, 1_000L, "m", 0, 0, 0));
		assertThat(roundStart(legacySession, historyWithOldResults(), 7200, 100).reason())
			.as("旧口径（只写 level、不重写基线）确实会被滞回锁挡掉")
			.isEqualTo(ContextCompactor.REASON_HYSTERESIS_LOCKED);

		// ② 轮内清除：基线改成"清除后的估算"（与轮首 ContextCompactor 同款），轮号不动
		this.contextStore.write(SESSION_ID, ContextState.EMPTY
			.withCompaction(ContextTrigger.Level.L1, 0, 0, "l1_tool_result_clearing", 1_000L)
			.withUsage(7200, 100, 3, false, 1_000L, "m", 0, 0, 0));
		List<Message> postClear = this.manager
			.executeToolCalls(prompt(historyWithOldResults()), response(7200, 100, "c-1"))
			.conversationHistory();

		ContextState state = this.contextStore.read(SESSION_ID);
		assertThat(state.usedTokens()).as("基线 = 清除后的估算，不是旧的高真值")
			.isEqualTo(ContextEstimator.estimateMessages(postClear));
		assertThat(state.est()).as("估算基线（est=true），不是伪造的上游真值").isTrue();
		assertThat(state.turnNo()).as("轮号由轮末 recordTurn 推进，轮内不改").isEqualTo(3);

		// ③ 同一套判据在清除后必须被重新评估（不是锁死）
		assertThat(roundStart(SESSION_ID, postClear, state.usedTokens(), state.completionTokens()).reason())
			.as("轮内 L1 不得把下一轮整体锁死")
			.isNotEqualTo(ContextCompactor.REASON_HYSTERESIS_LOCKED);
	}

	/**
	 * C13：轮内清除写下的基线也要<b>含 system + tools</b>（与轮首压缩的新基线同口径）。
	 *
	 * <p>两条写点若各写一套，同一会话的"占用"会随"这轮是靠轮首压的还是轮内清的"而跳变；
	 * 上游真值本来就含这两项，缺了它们只会让下一轮 nextEst 少算前缀。
	 */
	@Test
	void inRoundClearBaselineAlsoCountsSystemAndTools() {
		this.contextStore.write(SESSION_ID, ContextState.EMPTY
			.withUsage(7200, 100, 3, false, 1_000L, "m", 3_000, 4_000, 500));

		List<Message> postClear = this.manager
			.executeToolCalls(prompt(historyWithOldResults()), response(7200, 0, "c-1"))
			.conversationHistory();

		assertThat(this.contextStore.read(SESSION_ID).usedTokens())
			.isEqualTo(ContextEstimator.estimateMessages(postClear) + 3_000 + 4_000);
	}

	/** 已经到 L2/L3 的谱系不被一次轮内 L1 回退（{@code level} 同时是 advanceTo 的起点）。 */
	@Test
	void inRoundClearNeverDowngradesAHigherLineageLevel() {
		this.contextStore.write(SESSION_ID, ContextState.EMPTY
			.withCompaction(ContextTrigger.Level.L3, 2, 900, "hard_trim", 1_000L)
			.withUsage(5000, 100, 3, false, 1_000L, "m", 0, 0, 0));

		this.manager.executeToolCalls(prompt(historyWithOldResults()), response(7200, 0, "c-1"));

		ContextState state = this.contextStore.read(SESSION_ID);
		assertThat(state.level()).as("轮内 L1 不回退谱系").isEqualTo("L3");
		assertThat(state.compactedTurns()).isEqualTo(2);
		assertThat(state.lostTokens()).isEqualTo(900);
		assertThat(this.contextStore.contextLedger(SESSION_ID)).as("台账照记这次 L1 事件").hasSize(1);
		assertThat(this.contextStore.contextLedger(SESSION_ID).get(0).get("level")).isEqualTo("L1");
	}

	/** 重复触发不重复记账：已清成占位符的结果不会再产生一条事件。 */
	@Test
	void repeatedBatchesRecordEachRealClearExactlyOnce() {
		List<Message> first = this.manager.executeToolCalls(prompt(historyWithOldResults()), response(7200, 0, "c-1"))
			.conversationHistory();

		this.manager.executeToolCalls(prompt(first), response(7200, 0, "c-2"));

		List<Map<String, Object>> ledger = this.contextStore.contextLedger(SESSION_ID);
		assertThat(this.contextStore.ledgerCount()).as("两拍各一次真实清除 ⇒ 恰好两条事件").isEqualTo(2);
		assertThat(ledger).hasSize(2);
		// 读回新→旧：最新一条清的是本拍离开 pin 窗口的 c-1；旧拍的两条不会被重记
		assertThat(idsOf(ledger.get(0))).containsExactly("c-1");
		assertThat(idsOf(ledger.get(1))).containsExactly("old-1", "old-2");
	}

	/** 非 L1 分支（未达阈值 / 收尾 / 越窗）不得新增台账与归档。 */
	@Test
	void nonL1BranchesWriteNoLedgerOrArchive() {
		this.manager.executeToolCalls(prompt(historyWithOldResults()), response(1000, 0, "c-1"));
		this.manager.executeToolCalls(prompt(historyWithOldResults()), response(9600, 0, "c-2"));
		assertThatThrownBy(() -> this.manager.executeToolCalls(prompt(historyWithOldResults()), response(LIMIT, 0, "c-3")))
			.isInstanceOfSatisfying(StreamViolationException.class,
					ex -> assertThat(ex.getCode()).isEqualTo(StreamViolationException.CONTEXT_OVERFLOW));

		assertThat(this.contextStore.ledgerCount()).isZero();
		assertThat(this.contextStore.archiveCount()).isZero();
		assertThat(this.contextStore.read(SESSION_ID).level()).isEqualTo("none");
	}

	/**
	 * 记账失败 ⇒ 本轮不清；但三步是<b>三个独立 Redis 写、无事务</b>：台账失败之前归档与状态已经落盘。
	 *
	 * <p>本用例锁的是这个<b>真实的部分失败窗口</b>（不是"原子回滚"）：history 原样、占位符为 0，
	 * 与此同时 {@code ai:ctx:<sid>} 已是 {@code level=L1}、归档已写入，而台账缺这一条 ——
	 * 即 {@code /context} 说"压过"、{@code ?ledger=1} 说"没压过"。不新增锁/回滚协议。
	 */
	@Test
	void ledgerFailureKeepsHistoryUnchanged() {
		InMemoryContextStateStore broken = new InMemoryContextStateStore() {
			@Override
			public void appendLedger(String sessionId, Map<String, Object> event) {
				throw new IllegalStateException("redis down");
			}
		};
		this.manager.setContextStateStore(broken);

		ToolExecutionResult result = this.manager.executeToolCalls(prompt(historyWithOldResults()), response(7200, 0, "c-1"));

		// ① 历史真的没清（模型看到的东西不因记账失败而少一半）
		assertThat(oldResultText(result.conversationHistory(), "old-1")).isEqualTo("旧结果一：任务列表 A");
		assertThat(countPlaceholders(result.conversationHistory())).isZero();
		assertPairing(result.conversationHistory());
		// ② 但归档与 level=L1 在台账失败之前已经写下 —— 已知窗口，不是回滚
		assertThat(broken.archiveCount()).as("归档先于台账，已落盘").isEqualTo(1);
		assertThat(broken.read(SESSION_ID).level()).as("状态先于台账，已写 L1").isEqualTo("L1");
		assertThat(broken.contextLedger(SESSION_ID)).as("台账缺这一条（/context 与 ?ledger=1 在此窗口不一致）")
			.isEmpty();
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	/** C13 语料：三对"大"工具结果（全部落在 pin 窗口内，L1 清不掉）+ 一条本轮输入。 */
	private static List<Message> historyWithPinnedBigResults() {
		List<Message> out = new ArrayList<>();
		out.add(new UserMessage("旧问题"));
		List<AssistantMessage.ToolCall> calls = new ArrayList<>();
		List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
		for (int i = 1; i <= 3; i++) {
			calls.add(new AssistantMessage.ToolCall("old-" + i, "function", TOOL, "{}"));
			responses.add(new ToolResponseMessage.ToolResponse("old-" + i, TOOL,
					ToolResultEnvelope.of(ToolOutcome.OK, null, "旧结果 " + i + "：" + "大".repeat(5_990), "{}")
						.toModelJson(null, TOOL)));
		}
		out.add(AssistantMessage.builder().toolCalls(calls).build());
		out.add(ToolResponseMessage.builder().responses(responses).build());
		out.add(new UserMessage("新问题"));
		return out;
	}

	/** C13 语料：十轮纯文本历史（每轮 ≈1400 token）—— 没有工具结果可收窄，只能靠压缩固定历史救场。 */
	private static List<Message> historyWithLongTurns() {
		List<Message> out = new ArrayList<>();
		for (int i = 1; i <= 10; i++) {
			out.add(new UserMessage("第 " + i + " 个问题"));
			out.add(AssistantMessage.builder().content("这是第 " + i + " 轮的回答：" + "答".repeat(2_400)).build());
		}
		out.add(new UserMessage("新问题"));
		return out;
	}

	/** 旧历史：一条"很大"的工具结果 + 一条本轮输入（用于真值不重复计入的用例）。 */
	private static List<Message> historyWithLargeOldResult(String oldResult) {
		List<Message> out = new ArrayList<>();
		out.add(new UserMessage("旧问题"));
		out.add(AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall("old-1", "function", TOOL, "{}")))
			.build());
		out.add(ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("old-1", TOOL, oldResult)))
			.build());
		out.add(new UserMessage("新问题"));
		return out;
	}

	/** 旧历史：两对可清除的工具结果 + 一条本轮输入（最后一轮永不被 pin 之外的东西动）。 */
	private static List<Message> historyWithOldResults() {		List<Message> out = new ArrayList<>();
		out.add(new UserMessage("旧问题"));
		out.add(AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall("old-1", "function", TOOL, "{}"),
					new AssistantMessage.ToolCall("old-2", "function", TOOL, "{}")))
			.build());
		out.add(ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("old-1", TOOL, "旧结果一：任务列表 A"),
					new ToolResponseMessage.ToolResponse("old-2", TOOL, "旧结果二：任务列表 B")))
			.build());
		out.add(new UserMessage("新问题"));
		return out;
	}

	private static Prompt prompt(List<Message> instructions) {
		ToolCallingChatOptions options = ToolCallingChatOptions.builder()
			.toolContext(Map.of("runId", RUN_ID))
			.build();
		return new Prompt(instructions, options);
	}

	private static ChatResponse response(int promptTokens, int completionTokens, String... ids) {
		return response(ids, ChatResponseMetadata.builder()
			.model("test-model")
			.usage(new DefaultUsage(promptTokens, completionTokens, promptTokens + completionTokens))
			.build());
	}

	private static ChatResponse responseWithoutUsage(String... ids) {
		return response(ids, ChatResponseMetadata.builder().model("test-model").build());
	}

	private static ChatResponse response(String[] ids, ChatResponseMetadata metadata) {
		List<AssistantMessage.ToolCall> calls = new ArrayList<>(ids.length);
		for (String id : ids) {
			calls.add(new AssistantMessage.ToolCall(id, "function", TOOL, "{}"));
		}
		AssistantMessage assistant = AssistantMessage.builder().toolCalls(calls).build();
		return new ChatResponse(List.of(new Generation(assistant)), metadata);
	}

	private static ToolResponseMessage.ToolResponse responseById(List<Message> history, String toolCallId) {
		for (ToolResponseMessage.ToolResponse response : responsesOf(history)) {
			if (toolCallId.equals(response.id())) {
				return response;
			}
		}
		throw new AssertionError("找不到工具结果 " + toolCallId);
	}

	private static String oldResultText(List<Message> history, String toolCallId) {
		for (ToolResponseMessage.ToolResponse response : responsesOf(history)) {
			if (toolCallId.equals(response.id())) {
				return response.responseData();
			}
		}
		return null;
	}

	private static String newResultText(List<Message> history) {
		List<ToolResponseMessage.ToolResponse> all = responsesOf(history);
		return all.get(all.size() - 1).responseData();
	}

	private static List<ToolResponseMessage.ToolResponse> responsesOf(List<Message> history) {
		List<ToolResponseMessage.ToolResponse> out = new ArrayList<>();
		for (Message message : history) {
			if (message instanceof ToolResponseMessage toolMessage) {
				out.addAll(toolMessage.getResponses());
			}
		}
		return out;
	}

	private static long countPlaceholders(List<Message> history) {
		return responsesOf(history).stream()
			.filter(response -> ToolResultClearing.isAlreadyCleared(response.responseData()))
			.count();
	}

	/** 用真实 ContextCompactor（同一份 AiProperties/分母/resolver）跑一次"下一轮的轮首压缩"。 */
	private ContextCompactor.Outcome roundStart(String sessionId, List<Message> history, int used, int completion) {
		ContextCompactor compactor = new ContextCompactor(this.properties,
				new ContextLimitResolver(this.properties), this.contextStore);
		return compactor.compact(new ContextCompactor.Request(sessionId, RUN_ID, "test-model", 1L, history,
				this.codec.serializeAll(history), used, completion, 0, 0, 0, 0, Set.of(), null));
	}

	/** 台账事件里的 clearedToolCallIds（JSON 反序列化形态不固定，统一成字符串列表再断言）。 */
	private static List<String> idsOf(Map<String, Object> event) {
		return ((List<?>) event.get("clearedToolCallIds")).stream().map(String::valueOf).toList();
	}

	/** 配对完整性：每个 assistant toolCall 恰有一个 tool 响应，反之亦然（上游 400 的经典成因）。 */
	private static void assertPairing(List<Message> history) {
		Set<String> calls = new HashSet<>();
		Set<String> toolResponses = new HashSet<>();
		for (Message message : history) {
			if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
				for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
					calls.add(call.id());
				}
			}
			if (message instanceof ToolResponseMessage toolMessage) {
				for (ToolResponseMessage.ToolResponse response : toolMessage.getResponses()) {
					toolResponses.add(response.id());
				}
			}
		}
		assertThat(toolResponses).as("每个 tool 响应都有对应的 assistant tool_call").isEqualTo(calls);
	}

}
