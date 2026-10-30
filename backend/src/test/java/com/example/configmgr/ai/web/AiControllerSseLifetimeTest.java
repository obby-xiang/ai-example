package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.ResilientChatService;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.session.SessionGate;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P1：AI 流的<b>传输层</b>不再有死线 —— 挂起等人（确认门 / {@code generative_form}）可以跨越
 * {@code app.ai.resilience.total-budget}（300s）而不被容器掐断。
 *
 * <p>
 * 判据不靠"真的等 300 秒"：把总预算<b>短路注入</b>成几十毫秒，再等过数个预算周期。
 * 修复前 emitter 的 timeout 就是这个预算值，等价于"人还没点，连接先到期"；修复后
 * {@link SseEmitter#getTimeout()} 是 {@code -1L}（Servlet 3.1 §2.3.3.3：超时 ≤ 0 = 永不到期），
 * 无论预算多短都不触发 {@code onTimeout}。
 *
 * <p>
 * 总预算本身没有消失 —— 它退化为<b>机器预算</b>，判据在 {@code StreamWatchdog}
 * （墙钟 − 挂起等待，见 {@code StreamWatchdogGateWaitBudgetTest}），与传输层解耦。
 */
class AiControllerSseLifetimeTest {

	private final AiProperties properties = new AiProperties();

	private final RunStore runStore = mock(RunStore.class);

	private final RunRegistry runRegistry = mock(RunRegistry.class);

	private final SseChatEmitter out = mock(SseChatEmitter.class);

	private final AiController controller = controller(properties);

	@Test
	void chatEmitterSurvivesPastTheMachineBudgetWhileTheTurnIsSuspended() throws Exception {
		this.properties.getResilience().setTotalBudget(Duration.ofMillis(50));

		ResponseEntity<?> response = this.controller.chat(new AiChatRequest("s-suspend", "等人确认", null), null);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		SseEmitter emitter = (SseEmitter) response.getBody();
		assertThat(emitter.getTimeout()).as("传输层无死线（-1 = Servlet 语义永不到期）").isEqualTo(-1L);

		AtomicBoolean timeoutFired = new AtomicBoolean();
		emitter.onTimeout(() -> timeoutFired.set(true));

		// 挂起跨越 5 个"预算"（短预算注入把 300s 压成 50ms）：连接不得到期
		TimeUnit.MILLISECONDS.sleep(250L);

		assertThat(timeoutFired).as("旧实现会在预算到点时触发 onTimeout").isFalse();
		assertThat(emitter.getTimeout()).isEqualTo(-1L);
	}

	@Test
	void reattachEmitterAlsoHasNoTransportDeadline() {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId("r-reattach");
		snapshot.setSessionId("s-reattach");
		snapshot.setTenantId(1L);
		snapshot.setUserId(101L);
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		when(this.runStore.get("r-reattach")).thenReturn(snapshot);
		when(this.runRegistry.of("r-reattach")).thenReturn(this.out);
		when(this.out.replayAndAttach(any(SseEmitter.class), any(), any(Boolean.class))).thenReturn(0);

		// 重挂是 HTTP 数据面入口：生产里必然带身份（无上下文时归属复核 fail-closed）
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(1L, 101L)) {
			ResponseEntity<?> response = this.controller.events("r-reattach", null);

			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			assertThat(((SseEmitter) response.getBody()).getTimeout()).isEqualTo(-1L);
		}
	}

	private AiController controller(AiProperties props) {
		AiAvailability availability = mock(AiAvailability.class);
		when(availability.isAvailable()).thenReturn(true);
		RedisAvailability redisAvailability = mock(RedisAvailability.class);
		when(redisAvailability.isAvailable()).thenReturn(true);
		SessionGate sessionGate = mock(SessionGate.class);
		when(sessionGate.acquire(any(), any())).thenReturn(null);
		when(this.runRegistry.of(any())).thenReturn(this.out);

		AiController controller = new AiController(availability, providerOf(mock(ResilientChatService.class)),
				providerOf((com.example.configmgr.ai.run.ResumeService) null),
				providerOf((com.example.configmgr.ai.run.StartupResumeRunner) null), mock(ChatMemory.class),
				mock(ChatMemoryRepository.class), sessionGate, mock(ConfirmGate.class), this.runStore, this.runRegistry,
				mock(CancellationRegistry.class), redisAvailability, mock(ThreadPoolTaskExecutor.class),
				new ObjectMapper(), props, mock(Environment.class),
				mock(com.example.configmgr.job.service.JobWatchdog.class));
		// 批① 入口闸门是字段注入（构造器签名不变）：本类要过 chat() 的闸门，故灌入两个组件。
		org.springframework.test.util.ReflectionTestUtils.setField(controller, "inputSanitizer",
				new com.example.configmgr.ai.gate.InputSanitizer());
		org.springframework.test.util.ReflectionTestUtils.setField(controller, "aiRateLimiter",
				new com.example.configmgr.ai.gate.AiRateLimiter(
						new com.example.configmgr.ai.gate.FakeRateRedis().redis, props));
		return controller;
	}

	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> providerOf(T bean) {
		ObjectProvider<T> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(bean);
		return provider;
	}

}
