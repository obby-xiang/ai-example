package com.example.configmgr.perf.production;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.run.LeaseService;
import com.example.configmgr.ai.run.ResumeService;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.StartupResumeRunner;
import com.example.configmgr.ai.tenant.RunKeyScope;
import com.example.configmgr.config.AppProperties;
import com.example.configmgr.config.RedisClusterTopologyGuard;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.job.repo.ValidationIssueRepository;
import com.example.configmgr.job.service.JobCancellationRegistry;
import com.example.configmgr.job.service.JobHeartbeatRegistry;
import com.example.configmgr.job.service.JobTerminalWriter;
import com.example.configmgr.job.service.JobWatchdog;
import com.example.configmgr.perf.redis.CountingRedis;
import com.example.configmgr.perf.redis.HarnessConfig;
import com.example.configmgr.perf.redis.Manifest;

import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.env.MockEnvironment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class StartupResumeCandidateStormScenario implements ProductionScenario {

	@Override
	public String id() {
		return "startup_resume_candidate_storm";
	}

	@Override
	public Manifest.Dataset generate(ProductionSupport support, Manifest.Dataset dataset) {
		AiProperties properties = recoveryProperties(2, 2);
		RunStore store = P1ScenarioSupport.runStore(support, properties, null);
		for (int i = 1; i <= 4; i++) {
			String runId = dataset.sessionId + "-candidate-" + i;
			P1ScenarioSupport.seedRun(support, dataset, store, runId, RunSnapshot.SUSPENDED);
			makeDue(support, dataset.sessionId + "-candidate-" + i, store);
		}
		List<Long> jobIds = List.of(support.jobId(dataset, 101), support.jobId(dataset, 102));
		for (Long jobId : jobIds) {
			P1ScenarioSupport.ownJob(support, dataset, jobId);
		}
		support.own(dataset, "ai:lock:resume-scan");
		P1ScenarioSupport.ownGlobalEpoch(support, dataset);
		dataset.attributes.put("candidateCount", 4);
		dataset.attributes.put("candidatePageLimit", 2);
		dataset.attributes.put("jobCandidateIds", jobIds);
		dataset.attributes.put("concurrentStartupRunners", 2);
		dataset.attributes.put("scaleClaim", "small-sample protocol and fairness only; no 1k/10k performance claim");
		return dataset;
	}

	@Override
	public void measure(ProductionSupport support, Manifest.Dataset dataset) {
		long scansBefore = support.commandMetrics.keyspaceScanPages();
		AiProperties properties = recoveryProperties(2, 2);
		LeaseService leasesA = P1ScenarioSupport.lease(support, properties, dataset.sessionId + "-scan-a");
		LeaseService leasesB = P1ScenarioSupport.lease(support, properties, dataset.sessionId + "-scan-b");
		RunStore storeA = P1ScenarioSupport.runStore(support, properties, leasesA);
		RunStore storeB = P1ScenarioSupport.runStore(support, properties, leasesB);
		ResumeService serviceA = spy(P1ScenarioSupport.resumeService(support, storeA, properties));
		ResumeService serviceB = spy(P1ScenarioSupport.resumeService(support, storeB, properties));
		CountDownLatch firstResume = new CountDownLatch(1);
		CountDownLatch releaseResume = new CountDownLatch(1);
		AtomicBoolean blockFirst = new AtomicBoolean(true);
		doAnswer(invocation -> {
			if (blockFirst.compareAndSet(true, false)) {
				firstResume.countDown();
				if (!releaseResume.await(5L, TimeUnit.SECONDS)) {
					throw new IllegalStateException("candidate competition barrier timed out");
				}
			}
			return new ResumeService.ResumeResult(ResumeService.Outcome.ACCEPTED, Map.of());
		}).when(serviceA).resume(anyString(), anyBoolean());
		doAnswer(invocation -> new ResumeService.ResumeResult(ResumeService.Outcome.ACCEPTED, Map.of()))
				.when(serviceB).resume(anyString(), anyBoolean());
		StartupResumeRunner runnerA = new StartupResumeRunner(serviceA, storeA, properties, P1ScenarioSupport.available());
		StartupResumeRunner runnerB = new StartupResumeRunner(serviceB, storeB, properties, P1ScenarioSupport.available());
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			AtomicReference<Map<String, Object>> firstReport = new AtomicReference<>();
			AtomicReference<Map<String, Object>> deferredReport = new AtomicReference<>();
			P1ScenarioSupport.stage(support, dataset, "two_real_startup_runners_compete", () -> {
				Future<Map<String, Object>> first = pool.submit(() -> runnerA.scan(true));
				P1ScenarioSupport.await(() -> firstResume.getCount() == 0L, 2_000L,
						"the first real runner holds the scan lease inside resume");
				Future<Map<String, Object>> second = pool.submit(() -> runnerB.scan(true));
				deferredReport.set(get(second));
				releaseResume.countDown();
				firstReport.set(get(first));
			});
			support.require(dataset, "concurrent_peer_defers_without_fake_empty_scan",
					Boolean.TRUE.equals(deferredReport.get().get("deferred"))
							&& "SCAN_IN_FLIGHT".equals(deferredReport.get().get("reason")),
					"the second real runner yields while the first owns the scan flight");
			Map<String, Object> second = P1ScenarioSupport.stage(support, dataset, "deferred_runner_next_bounded_page",
					() -> runnerB.scan(true));
			List<String> firstIds = P1ScenarioSupport.runIds(firstReport.get(), "resumed");
			List<String> secondIds = P1ScenarioSupport.runIds(second, "resumed");
			Set<String> union = new HashSet<>();
			union.addAll(firstIds);
			union.addAll(secondIds);
			support.require(dataset, "bounded_candidate_pages", firstIds.size() == 2 && secondIds.size() == 2
					&& ((Number) firstReport.get().get("candidates")).intValue() <= 2
					&& ((Number) second.get("candidates")).intValue() <= 2,
					"the winning runner and the deferred runner each process at most two candidates");
			support.require(dataset, "rotation_eventually_covers_every_candidate", union.size() == 4
					&& firstIds.stream().noneMatch(secondIds::contains),
					"the deferred real runner receives the remaining page instead of starving the tail");
			support.require(dataset, "singleflight_released_between_scans", !support.redis.hasKey("ai:lock:resume-scan"),
					"both ScanFlights stop renewal and release their exact owners");
			dataset.attributes.put("firstPageRunIds", firstIds);
			dataset.attributes.put("secondPageRunIds", secondIds);
			jobCandidateCompetition(support, dataset);
			support.require(dataset, "candidate_discovery_no_keyspace_scan", scansBefore == support.commandMetrics.keyspaceScanPages(),
					"AI and job candidate competition uses exact keys and bounded ZSET pages, never SCAN");
			dataset.state = "measured";
		}
		finally {
			releaseResume.countDown();
			pool.shutdownNow();
			runnerA.stopRuntimeSweep();
			runnerB.stopRuntimeSweep();
			P1ScenarioSupport.shutdownLease(leasesA);
			P1ScenarioSupport.shutdownLease(leasesB);
		}
	}

	private static void jobCandidateCompetition(ProductionSupport support, Manifest.Dataset dataset) {
		@SuppressWarnings("unchecked")
		List<Long> jobIds = (List<Long>) dataset.attributes.get("jobCandidateIds");
		JobRepository repository = mock(JobRepository.class);
		List<Job> jobs = jobIds.stream().map(id -> P1ScenarioSupport.job(id, 101L, Job.JobStatus.RUNNING)).toList();
		when(repository.findByStatusIn(anyCollection())).thenReturn(jobs);
		when(repository.findCancelStateById(anyLong())).thenAnswer(invocation -> Optional.of(
				P1ScenarioSupport.cancelState(Job.JobStatus.RUNNING, 0L)));
		AppProperties properties = P1ScenarioSupport.jobProperties();
		JobCancellationRegistry cancellations = new JobCancellationRegistry(support.redis, repository, properties);
		JobHeartbeatRegistry heartbeatA = new JobHeartbeatRegistry(support.redis, "redis", properties, repository,
				cancellations);
		JobHeartbeatRegistry heartbeatB = new JobHeartbeatRegistry(support.redis, "redis", properties, repository,
				cancellations);
		JobTerminalWriter terminal = mock(JobTerminalWriter.class);
		ValidationIssueRepository issues = mock(ValidationIssueRepository.class);
		JobWatchdog watchdogA = new JobWatchdog(repository, heartbeatA, terminal, cancellations, issues, properties);
		JobWatchdog watchdogB = new JobWatchdog(repository, heartbeatB, terminal, cancellations, issues, properties);
		CountDownLatch judgementLeaseHeld = new CountDownLatch(1);
		CountDownLatch releaseJudgement = new CountDownLatch(1);
		AtomicBoolean blockFirstJudgement = new AtomicBoolean(true);
		doAnswer(invocation -> {
			if (blockFirstJudgement.compareAndSet(true, false)) {
				judgementLeaseHeld.countDown();
				if (!releaseJudgement.await(5L, TimeUnit.SECONDS)) {
					throw new IllegalStateException("job judgement barrier timed out");
				}
			}
			return null;
		}).when(terminal).complete(any(Job.class), eq(Job.JobStatus.FAILED), anyInt(), anyInt(), anyInt(), anyInt(),
				anyMap());
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			for (Long jobId : jobIds) {
				support.redis.opsForValue().set(JobHeartbeatRegistry.RUNNER_ACTIVE_PREFIX + jobId,
						"runner-perf-candidate-pid999-old|" + jobId + "|1", support.ttl());
				support.redis.delete(JobHeartbeatRegistry.BEAT_PREFIX + jobId);
			}
			P1ScenarioSupport.stage(support, dataset, "two_job_watchdogs_small_candidate_set", () -> {
				Future<?> first = pool.submit(() -> P1ScenarioSupport.scanWatchdog(watchdogA));
				P1ScenarioSupport.await(() -> judgementLeaseHeld.getCount() == 0L, 2_000L,
						"watchdog A must hold the per-job judgement lease before watchdog B starts");
				Future<?> second = pool.submit(() -> P1ScenarioSupport.scanWatchdog(watchdogB));
				get(second);
				releaseJudgement.countDown();
				get(first);
			});
			long judged = ((Number) watchdogA.metrics().get("judgedDeadTotal")).longValue()
					+ ((Number) watchdogB.metrics().get("judgedDeadTotal")).longValue();
			long peerSkipped = ((Number) watchdogB.metrics().get("skippedNotLeaderTotal")).longValue();
			support.require(dataset, "job_candidates_singleflight_exactly_once", judged == 2L && peerSkipped >= 1L,
					"watchdog B starts while A holds the first judgement lease, skips it as HELD, and each job is judged once");
			dataset.attributes.put("concurrentJobWatchdogOverlap", true);
			dataset.attributes.put("jobCandidatePeerSkippedNotLeader", peerSkipped);
			verify(issues, times(2)).save(any());
			verify(terminal, times(2)).complete(any(Job.class), eq(Job.JobStatus.FAILED), anyInt(), anyInt(), anyInt(),
					anyInt(), anyMap());
			dataset.attributes.put("jobCandidateJudgements", judged);
		}
		finally {
			releaseJudgement.countDown();
			pool.shutdownNow();
			P1ScenarioSupport.stopTicker(heartbeatA);
			P1ScenarioSupport.stopTicker(heartbeatB);
		}
	}

	private static <T> T get(Future<T> future) {
		try {
			return future.get(5L, TimeUnit.SECONDS);
		}
		catch (Exception ex) {
			throw new IllegalStateException("candidate runner future failed", ex);
		}
	}

	static AiProperties recoveryProperties(int maxOnStartup, int scanMaxAttempts) {
		AiProperties properties = new AiProperties();
		properties.getResume().setMaxOnStartup(maxOnStartup);
		properties.getResume().setScanMaxAttempts(scanMaxAttempts);
		properties.getResume().setScanLeaseTtl(Duration.ofMillis(500L));
		properties.getStream().setIndexPruneInterval(Duration.ZERO);
		return properties;
	}

	static void makeDue(ProductionSupport support, String runId, RunStore store) {
		String member = RunKeyScope.indexMember(support.cfg.tenantId, support.cfg.userId, runId);
		support.redis.opsForZSet().add(RunStore.RECOVERY_DUE_INDEX, member, 0L);
		support.redis.opsForZSet().add(RunStore.RECOVERY_DUE_IDENTITY_PREFIX + support.cfg.tenantId + ":"
				+ support.cfg.userId, runId, 0L);
	}

}

