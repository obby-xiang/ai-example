package com.example.configmgr.perf.redis;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 候选删除协议：精确索引枚举（SCAN=0）→ 会话级 lease/epoch 单飞 → 终态收敛/收尾 →
 * 同槽 run 删除 Lua + staging 同槽批量 Lua + archive 单键 TYPE+DEL pipeline →
 * durable pending / guard / tombstone。
 */
public final class PurgeProtocol {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final HarnessConfig cfg;

	private final CountingRedis redis;

	private final HarnessMetrics metrics;

	private final Manifest manifest;

	private final String owner;

	public PurgeProtocol(HarnessConfig cfg, CountingRedis redis, HarnessMetrics metrics, Manifest manifest,
			String owner) {
		this.cfg = cfg;
		this.redis = redis;
		this.metrics = metrics;
		this.manifest = manifest;
		this.owner = owner;
	}

	public record Lease(boolean acquired, long epoch, String purgeId, String status) {
	}

	public record PurgeOutcome(String scenario, int copy, String sessionId, boolean complete, boolean found,
			List<String> deferred, List<String> errors, String purgeId, long epoch, boolean scanZero,
			long deletedKeys, boolean guardBlockedWrite, String pendingStage, int commands, int scanPages,
			int pipelineBatches, long requestBytesEstimated, long responseBytesEstimated) {
	}

	/** 正常删除；{@code failOnBusy=true} 时并发败者立即返回 BUSY（dual executor 用）。 */
	public PurgeOutcome purge(ScenarioCatalog.Scenario scenario, int copy, boolean warmup,
			boolean failOnBusy, Deadline deadline) {
		return purge(scenario, copy, warmup, failOnBusy, this.cfg.finalizeAllowed, deadline);
	}

	/** 场景级收尾开关：active_unfinalizable 必须传 false，不能靠全局默认掩盖。 */
	public PurgeOutcome purge(ScenarioCatalog.Scenario scenario, int copy, boolean warmup,
			boolean failOnBusy, boolean allowFinalize, Deadline deadline) {
		Manifest.Dataset dataset = this.manifest.require(scenario.id(), copy);
		List<String> errors = new ArrayList<>();
		List<String> deferred = new ArrayList<>();
		try {
			Enumeration enumeration = this.metrics.stage(this.redis, scenario.id(), copy, "enumerate",
					() -> enumerate(dataset));
			deadline.check("acquire");
			Lease lease = this.metrics.stage(this.redis, scenario.id(), copy, "acquire",
					() -> acquire(dataset, failOnBusy));
			if (!lease.acquired()) {
				return outcome(scenario.id(), copy, dataset.sessionId, false, enumeration.found(), deferred,
						List.of("BUSY:" + lease.status()), lease, 0L, false, "BUSY");
			}

			deadline.check("stage");
			this.metrics.stage(this.redis, scenario.id(), copy, "lease-check", () -> {
				requireAllowed(lease, "BEGIN", 0L);
				return Boolean.TRUE;
			});

			deadline.check("finalize");
			this.metrics.stage(this.redis, scenario.id(), copy, "finalize", () -> {
				finalizeRuns(lease, enumeration, deferred, allowFinalize);
				return Boolean.TRUE;
			});
			if (!deferred.isEmpty()) {
				String pending = pendingJson(lease, "DEFERRED", 1, 0L, "deferred", deferred);
				Boolean deferWritten = this.metrics.stage(this.redis, scenario.id(), copy, "defer",
						() -> defer(lease, pending));
				PurgeCompletionPolicy.Decision deferredDecision = PurgeCompletionPolicy.decide(false,
						Boolean.TRUE.equals(deferWritten), errors, deferred);
				dataset.state = datasetState(deferredDecision);
				return outcome(scenario.id(), copy, dataset.sessionId, deferredDecision.complete(),
						enumeration.found(), deferred, deferredDecision.errors(), lease, 0L, false,
						deferredDecision.state().name());
			}

			long[] deleted = new long[1];
			deadline.check("delete-runs");
			deleted[0] += this.metrics.stage(this.redis, scenario.id(), copy, "delete-runs",
					() -> deleteRuns(lease, dataset, enumeration));
			deadline.check("delete-staging");
			deleted[0] += this.metrics.stage(this.redis, scenario.id(), copy, "delete-staging",
					() -> deleteStaging(lease, enumeration));
			deadline.check("delete-archives");
			deleted[0] += this.metrics.stage(this.redis, scenario.id(), copy, "delete-archives",
					() -> deleteArchives(lease, enumeration, errors));
			deadline.check("delete-session");
			deleted[0] += this.metrics.stage(this.redis, scenario.id(), copy, "delete-session",
					() -> deleteSessionKeys(lease, dataset));

			boolean guardBlocked = this.metrics.stage(this.redis, scenario.id(), copy, "guarded-write",
					() -> guardBlocksWrite(dataset));
			if (!guardBlocked) {
				errors.add("guard did not block write-back");
			}
			PurgeLifecyclePolicy.WriteKind writeKind = PurgeLifecyclePolicy.writeKind(
					!errors.isEmpty(), false, guardBlocked);
			if (writeKind == PurgeLifecyclePolicy.WriteKind.FAILED_RETRYABLE) {
				String failPending = pendingJson(lease, "FAILED_RETRYABLE", 1, 0L, "failure", List.of());
				Boolean failWritten = this.metrics.stage(this.redis, scenario.id(), copy, "fail-retryable",
						() -> failRetryable(lease, failPending));
				if (!Boolean.TRUE.equals(failWritten)) {
					errors.add("failure state write rejected");
				}
				PurgeCompletionPolicy.Decision failed = PurgeCompletionPolicy.decide(false, false,
						errors, List.of());
				dataset.state = datasetState(failed);
				return outcome(scenario.id(), copy, dataset.sessionId, false, enumeration.found(), deferred,
						failed.errors(), lease, deleted[0], guardBlocked, "FAILED_RETRYABLE");
			}

			deadline.check("due");
			try {
				this.redis.zadd(KeyShapes.purgeDueKey(),
						KeyShapes.purgePendingKey(this.cfg.tenantId, this.cfg.userId, dataset.sessionId),
						System.currentTimeMillis() + 60_000L);
			}
			catch (RuntimeException ex) {
				errors.add("due zadd failed: " + ex.getMessage());
			}
			if (!errors.isEmpty()) {
				String failPending = pendingJson(lease, "FAILED_RETRYABLE", 1, 0L, "failure", List.of());
				Boolean failWritten = this.metrics.stage(this.redis, scenario.id(), copy, "fail-retryable",
						() -> failRetryable(lease, failPending));
				if (!Boolean.TRUE.equals(failWritten)) {
					errors.add("failure state write rejected");
				}
				PurgeCompletionPolicy.Decision failed = PurgeCompletionPolicy.decide(false, false,
						errors, List.of());
				dataset.state = datasetState(failed);
				return outcome(scenario.id(), copy, dataset.sessionId, false, enumeration.found(), deferred,
						failed.errors(), lease, deleted[0], guardBlocked, "FAILED_RETRYABLE");
			}
			deadline.check("complete");
			String pending = pendingJson(lease, "COMPLETE", 1,
					System.currentTimeMillis() + 60_000L, "complete", List.of());
			Boolean completed = this.metrics.stage(this.redis, scenario.id(), copy, "complete",
					() -> complete(lease, pending));
			if (!Boolean.TRUE.equals(completed)) {
				errors.add("complete rejected (lease lost?)");
			}
			PurgeCompletionPolicy.Decision finalDecision = PurgeCompletionPolicy.decide(
					Boolean.TRUE.equals(completed), false, errors, List.of());
			dataset.state = datasetState(finalDecision);
			return outcome(scenario.id(), copy, dataset.sessionId, finalDecision.complete(),
					enumeration.found(), deferred, finalDecision.errors(), lease, deleted[0], guardBlocked,
					finalDecision.state().name());
		}
		catch (BudgetExceededException ex) {
			errors.add("budget_exhausted:" + ex.phase());
			throw ex;
		}
		catch (RuntimeException ex) {
			errors.add(ex.getClass().getSimpleName() + ": " + ex.getMessage());
			dataset.state = "failed";
			return outcome(scenario.id(), copy, dataset.sessionId, false, true, deferred, errors,
					new Lease(false, 0L, "unknown", "ERROR"), 0L, false, "ERROR");
		}
	}

