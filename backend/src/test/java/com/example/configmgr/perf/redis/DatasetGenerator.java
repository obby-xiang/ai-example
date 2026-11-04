package com.example.configmgr.perf.redis;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 等价会话数据生成器。所有键带 TTL；不写全局 ai:runs（避免真实实例的 startup-resume 驱动合成轮）。
 */
public final class DatasetGenerator {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final HarnessConfig cfg;

	private final CountingRedis redis;

	private final Duration ttl;

	public DatasetGenerator(HarnessConfig cfg, CountingRedis redis) {
		this.cfg = cfg;
		this.redis = redis;
		this.ttl = Duration.ofSeconds(cfg.keyTtlSeconds);
	}

	private record RunSpec(String status, boolean terminalFrame, String terminalType, String layout,
			boolean leaseHeld, String pendingStatus, boolean claim, boolean decided, boolean executed, int frames) {
	}

	public Manifest.Dataset generate(ScenarioCatalog.Scenario scenario, int copy) {
		String sessionId = KeyShapes.sessionId(this.cfg.namespace, scenario.id(), copy);
		Manifest.Dataset dataset = new Manifest.Dataset(scenario.id(), copy, sessionId);
		dataset.protocol = scenario.protocol();

		if ("empty".equals(scenario.id())) {
			return dataset;
		}

		List<String> runIds = new ArrayList<>();
		for (int i = 0; i < scenario.runs(); i++) {
			runIds.add(KeyShapes.runId(sessionId, i));
		}
		dataset.runIds = runIds;
		writeSession(dataset, scenario, runIds);
		writeRuns(dataset, scenario, runIds);
		writeStaging(dataset, scenario);
		writeArchives(dataset, scenario);
		writeAttachments(dataset, scenario, runIds);

		if (scenario.background() > 0) {
			dataset.background = scenario.background();
			dataset.declaredBackground = scenario.background();
			dataset.backgroundBucket = ScenarioCatalog.backgroundBucket(scenario);
			writeBackground(dataset);
			this.redis.set(KeyShapes.backgroundMarkerKey(this.cfg.namespace),
					String.valueOf(scenario.background()), this.ttl);
			dataset.keys.add(KeyShapes.backgroundMarkerKey(this.cfg.namespace));
		}
		if ("bg_existing_runs_1".equals(scenario.id())) {
			// 声明规模 ≠ 本工具生成的键数：既有大键空间不展开、不清理、不遍历。
			dataset.background = 0;
			dataset.declaredBackground = this.cfg.existingBackgroundScale;
			dataset.externalBackground = true;
		}
		return dataset;
	}