final class StartupDependencyFailureMatrixScenario implements ProductionScenario {

	@Override
	public String id() {
		return "startup_dependency_failure_matrix";
	}

	@Override
	public Manifest.Dataset generate(ProductionSupport support, Manifest.Dataset dataset) {
		AiProperties properties = StartupResumeCandidateStormScenario.recoveryProperties(2, 2);
		RunStore store = P1ScenarioSupport.runStore(support, properties, null);
		for (int i = 1; i <= 2; i++) {
			String runId = dataset.sessionId + "-dependency-" + i;
			P1ScenarioSupport.seedRun(support, dataset, store, runId, com.example.configmgr.ai.run.RunSnapshot.SUSPENDED);
			StartupResumeCandidateStormScenario.makeDue(support, runId, store);
		}
		support.own(dataset, "ai:lock:resume-scan");
		P1ScenarioSupport.ownGlobalEpoch(support, dataset);
		P1ScenarioSupport.ownJob(support, dataset, support.jobId(dataset, 201));
		dataset.attributes.put("liveJobId", support.jobId(dataset, 201));
		return dataset;
	}

	@Override
	public void measure(ProductionSupport support, Manifest.Dataset dataset) {
		long scansBefore = support.commandMetrics.keyspaceScanPages();
		AiProperties properties = StartupResumeCandidateStormScenario.recoveryProperties(2, 2);
		RunStore unavailableStore = P1ScenarioSupport.runStore(support, properties, null);
		StartupResumeRunner unavailable = new StartupResumeRunner(
				P1ScenarioSupport.resumeService(support, unavailableStore, properties), unavailableStore, properties,
				P1ScenarioSupport.unavailable());
		Map<String, Object> unavailableReport = P1ScenarioSupport.stage(support, dataset, "ai_unavailable_short_circuit",
				() -> unavailable.scan(true));
		support.require(dataset, "ai_unavailable_does_not_start_scan", unavailableReport.containsKey("reason")
				&& P1ScenarioSupport.runIds(unavailableReport, "resumed").isEmpty()
				&& !support.redis.hasKey("ai:lock:resume-scan"), "provider unavailability leaves Redis discovery untouched");

		if (support.cfg.mode == HarnessConfig.Mode.STANDALONE) {
			dataset.attributes.put("singleMasterCoverage", "SINGLE_NODE_ONLY");
			dataset.attributes.put("coverageLimit",
					"standalone proves only single-node PING failure and bounded negative cache; no slot/master targeting claim");
			RedisAvailability nodeAvailability = new RedisAvailability(support.redis);
			support.require(dataset, "standalone_single_node_baseline_available", nodeAvailability.isAvailable(),
					"the standalone node answers PING before the connection failure");
			AtomicReference<Boolean> nodeDown = new AtomicReference<>();
			boolean nodeInjected = P1ScenarioSupport.fault(support, dataset, "ping", "", () ->
					P1ScenarioSupport.stage(support, dataset, "standalone_single_node_unavailable", () ->
							nodeDown.set(!nodeAvailability.isAvailable())));
			if (nodeInjected) {
				long probes = ((Number) nodeAvailability.describe().get("probes")).longValue();
				support.require(dataset, "standalone_single_node_failure_is_bounded", nodeDown.get()
						&& !nodeAvailability.isAvailable()
						&& ((Number) nodeAvailability.describe().get("probes")).longValue() == probes,
						"standalone single-node connection failure is DOWN and its negative cache does not retry in a storm");
			}
		}
		else {
			dataset.attributes.put("singleMasterCoverage", "PARTIAL");
			dataset.attributes.put("coverageLimit",
					"Cluster PING has no slot-to-master routing evidence; targeted master outage is not covered and no PASS is recorded");
			support.faultSkipped(dataset, "ping");
		}

		LeaseService leases = P1ScenarioSupport.lease(support, properties, dataset.sessionId + "-failure-owner");
		RunStore store = P1ScenarioSupport.runStore(support, properties, leases);
		ResumeService service = spy(P1ScenarioSupport.resumeService(support, store, properties));
		doAnswer(invocation -> new ResumeService.ResumeResult(ResumeService.Outcome.ACCEPTED, Map.of()))
				.when(service).resume(anyString(), anyBoolean());
		StartupResumeRunner runner = new StartupResumeRunner(service, store, properties, P1ScenarioSupport.available());
		try {
			AtomicReference<Map<String, Object>> failed = new AtomicReference<>();
			boolean discoveryInjected = P1ScenarioSupport.fault(support, dataset, "zrangebyscore",
					RunStore.RECOVERY_DUE_INDEX, () -> P1ScenarioSupport.stage(support, dataset,
							"discovery_dependency_failure", () -> failed.set(runner.scan(true))));
			if (discoveryInjected) {
				support.require(dataset, "discovery_failure_is_explicit", failed.get().get("error") != null
						&& P1ScenarioSupport.runIds(failed.get(), "resumed").isEmpty()
						&& !support.redis.hasKey("ai:lock:resume-scan"),
						"the report contains the real failure and the scan lease is still released");
				CountingRedis.Snapshot beforeCached = support.commandMetrics.snapshot();
				Map<String, Object> cached = P1ScenarioSupport.stage(support, dataset, "non_forced_scan_after_failure",
						() -> runner.scan(false));
				CountingRedis.Snapshot cachedDelta = CountingRedis.delta(beforeCached, support.commandMetrics.snapshot());
				support.require(dataset, "startup_failure_is_not_falsely_retried_as_empty_success", cached.get("error") != null
						&& cachedDelta.commands() == 0L,
						"scan(false) returns the cached failure; only an explicit later scan retries discovery");
				dataset.attributes.put("automaticRetryAfterDiscoveryFailure", false);
				RunSnapshot preserved = support.identity(() -> store.get(dataset.runIds.get(0)));
				support.require(dataset, "discovery_failure_does_not_misjudge_live_run", preserved != null
						&& RunSnapshot.SUSPENDED.equals(preserved.getStatus()),
						"the AI run remains SUSPENDED and is not resumed, deleted or marked terminal by discovery failure");
			}

			liveRunnerReadFailure(support, dataset, support.jobId(dataset, 201));
			Map<String, Object> recovered = P1ScenarioSupport.stage(support, dataset, "discovery_recovery_forced_scan",
					() -> runner.scan(true));
			support.require(dataset, "forced_scan_recovers_after_dependency_returns", recovered.get("error") == null
					&& P1ScenarioSupport.runIds(recovered, "resumed").size() == 2,
					"the next real forced scan reads the same due candidates and releases the lease");

			LeaseService degradedLeases = P1ScenarioSupport.lease(support, properties, dataset.sessionId + "-degraded-owner");
			try {
				RunStore degradedStore = P1ScenarioSupport.runStore(support, properties, degradedLeases);
				ResumeService degradedService = spy(P1ScenarioSupport.resumeService(support, degradedStore, properties));
				doAnswer(invocation -> new ResumeService.ResumeResult(ResumeService.Outcome.ACCEPTED, Map.of()))
						.when(degradedService).resume(anyString(), anyBoolean());
				StartupResumeRunner degraded = new StartupResumeRunner(degradedService, degradedStore, properties,
						P1ScenarioSupport.available());
				AtomicReference<Map<String, Object>> degradedReport = new AtomicReference<>();
				boolean leaseInjected = P1ScenarioSupport.fault(support, dataset, "eval", "ai:lock:resume-scan", () ->
						P1ScenarioSupport.stage(support, dataset, "scan_lease_unavailable_fallback", () ->
								degradedReport.set(degraded.scan(true))));
				if (leaseInjected) {
					support.require(dataset, "scan_lease_unavailable_is_local_singleflight_only",
							"UNAVAILABLE".equals(degradedReport.get().get("scanLease"))
									&& degradedReport.get().get("error") == null,
							"the report never claims global single-flight while the lease command failed");
				}
			}
			finally {
				P1ScenarioSupport.shutdownLease(degradedLeases);
			}

			for (String runId : dataset.runIds) {
				StartupResumeCandidateStormScenario.makeDue(support, runId, store);
			}
			ResumeService actionService = spy(P1ScenarioSupport.resumeService(support, store, properties));
			String failingRunId = dataset.runIds.get(0);
			doAnswer(invocation -> {
				String runId = invocation.getArgument(0);
				if (failingRunId.equals(runId)) {
					throw new IllegalStateException("PERF_ACTION_FAILURE");
				}
				return new ResumeService.ResumeResult(ResumeService.Outcome.ACCEPTED, Map.of());
			}).when(actionService).resume(anyString(), anyBoolean());
			StartupResumeRunner actionRunner = new StartupResumeRunner(actionService, store, properties,
					P1ScenarioSupport.available());
			Map<String, Object> actionReport = P1ScenarioSupport.stage(support, dataset, "individual_action_failure",
					() -> actionRunner.scan(true));
			List<Map<String, Object>> actionItems = resumedItems(actionReport);
			int failures = 0;
			int accepted = 0;
			for (Map<String, Object> item : actionItems) {
				String outcome = String.valueOf(item.get("outcome"));
				String runId = String.valueOf(item.get("runId"));
				if (failingRunId.equals(runId) && "EXCEPTION".equals(outcome)) {
					failures++;
				}
				if (!failingRunId.equals(runId) && "ACCEPTED".equals(outcome)) {
					accepted++;
				}
			}
			support.require(dataset, "one_action_failure_does_not_hide_other_results", failures == 1 && accepted == 1
					&& actionItems.size() == 2,
					"only the designated run fails; the other real run is ACCEPTED, failingRunId=" + failingRunId);
			slowCommandRecovery(support, dataset, properties);
			topologyFailures(support, dataset);
			support.require(dataset, "dependency_matrix_no_keyspace_scan", scansBefore == support.commandMetrics.keyspaceScanPages(),
					"all failure paths use exact keys and bounded ZSET reads");
			dataset.state = "measured";
		}
		finally {
			runner.stopRuntimeSweep();
			P1ScenarioSupport.shutdownLease(leases);
		}
	}

