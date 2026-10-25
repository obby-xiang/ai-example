package com.example.configmgr.job.service;

import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.entity.ValidationIssue;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.job.repo.ValidationIssueRepository;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.repo.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BZ-01（S1）：<b>零条目作业不得报 COMPLETED</b>。
 *
 * <h2>缺陷（修复前实测）</h2>
 * 四个执行器都以 {@code for (item : taskItems)} 为唯一工作循环，任务没有任何配置项时
 * 循环体一次也不跑，于是 {@code errorCount/totalErrors} 恒为 0 ⇒ 终态判据
 * {@code errors > 0 ? FAILED : COMPLETED} 落到 <b>COMPLETED</b>：
 * <ul>
 * <li>EXPORT：{@code terminalWriter.complete(..., COMPLETED, successStep="EXPORT")} 还会把
 *     任务推进到 COMPLETED/EXPORT —— {@code JobService#finalizedConflict} 随后对任何新导出作业
 *     恒抛 409 {@code TASK_FINALIZED}，任务被"零数据的成功"永久锁死；</li>
 * <li>PRECHECK：COMPLETED 且 {@code errorCount==0} 恰好满足 {@code precheckConflict} 的"预检查已通过"，
 *     等于把"一份数据都没校验过"当成放行凭证；</li>
 * <li>IMPORT / PUBLISH：零暂存、零发布也报成功（FA3 的 FE-49 入口：零暂存仍可发布）。</li>
 * </ul>
 *
 * <h2>判据（本类）</h2>
 * 真实 H2 + 真实 Bean，任务零条目，直接驱动四个执行器（同步，不走线程池）：
 * 作业必须到 <b>FAILED</b>（不是 COMPLETED）、带作业级 ERROR issue，且任务不得被写成 COMPLETED。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:zero-item-jobs;DB_CLOSE_DELAY=-1",
        // CI 无 Redis：钉到不可达端口（同 JobWatchdogItemConvergenceTest 口径）
        "spring.data.redis.port=1",
        "app.job.runner-active-store=memory",
        "app.task.sse.cross-instance-store=memory",
        // 看门狗不参与本类判据（终态必须由执行器自己写，不得靠 30min 兜底）
        "app.job.watchdog.scan-period=1h",
        "app.job.demo-batch-delay-ms=0",
        "app.file.storage-path=./target/zero-item-files"
})
class ZeroItemJobTerminalTest {

    @Autowired
    private ExportJobRunner exportJobRunner;

    @Autowired
    private ImportJobRunner importJobRunner;

    @Autowired
    private PrecheckJobRunner precheckJobRunner;

    @Autowired
    private PublishJobRunner publishJobRunner;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private ValidationIssueRepository issueRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long taskId;

    @BeforeEach
    void cleanSlate() {
        this.jdbcTemplate.update("DELETE FROM job_items");
        this.jdbcTemplate.update("DELETE FROM validation_issues");
        this.jdbcTemplate.update("DELETE FROM jobs");
        this.jdbcTemplate.update("DELETE FROM task_files");
        // 零条目任务：一个配置项都没勾选（TASK_FINALIZED 锁死面与 PRE-08 同源）
        Task task = new Task();
        task.setType(Task.TaskType.EXPORT);
        task.setTitle("零条目任务");
        task.setCurrentStep("SELECT_DEFS");
        this.taskId = this.taskRepository.save(task).getId();
    }

    @Test
    void exportJobWithoutTaskItemsMustNotComplete() {
        Job job = pendingJob(Job.JobType.EXPORT);

        this.exportJobRunner.run(job);

        assertZeroItemFailure(job.getId());
    }

    @Test
    void importJobWithoutTaskItemsMustNotComplete() {
        Job job = pendingJob(Job.JobType.IMPORT);

        this.importJobRunner.run(job);

        assertZeroItemFailure(job.getId());
    }

    @Test
    void precheckJobWithoutTaskItemsMustNotComplete() {
        Job job = pendingJob(Job.JobType.PRECHECK);

        this.precheckJobRunner.run(job);

        assertZeroItemFailure(job.getId());
    }

    @Test
    void publishJobWithoutTaskItemsMustNotComplete() {
        Job job = pendingJob(Job.JobType.PUBLISH);

        this.publishJobRunner.run(job);

        assertZeroItemFailure(job.getId());
    }

    // ── 辅助 ────────────────────────────────────────────────────────────────

    private Job pendingJob(Job.JobType jobType) {
        Job job = new Job();
        job.setTaskId(this.taskId);
        job.setJobType(jobType);
        job.setStatus(Job.JobStatus.PENDING);
        return this.jobRepository.save(job);
    }

    private void assertZeroItemFailure(Long jobId) {
        Job finished = this.jobRepository.findById(jobId).orElseThrow();
        assertThat(finished.getStatus())
                .as("零条目作业不得报 COMPLETED（零数据不是成功）")
                .isEqualTo(Job.JobStatus.FAILED);
        assertThat(finished.getErrorCount())
                .as("零条目失败必须带可见的错误计数")
                .isPositive();
        assertThat(finished.getFinishedAt()).as("必须有界地到达终态").isNotNull();

        var issues = this.issueRepository.findByJobId(jobId, PageRequest.of(0, 20)).getContent();
        assertThat(issues)
                .as("零条目失败必须留下作业级 issue（否则用户只看到一句 FAILED）")
                .anyMatch(issue -> issue.getSeverity() == ValidationIssue.Severity.ERROR
                        && "-".equals(issue.getDefCode())
                        && issue.getMessage() != null
                        && issue.getMessage().contains("配置项"));

        Task task = this.taskRepository.findById(this.taskId).orElseThrow();
        assertThat(task.getStatus())
                .as("零数据作业不得把任务写成 COMPLETED（否则新作业被 TASK_FINALIZED 永久拒绝）")
                .isNotEqualTo(Task.TaskStatus.COMPLETED);
    }
}
