package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
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
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.example.configmgr.common.ErrorCodes;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>槽位已确定的收集表单：留在账上，不留在界面上</b>（{@code FORM_SLOTS_ALREADY_KNOWN}）。
 *
 * <h2>被钉住的缺陷</h2>
 * 槽位放行的拒绝是<b>零副作用</b>的（没有挂起、没有 {@code frontend_tool_request}），但执行循环随后
 * 仍为它发了 {@code tool_start} + {@code frontend_tool_result} —— 前端于是渲染出一张"未执行"的表单卡，
 * 用户看到的却是"我什么都没收到就多了一张卡"。这条卡既不能填也不能点，属于纯噪声。
 *
 * <h2>本批口径</h2>
 * 只对<b>这一个错误码</b>（{@link ErrorCodes#FORM_SLOTS_ALREADY_KNOWN}）静默用户可见的工具帧，
 * 其余三面<b>一字不改</b>：条目仍是 {@code REJECTED_ARGUMENTS} + 同名 code（审计/幂等/排障可查）、
 * 日志照旧 WARN、模型照旧收到结构化的纠正回执（含"下一步直接调 select_definitions"的指引）。
 *
 * <p>两条反向用例钉住"不修复过度"：①槽位未知/歧义 ⇒ 表单照旧下发（挂起 + 请求帧 + 工具卡全在）；
 * ②其它拒绝码（如 {@code FORM_SCHEMA_REJECTED}）⇒ 失败卡照旧可见（用户要知道模型给的表单为什么没成）。
 */
class FormSlotSilenceTest {

	private static final String RUN_ID = "run-form-slot-silence";

	private static final String SESSION_ID = "s-form-slot-silence";

	private static final String CALL_ID = "call-form-slot";

	private final ObjectMapper mapper = new ObjectMapper();

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolActivityBeacon beacon = new ToolActivityBeacon();

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final AiProperties properties = new AiProperties();

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
	void knownSlotFormKeepsEntryLogAndModelReceiptButEmitsNoUserVisibleToolFrames() {
		ToolExecutionResult result = manager(rejecting(ErrorCodes.FORM_SLOTS_ALREADY_KNOWN)).executeToolCalls(prompt(),
				response());

		// ① 用户可见面：一条会产生卡片的工具帧都不发（tool_start / frontend_tool_result 都不发）
		assertThat(this.store.archivedTypes(RUN_ID))
			.as("零副作用的拒绝不该在界面上留下任何卡片帧")
			.doesNotContain("tool_start", "frontend_tool_result", "frontend_tool_request", "suspended");
		// ② 条目仍在：状态/码与既有结算口径逐字一致（审计、幂等、排障都靠它）
		PendingToolCall settled = this.store.pending(RUN_ID, CALL_ID);
		assertThat(settled.getStatus()).isEqualTo(PendingToolCall.REJECTED_ARGUMENTS);
		assertThat(settled.getCode()).isEqualTo(ErrorCodes.FORM_SLOTS_ALREADY_KNOWN);
		assertThat(settled.getOutcome()).isEqualTo("rejected");
		// ③ 模型仍收到纠正回执（含码与人话指引）：静默的是用户帧，不是给模型的事实
		String modelReceipt = toolResponseData(result);
		assertThat(modelReceipt).contains(ErrorCodes.FORM_SLOTS_ALREADY_KNOWN);
		assertThat(modelReceipt).contains("select_definitions");
	}

	@Test
	void unknownSlotFormStillReachesTheUser() {
		// 放行的表单在 park=true 下会挂起等人填：本批以 RunParkedException 交还线程（生产控制流）
		assertThatThrownBy(() -> manager(accepting()).executeToolCalls(prompt(), response()))
			.isInstanceOf(RunParkedException.class);

		// 修复不得过度：槽位未知/歧义时表单必须照常下发（挂起 + 请求帧 + 工具卡）
		assertThat(this.store.archivedTypes(RUN_ID))
			.as("未知配置仍要能用表单收集")
			.contains("suspended", "frontend_tool_request", "tool_start");
		assertThat(this.store.pending(RUN_ID, CALL_ID).getStatus()).isEqualTo(PendingToolCall.PENDING);
	}

	@Test
	void otherFrontendRejectionsStillShowTheirFailureCard() {
		this.manager(rejecting("FORM_SCHEMA_REJECTED")).executeToolCalls(prompt(), response());

		// 只有槽位放行那一个码静默；schema 被拒要让用户看见"这张表为什么没成"
		assertThat(this.store.archivedTypes(RUN_ID)).contains("tool_start", "frontend_tool_result");
		assertThat(this.store.pending(RUN_ID, CALL_ID).getCode()).isEqualTo("FORM_SCHEMA_REJECTED");
	}

	// ── 桩与辅助 ────────────────────────────────────────────────────────────

	private SpToolCallingManager manager(FrontendToolGuard guard) {
		ObjectProvider<ResumeService> provider = mock(ObjectProvider.class);
		ConfirmGate gate = new ConfirmGate(this.store, this.registry, this.properties, this.cancellations, this.beacon,
				List.of(), provider, Runnable::run, null, ConfirmGate.DEFAULT_RETRY_SCHEDULER);
		return new SpToolCallingManager(this.store, this.registry, gate, this.toolRegistry,
				mock(MessageJsonCodec.class), this.beacon, this.cancellations,
				new ToolResultLimiter(this.properties), List.of(guard));
	}

	private static FrontendToolGuard accepting() {
		return guard(null);
	}

	private static FrontendToolGuard rejecting(String code) {
		return guard(code);
	}

	private static FrontendToolGuard guard(String rejectCode) {
		return new FrontendToolGuard() {

			@Override
			public boolean supports(String toolName) {
				return "generative_form".equals(toolName);
			}

			@Override
			public Verdict inspectArguments(String argumentsJson) {
				return verdict();
			}

			@Override
			public Verdict inspectArguments(String toolName, String argumentsJson) {
				return verdict();
			}

			@Override
			public Verdict inspectArguments(String toolName, String argumentsJson, AiContext context) {
				return verdict();
			}

			@Override
			public Verdict inspectResult(String argumentsJson, String resultJson) {
				return Verdict.accept();
			}

			private Verdict verdict() {
				if (rejectCode == null) {
					return Verdict.accept();
				}
				return Verdict.reject(rejectCode, "表单未下发（零副作用）",
						List.of("已确定配置项：CURRENCY（系统解析，不是猜测）",
								"下一步：直接调用 select_definitions"));
			}
		};
	}

	private static String toolResponseData(ToolExecutionResult result) {
		return result.conversationHistory().stream()
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

	private static ChatResponse response() {
		AssistantMessage assistant = AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall(CALL_ID, "function", "generative_form",
					"{\"form\":{\"scenario\":\"FILTER\",\"fields\":[{\"key\":\"scope\",\"type\":\"text\"}]}}")))
			.build();
		return new ChatResponse(List.of(new Generation(assistant)));
	}

}
