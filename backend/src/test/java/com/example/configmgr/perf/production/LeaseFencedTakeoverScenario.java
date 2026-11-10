package com.example.configmgr.perf.production;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.example.configmgr.ai.run.LeaseService;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.perf.redis.Manifest;
import com.example.configmgr.perf.redis.RedisSlots;

final class LeaseFencedTakeoverScenario implements ProductionScenario {

	@Override
	public String id() {
		return "lease_fenced_takeover_cluster";
	}

	@Override
	public Manifest.Dataset generate(ProductionSupport support, Manifest.Dataset dataset) {
		String runId = dataset.sessionId + "-run";
		dataset.runIds.add(runId);
		String lease = RunStore.runScopedKey("ai:lease:run:", support.cfg.tenantId, support.cfg.userId, runId);
		String epoch = RunStore.runScopedKey("ai:lease:epoch:", support.cfg.tenantId, support.cfg.userId, runId);
		String fence = RunStore.runScopedKey("ai:fence:run:", support.cfg.tenantId, support.cfg.userId, runId);
		support.own(dataset, lease);
		support.own(dataset, epoch);
		support.own(dataset, fence);
		dataset.attributes.put("runId", runId);
		dataset.attributes.put("leaseKey", lease);
		dataset.attributes.put("epochKey", epoch);
		dataset.attributes.put("fenceKey", fence);
		support.require(dataset, "three_keys_same_slot", RedisSlots.sameSlot(List.of(lease, epoch, fence)),
				"lease/epoch/fence must share the production run hash tag");
		return dataset;
	}

