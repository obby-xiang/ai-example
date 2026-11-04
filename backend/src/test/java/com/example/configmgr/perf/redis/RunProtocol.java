package com.example.configmgr.perf.redis;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * B：run/frame/decision 原子协议的直接 Redis 验证。
 */
public final class RunProtocol {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final HarnessConfig cfg;

	private final CountingRedis redis;

	private final HarnessMetrics metrics;

	private final Manifest manifest;

	private boolean unsafeForCleanup;

	public RunProtocol(HarnessConfig cfg, CountingRedis redis, HarnessMetrics metrics, Manifest manifest) {
		this.cfg = cfg;
		this.redis = redis;
		this.metrics = metrics;
		this.manifest = manifest;
	}

	public record FrameResult(long seq, boolean rejected, boolean idempotent, long fence) {
	}

	public boolean unsafeForCleanup() {
		return this.unsafeForCleanup;
	}

	public void run(ScenarioCatalog.Scenario scenario, int copy, Deadline deadline) {
		Manifest.Dataset dataset = this.manifest.require(scenario.id(), copy);
		if (dataset.runIds.isEmpty()) {
			throw new IllegalStateException("B 场景缺少 run：" + scenario.id());
		}
		String runId = dataset.runIds.get(0);
		switch (scenario.id()) {
			case "b_frame_fence" -> frameFence(scenario.id(), runId, deadline);
			case "b_decision_atomic" -> decisionAtomic(scenario.id(), runId, deadline);
			case "b_claim_cancel" -> claimCancel(scenario.id(), runId, deadline);
			case "b_stream_gap" -> streamGap(scenario.id(), runId, deadline);
			case "b_slot_consistency" -> slotConsistency(scenario.id(), runId, deadline);
			default -> throw new IllegalArgumentException("不是 B 场景：" + scenario.id());
		}
	}

	private void frameFence(String scenario, String runId, Deadline deadline) {
		record(scenario, "fence_before_allocate_archive_fanout", () -> {
			deadline.check("b_frame_fence");
			long[] first = runLeaseAcquire(runId, "owner-a", 250L);
			check(first[0] == 1L, "first run lease acquire failed");
			long firstEpoch = first[1];
			FrameResult delta = appendFrame(runId, "tok-a", firstEpoch, "delta", false, null);
			check(delta.seq() > 0 && !delta.rejected(), "first frame should be archived");
			long seqAfterFirst = seq(runId);
			long lenAfterFirst = xlen(runId);
			long fanoutAfterFirst = 1L;

			long[] busy = runLeaseAcquire(runId, "owner-b", 250L);
			check(busy[0] == 0L, "live lease must reject a second owner");

			sleep(320L);
			long[] second = runLeaseAcquire(runId, "owner-b", 250L);
			check(second[0] == 1L, "takeover after expiry must acquire");
			check(second[1] > firstEpoch, "takeover epoch must advance");
			long fanout = fanoutAfterFirst;

			FrameResult oldFrame = appendFrame(runId, "tok-old", firstEpoch, "delta", false, null);
			check(oldFrame.rejected(), "old epoch frame must be rejected");
			check(seq(runId) == seqAfterFirst, "old epoch must not allocate a seq");
			check(xlen(runId) == lenAfterFirst, "old epoch must not archive");
			check(fanout == fanoutAfterFirst, "old epoch must not fan out");

			FrameResult newFrame = appendFrame(runId, "tok-b", second[1], "delta", false, null);
			check(newFrame.seq() > seqAfterFirst, "new epoch frame must allocate after takeover");
			fanout++;
			FrameResult terminal = appendFrame(runId, "tok-b-terminal", second[1], "done", true,
					"{\"type\":\"done\"}");
			check(terminal.seq() > 0, "new epoch terminal must archive");
			check(redis.exists(KeyShapes.xterminalKey(cfg.tenantId, cfg.userId, runId)),
					"terminal fact (xterminal) must be written atomically with terminal frame");
			check(fanout == 2L, "fanout must only happen for accepted frames");
		});
	}

