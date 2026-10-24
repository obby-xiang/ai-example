package com.example.configmgr.ai.config;

import com.example.configmgr.ai.config.db.AiProvider;
import com.example.configmgr.ai.gate.AiRateLimiter;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.FakeRateRedis;
import com.example.configmgr.ai.gate.InputSanitizer;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.ResilientChatService;
import com.example.configmgr.ai.run.ResumeService;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.StartupResumeRunner;
import com.example.configmgr.ai.session.SessionGate;
import com.example.configmgr.ai.web.AiChatRequest;
import com.example.configmgr.ai.web.AiController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B20（动态可用性）：原本 503 的 {@code /chat} 在一次配置变更后下一次就 200 —— <b>不需要重启</b>。
 *
 * <p>
 * <b>证红</b>：把 {@link AiAvailability} 改回"构造期把 env key 快照进字段"（即批①之前的实现）
 * ⇒ 第二个用例（跑的是同一个控制器/同一个 availability 实例）仍然 503 ⇒ 红。
 *
 * <p>
 * 变更机制按设计稿 §3.4：写路径改 DB 行<b>并</b>把 {@code ai_setting['config.version']} 加一
 * （跨实例信号走 Redis {@code ai:cfg:ver}）；读路径按版本重载快照。这里没有真 Redis，
 * 因此走的是"DB 版本比对"那条退化路径 —— 也正是单实例正确性的口径。
 */
class AiAvailabilityDynamicTest {

	private final AiConfigTestFixture fixture = new AiConfigTestFixture();

	private final AiConfigService configService = this.fixture.service();

	private final AiAvailability availability = new AiAvailability(this.configService);

	private final SseChatEmitter out = mock(SseChatEmitter.class);

	private final RunRegistry runRegistry = mock(RunRegistry.class);

	@Test
	void chatIs503WithTheLegacyCodeWhenNoProviderAndNoEnvKey() {
		this.fixture.withoutEnvKey();
		AiController controller = controller();

		ResponseEntity<?> response = chat(controller, new AiChatRequest("s-empty", "你好", null));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(asMap(response).get("code")).isEqualTo("AI_UNAVAILABLE");
		assertThat(asMap(response).get("message"))
			.isEqualTo("AI 能力未启用：请在「设置 → AI」配置供应商与 API Key");
		assertThat(this.availability.isAvailable()).isFalse();
	}

	@Test
	void addingAUsableProviderFlipsTheSameControllerTo200WithoutRestart() {
		// 起点：表非空但这一行没有 key（key_ref=NONE）⇒ 不可用（AI_KEY_MISSING）。
		// 用"行已有、key 不可解析"做起点，刻意避开"空表 + env 合成行"那条路 ——
		// 于是 200 只能来自"配置真的生效了"，而不是 env 兜底。
		AiProvider provider = this.fixture.addDbProvider("custom", "http://custom-endpoint", null);
		provider.setKeyRef(AiProvider.KEY_REF_NONE);
		this.fixture.addModel(provider.getId(), "custom-model", true);
		AiController controller = controller();
		ResponseEntity<?> before = chat(controller, new AiChatRequest("s-flip", "你好", null));
		assertThat(before.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(asMap(before).get("code")).isEqualTo(AiConfigService.CODE_KEY_MISSING);

		// 运行中把这一行改成"从 env 取 key"（写路径语义：改行 + 版本号 +1）
		provider.setKeyRef(AiProvider.KEY_REF_ENV);
		this.fixture.bumpVersion();

		assertThat(this.availability.isAvailable()).as("同一个 availability 实例，不重建").isTrue();
		assertThat(chat(controller, new AiChatRequest("s-flip", "你好", null)).getStatusCode())
			.as("下一次请求就 200（开流），无需重启").isEqualTo(HttpStatus.OK);
		assertThat(chat(controller, new AiChatRequest("s-flip-2", "你好", null)).getBody())
			.isInstanceOf(SseEmitter.class);
	}

	// ── 控制器装配（照抄 AiControllerSseLifetimeTest 的固定参数表） ──────────────

	/**
	 * 合流后 {@code /chat} 多了 {@code HttpServletRequest} 形参（防护批① 取来源 IP 做限流）。
	 * 本用例只关心可用性翻转，来源 IP 与它无关 ⇒ 固定传 {@code null}（{@code clientIp} 归为
	 * {@code "unknown"} 桶，20 次/分对本用例的 3 次请求不构成瓶颈）。
	 */
	private static ResponseEntity<?> chat(AiController controller, AiChatRequest request) {
		return controller.chat(request, null);
	}

	private AiController controller() {
		RedisAvailability redisAvailability = mock(RedisAvailability.class);
		when(redisAvailability.isAvailable()).thenReturn(true);
		SessionGate sessionGate = mock(SessionGate.class);
		when(sessionGate.acquire(any(), any())).thenReturn(null);
		when(this.runRegistry.of(any())).thenReturn(this.out);
		RunStore runStore = mock(RunStore.class);
		AiProperties properties = new AiProperties();

		AiController built = new AiController(this.availability, providerOf(mock(ResilientChatService.class)),
				providerOf((ResumeService) null), providerOf((StartupResumeRunner) null), mock(ChatMemory.class),
				mock(ChatMemoryRepository.class), sessionGate, mock(ConfirmGate.class), runStore, this.runRegistry,
				mock(CancellationRegistry.class), redisAvailability, mock(ThreadPoolTaskExecutor.class),
				new ObjectMapper(), properties, mock(Environment.class),
				mock(com.example.configmgr.job.service.JobWatchdog.class));
		// 合流面：防护批① 的两道闸门是字段注入（构造器签名不动），此处按同一口径灌入实件 ——
		// 不灌会 NPE，而灌成 mock 会让"限流器真的判了一次"这件事失去意义。
		ReflectionTestUtils.setField(built, "inputSanitizer", new InputSanitizer());
		ReflectionTestUtils.setField(built, "aiRateLimiter", new AiRateLimiter(new FakeRateRedis().redis, properties));
		return built;
	}

	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> providerOf(T bean) {
		ObjectProvider<T> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(bean);
		return provider;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> asMap(ResponseEntity<?> response) {
		return (Map<String, Object>) response.getBody();
	}

}