	private PurgeOutcome outcome(String scenario, int copy, String sessionId, boolean complete, boolean found,
			List<String> deferred, List<String> errors, Lease lease, long deletedKeys, boolean guardBlocked,
			String pendingStage) {
		CountingRedis.Snapshot snapshot = this.redis.snapshot();
		return new PurgeOutcome(scenario, copy, sessionId, complete, found, List.copyOf(deferred),
				List.copyOf(errors), lease.purgeId(), lease.epoch(), this.redis.scanPages() == 0L,
				deletedKeys, guardBlocked, pendingStage, (int) snapshot.commands(), (int) snapshot.scanPages(),
				(int) snapshot.pipelineBatches(), snapshot.requestBytes(), snapshot.responseBytes());
	}

	public Lease acquire(Manifest.Dataset dataset, boolean failOnBusy) {
		String purgeId = "pg-" + dataset.sessionId + "-" + Long.toHexString(System.nanoTime());
		String leaseKey = KeyShapes.purgeLeaseKey(this.cfg.tenantId, this.cfg.userId, dataset.sessionId);
		String pendingKey = KeyShapes.purgePendingKey(this.cfg.tenantId, this.cfg.userId, dataset.sessionId);
		String guardKey = KeyShapes.purgeGuardKey(this.cfg.tenantId, this.cfg.userId, dataset.sessionId);
		this.redis.checkSameSlot(List.of(leaseKey, pendingKey, guardKey));
		List<?> result = this.redis.eval(Scripts.PURGE_ACQUIRE, List.of(leaseKey, pendingKey, guardKey),
				this.owner, purgeId, String.valueOf(this.cfg.leaseTtlMs),
				String.valueOf(this.cfg.keyTtlSeconds * 1_000L), String.valueOf(System.currentTimeMillis()),
				dataset.sessionId, "1");
		boolean acquired = number(result, 0) == 1L;
		long epoch = number(result, 1);
		if (!acquired && failOnBusy) {
			return new Lease(false, epoch, purgeId, text(result, 2));
		}
		if (!acquired) {
			// 正常场景：短暂等待租约自然过期/上一轮完成，最多 3 秒；仍 BUSY 则如实返回。
			long deadline = System.currentTimeMillis() + 3_000L;
			while (!acquired && System.currentTimeMillis() < deadline) {
				sleep(50L);
				result = this.redis.eval(Scripts.PURGE_ACQUIRE, List.of(leaseKey, pendingKey, guardKey),
						this.owner, purgeId, String.valueOf(this.cfg.leaseTtlMs),
						String.valueOf(this.cfg.keyTtlSeconds * 1_000L),
						String.valueOf(System.currentTimeMillis()), dataset.sessionId, "1");
				acquired = number(result, 0) == 1L;
				epoch = number(result, 1);
			}
		}
		return new Lease(acquired, epoch, purgeId, text(result, 2));
	}

