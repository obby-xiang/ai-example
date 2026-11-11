package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiConfigService;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.RunKeyScope;
import com.example.configmgr.ai.tenant.TenantUser;
import com.example.configmgr.ai.tool.AiContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * <b>启动/运行期发现必须能在"无请求身份"的定时线程上看见带身份的轮次</b>（真实 {@link RunStore}
 * + 真实键形，不用身份无关的 {@code InMemoryRunStore}/mock）。
 *
 * <h2>被钉住的缺陷</h2>
 * {@code ai:runs} 的成员在租户改造后是复合成员 {@code tenantId/userId/runId}，而
 * {@code ResumeService.listRuns()} 原先走 {@code allRunIds()}：<b>无身份上下文时跳过所有复合成员</b>
 * （只认 pre-deploy 的裸成员）。扫描线程没有请求身份 ⇒ 本改造之后创建的轮次<b>一条都发现不了</b>，
 * 于是"重启后挂起轮自动续跑"、"崩溃后僵尸接管"对真实轮次整体失效，且完全静默。
 *
 * <h2>本用例锁两点</h2>
 * <ol>
 * <li><b>发现面</b>：无身份线程上 {@code listRuns()} 必须按索引成员里的身份逐条看见这一轮，
 *     并把身份随条目交给扫描方（不再"先读快照再猜身份"）；</li>
 * <li><b>恢复面</b>：启动扫描调用续跑时，续跑入口看到的是<b>该轮记录身份</b>（不是 {@code null}，
 *     也不是扫描线程的残留身份），扫描结束后线程不留身份。</li>
 * </ol>
 *
 * <p>证红方式：修前 ① {@code listRuns()} 返回空（复合成员被跳过）；② 即便进了循环，
 * {@code resumeWithRunIdentity} 在无身份线程上 {@code store.get(runId)} 读到 {@code null}
 * ⇒ 空作用域 ⇒ 续跑侧身份为 {@code null}。
 */
class StartupResumeRealStoreDiscoveryTest {

	private static final String RUN_ID = "run-startup-real-store";

	private static final String SESSION_ID = "s-startup-real-store";

	private static final long TENANT_ID = 1L;

	private static final long USER_ID = 101L;

	private final ObjectMapper mapper = new ObjectMapper();

	private final AiProperties properties = new AiProperties();

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final RunStore store = new RunStore(this.fake.redis, this.mapper, this.properties);

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	@Test
	void startupScanFindsTheIdentityScopedRunAndResumesItUnderItsRecordIdentity() {
		long dueAtMs = seedSuspendedRun();
		assertThat(RequestContextUtils.currentOrNull()).as("前置：扫描线程模拟定时线程，没有请求身份").isNull();

		ResumeService service = spy(realResumeService());
		AtomicReference<TenantUser> resumeIdentity = new AtomicReference<>();
		doAnswer(invocation -> {
			resumeIdentity.set(RequestContextUtils.currentOrNull());
			return new ResumeService.ResumeResult(ResumeService.Outcome.ACCEPTED, Map.of());
		}).when(service).resume(eq(RUN_ID), anyBoolean());

		List<Map<String, Object>> candidates = service.listRunsForRecovery();
		assertThat(candidates).as("无身份线程上必须按索引成员发现带身份的轮次").hasSize(1);
		assertThat(candidates.get(0)).containsEntry("runId", RUN_ID)
			.containsEntry("tenantId", TENANT_ID)
			.containsEntry("userId", USER_ID);

		// 发现调用按设计把候选 defer 到 now+zombieAge（防止同一候选每拍热循环重扫）。
		// 这里把"下一拍已越过 defer 窗口"显式化：按同一生产到期公式重新登记，扫描才能再看到它。
		seedRecoveryDue(dueAtMs);
		// 合法候选（快照存在、身份匹配、非终态）不得被对账清掉。
		this.store.pruneRunIndex();
		assertThat(this.store.recoveryDueRecords(System.currentTimeMillis(), 64))
			.as("对账后合法到期候选仍在").hasSize(1);

		StartupResumeRunner runner = new StartupResumeRunner(service, this.store, this.properties,
				alwaysAvailable());
		Map<String, Object> report = runner.scan(false);

		assertThat(report.get("candidates")).as("扫描的发现面必须看到这一条到期候选").isEqualTo(1);
		assertThat((List<?>) report.get("resumed")).as("挂起轮必须被启动扫描受理").hasSize(1);
		assertThat(resumeIdentity.get()).as("续跑入口必须以该轮记录身份执行")
			.isEqualTo(new TenantUser(TENANT_ID, USER_ID));
		assertThat(RequestContextUtils.currentOrNull()).as("扫描结束不得在扫描线程残留身份").isNull();
	}

	/**
	 * 构造"进程已死在 10 分钟前"的合法 SUSPENDED 现场：真实 {@code create}/{@code save} 落身份键，
	 * 再把 {@code updatedAt} 与键面冻结到那一刻（{@code save} 会刷新 updatedAt，故最后直写键面），
	 * 并按生产公式 {@code updatedAt + zombie-age} 登记到期索引。
	 *
	 * @return 生产公式算出的到期时刻（供 defer 后重新登记）
	 */
	private long seedSuspendedRun() {
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_ID, USER_ID)) {
			this.store.create(RUN_ID, SESSION_ID, AiContext.empty(), List.of(), this.codec, null, "test-model",
					new RunSnapshot.FrozenParams(null, null, 4096, false), null, "恢复扫描",
					System.currentTimeMillis());
			RunSnapshot snapshot = this.store.get(RUN_ID);
			snapshot.setStatus(RunSnapshot.SUSPENDED);
			this.store.save(snapshot);
			snapshot.setUpdatedAtMs(System.currentTimeMillis() - 600_000L);
			try {
				this.fake.strings.put(this.store.runKey(RUN_ID), this.mapper.writeValueAsString(snapshot));
			}
			catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
				throw new IllegalStateException("快照序列化失败", ex);
			}
			long dueAtMs = snapshot.getUpdatedAtMs() + this.properties.getResume().getZombieAge().toMillis();
			seedRecoveryDue(dueAtMs);
			return dueAtMs;
		}
	}

	/** 按生产到期 score 写全局复合成员与身份窄索引（生产写路径两者同步登记）。 */
	private void seedRecoveryDue(long dueAtMs) {
		String member = RunKeyScope.indexMember(TENANT_ID, USER_ID, RUN_ID);
		this.fake.zsetsByKey.computeIfAbsent(RunStore.RECOVERY_DUE_INDEX, key -> new ConcurrentHashMap<>())
			.put(member, (double) dueAtMs);
		this.fake.zsetsByKey
			.computeIfAbsent(RunStore.RECOVERY_DUE_IDENTITY_PREFIX + TENANT_ID + ":" + USER_ID,
					key -> new ConcurrentHashMap<>())
			.put(RUN_ID, (double) dueAtMs);
	}

	/** 真实 {@code ResumeService}（{@code listRuns} 走真实 {@link RunStore}；续跑入口由用例 spy 观测）。 */
	private ResumeService realResumeService() {
		CancellationRegistry cancellations = new CancellationRegistry(this.fake.redis, this.properties);
		return new ResumeService(this.store, null, this.codec, null, null, null, this.fake.redis, this.properties,
				cancellations, null);
	}

	private static AiAvailability alwaysAvailable() {
		AiConfigService configService = mock(AiConfigService.class);
		when(configService.availability())
			.thenReturn(new AiConfigService.Availability(true, "AI_AVAILABLE", "ok"));
		return new AiAvailability(configService);
	}

}
