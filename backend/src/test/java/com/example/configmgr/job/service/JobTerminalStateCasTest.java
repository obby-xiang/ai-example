package com.example.configmgr.job.service;

import com.example.configmgr.common.ConflictException;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.entity.JobItem;
import com.example.configmgr.job.repo.JobItemRepository;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.repo.TaskRepository;
import com.example.configmgr.task.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * R1-11 / R1-12 的真实 H2 并发与事务用例（不用 Mockito 事务锁模拟串行化）。
 *
 * <p>覆盖：
 * <ul>
 * <li>重复完成 —— 终态条件 UPDATE 只允许第一个写者落写，伴随字段整体不被覆盖；</li>
 * <li>完成与取消竞态 —— 条件 UPDATE 的影响行数决定唯一胜者；</li>
 * <li>取消的 Redis / SSE 旁路必须在 DB 提交之后才执行（旧实现事务内做网络 I/O）；</li>
 * <li>提交后失败归因 —— Redis 失败不回滚已提交的取消，只体现为 {@code redisWritten=false}；</li>
 * <li>finishItem 同步核父作业终态与进度 —— 作业已终态时条目终态写必须被丢弃；</li>
 * <li>snapshot 不得把终态之后的进度写回旧值。</li>
 * </ul>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:r1-11-12-cas;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "app.job.demo-batch-delay-ms=0",
        "app.file.storage-path=./target/r1-11-12-cas-files"
})
class JobTerminalStateCasTest {

    @Autowired
    private JobService jobService;
    @Autowired
    private JobProgressService progressService;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private JobItemRepository jobItemRepository;
    @Autowired
    private TaskService taskService;
    @Autowired
    private TaskRepository taskRepository;
    @Autowired
    private DataSource dataSource;

    @MockitoBean
    private StringRedisTemplate redis;
    @MockitoBean
    private com.example.configmgr.task.service.TaskSseService taskSseService;

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps =
            org.mockito.Mockito.mock(ValueOperations.class);

    @BeforeEach
    void redisIsReachableUnlessTestOverridesIt() {
        lenient().when(this.redis.opsForValue()).thenReturn(this.valueOps);
    }

    // ── 重复完成 ──────────────────────────────────────────────────────────

    @Test
    void repeatedCompletionKeepsFirstTerminalAndIgnoresLaterWriteEntirely() {
        Job job = runningJob("R1-11-重复完成");

        assertThat(progressService.finish(job.getId(), Job.JobStatus.COMPLETED, 0, 0, 10, 10))
                .as("首个终态写必须胜出").isTrue();
        assertThat(progressService.finish(job.getId(), Job.JobStatus.FAILED, 9, 0, 3, 10))
                .as("第二个终态写必须被条件 UPDATE 拦下").isFalse();

        Job stored = reload(job.getId());
        assertThat(stored.getStatus()).isEqualTo(Job.JobStatus.COMPLETED);
        assertThat(stored.getErrorCount()).as("后到者不得覆盖伴随字段").isZero();
        assertThat(stored.getProgress()).as("后到者不得覆盖进度").isEqualTo(10);
        assertThat(stored.getTotal()).isEqualTo(10);
        assertThat(stored.getFinishedAt()).isNotNull();
    }

    @Test
    void concurrentTerminalWritersHaveExactlyOneWinnerOnRealH2() throws Exception {
        Job job = runningJob("R1-11-并发完成");

        int writers = 8;
        CyclicBarrier barrier = new CyclicBarrier(writers);
        AtomicInteger wins = new AtomicInteger();
        AtomicReference<Job.JobStatus> winningStatus = new AtomicReference<>();
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < writers; i++) {
            boolean completed = i % 2 == 0;
            Job.JobStatus status = completed ? Job.JobStatus.COMPLETED : Job.JobStatus.FAILED;
            int processed = completed ? 10 : 7;
            int errors = completed ? 0 : 1;
            Thread t = new Thread(() -> {
                try {
                    barrier.await();
                    if (progressService.finish(job.getId(), status, errors, 0, processed, 10)) {
                        wins.incrementAndGet();
                        winningStatus.set(status);
                    }
                } catch (Throwable e) {
                    failures.add(e);
                }
            }, "r1-11-finish-" + i);
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }

