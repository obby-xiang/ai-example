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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * R1-11 / R1-12 修轮 1（I-1 / I-2 / I-3）的真实 H2 用例。
 *
 * <p>覆盖：
 * <ul>
 * <li>I-1：取消的 Redis/SSE 旁路在事务模板返回后执行 —— 事务同步已清理、另一连接已见 CANCELLED；</li>
 * <li>I-1 兼容：重复取消仍为 409，且 409 不写 Redis/不推 SSE；SSE 失败归因不回滚；</li>
 * <li>I-1 列保留：取消不整行回写 progress/total/startedAt；</li>
 * <li>I-2：取消后写指纹摘要不复活作业；并发“先读 RUNNING、后取消、再写摘要”同样不复活；</li>
 * <li>I-3：updateItem / markItemFinal 在父作业终态或条目终态后的迟到写 0 行，
 *     同时保留取消补偿所需的条目 CANCELLED 路径与缺失条目的插入路径。</li>
 * </ul>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:r1-fix-round1;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "app.job.demo-batch-delay-ms=0",
        "app.file.storage-path=./target/r1-fix-round1-files"
})
class JobSideWriteGuardTest {

    @Autowired
    private JobService jobService;
    @Autowired
    private JobProgressService progressService;
    @Autowired
    private PrecheckJobRunner precheckJobRunner;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private JobItemRepository jobItemRepository;
    @Autowired
    private TaskService taskService;
    @Autowired
    private TaskRepository taskRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
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

    // ── I-1：旁路在事务提交 + 清理之后 ─────────────────────────────────────

    @Test
    void cancelSideEffectsRunAfterTransactionCleanup() {
        Task task = taskService.create(Task.TaskType.EXPORT, "I1-清理后旁路");
        Job job = jobRepository.saveAndFlush(jobRow(task.getId(), Job.JobStatus.RUNNING));

        AtomicReference<Boolean> synchronizationActive = new AtomicReference<>();
        AtomicReference<Boolean> transactionActive = new AtomicReference<>();
        AtomicReference<Object> boundDataSource = new AtomicReference<>();
        AtomicReference<String> statusSeen = new AtomicReference<>();
        doAnswer(invocation -> {
            synchronizationActive.set(TransactionSynchronizationManager.isSynchronizationActive());
            transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            boundDataSource.set(TransactionSynchronizationManager.getResource(dataSource));
            statusSeen.set(scalar("SELECT STATUS FROM JOBS WHERE ID = " + job.getId()));
            return null;
        }).when(this.valueOps).set(anyString(), anyString(), any(Duration.class));

        jobService.cancel(job.getId(), "api");

        assertThat(synchronizationActive.get())
                .as("旁路执行时事务同步必须已清理（afterCommit 阶段仍为 true）").isFalse();
        assertThat(transactionActive.get())
                .as("旁路执行时不得仍绑定在事务上").isFalse();
        assertThat(boundDataSource.get())
                .as("旁路执行时 JDBC 连接资源必须已解绑（afterCommit 阶段仍绑定）").isNull();
        assertThat(statusSeen.get())
                .as("旁路执行时另一连接必须已看到 CANCELLED").isEqualTo("CANCELLED");
    }

    @Test
    void sseFailureAfterCommitIsAttributedAndDoesNotRollbackCancel() {
        Task task = taskService.create(Task.TaskType.EXPORT, "I1-SSE失败归因");
        Job job = jobRepository.saveAndFlush(jobRow(task.getId(), Job.JobStatus.RUNNING));

        doThrow(new IllegalStateException("sse down in test"))
                .when(this.taskSseService).publish(anyLong(), anyString(), any());

        JobCancellationRegistry.CancelResult result = jobService.cancel(job.getId(), "api");

        verify(this.taskSseService).publish(anyLong(), anyString(), any());
        assertThat(result.redisWritten()).isTrue();
        assertThat(reload(job.getId()).getStatus())
                .as("SSE 失败只归因到提交后旁路，不回滚已提交的取消")
                .isEqualTo(Job.JobStatus.CANCELLED);
        assertThat(taskRepository.findById(task.getId()).orElseThrow().getStatus())
                .isEqualTo(Task.TaskStatus.CANCELLED);
    }

