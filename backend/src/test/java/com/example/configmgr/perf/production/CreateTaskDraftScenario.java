package com.example.configmgr.perf.production;

import com.example.configmgr.ai.run.CreateTaskDraftStore;
import com.example.configmgr.ai.run.LeaseService;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.perf.redis.Manifest;
import com.example.configmgr.ai.tenant.RequestContextUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisSentinelConnection;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Concurrent create_task draft merge and read-failure preservation on the real Redis path. */
final class CreateTaskDraftScenario implements ProductionScenario {

	@Override
	public String id() {
		return "create_task_draft_merge_large";
	}

	@Override
	public Manifest.Dataset generate(ProductionSupport support, Manifest.Dataset dataset) {
		String runId = dataset.sessionId + "-draft-run";
		ProductionDomainSupport.ownRun(support, dataset, runId);
		dataset.attributes.put("runId", runId);
		String draftKey = support.identity(() -> CreateTaskDraftStore.sessionKey(dataset.sessionId));
		dataset.attributes.put("draftKey", draftKey);
		support.ownHashField(dataset, draftKey, "101");
		support.ownHashField(dataset, draftKey, "102");
		LeaseService leases = ProductionDomainSupport.lease(support, "draft-owner");
		RunStore runs = new RunStore(support.redis, support.mapper, support.aiProperties, leases);
		support.require(dataset, "draft_run_lease", ProductionDomainSupport.acquire(support, runs, leases, runId,
				Duration.ofSeconds(60)).acquired(), "real draft run lease acquired");
		support.identity(() -> runs.save(ProductionDomainSupport.snapshot(support, dataset, runId, "RUNNING")));
		dataset.attributes.put("productionFixesAppliedAwaitingVerification", java.util.List.of(
				java.util.Map.of("id", "concurrent_draft_merge_preserves_complementary_fields",
						"reason", "CreateTaskDraftStore now retries read/merge/CAS up to three times, unions defCodes and merges conditions per key so complementary fields survive."),
				java.util.Map.of("id", "draft_read_failure_preserves_existing",
						"reason", "CreateTaskDraftStore now aborts immediately on HGET failure before any CAS write, preserving the previous draft.")));
		return dataset;
	}

