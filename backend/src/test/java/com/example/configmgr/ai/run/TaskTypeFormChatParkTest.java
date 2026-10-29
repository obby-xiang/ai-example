package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiConfigService;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.AiProviderRegistry;
import com.example.configmgr.ai.config.ProviderRuntime;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.form.ConditionsForm;
import com.example.configmgr.ai.form.GenerativeFormGuard;
import com.example.configmgr.ai.form.GenerativeFormRules;
import com.example.configmgr.ai.form.TaskTypeForm;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.slot.DefinitionCatalog;
import com.example.configmgr.ai.slot.TaskSlotResolver;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ContextBuilder;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.web.AiChatRequest;
import com.example.configmgr.ai.config.AiProviderRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>首轮接入点</b>：类型未知的"新建任务"话术，在<b>驱动模型之前</b>就被宿主接住 —— 这一拍模型一次
 * 都没被调用（因此不可能出现"要创建导出还是导入任务？"这类文字反问）。
 *
 * <h2>为什么测在 chat() 上</h2>
 * 生产 {@code park=true} 时，{@code create_task}/{@code navigate_to} 这些后续节点都在续跑侧，但
 * <b>类型未知</b>这一节点在首轮：这一轮连可执行的工具都没有（{@code create_task} 缺 type），
 * "先问类型"是它最短的路径。接点必须在驱动模型之前 —— 否则模型先说话，宿主就没有确定性可言。
 *
 * <h2>口径</h2>
 * <ol>
 * <li>类型未知 + 新建意图 ⇒ 宿主挂起类型单选，模型不被调用，快照进 SUSPENDED；</li>
 * <li>类型已给（"新建一个导出任务"）⇒ 照常驱动模型（直接进 create_task 的确认卡链路）；</li>
 * <li>普通浏览（打开既有任务）⇒ 照常驱动模型，不弹任何宿主表单。</li>
 * </ol>
 *
 * <p><b>证红</b>：首轮不接这张表 ⇒ 第 1 条红；判据不看槽位 ⇒ 第 2 条红；判据不看意图 ⇒ 第 3 条红。
 */
class TaskTypeFormChatParkTest {

	private static final String SESSION_ID = "s-type-chat";

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final AiProperties properties = new AiProperties();

	private final ChatModel chatModel = mock(ChatModel.class);

	private final ChatClient chatClient = mock(ChatClient.class);

