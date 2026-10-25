package com.example.configmgr.ai.run;

import com.example.configmgr.ai.conformance.FrameContract;
import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.AiProviderRegistry;
import com.example.configmgr.ai.config.ProviderRuntime;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ContextBuilder;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.common.ErrorCodes;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 第 10 项：<b>上游错误分类落到重试循环</b>的行为矩阵。
 *
 * <p>本类只锁"分类 → 是否重试 → 终帧/终态"这条链，不重复
 * {@link UpstreamErrorClassifierTest} 的纯函数矩阵。关键断言：
 * <ul>
 * <li>状态类错误不盲目重试（{@code stream()} 只被调一次），可重试项按 {@code max-attempts} 有界；</li>
 * <li>429 的 {@code Retry-After} 超过剩余预算 ⇒ 不睡也不重试（预算约束优先）；</li>
 * <li>已产出/工具副作用 ⇒ 可重试类错误也不再重试（沿用三条件）；</li>
 * <li>{@code length} 有正文 ⇒ {@code done{truncated:true}} 且不重试；{@code content_filter} ⇒ 明确失败；</li>
 * <li>最后的 finishReason 是 {@code tool_calls}、或只有 {@code [DONE]} 没有 finishReason ⇒ 不完整流。</li>
 * </ul>
 */
class UpstreamErrorRetryPolicyTest {

	private static final String RUN_ID = "run-upstream-policy";

	private static final String SESSION_ID = "s-upstream-policy";

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunRegistry registry = new RunRegistry(this.store, this.mapper, null);

	private final AiProperties properties = new AiProperties();

	private final ChatModel chatModel = mock(ChatModel.class);

	private final AiProviderRegistry providerRegistry = mock(AiProviderRegistry.class);

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

