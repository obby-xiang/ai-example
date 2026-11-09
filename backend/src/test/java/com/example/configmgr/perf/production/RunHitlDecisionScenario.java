package com.example.configmgr.perf.production;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.LeaseService;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.perf.redis.Manifest;
import com.example.configmgr.perf.redis.RedisSlots;

final class RunHitlDecisionScenario implements ProductionScenario {

	private static final List<String> RUN_KEY_PREFIXES = List.of("ai:run:", "ai:runstate:", "ai:pending:", "ai:claim:",
			"ai:ledger:", "ai:idem:", "ai:seq:", "ai:xevents:", "ai:lease:run:", "ai:fence:run:", "ai:req:",
			"ai:decided:", "ai:lease:epoch:");

	private record Fixture(String runId, LeaseService lease, RunStore store, CancellationRegistry cancellations,
			LeaseService.AcquireResult acquisition) {
	}

	@Override
	public String id() {
		return "run_hitl_decision_claim_complete";
	}

	@Override
	public Manifest.Dataset generate(ProductionSupport support, Manifest.Dataset dataset) {
		for (String kind : List.of("read", "write", "race")) {
			String runId = dataset.sessionId + "-" + kind;
			dataset.runIds.add(runId);
			Fixture fixture = fixture(support, dataset, runId);
			support.identity(() -> {
				RunSnapshot snapshot = snapshot(support, dataset, runId);
				fixture.store.save(snapshot);
				PendingToolCall pending = pending(runId, PendingToolCall.PENDING);
				fixture.store.putPending(runId, pending);
				fixture.lease.release(fixture.acquisition.lease());
			});
			for (String prefix : RUN_KEY_PREFIXES) {
				support.own(dataset, RunStore.runScopedKey(prefix, support.cfg.tenantId, support.cfg.userId, runId));
			}
			support.own(dataset, RunStore.runScopedKey(CancellationRegistry.CANCEL_PREFIX,
					support.cfg.tenantId, support.cfg.userId, runId));
			support.own(dataset, com.example.configmgr.perf.redis.KeyShapes.sessionRunsKey(
					support.cfg.tenantId, support.cfg.userId, dataset.sessionId));
			support.own(dataset, com.example.configmgr.perf.redis.KeyShapes.sessionActiveKey(
					support.cfg.tenantId, support.cfg.userId, dataset.sessionId));
			support.own(dataset, com.example.configmgr.perf.redis.KeyShapes.sessionCurrentKey(
					support.cfg.tenantId, support.cfg.userId, dataset.sessionId));
			support.own(dataset, "ai:session:lock:" + support.cfg.tenantId + ":" + support.cfg.userId + ":"
					+ dataset.sessionId);
			support.require(dataset, "six_keys_same_slot_" + kind, RedisSlots.sameSlot(sixKeys(support, runId)),
					"pending/claim/fence/ledger/idem/cancel share one slot");
		}
		return dataset;
	}

	@Override
	public void measure(ProductionSupport support, Manifest.Dataset dataset) throws Exception {
		for (String kind : List.of("read", "write", "race")) {
			String runId = dataset.sessionId + "-" + kind;
			Fixture fixture = fixture(support, dataset, runId);
			support.stage(dataset, kind + "_concurrent_decision_and_claim", () -> {
				try {
					concurrentApprove(support, fixture, dataset);
				}
				catch (Exception ex) {
					throw new IllegalStateException(ex);
				}
				RunStore.ClaimResult claim = support.identity(() -> fixture.store.tryClaimExecution(runId,
						"call-" + runId, fixture.lease.instanceId()));
				support.require(dataset, kind + "_single_claim_winner", claim.isWon(),
						"outcome=" + claim.outcome() + " epoch=" + claim.epoch() + " fence=" + claim.fence());
				dataset.attributes.put(kind + "ClaimEpoch", claim.epoch());
			});
			if ("read".equals(kind)) {
				readCancelDoesNotWrite(support, dataset, fixture);
			}
			else if ("write".equals(kind)) {
				nonReadKeepsResult(support, dataset, fixture, false);
			}
			else {
				raceCancelAndComplete(support, dataset, fixture);
			}
			oldEpochCannotAdvance(support, dataset, fixture);
		}
		dataset.state = "measured";
	}

