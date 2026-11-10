package com.example.configmgr.perf.production;

import com.example.configmgr.ai.context.ContextState;
import com.example.configmgr.ai.context.ContextStateStore;
import com.example.configmgr.ai.context.ContextTrigger;
import com.example.configmgr.ai.context.RedisContextStateStore;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.LeaseService;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.tenant.TenantKeyScope;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.perf.redis.Manifest;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Production context-state CAS, compaction reconciliation and bounded context ledgers. */
final class ContextCompactionLedgerScenario implements ProductionScenario {

	@Override
	public String id() {
		return "context_compaction_commit_hot";
	}

	@Override
	public Manifest.Dataset generate(ProductionSupport support, Manifest.Dataset dataset) {
		dataset.attributes.put("sessionId", dataset.sessionId);
		for (String prefix : List.of("ai:ctx:", "ai:ctx:ledger:", "ai:ctx:events:")) {
			support.own(dataset, TenantKeyScope.scoped(prefix, support.cfg.tenantId, support.cfg.userId,
					dataset.sessionId));
		}
		return dataset;
	}

	@Override
	public void measure(ProductionSupport support, Manifest.Dataset dataset) {
		String sessionId = String.valueOf(dataset.attributes.get("sessionId"));
		String contextKey = support.identity(() -> TenantKeyScope.scoped("ai:ctx:", sessionId));
		String eventsKey = support.identity(() -> TenantKeyScope.scoped("ai:ctx:events:", sessionId));
		RedisContextStateStore store = new RedisContextStateStore(support.redis, support.mapper, support.aiProperties);
		long scanBefore = support.commandMetrics.keyspaceScanPages();
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(support.cfg.tenantId,
				support.cfg.userId)) {
			boolean valid = true;
			try {
			valid &= support.stage(dataset, "context_fields_and_stale_cas", () -> {
				store.recordTurn(sessionId, 7L, "model-a", 100, 20, 0, false, 1000L, 10, 11, 12);
				ContextState stale = store.readStrict(sessionId);
				boolean fields = support.check(dataset, "context_field_write",
						stale.usedTokens() == 100 && stale.completionTokens() == 20 && stale.systemTokens() == 10
								&& stale.toolsTokens() == 11 && stale.inputTokens() == 12 && stale.turnNo() == 1
								&& stale.version() == 1 && Long.valueOf(7L).equals(stale.providerId()),
						"atomic field update preserves token dimensions, model/provider and version");
				store.recordTurn(sessionId, 7L, "model-a", 222, 21, 0, false, 1001L, 20, 21, 22);
				fields &= support.check(dataset, "context_stale_cas_rejected", store.compareAndSet(sessionId, stale) == null,
						"stale whole-state replacement cannot overwrite the newer turn");
				ContextState latest = store.readStrict(sessionId);
				fields &= support.check(dataset, "context_latest_survives_conflict",
						latest.usedTokens() == 222 && latest.turnNo() == 2 && latest.version() == 2,
						"latest values and version survive the rejected CAS");
				return fields;
			});

			if (support.faultEnabled("hgetall")) {
				support.faults.failNext("hgetall", contextKey);
				valid &= support.check(dataset, "strict_read_does_not_fabricate_empty",
						expectFailure(() -> store.readStrict(sessionId)),
						"HGETALL failure must propagate instead of becoming ContextState.EMPTY");
				support.faults.clear();
			}
			else {
				support.faultSkipped(dataset, "hgetall");
			}

			ContextState latest = store.readStrict(sessionId);
			String compactionId = "compaction-" + support.cfg.namespace;
			ContextState compacted = latest.withCompaction(ContextTrigger.Level.L1, 0, 0, "l1_tool_result_clearing",
					2000L);
			valid &= support.stage(dataset, "compaction_idempotent_commit", () -> {
				ContextStateStore.CompactionCommitStatus first = store.commitCompaction(sessionId, compactionId,
						compacted, List.of(ledgerEvent(compactionId)), compactionEvent(compactionId));
				ContextStateStore.CompactionCommitStatus replay = store.commitCompaction(sessionId, compactionId,
						compacted, List.of(ledgerEvent(compactionId)), compactionEvent(compactionId));
				return support.check(dataset, "compaction_idempotent_replay",
						first == ContextStateStore.CompactionCommitStatus.APPLIED
								&& replay == ContextStateStore.CompactionCommitStatus.ALREADY_APPLIED
								&& store.contextLedger(sessionId).size() == 1 && store.events(sessionId).size() == 1
								&& store.readStrict(sessionId).version() == 3,
						"same compactionId applies once and replay does not duplicate facts");
			});

			if (support.faultEnabled("lpush")) {
				String pendingId = compactionId + "-pending";
				ContextState pendingBase = store.readStrict(sessionId);
				ContextState pendingState = pendingBase.withCompaction(ContextTrigger.Level.L1, 0, 0,
						"l1_tool_result_clearing", 2001L);
				valid &= support.stage(dataset, "compaction_pending_reconcile", () -> {
					support.faults.failNext("lpush", eventsKey);
					boolean pending = support.check(dataset, "compaction_event_failure_is_real",
							expectFailure(() -> store.commitCompaction(sessionId, pendingId, pendingState,
									List.of(ledgerEvent(pendingId)), compactionEvent(pendingId))),
							"real LPUSH failure leaves durable pending evidence");
					support.faults.clear();
					pending &= support.check(dataset, "pending_persisted_before_reconcile",
							support.redis.opsForHash().get(contextKey, "pendingCompaction") != null,
							"pendingCompaction survives the failed event");
					pending &= support.check(dataset, "pending_reconcile_idempotent", store.reconcileCompaction(sessionId)
							&& support.redis.opsForHash().get(contextKey, "pendingCompaction") == null
							&& countById(store.events(sessionId), pendingId) == 1
							&& countByCommitEntry(store.contextLedger(sessionId), pendingId + ":main") == 1,
							"reconcile appends each missing fact once and clears pending");
					return pending;
				});
			}
			else {
				support.faultSkipped(dataset, "lpush");
			}

			valid &= support.stage(dataset, "compaction_conflict_and_bounded_ledger", () -> {
				ContextState conflictBase = store.readStrict(sessionId);
				store.recordTurn(sessionId, 7L, "model-a", 300, 0, 0, false, 3000L, 0, 0, 0);
				ContextStateStore.CompactionCommitStatus conflict = store.commitCompaction(sessionId,
						compactionId + "-conflict", conflictBase,
						List.of(ledgerEvent(compactionId + "-conflict")), compactionEvent(compactionId + "-conflict"));
				boolean bounded = support.check(dataset, "compaction_conflict_writes_no_fact",
						conflict == ContextStateStore.CompactionCommitStatus.CONFLICT
								&& countById(store.events(sessionId), compactionId + "-conflict") == 0,
						"version conflict cannot emit committed facts");
				for (int i = 0; i < 25; i++) {
					store.appendLedger(sessionId, ledgerEvent("bounded-ledger-" + i));
				}
				for (int i = 0; i < 205; i++) {
					store.appendEvent(sessionId, compactionEvent("bounded-event-" + i));
				}
				store.appendLedger(sessionId, ledgerEvent("bounded-ledger-0"));
				store.appendEvent(sessionId, compactionEvent("bounded-event-0"));
				List<Map<String, Object>> ledger = store.contextLedger(sessionId);
				List<Map<String, Object>> events = store.events(sessionId);
				bounded &= support.check(dataset, "context_ledger_capacity_and_dedup",
						ledger.size() == 20 && events.size() == 200
								&& countByCommitEntry(ledger, "bounded-ledger-0") == 1
								&& countById(events, "bounded-event-0") == 1,
						"reads remain bounded and duplicate IDs never grow the lists");
				dataset.attributes.put("boundedLedger", ledger.size());
				dataset.attributes.put("boundedEvents", events.size());
				return bounded;
			});
		}
		catch (RuntimeException ex) {
			support.faults.clear();
			throw ex;
		}
		valid &= support.check(dataset, "context_no_keyspace_scan",
				scanBefore == support.commandMetrics.keyspaceScanPages(),
				"context writes, CAS, reconciliation and bounded reads issue no SCAN");
		support.require(dataset, "context_compaction_commit_contract", valid,
				"strict fields/CAS/pending/bounded-idempotency assertions failed");
		dataset.state = "measured";
		}
	}

	private static Map<String, Object> ledgerEvent(String compactionId) {
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("commitEntryId", compactionId + ":main");
		event.put("level", "L1");
		event.put("atMs", 2000L);
		return event;
	}

	private static Map<String, Object> compactionEvent(String compactionId) {
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("eventId", compactionId);
		event.put("compactionId", compactionId);
		event.put("runId", "perf-context");
		event.put("atMs", 2000L);
		event.put("level", "L1");
		return event;
	}

	private static long countById(List<Map<String, Object>> events, String eventId) {
		return events.stream().filter(event -> eventId.equals(String.valueOf(event.get("eventId")))).count();
	}

	private static long countByCommitEntry(List<Map<String, Object>> events, String entryId) {
		return events.stream().filter(event -> entryId.equals(String.valueOf(event.get("commitEntryId")))).count();
	}

	private static boolean expectFailure(Runnable body) {
		try {
			body.run();
			return false;
		}
		catch (RuntimeException ex) {
			return true;
		}
	}

}

