package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolResultEnvelope;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolExecutionResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0-1 六出口里"不易从真实工具触发"的两条：{@code orElse(null)}（结果缺失）与整轮取消。
 * 其余四条（成功/异常/越 scope/确认拒绝/超时）在 conformance 与 wiring 用例里走真实链路。
 */
class ToolOutcomeExitTest {

	private static final String RUN_ID = "run-tool-outcome";

	private static final String CALL_ID = "call-outcome-1";

	private final RunStore store = mock(RunStore.class);

	{
		// C2：生产读侧已改为 pendingLenient/pendingsLenient；Mockito 桩只需把非抛读口委托给原桩。
		when(this.store.pendingLenient(org.mockito.ArgumentMatchers.anyString(),
				org.mockito.ArgumentMatchers.anyString()))
			.thenAnswer(invocation -> this.store.pending((String) invocation.getArgument(0),
					(String) invocation.getArgument(1)));
		when(this.store.pendingsLenient(org.mockito.ArgumentMatchers.anyString()))
			.thenAnswer(invocation -> this.store.pendings((String) invocation.getArgument(0)));
	}


	private final RunRegistry registry = mock(RunRegistry.class);

	private final ConfirmGate gate = mock(ConfirmGate.class);

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final MessageJsonCodec codec = mock(MessageJsonCodec.class);

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final SseChatEmitter emitter = mock(SseChatEmitter.class);

	private final AiProperties properties = new AiProperties();

	private final ToolResultLimiter limiter = new ToolResultLimiter(this.properties);

	private final SpToolCallingManager manager = new SpToolCallingManager(this.store, this.registry, this.gate,
			this.toolRegistry, this.codec, this.beacon, this.cancellations, this.limiter, List.of());

	@BeforeEach
	void stubRun() {
		when(this.cancellations.isCancelled(RUN_ID)).thenReturn(false);
		when(this.store.get(RUN_ID)).thenReturn(snapshot());
		when(this.store.runKey(RUN_ID)).thenReturn("ai:run:" + RUN_ID);
		when(this.store.pendingKey(RUN_ID)).thenReturn("ai:pending:" + RUN_ID);
		when(this.store.instanceId()).thenReturn("test-instance");
		when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
		when(this.store.ledgerFor(eq(RUN_ID), anyString())).thenReturn(null);
		when(this.toolRegistry.matchesContext(anyString(), any(AiContext.class))).thenReturn(true);
		when(this.store.claimExecution(eq(RUN_ID), eq(CALL_ID), anyString())).thenReturn(true);
	}

	@Test
	void missingDelegateResultBecomesFailedEnvelope() {
		when(this.toolRegistry.channelOf("list_tasks")).thenReturn(ToolMeta.Channel.BACKEND);
		when(this.toolRegistry.riskOf("list_tasks")).thenReturn(ToolMeta.RiskLevel.READ);
		SpToolCallingManager missing = new SpToolCallingManager(this.store, this.registry, this.gate,
				this.toolRegistry, this.codec, this.beacon, this.cancellations, this.limiter, List.of()) {
			@Override
			String delegateSingle(Prompt prompt, PendingToolCall pending) {
				return null;
			}
		};

		ToolExecutionResult result = missing.executeToolCalls(prompt("list_tasks", "{\"page\":\"tasks\"}"),
				response("list_tasks", "{\"page\":\"tasks\"}"));

		ToolResultEnvelope envelope = ToolResultEnvelope.fromJson(toolText(result));
		assertThat(envelope.outcome()).isEqualTo(ToolOutcome.FAILED.wire());
		assertThat(envelope.code()).isEqualTo(ToolOutcome.CODE_TOOL_RESULT_MISSING);
		assertThat(envelope.retryable()).isFalse();
		assertThat(envelope.message()).isEmpty();
		assertThat(envelope.target()).isEqualTo(Map.of("page", "tasks"));

		ArgumentCaptor<PendingToolCall> framePending = ArgumentCaptor.forClass(PendingToolCall.class);
		verify(this.emitter).toolResult(framePending.capture(), eq(false), eq(false), eq(""));
		assertThat(framePending.getValue().getOutcome()).isEqualTo(ToolOutcome.FAILED.wire());
		assertThat(framePending.getValue().getCode()).isEqualTo(ToolOutcome.CODE_TOOL_RESULT_MISSING);
	}

	@Test
	void cancelledEntryBecomesCancelledEnvelope() {
		when(this.toolRegistry.channelOf("start_import")).thenReturn(ToolMeta.Channel.BACKEND);
		when(this.toolRegistry.riskOf("start_import")).thenReturn(ToolMeta.RiskLevel.DANGER);
		when(this.gate.awaitDecision(eq(RUN_ID), any(PendingToolCall.class))).thenAnswer(invocation -> {
			PendingToolCall pending = invocation.getArgument(1);
			pending.setStatus(PendingToolCall.CANCELLED);
			pending.setExecuted(false);
			pending.setResultText("本轮对话已被用户取消，该工具未执行。");
			return pending;
		});

		ToolExecutionResult result = this.manager.executeToolCalls(prompt("start_import", "{\"taskId\":7}"),
				response("start_import", "{\"taskId\":7}"));

		ToolResultEnvelope envelope = ToolResultEnvelope.fromJson(toolText(result));
		assertThat(envelope.outcome()).isEqualTo(ToolOutcome.CANCELLED.wire());
		assertThat(envelope.code()).isEqualTo(ToolOutcome.CODE_RUN_CANCELLED);
		assertThat(envelope.retryable()).isFalse();
		assertThat(envelope.message()).contains("已被用户取消");

		ArgumentCaptor<PendingToolCall> framePending = ArgumentCaptor.forClass(PendingToolCall.class);
		verify(this.emitter).toolResult(framePending.capture(), eq(false), eq(false), eq(envelope.message()));
		assertThat(framePending.getValue().getStatus()).isEqualTo(PendingToolCall.CANCELLED);
	}

	private static RunSnapshot snapshot() {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(RUN_ID);
		snapshot.setStatus(RunSnapshot.RUNNING);
		snapshot.setContext(AiContext.of("import", "IMPORT", "IMPORT", 7L, null).toMap());
		return snapshot;
	}

	private static Prompt prompt(String tool, String args) {
		ToolCallingChatOptions options = ToolCallingChatOptions.builder()
			.toolContext(Map.of("runId", RUN_ID))
			.build();
		return new Prompt(List.of(new UserMessage("hi")), options);
	}

	private static ChatResponse response(String tool, String args) {
		AssistantMessage assistant = AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall(CALL_ID, "function", tool, args)))
			.build();
		return new ChatResponse(List.of(new Generation(assistant)));
	}

	private static String toolText(ToolExecutionResult result) {
		return result.conversationHistory()
			.stream()
			.filter(ToolResponseMessage.class::isInstance)
			.map(ToolResponseMessage.class::cast)
			.flatMap(message -> message.getResponses().stream())
			.map(ToolResponseMessage.ToolResponse::responseData)
			.findFirst()
			.orElseThrow();
	}

}
