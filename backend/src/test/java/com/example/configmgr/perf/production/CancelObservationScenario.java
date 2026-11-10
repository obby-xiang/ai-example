package com.example.configmgr.perf.production;

import com.example.configmgr.ai.observe.SettlementObservationStore;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.perf.redis.Manifest;
import com.example.configmgr.ai.tenant.RequestContextUtils;

import java.util.concurrent.atomic.AtomicInteger;

/** Cancellation authority/lifecycle and settlement-observation ownership/identity. */
final class CancelObservationScenario implements ProductionScenario {

	@Override
	public String id() {
		return "cancel_and_observation_lifecycle";
	}

	@Override
	public Manifest.Dataset generate(ProductionSupport support, Manifest.Dataset dataset) {
		String runId = dataset.sessionId + "-run";
		ProductionDomainSupport.ownRun(support, dataset, runId);
		dataset.attributes.put("runId", runId);
		dataset.attributes.put("toolCallId", "call-observation");
		String observationKey = support.identity(() -> new SettlementObservationStore(support.redis, support.aiProperties)
				.observedKey(runId));
		support.own(dataset, observationKey);
		dataset.attributes.put("observationKey", observationKey);
		dataset.attributes.put("productionFixesAppliedAwaitingVerification", java.util.List.of(
				java.util.Map.of("id", "late_release_preserves_new_owner",
						"reason", "SettlementObservationStore.release now compares the claim handle token before HDEL, so a stale owner cannot delete the newer owner slot.")));
		return dataset;
	}

	@Override
	public void measure(ProductionSupport support, Manifest.Dataset dataset) {
		String runId = String.valueOf(dataset.attributes.get("runId"));
		String toolCallId = String.valueOf(dataset.attributes.get("toolCallId"));
		String observationKey = String.valueOf(dataset.attributes.get("observationKey"));
		long scanBefore = support.commandMetrics.keyspaceScanPages();
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(support.cfg.tenantId,
				support.cfg.userId)) {
			boolean valid = true;
			try {
			valid &= support.stage(dataset, "cancel_registration_authority_and_unregister", () -> {
				CancellationRegistry cancellations = new CancellationRegistry(support.redis, support.aiProperties);
				cancellations.register(runId);
				AtomicInteger signals = new AtomicInteger();
				AutoCloseable listener = cancellations.onCancel(runId, signals::incrementAndGet);
				CancellationRegistry.CancelResult result = cancellations.cancel(runId, "perf-cancel");
				boolean current = support.check(dataset, "cancel_redis_and_listener",
						result.redisWritten() && result.wokeInProcess() && signals.get() == 1
								&& cancellations.cancelState(runId) == CancellationRegistry.CancelState.CANCELLED
								&& support.redis.hasKey(CancellationRegistry.cancelKey(runId)),
						"cancel writes the authoritative Redis flag and wakes the registered listener once");
				dataset.attributes.put("cancelActualKey", CancellationRegistry.cancelKey(runId));
				current &= support.check(dataset, "cancel_key_identity",
						CancellationRegistry.cancelKey(runId).contains("{" + support.cfg.tenantId + ":"
								+ support.cfg.userId + ":run:"),
						"cancel key carries tenant/user/run identity");
				try {
					listener.close();
				}
				catch (Exception ex) {
					throw new IllegalStateException(ex);
				}
				cancellations.unregister(runId);
				current &= support.check(dataset, "cancel_unregister_local_only",
						cancellations.registeredRuns() == 0 && support.redis.hasKey(CancellationRegistry.cancelKey(runId)),
						"unregister clears local registration while the durable cancel fact remains");
				return current;
			});

			if (support.faultEnabled("get")) {
				valid &= support.check(dataset, "cancel_read_failure_is_unknown", expectUnknown(support, runId),
						"read failure must be UNKNOWN, not NOT_CANCELLED");
				support.faults.clear();
			}
			else {
				support.faultSkipped(dataset, "get");
			}

			valid &= support.stage(dataset, "observation_first_claim_and_owner_release", () -> {
				SettlementObservationStore observations = new SettlementObservationStore(support.redis,
						support.aiProperties);
				SettlementObservationStore.ClaimHandle first = observations.claim(runId, toolCallId);
				SettlementObservationStore.ClaimHandle duplicate = observations.claim(runId, toolCallId);
				boolean ownership = support.check(dataset, "observation_first_write_wins",
						first.claimed() && !duplicate.claimed()
								&& support.redis.opsForHash().hasKey(observationKey, toolCallId),
						"the first persistent observation wins and duplicate claims are suppressed");
				support.redis.delete(observationKey);
				SettlementObservationStore.ClaimHandle nextOwner = observations.claim(runId, toolCallId);
				ownership &= support.check(dataset, "observation_new_generation_exists", nextOwner.claimed(),
						"a later owner creates a new observation generation");
				observations.release(first);
				ownership &= support.check(dataset, "late_release_preserves_new_owner",
						support.redis.opsForHash().hasKey(observationKey, toolCallId),
						"release of the first handle must not delete the newer owner");
				return ownership;
			});
		}
		catch (RuntimeException ex) {
			support.faults.clear();
			throw ex;
		}
		valid &= support.check(dataset, "cancel_observation_no_keyspace_scan",
				scanBefore == support.commandMetrics.keyspaceScanPages(),
				"cancel and observation lifecycle uses single-key commands only");
		support.require(dataset, "cancel_observation_contract", valid,
				"cancel/observation ownership assertions failed");
		dataset.state = "measured";
		}
	}

	private static boolean expectUnknown(ProductionSupport support, String runId) {
		CancellationRegistry cancellations = new CancellationRegistry(support.redis, support.aiProperties);
		support.faults.failNext("get", CancellationRegistry.cancelKey(runId));
		return cancellations.cancelState(runId) == CancellationRegistry.CancelState.UNKNOWN;
	}

}
