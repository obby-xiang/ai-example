package com.example.configmgr.perf.production;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ProductionScenarioCatalog {

	private static final Map<String, ProductionScenario> ALL = new LinkedHashMap<>();

	static {
		register(new StartupRunnerActiveScenario());
		register(new RunHitlDecisionScenario());
		register(new MemoryConcurrentCasScenario());
		register(new KbGenerationPruneScenario());
		register(new PurgeScenarios.FullLifecycle());
		register(new PurgeScenarios.ActiveDeferred());
		register(new PurgeScenarios.RetryFairness());
		register(new LeaseFencedTakeoverScenario());
	}

	private ProductionScenarioCatalog() {
	}

	private static void register(ProductionScenario scenario) {
		ALL.put(scenario.id(), scenario);
	}

	static ProductionScenario byId(String id) {
		ProductionScenario scenario = ALL.get(id);
		if (scenario == null) {
			throw new IllegalArgumentException("未知生产场景：" + id);
		}
		return scenario;
	}

	static List<ProductionScenario> selected(String explicit) {
		if (explicit == null || explicit.isBlank()) {
			return List.copyOf(ALL.values());
		}
		List<ProductionScenario> out = new ArrayList<>();
		for (String id : explicit.split(",")) {
			if (!id.isBlank()) {
				out.add(byId(id.trim()));
			}
		}
		return List.copyOf(out);
	}

}
