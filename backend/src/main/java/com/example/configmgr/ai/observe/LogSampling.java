package com.example.configmgr.ai.observe;

import com.google.common.util.concurrent.RateLimiter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Bounds repeated diagnostic messages without changing the calling control flow. */
public final class LogSampling {

	private static final double PERMITS_PER_SECOND = 1.0d / 60.0d;

	private static final ConcurrentMap<String, RateLimiter> LIMITERS = new ConcurrentHashMap<>();

	private LogSampling() {
	}

	public static boolean canAcquire(String category) {
		return LIMITERS.computeIfAbsent(category, ignored -> RateLimiter.create(PERMITS_PER_SECOND)).tryAcquire();
	}

}
