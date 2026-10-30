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
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>F4：租约探针读失败不得把活轮判死</b>（运行期扫描侧）。
 *
 * <h2>缺陷</h2>
 * {@link LeaseService#isHeld(String)} 吞掉 {@code hasKey} 的异常返回 {@code false}（对账口径），
 * 而 {@code StartupResumeRunner#deadWithoutDriver} 与 {@code AiController#isDeadRound} 的
 * try/catch 都按"读不出来 ⇒ 抛异常"写：吞掉之后 catch 永远不触发，"不知道"被当成"没人持有"。
 * 于是一次集群抖动（MOVED/TRYAGAIN 超时/单节点故障）就能让仍持租的活轮被判死并接管 = 双驱动。
 *
 * <h2>本批口径</h2>
 * 新增严格读取口（异常上抛）供这两条判死路径使用；原 {@code isHeld} 保留给对账
 * （{@code ToolUnknownReconciler} / {@code ToolResourceLocks}）。
 */
class StartupResumeRunnerLeaseProbeFailureTest {

	private static final String RUN_ZOMBIE = "run-lease-probe-zombie";

	private static final long TENANT_ID = 1L;

	private static final long USER_ID = 101L;

	private static final Duration STALE = Duration.ofMinutes(10);

	private final AiProperties properties = new AiProperties();

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final MessageJsonCodec codec = new MessageJsonCodec(new ObjectMapper());

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ResumeService service = new ResumeService(this.store, mock(RunRegistry.class), this.codec,
			mock(ResilientChatService.class), mock(SpToolCallingManager.class),
			mock(org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor.class),
			mock(StringRedisTemplate.class), this.properties, this.cancellations,
			new ToolResultLimiter(this.properties));

	private final AiAvailability availability = mock(AiAvailability.class);

	private final StartupResumeRunner runner = new StartupResumeRunner(this.service, this.store, this.properties,
			this.availability);

	@BeforeEach
	void setUp() {
		when(this.availability.isAvailable()).thenReturn(true);
	}

	@Test
	void leaseProbeFailureKeepsTheRoundActiveInsteadOfJudgingItDead() {
		// 真 LeaseService + 一个"hasKey 抛设施异常"的 Redis：这正是 isHeld 吞掉的现场。
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		when(redis.hasKey(anyString())).thenThrow(new RedisConnectionFailureException("cluster node down"));
		LeaseService leases = new LeaseService(redis, this.properties, new FakeRenewScheduler(), "inst-probe");
		seedZombie();

		this.store.useLeaseFacility(leases);
		Map<String, Object> report = this.runner.sweep();

		assertThat(resumedRunIds(report)).as("租约读不出来 ≠ 没人持有：不得接管（否则双驱动）").isEmpty();
		assertThat(skippedReasons(report)).containsEntry(RUN_ZOMBIE, "ACTIVE");
		RunSnapshot after = this.store.get(RUN_ZOMBIE);
		assertThat(after.getStatus()).as("活轮不得被改写").isEqualTo(RunSnapshot.RUNNING);
	}

	/** 两个读取口的分工：对账口 fail-open（吞异常返 false），严格口异常上抛（判死路径用）。 */
	@Test
	void strictProbePropagatesTheFailureWhileTheReconcilingProbeStaysFailOpen() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		RedisConnectionFailureException failure = new RedisConnectionFailureException("cluster node down");
		when(redis.hasKey(anyString())).thenThrow(failure);
		LeaseService leases = new LeaseService(redis, this.properties, new FakeRenewScheduler(), "inst-probe");

		assertThat(leases.isHeld("ai:lease:run:{run:r-1}")).as("对账口保持旧口径").isFalse();
		assertThatThrownBy(() -> leases.isHeldStrict("ai:lease:run:{run:r-1}"))
			.as("严格口不得把'不知道'折叠成'没人持有'")
			.isSameAs(failure);
	}

	/** 超龄 RUNNING、无本进程订阅、无租约视图 —— 唯一能救它的是"租约也许还在别人手里"。 */
	private void seedZombie() {
		RunSnapshot snapshot = this.store.create(RUN_ZOMBIE, "s-" + RUN_ZOMBIE, AiContext.empty(), List.of(),
				this.codec);
		snapshot.setTenantId(TENANT_ID);
		snapshot.setUserId(USER_ID);
		snapshot.setStatus(RunSnapshot.RUNNING);
		snapshot.setMessageJson(this.codec.serializeAll(List.of(new UserMessage("继续"))));
		this.store.save(snapshot);
		long atMs = System.currentTimeMillis() - STALE.toMillis();
		snapshot.setUpdatedAtMs(atMs);
		this.store.appendEvent(RUN_ZOMBIE, Map.of("type", "tool_start", "seq", 1L, "atMs", atMs));
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> resumed(Map<String, Object> report) {
		return (List<Map<String, Object>>) report.getOrDefault("resumed", List.of());
	}

	private static List<String> resumedRunIds(Map<String, Object> report) {
		return resumed(report).stream().map(item -> String.valueOf(item.get("runId"))).toList();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, String> skippedReasons(Map<String, Object> report) {
		Map<String, String> out = new java.util.LinkedHashMap<>();
		for (Map<String, Object> item : (List<Map<String, Object>>) report.getOrDefault("skipped", List.of())) {
			out.put(String.valueOf(item.get("runId")), String.valueOf(item.get("reason")));
		}
		return out;
	}

}
