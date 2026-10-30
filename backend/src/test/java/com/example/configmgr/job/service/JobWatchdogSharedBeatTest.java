package com.example.configmgr.job.service;

import com.example.configmgr.config.AppProperties;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.entity.ValidationIssue;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.job.repo.ValidationIssueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 多实例「共享存活 + 单飞判死」用例族（复审 C1/I1-I3 修后的口径）：
 *
 * <ul>
 * <li><b>判活只看共享心跳键是否存在</b>（Redis TTL 权威）—— 值里的时间戳只作诊断，
 *     快/慢时钟都不得造成误杀或漏杀（{@code slowOwnerClock...} / {@code fastOwnerClock...}）；</li>
 * <li><b>进程级 ticker</b> 续期共享键：长导入/预检整段没有分片 beat 也不会被对端 90s 误判
 *     （{@code processTickerKeepsLongPhaseAlive...}）；</li>
 * <li><b>hold 有上界</b>：hold 不延长共享 TTL，持有者死掉后对端在 ≤ 判死窗内接管，
 *     不再等 runner-active 的 65m（{@code holdDoesNotExtendSharedLiveness...}）；</li>
 * <li><b>单飞 issue</b>：单飞键不可用时超时只收敛条件终态、不写 issue，
 *     多实例不会各写一条（{@code timeoutWithUnavailableLease...}）。</li>
 * </ul>
 *
 * <p>底座 = {@link FakeJobRedis}（共享态替身，TTL 按"Redis 服务端时钟"判定）+ 可控时钟。
 * 三个 {@link JobHeartbeatRegistry}（A/B/C）共享同一替身模拟三实例，看门狗共享同一份假仓库
 * （= 同一张 jobs 表）。
 */
class JobWatchdogSharedBeatTest {

    private static final long JOB_ID = 1L;

    private static final Duration DEAD_WINDOW = Duration.ofSeconds(90);

    /** Redis 服务端时钟（TTL 判定口径）。 */
    private final MutableClock clock = new MutableClock(System.currentTimeMillis());

    private final FakeJobRedis shared = new FakeJobRedis(this.clock);

    /** 本用例创建的全部登记簿（@AfterEach 统一停 ticker，避免守护线程跨用例残留）。 */
    private final java.util.List<JobHeartbeatRegistry> registries = new java.util.concurrent.CopyOnWriteArrayList<>();

    private final JobHeartbeatRegistry instanceA = registry(this.clock);
    private final JobHeartbeatRegistry instanceB = registry(this.clock);
    private final JobHeartbeatRegistry instanceC = registry(this.clock);

    private JobHeartbeatRegistry registry(Clock instanceClock) {
        JobHeartbeatRegistry created = new JobHeartbeatRegistry(instanceClock, this.shared.redis, true);
        this.registries.add(created);
        return created;
    }

    @org.junit.jupiter.api.AfterEach
    void stopTickers() {
        this.registries.forEach(JobHeartbeatRegistry::stopTicker);
    }

    private final JobRepository jobRepository = mock(JobRepository.class);
    private final JobTerminalWriter terminalWriter = mock(JobTerminalWriter.class);
    private final JobCancellationRegistry cancellationRegistry = mock(JobCancellationRegistry.class);
    private final ValidationIssueRepository issueRepository = mock(ValidationIssueRepository.class);

    private final JobWatchdog watchdogA = watchdog(this.instanceA);
    private final JobWatchdog watchdogB = watchdog(this.instanceB);
    private final JobWatchdog watchdogC = watchdog(this.instanceC);

    @BeforeEach
    void oneRunningJob() {
        when(this.jobRepository.findByStatusIn(any())).thenReturn(List.of(runningJob()));
    }

    // ── 共享存活语义：短 TTL + 键存在性 ───────────────────────────────────────

