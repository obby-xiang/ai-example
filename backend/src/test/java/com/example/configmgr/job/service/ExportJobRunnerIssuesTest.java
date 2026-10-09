package com.example.configmgr.job.service;

import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.entity.ValidationIssue;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.job.repo.ValidationIssueRepository;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.service.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T3-3 用例：<b>导出作业失败必须落 {@code validation_issues}</b>（项级 + 作业级）。
 *
 * <p>缺口背景（M2-T3 设计卡 §T3-3 原文）：{@link ExportJobRunner} 的两个 catch 此前只
 * {@code log.error} + 计数，失败原因对 REST 通道不可见 —— {@code GET /api/jobs/{id}/issues}
 * 返回空、前端明细区无可展示条目。本用例锁住三件事：
 * <ol>
 * <li><b>项级失败落 issue</b>：severity=ERROR、defCode=失败配置项、rowKey/fieldCode 留空、
 * message 含异常类名且不超过列宽 1024（超长异常消息不得让 INSERT 失败）；
 * <li><b>幂等</b>：同一 jobId 的历史 issue 在开工时被 {@code deleteByJobId} 清掉，不叠加；
 * <li><b>端点可见</b>：{@code JobService#findIssues}（issues 端点的服务层）能读到该条目。
 * </ol>
 *
 * <p>作业级 issue（{@code defCode="-"}）的用例在
 * {@link ExportJobRunnerJobLevelIssueTest}（外层 catch 需要可注入的假件，故用纯 Mockito 形态）。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:t3b-export-issues;DB_CLOSE_DELAY=-1",
        "app.job.demo-batch-delay-ms=0",
        "app.file.storage-path=./target/t3b-export-issues-files"
})
class ExportJobRunnerIssuesTest {

    private static final String SYS_PARAM = "SYS_PARAM";

    @Autowired
    private TaskService taskService;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private JobService jobService;
    @Autowired
    private ExportJobRunner exportJobRunner;
    @Autowired
    private ValidationIssueRepository issueRepository;

    /** 该作业下的 issue（走 issues 端点的服务层同一条读路径）。 */
    private List<ValidationIssue> issuesOf(Long jobId) {
        return jobService.findIssues(jobId, 0, 50).getContent();
    }

    // ── ① 项级失败 → validation_issues 可见 ERROR 条目 ──

    @Test
    void itemLevelFailureWritesErrorIssueWithinColumnWidth() {
        // 非法操作符（超长形态）：ConditionEvaluator.validate 抛 IllegalArgumentException，
        // 且异常消息长度远超列宽 1024 —— 同时覆盖"列宽保护"这条隐性坑
        String bogusOperator = "X".repeat(1200);
        long taskId = exportTaskWithCondition("T3B-导出-项级失败", conditionJson("paramKey", bogusOperator));
        Job job = runExport(taskId);

        assertThat(job.getStatus()).as("项级失败 → 作业 FAILED").isEqualTo(Job.JobStatus.FAILED);
        assertThat(job.getErrorCount()).as("ErrorCount 计入本次项级失败").isGreaterThanOrEqualTo(1);

        List<ValidationIssue> issues = issuesOf(job.getId());
        assertThat(issues).as("该作业下恰 1 条 issue（本轮的项级失败）").hasSize(1);

        ValidationIssue issue = issues.get(0);
        assertThat(issue.getSeverity()).isEqualTo(ValidationIssue.Severity.ERROR);
        assertThat(issue.getDefCode()).isEqualTo(SYS_PARAM);
        assertThat(issue.getRowKey()).as("导出失败不是行级问题，rowKey 留空").isNull();
        assertThat(issue.getFieldCode()).as("导出失败不是字段级问题，fieldCode 留空").isNull();
        assertThat(issue.getMessage()).contains("IllegalArgumentException");
        assertThat(issue.getMessage().length())
                .as("message 必须落在列宽 1024 内（超长异常消息不得让 INSERT 失败）")
                .isLessThanOrEqualTo(1024);

        // ② 端点级可见（issues 端点的服务层就是 JobService#findIssues）
        Page<ValidationIssue> page = jobService.findIssues(job.getId(), 0, 50);
        assertThat(page.getContent()).extracting(ValidationIssue::getDefCode).containsExactly(SYS_PARAM);
    }

    // ── ③ 幂等：历史 issue 不叠加 ──

    @Test
    void rerunClearsPreviousIssuesForTheSameJob() {
        // 预置 2 条"历史 issue"（同一 jobId 上的旧记录）——必须在本次 run 之前写好：
        // 生产侧同一作业行不可重跑（job_items 上有 (job_id, def_code) 唯一约束），
        // 故"重跑"的语义只能由"开工时的 deleteByJobId 幂等清理"表达
        Task task = taskService.create(Task.TaskType.EXPORT, "T3B-导出-幂等");
        taskService.selectDefs(task.getId(), List.of(SYS_PARAM));
        taskService.setCondition(task.getId(), SYS_PARAM, conditionJson("paramKey", "BOGUS_OP"));

        Job job = new Job();
        job.setTaskId(task.getId());
        job.setJobType(Job.JobType.EXPORT);
        job.setStatus(Job.JobStatus.PENDING);
        job = jobRepository.save(job);
        seedIssue(job.getId(), "LEGACY-A");
        seedIssue(job.getId(), "LEGACY-B");
        assertThat(issuesOf(job.getId())).as("预置的历史 issue 已落库").hasSize(2);

        exportJobRunner.run(jobRepository.findById(job.getId()).orElseThrow());

        assertThat(issuesOf(job.getId()))
                .as("开工时 deleteByJobId 清掉历史，最终只剩本轮的 issue（计数不叠加）")
                .hasSize(1)
                .allSatisfy(issue -> assertThat(issue.getDefCode()).isEqualTo(SYS_PARAM));
    }

    // ── 成功路径回归：不产 issue（不误伤） ──

    @Test
    void successfulExportWritesNoIssue() {
        Task task = taskService.create(Task.TaskType.EXPORT, "T3B-导出-成功无 issue");
        taskService.selectDefs(task.getId(), List.of("ROLE_DICT"));

        Job job = runExport(task.getId());

        assertThat(job.getStatus()).isEqualTo(Job.JobStatus.COMPLETED);
        assertThat(issuesOf(job.getId())).isEmpty();
    }

    // ───────────────────────── helpers ─────────────────────────

    private long exportTaskWithCondition(String title, String conditionJson) {
        Task task = taskService.create(Task.TaskType.EXPORT, title);
        taskService.selectDefs(task.getId(), List.of(SYS_PARAM));
        taskService.setCondition(task.getId(), SYS_PARAM, conditionJson);
        return task.getId();
    }

    private Job runExport(Long taskId) {
        Job job = new Job();
        job.setTaskId(taskId);
        job.setJobType(Job.JobType.EXPORT);
        job.setStatus(Job.JobStatus.PENDING);
        job = jobRepository.save(job);
        exportJobRunner.run(job);
        return jobRepository.findById(job.getId()).orElseThrow();
    }

    private void seedIssue(Long jobId, String defCode) {
        ValidationIssue legacy = new ValidationIssue();
        legacy.setJobId(jobId);
        legacy.setDefCode(defCode);
        legacy.setSeverity(ValidationIssue.Severity.ERROR);
        legacy.setMessage("历史遗留 issue");
        issueRepository.save(legacy);
    }

    private String conditionJson(String fieldCode, String operator) {
        return "{\"fields\":[{\"fieldCode\":\"" + fieldCode + "\",\"operator\":\"" + operator
                + "\",\"value\":\"x\"}]}";
    }
}
