package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.gate.AiRateLimiter;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.ContextFieldGate;
import com.example.configmgr.ai.gate.InputSanitizer;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.ResilientChatService;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SecurityEvents;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.session.SessionGate;
import com.example.configmgr.common.ErrorBodyFactory;
import com.example.configmgr.common.ErrorCodes;
import com.example.configmgr.common.WebI18nConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A3-Y7 / I-1：{@code /chat} 的**开流前**错误体必须与 REST 通道同一语言口径（`Accept-Language`）。
 *
 * <h2>为什么这组用例存在</h2>
 * 加头只让走 {@code errorBody()/localize()} 的错误体本地化；同一端点上另外 5 个"专门 helper"
 * （400 超长 / 429 限流 / 503 池饱和 / 503 AI 不可用 / 503 Redis 不可用）仍写死中文。en 界面下
 * 这些句子会原样进提示（{@code stores/ai.ts} 的 {@code setRawNotice(error.message)}），
 * 与 A3-Y7 点名的 {@code MESSAGE_TOO_LONG}/503 验收面不符。
 *
 * <h2>语言来源（不另造第二源）</h2>
 * 这里挂的是**生产同一个解析器**（{@link WebI18nConfig#localeResolver()}）→ DispatcherServlet 写入
 * {@link LocaleContextHolder} → {@code ErrorBodyFactory.requestLocale()}。因此断言同时覆盖
 * M-3 的语言级回退（`en` / `en-GB` → en-US）与"缺失/非法 → zh-CN"。
 *
 * <p><b>证红方式</b>：把任一 helper 的 message 改回写死中文 ⇒ 对应用例红；把解析器的语言级回退去掉
 * ⇒ {@code *LanguageOnlyHeader*} / {@code *EnGbHeader*} 红。
 */
class AiControllerPreStreamLocaleTest {

	private static final String SESSION = "s-prelocale";

	private final AiAvailability availability = mock(AiAvailability.class);

	private final RedisAvailability redisAvailability = mock(RedisAvailability.class);

	private final SessionGate sessionGate = mock(SessionGate.class);

	private final RunRegistry runRegistry = mock(RunRegistry.class);

	private final ResilientChatService chatService = mock(ResilientChatService.class);

	private final SseChatEmitter out = mock(SseChatEmitter.class);

	private final AiProperties properties = new AiProperties();

	private final ThreadPoolTaskExecutor executor = executor();

	private final AiController controller = controller(this.executor);

	private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(this.controller)
		.setLocaleResolver(new WebI18nConfig().localeResolver())
		.build();

	@AfterEach
	void resetLocale() {
		LocaleContextHolder.resetLocaleContext();
		this.executor.shutdown();
	}

	// ── 400 MESSAGE_TOO_LONG ────────────────────────────────────────────────

	@Test
	void overlongMessageStaysChineseWithoutAcceptLanguage() throws Exception {
		allowChat();

		this.mockMvc.perform(overlongRequest())
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCodes.MESSAGE_TOO_LONG))
			.andExpect(jsonPath("$.message").value(containsString("消息长度")))
			.andExpect(jsonPath("$.limit").value(8192));
	}

	@Test
	void overlongMessageIsEnglishWithEnUsHeader() throws Exception {
		allowChat();

		this.mockMvc.perform(overlongRequest().header("Accept-Language", "en-US"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCodes.MESSAGE_TOO_LONG))
			.andExpect(jsonPath("$.message").value("Message exceeds the length limit"))
			// 数字仍在结构字段里：本地化不吞掉可定位信息
			.andExpect(jsonPath("$.length").value(8193))
			.andExpect(jsonPath("$.limit").value(8192));
	}

	@Test
	void languageOnlyEnglishHeaderIsTreatedAsEnglish() throws Exception {
		allowChat();

		this.mockMvc.perform(overlongRequest().header("Accept-Language", "en"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("Message exceeds the length limit"));
	}

	@Test
	void regionalEnglishHeaderIsTreatedAsEnglish() throws Exception {
		allowChat();

		this.mockMvc.perform(overlongRequest().header("Accept-Language", "en-GB"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("Message exceeds the length limit"));
	}

	@Test
	void traditionalChineseHeaderFallsBackToChinese() throws Exception {
		allowChat();

		this.mockMvc.perform(overlongRequest().header("Accept-Language", "zh-TW"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value(containsString("消息长度")));
	}

	// ── 429 RATE_LIMITED ────────────────────────────────────────────────────

	@Test
	void rateLimitedIsEnglishWithHeader() throws Exception {
		allowChat();
		refuseRateLimit();

		this.mockMvc.perform(chatRequest().header("Accept-Language", "en-US"))
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value(ErrorCodes.RATE_LIMITED))
			.andExpect(jsonPath("$.message").value("Too many requests, please retry later"))
			.andExpect(jsonPath("$.retryAfterSeconds").value(30));
	}

	@Test
	void rateLimitedStaysChineseWithoutHeader() throws Exception {
		allowChat();
		refuseRateLimit();

		this.mockMvc.perform(chatRequest())
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.message").value(containsString("对话请求过于频繁")));
	}

	// ── 503 三出口 ──────────────────────────────────────────────────────────

	@Test
	void aiUnavailableIsEnglishWithHeader() throws Exception {
		when(this.availability.isAvailable()).thenReturn(false);
		when(this.availability.code()).thenReturn(ErrorCodes.AI_UNAVAILABLE);
		when(this.availability.reason()).thenReturn("AI 能力未启用：已配置的 provider 均无可用 key");

		this.mockMvc.perform(chatRequest().header("Accept-Language", "en-US"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value(ErrorCodes.AI_UNAVAILABLE))
			.andExpect(jsonPath("$.message").value("AI features are unavailable"));
	}

	@Test
	void aiKeyMissingIsEnglishWithHeaderAndPointsToSettings() throws Exception {
		when(this.availability.isAvailable()).thenReturn(false);
		when(this.availability.code()).thenReturn(ErrorCodes.AI_KEY_MISSING);
		when(this.availability.reason()).thenReturn("AI 能力未启用：请到「设置 → AI」配置供应商与 API Key");

		this.mockMvc.perform(chatRequest().header("Accept-Language", "en-US"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value(ErrorCodes.AI_KEY_MISSING))
			.andExpect(jsonPath("$.message").value(containsString("Settings")))
			.andExpect(jsonPath("$.message").value(not(containsString("AI 能力未启用"))));
	}

	@Test
	void aiKeyUnreadableIsEnglishWithHeaderAndPointsToSettings() throws Exception {
		when(this.availability.isAvailable()).thenReturn(false);
		when(this.availability.code()).thenReturn(ErrorCodes.AI_KEY_UNREADABLE);
		when(this.availability.reason()).thenReturn("AI 能力未启用：请到「设置 → AI」重新录入 API Key");

		this.mockMvc.perform(chatRequest().header("Accept-Language", "en-US"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value(ErrorCodes.AI_KEY_UNREADABLE))
			.andExpect(jsonPath("$.message").value(containsString("Settings")))
			.andExpect(jsonPath("$.message").value(not(containsString("AI 能力未启用"))));
	}

	// ── I-2：/health 503 message 与 /chat 同一 locale 口径 ──────────────────

	@Test
	void healthUnavailableIsEnglishWithHeader() throws Exception {
		when(this.availability.isAvailable()).thenReturn(false);
		when(this.availability.code()).thenReturn(ErrorCodes.AI_KEY_UNREADABLE);
		when(this.availability.reason())
			.thenReturn("AI 能力未启用：请到「设置 → AI」重新录入密钥，或让管理员恢复加密配置");

		this.mockMvc.perform(get("/api/ai/health").header("Accept-Language", "en-US"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value(ErrorCodes.AI_KEY_UNREADABLE))
			.andExpect(jsonPath("$.message").value(containsString("Settings")))
			.andExpect(jsonPath("$.message").value(not(containsString("AI 能力未启用"))))
			.andExpect(jsonPath("$.message").value(not(containsString("AI_SECRET_KEK"))))
			.andExpect(jsonPath("$.keyDiagnostics.envVars[0]").value("AI_SECRET_KEK"));
	}

	@Test
	void healthUnavailableKeepsChineseReasonWithoutHeader() throws Exception {
		when(this.availability.isAvailable()).thenReturn(false);
		when(this.availability.code()).thenReturn(ErrorCodes.AI_KEY_UNREADABLE);
		when(this.availability.reason())
			.thenReturn("AI 能力未启用：请到「设置 → AI」重新录入密钥，或让管理员恢复加密配置");

		this.mockMvc.perform(get("/api/ai/health"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.message").value("AI 能力未启用：请到「设置 → AI」重新录入密钥，或让管理员恢复加密配置"))
			.andExpect(jsonPath("$.message").value(not(containsString("AI_SECRET_KEK"))));
	}

	@Test
	void redisUnavailableIsEnglishWithHeader() throws Exception {		allowChat();
		when(this.redisAvailability.isAvailable()).thenReturn(false);
		when(this.redisAvailability.reason()).thenReturn("AI 服务暂不可用，请稍后重试。");
		when(this.redisAvailability.describeForResponse()).thenReturn(Map.of("available", false,
				"probes", 1L, "failures", 1L));

		this.mockMvc.perform(chatRequest().header("Accept-Language", "en-US"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value(RedisAvailability.code()))
			.andExpect(jsonPath("$.message").value("Redis required by the AI runtime is unavailable"))
			.andExpect(jsonPath("$.message").value(not(containsString("AI 服务暂不可用"))))
			.andExpect(jsonPath("$.redis.lastError").doesNotExist())
			.andExpect(jsonPath("$.redis.available").value(false));
	}

	@Test
	void redisUnavailableStaysChineseWithoutHeader() throws Exception {
		allowChat();
		when(this.redisAvailability.isAvailable()).thenReturn(false);
		when(this.redisAvailability.reason()).thenReturn("AI 服务暂不可用，请稍后重试。");
		when(this.redisAvailability.describeForResponse()).thenReturn(Map.of("available", false));

		this.mockMvc.perform(chatRequest())
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.message").value("AI 服务暂不可用，请稍后重试。"))
			.andExpect(jsonPath("$.redis.lastError").doesNotExist());
	}

	@Test
	void poolSaturatedIsEnglishWithHeader() throws Exception {
		ThreadPoolTaskExecutor rejecting = mock(ThreadPoolTaskExecutor.class);
		doThrow(new RejectedExecutionException("full")).when(rejecting).execute(any(Runnable.class));
		AiController saturated = controller(rejecting);
		MockMvc mvc = MockMvcBuilders.standaloneSetup(saturated)
			.setLocaleResolver(new WebI18nConfig().localeResolver())
			.build();
		allowChat(saturated);

		mvc.perform(chatRequest().header("Accept-Language", "en-US"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value(ErrorCodes.SUSPEND_POOL_SATURATED))
			.andExpect(jsonPath("$.message").value("Suspend pool is saturated, retry later"))
			.andExpect(jsonPath("$.poolSize").exists());
	}

	@Test
	void poolSaturatedStaysChineseWithoutHeader() throws Exception {
		ThreadPoolTaskExecutor rejecting = mock(ThreadPoolTaskExecutor.class);
		doThrow(new RejectedExecutionException("full")).when(rejecting).execute(any(Runnable.class));
		AiController saturated = controller(rejecting);
		MockMvc mvc = MockMvcBuilders.standaloneSetup(saturated)
			.setLocaleResolver(new WebI18nConfig().localeResolver())
			.build();
		allowChat(saturated);

		mvc.perform(chatRequest())
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.message").value(containsString("挂起专用线程池")));
	}

	// ── R-16：/context 503（去 Java 类名 + 同一本地化） ─────────────────────

	@Test
	void contextUnavailableIsEnglishWithHeader() throws Exception {
		when(this.redisAvailability.isAvailable()).thenReturn(true);

		this.mockMvc.perform(get("/api/ai/context/" + SESSION).header("Accept-Language", "en-US"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value(ErrorCodes.AI_CONTEXT_UNAVAILABLE))
			.andExpect(jsonPath("$.message").value("Context accounting is unavailable"));
	}

	@Test
	void contextUnavailableChineseMessageCarriesNoJavaClassName() throws Exception {
		when(this.redisAvailability.isAvailable()).thenReturn(true);

		this.mockMvc.perform(get("/api/ai/context/" + SESSION))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.message").value(containsString("上下文计量")))
			.andExpect(jsonPath("$.message").value(not(containsString("ContextBreakdownService"))));
	}

	// ── reset / no-leak：工作线程 locale 不得留在池里 ────────────────────────

	@Test
	void englishHeaderReachesTheWorkerThreadThroughTheResolver() throws Exception {
		allowChat();
		AtomicReference<String> workerLanguage = new AtomicReference<>();
		org.mockito.Mockito.doAnswer(invocation -> {
			workerLanguage.set(ErrorBodyFactory.requestLocale().getLanguage());
			return null;
		}).when(this.chatService).chat(anyString(), any(AiChatRequest.class), any(SseChatEmitter.class));

		// 语言级范围 `en`（M-3）：resolver → LocaleContextHolder（HTTP 线程）→ 控制器搬运 → 工作线程。
		this.mockMvc.perform(chatRequest().header("Accept-Language", "en"))
			.andExpect(status().isOk());

		verify(this.chatService, timeout(2000)).chat(anyString(), any(AiChatRequest.class),
				any(SseChatEmitter.class));
		assertThat(workerLanguage.get()).as("工作线程必须看到 en（语言段即由它决定）").isEqualTo("en");
	}

	@Test
	void workerThreadLocaleIsResetAfterTheTurn() throws Exception {
		allowChat();
		LocaleContextHolder.setLocale(Locale.US);
		try {
			this.controller.chat(new AiChatRequest(SESSION, "hello", null), null);
			verify(this.chatService, timeout(2000)).chat(anyString(), any(AiChatRequest.class),
					any(SseChatEmitter.class));
		}
		finally {
			LocaleContextHolder.resetLocaleContext();
		}

		// 复用池线程（core=max=1）：若 finally 没有 resetLocaleContext，这里读到的会是 en
		AtomicReference<String> language = new AtomicReference<>();
		CountDownLatch done = new CountDownLatch(1);
		this.executor.execute(() -> {
			language.set(ErrorBodyFactory.requestLocale().getLanguage());
			done.countDown();
		});
		assertThat(done.await(2, TimeUnit.SECONDS)).as("探针任务必须跑完").isTrue();
		assertThat(language.get()).as("轮次结束必须清掉工作线程上的 locale（池会复用线程）").isEqualTo("zh");
	}

	// ── 装配 ────────────────────────────────────────────────────────────────

	private static ThreadPoolTaskExecutor executor() {
		ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
		pool.setCorePoolSize(1);
		pool.setMaxPoolSize(1);
		pool.setQueueCapacity(64);
		pool.initialize();
		return pool;
	}

	private static ErrorBodyFactory factory() {
		ResourceBundleMessageSource source = new ResourceBundleMessageSource();
		source.setBasename("messages");
		source.setDefaultEncoding(StandardCharsets.UTF_8.name());
		source.setFallbackToSystemLocale(false);
		var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
		beans.registerSingleton(org.springframework.context.MessageSource.class.getName(), source);
		return new ErrorBodyFactory(beans.getBeanProvider(org.springframework.context.MessageSource.class));
	}

	private AiController controller(ThreadPoolTaskExecutor runExecutor) {
		AiController built = new AiController(this.availability, providerOf(this.chatService),
				providerOf((com.example.configmgr.ai.run.ResumeService) null),
				providerOf((com.example.configmgr.ai.run.StartupResumeRunner) null), mock(ChatMemory.class),
				mock(ChatMemoryRepository.class), this.sessionGate, mock(ConfirmGate.class), mock(RunStore.class),
				this.runRegistry, mock(CancellationRegistry.class), this.redisAvailability, runExecutor,
				new ObjectMapper(), this.properties, mock(Environment.class),
				mock(com.example.configmgr.job.service.JobWatchdog.class));
		ReflectionTestUtils.setField(built, "errorBodyFactory", factory());
		ReflectionTestUtils.setField(built, "inputSanitizer", new InputSanitizer());
		ReflectionTestUtils.setField(built, "aiRateLimiter", mock(AiRateLimiter.class));
		ReflectionTestUtils.setField(built, "contextFieldGate", new ContextFieldGate());
		ReflectionTestUtils.setField(built, "securityEvents", new SecurityEvents());
		when(this.runRegistry.of(any())).thenReturn(this.out);
		when(this.sessionGate.acquire(any(), any())).thenReturn(null);
		return built;
	}

	/** 通过 /chat 的"能继续往下走"的前置：AI 可用 + Redis 可用 + 限流放行。 */
	private void allowChat() {
		allowChat(this.controller);
	}

	private void allowChat(AiController target) {
		when(this.availability.isAvailable()).thenReturn(true);
		when(this.redisAvailability.isAvailable()).thenReturn(true);
		AiRateLimiter limiter = (AiRateLimiter) ReflectionTestUtils.getField(target, "aiRateLimiter");
		when(limiter.check(any(), any())).thenReturn(new AiRateLimiter.Decision(true, AiRateLimiter.SCOPE_NONE,
				1, 20, 0L, false));
	}

	private void refuseRateLimit() {
		AiRateLimiter limiter = (AiRateLimiter) ReflectionTestUtils.getField(this.controller, "aiRateLimiter");
		when(limiter.check(any(), any())).thenReturn(new AiRateLimiter.Decision(false, AiRateLimiter.SCOPE_SESSION,
				20, 20, 30_000L, false));
	}

	private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder overlongRequest() {
		return chatRequest("中".repeat(8193));
	}

	private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder chatRequest() {
		return chatRequest("你好");
	}

	private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder chatRequest(String message) {
		return post("/api/ai/chat").contentType(MediaType.APPLICATION_JSON)
			.content("{\"sessionId\":\"" + SESSION + "\",\"message\":\"" + message + "\"}");
	}

	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> providerOf(T bean) {
		ObjectProvider<T> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(bean);
		return provider;
	}
}
