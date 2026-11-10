package com.example.configmgr.perf.production;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.gate.ToolResourceLocks;
import com.example.configmgr.ai.run.LeaseKeys;
import com.example.configmgr.ai.run.LeaseService;
import com.example.configmgr.ai.run.ResumeService;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.StartupResumeRunner;
import com.example.configmgr.perf.redis.Manifest;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

final class ResourceLockTwoPhaseReleaseScenario implements ProductionScenario {

	@Override
	public String id() {
		return "resource_lock_two_phase_release";
	}

	@Override
	public Manifest.Dataset generate(ProductionSupport support, Manifest.Dataset dataset) {
		long taskId = support.jobId(dataset, 1);
		dataset.attributes.put("taskId", taskId);
		support.own(dataset, LeaseKeys.task(taskId));
		support.own(dataset, LeaseKeys.job(taskId, "EXPORT"));
		support.own(dataset, LeaseKeys.job(taskId, "PUBLISH"));
		P1ScenarioSupport.ownGlobalEpoch(support, dataset);
		return dataset;
	}

	@Override
	public void measure(ProductionSupport support, Manifest.Dataset dataset) {
		long scansBefore = support.commandMetrics.keyspaceScanPages();
		long taskId = ((Number) dataset.attributes.get("taskId")).longValue();
		String taskKey = LeaseKeys.task(taskId);
		String jobKey = LeaseKeys.job(taskId, "EXPORT");
		AiProperties properties = new AiProperties();
		properties.getLease().setResourceTtl(Duration.ofSeconds(2L));
		LeaseService leasesA = P1ScenarioSupport.lease(support, properties, dataset.sessionId + "-owner-a");
		LeaseService leasesB = P1ScenarioSupport.lease(support, properties, dataset.sessionId + "-owner-b");
		LeaseService jobHolder = P1ScenarioSupport.lease(support, properties, dataset.sessionId + "-job-holder");
		ToolResourceLocks locksA = new ToolResourceLocks(leasesA, properties);
		ToolResourceLocks locksB = new ToolResourceLocks(leasesB, properties);
		try {
			P1ScenarioSupport.stage(support, dataset, "two_phase_reentry_and_contention", () -> {
				ToolResourceLocks.LockHandle first = locksA.acquire(taskId, "EXPORT", "owner-a");
				support.require(dataset, "both_phases_created", first.outcome() == ToolResourceLocks.LockOutcome.ACQUIRED
						&& first.created().size() == 2, "task then job locks are both newly created");
				ToolResourceLocks.LockHandle reentrant = locksA.acquire(taskId, "EXPORT", "owner-a");
				support.require(dataset, "reentrant_has_no_release_right", reentrant.created().isEmpty(),
						"same-owner reentry does not own either creation");
				reentrant.close();
				support.require(dataset, "reentrant_close_preserves_creator", support.redis.hasKey(taskKey)
						&& support.redis.hasKey(jobKey), "the creator's two Redis leases remain present");
				ToolResourceLocks.LockHandle contender = locksB.acquire(taskId, "EXPORT", "owner-b");
				support.require(dataset, "other_owner_is_blocked", contender.heldElsewhere(),
						"another owner cannot enter the same task/job critical section");
				contender.close();
				LeaseService.AcquireResult publish = leasesB.acquire(LeaseKeys.job(taskId, "PUBLISH"),
						properties.getLease().getResourceTtl(), "publish-owner");
				support.require(dataset, "different_job_types_parallel_at_job_lease_level", publish.acquired()
						&& support.redis.hasKey(jobKey) && support.redis.hasKey(LeaseKeys.job(taskId, "PUBLISH")),
						"EXPORT and PUBLISH job-level leases coexist; the separate task-level lock is unchanged");
				leasesB.release(publish.lease());
				first.close();
				support.require(dataset, "creator_close_releases_reverse_order", !support.redis.hasKey(taskKey)
						&& !support.redis.hasKey(jobKey), "the exact created generations are released");
			});

			LeaseService.AcquireResult jobOnly = jobHolder.acquire(jobKey, properties.getLease().getResourceTtl(),
					"job-holder");
			support.require(dataset, "second_phase_fixture_held", jobOnly.acquired() && jobOnly.created(),
					"only the job-level lease is held before the two-phase attempt");
			ToolResourceLocks.LockHandle blocked = locksA.acquire(taskId, "EXPORT", "owner-a-blocked");
			support.require(dataset, "second_phase_rejection_rolls_back_first", blocked.heldElsewhere()
					&& !support.redis.hasKey(taskKey) && support.redis.hasKey(jobKey),
					"the newly created task lease is returned while the other owner's job lease remains");
			blocked.close();
			jobHolder.release(jobOnly.lease());

			AtomicReference<ToolResourceLocks.LockHandle> degraded = new AtomicReference<>();
			boolean injected = P1ScenarioSupport.fault(support, dataset, "eval", jobKey, () ->
					P1ScenarioSupport.stage(support, dataset, "second_phase_unavailable", () ->
							degraded.set(locksA.acquire(taskId, "EXPORT", "owner-a-degraded"))));
			if (injected) {
				support.require(dataset, "second_phase_unavailable_owns_first_for_cleanup",
						degraded.get().outcome() == ToolResourceLocks.LockOutcome.UNAVAILABLE
								&& degraded.get().created().size() == 1
								&& taskKey.equals(degraded.get().created().get(0).key()),
						"degraded acquisition is explicit and retains only the created task lease for close");
			}
			if (degraded.get() != null) {
				degraded.get().close();
			}
			support.require(dataset, "degraded_close_leaves_no_partial_lock", !support.redis.hasKey(taskKey)
					&& !support.redis.hasKey(jobKey), "UNAVAILABLE close also releases the first phase exactly");

			properties.getLease().setResourceTtl(Duration.ofMillis(120L));
			P1ScenarioSupport.stage(support, dataset, "resource_lock_natural_ttl_expiry", () -> {
				ToolResourceLocks.LockHandle stale = locksA.acquire(taskId, "EXPORT", "owner-a-stale");
				List<LeaseService.Lease> oldGenerations = stale.created();
				P1ScenarioSupport.sleep(220L);
				support.require(dataset, "resource_lock_expires_naturally", !support.redis.hasKey(taskKey)
						&& !support.redis.hasKey(jobKey), "both short-TTL resource keys expire without a manual DEL");
				ToolResourceLocks.LockHandle successor = locksB.acquire(taskId, "EXPORT", "owner-b-successor");
				String successorTask = support.redis.opsForValue().get(taskKey);
				String successorJob = support.redis.opsForValue().get(jobKey);
				support.require(dataset, "successor_created_after_expiry", successor.created().size() == 2
						&& oldGenerations.size() == 2, "a successor generation owns both keys after real TTL expiry");
				stale.close();
				support.require(dataset, "stale_handle_cannot_release_successor", successorTask.equals(
						support.redis.opsForValue().get(taskKey)) && successorJob.equals(
						support.redis.opsForValue().get(jobKey)),
						"release Lua compares the captured token, not the current global epoch");
				successor.close();
			});

			P1ScenarioSupport.stage(support, dataset, "global_epoch_is_not_resource_fencing", () -> {
				LeaseService.AcquireResult held = leasesA.acquire(jobKey, Duration.ofSeconds(1L), "epoch-resource");
				long capturedEpoch = held.lease().epoch();
				long globalAfter = support.redis.opsForValue().increment(LeaseService.EPOCH_KEY);
				support.require(dataset, "resource_renew_ignores_later_global_epoch", globalAfter > capturedEpoch
						&& leasesA.renew(held.lease(), Duration.ofSeconds(1L)),
						"resource renewal compares only the captured epoch|owner token");
				support.require(dataset, "resource_release_ignores_later_global_epoch", leasesA.release(held.lease()),
						"the same captured token still releases after ai:lease:epoch advances");
				dataset.attributes.put("resourceGlobalEpochParticipatesInFencing", false);
			});
			support.require(dataset, "resource_lock_no_keyspace_scan", scansBefore == support.commandMetrics.keyspaceScanPages(),
					"all two-phase operations use exact lock keys");
			dataset.state = "measured";
		}
		finally {
			P1ScenarioSupport.shutdownLease(leasesA);
			P1ScenarioSupport.shutdownLease(leasesB);
			P1ScenarioSupport.shutdownLease(jobHolder);
		}
	}

}

