package com.example.configmgr.job.service;

import com.example.configmgr.common.ConflictException;
import com.example.configmgr.data.entity.ConfigDataRow;
import com.example.configmgr.data.repo.ConfigDataRowRepository;
import com.example.configmgr.data.repo.ConfigStagingRowRepository;
import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.service.DefinitionService;
import com.example.configmgr.definition.service.DependencyResolver;
import com.example.configmgr.excel.ExcelWriter;
import com.example.configmgr.file.FileStorageService;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.entity.ValidationIssue;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.job.repo.ValidationIssueRepository;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskItem;
import com.example.configmgr.task.entity.TaskFile;
import com.example.configmgr.task.repo.TaskFileRepository;
import com.example.configmgr.task.repo.TaskItemRepository;
import com.example.configmgr.task.repo.TaskRepository;
import com.example.configmgr.task.service.TaskService;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S5.a 用例（移植自 glm-5.3 {@code ImportFlowTest}）：导入全流程 ——
 * 建任务 → 上传文件 → 预检查（含依赖）→ 导入（写暂存）→ 发布（{@code upsert + 范围差集删除}）。
 *
 * <p>glm 侧的被测面是 {@code ImportRunner#runCheck/runImport/runPublish}；本仓库同职责的实现是
 * {@link PrecheckJobRunner} / {@link ImportJobRunner} / {@link PublishJobRunner}，
 * 由 {@link AsyncJobExecutor} 在异步通道里驱动；此处直接同步调用执行器（与
 * {@code PublishSemanticsTest} 同一手法），保证断言面对的是同步完成后的库状态。
 *
 * <h2>发布语义改写（本类最重要的一处）</h2>
 * glm 的发布是"<b>范围内删表重建</b>"（断言"发布后 SYS_PARAM 行数 = 3"这类全量替换行为，并隐含行身份变更）；
 * 本仓库 ADR-7 / Q14 定案的发布语义是"<b>行级 upsert + 范围差集删除</b>"。故用例 5 的断言按新语义重写：
 * <ul>
 * <li>本次导入<b>覆盖到</b>的业务键行：<b>保留行 id</b>、版本递增、字段值更新（非删建）——用例 5 的①②；</li>
 * <li>覆盖范围内、本次导入<b>未出现</b>的旧行：删除（差集删除）——用例 5 的②；</li>
 * <li>未被本次导入覆盖的范围（其他地区）：行 id 与内容<b>零变化</b>——用例 5 的③；</li>
 * <li>发布动作按任务导入模式执行：{@code REPLACE} 才做差集删除，{@code MERGE} 为纯增量 upsert
 *     （{@code AiToolsInvocationTest} 的导入工具链用例覆盖 MERGE 一侧）。</li>
 * </ul>
 *
 * <h2>其他语义改写点</h2>
 * <ul>
 * <li>上传/提交数据：glm 走任务参数包 {@code params.uploads}（内存行），本仓库走
 *     {@code task_files(UPLOAD)} 指向的 xlsx → 用例用生产侧 {@link ExcelWriter} 真写文件、
 *     真落 task_files，再由 {@link com.example.configmgr.excel.ExcelReader} 在读侧解析；</li>
 * <li>预检查规则：glm 覆盖"必填 / 枚举合法 / 引用依赖 / 范围合法"，本仓库
 *     {@link PrecheckJobRunner} 实际覆盖"必填 / 主键重复 / 引用存在性"（无枚举合法性与范围合法性校验），
 *     故用例 2 的三类规则改为 必填 + 引用 + 主键重复；</li>
 * <li>"校验未通过则拒绝导入"曾因实现里没有守卫而未移植：<b>M1 收尾守卫① 后已补齐</b> ——
 *     守卫在作业入口 {@link JobService#createAndStart}（IMPORT/PUBLISH 前必须有 COMPLETED 且
 *     errorCount==0 的 PRECHECK），用例 2 末尾断言该拒绝；正/反例全集见 {@code PrecheckGateGuardTest}。</li>
 * </ul>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:s5a-import-flow;DB_CLOSE_DELAY=-1",
        "app.job.demo-batch-delay-ms=0",
        "app.file.storage-path=./target/s5a-test-files"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ImportFlowJobTest {

    private static final String SYS_PARAM = "SYS_PARAM";
    private static final String ALARM_THRESHOLD = "ALARM_THRESHOLD";
    private static final String REGION_NETWORK = "REGION_NETWORK";
    private static final String ROLE_DICT = "ROLE_DICT";
    private static final String METRIC_DICT = "METRIC_DICT";
    private static final String PROJECT_MEMBER = "PROJECT_MEMBER";

    /** 用例 1~5 共用同一条导入任务（顺序执行），后半段用例各自新建任务。 */
    private static Long flowTaskId;

    @Autowired
    private TaskService taskService;
    @Autowired
    private JobService jobService;
    @Autowired
    private TaskRepository taskRepository;
    @Autowired
    private TaskItemRepository taskItemRepository;
    @Autowired
    private TaskFileRepository taskFileRepository;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private ValidationIssueRepository issueRepository;
    @Autowired
    private ConfigDataRowRepository dataRowRepository;
    @Autowired
    private ConfigStagingRowRepository stagingRowRepository;
    @Autowired
    private DefinitionService definitionService;
    @Autowired
    private DependencyResolver dependencyResolver;
    @Autowired
    private ExcelWriter excelWriter;
    @Autowired
    private FileStorageService fileStorage;
    @Autowired
    private PrecheckJobRunner precheckJobRunner;
    @Autowired
    private ImportJobRunner importJobRunner;
    @Autowired
    private PublishJobRunner publishJobRunner;

    // ── 用例 1：建导入任务、选 3 个配置项、上传 3 份带缺陷的文件 ──

    @Test
    @Order(1)
    void createAndPrepareUploads() throws Exception {
        Task task = taskService.create(Task.TaskType.IMPORT, "S5A-导入全流程");
        flowTaskId = task.getId();
        taskService.selectDefs(flowTaskId, List.of(SYS_PARAM, ALARM_THRESHOLD, REGION_NETWORK));

        // SYS_PARAM：第 1 行缺必填 paramValue
        upload(flowTaskId, SYS_PARAM, List.of(
                row("paramKey", "param.1", "paramType", "STRING", "editable", "true"),
                row("paramKey", "new.param.1", "paramValue", "100", "paramType", "STRING", "editable", "true")));

        // ALARM_THRESHOLD：第 2 行引用不存在的指标
        upload(flowTaskId, ALARM_THRESHOLD, List.of(
                alarmRow("阈值规则-001", "metric.001", "90", "95"),
                alarmRow("S5A-坏引用", "metric.999", "80", "85")));

        // REGION_NETWORK：两行业务键重复（regionCode + bandwidthMbps）
        upload(flowTaskId, REGION_NETWORK, List.of(
                row("regionCode", "HE", "bandwidthMbps", "100", "gateway", "10.9.9.9", "redundancy", "true"),
                row("regionCode", "HE", "bandwidthMbps", "100", "gateway", "10.9.9.8", "redundancy", "true")));

        assertThat(taskFileRepository.findByTaskId(flowTaskId)).hasSize(3);
        assertThat(taskItemRepository.findByTaskIdOrderBySortOrder(flowTaskId))
                .extracting(TaskItem::getDefCode)
                .containsExactly(SYS_PARAM, ALARM_THRESHOLD, REGION_NETWORK);
    }

    // ── 用例 2：预检查检出三类规则（必填缺失 / 引用不存在 / 主键重复）──

    @Test
    @Order(2)
    void checkDetectsAllRuleTypes() {
        Job job = run(Job.JobType.PRECHECK, flowTaskId);

        assertThat(job.getStatus()).isEqualTo(Job.JobStatus.FAILED);
        assertThat(job.getErrorCount()).as("三个配置项各 1 处错误").isEqualTo(3);

        assertThat(messages(job.getId(), SYS_PARAM))
                .anyMatch(m -> m.contains("必填字段"));
        assertThat(messages(job.getId(), ALARM_THRESHOLD))
                .anyMatch(m -> m.contains("不存在"));
        assertThat(messages(job.getId(), REGION_NETWORK))
                .anyMatch(m -> m.contains("主键重复"));

        assertThat(taskItemRepository.findByTaskIdOrderBySortOrder(flowTaskId))
                .allSatisfy(i -> assertThat(i.getStatus()).isEqualTo("FAILED"));

        // M1 收尾守卫①（补齐 glm 的 importRejectedWhileCheckHasError）：预检查未通过时，
        // 作业入口（JobService#createAndStart）直接拒绝导入 —— 原实现无此守卫，故当时未移植。
        // 守卫的正/反例全集见 PrecheckGateGuardTest（本类直接驱动执行器，绕过入口守卫）。
        assertThatThrownBy(() -> jobService.createAndStart(flowTaskId, Job.JobType.IMPORT))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).getCode()).isEqualTo("PRECHECK_NOT_PASSED"));
        assertThat(jobRepository.findByTaskId(flowTaskId))
                .as("被守卫拒绝时不落导入作业行").hasSize(1);
    }

    // ── 用例 3：修数据后重跑预检查 → 通过 ──

    @Test
    @Order(3)
    void fixDataThenCheckPass() throws Exception {
        upload(flowTaskId, SYS_PARAM, List.of(
                row("paramKey", "param.1", "paramValue", "已更新", "paramType", "STRING", "editable", "true"),
                row("paramKey", "new.param.1", "paramValue", "100", "paramType", "STRING", "editable", "true"),
                row("paramKey", "new.param.2", "paramValue", "200", "paramType", "NUMBER", "editable", "false")));
        upload(flowTaskId, ALARM_THRESHOLD, List.of(
                alarmRow("阈值规则-001", "metric.001", "90", "95"),
                alarmRow("S5A-新阈值", "metric.002", "80", "85")));
        upload(flowTaskId, REGION_NETWORK, List.of(
                row("regionCode", "HE", "bandwidthMbps", "100", "gateway", "10.9.9.9", "redundancy", "true")));

        Job job = run(Job.JobType.PRECHECK, flowTaskId);

        assertThat(job.getStatus()).isEqualTo(Job.JobStatus.COMPLETED);
        assertThat(job.getErrorCount()).isZero();
        assertThat(issueRepository.findByJobIdAndDefCode(job.getId(), SYS_PARAM)).isEmpty();
        assertThat(taskItemRepository.findByTaskIdOrderBySortOrder(flowTaskId))
                .allSatisfy(i -> assertThat(i.getStatus()).isEqualTo("CHECKED"));
    }

    // ── 用例 4：导入只写暂存，不动已发布数据 ──

    @Test
    @Order(4)
    void importStagesDataWithoutTouchingPublished() {
        long publishedSys = dataRowRepository.countByDefCode(SYS_PARAM);
        long publishedNet = dataRowRepository.countByDefCode(REGION_NETWORK);

        Job job = run(Job.JobType.IMPORT, flowTaskId);

        assertThat(job.getStatus()).isEqualTo(Job.JobStatus.COMPLETED);
        assertThat(dataRowRepository.countByDefCode(SYS_PARAM)).isEqualTo(publishedSys);
        assertThat(dataRowRepository.countByDefCode(REGION_NETWORK)).isEqualTo(publishedNet);

        assertThat(stagingRowRepository.findByTaskIdAndDefCode(flowTaskId, SYS_PARAM)).hasSize(3)
                .allSatisfy(sr -> assertThat(sr.getStatus()).isEqualTo("STAGED"));
        assertThat(stagingRowRepository.findByTaskIdAndDefCode(flowTaskId, ALARM_THRESHOLD)).hasSize(2);
        assertThat(stagingRowRepository.findByTaskIdAndDefCode(flowTaskId, REGION_NETWORK)).hasSize(1);
    }

    // ── 用例 5（核心）：REPLACE 发布 = 行级 upsert（保 id 递增版本）+ 范围差集删除 ──

    @Test
    @Order(5)
    void publishUpsertsKeepsRowIdsAndDeletesOutOfRangeRows() {
        ConfigDataRow sysBefore = published(SYS_PARAM, "GLOBAL", null, "param.1");
        ConfigDataRow alarmBefore = published(ALARM_THRESHOLD, "GLOBAL", null, "阈值规则-001");
        ConfigDataRow netHeBefore = published(REGION_NETWORK, "REGION", "HE", "HE|100");
        ConfigDataRow netXnBefore = published(REGION_NETWORK, "REGION", "XN", "XN|100");
        String netXnJsonBefore = netXnBefore.getDataJson();
        long netXnRowsBefore = dataRowRepository.findRowsInScope(REGION_NETWORK, "REGION", "XN").size();

        taskService.setImportMode(flowTaskId, "REPLACE");
        Job job = run(Job.JobType.PUBLISH, flowTaskId);
        assertThat(job.getStatus()).isEqualTo(Job.JobStatus.COMPLETED);

        // ① 覆盖到的业务键：保留行 id、版本递增、字段值更新（行级 upsert，非删建）
        ConfigDataRow sysAfter = published(SYS_PARAM, "GLOBAL", null, "param.1");
        assertThat(sysAfter.getId()).as("已存在行必须保留行 id（非删建）").isEqualTo(sysBefore.getId());
        assertThat(sysAfter.getVersion()).as("版本必须递增（并发冲突检测依赖它）")
                .isGreaterThan(sysBefore.getVersion());
        assertThat(sysAfter.getDataJson()).contains("已更新");

        ConfigDataRow alarmAfter = published(ALARM_THRESHOLD, "GLOBAL", null, "阈值规则-001");
        assertThat(alarmAfter.getId()).isEqualTo(alarmBefore.getId());
        ConfigDataRow netHeAfter = published(REGION_NETWORK, "REGION", "HE", "HE|100");
        assertThat(netHeAfter.getId()).as("地区级行同样保 id").isEqualTo(netHeBefore.getId());
        assertThat(netHeAfter.getDataJson()).contains("10.9.9.9");

        // ② 范围差集删除：覆盖范围内、本次导入未出现的旧行删除
        assertThat(dataRowRepository.countByDefCode(SYS_PARAM))
                .as("GLOBAL：40 旧行中 1 行被 upsert 保留、2 行为新增，其余 37 行差集删除")
                .isEqualTo(3);
        assertThat(dataRowRepository.findRow(SYS_PARAM, "GLOBAL", null, "param.2"))
                .as("范围内未出现的旧行应被删除").isEmpty();
        assertThat(dataRowRepository.countByDefCode(ALARM_THRESHOLD))
                .as("ALARM_THRESHOLD：1 行保留 + 1 行新增").isEqualTo(2);
        assertThat(dataRowRepository.findRow(ALARM_THRESHOLD, "GLOBAL", null, "阈值规则-002")).isEmpty();
        assertThat(dataRowRepository.findRowsInScope(REGION_NETWORK, "REGION", "HE"))
                .as("HE 地区覆盖范围内未出现的旧行（HE|150、HE|200）应被删除")
                .hasSize(1)
                .extracting(ConfigDataRow::getRowKey).containsExactly("HE|100");

        // ③ 未覆盖范围零影响：别地区的行 id 与内容都不动
        assertThat(dataRowRepository.findRowsInScope(REGION_NETWORK, "REGION", "XN"))
                .as("未被本次导入覆盖的地区行数不变").hasSize((int) netXnRowsBefore);
        ConfigDataRow netXnAfter = published(REGION_NETWORK, "REGION", "XN", "XN|100");
        assertThat(netXnAfter.getId()).isEqualTo(netXnBefore.getId());
        assertThat(netXnAfter.getDataJson()).isEqualTo(netXnJsonBefore);

        // ④ 暂存行提升为 PUBLISHED（发布成功的行不再停留在 STAGED）
        assertThat(stagingRowRepository.findByTaskIdAndDefCode(flowTaskId, SYS_PARAM))
                .hasSize(3)
                .allSatisfy(sr -> assertThat(sr.getStatus()).isEqualTo("PUBLISHED"));

        // ⑤ 任务终态与最终步骤
        Task finished = taskRepository.findById(flowTaskId).orElseThrow();
        assertThat(finished.getStatus()).isEqualTo(Task.TaskStatus.COMPLETED);
        assertThat(finished.getCurrentStep()).isEqualTo("PUBLISH");
    }

    // ── 用例 6：依赖先序（拓扑排序）──

    @Test
    @Order(6)
    void dependentsImportedAfterDependency() {
        List<String> order = dependencyResolver.sort(
                List.of(ALARM_THRESHOLD, METRIC_DICT, SYS_PARAM, PROJECT_MEMBER, ROLE_DICT));

        assertThat(order).containsExactlyInAnyOrder(ALARM_THRESHOLD, METRIC_DICT, SYS_PARAM, PROJECT_MEMBER, ROLE_DICT);
        assertThat(order.indexOf(METRIC_DICT)).isLessThan(order.indexOf(ALARM_THRESHOLD));
        assertThat(order.indexOf(ROLE_DICT)).isLessThan(order.indexOf(PROJECT_MEMBER));
    }

    // ── 用例 7：业务键重复被检出 ──

    @Test
    @Order(7)
    void duplicateKeyDetected() throws Exception {
        Task task = taskService.create(Task.TaskType.IMPORT, "S5A-导入-重复主键");
        taskService.selectDefs(task.getId(), List.of(ROLE_DICT));
        upload(task.getId(), ROLE_DICT, List.of(
                roleRow("R1", "角色一", "1", "true"),
                roleRow("R1", "角色二", "2", "false")));

        Job job = run(Job.JobType.PRECHECK, task.getId());

        assertThat(job.getStatus()).isEqualTo(Job.JobStatus.FAILED);
        assertThat(messages(job.getId(), ROLE_DICT)).anyMatch(m -> m.contains("主键重复"));
    }

    // ── 用例 8：空文件不产生错误（glm 另有"产生警告"的断言，本仓库预检查无警告语义）──

    @Test
    @Order(8)
    void emptyUploadProducesNoError() throws Exception {
        Task task = taskService.create(Task.TaskType.IMPORT, "S5A-导入-空文件");
        taskService.selectDefs(task.getId(), List.of(SYS_PARAM));
        upload(task.getId(), SYS_PARAM, List.of());

        Job job = run(Job.JobType.PRECHECK, task.getId());

        assertThat(job.getStatus()).isEqualTo(Job.JobStatus.COMPLETED);
        assertThat(job.getErrorCount()).isZero();
        assertThat(issueRepository.findByJobIdAndDefCode(job.getId(), SYS_PARAM)).isEmpty();
    }

    // ── 用例 9：必填字段缺失被检出 ──

    @Test
    @Order(9)
    void missingRequiredFieldDetected() throws Exception {
        Task task = taskService.create(Task.TaskType.IMPORT, "S5A-导入-必填缺失");
        taskService.selectDefs(task.getId(), List.of(SYS_PARAM));
        // paramValue 必填但缺失
        upload(task.getId(), SYS_PARAM, List.of(
                row("paramKey", "x.1", "paramType", "STRING", "editable", "true")));

        Job job = run(Job.JobType.PRECHECK, task.getId());

        assertThat(job.getStatus()).isEqualTo(Job.JobStatus.FAILED);
        assertThat(messages(job.getId(), SYS_PARAM))
                .anyMatch(m -> m.contains("必填字段") && m.contains("参数值"));
    }

    // ───────────────────────── helpers ─────────────────────────

    /**
     * 上传件写入：真写一份 xlsx（生产 {@link ExcelWriter}）+ 真落 {@code task_files(UPLOAD)}，
     * 等价于前端上传通道（{@code FileController#upload}）。
     */
    private void upload(Long taskId, String defCode, List<Map<String, Object>> rows) throws Exception {
        ConfigDefinition def = definitionService.findByCode(defCode);
        String path = fileStorage.newPath(".xlsx");
        excelWriter.write(def, rows, path);

        TaskFile file = taskFileRepository
                .findByTaskIdAndDefCodeAndFileType(taskId, defCode, "UPLOAD")
                .orElseGet(TaskFile::new);
        file.setTaskId(taskId);
        file.setDefCode(defCode);
        file.setFileType("UPLOAD");
        file.setStoragePath(path);
        file.setOriginalPath(path);
        file.setFileName(defCode + ".xlsx");
        file.setRowCount(rows.size());
        taskFileRepository.save(file);
    }

    /** 同步执行一个作业（执行器直接驱动），返回库里的终态作业。 */
    private Job run(Job.JobType type, Long taskId) {
        Job job = new Job();
        job.setTaskId(taskId);
        job.setJobType(type);
        job.setStatus(Job.JobStatus.PENDING);
        job = jobRepository.save(job);
        switch (type) {
            case PRECHECK -> precheckJobRunner.run(job);
            case IMPORT -> importJobRunner.run(job);
            case PUBLISH -> publishJobRunner.run(job);
            default -> throw new IllegalArgumentException("本用例不驱动 " + type);
        }
        return jobRepository.findById(job.getId()).orElseThrow();
    }

    private List<String> messages(Long jobId, String defCode) {
        return issueRepository.findByJobIdAndDefCode(jobId, defCode).stream()
                .map(ValidationIssue::getMessage)
                .toList();
    }

    private ConfigDataRow published(String defCode, String scopeType, String scopeKey, String rowKey) {
        return dataRowRepository.findRow(defCode, scopeType, scopeKey, rowKey)
                .orElseThrow(() -> new AssertionError("已发布行不存在: " + defCode + "/" + rowKey));
    }

    private Map<String, Object> row(String... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private Map<String, Object> alarmRow(String name, String metricCode, String warn, String critical) {
        return row("thresholdName", name, "metricCode", metricCode, "warnThreshold", warn,
                "criticalThreshold", critical, "effectiveDate", "2026-06-01");
    }

    private Map<String, Object> roleRow(String code, String name, String level, String builtin) {
        return row("roleCode", code, "roleName", name, "permissionLevel", level, "builtin", builtin);
    }
}
