package com.example.configmgr.ai.run;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 段锚点的观测面契约（超时面治理批② 第 1 项；决策看板 {@code :14}）。
 *
 * <p>本类是<b>新增契约</b>用例（实现前不存在该 API，无"旧行为可红"）：
 * 它把「挂起点段结束、恢复点段重置」的对象级语义钉死，供
 * {@link StreamWatchdogSegmentBudgetTest}（行为级）与
 * {@code ResilientChatService#segmentElapsedMs}（兜底判据）共用同一份事实。
 */
class ToolActivityBeaconSegmentTest {

	private static final String RUN_ID = "run-segment-beacon";

	private final ToolActivityBeacon beacon = new ToolActivityBeacon();

	@Test
	void segmentStartAdvancesAtEveryResume() throws Exception {
		long seeded = 1_000L;
		this.beacon.markSegmentStart(RUN_ID, seeded);
		assertThat(this.beacon.segmentStartedAtMs(RUN_ID, 0L)).isEqualTo(seeded);
		assertThat(this.beacon.inGateWait(RUN_ID)).isFalse();

		this.beacon.beginGateWait(RUN_ID);
		assertThat(this.beacon.inGateWait(RUN_ID)).as("挂起点：段已结束").isTrue();
		Thread.sleep(60L);
		this.beacon.endGateWait(RUN_ID);

		assertThat(this.beacon.inGateWait(RUN_ID)).isFalse();
		assertThat(this.beacon.segmentStartedAtMs(RUN_ID, 0L))
			.as("恢复点：段起点前移（重置段预算时钟）")
			.isGreaterThan(seeded);
	}

	@Test
	void gateWaitIsStillAccumulatedForObservability() throws Exception {
		this.beacon.beginGateWait(RUN_ID);
		Thread.sleep(60L);
		this.beacon.endGateWait(RUN_ID);
		long accumulated = this.beacon.gateWaitMs(RUN_ID);
		assertThat(accumulated).as("gateWaitMs 保留为观测面（日志批 turn_end.gateWaitMs 依赖它）")
			.isGreaterThanOrEqualTo(50L);

		this.beacon.endGateWait(RUN_ID);
		assertThat(this.beacon.gateWaitMs(RUN_ID)).as("幂等：重复 endGateWait 不再累加").isEqualTo(accumulated);
	}

	@Test
	void segmentSeedIsUsedWhenNoAnchorExists() {
		long fallback = 7_777L;
		assertThat(this.beacon.segmentStartedAtMs("run-without-anchor", fallback)).isEqualTo(fallback);
	}

	@Test
	void clearDropsSegmentAnchor() {
		this.beacon.markSegmentStart(RUN_ID, 5_000L);
		this.beacon.clear(RUN_ID);
		long fallback = 9_000L;
		assertThat(this.beacon.segmentStartedAtMs(RUN_ID, fallback))
			.as("轮终态清理后不残留旧段锚点")
			.isEqualTo(fallback);
	}

	/**
	 * <b>N5：终态清理活动锚点</b>（只清 {@code lastActivityAtMs}，幂等、不触碰其它进程内状态）。
	 *
	 * <p>{@code clear} 刻意保留这个锚点（重试判定要看退出时刻），终态之后它再没有读者 ——
	 * 因此单独给一个只做这一件事的入口；执行计数/段起点等各有自己的清理语义，不得被顺手清掉。
	 */
	@Test
	void clearRunDropsOnlyTheActivityAnchor() {
		this.beacon.enter(RUN_ID, "list_tasks", "BACKEND");
		this.beacon.markSegmentStart(RUN_ID, 5_000L);
		assertThat(this.beacon.lastActivityAtMs(RUN_ID)).isPositive();

		this.beacon.clearRun(RUN_ID);

		assertThat(this.beacon.lastActivityAtMs(RUN_ID)).as("活动锚点已清").isZero();
		assertThat(this.beacon.startedCount(RUN_ID)).as("只清锚点：执行计数不动").isEqualTo(1L);
		assertThat(this.beacon.segmentStartedAtMs(RUN_ID, 0L)).as("只清锚点：段起点不动").isEqualTo(5_000L);

		// 幂等 + null 安全
		this.beacon.clearRun(RUN_ID);
		this.beacon.clearRun(null);
		assertThat(this.beacon.lastActivityAtMs(RUN_ID)).isZero();
	}

}
