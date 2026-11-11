package com.example.configmgr.job.service;

import com.example.configmgr.config.AppProperties;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.job.repo.ValidationIssueRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * runner 共享键清理的顺序与半失败语义（复审边界 2）。
 *
 * <p>危险形态是"anchor 还在、beat 已删"：对端看到 anchor-only ⇒ {@code STALE} ⇒ 可能给已完成/已撤销的
 * 作业留下不可撤销的 ERROR issue 与取消标志。因此清理改为<b>先删 beat、后删 anchor</b>，并且：
 * <ul>
 * <li>beat 删除失败 ⇒ <b>不</b>删 anchor（两键都在 = {@code ALIVE}，对端不判死）；</li>
 * <li>beat 删成功、anchor 删除失败 ⇒ <b>补写一次短 TTL beat</b>，把最坏情形退化为"一个判死窗后
 *     才可能判死"（有界延迟），而不是立刻按 STALE 误杀；</li>
 * <li>TTL 仍是短 TTL，不引入永久豁免或无限 TTL。</li>
 * </ul>
 */
class JobHeartbeatCleanupOrderTest {

    private static final long JOB_ID = 3L;

    private static final Duration DEAD_WINDOW = Duration.ofSeconds(90);

    private final MutableClock clock = new MutableClock(System.currentTimeMillis());

    private final FakeJobRedis shared = new FakeJobRedis(this.clock);

    private final JobRepository jobRepository = mock(JobRepository.class);
    private final JobTerminalWriter terminalWriter = mock(JobTerminalWriter.class);
    private final JobCancellationRegistry cancellationRegistry = mock(JobCancellationRegistry.class);
    private final ValidationIssueRepository issueRepository = mock(ValidationIssueRepository.class);

    private final JobHeartbeatRegistry runner = registry();
    private final JobHeartbeatRegistry peer = registry();

    private final JobWatchdog peerWatchdog = new JobWatchdog(this.jobRepository, this.peer,
            this.terminalWriter, this.cancellationRegistry, this.issueRepository,
            new AppProperties(), this.clock);

    private final String beatKey = JobHeartbeatRegistry.BEAT_PREFIX + JOB_ID;
    private final String anchorKey = JobHeartbeatRegistry.RUNNER_ACTIVE_PREFIX + JOB_ID;

    @BeforeEach
    void oneRunningJob() {
        when(this.jobRepository.findByStatusIn(any())).thenReturn(List.of(runningJob()));
        JobRepository.CancelState inFlight = mock(JobRepository.CancelState.class);
        when(inFlight.getStatus()).thenReturn(Job.JobStatus.RUNNING);
        when(this.jobRepository.findCancelStateById(JOB_ID)).thenReturn(java.util.Optional.of(inFlight));
    }

    @AfterEach
    void stopTickers() {
        this.runner.stopTicker();
        this.peer.stopTicker();
    }

    // ── 顺序：先 beat、后 anchor ─────────────────────────────────────────────

    @Test
    void endRunnerDeletesBeatBeforeAnchor() {
        this.runner.beginRunner(JOB_ID);
        this.shared.deleteAttempts.clear();

        this.runner.endRunner(JOB_ID);

        assertThat(this.shared.deleteAttempts)
                .as("先删 beat、后删 anchor（anchor-only 才是误杀形态）")
                .containsExactly(this.beatKey, this.anchorKey);
        assertThat(this.shared.has(this.beatKey)).isFalse();
        assertThat(this.shared.has(this.anchorKey)).isFalse();
    }

    @Test
    void revokeRunnerWritesMonotonicRevocationTombstones() {
        this.runner.beginRunner(JOB_ID);
        this.shared.deleteAttempts.clear();

        this.runner.revokeRunner(JOB_ID);

        // 当前口径：撤销写 REVOKED 墓碑（代次围栏），不再删除键；之后即使本代存活也不能重建 beat/anchor
        assertThat(this.shared.get(this.beatKey)).startsWith("REVOKED|");
        assertThat(this.shared.get(this.anchorKey)).startsWith("REVOKED|");
    }

