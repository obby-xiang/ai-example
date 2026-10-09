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
import com.example.configmgr.task.repo.TaskItemRepository;
import com.example.configmgr.task.service.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T3-4 用例：<b>预检查文件指纹锚定（仅 IMPORT 生效）</b>。
 *
 * <p>背景（M2-T3 设计卡 §T3-4）：原守卫只判"最近一次 PRECHECK 通过"，不追踪上传件是否已被替换 ——
 * 用户预检查通过后替换上传件，旧的一次通过仍会放行（"检查的是 A、导入的是 B"）。本用例锁住：
 * <ol>
 * <li><b>① 未替换 → 放行</b>（201）；</li>
 * <li><b>② 替换上传件 → 409 {@code PRECHECK_STALE}</b>；</li>
 * <li><b>③ 交叉 fileType</b>：预检查读盘回落 EXPORT（记的是 EXPORT 指纹），导入侧只读 UPLOAD →
 *     走"UPLOAD 指纹缺失"的<b>从严</b>分支（断言命中哪条分支，杜绝"拿 EXPORT 指纹比 UPLOAD"的恒 409）；</li>
 * <li><b>④ 历史无指纹</b>（{@code resultJson=null} 的 COMPLETED PRECHECK）→ 409 {@code PRECHECK_STALE}；</li>
 * <li><b>⑤ A4 附加义务：合法重跑不误伤</b> —— 预检查通过 → 替换上传件 → <b>重新预检查</b>（写新指纹）
 *     → 导入放行且作业真跑 COMPLETED；</li>
 * <li><b>⑥ 仅 IMPORT 生效</b>：PUBLISH 不查指纹（无指纹也放行），仍按既有状态守卫拒绝未通过的预检查。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:t3b-precheck-fingerprint;DB_CLOSE_DELAY=-1",
        "app.job.demo-batch-delay-ms=0",
        "app.file.storage-path=./target/t3b-precheck-fingerprint-files"
})
class PrecheckFingerprintGuardTest {

    private static final String SYS_PARAM = "SYS_PARAM";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TaskService taskService;
    @Autowired
    private TaskItemRepository taskItemRepository;
    @Autowired
    private TaskFileRepository taskFileRepository;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private JobService jobService;
    @Autowired
    private PrecheckJobRunner precheckJobRunner;
    @Autowired
    private ExcelWriter excelWriter;
    @Autowired
    private FileStorageService fileStorage;
    @Autowired
    private DefinitionService definitionService;

    // ── ① 未替换上传件 → IMPORT 放行 ──

    @Test
    void importAllowedWhenUploadFileUnchanged() throws Exception {
        long taskId = importTask("T3B-指纹-未替换");
        upload(taskId, SYS_PARAM, rows("v1"));

        Job precheck = runJob(Job.JobType.PRECHECK, taskId);
        assertThat(precheck.getStatus()).as("预检查应通过（数据无缺陷）").isEqualTo(Job.JobStatus.COMPLETED);
        assertThat(precheck.getResultJson()).as("T3-4：resultJson 死列已被激活").isNotBlank();
        assertThat(precheck.getResultJson()).contains("\"UPLOAD\"").contains("\"sha256\"");

        expectImportCreated(taskId);
    }

    // ── ② 替换上传件 → 409 PRECHECK_STALE ──

    @Test
    void importRejectedWhenUploadFileReplaced() throws Exception {
        long taskId = importTask("T3B-指纹-已替换");
        upload(taskId, SYS_PARAM, rows("v1"));
        assertThat(runJob(Job.JobType.PRECHECK, taskId).getStatus()).isEqualTo(Job.JobStatus.COMPLETED);

        // 同 defCode 重写 UPLOAD 件（内容不同 ⇒ 指纹不同）
        upload(taskId, SYS_PARAM, rows("v2-replaced"));

        expectImportStale(taskId, "已被替换");
    }

    // ── ③ 交叉 fileType：预检查记 EXPORT，导入只认 UPLOAD ──

