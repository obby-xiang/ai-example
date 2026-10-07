package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.form.GenerativeFormGuard;
import com.example.configmgr.ai.form.GenerativeFormRules;
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
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DC-15 用例③：<b>两道安全闸的接线</b>（无 Redis、无 Spring 容器，桩掉外置状态与帧出口）。
 *
 * <ol>
 * <li><b>下发前</b>（{@link SpToolCallingManager}）：schema 不合白名单 ⇒
 * <b>不挂起</b>（确认门/前端等待的入口一次都没被调用）、不下发前端，只把
 * {@code FORM_SCHEMA_REJECTED} + 原因清单回填模型，条目不留在 {@code PENDING}；</li>
 * <li><b>回灌前</b>（{@link ConfirmGate}）：值不合 schema ⇒ {@code Outcome.REJECTED}、
 * <b>状态不变、不发帧、不唤醒</b>（可修正后重试）；值合规 ⇒ 既有回灌语义（{@code FRONTEND_RESULT}）；
 * 用户取消 ⇒ {@link PendingToolCall#FRONTEND_CANCELLED}（明确终态，不悬置）；
 * 已决条目再提交 ⇒ {@code DUPLICATE}（幂等，取消也不例外）；</li>
 * <li><b>既有前端工具不受影响</b>：没有声明复核器的工具（如 {@code navigate_to}）照旧接受任意文本结果。</li>
 * </ol>
 */
class GenerativeFormGateTest {

	private static final String RUN_ID = "run-form-gate";

	private static final String CALL_ID = "call-form-1";

	/** 合法 schema（一个必填 text 字段）。 */
	private static final String VALID_ARGS = "{\"form\":{\"scenario\":\"FILTER\",\"fields\":["
			+ "{\"key\":\"keyword\",\"label\":\"关键字\",\"type\":\"text\",\"required\":true}]}}";

	/** 非法 schema：{@code type:"html"} 越白名单。 */
	private static final String INVALID_ARGS = "{\"form\":{\"scenario\":\"FILTER\",\"fields\":["
			+ "{\"key\":\"content\",\"label\":\"内容\",\"type\":\"html\",\"html\":\"<img onerror=alert(1)>\"}]}}";

	private final RunStore store = mock(RunStore.class);

	private final RunRegistry registry = mock(RunRegistry.class);

	private final SseChatEmitter emitter = mock(SseChatEmitter.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final MessageJsonCodec codec = mock(MessageJsonCodec.class);

	private final AiProperties properties = new AiProperties();

	private final GenerativeFormGuard guard = new GenerativeFormGuard(new ObjectMapper());

	/** 前端通道的等待入口做成桩：本类关心的是"闸门放行与否"，不是挂起等待本身。 */
	private final ConfirmGate gateForSuspension = mock(ConfirmGate.class);

	private final SpToolCallingManager manager = new SpToolCallingManager(this.store, this.registry,
			this.gateForSuspension, this.toolRegistry, this.codec, this.beacon, this.cancellations,
			new ToolResultLimiter(this.properties), List.of(this.guard));

	/** 回灌闸门用真实实现（复核 + 取消 + 幂等三条分支都在里面）。 */
	private final ConfirmGate gate = new ConfirmGate(this.store, this.registry, this.properties, this.cancellations,
			this.beacon, List.of(this.guard));

	@BeforeEach
	void stubCommon() {
		when(this.cancellations.isCancelled(RUN_ID)).thenReturn(false);
		when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
		when(this.store.get(RUN_ID)).thenReturn(snapshot());
		when(this.store.runKey(RUN_ID)).thenReturn("ai:run:" + RUN_ID);
		when(this.store.pendingKey(RUN_ID)).thenReturn("ai:pending:" + RUN_ID);
		when(this.store.instanceId()).thenReturn("test-instance");
		when(this.toolRegistry.matchesContext(anyString(), any(AiContext.class))).thenReturn(true);
		when(this.toolRegistry.channelOf("generative_form")).thenReturn(ToolMeta.Channel.FRONTEND);
		when(this.toolRegistry.channelOf("navigate_to")).thenReturn(ToolMeta.Channel.FRONTEND);
	}

	// ── ① 下发前的 schema 闸门 ──────────────────────────────────────────────

	@Nested
	class SuspensionGate {

		@Test
		void validSchemaSuspendsAtFrontendGate() {
			when(GenerativeFormGateTest.this.gateForSuspension.awaitFrontendResult(eq(RUN_ID),
					any(PendingToolCall.class))).thenAnswer(invocation -> {
						PendingToolCall pending = invocation.getArgument(1);
						pending.setStatus(PendingToolCall.FRONTEND_RESULT);
						pending.setResultText("{\"keyword\":\"CNY\"}");
						return pending;
					});

			ToolExecutionResult result = GenerativeFormGateTest.this.manager.executeToolCalls(
					prompt(), response("generative_form", VALID_ARGS));

			// 回填模型的就是前端回灌的值本体（字段 key→值 JSON）
			assertThat(toolText(result)).isEqualTo("{\"keyword\":\"CNY\"}");
			verify(GenerativeFormGateTest.this.gateForSuspension).awaitFrontendResult(eq(RUN_ID),
					any(PendingToolCall.class));
		}

		@Test
		void invalidSchemaIsRejectedBeforeAnySuspension() {
			ToolExecutionResult result = GenerativeFormGateTest.this.manager.executeToolCalls(
					prompt(), response("generative_form", INVALID_ARGS));

			String modelText = toolText(result);
			// ① 模型收到机器可读码 + 原因清单（不是"成功"、也不是空文本）
			assertThat(modelText).startsWith(GenerativeFormRules.SCHEMA_REJECTED);
			assertThat(modelText).contains("未向前端下发表单").contains("不在白名单类型内").contains("1. ");

			// ② 一次都没挂起（前端从未被要求渲染这张非法表单），也没有任何副作用认领
			verify(GenerativeFormGateTest.this.gateForSuspension, never()).awaitFrontendResult(anyString(),
					any(PendingToolCall.class));
			verify(GenerativeFormGateTest.this.store, never()).claimExecution(anyString(), anyString(), anyString());

			// ③ 挂起条目不留在 PENDING（续跑不会把它当成"等人/等前端"），结局帧 ok=false
			ArgumentCaptor<PendingToolCall> captor = ArgumentCaptor.forClass(PendingToolCall.class);
			verify(GenerativeFormGateTest.this.store).putPending(eq(RUN_ID), captor.capture());
			assertThat(captor.getValue().getStatus()).isEqualTo(PendingToolCall.REJECTED_ARGUMENTS);
			assertThat(captor.getValue().getReason()).isEqualTo(GenerativeFormRules.SCHEMA_REJECTED);
			assertThat(captor.getValue().isExecuted()).isFalse();
			verify(GenerativeFormGateTest.this.emitter).frontendToolResult(any(PendingToolCall.class), eq(false),
					anyString());
		}
	}

	// ── ② 回灌前的结果闸门 + 取消终态 + 幂等 ────────────────────────────────

	@Nested
	class SubmissionGate {

		@Test
		void validSubmissionIsAcceptedAndWakesTheLoop() {
			PendingToolCall pending = formPending();
			when(GenerativeFormGateTest.this.store.pending(RUN_ID, CALL_ID)).thenReturn(pending);
			String values = "{\"keyword\":\"CNY\"}";

			ConfirmGate.Submission submission = GenerativeFormGateTest.this.gate
				.submitFrontendResult(RUN_ID, CALL_ID, values, "unit-test");

			assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
			assertThat(pending.getStatus()).isEqualTo(PendingToolCall.FRONTEND_RESULT);
			assertThat(pending.getResultText()).isEqualTo(values);
			verify(GenerativeFormGateTest.this.store).putPending(RUN_ID, pending);
			verify(GenerativeFormGateTest.this.emitter).frontendToolResult(pending, true, values);
		}

		@Test
		void invalidSubmissionIsRejectedAndKeepsTheGateOpen() {
			PendingToolCall pending = formPending();
			when(GenerativeFormGateTest.this.store.pending(RUN_ID, CALL_ID)).thenReturn(pending);

			ConfirmGate.Submission submission = GenerativeFormGateTest.this.gate
				.submitFrontendResult(RUN_ID, CALL_ID, "{\"keyword\":7}", "unit-test");

			assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.REJECTED);
			assertThat(submission.verdict()).isNotNull();
			assertThat(submission.verdict().code()).isEqualTo(GenerativeFormRules.RESULT_REJECTED);
			assertThat(submission.verdict().reasons()).anySatisfy(reason -> assertThat(reason).contains("keyword"));

			// 挂起未被消费：状态不变、不落库、不发帧（可修正后重试，或带 cancelled=true 放弃）
			assertThat(pending.getStatus()).isEqualTo(PendingToolCall.PENDING);
			verify(GenerativeFormGateTest.this.store, never()).putPending(eq(RUN_ID), any(PendingToolCall.class));
			verify(GenerativeFormGateTest.this.emitter, never()).frontendToolResult(any(PendingToolCall.class),
					anyBoolean(), anyString());
		}

		@Test
		void dismissalEndsAtExplicitCancelledState() {
			PendingToolCall pending = formPending();
			when(GenerativeFormGateTest.this.store.pending(RUN_ID, CALL_ID)).thenReturn(pending);

			ConfirmGate.Submission submission = GenerativeFormGateTest.this.gate
				.submitFrontendResult(RUN_ID, CALL_ID, null, "frontend-form", true);

			assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
			assertThat(pending.getStatus()).isEqualTo(PendingToolCall.FRONTEND_CANCELLED);
			assertThat(pending.getResultText()).startsWith("FRONTEND_CANCELLED").contains("未获得任何数据");
			assertThat(pending.isExecuted()).isFalse();
			verify(GenerativeFormGateTest.this.store).putPending(RUN_ID, pending);
			// 结局帧与"前端超时"同形（ok=false），前端不必为新终态再解析一种帧
			verify(GenerativeFormGateTest.this.emitter).frontendToolResult(pending, false, pending.getResultText());
		}

		@Test
		void dismissalWinsOverResultReview() {
			// 取消是用户的兜底出口：一张填错一半的表单被关掉，也不该因"值不合规"被拒
			PendingToolCall pending = formPending();
			when(GenerativeFormGateTest.this.store.pending(RUN_ID, CALL_ID)).thenReturn(pending);

			ConfirmGate.Submission submission = GenerativeFormGateTest.this.gate
				.submitFrontendResult(RUN_ID, CALL_ID, "{不是 JSON", "frontend-form", true);

			assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
			assertThat(pending.getStatus()).isEqualTo(PendingToolCall.FRONTEND_CANCELLED);
		}

		@Test
		void repeatedSubmissionIsDuplicateEvenWhenCancelling() {
			PendingToolCall pending = formPending();
			pending.setStatus(PendingToolCall.FRONTEND_RESULT);
			pending.setResultText("{\"keyword\":\"CNY\"}");
			when(GenerativeFormGateTest.this.store.pending(RUN_ID, CALL_ID)).thenReturn(pending);

			ConfirmGate.Submission again = GenerativeFormGateTest.this.gate
				.submitFrontendResult(RUN_ID, CALL_ID, "{\"keyword\":\"HD\"}", "http-post");
			ConfirmGate.Submission cancelled = GenerativeFormGateTest.this.gate
				.submitFrontendResult(RUN_ID, CALL_ID, null, "frontend-form", true);

			assertThat(again.outcome()).isEqualTo(ConfirmGate.Outcome.DUPLICATE);
			assertThat(cancelled.outcome()).isEqualTo(ConfirmGate.Outcome.DUPLICATE);
			verify(GenerativeFormGateTest.this.store, never()).putPending(eq(RUN_ID), any(PendingToolCall.class));
		}

		/** 既有前端工具（无复核器）语义不变：任意文本结果照收。 */
		@Test
		void frontendToolWithoutGuardStillTakesFreeTextResult() {
			PendingToolCall pending = new PendingToolCall();
			pending.setToolCallId(CALL_ID);
			pending.setName("navigate_to");
			pending.setKind(PendingToolCall.KIND_FRONTEND);
			pending.setArguments("{\"page\":\"export\"}");
			pending.setStatus(PendingToolCall.PENDING);
			when(GenerativeFormGateTest.this.store.pending(RUN_ID, CALL_ID)).thenReturn(pending);

			ConfirmGate.Submission submission = GenerativeFormGateTest.this.gate
				.submitFrontendResult(RUN_ID, CALL_ID, "{\"ok\":true,\"handled\":\"e2e\"}", "e2e-script");

			assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
			assertThat(pending.getStatus()).isEqualTo(PendingToolCall.FRONTEND_RESULT);
		}
	}

	// ── 夹具 ────────────────────────────────────────────────────────────────

	private static PendingToolCall formPending() {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(CALL_ID);
		pending.setName("generative_form");
		pending.setKind(PendingToolCall.KIND_FRONTEND);
		pending.setArguments(VALID_ARGS);
		pending.setStatus(PendingToolCall.PENDING);
		return pending;
	}

	private static RunSnapshot snapshot() {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(RUN_ID);
		snapshot.setStatus(RunSnapshot.RUNNING);
		return snapshot;
	}

	/** 本轮的 prompt（工具调用来自上游响应，因此这里只需带上 runId 让挂起态可外置）。 */
	private Prompt prompt() {
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
			.filter(org.springframework.ai.chat.messages.ToolResponseMessage.class::isInstance)
			.map(org.springframework.ai.chat.messages.ToolResponseMessage.class::cast)
			.flatMap(message -> message.getResponses().stream())
			.map(org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse::responseData)
			.findFirst()
			.orElseThrow();
	}

}
