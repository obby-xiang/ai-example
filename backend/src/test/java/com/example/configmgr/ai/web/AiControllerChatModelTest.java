package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiConfigService;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.gate.AiRateLimiter;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.FakeRateRedis;
import com.example.configmgr.ai.gate.InputSanitizer;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.ResilientChatService;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.session.SessionGate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 批③ {@code POST /api/ai/chat} 的模型/参数闸（设计稿 §3.6 + 矩阵 B3-5/6/7）。
 *
 * <h2>闸的位置与理由</h2>
 * 两条校验都在<b>开流之前</b>（HTTP 400 + 稳定码），且排在限流/长度之后、会话门之前：
 * ① 所有请求都计数（沿用批① "防洪优先"的闸序）；② 被拒的请求不占 in-flight 位。
 *
 * <h2>证红方式</h2>
 * <ul>
 * <li>去掉 {@code MODEL_NOT_FOUND} 分支 ⇒ {@code unknownModelIsRejectedBeforeTheStreamOpens} 红；</li>
 * <li>把 {@code validateParams} 的三段删掉 ⇒ {@code outOfRangeParamsAreRejected} 红（会 200 开流，
 *     脏值到 registry 才被"静默忽略"，用户以为生效了）。</li>
 * </ul>
 */
class AiControllerChatModelTest {

	private static final String SESSION = "s-model";

	private final AiProperties properties = new AiProperties();

	private final ResilientChatService service = mock(ResilientChatService.class);

	private final RunRegistry runRegistry = mock(RunRegistry.class);

	private final SseChatEmitter out = mock(SseChatEmitter.class);

	private final FakeRateRedis redis = new FakeRateRedis();

	private final AiConfigService configService = mock(AiConfigService.class);

	private final AiController controller = controller();

	@Test
	void unknownModelIsRejectedBeforeTheStreamOpens() {
		when(this.configService.resolveChatTarget("ghost-model")).thenReturn(Optional.empty());

		ResponseEntity<?> response = this.controller.chat(
				new AiChatRequest(SESSION, "你好", null, "ghost-model", null), null);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(body(response)).containsEntry("code", "MODEL_NOT_FOUND");
		verify(this.service, org.mockito.Mockito.never()).chat(any(), any(), any());
	}

	@Test
	void requestedModelIdReachesTheService() {
		when(this.configService.resolveChatTarget("deepseek-pro")).thenReturn(Optional.of(target("deepseek-pro")));

		ResponseEntity<?> response = this.controller.chat(
				new AiChatRequest(SESSION, "你好", null, "deepseek-pro", null), null);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		ArgumentCaptor<AiChatRequest> captor = ArgumentCaptor.forClass(AiChatRequest.class);
		verify(this.service, timeout(2000)).chat(any(), captor.capture(), any(SseChatEmitter.class));
		assertThat(captor.getValue().modelId()).isEqualTo("deepseek-pro");
	}

	@Test
	void outOfRangeParamsAreRejected() {
		for (AiChatRequest.Params params : new AiChatRequest.Params[] {
				new AiChatRequest.Params(2.5d, null, null), new AiChatRequest.Params(null, 1.5d, null),
				new AiChatRequest.Params(null, null, 0) }) {
			ResponseEntity<?> response = this.controller.chat(
					new AiChatRequest(SESSION, "你好", null, null, params), null);

			assertThat(response.getStatusCode()).as("越界参数：%s", params).isEqualTo(HttpStatus.BAD_REQUEST);
			assertThat(body(response)).containsEntry("code", "INVALID_PARAM");
		}
	}

	@Test
	void legacyBodyWithoutModelFieldsStillOpensTheStream() {
		// 旧前端（三键体）在新后端上零影响：不查模型、不看参数
		ResponseEntity<?> response = this.controller.chat(new AiChatRequest(SESSION, "你好", null), null);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void unknownJsonKeyIs400WithTheProductionObjectMapper() throws Exception {
		// 口径钉死：生产装配的 ObjectMapper 是裸 new ObjectMapper()（JacksonConfig），
		// 未知键 ⇒ UnrecognizedPropertyException ⇒ 400。standalone 默认的 Spring mapper
		// 关掉了该开关，故这里显式装上<b>生产同款</b> mapper —— 断言才对应真实部署行为。
		MockMvc mockMvc = MockMvcBuilders.standaloneSetup(this.controller)
			.setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()))
			.build();

		mockMvc.perform(post("/api/ai/chat").contentType("application/json")
			.content("{\"sessionId\":\"s\",\"message\":\"m\",\"surprise\":1}"))
			.andExpect(status().isBadRequest());
	}

	// ── 装配 ────────────────────────────────────────────────────────────────

	private AiConfigService.ChatTarget target(String modelId) {
		AiConfigService.ResolvedProvider resolved = new AiConfigService.ResolvedProvider(7L, "deepseek",
				"http://placeholder", "/chat/completions", modelId, "sk-test-0123456789", "fp", false);
		return new AiConfigService.ChatTarget(resolved, modelId, true);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> body(ResponseEntity<?> response) {
		assertThat(response.getBody()).isInstanceOf(Map.class);
		return (Map<String, Object>) response.getBody();
	}

	private AiController controller() {
		when(this.runRegistry.of(any())).thenReturn(this.out);
		AiAvailability availability = mock(AiAvailability.class);
		when(availability.isAvailable()).thenReturn(true);
		RedisAvailability redisAvailability = mock(RedisAvailability.class);
		when(redisAvailability.isAvailable()).thenReturn(true);
		AiController built = new AiController(availability, providerOf(this.service),
				providerOf((com.example.configmgr.ai.run.ResumeService) null),
				providerOf((com.example.configmgr.ai.run.StartupResumeRunner) null), mock(ChatMemory.class),
				mock(ChatMemoryRepository.class), mock(SessionGate.class), mock(ConfirmGate.class),
				mock(RunStore.class), this.runRegistry, mock(CancellationRegistry.class), redisAvailability,
				directExecutor(), new ObjectMapper(), this.properties, mock(Environment.class),
				mock(com.example.configmgr.job.service.JobWatchdog.class));
		ReflectionTestUtils.setField(built, "inputSanitizer", new InputSanitizer());
		ReflectionTestUtils.setField(built, "aiRateLimiter", new AiRateLimiter(this.redis.redis, this.properties));
		ReflectionTestUtils.setField(built, "aiConfigService", this.configService);
		return built;
	}

	/** 同步执行器：{@code service.chat} 的 captor 断言不必等线程调度。 */
	private static ThreadPoolTaskExecutor directExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor() {
			@Override
			public void execute(Runnable task) {
				task.run();
			}
		};
		executor.initialize();
		return executor;
	}

	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> providerOf(T bean) {
		ObjectProvider<T> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(bean);
		return provider;
	}

}
