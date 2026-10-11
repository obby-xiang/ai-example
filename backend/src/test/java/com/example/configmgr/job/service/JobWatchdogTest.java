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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 作业看门狗用例族（R-96 批 B，侦察报告 §7 U6/U7/U8）：
 * 心跳 ×3 判死、30min 绝对上限判超时、判死后「issue → 终态 FAILED → cancel」链路、幂等。
 *
 * <p>纯 Mockito + 可控时钟（{@link MutableClock}）：判死窗 90s / 上限 30m 用真实睡眠不可行，
 * 时钟注入后边界断言（3×interval ± ε）成为确定性用例。{@link JobWatchdog#scan()} 包级可见，
 * 直接驱动（不起调度线程）。
 */
class JobWatchdogTest {

    private final MutableClock clock = new MutableClock(System.currentTimeMillis());

    private final JobRepository jobRepository = mock(JobRepository.class);
    private final JobTerminalWriter terminalWriter = mock(JobTerminalWriter.class);
    private final JobCancellationRegistry cancellationRegistry = mock(JobCancellationRegistry.class);
    private final ValidationIssueRepository issueRepository = mock(ValidationIssueRepository.class);
    private final JobHeartbeatRegistry heartbeatRegistry = new JobHeartbeatRegistry(this.clock);

    private final JobWatchdog watchdog = new JobWatchdog(this.jobRepository, this.heartbeatRegistry,
            this.terminalWriter, this.cancellationRegistry, this.issueRepository,
            new AppProperties(), this.clock);

    @BeforeEach
    void resetClock() {
        this.clock.reset(System.currentTimeMillis());
    }

    // ── U6：心跳 ×3 判死 —— 边界内不判、越过即判，且落「issue → FAILED → cancel」全链路 ──

    @Test
    void heartbeatTimesThreeBoundaryJudgesDeadWithFullActionChain() {
        Job job = runningJob(1L, Duration.ZERO);
        when(this.jobRepository.findByStatusIn(any())).thenReturn(List.of(job));
        this.heartbeatRegistry.beat(1L);

        // 失联 3×30s − 1ms：不判死
        this.clock.advance(Duration.ofSeconds(90).minusMillis(1));
        this.watchdog.scan();
        assertNoJudgement();

        // 再推进 2ms（= 失联 3×30s + 1ms）：判死
        this.clock.advance(Duration.ofMillis(2));
        this.watchdog.scan();

        assertThat(this.watchdog.metrics().get("judgedDeadTotal")).isEqualTo(1L);
        assertThat(this.watchdog.metrics().get("judgedTimeoutTotal")).isEqualTo(0L);
        assertJudgementChain(job, "判死");
    }

    @Test
    void freshHeartbeatNeverJudgedDeadEvenBeyondScanCycles() {
        Job job = runningJob(1L, Duration.ZERO);
        when(this.jobRepository.findByStatusIn(any())).thenReturn(List.of(job));

        for (int i = 0; i < 5; i++) {
            this.clock.advance(Duration.ofSeconds(25)); // 始终小于 90s 判死窗
            this.heartbeatRegistry.beat(1L);            // 分片边界续期（生产 = JobProgressService 节拍）
            this.watchdog.scan();
        }
        assertNoJudgement();
        assertThat(this.watchdog.metrics().get("tracked")).isEqualTo(1);
    }

    // ── U7：30min 绝对上限 —— 心跳新鲜也照判超时；PENDING 用 createdAt 起算 ──

    @Test
    void absoluteTimeoutJudgesDespiteFreshHeartbeat() {
        Job job = runningJob(1L, Duration.ofMinutes(31)); // 已运行 31min（< 判死窗不相关，心跳新鲜）
        when(this.jobRepository.findByStatusIn(any())).thenReturn(List.of(job));
        this.heartbeatRegistry.beat(1L); // 心跳此刻新鲜

        this.watchdog.scan();

        assertThat(this.watchdog.metrics().get("judgedTimeoutTotal")).isEqualTo(1L);
        assertThat(this.watchdog.metrics().get("judgedDeadTotal")).isEqualTo(0L);
        assertJudgementChain(job, "判超时");
    }

    @Test
    void pendingJobUsesCreatedAtAsTimeoutBaseline() {
        Job job = pendingJob(2L, Duration.ofMinutes(31)); // PENDING 从未开工，无心跳
        when(this.jobRepository.findByStatusIn(any())).thenReturn(List.of(job));

        this.watchdog.scan();

        assertThat(this.watchdog.metrics().get("judgedTimeoutTotal")).isEqualTo(1L);
        assertJudgementChain(job, "判超时");
    }

    @Test
    void runningWithinLimitsNotJudged() {
        Job job = runningJob(1L, Duration.ofMinutes(20)); // 运行 20min < 30m，心跳在 markRunning 时续过
        when(this.jobRepository.findByStatusIn(any())).thenReturn(List.of(job));
        this.heartbeatRegistry.beat(1L);

        this.watchdog.scan();
        assertNoJudgement();
    }

    /** 无心跳的 RUNNING 作业不判死（保守：无输入不判死，交给绝对上限收敛）。 */
    @Test
    void runningJobWithoutAnyHeartbeatIsNotJudgedDead() {
        Job job = runningJob(1L, Duration.ofMinutes(5));
        when(this.jobRepository.findByStatusIn(any())).thenReturn(List.of(job));

        this.watchdog.scan();
        assertNoJudgement();
    }

    // ── U8 / 观测面：tracked 回落、lastScanAt 更新、空在途集合零动作 ──

    @Test
    void trackedReflectsLatestScanAndLastScanAtAdvances() {
        when(this.jobRepository.findByStatusIn(any())).thenReturn(List.of(
                runningJob(1L, Duration.ZERO), pendingJob(2L, Duration.ZERO)));

        this.watchdog.scan();
        Map<String, Object> metrics = this.watchdog.metrics();
        assertThat(metrics.get("tracked")).isEqualTo(2);
        assertThat(metrics.get("lastScanAt")).isNotNull();
        assertThat(Instant.parse((String) metrics.get("lastScanAt"))).isEqualTo(Instant.ofEpochMilli(this.clock.millis()));
        assertNoJudgement();
    }

    @Test
    void emptyInFlightSetCausesNoAction() {
        when(this.jobRepository.findByStatusIn(any())).thenReturn(List.of());

        this.watchdog.scan();

        assertThat(this.watchdog.metrics().get("tracked")).isEqualTo(0);
        verify(this.terminalWriter, never()).complete(any(), any(), anyInt(), anyInt(), anyInt(), anyInt(), any());
        verify(this.cancellationRegistry, never()).cancel(anyLong(), any());
        verify(this.issueRepository, never()).save(any());
    }

    // ── 收口修：慢临界段 hold 豁免（红队 M1 / 复核 C2）──

    @Test
    void heldJobIsExemptFromHeartbeatJudgementUntilReleased() {
        Job job = runningJob(1L, Duration.ZERO);
        when(this.jobRepository.findByStatusIn(any())).thenReturn(List.of(job));
        this.heartbeatRegistry.beat(1L);
        this.heartbeatRegistry.hold(1L);

        // held 期间远超 90s 判死窗也不判死（心跳判死豁免；release 幂等，重复调用无副作用）
        this.clock.advance(Duration.ofSeconds(120));
        this.watchdog.scan();
        assertNoJudgement();

        this.heartbeatRegistry.release(1L);
        this.heartbeatRegistry.release(1L);
        this.watchdog.scan();

        assertThat(this.watchdog.metrics().get("judgedDeadTotal")).isEqualTo(1L);
        assertJudgementChain(job, "判死");
    }

    @Test
    void leakedHoldStillCappedByAbsoluteTimeout() {
        Job job = runningJob(1L, Duration.ofMinutes(31)); // 已运行 31min，心跳此刻新鲜
        when(this.jobRepository.findByStatusIn(any())).thenReturn(List.of(job));
        this.heartbeatRegistry.beat(1L);
        this.heartbeatRegistry.hold(1L); // hold 泄漏（未 release）：30min 上限仍兜底

        this.watchdog.scan();

        assertThat(this.watchdog.metrics().get("judgedTimeoutTotal")).isEqualTo(1L);
        assertJudgementChain(job, "判超时");
    }

    // ── 收口修：判死幂等（红队 m7）—— 同一作业连续扫描不重复写 issue / 不重复累加 ──

    @Test
    void timeoutJudgementIsIdempotentAcrossRepeatedScans() {
        // mock 下终态不落库：若判重缺失，第二次 scan 会对同一作业再次判超时
        Job job = runningJob(1L, Duration.ofMinutes(31));
        when(this.jobRepository.findByStatusIn(any())).thenReturn(List.of(job));

        this.watchdog.scan();
        this.watchdog.scan();

        assertThat(this.watchdog.metrics().get("judgedTimeoutTotal")).isEqualTo(1L);
        verify(this.issueRepository, times(1)).save(any());
        verify(this.terminalWriter, times(1)).complete(any(), any(), anyInt(), anyInt(), anyInt(), anyInt(), any());
        verify(this.cancellationRegistry, times(1)).cancel(anyLong(), any());
    }

    // ── 收口修：看门狗扫描对 Error 免疫（红队 m4）—— 单轮 Error 不杀死调度 ──

    @Test
    void scanSafelySurvivesErrorAndNextScanStillExecutes() {
        when(this.jobRepository.findByStatusIn(any()))
                .thenThrow(new StackOverflowError("模拟扫描期 Error"))
                .thenReturn(List.of());

        this.watchdog.scanSafely(); // Error 被兜住并留根因日志，不逃出调度线程
        this.watchdog.scanSafely(); // 下一轮扫描仍被执行（scheduleWithFixedDelay 不被终止）

        assertThat(this.watchdog.metrics().get("tracked")).isEqualTo(0);
        assertThat(this.watchdog.metrics().get("lastScanAt")).isNotNull();
    }

    // ───────────────────────── helpers ─────────────────────────

    private void assertNoJudgement() {
        verify(this.terminalWriter, never()).complete(any(), any(), anyInt(), anyInt(), anyInt(), anyInt(), any());
        verify(this.cancellationRegistry, never()).cancel(anyLong(), any());
    }

    /** 判死/判超时后的固定动作链：issue（ERROR + "-" 哨兵）→ terminalWriter.complete(FAILED) → cancel("job-watchdog")。 */
    private void assertJudgementChain(Job job, String reasonKeyword) {
        ArgumentCaptor<ValidationIssue> captor = ArgumentCaptor.forClass(ValidationIssue.class);
        verify(this.issueRepository).save(captor.capture());
        ValidationIssue issue = captor.getValue();
        assertThat(issue.getJobId()).isEqualTo(job.getId());
        assertThat(issue.getDefCode()).as("作业级 issue 的 defCode = 哨兵 \"-\"").isEqualTo("-");
        assertThat(issue.getSeverity()).isEqualTo(ValidationIssue.Severity.ERROR);
        assertThat(issue.getMessage()).contains("作业看门狗").contains(reasonKeyword);

        verify(this.terminalWriter).complete(eq(job), eq(Job.JobStatus.FAILED),
                anyInt(), anyInt(), anyInt(), anyInt(), any());
        verify(this.cancellationRegistry).cancel(job.getId(), "job-watchdog");
    }

    private Job runningJob(Long id, Duration startedAgo) {
        Job job = baseJob(id, Job.JobStatus.RUNNING);
        job.setStartedAt(LocalDateTime.now().minus(startedAgo));
        job.setCreatedAt(LocalDateTime.now().minus(startedAgo));
        return job;
    }

    private Job pendingJob(Long id, Duration createdAgo) {
        Job job = baseJob(id, Job.JobStatus.PENDING);
        job.setCreatedAt(LocalDateTime.now().minus(createdAgo));
        return job;
    }

    private Job baseJob(Long id, Job.JobStatus status) {
        Job job = new Job();
        job.setId(id);
        job.setTaskId(id * 10);
        job.setJobType(Job.JobType.EXPORT);
        job.setStatus(status);
        job.setProgress(3);
        job.setTotal(10);
        return job;
    }

    /** 测试用可控时钟：only advances when told（判死窗边界断言因此确定性）。 */
    private static final class MutableClock extends Clock {
        private long current;

        private MutableClock(long current) {
            this.current = current;
        }

        private void reset(long current) {
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