	private Enumeration enumerate(Manifest.Dataset dataset) {
		// 4 个索引键不同槽：同一条有界 pipeline 发出，只付 1 个网络往返（不跨槽 DEL/Lua）。
		List<String[]> commands = List.of(
				new String[] { "EXISTS",
						KeyShapes.sessionKey(this.cfg.tenantId, this.cfg.userId, dataset.sessionId) },
				new String[] { "SMEMBERS",
						KeyShapes.sessionRunsKey(this.cfg.tenantId, this.cfg.userId, dataset.sessionId) },
				new String[] { "SMEMBERS",
						KeyShapes.stagingIndexKey(this.cfg.tenantId, this.cfg.userId, dataset.sessionId) },
				new String[] { "SMEMBERS",
						KeyShapes.archiveIndexKey(this.cfg.tenantId, this.cfg.userId, dataset.sessionId) });
		List<Object> results = this.redis.pipelineRaw(commands);
		boolean found = asLong(value(results, 0)) > 0L;
		return new Enumeration(found, strings(results, 1), strings(results, 2), strings(results, 3),
				dataset.sessionId);
	}

	private void finalizeRuns(Lease lease, Enumeration enumeration, List<String> deferred,
			boolean allowFinalize) {
		List<String> runs = enumeration.runs();
		if (runs.isEmpty()) {
			return;
		}
		// runstate 一次有界 pipeline 取齐；只有缺 runstate 的 run 才再批量读快照，避免逐个 run 串行 GET。
		List<String> stateKeys = new ArrayList<>(runs.size());
		for (String runId : runs) {
			stateKeys.add(KeyShapes.runKey("ai:runstate:", this.cfg.tenantId, this.cfg.userId, runId));
		}
		List<String> states = batchGet(stateKeys);
		List<String> snapshotRuns = new ArrayList<>();
		List<String> snapshotKeys = new ArrayList<>();
		Map<String, String> snapshotByRun = new LinkedHashMap<>();
		for (int i = 0; i < runs.size(); i++) {
			if (states.get(i) == null) {
				String runId = runs.get(i);
				snapshotRuns.add(runId);
				snapshotKeys.add(KeyShapes.runKey("ai:run:", this.cfg.tenantId, this.cfg.userId, runId));
			}
		}
		List<String> snapshots = batchGet(snapshotKeys);
		for (int i = 0; i < snapshotRuns.size(); i++) {
			snapshotByRun.put(snapshotRuns.get(i), snapshots.get(i));
		}
		for (int i = 0; i < runs.size(); i++) {
			String runId = runs.get(i);
			String status = states.get(i);
			if (status == null) {
				status = snapshotStatus(snapshotByRun.get(runId));
			}
			if (status == null) {
				continue;
			}
			if (RunStateMachine.isTerminalStatus(status)) {
				continue;
			}
			TerminalFact terminal = terminalFact(runId);
			if (terminal.unknown()) {
				deferred.add(runId);
				continue;
			}
			if (terminal.json() != null) {
				if (!converge(runId, status, terminal.json())) {
					deferred.add(runId);
				}
				continue;
			}
			if (!allowFinalize) {
				deferred.add(runId);
				continue;
			}
			long appended = appendTerminalFrame(runId, lease.epoch(), lease.purgeId());
			if (appended <= 0L) {
				deferred.add(runId);
				continue;
			}
			if (!converge(runId, status, "{\"seq\":" + appended + ",\"type\":\"done\",\"atMs\":"
					+ System.currentTimeMillis() + ",\"source\":\"purge-finalize\"}")) {
				deferred.add(runId);
			}
		}
	}

	/** 有界 GET pipeline；结果与 keys 一一对应，缺失键为 null。 */
	private List<String> batchGet(List<String> keys) {
		List<String> out = new ArrayList<>(keys.size());
		for (List<String> batch : CountingRedis.batch(keys, this.cfg.batchSize)) {
			List<String[]> commands = new ArrayList<>(batch.size());
			for (String key : batch) {
				commands.add(new String[] { "GET", key });
			}
			List<Object> results = this.redis.pipelineRaw(commands);
			for (int i = 0; i < batch.size(); i++) {
				out.add(text(value(results, i)));
			}
		}
		return out;
	}

	private String snapshotStatus(String raw) {
		if (raw == null) {
			return null;
		}
		try {
			Map<String, Object> snapshot = MAPPER.readValue(raw,
					new TypeReference<Map<String, Object>>() {
					});
			Object status = snapshot.get("status");
			return status == null ? null : String.valueOf(status);
		}
		catch (Exception ex) {
			return null;
		}
	}

