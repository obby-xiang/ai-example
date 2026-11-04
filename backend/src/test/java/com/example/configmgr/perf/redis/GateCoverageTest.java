package com.example.configmgr.perf.redis;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GateCoverageTest {

	@Test
	void selectedNonGateScenarioFailureFailsTheWholeRun() {
		Map<String, GateEvaluator.Outcome> outcomes = new LinkedHashMap<>();
		outcomes.put("empty", new GateEvaluator.Outcome("empty", "ok", 100L, 10L, 0L));
		outcomes.put("runs_10_terminal", new GateEvaluator.Outcome("runs_10_terminal", "failed", 100L, 10L, 0L));
		GateEvaluator.Decision d = GateEvaluator.evaluate(GateEvaluator.Thresholds.defaults(), outcomes,
				List.of("empty", "runs_10_terminal"), List.of("empty"), Set.of());
		assertFalse(d.passed());
		assertTrue(d.failures().stream().anyMatch(s -> s.contains("runs_10_terminal")));
	}

	@Test
	void selectedScenarioMissingIsUnknown() {
		Map<String, GateEvaluator.Outcome> outcomes = new LinkedHashMap<>();
		outcomes.put("empty", new GateEvaluator.Outcome("empty", "ok", 100L, 10L, 0L));
		GateEvaluator.Decision d = GateEvaluator.evaluate(GateEvaluator.Thresholds.defaults(), outcomes,
				List.of("empty", "minimal"), List.of("empty"), Set.of());
		assertFalse(d.passed());
		assertTrue(d.unknowns().stream().anyMatch(s -> s.contains("minimal")));
	}

	@Test
	void expectedDeferredScenarioIsAllowedOnlyWhenNamed() {
		Map<String, GateEvaluator.Outcome> outcomes = new LinkedHashMap<>();
		outcomes.put("active_unfinalizable", new GateEvaluator.Outcome("active_unfinalizable",
				"deferred", 10L, 10L, 0L));
		GateEvaluator.Decision allowed = GateEvaluator.evaluate(GateEvaluator.Thresholds.defaults(), outcomes,
				List.of("active_unfinalizable"), List.of(), Set.of("active_unfinalizable"));
		assertTrue(allowed.passed(), () -> String.join("; ", allowed.failures()) + String.join("; ", allowed.unknowns()));
		GateEvaluator.Decision notAllowed = GateEvaluator.evaluate(GateEvaluator.Thresholds.defaults(), outcomes,
				List.of("active_unfinalizable"), List.of(), Set.of());
		assertFalse(notAllowed.passed());
	}

}
