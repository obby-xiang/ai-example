package com.example.configmgr.job.service;

import com.example.configmgr.common.ConflictException;
import com.example.configmgr.definition.service.DefinitionService;
import com.example.configmgr.excel.ExcelReader;
import com.example.configmgr.file.FileStorageService;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskItem;
import com.example.configmgr.task.repo.TaskFileRepository;
import com.example.configmgr.task.repo.TaskItemRepository;
import com.example.configmgr.task.repo.TaskRepository;
import com.example.configmgr.task.service.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * S5.a 用例（移植自 glm-5.3 {@code ExportFlowTest}）：导出全流程 —— 建任务 → 选配置 → 设条件 → 执行 → 结果。
 *
 * <p>glm 侧的被测面是 {@code ExportRunner#run(task, session)}；本仓库同职责的实现是
 * {@link ExportJobRunner}（逐配置项按条件过滤已发布行 → 写 Excel + task_files），
 * 故此处直接同步驱动该执行器（与 {@code PublishSemanticsTest} 同一手法），
 * 断言落在真实 H2 与真实落盘的 xlsx 上。
 *
 * <p><b>语义改写点</b>：
 * <ul>
 * <li>glm 的 {@code taskService.assertSelection} / {@code assertNotTerminal} 在本仓库没有对应守卫；
 *     本仓库的真实守卫是"条件只对<b>已勾选</b>的配置项有效"（{@code TaskService#setCondition} 落库时校验）
 *     与"已终态作业不可再取消"（409 JOB_ALREADY_FINAL），两条分别由用例 1、3 承接；</li>
 * <li>导出结果读取：glm 从任务参数包 {@code exportResult} 取内存结果，本仓库结果是落盘的 xlsx +
 *     task_files 行 → 用 {@link ExcelReader} 回读文件校验行数与内容；</li>
 * <li>"导出中重复导出应被拒"在实现里没有并发互斥守卫，未移植（见证据文档【待裁决】）。</li>
 * </ul>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:s5a-export-flow;DB_CLOSE_DELAY=-1",
        "app.job.demo-batch-delay-ms=0",
        "app.file.storage-path=./target/s5a-test-files"
})
class ExportFlowJobTest {

    private static final String SYS_PARAM = "SYS_PARAM";
    private static final String ALARM_THRESHOLD = "ALARM_THRESHOLD";
    private static final String ROLE_DICT = "ROLE_DICT";

    @Autowired
    private TaskService taskService;
    @Autowired
    private TaskRepository taskRepository;
    @Autowired
    private TaskItemRepository taskItemRepository;
    @Autowired
    private JobService jobService;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private ExportJobRunner exportJobRunner;
    @Autowired
    private DefinitionService definitionService;
    @Autowired
    private TaskFileRepository taskFileRepository;
    @Autowired
    private FileStorageService fileStorage;
    @Autowired
    private ExcelReader excelReader;

    // ── 用例 1：建任务 → 选配置 → 设条件（未选配置不得设条件）──

    @Test
    void createAndSelectDefsPersistsItemsAndConditionNeedsSelection() {
        Task task = taskService.create(Task.TaskType.EXPORT, "S5A-导出-选配置");
        assertThat(taskItemRepository.findByTaskIdOrderBySortOrder(task.getId())).isEmpty();

        // 未选配置时设条件应失败（glm：assertSelection 失败）
        assertThatThrownBy(() -> taskService.setCondition(task.getId(), SYS_PARAM, conditionJson("paramKey", "CONTAINS", "param.2")))
                .as("条件只对已勾选的配置项有效")
                .isInstanceOf(com.example.configmgr.common.ResourceNotFoundException.class);

        Task selected = taskService.selectDefs(task.getId(), List.of(SYS_PARAM, ROLE_DICT, ALARM_THRESHOLD));
        assertThat(selected.getItems()).extracting(TaskItem::getDefCode)
                .containsExactly(SYS_PARAM, ROLE_DICT, ALARM_THRESHOLD);

        String json = conditionJson("paramKey", "CONTAINS", "param.2");
        taskService.setCondition(task.getId(), SYS_PARAM, json);
        TaskItem item = taskItemRepository.findByTaskIdAndDefCode(task.getId(), SYS_PARAM).orElseThrow();
        assertThat(item.getConditionJson()).isEqualTo(json);
        assertThat(item.getStatus()).as("设条件后条目进入 READY").isEqualTo("READY");
    }

    // ── 用例 2：带条件导出（同步执行），行数与文件内容都按条件过滤 ──

