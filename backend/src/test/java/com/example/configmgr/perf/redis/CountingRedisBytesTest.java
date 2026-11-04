package com.example.configmgr.perf.redis;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CountingRedisBytesTest {

	@Test
	void requestEstimateIncludesScriptAndArguments() {
		long withArgs = CountingRedis.estimateRequestBytes("return redis.call('DEL', KEYS[1])",
				List.of("ai:run:{1:101:run:r1}"), List.of("payload-value-0123456789"));
		long withoutArgs = CountingRedis.estimateRequestBytes("return redis.call('DEL', KEYS[1])",
				List.of("ai:run:{1:101:run:r1}"), List.of());
		assertTrue(withArgs > withoutArgs, "ARGV bytes must be included");
	}

	@Test
	void requestEstimateIncludesScriptBody() {
		long longScript = CountingRedis.estimateRequestBytes(
				"return redis.call('DEL', KEYS[1]) -- a long script body with many bytes", List.of("k"), List.of());
		long shortScript = CountingRedis.estimateRequestBytes("return 1", List.of("k"), List.of());
		assertTrue(longScript > shortScript, "script bytes must be included");
	}

}