    @Test
    void importRejectedWithUploadMissingWhenPrecheckRecordedExportInstead() throws Exception {
        long taskId = importTask("T3B-指纹-交叉类型");
        // 只放一份 EXPORT 件：预检查读盘回落到它并记 EXPORT 指纹（无 UPLOAD 键）
        putFile(taskId, SYS_PARAM, "EXPORT", writeXlsx(SYS_PARAM, rows("v1")), 1);

        Job precheck = runJob(Job.JobType.PRECHECK, taskId);
        assertThat(precheck.getStatus()).isEqualTo(Job.JobStatus.COMPLETED);
        assertThat(precheck.getResultJson())
                .as("记录的是本次实际使用的 fileType（EXPORT），不是 UPLOAD")
                .contains("\"EXPORT\"")
                .doesNotContain("\"UPLOAD\"");

        // 断言走的是"UPLOAD 指纹缺失 → 从严"分支，而不是"指纹不一致"分支
        expectImportStale(taskId, "没有记下 UPLOAD 件指纹");
    }

    // ── ④ 历史无指纹 → 409 PRECHECK_STALE ──

    @Test
    void importRejectedWhenLatestPassingPrecheckHasNoFingerprint() throws Exception {
        long taskId = importTask("T3B-指纹-历史无指纹");
        upload(taskId, SYS_PARAM, rows("v1"));
        // 历史形态：COMPLETED + errorCount=0，但没有 resultJson（本棒之前落库的预检查）
        precheckRow(taskId, Job.JobStatus.COMPLETED, 0, null);

        expectImportStale(taskId, "没有记下 UPLOAD 件指纹");
    }

    // ── ⑤ A4 附加义务：合法重跑不误伤 ──

    @Test
    void legitimateReUploadAndRePrecheckStillAllowsImport() throws Exception {
        long taskId = importTask("T3B-指纹-合法重跑");
        upload(taskId, SYS_PARAM, rows("v1"));
        assertThat(runJob(Job.JobType.PRECHECK, taskId).getStatus()).isEqualTo(Job.JobStatus.COMPLETED);
        // 替换上传件后，旧指纹确实会让导入被拦（本用例的前置事实，②已独立覆盖）
        upload(taskId, SYS_PARAM, rows("v2"));
        expectImportStale(taskId, "已被替换");

        // 重新预检查：写入新指纹 → 导入放行，且导入作业真跑完
        Job recheck = runJob(Job.JobType.PRECHECK, taskId);
        assertThat(recheck.getStatus()).isEqualTo(Job.JobStatus.COMPLETED);

        var created = mockMvc.perform(post("/api/tasks/{id}/jobs", taskId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"jobType\":\"IMPORT\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.jobType").value("IMPORT"))
                .andReturn();
        long importJobId = jobIdOf(created.getResponse().getContentAsString());
        Job importJob = waitTerminal(importJobId);
        assertThat(importJob.getStatus()).as("合法重跑后导入应真跑完成").isEqualTo(Job.JobStatus.COMPLETED);
    }

    // ── ⑥ 仅 IMPORT 生效：PUBLISH 不查指纹 ──

