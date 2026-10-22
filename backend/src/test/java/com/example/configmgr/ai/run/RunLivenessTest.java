package com.example.configmgr.ai.run;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批G · G6：会话门里"占着会话却早已死掉"的轮（僵尸轮）不拦新发送。
 *
 * <p>
 * 判据 = 无租约 + 本进程无活连接 + 最近活动早已超出僵尸窗口。判错的两种代价都必须被这条
 * 用例挡住：把活轮判死会让同一会话并发两轮；把死轮判活会让用户永远发不出消息（只能刷新）。
 */
class RunLivenessTest {

	private static final long NOW = 1_800_000_000_000L;

	private static final long WINDOW = 60_000L;

	@ParameterizedTest
	@ValueSource(strings = { RunSnapshot.DONE, RunSnapshot.FAILED, RunSnapshot.CANCELLED })
	void terminalRoundsAreDead(String status) {
		assertThat(RunLiveness.isDead(status, NOW, NOW, true, true, WINDOW, NOW)).isTrue();
	}

	@Test
	void missingRoundIsDead() {
		assertThat(RunLiveness.isDead(null, 0L, 0L, false, false, WINDOW, NOW)).isTrue();
	}

	@Test
	void unknownStatusIsDead() {
		assertThat(RunLiveness.isDead("WEIRD", NOW, NOW, false, false, WINDOW, NOW)).isTrue();
	}

	@Test
	void leaseHolderIsAliveEvenWhenQuiet() {
		// 另一实例正持续跑权：安静也只是"正在想"，不是死
		assertThat(RunLiveness.isDead(RunSnapshot.RUNNING, NOW - 10 * WINDOW, 0L, true, false, WINDOW, NOW)).isFalse();
	}

	@Test
	void inProcessConnectionIsAliveEvenWhenQuiet() {
		assertThat(RunLiveness.isDead(RunSnapshot.RUNNING, NOW - 10 * WINDOW, 0L, false, true, WINDOW, NOW)).isFalse();
	}

	@Test
	void freshRunningRoundIsAlive() {
		assertThat(RunLiveness.isDead(RunSnapshot.RUNNING, NOW - WINDOW / 2, 0L, false, false, WINDOW, NOW)).isFalse();
	}

	@Test
	void staleRunningRoundWithoutLeaseOrConnectionIsDead() {
		assertThat(RunLiveness.isDead(RunSnapshot.RUNNING, NOW - 2 * WINDOW, 0L, false, false, WINDOW, NOW)).isTrue();
	}

	/** 挂起中的轮每秒有心跳（心跳只更新活动戳）：只要有心跳就不算死，不能把等人决策的轮放掉。 */
	@Test
	void suspendedRoundWithFreshHeartbeatIsAlive() {
		assertThat(RunLiveness.isDead(RunSnapshot.SUSPENDED, NOW - 10 * WINDOW, NOW - 1_000L, false, false, WINDOW, NOW))
			.isFalse();
	}

	@Test
	void suspendedRoundWithoutHeartbeatBeyondWindowIsDead() {
		assertThat(RunLiveness.isDead(RunSnapshot.SUSPENDED, NOW - 10 * WINDOW, NOW - 10 * WINDOW, false, false, WINDOW, NOW))
			.isTrue();
	}
}
