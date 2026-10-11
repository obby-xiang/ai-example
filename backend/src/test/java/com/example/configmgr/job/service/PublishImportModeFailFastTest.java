package com.example.configmgr.job.service;

import com.example.configmgr.data.entity.ConfigDataRow;
import com.example.configmgr.data.entity.ConfigStagingRow;
import com.example.configmgr.data.repo.ConfigDataRowRepository;
import com.example.configmgr.data.repo.ConfigStagingRowRepository;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.entity.ValidationIssue;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.job.repo.ValidationIssueRepository;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskItem;
import com.example.configmgr.task.repo.TaskItemRepository;
import com.example.configmgr.task.repo.TaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W-04 fail-fast 用例族（R-96 批 B）：发布导入模式解析失败**不再静默降级 REPLACE → MERGE**。
 *
 * <p>施工前病灶：{@code isReplaceMode} 解析失败 {@code return false} ⇒ 用户/AI 明确选的
 * 「整体替换」静默变「增量合并」（无 WARN、无 issue、无失败）。施工后（裁决 Q2 严格口径）：
 * 三态 {@code REPLACE / MERGE / UNPARSEABLE}，UNPARSEABLE 抛异常走
 * {@code run} 的既有 catch → {@code recordFailure} → 整批回滚 + 作业 FAILED + 作业级 issue
 * （{@code defCode} 哨兵 {@code "-"}）。「未配置」回落 MERGE 是正常语义，不得误伤（U5 回归锁）。
 *
 * <p>断言落在真实 H2（手法同 {@link PublishSemanticsTest}）。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:r96-publish-failfast;DB_CLOSE_DELAY=-1",
        "app.job.demo-batch-delay-ms=0"
})
class PublishImportModeFailFastTest {

    private static final String DEF = "REGION_NETWORK";

    @Autowired
    private PublishJobRunner publishJobRunner;
    @Autowired
    private TaskRepository taskRepository;
    @Autowired
    private TaskItemRepository taskItemRepository;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private ConfigDataRowRepository dataRowRepository;
    @Autowired
    private ConfigStagingRowRepository stagingRowRepository;
    @Autowired
    private ValidationIssueRepository issueRepository;

    // ── U4：settings_json 是坏 JSON ⇒ 整批回滚 + 作业 FAILED + 作业级 issue（defCode "-"）──

    @Test
    void unparsableImportModeFailsWholeBatchWithJobLevelIssue() {
        ConfigDataRow before = published("XN", "XN|100");
        String dataBefore = before.getDataJson();
        int publishedBefore = dataRowRepository.findByDefCodeOrderByRowKey(DEF).size();

        Task task = task("MERGE");
        task.setSettingsJson("{\"importMode\":"); // 截断的坏 JSON：readTree 必抛 ⇒ UNPARSEABLE
        taskRepository.save(task);

        Job job = newJob(task);
        publishJobRunner.run(job);

        Job finished = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(finished.getStatus()).as("解析失败 ⇒ 严格 fail-fast，作业 FAILED（不再静默降级 MERGE）")
                .isEqualTo(Job.JobStatus.FAILED);
        assertThat(finished.getErrorCount()).isEqualTo(1);

        List<ValidationIssue> jobLevel = issueRepository.findByJobIdAndDefCode(job.getId(), "-");
        assertThat(jobLevel).as("作业级 issue 一条（recordFailure 的 defCode=null 哨兵路径）").hasSize(1);
        assertThat(jobLevel.get(0).getSeverity()).isEqualTo(ValidationIssue.Severity.ERROR);
        assertThat(jobLevel.get(0).getMessage())
                .startsWith("发布过程出错: ")
                .contains("settings_json 解析失败")
                .contains("不静默降级 REPLACE→MERGE");

        assertThat(dataRowRepository.findByDefCodeOrderByRowKey(DEF))
                .as("整批回滚：已发布行数不变").hasSize(publishedBefore);
        assertThat(published("XN", "XN|100").getDataJson()).as("已发布数据零变化").isEqualTo(dataBefore);
    }

