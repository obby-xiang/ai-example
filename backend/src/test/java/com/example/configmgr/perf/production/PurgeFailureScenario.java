package com.example.configmgr.perf.production;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisSentinelConnection;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.example.configmgr.ai.form.FormAttachmentStore;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.memory.MemoryTurn;
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
import com.example.configmgr.perf.redis.RedisSlots;
import org.springframework.ai.chat.messages.UserMessage;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class PurgeFailureScenario {

	private PurgeFailureScenario() {
	}

	static final class MemoryPurgeGeneration implements ProductionScenario {
		@Override
		public String id() {
			return "memory_purge_generation";
		}

		@Override
		public Manifest.Dataset generate(ProductionSupport support, Manifest.Dataset dataset) {
			PurgeScenarioSupport.ownMemory(support, dataset, dataset.sessionId);
			RedisChatMemoryRepository repository = PurgeScenarioSupport.repository(support);
			support.stage(dataset, "memory_generation_seed", () -> support.identity(() -> repository.saveAll(dataset.sessionId,
					List.of(new UserMessage("legacy-history")))));
			return dataset;
		}

		@Override
		public void measure(ProductionSupport support, Manifest.Dataset dataset) throws Exception {
			CasBarrier barrier = new CasBarrier(support.harness.connectionFactory());
			RedisChatMemoryRepository repository = new RedisChatMemoryRepository(barrier.template(),
					new MessageJsonCodec(support.mapper), support.ttl());
			String generationKey = support.identity(() -> repository.keyFor(dataset.sessionId) + ":gen");
			long before = PurgeScenarioSupport.counter(support, generationKey);
			support.stage(dataset, "memory_generation_race", () -> {
				ExecutorService pool = Executors.newSingleThreadExecutor();
				try {
					Future<Boolean> write = pool.submit(() -> support.identity(() -> repository.upsertRun(dataset.sessionId,
							new MemoryTurn(dataset.sessionId + "-run", 1L),
							List.of(new UserMessage("stale-history")), 100, false)));
					barrier.awaitCas();
					support.identity(() -> repository.deleteByConversationId(dataset.sessionId));
					barrier.release();
					boolean applied = write.get();
					support.require(dataset, "memory_generation_stale_write_rejected", !applied,
							"generation change after staging rejects the old write without a retry");
					support.require(dataset, "memory_generation_history_not_resurrected",
							!support.identity(() -> repository.hasLiveMemory(dataset.sessionId)), "the pre-clear history does not return");
					support.require(dataset, "memory_generation_staging_converges",
							support.identity(() -> repository.stagingKeys(dataset.sessionId)).isEmpty(), "superseded staging is exactly reclaimed");
					long current = PurgeScenarioSupport.counter(support, generationKey);
					support.require(dataset, "memory_generation_tombstone_advanced", current > before,
							"clear advances the generation tombstone instead of deleting it");
				}
				catch (Exception ex) {
					throw new IllegalStateException(ex);
				}
				finally {
					barrier.release();
					pool.shutdownNow();
				}
			});
			dataset.state = "measured";
		}
	}

	static final class MemoryWritebackCrash implements ProductionScenario {
		@Override
		public String id() {
			return "purge_memory_writeback_crash";
		}

		@Override
		public Manifest.Dataset generate(ProductionSupport support, Manifest.Dataset dataset) {
			PurgeScenarioSupport.ownMemory(support, dataset, dataset.sessionId);
			RedisChatMemoryRepository repository = PurgeScenarioSupport.repository(support);
			support.stage(dataset, "memory_writeback_seed", () -> support.identity(() -> repository.saveAll(dataset.sessionId,
					List.of(new UserMessage("before-crash")))));
			return dataset;
		}

		@Override
		public void measure(ProductionSupport support, Manifest.Dataset dataset) throws Exception {
			CasBarrier barrier = new CasBarrier(support.harness.connectionFactory(), 2);
			SessionPurgeGuard guard = new SessionPurgeGuard(support.redis);
			RedisChatMemoryRepository repository = new RedisChatMemoryRepository(barrier.template(),
					new MessageJsonCodec(support.mapper), support.ttl(), guard);
			support.stage(dataset, "memory_writeback_crash_race", () -> {
				ExecutorService pool = Executors.newSingleThreadExecutor();
				try {
					Future<?> write = pool.submit(() -> support.identity(() -> repository.saveAll(dataset.sessionId,
							List.of(new UserMessage("writeback-must-be-undone")))));
					barrier.awaitCas();
					support.identity(() -> guard.arm(dataset.sessionId, "purge-" + dataset.sessionId, support.ttl()));
					barrier.release();
					write.get();
					support.require(dataset, "memory_writeback_guard_reclaims_final",
							!support.identity(() -> repository.hasLiveMemory(dataset.sessionId)), "guard after RENAME revokes the late writeback");
					support.require(dataset, "memory_writeback_staging_reclaimed",
							support.identity(() -> repository.stagingKeys(dataset.sessionId)).isEmpty(), "crash-era staging is not left behind");
				}
				catch (Exception ex) {
					throw new IllegalStateException(ex);
				}
				finally {
					barrier.release();
					pool.shutdownNow();
				}
			});
			dataset.state = "measured";
		}
	}

	static final class ClusterSlotAndErrorCleanup implements ProductionScenario {
		@Override
		public String id() {
			return "purge_cluster_slot_and_error_cleanup";
		}

		@Override
		public Manifest.Dataset generate(ProductionSupport support, Manifest.Dataset dataset) {
			String bad = dataset.sessionId + "-bad";
			String good = dataset.sessionId + "-good";
			dataset.attributes.put("badSession", bad);
			dataset.attributes.put("goodSession", good);
			PurgeFixture fixture = PurgeScenarioSupport.fixture(support);
			PurgeScenarioSupport.seed(support, dataset, fixture, bad, "purge-bad", 0L);
			PurgeScenarioSupport.seed(support, dataset, fixture, good, "purge-good", 1L);
			return dataset;
		}

		@Override
		public void measure(ProductionSupport support, Manifest.Dataset dataset) {
			String bad = String.valueOf(dataset.attributes.get("badSession"));
			String good = String.valueOf(dataset.attributes.get("goodSession"));
			PurgeFixture fixture = PurgeScenarioSupport.fixture(support);
			try {
				String badMemory = support.identity(() -> fixture.memory.keyFor(bad));
				String goodMemory = support.identity(() -> fixture.memory.keyFor(good));
				support.require(dataset, "purge_memory_keys_same_slot", RedisSlots.sameSlot(List.of(badMemory,
						badMemory + ":ver", badMemory + ":gen", support.identity(() -> RedisChatMemoryRepository.stagingIndexKey(bad))))
						&& RedisSlots.sameSlot(List.of(goodMemory, goodMemory + ":ver", goodMemory + ":gen",
								support.identity(() -> RedisChatMemoryRepository.stagingIndexKey(good)))),
						"memory final/ver/gen/staging index share each session hash tag");
				SessionPurgeRetryRunner runner = new SessionPurgeRetryRunner(fixture.pending, fixture.purge, fixture.leases,
						support.aiProperties);
				boolean fault = support.faultEnabled("MEMORY_CLEAR");
				if (!fault) {
					support.faultSkipped(dataset, "MEMORY_CLEAR");
				}
				else {
					support.faults.failNext("MEMORY_CLEAR", badMemory);
				}
				support.stage(dataset, "purge_targeted_failure", () -> runner.runOnce(System.currentTimeMillis()));
				support.requireFaultsDrained(dataset, "purge_targeted_failure_rule_consumed");
				support.faults.clear();
				SessionPurgePendingStore.Pending badPending = support.identity(() -> fixture.pending.current(bad));
				if (fault) {
					support.require(dataset, "purge_targeted_failure_retains_pending", badPending != null && badPending.attempts() == 1,
							"targeted MEMORY_CLEAR failure retains durable pending with one failure");
					support.require(dataset, "purge_targeted_failure_retains_memory", support.identity(() -> fixture.memory.hasLiveMemory(bad)),
							"failed session memory remains for the next retry");
				}
				SessionPurgePendingStore.Pending goodVerifying = support.identity(() -> fixture.pending.current(good));
				support.require(dataset, "purge_next_candidate_progresses", goodVerifying != null && goodVerifying.verifyAtMs() != null,
						"the following candidate still reaches memory verification");
				support.stage(dataset, "purge_good_verify", () -> runner.runOnce(goodVerifying.verifyAtMs()));
				support.require(dataset, "purge_next_candidate_completes", support.identity(() -> fixture.pending.current(good)) == null
						&& !support.identity(() -> fixture.memory.hasLiveMemory(good)), "the good candidate completes despite the earlier failure");
				if (fault) {
					SessionPurgePendingStore.Pending retry = support.identity(() -> fixture.pending.current(bad));
					support.stage(dataset, "purge_bad_retry", () -> runner.runOnce(retry.nextAtMs()));
					SessionPurgePendingStore.Pending verifying = support.identity(() -> fixture.pending.current(bad));
					if (verifying != null && verifying.verifyAtMs() != null) {
						support.stage(dataset, "purge_bad_verify", () -> runner.runOnce(verifying.verifyAtMs()));
					}
					support.require(dataset, "purge_failed_candidate_recovers", support.identity(() -> fixture.pending.current(bad)) == null
							&& !support.identity(() -> fixture.memory.hasLiveMemory(bad)), "the failed candidate converges after the targeted fault clears");
				}
				else {
					support.require(dataset, "purge_all_complete_without_injected_failure", support.identity(() -> fixture.pending.current(bad)) == null
							&& !support.identity(() -> fixture.memory.hasLiveMemory(bad)), "both candidates complete when no failure is injected");
				}
			}
			finally {
				PurgeScenarioSupport.shutdownGate(fixture.gate);
			}
			dataset.state = "measured";
		}
	}

	private static final class CasBarrier implements RedisConnectionFactory {
		private final RedisConnectionFactory delegate;
		private final int expectedKeys;
		private final CountDownLatch casReached = new CountDownLatch(1);
		private final CountDownLatch release = new CountDownLatch(1);
		private final AtomicBoolean armed = new AtomicBoolean(true);

		CasBarrier(RedisConnectionFactory delegate) {
			this(delegate, 5);
		}

		CasBarrier(RedisConnectionFactory delegate, int expectedKeys) {
			this.delegate = delegate;
			this.expectedKeys = expectedKeys;
		}

		StringRedisTemplate template() {
			StringRedisTemplate template = new StringRedisTemplate(this);
			template.afterPropertiesSet();
			return template;
		}

		void awaitCas() {
			try {
				if (!this.casReached.await(3L, TimeUnit.SECONDS)) {
					throw new IllegalStateException("writer did not reach MERGE_CAS");
				}
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(ex);
			}
		}

		void release() {
			this.release.countDown();
		}

		@Override
		public RedisConnection getConnection() {
			return proxy(RedisConnection.class, this.delegate.getConnection());
		}

		@Override
		public RedisClusterConnection getClusterConnection() {
			return proxy(RedisClusterConnection.class, this.delegate.getClusterConnection());
		}

		@Override
		public RedisSentinelConnection getSentinelConnection() {
			return this.delegate.getSentinelConnection();
		}

		@Override
		public boolean getConvertPipelineAndTxResults() {
			return this.delegate.getConvertPipelineAndTxResults();
		}

		@Override
		public DataAccessException translateExceptionIfPossible(RuntimeException ex) {
			return this.delegate.translateExceptionIfPossible(ex);
		}

		private <T> T proxy(Class<T> type, T connection) {
			return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type },
					(proxy, method, args) -> invoke(connection, method, args)));
		}

		private Object invoke(Object connection, Method method, Object[] args) throws Throwable {
			if (method.getName().toLowerCase().contains("eval") && this.armed.get()
					&& numKeys(args) == this.expectedKeys && this.armed.compareAndSet(true, false)) {
				this.casReached.countDown();
				if (!this.release.await(3L, TimeUnit.SECONDS)) {
					throw new IllegalStateException("MERGE_CAS release timed out");
				}
			}
			try {
				return method.invoke(connection, args);
			}
			catch (InvocationTargetException ex) {
				throw ex.getCause();
			}
		}

		private static int numKeys(Object[] args) {
			if (args != null) {
				for (Object arg : args) {
					if (arg instanceof Number number) {
						return number.intValue();
					}
				}
			}
			return -1;
		}
	}

	private record PurgeFixture(RunStore runStore, AiSessionPurgeService purge, SessionPurgePendingStore pending,
			SessionPurgeGuard guard, RedisChatMemoryRepository memory, LeaseService leases, SessionGate gate) {
	}

	private static final class PurgeScenarioSupport {
		private PurgeScenarioSupport() {
		}

		static RedisChatMemoryRepository repository(ProductionSupport support) {
			return new RedisChatMemoryRepository(support.redis, new MessageJsonCodec(support.mapper), support.ttl());
		}

		static void ownMemory(ProductionSupport support, Manifest.Dataset dataset, String sessionId) {
			support.identity(() -> {
				RedisChatMemoryRepository repository = repository(support);
				String key = repository.keyFor(sessionId);
				support.own(dataset, key);
				support.own(dataset, key + ":ver");
				support.own(dataset, key + ":gen");
				support.own(dataset, RedisChatMemoryRepository.stagingIndexKey(sessionId));
				support.own(dataset, new SessionPurgeGuard(support.redis).keyFor(sessionId));
				support.own(dataset, SessionPurgePendingStore.pendingKey(sessionId, support.cfg.tenantId, support.cfg.userId));
				support.own(dataset, com.example.configmgr.perf.redis.KeyShapes.sessionRunsKey(
						support.cfg.tenantId, support.cfg.userId, sessionId));
				support.own(dataset, com.example.configmgr.perf.redis.KeyShapes.sessionActiveKey(
						support.cfg.tenantId, support.cfg.userId, sessionId));
				support.own(dataset, com.example.configmgr.perf.redis.KeyShapes.sessionCurrentKey(
						support.cfg.tenantId, support.cfg.userId, sessionId));
				support.own(dataset, "ai:session:lock:" + support.cfg.tenantId + ":" + support.cfg.userId + ":" + sessionId);
			});
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
			support.own(dataset, RunStore.runScopedKey(CancellationRegistry.CANCEL_PREFIX,
					support.cfg.tenantId, support.cfg.userId, runId));
		}

		static RunSnapshot snapshot(ProductionSupport support, Manifest.Dataset dataset, String runId, String sessionId) {
			long now = System.currentTimeMillis();
			RunSnapshot snapshot = new RunSnapshot();
			snapshot.setRunId(runId);
			snapshot.setSessionId(sessionId);
			snapshot.setTenantId(support.cfg.tenantId);
			snapshot.setUserId(support.cfg.userId);
			snapshot.setStatus(RunSnapshot.DONE);
			snapshot.setCreatedAtMs(now);
			snapshot.setUpdatedAtMs(now);
			snapshot.setTurnStartedAtMs(now);
			snapshot.setRequestText("perf-" + runId);
			snapshot.setClientRequestId("creq-" + runId);
			snapshot.freezeParams(new RunSnapshot.FrozenParams(null, null, null, true));
			return snapshot;
		}

		static PurgeFixture fixture(ProductionSupport support) {
			LeaseService leases = new LeaseService(support.redis, support.aiProperties, (task, interval) -> () -> { },
					"purge-failure-perf");
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
			SessionGate gate = new SessionGate(support.redis, support.aiProperties);
			AiSessionPurgeService purge = new AiSessionPurgeService(support.redis, runStore, gate,
					new CancellationRegistry(support.redis, support.aiProperties), memory, attachments,
					mock(FileStorageService.class), database, guard, pending, support.aiProperties, Duration.ZERO);
			return new PurgeFixture(runStore, purge, pending, guard, memory, leases, gate);
		}

		static void seed(ProductionSupport support, Manifest.Dataset dataset, PurgeFixture fixture, String sessionId,
				String purgeId, long dueAt) {
			ownMemory(support, dataset, sessionId);
			String runId = sessionId + "-run";
			ownRun(support, dataset, runId);
			support.stage(dataset, "purge_seed_" + sessionId, () -> support.identity(() -> {
				fixture.runStore.save(snapshot(support, dataset, runId, sessionId));
				fixture.memory.saveAll(sessionId, List.of(new UserMessage("memory-" + sessionId)));
				fixture.pending.register(new TenantUser(support.cfg.tenantId, support.cfg.userId), sessionId, purgeId,
						"perf", dueAt);
			}));
		}

		static long counter(ProductionSupport support, String key) {
			String value = support.redis.opsForValue().get(key);
			return value == null || value.isBlank() ? 0L : Long.parseLong(value);
		}

		static void awaitStaging(RedisChatMemoryRepository repository, String sessionId) {
			long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
			while (repository.stagingKeys(sessionId).isEmpty() && System.nanoTime() < deadline) {
				try {
					Thread.sleep(1L);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(ex);
				}
			}
			if (repository.stagingKeys(sessionId).isEmpty()) {
				throw new IllegalStateException("production write did not reach staging");
			}
		}

		static void shutdownGate(SessionGate gate) {
			try {
				java.lang.reflect.Method method = SessionGate.class.getDeclaredMethod("shutdown");
				method.setAccessible(true);
				method.invoke(gate);
			}
			catch (Exception ex) {
				throw new IllegalStateException("unable to stop purge session gate", ex);
			}
		}
	}
}
