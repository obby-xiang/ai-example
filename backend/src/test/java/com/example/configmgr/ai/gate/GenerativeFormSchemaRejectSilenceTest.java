package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.form.GenerativeFormGuard;
import com.example.configmgr.ai.form.GenerativeFormRules;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.ResumeService;
import com.example.configmgr.ai.run.RunParkedException;
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
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>非法 schema 的表单不得先变成一张"已拒绝"卡，再让下一张可用卡跟在后面</b>。
 *
 * <h2>被钉住的缺陷（浏览器实测）</h2>
 * 模型给的 {@code generative_form} 顶层 {@code purpose}/{@code scenario}/{@code title} 不合法时，
 * 后端<b>确实</b>没挂起、没下发 {@code frontend_tool_request}，但执行循环仍为它补了
 * {@code tool_start} + {@code frontend_tool_result(ok=false)} —— 用户屏幕上先落一张"已拒绝"的表单卡，
 * 模型纠正后重出一张合法表单，再落第二张卡。用户看到的是"一张坏的 + 一张好的"，而实际只有一次收集意图。
 *
 * <h2>本批口径</h2>
 * 生成式表单的 schema / 槽位<b>系统拒绝</b>（{@code FORM_SCHEMA_REJECTED} /
 * {@code FORM_SLOTS_ALREADY_KNOWN}）只回执模型：<b>一条会产生卡片的帧都不发</b>
 * （{@code tool_start} / {@code frontend_tool_request} / {@code frontend_tool_result} /
 * {@code suspended} / {@code confirm_request} 全无），条目、日志与模型回执照旧。
 * 后续合法表单照常挂起下发，且<b>只出现一次</b>。用户取消/拒绝<b>合法</b>表单的既有语义不变
 * （不在本用例的判据内：它走的是回灌闸门，不经过入参闸门）。
 */
class GenerativeFormSchemaRejectSilenceTest {

	private static final String RUN_ID = "run-form-schema-silence";

	private static final String SESSION_ID = "s-form-schema-silence";

	private static final String ILLEGAL_CALL_ID = "call-form-illegal";

	private static final String LEGAL_CALL_ID = "call-form-legal";

	/** 三层顶层属性同时不合法：{@code scenario} 越白名单、{@code purpose} 越白名单、{@code title} 非文本。 */
	private static final String ILLEGAL_ARGS = "{\"form\":{\"scenario\":\"REPORT\",\"purpose\":\"COLLECT_HOLIDAY\","
			+ "\"title\":7,\"fields\":[{\"key\":\"keyword\",\"label\":\"关键字\",\"type\":\"text\"}]}}";

	/** 合法表单：显式 {@code GENERAL_CLARIFY}（不触发槽位关闭），一个普通文本字段。 */
	private static final String LEGAL_ARGS = "{\"form\":{\"scenario\":\"CLARIFY\",\"purpose\":\"GENERAL_CLARIFY\","
			+ "\"title\":\"确认范围\",\"fields\":[{\"key\":\"scope\",\"label\":\"范围\",\"type\":\"text\"}]}}";

	/** 会产生用户卡片的帧型（本批要求非法 schema 一条都不发）。 */
	private static final Set<String> CARD_FRAME_TYPES = Set.of("suspended", "tool_start", "frontend_tool_request",
			"frontend_tool_result", "confirm_request");

	private final ObjectMapper mapper = new ObjectMapper();

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolActivityBeacon beacon = new ToolActivityBeacon();

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final AiProperties properties = new AiProperties();

