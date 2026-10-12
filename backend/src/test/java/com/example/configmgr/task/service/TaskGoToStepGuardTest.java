package com.example.configmgr.task.service;

import com.example.configmgr.common.ConflictException;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.repo.TaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R-137（T4）{@link TaskService#goToStep} 双守卫的回归用例：
 * <ol>
 * <li><b>终态守卫</b>（口径与 JobService.assertTaskNotFinalized / 前端 isCurrentTaskReadOnly 同构）：
 *     CANCELLED 恒拒；COMPLETED 按类型核最终作业（IMPORT→PUBLISH 成功、EXPORT→EXPORT 成功）；FAILED 放行；</li>
 * <li><b>step 校验</b>：非空 + 格式（{@code ^[A-Z][A-Z0-9_]*$}）+ 集中常量白名单
 *     （按任务类型步骤序列 + 完成态 DONE；ExportJobRunner/PublishJobRunner 的 "EXPORT"/"PUBLISH" 落位内）。</li>
 * </ol>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:r137-gotostep;DB_CLOSE_DELAY=-1",
        "app.job.demo-batch-delay-ms=0",
        "app.file.storage-path=./target/r137-gotostep-files"
})
class TaskGoToStepGuardTest {

    @Autowired
    private TaskService taskService;
    @Autowired
    private TaskRepository taskRepository;
    @Autowired
    private JobRepository jobRepository;

    // ── 终态守卫 ──

    @Test
    void cancelledTaskBlocksGoToStep() {
        long taskId = task(Task.TaskType.EXPORT, Task.TaskStatus.CANCELLED);
        assertThatThrownBy(() -> taskService.goToStep(taskId, "EXPORT"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("TASK_FINALIZED");
    }

    @Test
    void completedExportWithFinalJobBlocksGoToStep() {
        long taskId = task(Task.TaskType.EXPORT, Task.TaskStatus.COMPLETED);
        jobRow(taskId, Job.JobType.EXPORT, Job.JobStatus.COMPLETED);
        assertThatThrownBy(() -> taskService.goToStep(taskId, "EXPORT"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("TASK_FINALIZED");
    }

    @Test
    void completedImportWithPublishJobBlocksGoToStep() {
        long taskId = task(Task.TaskType.IMPORT, Task.TaskStatus.COMPLETED);
        jobRow(taskId, Job.JobType.PUBLISH, Job.JobStatus.COMPLETED);
        assertThatThrownBy(() -> taskService.goToStep(taskId, "IMPORT"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("TASK_FINALIZED");
    }

    /** 预检查 0 错即置 COMPLETED 的导入任务（无 PUBLISH 成功作业）——跳步是主路径，放行。 */
    @Test
    void completedImportWithoutPublishJobAllowsGoToStep() {
        long taskId = task(Task.TaskType.IMPORT, Task.TaskStatus.COMPLETED);
        Task updated = taskService.goToStep(taskId, "IMPORT");
        assertThat(updated.getCurrentStep()).isEqualTo("IMPORT");
    }

    @Test
    void failedTaskAllowsGoToStep() {
        long taskId = task(Task.TaskType.EXPORT, Task.TaskStatus.FAILED);
        Task updated = taskService.goToStep(taskId, "QUERY_COND");
        assertThat(updated.getCurrentStep()).isEqualTo("QUERY_COND");
    }

    @Test
    void activeTaskAllowsGoToStep() {
        long taskId = task(Task.TaskType.EXPORT, Task.TaskStatus.ACTIVE);
        Task updated = taskService.goToStep(taskId, "QUERY_COND");
        assertThat(updated.getCurrentStep()).isEqualTo("QUERY_COND");
    }

    // ── step 校验（非空 + 格式 + 白名单）──

    @Test
    void blankStepRejected() {
        long taskId = task(Task.TaskType.EXPORT, Task.TaskStatus.ACTIVE);
        assertThatThrownBy(() -> taskService.goToStep(taskId, "  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> taskService.goToStep(taskId, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformedStepRejected() {
        long taskId = task(Task.TaskType.EXPORT, Task.TaskStatus.ACTIVE);
        assertThatThrownBy(() -> taskService.goToStep(taskId, "HACK<script>"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> taskService.goToStep(taskId, "lowercase"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> taskService.goToStep(taskId, "A"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stepOutsideTaskTypeWhitelistRejected() {
        long exportId = task(Task.TaskType.EXPORT, Task.TaskStatus.ACTIVE);
        long importId = task(Task.TaskType.IMPORT, Task.TaskStatus.ACTIVE);
        // EXPORT 白名单不含 UPLOAD/PUBLISH；IMPORT 白名单不含 QUERY_COND
        assertThatThrownBy(() -> taskService.goToStep(exportId, "UPLOAD"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> taskService.goToStep(importId, "QUERY_COND"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void whitelistedStepsAccepted() {
        long exportId = task(Task.TaskType.EXPORT, Task.TaskStatus.ACTIVE);
        long importId = task(Task.TaskType.IMPORT, Task.TaskStatus.ACTIVE);
        assertThat(taskService.goToStep(exportId, "SELECT_DEFS").getCurrentStep()).isEqualTo("SELECT_DEFS");
        assertThat(taskService.goToStep(exportId, "QUERY_COND").getCurrentStep()).isEqualTo("QUERY_COND");
        assertThat(taskService.goToStep(exportId, "EXPORT").getCurrentStep()).isEqualTo("EXPORT");
        assertThat(taskService.goToStep(exportId, "DONE").getCurrentStep()).isEqualTo("DONE");
        assertThat(taskService.goToStep(importId, "UPLOAD").getCurrentStep()).isEqualTo("UPLOAD");
        assertThat(taskService.goToStep(importId, "PRECHECK").getCurrentStep()).isEqualTo("PRECHECK");
        assertThat(taskService.goToStep(importId, "IMPORT").getCurrentStep()).isEqualTo("IMPORT");
        assertThat(taskService.goToStep(importId, "PUBLISH").getCurrentStep()).isEqualTo("PUBLISH");
        assertThat(taskService.goToStep(importId, "DONE").getCurrentStep()).isEqualTo("DONE");
    }

    // ───────────────────────── helpers ─────────────────────────

    private long task(Task.TaskType type, Task.TaskStatus status) {
        Task task = taskService.create(type, "R137-goToStep");
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