    @Test
    void publishIsNotAffectedByFingerprint() throws Exception {
        long taskId = importTask("T3B-指纹-PUBLISH 不受限");
        upload(taskId, SYS_PARAM, rows("v1"));
        // 无 resultJson 的通过态预检查（同 ④ 的形态）——PUBLISH 仍放行
        precheckRow(taskId, Job.JobStatus.COMPLETED, 0, null);

        mockMvc.perform(post("/api/tasks/{id}/jobs", taskId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"jobType\":\"PUBLISH\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.jobType").value("PUBLISH"));

        // 状态守卫仍然生效（未通过的预检查 → 既有 PRECHECK_NOT_PASSED 语义不变）
        long failedTask = importTask("T3B-指纹-PUBLISH 状态守卫");
        precheckRow(failedTask, Job.JobStatus.FAILED, 3, null);
        mockMvc.perform(post("/api/tasks/{id}/jobs", failedTask)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"jobType\":\"PUBLISH\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRECHECK_NOT_PASSED"));
    }

    // ── 端到端反例（与既有守卫用例同形）：真跑预检查（无上传件）→ FAILED → 导入被拒 ──

    @Test
    void realPrecheckWithoutUploadStillFailsAndBlocksImport() {
        long taskId = importTask("T3B-指纹-无上传件");
        Job job = runJob(Job.JobType.PRECHECK, taskId);

        assertThat(job.getStatus()).isEqualTo(Job.JobStatus.FAILED);
        assertThat(job.getErrorCount()).isGreaterThan(0);
        assertThatThrownBy(() -> jobService.createAndStart(taskId, Job.JobType.IMPORT))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("PRECHECK_NOT_PASSED");
    }

    // ───────────────────────── helpers ─────────────────────────

    private long importTask(String title) {
        Task task = taskService.create(Task.TaskType.IMPORT, title);
        taskService.selectDefs(task.getId(), List.of(SYS_PARAM));
        return task.getId();
    }

    /** 真写一份上传件（生产 {@link ExcelWriter}）+ 真落 {@code task_files(UPLOAD)}（同 ImportFlowJobTest 手法）。 */
    private void upload(long taskId, String defCode, List<Map<String, Object>> rows) throws Exception {
        putFile(taskId, defCode, "UPLOAD", writeXlsx(defCode, rows), rows.size());
    }

    private String writeXlsx(String defCode, List<Map<String, Object>> rows) throws Exception {
        ConfigDefinition def = definitionService.findByCode(defCode);
        String path = fileStorage.newPath(".xlsx");
        excelWriter.write(def, rows, path);
        return path;
    }

    private void putFile(long taskId, String defCode, String fileType, String path, int rowCount) {
        TaskFile file = taskFileRepository.findByTaskIdAndDefCodeAndFileType(taskId, defCode, fileType)
                .orElseGet(TaskFile::new);
        file.setTaskId(taskId);
        file.setDefCode(defCode);
        file.setFileType(fileType);
        file.setStoragePath(path);
        file.setOriginalPath(path);
        file.setFileName(defCode + ".xlsx");
        file.setRowCount(rowCount);
        taskFileRepository.save(file);
    }

    /** 同步驱动一个作业执行器（绕过异步通道，断言面对同步完成后的库状态）。 */
    private Job runJob(Job.JobType type, long taskId) {
        Job job = new Job();
        job.setTaskId(taskId);
        job.setJobType(type);
        job.setStatus(Job.JobStatus.PENDING);
        job = jobRepository.save(job);
        if (type == Job.JobType.PRECHECK) {
            precheckJobRunner.run(job);
        }
        else {
            throw new IllegalArgumentException("本用例只同步驱动 PRECHECK");
        }
        return jobRepository.findById(job.getId()).orElseThrow();
    }

    /** 造一条 PRECHECK 记录（守卫的判据就是这条记录的 status/errorCount/resultJson）。 */
    private void precheckRow(long taskId, Job.JobStatus status, int errorCount, String resultJson) {
        Job job = new Job();
        job.setTaskId(taskId);
        job.setJobType(Job.JobType.PRECHECK);
        job.setStatus(status);
        job.setErrorCount(errorCount);
        job.setResultJson(resultJson);
        jobRepository.save(job);
    }

    private void expectImportCreated(long taskId) throws Exception {
        mockMvc.perform(post("/api/tasks/{id}/jobs", taskId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"jobType\":\"IMPORT\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.jobType").value("IMPORT"));
    }

    private void expectImportStale(long taskId, String branchMarker) throws Exception {
        mockMvc.perform(post("/api/tasks/{id}/jobs", taskId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"jobType\":\"IMPORT\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(JobService.PRECHECK_STALE_CODE))
                .andExpect(jsonPath("$.message").value(containsString(branchMarker)));
        assertThat(jobRepository.findByTaskId(taskId))
                .as("被指纹守卫拒绝时不留导入作业行")
                .noneMatch(job -> job.getJobType() == Job.JobType.IMPORT);
    }

    private long jobIdOf(String createResponseBody) {
        var matcher = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(createResponseBody);
        assertThat(matcher.find()).as("建作业响应中应有 id: " + createResponseBody).isTrue();
        return Long.parseLong(matcher.group(1));
    }

    private Job waitTerminal(long jobId) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Job job = jobRepository.findById(jobId).orElseThrow();
            if (job.getStatus() != Job.JobStatus.PENDING && job.getStatus() != Job.JobStatus.RUNNING) {
                return job;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("导入作业未在 10s 内到终态");
    }

    private Map<String, Object> row(String... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private List<Map<String, Object>> rows(String paramValue) {
        return List.of(row("paramKey", "t3b.key.1", "paramValue", paramValue, "paramType", "STRING", "editable", "true"));
    }
}
