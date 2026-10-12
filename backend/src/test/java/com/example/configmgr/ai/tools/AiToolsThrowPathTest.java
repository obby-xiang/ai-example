package com.example.configmgr.ai.tools;

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
 * R-137（T3+T6）AI 工具异常通道的回归用例。
 *
 * <p><b>T6 重抛面（恢复 ok=false）</b>：createTask 的 Exception 分支 + 四件 start_* 的
 * catch Exception——工具不再以字符串回填失败，改为抛出业务异常（中文文案保留于异常 message），
 * 由 {@code SpToolCallingManager.executeOnce} 的 catch 置 ok=false 呈给模型
 * （"工具执行失败：&lt;message&gt;"，框架既有通道）。
 *
 * <p><b>保留字符串回填 4 处</b>（会审一致反对一刀切）：getConfigDef / getWorkspaceState
 * （读路径 not-found）、checkJobStatus（读路径查询失败）、createTask 的 IAE（参数校验）。
 *
 * <p><b>T3 终态拒的 AI 通道形态</b>：终态任务上调 start_* ⇒ ConflictException("TASK_FINALIZED")
 * 透传（executeOnce 呈 ok=false + 文案），REST 侧由 GlobalExceptionHandler 转 409。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:r137-ai-tools-throw;DB_CLOSE_DELAY=-1",
        "app.job.demo-batch-delay-ms=0",
        "app.file.storage-path=./target/r137-ai-tools-throw-files"
})
class AiToolsThrowPathTest {

    @Autowired
    private AiTools aiTools;
    @Autowired
    private TaskService taskService;
    @Autowired
    private TaskRepository taskRepository;
    @Autowired
    private JobRepository jobRepository;

    // ── T6 重抛面：四件 start_* 在终态任务上抛 ConflictException（不再吞成字符串） ──

    @Test
    void startExportOnCancelledTaskThrowsInsteadOfString() {
        long taskId = cancelledExportTask();
        assertThatThrownBy(() -> aiTools.startExport(taskId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("TASK_FINALIZED");
    }

    @Test
    void startPrecheckOnCancelledTaskThrows() {
        long taskId = cancelledImportTask();
        assertThatThrownBy(() -> aiTools.startPrecheck(taskId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("TASK_FINALIZED");
    }

    @Test
    void startImportOnCancelledTaskThrows() {
        long taskId = cancelledImportTask();
        assertThatThrownBy(() -> aiTools.startImport(taskId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("TASK_FINALIZED");
    }

    @Test
    void startPublishOnFinalizedImportTaskThrows() {
        long taskId = finalizedImportTask();
        assertThatThrownBy(() -> aiTools.startPublish(taskId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("TASK_FINALIZED");
    }

    // ── T6 保留字符串回填 4 处（既有行为回归锁）──

    @Test
    void readPathNotFoundStillReturnsString() {
        // getConfigDef：不存在的配置定义 → 字符串提示（不抛）
        assertThat(aiTools.getConfigDef("NO_SUCH_DEF")).contains("未找到配置定义");
        // getWorkspaceState：不存在的任务 → 字符串提示（不抛）
        assertThat(aiTools.getWorkspaceState(999999L)).contains("不存在或无法访问");
    }

    @Test
    void createTaskIllegalArgumentStillReturnsString() {
        // createTask 的 IAE 参数校验分支：字符串回填（不抛）
        assertThat(aiTools.createTask("NOT_A_TYPE", null)).contains("任务类型不合法");
    }

    // ───────────────────────── helpers ─────────────────────────

    private long cancelledExportTask() {
        return taskWithStatus(Task.TaskType.EXPORT, Task.TaskStatus.CANCELLED);
    }

    private long cancelledImportTask() {
        return taskWithStatus(Task.TaskType.IMPORT, Task.TaskStatus.CANCELLED);
    }

    /** 导入终态 = COMPLETED + PUBLISH 成功作业（与前端 isCurrentTaskReadOnly 同构）。 */
    private long finalizedImportTask() {
        Task task = taskService.create(Task.TaskType.IMPORT, "R137-AI-导入-终态");
        task.setStatus(Task.TaskStatus.COMPLETED);
        taskRepository.save(task);
        Job publish = new Job();
        publish.setTaskId(task.getId());
        publish.setJobType(Job.JobType.PUBLISH);
        publish.setStatus(Job.JobStatus.COMPLETED);
        jobRepository.save(publish);
        // PUBLISH 的预检查前置也备好：确保拒因是 TASK_FINALIZED 而非 PRECHECK_NOT_PASSED
        Job precheck = new Job();
        precheck.setTaskId(task.getId());
        precheck.setJobType(Job.JobType.PRECHECK);
        precheck.setStatus(Job.JobStatus.COMPLETED);
        precheck.setErrorCount(0);
        jobRepository.save(precheck);
        return task.getId();
    }

    private long taskWithStatus(Task.TaskType type, Task.TaskStatus status) {
        Task task = taskService.create(type, "R137-AI-终态");
        task.setStatus(status);
        taskRepository.save(task);
        return task.getId();
    }
}