final class ScanLeaseLongAndStalledScenario implements ProductionScenario {

	private static final String SCAN_KEY = "ai:lock:resume-scan";

	@Override
	public String id() {
		return "scan_lease_long_and_stalled";
	}

	@Override
	public Manifest.Dataset generate(ProductionSupport support, Manifest.Dataset dataset) {
		AiProperties properties = properties();
		RunStore store = P1ScenarioSupport.runStore(support, properties, null);
		String runId = dataset.sessionId + "-long-scan";
		P1ScenarioSupport.seedRun(support, dataset, store, runId, com.example.configmgr.ai.run.RunSnapshot.SUSPENDED);
		StartupResumeCandidateStormScenario.makeDue(support, runId, store);
		support.own(dataset, SCAN_KEY);
		P1ScenarioSupport.ownGlobalEpoch(support, dataset);
		return dataset;
	}

	@Override
	public void measure(ProductionSupport support, Manifest.Dataset dataset) {
		long scansBefore = support.commandMetrics.keyspaceScanPages();
		P1ScenarioSupport.stage(support, dataset, "long_scan_renewal_and_peer_defer", () -> longScan(support, dataset));
		P1ScenarioSupport.stage(support, dataset, "stalled_owner_ttl_takeover", () -> stalledOwner(support, dataset));
		support.require(dataset, "scan_lease_no_keyspace_scan", scansBefore == support.commandMetrics.keyspaceScanPages(),
				"lease acquire/renew/release and candidate reads never SCAN");
		dataset.state = "measured";
	}