    @Test
    void beatWritesShortSharedBeatKeyAndHoldDoesNotExtendItsTtl() {
        this.instanceA.beginRunner(JOB_ID);
        this.instanceA.beat(JOB_ID);

        String raw = this.shared.get(JobHeartbeatRegistry.BEAT_PREFIX + JOB_ID);
        assertThat(raw).as("beat 必须写共享心跳（跨实例判活的唯一输入）").isNotNull();
        assertThat(raw).as("值 = 实例标识|纪元毫秒（时刻只作诊断，不参与判定）")
                .matches("runner-.+\\|\\d+");
        assertThat(this.shared.ttl(JobHeartbeatRegistry.BEAT_PREFIX + JOB_ID))
                .as("缺省判死窗 90s ⇒ 共享心跳 TTL 90s（判活上界 = 判死窗）")
                .isEqualTo(this.instanceA.beatTtl())
                .isEqualTo(DEAD_WINDOW)
                .isLessThan(this.instanceA.runnerActiveTtl());

        this.instanceA.hold(JOB_ID);
        assertThat(this.shared.ttl(JobHeartbeatRegistry.BEAT_PREFIX + JOB_ID))
                .as("复审 C1：hold 不得把共享 TTL 拉长到 65m（跨实例豁免必须有上界）")
                .isEqualTo(DEAD_WINDOW);
        assertThat(this.instanceA.isTickerRunning()).as("redis 模式 beginRunner 后 ticker 必须在跑")
                .isTrue();
    }

    @Test
    void expiredSharedBeatLetsAnotherInstanceTakeOverAndConvergeFailed() {
        this.instanceA.beginRunner(JOB_ID);
        this.instanceA.beat(JOB_ID);
        this.clock.advance(DEAD_WINDOW.plusSeconds(1));

        this.watchdogB.scan();

        assertThat(this.watchdogB.metrics().get("judgedDeadTotal")).isEqualTo(1L);
        assertJudgedDeadOnce();
        assertThat(this.shared.has(JobHeartbeatRegistry.RUNNER_ACTIVE_PREFIX + JOB_ID))
                .as("判死成功后清 runner-active：删除闸门必须随 runner 清理释放").isFalse();
        assertThat(this.shared.has(JobHeartbeatRegistry.BEAT_PREFIX + JOB_ID)).isFalse();
        assertThat(this.instanceB.isRunnerAlive(JOB_ID)).as("删除闸门已放开").isFalse();
    }

    @Test
    void freshSharedBeatIsNotJudgedDeadAcrossInstances() {
        this.instanceA.beginRunner(JOB_ID);
        this.instanceA.beat(JOB_ID);
        this.clock.advance(DEAD_WINDOW.minusSeconds(1));

        this.watchdogB.scan();

        assertThat(this.watchdogB.metrics().get("judgedDeadTotal")).isEqualTo(0L);
        verify(this.terminalWriter, never())
                .complete(any(), any(), anyInt(), anyInt(), anyInt(), anyInt(), any());
    }

    // ── C1：hold 不是无限豁免（持有者死亡 ⇒ ≤ 判死窗内接管）────────────────────

    @Test
    void holdDoesNotExtendSharedLivenessBeyondBeatTtl() {
        this.instanceA.beginRunner(JOB_ID);
        this.instanceA.beat(JOB_ID);
        this.instanceA.hold(JOB_ID);

        this.watchdogB.scan();
        assertThat(this.watchdogB.metrics().get("judgedDeadTotal")).as("键仍新鲜：不判死").isEqualTo(0L);

        // 持有者中途死亡：没有 tick 续期 ⇒ 键在判死窗到期后必须被当作失联，而不是等 65m
        this.clock.advance(DEAD_WINDOW.plusSeconds(1));
        this.watchdogB.scan();

        assertThat(this.watchdogB.metrics().get("judgedDeadTotal"))
                .as("旧实现把 held 写成 65m 长租约：这里必须按 STALE 接管").isEqualTo(1L);
        assertJudgedDeadOnce();
    }

    // ── I1：长导入/预检没有分片 beat —— 进程级 ticker 续期，对端不误判 ──────────

    @Test
    void processTickerKeepsLongPhaseAliveWithoutBusinessBeats() {
        this.instanceA.beginRunner(JOB_ID);
        this.instanceA.beat(JOB_ID);

        // 模拟长导入/预检：120s（> 一个判死窗）没有任何 snapshot 节拍，只有进程 ticker 在续期
        this.clock.advance(Duration.ofSeconds(60));
        this.instanceA.tickOnce();
        this.clock.advance(Duration.ofSeconds(60));

        this.watchdogB.scan();
        assertThat(this.watchdogB.metrics().get("judgedDeadTotal"))
                .as("ticker 续期 ⇒ 对端不得因长阶段无分片 beat 误判").isEqualTo(0L);

        // 进程死亡：ticker 停 ⇒ 最后一次续期（t=60s）后 90s 到期，t=150s 起应变 STALE
        this.clock.advance(Duration.ofSeconds(100));
        this.watchdogB.scan();
        assertThat(this.watchdogB.metrics().get("judgedDeadTotal")).isEqualTo(1L);
        assertJudgedDeadOnce();
    }