	private void readCancelDoesNotWrite(ProductionSupport support, Manifest.Dataset dataset, Fixture fixture) {
		String runId = fixture.runId;
		long epoch = ((Number) dataset.attributes.get("readClaimEpoch")).longValue();
		support.stage(dataset, "read_cancel_completion_atomicity", () -> support.identity(() -> {
			PendingToolCall pending = fixture.store.pending(runId, "call-" + runId);
			String before = support.redis.opsForHash().get(fixture.store.pendingKey(runId), "call-" + runId).toString();
			fixture.cancellations.cancel(runId, "perf-read-cancel");
			PendingToolCall completed = completed(pending, epoch, fixture.lease.instanceId(), "READ-REAL-RESULT");
			boolean wrote = fixture.store.completeExecutionWithLedger(runId, completed, "READ-REAL-RESULT", epoch,
					fixture.lease.instanceId(), true);
			support.require(dataset, "read_cancel_returns_false", !wrote, "READ completion observes cancellation");
			String after = support.redis.opsForHash().get(fixture.store.pendingKey(runId), "call-" + runId).toString();
			support.require(dataset, "read_cancel_pending_unchanged", before.equals(after),
					"the entire pending value is unchanged");
			support.require(dataset, "read_cancel_no_ledger_or_idem",
					!support.redis.hasKey(fixture.store.ledgerKey(runId)) && !support.redis.hasKey(fixture.store.idemKey(runId)),
					"cancelled READ writes neither ledger nor idempotency index");
		}));
	}

	private void nonReadKeepsResult(ProductionSupport support, Manifest.Dataset dataset, Fixture fixture,
			boolean concurrent) {
		String runId = fixture.runId;
		long epoch = ((Number) dataset.attributes.get("writeClaimEpoch")).longValue();
		support.stage(dataset, "non_read_cancel_completion_atomicity", () -> support.identity(() -> {
			PendingToolCall pending = fixture.store.pending(runId, "call-" + runId);
			fixture.cancellations.cancel(runId, "perf-non-read-cancel");
			PendingToolCall completed = completed(pending, epoch, fixture.lease.instanceId(), "NON-READ-REAL-RESULT");
			boolean wrote = fixture.store.completeExecutionWithLedger(runId, completed, "NON-READ-REAL-RESULT", epoch,
					fixture.lease.instanceId(), false);
			support.require(dataset, "non_read_completion_commits", wrote,
					"non-READ completion preserves the real side-effect result even when cancelled");
			Map<String, Object> ledger = fixture.store.ledgerFor(runId, "call-" + runId);
			support.require(dataset, "non_read_real_result_in_ledger",
					ledger != null && "NON-READ-REAL-RESULT".equals(ledger.get("resultText")),
					"ledger contains the actual tool result, not a synthetic marker");
			PendingToolCall stored = fixture.store.pending(runId, "call-" + runId);
			support.require(dataset, "non_read_pending_result", stored != null
					&& "NON-READ-REAL-RESULT".equals(stored.getResultText()), "pending retains the real result text");
		}));
	}

	private void raceCancelAndComplete(ProductionSupport support, Manifest.Dataset dataset, Fixture fixture)
			throws Exception {
		String runId = fixture.runId;
		long epoch = ((Number) dataset.attributes.get("raceClaimEpoch")).longValue();
		support.stage(dataset, "read_cancel_complete_race", () -> support.identity(() -> {
			ExecutorService pool = Executors.newFixedThreadPool(2);
			try {
				CyclicBarrier barrier = new CyclicBarrier(2);
				Future<Boolean> completion = pool.submit(() -> support.identity(() -> {
					PendingToolCall pending = fixture.store.pending(runId, "call-" + runId);
					PendingToolCall completed = completed(pending, epoch, fixture.lease.instanceId(), "RACE-REAL-RESULT");
					await(barrier);
					return fixture.store.completeExecutionWithLedger(runId, completed, "RACE-REAL-RESULT", epoch,
							fixture.lease.instanceId(), true);
				}));
				Future<?> cancel = pool.submit(() -> support.identity(() -> {
					await(barrier);
					fixture.cancellations.cancel(runId, "perf-race");
					return null;
				}));
				cancel.get();
				boolean wrote = completion.get();
				Map<String, Object> ledger = fixture.store.ledgerFor(runId, "call-" + runId);
				if (wrote) {
					support.require(dataset, "race_complete_is_whole", ledger != null
							&& "RACE-REAL-RESULT".equals(ledger.get("resultText")),
							"when completion wins, result and idempotency evidence are complete");
				}
				else {
					support.require(dataset, "race_cancel_is_whole", ledger == null
							&& !support.redis.hasKey(fixture.store.idemKey(runId)),
							"when cancellation wins, neither ledger nor idem is written");
				}
				dataset.attributes.put("raceCompletionWrote", wrote);
			}
			catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
			finally {
				pool.shutdownNow();
			}
		}));
	}

