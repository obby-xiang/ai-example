package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
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
 * <b>前端工具入参闸门必须早于挂起公告</b>（GF6 / G-13 / X-10 的 E2E 回归位）。
 *
 * <h2>被钉住的缺陷</h2>
 * 设计契约（{@code SpToolCallingManager} 类注）是"复核器不通过 ⇒ <b>不挂起、不下发</b>"：
 * 前端既不该看到非法 schema 的卡，也不该收到 {@code frontend_tool_request}。
 * park 批把整批请求帧提前公布（{@code publishRequestsForParkedBatch}）之后，入参闸门仍在
 * {@code awaitFrontend} 里才执行 —— 于是非法 {@code generative_form} 依然先拿到
 * {@code suspended} + {@code frontend_tool_request}，随后才被判 {@code REJECTED_ARGUMENTS}。
 * E2E 取证（本次 CI 的 gfc-GF6.sse.txt）逐帧可见：suspended(2) → frontend_tool_request(3) →
 * tool_start(4) → frontend_tool_result(rejected)(5) —— "已下发"这件事发生在拒绝之前。
 *
 * <h2>本批口径</h2>
 * 前端通道条目的入参复核<b>前移到挂起前</b>（与确认门的 F5 预检同一位置）：不通过即结算
 * {@code REJECTED_ARGUMENTS} 并<b>排除出 suspended</b>，因此不会公告、不会下发；
 * 结局帧仍在执行循环里成对产出（{@code tool_start} + {@code frontend_tool_result}），
 * 模型照样拿到结构化拒绝并可以改值重试。
 */
class FrontendGuardPreSuspendTest {

	private static final String RUN_ID = "run-fe-reject";

	private static final String SESSION_ID = "s-fe-reject";

	private static final String CALL_ID = "call-form-rejected";

	private static final String REJECT_CODE = "FORM_SCHEMA_REJECTED";

	private final ObjectMapper mapper = new ObjectMapper();

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolActivityBeacon beacon = new ToolActivityBeacon();

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final AiProperties properties = new AiProperties();

	private SpToolCallingManager manager;

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
		ObjectProvider<ResumeService> provider = mock(ObjectProvider.class);
		ConfirmGate gate = new ConfirmGate(this.store, this.registry, this.properties, this.cancellations, this.beacon,
				List.of(), provider, Runnable::run, null, ConfirmGate.DEFAULT_RETRY_SCHEDULER);
		this.manager = new SpToolCallingManager(this.store, this.registry, gate, this.toolRegistry,
				mock(MessageJsonCodec.class), this.beacon, this.cancellations,
				new ToolResultLimiter(this.properties), List.of(rejectingGuard()));
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setContext(AiContext.empty().toMap());
		this.store.save(snapshot);
	}

	@Test
	void rejectedFrontendArgumentsNeverEnterTheSuspendedBatchOrReachTheFrontend() {
		ToolExecutionResult result = this.manager.executeToolCalls(prompt(), response());

		assertThat(this.store.archivedTypes(RUN_ID))
			.as("非法入参不得公告挂起、不得下发请求帧（前端看不到这张卡）")
			.doesNotContain("suspended", "frontend_tool_request");
		assertThat(this.store.archivedTypes(RUN_ID))
			.as("拒绝仍要产出结局帧（ok=false + 结构化码），模型据此改值重试")
			.contains("frontend_tool_result");
		assertThat(this.store.pending(RUN_ID, CALL_ID).getStatus())
			.isEqualTo(PendingToolCall.REJECTED_ARGUMENTS);
		assertThat(this.store.pending(RUN_ID, CALL_ID).getCode()).isEqualTo(REJECT_CODE);
		assertThat(this.store.get(RUN_ID).getStatus())
			.as("被拒的调用不进入挂起态")
			.isNotEqualTo(RunSnapshot.SUSPENDED);
		ToolResponseMessage toolMessage = result.conversationHistory()
			.stream()
			.filter(ToolResponseMessage.class::isInstance)
			.map(ToolResponseMessage.class::cast)
			.findFirst()
			.orElseThrow();
		assertThat(toolMessage.getResponses()).hasSize(1);
		assertThat(toolMessage.getResponses().get(0).responseData())
			.as("模型收到的是工具结果（含拒绝码），不是「什么都没发生」")
			.contains(REJECT_CODE);
	}

	private static FrontendToolGuard rejectingGuard() {
		return new FrontendToolGuard() {

			@Override
			public boolean supports(String toolName) {
				return "generative_form".equals(toolName);
			}

			@Override
			public Verdict inspectArguments(String argumentsJson) {
				return reject();
			}

			@Override
			public Verdict inspectArguments(String toolName, String argumentsJson) {
				return reject();
			}

			@Override
			public Verdict inspectResult(String argumentsJson, String resultJson) {
				return Verdict.accept();
			}

			private Verdict reject() {
				return Verdict.reject(REJECT_CODE, "表单 schema 未通过白名单校验",
						List.of("fields[4].type=html 不在白名单类型内"));
			}
		};
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
					"{\"form\":{\"scenario\":\"FILTER\",\"fields\":[{\"key\":\"scope\",\"type\":\"html\"}]}}")))
			.build();
		return new ChatResponse(List.of(new Generation(assistant)));
	}

}
