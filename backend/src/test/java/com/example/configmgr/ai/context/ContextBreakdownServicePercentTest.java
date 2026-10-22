package com.example.configmgr.ai.context;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端点 {@code percent} 的取整契约（E2E-CTX-4 取整口径的分歧点护栏）。
 *
 * <p>
 * 产品口径是 <b>half-up</b>（{@link Math#round(double)}）；断言侧曾用 PowerShell
 * {@code [math]::Round} 的默认 ToEven（银行家舍入），两者只在中点（x.5）分歧：
 * {@code 5440 / 128000 = 42.5} 恰好落在中点，产品 4.3、ToEven 4.2。
 *
 * <p>
 * 本组只锁<b>取整口径</b>，不锁绝对值：used 是估算值（批 B 加工具后从 5291 漂到 5440 属正常），
 * 端点与断言各自只要同口径即可。
 */
class ContextBreakdownServicePercentTest {

	/** 中点向上：42.5 → 43 → 4.3（ToEven 会给 42 → 4.2，本断言是那个分歧点的护栏）。 */
	@Test
	void midpointRoundsHalfUp() {
		assertThat(ContextBreakdownService.percent(5440, 128000)).isEqualTo(4.3d);
		// 更小的中点：0.5 → 1 → 0.1（ToEven 会给 0.0，更锋利的口径探针）
		assertThat(ContextBreakdownService.percent(64, 128000)).isEqualTo(0.1d);
	}

	/** 非中点：41.3359375 → 41 → 4.1（中点口径与四舍五入口径在此同值，不构成分歧）。 */
	@Test
	void nonMidpointRoundsToOneDecimal() {
		assertThat(ContextBreakdownService.percent(5291, 128000)).isEqualTo(4.1d);
	}

	/** 零占用恒 0（与 limit 无关）。 */
	@Test
	void zeroUsedIsZero() {
		assertThat(ContextBreakdownService.percent(0, 128000)).isZero();
	}

	/** 上限未知/非法（0、负数）回 0 —— 与载荷侧「没测过就不发百分比」同姿态。 */
	@Test
	void nonPositiveLimitYieldsZero() {
		assertThat(ContextBreakdownService.percent(5440, 0)).isZero();
		assertThat(ContextBreakdownService.percent(5440, -1)).isZero();
	}

	/** 满占 100.0；超占不封顶（「已超」由前端渲染，端点如实给数）。 */
	@Test
	void fullAndOverflowAreNotCapped() {
		assertThat(ContextBreakdownService.percent(128000, 128000)).isEqualTo(100.0d);
		assertThat(ContextBreakdownService.percent(140800, 128000)).isEqualTo(110.0d);
	}

	/** 负 used 按 0 处理（{@code Math.max(0, used)}）—— 记账异常不得回负数百分比。 */
	@Test
	void negativeUsedClampsToZero() {
		assertThat(ContextBreakdownService.percent(-5, 128000)).isZero();
	}

}