	private static void longScan(ProductionSupport support, Manifest.Dataset dataset) {
		AiProperties properties = properties();
		LeaseService firstLeases = P1ScenarioSupport.renewingLease(support, properties, dataset.sessionId + "-long-owner");
		LeaseService peerLeases = P1ScenarioSupport.renewingLease(support, properties, dataset.sessionId + "-peer-owner");
		ExecutorService pool = Executors.newSingleThreadExecutor();
		CountDownLatch resumeEntered = new CountDownLatch(1);
		CountDownLatch finishResume = new CountDownLatch(1);
		try {
			RunStore firstStore = P1ScenarioSupport.runStore(support, properties, firstLeases);
			ResumeService firstService = spy(P1ScenarioSupport.resumeService(support, firstStore, properties));
			doAnswer(invocation -> {
				resumeEntered.countDown();
				if (!finishResume.await(5L, TimeUnit.SECONDS)) {
					throw new IllegalStateException("long scan barrier timed out");
				}
				return new ResumeService.ResumeResult(ResumeService.Outcome.ACCEPTED, Map.of());
			}).when(firstService).resume(anyString(), anyBoolean());
			StartupResumeRunner first = new StartupResumeRunner(firstService, firstStore, properties,
					P1ScenarioSupport.available());
			RunStore peerStore = P1ScenarioSupport.runStore(support, properties, peerLeases);
			StartupResumeRunner peer = new StartupResumeRunner(P1ScenarioSupport.resumeService(support, peerStore, properties),
					peerStore, properties, P1ScenarioSupport.available());
			Future<Map<String, Object>> longReport = pool.submit(() -> first.scan(true));
			try {
				P1ScenarioSupport.await(() -> resumeEntered.getCount() == 0L, 2_000L,
						"the first scan must own the lease before the peer probes");
			}
			catch (RuntimeException ex) {
				dataset.attributes.put("longScanResumeEntered", false);
				dataset.attributes.put("longScanReport", longReport.isDone() ? get(longReport) : "not completed");
				throw ex;
			}
			long pttl = support.redis.getExpire(SCAN_KEY, TimeUnit.MILLISECONDS);
			Map<String, Object> deferred = peer.sweep();
			support.require(dataset, "long_scan_peer_defers", Boolean.TRUE.equals(deferred.get("deferred"))
					&& "SCAN_IN_FLIGHT".equals(deferred.get("reason")) && pttl > 0L,
					"the peer does not scan the same candidates while the first owner is alive");
			boolean renewInjected = P1ScenarioSupport.armFault(support, dataset, "pexpire", SCAN_KEY);
			P1ScenarioSupport.sleep(220L);
			long renewedPttl = support.redis.getExpire(SCAN_KEY, TimeUnit.MILLISECONDS);
			support.require(dataset, "long_scan_renews_beyond_original_ttl", renewedPttl > 0L
					&& support.redis.hasKey(SCAN_KEY), "renewal keeps the same owner beyond the 160ms test TTL");
			finishResume.countDown();
			Map<String, Object> completed = get(longReport);
			support.require(dataset, "long_scan_completes_and_releases", P1ScenarioSupport.runIds(completed, "resumed").size() == 1
					&& !support.redis.hasKey(SCAN_KEY), "ScanFlight stops renewal before exact-owner release");
			if (renewInjected) {
				support.require(dataset, "one_renew_failure_does_not_abandon_long_scan", completed.get("error") == null
						&& P1ScenarioSupport.faultsDrained(support),
						"the injected PEXPIRE rule was consumed by a real renewal and the next renewal completed the scan");
			}
			peer.stopRuntimeSweep();
		}
		finally {
			finishResume.countDown();
			pool.shutdownNow();
			support.faults.clear();
			P1ScenarioSupport.shutdownLease(firstLeases);
			P1ScenarioSupport.shutdownLease(peerLeases);
		}
	}

