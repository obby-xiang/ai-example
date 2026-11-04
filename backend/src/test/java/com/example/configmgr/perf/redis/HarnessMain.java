package com.example.configmgr.perf.redis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 独立的 Redis 候选协议验证 harness（backend test 源集，不进入生产包）。
 *
 * <p>不启动服务、不调用 HTTP、不驱动模型；只直连显式配置的隔离 Redis。
 * 默认单元测试不连接 Redis；真实运行必须 {@code --execute} + 安全门禁环境变量。
 */
public final class HarnessMain {

	private static final String EXIT_OK = "ok";

	private static final String EXIT_FAIL = "fail";

	private static final String EXIT_UNKNOWN = "unknown";

	public static void main(String[] args) {
		System.exit(run(args));
	}

	public static int run(String[] args) {
		Args parsed;
		try {
			parsed = Args.parse(args);
		}
		catch (RuntimeException ex) {
			System.err.println(ex.getMessage());
			printHelp();
			return 2;
		}
		if (parsed.help()) {
			printHelp();
			return 0;
		}
		if (parsed.command() == null) {
			printHelp();
			return 2;
		}
		if ("scenarios".equals(parsed.command())) {
			printScenarios();
			return 0;
		}

		HarnessConfig base;
		try {
			base = new HarnessConfig(System.getenv());
		}
		catch (RuntimeException ex) {
			System.err.println("配置解析失败：" + ex.getMessage());
			return 2;
		}
		Path outputDir = parsed.output() == null ? base.outputDir : Path.of(parsed.output());
		try {
			Files.createDirectories(outputDir);
		}
		catch (Exception ex) {
			System.err.println("无法创建输出目录：" + outputDir);
			return 2;
		}
		if (!base.isolatedEnv) {
			System.err.println("拒绝连接：必须显式 PERF_ISOLATED_ENV=true（专用隔离实例）");
			return 2;
		}
		String namespace = resolveNamespace(base, outputDir);
		HarnessConfig cfg = base.withNamespace(namespace);
		OutputWriter writer = new OutputWriter(outputDir);
		Path manifestPath = outputDir.resolve("manifest.json");
		Manifest manifest = Files.exists(manifestPath) ? Manifest.load(manifestPath)
				: Manifest.empty(namespace, cfg.tenantId, cfg.userId, cfg.profile);
		if (manifest.namespace != null && !manifest.namespace.equals(namespace)) {
			System.err.println("输出目录里的 manifest namespace=" + manifest.namespace
					+ " 与本次 PERF_NAMESPACE=" + namespace + " 不一致；请换输出目录或换命名空间");
			return 2;
		}
		Ctx ctx = new Ctx(cfg, writer, manifest, manifestPath, parsed);

		try {
			return switch (parsed.command()) {
				case "doctor" -> doctor(ctx);
				case "generate" -> generate(ctx);
				case "measure" -> measure(ctx);
				case "verify" -> verify(ctx);
				case "cleanup" -> cleanup(ctx);
				case "all" -> all(ctx);
				default -> {
					System.err.println("未知子命令：" + parsed.command());
					printHelp();
					yield 2;
				}
			};
		}
		catch (IllegalArgumentException ex) {
			System.err.println("配置/安全门禁失败：" + ex.getMessage());
			return 2;
		}
		catch (BudgetExceededException ex) {
			System.err.println("预算耗尽：" + ex.getMessage());
			return 1;
		}
		catch (RuntimeException ex) {
			System.err.println("运行失败：" + ex.getClass().getSimpleName() + ": " + ex.getMessage());
			return 1;
		}
	}

	private static String resolveNamespace(HarnessConfig cfg, Path outputDir) {
		if (cfg.namespace != null) {
			return cfg.namespace;
		}
		Path marker = outputDir.resolve(".namespace");
		try {
			if (Files.exists(marker)) {
				String existing = Files.readString(marker).trim();
				if (!existing.isBlank()) {
					return existing;
				}
			}
			String generated = "rp-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
			Files.writeString(marker, generated);
			return generated;
		}
		catch (Exception ex) {
			throw new IllegalStateException("无法创建命名空间状态文件 " + marker, ex);
		}
	}

	private static int doctor(Ctx ctx) {
		ctx.cfg.validate(false);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("generatedAt", Instant.now().toString());
		body.put("config", ctx.cfg.redactedSummary());
		try (RedisHarness harness = RedisHarness.open(ctx.cfg)) {
			String ping = harness.ping();
			RedisHarness.ServerInfo info = harness.serverInfo();
			boolean modeMatch = ctx.cfg.mode == HarnessConfig.Mode.CLUSTER ? info.clusterEnabled()
					: !info.clusterEnabled();
			body.put("status", modeMatch ? EXIT_OK : EXIT_UNKNOWN);
			body.put("ping", ping);
			body.put("redisVersion", info.version());
			body.put("redisMode", info.mode());
			body.put("dbSize", info.dbSize());
			body.put("modeMatch", modeMatch);
			body.put("namespaceKey", KeyShapes.namespaceIndexKey(ctx.cfg.namespace));
			body.put("slotChecks", slotDoctor(harness, ctx.cfg));
			ctx.writer.json("doctor.json", body);
			System.out.println("doctor: " + body.get("status") + " mode=" + info.mode() + " version="
					+ info.version() + " dbsize=" + info.dbSize() + " namespace=" + ctx.cfg.namespace);
			return modeMatch ? 0 : 2;
		}
		catch (RuntimeException ex) {
			body.put("status", EXIT_FAIL);
			body.put("error", ex.getClass().getSimpleName() + ": " + ex.getMessage());
			ctx.writer.json("doctor.json", body);
			System.err.println("doctor: fail " + ex.getMessage());
			return 2;
		}
	}

	private static Map<String, Object> slotDoctor(RedisHarness harness, HarnessConfig cfg) {
		String runId = KeyShapes.runId(KeyShapes.sessionId(cfg.namespace, "doctor", 0), 0);
		List<String> runKeys = KeyShapes.runDeleteKeys(cfg.tenantId, cfg.userId, runId);
		boolean same = RedisSlots.sameSlot(runKeys);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("runKeys", runKeys.size());
		out.put("localSameSlot", same);
		out.put("runSlot", RedisSlots.slot(runKeys.get(0)));
		if (cfg.mode == HarnessConfig.Mode.CLUSTER) {
			boolean serverSame = true;
			long serverSlot = harness.clusterKeyslot(runKeys.get(0));
			for (String key : runKeys) {
				serverSame &= harness.clusterKeyslot(key) == serverSlot;
			}
			out.put("serverSameSlot", serverSame);
			out.put("serverSlot", serverSlot);
		}
		return out;
	}

	private static int generate(Ctx ctx) {
		return generateSelected(ctx, selected(ctx));
	}

