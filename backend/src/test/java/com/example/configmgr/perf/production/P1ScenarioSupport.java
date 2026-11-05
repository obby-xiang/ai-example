package com.example.configmgr.perf.production;

import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.LeaseService;
import com.example.configmgr.ai.run.ResumeService;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.config.AppProperties;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.job.service.JobHeartbeatRegistry;
import com.example.configmgr.perf.redis.CountingRedis;
import com.example.configmgr.perf.redis.Manifest;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisSentinelConnection;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class P1ScenarioSupport {

	private static final Map<LeaseService, AutoCloseable> RENEWERS = new java.util.concurrent.ConcurrentHashMap<>();

	private P1ScenarioSupport() {
	}

	static void stage(ProductionSupport support, Manifest.Dataset dataset, String name, Runnable body) {
		stage(support, dataset, name, () -> {
			body.run();
			return null;
		});
	}

	static <T> T stage(ProductionSupport support, Manifest.Dataset dataset, String name, Supplier<T> body) {
		CountingRedis.Snapshot before = support.commandMetrics.snapshot();
		T result = support.stage(dataset, name, body);
		CountingRedis.Snapshot delta = CountingRedis.delta(before, support.commandMetrics.snapshot());
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("stage", name);
		row.put("commands", delta.commands());
		row.put("requestBytes", delta.requestBytes());
		row.put("scanPages", delta.scanPages());
		stageRows(dataset).add(row);
		return result;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> stageRows(Manifest.Dataset dataset) {
		return (List<Map<String, Object>>) dataset.attributes.computeIfAbsent("stageMetrics",
				ignored -> new ArrayList<Map<String, Object>>());
	}

	static boolean fault(ProductionSupport support, Manifest.Dataset dataset, String operation, String keyPart,
			Runnable body) {
		if (!armFault(support, dataset, operation, keyPart)) {
			return false;
		}
		try {
			body.run();
			return true;
		}
		finally {
			support.faults.clear();
		}
	}

	static boolean armFault(ProductionSupport support, Manifest.Dataset dataset, String operation, String keyPart) {
		if (!support.faultEnabled(operation)) {
			support.faultSkipped(dataset, operation);
			return false;
		}
		@SuppressWarnings("unchecked")
		List<Map<String, String>> operations = (List<Map<String, String>>) dataset.attributes.computeIfAbsent(
				"faultOperations", ignored -> new ArrayList<Map<String, String>>());
		operations.add(Map.of("operation", operation, "keyPart", keyPart));
		support.faults.failNext(operation, keyPart);
		return true;
	}

	static boolean faultsDrained(ProductionSupport support) {
		try {
			java.lang.reflect.Field field = FaultInjectingConnectionFactory.class.getDeclaredField("rules");
			field.setAccessible(true);
			return ((java.util.Queue<?>) field.get(support.faults)).isEmpty();
		}
		catch (Exception ex) {
			throw new IllegalStateException("unable to inspect asynchronous fault consumption", ex);
		}
	}

	static StringRedisTemplate boundaryRedis(ProductionSupport support, Manifest.Dataset dataset, String operation,
			String keyPart, long delayMs, boolean failAfterDelay) {
		@SuppressWarnings("unchecked")
		List<Map<String, String>> operations = (List<Map<String, String>>) dataset.attributes.computeIfAbsent(
				"faultOperations", ignored -> new ArrayList<Map<String, String>>());
		operations.add(Map.of("operation", operation, "keyPart", keyPart, "mechanism",
				failAfterDelay ? "delay-then-error" : "delay-then-execute", "delayMs", Long.toString(delayMs)));
		BoundaryFactory factory = new BoundaryFactory(support.harness.connectionFactory(), operation, keyPart, delayMs,
				failAfterDelay);
		StringRedisTemplate template = new StringRedisTemplate(factory);
		template.afterPropertiesSet();
		return template;
	}

	private static final class BoundaryFactory implements RedisConnectionFactory {

		private final RedisConnectionFactory delegate;
		private final String operation;
		private final String keyPart;
		private final long delayMs;
		private final boolean failAfterDelay;
		private final java.util.concurrent.atomic.AtomicBoolean fired = new java.util.concurrent.atomic.AtomicBoolean();

		private BoundaryFactory(RedisConnectionFactory delegate, String operation, String keyPart, long delayMs,
				boolean failAfterDelay) {
			this.delegate = delegate;
			this.operation = operation.toLowerCase(java.util.Locale.ROOT);
			this.keyPart = keyPart;
			this.delayMs = delayMs;
			this.failAfterDelay = failAfterDelay;
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
			return type.cast(java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type },
					(proxy, method, args) -> invoke(connection, method, args)));
		}

		private Object invoke(Object connection, java.lang.reflect.Method method, Object[] args) throws Throwable {
			String commandText = commandText(args);
			boolean matches = (method.getName().toLowerCase(java.util.Locale.ROOT).contains(this.operation)
					|| commandText.toLowerCase(java.util.Locale.ROOT).contains(this.operation))
					&& (this.keyPart.isBlank() || commandText.contains(this.keyPart));
			if (matches && (!this.failAfterDelay || this.fired.compareAndSet(false, true))) {
				if (this.delayMs > 0L) {
					try {
						Thread.sleep(this.delayMs);
					}
					catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
						throw new IllegalStateException("scenario delay interrupted", ex);
					}
				}
				if (this.failAfterDelay) {
					throw new IllegalStateException("PERF_BOUNDARY_FAILURE " + this.operation + " " + this.keyPart);
				}
			}
			try {
				return method.invoke(connection, args);
			}
			catch (java.lang.reflect.InvocationTargetException ex) {
				throw ex.getCause();
			}
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
			if (value instanceof java.util.Collection<?> collection) {
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

	static RunStore runStore(ProductionSupport support, AiProperties properties, LeaseService leases) {
		return new RunStore(support.redis, support.mapper, properties, leases);
	}

	static ResumeService resumeService(ProductionSupport support, RunStore store, AiProperties properties) {
		return resumeService(support, store, properties, support.redis);
	}

	static ResumeService resumeService(ProductionSupport support, RunStore store, AiProperties properties,
			StringRedisTemplate redis) {
		CancellationRegistry cancellations = new CancellationRegistry(redis, properties);
		return new ResumeService(store, null, new MessageJsonCodec(support.mapper), null, null, null,
				redis, properties, cancellations, null);
	}

	static AiAvailability available() {
		AiAvailability availability = mock(AiAvailability.class);
		when(availability.isAvailable()).thenReturn(true);
		return availability;
	}

	static AiAvailability unavailable() {
		AiAvailability availability = mock(AiAvailability.class);
		when(availability.isAvailable()).thenReturn(false);
		when(availability.code()).thenReturn("AI_UNAVAILABLE");
		return availability;
	}

	static void seedRun(ProductionSupport support, Manifest.Dataset dataset, RunStore store, String runId,
			String status) {
		ProductionDomainSupport.ownRun(support, dataset, runId);
		RunSnapshot snapshot = ProductionDomainSupport.snapshot(support, dataset, runId, status);
		support.identity(() -> {
			store.save(snapshot);
			support.own(dataset, store.sessionRunsKey(dataset.sessionId));
			support.own(dataset, store.sessionCurrentKey(dataset.sessionId));
			support.own(dataset, store.sessionActiveKey(dataset.sessionId));
		});
	}

	static LeaseService lease(ProductionSupport support, AiProperties properties, String owner) {
		return new LeaseService(support.redis, properties, (task, interval) -> () -> { }, owner);
	}

	static LeaseService renewingLease(ProductionSupport support, AiProperties properties, String owner) {
		java.util.concurrent.ScheduledExecutorService executor = java.util.concurrent.Executors
				.newSingleThreadScheduledExecutor(runnable -> {
					Thread thread = new Thread(runnable, "perf-p1-lease-renew");
					thread.setDaemon(true);
					return thread;
				});
		LeaseService lease = new LeaseService(support.redis, properties, (task, interval) -> {
			java.util.concurrent.ScheduledFuture<?> future = executor.scheduleWithFixedDelay(task,
					interval.toMillis(), interval.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
			return () -> future.cancel(false);
		}, owner);
		RENEWERS.put(lease, executor::shutdownNow);
		return lease;
	}

	static void shutdownLease(LeaseService lease) {
		if (lease == null) {
			return;
		}
		try {
			invoke(LeaseService.class, "shutdown", lease);
		}
		finally {
			AutoCloseable renewer = RENEWERS.remove(lease);
			if (renewer != null) {
				try {
					renewer.close();
				}
				catch (Exception ex) {
					throw new IllegalStateException("unable to stop scenario lease renewer", ex);
				}
			}
		}
	}

	static void tickOnce(JobHeartbeatRegistry heartbeat) {
		invoke(JobHeartbeatRegistry.class, "tickOnce", heartbeat);
	}

	static void stopTicker(JobHeartbeatRegistry heartbeat) {
		if (heartbeat != null) {
			invoke(JobHeartbeatRegistry.class, "stopTicker", heartbeat);
		}
	}

	static boolean tickerRunning(JobHeartbeatRegistry heartbeat) {
		try {
			java.lang.reflect.Method method = JobHeartbeatRegistry.class.getDeclaredMethod("isTickerRunning");
			method.setAccessible(true);
			return (boolean) method.invoke(heartbeat);
		}
		catch (Exception ex) {
			throw new IllegalStateException("unable to inspect production ticker", ex);
		}
	}

	static void scanWatchdog(com.example.configmgr.job.service.JobWatchdog watchdog) {
		invoke(com.example.configmgr.job.service.JobWatchdog.class, "scan", watchdog);
	}

	private static void invoke(Class<?> type, String methodName, Object target) {
		try {
			java.lang.reflect.Method method = type.getDeclaredMethod(methodName);
			method.setAccessible(true);
			method.invoke(target);
		}
		catch (Exception ex) {
			throw new IllegalStateException("unable to drive production " + type.getSimpleName() + "." + methodName, ex);
		}
	}

	static JobRepository.CancelState cancelState(Job.JobStatus status, long version) {
		return new JobRepository.CancelState() {
			@Override
			public Job.JobStatus getStatus() {
				return status;
			}

			@Override
			public long getCancelVersion() {
				return version;
			}
		};
	}

	static Job job(long id, long taskId, Job.JobStatus status) {
		Job job = new Job();
		job.setId(id);
		job.setTaskId(taskId);
		job.setJobType(Job.JobType.EXPORT);
		job.setStatus(status);
		job.setProgress(1);
		job.setTotal(2);
		job.setCreatedAt(java.time.LocalDateTime.now());
		job.setStartedAt(java.time.LocalDateTime.now());
		return job;
	}

	static void ownJob(ProductionSupport support, Manifest.Dataset dataset, long jobId) {
		support.own(dataset, JobHeartbeatRegistry.RUNNER_ACTIVE_PREFIX + jobId);
		support.own(dataset, JobHeartbeatRegistry.BEAT_PREFIX + jobId);
		support.own(dataset, JobHeartbeatRegistry.DEAD_JUDGE_PREFIX + jobId);
		support.own(dataset, com.example.configmgr.job.service.JobCancellationRegistry.CANCEL_PREFIX + jobId);
		support.ownHashField(dataset, hostIndex(), String.valueOf(jobId));
	}

	static String hostIndex() {
		return "job:runner-active-host:" + hostName();
	}

	static String hostName() {
		try {
			return InetAddress.getLocalHost().getHostName();
		}
		catch (Exception ex) {
			return "unknown";
		}
	}

	static void ownGlobalEpoch(ProductionSupport support, Manifest.Dataset dataset) {
		support.retain(dataset, LeaseService.EPOCH_KEY);
	}

	static void sleep(long millis) {
		ProductionDomainSupport.sleep(millis);
	}

	static void await(java.util.function.BooleanSupplier condition, long timeoutMs, String detail) {
		ProductionDomainSupport.await(condition, timeoutMs, detail);
	}

	static boolean failure(Runnable body) {
		return ProductionDomainSupport.expectFailure(body);
	}

	static AppProperties jobProperties() {
		AppProperties properties = new AppProperties();
		properties.getJob().setHeartbeatInterval(Duration.ofMillis(1_500L));
		properties.getJob().setMaxExecutionTime(Duration.ofHours(2));
		return properties;
	}

	static List<String> runIds(Map<String, Object> report, String field) {
		if (!(report.get(field) instanceof List<?> items)) {
			return List.of();
		}
		List<String> out = new ArrayList<>();
		for (Object item : items) {
			if (item instanceof Map<?, ?> map) {
				out.add(String.valueOf(map.get("runId")));
			}
		}
		return out;
	}

}
