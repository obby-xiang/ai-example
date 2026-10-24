package com.example.configmgr.ai.run;

import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.context.ContextCompactor;
import com.example.configmgr.ai.context.ContextEstimator;
import com.example.configmgr.ai.gate.SpToolCallingManager;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R1-09：僵尸判定必须以<b>业务活动</b>为锚，而不是心跳或已冻结的旧 LIST。
 *
 * <p>
 * 配置允许两类合法静默：
 * <ul>
 * <li>{@code app.ai.tool.exec-timeout = 120s} —— 后端工具执行期间不出任何业务帧；</li>
 * <li>{@code app.ai.resilience.inter-event-timeout = 90s} —— 上游两次事件之间可以静默这么久。</li>
 * </ul>
 * 判死阈值比它们短、或把心跳当业务活动，都会把活轮判成僵尸并接管（同一轮执行两遍）。
 * 本用例走真实的 {@link ResumeService#listRuns()} 口径，锁四种现场：
 * 慢工具、慢吐字、只有心跳的卡死、真僵尸。
 */
class ResumeServiceZombieThresholdTest {

	private static final String RUN_ID = "run-zombie";

	private static final String SESSION_ID = "s-zombie";

	private final AiProperties properties = new AiProperties();

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ResumeService service = new ResumeService(this.store, mock(RunRegistry.class),
			mock(MessageJsonCodec.class), mock(ResilientChatService.class), mock(SpToolCallingManager.class),
			mock(ThreadPoolTaskExecutor.class), mock(StringRedisTemplate.class), this.properties, this.cancellations,
			mock(ToolResultLimiter.class));

	ResumeServiceZombieThresholdTest() {
		when(this.cancellations.isCancelled(any())).thenReturn(false);
	}

	@Test
	void aLongRunningToolIsNotTakenOver() {
		RunSnapshot snapshot = seedRunningWithLastBusinessFrame(Duration.ofSeconds(120));

		Map<String, Object> item = onlyItem();

		assertThat(snapshot.getStatus()).isEqualTo(RunSnapshot.RUNNING);
		assertThat(item.get("zombieRunning"))
			.as("工具还在 exec-timeout(120s) 的合法执行窗口内，不得判僵尸")
			.isEqualTo(false);
		assertThat(item.get("resumeable")).as("活轮不可被接管").isEqualTo(false);
	}

	@Test
	void aLongStreamingSilenceWithinInterEventTimeoutIsNotTakenOver() {
		seedRunningWithLastBusinessFrame(Duration.ofSeconds(90));

		Map<String, Object> item = onlyItem();

		assertThat(item.get("zombieRunning"))
			.as("上下游事件间静默 90s 仍在 inter-event-timeout 的合法窗口内")
			.isEqualTo(false);
		assertThat(item.get("resumeable")).isEqualTo(false);
	}

	@Test
	void aStalledRunKeptAliveOnlyByHeartbeatsIsStillAZombie() {
		seedRunningWithLastBusinessFrame(Duration.ofMinutes(5));
		// 进程还在发心跳（挂起期 2s / 流式期 5s 一拍），但业务早已不前进
		this.store.touchActivity(RUN_ID);

		Map<String, Object> item = onlyItem();

		assertThat(((Number) item.get("activeAgeMs")).longValue())
			.as("心跳不是业务活动：活动年龄必须按最后一条业务帧算")
			.isGreaterThanOrEqualTo(Duration.ofMinutes(5).toMillis() - 1_000L);
		assertThat(item.get("zombieRunning"))
			.as("只有心跳的卡死轮必须能判僵尸（心跳不得掩盖卡死）")
			.isEqualTo(true);
	}

	@Test
	void aTrulyStaleRunningRoundIsTakenOver() {
		seedRunningWithLastBusinessFrame(Duration.ofSeconds(181));

		Map<String, Object> item = onlyItem();

		assertThat(item.get("zombieRunning")).as("超过僵尸窗口 ⇒ 真僵尸").isEqualTo(true);
		assertThat(item.get("resumeable")).as("无未决外部输入 ⇒ 可接管").isEqualTo(true);
	}

	/**
	 * I-4 / M-3：SUSPENDED 的 {@code activeAgeMs} 必须含心跳年龄。
	 *
	 * <p>挂起等人三天的轮本来就没有业务帧；若按业务年龄展示会得到“3 天没动”，而同一条记录
	 * {@code zombieRunning=false}，运维/前端会误读成卡死。心跳才是“还在等”的证据。
	 */
	@Test
	void suspendedRunReportsHeartbeatAgeNotBusinessAge() {
		RunSnapshot snapshot = this.store.create(RUN_ID, SESSION_ID, AiContext.empty(), List.of(),
				mock(MessageJsonCodec.class));
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		snapshot.setMessageJson(List.of());
		snapshot.setUpdatedAtMs(System.currentTimeMillis() - Duration.ofDays(3).toMillis());
		this.store.appendEvent(RUN_ID, Map.of("type", "confirm_request", "seq", 1L, "atMs",
				System.currentTimeMillis() - Duration.ofDays(3).toMillis()));
		this.store.touchActivity(RUN_ID);

		Map<String, Object> item = onlyItem();

		assertThat(((Number) item.get("activeAgeMs")).longValue())
			.as("SUSPENDED 的 activeAgeMs 应按心跳算（否则“挂起很久”会被误读成死轮）")
			.isLessThan(5_000L);
		assertThat(((Number) item.get("lastHeartbeatAtMs")).longValue()).isPositive();
		assertThat(item.get("zombieRunning")).as("SUSPENDED 不参与僵尸接管").isEqualTo(false);
	}

	private RunSnapshot seedRunningWithLastBusinessFrame(Duration businessAge) {
		RunSnapshot snapshot = this.store.create(RUN_ID, SESSION_ID, AiContext.empty(), List.of(),
				mock(MessageJsonCodec.class));
		snapshot.setStatus(RunSnapshot.RUNNING);
		snapshot.setMessageJson(List.of());
		snapshot.setUpdatedAtMs(System.currentTimeMillis() - Duration.ofMinutes(10).toMillis());
		this.store.appendEvent(RUN_ID, Map.of("type", "tool_start", "seq", 1L, "atMs",
				System.currentTimeMillis() - businessAge.toMillis()));
		return snapshot;
	}

	private Map<String, Object> onlyItem() {
		List<Map<String, Object>> runs = this.service.listRuns();
		assertThat(runs).hasSize(1);
		return runs.get(0);
	}

}