	/** 生产形态的复核器（真实白名单规则），不是桩：被钉住的正是"真 schema 被拒后的帧面"。 */
	private final GenerativeFormGuard guard = new GenerativeFormGuard(this.mapper);

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		// 生产形态：park=true（帧前移/挂起与前移的先后顺序都按生产走）
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
	void illegalTopLevelPurposeScenarioTitleEmitsNoCardFrameAndTheLaterLegalFormAppearsExactlyOnce() {
		// ===== 第一轮：非法顶层 purpose/scenario/title ⇒ 系统拒绝只回执模型 =====
		ToolExecutionResult rejected = manager().executeToolCalls(prompt(), response(ILLEGAL_CALL_ID, ILLEGAL_ARGS));

		assertThat(this.store.archivedTypes(RUN_ID))
			.as("非法 schema 不得在界面上留下任何卡片帧（否则用户先看到一张'已拒绝'卡）")
			.doesNotContainAnyElementsOf(CARD_FRAME_TYPES);
		assertThat(framesText())
			.as("被拒的那次调用连 id 都不该出现在任何一帧里")
			.doesNotContain(ILLEGAL_CALL_ID);

		// 模型仍收到纠正回执：码 + 逐条"改哪一处"（purpose / scenario / title 三处都在）
		ToolResultEnvelope envelope = ToolResultEnvelope.fromJson(toolResponseData(rejected));
		assertThat(envelope.outcome()).isEqualTo("rejected");
		assertThat(envelope.code()).isEqualTo(GenerativeFormRules.SCHEMA_REJECTED);
		assertThat(envelope.message()).contains(GenerativeFormRules.SCHEMA_REJECTED)
			.contains("未向前端下发表单")
			.contains("purpose")
			.contains("scenario")
			.contains("title");
		// 条目留在账上（审计/排障面），但不进 PENDING
		assertThat(this.store.pending(RUN_ID, ILLEGAL_CALL_ID).getStatus())
			.isEqualTo(PendingToolCall.REJECTED_ARGUMENTS);
		assertThat(this.store.pending(RUN_ID, ILLEGAL_CALL_ID).getCode())
			.isEqualTo(GenerativeFormRules.SCHEMA_REJECTED);

		// ===== 第二轮：模型按回执改对 ⇒ 合法表单照常下发，且只出现一次 =====
		assertThatThrownBy(() -> manager().executeToolCalls(prompt(), response(LEGAL_CALL_ID, LEGAL_ARGS)))
			.isInstanceOf(RunParkedException.class);

		List<Map<String, Object>> cardFrames = this.store.events(RUN_ID)
			.stream()
			.filter(frame -> CARD_FRAME_TYPES.contains(String.valueOf(frame.get("type"))))
			.toList();
		assertThat(cardFrames).as("后续合法表单仍要真的到用户手里（挂起 + 工具卡 + 请求帧）")
			.hasSize(3);
		assertThat(cardFrames.toString())
			.as("三帧必须全属于合法那次调用（suspended 的 id 在 toolCalls[] 里）")
			.contains(LEGAL_CALL_ID);
		assertThat(framesText()).as("非法那次调用在帧面没有任何留痕").doesNotContain(ILLEGAL_CALL_ID);
		assertThat(this.store.pending(RUN_ID, LEGAL_CALL_ID).getStatus()).isEqualTo(PendingToolCall.PENDING);
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

	/** 归档帧的整体文本（判"某次调用是否在任何一帧里留痕"）。 */
	private String framesText() {
		return this.store.events(RUN_ID).toString();
	}

	private static String toolResponseData(ToolExecutionResult result) {
		return result.conversationHistory()
			.stream()
			.filter(org.springframework.ai.chat.messages.ToolResponseMessage.class::isInstance)
			.map(org.springframework.ai.chat.messages.ToolResponseMessage.class::cast)
			.flatMap(message -> message.getResponses().stream())
			.map(org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse::responseData)
			.findFirst()
			.orElseThrow(() -> new AssertionError("模型没有收到工具回执"));
	}

	private static Prompt prompt() {
		ToolCallingChatOptions options = ToolCallingChatOptions.builder()
			.toolCallbacks(List.of())
			.toolContext(Map.of("runId", RUN_ID))
			.build();
		return new Prompt(List.of(new UserMessage("出一张表单")), options);
	}

	private static ChatResponse response(String toolCallId, String arguments) {
		AssistantMessage assistant = AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall(toolCallId, "function", "generative_form", arguments)))
			.build();
		return new ChatResponse(List.of(new Generation(assistant)));
	}

}
