package com.example.configmgr.ai.run;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.slf4j.LoggerFactory;
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

	// ── 2026-10-27 回归：stop 之后的裸 tool_calls 标记不得翻案 ────────────────

	/**
	 * <b>【终态锁存回归】</b>真实上游的 6 片形态里，{@code stop} 之后还会补一个<b>裸</b>
	 * {@code finish_reason=tool_calls} 分片（该分片<b>没有</b>任何 toolCalls）。旧口径"最后一个非空
	 * finishReason 说了算"把它当成工具轮中间态 ⇒ {@code sawTerminal=false} ⇒ 一轮已经完整落地的
	 * 对话被误判成 {@code UPSTREAM_STREAM_INCOMPLETE}（DONE 变成 error）。
	 *
	 * <p>锁存口径下：首个可完成终态（{@code stop}）一次锁存，裸标记不翻案 ⇒ DONE、无 error、
	 * {@code terminalSignal} 仍是 {@code stop}；但锁存之后若真的来了带 toolCalls 的分片（真实的新
	 * 工具轮），仍按断流处理 —— 终态不得吞掉真实工具轮。
	 *
	 * <p>kill：{@code sawTerminal()} 改回读 {@code lastFinish} ⇒ 第一段变红；去掉真实工具分片守卫 ⇒
	 * 第二段变红。
	 */
	@Test
	void stopThenBareToolCallsMarkerStaysDoneWhileRealToolCallsStayIncomplete() {
		this.properties.getResilience().setMaxAttempts(3);
		// 6 片形态：正文 2 片 + 空片 + stop + 空片 + 裸 tool_calls 标记（无 toolCalls）
		when(this.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chunk("你"), chunk("好"), emptyChunk(),
				terminal("stop"), emptyChunk(), terminal("tool_calls")));

		List<Map<String, Object>> frames = runTurn();

		assertThat(FrameContract.types(frames)).containsExactly("start", "message_start", "delta", "delta",
				"message_end", "done");
		FrameContract.assertConformant(frames);
		assertThat(FrameContract.ofType(frames, "error")).as("裸 tool_calls 标记不是断流证据").isEmpty();
		assertThat(FrameContract.oneOfType(frames, "done")).containsEntry("terminalSignal", "finishReason=stop");
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.DONE);
		assertThat(this.store.get(RUN_ID).getFinalText()).isEqualTo("你好");
		verify(this.chatModel, times(1)).stream(any(Prompt.class));

		// 锁存之后又来一条<b>真实</b>工具调用分片（无 finishReason）：这是新的工具轮，不是完成
		String runId = "run-upstream-policy-tool-after-stop";
		String sessionId = "s-upstream-policy-tool-after-stop";
		seedRun(runId, sessionId);
		when(this.chatModel.stream(any(Prompt.class)))
			.thenReturn(Flux.just(chunk("你"), chunk("好"), terminal("stop"), toolCallChunk()));

		List<Map<String, Object>> second = runTurn(runId, sessionId);

		assertThat(FrameContract.ofType(second, "done")).as("真实工具轮不得被终态吞成 DONE").isEmpty();
		assertThat(FrameContract.oneOfType(second, "error"))
			.containsEntry("code", ErrorCodes.UPSTREAM_STREAM_INCOMPLETE_AFTER_PARTIAL);
		assertThat(this.store.get(runId).getStatus()).isEqualTo(RunSnapshot.FAILED);
	}

	/**
	 * <b>【安全分类不丢】</b>终态锁存不得把"最后一个 finishReason"的最终安全语义一起冻住：
	 * {@code stop → length} 仍必须 {@code truncated}、{@code stop → content_filter} 仍是明确失败
	 * （{@link ResilientChatService#finish} 只读 {@code Accumulator#finish()} = 最后一个 finishReason）。
	 *
	 * <p>kill：把 {@code finish()} 改成返回锁存的终态 ⇒ 第一段丢 {@code truncated}；让它只认锁存值
	 * 的 CONTENT_FILTER ⇒ 第二段从 error 变 DONE。
	 */
	@Test
	void stopFollowedByLengthOrContentFilterKeepsTheFinalSafetyClassification() {
		this.properties.getResilience().setMaxAttempts(3);
		when(this.chatModel.stream(any(Prompt.class)))
			.thenReturn(Flux.just(chunk("半"), terminal("stop"), terminal("length")));

		List<Map<String, Object>> truncated = runTurn();

		assertThat(FrameContract.types(truncated)).containsExactly("start", "message_start", "delta", "message_end",
				"done");
		assertThat(FrameContract.oneOfType(truncated, "done")).containsEntry("truncated", true)
			.containsEntry("terminalSignal", "finishReason=stop");
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.DONE);

		String runId = "run-upstream-policy-filter-after-stop";
		String sessionId = "s-upstream-policy-filter-after-stop";
		seedRun(runId, sessionId);
		when(this.chatModel.stream(any(Prompt.class)))
			.thenReturn(Flux.just(chunk("半"), terminal("stop"), terminal("content_filter")));

		List<Map<String, Object>> filtered = runTurn(runId, sessionId);

		assertThat(FrameContract.ofType(filtered, "done")).as("content_filter 不是完成").isEmpty();
		assertThat(FrameContract.oneOfType(filtered, "error")).containsEntry("code", ErrorCodes.CONTENT_FILTERED);
		assertThat(this.store.get(runId).getStatus()).isEqualTo(RunSnapshot.FAILED);
	}

	// ── N2：上游错误正文的日志收敛（敏感隐去 + 单行限长）────────────────────

	/**
	 * <b>【N2 日志正文收敛】</b>上游错误 body 只经 {@code describeForLog} 一个点进日志：
	 * 命中凭据（{@code ExcerptGuard.sensitive}）时整段隐去、只留字符数；普通长/多行 body
	 * 折成单行并限长（约 1024 字符）—— 正文既不能泄露，也不能靠换行伪造日志行。
	 *
	 * <p>kill：把 {@code describeForLog} 的 body 分支改回 {@code getResponseBodyAsString()}
	 * ⇒ 敏感原文出现在日志里、长 body 保留原始换行且不见截断标记，本用例变红。
	 */
	@Test
	void upstreamErrorBodyIsRedactedAndFoldedIntoOneBoundedLogLine() {
		this.properties.getResilience().setMaxAttempts(1);
		Logger serviceLogger = (Logger) LoggerFactory.getLogger(ResilientChatService.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		serviceLogger.addAppender(appender);
		try {
			// ① 敏感 body：整段隐去（可保留字符数），正文任何片段都不落日志
			String secret = "sk-xxxxxxxxxxxxxxxxxxxx";
			String sensitiveBody = "{\"error\":{\"message\":\"Incorrect API key provided: " + secret + "\"}}";
			when(this.chatModel.stream(any(Prompt.class)))
				.thenReturn(Flux.error(httpBody(401, sensitiveBody)));

			runTurn("run-upstream-log-sensitive", "s-upstream-log-sensitive");

			String sensitiveLine = logLineFor(appender, "run-upstream-log-sensitive");
			assertThat(sensitiveLine).as("HTTP 状态保留").contains("HTTP 401");
			assertThat(sensitiveLine).as("命中凭据 ⇒ 整段隐去，原文不落日志")
				.doesNotContain(secret)
				.doesNotContain("Incorrect API key");
			assertThat(sensitiveLine).as("隐去后保留字符数").contains("已隐去")
				.contains(String.valueOf(sensitiveBody.length()));

			// ② 普通长/多行 body：折成单行 + 限长，超出部分与尾部内容不进日志
			StringBuilder longBody = new StringBuilder();
			for (int i = 0; i < 80; i++) {
				longBody.append("row-").append(i).append("-0123456789\n");
			}
			longBody.append("TAIL-MARKER");
			when(this.chatModel.stream(any(Prompt.class)))
				.thenReturn(Flux.error(httpBody(502, longBody.toString())));

			runTurn("run-upstream-log-long", "s-upstream-log-long");

			String longLine = logLineFor(appender, "run-upstream-log-long");
			assertThat(longLine).as("HTTP 状态保留").contains("HTTP 502").contains("row-0-");
			assertThat(longLine).as("body 折成单行（换行可伪造日志行）").doesNotContain("\n").doesNotContain("\r");
			assertThat(longLine).as("超出上限的部分被截断且带标记").contains("截断").doesNotContain("TAIL-MARKER");
			assertThat(longLine).as("整行有界（1024 正文 + 行首字段 + 截断标记）").hasSizeLessThan(1400);
		}
		finally {
			serviceLogger.detachAppender(appender);
		}
	}

	/** 取该轮进入日志的失败行（带 HTTP 诊断的唯一一条）。 */
	private static String logLineFor(ListAppender<ILoggingEvent> appender, String runId) {
		return appender.list.stream()
			.map(ILoggingEvent::getFormattedMessage)
			.filter(message -> message.contains("runId=" + runId) && message.contains("HTTP "))
			.findFirst()
			.orElseThrow(() -> new AssertionError("没有找到该轮的失败日志行 runId=" + runId));
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private List<Map<String, Object>> runTurn() {
		return runTurn(RUN_ID, SESSION_ID);
	}

	private List<Map<String, Object>> runTurn(String runId, String sessionId) {
		this.store.seedArchive(runId,
				FrameContract.fixture("start", 1L, "runId", runId, "sessionId", sessionId));
		SseChatEmitter out = this.registry.of(runId);
		FrameWire wire = FrameWire.attachTo(out);
		this.service.driveResumed(runId, sessionId, AiContext.empty(), List.of(new UserMessage("你好")), out);
		return clientView(runId, wire);
	}

	/** 铺一个"运行中"的轮次快照（与 {@link #setUp()} 同形），供同一用例里的第二条轮使用。 */
	private void seedRun(String runId, String sessionId) {
		RunSnapshot snapshot = this.store.seedRun(runId, sessionId);
		snapshot.setContext(AiContext.empty().toMap());
		this.store.save(snapshot);
	}

	/** 客户端最终持有的完整帧流 = 归档帧 ∪ 实时流帧（按 seq 去重，归档优先）。 */
	private List<Map<String, Object>> clientView(FrameWire wire) {
		return clientView(RUN_ID, wire);
	}

	private List<Map<String, Object>> clientView(String runId, FrameWire wire) {
		Map<Long, Map<String, Object>> bySeq = new LinkedHashMap<>();
		for (Map<String, Object> frame : FrameWire.normalize(this.store.events(runId))) {
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

	/** 一个空正文分片（只有 role/用量，模拟上游的中间空片）。 */
	private static ChatResponse emptyChunk() {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").build())));
	}

	/** 一条<b>真实</b>工具调用分片（无 finishReason）：{@code output.hasToolCalls()=true}。 */
	private static ChatResponse toolCallChunk() {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
			.content("")
			.toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "ui_snapshot", "{}")))
			.build())));
	}

}