    @Test
    void endRunnerDoesNotDeleteForeignRunnerKeys() {
        this.shared.seed(this.anchorKey, "runner-other-host-pid4242-abcdef", this.runner.runnerActiveTtl());
        this.shared.seed(this.beatKey, "runner-other-host-pid4242-abcdef", this.runner.beatTtl());

        this.runner.endRunner(JOB_ID);

        // 当前口径：删除按本进程 generation 围栏；没有本代登记不得误删其它实例的键
        assertThat(this.shared.deleteAttempts).isEmpty();
        assertThat(this.shared.has(this.beatKey)).isTrue();
        assertThat(this.shared.has(this.anchorKey)).isTrue();
    }

    // ── 半失败 ①：beat 删除失败 ⇒ 保留 anchor（两键都在 = ALIVE）──────────────

    @Test
    void beatDeleteFailureLeavesBothKeysAndPeerSeesAlive() {
        this.runner.beginRunner(JOB_ID);
        this.shared.failDeleteOf(this.beatKey);

        assertThatCode(() -> this.runner.endRunner(JOB_ID)).doesNotThrowAnyException();

        assertThat(this.shared.has(this.beatKey)).as("beat 删除失败：键仍在").isTrue();
        assertThat(this.shared.has(this.anchorKey)).as("不得留下 anchor-only（那会被判 STALE）").isTrue();
        assertThat(this.peer.readSharedBeat(JOB_ID, DEAD_WINDOW.toMillis()).state())
                .isEqualTo(JobHeartbeatRegistry.SharedBeatState.ALIVE);
    }

    // ── 半失败 ②：anchor 删除失败 ⇒ 补写短 TTL beat，最坏延迟判死 ─────────────

    @Test
    void anchorDeleteFailureRearmsShortBeatInsteadOfImmediateStale() {
        this.runner.beginRunner(JOB_ID);
        this.shared.failDeleteOf(this.anchorKey);

        this.runner.endRunner(JOB_ID);

        assertThat(this.shared.has(this.beatKey)).as("补写 beat，避免 anchor-only").isTrue();
        assertThat(this.shared.ttl(this.beatKey))
                .as("补写用短 TTL（不是无限豁免）").isEqualTo(this.runner.beatTtl());
        assertThat(this.peer.readSharedBeat(JOB_ID, DEAD_WINDOW.toMillis()).state())
                .isEqualTo(JobHeartbeatRegistry.SharedBeatState.ALIVE);

        this.clock.advance(this.runner.beatTtl().plusSeconds(1));
        assertThat(this.peer.readSharedBeat(JOB_ID, DEAD_WINDOW.toMillis()).state())
                .as("有界延迟：一个判死窗后才退化为 STALE，而不是立刻误杀")
                .isEqualTo(JobHeartbeatRegistry.SharedBeatState.STALE);
    }

    @Test
    void peerWatchdogDoesNotKillJobRightAfterHalfFailedEndRunner() {
        this.runner.beginRunner(JOB_ID);
        this.shared.failDeleteOf(this.anchorKey);
        this.runner.endRunner(JOB_ID);

        this.peerWatchdog.scan();

        verify(this.terminalWriter, never())
                .complete(any(), any(), anyInt(), anyInt(), anyInt(), anyInt(), any());
        verify(this.issueRepository, never()).save(any());
        assertThat(this.peerWatchdog.metrics().get("judgedDeadTotal")).isEqualTo(0L);
    }

    // ───────────────────────── helpers ─────────────────────────

    /** 当前构造器把时钟固定为 systemUTC；用例用反射注入可变时钟，让 TTL 边界可确定推进。 */
    private JobHeartbeatRegistry registry() {
        JobHeartbeatRegistry created = new JobHeartbeatRegistry(this.shared.redis, "redis",
                new AppProperties(), this.jobRepository, this.cancellationRegistry);
        ReflectionTestUtils.setField(created, "clock", this.clock);
        return created;
    }

    private Job runningJob() {
        Job job = new Job();
        job.setId(JOB_ID);
        job.setTaskId(30L);
        job.setJobType(Job.JobType.EXPORT);
        job.setStatus(Job.JobStatus.RUNNING);
        job.setProgress(1);
        job.setTotal(10);
        job.setStartedAt(LocalDateTime.now());
        job.setCreatedAt(LocalDateTime.now());
        return job;
    }

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