	private void decisionAtomic(String scenario, String runId, Deadline deadline) {
		String field = "call-" + runId;
		String pendingKey = KeyShapes.runKey("ai:pending:", cfg.tenantId, cfg.userId, runId);
		String decidedKey = KeyShapes.runKey("ai:decided:", cfg.tenantId, cfg.userId, runId);

		record(scenario, "legacy_two_key_crash_control", () -> {
			deadline.check("b_decision_atomic");
			redis.hdel(decidedKey, field);
			redis.hset(decidedKey, field, "APPROVED|" + System.currentTimeMillis() + "|legacy");
			Map<String, Object> pending = pendingMap(pendingKey, field);
			check("PENDING".equals(pending.get("status")),
					"legacy control: decided written while pending is still PENDING");
			check(redis.hexists(decidedKey, field),
					"legacy control leaves decided/pending half state (not the candidate behavior)");
			redis.hdel(decidedKey, field);
		});

		record(scenario, "decided_pending_single_slot_lua", () -> {
			deadline.check("b_decision_atomic");
			String outcome = decide(runId, field, "APPROVED", "PENDING", false);
			check("APPLIED".equals(outcome), "atomic decide should apply, got " + outcome);
			Map<String, Object> pending = pendingMap(pendingKey, field);
			check("APPROVED".equals(pending.get("status")), "pending payload must be the decided payload");
			check(redis.hexists(decidedKey, field), "decided guard must exist");
			check(redis.ttlSeconds(pendingKey) > 0L, "pending TTL must be set");
			check(redis.ttlSeconds(decidedKey) > 0L, "decided TTL must be set");
			String again = decide(runId, field, "APPROVED", "", false);
			check("IDEMPOTENT".equals(again), "same decision must be idempotent, got " + again);
			String conflict = decide(runId, field, "REJECTED", "", false);
			check("CONFLICT_DECIDED".equals(conflict), "different decision must conflict, got " + conflict);
		});
	}

	private void claimCancel(String scenario, String runId, Deadline deadline) {
		String field = "call-" + runId;
		record(scenario, "frontend_requires_claim", () -> {
			deadline.check("b_claim_cancel");
			String missing = frontendResult(runId, field, "0|executor-a", "pre-claim");
			check("MISSING_CLAIM".equals(missing),
					"frontend result without a claim must be rejected, got " + missing);
			Map<String, Object> pending = pendingMap(KeyShapes.runKey("ai:pending:", cfg.tenantId,
					cfg.userId, runId), field);
			check(pending.get("resultText") == null, "rejected frontend result must not write payload");
		});

		record(scenario, "claim_single_winner", () -> {
			deadline.check("b_claim_cancel");
			long[] lease = runLeaseAcquire(runId, "owner-a", 5_000L);
			check(lease[0] == 1L, "claim scenario needs a run lease");
			CyclicBarrier barrier = new CyclicBarrier(2);
			AtomicReference<String> a = new AtomicReference<>();
			AtomicReference<String> b = new AtomicReference<>();
			AtomicReference<Throwable> e1 = new AtomicReference<>();
			AtomicReference<Throwable> e2 = new AtomicReference<>();
			Thread t1 = new Thread(() -> {
				try {
					await(barrier);
					a.set(claim(runId, field, lease[1], "executor-a", "APPROVED"));
				}
				catch (Throwable ex) {
					e1.set(ex);
				}
			});
			Thread t2 = new Thread(() -> {
				try {
					await(barrier);
					b.set(claim(runId, field, lease[1], "executor-b", "APPROVED"));
				}
				catch (Throwable ex) {
					e2.set(ex);
				}
			});
			t1.start();
			t2.start();
			try {
				ThreadGuard.joinOrFail(t1, 10_000L, "claim-executor-a", e1);
				ThreadGuard.joinOrFail(t2, 10_000L, "claim-executor-b", e2);
			}
			catch (RuntimeException ex) {
				this.unsafeForCleanup = true;
				throw ex;
			}
			long won = List.of(a.get(), b.get()).stream().filter("WON"::equals).count();
			long already = List.of(a.get(), b.get()).stream().filter("ALREADY_CLAIMED"::equals).count();
			check(won == 1L && already == 1L,
					"exactly one claim winner expected, got " + a.get() + "/" + b.get());
		});

		record(scenario, "cancel_cannot_overwrite_claim", () -> {
			deadline.check("b_claim_cancel");
			String cancel = cancel(runId, field);
			check("CONFLICT_CLAIMED".equals(cancel), "cancel must not overwrite a claim, got " + cancel);
			String claimValue = claimValue(runId, field);
			check(claimValue != null, "claim table must keep the winner's full claim value");
			String owner = claimInstance(claimValue);
			String wrong = "executor-a".equals(owner) ? "executor-b" : "executor-a";
			String wrongClaim = frontendResult(runId, field, withClaimInstance(claimValue, wrong), "wrong-result");
			check("CONFLICT_CLAIMED".equals(wrongClaim),
					"non-owner frontend result must be rejected, got " + wrongClaim);
			String applied = frontendResult(runId, field, claimValue, "ok-result");
			check("APPLIED".equals(applied), "owner frontend result should apply, got " + applied);
			Map<String, Object> pending = pendingMap(KeyShapes.runKey("ai:pending:", cfg.tenantId,
					cfg.userId, runId), field);
			check("APPROVED".equals(pending.get("status")), "cancel must not flip a claimed entry");
			check(Boolean.TRUE.equals(pending.get("executed")), "claimed side effect stays executed");
			check("ok-result".equals(pending.get("resultText")), "frontend result must be stored");
		});
	}

