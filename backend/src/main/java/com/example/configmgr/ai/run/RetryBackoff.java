package com.example.configmgr.ai.run;

import java.util.Random;
import java.util.random.RandomGenerator;

/**
 * 流式重试的退避计算（超时面治理批① 第 1 项）。
 *
 * <h2>为什么重试要睡</h2>
 * 断流重试若立刻重发，等来的通常是同一条已经劣化的链路（上游刚 reset 的 DNS / 连接池里的死连接），
 * 白烧一次尝试次数并把总预算花在"同一种失败"上。0.5s 起的指数退避给上游一点恢复窗口，
 * 且<b>第一次退避足够短</b>（500ms）—— 对"客户端感知"仍是瞬时的。
 *
 * <h2>三条硬约束</h2>
 * <ol>
 * <li><b>不突破总死线</b>：退避从"`total-budget` 扣掉已用（含挂起等待扣除）后的剩余"里出，
 * 并留 {@value #BUDGET_MARGIN_MS}ms 给下一次尝试本身；剩余不足即<b>收缩</b>（不是硬撞死线）。</li>
 * <li><b>抖动</b>：`±25%` 均匀抖动，防"同一时刻所有在飞轮次一起重试"（多实例/多页签下的共振）。
 * 抖动只在基线两侧缩扩，不改基线的指数走势。</li>
 * <li><b>可判定</b>：纯函数 + 注入 {@link RandomGenerator} ⇒ 单测不靠 sleep、不靠概率。</li>
 * </ol>
 *
 * <p>与"段死线"的关系：单次尝试内的首包/事件间上限由 {@link StreamWatchdog} 判，退避发生在
 * <b>两次尝试之间</b>（旧看门狗已关闭、新看门狗未建），因此不占用任何段死线，只消耗总预算。
 */
final class RetryBackoff {

	/** 第一次重试的基线（毫秒）；之后 ×2。 */
	static final long BASE_MS = 500L;

	/** 单次退避的上限（毫秒）：再多等不如把预算留给下一次尝试。 */
	static final long MAX_MS = 5_000L;

	/** 抖动幅度（相对基线的比例）：`[1−0.25, 1+0.25]`。 */
	static final double JITTER_RATIO = 0.25d;

	/** 留给下一次尝试本身的最小余量（毫秒）：退避不得把预算吃到"刚睡醒就超预算"。 */
	static final long BUDGET_MARGIN_MS = 1_000L;

	private RetryBackoff() {
	}

	/**
	 * 第 {@code retryNo} 次重试（1 = 第一次重试）的<b>抖动前</b>基线。
	 *
	 * @param retryNo 重试序号（≥1）；≤0 按 1 处理
	 */
	static long baseMs(int retryNo) {
		int n = Math.max(1, retryNo);
		// 2^(n-1) 的溢出防线：n 足够大时直接取上限（500 × 2^4 = 8000 > 5000）
		if (n - 1 >= 4) {
			return MAX_MS;
		}
		long raw = BASE_MS << (n - 1);
		return Math.min(raw, MAX_MS);
	}

	/**
	 * 实际退避时长（毫秒）：基线 × 抖动，再按剩余预算收缩。
	 *
	 * @param retryNo 重试序号（1 = 第一次重试）
	 * @param remainingBudgetMs 剩余可计入预算的时长（毫秒，已扣除挂起等待）；≤0 ⇒ 0（不再睡）
	 * @param random 抖动源（生产 = {@link java.util.concurrent.ThreadLocalRandom#current()}）
	 * @return 退避毫秒数，`0` = 不睡（预算已不足）
	 */
	static long delayMs(int retryNo, long remainingBudgetMs, RandomGenerator random) {
		return delayMs(retryNo, remainingBudgetMs, random.nextDouble());
	}

	/** 抖动源的确定性重载（单测直接给 `[0,1)` 的样本，绕开随机数）。 */
	static long delayMs(int retryNo, long remainingBudgetMs, double sample) {
		return delayMsWithRetryAfter(retryNo, remainingBudgetMs, null, sample);
	}

	/**
	 * 带 {@code Retry-After} 的退避（429）：等待 = max(抖动退避, Retry-After)，再按剩余预算收缩。
	 *
	 * <p>为什么取 max：退避是"给同一条劣化链路一点恢复时间"的下限，{@code Retry-After} 是上游
	 * 明确要求的下限；两者同时存在时取更长者才不会"刚被限流就立刻再打一次"。
	 * 预算约束不变：无论如何都不把时间睡到"刚醒就超预算"。
	 *
	 * @param retryAfterMillis 上游要求的最短等待（null = 没有/不可解析）
	 */
	static long delayMsWithRetryAfter(int retryNo, long remainingBudgetMs, Long retryAfterMillis, double sample) {
		long usable = remainingBudgetMs - BUDGET_MARGIN_MS;
		if (usable <= 0) {
			return 0L;
		}
		double factor = 1d - JITTER_RATIO + 2 * JITTER_RATIO * clamp01(sample);
		long jittered = Math.round(baseMs(retryNo) * factor);
		long target = retryAfterMillis == null ? jittered : Math.max(jittered, Math.max(0L, retryAfterMillis));
		return Math.max(0L, Math.min(target, usable));
	}

	/** 带 {@code Retry-After}、使用默认抖动源的便捷入口。 */
	static long delayMsWithRetryAfter(int retryNo, long remainingBudgetMs, Long retryAfterMillis) {
		return delayMsWithRetryAfter(retryNo, remainingBudgetMs, retryAfterMillis, new Random().nextDouble());
	}

	/** 便捷入口：给"默认抖动源"的调用点。 */
	static long delayMs(int retryNo, long remainingBudgetMs) {
		return delayMs(retryNo, remainingBudgetMs, new Random().nextDouble());
	}

	private static double clamp01(double sample) {
		if (Double.isNaN(sample)) {
			return 0.5d;
		}
		return Math.max(0d, Math.min(1d, sample));
	}

}
