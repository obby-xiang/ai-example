package com.example.configmgr.job.service;

import com.example.configmgr.config.AppProperties;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.job.repo.ValidationIssueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 滚动升级兼容（复审必修项 1）：旧 runner 只写 {@code job:runner-active}（无 {@code |v2}）、
 * 从不写 {@code job:beat}。新看门狗若把"锚点在 / 心跳缺"一律当 STALE，就会误杀仍活的旧 runner。
 *
 * <p>口径：新锚点带版本标记 {@code |v2}；<b>无标记的旧锚点按 {@code LEGACY} 处理</b> ——
 * 心跳判据不判死（存活交给 30m 绝对上限收敛，仍是有界收敛，不是永久豁免、也不延长任何 TTL）；
 * {@code |v2} 锚点心跳缺失仍按 STALE 判死（新 runner 有 ticker，键缺 = 失联）。
 *
 * <p>RED 证据：本类在改造前跑，{@code legacyAnchorWithoutBeatIsNotJudgedDead} 与
 * {@code newAnchorCarriesVersionMarker} 必失败（旧锚点被误判死 / 新锚点没有版本标记）。
 */
class JobWatchdogLegacyAnchorTest {

    private static final long JOB_ID = 7L;

    private static final Duration DEAD_WINDOW = Duration.ofSeconds(90);

    private final MutableClock clock = new MutableClock(System.currentTimeMillis());

    private final FakeJobRedis shared = new FakeJobRedis(this.clock);

    private final JobHeartbeatRegistry registry =
            new JobHeartbeatRegistry(this.clock, this.shared.redis, true);

    private final JobRepository jobRepository = mock(JobRepository.class);
    private final JobTerminalWriter terminalWriter = mock(JobTerminalWriter.class);
    private final JobCancellationRegistry cancellationRegistry = mock(JobCancellationRegistry.class);
    private final ValidationIssueRepository issueRepository = mock(ValidationIssueRepository.class);

    private final JobWatchdog watchdog = new JobWatchdog(this.jobRepository, this.registry,
            this.terminalWriter, this.cancellationRegistry, this.issueRepository,
            new AppProperties(), this.clock);

    @BeforeEach
    void oneRunningJob() {
        when(this.jobRepository.findByStatusIn(any())).thenReturn(List.of(runningJob(Duration.ZERO)));
    }

    @org.junit.jupiter.api.AfterEach
    void stopTicker() {
        this.registry.stopTicker();
    }

    // ── 旧 runner（无 |v2、无 beat）：不得被心跳判据误杀 ──────────────────────

    @Test
    void legacyAnchorWithoutBeatIsNotJudgedDead() {
        seedLegacyAnchor("runner-legacy-host-pid4242-abcdef");

        this.watchdog.scan();

        verify(this.terminalWriter, never())
                .complete(any(), any(), anyInt(), anyInt(), anyInt(), anyInt(), any());
        verify(this.issueRepository, never()).save(any());
        assertThat(this.shared.has(JobHeartbeatRegistry.RUNNER_ACTIVE_PREFIX + JOB_ID))
                .as("旧 runner 仍活：锚点必须保留（删除闸门也仍在）").isTrue();
        assertThat(this.registry.isRunnerAlive(JOB_ID))
                .as("旧 runner 的删除闸门不得因心跳判据被放开").isTrue();
        assertThat(this.watchdog.metrics().get("judgedDeadTotal")).isEqualTo(0L);
    }

    @Test
    void legacyAnchorIsStillCappedByAbsoluteTimeout() {
        when(this.jobRepository.findByStatusIn(any()))
                .thenReturn(List.of(runningJob(Duration.ofMinutes(31))));
        seedLegacyAnchor("runner-legacy-host-pid4242-abcdef");

        this.watchdog.scan();

        assertThat(this.watchdog.metrics().get("judgedTimeoutTotal"))
                .as("LEGACY 不是永久豁免：绝对上限仍收敛（30m）").isEqualTo(1L);
        assertThat(this.watchdog.metrics().get("judgedDeadTotal")).isEqualTo(0L);
    }

    // ── 新 runner（|v2）：心跳缺失仍是 STALE ⇒ 判死 ──────────────────────────