	private static void liveRunnerReadFailure(ProductionSupport support, Manifest.Dataset dataset, long jobId) {
		if (!support.faultEnabled("get")) {
			support.faultSkipped(dataset, "get");
			return;
		}
		JobRepository repository = mock(JobRepository.class);
		when(repository.findCancelStateById(anyLong())).thenReturn(Optional.of(
				P1ScenarioSupport.cancelState(Job.JobStatus.RUNNING, 0L)));
		AppProperties properties = P1ScenarioSupport.jobProperties();
		JobCancellationRegistry cancellations = new JobCancellationRegistry(support.redis, repository, properties);
		JobHeartbeatRegistry heartbeat = new JobHeartbeatRegistry(support.redis, "redis", properties, repository,
				cancellations);
		try {
			support.require(dataset, "live_runner_fixture_started", heartbeat.beginRunner(jobId),
					"the live job has a real active generation and heartbeat");
			P1ScenarioSupport.stopTicker(heartbeat);
			AtomicReference<JobHeartbeatRegistry.SharedBeat> state = new AtomicReference<>();
			boolean injected = P1ScenarioSupport.fault(support, dataset, "get", JobHeartbeatRegistry.BEAT_PREFIX + jobId,
					() -> P1ScenarioSupport.stage(support, dataset, "live_runner_targeted_beat_failure", () ->
							state.set(heartbeat.readSharedBeat(jobId, 1L))));
			if (injected) {
				support.require(dataset, "live_runner_read_failure_is_not_stale", state.get() != null
						&& state.get().state() == JobHeartbeatRegistry.SharedBeatState.UNAVAILABLE
						&& heartbeat.isRunnerAlive(jobId),
						"targeted connection failure is UNAVAILABLE, never a dead-runner STALE verdict");
			}
		}
		finally {
			heartbeat.endRunner(jobId);
			P1ScenarioSupport.stopTicker(heartbeat);
		}
	}