	private void writeSession(Manifest.Dataset dataset, ScenarioCatalog.Scenario scenario, List<String> runIds) {
		String sessionKey = KeyShapes.sessionKey(this.cfg.tenantId, this.cfg.userId, dataset.sessionId);
		Map<String, Object> session = new LinkedHashMap<>();
		session.put("sessionId", dataset.sessionId);
		session.put("tenantId", this.cfg.tenantId);
		session.put("userId", this.cfg.userId);
		session.put("title", "perf " + scenario.id());
		session.put("createdAtMs", System.currentTimeMillis());
		this.redis.set(sessionKey, json(session), this.ttl);
		dataset.keys.add(sessionKey);

		if (!runIds.isEmpty()) {
			String runsKey = KeyShapes.sessionRunsKey(this.cfg.tenantId, this.cfg.userId, dataset.sessionId);
			this.redis.sadd(runsKey, runIds);
			this.redis.pexpire(runsKey, this.ttl.toMillis());
			dataset.keys.add(runsKey);

			String activeKey = KeyShapes.sessionActiveKey(this.cfg.tenantId, this.cfg.userId, dataset.sessionId);
			for (int i = 0; i < runIds.size(); i++) {
				this.redis.zadd(activeKey, runIds.get(i), System.currentTimeMillis() + i);
			}
			this.redis.pexpire(activeKey, this.ttl.toMillis());
			dataset.keys.add(activeKey);

			String currentKey = KeyShapes.sessionCurrentKey(this.cfg.tenantId, this.cfg.userId, dataset.sessionId);
			this.redis.set(currentKey, runIds.get(0), this.ttl);
			dataset.keys.add(currentKey);
		}

		String contextKey = KeyShapes.contextKey(this.cfg.tenantId, this.cfg.userId, dataset.sessionId);
		this.redis.hset(contextKey, "turnNo", "3");
		this.redis.hset(contextKey, "compSeq", "0");
		this.redis.pexpire(contextKey, this.ttl.toMillis());
		dataset.keys.add(contextKey);

		String transcript = KeyShapes.transcriptKey(this.cfg.tenantId, this.cfg.userId, dataset.sessionId);
		this.redis.set(transcript, "{\"messages\":[]}", this.ttl);
		this.redis.set(KeyShapes.transcriptBytesKey(this.cfg.tenantId, this.cfg.userId, dataset.sessionId),
				"2", this.ttl);
		this.redis.set(KeyShapes.transcriptReadyKey(this.cfg.tenantId, this.cfg.userId, dataset.sessionId),
				"true", this.ttl);
		dataset.keys.add(transcript);
		dataset.keys.add(KeyShapes.transcriptBytesKey(this.cfg.tenantId, this.cfg.userId, dataset.sessionId));
		dataset.keys.add(KeyShapes.transcriptReadyKey(this.cfg.tenantId, this.cfg.userId, dataset.sessionId));

		String memory = KeyShapes.memoryKey(this.cfg.tenantId, this.cfg.userId, dataset.sessionId);
		this.redis.set(memory, "[]", this.ttl);
		this.redis.set(KeyShapes.memoryVerKey(this.cfg.tenantId, this.cfg.userId, dataset.sessionId), "0",
				this.ttl);
		this.redis.set(KeyShapes.memoryGenKey(this.cfg.tenantId, this.cfg.userId, dataset.sessionId), "1",
				this.ttl);
		dataset.keys.add(memory);
		dataset.keys.add(KeyShapes.memoryVerKey(this.cfg.tenantId, this.cfg.userId, dataset.sessionId));
		dataset.keys.add(KeyShapes.memoryGenKey(this.cfg.tenantId, this.cfg.userId, dataset.sessionId));
	}

	private void writeRuns(Manifest.Dataset dataset, ScenarioCatalog.Scenario scenario, List<String> runIds) {
		switch (scenario.id()) {
			case "mixed_active" -> {
				writeRun(dataset, runIds.get(0), 0, new RunSpec("DONE", true, "done", "tail", true,
						null, false, false, false, 8));
				writeRun(dataset, runIds.get(1), 1, new RunSpec("RUNNING", true, "done", "tail", true,
						null, false, false, false, 8));
				writeRun(dataset, runIds.get(2), 2, new RunSpec("RUNNING", false, null, "missing", true,
						null, false, false, false, 8));
				writeRun(dataset, runIds.get(3), 3, new RunSpec("SUSPENDED", false, null, "none", true,
						"PENDING", false, false, false, 0));
			}
			case "frames_terminal_head" -> writeRun(dataset, runIds.get(0), 0, new RunSpec("RUNNING",
					true, "done", "head", true, null, false, false, false, scenario.frames()));
			case "frames_terminal_middle" -> writeRun(dataset, runIds.get(0), 0, new RunSpec("RUNNING",
					true, "done", "middle", true, null, false, false, false, scenario.frames()));
			case "frames_terminal_missing" -> writeRun(dataset, runIds.get(0), 0, new RunSpec("RUNNING",
					false, null, "missing", true, null, false, false, false, scenario.frames()));
			case "frames_gap_large" -> writeRun(dataset, runIds.get(0), 0, new RunSpec("RUNNING",
					true, "done", "gap", true, null, false, false, false, 4));
			case "active_unfinalizable" -> writeRun(dataset, runIds.get(0), 0, new RunSpec("RUNNING",
					false, null, "missing", false, null, false, false, false, 4));
			case "lease_takeover" -> writeRun(dataset, runIds.get(0), 0, new RunSpec("RUNNING",
					false, null, "missing", true, null, false, false, false, 2));
			case "b_frame_fence" -> writeRun(dataset, runIds.get(0), 0, new RunSpec("RUNNING",
					false, null, "tail", false, null, false, false, false, 2));
			case "b_decision_atomic" -> writeRun(dataset, runIds.get(0), 0, new RunSpec("SUSPENDED",
					false, null, "none", false, "PENDING", false, false, false, 0));
			case "b_claim_cancel" -> writeRun(dataset, runIds.get(0), 0, new RunSpec("SUSPENDED",
					false, null, "none", false, "APPROVED", false, false, false, 0));
			case "b_stream_gap" -> writeRun(dataset, runIds.get(0), 0, new RunSpec("RUNNING",
					true, "done", "gap", false, null, false, false, false, 4));
			case "b_slot_consistency" -> writeRun(dataset, runIds.get(0), 0, new RunSpec("SUSPENDED",
					false, null, "none", true, "APPROVED", true, true, true, 2));
			default -> {
				for (int i = 0; i < runIds.size(); i++) {
					String status = i % 3 == 0 ? "DONE" : (i % 3 == 1 ? "FAILED" : "CANCELLED");
					writeRun(dataset, runIds.get(i), i, new RunSpec(status, true,
							"FAILED".equals(status) ? "error" : "done", "tail", true, null, false, false,
							false, 8));
				}
			}
		}
	}

