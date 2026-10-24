package com.example.configmgr.ai.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiConfigService;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.gate.AiRateLimiter;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.FakeRateRedis;
import com.example.configmgr.ai.gate.InputSanitizer;
import com.example.configmgr.ai.observe.AiTrace;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.ResilientChatService;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.session.SessionGate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 入口拒绝与"环外异常"的日志出口（AI 日志最小补齐批）。
 *
 * <p>此前的盲区：所有"在 {@code AiTrace.bind} 之前就 return"的拒绝路径（503 不可用 / 503 Redis /
 * 429 限流 / 409 会话忙 / 400 模型不存在·参数越界·超长·归一后为空 / 400 必填缺失）在日志里
 * <b>一行不留</b> —— 用户说"点了没反应"时，日志里既没有 {@code turn_accepted} 也没有任何终态行，
 * 只能靠 HTTP 侧访问日志猜。本类锁的就是"每一次被拒绝都要有一行可 grep 的终态"。
 *
 * <table>
 * <caption>用例矩阵</caption>
 * <tr><td>D1</td><td>429 限流 ⇒ {@code turn_denied status=429 code=RATE_LIMITED}</td></tr>
 * <tr><td>D2</td><td>409 会话忙 ⇒ {@code turn_denied status=409 code=SESSION_BUSY}，runId = 占位轮的 runId</td></tr>
 * <tr><td>D3</td><td>400 模型不存在 ⇒ {@code turn_denied status=400 code=MODEL_NOT_FOUND}</td></tr>
 * <tr><td>D4</td><td>400 超长 ⇒ {@code turn_denied status=400 code=MESSAGE_TOO_LONG}（带 chars/limit）</td></tr>
 * <tr><td>D5</td><td>503 不可用 ⇒ {@code turn_denied status=503}</td></tr>
 * <tr><td>D6</td><td>环外未捕获异常 ⇒ {@code turn_exception code=UNCAUGHT}（每轮必有终态行）</td></tr>
 * </table>
 */
class AiControllerDeniedLogTraceTest {

	private static final String SESSION = "s-denied";

	private final AiProperties properties = new AiProperties();

	private final ResilientChatService service = mock(ResilientChatService.class);

	private final SseChatEmitter out = mock(SseChatEmitter.class);

	private final RunRegistry runRegistry = mock(RunRegistry.class);

	private final FakeRateRedis redis = new FakeRateRedis();

	private final SessionGate sessionGate = mock(SessionGate.class);

	private final AiAvailability availability = mock(AiAvailability.class);

	private final RedisAvailability redisAvailability = mock(RedisAvailability.class);

	private final AiConfigService aiConfigService = mock(AiConfigService.class);

	private AiController controller;

	private final Logger traceLogger = (Logger) LoggerFactory.getLogger(AiTrace.class);

	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

	@BeforeEach
	void setUp() {
		AiTrace.clear();
		this.appender.start();
		this.traceLogger.addAppender(this.appender);
		when(this.availability.isAvailable()).thenReturn(true);
		when(this.availability.code()).thenReturn("AI_UNAVAILABLE");
		when(this.availability.reason()).thenReturn("未配置可用模型");
		when(this.redisAvailability.isAvailable()).thenReturn(true);
		when(this.redisAvailability.reason()).thenReturn("Redis 不可用");
		when(this.redisAvailability.describe()).thenReturn(java.util.Map.of("ping", "fail"));
		when(this.runRegistry.of(any())).thenReturn(this.out);
		when(this.sessionGate.acquire(any(), any())).thenReturn(null);
		this.controller = controller();
	}

	@AfterEach
	void tearDown() {
		this.traceLogger.detachAppender(this.appender);
		AiTrace.clear();
	}

	// ── D1：429 ──

	@Test
	void rateLimitedChatLeavesATurnDeniedLine() {
		this.properties.getLimit().setSessionPerMinute(0);

		ResponseEntity<?> response = this.controller.chat(new AiChatRequest(SESSION, "你好", null), null);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		String line = line("turn_denied");
		assertThat(line).contains("status=429").contains("code=RATE_LIMITED").contains("sessionId=" + SESSION);
	}

	// ── D2：409 会话忙（runId = 占位轮的 runId，而不是本次请求） ──

	@Test
	void sessionBusyDenialNamesTheRunThatHoldsTheSession() {
		when(this.sessionGate.acquire(any(), any())).thenReturn(new SessionGate.Busy("run-holder", false));

		ResponseEntity<?> response = this.controller.chat(new AiChatRequest(SESSION, "你好", null), null);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(line("turn_denied")).contains("status=409").contains("code=SESSION_BUSY")
			.contains("runId=run-holder").contains("sessionId=" + SESSION);
	}

	// ── D3：400 MODEL_NOT_FOUND ──

