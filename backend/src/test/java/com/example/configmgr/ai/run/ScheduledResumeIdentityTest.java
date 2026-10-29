package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiConfigService;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.TenantUser;
import com.example.configmgr.ai.tool.AiContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * 定时恢复（运行期死轮扫描）的身份链路（租户任务）：扫描跑在 {@code ai-runtime-sweep} 线程上，
 * 那里<b>没有</b>请求身份。
 *
 * <h2>为什么必须是真实 {@link RunStore}</h2>
 * 旧用例用 {@code mock(RunStore)}，{@code store.get(runId)} 恒返回快照 —— 于是"无身份线程上读不到
 * 带身份的轮次"这个真实故障被掩盖成全绿。本用例用真实 RunStore + 真实键形（{@code ai:run:{1:101:run:…}}），
 * 锁两点：
 * <ol>
 * <li><b>发现面</b>：扫描线程（无身份）能看见这条带身份的 SUSPENDED 轮次（来自 {@code ai:runs} 的
 *     复合索引成员，身份随条目携带）；</li>
 * <li><b>恢复面</b>：续跑入口看到的是<b>该轮记录身份</b>，扫描结束线程不留身份。</li>
 * </ol>
 */
class ScheduledResumeIdentityTest {

	private static final String RUN_ID = "run-scheduled-identity";

	private static final String SESSION_ID = "s-scheduled-identity";

	private static final long TENANT_ID = 9001L;

	private static final long USER_ID = 7L;

	private final ObjectMapper mapper = new ObjectMapper();

	private final AiProperties properties = new AiProperties();

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final RunStore store = new RunStore(this.fake.redis, this.mapper, this.properties);

	@Test
	void runtimeSweepRestoresRunIdentityFromTheRecordBeforeResume() throws Exception {
		seedAgedSuspendedRun();
		assertThat(RequestContextUtils.currentOrNull()).as("前置：扫描线程模拟定时线程，无身份").isNull();

		ResumeService service = spy(realResumeService());
		AtomicReference<TenantUser> observed = new AtomicReference<>();
		doAnswer(invocation -> {
			observed.set(RequestContextUtils.currentOrNull());
			return new ResumeService.ResumeResult(ResumeService.Outcome.ACCEPTED, Map.of());
		}).when(service).resume(eq(RUN_ID), anyBoolean());

		StartupResumeRunner runner = new StartupResumeRunner(service, this.store, this.properties,
				alwaysAvailable());
		Map<String, Object> report = runner.sweep();

		assertThat((List<?>) report.get("resumed")).as("超龄无驱动的挂起轮应被判死并交给续跑漏斗").hasSize(1);
		assertThat(observed.get()).as("定时续跑必须以记录身份进入续跑入口")
			.isEqualTo(new TenantUser(TENANT_ID, USER_ID));
		assertThat(RequestContextUtils.currentOrNull()).as("扫描结束不得在定时线程残留身份").isNull();
	}

	/**
	 * 铺一条"超龄、无驱动"的挂起轮：快照的 {@code updatedAtMs} 早于僵尸窗口
	 * （{@code save()} 会刷新它，故直接写键面 —— 这也是真实场景里"进程死了、时间戳停在那一刻"的形态）。
	 */
	private void seedAgedSuspendedRun() throws Exception {
		MessageJsonCodec codec = new MessageJsonCodec(this.mapper);
		long agedAt = System.currentTimeMillis() - 600_000L;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_ID, USER_ID)) {
			this.store.create(RUN_ID, SESSION_ID, AiContext.empty(), List.of(), codec);
			RunSnapshot snapshot = this.store.get(RUN_ID);
			snapshot.setStatus(RunSnapshot.SUSPENDED);
			snapshot.setUpdatedAtMs(agedAt);
			snapshot.setCreatedAtMs(agedAt);
			this.fake.strings.put(this.store.runKey(RUN_ID), this.mapper.writeValueAsString(snapshot));
		}
	}

	/** 真实 {@code ResumeService}（{@code listRuns} 走真实 store；续跑入口由用例 spy 观测）。 */
	private ResumeService realResumeService() {
		CancellationRegistry cancellations = new CancellationRegistry(this.fake.redis, this.properties);
		return new ResumeService(this.store, null, new MessageJsonCodec(this.mapper), null, null, null,
				this.fake.redis, this.properties, cancellations, null);
	}

	private static AiAvailability alwaysAvailable() {
		AiConfigService configService = mock(AiConfigService.class);
		when(configService.availability())
			.thenReturn(new AiConfigService.Availability(true, "AI_AVAILABLE", "ok"));
		return new AiAvailability(configService);
	}

}
