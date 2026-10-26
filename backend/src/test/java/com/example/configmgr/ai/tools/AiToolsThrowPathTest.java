package com.example.configmgr.ai.tools;

import com.example.configmgr.common.ConflictException;
import com.example.configmgr.common.ResourceNotFoundException;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.repo.TaskRepository;
import com.example.configmgr.task.service.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R-137（T3+T6）AI 工具异常通道的回归用例。
 *
 * <p><b>T6 重抛面（恢复 ok=false）</b>：createTask 的 Exception 分支 + 四件 start_* 的
 * catch Exception——工具不再以字符串回填失败，改为抛出业务异常（中文文案保留于异常 message），
 * 由 {@code SpToolCallingManager.executeOnce} 的 catch 置 ok=false 呈给模型
 * （统一信封 {@code outcome=failed}，message="工具执行失败：&lt;message&gt;"）。
 *
 * <p><b>P0-1：读路径不再吞错</b>：getConfigDef / getWorkspaceState / checkJobStatus / getRowCount
 * 的"未找到"抛 {@link ResourceNotFoundException}（带 RESOURCE_NOT_FOUND 码），
 * createTask 的参数非法抛 {@code ConflictException(ILLEGAL_ARGUMENT)} —— 这些路径以前返回
 * "未找到…"/"任务类型不合法…"的字符串，模型与帧都读成 success。
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

    // ── P0-1：读路径"未找到"与参数非法都以带码异常浮出（不再吞成字符串） ──

    @Test
    void readPathNotFoundThrowsWithResourceNotFoundCode() {
        assertThatThrownBy(() -> aiTools.getConfigDef("NO_SUCH_DEF"))
                .isInstanceOfSatisfying(ResourceNotFoundException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ResourceNotFoundException.DEFAULT_CODE);
                    assertThat(ex.getMessage()).contains("不存在: NO_SUCH_DEF");
                });
        assertThatThrownBy(() -> aiTools.getWorkspaceState(999999L))
                .isInstanceOfSatisfying(ResourceNotFoundException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ResourceNotFoundException.DEFAULT_CODE);
                    assertThat(ex.getMessage()).contains("任务 不存在: 999999");
                });
        assertThatThrownBy(() -> aiTools.checkJobStatus(999999L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("不存在");
        assertThatThrownBy(() -> aiTools.getRowCount("NO_SUCH_DEF", null, null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void listConfigDefsIllegalLevelThrowsWithCode() {
        // M5：非法 level 不再由 enum valueOf 抛裸 IllegalArgumentException（码会退化成
        // TOOL_EXECUTION_FAILED）；与 createTask 同手法包一层带码的 ConflictException。
        assertThatThrownBy(() -> aiTools.listConfigDefs("NOT_A_LEVEL"))
                .isInstanceOfSatisfying(ConflictException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("ILLEGAL_ARGUMENT");
                    assertThat(ex.getMessage()).contains("配置定义层级不合法").contains("GLOBAL");
                });
    }

    @Test
    void createTaskIllegalArgumentThrowsWithCode() {
        assertThatThrownBy(() -> aiTools.createTask("NOT_A_TYPE", null, new ToolContext(Map.of("runId", "s5a-throw-1"))))
                .isInstanceOfSatisfying(ConflictException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("ILLEGAL_ARGUMENT");
                    assertThat(ex.getMessage()).contains("任务类型不合法");
                });
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
