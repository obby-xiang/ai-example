package com.example.configmgr.ai.tools;

import com.example.configmgr.common.ConflictException;
import com.example.configmgr.common.ResourceNotFoundException;
import com.example.configmgr.data.repo.ConfigDataRowRepository;
import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.service.DefinitionService;
import com.example.configmgr.excel.ExcelWriter;
import com.example.configmgr.file.FileStorageService;
import com.example.configmgr.file.StoredFile;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskFile;
import com.example.configmgr.task.repo.TaskFileRepository;
import com.example.configmgr.task.repo.TaskRepository;
import com.example.configmgr.task.service.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S5.a 用例（移植自 glm-5.3 {@code AiToolsTest}）：AI 工具<b>直调</b>（不经过模型），
 * 验证工具行为、作业发起与真实数据面联动。
 *
 * <p>glm 侧的工具集（{@code BasicAiTools/ExportAiTools/ImportAiTools}）与本仓库的
 * {@link AiTools} 是两套工具面：本仓库只保留名称/语义能对上的部分，且工具实现与 REST 走同一服务层
 * （FR-5.4 单一事实源），故这里断言的是"调用工具后库里/盘上真的发生了什么"。
 *
 * <p><b>语义改写点</b>：
 * <ul>
 * <li>{@code list_config_items} → {@link AiTools#listConfigDefs}（返回面向模型的文本，非结构化列表）；</li>
 * <li>{@code get_current_state} → {@link AiTools#getWorkspaceState}；glm 的"无活动任务必须抛
 *     BizException(没有进行中的任务)"在本仓库不存在（工具返回提示文本），断言改为提示文本；</li>
 * <li>{@code create_task} 自 AR-03 起是 DANGER 级确认门工具（宿主幂等 + 单轮上限 1）；
 *     <b>直调</b>本用例绕开确认卡，只验"批准后的创建语义"与幂等 → 用例 3；</li>
 * <li>{@code set_selected_configs} / {@code submit_config_data} 是前端通道的哨兵桩
 *     （选择与上传的副作用在浏览器）→ 用例用 {@code TaskService} 与真实上传件准备前置状态，
 *     但<b>作业发起一律走 AI 工具</b>，从而覆盖"工具 → 作业 → 数据面"这条链路；</li>
 * <li>glm 的步骤守卫（CHECK 步不许发布）在本仓库不存在，未移植（见证据文档【待裁决】）。</li>
 * </ul>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:s5a-ai-tools;DB_CLOSE_DELAY=-1",
        "app.job.demo-batch-delay-ms=0",
        "app.file.storage-path=./target/s5a-test-files"
})
class AiToolsInvocationTest {

    private static final String SYS_PARAM = "SYS_PARAM";
    private static final String ROLE_DICT = "ROLE_DICT";

    /** 作业异步执行 + 数据面写库的等待上限（demo 延迟已置 0，正常 1s 内完成）。 */
    private static final long JOB_WAIT_MS = 30_000;

    private static ToolContext ctx(String runId) {
        return new ToolContext(Map.of("runId", runId));
    }

    @Autowired
    private AiTools aiTools;
    @Autowired
    private TaskService taskService;
    @Autowired
    private TaskRepository taskRepository;
    @Autowired
    private TaskFileRepository taskFileRepository;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private ConfigDataRowRepository dataRowRepository;
    @Autowired
    private DefinitionService definitionService;
    @Autowired
    private ExcelWriter excelWriter;
    @Autowired
    private FileStorageService fileStorage;

    // ── 用例 1：目录类只读工具 ──

    @Test
    void listConfigDefsReturnsGlmCatalog() {
        String all = aiTools.listConfigDefs(null);
        for (String code : List.of("SYS_PARAM", "METRIC_DICT", "ALARM_THRESHOLD", "ROLE_DICT",
                "REGION_NETWORK", "REGION_TARIFF", "PROJECT_MEMBER", "PROJECT_ENV")) {
            assertThat(all).contains(code);
        }

        assertThat(aiTools.listConfigDefs("GLOBAL")).contains(SYS_PARAM);
        assertThat(aiTools.listConfigDefs("REGION")).contains("REGION_NETWORK").doesNotContain(SYS_PARAM);

        String detail = aiTools.getConfigDef(SYS_PARAM);
        assertThat(detail).contains("配置定义: " + SYS_PARAM).contains("paramKey");
    }

    // ── 用例 2：工作区快照（无任务给提示，有任务给状态）──