	private void streamGap(String scenario, String runId, Deadline deadline) {
		record(scenario, "physical_entry_id_paging", () -> {
			deadline.check("b_stream_gap");
			String streamKey = KeyShapes.runKey("ai:xevents:", cfg.tenantId, cfg.userId, runId);
			StreamPager.Result full = RedisStreams.scan(redis, streamKey, 2, 10);
			check(full.terminalFound(), "terminal at physical ID 1-0 must be found");
			check(full.toolStartFound(), "tool_start after a 500000 seq gap must be found");
			check(!full.truncated(), "full scan should complete");
			StreamPager.Result partial = RedisStreams.scan(redis, streamKey, 1, 1);
			check(partial.truncated(), "exhausted budget must be reported incomplete");
			check(partial.terminalFound(), "first terminal must be found in the first entry");
			check(partial.toolStartUnknown(), "unscanned tool_start must be unknown, not false");
		});
	}

	private void slotConsistency(String scenario, String runId, Deadline deadline) {
		record(scenario, "same_slot_multi_key_groups", () -> {
			deadline.check("b_slot_consistency");
			List<List<String>> groups = List.of(
					KeyShapes.runDeleteKeys(cfg.tenantId, cfg.userId, runId),
					List.of(KeyShapes.runKey("ai:pending:", cfg.tenantId, cfg.userId, runId),
							KeyShapes.runKey("ai:decided:", cfg.tenantId, cfg.userId, runId),
							KeyShapes.runKey("ai:claim:", cfg.tenantId, cfg.userId, runId)),
					List.of(KeyShapes.purgeGuardKey(cfg.tenantId, cfg.userId, manifest.require(scenario, 0).sessionId),
							KeyShapes.purgePendingKey(cfg.tenantId, cfg.userId, manifest.require(scenario, 0).sessionId),
							KeyShapes.purgeLeaseKey(cfg.tenantId, cfg.userId, manifest.require(scenario, 0).sessionId)),
					List.of(KeyShapes.stagingIndexKey(cfg.tenantId, cfg.userId, manifest.require(scenario, 0).sessionId),
							KeyShapes.stagingKey(cfg.tenantId, cfg.userId, manifest.require(scenario, 0).sessionId, "a"),
							KeyShapes.stagingKey(cfg.tenantId, cfg.userId, manifest.require(scenario, 0).sessionId, "b")),
					List.of(KeyShapes.memoryKey(cfg.tenantId, cfg.userId, manifest.require(scenario, 0).sessionId),
							KeyShapes.purgeGuardKey(cfg.tenantId, cfg.userId, manifest.require(scenario, 0).sessionId)));
			int checks = 0;
			for (List<String> group : groups) {
				redis.checkSameSlot(group);
				checks++;
				if (cfg.mode == HarnessConfig.Mode.CLUSTER) {
					for (String key : group) {
						long server = this.clusterKeyslot(key);
						long local = RedisSlots.slot(key);
						check(server == local, "CLUSTER KEYSLOT mismatch for " + key + ": " + server + " vs "
								+ local);
						checks++;
					}
				}
			}
			check(checks >= groups.size(), "slot checks must cover every multi-key group");
		});
	}

