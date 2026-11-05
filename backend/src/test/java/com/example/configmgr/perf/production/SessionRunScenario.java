package com.example.configmgr.perf.production;

import java.time.Duration;
import java.util.List;

import com.example.configmgr.ai.run.ClientRequestRegistry;
import com.example.configmgr.ai.run.LeaseService;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.session.SessionGate;
import com.example.configmgr.perf.redis.Manifest;

final class SessionRunScenario {

	private SessionRunScenario() {
	}

	static final class RegistrationAndGateToken implements ProductionScenario {
		@Override
		public String id() {
			return "session_registration_and_gate_token";
		}

		@Override
		public Manifest.Dataset generate(ProductionSupport support, Manifest.Dataset dataset) {
			support.own(dataset, lockKey(support, dataset));
			return dataset;
		}

		@Override
		public void measure(ProductionSupport support, Manifest.Dataset dataset) {
			SessionGate first = new SessionGate(support.redis, support.aiProperties);
			SessionGate second = new SessionGate(support.redis, support.aiProperties);
			String lockKey = lockKey(support, dataset);
			try {
				support.stage(dataset, "gate_token_takeover_and_strict_read", () -> support.identity(() -> {
					String runId = dataset.sessionId + "-run";
					support.require(dataset, "gate_initial_acquire", first.acquire(dataset.sessionId, runId, "old-token") == null,
							"initial token acquires the real session gate");
					SessionGate.Busy busy = second.acquire(dataset.sessionId, dataset.sessionId + "-other", "other-token");
					support.require(dataset, "gate_other_run_rejected", busy != null && runId.equals(busy.runId()),
							"another run is rejected with the current runId");
					support.require(dataset, "gate_same_run_new_token", first.acquire(dataset.sessionId, runId, "new-token") == null,
							"same run takes over with a new token");
					support.require(dataset, "gate_old_token_cannot_release", !first.release(dataset.sessionId, runId, "old-token")
							&& support.redis.hasKey(lockKey), "late old-token release preserves the new token lock");
					support.require(dataset, "gate_strict_reads_current_run", runId.equals(second.currentRunIdStrict(dataset.sessionId)),
							"strict read resolves the real current run");
					if (!support.faultEnabled("get")) {
						support.faultSkipped(dataset, "get");
					}
					else {
						support.faults.failNext("get", lockKey);
						support.require(dataset, "gate_strict_failure_not_absent", ProductionScenarioSupport.expectFailure(
								() -> second.currentRunIdStrict(dataset.sessionId)),
								"strict read propagates Redis failure instead of returning null");
						support.faults.clear();
					}
					support.require(dataset, "gate_new_token_releases", first.release(dataset.sessionId, runId, "new-token")
							&& second.currentRunIdStrict(dataset.sessionId) == null,
							"new token releases exactly its own lock");
				}));
				dataset.state = "measured";
			}
			finally {
				ProductionScenarioSupport.shutdownGate(first);
				ProductionScenarioSupport.shutdownGate(second);
			}
		}

		private static String lockKey(ProductionSupport support, Manifest.Dataset dataset) {
			return "ai:session:lock:" + support.cfg.tenantId + ":" + support.cfg.userId + ":" + dataset.sessionId;
		}
	}

	static final class SnapshotHotSave implements ProductionScenario {
		@Override
		public String id() {
			return "run_snapshot_hot_save";
		}

		@Override
		public Manifest.Dataset generate(ProductionSupport support, Manifest.Dataset dataset) {
			ProductionScenarioSupport.ownRun(support, dataset, dataset.sessionId + "-run");
			support.own(dataset, com.example.configmgr.perf.redis.KeyShapes.sessionRunsKey(
					support.cfg.tenantId, support.cfg.userId, dataset.sessionId));
			support.own(dataset, com.example.configmgr.perf.redis.KeyShapes.sessionActiveKey(
					support.cfg.tenantId, support.cfg.userId, dataset.sessionId));
			support.own(dataset, com.example.configmgr.perf.redis.KeyShapes.sessionCurrentKey(
					support.cfg.tenantId, support.cfg.userId, dataset.sessionId));
			support.own(dataset, "ai:session:lock:" + support.cfg.tenantId + ":" + support.cfg.userId + ":" + dataset.sessionId);
			return dataset;
		}

