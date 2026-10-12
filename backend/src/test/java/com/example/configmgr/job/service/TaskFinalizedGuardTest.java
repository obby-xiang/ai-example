package com.example.configmgr.job.service;

import com.example.configmgr.common.ConflictException;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.repo.TaskRepository;
import com.example.configmgr.task.service.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R-137（T1+T2）终态守卫单点下沉的回归用例：{@link JobService#createAndStart} 落库前
 * {@code assertTaskNotFinalized(taskId)}。
 *
 * <p><b>终态口径与前端 {@code isCurrentTaskReadOnly} 同构</b>（会审双向实证：
 * PrecheckJobRunner 预检查 0 错误即置任务 COMPLETED，一刀切会掐断 start_import 主路径）：
 * <ul>
 * <li>CANCELLED 恒拒；</li>
 * <li>COMPLETED 按任务类型核最终作业 —— IMPORT 须存在 PUBLISH 成功作业才算终态，
 *     EXPORT 须存在 EXPORT 成功作业；</li>
 * <li>FAILED 放行（僵尸恢复语义，预检查守卫另行兜底）。</li>
 * </ul>
 *
 * <p>回归锁（T8）：①FAILED 放行正向 ②预检查通过后 start_precheck 不被终态守卫拒
 * ③COMPLETED 但无最终作业的 IMPORT 任务（终态守卫面）start_import 不被 TASK_FINALIZED 拒。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:r137-finalized;DB_CLOSE_DELAY=-1",
        "app.job.demo-batch-delay-ms=0",
        "app.file.storage-path=./target/r137-test-files"
})
class TaskFinalizedGuardTest {

    @Autowired
    private JobService jobService;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private TaskService taskService;
    @Autowired
    private TaskRepository taskRepository;

    // ── CANCELLED 恒拒（四类型全覆盖；PRECHECK/PUBLISH 造好前置防先撞其它守卫）──

    @Test
    void cancelledTaskBlocksAllFourJobTypes() {
        long exportId = task(Task.TaskType.EXPORT, "R137-取消-导出", Task.TaskStatus.CANCELLED);
        long importId = task(Task.TaskType.IMPORT, "R137-取消-导入", Task.TaskStatus.CANCELLED);
        // PRECHECK/PUBLISH 的前置（预检查通过）也备好：确保拒因是 TASK_FINALIZED 而非 PRECHECK_NOT_PASSED
        Job precheck = jobRow(importId, Job.JobType.PRECHECK, Job.JobStatus.COMPLETED);
        precheck.setErrorCount(0);
        jobRepository.save(precheck);

        assertThatThrownBy(() -> jobService.createAndStart(exportId, Job.JobType.EXPORT))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("TASK_FINALIZED");
        assertThatThrownBy(() -> jobService.createAndStart(exportId, Job.JobType.PRECHECK))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("TASK_FINALIZED");
        assertThatThrownBy(() -> jobService.createAndStart(importId, Job.JobType.PUBLISH))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("TASK_FINALIZED");
        long before = jobRepository.count();
        assertThatThrownBy(() -> jobService.createAndStart(importId, Job.JobType.IMPORT))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("TASK_FINALIZED");
        assertThat(jobRepository.count()).as("被拒请求不得留作业行").isEqualTo(before);
    }

    // ── COMPLETED 按类型核最终作业 ──

    @Test
    void completedExportTaskWithSuccessfulExportJobIsFinal() {
        long taskId = task(Task.TaskType.EXPORT, "R137-导出-终态", Task.TaskStatus.COMPLETED);
        jobRow(taskId, Job.JobType.EXPORT, Job.JobStatus.COMPLETED);

        assertThatThrownBy(() -> jobService.createAndStart(taskId, Job.JobType.EXPORT))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("TASK_FINALIZED");
    }

