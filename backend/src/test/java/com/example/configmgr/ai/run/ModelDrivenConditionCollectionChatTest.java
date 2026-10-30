package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiConfigService;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.AiProviderRegistry;
import com.example.configmgr.ai.config.ProviderRuntime;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.form.GenerativeFormRules;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.slot.DefinitionCatalog;
import com.example.configmgr.ai.slot.SlotDefinition;
import com.example.configmgr.ai.slot.TaskSlotResolver;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ContextBuilder;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.web.AiChatRequest;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>首轮入口</b>：停在 {@code QUERY_COND} 且用户在继续既有任务时，宿主<b>不再</b>抢在模型之前
 * 挂起筛选条件文本框 —— 这一拍模型必须被驱动，条件由模型理解用户原话后决定
 * （已给就 {@code set_condition}，确实缺失才发一次表单）。
 *
 * <p>旧入口会让这一轮在 {@code chat()} 里就停在"筛选条件（可选）"表单上，模型一次都没被问；
 * 同一句"导出cny货币字典"因此永远走不到 {@code set_condition}。
 *
 * <p><b>证红</b>：首轮仍接宿主条件表单 ⇒ 模型不被调用（{@code chatClient.prompt()} 零次），
 * 待决条目里多出 {@code generative_form}。
 */
class ModelDrivenConditionCollectionChatTest {

	private static final String RUN_ID = "run-model-cond-chat";

	private static final String SESSION_ID = "s-model-cond-chat";

	private static final long TASK_ID = 42L;

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

	private final DefinitionCatalog catalog = () -> List.of(new SlotDefinition("CURRENCY", "货币字典",
			List.of(new SlotDefinition.SlotField("code", "代码", true, List.of("CNY", "USD")))));

	private ScheduledExecutorService watchdogExecutor;

	private ResilientChatService service;

	@BeforeEach
	void setUp() {
		this.properties.getPark().setEnabled(true);
		this.watchdogExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "model-cond-chat-watchdog");
			thread.setDaemon(true);
			return thread;
		});
		this.service = new ResilientChatService(this.providerRegistry, this.chatMemory, this.codec, this.store,
				this.registry, this.toolRegistry, this.contextBuilder, this.sessionService, this.properties,
				this.cancellations, this.beacon, this.watchdogExecutor);
		this.service.setTaskSlotResolver(new TaskSlotResolver(this.catalog));
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

	@Test
	void queryCondTurnDrivesTheModelInsteadOfParkingAHostConditionForm() {
		AiChatRequest request = new AiChatRequest(SESSION_ID, "继续这个导出任务",
				new AiChatRequest.Context("export", "EXPORT", "QUERY_COND", TASK_ID, Map.of()), null, null);

		this.service.chat(RUN_ID, request, this.emitter);

		verify(this.chatClient).prompt();
		verify(this.requestSpec).user("继续这个导出任务");
		assertThat(this.store.pendingsLenient(RUN_ID)).as("首轮不得再挂宿主条件表单")
			.noneMatch(pending -> GenerativeFormRules.TOOL_NAME.equals(pending.getName()));
	}

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

}