	private void writeRun(Manifest.Dataset dataset, String runId, int index, RunSpec spec) {
		String runKey = KeyShapes.runKey("ai:run:", this.cfg.tenantId, this.cfg.userId, runId);
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("runId", runId);
		snapshot.put("sessionId", dataset.sessionId);
		snapshot.put("tenantId", this.cfg.tenantId);
		snapshot.put("userId", this.cfg.userId);
		snapshot.put("status", spec.status());
		snapshot.put("turnNo", index + 1);
		snapshot.put("cancelled", "CANCELLED".equals(spec.status()));
		this.redis.set(runKey, json(snapshot), this.ttl);
		dataset.keys.add(runKey);

		String stateKey = KeyShapes.runKey("ai:runstate:", this.cfg.tenantId, this.cfg.userId, runId);
		this.redis.set(stateKey, spec.status(), this.ttl);
		dataset.keys.add(stateKey);

		long epoch = spec.leaseHeld() ? 1L : 0L;
		if (spec.leaseHeld()) {
			String leaseKey = KeyShapes.runKey("ai:lease:run:", this.cfg.tenantId, this.cfg.userId, runId);
			this.redis.set(leaseKey, epoch + "|" + "seed-owner|" + (System.currentTimeMillis()
					+ this.cfg.leaseTtlMs), Duration.ofMillis(this.cfg.leaseTtlMs));
			this.redis.set(KeyShapes.runKey("ai:fence:run:", this.cfg.tenantId, this.cfg.userId, runId),
					String.valueOf(epoch), this.ttl);
			this.redis.set(KeyShapes.runKey("ai:lease:epoch:", this.cfg.tenantId, this.cfg.userId, runId),
					String.valueOf(epoch), this.ttl);
			dataset.keys.add(leaseKey);
			dataset.keys.add(KeyShapes.runKey("ai:fence:run:", this.cfg.tenantId, this.cfg.userId, runId));
			dataset.keys.add(KeyShapes.runKey("ai:lease:epoch:", this.cfg.tenantId, this.cfg.userId, runId));
		}

		int lastSeq = writeFrames(dataset, runId, spec);
		String seqKey = KeyShapes.runKey("ai:seq:", this.cfg.tenantId, this.cfg.userId, runId);
		this.redis.set(seqKey, String.valueOf(lastSeq), this.ttl);
		dataset.keys.add(seqKey);
		dataset.keys.add(KeyShapes.runKey("ai:xevents:", this.cfg.tenantId, this.cfg.userId, runId));

		if (spec.terminalFrame() && !"gap".equals(spec.layout())) {
			String terminalJson = "{\"seq\":" + lastSeq + ",\"type\":\"" + spec.terminalType()
					+ "\",\"atMs\":" + System.currentTimeMillis() + "}";
			String xterminal = KeyShapes.xterminalKey(this.cfg.tenantId, this.cfg.userId, runId);
			this.redis.set(xterminal, terminalJson, this.ttl);
			dataset.keys.add(xterminal);
		}

		if (spec.pendingStatus() != null) {
			String pendingKey = KeyShapes.runKey("ai:pending:", this.cfg.tenantId, this.cfg.userId, runId);
			Map<String, Object> pending = new LinkedHashMap<>();
			pending.put("toolCallId", "call-" + runId);
			pending.put("name", "tool");
			pending.put("kind", "confirm");
			pending.put("status", spec.pendingStatus());
			pending.put("executed", spec.executed());
			pending.put("executedBy", spec.executed() ? "executor-a" : null);
			pending.put("claimedAtMs", spec.executed() ? System.currentTimeMillis() : 0);
			pending.put("requestedAtMs", System.currentTimeMillis());
			this.redis.hset(pendingKey, "call-" + runId, json(pending));
			this.redis.pexpire(pendingKey, this.ttl.toMillis());
			dataset.keys.add(pendingKey);

			String reqKey = KeyShapes.runKey("ai:req:", this.cfg.tenantId, this.cfg.userId, runId);
			this.redis.hset(reqKey, "call-" + runId, String.valueOf(System.currentTimeMillis()));
			this.redis.pexpire(reqKey, this.ttl.toMillis());
			dataset.keys.add(reqKey);
		}
		if (spec.claim()) {
			String claimKey = KeyShapes.runKey("ai:claim:", this.cfg.tenantId, this.cfg.userId, runId);
			this.redis.hset(claimKey, "call-" + runId, epoch + "|executor-a");
			this.redis.pexpire(claimKey, this.ttl.toMillis());
			dataset.keys.add(claimKey);
		}
		if (spec.decided()) {
			String decidedKey = KeyShapes.runKey("ai:decided:", this.cfg.tenantId, this.cfg.userId, runId);
			this.redis.hset(decidedKey, "call-" + runId,
					spec.pendingStatus() + "|" + System.currentTimeMillis() + "|seed");
			this.redis.pexpire(decidedKey, this.ttl.toMillis());
			dataset.keys.add(decidedKey);
		}
		if (spec.executed()) {
			String ledgerKey = KeyShapes.runKey("ai:ledger:", this.cfg.tenantId, this.cfg.userId, runId);
			this.redis.raw("RPUSH", ledgerKey,
					"{\"toolCallId\":\"call-" + runId + "\",\"outcome\":\"ok\",\"executedBy\":\"executor-a\"}");
			this.redis.pexpire(ledgerKey, this.ttl.toMillis());
			String idemKey = KeyShapes.runKey("ai:idem:", this.cfg.tenantId, this.cfg.userId, runId);
			this.redis.hset(idemKey, runId + ":call-" + runId, "0|ok||executor-a|" + System.currentTimeMillis());
			this.redis.pexpire(idemKey, this.ttl.toMillis());
			dataset.keys.add(ledgerKey);
			dataset.keys.add(idemKey);
		}
	}

