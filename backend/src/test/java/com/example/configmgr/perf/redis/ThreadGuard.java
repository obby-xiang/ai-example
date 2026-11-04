package com.example.configmgr.perf.redis;

import java.util.concurrent.atomic.AtomicReference;

/** 并发执行体收口：join 超时或线程异常必须显式失败，不能继续清理。 */
public final class ThreadGuard {

	private ThreadGuard() {
	}

	public static void joinOrFail(Thread thread, long timeoutMs, String name,
			AtomicReference<Throwable> threadError) {
		try {
			thread.join(timeoutMs);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(name + " interrupted while joining", ex);
		}
		if (thread.isAlive()) {
			throw new IllegalStateException(name + " still alive after " + timeoutMs
					+ "ms; refusing to continue (possible write-through)");
		}
		Throwable error = threadError == null ? null : threadError.get();
		if (error != null) {
			throw new IllegalStateException(name + " failed: " + error.getMessage(), error);
		}
	}

}