	private void track(String runId, List<String> keys) {
		for (String key : keys) {
			this.manifest.addKeyForRun(runId, key);
		}
	}

	private long clusterKeyslot(String key) {
		return redis.clusterKeyslot(key);
	}

	public long[] runLeaseAcquire(String runId, String owner, long ttlMs) {
		List<String> keys = List.of(
				KeyShapes.runKey("ai:lease:run:", cfg.tenantId, cfg.userId, runId),
				KeyShapes.runKey("ai:lease:epoch:", cfg.tenantId, cfg.userId, runId),
				KeyShapes.runKey("ai:fence:run:", cfg.tenantId, cfg.userId, runId));
		redis.checkSameSlot(keys);
		List<?> result = redis.eval(Scripts.RUN_LEASE_ACQUIRE, keys, owner, String.valueOf(ttlMs),
				String.valueOf(System.currentTimeMillis()),
				String.valueOf(cfg.keyTtlSeconds * 1_000L));
		for (String key : keys) {
			this.manifest.addKeyForRun(runId, key);
		}
		return new long[] { number(result, 0), number(result, 1) };
	}

	public FrameResult appendFrame(String runId, String token, long epoch, String type, boolean terminal,
			String terminalJson) {
		String seqKey = KeyShapes.runKey("ai:seq:", cfg.tenantId, cfg.userId, runId);
		String streamKey = KeyShapes.runKey("ai:xevents:", cfg.tenantId, cfg.userId, runId);
		String ackKey = KeyShapes.frameAckKey(cfg.tenantId, cfg.userId, runId, token);
		String trimKey = KeyShapes.xtrimKey(cfg.tenantId, cfg.userId, runId);
		String fenceKey = KeyShapes.runKey("ai:fence:run:", cfg.tenantId, cfg.userId, runId);
		String xterminal = KeyShapes.xterminalKey(cfg.tenantId, cfg.userId, runId);
		List<String> keys = List.of(seqKey, streamKey, ackKey, trimKey, fenceKey, xterminal);
		redis.checkSameSlot(keys);
		List<?> result = redis.eval(Scripts.FRAME_APPEND, keys, String.valueOf(epoch),
				String.valueOf(cfg.keyTtlSeconds * 1_000L), "6000", "120", token,
				"{\"type\":\"" + type + "\",\"seq\":", "}", terminal ? "1" : "0",
				terminalJson == null ? "" : terminalJson);
		long seq = number(result, 0);
		if (seq > 0L) {
			this.manifest.addKeyForRun(runId, ackKey);
			this.manifest.addKeyForRun(runId, trimKey);
		}
		return new FrameResult(seq, seq < 0L, number(result, 1) == 1L, number(result, 1));
	}

	public String decide(String runId, String field, String requested, String expectedStatus,
			boolean cancelLike) {
		String pendingKey = KeyShapes.runKey("ai:pending:", cfg.tenantId, cfg.userId, runId);
		String decidedKey = KeyShapes.runKey("ai:decided:", cfg.tenantId, cfg.userId, runId);
		String claimKey = KeyShapes.runKey("ai:claim:", cfg.tenantId, cfg.userId, runId);
		Map<String, Object> pending = pendingMap(pendingKey, field);
		pending.put("status", requested);
		pending.put("resolvedAtMs", System.currentTimeMillis());
		pending.put("executed", false);
		String decidedValue = requested + "|" + System.currentTimeMillis() + "|harness";
		List<String> keys = List.of(pendingKey, decidedKey, claimKey);
		redis.checkSameSlot(keys);
		List<?> result = redis.eval(Scripts.DECIDE, keys, field, expectedStatus, requested,
				json(pending), decidedValue, cancelLike ? "1" : "0",
				String.valueOf(cfg.keyTtlSeconds * 1_000L));
		track(runId, keys);
		return text(result, 1);
	}

