package com.example.configmgr.job.service;

import com.example.configmgr.common.ConflictException;
import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.service.DefinitionService;
import com.example.configmgr.excel.ExcelWriter;
import com.example.configmgr.file.FileStorageService;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskFile;
import com.example.configmgr.task.repo.TaskFileRepository;
import com.example.configmgr.task.service.TaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

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

    private static final String SYS_PARAM = "SYS_PARAM";

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
    @Autowired
    private TaskFileRepository taskFileRepository;
    @Autowired
    private DefinitionService definitionService;
    @Autowired
    private ExcelWriter excelWriter;
    @Autowired
    private FileStorageService fileStorage;
    @Autowired
    private ObjectMapper objectMapper;

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

    /**
     * T3-4（A7）改法：本用例原先只造"无 resultJson 的 PRECHECK 行 + 无条目的任务"，
     * 指纹守卫引入后该形态会<b>空转</b>（任务无 item ⇒ 不进入比对循环）——
     * 即"最近一次通过 → 放行"这条原语义不再被真正验证。
     *
     * <p>故 fixture 补成真实形态：任务已选 1 个配置项 + 真落一份 UPLOAD 件 +
     * PRECHECK 行写入与该上传件一致的<b>真实指纹摘要</b>。期望值不变（仍 201 Created），
     * 只是从"空转放行"变成"指纹比对通过后放行"。改动登记见 T3b 施工报告「T3-4 既有用例改动」。
     */
    @Test
    void latestPassingPrecheckAllowsImportAndPublish() throws Exception {
        long taskId = importTask("S5F-守卫-最近一次通过");
        taskService.selectDefs(taskId, List.of(SYS_PARAM));
        String uploadPath = writeUpload(taskId, SYS_PARAM);
        Job failed = precheckJob(taskId, Job.JobStatus.FAILED, 5);              // 更早：失败
        Job passed = precheckJob(taskId, Job.JobStatus.COMPLETED, 0,           // 最近：通过
                fingerprintJson(SYS_PARAM, "UPLOAD", uploadPath));

        assertThat(failed.getErrorCount()).isEqualTo(5);
        assertThat(passed.getErrorCount()).isZero();
        mockMvc.perform(post("/api/tasks/{id}/jobs", taskId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"jobType\":\"IMPORT\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.jobType").value("IMPORT"));
    }

    /**
     * T3-4（A7 新增）：<b>无指纹的历史通过态 PRECHECK 一律从严</b> —— 409 {@code PRECHECK_STALE}，
     * 且被拒时不留导入作业行（与既有守卫的"拒绝不留行"契约一致）。
     */
    @Test
    void importRejectedWhenPassingPrecheckHasNoFingerprint() throws Exception {
        long taskId = importTask("S5F-守卫-通过但无指纹");
        taskService.selectDefs(taskId, List.of(SYS_PARAM));
        writeUpload(taskId, SYS_PARAM);
        precheckJob(taskId, Job.JobStatus.COMPLETED, 0);   // 无 resultJson 的历史形态

        mockMvc.perform(post("/api/tasks/{id}/jobs", taskId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"jobType\":\"IMPORT\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRECHECK_STALE"))
                .andExpect(jsonPath("$.message").value(containsString("PRECHECK_STALE")));
        assertThat(jobRepository.findByTaskId(taskId))
                .as("被拒的请求不得留下任何作业行")
                .noneMatch(job -> job.getJobType() == Job.JobType.IMPORT);
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
        return precheckJob(taskId, status, errorCount, null);
    }

    private Job precheckJob(long taskId, Job.JobStatus status, int errorCount, String resultJson) {
        Job job = new Job();
        job.setTaskId(taskId);
        job.setJobType(Job.JobType.PRECHECK);
        job.setStatus(status);
        job.setErrorCount(errorCount);
        job.setResultJson(resultJson);
        return jobRepository.save(job);
    }

    /**
     * T3-4 追加 fixture：真写一份上传件（生产 {@link ExcelWriter}）+ 真落
     * {@code task_files(UPLOAD)}（与 {@code ImportFlowJobTest#upload} 同一手法）。
     */
    private String writeUpload(long taskId, String defCode) throws Exception {
        ConfigDefinition def = definitionService.findByCode(defCode);
        String path = fileStorage.newPath(".xlsx");
        excelWriter.write(def, List.of(row("paramKey", "s5f.key.1", "paramValue", "v1",
                "paramType", "STRING", "editable", "true")), path);
        TaskFile file = new TaskFile();
        file.setTaskId(taskId);
        file.setDefCode(defCode);
        file.setFileType("UPLOAD");
        file.setStoragePath(path);
        file.setOriginalPath(path);
        file.setFileName(defCode + ".xlsx");
        file.setRowCount(1);
        taskFileRepository.save(file);
        return path;
    }

    /** 与上传件一致的<b>真实</b>指纹摘要（走生产侧的流式 digest，不手抄 sha256）。 */
    private String fingerprintJson(String defCode, String fileType, String storagePath) throws Exception {
        var digest = fileStorage.digest(storagePath);
        return objectMapper.writeValueAsString(Map.of(defCode,
                Map.of(fileType, Map.of("sha256", digest.sha256(), "size", digest.size()))));
    }

    private Map<String, Object> row(String... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
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