	private record TerminalFact(String json, boolean unknown) {
	}

	private TerminalFact terminalFact(String runId) {
		String xterminal = this.redis.get(KeyShapes.xterminalKey(this.cfg.tenantId, this.cfg.userId, runId));
		if (xterminal != null) {
			return new TerminalFact(xterminal, false);
		}
		StreamPager.Result scan = scanRunStream(runId);
		if (scan.terminalFound()) {
			for (int i = scan.entries().size() - 1; i >= 0; i--) {
				String frame = scan.entries().get(i).fields().get("frame");
				if (StreamPager.isTerminal(frame)) {
					return new TerminalFact(frame, false);
				}
			}
		}
		if (scan.terminalUnknown()) {
			return new TerminalFact(null, true);
		}
		return new TerminalFact(null, false);
	}

	public StreamPager.Result scanRunStream(String runId) {
		String streamKey = KeyShapes.runKey("ai:xevents:", this.cfg.tenantId, this.cfg.userId, runId);
		return RedisStreams.scan(this.redis, streamKey, this.cfg.streamPageSize,
				this.cfg.streamMaxEntries);
	}

	private long appendTerminalFrame(String runId, long epoch, String purgeId) {
		String seqKey = KeyShapes.runKey("ai:seq:", this.cfg.tenantId, this.cfg.userId, runId);
		String streamKey = KeyShapes.runKey("ai:xevents:", this.cfg.tenantId, this.cfg.userId, runId);
		String ackKey = KeyShapes.frameAckKey(this.cfg.tenantId, this.cfg.userId, runId,
				"purge-" + purgeId);
		String trimKey = KeyShapes.xtrimKey(this.cfg.tenantId, this.cfg.userId, runId);
		String fenceKey = KeyShapes.runKey("ai:fence:run:", this.cfg.tenantId, this.cfg.userId, runId);
		String xterminal = KeyShapes.xterminalKey(this.cfg.tenantId, this.cfg.userId, runId);
		List<String> keys = List.of(seqKey, streamKey, ackKey, trimKey, fenceKey, xterminal);
		this.redis.checkSameSlot(keys);
		List<?> result = this.redis.eval(Scripts.FRAME_APPEND, keys, String.valueOf(epoch),
				String.valueOf(this.cfg.keyTtlSeconds * 1_000L), "6000", "120", "purge",
				"{\"type\":\"done\",\"seq\":", "}", "0", "");
		long seq = number(result, 0);
		if (seq > 0) {
			this.manifest.addKeyForRun(runId, ackKey);
			this.manifest.addKeyForRun(runId, trimKey);
		}
		return seq > 0 ? seq : 0L;
	}

	private boolean converge(String runId, String expectedStatus, String terminalJson) {
		String terminalStatus = RunStateMachine.terminalStatus(terminalJson);
		if (terminalStatus == null) {
			return false;
		}
		String runKey = KeyShapes.runKey("ai:run:", this.cfg.tenantId, this.cfg.userId, runId);
		String raw = this.redis.get(runKey);
		if (raw == null) {
			return false;
		}
		Map<String, Object> snapshot;
		try {
			snapshot = MAPPER.readValue(raw, new TypeReference<Map<String, Object>>() {
			});
		}
		catch (Exception ex) {
			return false;
		}
		snapshot.put("status", terminalStatus);
		snapshot.put("cancelled", "CANCELLED".equals(terminalStatus));
		String newJson;
		try {
			newJson = MAPPER.writeValueAsString(snapshot);
		}
		catch (Exception ex) {
			return false;
		}
		List<String> keys = List.of(runKey,
				KeyShapes.runKey("ai:runstate:", this.cfg.tenantId, this.cfg.userId, runId),
				KeyShapes.xterminalKey(this.cfg.tenantId, this.cfg.userId, runId));
		this.redis.checkSameSlot(keys);
		Long applied = this.redis.eval(Scripts.RUN_CONVERGE, keys, expectedStatus, newJson,
				terminalStatus, terminalJson, String.valueOf(this.cfg.keyTtlSeconds * 1_000L));
		return applied != null && applied == 1L;
	}

	private void requireAllowed(Lease lease, String stage, long verifyAtMs) {
		String pendingJson = pendingJson(lease, stage, 1, verifyAtMs, stage.toLowerCase(), List.of());
		List<String> keys = List.of(
				KeyShapes.purgeLeaseKey(this.cfg.tenantId, this.cfg.userId, sessionId(lease)),
				KeyShapes.purgePendingKey(this.cfg.tenantId, this.cfg.userId, sessionId(lease)));
		this.redis.checkSameSlot(keys);
		List<?> result = this.redis.eval(Scripts.PURGE_STAGE, keys,
				this.owner, String.valueOf(lease.epoch()), lease.purgeId(), pendingJson,
				String.valueOf(this.cfg.leaseTtlMs), String.valueOf(System.currentTimeMillis()));
		if (number(result, 0) != 1L) {
			throw new IllegalStateException("lease check failed stage=" + stage + " reason=" + text(result, 1));
		}
	}

	private String sessionId(Lease lease) {
		String prefix = "pg-";
		String tail = lease.purgeId().substring(prefix.length());
		int idx = tail.lastIndexOf('-');
		return idx > 0 ? tail.substring(0, idx) : tail;
	}