	private static List<ScenarioCatalog.Scenario> selected(Ctx ctx) {
		List<String> explicit = ctx.parsed.scenarios().isEmpty() ? ctx.cfg.scenarios
				: ctx.parsed.scenarios();
		return ScenarioCatalog.selected(ctx.cfg.profile, explicit);
	}

	private static int generateSelected(Ctx ctx, List<ScenarioCatalog.Scenario> selected) {
		ctx.cfg.validate(true);
		if (ctx.cfg.needsLargeScaleGate(selected) && !ctx.cfg.allowLargeScale) {
			throw new IllegalArgumentException("包含 large 场景但 PERF_ALLOW_LARGE_SCALE 未开启");
		}
		for (ScenarioCatalog.Scenario scenario : selected) {
			if (ScenarioCatalog.requiresBackground100k(scenario) && !ctx.cfg.allowBackground100k) {
				throw new IllegalArgumentException("bg_100k_runs_1 需要 PERF_ALLOW_BACKGROUND_100K=true");
			}
		}
		if (!ctx.parsed.execute()) {
			System.out.println("[dry-run] generate 将写入以下场景（加 --execute 才执行）：");
			for (ScenarioCatalog.Scenario scenario : selected) {
				System.out.println("  " + scenario.id() + " runs=" + scenario.runs() + " staging="
						+ scenario.staging() + " archives=" + scenario.archives() + " bg="
						+ scenario.background() + " copies=" + (ctx.cfg.warmup + ctx.cfg.repeat));
			}
			return 0;
		}
		try (RedisHarness harness = RedisHarness.open(ctx.cfg)) {
			DatasetGenerator generator = new DatasetGenerator(ctx.cfg, harness.redis());
			for (ScenarioCatalog.Scenario scenario : selected) {
				generateScenario(ctx, generator, scenario);
			}
			return 0;
		}
	}

	private static void generateScenario(Ctx ctx, DatasetGenerator generator,
			ScenarioCatalog.Scenario scenario) {
		checkBackgroundGuard(ctx, scenario);
		int copies = ctx.cfg.warmup + ctx.cfg.repeat;
		for (int copy = 0; copy < copies; copy++) {
			Manifest.Dataset dataset = generator.generate(scenario, copy);
			ctx.manifest.add(dataset);
			ctx.manifest.save(ctx.manifestPath);
			System.out.println("generated " + scenario.id() + "/" + copy + " keys=" + dataset.keys.size()
					+ (dataset.background > 0 ? " bg=" + dataset.background : ""));
		}
	}

	private static void checkBackgroundGuard(Ctx ctx, ScenarioCatalog.Scenario scenario) {
		if (scenario.background() <= 0) {
			return;
		}
		String marker = KeyShapes.backgroundMarkerKey(ctx.cfg.namespace);
		String current = readKey(ctx, marker);
		if (current != null && !current.equals(String.valueOf(scenario.background()))) {
			throw new IllegalArgumentException("同一命名空间已有背景规模 " + current
					+ "，不得同时生成 " + scenario.background() + "；先 cleanup 该场景");
		}
	}

	private static String readKey(Ctx ctx, String key) {
		try (RedisHarness harness = RedisHarness.open(ctx.cfg)) {
			return harness.redis().get(key);
		}
	}

	private static int measure(Ctx ctx) {
		List<ScenarioCatalog.Scenario> selected = selected(ctx);
		ctx.cfg.validate(true);
		if (!ctx.parsed.execute()) {
			System.out.println("[dry-run] measure 会删除数据（加 --execute 才执行）：");
			for (ScenarioCatalog.Scenario scenario : selected) {
				System.out.println("  " + scenario.id());
			}
			return 0;
		}
		try (RedisHarness harness = RedisHarness.open(ctx.cfg)) {
			Budget budget = new Budget(ctx.cfg.totalBudget, ctx.cfg.scenarioBudget, System::nanoTime);
			runMeasure(ctx, harness, selected, budget);
		}
		return finish(ctx, selected);
	}

	private static void runMeasure(Ctx ctx, RedisHarness harness, List<ScenarioCatalog.Scenario> selected,
			Budget budget) {
		DatasetGenerator generator = new DatasetGenerator(ctx.cfg, harness.redis());
		PurgeProtocol purge = new PurgeProtocol(ctx.cfg, harness.redis(), ctx.metrics, ctx.manifest,
				"harness-" + ProcessHandle.current().pid());
		RunProtocol protocol = new RunProtocol(ctx.cfg, harness.redis(), ctx.metrics, ctx.manifest);
		for (ScenarioCatalog.Scenario scenario : selected) {
			if (budget.totalExhausted()) {
				ctx.metrics.scenarioStatus(scenario.id(), "skipped", "total budget exhausted");
				continue;
			}
			Deadline deadline = budget.startScenario(scenario.id());
			try {
				if (scenario.protocol()) {
					ensureCopy(ctx, generator, scenario, 0);
					protocol.run(scenario, 0, deadline);
					boolean pass = ctx.metrics.protocols().stream()
							.filter(p -> p.scenario().equals(scenario.id())).allMatch(p -> p.pass());
					ctx.metrics.scenarioStatus(scenario.id(), pass ? EXIT_OK : EXIT_FAIL,
							pass ? "protocol pass" : "protocol fail");
					ctx.manifest.require(scenario.id(), 0).state = "protocol";
					if (protocol.unsafeForCleanup()) {
						ctx.unsafeForCleanup = true;
					}
				}
				else if ("dual_executor".equals(scenario.id())) {
					runDual(ctx, generator, purge, scenario, deadline);
				}
				else if ("lease_takeover".equals(scenario.id())) {
					runTakeover(ctx, generator, purge, scenario, deadline);
				}
				else if ("duplicate_delete".equals(scenario.id())) {
					runDuplicate(ctx, generator, purge, scenario, deadline);
				}
				else {
					runNormal(ctx, generator, purge, scenario, deadline);
				}
			}
			catch (BudgetExceededException ex) {
				ctx.budgetExhausted = true;
				ctx.metrics.scenarioStatus(scenario.id(), "partial", "budget_exhausted:" + ex.phase());
				System.err.println("budget exhausted at " + scenario.id() + " phase=" + ex.phase());
				break;
			}
			ctx.purges.addAll(takePurges(ctx));
			ctx.manifest.save(ctx.manifestPath);
		}
		ctx.writeResults();
	}

	private static List<Map<String, Object>> takePurges(Ctx ctx) {
		List<Map<String, Object>> out = new ArrayList<>(ctx.pendingPurgeRows);
		ctx.pendingPurgeRows.clear();
		return out;
	}