    @Test
    void cancelDoesNotFullRowOverwriteProgressTotalAndStartedAt() {
        Task task = taskService.create(Task.TaskType.EXPORT, "I1-列保留");
        Job job = jobRow(task.getId(), Job.JobStatus.RUNNING);
        job.setProgress(5);
        job.setTotal(10);
        job.setStartedAt(LocalDateTime.of(2020, 1, 1, 0, 0));
        Job saved = jobRepository.saveAndFlush(job);

        doAnswer(invocation -> {
            execute("UPDATE JOBS SET PROGRESS = 42, TOTAL = 100, "
                    + "STARTED_AT = TIMESTAMP '2020-01-02 03:04:05' WHERE ID = " + saved.getId());
            return null;
        }).when(this.valueOps).set(anyString(), anyString(), any(Duration.class));

        jobService.cancel(saved.getId(), "api");

        Job stored = reload(saved.getId());
        assertThat(stored.getStatus()).isEqualTo(Job.JobStatus.CANCELLED);
        assertThat(stored.getProgress()).as("取消不得用整行 save 覆盖并发写入的 progress").isEqualTo(42);
        assertThat(stored.getTotal()).isEqualTo(100);
        assertThat(stored.getStartedAt())
                .as("取消不得回写 startedAt")
                .isEqualTo(LocalDateTime.of(2020, 1, 2, 3, 4, 5));
    }

    // ── I-1 兼容：重复取消 / 409 旁路零副作用 ─────────────────────────────

    @Test
    void secondCancelIsConflictAndWritesNoRedisOrSse() {
        Task task = taskService.create(Task.TaskType.EXPORT, "I1-重复取消");
        Job job = jobRepository.saveAndFlush(jobRow(task.getId(), Job.JobStatus.RUNNING));

        jobService.cancel(job.getId(), "api");
        clearInvocations(this.valueOps, this.taskSseService);

        assertThatThrownBy(() -> jobService.cancel(job.getId(), "api"))
                .isInstanceOfSatisfying(ConflictException.class,
                        conflict -> assertThat(conflict.getCode()).isEqualTo("JOB_ALREADY_FINAL"));

        verifyNoInteractions(this.valueOps, this.taskSseService);
        assertThat(reload(job.getId()).getStatus()).isEqualTo(Job.JobStatus.CANCELLED);
    }

    @Test
    void cancelOnTerminalJobIsConflictWithNoRedisOrSseSideEffects() {
        Task task = taskService.create(Task.TaskType.EXPORT, "I1-终态取消");
        Job job = jobRepository.saveAndFlush(jobRow(task.getId(), Job.JobStatus.COMPLETED));

        assertThatThrownBy(() -> jobService.cancel(job.getId(), "api"))
                .isInstanceOfSatisfying(ConflictException.class,
                        conflict -> assertThat(conflict.getCode()).isEqualTo("JOB_ALREADY_FINAL"));

        verifyNoInteractions(this.valueOps, this.taskSseService);
        assertThat(reload(job.getId()).getStatus()).isEqualTo(Job.JobStatus.COMPLETED);
    }

    // ── I-2：指纹摘要定向条件写 ────────────────────────────────────────────

    @Test
    void fingerprintSummaryAfterCancelIsNotWrittenAndDoesNotResurrect() {
        Job job = runningJob("I2-取消后摘要");

        progressService.finish(job.getId(), Job.JobStatus.CANCELLED, 0, 0, 0, 10);
        writeSummaryInTransaction(job.getId());

        Job stored = reload(job.getId());
        assertThat(stored.getStatus()).isEqualTo(Job.JobStatus.CANCELLED);
        assertThat(stored.getResultJson())
                .as("已取消作业不再写入指纹摘要（条件 UPDATE 影响 0 行）").isNull();
    }