	private long deleteRuns(Lease lease, Manifest.Dataset dataset, Enumeration enumeration) {
		if (enumeration.runs().isEmpty()) {
			return 0L;
		}
		requireAllowed(lease, "DELETE_RUNS", 0L);
		List<List<String>> keysPerCall = new ArrayList<>();
		for (String runId : enumeration.runs()) {
			List<String> keys = new ArrayList<>(KeyShapes.runDeleteKeys(this.cfg.tenantId, this.cfg.userId,
					runId));
			keys.add(KeyShapes.xtrimKey(this.cfg.tenantId, this.cfg.userId, runId));
			keys.add(KeyShapes.frameAckKey(this.cfg.tenantId, this.cfg.userId, runId,
					"purge-" + lease.purgeId()));
			this.redis.checkSameSlot(keys);
			keysPerCall.add(keys);
			this.manifest.addKey(dataset.scenario, dataset.copy,
					KeyShapes.xtrimKey(this.cfg.tenantId, this.cfg.userId, runId));
			this.manifest.addKey(dataset.scenario, dataset.copy,
					KeyShapes.frameAckKey(this.cfg.tenantId, this.cfg.userId, runId,
							"purge-" + lease.purgeId()));
		}
		long deleted = 0L;
		for (List<List<String>> batch : CountingRedis.batch(keysPerCall, this.cfg.batchSize)) {
			List<List<String>> args = new ArrayList<>();
			for (int i = 0; i < batch.size(); i++) {
				args.add(List.of());
			}
			List<Object> results = this.redis.pipelineEval(Scripts.RUN_DELETE, batch, args);
			for (Object value : results) {
				deleted += asLong(value);
			}
		}
		removeSessionIndexMembers(dataset, enumeration.runs());
		return deleted;
	}

	/**
	 * runs 索引是 SET（SADD 写入）用 SREM，active 索引是 ZSET（ZADD 写入）用 ZREM。
	 * 两键不同槽，按 batchSize 分批放进同一条有界 pipeline，避免逐 run 串行往返。
	 */
	private void removeSessionIndexMembers(Manifest.Dataset dataset, List<String> runIds) {
		String runsKey = KeyShapes.sessionRunsKey(this.cfg.tenantId, this.cfg.userId, dataset.sessionId);
		String activeKey = KeyShapes.sessionActiveKey(this.cfg.tenantId, this.cfg.userId, dataset.sessionId);
		for (List<String> batch : CountingRedis.batch(runIds, this.cfg.batchSize)) {
			this.redis.pipelineRaw(List.of(
					SessionIndexPolicy.remove(SessionIndexPolicy.Index.RUNS, runsKey, batch),
					SessionIndexPolicy.remove(SessionIndexPolicy.Index.ACTIVE, activeKey, batch)));
		}
	}

	private long deleteStaging(Lease lease, Enumeration enumeration) {
		if (enumeration.staging().isEmpty()) {
			return 0L;
		}
		requireAllowed(lease, "DELETE_STAGING", 0L);
		String index = KeyShapes.stagingIndexKey(this.cfg.tenantId, this.cfg.userId, sessionIdOf(enumeration));
		long deleted = 0L;
		for (List<String> tokens : CountingRedis.batch(enumeration.staging(), this.cfg.batchSize)) {
			List<String> keys = new ArrayList<>();
			keys.add(index);
			List<String> flatTokens = new ArrayList<>();
			for (String token : tokens) {
				flatTokens.add(token);
				keys.add(KeyShapes.stagingKey(this.cfg.tenantId, this.cfg.userId, sessionIdOf(enumeration),
						token));
			}
			this.redis.checkSameSlot(keys);
			Long result = this.redis.eval(Scripts.STAGING_DELETE, keys, flatTokens.toArray());
			deleted += result == null ? 0L : result;
		}
		return deleted;
	}

	private long deleteArchives(Lease lease, Enumeration enumeration, List<String> errors) {
		if (enumeration.archives().isEmpty()) {
			return 0L;
		}
		requireAllowed(lease, "DELETE_ARCHIVES", 0L);
		String sessionId = sessionIdOf(enumeration);
		String index = KeyShapes.archiveIndexKey(this.cfg.tenantId, this.cfg.userId, sessionId);
		long deleted = 0L;
		long typeMismatch = 0L;
		List<String> removableMembers = new ArrayList<>();
		for (List<String> turnBatch : CountingRedis.batch(enumeration.archives(), this.cfg.batchSize)) {
			List<List<String>> keys = new ArrayList<>();
			for (String turn : turnBatch) {
				keys.add(List.of(KeyShapes.archiveKey(this.cfg.tenantId, this.cfg.userId, sessionId,
						Integer.parseInt(turn))));
			}
			List<List<String>> args = new ArrayList<>();
			for (int i = 0; i < keys.size(); i++) {
				args.add(List.of("string"));
			}
			List<Object> results = this.redis.pipelineEval(Scripts.TYPE_DEL, keys, args);
			List<Long> typeResults = new ArrayList<>();
			for (Object value : results) {
				typeResults.add(asLong(value));
			}
			ArchiveIndexPolicy.BatchResult batchResult = ArchiveIndexPolicy.classifyBatch(turnBatch,
					typeResults);
			deleted += batchResult.deleted();
			typeMismatch += batchResult.typeMismatch();
			removableMembers.addAll(batchResult.removableMembers());
		}
		for (List<String> members : CountingRedis.batch(removableMembers, this.cfg.batchSize)) {
			this.redis.srem(index, members.toArray(String[]::new));
		}
		long remaining = this.redis.scard(index);
		if (typeMismatch > 0L) {
			errors.add("archive type mismatch (expected string), retained=" + typeMismatch);
		}
		if (typeMismatch == 0L && ArchiveIndexPolicy.shouldDeleteIndex(remaining)) {
			this.redis.del(List.of(index));
		}
		else if (typeMismatch == 0L) {
			// 没有类型不符但仍残留成员：说明索引里有本批未覆盖的成员，保留索引，交给后续重试。
			errors.add("archive index still has members after delete, remaining=" + remaining);
		}
		return deleted;
	}

