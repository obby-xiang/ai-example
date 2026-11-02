package com.example.configmgr.ai.run;

import java.time.Duration;

/**
 * 续跑派发与取消收尾共用的<b>封顶指数退避</b>：第 {@code attempt} 次判定的间隔从 {@code base}
 * （至少 1ms）开始、每次 ×2；后续档位按 {@code cap}（至少 1ms）封顶。
 *
 * <p>首档就是 {@code base}，<b>不因 {@code cap} 更低而压低</b>（沿用两调用方引入本类之前的算式）；
 * {@code cap} 只约束后续翻倍。
 *
 * <p>只做算式，不携带任何业务状态，也<b>不替调用方决定默认值</b>：重试计数、在飞去重、
 * 耗尽后的清理与 null/0/负配置的口径都留在各自的调用方（{@code ConfirmGate} 钉死计数、
 * {@code CancelledRunFinalizer} 释放交棒位）。
 *
 * <p>{@code base} / {@code cap} 由调用方传入已生效的值（非 null）：
 * {@code app.ai.park.dispatch-retry-delay / dispatch-retry-max-delay}。
 */
public final class DispatchRetryBackoff {

	private DispatchRetryBackoff() {
	}

	/**
	 * 第 {@code attempt} 次重试前的等待（{@code attempt ≤ 1} = 基线）。
	 *
	 * @param attempt 已尝试次数（1 起；≤0 按 1 处理）
	 * @param base 首次重试间隔（非 null；0/负值按 1ms 下限）
	 * @param cap 后续档位的上限（非 null；0/负值按 1ms 下限）
	 */
	public static Duration delay(int attempt, Duration base, Duration cap) {
		long capMs = Math.max(1L, cap.toMillis());
		long delayMs = Math.max(1L, base.toMillis());
		for (int i = 1; i < attempt && delayMs < capMs; i++) {
			delayMs = Math.min(capMs, delayMs * 2);
		}
		return Duration.ofMillis(delayMs);
	}

}
