package com.example.configmgr.task.service;

import com.example.configmgr.common.ConflictException;
import com.example.configmgr.common.ResourceNotFoundException;
import com.example.configmgr.data.repo.ConfigStagingRowRepository;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.repo.JobItemRepository;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.job.repo.ValidationIssueRepository;
import com.example.configmgr.job.service.AsyncJobExecutor;
import com.example.configmgr.job.service.JobHeartbeatRegistry;
import com.example.configmgr.job.service.JobProgressService;
import com.example.configmgr.job.service.JobService;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.repo.TaskFileRepository;
import com.example.configmgr.task.repo.TaskItemRepository;
import com.example.configmgr.task.repo.TaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R1-13 / C1：删除闸门 = runner 是否仍活着，而不是 job 状态或心跳。
 *
 * <p>用一条**未提交**的五表 INSERT（另一条真实连接）复现 H2 的 FK 盲区：
 * insert 已发出 → delete 提交也看不到 → insert 后提交会成孤儿。本用例要求删除在 runner
 * 存活期间被 409 挡住，因此上述序列不会发生；runner 退出后才允许级联清理。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:r113-runner-liveness;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "app.job.demo-batch-delay-ms=0",
        "app.file.storage-path=./target/r113-liveness-test-files"
})
class TaskDeleteRunnerLivenessTest {

    @Autowired
    private TaskService taskService;
    @Autowired
    private JobService jobService;
    @Autowired
    private JobProgressService progressService;
    @Autowired
    private JobHeartbeatRegistry heartbeatRegistry;
    @Autowired
    private TaskRepository taskRepository;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private TaskItemRepository taskItemRepository;
    @Autowired
    private TaskFileRepository taskFileRepository;
    @Autowired
    private ConfigStagingRowRepository stagingRowRepository;
    @Autowired
    private JobItemRepository jobItemRepository;
    @Autowired
    private ValidationIssueRepository issueRepository;
    @Autowired
    private DataSource dataSource;

    @MockitoBean
    private AsyncJobExecutor asyncJobExecutor;

    @Test
    void defaultTestProfileUsesInMemoryRunnerStore() {
        assertThat(heartbeatRegistry.isRedisMode())
                .as("测试 classpath 的 application.properties 应默认 memory，避免 CI backend 依赖 Redis")
                .isFalse();
    }

    @Test
    void deleteBlockedWhileRunnerActiveThenAllowedAfterRunnerExits() throws Exception {
        long taskId = taskService.create(Task.TaskType.EXPORT, "R113-RUNNER-LIVENESS").getId();
        Job job = jobService.createAndStart(taskId, Job.JobType.EXPORT);
        assertThat(heartbeatRegistry.isRunnerActive(job.getId()))
                .as("作业事务提交后必须已登记 runner").isTrue();

        Job cancelled = jobRepository.findById(job.getId()).orElseThrow();
        cancelled.setStatus(Job.JobStatus.CANCELLED);
        jobRepository.saveAndFlush(cancelled);

        try (Connection runnerConnection = dataSource.getConnection()) {
            runnerConnection.setAutoCommit(false);
            insertUncommittedChildRows(runnerConnection, taskId, job.getId());

            assertThatThrownBy(() -> taskService.delete(taskId))
                    .as("runner 未退出时删除必须 409 收尾")
                    .isInstanceOf(ConflictException.class)
                    .satisfies(e -> {
                        assertThat(((ConflictException) e).getCode()).isEqualTo("JOB_ALREADY_RUNNING");
                        assertThat(e.getMessage()).contains("收尾").contains("稍后重试");
                    });
            assertThat(taskRepository.existsById(taskId)).as("被拒删期间任务仍在").isTrue();

            // runner 的 INSERT 在删除被挡住后才提交：此时任务仍存在，不会成为孤儿。
            runnerConnection.commit();
        }

        assertThat(taskItemRepository.findByTaskIdOrderBySortOrder(taskId)).hasSize(1);
        assertThat(taskFileRepository.findByTaskId(taskId)).hasSize(1);
        assertThat(stagingRowRepository.findByTaskId(taskId)).hasSize(1);
        assertThat(jobItemRepository.findByJobId(job.getId())).hasSize(1);
        assertThat(issueRepository.findByJobIdAndDefCode(job.getId(), "R113-LIVE-ISSUE")).hasSize(1);

        // 模拟 runner 的 finish：它会清心跳，但不结束 runner 生命周期；
        // 删除仍必须被挡住（看门狗清心跳同理，不能把卡死当可删）。
        assertThat(progressService.finish(job.getId(), Job.JobStatus.CANCELLED, 0, 0, 0, 0)).isFalse();
        assertThat(heartbeatRegistry.isRunnerActive(job.getId()))
                .as("finish 清心跳不等于 runner 已退出").isTrue();
        assertThatThrownBy(() -> taskService.delete(taskId))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(e.getMessage()).contains("收尾"));

        // AsyncJobExecutor 的 finally 等价动作：runner 真正退出后才允许级联。
        heartbeatRegistry.endRunner(job.getId());
        heartbeatRegistry.clear(job.getId());
        taskService.delete(taskId);

        assertThat(taskRepository.existsById(taskId)).isFalse();
        assertThat(jobRepository.findByTaskId(taskId)).isEmpty();
        assertThat(taskItemRepository.findByTaskIdOrderBySortOrder(taskId)).isEmpty();
        assertThat(taskFileRepository.findByTaskId(taskId)).isEmpty();
        assertThat(stagingRowRepository.findByTaskId(taskId)).isEmpty();
        assertThat(jobItemRepository.findByJobId(job.getId())).isEmpty();
        assertThat(issueRepository.findByJobIdAndDefCode(job.getId(), "R113-LIVE-ISSUE")).isEmpty();
    }

    @Test
    void cancelMissingTaskReturns404NotJobFinal() {
        Job orphan = new Job();
        orphan.setTaskId(999_999L);
        orphan.setJobType(Job.JobType.EXPORT);
        orphan.setStatus(Job.JobStatus.RUNNING);
        Job saved = jobRepository.saveAndFlush(orphan);

        assertThatThrownBy(() -> jobService.cancel(saved.getId(), "r113-test"))
                .as("任务已不存在时 cancel 返回 404（不是 JOB_ALREADY_FINAL）")
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private void insertUncommittedChildRows(Connection connection, long taskId, long jobId) throws Exception {
        exec(connection, "INSERT INTO task_items(task_id, def_code) VALUES (?, ?)", taskId, "R113-LIVE-TASK");
        exec(connection, "INSERT INTO task_files(task_id, def_code, file_type, storage_path) VALUES (?, ?, ?, ?)",
                taskId, "R113-LIVE-FILE", "EXPORT", "./target/r113-live-file.xlsx");
        exec(connection, "INSERT INTO config_staging_rows(task_id, def_code, row_key, data_json) VALUES (?, ?, ?, ?)",
                taskId, "R113-LIVE-STAGING", "live-key", "{}");
        exec(connection, "INSERT INTO job_items(job_id, def_code) VALUES (?, ?)", jobId, "R113-LIVE-JOB");
        exec(connection, "INSERT INTO validation_issues(job_id, def_code, severity, message) VALUES (?, ?, ?, ?)",
                jobId, "R113-LIVE-ISSUE", "ERROR", "live issue");
    }

    private void exec(Connection connection, String sql, Object... params) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }
}
