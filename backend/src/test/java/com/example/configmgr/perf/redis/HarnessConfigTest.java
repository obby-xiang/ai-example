package com.example.configmgr.perf.redis;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarnessConfigTest {

	@Test
	void writesRequireIsolatedEnvironment() {
		HarnessConfig ungated = new HarnessConfig(env());
		assertThrows(IllegalArgumentException.class, () -> ungated.validate(true));
		HarnessConfig gated = new HarnessConfig(env("PERF_ISOLATED_ENV", "true"));
		gated.validate(true);
	}

	@Test
	void remoteRedisRequiresExplicitConfirmation() {
		HarnessConfig config = new HarnessConfig(env("REDIS_HOST", "10.0.0.5", "PERF_ISOLATED_ENV", "true"));
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> config.validate(true));
		assertTrue(ex.getMessage().contains("PERF_CONFIRM_REMOTE"));
	}

	@Test
	void clusterRejectsNonZeroDatabase() {
		HarnessConfig config = new HarnessConfig(env("REDIS_MODE", "cluster",
				"REDIS_CLUSTER_NODES", "127.0.0.1:7000", "REDIS_DB", "1",
				"PERF_ISOLATED_ENV", "true"));
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> config.validate(true));
		assertTrue(ex.getMessage().contains("db 0"));
	}

	@Test
	void redactedSummaryNeverContainsPassword() {
		HarnessConfig config = new HarnessConfig(env("REDIS_PASSWORD", "super-secret"));
		Map<String, Object> summary = config.redactedSummary();
		assertTrue((Boolean) summary.get("password_present"));
		assertFalse(summary.toString().contains("super-secret"));
	}

	private static Map<String, String> env(String... pairs) {
		Map<String, String> env = new LinkedHashMap<>();
		env.put("REDIS_MODE", "standalone");
		env.put("PERF_NAMESPACE", "rp-test");
		env.put("PERF_PROFILE", "smoke");
		for (int i = 0; i < pairs.length; i += 2) {
			env.put(pairs[i], pairs[i + 1]);
		}
		return env;
	}

}
