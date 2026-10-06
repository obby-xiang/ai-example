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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S4.3 §1 发布模块行为对照用例（Q14：行级 upsert + 范围差集删除 + 单事务）。
 *
 * <p>直接驱动 {@link PublishJobRunner}（同步执行），断言落在真实 H2 库上：
 * upsert 保留行 id、范围差集删除的范围隔离、baseVersion 快照冲突文案、硬错误整批回滚。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:s43a-publish-semantics;DB_CLOSE_DELAY=-1",
        "app.job.demo-batch-delay-ms=0"
})
class PublishSemanticsTest {

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

    // ── 用例 1：MERGE 行级 upsert —— 已存在业务键行保留行 id、版本递增；范围外数据不动 ──

    @Test
    void mergeUpsertKeepsRowIdAndIncrementsVersion() {
        ConfigDataRow before = published("HB", "HB|100");
        Long id = before.getId();
        Long version = before.getVersion();
        String untouchable = published("HB", "HB|150").getDataJson();

        Task task = task("MERGE");
        Job job = run(task, staged(task, "REGION", "HB", "HB|100",
                dataJson("HB", "100", "10.9.9.9"), version));

        assertThat(job.getStatus()).isEqualTo(Job.JobStatus.COMPLETED);
        assertThat(job.getErrorCount()).isZero();

        ConfigDataRow after = published("HB", "HB|100");
        assertThat(after.getId()).as("行 id 必须保留（行级 upsert，非删建）").isEqualTo(id);
        assertThat(after.getVersion()).as("版本必须递增").isGreaterThan(version);
        assertThat(after.getDataJson()).contains("10.9.9.9");
        assertThat(published("HB", "HB|150").getDataJson()).as("MERGE 不删除范围外旧行").isEqualTo(untouchable);
    }

    // ── 用例 2：REPLACE 范围差集删除 —— 覆盖范围内未出现的旧行删除；别的范围零影响 ──

    @Test
    void replaceDeletesRowsMissingFromImportWithinCoveredScopeOnly() {
        Long keptId = published("HS", "HS|100").getId();
        String otherScope = published("XN", "XN|100").getDataJson();

        Task task = task("REPLACE");
        Job job = run(task, staged(task, "REGION", "HS", "HS|100",
                dataJson("HS", "100", "10.8.8.8"), published("HS", "HS|100").getVersion()));

        assertThat(job.getStatus()).isEqualTo(Job.JobStatus.COMPLETED);
        assertThat(find("HS", "HS|100")).isPresent();
        assertThat(find("HS", "HS|150")).as("范围内差集删除").isEmpty();
        assertThat(find("HS", "HS|200")).as("范围内差集删除").isEmpty();
        assertThat(published("HS", "HS|100").getId()).isEqualTo(keptId);
        assertThat(published("XN", "XN|100").getDataJson()).as("未覆盖范围不受影响").isEqualTo(otherScope);
    }

    // ── 用例 3：baseVersion 快照冲突 —— 作业 FAILED + 基座原文案，已发布行零变化 ──

    @Test
    void snapshotMismatchFailsJobWithBaselineMessage() {
        ConfigDataRow current = published("HE", "HE|100");
        Long staleSnapshot = current.getVersion() + 5;
        String untouched = current.getDataJson();

        Task task = task("MERGE");
        Job job = run(task, staged(task, "REGION", "HE", "HE|100",
                dataJson("HE", "100", "10.222.0.1"), staleSnapshot));

        assertThat(job.getStatus()).isEqualTo(Job.JobStatus.FAILED);
        assertThat(job.getErrorCount()).isEqualTo(1);
        List<ValidationIssue> issues = issueRepository.findByJobIdAndDefCode(job.getId(), DEF);
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).getMessage()).isEqualTo(
                "发布冲突：行 HE|100 在导入后被其他操作修改（快照版本 " + staleSnapshot
                        + "，当前版本 " + current.getVersion() + "），请重新导入后再发布");
        assertThat(published("HE", "HE|100").getDataJson()).as("冲突行不得被覆盖").isEqualTo(untouched);
    }

    // ── 用例 4：单事务 —— 同批第 2 行数据损坏 → 第 1 行已执行的 upsert 一并回滚 ──

    @Test
    void hardErrorRollsBackWholeBatch() {
        String firstRowBefore = published("XN", "XN|100").getDataJson();
        int publishedBefore = dataRowRepository.findByDefCodeOrderByRowKey(DEF).size();

        Task task = task("MERGE");
        taskItemRepository.save(item(task, DEF));
        stagingRowRepository.save(staged(task, "REGION", "XN", "XN|100",
                dataJson("XN", "100", "10.7.7.7"), published("XN", "XN|100").getVersion()));
        ConfigStagingRow broken = staged(task, "REGION", "XN", "XN|700", dataJson("XN", "700", "10.7.7.7"), null);
        broken.setDataJson("{\"regionCode\":\"XN\",");
        stagingRowRepository.save(broken);

        Job job = newJob(task);
        publishJobRunner.run(job);

        Job finished = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(finished.getStatus()).isEqualTo(Job.JobStatus.FAILED);
        assertThat(finished.getErrorCount()).isEqualTo(1);
        assertThat(issueRepository.findByJobIdAndDefCode(job.getId(), DEF).get(0).getMessage())
                .startsWith("发布过程出错: ");

        assertThat(dataRowRepository.findByDefCodeOrderByRowKey(DEF))
                .as("整批回滚：已发布行数不变").hasSize(publishedBefore);
        assertThat(published("XN", "XN|100").getDataJson()).isEqualTo(firstRowBefore);
        assertThat(find("XN", "XN|700")).isEmpty();
        assertThat(stagingRowRepository.findByTaskIdAndDefCode(task.getId(), DEF))
                .as("回滚后暂存行仍为 STAGED（PUBLISHED 写入未提交）")
                .allMatch(sr -> "STAGED".equals(sr.getStatus()));
    }

    // ───────────────────────── helpers ─────────────────────────

    private Task task(String mode) {
        Task task = new Task();
        task.setType(Task.TaskType.IMPORT);
        task.setTitle("S43A-test-" + mode);
        task.setCurrentStep("UPLOAD");
        task.setSettingsJson("{\"importMode\":\"" + mode + "\"}");
        return taskRepository.save(task);
    }

    private TaskItem item(Task task, String defCode) {
        TaskItem it = new TaskItem();
        it.setTaskId(task.getId());
        it.setDefCode(defCode);
        it.setSortOrder(0);
        return it;
    }

    /** 建任务 + 选配置项 + 写暂存行（可选） + 建作业，返回 PENDING 作业。 */
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
        return find(scopeKey, rowKey).orElseThrow();
    }

    private Optional<ConfigDataRow> find(String scopeKey, String rowKey) {
        return dataRowRepository.findRow(DEF, "REGION", scopeKey, rowKey);
    }
}
