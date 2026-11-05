package com.example.configmgr.perf.production;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.perf.redis.CountingRedis;
import com.example.configmgr.perf.redis.Manifest;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisSentinelConnection;
import org.springframework.data.redis.core.StringRedisTemplate;

final class RedisAvailabilityScenario implements ProductionScenario {

	@Override
	public String id() {
		return "redis_availability_down_recovery";
	}

	@Override
	public Manifest.Dataset generate(ProductionSupport support, Manifest.Dataset dataset) {
		dataset.attributes.put("fault", "one injected PING failure followed by real negative-TTL recovery");
		return dataset;
	}

	@Override
	public void measure(ProductionSupport support, Manifest.Dataset dataset) {
		SingleFlightPingFactory pingGate = new SingleFlightPingFactory(support.faults);
		StringRedisTemplate probedRedis = new StringRedisTemplate(pingGate);
		probedRedis.afterPropertiesSet();
		RedisAvailability availability = new RedisAvailability(probedRedis);
		try {
			support.stage(dataset, "availability_baseline_real_ping", () -> {
				support.require(dataset, "baseline_available", availability.isAvailable(),
						"real Redis PING returned PONG");
				Map<String, Object> description = availability.describe();
				support.require(dataset, "baseline_description_available", Boolean.TRUE.equals(description.get("available")),
						"observability matches the real probe");
				support.require(dataset, "baseline_probe_count", ((Number) description.get("probes")).longValue() == 1L,
						"positive TTL is zero and the first call probed once");
			});
			support.stage(dataset, "availability_concurrent_single_flight", () -> {
				int callers = 8;
				ExecutorService pool = Executors.newFixedThreadPool(callers);
				try {
					CyclicBarrier barrier = new CyclicBarrier(callers);
					CountDownLatch callersReady = new CountDownLatch(callers);
					long probesBefore = ((Number) availability.describe().get("probes")).longValue();
					CountingRedis.Snapshot commandsBefore = support.commandMetrics.snapshot();
					pingGate.arm();
					List<Future<Boolean>> futures = new ArrayList<>();
					for (int i = 0; i < callers; i++) {
						futures.add(pool.submit(() -> {
							barrier.await();
							callersReady.countDown();
							return availability.isAvailable();
						}));
					}
					pingGate.awaitFirstPing();
					ProductionDomainSupport.await(() -> callersReady.getCount() == 0L, 2_000L,
							"all concurrent callers entered production availability probing");
					pingGate.release();
					for (Future<Boolean> future : futures) {
						support.require(dataset, "concurrent_probe_success", future.get(),
								"every concurrent caller observes the shared successful PING");
					}
					long probesAfter = ((Number) availability.describe().get("probes")).longValue();
					CountingRedis.Snapshot commandsAfter = support.commandMetrics.snapshot();
					support.require(dataset, "concurrent_single_flight_probe", probesAfter == probesBefore + 1L,
							"simultaneous callers share one production probe: before=" + probesBefore + " after=" + probesAfter);
					support.require(dataset, "concurrent_single_flight_one_ping",
							commandsAfter.commands() == commandsBefore.commands() + 1L,
							"eight simultaneous callers emit exactly one real PING");
				}
				catch (Exception ex) {
					throw new IllegalStateException("concurrent availability probe failed", ex);
				}
				finally {
					pingGate.release();
					pool.shutdownNow();
				}
			});
			if (support.faultEnabled("ping")) {
				long failuresBefore = ((Number) availability.describe().get("failures")).longValue();
				support.stage(dataset, "availability_injected_down", () -> {
					support.faults.failNext("ping", "");
					support.require(dataset, "injected_failure_marks_down", !availability.isAvailable(),
							"one real command-path failure marks Redis unavailable");
					support.require(dataset, "known_down_without_new_probe", availability.isKnownUnavailable(),
							"cached DOWN state is observable without another command");
					Map<String, Object> description = availability.describe();
					support.require(dataset, "failure_counter_incremented",
							((Number) description.get("failures")).longValue() == failuresBefore + 1L,
							"failure observability incremented exactly once");
					support.require(dataset, "failure_diagnostic_retained", description.get("lastError") != null,
							"health diagnostics retain the transport failure");
				});
				support.stage(dataset, "availability_negative_cache_is_bounded", () -> {
					CountingRedis.Snapshot before = support.commandMetrics.snapshot();
					long probesBefore = ((Number) availability.describe().get("probes")).longValue();
					support.require(dataset, "negative_cache_returns_down", !availability.isAvailable(),
							"negative TTL returns the cached DOWN result");
					CountingRedis.Snapshot after = support.commandMetrics.snapshot();
					long probesAfter = ((Number) availability.describe().get("probes")).longValue();
					support.require(dataset, "negative_cache_emits_no_ping", before.equals(after) && probesBefore == probesAfter,
							"cached DOWN path emitted no Redis command and started no new probe");
				});
				support.stage(dataset, "availability_real_recovery_after_negative_ttl", () -> {
					ProductionDomainSupport.sleep(RedisAvailability.NEGATIVE_TTL_MS + 100L);
					support.require(dataset, "recovered_real_ping", availability.isAvailable(),
							"negative TTL elapsed and a real PING confirmed recovery");
					Map<String, Object> description = availability.describe();
					support.require(dataset, "recovery_clears_down_and_error", Boolean.TRUE.equals(description.get("available"))
							&& description.get("lastError") == null && !availability.isKnownUnavailable(),
							"successful real probe clears both DOWN state and stale diagnostics");
					support.require(dataset, "recovery_preserves_failure_history",
							((Number) description.get("failures")).longValue() == failuresBefore + 1L,
							"recovery does not erase observability history");
				});
			}
			else {
				support.faultSkipped(dataset, "ping");
			}
			dataset.state = "measured";
		}
		finally {
			pingGate.release();
			support.faults.clear();
		}
	}

	private static final class SingleFlightPingFactory implements RedisConnectionFactory {

		private final RedisConnectionFactory delegate;
		private final CountDownLatch firstPing = new CountDownLatch(1);
		private final CountDownLatch release = new CountDownLatch(1);
		private volatile boolean armed;

		private SingleFlightPingFactory(RedisConnectionFactory delegate) {
			this.delegate = delegate;
		}

		private void arm() {
			this.armed = true;
		}

		private void awaitFirstPing() {
			ProductionDomainSupport.await(() -> this.firstPing.getCount() == 0L, 2_000L,
					"first real PING entered the transport barrier");
		}

		private void release() {
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
					(proxy, method, args) -> {
						if ("ping".equals(method.getName()) && this.armed) {
							this.firstPing.countDown();
							try {
								if (!this.release.await(2L, TimeUnit.SECONDS)) {
									throw new IllegalStateException("timed out waiting to release the shared real PING");
								}
							}
							catch (InterruptedException ex) {
								Thread.currentThread().interrupt();
								throw new IllegalStateException("interrupted at the shared PING barrier", ex);
							}
						}
						try {
							return method.invoke(connection, args);
						}
						catch (InvocationTargetException ex) {
							throw ex.getCause();
						}
					}));
		}
	}
}
