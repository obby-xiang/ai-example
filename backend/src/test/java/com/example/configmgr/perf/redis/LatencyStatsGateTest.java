package com.example.configmgr.perf.redis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LatencyStatsGateTest {

	@Test
	void nearestRankPercentiles() {
		List<Long> nanos = new ArrayList<>();
		for (long i = 1; i <= 100; i++) {
			nanos.add(i * 1_000_000L);
		}
		LatencyStats stats = LatencyStats.of(nanos);
		assertEquals(100L, stats.count());
		assertEquals(1L, stats.minMs());
		assertEquals(50L, stats.p50Ms());
		assertEquals(95L, stats.p95Ms());
		assertEquals(99L, stats.p99Ms());
		assertEquals(100L, stats.maxMs());
		assertEquals(51L, stats.meanMs());
	}

	@Test
	void emptyStatsIsUnknownNotZeroPass() {
		LatencyStats stats = LatencyStats.of(List.of());
		assertEquals(0L, stats.count());
		assertTrue(stats.isEmpty());
	}

	@Test
	void gateFailsOnDefaultThresholds() {
		GateEvaluator.Thresholds t = GateEvaluator.Thresholds.defaults();
		Map<String, GateEvaluator.Outcome> outcomes = new LinkedHashMap<>();
		outcomes.put("empty", new GateEvaluator.Outcome("empty", "ok", 300L, 10L, 0L));
		outcomes.put("runs_50_terminal", new GateEvaluator.Outcome("runs_50_terminal", "ok", 2100L, 200L, 0L));
		outcomes.put("archives_2000", new GateEvaluator.Outcome("archives_2000", "ok", 3100L, 4000L, 0L));
		GateEvaluator.Decision d = GateEvaluator.evaluate(t, outcomes,
				List.of("empty", "runs_50_terminal", "archives_2000"));
		assertFalse(d.passed());
		assertTrue(d.failures().stream().anyMatch(s -> s.contains("empty")));
		assertTrue(d.failures().stream().anyMatch(s -> s.contains("runs_50_terminal")));
		assertTrue(d.failures().stream().anyMatch(s -> s.contains("archives_2000")));
	}

	@Test
	void gatePassesWithinThresholdsAndZeroScan() {
		GateEvaluator.Thresholds t = GateEvaluator.Thresholds.defaults();
		Map<String, GateEvaluator.Outcome> outcomes = new LinkedHashMap<>();
		outcomes.put("empty", new GateEvaluator.Outcome("empty", "ok", 200L, 10L, 0L));
		outcomes.put("runs_50_terminal", new GateEvaluator.Outcome("runs_50_terminal", "ok", 1500L, 200L, 0L));
		outcomes.put("archives_2000", new GateEvaluator.Outcome("archives_2000", "ok", 2500L, 4000L, 0L));
		GateEvaluator.Decision d = GateEvaluator.evaluate(t, outcomes,
				List.of("empty", "runs_50_terminal", "archives_2000"));
		assertTrue(d.passed(), () -> String.join("; ", d.failures()));
	}

	@Test
	void gateRejectsScanPagesAndPartialResults() {
		GateEvaluator.Thresholds t = GateEvaluator.Thresholds.defaults();
		Map<String, GateEvaluator.Outcome> outcomes = new LinkedHashMap<>();
		outcomes.put("empty", new GateEvaluator.Outcome("empty", "ok", 100L, 10L, 3L));
		outcomes.put("minimal", new GateEvaluator.Outcome("minimal", "partial", 100L, 10L, 0L));
		GateEvaluator.Decision d = GateEvaluator.evaluate(t, outcomes, List.of("empty", "minimal"));
		assertFalse(d.passed());
		assertTrue(d.failures().stream().anyMatch(s -> s.contains("scan")));
		assertTrue(d.unknowns().stream().anyMatch(s -> s.contains("minimal")));
	}

	@Test
	void backgroundAmplificationMustNotChangeCommandCount() {
		Map<String, GateEvaluator.Outcome> outcomes = new LinkedHashMap<>();
		outcomes.put("bg_0_runs_1", new GateEvaluator.Outcome("bg_0_runs_1", "ok", 100L, 40L, 0L));
		outcomes.put("bg_10k_runs_1", new GateEvaluator.Outcome("bg_10k_runs_1", "ok", 110L, 41L, 0L));
		GateEvaluator.Decision d = GateEvaluator.evaluate(GateEvaluator.Thresholds.defaults(), outcomes,
				List.of("bg_0_runs_1", "bg_10k_runs_1"));
		assertFalse(d.passed());
		assertTrue(d.failures().stream().anyMatch(s -> s.contains("bg_10k_runs_1") && s.contains("command")));
	}

	@Test
	void missingGateScenarioIsUnknownNotPass() {
		GateEvaluator.Decision d = GateEvaluator.evaluate(GateEvaluator.Thresholds.defaults(),
				Map.of("empty", new GateEvaluator.Outcome("empty", "ok", 100L, 10L, 0L)),
				List.of("empty", "archives_2000"));
		assertFalse(d.passed());
		assertTrue(d.unknowns().stream().anyMatch(s -> s.contains("archives_2000")));
	}

}
