package com.example.configmgr.perf.redis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 延迟统计：nearest-rank 百分位（P95 的含义是"至少 95% 的样本不超过该值"）。
 * 空样本不返回 0 通过，而由 {@link #isEmpty()} 让门禁判 unknown。
 */
public record LatencyStats(long count, long minMs, long meanMs, long p50Ms, long p95Ms, long p99Ms, long maxMs) {

	public static LatencyStats of(List<Long> nanos) {
		if (nanos == null || nanos.isEmpty()) {
			return new LatencyStats(0L, 0L, 0L, 0L, 0L, 0L, 0L);
		}
		List<Long> sorted = new ArrayList<>(nanos);
		Collections.sort(sorted);
		long sum = 0L;
		for (long value : sorted) {
			sum += Math.max(0L, value);
		}
		long mean = Math.round((double) sum / sorted.size() / 1_000_000.0d);
		return new LatencyStats(sorted.size(), toMs(sorted.get(0)), mean,
				toMs(percentile(sorted, 50)), toMs(percentile(sorted, 95)),
				toMs(percentile(sorted, 99)), toMs(sorted.get(sorted.size() - 1)));
	}

	public boolean isEmpty() {
		return this.count == 0L;
	}

	private static long percentile(List<Long> sorted, int percent) {
		int rank = (int) Math.ceil(percent / 100.0d * sorted.size());
		int index = Math.min(sorted.size() - 1, Math.max(0, rank - 1));
		return sorted.get(index);
	}

	private static long toMs(long nanos) {
		return nanos / 1_000_000L;
	}

}
