package com.example.configmgr.ai.run;

import com.example.configmgr.ai.tenant.RunIdentityScope;
import com.example.configmgr.ai.tenant.TenantUser;
import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>工具活动信标的各张表按「身份 + runId」分条</b>。
 *
 * <h2>缺陷形态</h2>
 * 旧表全部用裸 runId：A 的工具执行会让 B 的同名轮次<b>被判成"有工具在跑"</b>（看门狗因此不判静默，
 * 卡死的 B 永远不被收），A 的收尾武装会让 B 也"只结算未执行"，A 的轮终态清理会清掉 B 的计数与段锚点。
 *
 * <p>本类钉住：工具活跃/计数/挂起等待/段起点/收尾武装/清理，六者都互不串身份。
 */
class ToolActivityBeaconIdentityTest {

	private static final String RUN_ID = "run-shared-beacon";

	private static final TenantUser A = new TenantUser(1L, 101L);

	private static final TenantUser B = new TenantUser(2L, 202L);

	private final ToolActivityBeacon beacon = new ToolActivityBeacon();

	@Test
	void beaconTablesAreScopedToIdentityPlusRunId() {
		as(A, () -> {
			this.beacon.enter(RUN_ID, "tool-a", PendingToolCall.KIND_CONFIRM);
			this.beacon.markSegmentStart(RUN_ID, 1_000L);
			this.beacon.beginGateWait(RUN_ID);
			this.beacon.armBudget(RUN_ID);
			return null;
		});
		as(B, () -> {
			this.beacon.enter(RUN_ID, "tool-b", PendingToolCall.KIND_CONFIRM);
			return null;
		});

		assertThat(as(A, () -> this.beacon.startedCount(RUN_ID))).isEqualTo(1L);
		assertThat(as(B, () -> this.beacon.startedCount(RUN_ID))).isEqualTo(1L);
		assertThat(as(B, () -> this.beacon.isActive(RUN_ID)))
			.as("A 执行的工具不得让 B 的同名轮次被判成'有工具在跑'（否则 B 卡死也不收）")
			.isTrue();

		as(A, () -> {
			this.beacon.exit(RUN_ID, "done");
			return null;
		});
		assertThat(as(A, () -> this.beacon.isActive(RUN_ID))).isFalse();
		assertThat(as(B, () -> this.beacon.isActive(RUN_ID))).as("A 的工具退出不得清掉 B 的活跃").isTrue();

		assertThat(as(B, () -> this.beacon.budgetArmed(RUN_ID))).as("A 的收尾武装不得让 B 也进入收尾")
			.isFalse();
		assertThat(as(A, () -> this.beacon.budgetArmed(RUN_ID))).isTrue();
		assertThat(as(B, () -> this.beacon.inGateWait(RUN_ID))).as("A 的挂起等待不得让 B 也暂停段预算")
			.isFalse();
		assertThat(as(A, () -> this.beacon.inGateWait(RUN_ID))).isTrue();
		assertThat(as(B, () -> this.beacon.segmentStartedAtMs(RUN_ID, 7_777L)))
			.as("B 从未播种段起点 ⇒ 用调用方兜底，而不是读 A 的段")
			.isEqualTo(7_777L);

		as(A, () -> {
			this.beacon.clear(RUN_ID);
			return null;
		});
		assertThat(as(A, () -> this.beacon.startedCount(RUN_ID))).isZero();
		assertThat(as(B, () -> this.beacon.startedCount(RUN_ID))).as("A 的轮终态清理不得清掉 B 的计数")
			.isEqualTo(1L);
		assertThat(as(B, () -> this.beacon.isActive(RUN_ID))).isTrue();
	}

	private static <T> T as(TenantUser identity, Supplier<T> body) {
		try (RunIdentityScope.Scope ignored = RunIdentityScope.open(identity)) {
			return body.get();
		}
	}

}
