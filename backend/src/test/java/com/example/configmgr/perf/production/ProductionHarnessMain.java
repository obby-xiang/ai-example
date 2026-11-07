package com.example.configmgr.perf.production;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.session.SessionPurgePendingStore;
import com.example.configmgr.perf.redis.BackgroundBatchWriter;
import com.example.configmgr.perf.redis.CountingRedis;
import com.example.configmgr.perf.redis.HarnessConfig;
import com.example.configmgr.perf.redis.HarnessMetrics;
import com.example.configmgr.perf.redis.KeyShapes;
import com.example.configmgr.perf.redis.Manifest;
import com.example.configmgr.perf.redis.OutputWriter;
import com.example.configmgr.perf.redis.RedisHarness;

/** Direct production-class Redis scenarios with the existing manifest/results/verify/cleanup evidence files. */
public final class ProductionHarnessMain {

	private ProductionHarnessMain() {
	}

	public static void main(String[] args) {
		System.exit(run(args));
	}

	public static int run(String[] args) {
		if (args.length == 0 || "scenarios".equals(args[0])) {
			for (ProductionScenario scenario : ProductionScenarioCatalog.selected(null)) {
				System.out.println(scenario.id());
			}
			return args.length == 0 ? 2 : 0;
		}
		String command = args[0];
		boolean execute = hasFlag(args, "--execute");
		boolean keep = hasFlag(args, "--keep");
		boolean cleanupDryRun = hasFlag(args, "--cleanup-dry-run");
		HarnessConfig cfg;
		try {
			cfg = new HarnessConfig(System.getenv());
			if (cfg.namespace == null) {
				throw new IllegalArgumentException("生产场景必须显式设置独立 PERF_NAMESPACE");
			}
			cfg.validate(!"doctor".equals(command) && !"verify".equals(command));
			Files.createDirectories(cfg.outputDir);
		}
		catch (Exception ex) {
			System.err.println("配置失败：" + ex.getMessage());
			return 2;
		}
		OutputWriter writer = new OutputWriter(cfg.outputDir);
		Path manifestPath = cfg.outputDir.resolve("manifest.json");
		Manifest manifest = Files.exists(manifestPath) ? Manifest.load(manifestPath)
				: Manifest.empty(cfg.namespace, cfg.tenantId, cfg.userId, cfg.profile);
		if (!cfg.namespace.equals(manifest.namespace)) {
			System.err.println("manifest namespace 与 PERF_NAMESPACE 不一致");
			return 2;
		}
		HarnessMetrics metrics = new HarnessMetrics();
		loadProtocols(cfg.outputDir, metrics);
		loadStages(cfg.outputDir, metrics);
		List<ProductionScenario> selected = ProductionScenarioCatalog.selected(String.join(",", cfg.scenarios));
		if (selected.isEmpty()) {
			System.err.println("没有选择场景");
			return 2;
		}
		if (("generate".equals(command) || "measure".equals(command) || "cleanup".equals(command)
				|| "all".equals(command)) && !execute) {
			System.out.println("[dry-run] " + command + " 需要 --execute 才连接并写 Redis");
			for (ProductionScenario scenario : selected) {
				System.out.println("  " + scenario.id());
			}
			return 0;
		}
		try (ProductionSupport support = new ProductionSupport(cfg, metrics)) {
			evaluateCoverage(support, selected, manifest);
			int exit = switch (command) {
				case "doctor" -> doctor(support, writer);
				case "generate" -> generate(support, selected, manifest, manifestPath, writer, metrics);
				case "measure" -> measure(support, selected, manifest, manifestPath, writer, metrics);
				case "verify" -> verify(support, selected, manifest, writer, metrics);
				case "cleanup" -> cleanup(support, selected, manifest, manifestPath, writer, metrics);
				case "all" -> all(support, selected, manifest, manifestPath, writer, metrics, keep || cleanupDryRun);
				default -> 2;
			};
			int reportedExit = "PARTIAL".equals(support.coverageStatus) && exit == 0 ? 1 : exit;
			boolean cleanupExecuted = "cleanup".equals(command)
					|| ("all".equals(command) && !(keep || cleanupDryRun));
			Map<String, Object> cleanupResult = cleanupExecuted
					? readJson(cfg.outputDir.resolve("cleanup.json")) : null;
			writeResults(support, writer, metrics, manifest, reportedExit, null, cleanupResult);
			return reportedExit;
		}
		catch (RuntimeException ex) {
			System.err.println("运行失败：" + ex.getClass().getSimpleName() + ": " + ex.getMessage());
			return 1;
		}
	}

