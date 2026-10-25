package com.example.configmgr.job.service;

import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.entity.JobItem;
import com.example.configmgr.job.repo.JobItemRepository;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.repo.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 看门狗判死/判超时后，<b>{@code job_items} 必须一起收敛到终态</b>
 * （多实例验收用例 {@code job-kill-a} 的产品侧根因）。
 *
 * <h2>缺陷</h2>
 * SIGKILL 打断执行器后，条目行由 {@code JobProgressService#startItem} 以 {@code RUNNING} 独立提交
 * （{@code REQUIRES_NEW}，全进程只剩数据库里的这一行）。看门狗把<b>作业</b>收敛成 FAILED 后，
 * 没有任何路径再动这条条目：
 * <ul>
 * <li>{@link StaleJobRecoveryRunner} 会归零条目，但它只处理"仍是 PENDING/RUNNING 的作业"——
 *     作业已被看门狗判 FAILED，重启时它压根不看（实测三实例 run：job #5 FAILED 而
 *     {@code job_items} 残留 {@code status=RUNNING, processed=1}）；</li>
 * <li>执行器已死，不会再有 {@code finishItem}；{@link JobService#cancel} 走的是 runner 自觉写条目。</li>
 * </ul>
 * 结果：作业详情与任务中心长期显示"1/5 进行中"，与 FAILED 终态自相矛盾。
 *
 * <p>本用例是<b>真实 H2 + 真实 Bean</b>（不 mock 仓库/终态写入）：建 PENDING/RUNNING 作业与
 * RUNNING 条目，分别用"绝对超时"和"心跳失联"两条真实判据驱动 {@link JobWatchdog#scan()}，
 * 断言作业与条目一起到终态，且任务态联动成立。
 *
 * <p>另守住收敛之后的<b>最后一道入口</b>（评审 I-1）：{@code startItem} 若在终态收敛之后才开工，
 * 会插入一条没人能再收的 RUNNING 条目（它的 {@code finishItem}/{@code updateItem} 因父作业终态恒为
 * 0 行）。两条用例分别覆盖"终态已提交后的迟到开工"和"与终态写并发的开工（真实行锁串行化）"。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:job-watchdog-items;DB_CLOSE_DELAY=-1",
        // CI 无 Redis；钉到不可达端口 ⇒ 与 CI 同环境，且不会把 job:cancel:* 写进开发机的 Redis
        // （作业 id 是每库自增，同名键会污染后续用例）。
        "spring.data.redis.port=1",
        "app.job.runner-active-store=memory",
        "app.job.heartbeat-interval=1s",
        "app.job.watchdog.dead-multiplier=1",
        "app.job.watchdog.scan-period=1h",
        "app.job.max-execution-time=10s",
        "app.task.sse.cross-instance-store=memory",
        "app.ai.kb.enabled=false",
        "app.job.demo-batch-delay-ms=0"
})
class JobWatchdogItemConvergenceTest {

    private static final int ITEM_PROCESSED = 1;

    private static final int ITEM_TOTAL = 5;

    @Autowired
    private JobWatchdog watchdog;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JobItemRepository jobItemRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private JobHeartbeatRegistry heartbeatRegistry;

    @Autowired
    private JobProgressService progressService;

    @Autowired
    private JobService jobService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long taskId;

    @BeforeEach
    void cleanSlate() {
        this.jdbcTemplate.update("DELETE FROM job_items");
        this.jdbcTemplate.update("DELETE FROM validation_issues");
        this.jdbcTemplate.update("DELETE FROM jobs");
        Task task = new Task();
        task.setType(Task.TaskType.EXPORT);
        task.setTitle("看门狗条目收敛");
        task.setCurrentStep("EXPORT");
        this.taskId = this.taskRepository.save(task).getId();
    }

    @Test
    void timeoutJudgementConvergesOpenItemsToFailed() {
        Job job = persistJob(Job.JobStatus.PENDING, null);
        JobItem item = persistOpenItem(job.getId());
        backdateCreatedAt(job.getId(), 11);

        this.watchdog.scan();

        assertThat(this.watchdog.metrics().get("judgedTimeoutTotal")).isEqualTo(1L);
        assertJobAndItemsTerminal(job.getId(), item.getId());
    }

    @Test
    void heartbeatDeadJudgementConvergesOpenItemsToFailed() throws Exception {
        Job job = persistJob(Job.JobStatus.RUNNING, LocalDateTime.now());
        JobItem item = persistOpenItem(job.getId());
        // 唯一活着的 runner 已死：只剩"曾经的本地心跳"，超过判死窗（heartbeat 1s × multiplier 1）即 STALE
        this.heartbeatRegistry.beat(job.getId());
        Thread.sleep(1_300L);

        this.watchdog.scan();

        assertThat(this.watchdog.metrics().get("judgedDeadTotal")).isEqualTo(1L);
        assertJobAndItemsTerminal(job.getId(), item.getId());
    }

    /**
     * 收敛的边界（取消路径）：未收终态的条目记 <b>CANCELLED</b>，已 COMPLETED 的条目一律不动。
     *
     * <p>取消的收敛点放在 {@code JobService#cancel} 自己的 REQUIRES_NEW 事务里（而不是终态写入器）：
     * 取消语义下"条目记 CANCELLED"由取消方负责（{@code finishItem}/{@code markItemFinal} 的 CANCELLED
     * 分支有专门用例守着），事务原子提交 ⇒ 不会出现"作业 CANCELLED、条目还 RUNNING"的中间态。
     */
    @Test
    void cancelConvergesOpenItemsToCancelledAndLeavesSuccessfulItemsAlone() {
        Job job = persistJob(Job.JobStatus.RUNNING, LocalDateTime.now());
        JobItem open = persistOpenItem(job.getId());
        JobItem successful = persistItem(job.getId(), "DOC_TYPE", "COMPLETED", ITEM_TOTAL, ITEM_TOTAL);

        this.jobService.cancel(job.getId(), "test-cancel");

        assertThat(this.jobRepository.findById(job.getId()).orElseThrow().getStatus())
                .as("取消必须落成 CANCELLED").isEqualTo(Job.JobStatus.CANCELLED);
        assertThat(this.jobItemRepository.findById(open.getId()).orElseThrow().getStatus())
                .as("取消作业的在途条目收敛为 CANCELLED（不得残留 RUNNING，也不得记 FAILED）")
                .isEqualTo("CANCELLED");
        JobItem untouched = this.jobItemRepository.findById(successful.getId()).orElseThrow();
        assertThat(untouched.getStatus()).as("已 COMPLETED 的条目不得被收敛覆盖").isEqualTo("COMPLETED");
        assertThat(untouched.getProcessed()).isEqualTo(ITEM_TOTAL);
    }


    // ── 评审 I-1：收敛之后的"迟到开工"不得再插入永久 RUNNING 条目 ──────────────

    /**
     * 确定性复现（评审 I-1 场景 1/2）：终态写 + 收敛<b>已提交</b>，随后 runner 才走到下一项的
     * {@code startItem}。修复前它无条件插入 RUNNING，而该条目的终态写（{@code finishItem}）因父作业
     * 已终态恒为 0 行 ⇒ 永久 RUNNING。
     *
     * <p>同时钉住"在途作业的开工不受影响"（不能把守卫做成"一律拒绝"）。
     */
    @Test
    void lateStartAfterCommittedTerminalIsRejectedAndLeavesNoOpenItem() {
        Job running = persistJob(Job.JobStatus.RUNNING, LocalDateTime.now());
        Long inFlightItemId = this.progressService.startItem(running.getId(), "CURRENCY");
        assertThat(inFlightItemId).as("在途作业的开工必须照常成功").isNotNull();
        assertThat(this.jobItemRepository.findById(inFlightItemId).orElseThrow().getStatus())
                .isEqualTo("RUNNING");
        assertThat(this.progressService.finish(running.getId(), Job.JobStatus.FAILED, 1, 0, ITEM_PROCESSED, ITEM_TOTAL))
                .as("终态必须实际落写").isTrue();
        assertThat(this.jobItemRepository.findById(inFlightItemId).orElseThrow().getStatus())
                .as("落写终态时已开工的条目必须被收敛").isEqualTo("FAILED");

        Long lateItemId = this.progressService.startItem(running.getId(), "DOC_TYPE");

        assertThat(lateItemId)
                .as("终态已提交后的迟到开工必须被拒绝（否则这条条目永久 RUNNING：其 finishItem 恒 0 行）")
                .isNull();
        assertThat(this.jobItemRepository.findByJobId(running.getId()))
                .as("不得为迟到开工插入任何新条目").hasSize(1);
        assertThat(openItemStatuses(running.getId()))
                .as("终态作业下不得残留 PENDING/RUNNING 条目").isEmpty();
    }

    /**
     * 真实并发（评审 I-1 要求的 terminal/start race，<b>两个真实连接</b>，不是 mock）：
     * 终态事务先拿住父作业行锁并保持 400ms，期间另一个线程调 {@code startItem}。
     *
     * <p>修复后：{@code startItem} 在同一把行锁上等待（≥ 200ms）→ 终态提交后读到 FAILED → 返回 null，
     * 不插条目。修复前：它不参与任何数据库序列化 ⇒ 立刻返回 id 并插入（CHECK-THEN-INSERT 的
     * 典型漏洞），因此本用例同时断言"被阻塞"和"返回 null"，不依赖调度顺序。
     */
    @Test
    void startItemSerializesWithTerminalWriteOnParentJobRow() throws Exception {
        Job job = persistJob(Job.JobStatus.RUNNING, LocalDateTime.now());
        CountDownLatch terminalHoldsRowLock = new CountDownLatch(1);
        AtomicReference<Long> lateItemId = new AtomicReference<>(-1L);
        AtomicLong starterElapsedMs = new AtomicLong(-1L);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread terminal = new Thread(() -> {
            try {
                TransactionTemplate tx = new TransactionTemplate(this.transactionManager);
                tx.executeWithoutResult(status -> {
                    this.jobRepository.findByIdForUpdate(job.getId()).orElseThrow();
                    terminalHoldsRowLock.countDown();
                    sleepQuietly(400L);
                    this.jobRepository.finishIfInFlight(job.getId(), Job.JobStatus.FAILED, 1, 0,
                            ITEM_PROCESSED, ITEM_TOTAL, LocalDateTime.now(),
                            List.of(Job.JobStatus.PENDING, Job.JobStatus.RUNNING));
                    this.jobItemRepository.convergeOpenItems(job.getId(), "FAILED");
                });
            }
            catch (Throwable t) {
                failure.set(t);
                terminalHoldsRowLock.countDown();
            }
        }, "terminal-writer");
        Thread starter = new Thread(() -> {
            try {
                if (!terminalHoldsRowLock.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("终态线程未能在 10s 内拿到父作业行锁");
                }
                long startedAt = System.nanoTime();
                lateItemId.set(this.progressService.startItem(job.getId(), "DOC_TYPE"));
                starterElapsedMs.set((System.nanoTime() - startedAt) / 1_000_000L);
            }
            catch (Throwable t) {
                failure.set(t);
            }
        }, "late-starter");

        terminal.start();
        starter.start();
        terminal.join(15_000L);
        starter.join(15_000L);
        assertThat(terminal.isAlive()).as("终态线程必须结束（不得与迟到开工互相死等）").isFalse();
        assertThat(starter.isAlive()).as("迟到开工必须结束").isFalse();
        assertThat(failure.get()).as("并发路径不得抛异常").isNull();

        assertThat(starterElapsedMs.get())
                .as("startItem 必须在父作业行锁上等待终态事务（实得 %sms）：不等待就是 CHECK-THEN-INSERT",
                        starterElapsedMs.get())
                .isGreaterThanOrEqualTo(200L);
        assertThat(lateItemId.get()).as("与终态写并发的开工必须被拒绝").isNull();
        assertThat(this.jobRepository.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(Job.JobStatus.FAILED);
        assertThat(openItemStatuses(job.getId())).as("并发后不得残留 PENDING/RUNNING 条目").isEmpty();
    }

    // ---------- helpers ----------

    private void assertJobAndItemsTerminal(Long jobId, Long itemId) {
        Job judged = this.jobRepository.findById(jobId).orElseThrow();
        assertThat(judged.getStatus()).as("作业终态").isEqualTo(Job.JobStatus.FAILED);
        assertThat(judged.getFinishedAt()).as("终态必须带 finishedAt").isNotNull();

        List<JobItem> items = this.jobItemRepository.findByJobId(jobId);
        assertThat(items).as("被中断作业的条目").hasSize(1);
        assertThat(items.get(0).getId()).isEqualTo(itemId);
        assertThat(items.get(0).getStatus())
                .as("条目必须与作业终态一致（残留 RUNNING 会让详情抽屉永远显示进行中）")
                .isEqualTo("FAILED");
        assertThat(items.get(0).getProcessed()).as("已处理数保留（取证用，不清零）").isEqualTo(ITEM_PROCESSED);

        assertThat(this.taskRepository.findById(this.taskId).orElseThrow().getStatus())
                .as("任务态联动仍成立").isEqualTo(Task.TaskStatus.FAILED);

        // 再扫一遍：终态作业不在扫描范围，不得产生二次动作
        this.watchdog.scan();
        assertThat(this.jobItemRepository.findByJobId(jobId).get(0).getStatus()).isEqualTo("FAILED");
    }

    private List<String> openItemStatuses(Long jobId) {
        return this.jobItemRepository.findByJobId(jobId).stream()
                .map(JobItem::getStatus)
                .filter(status -> "PENDING".equals(status) || "RUNNING".equals(status))
                .toList();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private Job persistJob(Job.JobStatus status, LocalDateTime startedAt) {
        Job job = new Job();
        job.setTaskId(this.taskId);
        job.setJobType(Job.JobType.EXPORT);
        job.setStatus(status);
        job.setStartedAt(startedAt);
        job.setProgress(ITEM_PROCESSED);
        job.setTotal(ITEM_TOTAL);
        return this.jobRepository.save(job);
    }

    private JobItem persistOpenItem(Long jobId) {
        return persistItem(jobId, "CURRENCY", "RUNNING", ITEM_PROCESSED, ITEM_TOTAL);
    }

    private JobItem persistItem(Long jobId, String defCode, String status, int processed, int total) {
        JobItem item = new JobItem();
        item.setJobId(jobId);
        item.setDefCode(defCode);
        item.setStatus(status);
        item.setProcessed(processed);
        item.setTotal(total);
        return this.jobItemRepository.save(item);
    }

    /** {@code created_at} 是 {@code @CreationTimestamp}，只能落库后改（模拟"已经跑了很久"）。 */
    private void backdateCreatedAt(Long jobId, int seconds) {
        this.jdbcTemplate.update("UPDATE jobs SET created_at = ? WHERE id = ?",
                LocalDateTime.now().minusSeconds(seconds), jobId);
    }

}
