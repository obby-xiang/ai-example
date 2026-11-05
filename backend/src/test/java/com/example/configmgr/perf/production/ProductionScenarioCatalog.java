package com.example.configmgr.perf.production;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ProductionScenarioCatalog {

	private static final Map<String, ProductionScenario> ALL = new LinkedHashMap<>();

	static {
		// P0：删除主事故面与核心写协议（6）。
		register(new StartupRunnerActiveScenario());
		register(new RunHitlDecisionScenario());
		register(new MemoryConcurrentCasScenario());
		register(new KbGenerationPruneScenario());
		register(new PurgeScenarios.FullLifecycle());
		register(new LeaseFencedTakeoverScenario());

		// P1：高放大接口、跨实例一致性与持续资源治理（29）。
		register(new PurgeScenarios.RetryFairness());
		register(new PurgeScenarios.ActiveDeferred());
		register(new StartupResumeCandidateStormScenario());
		register(new StartupDependencyFailureMatrixScenario());
		register(new JobHeartbeatTickerHotScenario());
		register(new JobCancelVersionAndDbFallbackScenario());
		register(new JobWatchdogSingleflightScenario());
		register(new TaskDeleteSlowRedisLockHoldScenario());
		register(new TaskEventScenario.PubsubFanout());
		register(new TaskEventScenario.SlowEmitterSaturation());
		register(new FrameWakeScenario.AppendHotLarge());
		register(new FrameWakeScenario.ReplayGapAndTrim());
		register(new FrameWakeScenario.WakeCoalesceAndReconnect());
		register(new TranscriptPaginationScenario());
		register(new ContextCompactionLedgerScenario());
		register(new LedgerIdemLargeProjectionScenario());
		register(new AttachmentLifecycleScenario());
		register(new AttachmentFailureScenario());
		register(new SessionRunScenario.RegistrationAndGateToken());
		register(new SessionRunScenario.SnapshotHotSave());
		register(new SessionRunScenario.RecoveryIndexLarge());
		register(new SessionRunScenario.ClientRequestSweepLarge());
		register(new PurgeFailureScenario.MemoryPurgeGeneration());
		register(new PurgeFailureScenario.MemoryWritebackCrash());
		register(new PurgeFailureScenario.ClusterSlotAndErrorCleanup());
		register(new ResourceLockTwoPhaseReleaseScenario());
		register(new ScanLeaseLongAndStalledScenario());
		register(new KbCorpusScenario());
		register(new RedisAvailabilityScenario());

		// P2：固定配置、健康、缓存与降级（8）。
		register(new TaskEventScenario.RedisDownLazyStart());
		register(new CancelObservationScenario());
		register(new MemoryPurgeScenario.FindIdsScan());
		register(new KbCacheOutageScenario());
		register(new AiModelCatalogScenario());
		register(new CreateTaskDraftScenario());
		register(new TopologyHealthScenario.StartupTopologyContract());
		register(new TopologyHealthScenario.HealthDegradationObservability());
	}

	private ProductionScenarioCatalog() {
	}

	private static void register(ProductionScenario scenario) {
		ProductionScenario previous = ALL.putIfAbsent(scenario.id(), scenario);
		if (previous != null) {
			throw new IllegalStateException("重复生产场景 ID：" + scenario.id());
		}
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