/** Large-result idempotency projection: metadata stays small while the ledger retains the real result. */
final class LedgerIdemLargeProjectionScenario implements ProductionScenario {

	private LeaseService leases;

	private RunStore store;

	@Override
	public String id() {
		return "ledger_idem_large_projection";
	}

	@Override
	public Manifest.Dataset generate(ProductionSupport support, Manifest.Dataset dataset) {
		String runId = dataset.sessionId + "-run";
		ProductionDomainSupport.ownRun(support, dataset, runId);
		dataset.attributes.put("runId", runId);
		this.leases = ProductionDomainSupport.lease(support, "ledger-projection-owner");
		this.store = new RunStore(support.redis, support.mapper, support.aiProperties, this.leases);
		LeaseService.AcquireResult acquisition = ProductionDomainSupport.acquire(support, store, leases, runId,
				Duration.ofSeconds(60));
		support.require(dataset, "ledger_projection_lease", acquisition.acquired(), "real fenced run lease acquired");
		support.identity(() -> store.save(ProductionDomainSupport.snapshot(support, dataset, runId, "RUNNING")));
		return dataset;
	}

	@Override
	public void measure(ProductionSupport support, Manifest.Dataset dataset) {
		String runId = String.valueOf(dataset.attributes.get("runId"));
		this.leases = ProductionDomainSupport.lease(support, "ledger-projection-owner");
		this.store = new RunStore(support.redis, support.mapper, support.aiProperties, this.leases);
		long scanBefore = support.commandMetrics.keyspaceScanPages();
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(support.cfg.tenantId,
				support.cfg.userId)) {
			boolean valid = true;
			try {
			valid &= support.stage(dataset, "large_result_idem_projection", () -> {
				String largeResult = "x".repeat(64 * 1024);
				for (int i = 0; i < 48; i++) {
					PendingToolCall pending = pending("call-" + i);
					support.identity(() -> store.appendLedger(runId, pending, largeResult));
				}
				Map<String, Integer> counts = store.ledgerCounts(runId);
				boolean projected = support.check(dataset, "ledger_projection_counts",
						counts.size() == 1 && counts.get("read_task") == 48 && store.ledgerCountSince(runId, 0L) == 48,
						"small metadata projection answers counts without replaying resultText");
				Map<Object, Object> raw = support.redis.opsForHash().entries(store.idemKey(runId));
				long metadataBytes = 0L;
				int metadataFields = 0;
				for (Object field : raw.keySet()) {
					if (String.valueOf(field).startsWith(RunStore.IDEM_META_FIELD_PREFIX)) {
						metadataFields++;
						metadataBytes += String.valueOf(raw.get(field)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
						projected &= !String.valueOf(raw.get(field)).contains("resultText");
					}
				}
				projected &= support.check(dataset, "large_result_not_in_projection",
						metadataFields == 48 && metadataBytes < 64 * 1024,
						"48 large results leave only small name/atMs/toolCallId projections");
				dataset.attributes.put("metadataBytes", metadataBytes);
				return projected;
			});
			valid &= support.stage(dataset, "idem_replay_and_duplicate_limit", () -> {
				Map<String, Object> replay = store.idemFor(runId, "call-0");
				boolean replayed = support.check(dataset, "idem_replay_real_result",
						replay != null && replay.get("resultText") != null
								&& replay.get("resultText").toString().length() == 64 * 1024,
						"full ledger still returns the real 64KiB result on demand");
				PendingToolCall duplicate = pending("call-0");
				support.identity(() -> store.appendLedger(runId, duplicate, "duplicate-must-not-index"));
				replayed &= support.check(dataset, "duplicate_idempotent_index",
						store.ledgerCounts(runId).get("read_task") == 49 && store.ledger(runId).size() == 49
								&& support.redis.opsForHash().size(store.idemKey(runId)) == 97
								&& store.idemFor(runId, "call-0").get("resultText").toString().length() == 64 * 1024,
						"48 original idem fields + 48 original ~meta fields + 1 new duplicate ~meta = 97; replay keeps the first fact");
				return replayed;
			});
		}
		catch (RuntimeException ex) {
			support.faults.clear();
			throw ex;
		}
		valid &= support.check(dataset, "ledger_projection_no_keyspace_scan",
				scanBefore == support.commandMetrics.keyspaceScanPages(), "projection and replay issue no SCAN");
		support.require(dataset, "ledger_large_projection_contract", valid,
				"large-result projection/replay assertions failed");
		dataset.state = "measured";
		}
	}

	private static PendingToolCall pending(String toolCallId) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(toolCallId);
		pending.setName("read_task");
		pending.setKind(PendingToolCall.KIND_BACKEND);
		pending.setArguments("{}");
		pending.setStatus(PendingToolCall.EXECUTED);
		pending.setOutcome("ok");
		pending.setExecuted(true);
		pending.setExecutedBy("ledger-projection-owner");
		return pending;
	}

}
