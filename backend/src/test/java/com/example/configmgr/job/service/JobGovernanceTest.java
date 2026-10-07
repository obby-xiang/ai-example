package com.example.configmgr.job.service;

import com.example.configmgr.common.ConflictException;
import com.example.configmgr.data.entity.ConfigStagingRow;
import com.example.configmgr.data.repo.ConfigStagingRowRepository;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.repo.TaskRepository;
import com.example.configmgr.task.service.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * S4.3 §2 作业治理用例（修正②④⑤⑥）。
 *
 * <p>落在真实 H2（内存库）上：僵尸恢复联动任务态且幂等、取消已终态作业 409 JOB_ALREADY_FINAL、
 * 进度快照对"另一个连接"可见（⑤ 独立事务的机制证明）。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:s43b-job-governance;DB_CLOSE_DELAY=-1",
        "app.job.demo-batch-delay-ms=0"
})
class JobGovernanceTest {

    @Autowired
    private StaleJobRecoveryRunner staleJobRecoveryRunner;
    @Autowired
    private JobService jobService;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private TaskRepository taskRepository;
    @Autowired
    private TaskService taskService;
    @Autowired
    private ConfigStagingRowRepository stagingRowRepository;
    @Autowired
    private JobProgressService progressService;
    @Autowired
    private JobTerminalWriter terminalWriter;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private DataSource dataSource;

    // ── 修正①②：PENDING 与 RUNNING 两类残留都收；作业 FAILED 且任务态联动为 FAILED ──

    @Test
    void zombieRecoveryCoversPendingAndRunningAndSyncsTaskState() {
        Task pendingTask = taskService.create(Task.TaskType.IMPORT, "S43B-zombie-pending");
        Job pendingJob = jobRepository.save(job(pendingTask.getId(), Job.JobStatus.PENDING));

        Task runningTask = taskService.create(Task.TaskType.IMPORT, "S43B-zombie-running");
        Job runningJob = jobRepository.save(job(runningTask.getId(), Job.JobStatus.RUNNING));

        staleJobRecoveryRunner.run(null);

        assertThat(jobRepository.findById(pendingJob.getId()).orElseThrow().getStatus())
                .as("PENDING 残留（基座实测崩溃形态）也必须被恢复").isEqualTo(Job.JobStatus.FAILED);
        assertThat(jobRepository.findById(runningJob.getId()).orElseThrow().getStatus())
                .as("RUNNING 残留同样恢复为 FAILED").isEqualTo(Job.JobStatus.FAILED);
        assertThat(jobRepository.findById(pendingJob.getId()).orElseThrow().getFinishedAt())
                .as("僵尸作业补记 finishedAt").isNotNull();

        assertThat(taskRepository.findById(pendingTask.getId()).orElseThrow().getStatus())
                .as("② 僵尸恢复联动回写任务状态").isEqualTo(Task.TaskStatus.FAILED);
        assertThat(taskRepository.findById(runningTask.getId()).orElseThrow().getStatus())
                .isEqualTo(Task.TaskStatus.FAILED);
    }

    @Test
    void zombieRecoveryIsIdempotentOnRepeatedRestarts() {
        Task task = taskService.create(Task.TaskType.IMPORT, "S43B-zombie-idem");
        Job job = jobRepository.save(job(task.getId(), Job.JobStatus.RUNNING));

        staleJobRecoveryRunner.run(null);
        Long versionAfterFirst = taskRepository.findById(task.getId()).orElseThrow().getVersion();

        staleJobRecoveryRunner.run(null);

        assertThat(taskRepository.findById(task.getId()).orElseThrow().getVersion())
                .as("重复重启不重复处理：任务行零写入（@Version 不变）").isEqualTo(versionAfterFirst);
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(Job.JobStatus.FAILED);
    }

    // ── 修正④：取消已终态作业 → ConflictException(JOB_ALREADY_FINAL)（HTTP 层 409，见证据 V3）──