		@Override
		public void measure(ProductionSupport support, Manifest.Dataset dataset) {
			String runId = dataset.sessionId + "-run";
			RunStore store = new RunStore(support.redis, support.mapper, support.aiProperties,
					ProductionScenarioSupport.lease(support, "snapshot-hot"));
			support.stage(dataset, "snapshot_hot_save_128", () -> support.identity(() -> {
				RunSnapshot snapshot = ProductionScenarioSupport.snapshot(support, dataset, runId, RunSnapshot.RUNNING);
				for (int i = 0; i < 128; i++) {
					snapshot.setUpdatedAtMs(System.currentTimeMillis());
					store.save(snapshot);
				}
				RunSnapshot hot = store.get(runId);
				support.require(dataset, "snapshot_hot_latest_running", hot != null && RunSnapshot.RUNNING.equals(hot.getStatus()),
						"hot saves preserve the latest RUNNING snapshot");
				snapshot.setStatus(RunSnapshot.DONE);
				snapshot.setUpdatedAtMs(System.currentTimeMillis());
				store.save(snapshot);
			}));
			support.require(dataset, "snapshot_terminal_persisted", support.identity(() -> RunSnapshot.DONE.equals(store.get(runId).getStatus())),
					"the final DONE snapshot is authoritative");
			support.require(dataset, "snapshot_terminal_recovery_removed", support.redis.opsForZSet().score(
					RunStore.RECOVERY_DUE_INDEX, support.cfg.tenantId + "/" + support.cfg.userId + "/" + runId) == null,
					"terminal run is absent from recovery-due");
			dataset.state = "measured";
		}
	}

	static final class RecoveryIndexLarge implements ProductionScenario {
		@Override
		public String id() {
			return "run_recovery_index_large";
		}

		@Override
		public Manifest.Dataset generate(ProductionSupport support, Manifest.Dataset dataset) {
			support.own(dataset, com.example.configmgr.perf.redis.KeyShapes.sessionRunsKey(
					support.cfg.tenantId, support.cfg.userId, dataset.sessionId));
			support.own(dataset, com.example.configmgr.perf.redis.KeyShapes.sessionActiveKey(
					support.cfg.tenantId, support.cfg.userId, dataset.sessionId));
			support.own(dataset, com.example.configmgr.perf.redis.KeyShapes.sessionCurrentKey(
					support.cfg.tenantId, support.cfg.userId, dataset.sessionId));
			support.own(dataset, "ai:session:lock:" + support.cfg.tenantId + ":" + support.cfg.userId + ":" + dataset.sessionId);
			RunStore store = new RunStore(support.redis, support.mapper, support.aiProperties,
					ProductionScenarioSupport.lease(support, "recovery-large"));
			support.stage(dataset, "recovery_seed_130", () -> support.identity(() -> {
				for (int i = 0; i < 130; i++) {
					String runId = dataset.sessionId + "-run-" + i;
					ProductionScenarioSupport.ownRun(support, dataset, runId);
					store.save(ProductionScenarioSupport.snapshot(support, dataset, runId, RunSnapshot.SUSPENDED));
				}
			}));
			return dataset;
		}