	private static void slowCommandRecovery(ProductionSupport support, Manifest.Dataset dataset,
			AiProperties properties) {
		if (!support.faultEnabled("zrangebyscore")) {
			support.faultSkipped(dataset, "zrangebyscore");
			return;
		}
		StringRedisTemplate slowRedis = P1ScenarioSupport.boundaryRedis(support, dataset, "zrangebyscore",
				RunStore.RECOVERY_DUE_INDEX, 75L, false);
		LeaseService slowLeases = new LeaseService(slowRedis, properties, (task, interval) -> () -> { },
				dataset.sessionId + "-slow-owner");
		RunStore slowStore = new RunStore(slowRedis, support.mapper, properties, slowLeases);
		for (String runId : dataset.runIds) {
			StartupResumeCandidateStormScenario.makeDue(support, runId, slowStore);
		}
		ResumeService slowService = spy(P1ScenarioSupport.resumeService(support, slowStore, properties, slowRedis));
		doAnswer(invocation -> new ResumeService.ResumeResult(ResumeService.Outcome.ACCEPTED, Map.of()))
				.when(slowService).resume(anyString(), anyBoolean());
		StartupResumeRunner slowRunner = new StartupResumeRunner(slowService, slowStore, properties,
				P1ScenarioSupport.available());
		try {
			AtomicReference<Map<String, Object>> report = new AtomicReference<>();
			long started = System.nanoTime();
			P1ScenarioSupport.stage(support, dataset, "delayed_recovery_command_then_success", () ->
					report.set(slowRunner.scan(true)));
			long elapsed = System.nanoTime() - started;
			dataset.attributes.put("slowRecoveryCommandNanos", elapsed);
			support.require(dataset, "slow_command_is_real_delay_then_bounded_success", elapsed >= 50_000_000L
					&& report.get().get("error") == null && P1ScenarioSupport.runIds(report.get(), "resumed").size() == 2,
					"the ZSET command waits at the Lettuce connection boundary, then executes and recovers");
		}
		finally {
			slowRunner.stopRuntimeSweep();
			P1ScenarioSupport.shutdownLease(slowLeases);
		}
	}