        assertThat(failures).as("并发 finish 不得抛异常").isEmpty();
        assertThat(wins.get()).as("真实 H2 上只能有一个终态写者获胜").isEqualTo(1);
        Job stored = reload(job.getId());
        assertThat(stored.getStatus()).isEqualTo(winningStatus.get());
        assertThat(stored.getProgress()).isEqualTo(stored.getStatus() == Job.JobStatus.COMPLETED ? 10 : 7);
        assertThat(stored.getErrorCount()).isEqualTo(stored.getStatus() == Job.JobStatus.COMPLETED ? 0 : 1);
    }

    // ── 开工与取消竞态 ────────────────────────────────────────────────────

    @Test
    void markRunningCannotResurrectAJobCancelledConcurrently() throws Exception {
        for (int round = 0; round < 10; round++) {
            Task task = taskService.create(Task.TaskType.EXPORT, "R1-11-开工竞态-" + round);
            Job job = jobRepository.saveAndFlush(jobRow(task.getId(), Job.JobStatus.PENDING));

            CyclicBarrier barrier = new CyclicBarrier(2);
            AtomicReference<Throwable> unexpected = new AtomicReference<>();
            Thread starter = new Thread(() -> {
                try {
                    barrier.await();
                    progressService.markRunning(job.getId(), 100);
                } catch (Throwable e) {
                    unexpected.compareAndSet(null, e);
                }
            });
            Thread canceller = new Thread(() -> {
                try {
                    barrier.await();
                    progressService.finish(job.getId(), Job.JobStatus.CANCELLED, 0, 0, 0, 100);
                } catch (Throwable e) {
                    unexpected.compareAndSet(null, e);
                }
            });
            starter.start();
            canceller.start();
            starter.join();
            canceller.join();

            assertThat(unexpected.get()).isNull();
            assertThat(reload(job.getId()).getStatus())
                    .as("第 %d 轮：取消已提交后，开工写不得把终态翻回 RUNNING", round)
                    .isEqualTo(Job.JobStatus.CANCELLED);
        }
    }

    // ── 完成与取消竞态 ────────────────────────────────────────────────────

    @Test
    void completionAndCancelRaceHasExactlyOneWinnerAcrossRounds() throws Exception {
        for (int round = 0; round < 12; round++) {
            Task task = taskService.create(Task.TaskType.EXPORT, "R1-12-竞态-" + round);
            Job job = jobRepository.saveAndFlush(jobRow(task.getId(), Job.JobStatus.RUNNING));

            CyclicBarrier barrier = new CyclicBarrier(2);
            AtomicBoolean finishWon = new AtomicBoolean();
            AtomicBoolean cancelWon = new AtomicBoolean();
            AtomicReference<Throwable> unexpected = new AtomicReference<>();

            Thread finisher = new Thread(() -> {
                try {
                    barrier.await();
                    finishWon.set(progressService.finish(job.getId(), Job.JobStatus.COMPLETED, 0, 0, 10, 10));
                } catch (Throwable e) {
                    unexpected.compareAndSet(null, e);
                }
            });
            Thread canceller = new Thread(() -> {
                try {
                    barrier.await();
                    jobService.cancel(job.getId(), "race");
                    cancelWon.set(true);
                } catch (ConflictException expected) {
                    assertThat(expected.getCode()).isEqualTo("JOB_ALREADY_FINAL");
                } catch (Throwable e) {
                    unexpected.compareAndSet(null, e);
                }
            });
            finisher.start();
            canceller.start();
            finisher.join();
            canceller.join();

            assertThat(unexpected.get()).as("竞态不得产生非预期异常").isNull();
            assertThat(finishWon.get() ^ cancelWon.get())
                    .as("第 %d 轮：完成与取消只能有一个胜者（finishWon=%s, cancelWon=%s）",
                            round, finishWon.get(), cancelWon.get())
                    .isTrue();

            Job stored = reload(job.getId());
            assertThat(stored.getStatus())
                    .isEqualTo(cancelWon.get() ? Job.JobStatus.CANCELLED : Job.JobStatus.COMPLETED);
            assertThat(taskRepository.findById(task.getId()).orElseThrow().getStatus())
                    .isEqualTo(cancelWon.get() ? Task.TaskStatus.CANCELLED : Task.TaskStatus.ACTIVE);
        }
    }

    // ── 取消：DB 提交后才做 Redis / SSE，提交后失败归因 ─────────────────────

    @Test
    void redisIsWrittenOnlyAfterCancelIsCommitted() {
        Task task = taskService.create(Task.TaskType.EXPORT, "R1-12-提交后Redis");
        Job job = jobRepository.saveAndFlush(jobRow(task.getId(), Job.JobStatus.RUNNING));

        AtomicReference<String> statusSeenFromAnotherConnection = new AtomicReference<>();
        doAnswer(invocation -> {
            statusSeenFromAnotherConnection.set(scalar(
                    "SELECT STATUS FROM JOBS WHERE ID = " + job.getId()));
            return null;
        }).when(this.valueOps).set(anyString(), anyString(), any(Duration.class));

        jobService.cancel(job.getId(), "api");

        assertThat(statusSeenFromAnotherConnection.get())
                .as("Redis 写必须发生在 DB 提交之后（另一连接应已看到 CANCELLED）")
                .isEqualTo("CANCELLED");
    }

    @Test
    void sseIsPublishedOnlyAfterCancelIsCommitted() {
        Task task = taskService.create(Task.TaskType.EXPORT, "R1-12-提交后SSE");
        Job job = jobRepository.saveAndFlush(jobRow(task.getId(), Job.JobStatus.RUNNING));

        AtomicReference<String> statusSeenFromAnotherConnection = new AtomicReference<>();
        doAnswer(invocation -> {
            statusSeenFromAnotherConnection.set(scalar(
                    "SELECT STATUS FROM JOBS WHERE ID = " + job.getId()));
            return null;
        }).when(this.taskSseService).publish(anyLong(), anyString(), any());

        jobService.cancel(job.getId(), "api");

        verify(this.taskSseService).publish(anyLong(), anyString(), any());
        assertThat(statusSeenFromAnotherConnection.get())
                .as("SSE 推送必须发生在 DB 提交之后").isEqualTo("CANCELLED");
    }

    @Test
    void redisFailureAfterCommitDoesNotRollBackAndIsAttributedToTheSideEffect() {
        Task task = taskService.create(Task.TaskType.EXPORT, "R1-12-Redis失败归因");
        Job job = jobRepository.saveAndFlush(jobRow(task.getId(), Job.JobStatus.RUNNING));

        AtomicReference<String> jobStatusSeen = new AtomicReference<>();
        AtomicReference<String> taskStatusSeen = new AtomicReference<>();
        doAnswer(invocation -> {
            jobStatusSeen.set(scalar("SELECT STATUS FROM JOBS WHERE ID = " + job.getId()));
            taskStatusSeen.set(scalar("SELECT STATUS FROM TASKS WHERE ID = " + task.getId()));
            throw new RedisConnectionFailureException("redis down in test");
        }).when(this.valueOps).set(anyString(), anyString(), any(Duration.class));

        JobCancellationRegistry.CancelResult result = jobService.cancel(job.getId(), "api");

        assertThat(result.redisWritten())
                .as("Redis 失败只体现为降级标志，不得抛错/回滚").isFalse();
        assertThat(result.wokeInProcess()).isTrue();
        assertThat(jobStatusSeen.get()).as("失败发生在提交之后，另一连接应已看到 CANCELLED")
                .isEqualTo("CANCELLED");
        assertThat(taskStatusSeen.get()).isEqualTo("CANCELLED");
        assertThat(reload(job.getId()).getStatus()).isEqualTo(Job.JobStatus.CANCELLED);
        assertThat(taskRepository.findById(task.getId()).orElseThrow().getStatus())
                .isEqualTo(Task.TaskStatus.CANCELLED);
    }

    @Test
    void lateCompletionDuringCancelSideEffectsLosesToCommittedCancel() {
        Task task = taskService.create(Task.TaskType.EXPORT, "R1-12-迟到完成");
        Job job = jobRepository.saveAndFlush(jobRow(task.getId(), Job.JobStatus.RUNNING));

        AtomicBoolean lateCompletionWon = new AtomicBoolean();
        doAnswer(invocation -> {
            lateCompletionWon.set(progressService.finish(
                    job.getId(), Job.JobStatus.COMPLETED, 0, 0, 10, 10));
            return null;
        }).when(this.valueOps).set(anyString(), anyString(), any(Duration.class));

        jobService.cancel(job.getId(), "api");

        assertThat(lateCompletionWon.get())
                .as("取消 CAS 已提交，迟到 COMPLETED 必须被丢弃（不得出现两个胜者）").isFalse();
        assertThat(reload(job.getId()).getStatus()).isEqualTo(Job.JobStatus.CANCELLED);
        assertThat(taskRepository.findById(task.getId()).orElseThrow().getStatus())
                .isEqualTo(Task.TaskStatus.CANCELLED);
    }

    // ── finishItem：同步核父作业终态与进度 ─────────────────────────────────

    @Test
    void finishItemIsDroppedWhenParentJobIsAlreadyTerminal() {
        Job job = runningJob("R1-11-条目终态守卫");
        JobItem item = item(job.getId(), "CURRENCY");

        assertThat(progressService.finish(job.getId(), Job.JobStatus.CANCELLED, 0, 0, 0, 10)).isTrue();

        progressService.finishItem(item.getId(), "COMPLETED", 10, 10);

        JobItem stored = jobItemRepository.findById(item.getId()).orElseThrow();
        assertThat(stored.getStatus()).as("父作业已终态，条目终态写必须被丢弃").isEqualTo("RUNNING");
        assertThat(stored.getProcessed()).isZero();
        assertThat(stored.getTotal()).isZero();
    }

    @Test
    void finishItemStillRecordsCancelledAfterParentJobCancelled() {
        Job job = runningJob("R1-11-条目取消收口");
        JobItem item = item(job.getId(), "CURRENCY");

        assertThat(progressService.finish(job.getId(), Job.JobStatus.CANCELLED, 0, 0, 7, 10)).isTrue();

        progressService.finishItem(item.getId(), "CANCELLED", 7, 10);

        JobItem stored = jobItemRepository.findById(item.getId()).orElseThrow();
        assertThat(stored.getStatus())
                .as("父作业 CANCELLED 时，条目收成 CANCELLED 是同一语义，不得丢")
                .isEqualTo("CANCELLED");
        assertThat(stored.getProcessed()).isEqualTo(7);
        assertThat(stored.getTotal()).isEqualTo(10);
    }

    @Test
    void finishItemWritesStatusAndProgressWhenParentJobIsInFlight() {
        Job job = runningJob("R1-11-条目在途");
        JobItem item = item(job.getId(), "CURRENCY");

        progressService.finishItem(item.getId(), "COMPLETED", 10, 10);

        JobItem stored = jobItemRepository.findById(item.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo("COMPLETED");
        assertThat(stored.getProcessed()).isEqualTo(10);
        assertThat(stored.getTotal()).isEqualTo(10);
    }

    @Test
    void snapshotAfterTerminalDoesNotRegressProgress() {
        Job job = runningJob("R1-11-进度回退");
        progressService.snapshot(job.getId(), 5, 10);
        assertThat(reload(job.getId()).getProgress()).isEqualTo(5);

        assertThat(progressService.finish(job.getId(), Job.JobStatus.COMPLETED, 0, 0, 10, 10)).isTrue();
        progressService.snapshot(job.getId(), 3, 10);

        Job stored = reload(job.getId());
        assertThat(stored.getProgress()).as("终态之后的旧进度快照不得写回").isEqualTo(10);
        assertThat(stored.getTotal()).isEqualTo(10);
    }

    // ───────────────────────── helpers ───────────────────────────────────

    private Job runningJob(String title) {
        Task task = taskService.create(Task.TaskType.EXPORT, title);
        return jobRepository.saveAndFlush(jobRow(task.getId(), Job.JobStatus.RUNNING));
    }

    private Job jobRow(Long taskId, Job.JobStatus status) {
        Job job = new Job();
        job.setTaskId(taskId);
        job.setJobType(Job.JobType.EXPORT);
        job.setStatus(status);
        return job;
    }

    private JobItem item(Long jobId, String defCode) {
        JobItem item = new JobItem();
        item.setJobId(jobId);
        item.setDefCode(defCode);
        item.setStatus("RUNNING");
        return jobItemRepository.saveAndFlush(item);
    }

    private Job reload(Long jobId) {
        return jobRepository.findById(jobId).orElseThrow();
    }

    private String scalar(String sql) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            assertThat(rs.next()).as("查询必须有结果：" + sql).isTrue();
            return rs.getString(1);
        } catch (Exception e) {
            throw new IllegalStateException("跨连接查询失败：" + sql, e);
        }
    }
}