	private static void runNormal(Ctx ctx, DatasetGenerator generator, PurgeProtocol purge,
			ScenarioCatalog.Scenario scenario, Deadline deadline) {
		int total = ctx.cfg.warmup + ctx.cfg.repeat;
		boolean allComplete = true;
		boolean anyDeferred = false;
		for (int round = 0; round < total; round++) {
			Manifest.Dataset dataset = ensureCopy(ctx, generator, scenario, round);
			if ("purged".equals(dataset.state) || "deferred".equals(dataset.state)) {
				dataset = regenerate(ctx, generator, scenario, round);
			}
			ctx.metrics.beginRound(scenario.id(), round);
			long start = System.nanoTime();
			PurgeProtocol.PurgeOutcome outcome = purge.purge(scenario, round, round < ctx.cfg.warmup,
					false, !ScenarioCatalog.expectedDeferred(scenario.id()), deadline);
			long nanos = System.nanoTime() - start;
			ctx.metrics.deleteLatency(scenario.id(), round, nanos, round < ctx.cfg.warmup);
			recordPurge(ctx, outcome, round, nanos);
			dataset.state = outcome.complete() ? "purged"
					: ("DEFERRED".equals(outcome.pendingStage()) ? "deferred" : "failed");
			allComplete &= outcome.complete();
			anyDeferred |= !outcome.deferred().isEmpty();
			ctx.manifest.save(ctx.manifestPath);
		}
		ctx.metrics.scenarioStatus(scenario.id(), allComplete ? EXIT_OK : (anyDeferred ? "deferred" : EXIT_FAIL),
				allComplete ? "all rounds complete" : (anyDeferred ? "deferred (no half delete)" : "failed"));
	}

	private static Manifest.Dataset ensureCopy(Ctx ctx, DatasetGenerator generator,
			ScenarioCatalog.Scenario scenario, int copy) {
		Manifest.Dataset dataset = ctx.manifest.find(scenario.id(), copy);
		if (dataset == null) {
			dataset = generator.generate(scenario, copy);
			ctx.manifest.add(dataset);
			ctx.manifest.save(ctx.manifestPath);
		}
		return dataset;
	}

	private static Manifest.Dataset regenerate(Ctx ctx, DatasetGenerator generator,
			ScenarioCatalog.Scenario scenario, int copy) {
		Manifest.Dataset dataset = generator.generate(scenario, copy);
		ctx.manifest.add(dataset);
		ctx.manifest.save(ctx.manifestPath);
		return dataset;
	}

	private static void runDuplicate(Ctx ctx, DatasetGenerator generator, PurgeProtocol purge,
			ScenarioCatalog.Scenario scenario, Deadline deadline) {
		Manifest.Dataset dataset = ensureCopy(ctx, generator, scenario, 0);
		if (!"generated".equals(dataset.state)) {
			dataset = regenerate(ctx, generator, scenario, 0);
		}
		ctx.metrics.beginRound(scenario.id(), 0);
		long start = System.nanoTime();
		PurgeProtocol.PurgeOutcome first = purge.purge(scenario, 0, false, false, deadline);
		long firstNanos = System.nanoTime() - start;
		ctx.metrics.deleteLatency(scenario.id(), 0, firstNanos, false);
		long secondStart = System.nanoTime();
		PurgeProtocol.PurgeOutcome second = purge.purge(scenario, 0, false, false, deadline);
		long secondNanos = System.nanoTime() - secondStart;
		recordPurge(ctx, first, 0, firstNanos);
		recordPurge(ctx, second, 1, secondNanos);
		boolean pass = first.complete() && second.complete() && !second.found();
		ctx.metrics.protocol(scenario.id(), "duplicate_delete_idempotent", pass,
				"first.complete=" + first.complete() + " second.complete=" + second.complete()
						+ " second.found=" + second.found(), 0L);
		ctx.metrics.scenarioStatus(scenario.id(), pass ? EXIT_OK : EXIT_FAIL,
				pass ? "duplicate delete idempotent" : "duplicate delete mismatch");
		dataset.state = pass ? "purged" : "failed";
	}

	private static void runDual(Ctx ctx, DatasetGenerator generator, PurgeProtocol purge,
			ScenarioCatalog.Scenario scenario, Deadline deadline) {
		Manifest.Dataset dataset = ensureCopy(ctx, generator, scenario, 0);
		if (!"generated".equals(dataset.state)) {
			dataset = regenerate(ctx, generator, scenario, 0);
		}
		Manifest.Dataset target = dataset;
		CyclicBarrier barrier = new CyclicBarrier(2);
		AtomicReference<PurgeProtocol.Lease> a = new AtomicReference<>();
		AtomicReference<PurgeProtocol.Lease> b = new AtomicReference<>();
		AtomicReference<Throwable> e1 = new AtomicReference<>();
		AtomicReference<Throwable> e2 = new AtomicReference<>();
		Thread t1 = new Thread(() -> {
			try {
				await(barrier);
				a.set(purge.acquire(target, true));
			}
			catch (Throwable ex) {
				e1.set(ex);
			}
		});
		Thread t2 = new Thread(() -> {
			try {
				await(barrier);
				b.set(purge.acquire(target, true));
			}
			catch (Throwable ex) {
				e2.set(ex);
			}
		});
		t1.start();
		t2.start();
		boolean threadsStopped = true;
		try {
			ThreadGuard.joinOrFail(t1, 10_000L, "dual-executor-a", e1);
			ThreadGuard.joinOrFail(t2, 10_000L, "dual-executor-b", e2);
		}
		catch (RuntimeException ex) {
			threadsStopped = false;
			ctx.unsafeForCleanup = true;
			ctx.metrics.protocol(scenario.id(), "concurrent_writers_stopped", false,
					ex.getClass().getSimpleName() + ": " + ex.getMessage(), 0L);
			ctx.metrics.scenarioStatus(scenario.id(), EXIT_FAIL, "concurrent writer did not stop");
			System.err.println("dual_executor unsafe: " + ex.getMessage() + " (cleanup will be skipped)");
		}
		PurgeProtocol.Lease winner = a.get() != null && a.get().acquired() ? a.get() : b.get();
		PurgeProtocol.Lease loser = a.get() != null && a.get().acquired() ? b.get() : a.get();
		boolean oneWinner = threadsStopped && winner != null && winner.acquired() && loser != null
				&& !loser.acquired();
		long completedStart = System.nanoTime();
		PurgeProtocol.PurgeOutcome completed = threadsStopped && winner != null && winner.acquired()
				? purge.purgeWithLease(scenario, 0, winner,
						!ScenarioCatalog.expectedDeferred(scenario.id()), deadline) : null;
		long completedNanos = System.nanoTime() - completedStart;
		boolean pass = oneWinner && completed != null && completed.complete();
		ctx.metrics.protocol(scenario.id(), "single_flight_one_winner", pass,
				"winner=" + (winner == null ? "none" : winner.purgeId()) + " loserStatus="
						+ (loser == null ? "none" : loser.status()), 0L);
		ctx.metrics.scenarioStatus(scenario.id(), pass ? EXIT_OK : EXIT_FAIL,
				pass ? "one purge winner" : "single-flight failed");
		if (completed != null) {
			recordPurge(ctx, completed, 0, completedNanos);
			dataset.state = completed.complete() ? "purged" : "failed";
		}
	}