    @Test
    void cancelOnFinalJobIsRejectedWithJobAlreadyFinalCode() {
        Task task = taskService.create(Task.TaskType.EXPORT, "S43B-cancel-final");
        Job finished = jobRepository.save(job(task.getId(), Job.JobStatus.COMPLETED));

        ConflictException conflict = catchThrowableOfType(
                () -> jobService.cancel(finished.getId(), "test"), ConflictException.class);

        assertThat(conflict).isNotNull();
        assertThat(conflict.getCode()).isEqualTo("JOB_ALREADY_FINAL");
        assertThat(jobRepository.findById(finished.getId()).orElseThrow().getStatus())
                .as("已终态作业不得被改成 CANCELLED").isEqualTo(Job.JobStatus.COMPLETED);
    }

    // ── 修正⑤：作业行进度快照写在独立事务 —— 外层（作业数据面）事务未提交时，
    //           另一个连接就能读到 RUNNING/processed/total，而数据面行仍不可见 ──

    @Test
    void progressSnapshotIsVisibleToAnotherConnectionWhileOuterJobTransactionIsOpen() throws Exception {
        Task task = taskService.create(Task.TaskType.IMPORT, "S43B-snapshot");
        Job job = jobRepository.save(job(task.getId(), Job.JobStatus.PENDING)); // 已提交的 PENDING 作业

        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        outer.executeWithoutResult(status -> {
            // 数据面：留在外层事务里（未提交）
            ConfigStagingRow sr = new ConfigStagingRow();
            sr.setTaskId(task.getId());
            sr.setDefCode("CURRENCY");
            sr.setRowKey("S43B|staged");
            sr.setDataJson("{\"code\":\"S43B\"}");
            stagingRowRepository.save(sr);

            // ⑤ 快照：独立事务写入
            progressService.markRunning(job.getId(), 0);
            progressService.snapshot(job.getId(), 42, 100);

            try (Connection c = dataSource.getConnection();
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT STATUS, PROGRESS, TOTAL FROM JOBS WHERE ID = " + job.getId())) {
                assertThat(rs.next()).as("作业行对另一个连接可见").isTrue();
                assertThat(rs.getString("STATUS")).as("不再停在 PENDING（W2：快照恒 PENDING）")
                        .isEqualTo("RUNNING");
                assertThat(rs.getInt("PROGRESS")).as("processed 实时可见、非 0").isEqualTo(42);
                assertThat(rs.getInt("TOTAL")).isEqualTo(100);
            } catch (Exception e) {
                throw new IllegalStateException("快照可见性检查失败: " + e.getMessage(), e);
            }

            try (Connection c = dataSource.getConnection();
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT COUNT(*) FROM CONFIG_STAGING_ROWS WHERE TASK_ID = " + task.getId())) {
                rs.next();
                assertThat(rs.getInt(1)).as("数据面行仍随外层事务提交（对照面）").isZero();
            } catch (Exception e) {
                throw new IllegalStateException("数据面可见性对照检查失败: " + e.getMessage(), e);
            }
        });
    }

    // ───────────────────────── helpers ─────────────────────────

    private Job job(Long taskId, Job.JobStatus status) {
        Job job = new Job();
        job.setTaskId(taskId);
        job.setJobType(Job.JobType.IMPORT);
        job.setStatus(status);
        return job;
    }

    /**
     * 回归点：终态写入被登记到 afterCommit —— 若 It 用 REQUIRED 打开业务写入，会静默加入
     * "已提交但资源仍绑定"的旧事务而不落库（首轮实测踩到：作业行写了、任务行没写）。
     */
    @Test
    void terminalWriteRegisteredInAfterCommitStillLands() {
        Task task = taskService.create(Task.TaskType.IMPORT, "S43B-terminal-after-commit");
        Job job = jobRepository.save(job(task.getId(), Job.JobStatus.PENDING));

        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        outer.executeWithoutResult(status -> {
            job.setStatus(Job.JobStatus.RUNNING);
            jobRepository.save(job); // 执行器事务内的作业行写入
            terminalWriter.complete(job, Job.JobStatus.FAILED, 2, 0, 3, 9, Map.of("jobId", job.getId()));
        });

        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus())
                .as("afterCommit 登记的终态必须真的落库").isEqualTo(Job.JobStatus.FAILED);
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getProgress()).isEqualTo(3);
        assertThat(taskRepository.findById(task.getId()).orElseThrow().getStatus())
                .as("⑥ 作业失败联动：任务态必须一并落库（REQUIRED 加入已完成事务会静默丢失）")
                .isEqualTo(Task.TaskStatus.FAILED);
    }
}