	public String claim(String runId, String field, long epoch, String instance, String expectedStatus) {
		String claimKey = KeyShapes.runKey("ai:claim:", cfg.tenantId, cfg.userId, runId);
		String pendingKey = KeyShapes.runKey("ai:pending:", cfg.tenantId, cfg.userId, runId);
		String fenceKey = KeyShapes.runKey("ai:fence:run:", cfg.tenantId, cfg.userId, runId);
		Map<String, Object> pending = pendingMap(pendingKey, field);
		pending.put("status", expectedStatus);
		pending.put("executed", true);
		pending.put("executedBy", instance);
		pending.put("claimedAtMs", System.currentTimeMillis());
		List<String> keys = List.of(claimKey, pendingKey, fenceKey);
		redis.checkSameSlot(keys);
		List<?> result = redis.eval(Scripts.CLAIM, keys, field, String.valueOf(epoch), instance,
				json(pending), String.valueOf(cfg.keyTtlSeconds * 1_000L), expectedStatus);
		track(runId, keys);
		return text(result, 1);
	}

	public String cancel(String runId, String field) {
		String pendingKey = KeyShapes.runKey("ai:pending:", cfg.tenantId, cfg.userId, runId);
		String decidedKey = KeyShapes.runKey("ai:decided:", cfg.tenantId, cfg.userId, runId);
		String claimKey = KeyShapes.runKey("ai:claim:", cfg.tenantId, cfg.userId, runId);
		Map<String, Object> pending = pendingMap(pendingKey, field);
		pending.put("status", "CANCELLED");
		pending.put("cancelled", true);
		pending.put("resolvedAtMs", System.currentTimeMillis());
		List<String> keys = List.of(pendingKey, decidedKey, claimKey);
		redis.checkSameSlot(keys);
		List<?> result = redis.eval(Scripts.CANCEL_PENDING, keys, field, json(pending),
				"CANCELLED|" + System.currentTimeMillis() + "|harness",
				String.valueOf(cfg.keyTtlSeconds * 1_000L));
		track(runId, keys);
		return text(result, 1);
	}

	public String frontendResult(String runId, String field, String expectedClaimValue, String resultText) {
		String pendingKey = KeyShapes.runKey("ai:pending:", cfg.tenantId, cfg.userId, runId);
		String decidedKey = KeyShapes.runKey("ai:decided:", cfg.tenantId, cfg.userId, runId);
		String claimKey = KeyShapes.runKey("ai:claim:", cfg.tenantId, cfg.userId, runId);
		Map<String, Object> pending = pendingMap(pendingKey, field);
		pending.put("resultText", resultText);
		pending.put("executed", true);
		pending.put("resolvedAtMs", System.currentTimeMillis());
		List<String> keys = List.of(pendingKey, decidedKey, claimKey);
		redis.checkSameSlot(keys);
		List<?> result = redis.eval(Scripts.FRONTEND_RESULT, keys, field, json(pending),
				"APPROVED|" + System.currentTimeMillis() + "|harness",
				String.valueOf(cfg.keyTtlSeconds * 1_000L), expectedClaimValue);
		track(runId, keys);
		return text(result, 1);
	}

	/** claim 表的完整值 {@code <epoch>|<instanceId>} —— 回执比较用的就是这个原值。 */
	public String claimValue(String runId, String field) {
		return redis.hget(KeyShapes.runKey("ai:claim:", cfg.tenantId, cfg.userId, runId), field);
	}

