package com.example.configmgr.job.service;

import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskItem;
import com.example.configmgr.task.repo.TaskItemRepository;
import com.example.configmgr.task.repo.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BZ-02（S2）：<b>IMPORT / PRECHECK 在数据面事务回滚时必须自己收敛终态</b>。
 *
 * <h2>缺陷（修复前实测）</h2>
 * 两个执行器的 {@code run} 都是 {@code @Transactional}，异常路径调
 * {@link JobTerminalWriter#complete} —— 而它把"作业终态 + 任务态联动"登记在
 * {@code afterCommit}：只要这轮事务以<b>回滚</b>结局收场（提交期约束失败、
 * 事务被标 rollback-only 后的 {@code UnexpectedRollbackException}），终态写<b>一次都不会执行</b>。
 * 结果：作业停在 {@code RUNNING}（进度已由 {@code REQUIRES_NEW} 独立提交），
 * 既挡住同任务重跑（{@code JOB_ALREADY_RUNNING}），也只能等看门狗的 30min 绝对上限。
 * EXPORT 侧早有 {@code afterCompletion} 回滚收敛（{@code ExportJobRunner:88-100}），
 * IMPORT / PRECHECK 没有 —— 本类把这条差距钉成断言。
 *
 * <h2>确定性复现</h2>
 * 把执行器调用放进一个外层事务，执行完再 {@code setRollbackOnly}：这就是"整轮数据面被丢弃"
 * 的最小同义形态（执行器 join 外层事务 ⇒ 它的数据面写与终态登记一起回滚）。
 * 条目故意指向不存在的配置定义 —— 执行器会走完条目循环并带着错误返回，
 * 于是"回滚后仍有终态"这件事只可能来自回滚收敛，不来自正常路径。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:job-rollback-terminal;DB_CLOSE_DELAY=-1",
        "spring.data.redis.port=1",
        "app.job.runner-active-store=memory",
        "app.task.sse.cross-instance-store=memory",
        "app.job.watchdog.scan-period=1h",
        "app.job.demo-batch-delay-ms=0",
        "app.file.storage-path=./target/job-rollback-files"
})
class JobRollbackTerminalFallbackTest {

    @Autowired
    private ImportJobRunner importJobRunner;

    @Autowired
    private PrecheckJobRunner precheckJobRunner;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TaskItemRepository taskItemRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long taskId;

    @BeforeEach
    void cleanSlate() {
        this.jdbcTemplate.update("DELETE FROM job_items");
        this.jdbcTemplate.update("DELETE FROM validation_issues");
        this.jdbcTemplate.update("DELETE FROM jobs");
        this.jdbcTemplate.update("DELETE FROM task_files");
        this.jdbcTemplate.update("DELETE FROM task_items");
        Task task = new Task();
        task.setType(Task.TaskType.IMPORT);
        task.setTitle("回滚收敛任务");
        task.setCurrentStep("IMPORT");
        this.taskId = this.taskRepository.save(task).getId();
        TaskItem item = new TaskItem();
        item.setTaskId(this.taskId);
        item.setDefCode("NO_SUCH_DEF");
        item.setSortOrder(0);
        this.taskItemRepository.save(item);
    }

    @Test
    void importJobMustNotStayRunningWhenDataPlaneRollsBack() {
        Job job = pendingJob(Job.JobType.IMPORT);

        runInsideRolledBackTransaction(() -> this.importJobRunner.run(job));

        assertTerminalAfterRollback(job.getId());
    }

    @Test
    void precheckJobMustNotStayRunningWhenDataPlaneRollsBack() {
        Job job = pendingJob(Job.JobType.PRECHECK);

        runInsideRolledBackTransaction(() -> this.precheckJobRunner.run(job));

        assertTerminalAfterRollback(job.getId());
    }

    // ── 辅助 ────────────────────────────────────────────────────────────────

    private Job pendingJob(Job.JobType jobType) {
        Job job = new Job();
        job.setTaskId(this.taskId);
        job.setJobType(jobType);
        job.setStatus(Job.JobStatus.PENDING);
        return this.jobRepository.save(job);
    }

    private void runInsideRolledBackTransaction(Runnable body) {
        TransactionTemplate tx = new TransactionTemplate(this.transactionManager);
        tx.executeWithoutResult(status -> {
            body.run();
            status.setRollbackOnly();
        });
    }

    private void assertTerminalAfterRollback(Long jobId) {
        Job finished = this.jobRepository.findById(jobId).orElseThrow();
        assertThat(finished.getStatus())
                .as("数据面事务回滚后作业必须有界收敛（不得停在 RUNNING 挡重跑、等 30min 看门狗）")
                .isNotIn(Job.JobStatus.PENDING, Job.JobStatus.RUNNING);
        assertThat(finished.getStatus())
                .as("回滚结局只能是失败/取消，绝不能是 COMPLETED")
                .isIn(Job.JobStatus.FAILED, Job.JobStatus.CANCELLED);
        assertThat(finished.getFinishedAt()).as("终态必须带 finishedAt").isNotNull();
        List<String> openItems = this.jdbcTemplate.queryForList(
                "SELECT status FROM job_items WHERE job_id = ?", String.class, jobId);
        assertThat(openItems)
                .as("终态作业下不得残留 PENDING/RUNNING 条目（条目收敛与终态同事务）")
                .noneMatch(status -> "PENDING".equals(status) || "RUNNING".equals(status));
    }
}
