package com.example.configmgr.perf.production;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.example.configmgr.config.AppProperties;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.repo.JobItemRepository;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.job.service.JobCancellationRegistry;
import com.example.configmgr.job.service.JobHeartbeatRegistry;
import com.example.configmgr.job.service.RunnerActiveKeyCleanupRunner;
import com.example.configmgr.job.service.StaleJobRecoveryRunner;
import com.example.configmgr.perf.redis.BackgroundBatchWriter;
import com.example.configmgr.perf.redis.KeyShapes;
import com.example.configmgr.perf.redis.Manifest;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.service.TaskService;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class StartupRunnerActiveScenario implements ProductionScenario {

	@Override
	public String id() {
		return "startup_runner_active_scan_1m";
	}

	@Override
	public Manifest.Dataset generate(ProductionSupport support, Manifest.Dataset dataset) {
		int background = support.intEnv("PERF_STARTUP_BACKGROUND_KEYS",
				"stress".equals(support.cfg.profile) ? 1_000_000 : ("full".equals(support.cfg.profile) ? 100_000 : 2_000));
		if (background > 100_000 && (!support.cfg.allowLargeScale
				|| !support.boolEnv("PERF_ALLOW_BACKGROUND_MILLION", false))) {
			throw new IllegalArgumentException("百万背景需要 PERF_ALLOW_LARGE_SCALE=true 和 PERF_ALLOW_BACKGROUND_MILLION=true");
		}
		if (background > 10_000 && !support.cfg.allowLargeScale) {
			throw new IllegalArgumentException("大背景需要 PERF_ALLOW_LARGE_SCALE=true");
		}
		dataset.background = background;
		dataset.declaredBackground = background;
		dataset.backgroundBucket = "startup";
		dataset.attributes.put("backgroundKeys", background);
		dataset.attributes.put("backgroundWriteBatchSize", BackgroundBatchWriter.BATCH_SIZE);
		support.stage(dataset, "startup_background_generate", () -> BackgroundBatchWriter.write(support.direct,
				support.cfg.namespace, dataset.backgroundBucket, background, support.cfg.keyTtlSeconds));

		long localDead = support.jobId(dataset, 1);
		long remoteDead = support.jobId(dataset, 2);
		long alive = support.jobId(dataset, 3);
		long invalid = support.jobId(dataset, 4);
		long tickerAlive = support.jobId(dataset, 5);
		dataset.attributes.put("localDeadJob", localDead);
		dataset.attributes.put("remoteDeadJob", remoteDead);
		dataset.attributes.put("aliveJob", alive);
		dataset.attributes.put("invalidJob", invalid);
		dataset.attributes.put("tickerAliveJob", tickerAlive);
		String host = hostName();
		String hostIndex = "job:runner-active-host:" + host;
		String remoteIndex = "job:runner-active-host:perf-other-host";
		dataset.attributes.put("hostIndex", hostIndex);
		dataset.attributes.put("remoteIndex", remoteIndex);
		String localDeadKey = JobHeartbeatRegistry.RUNNER_ACTIVE_PREFIX + localDead;
		String remoteDeadKey = JobHeartbeatRegistry.RUNNER_ACTIVE_PREFIX + remoteDead;
		String aliveKey = JobHeartbeatRegistry.RUNNER_ACTIVE_PREFIX + alive;
		String invalidKey = JobHeartbeatRegistry.RUNNER_ACTIVE_PREFIX + invalid;
		String tickerAliveKey = JobHeartbeatRegistry.RUNNER_ACTIVE_PREFIX + tickerAlive;
		for (String key : List.of(localDeadKey, remoteDeadKey, aliveKey, invalidKey, tickerAliveKey,
				JobHeartbeatRegistry.BEAT_PREFIX + alive, JobHeartbeatRegistry.BEAT_PREFIX + tickerAlive)) {
			support.own(dataset, key);
		}
		support.ownHashField(dataset, hostIndex, String.valueOf(localDead));
		support.ownHashField(dataset, hostIndex, String.valueOf(alive));
		support.ownHashField(dataset, hostIndex, String.valueOf(tickerAlive));
		support.ownHashField(dataset, hostIndex, "invalid-pid-field");
		support.ownHashField(dataset, hostIndex, "not-a-job-id");
		support.ownHashField(dataset, remoteIndex, String.valueOf(remoteDead));
		for (String variable : List.of("PERF_STARTUP_EXTERNAL_ACTIVE_KEY", "PERF_STARTUP_EXTERNAL_REMOTE_KEY",
				"PERF_STARTUP_EXTERNAL_INVALID_KEY")) {
			String external = System.getenv(variable);
			if (external != null && !external.isBlank()) {
				support.retain(dataset, external.trim());
			}
		}
		support.stage(dataset, "startup_fixture_dead_remote_invalid", () -> {
			String deadValue = "runner-" + host + "-pid" + Long.MAX_VALUE + "-deadA|" + System.currentTimeMillis();
			String remoteValue = "runner-perf-other-host-pid" + Long.MAX_VALUE + "-deadB|"
					+ System.currentTimeMillis();
			support.redis.opsForValue().set(localDeadKey, deadValue, support.ttl());
			support.redis.opsForHash().put(hostIndex, String.valueOf(localDead), deadValue);
			support.redis.opsForValue().set(remoteDeadKey, remoteValue, support.ttl());
			support.redis.opsForHash().put(remoteIndex, String.valueOf(remoteDead), remoteValue);
			support.redis.opsForValue().set(invalidKey, "invalid-instance-value", support.ttl());
			support.redis.opsForHash().put(hostIndex, "invalid-pid-field", "invalid-instance-value");
			support.redis.opsForHash().put(hostIndex, "not-a-job-id", "invalid-instance-value");
		});

		JobRepository repository = jobRepository();
		JobHeartbeatRegistry heartbeat = heartbeat(support, repository);
		try {
			support.stage(dataset, "beginRunner_index_write_and_beat_renew", () -> {
			support.require(dataset, "real_beginRunner", heartbeat.beginRunner(alive), "production beginRunner accepted in-flight job");
			support.require(dataset, "real_beginRunner_ticker_peer", heartbeat.beginRunner(tickerAlive),
					"a second real runner exercises the batch ticker path");
			String indexed = String.valueOf(support.redis.opsForHash().get(hostIndex, String.valueOf(alive)));
			support.require(dataset, "beginRunner_writes_host_index", indexed != null && !"null".equals(indexed),
					"HSCAN source index is populated by the real beginRunner");
			long beforeBeat = support.redis.getExpire(hostIndex, java.util.concurrent.TimeUnit.MILLISECONDS);
			pause(25L);
			heartbeat.beat(alive);
			long afterBeat = support.redis.getExpire(hostIndex, java.util.concurrent.TimeUnit.MILLISECONDS);
			String active = support.redis.opsForValue().get(aliveKey);
			String renewed = String.valueOf(support.redis.opsForHash().get(hostIndex, String.valueOf(alive)));
			support.require(dataset, "beat_renews_host_index", active != null && active.equals(renewed)
					&& afterBeat > beforeBeat,
					"host index value matches and PTTL increases: before=" + beforeBeat + " after=" + afterBeat);
			long beforeTicker = support.redis.getExpire(hostIndex, java.util.concurrent.TimeUnit.MILLISECONDS);
			pause(25L);
			tickOnce(heartbeat);
			long afterTicker = support.redis.getExpire(hostIndex, java.util.concurrent.TimeUnit.MILLISECONDS);
			support.require(dataset, "batch_ticker_two_stage_renew", afterTicker > beforeTicker
					&& support.redis.hasKey(JobHeartbeatRegistry.BEAT_PREFIX + alive)
					&& support.redis.hasKey(JobHeartbeatRegistry.BEAT_PREFIX + tickerAlive)
					&& support.redis.opsForValue().get(aliveKey).equals(
							support.redis.opsForHash().get(hostIndex, String.valueOf(alive)))
					&& support.redis.opsForValue().get(tickerAliveKey).equals(
							support.redis.opsForHash().get(hostIndex, String.valueOf(tickerAlive))),
					"tickOnce renews runner-active/index first and both shared beats in the batch phase; PTTL before="
							+ beforeTicker + " after=" + afterTicker);
			support.require(dataset, "active_ttl_armed", support.redis.getExpire(aliveKey) > 0L
					&& support.redis.getExpire(tickerAliveKey) > 0L, "both live runner keys have the production TTL");
			dataset.attributes.put("indexPttlBeforeBeat", beforeBeat);
			dataset.attributes.put("indexPttlAfterBeat", afterBeat);
			dataset.attributes.put("indexPttlBeforeTicker", beforeTicker);
			dataset.attributes.put("indexPttlAfterTicker", afterTicker);
		});
		}
		finally {
			stopTicker(heartbeat);
			dataset.attributes.put("tickerStoppedForCleanup", true);
		}
		return dataset;
	}

	@Override
	public void measure(ProductionSupport support, Manifest.Dataset dataset) {
		long localDead = ((Number) dataset.attributes.get("localDeadJob")).longValue();
		long remoteDead = ((Number) dataset.attributes.get("remoteDeadJob")).longValue();
		long alive = ((Number) dataset.attributes.get("aliveJob")).longValue();
		long invalid = ((Number) dataset.attributes.get("invalidJob")).longValue();
		long tickerAlive = ((Number) dataset.attributes.get("tickerAliveJob")).longValue();
		String hostIndex = String.valueOf(dataset.attributes.get("hostIndex"));
		long scansBefore = support.commandMetrics.keyspaceScanPages();
		long hashScansBefore = support.commandMetrics.hashScanPages();
		JobRepository repository = jobRepository();
		when(repository.findInFlightRefsAfter(anyLong(), anyCollection(), any())).thenAnswer(invocation -> {
			if (invocation.getArgument(0, Long.class) > 0L) {
				return List.of();
			}
			return List.of(ref(localDead), ref(remoteDead), ref(alive), ref(invalid));
		});
		JobHeartbeatRegistry heartbeat = heartbeat(support, repository);
		RunnerActiveKeyCleanupRunner cleanup = new RunnerActiveKeyCleanupRunner(heartbeat);
		StaleJobRecoveryRunner recovery = recovery(repository, heartbeat);
		support.stage(dataset, "startup_cleanup_and_stale_recovery_total", () -> {
			support.stage(dataset, "RunnerActiveKeyCleanupRunner", () -> cleanup.run(null));
			support.stage(dataset, "StaleJobRecoveryRunner", () -> recovery.run(null));
		});
		dataset.attributes.put("keyspaceScanPages", support.commandMetrics.keyspaceScanPages() - scansBefore);
		dataset.attributes.put("hostHashScanPages", support.commandMetrics.hashScanPages() - hashScansBefore);
		support.require(dataset, "no_full_keyspace_scan", scansBefore == support.commandMetrics.keyspaceScanPages(),
				"startup cleanup/recovery emitted zero SCAN commands");
		support.require(dataset, "host_hscan_was_not_empty", support.commandMetrics.hashScanPages() > hashScansBefore,
				"real beginRunner populated the index later read by HSCAN");
		support.require(dataset, "local_dead_key_removed",
				!support.redis.hasKey(JobHeartbeatRegistry.RUNNER_ACTIVE_PREFIX + localDead),
				"only a dead PID on this host is removed");
		support.require(dataset, "local_dead_index_removed",
				!support.redis.opsForHash().hasKey(hostIndex, String.valueOf(localDead)),
				"the dead local member is removed from the host index");
		support.require(dataset, "remote_dead_key_retained",
				support.redis.hasKey(JobHeartbeatRegistry.RUNNER_ACTIVE_PREFIX + remoteDead),
				"another host's runner key is never deleted by local startup");
		support.require(dataset, "live_key_retained", support.redis.hasKey(JobHeartbeatRegistry.RUNNER_ACTIVE_PREFIX + alive)
				&& support.redis.hasKey(JobHeartbeatRegistry.RUNNER_ACTIVE_PREFIX + tickerAlive),
				"both current-PID runners remain active during the scenario");
		support.require(dataset, "invalid_key_and_fields_retained",
				support.redis.hasKey(JobHeartbeatRegistry.RUNNER_ACTIVE_PREFIX + invalid)
						&& support.redis.opsForHash().hasKey(hostIndex, "invalid-pid-field")
						&& support.redis.opsForHash().hasKey(hostIndex, "not-a-job-id"),
				"unparseable values remain for TTL/manual diagnosis");
		dataset.state = "measured";
	}

	private static JobHeartbeatRegistry heartbeat(ProductionSupport support, JobRepository repository) {
		AppProperties properties = new AppProperties();
		JobCancellationRegistry cancellations = new JobCancellationRegistry(support.redis, repository, properties);
		return new JobHeartbeatRegistry(support.redis, "redis", properties, repository, cancellations);
	}

	private static StaleJobRecoveryRunner recovery(JobRepository repository, JobHeartbeatRegistry heartbeat) {
		JobItemRepository items = mock(JobItemRepository.class);
		TaskService tasks = mock(TaskService.class);
		when(tasks.tryLockForUpdate(anyLong())).thenReturn(Optional.of(new Task()));
		PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
		when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
		return new StaleJobRecoveryRunner(repository, items, tasks, heartbeat, transactions);
	}

	private static JobRepository jobRepository() {
		JobRepository repository = mock(JobRepository.class);
		when(repository.findCancelStateById(anyLong())).thenAnswer(invocation -> Optional.of(new JobRepository.CancelState() {
			@Override
			public Job.JobStatus getStatus() {
				return Job.JobStatus.RUNNING;
			}

			@Override
			public long getCancelVersion() {
				return 0L;
			}
		}));
		when(repository.findInFlightRefsAfter(anyLong(), anyCollection(), any())).thenAnswer(invocation -> {
			long after = invocation.getArgument(0);
			List<JobRepository.InFlightJobRef> refs = new ArrayList<>();
			for (long id = after + 1; id <= after + 4; id++) {
				long jobId = id;
				refs.add(new JobRepository.InFlightJobRef() {
					@Override
					public Long getId() {
						return jobId;
					}

					@Override
					public Long getTaskId() {
						return jobId + 10_000L;
					}

					@Override
					public Job.JobType getJobType() {
						return Job.JobType.IMPORT;
					}
				});
			}
			return refs;
		});
		when(repository.finishInterruptedIfInFlight(anyLong(), eq(Job.JobStatus.FAILED), any(), anyCollection()))
				.thenReturn(1);
		return repository;
	}

	private static void tickOnce(JobHeartbeatRegistry heartbeat) {
		try {
			java.lang.reflect.Method method = JobHeartbeatRegistry.class.getDeclaredMethod("tickOnce");
			method.setAccessible(true);
			method.invoke(heartbeat);
		}
		catch (Exception ex) {
			throw new IllegalStateException("unable to drive production tickOnce", ex);
		}
	}

	private static void stopTicker(JobHeartbeatRegistry heartbeat) {
		try {
			java.lang.reflect.Method method = JobHeartbeatRegistry.class.getDeclaredMethod("stopTicker");
			method.setAccessible(true);
			method.invoke(heartbeat);
		}
		catch (Exception ex) {
			throw new IllegalStateException("unable to stop production ticker before cleanup", ex);
		}
	}

	private static void pause(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	private static JobRepository.InFlightJobRef ref(long jobId) {
		return new JobRepository.InFlightJobRef() {
			@Override
			public Long getId() {
				return jobId;
			}

			@Override
			public Long getTaskId() {
				return jobId + 10_000L;
			}

			@Override
			public Job.JobType getJobType() {
				return Job.JobType.IMPORT;
			}
		};
	}

	private static String hostName() {
		try {
			return InetAddress.getLocalHost().getHostName();
		}
		catch (Exception ex) {
			return "unknown";
		}
	}

}