	private static void topologyFailures(ProductionSupport support, Manifest.Dataset dataset) {
		P1ScenarioSupport.stage(support, dataset, "missing_and_invalid_cluster_seeds", () -> {
			boolean missing = P1ScenarioSupport.failure(() -> new RedisClusterTopologyGuard(new MockEnvironment()));
			MockEnvironment malformed = new MockEnvironment().withProperty(RedisClusterTopologyGuard.NODES_PROPERTY,
					"127.0.0.1");
			boolean invalid = P1ScenarioSupport.failure(() -> new RedisClusterTopologyGuard(malformed));
			support.require(dataset, "missing_seed_fails_before_client_start", missing,
					"the production guard rejects an empty seed list instead of starting a client");
			support.require(dataset, "invalid_seed_shape_fails_before_client_start", invalid,
					"the production guard rejects a node without host:port shape");
		});

		new RedisClusterTopologyGuard(new MockEnvironment().withProperty(RedisClusterTopologyGuard.NODES_PROPERTY,
				"127.0.0.1:1"));
		RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration("127.0.0.1", 1);
		LettuceClientConfiguration client = LettuceClientConfiguration.builder()
				.commandTimeout(Duration.ofMillis(100L)).shutdownTimeout(Duration.ofMillis(50L)).build();
		LettuceConnectionFactory factory = new LettuceConnectionFactory(standalone, client);
		try {
			boolean startFailed = P1ScenarioSupport.failure(() -> {
				factory.afterPropertiesSet();
				factory.start();
			});
			if (startFailed) {
				dataset.attributes.put("wrongTopologyClientStartFailed", true);
				support.require(dataset, "wrong_topology_fails_during_bounded_client_start", startFailed,
						"the wrong endpoint fails during client startup instead of hanging the scenario");
				return;
			}
			StringRedisTemplate wrongTemplate = new StringRedisTemplate(factory);
			wrongTemplate.afterPropertiesSet();
			RedisAvailability wrongAvailability = new RedisAvailability(wrongTemplate);
			long started = System.nanoTime();
			boolean down = !wrongAvailability.isAvailable();
			long probes = ((Number) wrongAvailability.describe().get("probes")).longValue();
			boolean cachedDown = !wrongAvailability.isAvailable();
			long probesAfter = ((Number) wrongAvailability.describe().get("probes")).longValue();
			dataset.attributes.put("wrongTopologyProbeNanos", System.nanoTime() - started);
			support.require(dataset, "wrong_topology_is_bounded_runtime_down", down && cachedDown && probes == probesAfter,
					"the real wrong endpoint fails at connection time and the negative cache prevents an unbounded retry");
		}
		finally {
			factory.destroy();
		}
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> resumedItems(Map<String, Object> report) {
		return (List<Map<String, Object>>) report.getOrDefault("resumed", List.of());
	}

}

final class JobHeartbeatTickerHotScenario implements ProductionScenario {

