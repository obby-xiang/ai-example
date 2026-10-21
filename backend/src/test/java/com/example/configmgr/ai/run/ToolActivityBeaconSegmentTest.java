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

}
