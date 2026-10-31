package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.form.FormIssueCodes;
import com.example.configmgr.ai.form.GenerativeFormGuard;
import com.example.configmgr.ai.form.GenerativeFormRules;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.ResumeService;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.FrontendToolGuard;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolResultEnvelope;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>入参拒绝的机器可读清单必须留到 {@code pending.resultText}</b>。
 *
 * <p>结算路径曾把复核结论拆成 {@code code/message/reasons} 再重建成 {@code Verdict}，
 * {@code issues} 在这一拆一装之间丢失：模型回执里只剩人读原因，按码检索
 * （{@code LINKAGE_DEPTH_EXCEEDED} / {@code LINKAGE_CONDITION_INVALID} 等）取不到任何东西，
 * 前端也无法据此点亮字段。本类用真实复核器钉住"两个码都要出现在结算文本里"。
 */
class GenerativeFormLinkageIssuesSettleTest {

	private static final String RUN_ID = "run-form-linkage-issues";

	private static final String SESSION_ID = "s-form-linkage-issues";

	private static final String CALL_ID = "call-form-linkage";

	/**
	 * 一张同时踩两处联动规则的表单：{@code d→c→b→a} 依赖链 3 层（上限 2）⇒
	 * {@code LINKAGE_DEPTH_EXCEEDED}；{@code e.visibleWhen} 是表达式字符串 ⇒
	 * {@code LINKAGE_CONDITION_INVALID}。
	 */
	private static final String LINKAGE_ARGS = "{\"form\":{\"scenario\":\"FILTER\",\"fields\":["
			+ "{\"key\":\"a\",\"label\":\"A\",\"type\":\"text\"},"
			+ "{\"key\":\"b\",\"label\":\"B\",\"type\":\"text\",\"visibleWhen\":{\"key\":\"a\",\"op\":\"notEmpty\"}},"
			+ "{\"key\":\"c\",\"label\":\"C\",\"type\":\"text\",\"visibleWhen\":{\"key\":\"b\",\"op\":\"notEmpty\"}},"
			+ "{\"key\":\"d\",\"label\":\"D\",\"type\":\"text\",\"visibleWhen\":{\"key\":\"c\",\"op\":\"notEmpty\"}},"
			+ "{\"key\":\"e\",\"label\":\"E\",\"type\":\"text\",\"visibleWhen\":\"a === 1 || alert(1)\"}]}}";

	private final ObjectMapper mapper = new ObjectMapper();

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolActivityBeacon beacon = new ToolActivityBeacon();

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final AiProperties properties = new AiProperties();

	private final GenerativeFormGuard guard = new GenerativeFormGuard(this.mapper);

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		this.properties.getPark().setEnabled(true);
		when(this.cancellations.isCancelled(anyString())).thenReturn(false);
		when(this.cancellations.onCancel(anyString(), any())).thenAnswer(invocation -> (AutoCloseable) () -> {
		});
		when(this.toolRegistry.matchesContext(anyString(), any(AiContext.class))).thenReturn(true);
		when(this.toolRegistry.channelOf("generative_form")).thenReturn(ToolMeta.Channel.FRONTEND);
		when(this.toolRegistry.riskOf("generative_form")).thenReturn(ToolMeta.RiskLevel.WRITE);
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setContext(AiContext.empty().toMap());
		this.store.save(snapshot);
	}

	@Test
	void linkageIssueCodesSurviveSettleIntoResultText() {
		ToolExecutionResult rejected = manager().executeToolCalls(prompt(), response(CALL_ID, LINKAGE_ARGS));

		ToolResultEnvelope envelope = ToolResultEnvelope.fromJson(toolResponseData(rejected));
		assertThat(envelope.outcome()).isEqualTo("rejected");
		assertThat(envelope.code()).isEqualTo(GenerativeFormRules.SCHEMA_REJECTED);

		String resultText = this.store.pending(RUN_ID, CALL_ID).getResultText();
		assertThat(resultText)
			.as("结算文本必须带机器可读清单（模型/前端按码定位），不能只剩人读原因")
			.contains("机器可读")
			.contains("code=" + FormIssueCodes.LINKAGE_DEPTH_EXCEEDED)
			.contains("code=" + FormIssueCodes.LINKAGE_CONDITION_INVALID);
		assertThat(envelope.message()).as("模型回执与条目结算文本同源").isEqualTo(resultText);
		assertThat(this.store.pending(RUN_ID, CALL_ID).getStatus())
			.isEqualTo(PendingToolCall.REJECTED_ARGUMENTS);
	}

	// ── 桩与辅助 ────────────────────────────────────────────────────────────

	private SpToolCallingManager manager() {
		ObjectProvider<ResumeService> provider = mock(ObjectProvider.class);
		ConfirmGate gate = new ConfirmGate(this.store, this.registry, this.properties, this.cancellations, this.beacon,
				List.of(), provider, Runnable::run, null, ConfirmGate.DEFAULT_RETRY_SCHEDULER);
		return new SpToolCallingManager(this.store, this.registry, gate, this.toolRegistry,
				mock(MessageJsonCodec.class), this.beacon, this.cancellations,
				new ToolResultLimiter(this.properties), List.of((FrontendToolGuard) this.guard));
	}

	private static String toolResponseData(ToolExecutionResult result) {
		return result.conversationHistory()
			.stream()
			.filter(ToolResponseMessage.class::isInstance)
			.map(ToolResponseMessage.class::cast)
			.flatMap(message -> message.getResponses().stream())
			.map(ToolResponseMessage.ToolResponse::responseData)
			.findFirst()
			.orElseThrow(() -> new AssertionError("模型没有收到工具回执"));
	}

	private static Prompt prompt() {
		ToolCallingChatOptions options = ToolCallingChatOptions.builder()
			.toolCallbacks(List.of())
			.toolContext(Map.of("runId", RUN_ID))
			.build();
		return new Prompt(List.of(new UserMessage("出一张筛选表单")), options);
	}

	private static ChatResponse response(String toolCallId, String arguments) {
		AssistantMessage assistant = AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall(toolCallId, "function", "generative_form", arguments)))
			.build();
		return new ChatResponse(List.of(new Generation(assistant)));
	}

}
