package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.AiProviderRegistry;
import com.example.configmgr.ai.config.ProviderRuntime;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ContextBuilder;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.web.AiChatRequest;
import com.example.configmgr.task.service.TaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A3-Y1 + A3-Y7 的**链路**取证：本轮回答语言从请求 locale 一路落到模型看到的系统消息里。
 *
 * <p>为什么必须从装配点取证（而不是只读常量）：A3-Y1 的裁决是「默认中文、随用户语言」，
 * 但"随哪个语言"取决于运行时来源。修复前 SSE 通道不带 {@code Accept-Language}，后端也从不把
 * locale 写进提示词 —— 两条语言硬约束之外，<b>没有任何一处</b>能把界面语言带给模型。
 * 本类锁三段：
 * <ol>
 * <li><b>来源</b>：locale 由 {@code LocaleContextHolder} 提供（与错误体 {@code ErrorBodyFactory.requestLocale}
 * 同一个取值点；控制器在提交任务前把请求 locale 放到工作线程）；</li>
 * <li><b>落地</b>：{@code chat()} 传给 {@code ChatClient.system(..)} 的系统消息**中**（工作区数据段之前）
 * 带「回答语言（本轮）」段，中/英两轮各给自己的目标语言；</li>
 * <li><b>回填</b>：语言段在轮次快照的系统消息里（{@link RunSnapshot#getMessageJson()} 首条），
 * 续跑/回灌读快照历史 ⇒ 同一轮的后续调用沿用当时的语言。注意这是**运行时事实**，
 * 不是"旧快照会被新规则升级"：修复前生成的旧快照里存的是旧系统提示（含「始终使用中文回复」），
 * 续跑时按旧提示运行（旧轮保持旧语言），我们不改恢复逻辑；</li>
 * </ol>
 *
 * <p><b>证红方式</b>：删掉 {@code chat()} 里的语言段拼接 ⇒ 三条落地/回填用例红；把语言段固定成中文
 * （不看 locale）⇒ {@code englishRequestCarriesEnglishDirective} 红。
 */
class LanguageDirectiveWiringTest {

	private static final String RUN_ZH = "run-language-zh";

	private static final String RUN_EN = "run-language-en";

	private static final String SESSION_ID = "s-language";

	private static final String DIRECTIVE_HEADING = "回答语言（本轮）";

	private final InMemoryRunStore store = spy(new InMemoryRunStore());

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final AiProperties properties = new AiProperties();

	private final ChatModel chatModel = mock(ChatModel.class);

	private final ChatClient chatClient = mock(ChatClient.class);

	private final ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);

	private final ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);

	private final AiProviderRegistry providerRegistry = mock(AiProviderRegistry.class);

	private final ChatMemory chatMemory = mock(ChatMemory.class);

	private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

	private final TaskService taskService = mock(TaskService.class);

	private final ContextBuilder contextBuilder = new ContextBuilder(this.taskService, this.mapper);

	private final AiSessionService sessionService = mock(AiSessionService.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private ScheduledExecutorService watchdogExecutor;

	private ResilientChatService service;

	@BeforeEach
	void setUp() {
		this.watchdogExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "language-watchdog");
			thread.setDaemon(true);
			return thread;
		});
		this.service = new ResilientChatService(this.providerRegistry, this.chatMemory, this.codec, this.store,
				this.registry, this.toolRegistry, this.contextBuilder, this.sessionService, this.properties,
				this.cancellations, this.beacon, this.watchdogExecutor);
		when(this.providerRegistry.current()).thenReturn(runtime("deepseek-flash"));
		when(this.cancellations.isCancelled(any())).thenReturn(false);
		when(this.cancellations.onCancel(any(), any())).thenReturn(() -> {
		});
		when(this.chatMemory.get(SESSION_ID)).thenReturn(List.of());
		when(this.toolRegistry.forContext(any(AiContext.class))).thenReturn(List.of());
		when(this.sessionService.ensure(SESSION_ID)).thenReturn(true);
		stubFluentChain();
	}

	@AfterEach
	void tearDown() {
		LocaleContextHolder.resetLocaleContext();
		this.watchdogExecutor.shutdownNow();
	}

	@Test
	void chineseRequestCarriesChineseDirective() {
		String systemText = runTurnAndCaptureSystemText(RUN_ZH, Locale.SIMPLIFIED_CHINESE);

		assertThat(systemText).contains(DIRECTIVE_HEADING);
		assertThat(systemText).as("默认中文：请求 locale 为中文时目标语言是中文").contains("默认用中文回答");
		assertThat(systemText).doesNotContain("默认用英文回答");
	}

	@Test
	void englishRequestCarriesEnglishDirective() {
		String systemText = runTurnAndCaptureSystemText(RUN_EN, Locale.US);

		assertThat(systemText).contains(DIRECTIVE_HEADING);
		assertThat(systemText).as("A3-Y7：界面英文 ⇒ 本轮语言段的目标语言是英文").contains("默认用英文回答");
		assertThat(systemText).as("语言段要点名来源，便于排障时对账 Accept-Language").contains("en-US");
	}

	@Test
	void directiveProtectsInternalIdentifiersInBothLanguages() {
		assertThat(runTurnAndCaptureSystemText(RUN_ZH, Locale.SIMPLIFIED_CHINESE))
			.contains("工具名、参数名、错误码")
			.contains("不得翻译");
		assertThat(runTurnAndCaptureSystemText(RUN_EN, Locale.US))
			.contains("工具名、参数名、错误码")
			.contains("不得翻译");
	}

	@Test
	void directiveIsHandedToTheRoundSnapshotForReplay() {
		runTurnAndCaptureSystemText(RUN_EN, Locale.US);

		// 快照拿到的就是"模型当时看到的那串消息"（ResumeService 据此重建续跑历史）；
		// InMemoryRunStore 不落 messageJson，故在装配点取证（同一份 full 列表）。
		String systemText = capturedSnapshotSystemText(RUN_EN);
		assertThat(systemText)
			.as("回填/续跑读的是快照里的系统消息：语言段必须已经在里面，否则换线程后续跑会回落中文")
			.contains(DIRECTIVE_HEADING)
			.contains("默认用英文回答");
	}

	@Test
	void multiTurnReplayKeepsMemoryAndUsesTheCurrentTurnLanguage() {
		when(this.chatMemory.get(SESSION_ID)).thenReturn(List.of(
				new UserMessage("上一轮的问题"),
				AssistantMessage.builder().content("上一轮的回答").build()));

		runTurnAndCaptureSystemText(RUN_ZH, Locale.SIMPLIFIED_CHINESE);

		List<Message> snapshotHistory = capturedSnapshotHistory(RUN_ZH);
		assertThat(snapshotHistory).as("多轮回填：系统消息 + 记忆窗口 + 本轮用户消息").hasSize(4);
		assertThat(snapshotHistory.get(0)).isInstanceOf(SystemMessage.class);
		assertThat(((SystemMessage) snapshotHistory.get(0)).getText())
			.as("记忆回放轮仍用本轮声明的语言")
			.contains("默认用中文回答");
	}

	/**
	 * M-6（4B 独立评审）：英文连续两轮 —— 语言段每轮按**当轮** locale 重建，不会"第一轮英文、第二轮回中文"。
	 */
	@Test
	void englishTurnsStayEnglishAcrossTwoRounds() {
		String first = runTurnAndCaptureSystemText(RUN_EN, Locale.US);
		String second = runTurnAndCaptureSystemText(RUN_EN + "-2", Locale.US);

		assertThat(first).contains("默认用英文回答");
		assertThat(second).contains("默认用英文回答").doesNotContain("默认用中文回答");
	}

	// ── 装配与取证 ──────────────────────────────────────────────────────────

	private String runTurnAndCaptureSystemText(String runId, Locale locale) {
		RunSnapshot snapshot = this.store.seedRun(runId, SESSION_ID);
		snapshot.setContext(AiContext.empty().toMap());
		this.store.save(snapshot);
		clearInvocations(this.requestSpec);

		AiChatRequest request = new AiChatRequest(SESSION_ID, "你好",
				new AiChatRequest.Context("chat", null, null, null, Map.of()), null, null);
		SseChatEmitter out = this.registry.of(runId);

		// 控制器在 HTTP 线程取请求 locale，提交任务时放到工作线程（见 AiController#chat）；
		// 这里以同一取值点（LocaleContextHolder）复现工作线程上的上下文。
		LocaleContextHolder.setLocale(locale);
		try {
			this.service.chat(runId, request, out);
		}
		finally {
			LocaleContextHolder.resetLocaleContext();
		}

		ArgumentCaptor<String> systemText = ArgumentCaptor.forClass(String.class);
		verify(this.requestSpec).system(systemText.capture());
		return systemText.getValue();
	}

	/** 取本轮 {@code store.create(..)} 收到的完整历史（与 {@code RunSnapshot#messageJson} 同一份）。 */
	private List<Message> capturedSnapshotHistory(String runId) {
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Message>> history = ArgumentCaptor.forClass(List.class);
		verify(this.store).create(eq(runId), eq(SESSION_ID), any(AiContext.class), history.capture(),
				any(MessageJsonCodec.class), any(), any(), any());
		return history.getValue();
	}

	private String capturedSnapshotSystemText(String runId) {
		List<Message> history = capturedSnapshotHistory(runId);
		assertThat(history).isNotEmpty();
		assertThat(history.get(0)).isInstanceOf(SystemMessage.class);
		return ((SystemMessage) history.get(0)).getText();
	}

	private void stubFluentChain() {
		when(this.chatClient.prompt()).thenReturn(this.requestSpec);
		when(this.requestSpec.system(any(String.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.user(any(String.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.advisors(any(java.util.function.Consumer.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.toolContext(any(java.util.Map.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.toolCallbacks(any(java.util.List.class))).thenReturn(this.requestSpec);
		when(this.requestSpec.stream()).thenReturn(this.streamSpec);
		when(this.streamSpec.chatResponse()).thenAnswer(invocation -> reactor.core.publisher.Flux.just(chunk("好了"),
				terminal()));
	}

	private ProviderRuntime runtime(String modelId) {
		return new ProviderRuntime(7L, "deepseek", modelId, "http://placeholder", "/chat/completions",
				"7|fp|u|p", null, null, this.chatModel, this.chatClient);
	}

	private static ChatResponse chunk(String text) {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).build())));
	}

	private static ChatResponse terminal() {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").build(),
				ChatGenerationMetadata.builder().finishReason("stop").build())),
				ChatResponseMetadata.builder().build());
	}
}