	@Override
	public void measure(ProductionSupport support, Manifest.Dataset dataset) throws Exception {
		String runId = String.valueOf(dataset.attributes.get("runId"));
		String leaseKey = String.valueOf(dataset.attributes.get("leaseKey"));
		String epochKey = String.valueOf(dataset.attributes.get("epochKey"));
		String fenceKey = String.valueOf(dataset.attributes.get("fenceKey"));
		Duration ttl = Duration.ofMillis(Math.max(100L, support.cfg.leaseTtlMs));
		int ownerCount = support.intEnv("PERF_LEASE_CONCURRENT_OWNERS", 8);
		if (ownerCount < 2 || ownerCount > 64) {
			throw new IllegalArgumentException("PERF_LEASE_CONCURRENT_OWNERS 必须在 2..64");
		}
		dataset.attributes.put("concurrentOwners", ownerCount);
		List<LeaseService> services = new ArrayList<>();
		for (int i = 0; i < ownerCount; i++) {
			services.add(service(support, "owner-" + i));
		}
		ExecutorService pool = Executors.newFixedThreadPool(services.size());
		try {
			CyclicBarrier barrier = new CyclicBarrier(services.size());
			List<Future<LeaseService.AcquireResult>> futures = new ArrayList<>();
			for (LeaseService service : services) {
				Callable<LeaseService.AcquireResult> call = () -> {
					barrier.await();
					return service.acquireFenced(leaseKey, fenceKey, ttl);
				};
				futures.add(pool.submit(call));
			}
			List<LeaseService.AcquireResult> acquired = new ArrayList<>();
			for (Future<LeaseService.AcquireResult> future : futures) {
				LeaseService.AcquireResult result = future.get();
				if (result.acquired()) {
					acquired.add(result);
				}
			}
			support.require(dataset, "concurrent_single_creator", acquired.size() == 1,
					"acquired=" + acquired.size());
			LeaseService winner = services.get(0);
			LeaseService.AcquireResult first = acquired.get(0);
			for (int i = 0; i < services.size(); i++) {
				if (services.get(i).instanceId().equals(first.lease().owner())) {
					winner = services.get(i);
				}
			}
			long firstEpoch = first.lease().epoch();
			LeaseService.AcquireResult reentry = winner.acquireFenced(leaseKey, fenceKey, ttl,
					first.lease().owner());
			support.require(dataset, "reentry_created_false", reentry.acquired() && !reentry.created(),
					"created=" + reentry.created());
			support.require(dataset, "reentry_cannot_release", !winner.release(reentry.lease())
					&& support.redis.hasKey(leaseKey), "reentrant owner has no release right");

			support.require(dataset, "creator_release", winner.release(first.lease()), "creator releases its own lease");
			LeaseService secondService = service(support, "second-owner");
			LeaseService.AcquireResult second = secondService.acquireFenced(leaseKey, fenceKey, ttl, "second-owner");
			support.require(dataset, "takeover_epoch_monotonic", second.acquired() && second.lease().epoch() > firstEpoch,
					"first=" + firstEpoch + " second=" + second.lease().epoch());
			support.require(dataset, "old_owner_cannot_renew", !winner.renew(first.lease(), ttl),
					"stale owner renew must fail");
			support.require(dataset, "old_owner_cannot_release", !winner.release(first.lease())
					&& support.redis.hasKey(leaseKey), "stale owner release must preserve the new lease");

			long beforeMissingCounter = second.lease().epoch();
			support.redis.delete(epochKey);
			LeaseService thirdService = service(support, "third-owner");
			support.require(dataset, "missing_counter_does_not_steal_live_lease",
					!thirdService.acquireFenced(leaseKey, fenceKey, ttl, "third-owner").acquired(),
					"counter missing must not bypass a live lease");
			secondService.release(second.lease());
			LeaseService.AcquireResult third = thirdService.acquireFenced(leaseKey, fenceKey, ttl, "third-owner");
			support.require(dataset, "missing_counter_epoch_monotonic",
					third.acquired() && third.lease().epoch() > beforeMissingCounter,
					"before=" + beforeMissingCounter + " third=" + third.lease().epoch());

			long beforeMissingFence = third.lease().epoch();
			thirdService.release(third.lease());
			support.redis.delete(fenceKey);
			LeaseService fourthService = service(support, "fourth-owner");
			LeaseService.AcquireResult fourth = fourthService.acquireFenced(leaseKey, fenceKey, ttl, "fourth-owner");
			String fenceBeforeSave = support.redis.opsForValue().get(fenceKey);
			dataset.attributes.put("fenceBeforeSave", fenceBeforeSave);
			dataset.attributes.put("fenceMissingBeforeSave", fenceBeforeSave == null);
			RunStore fourthStore = new RunStore(support.redis, support.mapper, support.aiProperties, fourthService);
			LeaseService.LeaseAuthority authorityBeforeSave = support.identity(() -> fourthStore.runLeaseAuthority(runId));
			long heldEpochBeforeSave = support.identity(() -> fourthStore.heldRunEpoch(runId));
			dataset.attributes.put("fenceSaveAuthority", authorityBeforeSave.name());
			dataset.attributes.put("fenceSaveHeldEpoch", heldEpochBeforeSave);
			support.require(dataset, "missing_fence_save_uses_held_lease",
					authorityBeforeSave == LeaseService.LeaseAuthority.HELD && heldEpochBeforeSave == fourth.lease().epoch(),
					"real RunStore save must use the fourth owner's HELD lease");
			ProductionDomainSupport.ownRun(support, dataset, runId);
			RunSnapshot fourthSnapshot = ProductionDomainSupport.snapshot(support, dataset, runId, "RUNNING");
			support.identity(() -> fourthStore.save(fourthSnapshot));
			String fenceAfterSave = support.redis.opsForValue().get(fenceKey);
			dataset.attributes.put("fenceAfterSave", fenceAfterSave);
			dataset.attributes.put("fenceSaveRunEpoch", fourthSnapshot.getRunEpoch());
			support.require(dataset, "missing_fence_rebuilt_monotonic",
					fourth.acquired() && fourth.lease().epoch() > beforeMissingFence && fenceBeforeSave == null
							&& fourthSnapshot.getRunEpoch() == fourth.lease().epoch() && fenceAfterSave != null
							&& Long.parseLong(fenceAfterSave) > beforeMissingFence,
					"before=" + beforeMissingFence + " fourth=" + fourth.lease().epoch());

			fourthService.release(fourth.lease());
			LeaseService shortService = service(support, "short-owner");
			LeaseService.AcquireResult shortLease = shortService.acquireFenced(leaseKey, fenceKey,
					Duration.ofMillis(40L), "short-owner");
			Thread.sleep(80L);
			LeaseService finalService = service(support, "final-owner");
			LeaseService.AcquireResult finalLease = finalService.acquireFenced(leaseKey, fenceKey, ttl, "final-owner");
			support.require(dataset, "expired_lease_takeover", finalLease.acquired()
					&& finalLease.lease().epoch() > shortLease.lease().epoch(), "expired owner was replaced");
			support.require(dataset, "expired_owner_fenced", !shortService.renew(shortLease.lease(), ttl)
					&& !shortService.release(shortLease.lease()), "expired owner can neither renew nor release");
			finalService.release(finalLease.lease());
			dataset.attributes.put("finalFence", support.redis.opsForValue().get(fenceKey));
			dataset.state = "measured";
		}
		finally {
			pool.shutdownNow();
			support.faults.clear();
		}
	}

	private static LeaseService service(ProductionSupport support, String owner) {
		return new LeaseService(support.redis, support.aiProperties, (task, interval) -> () -> { }, owner);
	}

}