	private static void runTakeover(Ctx ctx, DatasetGenerator generator, PurgeProtocol purge,
			ScenarioCatalog.Scenario scenario, Deadline deadline) {
		Manifest.Dataset dataset = ensureCopy(ctx, generator, scenario, 0);
		if (!"generated".equals(dataset.state)) {
			dataset = regenerate(ctx, generator, scenario, 0);
		}
		long takeoverStart = System.nanoTime();
		PurgeProtocol.PurgeOutcome outcome = purge.leaseTakeover(scenario, 0, deadline);
		long takeoverNanos = System.nanoTime() - takeoverStart;
		boolean pass = outcome.complete() && outcome.deferred().isEmpty();
		ctx.metrics.protocol(scenario.id(), "stale_owner_rejected_after_takeover", pass,
				"complete=" + outcome.complete() + " epoch=" + outcome.epoch() + " errors="
						+ outcome.errors(), 0L);
		ctx.metrics.scenarioStatus(scenario.id(), pass ? EXIT_OK : EXIT_FAIL,
				pass ? "takeover completed" : "takeover failed");
		recordPurge(ctx, outcome, 0, takeoverNanos);
		dataset.state = outcome.complete() ? "purged"
				: ("DEFERRED".equals(outcome.pendingStage()) ? "deferred" : "failed");
	}

