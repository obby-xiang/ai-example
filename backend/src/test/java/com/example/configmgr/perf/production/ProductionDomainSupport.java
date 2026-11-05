package com.example.configmgr.perf.production;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.LeaseService;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.perf.redis.Manifest;

final class ProductionDomainSupport {

	private static final List<String> RUN_KEY_PREFIXES = List.of("ai:run:", "ai:runstate:", "ai:pending:", "ai:claim:",
			"ai:ledger:", "ai:idem:", "ai:seq:", "ai:xevents:", "ai:lease:run:", "ai:fence:run:", "ai:req:",
			"ai:decided:", "ai:lease:epoch:", "ai:xack:", "ai:xtrim:");

	private ProductionDomainSupport() {
	}

	static void ownRun(ProductionSupport support, Manifest.Dataset dataset, String runId) {
		if (!dataset.runIds.contains(runId)) {
			dataset.runIds.add(runId);
		}
		for (String prefix : RUN_KEY_PREFIXES) {
			support.own(dataset, RunStore.runScopedKey(prefix, support.cfg.tenantId, support.cfg.userId, runId));
		}
		support.own(dataset, RunStore.runScopedKey(CancellationRegistry.CANCEL_PREFIX,
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

	static LeaseService.AcquireResult acquire(ProductionSupport support, RunStore store, LeaseService leases,
			String runId, Duration ttl) {
		return support.identity(() -> leases.acquireFenced(store.runLeaseKey(runId), store.fenceKey(runId), ttl));
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

	static void await(BooleanSupplier condition, long timeoutMs, String detail) {
		long deadline = System.nanoTime() + Duration.ofMillis(timeoutMs).toNanos();
		while (System.nanoTime() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			sleep(5L);
		}
		throw new IllegalStateException("timed out: " + detail);
	}

	static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	static byte[] bytes(String value) {
		return value.getBytes(StandardCharsets.UTF_8);
	}
}
