package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.SseChatEmitter;
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
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolExecutionResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工具步数笼（决策看板 {@code docs/决策看板.md:14}，2026-10-21）：
 * {@code app.ai.resilience.max-tool-steps = 25}，<b>步数累计、跨挂起不重置</b>（LangGraph 口径），
 * 到顶 ⇒ <b>降级「部分完成收尾」</b>（给模型一条收尾指令让其用已有信息作答），<b>不判整轮失败</b>。
 *
 * <h2>为什么计数必须外置到快照</h2>
 * 「跨挂起不重置」的判据不是本进程的计数器，而是 {@link RunSnapshot#getToolSteps()} ——
 * 确认门/前端工具挂起后，续跑可能落在<b>另一个进程</b>上；进程内计数会被重置成 0，
 * 于是步数笼形同不存在（一次五段挂起就能刷出 5×25 步）。故本类里「跨挂起」用
 * <b>直接 seed 快照里的累计值</b>来模拟，而不是再走一遍内存路径。
 */
class SpToolCallingManagerStepLimitTest {

	private static final String RUN_ID = "run-step-limit";

	private static final String SESSION_ID = "s-step-limit";

	private static final String TOOL = "list_tasks";

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final RunRegistry registry = mock(RunRegistry.class);

	private final ConfirmGate gate = mock(ConfirmGate.class);

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final MessageJsonCodec codec = new MessageJsonCodec(new ObjectMapper());

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final SseChatEmitter emitter = mock(SseChatEmitter.class);

	private final AiProperties properties = new AiProperties();

	private final ToolResultLimiter limiter = new ToolResultLimiter(this.properties);

	/** 执行替身调用次数（断言「到顶后的调用真的没执行」）。 */
	private final List<String> executed = new ArrayList<>();

	private final SpToolCallingManager manager = new SpToolCallingManager(this.store, this.registry, this.gate,
			this.toolRegistry, this.codec, this.beacon, this.cancellations, this.limiter, List.of()) {

		@Override
		String delegateSingle(Prompt prompt, PendingToolCall pending) {
			SpToolCallingManagerStepLimitTest.this.executed.add(pending.getToolCallId());
			return "执行成功：" + pending.getToolCallId();
		}
	};

	@BeforeEach
	void setUp() {
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

	@Test
	void stepLimitSkipsTheCallBeyondTheBudget() {
		// 一批 26 个调用：第 26 个越过 25 步上限
		ToolExecutionResult result = this.manager.executeToolCalls(prompt(), response(26));

		List<ToolResponseMessage.ToolResponse> responses = responses(result);
		assertThat(responses).hasSize(26);
		assertThat(this.executed).as("只有前 25 步真的执行了").hasSize(25);
		assertThat(this.executed).doesNotContain("c-26");

		ToolResultEnvelope beyond = envelope(responses.get(25));
		assertThat(beyond.outcome()).isEqualTo(ToolOutcome.SKIPPED.wire());
		assertThat(beyond.code()).isEqualTo("TOOL_STEP_LIMIT");
		assertThat(beyond.ok()).isFalse();

		PendingToolCall entry = this.store.pending(RUN_ID, "c-26");
		assertThat(entry.getStatus()).isEqualTo(PendingToolCall.SKIPPED);
		assertThat(entry.getOutcome()).isEqualTo(ToolOutcome.SKIPPED.wire());
		assertThat(entry.getCode()).isEqualTo("TOOL_STEP_LIMIT");
		assertThat(entry.isExecuted()).isFalse();
		// 计数落在快照上（跨挂起/跨进程的唯一真值）
		assertThat(this.store.get(RUN_ID).getToolSteps()).isEqualTo(25);
	}

	@Test
	void stepLimitAppendsAWrapUpInstruction() {
		ToolExecutionResult result = this.manager.executeToolCalls(prompt(), response(26));

		List<Message> history = result.conversationHistory();
		String tail = history.get(history.size() - 1).getText();
		assertThat(tail).as("到顶后追加一条收尾指令（不再调用工具、用已有信息作答）").contains("工具调用步数");
		assertThat(tail).contains("不要再次调用工具");
		// 不判整轮失败：正常返回 ToolExecutionResult（异常在上一行就不存在了）
		assertThat(result.conversationHistory()).isNotEmpty();
	}

	@Test
	void stepCountSurvivesSuspension() {
		// 模拟"上一段已用满 25 步"（另一个进程/另一个挂起段写下的累计值）
		RunSnapshot snapshot = this.store.get(RUN_ID);
		snapshot.setToolSteps(25);
		this.store.save(snapshot);

		ToolExecutionResult result = this.manager.executeToolCalls(prompt(), response(1));

		assertThat(this.executed).as("步数不因新段/新进程重置").isEmpty();
		ToolResultEnvelope first = envelope(responses(result).get(0));
		assertThat(first.code()).isEqualTo("TOOL_STEP_LIMIT");
		assertThat(this.store.get(RUN_ID).getToolSteps()).isEqualTo(25);
	}

	@Test
	void belowTheLimitNothingChanges() {
		ToolExecutionResult result = this.manager.executeToolCalls(prompt(), response(3));

		assertThat(this.executed).containsExactly("c-1", "c-2", "c-3");
		for (ToolResponseMessage.ToolResponse response : responses(result)) {
			assertThat(envelope(response).outcome()).isEqualTo(ToolOutcome.OK.wire());
		}
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private static Prompt prompt() {
		ToolCallingChatOptions options = ToolCallingChatOptions.builder()
			.toolContext(Map.of("runId", RUN_ID))
			.build();
		return new Prompt(List.of(new UserMessage("把任务列一下")), options);
	}

	private static ChatResponse response(int callCount) {
		List<AssistantMessage.ToolCall> calls = new ArrayList<>(callCount);
		for (int i = 1; i <= callCount; i++) {
			calls.add(new AssistantMessage.ToolCall("c-" + i, "function", TOOL, "{}"));
		}
		AssistantMessage assistant = AssistantMessage.builder().toolCalls(calls).build();
		return new ChatResponse(List.of(new Generation(assistant)));
	}

	private static List<ToolResponseMessage.ToolResponse> responses(ToolExecutionResult result) {
		List<ToolResponseMessage.ToolResponse> out = new ArrayList<>();
		for (Message message : result.conversationHistory()) {
			if (message instanceof ToolResponseMessage toolMessage) {
				out.addAll(toolMessage.getResponses());
			}
		}
		return out;
	}

	private static ToolResultEnvelope envelope(ToolResponseMessage.ToolResponse response) {
		return ToolResultEnvelope.fromJson(response.responseData());
	}

}