	private long deleteSessionKeys(Lease lease, Manifest.Dataset dataset) {
		requireAllowed(lease, "DELETE_SESSION", 0L);
		String sessionId = dataset.sessionId;
		List<String> keys = new ArrayList<>(List.of(
				KeyShapes.sessionKey(this.cfg.tenantId, this.cfg.userId, sessionId),
				KeyShapes.contextKey(this.cfg.tenantId, this.cfg.userId, sessionId),
				KeyShapes.transcriptKey(this.cfg.tenantId, this.cfg.userId, sessionId),
				KeyShapes.transcriptBytesKey(this.cfg.tenantId, this.cfg.userId, sessionId),
				KeyShapes.transcriptReadyKey(this.cfg.tenantId, this.cfg.userId, sessionId),
				KeyShapes.memoryKey(this.cfg.tenantId, this.cfg.userId, sessionId),
				KeyShapes.attachmentSessionIndexKey(this.cfg.tenantId, this.cfg.userId, sessionId),
				KeyShapes.attachmentKey(this.cfg.tenantId, this.cfg.userId, "file-" + sessionId),
				KeyShapes.sessionCurrentKey(this.cfg.tenantId, this.cfg.userId, sessionId)));
		if (dataset.runIds != null) {
			for (String runId : dataset.runIds) {
				keys.add(KeyShapes.attachmentFormIndexKey(this.cfg.tenantId, this.cfg.userId, runId,
						"call-" + runId));
			}
		}
		long deleted = 0L;
		for (List<String> batch : CountingRedis.batch(keys, this.cfg.batchSize)) {
			List<String[]> commands = new ArrayList<>();
			for (String key : batch) {
				commands.add(new String[] { "DEL", key });
			}
			List<Object> results = this.redis.pipelineRaw(commands);
			for (Object value : results) {
				deleted += asLong(value);
			}
		}
		return deleted;
	}

	private boolean guardBlocksWrite(Manifest.Dataset dataset) {
		List<String> keys = List.of(
				KeyShapes.purgeGuardKey(this.cfg.tenantId, this.cfg.userId, dataset.sessionId),
				KeyShapes.purgePendingKey(this.cfg.tenantId, this.cfg.userId, dataset.sessionId),
				KeyShapes.memoryKey(this.cfg.tenantId, this.cfg.userId, dataset.sessionId));
		this.redis.checkSameSlot(keys);
		Long written = this.redis.eval(Scripts.PURGE_GUARDED_WRITE, keys, "should-not-write",
				String.valueOf(this.cfg.keyTtlSeconds * 1_000L));
		return written != null && written == 0L;
	}

	private Boolean defer(Lease lease, String pendingJson) {
		List<?> result = this.redis.eval(Scripts.PURGE_DEFER,
				List.of(KeyShapes.purgeLeaseKey(this.cfg.tenantId, this.cfg.userId, sessionId(lease)),
						KeyShapes.purgePendingKey(this.cfg.tenantId, this.cfg.userId, sessionId(lease))),
				this.owner, String.valueOf(lease.epoch()), lease.purgeId(), pendingJson,
				String.valueOf(System.currentTimeMillis()));
		return number(result, 0) == 1L;
	}

	private Boolean failRetryable(Lease lease, String pendingJson) {
		List<String> keys = List.of(
				KeyShapes.purgeLeaseKey(this.cfg.tenantId, this.cfg.userId, sessionId(lease)),
				KeyShapes.purgePendingKey(this.cfg.tenantId, this.cfg.userId, sessionId(lease)));
		this.redis.checkSameSlot(keys);
		List<?> result = this.redis.eval(Scripts.PURGE_FAIL, keys, this.owner,
				String.valueOf(lease.epoch()), lease.purgeId(), pendingJson,
				String.valueOf(System.currentTimeMillis()));
		return number(result, 0) == 1L;
	}