    // ── I2：跨实例时钟漂移不得影响判活（只看键是否存在/TTL）────────────────────

    @Test
    void slowOwnerClockDoesNotCausePeerMisjudgement() {
        JobHeartbeatRegistry slowA = registry(Clock.offset(this.clock, Duration.ofMinutes(-10)));
        slowA.beginRunner(JOB_ID);
        slowA.beat(JOB_ID);

        this.watchdogB.scan();
        assertThat(this.watchdogB.metrics().get("judgedDeadTotal"))
                .as("A 的时钟慢 10min：键还在就不得判死（时间戳比较会误杀）").isEqualTo(0L);

        this.clock.advance(DEAD_WINDOW.plusSeconds(1));
        this.watchdogB.scan();
        assertThat(this.watchdogB.metrics().get("judgedDeadTotal"))
                .as("TTL 到期后才接管（慢时钟不改变检测上界）").isEqualTo(1L);
    }

    @Test
    void fastOwnerClockDoesNotKeepDeadRunnerAlive() {
        JobHeartbeatRegistry fastA = registry(Clock.offset(this.clock, Duration.ofMinutes(10)));
        fastA.beginRunner(JOB_ID);
        fastA.beat(JOB_ID);
        // 键里的时间戳在"未来"；时间戳比较会认为永不失联，TTL 到期则必须判死
        this.clock.advance(DEAD_WINDOW.plusSeconds(1));

        this.watchdogB.scan();

        assertThat(this.watchdogB.metrics().get("judgedDeadTotal"))
                .as("快时钟不得让死 runner 看起来永远新鲜").isEqualTo(1L);
        assertJudgedDeadOnce();
    }

    // ── fail-closed：读失败不判死；本实例 runner 仍活不判死 ────────────────────

    @Test
    void unreadableSharedBeatFailsClosedWithoutJudgement() {
        this.instanceA.beginRunner(JOB_ID);
        this.instanceA.beat(JOB_ID);
        this.clock.advance(DEAD_WINDOW.plusSeconds(30));
        this.shared.goDown();

        this.watchdogB.scan();

        verify(this.terminalWriter, never())
                .complete(any(), any(), anyInt(), anyInt(), anyInt(), anyInt(), any());
        verify(this.issueRepository, never()).save(any());
        assertThat(this.watchdogB.metrics().get("skippedUnavailableTotal"))
                .as("Redis 不可读 = 无法确认，必须记降级计数（可观测）").isEqualTo(1L);
    }

    @Test
    void locallyRegisteredRunnerIsNotJudgedDeadEvenWithExpiredBeat() {
        this.instanceB.beginRunner(JOB_ID);
        this.clock.advance(DEAD_WINDOW.multipliedBy(2));

        this.watchdogB.scan();

        verify(this.terminalWriter, never())
                .complete(any(), any(), anyInt(), anyInt(), anyInt(), anyInt(), any());
        assertThat(this.instanceB.isRunnerAlive(JOB_ID)).isTrue();
    }

    // ── 单飞：SET NX PX 只让一个实例判死（重复 issue / 重复终态不增加）───────

    @Test
    void deadJudgementIsSingleFlightAcrossInstances() {
        this.instanceA.beginRunner(JOB_ID);
        this.instanceA.beat(JOB_ID);
        this.clock.advance(DEAD_WINDOW.plusSeconds(1));

        // A 自己的 runner 仍登记 ⇒ A 必须 fail-closed（不误杀活线程）；判死由没有本地 runner 的 B 发起
        this.watchdogA.scan();
        assertThat(this.watchdogA.metrics().get("judgedDeadTotal")).isEqualTo(0L);
        this.watchdogB.scan();
        assertThat(this.watchdogB.metrics().get("judgedDeadTotal")).isEqualTo(1L);

        assertThat(this.instanceC.tryAcquireDeadJudgement(JOB_ID))
                .as("B 已持有判死单飞键（SET NX）⇒ C 拿不到")
                .isEqualTo(JobHeartbeatRegistry.JudgeLease.HELD);

        // C 读到的是 B 清理之前的锚点快照（真实并发里两次扫描可交错到这一形态）
        this.shared.seed(JobHeartbeatRegistry.RUNNER_ACTIVE_PREFIX + JOB_ID,
                "runner-other-pid999-abcdef",
                this.instanceA.runnerActiveTtl());
        this.watchdogC.scan();

        assertJudgedDeadOnce();
        assertThat(this.watchdogC.metrics().get("judgedDeadTotal")).isEqualTo(0L);
        assertThat(this.watchdogC.metrics().get("skippedNotLeaderTotal")).isEqualTo(1L);
    }