	private static void recordPurge(Ctx ctx, PurgeProtocol.PurgeOutcome outcome, int round, long nanos) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("scenario", outcome.scenario());
		row.put("copy", outcome.copy());
		row.put("round", round);
		row.put("sessionId", outcome.sessionId());
		row.put("complete", outcome.complete());
		row.put("found", outcome.found());
		row.put("deferred", outcome.deferred());
		row.put("errors", outcome.errors());
		row.put("purgeId", outcome.purgeId());
		row.put("epoch", outcome.epoch());
		row.put("scanZero", outcome.scanZero());
		row.put("deletedKeys", outcome.deletedKeys());
		row.put("guardBlockedWrite", outcome.guardBlockedWrite());
		row.put("pendingStage", outcome.pendingStage());
		row.put("durationNanos", nanos);
		row.put("commandsSinceProcessStart", outcome.commands());
		row.put("scanPagesSinceProcessStart", outcome.scanPages());
		row.put("pipelineBatchesSinceProcessStart", outcome.pipelineBatches());
		row.put("requestBytesSinceProcessStartEstimated", outcome.requestBytesEstimated());
		row.put("responseBytesSinceProcessStartEstimated", outcome.responseBytesEstimated());
		row.put("bytesEstimated", true);
		ctx.pendingPurgeRows.add(row);
	}

	private static int finish(Ctx ctx, List<ScenarioCatalog.Scenario> selected) {
		List<String> gates = ScenarioCatalog.gateScenarios(selected);
		List<String> allScenarios = selected.stream().map(ScenarioCatalog.Scenario::id).toList();
		java.util.Set<String> allowedDeferred = selected.stream()
				.filter(s -> ScenarioCatalog.expectedDeferred(s.id()))
				.map(ScenarioCatalog.Scenario::id)
				.collect(java.util.stream.Collectors.toSet());
		Map<String, GateEvaluator.Outcome> outcomes = new LinkedHashMap<>();
		for (String scenario : ctx.metrics.scenarios()) {
			outcomes.put(scenario, ctx.metrics.outcome(scenario));
		}
		GateEvaluator.Decision decision = GateEvaluator.evaluate(ctx.cfg.gates, outcomes, allScenarios, gates,
				allowedDeferred);
		ctx.gateDecision = decision;
		ctx.writeResults();
		System.out.println("gate: " + (decision.passed() ? "PASS" : "FAIL"));
		for (String failure : decision.failures()) {
			System.out.println("  FAIL " + failure);
		}
		for (String unknown : decision.unknowns()) {
			System.out.println("  UNKNOWN " + unknown);
		}
		if (!ctx.metrics.protocolsPassed()) {
			System.out.println("protocol: FAIL (see protocols.json)");
			return 1;
		}
		return decision.passed() ? 0 : 1;
	}

	private static int verify(Ctx ctx) {
		return verify(ctx, null);
	}

	private static int verify(Ctx ctx, java.util.Set<String> onlyScenarios) {
		ctx.cfg.validate(false);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("generatedAt", Instant.now().toString());
		List<Map<String, Object>> checks = new ArrayList<>();
		if (ctx.manifest.datasets.isEmpty()) {
			body.put("status", EXIT_UNKNOWN);
			body.put("detail", "manifest has no datasets; cannot prove a clean final state");
			ctx.writer.json("verify.json", body);
			System.err.println("verify: unknown (empty manifest)");
			return 2;
		}
		try (RedisHarness harness = RedisHarness.open(ctx.cfg)) {
			CountingRedis redis = harness.redis();
			for (Manifest.Dataset dataset : ctx.manifest.datasets) {
				if (onlyScenarios != null && !onlyScenarios.contains(dataset.scenario)) {
					continue;
				}
				Map<String, Object> check = verifyDataset(ctx, redis, dataset);
				checks.add(check);
			}
			body.put("checks", checks);
			boolean fail = checks.stream().anyMatch(c -> "fail".equals(c.get("status")));
			boolean unknown = checks.stream().anyMatch(c -> "unknown".equals(c.get("status")));
			body.put("status", fail ? EXIT_FAIL : (unknown ? EXIT_UNKNOWN : EXIT_OK));
			ctx.writer.json("verify.json", body);
			ctx.verify = body;
			writeVerifyCsv(ctx, checks);
			System.out.println("verify: " + body.get("status") + " checks=" + checks.size());
			return fail ? 1 : (unknown ? 2 : 0);
		}
	}

	private static Map<String, Object> verifyDataset(Ctx ctx, CountingRedis redis,
			Manifest.Dataset dataset) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("scenario", dataset.scenario);
		out.put("copy", dataset.copy);
		out.put("state", dataset.state);
		try {
			if ("generated".equals(dataset.state)) {
				out.put("status", "unknown");
				out.put("detail", "not measured");
				return out;
			}
			if ("cleaned".equals(dataset.state)) {
				out.put("status", EXIT_OK);
				out.put("detail", "cleaned by cleanup");
				return out;
			}
			if ("failed".equals(dataset.state) || "residue".equals(dataset.state)) {
				out.put("status", EXIT_UNKNOWN);
				out.put("detail", "previous phase left " + dataset.state);
				return out;
			}
			if ("protocol".equals(dataset.state)) {
				boolean protocolsPass = protocolsPassed(ctx);
				boolean keysPresent = protocolKeysPresent(ctx, redis, dataset);
				boolean pass = protocolsPass && keysPresent;
				out.put("protocolsPass", protocolsPass);
				out.put("keysPresent", keysPresent);
				out.put("status", pass ? EXIT_OK : EXIT_FAIL);
				out.put("detail", pass ? "protocol assertions and final keys present"
						: "protocol assertions or final keys missing");
				return out;
			}
			if ("deferred".equals(dataset.state)) {
				String pending = KeyShapes.purgePendingKey(ctx.cfg.tenantId, ctx.cfg.userId, dataset.sessionId);
				boolean pendingExists = redis.exists(pending);
				long ttl = redis.ttlSeconds(pending);
				String runKey = dataset.runIds.isEmpty() ? null
						: KeyShapes.runKey("ai:run:", ctx.cfg.tenantId, ctx.cfg.userId, dataset.runIds.get(0));
				boolean runExists = runKey != null && redis.exists(runKey);
				out.put("pendingExists", pendingExists);
				out.put("pendingTtl", ttl);
				out.put("runExists", runExists);
				List<String> missing = missingKeys(redis, dataset, ctx.cfg.batchSize);
				out.put("missingPlannedKeys", missing.size());
				out.put("missingSample", missing.stream().limit(5).toList());
				boolean pass = pendingExists && ttl == -1L && runExists && missing.isEmpty();
				out.put("status", pass ? EXIT_OK : EXIT_FAIL);
				out.put("detail", pass ? "deferred: all planned keys retained (no half delete)"
						: "deferred but pending/run/planned keys missing");
				return out;
			}
			List<String> existing = new ArrayList<>();
			List<String> retained = new ArrayList<>();
			List<String> keys = dataset.keys;
			for (List<String> batch : CountingRedis.batch(keys, ctx.cfg.batchSize)) {
				List<String[]> commands = new ArrayList<>();
				for (String key : batch) {
					commands.add(new String[] { "EXISTS", key });
				}
				List<Object> results = redis.pipelineRaw(commands);
				for (int i = 0; i < results.size(); i++) {
					long n = results.get(i) instanceof Number number ? number.longValue() : 0L;
					if (n > 0L) {
						String key = batch.get(i);
						if (allowedRetained(ctx, dataset, key)) {
							retained.add(key);
						}
						else {
							existing.add(key);
						}
					}
				}
			}
			out.put("residue", existing.size());
			out.put("retained", retained.size());
			out.put("residueSample", existing.stream().limit(5).toList());
			String guardKey = KeyShapes.purgeGuardKey(ctx.cfg.tenantId, ctx.cfg.userId, dataset.sessionId);
			String pendingKey = KeyShapes.purgePendingKey(ctx.cfg.tenantId, ctx.cfg.userId, dataset.sessionId);
			String genKey = KeyShapes.memoryGenKey(ctx.cfg.tenantId, ctx.cfg.userId, dataset.sessionId);
			String verKey = KeyShapes.memoryVerKey(ctx.cfg.tenantId, ctx.cfg.userId, dataset.sessionId);
			boolean guard = redis.exists(guardKey);
			long guardTtl = redis.ttlSeconds(guardKey);
			boolean pending = redis.exists(pendingKey);
			long pendingTtl = redis.ttlSeconds(pendingKey);
			boolean gen = redis.exists(genKey);
			long genTtl = redis.ttlSeconds(genKey);
			boolean ver = redis.exists(verKey);
			long verTtl = redis.ttlSeconds(verKey);
			String pendingRaw = redis.get(pendingKey);
			String pendingStage = null;
			if (pendingRaw != null) {
				try {
					Map<String, Object> pendingMap = Manifest.mapper().readValue(pendingRaw,
							new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
							});
					pendingStage = String.valueOf(pendingMap.get("stage"));
				}
				catch (Exception ignored) {
					pendingStage = "unparsable";
				}
			}
			out.put("guardExists", guard);
			out.put("guardTtl", guardTtl);
			out.put("pendingExists", pending);
			out.put("pendingTtl", pendingTtl);
			out.put("pendingStage", pendingStage);
			out.put("genExists", gen);
			out.put("genTtl", genTtl);
			out.put("verExists", ver);
			out.put("verTtl", verTtl);
			if (dataset.background > 0 && !"bg_existing_runs_1".equals(dataset.scenario)) {
				List<String> bg = Manifest.backgroundKeys(ctx.cfg.namespace, dataset);
				int present = 0;
				for (int index : sampleIndexes(bg.size())) {
					if (redis.exists(bg.get(index))) {
						present++;
					}
				}
				out.put("backgroundSamplePresent", present);
			}
			boolean pass = existing.isEmpty() && guard && guardTtl > 0L && pending
					&& pendingTtl == -1L && "COMPLETE".equals(pendingStage) && gen && genTtl > 0L
					&& ver && verTtl > 0L;
			out.put("status", pass ? EXIT_OK : EXIT_FAIL);
			out.put("detail", pass ? "all purge-scope keys removed; guard/pending/tombstone retained"
					: "unexpected residue or missing guard/pending/tombstone");
			return out;
		}
		catch (RuntimeException ex) {
			out.put("status", "unknown");
			out.put("detail", ex.getClass().getSimpleName() + ": " + ex.getMessage());
			return out;
		}
	}

	private static boolean allowedRetained(Ctx ctx, Manifest.Dataset dataset, String key) {
		String sid = dataset.sessionId;
		if (key.equals(KeyShapes.memoryGenKey(ctx.cfg.tenantId, ctx.cfg.userId, sid))
				|| key.equals(KeyShapes.memoryVerKey(ctx.cfg.tenantId, ctx.cfg.userId, sid))
				|| key.equals(KeyShapes.purgeGuardKey(ctx.cfg.tenantId, ctx.cfg.userId, sid))
				|| key.equals(KeyShapes.purgePendingKey(ctx.cfg.tenantId, ctx.cfg.userId, sid))) {
			return true;
		}
		if (key.startsWith("perf:bg:")) {
			return true;
		}
		if (key.equals(KeyShapes.backgroundMarkerKey(ctx.cfg.namespace))) {
			return true;
		}
		return false;
	}

	private static boolean protocolsPassed(Ctx ctx) {
		try {
			String raw = ctx.writer.read("protocols.json");
			if (raw == null || raw.isBlank()) {
				return false;
			}
			List<Map<String, Object>> rows = Manifest.mapper().readValue(raw,
					new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {
					});
			return rows.stream().allMatch(row -> Boolean.TRUE.equals(row.get("pass")));
		}
		catch (Exception ex) {
			return false;
		}
	}

	private static boolean protocolKeysPresent(Ctx ctx, CountingRedis redis, Manifest.Dataset dataset) {
		String runId = dataset.runIds.isEmpty() ? null : dataset.runIds.get(0);
		if (runId == null) {
			return false;
		}
		if ("b_frame_fence".equals(dataset.scenario) || "b_stream_gap".equals(dataset.scenario)) {
			return redis.exists(KeyShapes.xterminalKey(ctx.cfg.tenantId, ctx.cfg.userId, runId))
					|| redis.xlen(KeyShapes.runKey("ai:xevents:", ctx.cfg.tenantId, ctx.cfg.userId, runId)) > 0;
		}
		if ("b_decision_atomic".equals(dataset.scenario)) {
			return redis.hexists(KeyShapes.runKey("ai:decided:", ctx.cfg.tenantId, ctx.cfg.userId, runId),
					"call-" + runId);
		}
		if ("b_claim_cancel".equals(dataset.scenario)) {
			return redis.hexists(KeyShapes.runKey("ai:claim:", ctx.cfg.tenantId, ctx.cfg.userId, runId),
					"call-" + runId);
		}
		return true;
	}

	private static List<String> presentKeys(CountingRedis redis, List<String> keys, int batchSize) {
		List<String> present = new ArrayList<>();
		for (List<String> batch : CountingRedis.batch(keys, batchSize)) {
			List<String[]> commands = new ArrayList<>();
			for (String key : batch) {
				commands.add(new String[] { "EXISTS", key });
			}
			List<Object> results = redis.pipelineRaw(commands);
			for (int i = 0; i < results.size(); i++) {
				long n = results.get(i) instanceof Number number ? number.longValue() : 0L;
				if (n > 0L) {
					present.add(batch.get(i));
				}
			}
		}
		return present;
	}

	private static List<String> missingKeys(CountingRedis redis, Manifest.Dataset dataset, int batchSize) {
		List<String> missing = new ArrayList<>();
		for (List<String> batch : CountingRedis.batch(dataset.keys, batchSize)) {
			List<String[]> commands = new ArrayList<>();
			for (String key : batch) {
				commands.add(new String[] { "EXISTS", key });
			}
			List<Object> results = redis.pipelineRaw(commands);
			for (int i = 0; i < results.size(); i++) {
				long n = results.get(i) instanceof Number number ? number.longValue() : 0L;
				if (n == 0L) {
					missing.add(batch.get(i));
				}
			}
		}
		return missing;
	}

	private static int cleanup(Ctx ctx) {
		ctx.cfg.validate(true);
		if (!ctx.parsed.execute()) {
			System.out.println("[dry-run] cleanup 清单（加 --execute 才删除）：");
			for (Manifest.Dataset dataset : ctx.manifest.datasets) {
				int bg = "bg_existing_runs_1".equals(dataset.scenario) ? 0 : dataset.background;
				System.out.println("  " + dataset.scenario + "/" + dataset.copy + " keys="
						+ dataset.keys.size() + " bg=" + bg + " state=" + dataset.state);
			}
			return 0;
		}
		try (RedisHarness harness = RedisHarness.open(ctx.cfg)) {
			CountingRedis redis = harness.redis();
			boolean allClean = true;
			Map<String, Object> body = new LinkedHashMap<>();
			if (ctx.manifest.datasets.isEmpty()) {
				body.put("complete", false);
				body.put("reason", "manifest has no datasets; refusing to claim a clean cleanup");
				ctx.writer.json("cleanup.json", body);
				ctx.cleanup = body;
				System.err.println("cleanup: complete=false (empty manifest)");
				return 2;
			}
			List<Map<String, Object>> results = new ArrayList<>();
			for (Manifest.Dataset dataset : ctx.manifest.datasets) {
				long deleted = 0L;
				List<String> keys = new ArrayList<>(dataset.keys);
				keys.add(KeyShapes.purgeGuardKey(ctx.cfg.tenantId, ctx.cfg.userId, dataset.sessionId));
				keys.add(KeyShapes.purgePendingKey(ctx.cfg.tenantId, ctx.cfg.userId, dataset.sessionId));
				keys.add(KeyShapes.purgeLeaseKey(ctx.cfg.tenantId, ctx.cfg.userId, dataset.sessionId));
				keys.add(KeyShapes.memoryGenKey(ctx.cfg.tenantId, ctx.cfg.userId, dataset.sessionId));
				keys.add(KeyShapes.memoryVerKey(ctx.cfg.tenantId, ctx.cfg.userId, dataset.sessionId));
				if (!"bg_existing_runs_1".equals(dataset.scenario)) {
					keys.addAll(Manifest.backgroundKeys(ctx.cfg.namespace, dataset));
					if (dataset.background > 0) {
						keys.add(KeyShapes.backgroundMarkerKey(ctx.cfg.namespace));
					}
				}
				keys = new ArrayList<>(new java.util.LinkedHashSet<>(keys));
				for (List<String> batch : CountingRedis.batch(keys, ctx.cfg.batchSize)) {
					List<String[]> commands = new ArrayList<>();
					for (String key : batch) {
						commands.add(new String[] { "DEL", key });
					}
					List<Object> replies = redis.pipelineRaw(commands);
					for (Object reply : replies) {
						deleted += reply instanceof Number number ? number.longValue() : 0L;
					}
				}
				redis.zrem(KeyShapes.purgeDueKey(),
						KeyShapes.purgePendingKey(ctx.cfg.tenantId, ctx.cfg.userId, dataset.sessionId));
				List<String> checkKeys = new ArrayList<>(dataset.keys);
				checkKeys.add(KeyShapes.purgeGuardKey(ctx.cfg.tenantId, ctx.cfg.userId, dataset.sessionId));
				checkKeys.add(KeyShapes.purgePendingKey(ctx.cfg.tenantId, ctx.cfg.userId, dataset.sessionId));
				checkKeys.add(KeyShapes.purgeLeaseKey(ctx.cfg.tenantId, ctx.cfg.userId, dataset.sessionId));
				checkKeys.add(KeyShapes.memoryGenKey(ctx.cfg.tenantId, ctx.cfg.userId, dataset.sessionId));
				checkKeys.add(KeyShapes.memoryVerKey(ctx.cfg.tenantId, ctx.cfg.userId, dataset.sessionId));
				List<String> bg = Manifest.backgroundKeys(ctx.cfg.namespace, dataset);
				checkKeys.addAll(bg);
				checkKeys = new ArrayList<>(new java.util.LinkedHashSet<>(checkKeys));
				List<String> existing = presentKeys(redis, checkKeys, ctx.cfg.batchSize);
				boolean residue = !existing.isEmpty();
				allClean &= !residue;
				dataset.state = residue ? "residue" : "cleaned";
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("scenario", dataset.scenario);
				row.put("copy", dataset.copy);
				row.put("deleted", deleted);
				row.put("residueSample", existing.stream().limit(5).toList());
				row.put("residueCount", existing.size());
				results.add(row);
			}
			ctx.manifest.save(ctx.manifestPath);
			body.put("complete", allClean);
			body.put("datasets", results);
			body.put("safety", "no FLUSHDB/FLUSHALL/KEYS/SCAN; exact keys from manifest only");
			ctx.writer.json("cleanup.json", body);
			ctx.cleanup = body;
			System.out.println("cleanup: complete=" + allClean + " datasets=" + results.size());
			return allClean ? 0 : 1;
		}
	}

	private static int all(Ctx ctx) {
		int doctorCode = doctor(ctx);
		if (doctorCode != 0) {
			return doctorCode;
		}
		List<ScenarioCatalog.Scenario> selected = selected(ctx);
		ctx.cfg.validate(true);
		if (!ctx.parsed.execute()) {
			System.out.println("[dry-run] all 会写/删数据（加 --execute 才执行）");
			return 0;
		}
		int exit = 0;
		try (RedisHarness harness = RedisHarness.open(ctx.cfg)) {
			DatasetGenerator generator = new DatasetGenerator(ctx.cfg, harness.redis());
			Budget budget = new Budget(ctx.cfg.totalBudget, ctx.cfg.scenarioBudget, System::nanoTime);
			for (ScenarioCatalog.Scenario scenario : selected) {
				System.out.println("=== " + scenario.id() + " ===");
				generateScenario(ctx, generator, scenario);
				runMeasure(ctx, harness, List.of(scenario), budget);
				int verifyExit = verify(ctx, java.util.Set.of(scenario.id()));
				ctx.verifyExit = verifyExit;
				if (ctx.verify != null) {
					ctx.writer.json("verify-" + scenario.id() + ".json", ctx.verify);
				}
				exit = HarnessExit.combine(exit, verifyExit);
				if (ctx.unsafeForCleanup) {
					ctx.cleanupSkipped = true;
					System.err.println("cleanup skipped for safety: a concurrent writer did not stop");
					exit = 1;
					break;
				}
				else if (!ctx.parsed.keep()) {
					int cleanupExit = cleanup(ctx);
					ctx.cleanupExit = cleanupExit;
					if (ctx.cleanup != null) {
						ctx.writer.json("cleanup-" + scenario.id() + ".json", ctx.cleanup);
					}
					exit = HarnessExit.combine(exit, cleanupExit);
				}
				ctx.manifest.save(ctx.manifestPath);
			}
		}
		int gateExit = finish(ctx, selected);
		return HarnessExit.combine(exit, gateExit);
	}

	private static void writeVerifyCsv(Ctx ctx, List<Map<String, Object>> checks) {
		List<List<String>> rows = new ArrayList<>();
		for (Map<String, Object> check : checks) {
			rows.add(List.of(String.valueOf(check.get("scenario")), String.valueOf(check.get("copy")),
					String.valueOf(check.get("status")), String.valueOf(check.get("detail"))));
		}
		ctx.writer.csv("verify.csv", List.of("scenario", "copy", "status", "detail"), rows);
	}

	private static int[] sampleIndexes(int size) {
		if (size <= 0) {
			return new int[0];
		}
		if (size == 1) {
			return new int[] { 0 };
		}
		return new int[] { 0, size / 2, size - 1 };
	}

	private static void await(CyclicBarrier barrier) {
		try {
			barrier.await();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static void join(Thread thread) {
		try {
			thread.join(10_000L);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private static void printScenarios() {
		System.out.println("scenario\truns\tstaging\tarchives\tbg\tprotocol\tdescription");
		for (ScenarioCatalog.Scenario scenario : ScenarioCatalog.all()) {
			System.out.println(scenario.id() + "\t" + scenario.runs() + "\t" + scenario.staging() + "\t"
					+ scenario.archives() + "\t" + scenario.background() + "\t"
					+ scenario.protocol() + "\t" + scenario.description());
		}
	}

	private static void printHelp() {
		System.out.println("""
				Redis candidate protocol harness (backend test sources; no HTTP, no model, no service).

				Usage:
				  <command> [--execute] [--keep] [--scenario id]... [--profile smoke|full|stress] [--output dir]

				Commands:
				  scenarios   print the matrix (no connection)
				  doctor      read-only connectivity/topology/slot check
				  generate    write isolated datasets (dry-run without --execute)
				  measure     run A performance + B protocol scenarios (needs --execute)
				  verify      read-only final key/state assertions
				  cleanup     exact-key cleanup (dry-run without --execute)
				  all         doctor + per-scenario generate/measure/verify/cleanup

				Safety:
				  PERF_ISOLATED_ENV=true is mandatory for writes.
				  Remote Redis needs PERF_CONFIRM_REMOTE=true; cluster db must be 0.
				  No FLUSHDB/FLUSHALL/KEYS/DEBUG/CONFIG; no unbounded SCAN.
				""");
	}

	private record Args(String command, boolean execute, boolean keep, boolean help,
			List<String> scenarios, String profile, String output) {

		private static Args parse(String[] args) {
			if (args.length == 0) {
				return new Args(null, false, false, true, List.of(), null, null);
			}
			String command = args[0];
			boolean execute = false;
			boolean keep = false;
			boolean help = "--help".equals(command) || "-h".equals(command);
			List<String> scenarios = new ArrayList<>();
			String profile = null;
			String output = null;
			for (int i = 1; i < args.length; i++) {
				switch (args[i]) {
					case "--execute" -> execute = true;
					case "--keep" -> keep = true;
					case "--help", "-h" -> help = true;
					case "--scenario" -> {
						if (++i >= args.length) {
							throw new IllegalArgumentException("--scenario 需要值");
						}
						scenarios.add(args[i]);
					}
					case "--profile" -> {
						if (++i >= args.length) {
							throw new IllegalArgumentException("--profile 需要值");
						}
						profile = args[i];
					}
					case "--output" -> {
						if (++i >= args.length) {
							throw new IllegalArgumentException("--output 需要值");
						}
						output = args[i];
					}
					default -> throw new IllegalArgumentException("未知参数：" + args[i]);
				}
			}
			return new Args(command, execute, keep, help, List.copyOf(scenarios), profile, output);
		}

	}

	private static final class Ctx {

		private final HarnessConfig cfg;

		private final OutputWriter writer;

		private final Manifest manifest;

		private final Path manifestPath;

		private final Args parsed;

		private final HarnessMetrics metrics = new HarnessMetrics();

		private final List<Map<String, Object>> purges = new ArrayList<>();

		private final List<Map<String, Object>> pendingPurgeRows = new ArrayList<>();

		private GateEvaluator.Decision gateDecision;

		private Map<String, Object> verify;

		private Map<String, Object> cleanup;

		private boolean budgetExhausted;

		private int verifyExit;

		private int cleanupExit;

		private boolean cleanupSkipped;

		private boolean unsafeForCleanup;

		private Ctx(HarnessConfig cfg, OutputWriter writer, Manifest manifest, Path manifestPath,
				Args parsed) {
			this.cfg = parsed.profile() == null ? cfg : withProfile(cfg, parsed.profile());
			this.writer = writer;
			this.manifest = manifest;
			this.manifestPath = manifestPath;
			this.parsed = parsed;
		}

		private static HarnessConfig withProfile(HarnessConfig cfg, String profile) {
			Map<String, String> env = new LinkedHashMap<>();
			env.put("REDIS_MODE", cfg.mode.name().toLowerCase());
			env.put("REDIS_HOST", cfg.host);
			env.put("REDIS_PORT", String.valueOf(cfg.port));
			env.put("REDIS_DB", String.valueOf(cfg.db));
			env.put("REDIS_CLUSTER_NODES", String.join(",", cfg.clusterNodes));
			env.put("REDIS_USERNAME", cfg.username == null ? "" : cfg.username);
			env.put("REDIS_PASSWORD", cfg.password == null ? "" : cfg.password);
			env.put("REDIS_TLS", String.valueOf(cfg.tls));
			env.put("REDIS_TLS_INSECURE", String.valueOf(cfg.tlsInsecure));
			env.put("REDIS_COMMAND_TIMEOUT_MS", String.valueOf(cfg.commandTimeout.toMillis()));
			env.put("REDIS_CONNECT_TIMEOUT_MS", String.valueOf(cfg.connectTimeout.toMillis()));
			env.put("PERF_NAMESPACE", cfg.namespace);
			env.put("PERF_OUTPUT_DIR", cfg.outputDir.toString());
			env.put("PERF_PROFILE", profile);
			env.put("PERF_SCENARIOS", String.join(",", cfg.scenarios));
			env.put("PERF_REPEAT", String.valueOf(cfg.repeat));
			env.put("PERF_WARMUP", String.valueOf(cfg.warmup));
			env.put("PERF_BATCH_SIZE", String.valueOf(cfg.batchSize));
			env.put("PERF_STREAM_PAGE_SIZE", String.valueOf(cfg.streamPageSize));
			env.put("PERF_STREAM_MAX_ENTRIES", String.valueOf(cfg.streamMaxEntries));
			env.put("PERF_SCENARIO_TIMEOUT_SECONDS", String.valueOf(cfg.scenarioBudget.toSeconds()));
			env.put("PERF_TOTAL_TIMEOUT_SECONDS", String.valueOf(cfg.totalBudget.toSeconds()));
			env.put("PERF_KEY_TTL_SECONDS", String.valueOf(cfg.keyTtlSeconds));
			env.put("PERF_TENANT_ID", String.valueOf(cfg.tenantId));
			env.put("PERF_USER_ID", String.valueOf(cfg.userId));
			env.put("PERF_ISOLATED_ENV", String.valueOf(cfg.isolatedEnv));
			env.put("PERF_CONFIRM_REMOTE", String.valueOf(cfg.confirmRemote));
			env.put("PERF_CONFIRM_NON_DEFAULT_DB", String.valueOf(cfg.confirmNonDefaultDb));
			env.put("PERF_ALLOW_LARGE_SCALE", String.valueOf(cfg.allowLargeScale));
			env.put("PERF_ALLOW_BACKGROUND_100K", String.valueOf(cfg.allowBackground100k));
			env.put("PERF_EXISTING_BG_SCALE", String.valueOf(cfg.existingBackgroundScale));
			env.put("PERF_LEASE_TTL_MS", String.valueOf(cfg.leaseTtlMs));
			env.put("PERF_FINALIZE", String.valueOf(cfg.finalizeAllowed));
			env.put("PERF_GATE_EMPTY_P95_MS", String.valueOf(cfg.gates.emptyP95Ms()));
			env.put("PERF_GATE_RUNS50_P95_MS", String.valueOf(cfg.gates.runs50P95Ms()));
			env.put("PERF_GATE_ARCHIVE2000_P95_MS", String.valueOf(cfg.gates.archive2000P95Ms()));
			env.put("PERF_GATE_MAX_SCAN_PAGES", String.valueOf(cfg.gates.maxScanPages()));
			env.put("PERF_GATE_MAX_BG_COMMAND_GROWTH", String.valueOf(cfg.gates.maxBackgroundCommandGrowth()));
			return new HarnessConfig(env);
		}

		private void writeResults() {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("generatedAt", Instant.now().toString());
			body.put("config", this.cfg.redactedSummary());
			body.put("summaries", this.metrics.allSummaries());
			body.put("stages", this.metrics.stages());
			body.put("protocols", this.metrics.protocols());
			body.put("purges", this.purges);
			body.put("gate", this.gateDecision == null ? Map.of()
					: Map.of("passed", this.gateDecision.passed(), "failures", this.gateDecision.failures(),
							"unknowns", this.gateDecision.unknowns()));
			body.put("verify", this.verify);
			body.put("cleanup", this.cleanup);
			body.put("verifyExit", this.verifyExit);
			body.put("cleanupExit", this.cleanupExit);
			body.put("cleanupSkipped", this.cleanupSkipped);
			body.put("unsafeForCleanup", this.unsafeForCleanup);
			body.put("budget", Map.of("totalExhausted", this.budgetExhausted));
			this.writer.json("results.json", body);
			List<List<String>> summaryRows = new ArrayList<>();
			for (Map<String, Object> summary : this.metrics.allSummaries()) {
				summaryRows.add(summary.values().stream().map(String::valueOf).toList());
			}
			this.writer.csv("summary.csv", List.of("scenario", "status", "detail", "count", "minMs", "meanMs",
					"p50Ms", "p95Ms", "p99Ms", "maxMs", "commands", "pipelineBatches", "scanPages",
					"slotChecks", "requestBytesEstimated", "responseBytesEstimated", "bytesEstimated"),
					summaryRows);
			List<List<String>> requestRows = new ArrayList<>();
			for (HarnessMetrics.StageRecord stage : this.metrics.stages()) {
				requestRows.add(List.of(stage.scenario(), String.valueOf(stage.round()), stage.stage(),
						String.valueOf(stage.durationNanos()), String.valueOf(stage.commands()),
						String.valueOf(stage.pipelineBatches()), String.valueOf(stage.scanPages()),
						String.valueOf(stage.slotChecks()), String.valueOf(stage.requestBytes()),
						String.valueOf(stage.responseBytes()), String.valueOf(stage.bytesEstimated()),
						stage.status(), stage.detail() == null ? "" : stage.detail()));
			}
			this.writer.csv("requests.csv", List.of("scenario", "round", "stage", "durationNanos",
					"commands", "pipelineBatches", "scanPages", "slotChecks", "requestBytes",
					"responseBytes", "bytesEstimated", "status", "detail"), requestRows);
			this.writer.json("protocols.json", this.metrics.protocols());
		}

	}

}
