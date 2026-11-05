package com.example.configmgr.perf.production;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.example.configmgr.common.ConflictException;
import com.example.configmgr.common.ErrorCodes;
import com.example.configmgr.config.AppProperties;
import com.example.configmgr.data.repo.ConfigStagingRowRepository;
import com.example.configmgr.file.FileStorageService;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.repo.JobItemRepository;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.job.repo.ValidationIssueRepository;
import com.example.configmgr.job.service.JobCancellationRegistry;
import com.example.configmgr.job.service.JobHeartbeatRegistry;
import com.example.configmgr.job.service.JobTerminalWriter;
import com.example.configmgr.job.service.JobWatchdog;
import com.example.configmgr.perf.redis.CountingRedis;
import com.example.configmgr.perf.redis.Manifest;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.repo.TaskFileRepository;
import com.example.configmgr.task.repo.TaskItemRepository;
import com.example.configmgr.task.repo.TaskRepository;
import com.example.configmgr.task.service.TaskService;

import org.springframework.transaction.PlatformTransactionManager;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class JobWatchdogSingleflightScenario implements ProductionScenario {

	@Override
	public String id() {
		return "job_watchdog_singleflight";
	}

	@Override
	public Manifest.Dataset generate(ProductionSupport support, Manifest.Dataset dataset) {
		P1ScenarioSupport.ownJob(support, dataset, support.jobId(dataset, 1));
		P1ScenarioSupport.ownJob(support, dataset, support.jobId(dataset, 2));
		return dataset;
	}

	@Override
	public void measure(ProductionSupport support, Manifest.Dataset dataset) throws Exception {
		long scansBefore = support.commandMetrics.keyspaceScanPages();
		long jobId = support.jobId(dataset, 1);
		Map<Long, JobRepository.CancelState> states = new ConcurrentHashMap<>();
		states.put(jobId, P1ScenarioSupport.cancelState(Job.JobStatus.RUNNING, 0L));
		JobRepository repository = mock(JobRepository.class);
		Job job = P1ScenarioSupport.job(jobId, 91L, Job.JobStatus.RUNNING);
		when(repository.findByStatusIn(anyCollection())).thenReturn(List.of(job));
		when(repository.findCancelStateById(anyLong())).thenAnswer(invocation ->
				Optional.ofNullable(states.get(invocation.getArgument(0))));
		AppProperties properties = P1ScenarioSupport.jobProperties();
		JobCancellationRegistry cancellations = new JobCancellationRegistry(support.redis, repository, properties);
		JobHeartbeatRegistry owner = new JobHeartbeatRegistry(support.redis, "redis", properties, repository, cancellations);
		JobHeartbeatRegistry judge = new JobHeartbeatRegistry(support.redis, "redis", properties, repository, cancellations);
		JobHeartbeatRegistry peer = new JobHeartbeatRegistry(support.redis, "redis", properties, repository, cancellations);
		JobTerminalWriter terminal = mock(JobTerminalWriter.class);
		ValidationIssueRepository issues = mock(ValidationIssueRepository.class);
		CountDownLatch terminalEntered = new CountDownLatch(1);
		CountDownLatch releaseTerminal = new CountDownLatch(1);
		doAnswer(invocation -> {
			terminalEntered.countDown();
			if (!releaseTerminal.await(5L, TimeUnit.SECONDS)) {
				throw new IllegalStateException("watchdog test barrier timed out");
			}
			return null;
		}).when(terminal).complete(any(Job.class), eq(Job.JobStatus.FAILED), anyInt(), anyInt(), anyInt(), anyInt(),
				anyMap());
		JobWatchdog watchdogB = new JobWatchdog(repository, judge, terminal, cancellations, issues, properties);
		JobWatchdog watchdogC = new JobWatchdog(repository, peer, terminal, cancellations, issues, properties);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			P1ScenarioSupport.stage(support, dataset, "watchdog_owner_stale_fixture", () -> {
				support.require(dataset, "owner_runner_started", owner.beginRunner(jobId), "real generation registered");
				P1ScenarioSupport.stopTicker(owner);
				support.redis.delete(JobHeartbeatRegistry.BEAT_PREFIX + jobId);
			});
			Future<?> judged = pool.submit(() -> P1ScenarioSupport.scanWatchdog(watchdogB));
			P1ScenarioSupport.await(() -> terminalEntered.getCount() == 0L, 2_000L,
					"watchdog B must hold the judge lease before terminal completion");
			Future<?> deferred = pool.submit(() -> P1ScenarioSupport.scanWatchdog(watchdogC));
			P1ScenarioSupport.stage(support, dataset, "watchdog_peer_singleflight", () -> {
				try {
					deferred.get(5L, TimeUnit.SECONDS);
				}
				catch (Exception ex) {
					throw new IllegalStateException("peer watchdog scan failed", ex);
				}
			});
			releaseTerminal.countDown();
			judged.get(5L, TimeUnit.SECONDS);

			support.require(dataset, "singleflight_one_judge", ((Number) watchdogB.metrics().get("judgedDeadTotal")).longValue() == 1L
					&& ((Number) watchdogC.metrics().get("judgedDeadTotal")).longValue() == 0L
					&& ((Number) watchdogC.metrics().get("skippedNotLeaderTotal")).longValue() == 1L,
					"B judges once while C observes HELD and performs no terminal action");
			verify(issues, times(1)).save(any());
			verify(terminal, times(1)).complete(any(Job.class), eq(Job.JobStatus.FAILED), anyInt(), anyInt(), anyInt(),
					anyInt(), anyMap());
			String revoked = support.redis.opsForValue().get(JobHeartbeatRegistry.RUNNER_ACTIVE_PREFIX + jobId);
			support.require(dataset, "revocation_is_shared_tombstone", revoked != null && revoked.startsWith("REVOKED|0|"),
					"the late owner sees a versioned shared fence, active=" + revoked);
			owner.beat(jobId);
			owner.endRunner(jobId);
			String afterLateBeat = support.redis.opsForValue().get(JobHeartbeatRegistry.BEAT_PREFIX + jobId);
			String afterLateEnd = support.redis.opsForValue().get(JobHeartbeatRegistry.RUNNER_ACTIVE_PREFIX + jobId);
			support.require(dataset, "late_beat_and_end_cannot_revive_or_delete_new_fence", afterLateBeat != null
					&& afterLateBeat.startsWith("REVOKED|") && revoked.equals(afterLateEnd),
					"generation-fenced Lua rejects both stale beat and stale end");

			if (support.faultEnabled("get")) {
				watchdogRecheckFailure(support, dataset, properties);
			}
			else {
				support.faultSkipped(dataset, "get");
			}
			support.require(dataset, "watchdog_no_keyspace_scan", scansBefore == support.commandMetrics.keyspaceScanPages(),
					"single-flight and recheck use exact job keys only");
			dataset.state = "measured";
		}
		finally {
			releaseTerminal.countDown();
			pool.shutdownNow();
			P1ScenarioSupport.stopTicker(owner);
			P1ScenarioSupport.stopTicker(judge);
			P1ScenarioSupport.stopTicker(peer);
		}
	}

	private static void watchdogRecheckFailure(ProductionSupport support, Manifest.Dataset dataset,
			AppProperties properties) {
		long jobId = support.jobId(dataset, 2);
		Job job = P1ScenarioSupport.job(jobId, 92L, Job.JobStatus.RUNNING);
		AtomicInteger authorityReads = new AtomicInteger();
		JobRepository repository = mock(JobRepository.class);
		when(repository.findByStatusIn(anyCollection())).thenReturn(List.of(job));
		when(repository.findCancelStateById(anyLong())).thenAnswer(invocation -> {
			if (authorityReads.incrementAndGet() == 2) {
				P1ScenarioSupport.armFault(support, dataset, "get", JobHeartbeatRegistry.BEAT_PREFIX + jobId);
			}
			return Optional.of(P1ScenarioSupport.cancelState(Job.JobStatus.RUNNING, 0L));
		});
		JobCancellationRegistry cancellations = new JobCancellationRegistry(support.redis, repository, properties);
		JobHeartbeatRegistry owner = new JobHeartbeatRegistry(support.redis, "redis", properties, repository, cancellations);
		JobHeartbeatRegistry judge = new JobHeartbeatRegistry(support.redis, "redis", properties, repository, cancellations);
		JobTerminalWriter terminal = mock(JobTerminalWriter.class);
		ValidationIssueRepository issues = mock(ValidationIssueRepository.class);
		JobWatchdog watchdog = new JobWatchdog(repository, judge, terminal, cancellations, issues, properties);
		try {
			support.require(dataset, "recheck_owner_started", owner.beginRunner(jobId), "second real generation registered");
			P1ScenarioSupport.stopTicker(owner);
			support.redis.delete(JobHeartbeatRegistry.BEAT_PREFIX + jobId);
			P1ScenarioSupport.stage(support, dataset, "watchdog_recheck_read_failure", () ->
					P1ScenarioSupport.scanWatchdog(watchdog));
			support.require(dataset, "recheck_unavailable_aborts_irreversible_writes",
					((Number) watchdog.metrics().get("judgedDeadTotal")).longValue() == 0L
							&& ((Number) watchdog.metrics().get("skippedUnavailableTotal")).longValue() >= 1L,
					"the second heartbeat read fails closed after the lease was acquired");
			verify(issues, times(0)).save(any());
			verify(terminal, times(0)).complete(any(Job.class), any(), anyInt(), anyInt(), anyInt(), anyInt(), anyMap());
		}
		finally {
			support.faults.clear();
			owner.endRunner(jobId);
			P1ScenarioSupport.stopTicker(owner);
			P1ScenarioSupport.stopTicker(judge);
		}
	}

}