    @Test
    void fingerprintSummaryCannotResurrectCancelCommittedAfterRead() throws Exception {
        Job job = runningJob("I2-读后取消");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch readDone = new CountDownLatch(1);
        CountDownLatch cancelDone = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread writer = new Thread(() -> {
            try {
                tx.executeWithoutResult(status -> {
                    jobRepository.findById(job.getId()).orElseThrow();
                    readDone.countDown();
                    try {
                        assertThat(cancelDone.await(5, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    precheckJobRunner.writeFingerprintSummary(job.getId(), summary());
                });
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "i2-summary-writer");
        writer.start();

        assertThat(readDone.await(5, TimeUnit.SECONDS)).isTrue();
        progressService.finish(job.getId(), Job.JobStatus.CANCELLED, 0, 0, 0, 10);
        cancelDone.countDown();
        writer.join(5000);

        assertThat(failure.get()).isNull();
        Job stored = reload(job.getId());
        assertThat(stored.getStatus())
                .as("长事务先读到 RUNNING、随后取消已提交 —— 摘要写不得把作业翻回 RUNNING")
                .isEqualTo(Job.JobStatus.CANCELLED);
        assertThat(stored.getResultJson()).isNull();
    }

    @Test
    void fingerprintSummaryStillWritesWhenJobInFlight() {
        Job job = runningJob("I2-在途摘要");

        writeSummaryInTransaction(job.getId());

        Job stored = reload(job.getId());
        assertThat(stored.getStatus()).isEqualTo(Job.JobStatus.RUNNING);
        assertThat(stored.getResultJson()).isNotBlank().contains("\"UPLOAD\"");
    }

    // ── I-3：updateItem / markItemFinal 条件写 ─────────────────────────────

    @Test
    void updateItemAfterParentCancelledIsDropped() {
        Job job = runningJob("I3-进度-父终态");
        JobItem item = item(job.getId(), "CURRENCY");

        progressService.finish(job.getId(), Job.JobStatus.CANCELLED, 0, 0, 0, 10);
        progressService.updateItem(item.getId(), 5, 10);

        JobItem stored = jobItemRepository.findById(item.getId()).orElseThrow();
        assertThat(stored.getProcessed()).isZero();
        assertThat(stored.getTotal()).isZero();
    }

    @Test
    void updateItemAfterItemTerminalIsDropped() {
        Job job = runningJob("I3-进度-条目终态");
        JobItem item = item(job.getId(), "CURRENCY");
        item.setStatus("COMPLETED");
        item.setProcessed(10);
        item.setTotal(10);
        jobItemRepository.saveAndFlush(item);

        progressService.updateItem(item.getId(), 3, 10);

        JobItem stored = jobItemRepository.findById(item.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo("COMPLETED");
        assertThat(stored.getProcessed()).as("条目终态后的旧进度不得写回").isEqualTo(10);
    }

    @Test
    void markItemFinalAfterParentCancelledIsDropped() {
        Job job = runningJob("I3-补记-父终态");
        JobItem item = item(job.getId(), "CURRENCY");
        progressService.finish(job.getId(), Job.JobStatus.CANCELLED, 0, 0, 0, 10);

        progressService.markItemFinal(job.getId(), "CURRENCY", "FAILED", 0, 10);

        JobItem stored = jobItemRepository.findById(item.getId()).orElseThrow();
        assertThat(stored.getStatus()).as("父作业已 CANCELLED，FAILED 迟到补记不得翻写条目")
                .isEqualTo("RUNNING");
    }

    @Test
    void markItemFinalCancelledAfterParentCancelledIsWritten() {
        Job job = runningJob("I3-取消补偿");
        JobItem item = item(job.getId(), "CURRENCY");
        progressService.finish(job.getId(), Job.JobStatus.CANCELLED, 0, 0, 0, 10);

        progressService.markItemFinal(job.getId(), "CURRENCY", "CANCELLED", 7, 10);

        JobItem stored = jobItemRepository.findById(item.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo("CANCELLED");
        assertThat(stored.getProcessed()).isEqualTo(7);
        assertThat(stored.getTotal()).isEqualTo(10);
    }

    @Test
    void markItemFinalAfterItemTerminalIsDropped() {
        Job job = runningJob("I3-补记-条目终态");
        JobItem item = item(job.getId(), "CURRENCY");
        item.setStatus("COMPLETED");
        item.setProcessed(10);
        item.setTotal(10);
        jobItemRepository.saveAndFlush(item);

        progressService.markItemFinal(job.getId(), "CURRENCY", "FAILED", 0, 10);

        JobItem stored = jobItemRepository.findById(item.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo("COMPLETED");
        assertThat(stored.getProcessed()).isEqualTo(10);
    }

    @Test
    void markItemFinalInsertsWhenItemAbsentAndParentInFlight() {
        Job job = runningJob("I3-补记-插入");

        progressService.markItemFinal(job.getId(), "NEW_DEF", "FAILED", 0, 3);

        JobItem stored = jobItemRepository.findByJobIdAndDefCode(job.getId(), "NEW_DEF").orElseThrow();
        assertThat(stored.getStatus()).isEqualTo("FAILED");
        assertThat(stored.getTotal()).isEqualTo(3);
    }

    @Test
    void markItemFinalDoesNotInsertWhenParentCancelled() {
        Job job = runningJob("I3-补记-取消不插入");
        progressService.finish(job.getId(), Job.JobStatus.CANCELLED, 0, 0, 0, 10);

        progressService.markItemFinal(job.getId(), "NEW_DEF", "FAILED", 0, 3);

        assertThat(jobItemRepository.findByJobIdAndDefCode(job.getId(), "NEW_DEF")).isEmpty();
    }

    @Test
    void markItemFinalCancelledInsertsWhenParentCancelled() {
        Job job = runningJob("I3-补记-取消补偿插入");
        progressService.finish(job.getId(), Job.JobStatus.CANCELLED, 0, 0, 0, 10);

        progressService.markItemFinal(job.getId(), "NEW_DEF", "CANCELLED", 0, 3);

        JobItem stored = jobItemRepository.findByJobIdAndDefCode(job.getId(), "NEW_DEF").orElseThrow();
        assertThat(stored.getStatus()).isEqualTo("CANCELLED");
    }

    // ── I-4：失败补偿专用路径 ─────────────────────────────────────────────

    @Test
    void compensationDowngradesCompletedItemWhenParentInFlight() {
        Job job = runningJob("I4-补偿-成功路径回退");
        JobItem item = item(job.getId(), "CURRENCY");
        item.setStatus("COMPLETED");
        item.setProcessed(10);
        item.setTotal(10);
        jobItemRepository.saveAndFlush(item);

        assertThat(progressService.compensateItemFailure(item.getId(), 10, 10)).isTrue();

        JobItem stored = jobItemRepository.findById(item.getId()).orElseThrow();
        assertThat(stored.getStatus()).as("父作业在途时允许 COMPLETED→FAILED 的失败补偿")
                .isEqualTo("FAILED");
        assertThat(stored.getProcessed()).isEqualTo(10);
        assertThat(stored.getTotal()).isEqualTo(10);
    }

    @Test
    void compensationByJobIdDowngradesCompletedItemWhenParentInFlight() {
        Job job = runningJob("I4-补偿-发布键");
        JobItem item = item(job.getId(), "CURRENCY");
        item.setStatus("COMPLETED");
        item.setProcessed(10);
        item.setTotal(10);
        jobItemRepository.saveAndFlush(item);

        assertThat(progressService.compensateItemFailure(job.getId(), "CURRENCY", 10, 10)).isTrue();

        assertThat(jobItemRepository.findById(item.getId()).orElseThrow().getStatus())
                .as("Publish catch 按 jobId+defCode 也要能把 COMPLETED 降级为 FAILED")
                .isEqualTo("FAILED");
    }

    @Test
    void compensationDoesNotFlipCancelledItem() {
        Job job = runningJob("I4-补偿-取消条目不翻写");
        JobItem item = item(job.getId(), "CURRENCY");
        item.setStatus("CANCELLED");
        item.setProcessed(7);
        item.setTotal(10);
        jobItemRepository.saveAndFlush(item);

        assertThat(progressService.compensateItemFailure(item.getId(), 10, 10)).isFalse();

        JobItem stored = jobItemRepository.findById(item.getId()).orElseThrow();
        assertThat(stored.getStatus()).as("CANCELLED 条目不得被失败补偿翻写").isEqualTo("CANCELLED");
        assertThat(stored.getProcessed()).isEqualTo(7);
    }

    @Test
    void compensationDoesNotFlipWhenParentTerminal() {
        Job job = runningJob("I4-补偿-父终态");
        JobItem item = item(job.getId(), "CURRENCY");
        item.setStatus("COMPLETED");
        item.setProcessed(10);
        item.setTotal(10);
        jobItemRepository.saveAndFlush(item);
        progressService.finish(job.getId(), Job.JobStatus.CANCELLED, 0, 0, 0, 10);

        assertThat(progressService.compensateItemFailure(item.getId(), 10, 10)).isFalse();

        JobItem stored = jobItemRepository.findById(item.getId()).orElseThrow();
        assertThat(stored.getStatus()).as("父作业已 CANCELLED，补偿不得翻写条目")
                .isEqualTo("COMPLETED");
    }

    @Test
    void compensationInsertsFailedItemForPublishCatchWhenAbsentAndParentInFlight() {
        Job job = runningJob("I4-补偿-发布插入");

        assertThat(progressService.compensateItemFailure(job.getId(), "NEW_DEF", 0, 3)).isTrue();

        JobItem stored = jobItemRepository.findByJobIdAndDefCode(job.getId(), "NEW_DEF").orElseThrow();
        assertThat(stored.getStatus()).isEqualTo("FAILED");
    }

    @Test
    void compensationDoesNotInsertWhenParentCancelled() {
        Job job = runningJob("I4-补偿-发布不插入");
        progressService.finish(job.getId(), Job.JobStatus.CANCELLED, 0, 0, 0, 10);

        assertThat(progressService.compensateItemFailure(job.getId(), "NEW_DEF", 0, 3)).isFalse();

        assertThat(jobItemRepository.findByJobIdAndDefCode(job.getId(), "NEW_DEF")).isEmpty();
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

    /** 生产里摘要写发生在 PrecheckJobRunner.run 的事务内；直测时补同样的外围事务。 */
    private void writeSummaryInTransaction(Long jobId) {
        new TransactionTemplate(this.transactionManager)
                .executeWithoutResult(status -> precheckJobRunner.writeFingerprintSummary(jobId, summary()));
    }

    private Map<String, Map<String, Map<String, Object>>> summary() {
        return Map.of("SYS_PARAM", Map.of("UPLOAD",
                Map.of("sha256", "abc123", "size", 3)));
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

    private void execute(String sql) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        } catch (Exception e) {
            throw new IllegalStateException("跨连接写入失败：" + sql, e);
        }
    }
}