	private final ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);

	private final ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);

	private final AiProviderRegistry providerRegistry = mock(AiProviderRegistry.class);

	private final ChatMemory chatMemory = mock(ChatMemory.class);

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final ContextBuilder contextBuilder = mock(ContextBuilder.class);

	private final AiSessionService sessionService = mock(AiSessionService.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private final SseChatEmitter emitter = mock(SseChatEmitter.class);

	private final RunRegistry registry = mock(RunRegistry.class);

	private final ConfirmGate gate = mock(ConfirmGate.class);

	private final DefinitionCatalog catalog = () -> List.of();

	private ScheduledExecutorService watchdogExecutor;

	private ResilientChatService service;

	@BeforeEach
	void setUp() {
		this.properties.getPark().setEnabled(true);
		this.watchdogExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "type-chat-watchdog");
			thread.setDaemon(true);
			return thread;
		});
		this.service = new ResilientChatService(this.providerRegistry, this.chatMemory, this.codec, this.store,
				this.registry, this.toolRegistry, this.contextBuilder, this.sessionService, this.properties,
				this.cancellations, this.beacon, this.watchdogExecutor);
		this.service.setTaskSlotResolver(new TaskSlotResolver(this.catalog));
		this.service.setTaskTypeForm(new TaskTypeForm(this.store, this.registry, this.gate, this.codec,
				this.properties));
		this.service.setConditionsForm(new ConditionsForm(this.store, this.registry, this.gate, this.codec,
				this.properties));
		stubFluentChain();
		when(this.cancellations.isCancelled(anyString())).thenReturn(false);
		when(this.cancellations.onCancel(anyString(), any())).thenReturn(() -> {
		});
		when(this.chatMemory.get(anyString())).thenReturn(List.of());
		when(this.contextBuilder.buildContextMessage(any())).thenReturn("workspace");
		when(this.sessionService.ensure(anyString())).thenReturn(true);
		when(this.toolRegistry.forContext(any(AiContext.class))).thenReturn(List.of());
		when(this.registry.of(anyString())).thenReturn(this.emitter);
		when(this.providerRegistry.target(org.mockito.ArgumentMatchers.nullable(String.class)))
			.thenReturn(Optional.of(new AiConfigService.ChatTarget(
					new AiConfigService.ResolvedProvider(7L, "deepseek", "http://placeholder",
							"/chat/completions", "deepseek-flash", "sk-test-0123456789", "fp", false),
					"deepseek-flash", true)));
		when(this.providerRegistry.allowUserParams()).thenReturn(true);
		when(this.providerRegistry.forRequest(any(), any(), any())).thenReturn(runtime());
		when(this.streamSpec.chatResponse()).thenAnswer(invocation -> reactor.core.publisher.Flux.just(
				new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("好").build(),
						org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder()
							.finishReason("stop")
							.build())))));
	}

	@AfterEach
	void tearDown() {
		this.watchdogExecutor.shutdownNow();
	}

	// ── ① 类型未知：宿主挂表，模型不被调用 ──────────────────────────────────

	@Test
	void typeUnknownNewTaskTurnParksBeforeTheModelIsCalled() {
		AiChatRequest request = new AiChatRequest(SESSION_ID, "帮我创建一个任务",
				new AiChatRequest.Context("tasks", null, null, null, Map.of()), null, null);

		this.service.chat(RUN_ID, request, this.emitter);

		// 模型一次都没被问 ⇒ 不可能出现类型文字反问
		verify(this.chatClient, never()).prompt();
		verify(this.chatModel, never()).stream(any(org.springframework.ai.chat.prompt.Prompt.class));

		PendingToolCall parked = this.store.pending(RUN_ID, TaskTypeForm.toolCallId(RUN_ID));
		assertThat(parked).as("类型未知 + 新建意图 ⇒ 宿主必须先挂类型单选").isNotNull();
		assertThat(parked.getName()).isEqualTo(GenerativeFormRules.TOOL_NAME);
		assertThat(parked.getKind()).isEqualTo(PendingToolCall.KIND_FRONTEND);
		assertThat(parked.getStatus()).isEqualTo(PendingToolCall.PENDING);
		JsonNode field = parse(parked.getArguments()).path("form").path("fields").get(0);
		assertThat(field.path("type").asText()).isEqualTo("enum");
		assertThat(field.path("options").findValuesAsText("value")).containsExactly("EXPORT", "IMPORT");

		RunSnapshot snapshot = this.store.get(RUN_ID);
		assertThat(snapshot.getStatus()).as("挂起态：这一轮在等人填表").isEqualTo(RunSnapshot.SUSPENDED);
		assertThat(snapshot.getInFlightToolCalls()).extracting(PendingToolCall::getToolCallId)
			.containsExactly(TaskTypeForm.toolCallId(RUN_ID));
		verify(this.emitter).suspended(eq(List.of(parked)), anyString(), anyString(), anyString());
		verify(this.emitter).completeSubscriptions();
	}

	// ── ② 已知信息/浏览：照常驱动模型，不弹宿主表单 ────────────────────────

	@Test
	void knownTypeOrBrowsingTurnStillDrivesTheModel() {
		AiChatRequest knownType = new AiChatRequest(SESSION_ID, "帮我新建一个导出任务",
				new AiChatRequest.Context("tasks", null, null, null, Map.of()), null, null);
		this.service.chat("run-known-type", knownType, this.emitter);

		verify(this.chatClient).prompt();
		assertThat(this.store.pendingsLenient("run-known-type"))
			.as("类型已在本轮话里给出 ⇒ 不弹类型表单（直接 create_task）")
			.noneMatch(pending -> pending.getToolCallId() != null
					&& pending.getToolCallId().startsWith(TaskTypeForm.TOOL_CALL_ID_PREFIX));

		AiChatRequest browsing = new AiChatRequest(SESSION_ID, "打开任务 7 看看",
				new AiChatRequest.Context("tasks", null, null, 7L, Map.of()), null, null);
		this.service.chat("run-browse", browsing, this.emitter);

		assertThat(this.store.pendingsLenient("run-browse"))
			.as("普通浏览不打扰").isEmpty();
	}

	// ── 桩与辅助 ────────────────────────────────────────────────────────────

	private static final String RUN_ID = "run-type-chat";

	private void stubFluentChain() {
		when(this.chatClient.prompt()).thenReturn(this.requestSpec);
		when(this.requestSpec.system(any(String.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.user(any(String.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.advisors(any(java.util.function.Consumer.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.toolContext(any(java.util.Map.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.toolCallbacks(any(java.util.List.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.stream()).thenReturn(this.streamSpec);
	}

	private ProviderRuntime runtime() {
		return new ProviderRuntime(7L, "deepseek", "deepseek-flash", "http://placeholder", "/chat/completions",
				"7|fp|u|p", null, null, this.chatModel, this.chatClient);
	}

	private JsonNode parse(String json) {
		try {
			return this.mapper.readTree(json);
		}
		catch (Exception ex) {
			throw new IllegalStateException("不是合法 JSON：" + json, ex);
		}
	}

}