final class TaskDeleteSlowRedisLockHoldScenario implements ProductionScenario {

	@Override
	public String id() {
		return "task_delete_slow_redis_lock_hold";
	}

	@Override
	public Manifest.Dataset generate(ProductionSupport support, Manifest.Dataset dataset) {
		for (int i = 11; i <= 14; i++) {
			P1ScenarioSupport.ownJob(support, dataset, support.jobId(dataset, i));
		}
		dataset.attributes.put("historyJobIds", List.of(support.jobId(dataset, 11), support.jobId(dataset, 12),
				support.jobId(dataset, 13), support.jobId(dataset, 14)));
		return dataset;
	}

	@Override
	public void measure(ProductionSupport support, Manifest.Dataset dataset) {
		long scansBefore = support.commandMetrics.keyspaceScanPages();
		long taskId = support.jobId(dataset, 10);
		@SuppressWarnings("unchecked")
		List<Long> jobIds = (List<Long>) dataset.attributes.get("historyJobIds");
		TaskRepository tasks = mock(TaskRepository.class);
		TaskItemRepository taskItems = mock(TaskItemRepository.class);
		TaskFileRepository taskFiles = mock(TaskFileRepository.class);
		JobRepository jobs = mock(JobRepository.class);
		JobItemRepository jobItems = mock(JobItemRepository.class);
		ValidationIssueRepository issues = mock(ValidationIssueRepository.class);
		ConfigStagingRowRepository staging = mock(ConfigStagingRowRepository.class);
		FileStorageService files = mock(FileStorageService.class);
		Task task = new Task();
		task.setId(taskId);
		task.setType(Task.TaskType.EXPORT);
		task.setStatus(Task.TaskStatus.COMPLETED);
		AtomicLong lockedAt = new AtomicLong();
		AtomicLong postGuardAt = new AtomicLong();
		when(tasks.findByIdForUpdate(taskId)).thenAnswer(invocation -> {
			lockedAt.set(System.nanoTime());
			return Optional.of(task);
		});
		List<Job> history = jobIds.stream().map(id -> P1ScenarioSupport.job(id, taskId, Job.JobStatus.CANCELLED)).toList();
		when(jobs.findByTaskId(taskId)).thenReturn(history);
		when(jobs.findCancelStateById(anyLong())).thenAnswer(invocation -> Optional.of(
				P1ScenarioSupport.cancelState(Job.JobStatus.CANCELLED, 1L)));
		when(taskFiles.findByTaskId(taskId)).thenAnswer(invocation -> {
			postGuardAt.set(System.nanoTime());
			return List.of();
		});
		when(staging.findByTaskId(taskId)).thenReturn(List.of());
		AppProperties properties = P1ScenarioSupport.jobProperties();
		JobCancellationRegistry cancellations = new JobCancellationRegistry(support.redis, jobs, properties);
		JobHeartbeatRegistry heartbeat = new JobHeartbeatRegistry(support.redis, "redis", properties, jobs, cancellations);
		TaskService service = new TaskService(tasks, taskItems, taskFiles, jobs, heartbeat, jobItems, issues, staging,
				files, support.mapper, mock(PlatformTransactionManager.class));
		try {
			CountingRedis.Snapshot beforeDelete = support.commandMetrics.snapshot();
			P1ScenarioSupport.stage(support, dataset, "delete_with_four_terminal_history_jobs", () -> service.delete(taskId));
			CountingRedis.Snapshot deleteDelta = CountingRedis.delta(beforeDelete, support.commandMetrics.snapshot());
			support.require(dataset, "all_terminal_history_checked_before_delete", deleteDelta.commands() == history.size()
					&& postGuardAt.get() >= lockedAt.get() && lockedAt.get() > 0L,
					"four exact runner-alive GETs occur between the Mockito task-lock invocation and cascade, commands="
							+ deleteDelta.commands());
			verify(tasks, times(1)).deleteById(taskId);
			dataset.attributes.put("historyJobs", history.size());
			dataset.attributes.put("deleteGuardCommands", deleteDelta.commands());
			dataset.attributes.put("deleteGuardRequestBytes", deleteDelta.requestBytes());
			dataset.attributes.put("mockitoLockInvocationToPostGuardNanos", postGuardAt.get() - lockedAt.get());
			dataset.attributes.put("dbRowLockDurationCovered", false);
			dataset.attributes.put("dbRowLockDurationReason",
					"Mockito task lock stub; measured nanos cover only the service invocation segment, not a real DB row lock");

			String activeKey = JobHeartbeatRegistry.RUNNER_ACTIVE_PREFIX + jobIds.get(2);
			support.redis.opsForValue().set(activeKey, "runner-perf-other-pid999-oldgen|1", support.ttl());
			ConflictException activeConflict = expectConflict(() -> service.delete(taskId));
			support.require(dataset, "terminal_but_live_old_runner_blocks_delete", activeConflict != null
					&& ErrorCodes.JOB_ALREADY_RUNNING.equals(activeConflict.getCode()),
					"an older CANCELLED row still blocks deletion while its runner is alive");
			verify(tasks, times(1)).deleteById(taskId);
			support.redis.delete(activeKey);

			AtomicReferenceConflict conflict = new AtomicReferenceConflict();
			boolean injected = P1ScenarioSupport.fault(support, dataset, "get", activeKey, () ->
					P1ScenarioSupport.stage(support, dataset, "delete_runner_probe_failure", () ->
							conflict.value = expectConflict(() -> service.delete(taskId))));
			if (injected) {
				support.require(dataset, "runner_probe_unknown_fails_closed", conflict.value != null
						&& ErrorCodes.JOB_ALREADY_RUNNING.equals(conflict.value.getCode()),
						"Redis UNKNOWN is never treated as no live runner");
			}
			verify(tasks, times(1)).deleteById(taskId);
			if (support.faultEnabled("get")) {
				int delayMs = support.intEnv("PERF_TASK_REDIS_DELAY_MS", 80);
				if (delayMs < 1 || delayMs > 2_000) {
					throw new IllegalArgumentException("PERF_TASK_REDIS_DELAY_MS must be within 1..2000");
				}
				dataset.attributes.put("taskRedisDelayMs", delayMs);
				org.springframework.data.redis.core.StringRedisTemplate slowRedis = P1ScenarioSupport.boundaryRedis(
						support, dataset, "get", activeKey, delayMs, true);
				JobCancellationRegistry slowCancellations = new JobCancellationRegistry(slowRedis, jobs, properties);
				JobHeartbeatRegistry slowHeartbeat = new JobHeartbeatRegistry(slowRedis, "redis", properties, jobs,
						slowCancellations);
				TaskService slowService = new TaskService(tasks, taskItems, taskFiles, jobs, slowHeartbeat, jobItems, issues,
						staging, files, support.mapper, mock(PlatformTransactionManager.class));
				try {
					AtomicReferenceConflict slowConflict = new AtomicReferenceConflict();
					long started = System.nanoTime();
					P1ScenarioSupport.stage(support, dataset, "slow_redis_delete_fail_closed", () ->
							slowConflict.value = expectConflict(() -> slowService.delete(taskId)));
					long elapsed = System.nanoTime() - started;
					dataset.attributes.put("slowRedisDeleteFailureNanos", elapsed);
					support.require(dataset, "slow_redis_failure_is_fail_closed", slowConflict.value != null
							&& ErrorCodes.JOB_ALREADY_RUNNING.equals(slowConflict.value.getCode())
							&& elapsed >= delayMs * 1_000_000L,
							"the targeted GET really waits at the connection boundary, fails, and never authorizes deletion");
					verify(tasks, times(1)).deleteById(taskId);
					P1ScenarioSupport.stage(support, dataset, "slow_redis_followup_recovery", () -> service.delete(taskId));
					verify(tasks, times(2)).deleteById(taskId);
				}
				finally {
					P1ScenarioSupport.stopTicker(slowHeartbeat);
				}
			}
			else {
				support.faultSkipped(dataset, "get");
			}
			support.require(dataset, "task_delete_no_keyspace_scan", scansBefore == support.commandMetrics.keyspaceScanPages(),
					"history checks use exact runner keys, never SCAN");
			dataset.state = "measured";
		}
		finally {
			P1ScenarioSupport.stopTicker(heartbeat);
		}
	}

	private static ConflictException expectConflict(Runnable body) {
		try {
			body.run();
			return null;
		}
		catch (ConflictException ex) {
			return ex;
		}
	}

	private static final class AtomicReferenceConflict {
		private ConflictException value;
	}

}
