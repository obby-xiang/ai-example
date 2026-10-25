package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.ResumeService;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.StartupResumeRunner;
import com.example.configmgr.ai.session.SessionGate;
import com.example.configmgr.job.service.JobWatchdog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>park 批的 HTTP 接线</b>（AiController 侧，构造方式照既有 controller 用例：mock + 真线程池）。
 *
 * <h2>三件必须钉住的事</h2>
 * <ol>
 * <li><b>空写出器不再冒充活轮</b>：会话门判死只看<b>真实订阅</b>与<b>业务活动锚点</b>（读侧 touch）。
 * 旧实现把 {@code registry.find != null} 与 {@code ai:beat} 心跳当活证据 —— park 的轮既没有等待线程
 * 也没有心跳，却会因此永久 409；</li>
 * <li><b>挂起态 reattach 回放后收尾</b>：park 的轮没有驱动方，连接不能悬着等人；</li>
 * <li><b>park 后主动 stop 能推动收敛</b>：{@code POST /cancel/{runId}} 对 SUSPENDED 轮触发一次
 * 死卡兜底派发（续跑侧看到取消标志 → 收敛 CANCELLED）。</li>
 * </ol>
 */
class AiControllerParkWiringTest {

	private static final String RUN_ID = "run-park-wiring";

	private static final String SESSION_ID = "s-park-wiring";

	private final AiProperties properties = new AiProperties();

	private final RunStore runStore = mock(RunStore.class);

	private final RunRegistry runRegistry = mock(RunRegistry.class);

	private final ConfirmGate confirmGate = mock(ConfirmGate.class);

	private final SseChatEmitter out = mock(SseChatEmitter.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final AiController controller = controller();

	@Test
	void emptyEmitterDoesNotKeepTheSessionBusyForever() {
		RunSnapshot snapshot = snapshot(RunSnapshot.SUSPENDED);
		when(this.runStore.get(RUN_ID)).thenReturn(snapshot);
		when(this.runStore.lastActivityAtMs(RUN_ID)).thenReturn(0L);
		// 心跳很新（旧实现据此判活）：park 批不再把自写 ai:beat 当活证据
		when(this.runStore.lastEventAtMs(RUN_ID)).thenReturn(System.currentTimeMillis());
		when(this.runRegistry.hasLiveSubscriber(RUN_ID)).thenReturn(false);

		Object dead = ReflectionTestUtils.invokeMethod(this.controller, "isDeadRound", RUN_ID);

		assertThat(dead).as("没有真实订阅、业务活动早已超龄 ⇒ 判死（放行新发送），心跳不作数")
			.isEqualTo(Boolean.TRUE);
		verify(this.runRegistry).recycleIfIdle(RUN_ID);
	}

	@Test
	void liveSubscriberStillKeepsTheSessionBusy() {
		RunSnapshot snapshot = snapshot(RunSnapshot.SUSPENDED);
		when(this.runStore.get(RUN_ID)).thenReturn(snapshot);
		when(this.runStore.lastActivityAtMs(RUN_ID)).thenReturn(0L);
		when(this.runRegistry.hasLiveSubscriber(RUN_ID)).thenReturn(true);

		Object dead = ReflectionTestUtils.invokeMethod(this.controller, "isDeadRound", RUN_ID);

		assertThat(dead).as("有人连着（真实订阅）⇒ 不得并发同会话两轮").isEqualTo(Boolean.FALSE);
	}

	@Test
	void suspendedReattachReplaysThenClosesWhenParkIsEnabled() {
		RunSnapshot snapshot = snapshot(RunSnapshot.SUSPENDED);
		when(this.runStore.get(RUN_ID)).thenReturn(snapshot);
		when(this.runRegistry.of(RUN_ID)).thenReturn(this.out);
		when(this.out.replayAndAttach(any(), any(), anyBoolean())).thenReturn(3);
		when(this.out.completeSubscriptions()).thenReturn(1);

		ResponseEntity<?> response = this.controller.events(RUN_ID, null);

		assertThat(response.getStatusCode().value()).isEqualTo(200);
		verify(this.out, never()).replayTo(any(), any());
		verify(this.out).replayAndAttach(any(), any(), anyBoolean());
		verify(this.out).completeSubscriptions();
	}

	@Test
	void cancelOfAParkedRunTriggersTheResumePath() {
		RunSnapshot snapshot = snapshot(RunSnapshot.SUSPENDED);
		when(this.runStore.get(RUN_ID)).thenReturn(snapshot);
		when(this.confirmGate.triggerResumeForParked(RUN_ID))
			.thenReturn(new ConfirmGate.ResumeTrigger(true, "DISPATCHED", null));

		ResponseEntity<?> response = this.controller.cancel(RUN_ID);

		assertThat(response.getBody()).isInstanceOf(Map.class);
		@SuppressWarnings("unchecked")
		Map<String, Object> body = (Map<String, Object>) response.getBody();
		assertThat(body).containsEntry("parkedResume", "DISPATCHED");
		verify(this.confirmGate).triggerResumeForParked(RUN_ID);
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private RunSnapshot snapshot(String status) {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(RUN_ID);
		snapshot.setSessionId(SESSION_ID);
		snapshot.setStatus(status);
		snapshot.setUpdatedAtMs(System.currentTimeMillis() - 10 * 60 * 1000L);
		return snapshot;
	}

	@SuppressWarnings("unchecked")
	private AiController controller() {
		this.properties.getPark().setEnabled(true);
		AiAvailability availability = mock(AiAvailability.class);
		when(availability.isAvailable()).thenReturn(true);
		RedisAvailability redisAvailability = mock(RedisAvailability.class);
		when(redisAvailability.isAvailable()).thenReturn(true);
		when(this.cancellations.status(anyString())).thenReturn(Map.of("redisFlag", false, "localFlag", false));
		when(this.cancellations.cancel(anyString(), any()))
			.thenReturn(mock(CancellationRegistry.CancelResult.class));
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		executor.setQueueCapacity(8);
		executor.initialize();
		return new AiController(availability, providerOf(null), providerOf((ResumeService) null),
				providerOf((StartupResumeRunner) null), mock(ChatMemory.class), mock(ChatMemoryRepository.class),
				mock(SessionGate.class), this.confirmGate, this.runStore, this.runRegistry,
				this.cancellations, redisAvailability, executor, new ObjectMapper(), this.properties,
				mock(Environment.class), mock(JobWatchdog.class));
	}

	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> providerOf(T bean) {
		ObjectProvider<T> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(bean);
		return provider;
	}

}
