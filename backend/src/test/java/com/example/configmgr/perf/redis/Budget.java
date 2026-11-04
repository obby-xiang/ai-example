package com.example.configmgr.perf.redis;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * 两级预算：整次运行的总预算 + 每个场景的独立预算。场景截止时间取二者较小值。
 */
public final class Budget {

	private final Deadline total;

	private final long scenarioBudgetNanos;

	private final LongSupplier clock;

	public Budget(Duration totalBudget, Duration scenarioBudget, LongSupplier clock) {
		this.clock = clock;
		this.total = new Deadline(totalBudget.toNanos(), clock);
		this.scenarioBudgetNanos = scenarioBudget.toNanos();
	}

	public Deadline startScenario(String scenario) {
		return startScenario(scenario, Duration.ZERO);
	}

	/** 某次调用还可以压缩场景预算（例如单条 DELETE 的剩余预算）。 */
	public Deadline startScenario(String scenario, Duration cap) {
		long scenarioNanos = this.scenarioBudgetNanos;
		if (!cap.isZero() && !cap.isNegative()) {
			scenarioNanos = scenarioNanos <= 0L ? cap.toNanos() : Math.min(scenarioNanos, cap.toNanos());
		}
		if (scenarioNanos <= 0L) {
			return new Deadline(this.total.remainingNanos(), this.clock);
		}
		if (this.total.unlimited()) {
			return new Deadline(scenarioNanos, this.clock);
		}
		return new Deadline(Math.min(scenarioNanos, this.total.remainingNanos()), this.clock);
	}

	public Deadline totalDeadline() {
		return this.total;
	}

	public boolean totalExhausted() {
		return this.total.expired();
	}

	public long totalRemainingMillis() {
		return this.total.remainingMillis();
	}

	public long totalElapsedMillis() {
		return this.total.elapsedMillis();
	}

}