	@Override
	public void measure(ProductionSupport support, Manifest.Dataset dataset) {
		String runId = String.valueOf(dataset.attributes.get("runId"));
		String draftKey = String.valueOf(dataset.attributes.get("draftKey"));
		long scanBefore = support.commandMetrics.keyspaceScanPages();
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(support.cfg.tenantId,
				support.cfg.userId)) {
			boolean valid = true;
			try {
			LeaseService leases = ProductionDomainSupport.lease(support, "draft-owner");
			RunStore runs = new RunStore(support.redis, support.mapper, support.aiProperties, leases);
			CreateTaskDraftStore drafts = new CreateTaskDraftStore(provider(support.redis), support.mapper,
					support.aiProperties, provider(runs));
			valid &= support.stage(dataset, "draft_seed_real_snapshot", () -> {
				drafts.save(runId, 101L, List.of("BASE"), Map.of("base", "1"));
				CreateTaskDraftStore.Draft seed = drafts.read(runId, 101L);
				return support.check(dataset, "draft_seed_written",
						seed != null && seed.defCodes().equals(List.of("BASE")) && seed.hasConditions(),
						"real RunStore.snapshotOwner resolves the session before the draft write");
			});

			valid &= support.stage(dataset, "draft_concurrent_complementary_merge", () -> {
				StringRedisTemplate barrierRedis = new StringRedisTemplate(new EvalBarrierFactory(support.faults));
				barrierRedis.afterPropertiesSet();
				CreateTaskDraftStore concurrent = new CreateTaskDraftStore(provider(barrierRedis), support.mapper,
						support.aiProperties, provider(runs));
				List<Throwable> failures = new ArrayList<>();
				Thread first = new Thread(() -> save(concurrent, runId, 101L, "A", failures));
				Thread second = new Thread(() -> save(concurrent, runId, 101L, "B", failures));
				first.start();
				second.start();
				try {
					first.join(10_000L);
					second.join(10_000L);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException("interrupted while awaiting draft writers", ex);
				}
				boolean merged = support.check(dataset, "draft_writers_finished", failures.isEmpty()
						&& !first.isAlive() && !second.isAlive(), "both real EVAL calls crossed the read/write barrier");
				CreateTaskDraftStore.Draft mergedDraft = drafts.read(runId, 101L);
				merged &= support.check(dataset, "draft_complementary_values_not_lost", mergedDraft != null
						&& mergedDraft.defCodes().containsAll(List.of("BASE", "A", "B"))
						&& mergedDraft.conditions().keySet().containsAll(java.util.Set.of("base", "a", "b")),
						"concurrent complementary defCodes/conditions must merge rather than last-write-wins");
				return merged;
			});

			if (support.faultEnabled("hget")) {
				valid &= support.stage(dataset, "draft_read_failure_preserves_old", () -> {
					drafts.save(runId, 102L, List.of("KEEP"), Map.of("keep", "yes"));
					support.faults.failNext("hget", draftKey);
					boolean rejected = expectFailure(() -> drafts.save(runId, 102L, List.of("NEW"), Map.of()));
					support.faults.clear();
					CreateTaskDraftStore.Draft after = drafts.read(runId, 102L);
					return support.check(dataset, "draft_read_failure_no_overwrite", rejected && after != null
							&& after.defCodes().equals(List.of("KEEP")) && after.conditions().containsKey("keep"),
							"HGET failure must abort the write and preserve the previous draft");
				});
			}
			else {
				support.faultSkipped(dataset, "hget");
			}
		}
		catch (Exception ex) {
			support.faults.clear();
			throw new IllegalStateException(ex);
		}
		valid &= support.check(dataset, "draft_no_keyspace_scan",
				scanBefore == support.commandMetrics.keyspaceScanPages(), "draft merge/read uses single-key commands only");
		support.require(dataset, "create_task_draft_contract", valid,
				"draft concurrency/read-failure assertions failed");
		dataset.state = "measured";
		}
	}

	private static void save(CreateTaskDraftStore drafts, String runId, long taskId, String value,
			List<Throwable> failures) {
		try {
			drafts.save(runId, taskId, List.of(value), Map.of(value, value));
		}
		catch (Throwable ex) {
			failures.add(ex);
		}
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

	private static <T> ObjectProvider<T> provider(T value) {
		return new ObjectProvider<>() {
			@Override
			public T getObject() {
				return value;
			}

			@Override
			public T getIfAvailable() {
				return value;
			}
		};
	}

	/** Transport-only barrier: both writers finish HGET before either real EVAL is allowed through. */
	private static final class EvalBarrierFactory implements RedisConnectionFactory {

		private final RedisConnectionFactory delegate;

		private final CyclicBarrier firstTwoWrites = new CyclicBarrier(2);

		private EvalBarrierFactory(RedisConnectionFactory delegate) {
			this.delegate = delegate;
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
					(proxy, method, args) -> {
						if (method.getName().toLowerCase().contains("eval")) {
							this.firstTwoWrites.await(10L, java.util.concurrent.TimeUnit.SECONDS);
						}
						try {
							return method.invoke(connection, args);
						}
						catch (InvocationTargetException ex) {
							throw ex.getCause();
						}
					}));
		}

		private static String commandText(Object[] args) {
			StringBuilder out = new StringBuilder();
			if (args != null) {
				for (Object arg : args) {
					append(out, arg);
				}
			}
			return out.toString();
		}

		private static void append(StringBuilder out, Object value) {
			if (value == null) {
				return;
			}
			if (value instanceof byte[] bytes) {
				out.append(' ').append(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
				return;
			}
			if (value instanceof byte[][] many) {
				for (byte[] item : many) {
					append(out, item);
				}
				return;
			}
			if (value instanceof Collection<?> collection) {
				for (Object item : collection) {
					append(out, item);
				}
				return;
			}
			if (value.getClass().isArray()) {
				int length = java.lang.reflect.Array.getLength(value);
				for (int i = 0; i < length; i++) {
					append(out, java.lang.reflect.Array.get(value, i));
				}
				return;
			}
			out.append(' ').append(value);
		}

	}

}
