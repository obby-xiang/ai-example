package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiConfigService;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.TenantUser;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 定时续跑的身份恢复（租户任务3）：运行期死轮扫描跑在定时线程上，那里<b>没有</b>请求身份。
 *
 * <p>身份唯一来源 = 该轮快照（{@code tenantId/userId}）。本用例锁两点：
 * <ol>
 * <li>调 {@code ResumeService.resume} 之前，扫描线程已经处于本轮快照的身份作用域里 ——
 *     续跑入口里的任何数据操作（重建/结算/出帧）都在原身份下执行，而不是"无身份"或"上一位调用者"；</li>
 * <li>扫描结束（无论受理与否）定时线程不得残留身份 —— 下一拍可能处理另一个租户的轮次。</li>
 * </ol>
 *
 * <p><b>证红</b>：不在扫描循环里按快照 {@code useContext} ⇒ 续跑侧看到的是 {@code null}（红）。
 */
class ScheduledResumeIdentityTest {

	private static final String RUN_ID = "run-scheduled-identity";

	private static final long TENANT_ID = 9001L;

	private static final long USER_ID = 7L;

	@Test
	void runtimeSweepRestoresRunIdentityFromSnapshotBeforeResume() {
		RunStore store = mock(RunStore.class);
		ResumeService resumeService = mock(ResumeService.class);
		AiProperties properties = new AiProperties();
		AiConfigService configService = mock(AiConfigService.class);
		when(configService.availability())
			.thenReturn(new AiConfigService.Availability(true, "AI_AVAILABLE", "ok"));
		AiAvailability availability = new AiAvailability(configService);

		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(RUN_ID);
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		snapshot.setTenantId(TENANT_ID);
		snapshot.setUserId(USER_ID);
		when(store.get(RUN_ID)).thenReturn(snapshot);
		when(store.instanceId()).thenReturn("test-instance");
		when(store.runLeaseKey(RUN_ID)).thenReturn("ai:lease:run:" + RUN_ID);
		when(store.leaseService()).thenReturn(null);

		long now = System.currentTimeMillis();
		Map<String, Object> candidate = new LinkedHashMap<>();
		candidate.put("runId", RUN_ID);
		candidate.put("status", RunSnapshot.SUSPENDED);
		candidate.put("cancelled", false);
		candidate.put("unresolvedExternal", List.of());
		candidate.put("zombieRunning", false);
		candidate.put("updatedAtMs", now - 600_000L);
		candidate.put("activeAtMs", now - 600_000L);
		when(resumeService.listRuns()).thenReturn(List.of(candidate));

		AtomicReference<TenantUser> observed = new AtomicReference<>();
		when(resumeService.resume(eq(RUN_ID), anyBoolean())).thenAnswer(invocation -> {
			observed.set(RequestContextUtils.currentOrNull());
			return new ResumeService.ResumeResult(ResumeService.Outcome.ACCEPTED, Map.of());
		});

		assertThat(RequestContextUtils.currentOrNull()).as("前置：用例线程本无身份").isNull();

		StartupResumeRunner runner = new StartupResumeRunner(resumeService, store, properties, availability);
		Map<String, Object> report = runner.sweep();

		assertThat((List<?>) report.get("resumed")).as("这一轮应被判死并交给续跑漏斗").hasSize(1);
		assertThat(observed.get()).as("定时续跑必须以快照身份进入续跑入口")
			.isEqualTo(new TenantUser(TENANT_ID, USER_ID));
		assertThat(RequestContextUtils.currentOrNull()).as("扫描结束不得在定时线程残留身份").isNull();
	}

}