	private static void stalledOwner(ProductionSupport support, Manifest.Dataset dataset) {
		AiProperties properties = properties();
		LeaseService crashed = P1ScenarioSupport.lease(support, properties, dataset.sessionId + "-crashed-owner");
		LeaseService takeoverLeases = P1ScenarioSupport.renewingLease(support, properties,
				dataset.sessionId + "-takeover-owner");
		try {
			LeaseService.AcquireResult abandoned = crashed.acquire(SCAN_KEY, properties.getResume().getScanLeaseTtl(),
					"crashed-owner");
			support.require(dataset, "stalled_owner_fixture_acquired", abandoned.acquired() && abandoned.created(),
					"an owner with no renewal task holds the short lease");
			RunStore store = P1ScenarioSupport.runStore(support, properties, takeoverLeases);
			StartupResumeRunner takeover = new StartupResumeRunner(P1ScenarioSupport.resumeService(support, store, properties),
					store, properties, P1ScenarioSupport.available());
			Map<String, Object> deferred = takeover.sweep();
			support.require(dataset, "live_stalled_lease_still_excludes_peer", Boolean.TRUE.equals(deferred.get("deferred")),
					"TTL not expired means the peer still yields");
			P1ScenarioSupport.sleep(220L);
			Map<String, Object> acquired = takeover.sweep();
			support.require(dataset, "expired_stalled_lease_can_be_taken_over", !Boolean.TRUE.equals(acquired.get("deferred"))
					&& !"SCAN_IN_FLIGHT".equals(acquired.get("reason")) && !support.redis.hasKey(SCAN_KEY),
					"after TTL the peer acquires, completes and releases its own scan flight");
			support.require(dataset, "crashed_owner_cannot_release_successor", !crashed.release(abandoned.lease()),
					"the old owner has no release right over the successor generation");
			takeover.stopRuntimeSweep();
		}
		finally {
			P1ScenarioSupport.shutdownLease(crashed);
			P1ScenarioSupport.shutdownLease(takeoverLeases);
		}
	}

	private static AiProperties properties() {
		AiProperties properties = StartupResumeCandidateStormScenario.recoveryProperties(1, 1);
		properties.getResume().setScanLeaseTtl(Duration.ofMillis(160L));
		properties.getLease().setRenewInterval(Duration.ofMillis(30L));
		return properties;
	}

	private static <T> T get(Future<T> future) {
		try {
			return future.get(5L, TimeUnit.SECONDS);
		}
		catch (Exception ex) {
			throw new IllegalStateException("production scan future failed", ex);
		}
	}

}