	private static final int RUNNERS = 40;

	@Override
	public String id() {
		return "job_heartbeat_ticker_hot";
	}

	@Override
	public Manifest.Dataset generate(ProductionSupport support, Manifest.Dataset dataset) {
		List<Long> jobIds = new ArrayList<>();
		for (int i = 1; i <= RUNNERS; i++) {
			long jobId = support.jobId(dataset, i);
			jobIds.add(jobId);
			P1ScenarioSupport.ownJob(support, dataset, jobId);
		}
		dataset.attributes.put("jobIds", jobIds);
		dataset.attributes.put("runnerCount", RUNNERS);
		dataset.attributes.put("productionPipelineBatch", 32);
		return dataset;
	}

	@Override
	public void measure(ProductionSupport support, Manifest.Dataset dataset) {
		long scansBefore = support.commandMetrics.keyspaceScanPages();
		@SuppressWarnings("unchecked")
		List<Long> jobIds = (List<Long>) dataset.attributes.get("jobIds");
		Map<Long, JobRepository.CancelState> states = new ConcurrentHashMap<>();
		Set<Long> unknown = ConcurrentHashMap.newKeySet();
		for (Long jobId : jobIds) {
			states.put(jobId, P1ScenarioSupport.cancelState(Job.JobStatus.RUNNING, 0L));
		}
		JobRepository repository = mock(JobRepository.class);
		when(repository.findCancelStateById(anyLong())).thenAnswer(invocation -> {
			long jobId = invocation.getArgument(0);
			if (unknown.contains(jobId)) {
				throw new IllegalStateException("PERF_DB_UNKNOWN");
			}
			return Optional.ofNullable(states.get(jobId));
		});
		AppProperties properties = P1ScenarioSupport.jobProperties();
		JobCancellationRegistry cancellations = new JobCancellationRegistry(support.redis, repository, properties);
		JobHeartbeatRegistry heartbeat = new JobHeartbeatRegistry(support.redis, "redis", properties, repository,
				cancellations);
		try {
			P1ScenarioSupport.stage(support, dataset, "begin_40_real_runners", () -> {
				for (Long jobId : jobIds) {
					support.require(dataset, "begin_runner_" + jobId, heartbeat.beginRunner(jobId),
							"the production registry accepts an in-flight job");
				}
				support.require(dataset, "ticker_started_by_real_registration", P1ScenarioSupport.tickerRunning(heartbeat),
						"the first real runner starts the production ticker");
				P1ScenarioSupport.stopTicker(heartbeat);
			});
			CountingRedis.Snapshot beforeFullTick = support.commandMetrics.snapshot();
			P1ScenarioSupport.stage(support, dataset, "tick_40_batch_boundary", () -> P1ScenarioSupport.tickOnce(heartbeat));
			long fullTickCommands = CountingRedis.delta(beforeFullTick, support.commandMetrics.snapshot()).commands();
			dataset.attributes.put("fullTickCommands", fullTickCommands);
			support.require(dataset, "batch_32_two_pipeline_phases", fullTickCommands == RUNNERS * 3L,
					"40 runners emit 40 active + 40 beat + 40 host-index scripts in two pipeline phases, commands="
							+ fullTickCommands);
			for (Long jobId : jobIds) {
				String active = support.redis.opsForValue().get(JobHeartbeatRegistry.RUNNER_ACTIVE_PREFIX + jobId);
				Object indexed = support.redis.opsForHash().get(P1ScenarioSupport.hostIndex(), String.valueOf(jobId));
				support.require(dataset, "tick_generation_and_index_" + jobId, active != null && active.equals(indexed)
						&& support.redis.hasKey(JobHeartbeatRegistry.BEAT_PREFIX + jobId),
						"the active/beat keys and host index carry the same generation");
			}

			long terminal = jobIds.get(RUNNERS - 1);
			long unknownJob = jobIds.get(RUNNERS - 2);
			states.put(terminal, P1ScenarioSupport.cancelState(Job.JobStatus.COMPLETED, 1L));
			unknown.add(unknownJob);
			heartbeat.clear(terminal);
			heartbeat.clear(unknownJob);
			String terminalBefore = support.redis.opsForValue().get(JobHeartbeatRegistry.BEAT_PREFIX + terminal);
			String unknownBefore = support.redis.opsForValue().get(JobHeartbeatRegistry.BEAT_PREFIX + unknownJob);
			P1ScenarioSupport.sleep(5L);
			P1ScenarioSupport.stage(support, dataset, "tick_db_unknown_and_terminal", () -> P1ScenarioSupport.tickOnce(heartbeat));
			String terminalAfter = support.redis.opsForValue().get(JobHeartbeatRegistry.BEAT_PREFIX + terminal);
			String unknownAfter = support.redis.opsForValue().get(JobHeartbeatRegistry.BEAT_PREFIX + unknownJob);
			support.require(dataset, "terminal_generation_not_renewed", terminalBefore.equals(terminalAfter),
					"an authoritative terminal job is excluded from the next batch");
			support.require(dataset, "db_unknown_keeps_liveness_renewal", unknownAfter != null
					&& !unknownBefore.equals(unknownAfter), "DB UNKNOWN is not treated as terminal");

			unknown.clear();
			states.put(terminal, P1ScenarioSupport.cancelState(Job.JobStatus.RUNNING, 1L));
			heartbeat.clear(terminal);
			String remainderBefore = support.redis.opsForValue().get(JobHeartbeatRegistry.BEAT_PREFIX + jobIds.get(32));
			CountingRedis.Snapshot beforeFault = support.commandMetrics.snapshot();
			boolean heartbeatInjected = P1ScenarioSupport.fault(support, dataset, "eval",
					JobHeartbeatRegistry.BEAT_PREFIX + jobIds.get(0), () -> P1ScenarioSupport.stage(support, dataset,
							"heartbeat_pipeline_short_circuit", () -> P1ScenarioSupport.tickOnce(heartbeat)));
			long faultCommands = CountingRedis.delta(beforeFault, support.commandMetrics.snapshot()).commands();
			String remainderAfter = support.redis.opsForValue().get(JobHeartbeatRegistry.BEAT_PREFIX + jobIds.get(32));
			if (heartbeatInjected) {
				support.require(dataset, "pipeline_failure_skips_remainder", faultCommands < fullTickCommands
						&& remainderBefore.equals(remainderAfter),
						"an injected first-batch script failure prevents the next batch from starting");
			}
			dataset.attributes.put("faultTickCommands", faultCommands);
			P1ScenarioSupport.sleep(5L);
			CountingRedis.Snapshot beforeRecovery = support.commandMetrics.snapshot();
			P1ScenarioSupport.stage(support, dataset, "heartbeat_next_tick_recovers", () ->
					P1ScenarioSupport.tickOnce(heartbeat));
			long recoveryCommands = CountingRedis.delta(beforeRecovery, support.commandMetrics.snapshot()).commands();
			String recoveredRemainder = support.redis.opsForValue().get(JobHeartbeatRegistry.BEAT_PREFIX + jobIds.get(32));
			if (heartbeatInjected) {
				support.require(dataset, "next_tick_recovers_skipped_remainder", recoveryCommands == fullTickCommands
						&& recoveredRemainder != null && !remainderAfter.equals(recoveredRemainder),
						"the following tick resumes at the cursor and renews every previously skipped runner");
			}

			long revokedJob = jobIds.get(0);
			for (int i = 1; i < jobIds.size(); i++) {
				heartbeat.endRunner(jobIds.get(i));
			}
			heartbeat.endRunner(revokedJob);
			support.require(dataset, "last_runner_restarts_ticker", heartbeat.beginRunner(revokedJob)
					&& P1ScenarioSupport.tickerRunning(heartbeat),
					"the final real registration restarts the production ticker before revocation");
			heartbeat.revokeRunner(revokedJob);
			support.require(dataset, "last_revocation_stops_ticker", !P1ScenarioSupport.tickerRunning(heartbeat),
					"revoking the final active runner stops renewal immediately");
			String revokedActive = support.redis.opsForValue().get(JobHeartbeatRegistry.RUNNER_ACTIVE_PREFIX + revokedJob);
			String revokedBeat = support.redis.opsForValue().get(JobHeartbeatRegistry.BEAT_PREFIX + revokedJob);
			heartbeat.beat(revokedJob);
			P1ScenarioSupport.stage(support, dataset, "revoked_runner_tick_cannot_revive", () ->
					P1ScenarioSupport.tickOnce(heartbeat));
			support.require(dataset, "revocation_survives_beat_and_ticker", revokedActive != null
					&& revokedActive.startsWith("REVOKED|") && revokedActive.equals(
					support.redis.opsForValue().get(JobHeartbeatRegistry.RUNNER_ACTIVE_PREFIX + revokedJob))
					&& revokedBeat != null && revokedBeat.startsWith("REVOKED|")
					&& revokedBeat.equals(support.redis.opsForValue().get(JobHeartbeatRegistry.BEAT_PREFIX + revokedJob)),
					"neither a late beat nor a later ticker batch can recreate the revoked generation");
			heartbeat.endRunner(revokedJob);
			support.require(dataset, "ticker_stops_after_last_runner", !P1ScenarioSupport.tickerRunning(heartbeat),
					"ending the final runner leaves no renewal thread");
			support.require(dataset, "heartbeat_no_keyspace_scan", scansBefore == support.commandMetrics.keyspaceScanPages(),
					"ticker uses generation-fenced single-key scripts only");
			dataset.state = "measured";
		}
		finally {
			for (Long jobId : jobIds) {
				heartbeat.endRunner(jobId);
			}
			P1ScenarioSupport.stopTicker(heartbeat);
		}
	}

}

final class JobCancelVersionAndDbFallbackScenario implements ProductionScenario {