	private Boolean complete(Lease lease, String pendingJson) {
		String sessionId = sessionId(lease);
		// lease/pending/guard 与墓碑（chat:mem:...{sid}:gen|ver）同槽：完成态与防复活墓碑原子落库。
		List<String> keys = List.of(
				KeyShapes.purgeLeaseKey(this.cfg.tenantId, this.cfg.userId, sessionId),
				KeyShapes.purgePendingKey(this.cfg.tenantId, this.cfg.userId, sessionId),
				KeyShapes.purgeGuardKey(this.cfg.tenantId, this.cfg.userId, sessionId),
				KeyShapes.memoryGenKey(this.cfg.tenantId, this.cfg.userId, sessionId),
				KeyShapes.memoryVerKey(this.cfg.tenantId, this.cfg.userId, sessionId));
		this.redis.checkSameSlot(keys);
		List<?> result = this.redis.eval(Scripts.PURGE_COMPLETE, keys,
				this.owner, String.valueOf(lease.epoch()), lease.purgeId(), pendingJson,
				String.valueOf(this.cfg.keyTtlSeconds * 1_000L), String.valueOf(System.currentTimeMillis()),
				lease.purgeId() + "|" + this.owner + "|" + lease.epoch());
		return number(result, 0) == 1L;
	}

	public PurgeOutcome leaseTakeover(ScenarioCatalog.Scenario scenario, int copy, Deadline deadline) {
		Manifest.Dataset dataset = this.manifest.require(scenario.id(), copy);
		deadline.check("takeover-acquire-first");
		Lease first = acquire(dataset, true);
		if (!first.acquired()) {
			return outcome(scenario.id(), copy, dataset.sessionId, false, true, List.of(),
					List.of("first acquire BUSY"), first, 0L, false, "BUSY");
		}
		sleep(this.cfg.leaseTtlMs + 200L);
		deadline.check("takeover-acquire-second");
		Lease second = acquire(dataset, true);
		if (!second.acquired()) {
			return outcome(scenario.id(), copy, dataset.sessionId, false, true, List.of(),
					List.of("takeover acquire BUSY"), second, 0L, false, "BUSY");
		}
		deadline.check("takeover-old-owner-check");
		List<?> oldStage = this.redis.eval(Scripts.PURGE_STAGE,
				List.of(KeyShapes.purgeLeaseKey(this.cfg.tenantId, this.cfg.userId, dataset.sessionId),
						KeyShapes.purgePendingKey(this.cfg.tenantId, this.cfg.userId, dataset.sessionId)),
				this.owner, String.valueOf(first.epoch()), first.purgeId(),
				pendingJson(first, "OLD_OWNER", 1, 0L, "old", List.of()),
				String.valueOf(this.cfg.leaseTtlMs), String.valueOf(System.currentTimeMillis()));
		boolean oldRejected = number(oldStage, 0) == 0L
				&& "STALE_EPOCH".equals(text(oldStage, 1));
		if (!oldRejected) {
			return outcome(scenario.id(), copy, dataset.sessionId, false, true, List.of(),
					List.of("old owner was not rejected: " + text(oldStage, 1)), second, 0L, false,
					"TAKEOVER_FAILED");
		}
		PurgeOutcome completed = purgeWithLease(scenario, copy, second,
				!ScenarioCatalog.expectedDeferred(scenario.id()), deadline);
		return new PurgeOutcome(completed.scenario(), completed.copy(), completed.sessionId(),
				completed.complete(), completed.found(), completed.deferred(), completed.errors(),
				completed.purgeId(), completed.epoch(), completed.scanZero(), completed.deletedKeys(),
				completed.guardBlockedWrite(), completed.pendingStage(), completed.commands(),
				completed.scanPages(), completed.pipelineBatches(), completed.requestBytesEstimated(),
				completed.responseBytesEstimated());
	}

	public PurgeOutcome purgeWithLease(ScenarioCatalog.Scenario scenario, int copy, Lease lease,
			Deadline deadline) {
		return purgeWithLease(scenario, copy, lease, this.cfg.finalizeAllowed, deadline);
	}