	private void oldEpochCannotAdvance(ProductionSupport support, Manifest.Dataset dataset, Fixture fixture) {
		String runId = fixture.runId;
		LeaseService newService = lease(support, "new-owner-" + fixture.runId);
		RunStore newStore = new RunStore(support.redis, support.mapper, support.aiProperties, newService);
		support.stage(dataset, fixture.runId + "_old_epoch_fence", () -> support.identity(() -> {
			long oldEpoch = fixture.acquisition.lease().epoch();
			fixture.lease.release(fixture.acquisition.lease());
			LeaseService.AcquireResult newer = newService.acquireFenced(fixture.store.runLeaseKey(runId),
					fixture.store.fenceKey(runId), Duration.ofSeconds(30));
			support.require(dataset, fixture.runId + "_new_epoch_acquired",
					newer.acquired() && newer.lease().epoch() > oldEpoch, "new owner advances the epoch");
			RunStore.ClaimResult stale = fixture.store.tryClaimExecution(runId, "call-" + runId,
					fixture.lease.instanceId());
			support.require(dataset, fixture.runId + "_old_epoch_claim_rejected",
					stale.outcome() == RunStore.ClaimOutcome.STALE_FENCE,
					"old epoch cannot advance claim/fence: " + stale.outcome());
			newService.release(newer.lease());
		}));
	}

	private static void concurrentApprove(ProductionSupport support, Fixture fixture, Manifest.Dataset dataset)
			throws Exception {
		String runId = fixture.runId;
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			CyclicBarrier barrier = new CyclicBarrier(2);
			List<Future<RunStore.DecisionClaim>> futures = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				futures.add(pool.submit(() -> {
					PendingToolCall approved = pending(runId, PendingToolCall.APPROVED);
					await(barrier);
					return support.identity(() -> fixture.store.tryApplyDecision(runId, approved));
				}));
			}
			int winners = 0;
			for (Future<RunStore.DecisionClaim> future : futures) {
				if (future.get().won()) {
					winners++;
				}
			}
			support.require(dataset, runId + "_one_approval_winner", winners == 1, "approval winners=" + winners);
		}
		finally {
			pool.shutdownNow();
		}
	}

	private static Fixture fixture(ProductionSupport support, Manifest.Dataset dataset, String runId) {
		LeaseService lease = lease(support, "owner-" + runId);
		RunStore store = new RunStore(support.redis, support.mapper, support.aiProperties, lease);
		CancellationRegistry cancellations = new CancellationRegistry(support.redis, support.aiProperties);
		LeaseService.AcquireResult acquisition = support.identity(() -> lease.acquireFenced(store.runLeaseKey(runId),
				store.fenceKey(runId), Duration.ofSeconds(60)));
		if (!acquisition.acquired()) {
			throw new IllegalStateException("unable to acquire production run lease " + runId);
		}
		return new Fixture(runId, lease, store, cancellations, acquisition);
	}

	private static LeaseService lease(ProductionSupport support, String owner) {
		return new LeaseService(support.redis, support.aiProperties, (task, interval) -> () -> { }, owner);
	}

	private static RunSnapshot snapshot(ProductionSupport support, Manifest.Dataset dataset, String runId) {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(runId);
		snapshot.setSessionId(dataset.sessionId);
		snapshot.setTenantId(support.cfg.tenantId);
		snapshot.setUserId(support.cfg.userId);
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		snapshot.setCreatedAtMs(System.currentTimeMillis());
		snapshot.setUpdatedAtMs(System.currentTimeMillis());
		snapshot.freezeParams(new RunSnapshot.FrozenParams(null, null, null, true));
		return snapshot;
	}

	private static PendingToolCall pending(String runId, String status) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId("call-" + runId);
		pending.setName("read_task");
		pending.setKind(PendingToolCall.KIND_CONFIRM);
		pending.setArguments("{}");
		pending.setStatus(status);
		return pending;
	}

	private static PendingToolCall completed(PendingToolCall pending, long epoch, String owner, String result) {
		pending.setStatus(PendingToolCall.EXECUTED);
		pending.setOutcome("ok");
		pending.setExecuted(true);
		pending.setExecutedBy(owner);
		pending.setClaimedEpoch(epoch);
		pending.setExecutedAtMs(System.currentTimeMillis());
		pending.setResultText(result);
		return pending;
	}

	private static void await(CyclicBarrier barrier) {
		try {
			barrier.await();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static List<String> sixKeys(ProductionSupport support, String runId) {
		long tenant = support.cfg.tenantId;
		long user = support.cfg.userId;
		return List.of(RunStore.runScopedKey("ai:pending:", tenant, user, runId),
				RunStore.runScopedKey("ai:claim:", tenant, user, runId),
				RunStore.runScopedKey("ai:fence:run:", tenant, user, runId),
				RunStore.runScopedKey("ai:ledger:", tenant, user, runId),
				RunStore.runScopedKey("ai:idem:", tenant, user, runId),
				RunStore.runScopedKey(CancellationRegistry.CANCEL_PREFIX, tenant, user, runId));
	}

}
