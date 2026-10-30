package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.gate.SpToolCallingManager;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>F3：取消旗标已写、驱动进程已死时，运行期扫描必须把 RUNNING/SUSPENDED 收敛成 CANCELLED</b>。
 *
 * <h2>缺陷</h2>
 * 取消的终结路径全在进程内（驱动线程的写门交棒 / park 退出交棒）；驱动进程 {@code kill -9}
 * 之后，{@code ai:cancel:<tenantId>:<userId>:<runId>} 还在，但没有任何执行体会再看它一眼。
 * {@code StartupResumeRunner#discoveryOf} 对 {@code cancelled=true} 的轮次直接
 * {@code Discovery.CANCELLED} 跳过 —— 于是快照永远停在 RUNNING/SUSPENDED，终帧永远不出现。
 *
 * <h2>本批口径</h2>
 * 扫描发现"取消旗标存在 + 快照非终态"时不跳过，改走既有的
 * {@link ResumeService#cancelConfirmed(String)}（自己抢租约、校验取消事实、按归档终帧幂等），
 * <b>不重新驱动模型</b>、<b>不扫描租户列表</b>（候选仍来自 {@code ai:runs} 索引）。
 * 同一轮重复扫描只收敛一次（归档已有终帧 ⇒ {@code ALREADY_TERMINAL}）。
 */
class StartupResumeRunnerCanceledRunTest {

	private static final String RUN_ID = "run-cancel-dead-driver";

	private static final String SESSION_ID = "s-cancel-dead-driver";

	private static final long TENANT_ID = 1L;

	private static final long USER_ID = 101L;

	private static final Duration STALE = Duration.ofMinutes(10);

	private final AiProperties properties = new AiProperties();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final LeaseService leases = new LeaseService(this.fake.redis, this.properties,
			new FakeRenewScheduler(), "inst-cancel-sweep");

	private final InMemoryRunStore store = new InMemoryRunStore(true);

	/** 终帧是否已归档（取消收尾的幂等判据）。 */
	private final AtomicBoolean archived = new AtomicBoolean();

	private final ResilientChatService chatService = mock(ResilientChatService.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final AiAvailability availability = mock(AiAvailability.class);

	private ResumeService service;

	private StartupResumeRunner runner;

	@BeforeEach
	void setUp() {
		when(this.availability.isAvailable()).thenReturn(true);
		when(this.cancellations.cancelState(RUN_ID)).thenReturn(CancellationRegistry.CancelState.CANCELLED);
		when(this.cancellations.isCancelled(anyString())).thenReturn(true);
		// 取消收尾的"落终态 + 终帧"由驱动侧的 cancelParked 完成：这里用桩复刻它的可判定后果
		// （快照 CANCELLED、归档一条 done）。用例断言的是扫描侧"该不该调用它、调用几次"。
		when(this.chatService.cancelTerminalArchived(RUN_ID)).thenAnswer(invocation -> this.archived.get());
		doAnswer(invocation -> {
			this.archived.set(true);
			RunSnapshot snapshot = this.store.get(RUN_ID);
			if (snapshot != null && !RunSnapshot.isTerminal(snapshot.getStatus())) {
				snapshot.setStatus(RunSnapshot.CANCELLED);
				this.store.save(snapshot);
				this.store.appendEvent(RUN_ID, Map.of("type", "done", "seq", 2L, "cancelled", true));
			}
			return null;
		}).when(this.chatService).cancelParked(RUN_ID);

		this.store.useLeaseFacility(this.leases);
		this.service = new ResumeService(this.store, mock(RunRegistry.class), this.codec, this.chatService,
				mock(SpToolCallingManager.class),
				mock(org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor.class),
				mock(StringRedisTemplate.class), this.properties, this.cancellations,
				new ToolResultLimiter(this.properties));
		this.runner = new StartupResumeRunner(this.service, this.store, this.properties, this.availability);
	}

	@Test
	void sweepFinalizesACanceledRunWhoseDriverIsGoneExactlyOnce() {
		seedZombieRunning();

		Map<String, Object> first = this.runner.sweep();

		assertThat(finalizedRunIds(first)).as("取消旗标存在 + 非终态 ⇒ 扫描必须走取消收尾")
			.contains(RUN_ID);
		assertThat(resumedRunIds(first)).as("取消收尾绝不重新驱动模型").isEmpty();
		assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.CANCELLED);
		assertThat(this.store.archivedTypes(RUN_ID)).as("终帧恰一条").containsOnlyOnce("done");
		verify(this.chatService, times(1)).cancelParked(RUN_ID);

		Map<String, Object> second = this.runner.sweep();

		assertThat(finalizedRunIds(second)).as("已终态 ⇒ 本拍没有可收尾对象").isEmpty();
		assertThat(this.store.archivedTypes(RUN_ID)).as("重复扫描不得补第二条终帧").containsOnlyOnce("done");
		verify(this.chatService, times(1)).cancelParked(RUN_ID);
	}

	/** 取消旗标已写、驱动已死：RUNNING 超龄、无租约、无活订阅。 */
	private void seedZombieRunning() {
		RunSnapshot snapshot = this.store.create(RUN_ID, SESSION_ID, AiContext.empty(), List.of(), this.codec);
		snapshot.setTenantId(TENANT_ID);
		snapshot.setUserId(USER_ID);
		snapshot.setStatus(RunSnapshot.RUNNING);
		snapshot.setMessageJson(this.codec.serializeAll(List.of(new UserMessage("继续"))));
		this.store.save(snapshot);
		long atMs = System.currentTimeMillis() - STALE.toMillis();
		snapshot.setUpdatedAtMs(atMs);
		this.store.appendEvent(RUN_ID, Map.of("type", "tool_start", "seq", 1L, "atMs", atMs));
	}

	@SuppressWarnings("unchecked")
	private static List<String> finalizedRunIds(Map<String, Object> report) {
		return ((List<Map<String, Object>>) report.getOrDefault("finalized", List.of())).stream()
			.map(item -> String.valueOf(item.get("runId")))
			.toList();
	}

	@SuppressWarnings("unchecked")
	private static List<String> resumedRunIds(Map<String, Object> report) {
		return ((List<Map<String, Object>>) report.getOrDefault("resumed", List.of())).stream()
			.map(item -> String.valueOf(item.get("runId")))
			.toList();
	}

}