    @Test
    void repeatedScansDoNotDuplicateIssueOrTerminal() {
        this.instanceA.beginRunner(JOB_ID);
        this.instanceA.beat(JOB_ID);
        this.clock.advance(DEAD_WINDOW.plusSeconds(1));

        this.watchdogB.scan();
        this.watchdogB.scan();

        assertJudgedDeadOnce();
        assertThat(this.watchdogB.metrics().get("judgedDeadTotal")).isEqualTo(1L);
    }

    // ── 绝对上限：不释放 runner 闸门；单飞不可用 ⇒ 只收敛终态、不写 issue ──────

    @Test
    void timeoutJudgementKeepsRunnerActiveKey() {
        when(this.jobRepository.findByStatusIn(any()))
                .thenReturn(List.of(runningJob(Duration.ofMinutes(31))));
        this.instanceA.beginRunner(JOB_ID);

        this.watchdogA.scan();

        assertThat(this.watchdogA.metrics().get("judgedTimeoutTotal")).isEqualTo(1L);
        assertThat(this.shared.has(JobHeartbeatRegistry.RUNNER_ACTIVE_PREFIX + JOB_ID))
                .as("超时判死不强杀线程：runner 可能仍在写，闸门保留给 AsyncJobExecutor.finally 释放").isTrue();
        assertThat(this.instanceA.isRunnerAlive(JOB_ID)).isTrue();
    }

    @Test
    void timeoutWithUnavailableLeaseConvergesTerminalWithoutDuplicatingIssues() {
        when(this.jobRepository.findByStatusIn(any()))
                .thenReturn(List.of(runningJob(Duration.ofMinutes(31))));
        this.shared.goDown(); // 单飞键写不进去 ⇒ N 个实例都可能判超时

        this.watchdogB.scan();
        this.watchdogC.scan();

        assertThat(this.watchdogB.metrics().get("judgedTimeoutTotal")).isEqualTo(1L);
        assertThat(this.watchdogC.metrics().get("judgedTimeoutTotal")).isEqualTo(1L);
        verify(this.terminalWriter, times(2)).complete(any(), eq(Job.JobStatus.FAILED),
                anyInt(), anyInt(), anyInt(), anyInt(), any());
        verify(this.issueRepository, never()).save(any());
        assertThat(this.watchdogB.metrics().get("issueSuppressedTotal"))
                .as("复审 I3：issue 必须单飞；租约不可用时宁可少写一条，也不能 N 实例各写一条")
                .isEqualTo(1L);
    }

    // ───────────────────────── helpers ─────────────────────────

    private JobWatchdog watchdog(JobHeartbeatRegistry registry) {
        return new JobWatchdog(this.jobRepository, registry, this.terminalWriter,
                this.cancellationRegistry, this.issueRepository, new AppProperties(), this.clock);
    }

    private void assertJudgedDeadOnce() {
        ArgumentCaptor<ValidationIssue> issues = ArgumentCaptor.forClass(ValidationIssue.class);
        verify(this.issueRepository, times(1)).save(issues.capture());
        assertThat(issues.getValue().getMessage()).contains("判死").contains("不自动重排队");

        ArgumentCaptor<Job> judged = ArgumentCaptor.forClass(Job.class);
        verify(this.terminalWriter, times(1)).complete(judged.capture(), eq(Job.JobStatus.FAILED),
                anyInt(), anyInt(), anyInt(), anyInt(), any());
        assertThat(judged.getValue().getId()).isEqualTo(JOB_ID);
        verify(this.cancellationRegistry, times(1)).cancel(JOB_ID, "job-watchdog");
    }

    private Job runningJob() {
        return runningJob(Duration.ZERO);
    }

    private Job runningJob(Duration startedAgo) {
        Job job = new Job();
        job.setId(JOB_ID);
        job.setTaskId(10L);
        job.setJobType(Job.JobType.EXPORT);
        job.setStatus(Job.JobStatus.RUNNING);
        job.setProgress(3);
        job.setTotal(10);
        job.setStartedAt(LocalDateTime.now().minus(startedAgo));
        job.setCreatedAt(LocalDateTime.now().minus(startedAgo));
        return job;
    }

    /** 可控时钟：只在显式推进时前进（TTL 边界因此确定性）。 */
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
