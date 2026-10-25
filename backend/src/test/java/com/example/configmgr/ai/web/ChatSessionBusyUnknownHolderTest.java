package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiAvailability;
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
import com.example.configmgr.ai.session.SessionGate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 409 {@code SESSION_BUSY} 在<b>持有者不可知</b>（{@link SessionGate.Busy#unknown()}）时的响应体。
 *
 * <h2>缺陷</h2>
 * {@code AiController#chat} 此前无条件拼 {@code "reattach" = "/api/ai/events/" + busy.runId()}：
 * 诊断 GET 失败/锁已消失时 {@code busy.runId() == null} ⇒ 对外给出
 * <b>{@code /api/ai/events/null}</b> 这个<b>不存在</b>的流地址（ADR-5 的 reattach 线索承诺被
 * 一条假地址顶替）。同一次调用还会把 {@code null} 当 runId 送进僵尸轮判定
 * （{@code RunStore.get(null)} / 租约键），Redis 抖动时足以把"如实 409"变成异常。
 *
 * <h2>最小安全口径（不改状态码、不新增语义）</h2>
 * <ul>
 * <li>仍是 <b>409 + {@code SESSION_BUSY}</b> + {@code degraded=true}（"被占用"这个事实不变，
 * 调用方既有的重试/退避照常可恢复，无粘滞）；</li>
 * <li>额外回 {@code unknown=true} 让消费方能区分"不知道是谁"与"知道是谁"；</li>
 * <li><b>不写</b> {@code runId} 与 {@code reattach}：没有可指向的轮，就不编造一个；</li>
 * <li>不做僵尸轮判定（没有 runId 可判，也不拿 null 去读 Redis）。</li>
 * </ul>
 *
 * <p>kill：把 {@code reattach}/{@code runId} 改回无条件写入 ⇒
 * {@code unknownHolderYieldsNoFabricatedStreamPointer} 变红；把 {@code isDeadRound} 的
 * {@code !busy.unknown()} 守卫去掉 ⇒ 同例的 {@code verifyNoInteractions} 变红。
 */
class ChatSessionBusyUnknownHolderTest {

	private static final String SESSION = "s-busy-unknown-holder";

	private final AiProperties properties = new AiProperties();

	private final ResilientChatService service = mock(ResilientChatService.class);

	private final RunRegistry runRegistry = mock(RunRegistry.class);

	private final FakeRateRedis redis = new FakeRateRedis();

	private final SessionGate sessionGate = mock(SessionGate.class);

	private final AiAvailability availability = mock(AiAvailability.class);

	private final RedisAvailability redisAvailability = mock(RedisAvailability.class);

	private final RunStore runStore = mock(RunStore.class);

	private AiController controller;

	@BeforeEach
	void setUp() {
		when(this.availability.isAvailable()).thenReturn(true);
		when(this.redisAvailability.isAvailable()).thenReturn(true);
		when(this.sessionGate.acquire(any(), any())).thenReturn(null);
		this.controller = controller();
	}

	@Test
	@SuppressWarnings("unchecked")
	void unknownHolderYieldsNoFabricatedStreamPointer() {
		when(this.sessionGate.acquire(any(), any())).thenReturn(new SessionGate.Busy(null, true, true));

		ResponseEntity<?> response = this.controller.chat(new AiChatRequest(SESSION, "你好", null), null);

		assertThat(response.getStatusCode()).as("状态码不变：仍是「被占用」").isEqualTo(HttpStatus.CONFLICT);
		Map<String, Object> body = (Map<String, Object>) response.getBody();
		assertThat(body).isNotNull();
		assertThat(body).containsEntry("code", "SESSION_BUSY")
			.containsEntry("sessionId", SESSION)
			.containsEntry("degraded", true)
			.containsEntry("unknown", true);
		assertThat(body).as("持有者不可知 ⇒ 不得编造 runId（旧实现写 null）").doesNotContainKey("runId");
		assertThat(body).as("持有者不可知 ⇒ 不得编造 /api/ai/events/null").doesNotContainKey("reattach");
		verifyNoInteractions(this.runStore);
	}

	/** 反例（防"一律去掉 reattach"式过度修复）：持有者可知时 ADR-5 的线索必须照旧。 */
	@Test
	@SuppressWarnings("unchecked")
	void knownHolderStillCarriesTheReattachPointer() {
		when(this.sessionGate.acquire(any(), any())).thenReturn(new SessionGate.Busy("run-holder", true));

		ResponseEntity<?> response = this.controller.chat(new AiChatRequest(SESSION, "你好", null), null);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		Map<String, Object> body = (Map<String, Object>) response.getBody();
		assertThat(body).isNotNull();
		assertThat(body).containsEntry("runId", "run-holder")
			.containsEntry("reattach", "/api/ai/events/run-holder")
			.containsEntry("degraded", true);
		assertThat(body).as("持有者可知时不得带 unknown 键").doesNotContainKey("unknown");
	}

	private AiController controller() {
		org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor =
				new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		executor.setQueueCapacity(256);
		executor.initialize();
		AiController built = new AiController(this.availability, providerOf(this.service),
				providerOf((com.example.configmgr.ai.run.ResumeService) null),
				providerOf((com.example.configmgr.ai.run.StartupResumeRunner) null), mock(ChatMemory.class),
				mock(ChatMemoryRepository.class), this.sessionGate, mock(ConfirmGate.class), this.runStore,
				this.runRegistry, mock(CancellationRegistry.class), this.redisAvailability, executor,
				new ObjectMapper(), this.properties, mock(Environment.class),
				mock(com.example.configmgr.job.service.JobWatchdog.class));
		ReflectionTestUtils.setField(built, "inputSanitizer", new InputSanitizer());
		ReflectionTestUtils.setField(built, "aiRateLimiter", new AiRateLimiter(this.redis.redis, this.properties));
		ReflectionTestUtils.setField(built, "contextFieldGate", new com.example.configmgr.ai.gate.ContextFieldGate());
		ReflectionTestUtils.setField(built, "securityEvents", new com.example.configmgr.ai.run.SecurityEvents());
		return built;
	}

	private static <T> ObjectProvider<T> providerOf(T value) {
		return new ObjectProvider<>() {
			@Override
			public T getObject(Object... args) {
				return value;
			}

			@Override
			public T getIfAvailable() {
				return value;
			}

			@Override
			public T getIfUnique() {
				return value;
			}

			@Override
			public T getObject() {
				return value;
			}
		};
	}

}