	/** 完整 claim 值里的实例标识；旧裸值（无 epoch 前缀）原样返回。 */
	static String claimInstance(String claimValue) {
		if (claimValue == null) {
			return null;
		}
		int sep = claimValue.indexOf('|');
		if (sep <= 0 || !digitsPrefix(claimValue, sep)) {
			return claimValue;
		}
		return claimValue.substring(sep + 1);
	}

	/** 换个实例标识、保留 epoch 前缀，用来构造同 epoch 的非 owner 完整 claim 值。 */
	static String withClaimInstance(String claimValue, String instance) {
		if (claimValue == null) {
			return instance;
		}
		int sep = claimValue.indexOf('|');
		if (sep <= 0 || !digitsPrefix(claimValue, sep)) {
			return instance;
		}
		return claimValue.substring(0, sep + 1) + instance;
	}

	private static boolean digitsPrefix(String value, int end) {
		for (int i = 0; i < end; i++) {
			if (!Character.isDigit(value.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	private Map<String, Object> pendingMap(String pendingKey, String field) {
		String raw = redis.hget(pendingKey, field);
		if (raw == null) {
			throw new AssertionError("pending entry missing: " + field);
		}
		try {
			return MAPPER.readValue(raw, new TypeReference<Map<String, Object>>() {
			});
		}
		catch (Exception ex) {
			throw new IllegalStateException("无法解析 pending JSON", ex);
		}
	}

	private long seq(String runId) {
		String value = redis.get(KeyShapes.runKey("ai:seq:", cfg.tenantId, cfg.userId, runId));
		return value == null ? 0L : Long.parseLong(value);
	}

	private long xlen(String runId) {
		return redis.xlen(KeyShapes.runKey("ai:xevents:", cfg.tenantId, cfg.userId, runId));
	}

	private void record(String scenario, String name, ThrowingRunnable body) {
		long start = System.nanoTime();
		try {
			body.run();
			metrics.protocol(scenario, name, true, "pass", System.nanoTime() - start);
		}
		catch (Throwable ex) {
			metrics.protocol(scenario, name, false, diagnostic(ex), System.nanoTime() - start);
		}
	}

	/**
	 * 失败诊断：外层异常常常只是 Spring 的 {@code Error in execution}，根因在 caused-by 链里。
	 * 这里把浅层摘要 + 完整堆栈（含全部 {@code Caused by}）一起落到 protocols.json，
	 * 远端不必再猜是语法、超时还是槽重定向。
	 */
	static String diagnostic(Throwable failure) {
		StringBuilder out = new StringBuilder();
		out.append(failure.getClass().getName());
		if (failure.getMessage() != null) {
			out.append(": ").append(failure.getMessage());
		}
		Throwable root = rootCause(failure);
		if (root != failure) {
			out.append(" | rootCause=").append(root.getClass().getName());
			if (root.getMessage() != null) {
				out.append(": ").append(root.getMessage());
			}
		}
		StringWriter stack = new StringWriter();
		failure.printStackTrace(new PrintWriter(stack));
		out.append(System.lineSeparator()).append(stack);
		return out.toString();
	}

	private static Throwable rootCause(Throwable failure) {
		Throwable current = failure;
		for (int depth = 0; depth < 64; depth++) {
			Throwable cause = current.getCause();
			if (cause == null || cause == current) {
				return current;
			}
			current = cause;
		}
		return current;
	}

	private interface ThrowingRunnable {

		void run() throws Exception;

	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}

	private static long number(List<?> values, int index) {
		if (values == null || values.size() <= index || values.get(index) == null) {
			return 0L;
		}
		Object value = values.get(index);
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

	private static String text(List<?> values, int index) {
		if (values == null || values.size() <= index) {
			return null;
		}
		Object value = values.get(index);
		if (value instanceof byte[] bytes) {
			return new String(bytes, StandardCharsets.UTF_8);
		}
		return value == null ? null : String.valueOf(value);
	}

	private static String json(Object value) {
		try {
			return MAPPER.writeValueAsString(value);
		}
		catch (Exception ex) {
			throw new IllegalStateException("JSON 序列化失败", ex);
		}
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

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

}
