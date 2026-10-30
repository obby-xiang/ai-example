package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.FakeRateRedis;
import com.example.configmgr.ai.gate.InputSanitizer;
import com.example.configmgr.ai.gate.AiRateLimiter;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.LeaseService;
import com.example.configmgr.ai.run.ResilientChatService;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.session.SessionGate;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批G · G6：**僵尸轮不拦发送**（会话门放行）。
 *
 * <p>
 * 现场：上一轮所在进程死在模型调用中间，会话门（进程内 + Redis）都还留着它的 runId ⇒ 用户
 * 每次发送都 409，只能刷新（实证 D10 / 场景 3-4 的锁死）。放行判据见 {@code RunLiveness}：
 * 快照已终态或早已无活动、且无续跑租约、且本进程没有活连接。
 *
 * <p>
 * 反向红线：**活轮照样 409** —— 同一会话并发两轮会把记忆写乱，并让前一轮的 SSE 变成孤儿。
 */
class AiControllerZombieGateTest {

	private static final String SESSION = "s-zombie";

	private final AiProperties properties = new AiProperties();

	private final ResilientChatService service = mock(ResilientChatService.class);

	private final SseChatEmitter out = mock(SseChatEmitter.class);

	private final RunRegistry runRegistry = mock(RunRegistry.class);

	private final RunStore runStore = mock(RunStore.class);

	private final SessionGate sessionGate = mock(SessionGate.class);

	private final LeaseService leases = mock(LeaseService.class);

	private final FakeRateRedis redis = new FakeRateRedis();

	private final AiController controller = controller();

	@Test
	void deadHolderIsReleasedAndChatProceeds() {
		String deadRun = "run-dead";
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(deadRun);
		snapshot.setSessionId(SESSION);
		snapshot.setStatus(RunSnapshot.RUNNING);
		snapshot.setUpdatedAtMs(System.currentTimeMillis() - 10 * 60_000L);
		when(this.runStore.get(deadRun)).thenReturn(snapshot);
		when(this.runStore.lastActivityAtMs(deadRun)).thenReturn(0L);
		when(this.runStore.runLeaseKey(deadRun)).thenReturn("ai:lease:run:" + deadRun);
		when(this.runStore.leaseService()).thenReturn(this.leases);
		when(this.leases.isHeldStrict("ai:lease:run:" + deadRun)).thenReturn(false);
		when(this.runRegistry.hasLiveSubscriber(deadRun)).thenReturn(false);
		when(this.sessionGate.acquire(eq(SESSION), anyString()))
			.thenReturn(new SessionGate.Busy(deadRun, false), null);

		ResponseEntity<?> response = this.controller.chat(new AiChatRequest(SESSION, "继续问", null), null);

		assertThat(response.getStatusCode()).as("死轮不拦发送").isEqualTo(HttpStatus.OK);
		verify(this.sessionGate).releaseDeadHeld(SESSION, deadRun);
	}

	@Test
	void liveHolderStillConflicts() {
		String liveRun = "run-live";
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(liveRun);
		snapshot.setSessionId(SESSION);
		snapshot.setStatus(RunSnapshot.RUNNING);
		snapshot.setUpdatedAtMs(System.currentTimeMillis());
		when(this.runStore.get(liveRun)).thenReturn(snapshot);
		when(this.runRegistry.hasLiveSubscriber(liveRun)).thenReturn(true);
		when(this.runStore.runLeaseKey(liveRun)).thenReturn("ai:lease:run:" + liveRun);
		when(this.sessionGate.acquire(any(), any())).thenReturn(new SessionGate.Busy(liveRun, false));

		ResponseEntity<?> response = this.controller.chat(new AiChatRequest(SESSION, "再说一句", null), null);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		verify(this.sessionGate, never()).release(any(), any());
	}

