package com.example.configmgr.ai.run;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流式重试退避（超时面治理批① 第 1 项）：0.5s 起 ×2、抖动 ±25%、按剩余预算收缩。
 *
 * <p>纯函数断言 —— 不 sleep、不靠概率（抖动源以样本值注入）。
 */
class RetryBackoffTest {

	// ── 基线：0.5s → 1s → 2s → 4s → 5s（上限） ─────────────────────────────

	@Test
	void baseDoublesFromHalfASecondAndCapsAtFiveSeconds() {
		assertThat(RetryBackoff.baseMs(1)).as("第 1 次重试").isEqualTo(500L);
		assertThat(RetryBackoff.baseMs(2)).as("×2").isEqualTo(1_000L);
		assertThat(RetryBackoff.baseMs(3)).isEqualTo(2_000L);
		assertThat(RetryBackoff.baseMs(4)).isEqualTo(4_000L);
		assertThat(RetryBackoff.baseMs(5)).as("上限 5s").isEqualTo(5_000L);
		assertThat(RetryBackoff.baseMs(99)).as("溢出不回绕").isEqualTo(5_000L);
		assertThat(RetryBackoff.baseMs(0)).as("非法序号按 1 处理").isEqualTo(500L);
	}

	// ── 抖动：样本 0 ⇒ −25%，样本 1 ⇒ +25%，样本 0.5 ⇒ 基线 ────────────────

	@Test
	void jitterStaysWithinPlusMinusTwentyFivePercent() {
		assertThat(RetryBackoff.delayMs(2, 300_000L, 0d)).as("1000 × 0.75").isEqualTo(750L);
		assertThat(RetryBackoff.delayMs(2, 300_000L, 0.5d)).as("1000 × 1.0").isEqualTo(1_000L);
		assertThat(RetryBackoff.delayMs(2, 300_000L, 1d)).as("1000 × 1.25").isEqualTo(1_250L);
	}

	@Test
	void jitterIsBoundedForEverySampleAndEveryRetry() {
		for (int retryNo = 1; retryNo <= 6; retryNo++) {
			long base = RetryBackoff.baseMs(retryNo);
			for (double sample = 0d; sample <= 1d; sample += 0.05d) {
				long delay = RetryBackoff.delayMs(retryNo, 300_000L, sample);
				assertThat(delay).as("retry=%d sample=%.2f", retryNo, sample)
					.isBetween(Math.round(base * 0.75d), Math.round(base * 1.25d));
			}
		}
	}

	// ── 预算收缩：不硬撞总死线 ────────────────────────────────────────────

	@Test
	void delayShrinksToTheRemainingBudgetInsteadOfOvershootingTheDeadline() {
		// 剩余 1.2s ⇒ 可用 0.2s（扣 1s 余量）⇒ 500ms 的基线被收缩到 200ms
		assertThat(RetryBackoff.delayMs(1, 1_200L, 1d)).isEqualTo(200L);
		// 剩余恰好等于余量 ⇒ 不睡
		assertThat(RetryBackoff.delayMs(1, RetryBackoff.BUDGET_MARGIN_MS, 1d)).isZero();
		// 剩余为负/零 ⇒ 不睡（下一次尝试自己会撞预算判定，由调用方收尾）
		assertThat(RetryBackoff.delayMs(1, 0L, 1d)).isZero();
		assertThat(RetryBackoff.delayMs(1, -5_000L, 1d)).isZero();
	}

	@Test
	void delayNeverExceedsTheUsableBudget() {
		for (long remaining = 1_000L; remaining <= 6_000L; remaining += 250L) {
			long delay = RetryBackoff.delayMs(1, remaining, 1d);
			assertThat(delay).as("remaining=%d", remaining)
				.isLessThanOrEqualTo(remaining - RetryBackoff.BUDGET_MARGIN_MS + 1L);
		}
	}

	@Test
	void fullBudgetLeavesTheJitteredBaselineUntouched() {
		// 默认 300s 总预算（app.ai.resilience.total-budget）对 500ms 退避是"用不完"的
		assertThat(RetryBackoff.delayMs(1, 300_000L, 0.5d)).isEqualTo(500L);
	}

	// ── 429 Retry-After：等待取 max(退避, Retry-After)，仍受剩余预算裁剪 ─────

	@Test
	void retryAfterWinsWhenItIsLongerThanTheJitteredBackoff() {
		assertThat(RetryBackoff.delayMsWithRetryAfter(1, 300_000L, 3_000L, 0.5d)).as("max(500ms, 3s)").isEqualTo(3_000L);
	}

	@Test
	void jitteredBackoffWinsWhenRetryAfterIsShorter() {
		assertThat(RetryBackoff.delayMsWithRetryAfter(2, 300_000L, 100L, 0.5d)).as("max(1000ms, 100ms)")
			.isEqualTo(1_000L);
	}

	@Test
	void retryAfterIsAlsoShrunkByTheRemainingBudget() {
		// 剩余 3.2s ⇒ 可用 2.2s（扣 1s 余量）⇒ 10s 的 Retry-After 被收缩
		assertThat(RetryBackoff.delayMsWithRetryAfter(1, 3_200L, 10_000L, 0.5d)).isEqualTo(2_200L);
		// 没有可用预算 ⇒ 不睡（调用方在此之前已判"不重试"）
		assertThat(RetryBackoff.delayMsWithRetryAfter(1, 500L, 10_000L, 0.5d)).isZero();
	}

	@Test
	void nullRetryAfterKeepsThePlainBackoff() {
		assertThat(RetryBackoff.delayMsWithRetryAfter(1, 300_000L, null, 0.5d)).isEqualTo(500L);
	}

}
