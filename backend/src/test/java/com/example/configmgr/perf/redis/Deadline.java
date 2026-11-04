package com.example.configmgr.perf.redis;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * 单调时间预算。{@code budgetNanos <= 0} 表示该范围不设限（沿用旧工具的 0 = 关闭）。
 */
public final class Deadline {

	private final long budgetNanos;

	private final LongSupplier clock;

	private final long startNanos;

	public Deadline(long budgetNanos, LongSupplier clock) {
		this.budgetNanos = budgetNanos;
		this.clock = clock;
		this.startNanos = clock.getAsLong();
	}

	public long budgetNanos() {
		return this.budgetNanos;
	}

	public static Deadline after(Duration budget) {
		return new Deadline(budget.toNanos(), System::nanoTime);
	}

	public boolean unlimited() {
		return this.budgetNanos <= 0L;
	}

	public boolean expired() {
		return !unlimited() && remainingNanos() <= 0L;
	}

	public long remainingNanos() {
		if (unlimited()) {
			return Long.MAX_VALUE;
		}
		return Math.max(0L, this.budgetNanos - elapsedNanos());
	}

	public long remainingMillis() {
		if (unlimited()) {
			return Long.MAX_VALUE;
		}
		return remainingNanos() / 1_000_000L;
	}

	public long elapsedNanos() {
		return Math.max(0L, this.clock.getAsLong() - this.startNanos);
	}

	public long elapsedMillis() {
		return elapsedNanos() / 1_000_000L;
	}

	public void check(String phase) {
		if (expired()) {
			throw new BudgetExceededException(phase, 0L);
		}
	}

}