	public PurgeOutcome purgeWithLease(ScenarioCatalog.Scenario scenario, int copy, Lease lease,
			boolean allowFinalize, Deadline deadline) {
		Manifest.Dataset dataset = this.manifest.require(scenario.id(), copy);
		deadline.check("takeover-enumerate");
		Enumeration enumeration = enumerate(dataset);
		List<String> errors = new ArrayList<>();
		List<String> deferred = new ArrayList<>();
		deadline.check("takeover-finalize");
		finalizeRuns(lease, enumeration, deferred, allowFinalize);
		if (!deferred.isEmpty()) {
			Boolean deferWritten = defer(lease,
					pendingJson(lease, "DEFERRED", 2, 0L, "takeover", deferred));
			PurgeCompletionPolicy.Decision decision = PurgeCompletionPolicy.decide(false,
					Boolean.TRUE.equals(deferWritten), errors, deferred);
			dataset.state = datasetState(decision);
			return outcome(scenario.id(), copy, dataset.sessionId, decision.complete(),
					enumeration.found(), deferred, decision.errors(), lease, 0L, false,
					decision.state().name());
		}
		deadline.check("takeover-delete-runs");
		long deleted = deleteRuns(lease, dataset, enumeration);
		deadline.check("takeover-delete-staging");
		deleted += deleteStaging(lease, enumeration);
		deadline.check("takeover-delete-archives");
		deleted += deleteArchives(lease, enumeration, errors);
		deadline.check("takeover-delete-session");
		deleted += deleteSessionKeys(lease, dataset);
		deadline.check("takeover-guarded-write");
		boolean guardBlocked = guardBlocksWrite(dataset);
		if (!guardBlocked) {
			errors.add("guard did not block write-back");
		}
		PurgeLifecyclePolicy.WriteKind writeKind = PurgeLifecyclePolicy.writeKind(
				!errors.isEmpty(), false, guardBlocked);
		if (writeKind == PurgeLifecyclePolicy.WriteKind.FAILED_RETRYABLE) {
			String failPending = pendingJson(lease, "FAILED_RETRYABLE", 2, 0L, "failure", List.of());
			Boolean failWritten = failRetryable(lease, failPending);
			if (!Boolean.TRUE.equals(failWritten)) {
				errors.add("failure state write rejected");
			}
			PurgeCompletionPolicy.Decision failed = PurgeCompletionPolicy.decide(false, false,
					errors, List.of());
			dataset.state = datasetState(failed);
			return outcome(scenario.id(), copy, dataset.sessionId, false, enumeration.found(), deferred,
					failed.errors(), lease, deleted, guardBlocked, "FAILED_RETRYABLE");
		}
		deadline.check("takeover-due");
		try {
			this.redis.zadd(KeyShapes.purgeDueKey(),
					KeyShapes.purgePendingKey(this.cfg.tenantId, this.cfg.userId, dataset.sessionId),
					System.currentTimeMillis() + 60_000L);
		}
		catch (RuntimeException ex) {
			errors.add("due zadd failed: " + ex.getMessage());
		}
		if (!errors.isEmpty()) {
			String failPending = pendingJson(lease, "FAILED_RETRYABLE", 2, 0L, "failure", List.of());
			Boolean failWritten = failRetryable(lease, failPending);
			if (!Boolean.TRUE.equals(failWritten)) {
				errors.add("failure state write rejected");
			}
			PurgeCompletionPolicy.Decision failed = PurgeCompletionPolicy.decide(false, false,
					errors, List.of());
			dataset.state = datasetState(failed);
			return outcome(scenario.id(), copy, dataset.sessionId, false, enumeration.found(), deferred,
					failed.errors(), lease, deleted, guardBlocked, "FAILED_RETRYABLE");
		}
		deadline.check("takeover-complete");
		Boolean completed = complete(lease, pendingJson(lease, "COMPLETE", 2,
				System.currentTimeMillis() + 60_000L, "takeover", List.of()));
		if (!Boolean.TRUE.equals(completed)) {
			errors.add("complete rejected (lease lost?)");
		}
		PurgeCompletionPolicy.Decision decision = PurgeCompletionPolicy.decide(
				Boolean.TRUE.equals(completed), false, errors, List.of());
		dataset.state = datasetState(decision);
		return outcome(scenario.id(), copy, dataset.sessionId, decision.complete(),
				enumeration.found(), deferred, decision.errors(), lease, deleted, guardBlocked,
				decision.state().name());
	}

	private static String datasetState(PurgeCompletionPolicy.Decision decision) {
		return switch (decision.state()) {
			case COMPLETE -> "purged";
			case DEFERRED -> "deferred";
			case FAILED -> "failed";
		};
	}

	private String pendingJson(Lease lease, String stage, int attempts, long verifyAtMs, String detail,
			List<String> deferred) {
		Map<String, Object> pending = new LinkedHashMap<>();
		pending.put("sessionId", sessionId(lease));
		pending.put("purgeId", lease.purgeId());
		pending.put("owner", this.owner);
		pending.put("epoch", lease.epoch());
		pending.put("stage", stage);
		pending.put("attempts", attempts);
		pending.put("verifyAtMs", verifyAtMs);
		pending.put("detail", detail);
		pending.put("deferred", deferred);
		pending.put("atMs", System.currentTimeMillis());
		return json(pending);
	}

	private String sessionIdOf(Enumeration enumeration) {
		return enumeration.sessionId();
	}

	private record Enumeration(boolean found, List<String> runs, List<String> staging,
			List<String> archives, String sessionId) {
	}

	private static long number(List<?> values, int index) {
		Object value = value(values, index);
		if (value == null) {
			return 0L;
		}
		if (value instanceof Number number) {
			return number.longValue();
		}
		try {
			return Long.parseLong(String.valueOf(value));
		}
		catch (NumberFormatException ex) {
			return 0L;
		}
	}

	/** pipeline 结果按命令顺序返回；SMEMBERS 是非空 Set<String>（空集可能仍是空 Set<byte[]>）。 */
	private static List<String> strings(List<?> values, int index) {
		Object value = value(values, index);
		if (!(value instanceof Collection<?> collection)) {
			return List.of();
		}
		List<String> out = new ArrayList<>(collection.size());
		for (Object item : collection) {
			out.add(text(item));
		}
		return out;
	}

	private static Object value(List<?> values, int index) {
		return values == null || values.size() <= index ? null : values.get(index);
	}

	private static String text(List<?> values, int index) {
		return text(value(values, index));
	}

	private static String text(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof byte[] bytes) {
			return new String(bytes, StandardCharsets.UTF_8);
		}
		return String.valueOf(value);
	}

	private static long asLong(Object value) {
		if (value instanceof Number number) {
			return number.longValue();
		}
		try {
			return Long.parseLong(String.valueOf(value));
		}
		catch (NumberFormatException ex) {
			return 0L;
		}
	}

	private static String json(Object value) {
		try {
			return MAPPER.writeValueAsString(value);
		}
		catch (Exception ex) {
			throw new IllegalStateException("JSON 序列化失败", ex);
		}
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted", ex);
		}
	}

}
