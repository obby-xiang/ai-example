package com.example.configmgr.perf.production;

import java.time.Duration;
import java.util.List;

import com.example.configmgr.ai.form.FormAttachmentStore;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.memory.RedisChatMemoryRepository;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.LeaseService;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.session.AiSessionPurgeService;
import com.example.configmgr.ai.session.SessionGate;
import com.example.configmgr.ai.session.SessionPurgeDatabase;
import com.example.configmgr.ai.session.SessionPurgeGuard;
import com.example.configmgr.ai.session.SessionPurgePendingStore;
import com.example.configmgr.ai.session.SessionPurgeRetryRunner;
import com.example.configmgr.ai.tenant.TenantUser;
import com.example.configmgr.file.FileStorageService;
import com.example.configmgr.perf.redis.Manifest;
import org.springframework.ai.chat.messages.UserMessage;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class PurgeScenarios {

	private PurgeScenarios() {
	}

	static final class FullLifecycle extends Base {
		@Override
		public String id() {
			return "purge_retry_due_full_lifecycle";
		}

		@Override
		public Manifest.Dataset generate(ProductionSupport support, Manifest.Dataset dataset) {
			seed(support, dataset, RunSnapshot.DONE);
			Fixture fixture = fixture(support);
			String purgeId = "purge-" + dataset.sessionId;
			dataset.attributes.put("purgeId", purgeId);
			support.stage(dataset, "deferred_seed", () -> support.identity(() -> {
				fixture.memory.saveAll(dataset.sessionId, List.of(new UserMessage("memory-requiring-verify")));
				support.require(dataset, "memory_seed_exists_before_guard",
						fixture.memory.hasLiveMemory(dataset.sessionId)
								&& fixture.memory.findByConversationId(dataset.sessionId).size() == 1,
						"memory is written before the purge guard is armed");
				fixture.guard.arm(dataset.sessionId, purgeId, support.ttl());
				fixture.pending.register(new TenantUser(support.cfg.tenantId, support.cfg.userId), dataset.sessionId,
						purgeId, "perf-deferred", System.currentTimeMillis());
			}));
			return dataset;
		}

		@Override
		public void measure(ProductionSupport support, Manifest.Dataset dataset) {
			Fixture fixture = fixture(support);
			String purgeId = String.valueOf(dataset.attributes.get("purgeId"));
			support.require(dataset, "memory_present_before_retry",
					support.identity(() -> fixture.memory.hasLiveMemory(dataset.sessionId)),
					"retry starts with a real memory sample, not an empty fixture");
			long now = System.currentTimeMillis();
			SessionPurgePendingStore.AcquireResult first = support.identity(() -> fixture.pending.acquire(
					dataset.sessionId, purgeId, "old-executor", now, 10L));
			support.require(dataset, "owner_epoch_acquired", first.acquired()
					&& first.execution().epoch() == 1L, "first execution owner and epoch");
			SessionPurgePendingStore.AcquireResult busy = support.identity(() -> fixture.pending.acquire(
					dataset.sessionId, purgeId, "new-executor", now + 1L, 10L));
			support.require(dataset, "live_owner_blocks_competitor", !busy.acquired(), "live owner cannot be taken over");
			SessionPurgePendingStore.AcquireResult takeover = support.identity(() -> fixture.pending.acquire(
					dataset.sessionId, purgeId, "new-executor", now + 20L, 10L));
			support.require(dataset, "expired_owner_takeover", takeover.acquired()
					&& takeover.execution().epoch() == 2L, "expired lease advances epoch");
			support.require(dataset, "old_executor_cannot_schedule_verify",
					!support.identity(() -> fixture.pending.scheduleVerify(first.execution(), 1_000L)),
					"stale executor cannot advance the ledger");

			SessionPurgeRetryRunner runner = new SessionPurgeRetryRunner(fixture.pending, fixture.purge, fixture.leases,
					support.aiProperties);
			SessionPurgeRetryRunner.RetryReport retry = support.stage(dataset, "due_to_retry",
					() -> runner.runOnce(now + 1_000L));
			dataset.attributes.put("retryReport", retry.toString());
			SessionPurgePendingStore.Pending verifying = support.identity(() -> fixture.pending.current(dataset.sessionId));
			support.require(dataset, "retry_scheduled_verify", verifying != null && verifying.verifyAtMs() != null,
					"real retry completes the body and schedules memory verification");
			long verifyAt = verifying.verifyAtMs();
			SessionPurgeRetryRunner.RetryReport verified = support.stage(dataset, "verify_to_complete",
					() -> runner.runOnce(verifyAt));
			dataset.attributes.put("verifyReport", verified.toString());
			support.require(dataset, "pending_completed", support.identity(() -> fixture.pending.current(dataset.sessionId)) == null,
					"verifyMemoryOnly completes and deletes pending");
			support.require(dataset, "due_member_removed", support.redis.opsForZSet().score(
					SessionPurgePendingStore.DUE_KEY, SessionPurgePendingStore.pendingKey(dataset.sessionId,
							support.cfg.tenantId, support.cfg.userId)) == null,
					"completed pending has no due member");
			support.require(dataset, "guard_bounded_after_complete",
					support.redis.getExpire(fixture.guard.keyFor(dataset.sessionId)) > 0L,
					"production retains only a bounded guard after completion; final harness cleanup removes it");
			support.require(dataset, "memory_removed_after_verify",
					support.identity(() -> !fixture.memory.hasLiveMemory(dataset.sessionId)
							&& fixture.memory.stagingKeys(dataset.sessionId).isEmpty()),
					"retry and verify remove both final memory and staging, not only pending/due");
			dataset.state = "measured";
		}
	}

	static final class ActiveDeferred extends Base {
		@Override
		public String id() {
			return "purge_active_cancel_deferred";
		}

		@Override
		public Manifest.Dataset generate(ProductionSupport support, Manifest.Dataset dataset) {
			seed(support, dataset, RunSnapshot.RUNNING);
			dataset.attributes.put("purgeId", "purge-" + dataset.sessionId);
			return dataset;
		}

		@Override
		public void measure(ProductionSupport support, Manifest.Dataset dataset) {
			Fixture fixture = fixture(support);
			AiSessionPurgeService.PurgeResult deferred = support.stage(dataset, "active_cancel_deferred",
					() -> support.identity(() -> fixture.purge.purge(dataset.sessionId, "perf-active")));
			String purgeId = deferred.purgeId();
			dataset.attributes.put("purgeId", purgeId);
			support.require(dataset, "active_run_deferred", !deferred.complete() && !deferred.deferred().isEmpty(),
					"active run remains deferred instead of being half-deleted");
			support.require(dataset, "active_run_keys_retained", dataset.runIds.stream().allMatch(runId ->
							support.redis.hasKey(RunStore.runScopedKey("ai:run:", support.cfg.tenantId, support.cfg.userId, runId))),
					"all active run snapshots remain");
			support.require(dataset, "deferred_pending_retained",
					support.identity(() -> fixture.pending.current(dataset.sessionId)) != null,
					"deferred discovery ledger remains durable");

			support.stage(dataset, "terminal_then_retry_cleanup", () -> support.identity(() -> {
				for (String runId : dataset.runIds) {
					RunSnapshot snapshot = fixture.runStore.get(runId);
					snapshot.setStatus(RunSnapshot.CANCELLED);
					snapshot.setCancelled(true);
					fixture.runStore.save(snapshot);
				}
				AiSessionPurgeService.PurgeResult completed = fixture.purge.retry(dataset.sessionId, purgeId,
						"perf-retry-after-terminal");
				support.require(dataset, "terminal_retry_completes", completed.complete(),
						"retry after terminal completion removes the remaining run state");
			}));
			support.require(dataset, "terminal_run_keys_removed", dataset.runIds.stream().noneMatch(runId ->
							support.redis.hasKey(RunStore.runScopedKey("ai:run:", support.cfg.tenantId, support.cfg.userId, runId))),
					"no active run key is half-deleted or left after terminal retry");
			dataset.state = "measured";
		}
	}

	static final class RetryFairness extends Base {
		@Override
		public String id() {
			return "purge_retry_failure_and_fairness";
		}

		@Override
		public Manifest.Dataset generate(ProductionSupport support, Manifest.Dataset dataset) {
			seed(support, dataset, RunSnapshot.DONE);
			Fixture fixture = fixture(support);
			String badSession = dataset.sessionId + "-missing-identity";
			String badKey = SessionPurgePendingStore.pendingKey(badSession, support.cfg.tenantId, support.cfg.userId);
			String goodKey = SessionPurgePendingStore.pendingKey(dataset.sessionId, support.cfg.tenantId,
					support.cfg.userId);
			support.own(dataset, badKey);
			support.own(dataset, goodKey);
			dataset.attributes.put("badPendingKey", badKey);
			dataset.attributes.put("goodPendingKey", goodKey);
			support.stage(dataset, "missing_identity_and_valid_candidate_seed", () -> support.identity(() -> {
				String bad = "{\"sessionId\":\"" + badSession + "\",\"purgeId\":\"bad-purge\","
						+ "\"requestedBy\":\"perf\",\"attempts\":0,\"nextAtMs\":0,\"createdAtMs\":0}";
				support.redis.opsForValue().set(badKey, bad);
				support.redis.opsForZSet().add(SessionPurgePendingStore.DUE_KEY, badKey, 0L);
				fixture.pending.register(new TenantUser(support.cfg.tenantId, support.cfg.userId), dataset.sessionId,
						"good-purge", "perf-good", 1L);
			}));
			return dataset;
		}

		@Override
		public void measure(ProductionSupport support, Manifest.Dataset dataset) {
			Fixture fixture = fixture(support);
			SessionPurgeRetryRunner runner = new SessionPurgeRetryRunner(fixture.pending, fixture.purge, fixture.leases,
					support.aiProperties);
			long started = System.currentTimeMillis();
			RuntimeException failure = null;
			try {
				runner.runOnce(started);
			}
			catch (RuntimeException ex) {
				failure = ex;
			}
			SessionPurgePendingStore.Pending verifying = support.identity(() -> fixture.pending.current(dataset.sessionId));
			if (verifying != null && verifying.verifyAtMs() != null) {
				try {
					runner.runOnce(verifying.verifyAtMs());
				}
				catch (RuntimeException ex) {
					if (failure == null) {
						failure = ex;
					}
				}
			}
			dataset.attributes.put("missingIdentityFailure", failure == null ? "" : failure.toString());
			String badKey = String.valueOf(dataset.attributes.get("badPendingKey"));
			Double quarantineScore = support.redis.opsForZSet().score(SessionPurgePendingStore.DUE_KEY, badKey);
			dataset.attributes.put("missingIdentityDueScore", quarantineScore);
			boolean fair = support.check(dataset, "missing_identity_runner_has_no_failure", failure == null,
					"failure=" + failure);
			fair &= support.check(dataset, "missing_identity_pending_retained", support.redis.hasKey(badKey),
					"identity-less pending is retained for manual recovery");
			fair &= support.check(dataset, "missing_identity_due_is_quarantined",
					quarantineScore != null && quarantineScore >= started + 3_600_000L,
					"original due member remains with a bounded quarantine score; actual=" + quarantineScore);
			fair &= support.check(dataset, "missing_identity_does_not_block_following_candidate",
					support.identity(() -> fixture.pending.current(dataset.sessionId)) == null,
					"the valid candidate after the identity-less record must still retry to completion");
			if (!fair) {
				throw new IllegalStateException("purge retry identity/fairness assertions failed");
			}
			dataset.state = "measured";
		}
	}

	private abstract static class Base implements ProductionScenario {
		Manifest.Dataset seed(ProductionSupport support, Manifest.Dataset dataset, String status) {
			Fixture fixture = fixture(support);
			String runId = dataset.sessionId + "-run";
			dataset.runIds.add(runId);
			support.identity(() -> {
				RunSnapshot snapshot = new RunSnapshot();
				snapshot.setRunId(runId);
				snapshot.setSessionId(dataset.sessionId);
				snapshot.setTenantId(support.cfg.tenantId);
				snapshot.setUserId(support.cfg.userId);
				snapshot.setStatus(status);
				snapshot.setCreatedAtMs(System.currentTimeMillis());
				snapshot.setUpdatedAtMs(System.currentTimeMillis());
				snapshot.freezeParams(new RunSnapshot.FrozenParams(null, null, null, true));
				fixture.runStore.save(snapshot);
			});
			for (String prefix : List.of("ai:run:", "ai:runstate:", "ai:pending:", "ai:claim:", "ai:ledger:",
					"ai:idem:", "ai:seq:", "ai:xevents:", "ai:lease:run:", "ai:fence:run:", "ai:req:",
					"ai:decided:", "ai:lease:epoch:")) {
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
			support.own(dataset, SessionPurgePendingStore.pendingKey(dataset.sessionId, support.cfg.tenantId,
					support.cfg.userId));
			support.identity(() -> {
				support.own(dataset, fixture.guard.keyFor(dataset.sessionId));
				String memory = fixture.memory.keyFor(dataset.sessionId);
				support.own(dataset, memory);
				support.own(dataset, memory + ":ver");
				support.own(dataset, memory + ":gen");
				support.own(dataset, RedisChatMemoryRepository.stagingIndexKey(dataset.sessionId));
			});
			return dataset;
		}
	}

	private record Fixture(RunStore runStore, AiSessionPurgeService purge, SessionPurgePendingStore pending,
			SessionPurgeGuard guard, RedisChatMemoryRepository memory, LeaseService leases) {
	}

	private static Fixture fixture(ProductionSupport support) {
		LeaseService leases = new LeaseService(support.redis, support.aiProperties, (task, interval) -> () -> { },
				"purge-perf");
		RunStore runStore = new RunStore(support.redis, support.mapper, support.aiProperties, leases);
		SessionPurgeGuard guard = new SessionPurgeGuard(support.redis);
		SessionPurgePendingStore pending = new SessionPurgePendingStore(support.redis, support.mapper,
				support.aiProperties);
		RedisChatMemoryRepository memory = new RedisChatMemoryRepository(support.redis,
				new MessageJsonCodec(support.mapper), support.ttl(), guard);
		SessionPurgeDatabase database = mock(SessionPurgeDatabase.class);
		when(database.snapshot(anyString(), anyLong(), anyLong())).thenReturn(new SessionPurgeDatabase.Snapshot(0));
		when(database.delete(anyString(), anyLong(), anyLong())).thenReturn(new SessionPurgeDatabase.Deleted(0));
		FormAttachmentStore attachments = mock(FormAttachmentStore.class);
		when(attachments.findBySessionId(anyString())).thenReturn(List.of());
		AiSessionPurgeService purge = new AiSessionPurgeService(support.redis, runStore,
				new SessionGate(support.redis, support.aiProperties), new CancellationRegistry(support.redis,
						support.aiProperties), memory, attachments, mock(FileStorageService.class), database, guard, pending,
				support.aiProperties, Duration.ZERO);
		return new Fixture(runStore, purge, pending, guard, memory, leases);
	}

}
