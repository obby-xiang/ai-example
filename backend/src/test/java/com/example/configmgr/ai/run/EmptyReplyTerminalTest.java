package com.example.configmgr.ai.run;

import com.example.configmgr.ai.conformance.FrameContract;
import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.AiProviderRegistry;
import com.example.configmgr.ai.config.ProviderRuntime;
import com.example.configmgr.ai.history.ConversationTranscriptStore;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ContextBuilder;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T3-7 用例（A12/A14）：<b>统一终态判定函数</b> + <b>空回复即失败</b>（口径变更）。
 *
 * <h2>A12 的三种形态（都要判 FAILED + {@code EMPTY_REPLY}）</h2>
 * <ol>
 * <li><b>空串</b>：上游只给终帧、正文 0 字符（静默截断的典型产物）；</li>
 * <li><b>纯空白</b>：正文只有空格/换行（去空白后为空）；</li>
 * <li><b>空文本但本轮用过工具</b>：快照历史里已有本轮的 {@code assistant(tool_calls)}+{@code tool} 配对
 *     （即这一轮<b>做过事</b>），最终仍无正文 —— "用过工具"不豁免"没答任何东西"。</li>
 * </ol>
 *
 * <p>另附非空回复回归（原 {@code DONE} 行为不变）与 {@link RunSnapshot#isTerminal(String)} 的参数化断言
 * （RUNNING/SUSPENDED/DONE/FAILED/CANCELLED/REJECTED/null/未知串）。
 *
 * <p>帧序契约沿用 {@link FrameContract}：空回复的终帧是 {@code error}（此前是 {@code done}），
 * 且若本轮开过正文段，必须先 {@code message_end} 再终帧。
 */
class EmptyReplyTerminalTest {

	private static final String RUN_ID = "run-empty-reply";

	private static final String SESSION_ID = "s-empty-reply";

	/**
	 * 记录"失败终态已落库"的瞬间：并发用例用它把取消线程精确放到"写完 FAILED、还没发 error"这个窗口，
	 * 从而区分"锁只包住写"与"锁包住写 + 终帧"两种实现。
	 */
	private static final class FailureSignalingStore extends InMemoryRunStore {

		private final CountDownLatch failedWritten = new CountDownLatch(1);

		@Override
		public boolean saveTerminalUnlessCancelled(RunSnapshot snapshot) {
			boolean written = super.saveTerminalUnlessCancelled(snapshot);
			if (written && RunSnapshot.FAILED.equals(snapshot.getStatus())) {
				this.failedWritten.countDown();
			}
			return written;
		}

		@Override
		public java.util.Set<String> sessionRunIds(String sessionId) {
			return java.util.Set.of("existing-run");
		}

	}

	private final FailureSignalingStore store = new FailureSignalingStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunRegistry registry = TestRunRegistryFactory.create(this.store, this.mapper, null);

	private final AiProperties properties = new AiProperties();

	private final ChatModel chatModel = mock(ChatModel.class);

	/** 注册表桩：取用即返回同一个运行态（模型 = 下面那个 ChatModel 桩）。 */
	private final AiProviderRegistry providerRegistry = mock(AiProviderRegistry.class);

	private final ConversationTranscriptStore transcript = mock(ConversationTranscriptStore.class);

	private final ProviderRuntime runtime = new ProviderRuntime(null, "test-provider", "test-model",
			"http://localhost", "/chat/completions", "test|runtime", null, null, this.chatModel,
			mock(ChatClient.class));

	private final ChatMemory chatMemory = mock(ChatMemory.class);

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final ContextBuilder contextBuilder = mock(ContextBuilder.class);

	private final AiSessionService sessionService = mock(AiSessionService.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private ScheduledExecutorService watchdogExecutor;

	private ResilientChatService service;

	private RequestContextUtils.SafeCloseable identity;

	@BeforeEach
	void setUp() {
		this.identity = RequestContextUtils.useContext(1L, 101L);
		when(this.providerRegistry.current()).thenReturn(this.runtime);
		when(this.providerRegistry.tryCurrent()).thenReturn(java.util.Optional.of(this.runtime));
		when(this.providerRegistry.target(any(), eq("test-model"))).thenReturn(java.util.Optional.of(
				new com.example.configmgr.ai.config.AiConfigService.ChatTarget(
						new com.example.configmgr.ai.config.AiConfigService.ResolvedProvider(null, "test-provider",
								"http://localhost", "/chat/completions", "test-model", "", "fp", false),
						"test-model", false)));
		when(this.providerRegistry.forFrozenRequest(any(), eq("test-model"), any(), any(), anyBoolean()))
			.thenReturn(this.runtime);
		when(this.providerRegistry.forFrozenRequest(any(), eq("test-model"), any(), any(), anyBoolean(), any(), any()))
			.thenReturn(this.runtime);
		this.watchdogExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "t3b-empty-reply-watchdog");
			thread.setDaemon(true);
			return thread;
		});
		// 模型配置专项批①：模型侧改成从注册表取用（ProviderRuntime 包住 api/model/client 三件套）
		this.service = new ResilientChatService(this.providerRegistry, this.chatMemory, this.codec,
				this.store, this.registry, this.toolRegistry, this.contextBuilder, this.sessionService,
				this.properties, this.cancellations, this.beacon, this.watchdogExecutor);
		org.springframework.test.util.ReflectionTestUtils.setField(this.service, "transcript", this.transcript);
		when(this.transcript.append(anyString(), anyLong(), anyLong(), anyList(), anyLong())).thenReturn(true);
		when(this.transcript.readPage(anyString(), anyLong(), anyLong(), any(), eq(500)))
			.thenReturn(ConversationTranscriptStore.ReadPage.EMPTY);
		when(this.cancellations.cancelState(RUN_ID)).thenReturn(com.example.configmgr.ai.run.CancellationRegistry.CancelState.NOT_CANCELLED);
		when(this.cancellations.onCancel(anyString(), any(Runnable.class))).thenReturn(() -> {
		});
		when(this.toolRegistry.forContext(any(AiContext.class))).thenReturn(List.of());
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setModelId("test-model");
		snapshot.setTenantId(1L);
		snapshot.setUserId(101L);
		long now = System.currentTimeMillis();
		snapshot.setCreatedAtMs(now);
		snapshot.setTurnStartedAtMs(now - 1L);
		snapshot.setContext(AiContext.empty().toMap());
		this.store.save(snapshot);
	}

	@org.junit.jupiter.api.AfterEach
	void tearDown() {
		this.watchdogExecutor.shutdownNow();
		this.identity.close();
	}

	// ── ① 空串 → FAILED + EMPTY_REPLY ──

	@Test
	void emptyTextTerminalBecomesFailedWithEmptyReplyCode() {
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(terminal("stop")));

		FrameWire wire = runTurn();

		List<Map<String, Object>> frames = clientView(wire);
		assertThat(FrameContract.types(frames)).as("空回复先按 AR-05 仅模型步重试一次（retry 帧），仍空才发 error 终帧")
				.containsExactly("start", "retry", "error");
		assertThat(FrameContract.oneOfType(frames, "retry")).as("重试是一次为限的模型步重试")
				.containsEntry("code", ResilientChatService.EMPTY_REPLY_CODE)
				.containsEntry("emptyReply", true);
		assertThat(FrameContract.oneOfType(frames, "error"))
				.containsEntry("code", ResilientChatService.EMPTY_REPLY_CODE)
				.doesNotContainKey("truncated");
		FrameContract.assertConformant(frames);

		RunSnapshot snapshot = this.store.get(RUN_ID);
		assertThat(snapshot.getStatus()).isEqualTo(RunSnapshot.FAILED);
		assertThat(snapshot.getError()).contains(ResilientChatService.EMPTY_REPLY_CODE);
		assertThat(snapshot.getFinalText()).isEqualTo("");
		assertThat(snapshot.getInFlightToolCalls()).isEmpty();
	}

	@Test
	void emptyLengthReplyFailsAsTruncatedWithoutSameRequestRetry() {
		this.properties.getResilience().setMaxAttempts(3);
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(terminal("length")));

		FrameWire wire = runTurn();

		List<Map<String, Object>> frames = clientView(wire);
		assertThat(FrameContract.types(frames)).containsExactly("start", "error");
		assertThat(FrameContract.oneOfType(frames, "error"))
			.containsEntry("code", ResilientChatService.EMPTY_REPLY_CODE)
			.containsEntry("truncated", true)
			.containsEntry("terminalSignal", "finishReason=length");
		verify(this.chatModel).stream(any(Prompt.class));
		RunSnapshot snapshot = this.store.get(RUN_ID);
		assertThat(snapshot.getStatus()).isEqualTo(RunSnapshot.FAILED);
		assertThat(snapshot.getError()).contains(ResilientChatService.EMPTY_REPLY_CODE);
		assertThat(snapshot.getFinalText()).isEmpty();
	}

	@Test
	void firstTurnEmptyLengthUsesConfiguredRecovery() {
		org.springframework.ai.openai.OpenAiChatOptions initialOptions =
				org.springframework.ai.openai.OpenAiChatOptions.builder().maxTokens(8192).reasoningEffort("high")
					.extraBody(Map.of("thinking", Map.of("type", "enabled"))).build();
		org.springframework.ai.openai.OpenAiChatOptions recoveryOptions =
				org.springframework.ai.openai.OpenAiChatOptions.builder().maxTokens(8192).reasoningEffort("low")
					.extraBody(Map.of("thinking", Map.of("type", "disabled"))).build();
		ChatClient initialClient = chatClient(Flux.just(terminal("length")));
		ChatClient recoveryClient = chatClient(Flux.just(chunk("首轮恢复正文"), terminal("stop")));
		ProviderRuntime initialRuntime = new ProviderRuntime(null, "test-provider", "test-model", "http://localhost",
				"/chat/completions", "test|runtime", initialOptions, null, this.chatModel, initialClient);
		ChatModel recoveryModel = mock(ChatModel.class);
		when(recoveryModel.getDefaultOptions()).thenReturn(recoveryOptions);
		when(recoveryModel.stream(any(Prompt.class)))
			.thenReturn(Flux.just(chunk("首轮恢复正文"), terminal("stop")));
		ProviderRuntime recoveryRuntime = new ProviderRuntime(null, "test-provider", "test-model", "http://localhost",
				"/chat/completions", "test|runtime", recoveryOptions, null, recoveryModel, recoveryClient);
		when(this.providerRegistry.current()).thenReturn(initialRuntime);
		when(this.providerRegistry.tryCurrent()).thenReturn(java.util.Optional.of(initialRuntime));
		com.example.configmgr.ai.config.EmptyLengthReasoningRecovery policy =
				new com.example.configmgr.ai.config.EmptyLengthReasoningRecovery("low",
						Map.of("thinking", Map.of("type", "disabled")));
		when(this.providerRegistry.emptyLengthReasoningRecovery(null, "test-model")).thenReturn(policy);
		when(this.providerRegistry.forReasoningRecovery(initialRuntime, policy)).thenReturn(recoveryRuntime);
		List<Message> beforeAttempt = List.of(new UserMessage("历史问题"), AssistantMessage.builder().content("历史回答")
			.properties(Map.of(ResilientChatService.REASONING_METADATA_KEY, "保留的历史推理")).build());
		when(this.chatMemory.get(SESSION_ID)).thenReturn(beforeAttempt);
		SseChatEmitter out = this.registry.of(RUN_ID);
		FrameWire wire = FrameWire.attachTo(out);

		this.service.chat(RUN_ID, new com.example.configmgr.ai.web.AiChatRequest(SESSION_ID, "你好", null, null, null), out);

		assertThat(FrameContract.types(clientView(wire))).containsExactly("start", "retry", "message_start", "delta",
				"message_end", "done");
		RunSnapshot snapshot = this.store.get(RUN_ID);
		assertThat(snapshot.getStatus()).isEqualTo(RunSnapshot.DONE);
		assertThat(snapshot.getReasoningEffort()).isEqualTo("high");
		assertThat(snapshot.getEmptyLengthReasoningRecovery()).isEqualTo(policy);
		assertThat(snapshot.getMaxTokens()).isEqualTo(8192);
		verify(this.chatMemory, never()).add(SESSION_ID, beforeAttempt);
	}

	@Test
	void productionMemoryAndChatClientRecoveryUsesOnlyFrozenHistoryAndPreservesOtherRuns() {
		RunBlocksRepository repository = new RunBlocksRepository();
		UserMessage otherRunUser = new UserMessage("其他run的问题");
		AssistantMessage otherRunAssistant = AssistantMessage.builder().content("其他run的回答")
			.properties(Map.of(ResilientChatService.REASONING_METADATA_KEY, "其他run的推理")).build();
		repository.window.add(otherRunUser);
		repository.window.add(otherRunAssistant);
		com.example.configmgr.ai.memory.PerRunChatMemory productionMemory = new com.example.configmgr.ai.memory.PerRunChatMemory(
				org.springframework.ai.chat.memory.MessageWindowChatMemory.builder()
					.chatMemoryRepository(repository).maxMessages(100).build(), repository, 100);
		org.springframework.ai.openai.OpenAiChatOptions initialOptions =
				org.springframework.ai.openai.OpenAiChatOptions.builder().maxTokens(8192).reasoningEffort("high").build();
		org.springframework.ai.openai.OpenAiChatOptions recoveryOptions =
				org.springframework.ai.openai.OpenAiChatOptions.builder().maxTokens(8192).reasoningEffort("low").build();
		RecordingChatModel initialModel = new RecordingChatModel(initialOptions, Flux.just(terminal("length")));
		RecordingChatModel recoveryModel = new RecordingChatModel(recoveryOptions,
				Flux.just(chunk("恢复正文"), terminal("stop")));
		ChatClient initialClient = ChatClient.builder(initialModel)
			.defaultAdvisors(new com.example.configmgr.ai.memory.IdentityBoundChatMemoryAdvisor(productionMemory)).build();
		ChatClient recoveryClient = ChatClient.builder(recoveryModel)
			.defaultAdvisors(new com.example.configmgr.ai.memory.IdentityBoundChatMemoryAdvisor(productionMemory)).build();
		ProviderRuntime initialRuntime = new ProviderRuntime(null, "test-provider", "test-model", "http://localhost",
				"/chat/completions", "test|runtime", initialOptions, null, initialModel, initialClient);
		ProviderRuntime recoveryRuntime = new ProviderRuntime(null, "test-provider", "test-model", "http://localhost",
				"/chat/completions", "test|runtime", recoveryOptions, null, recoveryModel, recoveryClient);
		com.example.configmgr.ai.config.EmptyLengthReasoningRecovery policy =
				new com.example.configmgr.ai.config.EmptyLengthReasoningRecovery("low", Map.of());
		when(this.providerRegistry.current()).thenReturn(initialRuntime);
		when(this.providerRegistry.tryCurrent()).thenReturn(java.util.Optional.of(initialRuntime));
		when(this.providerRegistry.emptyLengthReasoningRecovery(null, "test-model")).thenReturn(policy);
		when(this.providerRegistry.forReasoningRecovery(initialRuntime, policy)).thenReturn(recoveryRuntime);
		ResilientChatService productionService = new ResilientChatService(this.providerRegistry, productionMemory,
				repository, this.codec, this.store, this.registry, this.toolRegistry, this.contextBuilder,
				this.sessionService, this.properties, this.cancellations, this.beacon, this.watchdogExecutor);
		org.springframework.test.util.ReflectionTestUtils.setField(productionService, "transcript", this.transcript);
		SseChatEmitter out = this.registry.of(RUN_ID);

		productionService.chat(RUN_ID, new com.example.configmgr.ai.web.AiChatRequest(SESSION_ID, "本轮问题", null, null,
				null), out);

		assertThat(initialModel.prompts).hasSize(1);
		assertThat(recoveryModel.prompts).hasSize(1);
		List<Message> secondUpstream = recoveryModel.prompts.getFirst().getInstructions();
		assertThat(secondUpstream).hasSize(4);
		assertThat(secondUpstream.getFirst()).isInstanceOf(org.springframework.ai.chat.messages.SystemMessage.class);
		assertThat(secondUpstream.subList(1, 4)).extracting(Message::getText)
			.containsExactly("其他run的问题", "其他run的回答", "本轮问题");
		assertThat(secondUpstream).filteredOn(AssistantMessage.class::isInstance)
			.noneMatch(message -> ((AssistantMessage) message).getText().isEmpty());
		assertThat(secondUpstream).filteredOn(UserMessage.class::isInstance)
			.filteredOn(message -> "本轮问题".equals(message.getText())).hasSize(1);
		assertThat(repository.window).filteredOn(message -> message == otherRunUser || message == otherRunAssistant)
			.hasSize(2);
		assertThat(repository.deleteCalls).isZero();
	}

	@Test
	void emptyLengthUsesTheFrozenReasoningRecoveryWithoutChangingMaxTokens() throws Exception {
		this.properties.getResilience().setMaxAttempts(3);
		org.springframework.ai.openai.OpenAiChatOptions initialOptions =
				org.springframework.ai.openai.OpenAiChatOptions.builder().maxTokens(8192).reasoningEffort("high")
					.extraBody(Map.of("thinking", Map.of("type", "enabled"))).build();
		org.springframework.ai.openai.OpenAiChatOptions recoveryOptions =
				org.springframework.ai.openai.OpenAiChatOptions.builder().maxTokens(8192).reasoningEffort("low")
					.extraBody(Map.of("thinking", Map.of("type", "disabled"))).build();
		when(this.chatModel.getDefaultOptions()).thenReturn(initialOptions);
		ChatModel recoveryModel = mock(ChatModel.class);
		when(recoveryModel.getDefaultOptions()).thenReturn(recoveryOptions);
		ProviderRuntime initialRuntime = new ProviderRuntime(null, "test-provider", "test-model", "http://localhost",
				"/chat/completions", "test|runtime", initialOptions, null, this.chatModel, mock(ChatClient.class));
		ProviderRuntime recoveryRuntime = new ProviderRuntime(null, "test-provider", "test-model", "http://localhost",
				"/chat/completions", "test|runtime", recoveryOptions, null, recoveryModel, mock(ChatClient.class));
		when(this.providerRegistry.forFrozenRequest(any(), eq("test-model"), any(), any(), anyBoolean()))
			.thenReturn(initialRuntime);
		when(this.providerRegistry.forFrozenRequest(any(), eq("test-model"), any(), any(), anyBoolean(), any()))
			.thenReturn(initialRuntime);
		when(this.providerRegistry.forFrozenRequest(any(), eq("test-model"), any(), any(), anyBoolean(), any(), any()))
			.thenReturn(initialRuntime);
		when(this.providerRegistry.forReasoningRecovery(any(), any())).thenReturn(recoveryRuntime);
		RunSnapshot seeded = this.store.get(RUN_ID);
		seeded.freezeParams(new RunSnapshot.FrozenParams(null, null, 8192, false));
		seeded.freezeRequestExtraBody(initialOptions.getExtraBody());
		this.mapper.readerForUpdating(seeded).readValue("{\"reasoningEffort\":\"high\",\"emptyLengthReasoningRecovery\":{"
				+ "\"reasoningEffort\":\"low\",\"requestExtraBody\":{\"thinking\":{\"type\":\"disabled\"}}}}");
		this.store.save(seeded);
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(terminal("length")));
		when(recoveryModel.stream(any(Prompt.class)))
			.thenReturn(Flux.just(chunk("恢复后的正文"), terminal("stop")));

		FrameWire wire = runTurn();

		List<Map<String, Object>> frames = clientView(wire);
		assertThat(FrameContract.types(frames)).containsExactly("start", "retry", "message_start", "delta",
				"message_end", "done");
		assertThat(FrameContract.oneOfType(frames, "retry")).containsEntry("emptyLengthReasoningRecovery", true);
		org.mockito.ArgumentCaptor<Prompt> initialPrompt = org.mockito.ArgumentCaptor.forClass(Prompt.class);
		org.mockito.ArgumentCaptor<Prompt> recoveryPrompt = org.mockito.ArgumentCaptor.forClass(Prompt.class);
		verify(this.chatModel).stream(initialPrompt.capture());
		verify(recoveryModel).stream(recoveryPrompt.capture());
		org.springframework.ai.openai.OpenAiChatOptions initialWire =
				(org.springframework.ai.openai.OpenAiChatOptions) initialPrompt.getValue().getOptions();
		org.springframework.ai.openai.OpenAiChatOptions recoveryWire =
				(org.springframework.ai.openai.OpenAiChatOptions) recoveryPrompt.getValue().getOptions();
		assertThat(initialWire.getReasoningEffort()).isEqualTo("high");
		assertThat(recoveryWire.getReasoningEffort()).isEqualTo("low");
		assertThat(recoveryWire.getMaxTokens()).isEqualTo(initialWire.getMaxTokens()).isEqualTo(8192);
		assertThat(recoveryPrompt.getValue().getInstructions()).isEqualTo(initialPrompt.getValue().getInstructions());
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.DONE);
		assertThat(this.store.get(RUN_ID).getFinalText()).isEqualTo("恢复后的正文");
		assertThat(this.codec.deserializeAll(this.store.get(RUN_ID).getMessageJson()))
			.filteredOn(AssistantMessage.class::isInstance)
			.singleElement()
			.satisfies(message -> assertThat(((AssistantMessage) message).getText()).isEqualTo("恢复后的正文"));
	}

	@Test
	void recoveryFramesExposeBothUsagesAndFrozenPolicies() throws Exception {
		ChatModel recoveryModel = configureRecovery();
		when(this.chatModel.stream(any(Prompt.class)))
			.thenReturn(Flux.just(terminal("length", 11, 8192, 8192)));
		when(recoveryModel.stream(any(Prompt.class)))
			.thenReturn(Flux.just(chunk("正文"), terminal("stop", 13, 21, 5)));

		FrameWire wire = runTurn();

		List<Map<String, Object>> frames = clientView(wire);
		Map<String, Object> retry = FrameContract.oneOfType(frames, "retry");
		assertThat(retry).containsEntry("emptyLengthReasoningRecovery", true);
		assertThat((Map<String, Object>) retry.get("usage"))
			.containsEntry("completionTokens", 8192)
			.containsEntry("reasoningTokens", 8192);
		assertThat((Map<String, Object>) retry.get("initialReasoningPolicy"))
			.containsEntry("reasoningEffort", "high");
		assertThat((Map<String, Object>) retry.get("recoveryReasoningPolicy"))
			.containsEntry("reasoningEffort", "low");
		Map<String, Object> done = FrameContract.oneOfType(frames, "done");
		assertThat(done).containsEntry("emptyLengthReasoningRecovery", true);
		assertThat((Map<String, Object>) done.get("usage"))
			.containsEntry("completionTokens", 21)
			.containsEntry("reasoningTokens", 5);
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.DONE);
		assertThat(this.store.get(RUN_ID).getAttempts()).isEqualTo(2);
	}

	@Test
	void nonEmptyLengthAfterRecoveryStillEndsDoneAndTruncated() throws Exception {
		ChatModel recoveryModel = configureRecovery();
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(terminal("length")));
		when(recoveryModel.stream(any(Prompt.class)))
			.thenReturn(Flux.just(chunk("恢复后半截"), terminal("length")));

		FrameWire wire = runTurn();

		Map<String, Object> done = FrameContract.oneOfType(clientView(wire), "done");
		assertThat(done).containsEntry("truncated", true).containsEntry("emptyLengthReasoningRecovery", true);
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.DONE);
	}

	@Test
	void emptyLengthRecoveryFailureStillEndsAsTruncatedEmptyReply() throws Exception {
		this.properties.getResilience().setMaxAttempts(4);
		ChatModel recoveryModel = configureRecovery();
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(terminal("length")));
		when(recoveryModel.stream(any(Prompt.class))).thenReturn(Flux.just(terminal("length")));

		FrameWire wire = runTurn();

		List<Map<String, Object>> frames = clientView(wire);
		assertThat(FrameContract.types(frames)).containsExactly("start", "retry", "error");
		assertThat(FrameContract.oneOfType(frames, "error"))
			.containsEntry("code", ResilientChatService.EMPTY_REPLY_CODE)
			.containsEntry("truncated", true)
			.containsEntry("emptyLengthReasoningRecovery", true);
		verify(this.chatModel).stream(any(Prompt.class));
		verify(recoveryModel).stream(any(Prompt.class));
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.FAILED);
	}

	@Test
	void visibleReasoningBeforeEmptyLengthBlocksRecovery() throws Exception {
		ChatModel recoveryModel = configureRecovery();
		this.properties.getReasoning().setDisplayEnabled(true);
		when(this.chatModel.stream(any(Prompt.class)))
			.thenReturn(Flux.just(reasoning("可见思考"), terminal("length")));

		FrameWire wire = runTurn();

		assertThat(FrameContract.types(clientView(wire))).doesNotContain("retry").contains("error");
		verify(recoveryModel, never()).stream(any(Prompt.class));
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.FAILED);
	}

	@Test
	void toolCallBeforeEmptyLengthBlocksRecovery() throws Exception {
		ChatModel recoveryModel = configureRecovery();
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(toolCallTerminal("length")));

		FrameWire wire = runTurn();

		assertThat(FrameContract.types(clientView(wire))).doesNotContain("retry").contains("error");
		verify(recoveryModel, never()).stream(any(Prompt.class));
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.FAILED);
	}

	@Test
	void sideEffectBeforeEmptyLengthBlocksRecovery() throws Exception {
		ChatModel recoveryModel = configureRecovery();
		when(this.beacon.startedCount(RUN_ID)).thenReturn(0L, 1L);
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(terminal("length")));

		FrameWire wire = runTurn();

		assertThat(FrameContract.types(clientView(wire))).doesNotContain("retry").contains("error");
		verify(recoveryModel, never()).stream(any(Prompt.class));
	}

	@Test
	void pendingToolRoundBeforeEmptyLengthBlocksRecovery() throws Exception {
		ChatModel recoveryModel = configureRecovery();
		RunSnapshot pending = this.store.get(RUN_ID);
		pending.setStatus(RunSnapshot.SUSPENDED);
		PendingToolCall call = new PendingToolCall();
		call.setToolCallId("call-pending");
		call.setName("list_config_defs");
		call.setArguments("{}");
		pending.setInFlightToolCalls(List.of(call));
		this.store.save(pending);
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(terminal("length")));

		FrameWire wire = runTurn();

		assertThat(FrameContract.types(clientView(wire))).doesNotContain("retry").contains("error");
		verify(recoveryModel, never()).stream(any(Prompt.class));
	}

	@Test
	void exhaustedTotalBudgetBlocksRecovery() throws Exception {
		ChatModel recoveryModel = configureRecovery();
		this.properties.getResilience().setTotalBudget(java.time.Duration.ZERO);
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(terminal("length")));

		FrameWire wire = runTurn();

		assertThat(FrameContract.types(clientView(wire))).doesNotContain("retry").contains("error");
		verify(recoveryModel, never()).stream(any(Prompt.class));
	}

	@Test
	void exhaustedAttemptBudgetBlocksRecovery() throws Exception {
		ChatModel recoveryModel = configureRecovery();
		this.properties.getResilience().setMaxAttempts(1);
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(terminal("length")));

		FrameWire wire = runTurn();

		assertThat(FrameContract.types(clientView(wire))).doesNotContain("retry").contains("error");
		verify(recoveryModel, never()).stream(any(Prompt.class));
	}

	@Test
	void identicalRecoveryPolicyKeepsSingleCallFailure() throws Exception {
		ChatModel recoveryModel = configureRecovery();
		when(this.providerRegistry.forReasoningRecovery(any(), any()))
			.thenAnswer(invocation -> invocation.getArgument(0));
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(terminal("length")));

		FrameWire wire = runTurn();

		assertThat(FrameContract.types(clientView(wire))).containsExactly("start", "error");
		verify(recoveryModel, never()).stream(any(Prompt.class));
		verify(this.chatModel).stream(any(Prompt.class));
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.FAILED);
	}

	@Test
	void cancelDuringRecoveryBackoffPreventsSecondUpstream() throws Exception {
		ChatModel recoveryModel = configureRecovery();
		when(this.cancellations.cancelState(RUN_ID)).thenReturn(CancellationRegistry.CancelState.CANCELLED);
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(terminal("length")));

		FrameWire wire = runTurn();

		assertThat(FrameContract.oneOfType(clientView(wire), "done")).containsEntry("cancelled", true);
		verify(recoveryModel, never()).stream(any(Prompt.class));
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.CANCELLED);
	}

	@Test
	void cancelDuringRecoveryExecutionKeepsCancelledTerminal() throws Exception {
		ChatModel recoveryModel = configureRecovery();
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(terminal("length")));
		when(recoveryModel.stream(any(Prompt.class))).thenReturn(Flux.error(
				new StreamViolationException(StreamViolationException.CANCELLED, "cancel during recovery")));

		FrameWire wire = runTurn();

		assertThat(FrameContract.oneOfType(clientView(wire), "done")).containsEntry("cancelled", true);
		verify(recoveryModel).stream(any(Prompt.class));
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.CANCELLED);
	}

	@Test
	void whitespaceOnlyLengthDoesNotRecover() throws Exception {
		ChatModel recoveryModel = configureRecovery();
		when(this.chatModel.stream(any(Prompt.class)))
			.thenReturn(Flux.just(chunk(" \n"), terminal("length")));

		FrameWire wire = runTurn();

		List<Map<String, Object>> frames = clientView(wire);
		assertThat(FrameContract.types(frames)).doesNotContain("retry").contains("error");
		assertThat(FrameContract.oneOfType(frames, "error")).containsEntry("truncated", true);
		verify(recoveryModel, never()).stream(any(Prompt.class));
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.FAILED);
	}

	@Test
	void emptyStopFollowedByLengthKeepsLatchedStopSignalAndMarksTruncated() {
		this.properties.getResilience().setMaxAttempts(3);
		when(this.chatModel.stream(any(Prompt.class)))
			.thenReturn(Flux.just(terminal("stop"), terminal("length")));

		FrameWire wire = runTurn();

		List<Map<String, Object>> frames = clientView(wire);
		assertThat(FrameContract.types(frames)).containsExactly("start", "error");
		assertThat(FrameContract.oneOfType(frames, "error"))
			.containsEntry("code", ResilientChatService.EMPTY_REPLY_CODE)
			.containsEntry("truncated", true)
			.containsEntry("terminalSignal", "finishReason=stop");
		verify(this.chatModel).stream(any(Prompt.class));
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.FAILED);
	}

	@Test
	void nonEmptyLengthStillEndsWithDoneAndTruncated() {
		this.properties.getResilience().setMaxAttempts(3);
		when(this.chatModel.stream(any(Prompt.class)))
			.thenReturn(Flux.just(chunk("半截正文"), terminal("length")));

		FrameWire wire = runTurn();

		List<Map<String, Object>> frames = clientView(wire);
		assertThat(FrameContract.types(frames))
			.containsExactly("start", "message_start", "delta", "message_end", "done");
		assertThat(FrameContract.oneOfType(frames, "done"))
			.containsEntry("truncated", true)
			.containsEntry("terminalSignal", "finishReason=length");
		verify(this.chatModel).stream(any(Prompt.class));
		RunSnapshot snapshot = this.store.get(RUN_ID);
		assertThat(snapshot.getStatus()).isEqualTo(RunSnapshot.DONE);
		assertThat(snapshot.getFinalText()).isEqualTo("半截正文");
	}

	@Test
	void nonEmptyLengthWithRecoveryPolicyStillEndsDoneWithoutRecovery() throws Exception {
		ChatModel recoveryModel = configureRecovery();
		when(this.chatModel.stream(any(Prompt.class)))
			.thenReturn(Flux.just(chunk("半截正文"), terminal("length")));

		FrameWire wire = runTurn();

		List<Map<String, Object>> frames = clientView(wire);
		assertThat(FrameContract.types(frames)).containsExactly("start", "message_start", "delta", "message_end",
				"done");
		assertThat(FrameContract.oneOfType(frames, "done")).containsEntry("truncated", true)
			.doesNotContainKey("emptyLengthReasoningRecovery");
		verify(recoveryModel, never()).stream(any(Prompt.class));
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.DONE);
	}

	// ── ② 纯空白 → FAILED + EMPTY_REPLY（且已开的正文段先收尾） ──

	@Test
	void whitespaceOnlyTextAlsoBecomesFailed() {
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(
				Flux.just(chunk(" "), chunk("\n"), terminal("stop")));

		FrameWire wire = runTurn();

		List<Map<String, Object>> frames = clientView(wire);
		assertThat(FrameContract.types(frames)).as("空白分片照发 delta，但终帧是 error")
				.containsExactly("start", "message_start", "delta", "delta", "message_end", "error");
		assertThat(FrameContract.oneOfType(frames, "message_end")).as("轮终态前先关正文段（与 S5c-5 同契约）")
				.containsEntry("chars", 2L);
		assertThat(FrameContract.oneOfType(frames, "error"))
				.containsEntry("code", ResilientChatService.EMPTY_REPLY_CODE);
		FrameContract.assertConformant(frames);

		RunSnapshot snapshot = this.store.get(RUN_ID);
		assertThat(snapshot.getStatus()).isEqualTo(RunSnapshot.FAILED);
		assertThat(snapshot.getFinalText()).isEqualTo(" \n");
	}

	// ── ③ 空文本但本轮用过工具 → 同样 FAILED + EMPTY_REPLY ──

	@Test
	void emptyTextAfterAToolRoundAlsoBecomesFailed() {
		// 造"本轮已用过工具"的事实：快照历史里已有 assistant(tool_calls) + tool 配对
		RunSnapshot seeded = this.store.get(RUN_ID);
		AssistantMessage assistantWithToolCall = AssistantMessage.builder()
			.toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "list_config_defs", "{}")))
			.build();
		ToolResponseMessage toolResponse = ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "list_config_defs", "OK")))
			.build();
		seeded.setMessageJson(this.codec.serializeAll(
				List.of(new UserMessage("你好"), assistantWithToolCall, toolResponse)));
		this.store.save(seeded);
		int before = seeded.getMessageJson().size();

		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(terminal("stop")));

		FrameWire wire = runTurn();

		List<Map<String, Object>> frames = clientView(wire);
		assertThat(FrameContract.oneOfType(frames, "error"))
				.containsEntry("code", ResilientChatService.EMPTY_REPLY_CODE);
		RunSnapshot snapshot = this.store.get(RUN_ID);
		assertThat(snapshot.getStatus()).as("用过工具不豁免空回复").isEqualTo(RunSnapshot.FAILED);
		assertThat(snapshot.getMessageJson()).as("空正文不写成 assistant 消息（不污染下一轮记忆）")
				.hasSize(before);
		assertThat(snapshot.getMessageJson().get(before - 1)).contains("list_config_defs");
	}

	// ── ③b N2：取消事实先到 ⇒ 失败终局让位给取消终帧（不含 error） ──

	/**
	 * 空回复失败前取消已成立（取消登记表 CANCELLED，快照尚未被收尾改写）：失败出口必须让位给
	 * 取消收尾 —— 一轮只能有一条终帧（{@code done{cancelled:true}}），不能既有 {@code error} 又
	 * {@code done{cancelled:true}}。监视器（{@code finalizeMonitor}）与取消收尾同一把，轻装配下为空，
	 * 本用例因此锁的是"读取消事实 → 不写 FAILED"这一半语义。
	 */
	@Test
	void cancelledBeforeFailureEmitsOnlyCancelTerminal() {
		// 关掉"空回复仅模型步重试"，让本轮直接走到 emptyReply 的失败出口（否则取消会在重试退避里被看见，
		// 走的是另一条既有的取消路径，锁不住 N2 的"失败出口让位"）。
		this.properties.getResilience().setMaxAttempts(1);
		when(this.cancellations.cancelState(RUN_ID)).thenReturn(CancellationRegistry.CancelState.CANCELLED);
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(terminal("stop")));

		FrameWire wire = runTurn();

		List<Map<String, Object>> frames = clientView(wire);
		assertThat(FrameContract.types(frames)).as("取消优先：不得再发 error 终帧").doesNotContain("error");
		assertThat(FrameContract.oneOfType(frames, "done")).as("取消侧发唯一终帧").containsEntry("cancelled", true);
		RunSnapshot snapshot = this.store.get(RUN_ID);
		assertThat(snapshot.getStatus()).isEqualTo(RunSnapshot.CANCELLED);
		assertThat(snapshot.isCancelled()).isTrue();
	}

	// ── ③c N2（返修）：失败写与取消并发 ⇒ error 必须在监视器内发出、且只有一条终帧 ──

	/**
	 * 失败写与取消<b>并发</b>时：本轮只能有一条终帧，且 {@code error} 必须在失败方仍持有 per-run
	 * 监视器时发出（"写终态 → 发终帧"同一临界区）。
	 *
	 * <p>交错由 {@link FailureSignalingStore} 精确控制：取消线程等到"FAILED 已落库"才出手，此刻失败方
	 * 仍持有监视器。
	 * <ul>
	 * <li>只把写快照放进锁（上一版）：取消线程立刻进入收尾，{@code error} 在锁外发出 ⇒
	 *     {@code errorInMonitor=false}，本用例红；</li>
	 * <li>整段进锁（本版）：取消被挡到 error 之后，看到终态/终帧已定 ⇒ 不改写、不补帧，终帧恰好一条。</li>
	 * </ul>
	 */
	@Test
	void concurrentCancelDuringFailureLeavesExactlyOneTerminalFrameUnderTheMonitor() throws Exception {
		// 关掉空回复重试，让本轮直走 emptyReply 的失败出口
		this.properties.getResilience().setMaxAttempts(1);
		ScheduledExecutorService retryExecutor = mock(ScheduledExecutorService.class);
		when(retryExecutor.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
			.thenReturn(mock(ScheduledFuture.class));
		CancelledRunFinalizer finalizer = new CancelledRunFinalizer(
				new org.springframework.beans.factory.ObjectProvider<ResumeService>() {
					@Override
					public ResumeService getObject() {
						return null;
					}

					@Override
					public ResumeService getObject(Object... args) {
						return null;
					}
				}, Runnable::run, this.properties, retryExecutor);
		Object monitor = finalizer.finalizeMonitor(RUN_ID);
		this.service.setCancelFinalizer(finalizer);

		SseChatEmitter out = mock(SseChatEmitter.class);
		AtomicInteger terminals = new AtomicInteger();
		AtomicBoolean errorInMonitor = new AtomicBoolean();
		AtomicBoolean archived = new AtomicBoolean();
		when(out.terminalFrameArchived()).thenAnswer(invocation -> archived.get());
		doAnswer(invocation -> {
			archived.set(true);
			terminals.incrementAndGet();
			return null;
		}).when(out).done(anyMap(), any(), anyMap(), anyMap());
		doAnswer(invocation -> {
			archived.set(true);
			terminals.incrementAndGet();
			// 关键断言点：error 帧被调用时，失败方是否仍持有 per-run 监视器
			errorInMonitor.set(Thread.holdsLock(monitor));
			return null;
		}).when(out).error(anyString(), anyString());

		Thread canceller = new Thread(() -> {
			try {
				if (!this.store.failedWritten.await(5, TimeUnit.SECONDS)) {
					return;
				}
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return;
			}
			this.service.cancelParked(RUN_ID);
		}, "t-cancel-during-failure");

		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(terminal("stop")));
		canceller.start();
		this.service.driveResumed(RUN_ID, SESSION_ID, AiContext.empty(), List.of(new UserMessage("你好")), out);
		canceller.join(5000);

		assertThat(terminals.get()).as("error 与 done{cancelled:true} 只能有一条").isEqualTo(1);
		assertThat(errorInMonitor.get())
			.as("失败终帧必须在 per-run 监视器内发出（写终态 → 发终帧同一临界区）")
			.isTrue();
		verify(out, never()).done(anyMap(), any(), anyMap(), anyMap());
	}

	// ── ④ 非空回复回归：原 DONE 行为不变 ──

	@Test
	void nonEmptyTextStillEndsWithDone() {
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chunk("你好"), terminal("stop")));

		FrameWire wire = runTurn();

		List<Map<String, Object>> frames = clientView(wire);
		assertThat(FrameContract.types(frames)).containsExactly("start", "message_start", "delta", "message_end",
				"done");
		assertThat(FrameContract.oneOfType(frames, "done")).containsEntry("cancelled", false);
		RunSnapshot snapshot = this.store.get(RUN_ID);
		assertThat(snapshot.getStatus()).isEqualTo(RunSnapshot.DONE);
		assertThat(snapshot.getFinalText()).isEqualTo("你好");
	}

	// ── ⑥ 批次2（AR-05）：EMPTY_REPLY 只在"零产出 + 零副作用"时重试一次 ──

	/**
	 * 空回复 ⇒ <b>仅模型步</b>再试一次：{@code retry} 帧（additive {@code emptyReply=true}）后
	 * 第二次拿到正文 ⇒ 正常 {@code done}。不追加用户消息、不重执行工具（本用例零工具）。
	 */
	@Test
	void emptyReplyRetriesTheModelStepExactlyOnce() {
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(terminal("stop")),
				Flux.just(chunk("第二次答出来了"), terminal("stop")));

		FrameWire wire = runTurn();

		List<Map<String, Object>> frames = clientView(wire);
		assertThat(FrameContract.types(frames)).as("空回复先发 retry，再走正常成功帧序")
				.containsExactly("start", "retry", "message_start", "delta", "message_end", "done");
		assertThat(FrameContract.oneOfType(frames, "retry"))
				.containsEntry("code", ResilientChatService.EMPTY_REPLY_CODE)
				.containsEntry("emptyReply", true);
		RunSnapshot snapshot = this.store.get(RUN_ID);
		assertThat(snapshot.getStatus()).isEqualTo(RunSnapshot.DONE);
		assertThat(snapshot.getFinalText()).isEqualTo("第二次答出来了");
	}

	/**
	 * 两次空回复 + 仅 2 次尝试预算：仍必须先发一次 retry，再用唯一终帧收口（不得静默退出）。
	 */
	@Test
	void twoEmptyRepliesWithTwoAttemptsStillEndWithOneRetryAndFailedTerminal() {
		this.properties.getResilience().setMaxAttempts(2);
		when(this.chatModel.stream(any(Prompt.class)))
			.thenReturn(Flux.just(terminal("stop")), Flux.just(terminal("stop")));

		FrameWire wire = runTurn();

		List<Map<String, Object>> frames = clientView(wire);
		assertThat(FrameContract.types(frames)).as("一次 retry + 一个 error 终帧，不得无终态退出")
			.containsExactly("start", "retry", "error");
		assertThat(FrameContract.oneOfType(frames, "error"))
			.containsEntry("code", ResilientChatService.EMPTY_REPLY_CODE);
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.FAILED);
	}

	/**
	 * <b>只有思考、正文为空 ⇒ 不重试</b>（L-91 同族）：{@code emittedAny()=true} 时重试会把已发过的
	 * 思考段再吐一遍，且 {@code retry} 帧会落在思考 delta 之后（帧序契约禁止）。
	 */
	@Test
	void reasoningOnlyEmptyReplyIsNotRetried() {
		this.properties.getReasoning().setDisplayEnabled(true);
		when(this.chatModel.stream(any(Prompt.class)))
			.thenReturn(Flux.just(reasoning("先想一下"), terminal("stop")));

		FrameWire wire = runTurn();

		List<Map<String, Object>> frames = clientView(wire);
		assertThat(FrameContract.types(frames)).as("已出思考 ⇒ 直接空回复失败，不重试")
				.doesNotContain("retry")
				.contains("error");
		assertThat(FrameContract.oneOfType(frames, "error"))
				.containsEntry("code", ResilientChatService.EMPTY_REPLY_CODE);
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.FAILED);
	}

	/**
	 * <b>隐藏思考不算用户可见产出</b>（本批收口）：展示开关默认关闭时，思考只进累加器、
	 * 不发 {@code reasoning} 帧 —— 用户屏幕上什么都没出现。正文为空 ⇒ 仍属"零可见产出"，
	 * 按仅模型步重试一次；此时 {@code retry} 帧之前没有任何 delta，帧序契约不被破坏。
	 */
	@Test
	void hiddenReasoningOnlyEmptyReplyStillRetriesTheModelStepOnce() {
		assertThat(this.properties.getReasoning().isDisplayEnabled()).as("思考默认隐藏").isFalse();
		when(this.chatModel.stream(any(Prompt.class)))
			.thenReturn(Flux.just(reasoning("先在心里过一遍"), terminal("stop")),
					Flux.just(chunk("第二次答出来了"), terminal("stop")));

		FrameWire wire = runTurn();

		List<Map<String, Object>> frames = clientView(wire);
		assertThat(FrameContract.types(frames)).as("隐藏思考不占帧：retry 是这一轮的第一条非终态帧")
			.containsExactly("start", "retry", "message_start", "delta", "message_end", "done");
		assertThat(FrameContract.oneOfType(frames, "retry")).containsEntry("emptyReply", true)
			.containsEntry("code", ResilientChatService.EMPTY_REPLY_CODE);
		assertThat(frames).as("展示关闭 ⇒ 一个 reasoning delta 都不发")
			.noneMatch(frame -> "delta".equals(frame.get("type")) && "reasoning".equals(frame.get("kind")));
		RunSnapshot snapshot = this.store.get(RUN_ID);
		assertThat(snapshot.getStatus()).isEqualTo(RunSnapshot.DONE);
		assertThat(snapshot.getFinalText()).isEqualTo("第二次答出来了");
	}

	// ── ⑤ 终态判定函数（参数化，A13/A14） ──

	@ParameterizedTest(name = "isTerminal({0}) == {1}")
	@CsvSource({
			"RUNNING, false",
			"SUSPENDED, false",
			"DONE, true",
			"FAILED, true",
			"CANCELLED, true",
			"REJECTED, false",
			"done, false",
			"UNKNOWN_STATUS, false"
	})
	void isTerminalMatchesTheUnifiedRule(String status, boolean expected) {
		assertThat(RunSnapshot.isTerminal(status)).isEqualTo(expected);
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "", " ", "RUNNING " })
	void isTerminalIsStrictAboutNullAndUnknownStrings(String status) {
		assertThat(RunSnapshot.isTerminal(status)).as("null/空串/未知串都不是终态").isFalse();
	}

	// ───────────────────────── 辅助 ─────────────────────────

	private FrameWire runTurn() {
		this.store.seedArchive(RUN_ID,
				FrameContract.fixture("start", 1L, "runId", RUN_ID, "sessionId", SESSION_ID));
		SseChatEmitter out = this.registry.of(RUN_ID);
		FrameWire wire = FrameWire.attachTo(out);
		this.service.driveResumed(RUN_ID, SESSION_ID, AiContext.empty(), List.of(new UserMessage("你好")), out);
		return wire;
	}

	/**
	 * <b>客户端最终持有的完整帧流</b> = 归档帧 ∪ 实时流帧，按 seq 归并去重。
	 *
	 * <p>
	 * B1（2026-10-20）起 delta 也入档，归档本身就是客户端完整流的超集；这里仍按 seq
	 * 去重合并（归档优先），于是"归档与实时两条通道给的是同一份帧"这一点也被顺带锁住。
	 */
	private List<Map<String, Object>> clientView(FrameWire wire) {
		Map<Long, Map<String, Object>> bySeq = new LinkedHashMap<>();
		for (Map<String, Object> frame : FrameWire.normalize(this.store.events(RUN_ID))) {
			bySeq.putIfAbsent(((Number) frame.get("seq")).longValue(), frame);
		}
		for (Map<String, Object> frame : wire.frames()) {
			if (frame.get("seq") instanceof Number number) {
				bySeq.putIfAbsent(number.longValue(), frame);
			}
		}
		List<Map<String, Object>> all = new ArrayList<>(bySeq.values());
		all.sort(Comparator.comparingLong(frame -> ((Number) frame.get("seq")).longValue()));
		return all;
	}

	private static final class RecordingChatModel implements ChatModel {

		private final org.springframework.ai.openai.OpenAiChatOptions options;
		private final Flux<ChatResponse> responses;
		private final List<Prompt> prompts = new ArrayList<>();

		private RecordingChatModel(org.springframework.ai.openai.OpenAiChatOptions options, Flux<ChatResponse> responses) {
			this.options = options;
			this.responses = responses;
		}

		@Override
		public Flux<ChatResponse> stream(Prompt prompt) {
			this.prompts.add(prompt);
			return this.responses;
		}

		@Override
		public ChatResponse call(Prompt prompt) {
			this.prompts.add(prompt);
			return this.responses.blockFirst();
		}

		@Override
		public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions() {
			return this.options;
		}
	}

	private static final class RunBlocksRepository implements com.example.configmgr.ai.memory.RunUpsertChatMemoryRepository {

		private final List<Message> window = new ArrayList<>();
		private int deleteCalls;

		@Override
		public List<Message> findByConversationId(String conversationId) {
			return List.copyOf(this.window);
		}

		@Override
		public List<String> findConversationIds() {
			return List.of(SESSION_ID);
		}

		@Override
		public void saveAll(String conversationId, List<Message> messages) {
			this.window.clear();
			this.window.addAll(messages);
		}

		@Override
		public void deleteByConversationId(String conversationId) {
			this.deleteCalls++;
			this.window.clear();
		}

		@Override
		public boolean upsertRun(String conversationId, com.example.configmgr.ai.memory.MemoryTurn turn,
				List<Message> messages, int maxMessages, boolean replaceRunBlock) {
			this.window.addAll(messages);
			return true;
		}

		@Override
		public com.example.configmgr.ai.memory.MemoryWindowSnapshot readWindow(String conversationId) {
			return null;
		}

		@Override
		public boolean replaceWindow(String conversationId,
				com.example.configmgr.ai.memory.MemoryWindowSnapshot baseline, List<Message> messages) {
			this.window.clear();
			this.window.addAll(messages);
			return true;
		}
	}

	private static ChatClient chatClient(Flux<ChatResponse> responses) {
		ChatClient client = mock(ChatClient.class);
		ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
		ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
		when(client.prompt()).thenReturn(spec);
		when(spec.system(anyString())).thenReturn(spec);
		when(spec.user(anyString())).thenReturn(spec);
		when(spec.advisors(any(java.util.function.Consumer.class))).thenReturn(spec);
		when(spec.toolContext(anyMap())).thenReturn(spec);
		when(spec.toolCallbacks(anyList())).thenReturn(spec);
		when(spec.stream()).thenReturn(stream);
		when(stream.chatResponse()).thenReturn(responses);
		return client;
	}

	private ChatModel configureRecovery() throws Exception {
		org.springframework.ai.openai.OpenAiChatOptions initialOptions =
				org.springframework.ai.openai.OpenAiChatOptions.builder().maxTokens(8192).reasoningEffort("high")
					.extraBody(Map.of("thinking", Map.of("type", "enabled"))).build();
		org.springframework.ai.openai.OpenAiChatOptions recoveryOptions =
				org.springframework.ai.openai.OpenAiChatOptions.builder().maxTokens(8192).reasoningEffort("low")
					.extraBody(Map.of("thinking", Map.of("type", "disabled"))).build();
		when(this.chatModel.getDefaultOptions()).thenReturn(initialOptions);
		ChatModel recoveryModel = mock(ChatModel.class);
		when(recoveryModel.getDefaultOptions()).thenReturn(recoveryOptions);
		ProviderRuntime initialRuntime = new ProviderRuntime(null, "test-provider", "test-model", "http://localhost",
				"/chat/completions", "test|runtime", initialOptions, null, this.chatModel, mock(ChatClient.class));
		ProviderRuntime recoveryRuntime = new ProviderRuntime(null, "test-provider", "test-model", "http://localhost",
				"/chat/completions", "test|runtime", recoveryOptions, null, recoveryModel, mock(ChatClient.class));
		when(this.providerRegistry.forFrozenRequest(any(), eq("test-model"), any(), any(), anyBoolean()))
			.thenReturn(initialRuntime);
		when(this.providerRegistry.forFrozenRequest(any(), eq("test-model"), any(), any(), anyBoolean(), any()))
			.thenReturn(initialRuntime);
		when(this.providerRegistry.forFrozenRequest(any(), eq("test-model"), any(), any(), anyBoolean(), any(), any()))
			.thenReturn(initialRuntime);
		when(this.providerRegistry.forReasoningRecovery(any(), any())).thenReturn(recoveryRuntime);
		RunSnapshot seeded = this.store.get(RUN_ID);
		seeded.freezeParams(new RunSnapshot.FrozenParams(null, null, 8192, false));
		seeded.freezeRequestExtraBody(initialOptions.getExtraBody());
		this.mapper.readerForUpdating(seeded).readValue("{\"reasoningEffort\":\"high\",\"emptyLengthReasoningRecovery\":{"
				+ "\"reasoningEffort\":\"low\",\"requestExtraBody\":{\"thinking\":{\"type\":\"disabled\"}}}}");
		this.store.save(seeded);
		return recoveryModel;
	}

	private static ChatResponse chunk(String text) {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).build())));
	}

	private static ChatResponse terminal(String finishReason) {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").build(),
				ChatGenerationMetadata.builder().finishReason(finishReason).build())));
	}

	private static ChatResponse terminal(String finishReason, int promptTokens, int completionTokens, int reasoningTokens) {
		org.springframework.ai.chat.metadata.DefaultUsage usage = new org.springframework.ai.chat.metadata.DefaultUsage(
				promptTokens, completionTokens, promptTokens + completionTokens, Map.of("reasoningTokens", reasoningTokens));
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").build(),
				ChatGenerationMetadata.builder().finishReason(finishReason).build())),
				org.springframework.ai.chat.metadata.ChatResponseMetadata.builder().usage(usage).build());
	}

	private static ChatResponse toolCallTerminal(String finishReason) {
		AssistantMessage assistant = AssistantMessage.builder().content("")
			.toolCalls(List.of(new AssistantMessage.ToolCall("call-length", "function", "list_config_defs", "{}")))
			.build();
		return new ChatResponse(List.of(new Generation(assistant,
				ChatGenerationMetadata.builder().finishReason(finishReason).build())));
	}

	/**
	 * 思考分片（L-91）：Spring AI 1.1.7 用 {@code AssistantMessage} metadata 键
	 * {@code reasoningContent} 携带思考文本（与产品侧 {@code REASONING_METADATA_KEY} 同一拼写）。
	 */
	private static ChatResponse reasoning(String text) {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
			.content("")
			.properties(Map.of(ResilientChatService.REASONING_METADATA_KEY, text))
			.build())));
	}
}