	@Test
	void unknownModelDenialIsTraced() {
		when(this.aiConfigService.resolveChatTarget(anyString())).thenReturn(Optional.empty());

		ResponseEntity<?> response = this.controller.chat(new AiChatRequest(SESSION, "你好", null, "model-gone", null),
				null);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(line("turn_denied")).contains("status=400").contains("code=MODEL_NOT_FOUND")
			.contains("model=model-gone");
	}

	// ── D4：400 超长 ──

	@Test
	void tooLongMessageDenialCarriesTheNumbers() {
		String tooLong = "中".repeat(this.properties.getInput().getMaxChars() + 1);

		ResponseEntity<?> response = this.controller.chat(new AiChatRequest(SESSION, tooLong, null), null);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		String line = line("turn_denied");
		assertThat(line).contains("status=400").contains("code=MESSAGE_TOO_LONG").containsPattern("chars=\\d+")
			.contains("limit=" + this.properties.getInput().getMaxChars());
	}

	// ── D5：503 不可用（连 AiTrace.bind 都还没到） ──

	@Test
	void unavailableDenialIsTracedWithoutAnyRunId() {
		when(this.availability.isAvailable()).thenReturn(false);

		ResponseEntity<?> response = this.controller.chat(new AiChatRequest(SESSION, "你好", null), null);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		String line = line("turn_denied");
		assertThat(line).contains("status=503").contains("code=AI_UNAVAILABLE");
	}

	// ── D6：环外未捕获异常（压缩/记忆写/快照建/取模型在环外抛） ──

	@Test
	void uncaughtExceptionOutsideTheAttemptLoopLeavesATurnExceptionLine() throws Exception {
		doThrow(new IllegalStateException("memory write blew up")).when(this.service)
			.chat(anyString(), any(AiChatRequest.class), any(SseChatEmitter.class));

		this.controller.chat(new AiChatRequest(SESSION, "你好", null), null);

		String line = awaitLine("turn_exception");
		assertThat(line).as("每轮必有终态行：环外异常也写一条").contains("code=UNCAUGHT")
			.contains("type=IllegalStateException");
		assertThat(eventNames()).as("异常轮仍有收尾行（turn_exception 即终态出口）").contains("turn_exception");
	}

	// ───────────────────────── 辅助 ─────────────────────────

	private AiController controller() {
		ThreadPoolTaskExecutorStub stub = executor();
		AiController built = new AiController(this.availability, providerOf(this.service),
				providerOf((com.example.configmgr.ai.run.ResumeService) null),
				providerOf((com.example.configmgr.ai.run.StartupResumeRunner) null), mock(ChatMemory.class),
				mock(ChatMemoryRepository.class), this.sessionGate, mock(ConfirmGate.class), mock(RunStore.class),
				this.runRegistry, mock(CancellationRegistry.class), this.redisAvailability, stub.executor,
				new ObjectMapper(), this.properties, mock(Environment.class),
				mock(com.example.configmgr.job.service.JobWatchdog.class));
		ReflectionTestUtils.setField(built, "inputSanitizer", new InputSanitizer());
		ReflectionTestUtils.setField(built, "aiRateLimiter", new AiRateLimiter(this.redis.redis, this.properties));
		ReflectionTestUtils.setField(built, "contextFieldGate", new com.example.configmgr.ai.gate.ContextFieldGate());
		ReflectionTestUtils.setField(built, "securityEvents", new com.example.configmgr.ai.run.SecurityEvents());
		ReflectionTestUtils.setField(built, "aiConfigService", this.aiConfigService);
		return built;
	}

	private record ThreadPoolTaskExecutorStub(org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor,
			AtomicInteger submitted) {
	}

	private static ThreadPoolTaskExecutorStub executor() {
		org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor =
				new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		executor.setQueueCapacity(256);
		executor.initialize();
		return new ThreadPoolTaskExecutorStub(executor, new AtomicInteger());
	}

	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> providerOf(T bean) {
		ObjectProvider<T> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(bean);
		return provider;
	}

	private List<String> eventLines() {
		List<String> lines = new ArrayList<>();
		for (ILoggingEvent event : new ArrayList<>(this.appender.list)) {
			String message = event.getFormattedMessage();
			if (message.startsWith("event=")) {
				lines.add(message);
			}
		}
		return lines;
	}

	private List<String> eventNames() {
		return eventLines().stream().map(line -> line.substring("event=".length()).split(" ")[0]).toList();
	}

	private String line(String name) {
		return eventLines().stream().filter(line -> line.startsWith("event=" + name + " ")).findFirst()
			.orElseThrow(() -> new AssertionError("缺少事件行 event=" + name + "；实收 = " + eventLines()));
	}

	/** 环外异常由线程池线程产出：等它出现（最多 5s）。 */
	private String awaitLine(String name) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5000L;
		while (System.currentTimeMillis() < deadline) {
			for (String line : eventLines()) {
				if (line.startsWith("event=" + name + " ")) {
					return line;
				}
			}
			Thread.sleep(20L);
		}
		return line(name);
	}

}
