package com.example.configmgr.job.service;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M1 收尾守卫②（S5.a §5 待裁决 #2 / S5.b §6.6 裁决"采纳"）的回归用例：
 * <b>同任务 + 同类型已有在途（PENDING/RUNNING）作业 → 409 {@code JOB_ALREADY_RUNNING}</b>。
 *
 * <p>动机（S5.a §5#2 实测）：无互斥时两个导出会并发写同一 {@code task_files(task, def, EXPORT)} 行
 * （后发者覆盖前者的文件记录），且进度互不感知。守卫落在 {@link JobService#createAndStart}，
 * 与 DC-11 的 409 串行化同一个风格（机器可读码 + 中文可读文案）。
 *
 * <p>覆盖：PENDING/RUNNING 被拒（且不落新作业行）、终态（COMPLETED/CANCELLED）放行、
 * 不同类型互不影响、不同任务互不影响、被拒文案带在途作业号（便于排障）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:s5f-job-mutex;DB_CLOSE_DELAY=-1",
        "app.job.demo-batch-delay-ms=0",
        "app.file.storage-path=./target/s5f-test-files"
})
class JobMutexGuardTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private TaskService taskService;

    // ── 反例：同类作业在途 ──

    @Test
    void pendingSameTypeJobBlocksNewOne() throws Exception {
        long taskId = exportTask("S5F-互斥-PENDING");
        Job inFlight = jobRow(taskId, Job.JobType.EXPORT, Job.JobStatus.PENDING);
        long before = jobRepository.count();

        mockMvc.perform(startJob(taskId, "EXPORT"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("JOB_ALREADY_RUNNING"))
                .andExpect(jsonPath("$.message").value(containsString("#" + inFlight.getId())));

        assertThat(jobRepository.count()).as("被拒的请求不得留作业行").isEqualTo(before);
    }

    @Test
    void runningSameTypeJobBlocksNewOne() throws Exception {
        long taskId = exportTask("S5F-互斥-RUNNING");
        jobRow(taskId, Job.JobType.EXPORT, Job.JobStatus.RUNNING);

        mockMvc.perform(startJob(taskId, "EXPORT"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB_ALREADY_RUNNING"));
    }

    @Test
    void runningPrecheckBlocksSecondPrecheckButGateCodesDifferByType() throws Exception {
        long taskId = exportTask("S5F-互斥-预检查");
        jobRow(taskId, Job.JobType.PRECHECK, Job.JobStatus.RUNNING);

        // 同类型（PRECHECK）→ 互斥；注意不是 PRECHECK_NOT_PASSED（那是"导入/发布"的守卫，见 PrecheckGateGuardTest）
        mockMvc.perform(startJob(taskId, "PRECHECK"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB_ALREADY_RUNNING"));
    }

    // ── 正例：终态放行 ──

    @Test
    void terminalJobsDoNotBlockNewOne() throws Exception {
        long completed = exportTask("S5F-互斥-已完成后");
        jobRow(completed, Job.JobType.EXPORT, Job.JobStatus.COMPLETED);
        mockMvc.perform(startJob(completed, "EXPORT"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.jobType").value("EXPORT"));

        long cancelled = exportTask("S5F-互斥-已取消后");
        jobRow(cancelled, Job.JobType.EXPORT, Job.JobStatus.CANCELLED);
        mockMvc.perform(startJob(cancelled, "EXPORT"))
                .andExpect(status().isCreated());

        long failed = exportTask("S5F-互斥-失败后");
        jobRow(failed, Job.JobType.EXPORT, Job.JobStatus.FAILED);
        mockMvc.perform(startJob(failed, "EXPORT"))
                .andExpect(status().isCreated());
    }

    // ── 正例：类型/任务维度的隔离（守卫只按"同任务 + 同类型"生效）──

    @Test
    void otherJobTypeOnSameTaskIsNotBlocked() throws Exception {
        long taskId = exportTask("S5F-互斥-异类型");
        jobRow(taskId, Job.JobType.EXPORT, Job.JobStatus.PENDING);

        mockMvc.perform(startJob(taskId, "PRECHECK"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.jobType").value("PRECHECK"));
    }

    @Test
    void otherTaskIsNotBlocked() throws Exception {
        long busy = exportTask("S5F-互斥-占用中");
        long free = exportTask("S5F-互斥-另一任务");
        jobRow(busy, Job.JobType.EXPORT, Job.JobStatus.PENDING);

        mockMvc.perform(startJob(free, "EXPORT"))
                .andExpect(status().isCreated());
    }

    // ───────────────────────── helpers ─────────────────────────

    private long exportTask(String title) {
        return taskService.create(Task.TaskType.EXPORT, title).getId();
    }

    /** 造一条在途/终态作业记录（守卫的判据就是这条记录的状态）。 */
    private Job jobRow(long taskId, Job.JobType jobType, Job.JobStatus status) {
        Job job = new Job();
        job.setTaskId(taskId);
        job.setJobType(jobType);
        job.setStatus(status);
        return jobRepository.save(job);
    }

    private MockHttpServletRequestBuilder startJob(long taskId, String jobType) {
        return post("/api/tasks/{id}/jobs", taskId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"jobType\":\"" + jobType + "\"}");
    }
}
