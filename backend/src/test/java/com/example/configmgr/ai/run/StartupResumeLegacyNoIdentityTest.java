package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.RunIdentityScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>无身份的 legacy 轮次不得被系统扫描自动接管</b>（启动扫描与运行期死轮扫描）。
 *
 * <h2>被钉住的缺陷</h2>
 * pre-deploy 的轮次没有归属（{@code tenantId=0 / userId=0}），{@code ResumeService.listRuns()} 仍会在
 * 无身份线程上按旧键形把它们发现出来（过渡期口径）。扫描方拿到这类条目后照样走进续跑：
 * {@link RunIdentityScope#open(long, long)} 对无身份记录<b>清空</b>线程身份 —— 于是续跑在
 * <b>无任何身份</b>下驱动模型与工具，按身份定键的读写全部读不到（或读到 legacy 键），
 * 既跑不出用户以为的那一轮，也没有归属可解释。
 *
 * <h2>本组口径</h2>
 * <ul>
 * <li>身份<b>不可用</b>（{@code tenantId<=0 / userId=0}）⇒ 两条系统扫描都不驱动、只记
 * {@code LEGACY_NO_IDENTITY}：不伪造归属、不沿用扫描线程身份；</li>
 * <li>同一拍里<b>带身份</b>的轮次照常受理 —— 守卫只挡无身份记录，不是把自动续跑整体关掉。</li>
 * </ul>
 */
class StartupResumeLegacyNoIdentityTest {

	private static final String LEGACY_RUN = "run-legacy-no-identity";

	private static final String OWNED_RUN = "run-owned-identity";

	private static final long TENANT_ID = 1L;

	private static final long USER_ID = 101L;

	/** 超龄（远大于 180s 僵尸窗口）：若无守卫，两条扫描都会把它判死并接管。 */
	private static final long AGED_AT_MS = System.currentTimeMillis() - 600_000L;

	private final ResumeService resumeService = mock(ResumeService.class);

	private final RunStore store = mock(RunStore.class);

	private final AiAvailability availability = mock(AiAvailability.class);

	private final AiProperties properties = new AiProperties();

	private StartupResumeRunner runner;

	@BeforeEach
	void setUp() {
		when(this.availability.isAvailable()).thenReturn(true);
		when(this.store.instanceId()).thenReturn("test-instance");
		when(this.resumeService.listRuns())
			.thenReturn(List.of(agedSuspendedRun(LEGACY_RUN, 0L, 0L), agedSuspendedRun(OWNED_RUN, TENANT_ID, USER_ID)));
		when(this.resumeService.resume(any(), anyBoolean()))
			.thenReturn(new ResumeService.ResumeResult(ResumeService.Outcome.ACCEPTED, Map.of()));
		this.runner = new StartupResumeRunner(this.resumeService, this.store, this.properties, this.availability);
	}

	@Test
	void systemScanAndSweepLeaveLegacyRunsWithoutIdentityUndriven() {
		try (RunIdentityScope.Scope ignored = RunIdentityScope.denied()) {
			Map<String, Object> scan = this.runner.scan(true);
			Map<String, Object> sweep = this.runner.sweep();

			assertThat(skippedReasons(scan)).as("启动扫描：无身份的 legacy 轮次必须显式标记")
				.containsEntry(LEGACY_RUN, "LEGACY_NO_IDENTITY");
			assertThat(resumedRunIds(scan)).as("启动扫描：同一拍里带身份的轮次照常受理").containsExactly(OWNED_RUN);

			assertThat(skippedReasons(sweep)).as("运行期扫描：无身份的 legacy 轮次必须显式标记")
				.containsEntry(LEGACY_RUN, "LEGACY_NO_IDENTITY");
			assertThat(resumedRunIds(sweep)).as("运行期扫描：同一拍里带身份的轮次照常受理").containsExactly(OWNED_RUN);

			assertThat(RequestContextUtils.currentOrNull()).as("扫描不得替 legacy 轮次建立归属").isNull();
		}

		verify(this.resumeService, never()).resume(eq(LEGACY_RUN), anyBoolean());
		verify(this.resumeService, times(2)).resume(eq(OWNED_RUN), anyBoolean());
	}

	/** 一条超龄、无驱动、无待决外部项的 SUSPENDED 轮次（{@code 0/0} = legacy 无归属）。 */
	private static Map<String, Object> agedSuspendedRun(String runId, long tenantId, long userId) {
		Map<String, Object> run = new LinkedHashMap<>();
		run.put("runId", runId);
		run.put("tenantId", tenantId);
		run.put("userId", userId);
		run.put("status", RunSnapshot.SUSPENDED);
		run.put("cancelled", false);
		run.put("unresolvedExternal", List.of());
		run.put("zombieRunning", false);
		run.put("updatedAtMs", AGED_AT_MS);
		run.put("activeAtMs", AGED_AT_MS);
		return run;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> items(Map<String, Object> report, String key) {
		return (List<Map<String, Object>>) report.getOrDefault(key, List.of());
	}

	private static List<String> resumedRunIds(Map<String, Object> report) {
		return items(report, "resumed").stream().map(item -> String.valueOf(item.get("runId"))).toList();
	}

	private static Map<String, String> skippedReasons(Map<String, Object> report) {
		Map<String, String> reasons = new LinkedHashMap<>();
		for (Map<String, Object> item : items(report, "skipped")) {
			reasons.put(String.valueOf(item.get("runId")), String.valueOf(item.get("reason")));
		}
		return reasons;
	}

}