	private int writeFrames(Manifest.Dataset dataset, String runId, RunSpec spec) {
		String streamKey = KeyShapes.runKey("ai:xevents:", this.cfg.tenantId, this.cfg.userId, runId);
		if ("none".equals(spec.layout()) || spec.frames() <= 0) {
			return 0;
		}
		if ("gap".equals(spec.layout())) {
			xadd(streamKey, "1-0", 1, "done");
			xadd(streamKey, "2-0", 2, "delta");
			xadd(streamKey, "500000-0", 500_000, "tool_start");
			xadd(streamKey, "900000-0", 900_000, "delta");
			return 900_000;
		}
		int terminalAt = switch (spec.layout()) {
			case "head" -> 1;
			case "middle" -> Math.max(1, spec.frames() / 2);
			case "tail" -> spec.frames();
			default -> -1;
		};
		for (int seq = 1; seq <= spec.frames(); seq++) {
			String type = seq == terminalAt ? spec.terminalType() : "delta";
			xadd(streamKey, seq + "-0", seq, type);
		}
		return spec.frames();
	}

	private void xadd(String streamKey, String id, long seq, String type) {
		String frame = "{\"type\":\"" + type + "\",\"seq\":" + seq + "}";
		this.redis.raw("XADD", streamKey, id, "seq", String.valueOf(seq), "frame", frame);
		this.redis.pexpire(streamKey, this.ttl.toMillis());
	}

