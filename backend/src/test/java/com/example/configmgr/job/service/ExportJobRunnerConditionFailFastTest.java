package com.example.configmgr.job.service;

import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.entity.JobItem;
import com.example.configmgr.job.entity.ValidationIssue;
import com.example.configmgr.job.repo.JobItemRepository;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.job.repo.ValidationIssueRepository;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.repo.TaskFileRepository;
import com.example.configmgr.task.repo.TaskItemRepository;
import com.example.configmgr.task.repo.TaskRepository;
import com.example.configmgr.task.service.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W-03 fail-fast 用例族（R-96 批 B）：导出作业条件解析失败**不再静默放宽为全量导出**。
 *
 * <p>施工前病灶：私有 {@code parseCondition} 吞异常 {@code return null} ⇒
 * {@code matches(row, null)} 恒 true ⇒ 条件导出静默变全量导出（无 WARN、无 issue、无失败）。
 * 施工后：统一走数据面同义的 {@code ConfigDataService#parseCondition}（解析失败抛
 * {@code IllegalArgumentException}，与 {@code /api/data/**} 的 400 同口径），由项级 catch
 * 转成「该配置项 FAILED + 作业 FAILED」。
 *
 * <p>断言落在真实 H2 与真实落盘文件上（手法同 {@link ExportFlowJobTest} / {@link PublishSemanticsTest}）。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:r96-export-failfast;DB_CLOSE_DELAY=-1",
        "app.job.demo-batch-delay-ms=0",
        "app.file.storage-path=./target/r96-export-failfast-files"
})
class ExportJobRunnerConditionFailFastTest {

    private static final String ROLE_DICT = "ROLE_DICT";

    @Autowired
    private TaskService taskService;
    @Autowired
    private TaskRepository taskRepository;
    @Autowired
    private TaskItemRepository taskItemRepository;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private JobItemRepository jobItemRepository;
    @Autowired
    private ExportJobRunner exportJobRunner;
    @Autowired
    private ValidationIssueRepository issueRepository;
    @Autowired
    private TaskFileRepository taskFileRepository;

    // ── U1：脏条件 JSON（历史 Map.toString() 形态）⇒ 项级 FAILED + ERROR issue + 作业 FAILED + 零导出件 ──

    @Test
    void unparsableConditionFailsFastWithoutProducingAnyExportFile() {
        Task task = taskService.create(Task.TaskType.EXPORT, "R96-W03-脏条件");
        taskService.selectDefs(task.getId(), List.of(ROLE_DICT));
        // 历史脏数据形态：不是合法 JSON（等价 wait-audit 排查报告 §1 W-03 的 {scopeKeys=[XN], fields=[]}）
        taskService.setCondition(task.getId(), ROLE_DICT, "{scopeKeys=[XN], fields=[]}");

        Job job = runExport(task.getId());

        assertThat(job.getStatus()).as("解析失败 ⇒ 作业 FAILED（不再静默全量导出）")
                .isEqualTo(Job.JobStatus.FAILED);
        assertThat(job.getErrorCount()).isEqualTo(1);

        List<ValidationIssue> issues = issueRepository.findByJobIdAndDefCode(job.getId(), ROLE_DICT);
        assertThat(issues).as("解析失败必须落一条 ERROR issue（原实现无任何留痕）").hasSize(1);
        assertThat(issues.get(0).getSeverity()).isEqualTo(ValidationIssue.Severity.ERROR);
        assertThat(issues.get(0).getMessage()).contains("IllegalArgumentException").contains("条件 JSON 解析失败");

        assertThat(taskItemRepository.findByTaskIdAndDefCode(task.getId(), ROLE_DICT).orElseThrow().getStatus())
                .as("该配置项 task_item 标记 FAILED").isEqualTo("FAILED");
        assertThat(jobItemRepository.findByJobId(job.getId()))
                .anySatisfy(item -> assertThat(item.getStatus()).isEqualTo("FAILED"));

        assertThat(taskFileRepository.findByTaskIdAndDefCodeAndFileType(task.getId(), ROLE_DICT, "EXPORT"))
                .as("绝不出产导出件（全量导出件是原病灶的受损产物）").isEmpty();
    }

    // ── U2：回归锁 —— 空条件（null / 空白）仍是合法「全量导出」语义，不得被 fail-fast 误伤 ──

    @Test
    void nullAndBlankConditionStillExportEverything() {
        Task nullCond = taskService.create(Task.TaskType.EXPORT, "R96-W03-空条件-null");
        taskService.selectDefs(nullCond.getId(), List.of(ROLE_DICT));

        Task blankCond = taskService.create(Task.TaskType.EXPORT, "R96-W03-空条件-空白");
        taskService.selectDefs(blankCond.getId(), List.of(ROLE_DICT));
        taskService.setCondition(blankCond.getId(), ROLE_DICT, "   ");

        Job jobNull = runExport(nullCond.getId());
        Job jobBlank = runExport(blankCond.getId());

        for (Job job : List.of(jobNull, jobBlank)) {
            assertThat(job.getStatus()).as("空条件 = 正常全量导出").isEqualTo(Job.JobStatus.COMPLETED);
            assertThat(job.getErrorCount()).isZero();
            assertThat(taskFileRepository.findByTaskIdAndDefCodeAndFileType(
                    job.getTaskId(), ROLE_DICT, "EXPORT")).isPresent()
                    .get().extracting(f -> f.getRowCount())
                    .as("种子 ROLE_DICT 全量 8 行").isEqualTo(8);
        }
    }

    // ── U3：未知操作符 —— 与数据面 400 同因（ConditionEvaluator.validate 早失败），作业侧项级 FAILED ──

    @Test
    void unknownOperatorFailsItemAndJobSameCauseAsDataPlane400() {
        Task task = taskService.create(Task.TaskType.EXPORT, "R96-W03-未知操作符");
        taskService.selectDefs(task.getId(), List.of(ROLE_DICT));
        taskService.setCondition(task.getId(), ROLE_DICT,
                "{\"fields\":[{\"fieldCode\":\"roleCode\",\"operator\":\"NO_SUCH_OP\",\"value\":\"1\"}]}");

        Job job = runExport(task.getId());

        assertThat(job.getStatus()).isEqualTo(Job.JobStatus.FAILED);
        List<ValidationIssue> issues = issueRepository.findByJobIdAndDefCode(job.getId(), ROLE_DICT);
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).getMessage()).contains("未知的查询条件操作符");
        assertThat(taskFileRepository.findByTaskIdAndDefCodeAndFileType(task.getId(), ROLE_DICT, "EXPORT"))
                .as("未知操作符同样不得产出导出件").isEmpty();
    }

    // ───────────────────────── helpers ─────────────────────────

    /** 同步执行一次导出作业（与 ExportFlowJobTest#runExport 同手法）。 */
    private Job runExport(Long taskId) {
        Job job = new Job();
        job.setTaskId(taskId);
        job.setJobType(Job.JobType.EXPORT);
        job.setStatus(Job.JobStatus.PENDING);
        job = jobRepository.save(job);
        exportJobRunner.run(job);
        return jobRepository.findById(job.getId()).orElseThrow();
    }
}
