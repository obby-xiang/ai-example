package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.context.ContextEstimator;
import com.example.configmgr.ai.context.MemoryWindow;
import com.example.configmgr.ai.gate.SpToolCallingManager;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.memory.ReasoningOrigin;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.ReasoningAwareOpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionChunk;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>通用推理元数据</b>的挂起/续跑保真（不绑定任何 provider）。
 *
 * <h2>问题</h2>
 * 挂起时只外置了 {@code assistantContent} 与 {@code inFlightToolCalls}；续跑
 * （{@code ResumeService#rebuildAssistant}）重建出的那条 {@code assistant(tool_calls)} <b>没有 metadata</b> ——
 * 于是 {@code reasoningContent} 之外的推理字段（如 {@code encrypted_content} / {@code signature} /
 * {@code thinking} 等厂商私有键）在这一轮续跑时全部丢失。只认 {@code reasoningContent} 一个键
 * 等于把协议绑死在 DeepSeek 上。
 *
 * <h2>口径</h2>
 * <ul>
 * <li>外置的是<b>完整 metadata map</b>（不透明；嵌套 map/list/未知键一律原样保留），
 * <b>不解析、不截断</b>任何值；</li>
 * <li>恢复时整份挂回 assistant 消息；<b>没有</b> metadata 与 <b>有未知键</b>两种形态都必须保真；</li>
 * <li>{@code toolCallId} / {@code role:tool} 配对不变（重建顺序与参数原样）；</li>
 * <li>预算面：不做任何"部分文本截断"，reasoning 体量由既有估算计入（{@link ContextEstimator}），
 * 淘汰仍按整轮（{@link MemoryWindow}），不得把 assistant(tool_calls) 与它的 tool 响应拆开。</li>
 * </ul>
 */
class ResumeReasoningMetadataTest {

	private static final String RUN_ID = "run-reasoning-resume";

	private static final String SESSION_ID = "s-reasoning-resume";

	private static final String CALL_ID = "call-reasoning-1";

	private static final String ARGS = "{\"taskId\":9}";

	private static final String RESULT = "导出已启动（作业 #7788）";

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunRegistry registry = mock(RunRegistry.class);

	private final SseChatEmitter emitter = mock(SseChatEmitter.class);

	private final ResilientChatService chatService = mock(ResilientChatService.class);

	private final SpToolCallingManager toolCallingManager = mock(SpToolCallingManager.class);

	private final ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	private final AiProperties properties = new AiProperties();

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolResultLimiter limiter = new ToolResultLimiter(this.properties);

	private final ResumeService service = new ResumeService(this.store, this.registry, this.codec, this.chatService,
			this.toolCallingManager, this.executor, this.redis, this.properties, this.cancellations, this.limiter);

	/** 有推理元数据（含未知/嵌套键）：续跑重建的 assistant 必须逐键保真，配对不变。 */
	@Test
	void opaqueReasoningMetadataSurvivesSuspendAndResume() {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("reasoningContent", "先看任务，再启动导出。");
		metadata.put("encryptedContent", "gAAAAB-opaque-ciphertext==");
		metadata.put("signature", "sig-abc-123");
		metadata.put("thinking", Map.of("budget", 1024, "trace", List.of("步骤1", "步骤2")));
		metadata.put("vendorXUnknownField", List.of(Map.of("k", "v")));
		// 跨供应方隔离：来源标记（中性 providerId/modelId）也必须原样过桥
		metadata.put(ReasoningOrigin.KEY_PROVIDER_ID, 7L);
		metadata.put(ReasoningOrigin.KEY_MODEL_ID, "model-a");
		seedSuspendedRun(metadata);

		List<Message> history = resumeAndCaptureHistory();

		AssistantMessage assistant = assistantWithToolCalls(history);
		assertThat(assistant.getMetadata()).as("整份 metadata map 原样恢复（不解析、不截断、不丢未知键）")
			.containsAllEntriesOf(metadata);
		assertThat(assistant.getMetadata()).containsEntry("encryptedContent", "gAAAAB-opaque-ciphertext==");
		assertThat(assistant.getToolCalls()).hasSize(1);
		assertThat(assistant.getToolCalls().get(0).id()).isEqualTo(CALL_ID);
		assertThat(assistant.getToolCalls().get(0).arguments()).isEqualTo(ARGS);
		assertThat(pairingOf(history)).as("assistant(tool_calls) 后紧跟同 id 的 role:tool")
			.isEqualTo(CALL_ID);
	}

	/** 没有 metadata 的旧条目：重建出的 assistant 不带 metadata 键，配对同样不变（不得凭空造键）。 */
	@Test
	void assistantWithoutMetadataResumesCleanly() {
		seedSuspendedRun(null);

		List<Message> history = resumeAndCaptureHistory();

		AssistantMessage assistant = assistantWithToolCalls(history);
		assertThat(assistant.getMetadata())
			.as("没有 metadata 就不写推理键（Spring AI 自身的 messageType 除外）")
			.doesNotContainKey("reasoningContent")
			.doesNotContainKey("encryptedContent");
		assertThat(assistant.getToolCalls().get(0).id()).isEqualTo(CALL_ID);
		assertThat(pairingOf(history)).isEqualTo(CALL_ID);
	}

	/** 存储层：{@code saveSuspended} 的 metadata 是<b>不透明 JSON</b>，落库/回读逐键相等。 */
	@Test
	void saveSuspendedPersistsTheWholeMetadataMapOpaquely() throws Exception {
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		this.store.save(snapshot);
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("reasoningContent", "思考");
		metadata.put("encryptedContent", "opaque==");
		metadata.put("nested", Map.of("a", List.of(1, 2, 3)));

		this.store.saveSuspended(RUN_ID, "", List.of(entry()), List.of(new UserMessage("问题")), this.codec,
				metadata);

		RunSnapshot stored = this.store.get(RUN_ID);
		assertThat(stored.getAssistantMetadata()).containsAllEntriesOf(metadata);
		String json = this.mapper.writeValueAsString(stored);
		RunSnapshot readBack = this.mapper.readValue(json, RunSnapshot.class);
		assertThat(readBack.getAssistantMetadata()).as("未知键/嵌套结构过的就是纯 JSON 往返")
			.containsAllEntriesOf(metadata);
	}

	/** 旧 5 参 {@code saveSuspended} 仍可用：metadata 为 null（"没有"与"有但为空"不混）。 */
	@Test
	void oldSaveSuspendedSignatureLeavesMetadataNull() {
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		this.store.save(snapshot);

		this.store.saveSuspended(RUN_ID, "", List.of(entry()), List.of(new UserMessage("问题")), this.codec);

		assertThat(this.store.get(RUN_ID).getAssistantMetadata()).isNull();
	}

	/** 预算面：reasoning 计入估算（不做部分文本截断），整轮淘汰也不拆 assistant/tool 配对。 */
	@Test
	void reasoningIsCountedInTheEstimateAndWindowKeepsToolPairs() {
		String reasoning = "想".repeat(4000);
		AssistantMessage withReasoning = AssistantMessage.builder()
			.content("")
			.properties(Map.of("reasoningContent", reasoning))
			.toolCalls(List.of(new AssistantMessage.ToolCall(CALL_ID, "function", "start_export", ARGS)))
			.build();
		AssistantMessage withoutReasoning = AssistantMessage.builder()
			.content("")
			.toolCalls(List.of(new AssistantMessage.ToolCall(CALL_ID, "function", "start_export", ARGS)))
			.build();
		assertThat(ContextEstimator.estimateMessage(withReasoning))
			.as("reasoning 全文计入估算（不截断、不按 provider 分支）")
			.isGreaterThan(ContextEstimator.estimateMessage(withoutReasoning) + 1000);

		List<Message> history = new ArrayList<>();
		history.add(new UserMessage("老问题"));
		history.add(new UserMessage("新问题"));
		history.add(withReasoning);
		history.add(ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse(CALL_ID, "start_export", RESULT)))
			.build());
		history.add(new AssistantMessage("已完成"));

		MemoryWindow.Plan plan = MemoryWindow.plan(history, new MemoryWindow.Budget(200, 1, 40, 0), Set.of());
		assertThat(plan.messages()).as("淘汰按整轮：窗口首条不得是孤立的 TOOL")
			.isNotEmpty();
		assertThat(plan.messages().get(0).getMessageType()).isNotEqualTo(MessageType.TOOL);
		int assistantAt = indexOfToolCall(plan.messages());
		if (assistantAt >= 0) {
			assertThat(plan.messages().get(assistantAt + 1)).as("保住 assistant(tool_calls) 就必须保住它的 tool 响应")
				.isInstanceOf(ToolResponseMessage.class);
		}
	}

	/**
	 * <b>同一 run 续跑不回归</b>（跨实例同理：metadata 从存储读回）：续跑重建的历史交给真实补丁模型时，
	 * 同供应方 ⇒ 推理照常回放；换成别的供应方 ⇒ 该条推理不外发（正文/toolCalls/toolResponses 不变）。
	 */
	@Test
	void resumedHistoryReplaysReasoningOnlyForTheSameProvider() {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("reasoningContent", "续跑前的思考");
		metadata.put("encryptedContent", "opaque==");
		metadata.put(ReasoningOrigin.KEY_PROVIDER_ID, 7L);
		metadata.put(ReasoningOrigin.KEY_MODEL_ID, "model-a");
		seedSuspendedRun(metadata);

		List<Message> history = resumeAndCaptureHistory();

		assertThat(reasoningSentAfter(history, 7L)).as("同供应方续跑：思考照常回放（不回归）")
			.isEqualTo("续跑前的思考");
		assertThat(reasoningSentAfter(history, 8L)).as("换成别的供应方：只丢推理字段，结构不变")
			.isNull();
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	/** 用真实补丁模型发一次请求，取它给当前供应方回放的 reasoningContent。 */
	private static String reasoningSentAfter(List<Message> history, Long currentProviderId) {
		OpenAiApi api = mock(OpenAiApi.class);
		@SuppressWarnings("unchecked")
		ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
		when(api.chatCompletionStream(captor.capture(), any())).thenReturn(Flux.just(doneChunk()));
		ReasoningAwareOpenAiChatModel model = new ReasoningAwareOpenAiChatModel(api,
				OpenAiChatOptions.builder().model("model-a").build(), mock(ToolCallingManager.class),
				RetryTemplate.defaultInstance(), ObservationRegistry.NOOP, currentProviderId);

		model.stream(new Prompt(history, OpenAiChatOptions.builder().model("model-a").build()))
			.collectList()
			.block();

		verify(api).chatCompletionStream(captor.capture(), any());
		return captor.getValue()
			.messages()
			.stream()
			.filter(message -> message.role() == OpenAiApi.ChatCompletionMessage.Role.ASSISTANT)
			.findFirst()
			.orElseThrow(() -> new AssertionError("请求体里没有 assistant 消息"))
			.reasoningContent();
	}

	private static ChatCompletionChunk doneChunk() {
		OpenAiApi.ChatCompletionMessage delta = new OpenAiApi.ChatCompletionMessage("好",
				OpenAiApi.ChatCompletionMessage.Role.ASSISTANT, null, null, null, null, null, null, null);
		OpenAiApi.ChatCompletionChunk.ChunkChoice choice = new OpenAiApi.ChatCompletionChunk.ChunkChoice(
				OpenAiApi.ChatCompletionFinishReason.STOP, 0, delta, null);
		return new ChatCompletionChunk("cmpl-1", List.of(choice), 1750000000L, "model-a", null, null,
				"chat.completion.chunk", null);
	}

	private void seedSuspendedRun(Map<String, Object> metadata) {
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		snapshot.setContext(AiContext.empty().toMap());
		snapshot.setMessageJson(this.codec.serializeAll(List.of(new UserMessage("请启动导出"))));
		snapshot.setAssistantContent("");
		snapshot.setAssistantMetadata(metadata);
		snapshot.setInFlightToolCalls(new ArrayList<>(List.of(entry())));
		this.store.save(snapshot);
		this.store.seedPending(RUN_ID, entry());
	}

	private List<Message> resumeAndCaptureHistory() {
		when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
		when(this.chatService.buildResumedPrompt(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), any()))
			.thenReturn(new Prompt(List.of()));
		when(this.toolCallingManager.resolvePending(eq(RUN_ID), any(Prompt.class), any(PendingToolCall.class)))
			.thenReturn(RESULT);
		doAnswer(invocation -> {
			invocation.getArgument(0, Runnable.class).run();
			return null;
		}).when(this.executor).execute(any(Runnable.class));

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);
		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Message>> history = ArgumentCaptor.forClass(List.class);
		verify(this.chatService).driveResumed(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), history.capture(),
				eq(this.emitter));
		return history.getValue();
	}

	private static PendingToolCall entry() {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(CALL_ID);
		pending.setName("start_export");
		pending.setType("function");
		pending.setArguments(ARGS);
		pending.setKind(PendingToolCall.KIND_BACKEND);
		pending.setStatus(PendingToolCall.EXECUTED);
		pending.setOutcome(ToolOutcome.OK.wire());
		pending.setResultText(RESULT);
		pending.setExecuted(true);
		pending.setExecutedBy("instance-old");
		return pending;
	}

	private static AssistantMessage assistantWithToolCalls(List<Message> history) {
		return history.stream()
			.filter(AssistantMessage.class::isInstance)
			.map(AssistantMessage.class::cast)
			.filter(AssistantMessage::hasToolCalls)
			.findFirst()
			.orElseThrow(() -> new AssertionError("续跑历史里没有 assistant(tool_calls)：" + history));
	}

	private static String pairingOf(List<Message> history) {
		int assistantAt = indexOfToolCall(history);
		assertThat(assistantAt).as("历史里必须有 assistant(tool_calls)").isGreaterThanOrEqualTo(0);
		assertThat(assistantAt + 1).as("assistant(tool_calls) 后面必须紧跟一条消息").isLessThan(history.size());
		Message next = history.get(assistantAt + 1);
		assertThat(next).isInstanceOf(ToolResponseMessage.class);
		List<ToolResponseMessage.ToolResponse> responses = ((ToolResponseMessage) next).getResponses();
		assertThat(responses).hasSize(1);
		return responses.get(0).id();
	}

	private static int indexOfToolCall(List<Message> history) {
		for (int i = 0; i < history.size(); i++) {
			if (history.get(i) instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
				return i;
			}
		}
		return -1;
	}

}