    @Test
    void completedImportTaskWithSuccessfulPublishJobIsFinal() {
        long taskId = task(Task.TaskType.IMPORT, "R137-导入-终态", Task.TaskStatus.COMPLETED);
        jobRow(taskId, Job.JobType.PUBLISH, Job.JobStatus.COMPLETED);
        Job precheck = jobRow(taskId, Job.JobType.PRECHECK, Job.JobStatus.COMPLETED);
        precheck.setErrorCount(0);
        jobRepository.save(precheck);

        assertThatThrownBy(() -> jobService.createAndStart(taskId, Job.JobType.PUBLISH))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("TASK_FINALIZED");
    }

    /**
     * 回归锁③（主路径锁）：COMPLETED 但无最终作业的 IMPORT 任务 —— start_import 不得被 TASK_FINALIZED 拒
     * （预检查 0 错即置 COMPLETED 是既有态机行为，随后导入是主路径）。
     * 本任务无条目/无文件：指纹守卫对空条目集不设障，作业应成功创建。
     */
    @Test
    void completedImportWithoutPublishJobNotRejectedAsFinalized() {
        long taskId = task(Task.TaskType.IMPORT, "R137-预检查后继续导入", Task.TaskStatus.COMPLETED);
        Job precheck = jobRow(taskId, Job.JobType.PRECHECK, Job.JobStatus.COMPLETED);
        precheck.setErrorCount(0);
        jobRepository.save(precheck);

        Job job = jobService.createAndStart(taskId, Job.JobType.IMPORT);
        assertThat(job.getStatus()).isEqualTo(Job.JobStatus.PENDING);
    }

    /** 回归锁①：FAILED 放行正向（僵尸恢复语义）。 */
    @Test
    void failedTaskStillAcceptsNewJob() {
        long taskId = task(Task.TaskType.EXPORT, "R137-失败重试", Task.TaskStatus.FAILED);

        Job job = jobService.createAndStart(taskId, Job.JobType.EXPORT);
        assertThat(job.getStatus()).isEqualTo(Job.JobStatus.PENDING);
    }

    /** 回归锁②：预检查通过（任务已 COMPLETED）后 start_precheck 重跑不被终态守卫拒绝。 */
    @Test
    void precheckPassedTaskStillAcceptsPrecheckRerun() {
        long taskId = task(Task.TaskType.IMPORT, "R137-预检查重跑", Task.TaskStatus.COMPLETED);
        Job precheck = jobRow(taskId, Job.JobType.PRECHECK, Job.JobStatus.COMPLETED);
        precheck.setErrorCount(0);
        jobRepository.save(precheck);

        Job rerun = jobService.createAndStart(taskId, Job.JobType.PRECHECK);
        assertThat(rerun.getStatus()).isEqualTo(Job.JobStatus.PENDING);
    }

    /** ACTIVE 任务不受本守卫影响（既有行为回归）。 */
    @Test
    void activeTaskUnaffected() {
        long taskId = task(Task.TaskType.EXPORT, "R137-活跃", Task.TaskStatus.ACTIVE);

        Job job = jobService.createAndStart(taskId, Job.JobType.EXPORT);
        assertThat(job.getStatus()).isEqualTo(Job.JobStatus.PENDING);
    }

    /** COMPLETED 的 EXPORT 任务但 EXPORT 作业未成功（FAILED）——不算终态，可重试。 */
    @Test
    void completedExportWithFailedExportJobIsNotFinal() {
        long taskId = task(Task.TaskType.EXPORT, "R137-完成后重试", Task.TaskStatus.COMPLETED);
        jobRow(taskId, Job.JobType.EXPORT, Job.JobStatus.FAILED);

        Job job = jobService.createAndStart(taskId, Job.JobType.EXPORT);
        assertThat(job.getStatus()).isEqualTo(Job.JobStatus.PENDING);
    }

    // ───────────────────────── helpers ─────────────────────────

    private long task(Task.TaskType type, String title, Task.TaskStatus status) {
        Task task = taskService.create(type, title);
        task.setStatus(status);
        taskRepository.save(task);
        return task.getId();
    }

    private Job jobRow(long taskId, Job.JobType jobType, Job.JobStatus status) {
        Job job = new Job();
        job.setTaskId(taskId);
        job.setJobType(jobType);
        job.setStatus(status);
        return jobRepository.save(job);
    }
}