	@Override
	public String id() {
		return "job_cancel_version_and_db_fallback";
	}

	@Override
	public Manifest.Dataset generate(ProductionSupport support, Manifest.Dataset dataset) {
		for (int i = 1; i <= 4; i++) {
			P1ScenarioSupport.ownJob(support, dataset, support.jobId(dataset, i));
		}
		return dataset;
	}

	@Override
	public void measure(ProductionSupport support, Manifest.Dataset dataset) {
		long scansBefore = support.commandMetrics.keyspaceScanPages();
		Map<Long, JobRepository.CancelState> states = new ConcurrentHashMap<>();
		Set<Long> unknown = ConcurrentHashMap.newKeySet();
		JobRepository repository = mock(JobRepository.class);
		when(repository.findCancelStateById(anyLong())).thenAnswer(invocation -> {
			long jobId = invocation.getArgument(0);
			if (unknown.contains(jobId)) {
				throw new IllegalStateException("PERF_CANCEL_DB_UNKNOWN");
			}
			return Optional.ofNullable(states.get(jobId));
		});
		AppProperties properties = P1ScenarioSupport.jobProperties();
		JobCancellationRegistry cancellations = new JobCancellationRegistry(support.redis, repository, properties);
		long monotonic = support.jobId(dataset, 1);
		long fallback = support.jobId(dataset, 2);
		long unreadable = support.jobId(dataset, 3);
		long invalidated = support.jobId(dataset, 4);
		try {
			P1ScenarioSupport.stage(support, dataset, "cancel_version_monotonic_mirror", () -> {
				states.put(monotonic, P1ScenarioSupport.cancelState(Job.JobStatus.CANCELLED, 2L));
				support.require(dataset, "version_2_written", cancellations.cancel(monotonic, "v2").redisWritten(),
						"the first authoritative version is mirrored");
				String v2 = support.redis.opsForValue().get(cancellations.keyFor(monotonic));
				states.put(monotonic, P1ScenarioSupport.cancelState(Job.JobStatus.CANCELLED, 1L));
				support.require(dataset, "older_version_cannot_regress", !cancellations.cancel(monotonic, "v1").redisWritten()
						&& v2.equals(support.redis.opsForValue().get(cancellations.keyFor(monotonic))),
						"version 1 cannot overwrite version 2");
				states.put(monotonic, P1ScenarioSupport.cancelState(Job.JobStatus.CANCELLED, 3L));
				support.require(dataset, "newer_version_advances", cancellations.cancel(monotonic, "v3").redisWritten()
						&& support.redis.opsForValue().get(cancellations.keyFor(monotonic)).startsWith("3|"),
						"version 3 advances the same mirror");
				String v3 = support.redis.opsForValue().get(cancellations.keyFor(monotonic));
				unknown.add(monotonic);
				support.require(dataset, "unknown_version_preserves_existing_fence",
						!cancellations.cancel(monotonic, "unknown").redisWritten()
								&& v3.equals(support.redis.opsForValue().get(cancellations.keyFor(monotonic))),
						"DB UNKNOWN never deletes or rewrites the current mirror");
				unknown.remove(monotonic);
				long pttl = support.redis.getExpire(cancellations.keyFor(monotonic),
						java.util.concurrent.TimeUnit.MILLISECONDS);
				support.require(dataset, "cancel_ttl_covers_configured_job_limit",
						pttl > properties.getJob().getMaxExecutionTime().toMillis(),
						"TTL is derived from 2x max-execution-time plus margin, pttl=" + pttl);
			});

			states.put(fallback, P1ScenarioSupport.cancelState(Job.JobStatus.CANCELLED, 4L));
			AtomicReference<JobCancellationRegistry.CancelResult> writeFailed = new AtomicReference<>();
			boolean writeInjected = P1ScenarioSupport.fault(support, dataset, "eval", cancellations.keyFor(fallback), () ->
					P1ScenarioSupport.stage(support, dataset, "cancel_mirror_write_failure", () ->
							writeFailed.set(cancellations.cancel(fallback, "remote-cancel"))));
			if (writeInjected) {
				support.require(dataset, "mirror_failure_is_reported_not_faked", !writeFailed.get().redisWritten(),
						"redisWritten=false preserves the committed-DB versus acceleration distinction");
			}
			AtomicReference<Boolean> dbFallback = new AtomicReference<>();
			boolean readInjected = P1ScenarioSupport.fault(support, dataset, "get", cancellations.keyFor(fallback), () ->
					P1ScenarioSupport.stage(support, dataset, "cancel_read_db_fallback", () ->
							dbFallback.set(cancellations.isCancelled(fallback))));
			if (readInjected) {
				support.require(dataset, "redis_read_failure_uses_authoritative_db", dbFallback.get(),
						"the cancelled DB state is visible even when the mirror GET fails");
			}

			unknown.add(unreadable);
			AtomicReference<Boolean> bothUnknown = new AtomicReference<>();
			boolean unknownInjected = P1ScenarioSupport.fault(support, dataset, "get", cancellations.keyFor(unreadable), () ->
					P1ScenarioSupport.stage(support, dataset, "cancel_redis_and_db_unknown", () ->
							bothUnknown.set(P1ScenarioSupport.failure(() -> cancellations.isCancelled(unreadable)))));
			if (unknownInjected) {
				support.require(dataset, "unknown_cancel_state_fails_closed", bothUnknown.get(),
						"Redis and DB both unreadable cannot be converted into not-cancelled");
			}

			states.put(invalidated, P1ScenarioSupport.cancelState(Job.JobStatus.RUNNING, 0L));
			support.require(dataset, "negative_cache_initially_false", !cancellations.isCancelled(invalidated),
					"an in-flight job starts uncancelled");
			states.put(invalidated, P1ScenarioSupport.cancelState(Job.JobStatus.CANCELLED, 5L));
			cancellations.invalidate(invalidated);
			support.require(dataset, "terminal_write_invalidation_exposes_cancel", cancellations.isCancelled(invalidated),
					"the explicit terminal/cancel invalidation bypasses the short negative cache");
			support.require(dataset, "remote_cancel_does_not_leak_local_registration", cancellations.registeredJobs() == 0,
					"remote-only checks do not create permanent local flag entries");
			support.require(dataset, "cancel_no_keyspace_scan", scansBefore == support.commandMetrics.keyspaceScanPages(),
					"all cancel checks use one exact mirror key and DB projection");
			dataset.state = "measured";
		}
		finally {
			for (int i = 1; i <= 4; i++) {
				cancellations.unregister(support.jobId(dataset, i));
			}
		}
	}

}