    @Test
    void workspaceStateHintsWhenNoTask() {
        assertThat(aiTools.getWorkspaceState(null)).contains("未打开任何任务");
        // P0-1：不存在的任务不再吞成字符串 —— 抛带 RESOURCE_NOT_FOUND 的异常，由信封如实呈给模型
        assertThatThrownBy(() -> aiTools.getWorkspaceState(999_999L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("不存在");

        Task task = taskService.create(Task.TaskType.EXPORT, "S5A-AI-工作区");
        taskService.selectDefs(task.getId(), List.of(SYS_PARAM));

        assertThat(aiTools.getWorkspaceState(task.getId()))
                .contains("S5A-AI-工作区")
                .contains("SELECT_DEFS")
                .contains(SYS_PARAM);
    }

    // ── 用例 3：create_task 创建即落库，并给出下一个动作 ──

    @Test
    void createTaskFromAiPersistsTaskAndReportsNextAction() {
        String text = aiTools.createTask("EXPORT", null, ctx("s5a-create-1"));

        assertThat(text).contains("已创建导出任务").contains("SELECT_DEFS").contains("navigate_to");
        long taskId = firstId(text);
        Task task = taskRepository.findById(taskId).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(Task.TaskStatus.ACTIVE);
        assertThat(task.getTitle()).as("缺省标题按类型生成").isEqualTo("导出任务");
        assertThat(task.getCurrentStep()).isEqualTo("SELECT_DEFS");

        String importText = aiTools.createTask("IMPORT", "S5A-AI-导入任务", ctx("s5a-create-2"));
        assertThat(importText).contains("导入任务").contains("UPLOAD");
        assertThat(taskRepository.findById(firstId(importText)).orElseThrow().getCurrentStep())
                .isEqualTo("UPLOAD");

        // 类型非法 → 抛带 ILLEGAL_ARGUMENT 码的业务异常（由统一信封回填，不再伪造成功文本）
        assertThatThrownBy(() -> aiTools.createTask("NOPE", null, ctx("s5a-create-3")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("任务类型不合法");
    }

    @Test
    void createTaskHostIdempotencyAndSingleTurnLimit() {
        String first = aiTools.createTask("EXPORT", "同轮幂等任务", ctx("run-idem-1"));
        long firstId = firstId(first);

        String repeated = aiTools.createTask("EXPORT", "同轮幂等任务", ctx("run-idem-1"));
        assertThat(firstId(repeated)).as("同轮同键复用，绝不二次创建").isEqualTo(firstId);
        assertThat(repeated).contains("复用");

        assertThatThrownBy(() -> aiTools.createTask("EXPORT", "同轮另一个标题", ctx("run-idem-1")))
            .as("同轮不同键 = 明确冲突，不是静默丢弃")
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("同一轮只允许创建一次");

        String nextTurn = aiTools.createTask("EXPORT", "同轮幂等任务", ctx("run-idem-2"));
        assertThat(firstId(nextTurn)).as("新一轮可再创建（幂等按轮作用域）").isNotEqualTo(firstId);
    }

    // ── 用例 4：导出工具链 → 真起作业 → 真落导出文件 ──

    @Test
    void exportToolChainRunsRealJobAndWritesFile() throws Exception {
        long taskId = firstId(aiTools.createTask("EXPORT", "S5A-AI-导出", ctx("s5a-create-4")));
        taskService.selectDefs(taskId, List.of(SYS_PARAM));
        taskService.setCondition(taskId, SYS_PARAM,
                "{\"fields\":[{\"fieldCode\":\"paramKey\",\"operator\":\"CONTAINS\",\"value\":\"param.2\"}]}");

        assertThat(aiTools.startExport(taskId)).contains("导出作业已启动");

        Job job = awaitJob(taskId, Job.JobType.EXPORT);
        assertThat(job.getStatus()).isEqualTo(Job.JobStatus.COMPLETED);
        assertThat(aiTools.checkJobStatus(job.getId())).contains("COMPLETED");

        // param.2、param.20~29 = 11 行；导出文件由作业真落盘
        TaskFile file = taskFileRepository
                .findByTaskIdAndDefCodeAndFileType(taskId, SYS_PARAM, "EXPORT").orElseThrow();
        assertThat(file.getRowCount()).isEqualTo(11);
        assertThat(fileStorage.read(file.getStoragePath())).isNotEmpty();
    }

    // ── 用例 5：导入工具链（预检查 → 导入 → 发布）；默认 MERGE = 纯增量 upsert ──

    @Test
    void importToolChainRunsPrecheckImportAndPublish() throws Exception {
        long taskId = firstId(aiTools.createTask("IMPORT", "S5A-AI-导入", ctx("s5a-create-5")));
        taskService.selectDefs(taskId, List.of(ROLE_DICT));
        upload(taskId, ROLE_DICT, List.of(
                row("roleCode", "NEW_R", "roleName", "新角色", "permissionLevel", "3", "builtin", "false")));
        long publishedBefore = dataRowRepository.countByDefCode(ROLE_DICT);

        assertThat(aiTools.startPrecheck(taskId)).contains("预检查已启动");
        assertThat(awaitJob(taskId, Job.JobType.PRECHECK).getStatus()).isEqualTo(Job.JobStatus.COMPLETED);

        assertThat(aiTools.startImport(taskId)).contains("导入作业已启动");
        assertThat(awaitJob(taskId, Job.JobType.IMPORT).getStatus()).isEqualTo(Job.JobStatus.COMPLETED);
        assertThat(dataRowRepository.countByDefCode(ROLE_DICT))
                .as("导入只写暂存，已发布数据不动").isEqualTo(publishedBefore);

        assertThat(aiTools.startPublish(taskId)).contains("发布作业已启动");
        assertThat(awaitJob(taskId, Job.JobType.PUBLISH).getStatus()).isEqualTo(Job.JobStatus.COMPLETED);

        // 默认导入模式是 MERGE：纯增量 upsert（不做差集删除），已发布行只增一行
        assertThat(dataRowRepository.countByDefCode(ROLE_DICT)).isEqualTo(publishedBefore + 1);
        assertThat(dataRowRepository.findRow(ROLE_DICT, "GLOBAL", null, "NEW_R")).isPresent();

        assertThat(aiTools.getWorkspaceState(taskId)).contains("COMPLETED").contains("PUBLISH");
    }

    // ── 用例 6：未知配置编码如实回报 ──

    @Test
    void unknownConfigCodeIsReportedAsNotFound() {
        // P0-1：get_config_def / get_row_count 对不存在的编码抛带码异常（不再返回"未找到"/"0 行"）
        assertThatThrownBy(() -> aiTools.getConfigDef("NO_SUCH_DEF"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("不存在: NO_SUCH_DEF");
        assertThatThrownBy(() -> aiTools.getRowCount("NO_SUCH_DEF", null, null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("不存在: NO_SUCH_DEF");
        assertThatThrownBy(() -> aiTools.checkJobStatus(999_999L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("不存在: 999999");
    }

    // ── 用例 7（M1 收尾守卫①的 AI 通道回归）：预检查未通过 → 工具如实回报失败，不起作业 ──
    // R-137（T6 重抛面）：start_import/start_publish 失败不再以字符串回填（那会让 ok=true
    // 与失败文案自相矛盾），改为抛出 ConflictException，由 SpToolCallingManager.executeOnce
    // 捕获置 ok=false 呈给模型（"工具执行失败：<文案>"）。直调断言从 contains 改为异常形态。

    @Test
    void startImportReportsGuardWhenPrecheckNotPassed() {
        long taskId = firstId(aiTools.createTask("IMPORT", "S5F-AI-守卫", ctx("s5a-create-6")));
        taskService.selectDefs(taskId, List.of(ROLE_DICT));

        assertThatThrownBy(() -> aiTools.startImport(taskId))
                .as("无预检查记录时，守卫异常必须带机器可读码（executeOnce 置 ok=false 呈给模型）")
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("PRECHECK_NOT_PASSED");
        assertThatThrownBy(() -> aiTools.startPublish(taskId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("PRECHECK_NOT_PASSED");
        assertThat(jobRepository.findByTaskId(taskId))
                .as("被守卫拒绝时不得落作业行").isEmpty();
    }

    // ───────────────────────── helpers ─────────────────────────

    /** 轮询等待指定类型的作业进入终态（作业由确认门放行后异步执行）。 */
    private Job awaitJob(long taskId, Job.JobType type) {
        long deadline = System.currentTimeMillis() + JOB_WAIT_MS;
        Job last = null;
        while (System.currentTimeMillis() < deadline) {
            last = jobRepository.findTopByTaskIdAndJobTypeOrderByCreatedAtDesc(taskId, type).orElse(null);
            if (last != null && (last.getStatus() == Job.JobStatus.COMPLETED
                    || last.getStatus() == Job.JobStatus.FAILED
                    || last.getStatus() == Job.JobStatus.CANCELLED)) {
                return last;
            }
            sleep();
        }
        throw new AssertionError("等待 " + type + " 作业终态超时，最后状态="
                + (last == null ? "无作业" : last.getStatus()));
    }

    private void sleep() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待作业被中断", e);
        }
    }

    private long firstId(String text) {
        Matcher m = Pattern.compile("#(\\d+)").matcher(text);
        if (!m.find()) {
            throw new AssertionError("工具返回文本里没有任务 id: " + text);
        }
        return Long.parseLong(m.group(1));
    }

    /** 上传件准备（前端上传通道的等价物）：真写 xlsx + 真落 task_files(UPLOAD)。 */
    private void upload(Long taskId, String defCode, List<Map<String, Object>> rows) throws Exception {
        ConfigDefinition def = definitionService.findByCode(defCode);
        StoredFile stored = fileStorage.allocate(".xlsx");
        excelWriter.write(def, rows, stored.pathString());

        TaskFile file = taskFileRepository
                .findByTaskIdAndDefCodeAndFileType(taskId, defCode, "UPLOAD")
                .orElseGet(TaskFile::new);
        file.setTaskId(taskId);
        file.setDefCode(defCode);
        file.setFileType("UPLOAD");
        file.setStoragePath(stored.key());
        file.setOriginalPath(stored.key());
        file.setFileName(defCode + ".xlsx");
        file.setRowCount(rows.size());
        taskFileRepository.save(file);
    }

    private Map<String, Object> row(String... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }
}