	@BeforeEach
	void setUp() {
		when(this.providerRegistry.current()).thenReturn(this.runtime);
		when(this.providerRegistry.tryCurrent()).thenReturn(java.util.Optional.of(this.runtime));
		this.watchdogExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "upstream-policy-watchdog");
			thread.setDaemon(true);
			return thread;
		});
		this.service = new ResilientChatService(this.providerRegistry, this.chatMemory, this.codec,
				this.store, this.registry, this.toolRegistry, this.contextBuilder, this.sessionService,
				this.properties, this.cancellations, this.beacon, this.watchdogExecutor);
		when(this.cancellations.isCancelled(RUN_ID)).thenReturn(false);
		when(this.cancellations.onCancel(anyString(), any(Runnable.class))).thenReturn(() -> {
		});
		when(this.toolRegistry.forContext(any(AiContext.class))).thenReturn(List.of());
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setContext(AiContext.empty().toMap());
		this.store.save(snapshot);
	}

	@AfterEach
	void tearDown() {
		this.watchdogExecutor.shutdownNow();
	}

	// ── 不可重试状态：一次即止 ───────────────────────────────────────────────

	@ParameterizedTest(name = "HTTP {0} → {1}，只尝试一次")
	@CsvSource({
			"400, UPSTREAM_REJECTED",
			"401, UPSTREAM_AUTH_FAILED",
			"403, UPSTREAM_AUTH_FAILED",
			"404, UPSTREAM_REJECTED",
			"409, UPSTREAM_REJECTED",
			"422, UPSTREAM_REJECTED"
	})
	void nonRetryableStatusFailsOnTheFirstAttempt(int status, String code) {
		this.properties.getResilience().setMaxAttempts(3);
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.error(http(status)));

		List<Map<String, Object>> frames = runTurn();

		assertThat(FrameContract.types(frames)).containsExactly("start", "error");
		assertThat(FrameContract.oneOfType(frames, "error")).containsEntry("code", code);
		verify(this.chatModel, times(1)).stream(any(Prompt.class));
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.FAILED);
	}

	@Test
	void twoHundredWithAnErrorPayloadIsNotRetried() {
		this.properties.getResilience().setMaxAttempts(3);
		when(this.chatModel.stream(any(Prompt.class)))
			.thenReturn(Flux.error(httpBody(200, "{\"error\":{\"message\":\"bad payload\"}}")));

		List<Map<String, Object>> frames = runTurn();

		assertThat(FrameContract.oneOfType(frames, "error")).containsEntry("code", ErrorCodes.UPSTREAM_STREAM_ERROR);
		verify(this.chatModel, times(1)).stream(any(Prompt.class));
	}

	// ── 可重试：有界 + 预算约束 ─────────────────────────────────────────────

	@Test
	void serverErrorRetriesUpToMaxAttempts() {
		this.properties.getResilience().setMaxAttempts(2);
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.error(http(500)));

		List<Map<String, Object>> frames = runTurn();

		assertThat(FrameContract.types(frames)).containsExactly("start", "retry", "error");
		assertThat(FrameContract.oneOfType(frames, "retry")).containsEntry("code", ErrorCodes.UPSTREAM_UNAVAILABLE);
		assertThat(FrameContract.oneOfType(frames, "error")).containsEntry("code", ErrorCodes.UPSTREAM_UNAVAILABLE);
		verify(this.chatModel, times(2)).stream(any(Prompt.class));
		assertThat(this.store.get(RUN_ID).getAttempts()).isEqualTo(2);
	}

	@Test
	void networkIoFailureRetries() {
		this.properties.getResilience().setMaxAttempts(2);
		when(this.chatModel.stream(any(Prompt.class)))
			.thenReturn(Flux.error(new IOException("connection reset")));

		List<Map<String, Object>> frames = runTurn();

		assertThat(FrameContract.oneOfType(frames, "retry"))
			.containsEntry("code", ErrorCodes.UPSTREAM_CONNECTION_BROKEN);
		assertThat(FrameContract.oneOfType(frames, "error"))
			.containsEntry("code", ErrorCodes.UPSTREAM_CONNECTION_BROKEN);
		verify(this.chatModel, times(2)).stream(any(Prompt.class));
	}

	@Test
	void rateLimitHonoursAZeroRetryAfterAndRetries() {
		this.properties.getResilience().setMaxAttempts(2);
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.error(http(429, "0")));

		List<Map<String, Object>> frames = runTurn();

		assertThat(FrameContract.oneOfType(frames, "retry"))
			.containsEntry("code", ErrorCodes.UPSTREAM_RATE_LIMITED);
		assertThat(FrameContract.oneOfType(frames, "error"))
			.containsEntry("code", ErrorCodes.UPSTREAM_RATE_LIMITED);
		verify(this.chatModel, times(2)).stream(any(Prompt.class));
	}

	@Test
	void rateLimitRetryAfterBeyondTheRemainingBudgetFailsWithoutSleeping() {
		this.properties.getResilience().setMaxAttempts(3);
		this.properties.getResilience().setTotalBudget(Duration.ofSeconds(2));
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.error(http(429, "120")));

		long startedAt = System.nanoTime();
		List<Map<String, Object>> frames = runTurn();
		long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

		assertThat(FrameContract.ofType(frames, "retry"))
			.as("Retry-After 超出剩余预算 ⇒ 不做注定超预算的重试").isEmpty();
		assertThat(FrameContract.oneOfType(frames, "error"))
			.containsEntry("code", ErrorCodes.UPSTREAM_RATE_LIMITED);
		verify(this.chatModel, times(1)).stream(any(Prompt.class));
		assertThat(elapsedMs).as("没有睡满 Retry-After（120s）").isLessThan(10_000L);
	}

	@Test
	void retryableStatusAfterPartialContentIsNotRetried() {
		this.properties.getResilience().setMaxAttempts(3);
		when(this.chatModel.stream(any(Prompt.class)))
			.thenReturn(Flux.concat(Flux.just(chunk("半截")), Flux.error(http(500))));

		List<Map<String, Object>> frames = runTurn();

		assertThat(FrameContract.types(frames)).containsExactly("start", "message_start", "delta", "message_end",
				"error");
		assertThat(FrameContract.ofType(frames, "retry")).isEmpty();
		assertThat(FrameContract.oneOfType(frames, "error")).containsEntry("code", ErrorCodes.UPSTREAM_UNAVAILABLE);
		verify(this.chatModel, times(1)).stream(any(Prompt.class));
	}

	// ── finishReason：length / content_filter / tool_calls / 未知 ────────────

	@Test
	void lengthFinishReasonKeepsTheBodyAndMarksItTruncatedWithoutRetry() {
		this.properties.getResilience().setMaxAttempts(3);
		when(this.chatModel.stream(any(Prompt.class)))
			.thenReturn(Flux.just(chunk("半"), terminal("length")));

		List<Map<String, Object>> frames = runTurn();

		assertThat(FrameContract.types(frames)).containsExactly("start", "message_start", "delta", "message_end",
				"done");
		FrameContract.assertConformant(frames);
		assertThat(FrameContract.oneOfType(frames, "done")).containsEntry("truncated", true)
			.containsEntry("terminalSignal", "finishReason=length");
		assertThat(FrameContract.ofType(frames, "retry")).isEmpty();
		verify(this.chatModel, times(1)).stream(any(Prompt.class));
		RunSnapshot snapshot = this.store.get(RUN_ID);
		assertThat(snapshot.getStatus()).isEqualTo(RunSnapshot.DONE);
		assertThat(snapshot.getFinalText()).as("截断不是失败：已有正文照常落定").isEqualTo("半");
	}

	@Test
	void contentFilterFinishesAsAnExplicitFailureWithoutRetry() {
		this.properties.getResilience().setMaxAttempts(3);
		when(this.chatModel.stream(any(Prompt.class)))
			.thenReturn(Flux.just(chunk("半"), terminal("content_filter")));

		List<Map<String, Object>> frames = runTurn();

		assertThat(FrameContract.types(frames)).containsExactly("start", "message_start", "delta", "message_end",
				"error");
		FrameContract.assertConformant(frames);
		assertThat(FrameContract.oneOfType(frames, "error")).containsEntry("code", ErrorCodes.CONTENT_FILTERED);
		assertThat(FrameContract.ofType(frames, "retry")).isEmpty();
		verify(this.chatModel, times(1)).stream(any(Prompt.class));
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.FAILED);
	}

	@Test
	void toolCallsAsTheLastFinishReasonIsIncompleteAndRetriesWhenNothingWasEmitted() {
		this.properties.getResilience().setMaxAttempts(2);
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(terminal("tool_calls")));

		List<Map<String, Object>> frames = runTurn();

		assertThat(FrameContract.types(frames)).containsExactly("start", "retry", "error");
		assertThat(FrameContract.oneOfType(frames, "retry"))
			.containsEntry("code", ErrorCodes.UPSTREAM_STREAM_INCOMPLETE);
		assertThat(FrameContract.oneOfType(frames, "error"))
			.containsEntry("code", ErrorCodes.UPSTREAM_STREAM_INCOMPLETE);
		verify(this.chatModel, times(2)).stream(any(Prompt.class));
	}

	@Test
	void toolCallsAfterPartialContentIsIncompleteWithoutRetry() {
		this.properties.getResilience().setMaxAttempts(3);
		when(this.chatModel.stream(any(Prompt.class)))
			.thenReturn(Flux.just(chunk("半"), terminal("tool_calls")));

		List<Map<String, Object>> frames = runTurn();

		assertThat(FrameContract.types(frames)).containsExactly("start", "message_start", "delta", "message_end",
				"error");
		assertThat(FrameContract.oneOfType(frames, "error"))
			.containsEntry("code", ErrorCodes.UPSTREAM_STREAM_INCOMPLETE_AFTER_PARTIAL);
		verify(this.chatModel, times(1)).stream(any(Prompt.class));
	}

	@Test
	void streamThatOnlySendsDoneWithoutFinishReasonIsIncomplete() {
		this.properties.getResilience().setMaxAttempts(3);
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chunk("半")));

		List<Map<String, Object>> frames = runTurn();

		assertThat(FrameContract.oneOfType(frames, "error"))
			.containsEntry("code", ErrorCodes.UPSTREAM_STREAM_INCOMPLETE_AFTER_PARTIAL);
		verify(this.chatModel, times(1)).stream(any(Prompt.class));
	}

	@Test
	void unknownFinishReasonIsTreatedAsACompletedStream() {
		this.properties.getResilience().setMaxAttempts(3);
		when(this.chatModel.stream(any(Prompt.class)))
			.thenReturn(Flux.just(chunk("半"), terminal("end_turn")));

		List<Map<String, Object>> frames = runTurn();

		assertThat(FrameContract.types(frames)).containsExactly("start", "message_start", "delta", "message_end",
				"done");
		assertThat(FrameContract.oneOfType(frames, "done")).doesNotContainKey("truncated");
		verify(this.chatModel, times(1)).stream(any(Prompt.class));
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private List<Map<String, Object>> runTurn() {
		this.store.seedArchive(RUN_ID,
				FrameContract.fixture("start", 1L, "runId", RUN_ID, "sessionId", SESSION_ID));
		SseChatEmitter out = this.registry.of(RUN_ID);
		FrameWire wire = FrameWire.attachTo(out);
		this.service.driveResumed(RUN_ID, SESSION_ID, AiContext.empty(), List.of(new UserMessage("你好")), out);
		return clientView(wire);
	}

	/** 客户端最终持有的完整帧流 = 归档帧 ∪ 实时流帧（按 seq 去重，归档优先）。 */
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

	private static WebClientResponseException http(int status) {
		return http(status, null);
	}

	private static WebClientResponseException http(int status, String retryAfter) {
		HttpHeaders headers = new HttpHeaders();
		if (retryAfter != null) {
			headers.set(HttpHeaders.RETRY_AFTER, retryAfter);
		}
		return httpBody(status, "{\"error\":{\"message\":\"upstream says no\"}}", headers);
	}

	private static WebClientResponseException httpBody(int status, String body) {
		return httpBody(status, body, HttpHeaders.EMPTY);
	}

	private static WebClientResponseException httpBody(int status, String body, HttpHeaders headers) {
		return WebClientResponseException.create(status, "status-" + status, headers,
				body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
	}

	private static ChatResponse chunk(String text) {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).build())));
	}

	private static ChatResponse terminal(String finishReason) {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").build(),
				ChatGenerationMetadata.builder().finishReason(finishReason).build())));
	}

}
