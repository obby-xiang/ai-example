package com.example.configmgr.perf.redis;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 文档 §5 的性能门禁。门槛可由环境变量覆盖，但默认值先冻结：
 * empty/minimal P95 ≤ 250ms、50 terminal runs P95 ≤ 2s、2000 archives P95 ≤ 3s、
 * 在线删除 SCAN 页数 = 0、背景规模放大后命令数不得增长。
 *
 * <p>缺场景 = unknown，partial/budget/skipped = unknown，failed = failure；都不算通过。
 */
public final class GateEvaluator {

	public record Thresholds(long emptyP95Ms, long runs50P95Ms, long archive2000P95Ms,
			long maxScanPages, long maxBackgroundCommandGrowth) {

		public static Thresholds defaults() {
			return new Thresholds(250L, 2_000L, 3_000L, 0L, 0L);
		}

	}

	public record Outcome(String scenario, String status, long p95Ms, long commands, long scanPages) {

		public boolean ok() {
			return "ok".equals(this.status);
		}

	}

	public record Decision(boolean passed, List<String> failures, List<String> unknowns) {
	}

	private GateEvaluator() {
	}

	public static Decision evaluate(Thresholds thresholds, Map<String, Outcome> outcomes,
			List<String> gateScenarios) {
		return evaluate(thresholds, outcomes, gateScenarios, gateScenarios, java.util.Set.of());
	}

	/**
	 * 全量选中场景判定：每个选中场景都必须有可接受的最终状态；性能阈值只作用于 gateScenarios。
	 * deferred 只有显式列入 allowedDeferred 才算通过（例如 active_unfinalizable）。
	 */
	public static Decision evaluate(Thresholds thresholds, Map<String, Outcome> outcomes,
			List<String> allScenarios, List<String> gateScenarios, java.util.Set<String> allowedDeferred) {
		List<String> failures = new ArrayList<>();
		List<String> unknowns = new ArrayList<>();

		for (String scenario : allScenarios) {
			Outcome outcome = outcomes.get(scenario);
			if (outcome == null) {
				unknowns.add(scenario + ": missing (not measured)");
				continue;
			}
			if ("failed".equals(outcome.status())) {
				failures.add(scenario + ": status=failed");
				continue;
			}
			if ("deferred".equals(outcome.status())) {
				if (!allowedDeferred.contains(scenario)) {
					failures.add(scenario + ": status=deferred (not the declared expected outcome)");
				}
				continue;
			}
			if (!outcome.ok()) {
				unknowns.add(scenario + ": status=" + outcome.status());
			}
		}

		for (String scenario : gateScenarios) {
			Outcome outcome = outcomes.get(scenario);
			if (outcome == null || !outcome.ok()) {
				continue;
			}
			long limit = switch (scenario) {
				case "empty", "minimal" -> thresholds.emptyP95Ms();
				case "runs_50_terminal" -> thresholds.runs50P95Ms();
				case "archives_2000" -> thresholds.archive2000P95Ms();
				default -> Long.MAX_VALUE;
			};
			if (outcome.p95Ms() > limit) {
				failures.add(scenario + ": p95=" + outcome.p95Ms() + "ms > " + limit + "ms");
			}
			if (outcome.scanPages() > thresholds.maxScanPages()) {
				failures.add(scenario + ": scan pages=" + outcome.scanPages()
						+ " > " + thresholds.maxScanPages() + " (在线删除必须 SCAN=0)");
			}
		}

		Outcome baseline = outcomes.get("bg_0_runs_1");
		for (Map.Entry<String, Outcome> entry : outcomes.entrySet()) {
			String scenario = entry.getKey();
			if (!scenario.startsWith("bg_") || "bg_0_runs_1".equals(scenario)) {
				continue;
			}
			Outcome amplified = entry.getValue();
			if (baseline == null) {
				unknowns.add(scenario + ": background baseline bg_0_runs_1 missing");
				continue;
			}
			if (!amplified.ok() || !baseline.ok()) {
				continue;
			}
			long growth = amplified.commands() - baseline.commands();
			if (growth > thresholds.maxBackgroundCommandGrowth()) {
				failures.add(scenario + ": command growth=" + growth + " (baseline=" + baseline.commands()
						+ ", amplified=" + amplified.commands() + ") exceeds "
						+ thresholds.maxBackgroundCommandGrowth());
			}
			if (amplified.scanPages() > thresholds.maxScanPages()) {
				failures.add(scenario + ": scan pages=" + amplified.scanPages() + " at amplified keyspace");
			}
		}

		return new Decision(failures.isEmpty() && unknowns.isEmpty(),
				List.copyOf(failures), List.copyOf(unknowns));
	}

}