    @Test
    void v2AnchorWithoutBeatIsJudgedDead() {
        this.shared.seed(JobHeartbeatRegistry.RUNNER_ACTIVE_PREFIX + JOB_ID,
                "runner-new-host-pid4242-abcdef|v2", this.registry.runnerActiveTtl());
        this.clock.advance(DEAD_WINDOW.plusSeconds(1));

        this.watchdog.scan();

        assertThat(this.watchdog.metrics().get("judgedDeadTotal"))
                .as("v2 锚点 + 无心跳 = 新 runner 失联（ticker 没续期）").isEqualTo(1L);
        verify(this.terminalWriter).complete(any(), org.mockito.ArgumentMatchers.eq(Job.JobStatus.FAILED),
                anyInt(), anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    void v2AnchorWithLiveBeatIsNotJudgedDead() {
        this.registry.beginRunner(JOB_ID);
        this.registry.beat(JOB_ID);

        this.watchdog.scan();

        assertThat(this.watchdog.metrics().get("judgedDeadTotal")).isEqualTo(0L);
        assertThat(this.shared.has(JobHeartbeatRegistry.BEAT_PREFIX + JOB_ID)).isTrue();
    }

    // ── 新锚点必须带版本标记 ─────────────────────────────────────────────────

    @Test
    void newAnchorCarriesVersionMarker() {
        this.registry.beginRunner(JOB_ID);

        assertThat(this.shared.get(JobHeartbeatRegistry.RUNNER_ACTIVE_PREFIX + JOB_ID))
                .as("新 anchor 必须带 |v2，供对端区分旧 runner").endsWith("|v2");
        this.registry.beat(JOB_ID);
        assertThat(this.shared.get(JobHeartbeatRegistry.RUNNER_ACTIVE_PREFIX + JOB_ID))
                .as("续期不得把版本标记写丢").endsWith("|v2");
    }

    // ── 启动清理同时兼容旧值与 |v2 ───────────────────────────────────────────

    @Test
    void cleanupRemovesLegacyAndV2AnchorsOfDeadLocalPid() throws Exception {
        long taskIdBase = 900L;
        long legacyJob = taskIdBase;
        long v2Job = taskIdBase + 1;
        String localHost = InetAddress.getLocalHost().getHostName();
        String deadPid = String.valueOf(Long.MAX_VALUE);
        this.shared.seed(JobHeartbeatRegistry.RUNNER_ACTIVE_PREFIX + legacyJob,
                "runner-" + localHost + "-pid" + deadPid + "-abcdef", this.registry.runnerActiveTtl());
        this.shared.seed(JobHeartbeatRegistry.RUNNER_ACTIVE_PREFIX + v2Job,
                "runner-" + localHost + "-pid" + deadPid + "-abcdef|v2", this.registry.runnerActiveTtl());

        this.registry.cleanupDeadLocalRunner(legacyJob);
        this.registry.cleanupDeadLocalRunner(v2Job);

        assertThat(this.shared.has(JobHeartbeatRegistry.RUNNER_ACTIVE_PREFIX + legacyJob))
                .as("旧值（无 |v2）必须被清理").isFalse();
        assertThat(this.shared.has(JobHeartbeatRegistry.RUNNER_ACTIVE_PREFIX + v2Job))
                .as("新值（带 |v2）必须被清理").isFalse();
    }

    @Test
    void cleanupKeepsAliveLocalPidInBothForms() throws Exception {
        long legacyJob = 910L;
        long v2Job = 911L;
        String localHost = InetAddress.getLocalHost().getHostName();
        String alivePid = String.valueOf(ProcessHandle.current().pid());
        this.shared.seed(JobHeartbeatRegistry.RUNNER_ACTIVE_PREFIX + legacyJob,
                "runner-" + localHost + "-pid" + alivePid + "-abcdef", this.registry.runnerActiveTtl());
        this.shared.seed(JobHeartbeatRegistry.RUNNER_ACTIVE_PREFIX + v2Job,
                "runner-" + localHost + "-pid" + alivePid + "-abcdef|v2", this.registry.runnerActiveTtl());

        this.registry.cleanupDeadLocalRunner(legacyJob);
        this.registry.cleanupDeadLocalRunner(v2Job);

        assertThat(this.shared.has(JobHeartbeatRegistry.RUNNER_ACTIVE_PREFIX + legacyJob)).isTrue();
        assertThat(this.shared.has(JobHeartbeatRegistry.RUNNER_ACTIVE_PREFIX + v2Job)).isTrue();
    }

    // ── Redis 故障：旧/新锚点都 fail-closed ──────────────────────────────────

    @Test
    void redisFailureFailsClosedForLegacyAnchor() {
        seedLegacyAnchor("runner-legacy-host-pid4242-abcdef");
        this.shared.goDown();

        this.watchdog.scan();

        verify(this.terminalWriter, never())
                .complete(any(), any(), anyInt(), anyInt(), anyInt(), anyInt(), any());
        assertThat(this.watchdog.metrics().get("skippedUnavailableTotal")).isEqualTo(1L);
    }

    // ───────────────────────── helpers ─────────────────────────

    private void seedLegacyAnchor(String value) {
        this.shared.seed(JobHeartbeatRegistry.RUNNER_ACTIVE_PREFIX + JOB_ID, value,
                this.registry.runnerActiveTtl());
    }

    private Job runningJob(Duration startedAgo) {
        Job job = new Job();
        job.setId(JOB_ID);
        job.setTaskId(70L);
        job.setJobType(Job.JobType.EXPORT);
        job.setStatus(Job.JobStatus.RUNNING);
        job.setProgress(1);
        job.setTotal(10);
        job.setStartedAt(LocalDateTime.now().minus(startedAgo));
        job.setCreatedAt(LocalDateTime.now().minus(startedAgo));
        return job;
    }

    /** 可控时钟：TTL 边界确定性（不 sleep）。 */
    private static final class MutableClock extends Clock {

        private long current;

        private MutableClock(long current) {
            this.current = current;
        }

        private void advance(Duration duration) {
            this.current += duration.toMillis();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(this.current);
        }
    }
}
