package com.example.configmgr.perf.redis;

/** 预算耗尽：调用方必须保留已完成结果并把本场景标为 partial/unknown，不得伪造通过。 */
public final class BudgetExceededException extends RuntimeException {

	private final String phase;

	private final long remainingNanos;

	public BudgetExceededException(String phase, long remainingNanos) {
		super("budget exhausted at phase=" + phase + " remainingNanos=" + remainingNanos);
		this.phase = phase;
		this.remainingNanos = remainingNanos;
	}

	public String phase() {
		return this.phase;
	}

	public long remainingNanos() {
		return this.remainingNanos;
	}

}
