package com.example.configmgr.task.service;

import com.example.configmgr.common.ErrorCodes;
import com.example.configmgr.data.entity.ConfigStagingRow;
import com.example.configmgr.data.repo.ConfigStagingRowRepository;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.entity.JobItem;
import com.example.configmgr.job.entity.ValidationIssue;
import com.example.configmgr.job.repo.JobItemRepository;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.job.repo.ValidationIssueRepository;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskFile;
import com.example.configmgr.task.entity.TaskItem;
import com.example.configmgr.task.repo.TaskFileRepository;
import com.example.configmgr.task.repo.TaskItemRepository;
import com.example.configmgr.task.repo.TaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R1-13（S6 F04）回归：删除任务与在途作业互斥。
 *
 * <p>口径（fix round1 后）：任务存在 PENDING/RUNNING 作业 → 409 {@code JOB_ALREADY_RUNNING}
 * + 可读提示，<b>不级联</b>；终态本身不因状态阻删，但 CANCELLED/FAILED/COMPLETED 只要 runner 仍未退出
 * 也返回 409“正在收尾”（C1，见 {@code TaskDeleteRunnerLivenessTest}）。本类中的终态作业是手工 seed、
 * 没有活跃 runner，故可删，用于锁住“状态本身不阻删 + 五表级联”。REST 只是入口。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:r113-task-delete;DB_CLOSE_DELAY=-1",
        "app.file.storage-path=./target/r113-test-files",
        "app.job.demo-batch-delay-ms=0"
})
class TaskDeleteInFlightGuardTest {

    private static final String DEF_CODE = "R113-DEF";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TaskService taskService;
    @Autowired
    private TaskRepository taskRepository;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private JobItemRepository jobItemRepository;
    @Autowired
    private ValidationIssueRepository issueRepository;
    @Autowired
    private TaskItemRepository taskItemRepository;
    @Autowired
    private TaskFileRepository taskFileRepository;
    @Autowired
    private ConfigStagingRowRepository stagingRowRepository;