		@Override
		public void measure(ProductionSupport support, Manifest.Dataset dataset) {
			RunStore store = new RunStore(support.redis, support.mapper, support.aiProperties,
					ProductionScenarioSupport.lease(support, "recovery-large"));
			long now = System.currentTimeMillis() + 3_600_000L;
			support.require(dataset, "recovery_first_page_exact_64", support.identity(() -> store.recoveryDueRecords(now, 64)).size() == 64,
					"first due page is exactly bounded at 64 without a global member scan");
			boolean fault = support.faultEnabled("zrem");
			if (!fault) {
				support.faultSkipped(dataset, "zrem");
			}
			else {
				support.faults.failNext("zrem", RunStore.RECOVERY_DUE_INDEX);
			}
			int first = support.stage(dataset, "recovery_first_prune_with_optional_fault", () -> store.pruneRunIndex());
			support.faults.clear();
			dataset.attributes.put("recoveryFirstPruneResult", first);
			dataset.attributes.put("recoveryFirstFaultConsumed", fault);
			int remainingAfterFirst = support.identity(() -> store.recoveryDueRecords(now, 256)).size();
			dataset.attributes.put("recoveryDueAfterFirstPrune", remainingAfterFirst);
			support.require(dataset, "recovery_first_prune_is_real", first >= 0 && remainingAfterFirst > 0,
					"the first prune touches real candidates instead of an already-empty index");
			if (fault) {
				support.require(dataset, "recovery_first_zrem_failure_consumed", first == 0 && remainingAfterFirst == 130,
						"the injected first ZREM failure is consumed while all 130 candidates remain retryable");
			}
			support.stage(dataset, "recovery_mark_terminal", () -> support.identity(() -> {
				for (String runId : dataset.runIds) {
					RunSnapshot snapshot = store.get(runId);
					snapshot.setStatus(RunSnapshot.DONE);
					store.save(snapshot);
				}
			}));
			int rounds = 0;
			while (!support.identity(() -> store.recoveryDueRecords(now, 256)).isEmpty() && rounds < 6) {
				store.pruneRunIndex();
				rounds++;
			}
			dataset.attributes.put("recoveryPruneRoundsAfterFault", rounds);
			support.require(dataset, "recovery_large_all_converges", support.identity(() -> store.recoveryDueRecords(now, 256)).isEmpty(),
					"all 130 candidates converge after the first real removal attempt");
			support.require(dataset, "recovery_terminal_snapshots_retained", dataset.runIds.stream().allMatch(runId ->
					support.identity(() -> RunSnapshot.DONE.equals(store.get(runId).getStatus()))),
					"index cleanup does not delete terminal snapshots");
			dataset.state = "measured";
		}
	}

	static final class ClientRequestSweepLarge implements ProductionScenario {
		@Override
		public String id() {
			return "client_request_sweep_large";
		}

		@Override
		public Manifest.Dataset generate(ProductionSupport support, Manifest.Dataset dataset) {
			String key = support.identity(() -> ClientRequestRegistry.sessionKey(dataset.sessionId));
			long expiredAt = System.currentTimeMillis() - Duration.ofMinutes(30).toMillis();
			support.stage(dataset, "client_request_seed_300_expired", () -> {
				for (int i = 0; i < 300; i++) {
					String field = "expired-" + i;
					support.redis.opsForHash().put(key, field, "DONE:run-" + i + ":" + expiredAt);
					support.ownHashField(dataset, key, field);
				}
				for (String field : List.of("active-run", "cancelled-tombstone", "fresh-done")) {
					support.ownHashField(dataset, key, field);
				}
				support.ownHashField(dataset, key, ClientRequestRegistry.SWEEP_CURSOR_FIELD);
				support.redis.opsForHash().put(key, "active-run", "RUN:active-run");
				support.redis.opsForHash().put(key, "cancelled-tombstone", "CANCELLED:cancelled-run");
				support.redis.opsForHash().put(key, "fresh-done", "DONE:fresh-run:" + System.currentTimeMillis());
			});
			return dataset;
		}

