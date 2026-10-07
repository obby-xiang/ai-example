package com.example.configmgr.job.service;

import com.example.configmgr.common.ConflictException;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.service.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M1 收尾守卫①（S5.a §5 待裁决 #1 / S5.b §6.3 裁决"采纳"）的回归用例：
 * <b>IMPORT / PUBLISH 前必须存在一次通过的 PRECHECK</b>，否则 409 {@code PRECHECK_NOT_PASSED}。
 *
 * <p>守卫落点在 {@link JobService#createAndStart}（REST {@code POST /api/tasks/{id}/jobs} 与 AI 工具的
 * 同一入口），故这里走真实 HTTP 通道断言状态码 + 机器可读码，并核对"被拒时不留作业行"。
 *
 * <p>覆盖：无 PRECHECK 记录（从严）/ 最近一次 FAILED / 未结束（PENDING、RUNNING）/ 已取消 → 拒绝；
 * 最近一次 COMPLETED 且 errorCount==0 → 放行（且更早的失败不作算）；EXPORT 不受本守卫约束；
 * 以及"真跑一次预检查（无上传文件）→ FAILED → 导入被拒"的端到端反例。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:s5f-precheck-gate;DB_CLOSE_DELAY=-1",
        "app.job.demo-batch-delay-ms=0",
        "app.file.storage-path=./target/s5f-test-files"
})
class PrecheckGateGuardTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JobService jobService;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private TaskService taskService;
    @Autowired
    private PrecheckJobRunner precheckJobRunner;

    // ── 反例：无 PRECHECK 记录（从严口径）──

    @Test
    void importRejectedWhenNoPrecheckRecordExists() throws Exception {
        long taskId = importTask("S5F-守卫-无预检查");

        expectRejected(taskId, "IMPORT");
        expectRejected(taskId, "PUBLISH");

        assertThat(jobRepository.findByTaskId(taskId))
                .as("被拒的请求不得留下任何作业行（否则互斥守卫会被自己钉死）").isEmpty();
    }

    // ── 反例：最近一次 PRECHECK 未通过 ──

    @Test
    void importAndPublishRejectedWhenLatestPrecheckFailed() throws Exception {
        long taskId = importTask("S5F-守卫-预检查失败");
        Job precheck = precheckJob(taskId, Job.JobStatus.FAILED, 3);
        long before = jobRepository.count();

        expectRejected(taskId, "IMPORT");
        expectRejected(taskId, "PUBLISH");

        assertThat(jobRepository.count()).as("作业表不新增行").isEqualTo(before);
        assertThat(jobRepository.findById(precheck.getId())).isPresent();
    }

    @Test
    void rejectedWhenLatestPrecheckStillRunningOrPending() throws Exception {
        long running = importTask("S5F-守卫-预检查进行中");
        precheckJob(running, Job.JobStatus.RUNNING, 0);
        expectRejected(running, "IMPORT");

        long pending = importTask("S5F-守卫-预检查排队中");
        precheckJob(pending, Job.JobStatus.PENDING, 0);
        expectRejected(pending, "PUBLISH");
    }

    @Test
    void rejectedWhenLatestPrecheckCancelled() throws Exception {
        long taskId = importTask("S5F-守卫-预检查被取消");
        precheckJob(taskId, Job.JobStatus.CANCELLED, 0);
        expectRejected(taskId, "IMPORT");
    }

    // ── 正例：最近一次 PRECHECK 通过（更早的失败不作算）──

    @Test
    void latestPassingPrecheckAllowsImportAndPublish() throws Exception {
        long taskId = importTask("S5F-守卫-最近一次通过");
        precheckJob(taskId, Job.JobStatus.FAILED, 5);              // 更早：失败
        Job passed = precheckJob(taskId, Job.JobStatus.COMPLETED, 0); // 最近：通过

        assertThat(passed.getErrorCount()).isZero();
        mockMvc.perform(post("/api/tasks/{id}/jobs", taskId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"jobType\":\"IMPORT\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.jobType").value("IMPORT"));
    }

    @Test
    void exportIsNotGatedByPrecheck() throws Exception {
        long taskId = taskService.create(Task.TaskType.EXPORT, "S5F-守卫-导出不受限").getId();

        mockMvc.perform(post("/api/tasks/{id}/jobs", taskId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"jobType\":\"EXPORT\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.jobType").value("EXPORT"));
    }

    // ── 端到端反例：真跑一次预检查（无上传文件→FAILED）后再导入 ──

    @Test
    void realPrecheckRunWithoutUploadBlocksImport() {
        Task task = taskService.create(Task.TaskType.IMPORT, "S5F-守卫-真实预检查失败");
        taskService.selectDefs(task.getId(), List.of("PROJECT_ENV"));

        Job job = new Job();
        job.setTaskId(task.getId());
        job.setJobType(Job.JobType.PRECHECK);
        job.setStatus(Job.JobStatus.PENDING);
        job = jobRepository.save(job);
        precheckJobRunner.run(job);

        Job afterRun = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(afterRun.getStatus()).as("无上传文件的预检查应 FAILED").isEqualTo(Job.JobStatus.FAILED);
        assertThat(afterRun.getErrorCount()).isGreaterThan(0);

        assertThatThrownBy(() -> jobService.createAndStart(task.getId(), Job.JobType.IMPORT))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("PRECHECK_NOT_PASSED");
    }

    // ───────────────────────── helpers ─────────────────────────

    private long importTask(String title) {
        return taskService.create(Task.TaskType.IMPORT, title).getId();
    }

    /** 造一条该任务的 PRECHECK 作业记录（守卫的判据就是这条记录的 status/errorCount）。 */
    private Job precheckJob(long taskId, Job.JobStatus status, int errorCount) {
        Job job = new Job();
        job.setTaskId(taskId);
        job.setJobType(Job.JobType.PRECHECK);
        job.setStatus(status);
        job.setErrorCount(errorCount);
        return jobRepository.save(job);
    }

    private void expectRejected(long taskId, String jobType) throws Exception {
        mockMvc.perform(post("/api/tasks/{id}/jobs", taskId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"jobType\":\"" + jobType + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("PRECHECK_NOT_PASSED"))
                .andExpect(jsonPath("$.message").value(containsString("PRECHECK_NOT_PASSED")));
    }
}