	private static int doctor(ProductionSupport support, OutputWriter writer) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("generatedAt", Instant.now().toString());
		body.put("config", support.cfg.redactedSummary());
		try {
			RedisHarness.ServerInfo info = support.harness.serverInfo();
			body.put("ping", support.harness.ping());
			body.put("redis", info);
			body.put("status", "ok");
			writer.json("doctor.json", body);
			return 0;
		}
		catch (RuntimeException ex) {
			body.put("status", "unknown");
			body.put("error", ex.toString());
			writer.json("doctor.json", body);
			return 2;
		}
	}

	private static int generate(ProductionSupport support, List<ProductionScenario> selected, Manifest manifest,
			Path manifestPath, OutputWriter writer, HarnessMetrics metrics) {
		int exit = 0;
		for (ProductionScenario scenario : selected) {
			Manifest.Dataset dataset = datasetFor(support, scenario, manifest, manifestPath);
			try {
				support.stage(dataset, "generate", () -> {
					try {
						return scenario.generate(support, dataset);
					}
					catch (Exception ex) {
						throw asRuntime(ex);
					}
				});
				manifest.add(dataset);
				manifest.save(manifestPath);
			}
			catch (Exception ex) {
				exit = 1;
				dataset.state = "failed";
				support.check(dataset, "generate_failed", false, ex.toString());
				manifest.add(dataset);
				manifest.save(manifestPath);
			}
		}
		writeResults(support, writer, metrics, manifest, exit, null, null);
		return exit;
	}

	private static int measure(ProductionSupport support, List<ProductionScenario> selected, Manifest manifest,
			Path manifestPath, OutputWriter writer, HarnessMetrics metrics) {
		int exit = 0;
		for (ProductionScenario scenario : selected) {
			Manifest.Dataset dataset = manifest.find(scenario.id(), 0);
			try {
				if (dataset == null) {
					dataset = datasetFor(support, scenario, manifest, manifestPath);
					scenario.generate(support, dataset);
					manifest.add(dataset);
					manifest.save(manifestPath);
				}
				Manifest.Dataset current = dataset;
				support.stage(current, "scenario_total", () -> {
					try {
						scenario.measure(support, current);
					}
					catch (Exception ex) {
						throw asRuntime(ex);
					}
				});
				metrics.scenarioStatus(scenario.id(), "measured".equals(dataset.state) ? "ok" : "fail",
						"state=" + dataset.state);
				manifest.add(dataset);
				manifest.save(manifestPath);
			}
			catch (Exception ex) {
				exit = 1;
				metrics.scenarioStatus(scenario.id(), "fail", ex.toString());
				if (dataset != null) {
					dataset.state = "failed";
					support.check(dataset, "scenario_failed", false, ex.toString());
					manifest.add(dataset);
					manifest.save(manifestPath);
				}
			}
		}
		writeResults(support, writer, metrics, manifest, exit, null, null);
		return exit;
	}

	private static int verify(ProductionSupport support, List<ProductionScenario> selected, Manifest manifest,
			OutputWriter writer, HarnessMetrics metrics) {
		List<Map<String, Object>> checks = new ArrayList<>();
		int exit = 0;
		for (ProductionScenario scenario : selected) {
			Manifest.Dataset dataset = manifest.find(scenario.id(), 0);
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("scenario", scenario.id());
			if (dataset == null || "generated".equals(dataset.state)) {
				row.put("status", "unknown");
				row.put("detail", "not measured");
				exit = Math.max(exit, 2);
			}
			else {
				boolean protocols = protocolsPassed(support, scenario.id());
				boolean retained = retainedPresent(support, dataset);
				boolean cleaned = "cleaned".equals(dataset.state);
				boolean ownedAbsent = !cleaned || ownedAbsent(support, dataset);
				boolean pass = protocols && retained && ownedAbsent && !"failed".equals(dataset.state)
						&& !"residue".equals(dataset.state);
				row.put("status", pass ? "ok" : "fail");
				row.put("protocols", protocols);
				row.put("retainedPresent", retained);
				row.put("ownedAbsentWhenCleaned", ownedAbsent);
				row.put("state", dataset.state);
				if (!pass) {
					exit = 1;
				}
			}
			metrics.scenarioStatus(scenario.id(), String.valueOf(row.get("status")),
					String.valueOf(row.getOrDefault("detail", row.get("state"))));
			checks.add(row);
		}
		if ("PARTIAL".equals(support.coverageStatus) && exit == 0) {
			exit = 1;
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("generatedAt", Instant.now().toString());
		body.put("coverageStatus", support.coverageStatus);
		body.put("coverageReason", support.coverageReason);
		body.put("status", "PARTIAL".equals(support.coverageStatus) && exit == 1 ? "partial"
				: (exit == 0 ? "ok" : (exit == 2 ? "unknown" : "fail")));
		body.put("checks", checks);
		writer.json("verify.json", body);
		writeResults(support, writer, metrics, manifest, exit, body, null);
		return exit;
	}

	private static int cleanup(ProductionSupport support, List<ProductionScenario> selected, Manifest manifest,
			Path manifestPath, OutputWriter writer, HarnessMetrics metrics) {
		String attemptId = UUID.randomUUID().toString();
		String startedAt = Instant.now().toString();
		int exit = 0;
		List<Map<String, Object>> rows = new ArrayList<>();
		for (ProductionScenario scenario : selected) {
			Manifest.Dataset dataset = manifest.find(scenario.id(), 0);
			if (dataset == null) {
				Map<String, Object> missing = new LinkedHashMap<>();
				missing.put("scenario", scenario.id());
				missing.put("status", "unknown");
				missing.put("reason", "not generated");
				missing.put("unknown", 1L);
				rows.add(missing);
				metrics.scenarioStatus(scenario.id(), "unknown", "not generated");
				exit = 2;
				continue;
			}
			Map<String, Object> row = new LinkedHashMap<>();
			long deleted = 0L;
			long unknown = 0L;
			List<Map<String, Object>> failures = List.of();
			List<Map<String, Object>> removalReplies = new ArrayList<>();
			try {
				KeyCleanup cleaned = support.stage(dataset, "cleanup_exact_owned_keys",
						() -> deleteOwned(support, dataset, removalReplies));
				deleted = cleaned.deleted;
				failures = cleaned.failures;
				unknown = failures.size();
				List<String> residue = ownedPresent(support, dataset);
				boolean retained = retainedPresent(support, dataset);
				boolean clean = failures.isEmpty() && residue.isEmpty() && retained;
				dataset.state = clean ? "cleaned" : "residue";
				row.put("status", !failures.isEmpty() ? "unknown" : (clean ? "ok" : "fail"));
				row.put("residue", residue.stream().limit(20).toList());
				row.put("residueCount", residue.size());
				row.put("retainedPresent", retained);
				row.put("failures", failures);
				if (!failures.isEmpty()) {
					exit = Math.max(exit, 2);
				}
				else if (!clean) {
					exit = 1;
				}
			}
			catch (RuntimeException ex) {
				unknown++;
				dataset.state = "residue";
				row.put("status", "unknown");
				row.put("error", ex.toString());
				exit = Math.max(exit, 2);
			}
			row.put("scenario", scenario.id());
			metrics.scenarioStatus(scenario.id(), String.valueOf(row.get("status")), "state=" + dataset.state);
			row.put("deleted", deleted);
			row.put("unknown", unknown);
			row.put("removalReplies", removalReplies);
			rows.add(row);
			manifest.add(dataset);
			manifest.save(manifestPath);
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("attemptId", attemptId);
		body.put("startedAt", startedAt);
		body.put("endedAt", Instant.now().toString());
		body.put("complete", exit == 0);
		body.put("datasets", rows);
		body.put("safety", "exact owned keys and members only; retained keys are never deleted; no FLUSH/KEYS/SCAN");
		writer.json("cleanup.json", body);
		writeResults(support, writer, metrics, manifest, exit, null, body);
		return exit;
	}

	private static int all(ProductionSupport support, List<ProductionScenario> selected, Manifest manifest,
			Path manifestPath, OutputWriter writer, HarnessMetrics metrics, boolean skipCleanup) {
		int exit = generate(support, selected, manifest, manifestPath, writer, metrics);
		exit = Math.max(exit, measure(support, selected, manifest, manifestPath, writer, metrics));
		exit = Math.max(exit, verify(support, selected, manifest, writer, metrics));
		if (!skipCleanup) {
			exit = Math.max(exit, cleanup(support, selected, manifest, manifestPath, writer, metrics));
		}
		return exit;
	}

	private record KeyCleanup(long deleted, List<Map<String, Object>> failures) {
	}

	private static KeyCleanup deleteOwned(ProductionSupport support, Manifest.Dataset dataset,
			List<Map<String, Object>> removalReplies) {
		long deleted = 0L;
		List<Map<String, Object>> failures = new ArrayList<>();
		if ("kb_generation_prune_recovery".equals(dataset.scenario)) {
			deleted += KbGenerationPruneScenario.cleanupSharedState(support, dataset);
		}
		else if ("kb_refresh_large_corpus".equals(dataset.scenario)) {
			deleted += KbCorpusScenario.cleanupSharedState(support, dataset);
		}
		else if ("kb_cache_outage_fallback".equals(dataset.scenario)) {
			deleted += KbCacheOutageScenario.cleanupSharedState(support, dataset);
		}
		for (Map.Entry<String, List<String>> index : dataset.ownedHashFields.entrySet()) {
			for (String field : index.getValue()) {
				Long reply = support.redis.opsForHash().delete(index.getKey(), field);
				removalReplies.add(removalReply("HDEL", index.getKey(), field, reply));
				deleted += reply;
			}
		}
		KeyCleanup owned = deleteKeysPipelined(support, dataset.keys);
		deleted += owned.deleted;
		failures.addAll(owned.failures);
		for (String key : dataset.keys) {
			if (key.startsWith(SessionPurgePendingStore.PENDING_PREFIX)) {
				support.redis.opsForZSet().remove(SessionPurgePendingStore.DUE_KEY, key);
			}
		}
		for (String runId : dataset.runIds) {
			String recoveryDueMember = support.cfg.tenantId + "/" + support.cfg.userId + "/" + runId;
			Long recoveryDueReply = support.redis.opsForZSet().remove(RunStore.RECOVERY_DUE_INDEX, recoveryDueMember);
			removalReplies.add(removalReply("ZREM", RunStore.RECOVERY_DUE_INDEX, recoveryDueMember, recoveryDueReply));
			String identityIndex = RunStore.RECOVERY_DUE_IDENTITY_PREFIX
					+ support.cfg.tenantId + ":" + support.cfg.userId;
			Long identityReply = support.redis.opsForZSet().remove(identityIndex, runId);
			removalReplies.add(removalReply("ZREM", identityIndex, runId, identityReply));
			String runListIndex = RunStore.RUN_LIST_INDEX_PREFIX
					+ support.cfg.tenantId + ":" + support.cfg.userId;
			Long runListReply = support.redis.opsForZSet().remove(runListIndex, runId);
			removalReplies.add(removalReply("ZREM", runListIndex, runId, runListReply));
		}
		if (!dataset.externalBackground) {
			List<String> backgroundKeys = new ArrayList<>(dataset.background);
			for (int i = 0; i < dataset.background; i++) {
				backgroundKeys.add(KeyShapes.backgroundKey(support.cfg.namespace, dataset.backgroundBucket, i));
			}
			KeyCleanup background = deleteKeysPipelined(support, backgroundKeys);
			deleted += background.deleted;
			failures.addAll(background.failures);
		}
		return new KeyCleanup(deleted, failures);
	}

	private static Map<String, Object> removalReply(String operation, String key, String member, Object reply) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("operation", operation);
		row.put("key", key);
		row.put("member", member);
		row.put("reply", reply);
		return row;
	}

	private static KeyCleanup deleteKeysPipelined(ProductionSupport support, List<String> keys) {
		long deleted = 0L;
		List<Map<String, Object>> failures = new ArrayList<>();
		CountingRedis redis = new CountingRedis(support.redis, support.cfg.commandTimeout);
		for (List<String> batch : CountingRedis.batch(keys, BackgroundBatchWriter.BATCH_SIZE)) {
			List<String[]> commands = new ArrayList<>(batch.size());
			for (String key : batch) {
				commands.add(new String[] { "DEL", key });
			}
			List<Object> replies = redis.pipelineRawAttempts(commands);
			for (int i = 0; i < batch.size(); i++) {
				String key = batch.get(i);
				Object reply = i < replies.size() ? replies.get(i) : null;
				if (reply instanceof Number number) {
					deleted += number.longValue();
					continue;
				}
				Map<String, Object> failure = new LinkedHashMap<>();
				failure.put("key", key);
				failure.put("error", reply instanceof RuntimeException exception
						? exception.toString() : "missing or invalid pipeline reply");
				failures.add(failure);
			}
		}
		return new KeyCleanup(deleted, failures);
	}

	private static List<String> presentKeysPipelined(ProductionSupport support, List<String> keys) {
		List<String> present = new ArrayList<>();
		CountingRedis redis = new CountingRedis(support.redis, support.cfg.commandTimeout);
		for (List<String> batch : CountingRedis.batch(keys, BackgroundBatchWriter.BATCH_SIZE)) {
			List<String[]> commands = new ArrayList<>(batch.size());
			for (String key : batch) {
				commands.add(new String[] { "EXISTS", key });
			}
			List<Object> replies = redis.pipelineRaw(commands);
			for (int i = 0; i < batch.size(); i++) {
				Object reply = i < replies.size() ? replies.get(i) : null;
				if (!(reply instanceof Number number)) {
					throw new IllegalStateException("EXISTS pipeline 返回缺失或非法，key=" + batch.get(i));
				}
				if (number.longValue() > 0L) {
					present.add(batch.get(i));
				}
			}
		}
		return present;
	}

	private static boolean ownedAbsent(ProductionSupport support, Manifest.Dataset dataset) {
		return ownedPresent(support, dataset).isEmpty();
	}

	private static List<String> ownedPresent(ProductionSupport support, Manifest.Dataset dataset) {
		List<String> present = new ArrayList<>();
		if ("kb_generation_prune_recovery".equals(dataset.scenario)) {
			present.addAll(KbGenerationPruneScenario.sharedStateResidue(support, dataset));
		}
		else if ("kb_refresh_large_corpus".equals(dataset.scenario)) {
			present.addAll(KbCorpusScenario.sharedStateResidue(support, dataset));
		}
		else if ("kb_cache_outage_fallback".equals(dataset.scenario)) {
			present.addAll(KbCacheOutageScenario.sharedStateResidue(support, dataset));
		}
		for (Map.Entry<String, List<String>> index : dataset.ownedHashFields.entrySet()) {
			for (String field : index.getValue()) {
				if (support.redis.opsForHash().hasKey(index.getKey(), field)) {
					present.add(index.getKey() + "#" + field);
				}
			}
		}
		present.addAll(presentKeysPipelined(support, dataset.keys));
		if (!dataset.externalBackground) {
			List<String> backgroundKeys = new ArrayList<>(dataset.background);
			for (int i = 0; i < dataset.background; i++) {
				backgroundKeys.add(KeyShapes.backgroundKey(support.cfg.namespace, dataset.backgroundBucket, i));
			}
			present.addAll(presentKeysPipelined(support, backgroundKeys));
		}
		for (String key : dataset.keys) {
			if (key.startsWith(SessionPurgePendingStore.PENDING_PREFIX)
					&& support.redis.opsForZSet().score(SessionPurgePendingStore.DUE_KEY, key) != null) {
				present.add("purge-due:" + key);
			}
		}
		for (String runId : dataset.runIds) {
			String recoveryDueMember = support.cfg.tenantId + "/" + support.cfg.userId + "/" + runId;
			if (support.redis.opsForZSet().score(RunStore.RECOVERY_DUE_INDEX, recoveryDueMember) != null) {
				present.add("run-index:" + RunStore.RECOVERY_DUE_INDEX + "#" + recoveryDueMember);
			}
			String identityIndex = RunStore.RECOVERY_DUE_IDENTITY_PREFIX
					+ support.cfg.tenantId + ":" + support.cfg.userId;
			if (support.redis.opsForZSet().score(identityIndex, runId) != null) {
				present.add("run-index:" + identityIndex + "#" + runId);
			}
			String runListIndex = RunStore.RUN_LIST_INDEX_PREFIX
					+ support.cfg.tenantId + ":" + support.cfg.userId;
			if (support.redis.opsForZSet().score(runListIndex, runId) != null) {
				present.add("run-index:" + runListIndex + "#" + runId);
			}
		}
		return present;
	}

	private static boolean retainedPresent(ProductionSupport support, Manifest.Dataset dataset) {
		for (String key : dataset.retainedKeys) {
			if (!support.redis.hasKey(key)) {
				return false;
			}
		}
		return true;
	}

	private static boolean protocolsPassed(ProductionSupport support, String scenario) {
		List<HarnessMetrics.ProtocolRecord> current = support.metrics.protocols().stream()
				.filter(row -> row.scenario().equals(scenario)).toList();
		return !current.isEmpty() && current.stream().allMatch(HarnessMetrics.ProtocolRecord::pass);
	}

	private static void evaluateCoverage(ProductionSupport support, List<ProductionScenario> selected,
			Manifest manifest) {
		for (ProductionScenario scenario : selected) {
			Manifest.Dataset previous = manifest.find(scenario.id(), 0);
			Object limit = previous == null ? null : previous.attributes.get("coverageLimit");
			if (limit != null && !String.valueOf(limit).isBlank()) {
				support.coverageStatus = "PARTIAL";
				support.coverageReason = scenario.id() + ": " + limit;
				return;
			}
		}
		java.util.Set<String> actual = selected.stream().map(ProductionScenario::id)
				.collect(java.util.stream.Collectors.toSet());
		java.util.Set<String> required = ProductionScenarioCatalog.selected(null).stream().map(ProductionScenario::id)
				.collect(java.util.stream.Collectors.toSet());
		if (!actual.equals(required)) {
			support.coverageStatus = "PARTIAL";
			support.coverageReason = "scenario subset: " + actual;
			return;
		}
		for (String operation : List.of(
				"rpush", "sadd", "rename", "cas", "staging_discard", "hdel",
				"eval", "hgetall", "set", "get",
				"index_eval", "registration_delete", "tombstone_set", "del", "srem",
				"lpush", "hget", "ping", "subscribe",
				"zrem", "AI_FRAME_APPEND", "scan", "MEMORY_CLEAR",
				"zrangebyscore", "pexpire")) {
			if (!support.faultEnabled(operation)) {
				support.coverageStatus = "PARTIAL";
				support.coverageReason = "required fault not enabled: " + operation;
				return;
			}
		}
		support.coverageStatus = "COMPLETE";
		support.coverageReason = "all P0-P2 scenarios and required fault points enabled";
	}

	private static Manifest.Dataset datasetFor(ProductionSupport support, ProductionScenario scenario,
			Manifest manifest, Path manifestPath) {
		Manifest.Dataset dataset = manifest.find(scenario.id(), 0);
		if (dataset == null) {
			dataset = support.dataset(scenario.id());
			manifest.add(dataset);
			manifest.save(manifestPath);
		}
		return dataset;
	}

	private static void loadProtocols(Path outputDir, HarnessMetrics metrics) {
		Path path = outputDir.resolve("protocols.json");
		if (!Files.exists(path)) {
			return;
		}
		try {
			List<Map<String, Object>> rows = Manifest.mapper().readValue(path.toFile(),
					new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() { });
			for (Map<String, Object> row : rows) {
				metrics.protocol(String.valueOf(row.get("scenario")), String.valueOf(row.get("name")),
						Boolean.TRUE.equals(row.get("pass")), String.valueOf(row.get("detail")),
						((Number) row.getOrDefault("durationNanos", 0L)).longValue());
			}
		}
		catch (Exception ex) {
			throw new IllegalStateException("无法读取既有 protocols.json", ex);
		}
	}

	@SuppressWarnings("unchecked")
	private static void loadStages(Path outputDir, HarnessMetrics metrics) {
		Path path = outputDir.resolve("results.json");
		if (!Files.exists(path)) {
			return;
		}
		try {
			Map<String, Object> body = Manifest.mapper().readValue(path.toFile(),
					new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
			for (Map<String, Object> row : (List<Map<String, Object>>) body.getOrDefault("stages", List.of())) {
				com.example.configmgr.perf.redis.CountingRedis.Snapshot delta =
						new com.example.configmgr.perf.redis.CountingRedis.Snapshot(longValue(row, "commands"),
								longValue(row, "pipelineBatches"), longValue(row, "scanPages"),
								longValue(row, "slotChecks"), longValue(row, "requestBytes"),
								longValue(row, "responseBytes"));
				metrics.stageExternal(String.valueOf(row.get("scenario")),
						((Number) row.getOrDefault("round", 0)).intValue(), String.valueOf(row.get("stage")),
						longValue(row, "durationNanos"), delta, String.valueOf(row.get("status")),
						String.valueOf(row.get("detail")));
			}
		}
		catch (Exception ex) {
			throw new IllegalStateException("无法读取既有 results.json stages", ex);
		}
	}

	private static long longValue(Map<String, Object> row, String name) {
		return ((Number) row.getOrDefault(name, 0L)).longValue();
	}

	private static Map<String, Object> readJson(Path path) {
		if (!Files.exists(path)) {
			return null;
		}
		try {
			return Manifest.mapper().readValue(path.toFile(),
					new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
		}
		catch (Exception ex) {
			throw new IllegalStateException("无法读取 " + path, ex);
		}
	}

	private static void writeResults(ProductionSupport support, OutputWriter writer, HarnessMetrics metrics,
			Manifest manifest, int exit, Map<String, Object> verify, Map<String, Object> cleanup) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("generatedAt", Instant.now().toString());
		body.put("config", support.cfg.redactedSummary());
		body.put("summaries", metrics.allSummaries());
		body.put("stages", metrics.stages());
		body.put("protocols", metrics.protocols());
		body.put("datasets", manifest.datasets);
		body.put("failedCommands", support.commandMetrics.failedCommands());
		body.put("keyspaceScanPages", support.commandMetrics.keyspaceScanPages());
		body.put("hashScanPages", support.commandMetrics.hashScanPages());
		body.put("verify", verify == null ? readJson(support.cfg.outputDir.resolve("verify.json")) : verify);
		body.put("cleanup", cleanup);
		body.put("coverageStatus", support.coverageStatus);
		body.put("coverageReason", support.coverageReason);
		body.put("status", "PARTIAL".equals(support.coverageStatus) && exit == 1 ? "partial"
				: (exit == 0 ? "ok" : (exit == 2 ? "unknown" : "fail")));
		writer.json("results.json", body);
		writer.json("protocols.json", metrics.protocols());
	}

	private static RuntimeException asRuntime(Exception ex) {
		return ex instanceof RuntimeException runtime ? runtime : new IllegalStateException(ex);
	}

	private static boolean hasFlag(String[] args, String flag) {
		for (String arg : args) {
			if (flag.equals(arg)) {
				return true;
			}
		}
		return false;
	}

}