		@Override
		public void measure(ProductionSupport support, Manifest.Dataset dataset) {
			ClientRequestRegistry registry = new ClientRequestRegistry(support.redis, support.aiProperties);
			String key = support.identity(() -> ClientRequestRegistry.sessionKey(dataset.sessionId));
			support.stage(dataset, "client_request_bounded_sweep", () -> support.identity(() -> {
				for (int i = 0; i < 12; i++) {
					support.ownHashField(dataset, key, "new-request-" + i);
					registry.claim(dataset.sessionId, "new-request-" + i, "new-run-" + i);
				}
			}));
			for (int i = 0; i < 300; i++) {
				String field = "expired-" + i;
				support.require(dataset, "client_request_expired_removed_" + i,
						!support.redis.opsForHash().hasKey(key, field), "expired DONE field is reclaimed: " + field);
			}
			support.require(dataset, "client_request_active_and_cancelled_preserved",
					"RUN:active-run".equals(support.redis.opsForHash().get(key, "active-run"))
							&& "CANCELLED:cancelled-run".equals(support.redis.opsForHash().get(key, "cancelled-tombstone")),
					"RUN and CANCELLED are never reclaimed by DONE sweeping");
			support.require(dataset, "client_request_fresh_done_preserved",
					support.redis.opsForHash().hasKey(key, "fresh-done"), "fresh DONE remains within retention");
			support.require(dataset, "client_request_cursor_finished",
					!support.redis.opsForHash().hasKey(key, ClientRequestRegistry.SWEEP_CURSOR_FIELD),
					"cursor is removed after the full bounded sweep");
			dataset.state = "measured";
		}
	}

	private static final class ProductionScenarioSupport {
		private ProductionScenarioSupport() {
		}

		static void ownRun(ProductionSupport support, Manifest.Dataset dataset, String runId) {
			if (!dataset.runIds.contains(runId)) {
				dataset.runIds.add(runId);
			}
			for (String prefix : List.of("ai:run:", "ai:runstate:", "ai:pending:", "ai:claim:", "ai:ledger:",
					"ai:idem:", "ai:seq:", "ai:xevents:", "ai:lease:run:", "ai:fence:run:", "ai:req:",
					"ai:decided:", "ai:lease:epoch:", "ai:xack:", "ai:xtrim:")) {
				support.own(dataset, RunStore.runScopedKey(prefix, support.cfg.tenantId, support.cfg.userId, runId));
			}
			support.own(dataset, RunStore.runScopedKey(com.example.configmgr.ai.run.CancellationRegistry.CANCEL_PREFIX,
					support.cfg.tenantId, support.cfg.userId, runId));
		}

		static RunSnapshot snapshot(ProductionSupport support, Manifest.Dataset dataset, String runId, String status) {
			long now = System.currentTimeMillis();
			RunSnapshot snapshot = new RunSnapshot();
			snapshot.setRunId(runId);
			snapshot.setSessionId(dataset.sessionId);
			snapshot.setTenantId(support.cfg.tenantId);
			snapshot.setUserId(support.cfg.userId);
			snapshot.setStatus(status);
			snapshot.setCreatedAtMs(now);
			snapshot.setUpdatedAtMs(now);
			snapshot.setTurnStartedAtMs(now);
			snapshot.setRequestText("perf-" + runId);
			snapshot.setClientRequestId("creq-" + runId);
			snapshot.freezeParams(new RunSnapshot.FrozenParams(null, null, null, true));
			return snapshot;
		}

		static LeaseService lease(ProductionSupport support, String owner) {
			return new LeaseService(support.redis, support.aiProperties, (task, interval) -> () -> { }, owner);
		}

		static void shutdownGate(SessionGate gate) {
			try {
				java.lang.reflect.Method method = SessionGate.class.getDeclaredMethod("shutdown");
				method.setAccessible(true);
				method.invoke(gate);
			}
			catch (Exception ex) {
				throw new IllegalStateException("unable to stop session gate watchdog", ex);
			}
		}

		static boolean expectFailure(Runnable body) {
			try {
				body.run();
				return false;
			}
			catch (RuntimeException ex) {
				return true;
			}
		}
	}
}