    // ── U5：回归锁 —— 「未配置导入模式」回落 MERGE 是合法语义，不得被 fail-fast 误伤 ──

    @Test
    void missingImportModeStillFallsBackToMerge() {
        ConfigDataRow before = published("HB", "HB|100");
        Long id = before.getId();

        // settings_json == null（从未设置导入模式）
        Task task = task(null);
        task.setSettingsJson(null);
        taskRepository.save(task);

        Job job = run(task, staged(task, "REGION", "HB", "HB|100",
                dataJson("HB", "100", "10.9.9.9"), before.getVersion()));

        assertThat(job.getStatus()).as("未配置 ⇒ 正常 MERGE，作业完成").isEqualTo(Job.JobStatus.COMPLETED);
        assertThat(job.getErrorCount()).isZero();
        ConfigDataRow after = published("HB", "HB|100");
        assertThat(after.getId()).as("MERGE 行级 upsert：行 id 保留").isEqualTo(id);
        assertThat(after.getDataJson()).contains("10.9.9.9");
    }

    /** 无 importMode 键的空对象 settings 同样回落 MERGE（asText("MERGE") 既有口径）。 */
    @Test
    void emptySettingsObjectStillFallsBackToMerge() {
        ConfigDataRow before = published("HE", "HE|100");

        Task task = task("MERGE");
        task.setSettingsJson("{}");
        taskRepository.save(task);

        Job job = run(task, staged(task, "REGION", "HE", "HE|100",
                dataJson("HE", "100", "10.222.0.1"), before.getVersion()));

        assertThat(job.getStatus()).isEqualTo(Job.JobStatus.COMPLETED);
        assertThat(published("HE", "HE|100").getDataJson()).contains("10.222.0.1");
    }

    // ───────────────────────── helpers（与 PublishSemanticsTest 同构）─────────────────────────

    private Task task(String mode) {
        Task task = new Task();
        task.setType(Task.TaskType.IMPORT);
        task.setTitle("R96-W04-test-" + (mode == null ? "null" : mode));
        task.setCurrentStep("UPLOAD");
        task.setSettingsJson(mode == null ? null : "{\"importMode\":\"" + mode + "\"}");
        return taskRepository.save(task);
    }

    private TaskItem item(Task task, String defCode) {
        TaskItem it = new TaskItem();
        it.setTaskId(task.getId());
        it.setDefCode(defCode);
        it.setSortOrder(0);
        return it;
    }

    private Job run(Task task, ConfigStagingRow... stagedRows) {
        taskItemRepository.save(item(task, DEF));
        for (ConfigStagingRow sr : stagedRows) {
            stagingRowRepository.save(sr);
        }
        Job job = newJob(task);
        publishJobRunner.run(job);
        return jobRepository.findById(job.getId()).orElseThrow();
    }

    private Job newJob(Task task) {
        Job job = new Job();
        job.setTaskId(task.getId());
        job.setJobType(Job.JobType.PUBLISH);
        job.setStatus(Job.JobStatus.PENDING);
        return jobRepository.save(job);
    }

    private ConfigStagingRow staged(Task task, String scopeType, String scopeKey, String rowKey,
                                    String dataJson, Long baseVersion) {
        ConfigStagingRow sr = new ConfigStagingRow();
        sr.setTaskId(task.getId());
        sr.setDefCode(DEF);
        sr.setScopeType(scopeType);
        sr.setScopeKey(scopeKey);
        sr.setRowKey(rowKey);
        sr.setDataJson(dataJson);
        sr.setBaseVersion(baseVersion);
        return sr;
    }

    private String dataJson(String scope, String bandwidth, String gateway) {
        return "{\"regionCode\":\"" + scope + "\",\"bandwidthMbps\":\"" + bandwidth
                + "\",\"gateway\":\"" + gateway + "\",\"redundancy\":\"true\"}";
    }

    private ConfigDataRow published(String scopeKey, String rowKey) {
        return dataRowRepository.findRow(DEF, "REGION", scopeKey, rowKey).orElseThrow();
    }
}