	/**
	 * I-4 / R1-09：RUNNING 只看<b>业务</b>活动 —— 心跳新鲜不能把“业务已卡死”的轮判活。
	 *
	 * <p>修复前（统一用 lastEventAtMs）这条会 409；修复后必须放行，让用户能重新发送。
	 */
	@Test
	void runningRoundWithFreshHeartbeatButStaleBusinessIsReleased() {
		String stalledRun = "run-stalled";
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(stalledRun);
		snapshot.setSessionId(SESSION);
		snapshot.setStatus(RunSnapshot.RUNNING);
		snapshot.setUpdatedAtMs(System.currentTimeMillis() - 10 * 60_000L);
		when(this.runStore.get(stalledRun)).thenReturn(snapshot);
		when(this.runStore.lastActivityAtMs(stalledRun)).thenReturn(System.currentTimeMillis() - 10 * 60_000L);
		when(this.runStore.lastEventAtMs(stalledRun)).thenReturn(System.currentTimeMillis());
		when(this.runStore.runLeaseKey(stalledRun)).thenReturn("ai:lease:run:" + stalledRun);
		when(this.runStore.leaseService()).thenReturn(this.leases);
		when(this.leases.isHeldStrict("ai:lease:run:" + stalledRun)).thenReturn(false);
		when(this.runRegistry.hasLiveSubscriber(stalledRun)).thenReturn(false);
		when(this.sessionGate.acquire(eq(SESSION), anyString()))
			.thenReturn(new SessionGate.Busy(stalledRun, false), null);

		ResponseEntity<?> response = this.controller.chat(new AiChatRequest(SESSION, "重新问", null), null);

		assertThat(response.getStatusCode()).as("心跳不得掩盖 RUNNING 的业务卡死").isEqualTo(HttpStatus.OK);
		verify(this.sessionGate).releaseDeadHeld(SESSION, stalledRun);
	}

	/**
	 * <b>park 批（2026-10-25）口径变更</b>：SUSPENDED 的活动证据只认<b>真实订阅</b>与
	 * <b>业务活动锚点</b>（含读侧 touch）—— 自写 {@code ai:beat} 心跳与空写出器条目不再算活。
	 *
	 * <p>为什么改：park 之后等人期间既没有等待线程也不再心跳；旧判据"新鲜心跳 = 还在等"会让
	 * 一个没有任何人管的挂起轮<b>永久 409</b>。而真正有人连着的轮由
	 * {@code AiControllerParkWiringTest#liveSubscriberStillKeepsTheSessionBusy} 锁住（仍 409）。
	 */
	@Test
	void suspendedRoundWithOnlyAHeartbeatIsReleased() {
		String waitingRun = "run-waiting";
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(waitingRun);
		snapshot.setSessionId(SESSION);
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		snapshot.setUpdatedAtMs(System.currentTimeMillis() - 10 * 60_000L);
		when(this.runStore.get(waitingRun)).thenReturn(snapshot);
		when(this.runStore.lastActivityAtMs(waitingRun)).thenReturn(0L);
		when(this.runStore.lastEventAtMs(waitingRun)).thenReturn(System.currentTimeMillis());
		when(this.runStore.runLeaseKey(waitingRun)).thenReturn("ai:lease:run:" + waitingRun);
		when(this.runStore.leaseService()).thenReturn(this.leases);
		when(this.leases.isHeldStrict("ai:lease:run:" + waitingRun)).thenReturn(false);
		when(this.runRegistry.hasLiveSubscriber(waitingRun)).thenReturn(false);
		when(this.sessionGate.acquire(eq(SESSION), anyString()))
			.thenReturn(new SessionGate.Busy(waitingRun, false), null);

		ResponseEntity<?> response = this.controller.chat(new AiChatRequest(SESSION, "插队", null), null);

		assertThat(response.getStatusCode()).as("没有真实订阅/业务活动 ⇒ park 的挂起轮不得永久占着会话门")
			.isEqualTo(HttpStatus.OK);
		verify(this.sessionGate).releaseDeadHeld(SESSION, waitingRun);
	}

	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> providerOf(T bean) {
		ObjectProvider<T> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(bean);
		return provider;
	}

	private AiController controller() {
		AiAvailability availability = mock(AiAvailability.class);
		when(availability.isAvailable()).thenReturn(true);
		RedisAvailability redisAvailability = mock(RedisAvailability.class);
		when(redisAvailability.isAvailable()).thenReturn(true);
		when(this.runRegistry.of(any())).thenReturn(this.out);

		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		executor.setQueueCapacity(16);
		executor.initialize();

		AiController built = new AiController(availability, providerOf(this.service),
				providerOf((com.example.configmgr.ai.run.ResumeService) null),
				providerOf((com.example.configmgr.ai.run.StartupResumeRunner) null), mock(ChatMemory.class),
				mock(ChatMemoryRepository.class), this.sessionGate, mock(ConfirmGate.class), this.runStore,
				this.runRegistry, mock(CancellationRegistry.class), redisAvailability, executor, new ObjectMapper(),
				this.properties, mock(Environment.class), mock(com.example.configmgr.job.service.JobWatchdog.class));
		ReflectionTestUtils.setField(built, "inputSanitizer", new InputSanitizer());
		ReflectionTestUtils.setField(built, "aiRateLimiter", new AiRateLimiter(this.redis.redis, this.properties));
		ReflectionTestUtils.setField(built, "contextFieldGate", new com.example.configmgr.ai.gate.ContextFieldGate());
		return built;
	}
}