    @Test
    void exportJobFiltersRowsByConditionAndWritesFiles() throws Exception {
        Task task = taskService.create(Task.TaskType.EXPORT, "S5A-导出-条件过滤");
        taskService.selectDefs(task.getId(), List.of(SYS_PARAM, ALARM_THRESHOLD, ROLE_DICT));
        taskService.setCondition(task.getId(), SYS_PARAM, conditionJson("paramKey", "CONTAINS", "param.2"));
        taskService.setCondition(task.getId(), ALARM_THRESHOLD, conditionJson("warnThreshold", "GT", "100"));
        // ROLE_DICT 不设条件 → 导出全部 8 行

        Job job = runExport(task.getId());

        assertThat(job.getStatus()).isEqualTo(Job.JobStatus.COMPLETED);
        assertThat(job.getErrorCount()).isZero();

        Task finished = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(finished.getStatus()).as("导出成功 → 任务完成").isEqualTo(Task.TaskStatus.COMPLETED);
        assertThat(finished.getCurrentStep()).isEqualTo("EXPORT");

        // param.2* 前缀：param.2、param.20~29 = 11 行
        assertThat(rowCount(task.getId(), SYS_PARAM)).isEqualTo(11);
        // GT 100：warnThreshold 种子 50~537.5（每 40 行一轮，共 3 轮），>100 者 35×3
        assertThat(rowCount(task.getId(), ALARM_THRESHOLD)).isEqualTo(105);
        assertThat(rowCount(task.getId(), ROLE_DICT)).isEqualTo(8);

        List<Map<String, Object>> sysRows = readBack(task.getId(), SYS_PARAM);
        assertThat(sysRows).hasSize(11);
        assertThat(sysRows).allSatisfy(r ->
                assertThat(String.valueOf(r.get("paramKey"))).contains("param.2"));

        List<Map<String, Object>> alarmRows = readBack(task.getId(), ALARM_THRESHOLD);
        assertThat(alarmRows).isNotEmpty().hasSize(105);
        assertThat(alarmRows).allSatisfy(r ->
                assertThat(new BigDecimal(String.valueOf(r.get("warnThreshold"))))
                        .isGreaterThan(new BigDecimal("100")));

        assertThat(readBack(task.getId(), ROLE_DICT)).hasSize(8);
    }

    // ── 用例 3：任务/作业终态后拒绝进一步操作 ──

    @Test
    void completedTaskRejectsJobCancel() {
        Task task = taskService.create(Task.TaskType.EXPORT, "S5A-导出-终态拒绝");
        taskService.selectDefs(task.getId(), List.of(ROLE_DICT));
        Job job = runExport(task.getId());

        assertThat(taskRepository.findById(task.getId()).orElseThrow().getStatus())
                .isEqualTo(Task.TaskStatus.COMPLETED);

        // glm：assertNotTerminal(终态任务) 应失败；本仓库的等价守卫是终态作业取消 → 409 JOB_ALREADY_FINAL
        ConflictException conflict = catchThrowableOfType(
                () -> jobService.cancel(job.getId(), "s5a-test"), ConflictException.class);
        assertThat(conflict).isNotNull();
        assertThat(conflict.getCode()).isEqualTo("JOB_ALREADY_FINAL");
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus())
                .as("终态作业不得被改成 CANCELLED")
                .isEqualTo(Job.JobStatus.COMPLETED);
    }

    // ───────────────────────── helpers ─────────────────────────

    /** 同步执行一次导出作业（作业执行器直接驱动，与 PublishSemanticsTest 同手法）。 */
    private Job runExport(Long taskId) {
        Job job = new Job();
        job.setTaskId(taskId);
        job.setJobType(Job.JobType.EXPORT);
        job.setStatus(Job.JobStatus.PENDING);
        job = jobRepository.save(job);
        exportJobRunner.run(job);
        return jobRepository.findById(job.getId()).orElseThrow();
    }

    private long rowCount(Long taskId, String defCode) {
        return taskFileRepository.findByTaskIdAndDefCodeAndFileType(taskId, defCode, "EXPORT")
                .orElseThrow(() -> new AssertionError("导出文件未落库: " + defCode))
                .getRowCount();
    }

    /** 用生产侧 {@link ExcelReader} 回读导出文件——校验的是真落盘的内容，而不是内存对象。 */
    private List<Map<String, Object>> readBack(Long taskId, String defCode) {
        try {
            var file = taskFileRepository.findByTaskIdAndDefCodeAndFileType(taskId, defCode, "EXPORT").orElseThrow();
            byte[] bytes = fileStorage.read(file.getStoragePath());
            return excelReader.read(definitionService.findByCode(defCode), bytes);
        } catch (Exception e) {
            throw new IllegalStateException("回读导出文件失败: " + defCode, e);
        }
    }

    private String conditionJson(String fieldCode, String operator, String value) {
        return "{\"fields\":[{\"fieldCode\":\"" + fieldCode + "\",\"operator\":\"" + operator
                + "\",\"value\":\"" + value + "\"}]}";
    }
}
