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
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
		when(this.streamSpec.chatResponse()).thenAnswer(invocation -> Flux.just(
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

	/**
	 * Task 1 第 5 项：进入 EXPORT、start_export 已披露、尚未发起时，收尾前补一次内部纠正，
	 * 要求模型发起确认卡；纠正请求带本轮全部历史（含第一次已上屏正文），
	 * 最终正文/落库/字符数与屏幕一致（两段都保留），且不自动执行。
	 */
	@Test
	void exportWrapUpGetsOneInternalCorrectionAskingForTheConfirmCard() {
		stubStartExportDisclosed();
		when(this.chatModel.stream(any(Prompt.class)))
			.thenReturn(Flux.just(response("第二段", "stop")));

		this.service.chat(RUN_ID, exportRequest(), this.emitter);

		org.mockito.ArgumentCaptor<Prompt> prompts = org.mockito.ArgumentCaptor
			.forClass(Prompt.class);
		verify(this.chatModel, times(1)).stream(prompts.capture());
		List<Message> instructions = prompts.getValue().getInstructions();
		assertThat(instructions).as("纠正历史含第一次已上屏的 assistant 正文")
			.anyMatch(message -> message instanceof AssistantMessage assistant && "好".equals(assistant.getText()));
		assertThat(instructions.get(instructions.size() - 1)).as("末尾是内部纠正指令")
			.isInstanceOf(UserMessage.class);
		assertThat(((UserMessage) instructions.get(instructions.size() - 1))
			.getText()).contains("内部纠正").contains("start_export").contains("不得自动执行");

		RunSnapshot snapshot = this.store.get(RUN_ID);
		assertThat(snapshot.getStatus()).isEqualTo(RunSnapshot.DONE);
		assertThat(snapshot.getFinalText()).as("两段正文都保留（不是只算第二次）").isEqualTo("好第二段");
		assertThat(lastAssistantText(snapshot)).isEqualTo("好第二段");
		verify(this.emitter).textEnd("好第二段".length());
		assertThat(AiContext.fromMap(snapshot.getContext()).getExtra())
			.containsEntry(ResilientChatService.EXPORT_CORRECTION_MARKER, Boolean.TRUE);
		assertThat(this.store.pendingsLenient(RUN_ID))
			.as("纠正只要求模型发起确认卡，宿主不自动执行")
			.noneMatch(pending -> "start_export".equals(pending.getName()));
	}

	/** 纠正后的第二次仍空：不得把已成功的第一次写成 FAILED。 */
	@Test
	void emptyReplyAfterTheCorrectionStillEndsDoneWithTheFirstText() {
		stubStartExportDisclosed();
		when(this.chatModel.stream(any(Prompt.class)))
			.thenReturn(Flux.just(response("", "stop")));

		this.service.chat(RUN_ID, exportRequest(), this.emitter);

		RunSnapshot snapshot = this.store.get(RUN_ID);
		assertThat(snapshot.getStatus()).isEqualTo(RunSnapshot.DONE);
		assertThat(snapshot.getFinalText()).isEqualTo("好");
	}

	/**
	 * 纠正失败：清掉纠正态、保留已上屏正文正常收尾；不得无退避重发，也不得重复同值 retry 帧。
	 */
	@Test
	void correctionFailureKeepsThePrefixAndDoesNotRetryTheSameRequest() {
		stubStartExportDisclosed();
		when(this.chatModel.stream(any(Prompt.class)))
			.thenReturn(Flux.error(new IllegalStateException("correction boom")));

		this.service.chat(RUN_ID, exportRequest(), this.emitter);

		RunSnapshot snapshot = this.store.get(RUN_ID);
		assertThat(snapshot.getStatus()).as("纠正失败不得把已成功的第一次写成 FAILED").isEqualTo(RunSnapshot.DONE);
		assertThat(snapshot.getFinalText()).isEqualTo("好");
		verify(this.emitter, never()).retry(any());
	}

	/**
	 * Important-1：纠正轮已经 saveSuspended（SUSPENDED + in-flight）后抛错，不得被纠正兜底写成 DONE；
	 * 必须保留挂起现场供续跑。
	 */
	@Test
	void correctionFailureAfterParkedToolRoundKeepsSuspended() {
		stubStartExportDisclosed();
		when(this.chatModel.stream(any(Prompt.class))).thenAnswer(invocation -> {
			RunSnapshot snapshot = this.store.get(RUN_ID);
			snapshot.setStatus(RunSnapshot.SUSPENDED);
			PendingToolCall pending = new PendingToolCall();
			pending.setToolCallId("call-start-export");
			pending.setName("start_export");
			pending.setKind(PendingToolCall.KIND_CONFIRM);
			pending.setStatus(PendingToolCall.PENDING);
			snapshot.setInFlightToolCalls(List.of(pending));
			this.store.save(snapshot);
			return Flux.error(new IllegalStateException("after suspend boom"));
		});

		this.service.chat(RUN_ID, exportRequest(), this.emitter);

		RunSnapshot snapshot = this.store.get(RUN_ID);
		assertThat(snapshot.getStatus()).isEqualTo(RunSnapshot.SUSPENDED);
		assertThat(snapshot.getInFlightToolCalls()).extracting(PendingToolCall::getToolCallId)
			.containsExactly("call-start-export");
		assertThat(snapshot.getFinalText()).as("不得写 DONE/最终正文覆盖挂起现场").isNull();
	}

	/**
	 * Important-2：纠正挂起后续跑按快照前缀 seed（只计数/最终记忆，不重发前缀），
	 * message_end.chars = 前缀 + 续跑段。
	 */
	@Test
	void resumedCorrectionSeedsTheSavedPrefixForCharsAndMemory() {
		stubStartExportDisclosed();
		AiContext context = AiContext.of("export", "EXPORT", "EXPORT", TASK_ID, "ACTIVE", Map.of());
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setContext(context.toMap());
		java.util.Map<String, Object> extra = new java.util.LinkedHashMap<>();
		extra.put(ResilientChatService.EXPORT_CORRECTION_MARKER, Boolean.TRUE);
		extra.put(ResilientChatService.EXPORT_CORRECTION_PREFIX_KEY, "好");
		snapshot.setContext(AiContext.of("export", "EXPORT", "EXPORT", TASK_ID, "ACTIVE", extra).toMap());
		snapshot.setMessageJson(this.codec.serializeAll(List.of(new org.springframework.ai.chat.messages.SystemMessage("PERSONA"),
				new UserMessage("创建并导出"), new AssistantMessage("好"))));
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		this.store.save(snapshot);
		when(this.providerRegistry.current()).thenReturn(runtime());
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(response("续跑段", "stop")));

		this.service.driveResumed(RUN_ID, SESSION_ID, context,
				List.of(new org.springframework.ai.chat.messages.SystemMessage("PERSONA"),
						new UserMessage("创建并导出"), new AssistantMessage("好")),
				this.emitter);

		assertThat(this.store.get(RUN_ID).getFinalText()).isEqualTo("好续跑段");
		verify(this.emitter).textEnd("好续跑段".length());
		List<Message> history = this.codec.deserializeAll(this.store.get(RUN_ID).getMessageJson());
		assertThat(history).filteredOn(message -> message instanceof AssistantMessage assistant
				&& assistant.getText() != null && assistant.getText().contains("好"))
			.as("旧前缀 assistant 必须被合并后的内容替换（不能重复）").hasSize(1);
		assertThat(lastAssistantText(this.store.get(RUN_ID))).isEqualTo("好续跑段");
	}

	/** Important-3：成功合并时移除旧前缀并把完整回答放到工具链之后（不重复、不挪回工具前）。 */
	@Test
	void successMergesPrefixAfterToolChainWithoutDuplicates() {
		stubStartExportDisclosed();
		when(this.chatModel.stream(any(Prompt.class))).thenAnswer(invocation -> {
			writePrefixThenToolHistory();
			return Flux.just(response("第二段", "stop"));
		});

		this.service.chat(RUN_ID, exportRequest(), this.emitter);

		List<Message> history = this.codec.deserializeAll(this.store.get(RUN_ID).getMessageJson());
		assertThat(history).filteredOn(message -> message instanceof AssistantMessage assistant
				&& assistant.getText() != null && assistant.getText().contains("好")).hasSize(1);
		assertThat(history.get(history.size() - 1)).isInstanceOf(AssistantMessage.class);
		assertThat(((AssistantMessage) history.get(history.size() - 1)).getText()).isEqualTo("好第二段");
		assertThat(history).anyMatch(message -> message instanceof org.springframework.ai.chat.messages.ToolResponseMessage);
	}

	/** Important-3：取消时不得追加重复前缀，合并后的停止消息在工具链之后。 */
	@Test
	void cancelMergesPrefixAfterToolChainWithoutDuplicates() {
		stubStartExportDisclosed();
		when(this.chatModel.stream(any(Prompt.class))).thenAnswer(invocation -> {
			writePrefixThenToolHistory();
			return Flux.error(new StreamViolationException(StreamViolationException.CANCELLED, "cancel"));
		});

		this.service.chat(RUN_ID, exportRequest(), this.emitter);

		List<Message> history = this.codec.deserializeAll(this.store.get(RUN_ID).getMessageJson());
		assertThat(history).filteredOn(message -> message instanceof AssistantMessage assistant
				&& assistant.getText() != null && assistant.getText().contains("好")).hasSize(1);
		assertThat(history.get(history.size() - 1)).isInstanceOf(AssistantMessage.class);
		assertThat(((AssistantMessage) history.get(history.size() - 1)).getText()).isEqualTo("好");
		verify(this.emitter).textEnd("好".length());
	}

	private void writePrefixThenToolHistory() {
		RunSnapshot snapshot = this.store.get(RUN_ID);
		AssistantMessage toolCall = AssistantMessage.builder().content("").toolCalls(List.of(
				new AssistantMessage.ToolCall("call-tool", "function", "set_condition", "{}"))).build();
		org.springframework.ai.chat.messages.ToolResponseMessage toolResult = org.springframework.ai.chat.messages.ToolResponseMessage
			.builder()
			.responses(List.of(new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse("call-tool",
					"set_condition", "{}")))
			.build();
		snapshot.setMessageJson(this.codec.serializeAll(List.of(new org.springframework.ai.chat.messages.SystemMessage("PERSONA"),
				new UserMessage("创建并导出"), new AssistantMessage("好"), toolCall, toolResult)));
		this.store.save(snapshot);
	}

	/** 纠正轮取消：正文仍保留前缀 + 当前段（textEnd 与前端拼接一致）。 */
	@Test
	void cancelDuringCorrectionStillKeepsPrefixPlusCurrentText() {
		stubStartExportDisclosed();
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.error(
				new StreamViolationException(StreamViolationException.CANCELLED, "用户在纠正轮取消")));

		this.service.chat(RUN_ID, exportRequest(), this.emitter);

		verify(this.emitter).textEnd("好".length());
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.CANCELLED);
	}

	/** 纠正轮 content_filter：正文仍保留前缀 + 当前段（textEnd 与前端拼接一致）。 */
	@Test
	void contentFilterDuringCorrectionStillKeepsPrefixPlusCurrentText() {
		stubStartExportDisclosed();
		when(this.chatModel.stream(any(Prompt.class)))
			.thenReturn(Flux.just(response("第二段", "content_filter")));

		this.service.chat(RUN_ID, exportRequest(), this.emitter);

		verify(this.emitter).textEnd("好第二段".length());
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.FAILED);
	}

	/** 纠正不占 maxAttempts：只有 1 次失败重试预算时仍能补这一次内部纠正。 */
	@Test
	void correctionDoesNotConsumeTheFailureAttemptBudget() {
		this.properties.getResilience().setMaxAttempts(1);
		stubStartExportDisclosed();
		when(this.chatModel.stream(any(Prompt.class)))
			.thenReturn(Flux.just(response("第二段", "stop")));

		this.service.chat(RUN_ID, exportRequest(), this.emitter);

		verify(this.chatModel, times(1)).stream(any(Prompt.class));
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.DONE);
	}

	/** content_filter 优先于纠正：明确失败不被一次"收尾纠正"掩盖。 */
	@Test
	void contentFilteredTurnIsNotCorrected() {
		stubStartExportDisclosed();
		when(this.streamSpec.chatResponse()).thenAnswer(invocation -> Flux
			.just(response("半截", "content_filter")));

		this.service.chat(RUN_ID, exportRequest(), this.emitter);

		verify(this.chatModel, never()).stream(any(Prompt.class));
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.FAILED);
	}

	/** length 优先于纠正：被 max_tokens 截断的正文按截断收尾，不再追加一轮纠正。 */
	@Test
	void lengthTruncatedTurnIsNotCorrected() {
		stubStartExportDisclosed();
		when(this.streamSpec.chatResponse()).thenAnswer(invocation -> Flux
			.just(response("半截正文", "length")));

		this.service.chat(RUN_ID, exportRequest(), this.emitter);

		verify(this.chatModel, never()).stream(any(Prompt.class));
		RunSnapshot snapshot = this.store.get(RUN_ID);
		assertThat(snapshot.getStatus()).isEqualTo(RunSnapshot.DONE);
		assertThat(snapshot.getFinalText()).isEqualTo("半截正文");
	}

	private void stubStartExportDisclosed() {
		ToolCallback startExport = mock(ToolCallback.class);
		ToolDefinition definition = mock(ToolDefinition.class);
		when(definition.name()).thenReturn("start_export");
		when(startExport.getToolDefinition()).thenReturn(definition);
		when(this.toolRegistry.forContext(any(AiContext.class))).thenReturn(List.of(startExport));
	}

	private static AiChatRequest exportRequest() {
		return new AiChatRequest(SESSION_ID, "创建并导出这个任务",
				new AiChatRequest.Context("export", "EXPORT", "EXPORT", TASK_ID, Map.of()), null, null);
	}

	private static ChatResponse response(String text, String finishReason) {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).build(),
				org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder().finishReason(finishReason)
					.build())));
	}

	private static String lastAssistantText(RunSnapshot snapshot) {
		List<Message> history = new MessageJsonCodec(new ObjectMapper())
			.deserializeAll(snapshot.getMessageJson());
		for (int i = history.size() - 1; i >= 0; i--) {
			if (history.get(i) instanceof AssistantMessage assistant) {
				return assistant.getText();
			}
		}
		return null;
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