	private void writeStaging(Manifest.Dataset dataset, ScenarioCatalog.Scenario scenario) {
		if (scenario.staging() <= 0) {
			return;
		}
		String index = KeyShapes.stagingIndexKey(this.cfg.tenantId, this.cfg.userId, dataset.sessionId);
		for (int i = 0; i < scenario.staging(); i++) {
			String token = "s" + i;
			String key = KeyShapes.stagingKey(this.cfg.tenantId, this.cfg.userId, dataset.sessionId, token);
			this.redis.raw("RPUSH", key, "staged-" + i);
			this.redis.pexpire(key, this.ttl.toMillis());
			this.redis.sadd(index, List.of(token));
			dataset.keys.add(key);
		}
		this.redis.pexpire(index, this.ttl.toMillis());
		dataset.keys.add(index);
	}

	private void writeArchives(Manifest.Dataset dataset, ScenarioCatalog.Scenario scenario) {
		if (scenario.archives() <= 0) {
			return;
		}
		String index = KeyShapes.archiveIndexKey(this.cfg.tenantId, this.cfg.userId, dataset.sessionId);
		List<String> turns = new ArrayList<>();
		for (int i = 0; i < scenario.archives(); i++) {
			String key = KeyShapes.archiveKey(this.cfg.tenantId, this.cfg.userId, dataset.sessionId, i);
			this.redis.set(key, "archive-" + i, this.ttl);
			dataset.keys.add(key);
			turns.add(String.valueOf(i));
			if (turns.size() >= this.cfg.batchSize) {
				this.redis.sadd(index, turns);
				turns.clear();
			}
		}
		if (!turns.isEmpty()) {
			this.redis.sadd(index, turns);
		}
		this.redis.pexpire(index, this.ttl.toMillis());
		dataset.keys.add(index);
	}

	private void writeAttachments(Manifest.Dataset dataset, ScenarioCatalog.Scenario scenario,
			List<String> runIds) {
		if (runIds.isEmpty()) {
			return;
		}
		String fileId = "file-" + dataset.sessionId;
		String sessionIndex = KeyShapes.attachmentSessionIndexKey(this.cfg.tenantId, this.cfg.userId,
				dataset.sessionId);
		this.redis.sadd(sessionIndex, List.of(fileId));
		this.redis.pexpire(sessionIndex, this.ttl.toMillis());
		dataset.keys.add(sessionIndex);

		String formIndex = KeyShapes.attachmentFormIndexKey(this.cfg.tenantId, this.cfg.userId,
				runIds.get(0), "call-" + runIds.get(0));
		this.redis.set(formIndex, fileId, this.ttl);
		dataset.keys.add(formIndex);

		String attachment = KeyShapes.attachmentKey(this.cfg.tenantId, this.cfg.userId, fileId);
		this.redis.set(attachment, "{\"fileId\":\"" + fileId + "\",\"name\":\"a.txt\",\"size\":2}", this.ttl);
		dataset.keys.add(attachment);
	}

	private void writeBackground(Manifest.Dataset dataset) {
		List<String[]> batch = new ArrayList<>();
		for (int i = 0; i < dataset.background; i++) {
			String key = KeyShapes.backgroundKey(this.cfg.namespace, dataset.backgroundBucket, i);
			batch.add(new String[] { "SET", key, "bg", "EX", String.valueOf(this.cfg.keyTtlSeconds) });
			if (batch.size() >= this.cfg.batchSize) {
				this.redis.pipelineRaw(batch);
				batch.clear();
			}
		}
		if (!batch.isEmpty()) {
			this.redis.pipelineRaw(batch);
		}
	}

	private String json(Object value) {
		try {
			return MAPPER.writeValueAsString(value);
		}
		catch (Exception ex) {
			throw new IllegalStateException("JSON 序列化失败", ex);
		}
	}

}