    @Test
    void pendingJobBlocksDeleteWithoutCascade() throws Exception {
        Fixture fixture = seed(Job.JobStatus.PENDING, "R113-PENDING");

        mockMvc.perform(delete("/api/tasks/{id}", fixture.taskId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("JOB_ALREADY_RUNNING"))
                .andExpect(jsonPath("$.message").value(allOf(
                        containsString("#" + fixture.taskId()),
                        containsString("#" + fixture.jobId()),
                        containsString("PENDING"),
                        containsString("取消"),
                        containsString("JOB_ALREADY_RUNNING"))));

        assertNothingDeleted(fixture);
    }

    @Test
    void runningJobBlocksDeleteWithoutCascade() throws Exception {
        Fixture fixture = seed(Job.JobStatus.RUNNING, "R113-RUNNING");

        mockMvc.perform(delete("/api/tasks/{id}", fixture.taskId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB_ALREADY_RUNNING"))
                .andExpect(jsonPath("$.message").value(allOf(
                        containsString("#" + fixture.jobId()),
                        containsString("RUNNING"))));

        assertNothingDeleted(fixture);
    }

    @ParameterizedTest
    @EnumSource(value = Job.JobStatus.class, names = {"COMPLETED", "FAILED", "CANCELLED"})
    void terminalJobDoesNotBlockDeleteAndCascades(Job.JobStatus status) throws Exception {
        Fixture fixture = seed(status, "R113-" + status);

        mockMvc.perform(delete("/api/tasks/{id}", fixture.taskId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(taskRepository.findById(fixture.taskId())).as("任务本体已删").isEmpty();
        assertThat(jobRepository.findByTaskId(fixture.taskId())).as("作业已级联删除").isEmpty();
        assertThat(jobItemRepository.findByJobId(fixture.jobId())).as("作业条目已级联删除").isEmpty();
        assertThat(issueRepository.findByJobIdAndDefCode(fixture.jobId(), DEF_CODE))
                .as("校验问题已级联删除").isEmpty();
        assertThat(taskItemRepository.findByTaskIdOrderBySortOrder(fixture.taskId()))
                .as("任务条目已级联删除").isEmpty();
        assertThat(taskFileRepository.findByTaskId(fixture.taskId()))
                .as("任务文件行已级联删除").isEmpty();
        assertThat(stagingRowRepository.findByTaskId(fixture.taskId()))
                .as("暂存行已级联删除").isEmpty();
        assertThat(Files.exists(fixture.physicalFile())).as("物理文件在提交后删除").isFalse();
    }

    /**
     * Important 2：CANCELLED 后 runner 迟到的暂存/文件/问题写入必须被数据库外键拒绝，不落孤儿。
     *
     * <p>模拟的是真实窗口：取消先提交（作业 CANCELLED）→ 删除任务成功 → runner 长事务随后
     * {@code saveAll} / {@code save}。V6 的 FK 让这些 INSERT 失败；本用例验证失败后三张表仍为空。
     */
    @Test
    void lateCancelledRunnerWritesAreRejectedWithoutOrphans() throws Exception {
        Fixture fixture = seed(Job.JobStatus.CANCELLED, "R113-CANCELLED-LATE-WRITE");
        long taskId = fixture.taskId();
        long jobId = fixture.jobId();

        mockMvc.perform(delete("/api/tasks/{id}", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        ConfigStagingRow lateStaging = new ConfigStagingRow();
        lateStaging.setTaskId(taskId);
        lateStaging.setDefCode("R113-LATE-STAGING");
        lateStaging.setRowKey("late-key");
        lateStaging.setDataJson("{}");
        assertThatThrownBy(() -> stagingRowRepository.saveAndFlush(lateStaging))
                .as("任务已删，迟到暂存写入必须被 FK 拒绝")
                .isInstanceOf(DataIntegrityViolationException.class);

        TaskFile lateFile = new TaskFile();
        lateFile.setTaskId(taskId);
        lateFile.setDefCode("R113-LATE-FILE");
        lateFile.setFileType("EXPORT");
        lateFile.setStoragePath("./target/r113-late-file.xlsx");
        lateFile.setFileName("late.xlsx");
        assertThatThrownBy(() -> taskFileRepository.saveAndFlush(lateFile))
                .as("任务已删，迟到 task_files 写入必须被 FK 拒绝")
                .isInstanceOf(DataIntegrityViolationException.class);

        ValidationIssue lateIssue = new ValidationIssue();
        lateIssue.setJobId(jobId);
        lateIssue.setDefCode("R113-LATE-ISSUE");
        lateIssue.setSeverity(ValidationIssue.Severity.ERROR);
        lateIssue.setMessage("late runner write");
        assertThatThrownBy(() -> issueRepository.saveAndFlush(lateIssue))
                .as("作业已删，迟到 validation_issues 写入必须被 FK 拒绝")
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(stagingRowRepository.findByTaskId(taskId)).as("迟写后无孤儿暂存行").isEmpty();
        assertThat(taskFileRepository.findByTaskId(taskId)).as("迟写后无孤儿 task_files").isEmpty();
        assertThat(issueRepository.findByJobIdAndDefCode(jobId, "R113-LATE-ISSUE"))
                .as("迟写后无孤儿 validation_issues").isEmpty();
    }

    /** HTTP 面英文：基座 messages.properties 存在时，en-US 应取 messages_en 的码级兜底文案。 */
    @Test
    void englishDeleteConflictUsesMessagesEn() throws Exception {
        Fixture fixture = seed(Job.JobStatus.RUNNING, "R113-EN-API");

        mockMvc.perform(delete("/api/tasks/{id}", fixture.taskId())
                        .header("Accept-Language", "en-US"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB_ALREADY_RUNNING"))
                .andExpect(jsonPath("$.message").value("The job is already running"));

        assertNothingDeleted(fixture);
    }

    /**
     * 中英文案资源核对：码与 {@code error.<CODE>} 键在 zh/en 两份 messages 里都成立。
     *
     * <p>HTTP 级英文（含基座 {@code messages.properties} 自动配置是否生效）由
     * {@link #englishDeleteConflictUsesMessagesEn()} 覆盖；这里只锁资源键本身。
     */
    @Test
    void errorCodeHasChineseAndEnglishMessages() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        source.setFallbackToSystemLocale(false);

        String key = "error." + ErrorCodes.JOB_ALREADY_RUNNING;
        assertThat(source.getMessage(key, null, Locale.SIMPLIFIED_CHINESE)).isEqualTo("作业已在运行");
        assertThat(source.getMessage(key, null, Locale.US)).isEqualTo("The job is already running");
    }

    // ───────────────────────── helpers ─────────────────────────

    private record Fixture(long taskId, long jobId, Path physicalFile) {
    }

    private Fixture seed(Job.JobStatus status, String title) throws Exception {
        long taskId = taskService.create(Task.TaskType.EXPORT, title).getId();

        Job job = new Job();
        job.setTaskId(taskId);
        job.setJobType(Job.JobType.EXPORT);
        job.setStatus(status);
        Job savedJob = jobRepository.save(job);

        JobItem jobItem = new JobItem();
        jobItem.setJobId(savedJob.getId());
        jobItem.setDefCode(DEF_CODE);
        jobItem.setStatus("RUNNING");
        jobItemRepository.save(jobItem);

        ValidationIssue issue = new ValidationIssue();
        issue.setJobId(savedJob.getId());
        issue.setDefCode(DEF_CODE);
        issue.setSeverity(ValidationIssue.Severity.ERROR);
        issue.setMessage("R1-13 seed issue");
        issueRepository.save(issue);

        TaskItem item = new TaskItem();
        item.setTaskId(taskId);
        item.setDefCode(DEF_CODE);
        item.setStatus("READY");
        taskItemRepository.save(item);

        Path physical = Files.createTempFile("r113-", ".xlsx");
        Files.writeString(physical, "artifact");
        TaskFile file = new TaskFile();
        file.setTaskId(taskId);
        file.setDefCode(DEF_CODE);
        file.setFileType("EXPORT");
        file.setStoragePath(physical.toString());
        file.setFileName("r113.xlsx");
        taskFileRepository.save(file);

        ConfigStagingRow row = new ConfigStagingRow();
        row.setTaskId(taskId);
        row.setDefCode(DEF_CODE);
        row.setRowKey("r113-key");
        row.setDataJson("{}");
        stagingRowRepository.save(row);

        return new Fixture(taskId, savedJob.getId(), physical);
    }

    private void assertNothingDeleted(Fixture fixture) throws Exception {
        try {
            assertThat(taskRepository.findById(fixture.taskId())).as("被拒删后任务仍在").isPresent();
            assertThat(jobRepository.findByTaskId(fixture.taskId()))
                    .as("被拒删后作业仍在").extracting(Job::getId).containsExactly(fixture.jobId());
            assertThat(jobItemRepository.findByJobId(fixture.jobId()))
                    .as("被拒删后作业条目仍在").hasSize(1);
            assertThat(issueRepository.findByJobIdAndDefCode(fixture.jobId(), DEF_CODE))
                    .as("被拒删后校验问题仍在").hasSize(1);
            assertThat(taskItemRepository.findByTaskIdOrderBySortOrder(fixture.taskId()))
                    .as("被拒删后任务条目仍在").hasSize(1);
            assertThat(taskFileRepository.findByTaskId(fixture.taskId()))
                    .as("被拒删后任务文件行仍在").hasSize(1);
            assertThat(stagingRowRepository.findByTaskId(fixture.taskId()))
                    .as("被拒删后暂存行仍在").hasSize(1);
            assertThat(Files.exists(fixture.physicalFile())).as("被拒删后物理文件仍在").isTrue();
        } finally {
            Files.deleteIfExists(fixture.physicalFile());
        }
    }
}
