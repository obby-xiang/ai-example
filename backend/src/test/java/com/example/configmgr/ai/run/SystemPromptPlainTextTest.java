package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.AiProviderRegistry;
import com.example.configmgr.ai.config.ProviderRuntime;
import com.example.configmgr.ai.conformance.FrameContract;
import com.example.configmgr.ai.conformance.FrameWire;
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
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批 F · F2 用例：输出字符面（乱码规避）规则必须写进系统提示词。
 *
 * <p>
 * 问题面（用户评审 2026-10-22）：模型偶尔在正文里使用 emoji、装饰性符号或生僻排版字符，
 * 这些码位在部分终端/字体/导出链路里渲染成乱码，用户读到的是「锟斤拷」式的残字。
 * 约束只能写在提示词里（输出后过滤会把正常正文一并改写，且无法区分"用户明确要求"）。
 *
 * <p>
 * 本用例从真实装配点取证（{@code chat()} 传给 {@code ChatClient.system(..)} 的那一串），
 * 锁三件事：
 * <ol>
 * <li>第 19 条存在且<b>逐字</b>等于定稿措辞（正面指令 + 例外出口，见 {@link #PLAIN_TEXT_RULE}）；</li>
 * <li>编号顺延：17 / 18 / 19 都在，且 19 排在 18 之后（不改既有条目编号）；</li>
 * <li>例外口子只给「用户明确要求」——否则正常追问也会被规则挡掉。</li>
 * </ol>
 *
 * <p><b>证红方式</b>：把第 19 条整段删掉 ⇒ 三条全红；把「用户明确要求时除外」删掉 ⇒ 第 3 条红；
 * 把 19 插到 18 之前 ⇒ 第 2 条红。
 */
class SystemPromptPlainTextTest {

	/** 定稿措辞（用户裁决 2026-10-22，逐字锁定；改一个字即视为语义变更）。 */
	private static final String PLAIN_TEXT_RULE = "正文用纯文本表达：常规汉字与 ASCII、常见标点，必要时可用 Markdown 结构。"
			+ "不使用 emoji、装饰性符号或生僻排版字符；用户明确要求时除外。";

	private static final String RUN_ID = "run-plain-text";

	private static final String SESSION_ID = "s-plain-text";

	private final InMemoryRunStore store = new InMemoryRunStore();

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

	/** 真实 ContextBuilder：系统提示的装配路径必须端到端成立（不是桩出来的）。 */
	private final ContextBuilder contextBuilder = new ContextBuilder(this.taskService, this.mapper);

	private final AiSessionService sessionService = mock(AiSessionService.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private ScheduledExecutorService watchdogExecutor;

	private ResilientChatService service;

	@BeforeEach
	void setUp() {
		this.watchdogExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "plain-text-watchdog");
			thread.setDaemon(true);
			return thread;
		});
		this.service = new ResilientChatService(this.providerRegistry, this.chatMemory, this.codec, this.store,
				this.registry, this.toolRegistry, this.contextBuilder, this.sessionService, this.properties,
				this.cancellations, this.beacon, this.watchdogExecutor);
		when(this.providerRegistry.current()).thenReturn(runtime("deepseek-flash"));
		when(this.cancellations.isCancelled(RUN_ID)).thenReturn(false);
		when(this.cancellations.onCancel(any(), any())).thenReturn(() -> {
		});
		when(this.chatMemory.get(SESSION_ID)).thenReturn(List.of());
		when(this.toolRegistry.forContext(any(AiContext.class))).thenReturn(List.of());
		when(this.sessionService.ensure(SESSION_ID)).thenReturn(true);
		stubFluentChain();

		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setContext(AiContext.empty().toMap());
		this.store.save(snapshot);
	}

	@AfterEach
	void tearDown() {
		this.watchdogExecutor.shutdownNow();
	}

	@Test
	void systemPromptPinsPlainTextOutputRuleVerbatim() {
		String systemText = runTurnAndCaptureSystemText();

		assertThat(systemText).as("F2：第 19 条必须逐字等于定稿措辞")
			.contains(PLAIN_TEXT_RULE);
	}

	@Test
	void plainTextRuleContinuesExistingNumbering() {
		String systemText = runTurnAndCaptureSystemText();

		int rule17 = systemText.indexOf("17.");
		int rule18 = systemText.indexOf("18.");
		int rule19 = systemText.indexOf("19.");
		assertThat(rule17).as("F2：既有第 17 条不得丢").isGreaterThanOrEqualTo(0);
		assertThat(rule18).as("F2：既有第 18 条不得丢").isGreaterThan(rule17);
		assertThat(rule19).as("F2：新规则顺延为第 19 条，且排在 18 之后").isGreaterThan(rule18);
		assertThat(systemText.indexOf(PLAIN_TEXT_RULE))
			.as("F2：19 的正文紧跟编号（中间不夹别的条目）")
			.isGreaterThan(rule19);
		assertThat(countOccurrences(systemText, "\n19. 正文用纯文本表达"))
			.as("F2：第 19 条只出现一次（重复行会被当成两条规则）")
			.isEqualTo(1);
	}

	@Test
	void plainTextRuleKeepsPositiveFormAndNarrowException() {
		String systemText = runTurnAndCaptureSystemText();

		assertThat(systemText).as("F2：正面指令 = 纯文本 + 常用标点 + 必要时 Markdown")
			.contains("正文用纯文本表达")
			.contains("常规汉字与 ASCII")
			.contains("常见标点")
			.contains("必要时可用 Markdown 结构");
		assertThat(systemText).as("F2：负面清单点名 emoji / 装饰性符号 / 生僻排版字符")
			.contains("不使用 emoji")
			.contains("装饰性符号")
			.contains("生僻排版字符");
		assertThat(systemText).as("F2：例外只给「用户明确要求」，否则正常追问也会被挡")
			.contains("用户明确要求时除外");
	}

	// ── 装配与取证 ──────────────────────────────────────────────────────────

	private String runTurnAndCaptureSystemText() {
		AiChatRequest request = new AiChatRequest(SESSION_ID, "你好",
				new AiChatRequest.Context("chat", null, null, null, Map.of()), null, null);
		SseChatEmitter out = this.registry.of(RUN_ID);
		FrameWire wire = FrameWire.attachTo(out);
		this.service.chat(RUN_ID, request, out);
		FrameContract.assertConformant(wire.frames());

		ArgumentCaptor<String> systemText = ArgumentCaptor.forClass(String.class);
		verify(this.requestSpec).system(systemText.capture());
		return systemText.getValue();
	}

	private static int countOccurrences(String text, String needle) {
		int count = 0;
		int from = 0;
		while (true) {
			int at = text.indexOf(needle, from);
			if (at < 0) {
				return count;
			}
			count++;
			from = at + needle.length();
		}
	}

	/** {@code chat()} 走 ChatClient 的流式链：逐层返回同一个 spec，末端接回 {@code streamSpec}。 */
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
