package com.example.configmgr.ai.tools;

import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.FrontendToolGuard;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S4.4d 用例（S4.4e 扩充）：工具元数据与渐进披露（FR-5.2 三重防线的防线①）。
 *
 * <p>覆盖三类容易在改动中悄悄失守的事：
 * <ol>
 * <li><b>通道与风险（S4.4d；R3-02 + 工具安全边界批修订）</b>：4 个向导动作必须是 {@code FRONTEND} 通道
 * （后端只挂起、不执行副作用）；{@code confirm_step}（导入第 1 步 flush 写回服务端文件）、
 * {@code select_definitions}（勾选即落任务项）、{@code set_condition}（条件 PUT 落库）三个动作
 * 按事实标 {@code WRITE}，{@code navigate_to} 保持 {@code READ}（只改路由/任务绑定，可逆且无持久写）；
 * <b>不得</b>升级成 DANGER —— FRONTEND 通道优先，且这些动作的后果由下游 {@code start_*} 确认门兜底，
 * 多一道确认卡只会变成无意义弹窗（用户 2026-10-25 裁决）；</li>
 * <li><b>披露边界</b>：向导动作按<b>能成功的步骤</b>披露（2026-10-28 步骤披露收口：任务中心只披露
 * 入口动作，{@code select_definitions}/{@code set_condition}/{@code confirm_step} 按步骤收窄到
 * SELECT_DEFS-UPLOAD / QUERY_COND / 整个任务），非向导页面（配置定义/数据浏览）
 * 与空上下文不披露；同时锁住既有 BACKEND 工具的披露范围不回退（{@code start_import}/
 * {@code start_publish} 仍只在导入任务的对应步骤披露），并显式登记 S4.4d 放宽的两档
 * （{@code start_export}/{@code start_precheck} 增加 {@code page:tasks}）；</li>
 * <li><b>S4.4e + AR-03 三项</b>：{@code create_task} 的披露范围（任务中心 + 两类向导页，其余页面不披露）
 * 与风险（{@code DANGER}，进确认门）；启动类四个工具一律 {@code DANGER}（确认门，FR-5.3）；
 * 已完成 EXPORT 任务不再披露 {@code start_export}（终态收窄，引导改走下载）；
 * {@code set_condition} 的入参 Schema 里必须出现前端契约的内层结构
 * （{@code conditions.fields[].fieldCode/operator/value} + {@code conditions.scopeKeys}），
 * 且<b>不得</b>再出现扁平参数键（前端只读 {@code defCode/conditions}，扁平键会被静默丢弃）。</li>
 * </ol>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:s44d-tool-disclosure;DB_CLOSE_DELAY=-1"
})
class ToolRegistryDisclosureTest {

    /**
     * 受页面/任务类型约束的 3 个向导动作（{@code navigate_to} 是任意页面的导航入口，
     * 披露范围为 {@code *}，不参与"非向导页面不披露"的断言）。
     */
    private static final List<String> SCOPED_WIZARD_TOOLS = List.of("select_definitions", "set_condition",
            "confirm_step");

    /** 前端通道工具（2 个既有 + 4 个工作区动作 + DC-15 的 generative_form；仅 confirm_step = WRITE）。 */
    private static final List<String> FRONTEND_TOOLS = List.of("open_export_file_editor", "download_export_files",
            "navigate_to", "select_definitions", "set_condition", "confirm_step",
            com.example.configmgr.ai.form.GenerativeFormRules.TOOL_NAME);

    /** AI 表格操作专项的 9 个工具（读侧 5 / 写侧 3 + 撤销；normalize 双档同名）。 */
    private static final List<String> SHEET_READ_TOOLS = List.of("read_sheet_summary", "aggregate_sheet",
            "inspect_columns", "review_cells", "find_rows");

    private static final List<String> SHEET_WRITE_TOOLS = List.of("replace_values", "update_rows",
            "normalize_column", "undo_last_sheet_edit");

    private static final List<String> SHEET_TOOLS = List.of("read_sheet_summary", "aggregate_sheet",
            "inspect_columns", "review_cells", "find_rows", "normalize_column", "replace_values", "update_rows",
            "undo_last_sheet_edit");

    /** AI 可视可操作专项的 7 个通用原语（1 看 + 6 做）。 */
    private static final List<String> UI_PRIMITIVE_TOOLS = List.of("ui_snapshot", "ui_click", "ui_fill",
            "ui_select", "ui_scroll", "ui_highlight", "ui_request_upload");

    private static final List<String> UI_READ_TOOLS = List.of("ui_snapshot", "ui_scroll", "ui_highlight");

    private static final List<String> UI_WRITE_TOOLS = List.of("ui_click", "ui_fill", "ui_select",
            "ui_request_upload");

    /** S4.4e 裁决⑥：必须经确认门的启动类工具（FR-5.3 明文清单）。 */
    private static final List<String> GATED_START_TOOLS = List.of("start_export", "start_precheck", "start_import",
            "start_publish");

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private AiTools aiTools;

    @Autowired
    private List<FrontendToolGuard> frontendGuards;

    /** 会落服务端的向导动作（工具安全边界批 2026-10-25 裁决：WRITE，不是 READ）。 */
    private static final List<String> WIZARD_WRITE_TOOLS = List.of("select_definitions", "set_condition",
            "confirm_step");

    @Test
    void wizardToolsUseFrontendChannelAndHonestRisk() {
        for (String name : FRONTEND_TOOLS) {
            assertThat(toolRegistry.channelOf(name))
                    .as("%s 的执行通道", name)
                    .isEqualTo(ToolMeta.Channel.FRONTEND);
            ToolMeta.RiskLevel expected = WIZARD_WRITE_TOOLS.contains(name)
                    ? ToolMeta.RiskLevel.WRITE
                    : ToolMeta.RiskLevel.READ;
            assertThat(toolRegistry.riskOf(name))
                    .as("%s 的风险等级（三个向导动作都会落服务端：条件 PUT / 勾选 / 步骤推进；"
                            + "FRONTEND 通道优先，风险档只用于如实披露，不新增确认门）", name)
                    .isEqualTo(expected);
            assertThat(toolRegistry.getMeta(name)).as("%s 已注册元数据", name).isNotNull();
        }
    }

    /**
     * 确认边界（用户 2026-10-25 裁决）：三个向导动作的"实际后果"不足以进确认门，但**必须**
     * 走 {@code FRONTEND} 挂起（等前端回执），而不是被风险档误判成后端确认卡；
     * DANGER 只留给四个 {@code start_*}（后端执行、可造成业务数据落库/发布）。
     */
    @Test
    void wizardWritesAreNeverDanger() {
        for (String name : WIZARD_WRITE_TOOLS) {
            assertThat(toolRegistry.riskOf(name)).as(name).isEqualTo(ToolMeta.RiskLevel.WRITE);
        }
        for (String name : GATED_START_TOOLS) {
            assertThat(toolRegistry.riskOf(name)).as(name).isEqualTo(ToolMeta.RiskLevel.DANGER);
        }
    }

    /**
     * 守卫接线（工具安全边界批）：两个向导写的复核器必须真的进了挂起链路，
     * 且同一工具名只被一个复核器认领（多个认领会让"第一个命中即返回"变成顺序彩票）。
     */
    @Test
    void wizardActionGuardIsWiredAndClaimsEachToolExactlyOnce() {
        assertThat(this.frontendGuards).as("生产装配里的前端复核器")
                .anyMatch(com.example.configmgr.ai.tool.WizardActionGuard.class::isInstance);
        for (String name : List.of("set_condition", "select_definitions", "undo_last_sheet_edit")) {
            long claims = this.frontendGuards.stream().filter(guard -> guard.supports(name)).count();
            assertThat(claims).as("%s 的复核器数量", name).isEqualTo(1);
        }
    }

    /**
     * R3-02 / I-5：三个表格写工具的模型侧描述必须如实交代导入向导第 1 步的整表写回，
     * 不得再出现“只改浏览器内存 / 不落服务端”的绝对句（模型会把这句话原样转述给用户）。
     */
    @Test
    void sheetWriteToolDescriptionsDiscloseImportStepOneWriteBack() {
        for (String name : List.of("normalize_column", "replace_values", "update_rows")) {
            String description = toolRegistry.getCallback(name).getToolDefinition().description();
            assertThat(description).as(name + " 的模型侧描述")
                    .contains("导入向导第 1 步")
                    .contains("写回服务端");
            assertThat(description).as(name + " 不得再声称只在浏览器内存")
                    .doesNotContain("只改浏览器内存")
                    .doesNotContain("不落服务端");
        }
    }

    @Test
    void uiPrimitiveToolsUseFrontendChannelAndHonestRisk() {
        for (String name : UI_PRIMITIVE_TOOLS) {
            assertThat(toolRegistry.channelOf(name)).as("%s 的执行通道", name)
                    .isEqualTo(ToolMeta.Channel.FRONTEND);
            assertThat(toolRegistry.getMeta(name)).as("%s 已注册元数据", name).isNotNull();
        }
        for (String name : UI_READ_TOOLS) {
            assertThat(toolRegistry.riskOf(name)).as("%s 是只读原语", name)
                    .isEqualTo(ToolMeta.RiskLevel.READ);
        }
        for (String name : UI_WRITE_TOOLS) {
            assertThat(toolRegistry.riskOf(name)).as("%s 是写原语（对外部副作用诚实）", name)
                    .isEqualTo(ToolMeta.RiskLevel.WRITE);
        }
        // 披露面 ≠ 可达面：scope 一律 *（围栏在区域与元素两层）
        assertThat(toolRegistry.matchesContext("ui_snapshot", AiContext.empty()))
                .as("空上下文也披露 ui_snapshot").isTrue();
        assertThat(toolRegistry.matchesContext("ui_click", AiContext.empty())).isTrue();
    }

    @Test
    void uiPrimitiveStubsReturnFrontendStub() {
        assertThat(aiTools.uiSnapshot(null, null, null, null)).isEqualTo(AiTools.FRONTEND_STUB);
        assertThat(aiTools.uiClick("import.upload.choose", "a1b2c3-1")).isEqualTo(AiTools.FRONTEND_STUB);
        assertThat(aiTools.uiFill("import.upload.query", "v", "a1b2c3-1")).isEqualTo(AiTools.FRONTEND_STUB);
        assertThat(aiTools.uiSelect("import.mode.group", "MERGE", "a1b2c3-1")).isEqualTo(AiTools.FRONTEND_STUB);
        assertThat(aiTools.uiScroll("import.grid.viewport", 100, "nearest", "a1b2c3-1"))
                .isEqualTo(AiTools.FRONTEND_STUB);
        assertThat(aiTools.uiHighlight("import.upload.choose", null, "a1b2c3-1")).isEqualTo(AiTools.FRONTEND_STUB);
        assertThat(aiTools.uiRequestUpload(null, List.of("xlsx"))).isEqualTo(AiTools.FRONTEND_STUB);
    }

    @Test
    void uiSnapshotDescriptionMirrorsTheCaps() {
        // T-43b 防漂移：描述与常量同源（40 行 / 2000 字符），且写明 ref 不是 selector
        ToolCallback callback = toolRegistry.getCallback("ui_snapshot");
        assertThat(callback).isNotNull();
        String description = callback.getToolDefinition().description();
        assertThat(description).contains("40").contains("2000").contains("共");
        assertThat(description).contains("不是 selector");
        // 批 C：文本段 600 字符 / 12 行 + 控件行优先（描述与前端常量同源）
        assertThat(description).contains("600").contains("12").contains("控件行优先");
    }

    @Test
    void sheetToolsUseFrontendChannelAndExpectedRisk() {
        for (String name : SHEET_READ_TOOLS) {
            assertThat(toolRegistry.channelOf(name)).as("%s 的执行通道", name)
                    .isEqualTo(ToolMeta.Channel.FRONTEND);
            assertThat(toolRegistry.riskOf(name)).as("%s 是只读工具", name)
                    .isEqualTo(ToolMeta.RiskLevel.READ);
        }
        for (String name : SHEET_WRITE_TOOLS) {
            assertThat(toolRegistry.channelOf(name)).as("%s 的执行通道", name)
                    .isEqualTo(ToolMeta.Channel.FRONTEND);
            // 通道优先于风险：写工具即使标 WRITE，也不会进后端确认门；确认落在前端预览卡。
            assertThat(toolRegistry.riskOf(name)).as("%s 是写工具", name)
                    .isEqualTo(ToolMeta.RiskLevel.WRITE);
        }
    }

    @Test
    void sheetToolsAreDisclosedOnlyWhereATableExists() {
        // 导出结果步与导入上传步披露；其他步骤/非向导页面不披露（表格只在这两处存在）
        // 【2026-10-22 语义变更（AI 表格工具治理批）】缺省披露集 = 5 个纯读工具：
        // app.ai.sheet.write-enabled 缺省 false ⇒ 写 3 + 撤销 + normalize 双档不在披露面
        // （"改状态的通道默认关"）。写闸打开后的全量披露见 ToolRegistrySheetGateOnTest。
        assertThat(namesFor(AiContext.of("export", "EXPORT", "EXPORT", 1L, Map.of())))
                .containsAll(SHEET_READ_TOOLS)
                .doesNotContainAnyElementsOf(SHEET_WRITE_TOOLS);
        assertThat(namesFor(AiContext.of("import", "IMPORT", "UPLOAD", 1L, Map.of())))
                .containsAll(SHEET_READ_TOOLS)
                .doesNotContainAnyElementsOf(SHEET_WRITE_TOOLS);
        assertThat(namesFor(AiContext.of("export", "EXPORT", "SELECT_DEFS", 1L, Map.of())))
                .doesNotContainAnyElementsOf(SHEET_READ_TOOLS);
        assertThat(namesFor(AiContext.of("import", "IMPORT", "PRECHECK", 1L, Map.of())))
                .doesNotContainAnyElementsOf(SHEET_READ_TOOLS);
        assertThat(namesFor(AiContext.of("data", null, null, null, null)))
                .doesNotContainAnyElementsOf(SHEET_TOOLS);
    }

    @Test
    void sheetToolBodiesAreSentinelStubs() {
        assertThat(aiTools.readSheetSummary("CURRENCY", "sheet", new String[] { "code" }, null, null, 5))
                .isEqualTo(AiTools.FRONTEND_STUB);
        assertThat(aiTools.aggregateSheet("CURRENCY", null, "region", "code", new String[] { "count" }, "metric", 20))
                .isEqualTo(AiTools.FRONTEND_STUB);
        assertThat(aiTools.inspectColumns("CURRENCY")).isEqualTo(AiTools.FRONTEND_STUB);
        assertThat(aiTools.reviewCells("CURRENCY", "sheet", null, 5)).isEqualTo(AiTools.FRONTEND_STUB);
        assertThat(aiTools.findRows("CURRENCY", "华东", null, "contains", true, "sheet", null, 5))
                .isEqualTo(AiTools.FRONTEND_STUB);
        assertThat(aiTools.normalizeColumn("CURRENCY", new String[] { "code" }, new String[] { "trim" }, null, false, null))
                .isEqualTo(AiTools.FRONTEND_STUB);
        assertThat(aiTools.replaceValues("CURRENCY", "sheet", null, new String[] { "status" }, "wholeCell", true,
                "停用", "禁用", "count", null)).isEqualTo(AiTools.FRONTEND_STUB);
        assertThat(aiTools.updateRows("CURRENCY", "sheet", null,
                List.of(new AiTools.SheetSetItem("status", "literal", "禁用", null, null, null)), "count", null))
                .isEqualTo(AiTools.FRONTEND_STUB);
        assertThat(aiTools.undoLastSheetEdit("CURRENCY")).isEqualTo(AiTools.FRONTEND_STUB);
    }

    /**
     * 任务中心（向导入口页）只披露<b>入口动作</b>：{@code navigate_to}（任意页面进向导）与
     * {@code create_task}（没有任务时建任务）。
     *
     * <p>【2026-10-28 步骤披露收口】三个步骤级向导动作在任务中心<b>不再</b>披露：任务中心没有
     * 「当前步骤」这个事实，按任务类型宽披露正是「模型在 SELECT_DEFS 直接调 {@code set_condition}
     * 被前端拒绝」的入口（模型看得见就敢调）。改按步骤披露后，模型必须先
     * {@code navigate_to} 进向导、再按披露面逐步走（见 {@link #wizardToolDisclosureIsStepScoped}）。
     */
    @Test
    void taskCenterDisclosesEntryActionsButNotStepScopedWizardActions() {
        List<String> names = namesFor(AiContext.of("tasks", null, null, null, null));
        assertThat(names).contains("navigate_to", "create_task");
        assertThat(names).doesNotContain("select_definitions", "set_condition", "confirm_step");
    }

    @Test
    void wizardToolsAreNotDisclosedWithoutWorkspaceContext() {
        List<String> names = namesFor(AiContext.empty());
        assertThat(names).doesNotContainAnyElementsOf(SCOPED_WIZARD_TOOLS);
        assertThat(names).doesNotContain("create_task");
        assertThat(names).contains("list_config_defs", "list_tasks", "navigate_to");
    }

    @Test
    void wizardToolsAreNotDisclosedOnNonWizardPages() {
        for (String page : List.of("definitions", "data")) {
            List<String> names = namesFor(AiContext.of(page, null, null, null, null));
            assertThat(names).as("页面 %s 不得披露向导动作", page)
                    .doesNotContainAnyElementsOf(SCOPED_WIZARD_TOOLS);
            // 创建任务同理：只在任务中心/向导披露（S4.4e 裁决②）
            assertThat(names).as("页面 %s 不得披露 create_task", page).doesNotContain("create_task");
            // 导航动作是任意页面进入向导的入口，披露范围最宽
            assertThat(names).contains("navigate_to");
        }
    }

    /**
     * 【2026-10-28 步骤披露收口】三个向导写动作按<b>能成功的步骤</b>披露，与前端动作表
     * {@code allowSteps} 同源（{@code frontend/src/utils/frontend-tools.ts}）：
     * <ul>
     * <li>{@code select_definitions}：EXPORT/{@code SELECT_DEFS} 与 IMPORT/{@code UPLOAD}
     * （前端 {@code allowSteps: ['SELECT_DEFS','UPLOAD']}）；</li>
     * <li>{@code set_condition}：只 EXPORT/{@code QUERY_COND}（前端 {@code allowSteps: ['QUERY_COND']}）；</li>
     * <li>{@code confirm_step}：整个任务内（{@code task:EXPORT} / {@code task:IMPORT}），它本身就是推进步骤的手段。</li>
     * </ul>
     * 旧口径「按任务类型宽披露」（EXPORT 三步都披露 set_condition）==> 模型在 {@code SELECT_DEFS}
     * 直接调 {@code set_condition}，前端 {@code allowSteps} 守卫拒绝并回填「步骤不符」。
     */
    @Test
    void wizardToolDisclosureIsStepScoped() {
        List<String> atSelectDefs = namesFor(AiContext.of("export", "EXPORT", "SELECT_DEFS", 1L, Map.of()));
        assertThat(atSelectDefs).as("EXPORT/SELECT_DEFS：选配置与推进可见，条件工具不可见")
                .contains("navigate_to", "select_definitions", "confirm_step")
                .doesNotContain("set_condition");

        List<String> atQueryCond = namesFor(AiContext.of("export", "EXPORT", "QUERY_COND", 1L, Map.of()));
        assertThat(atQueryCond).as("EXPORT/QUERY_COND：条件工具正向披露（选配置已过步）")
                .contains("navigate_to", "set_condition", "confirm_step")
                .doesNotContain("select_definitions");

        List<String> atExport = namesFor(AiContext.of("export", "EXPORT", "EXPORT", 1L, Map.of()));
        assertThat(atExport).as("EXPORT/EXPORT：只剩推进与作业入口")
                .contains("navigate_to", "confirm_step", "start_export")
                .doesNotContain("select_definitions", "set_condition");

        // 导入：第 1 步（UPLOAD）选配置；条件步骤不适用（前端动作表也不允许）
        List<String> atUpload = namesFor(AiContext.of("import", "IMPORT", "UPLOAD", 1L, Map.of()));
        assertThat(atUpload).contains("navigate_to", "select_definitions", "confirm_step")
                .doesNotContain("set_condition");
        for (String step : List.of("PRECHECK", "IMPORT", "PUBLISH")) {
            assertThat(namesFor(AiContext.of("import", "IMPORT", step, 1L, Map.of()))).as("IMPORT/%s", step)
                    .contains("navigate_to", "confirm_step")
                    .doesNotContain("select_definitions", "set_condition");
        }
    }

    @Test
    void backendToolDisclosureIsUnchanged() {
        // 后端作业类工具的披露口径：只在对应任务类型的具体步骤出现
        assertThat(namesFor(AiContext.of("export", "EXPORT", "SELECT_DEFS", 1L, Map.of())))
                .doesNotContain("start_export");
        assertThat(namesFor(AiContext.of("export", "EXPORT", "EXPORT", 1L, Map.of())))
                .contains("start_export");
        assertThat(namesFor(AiContext.of("import", "IMPORT", "PRECHECK", 1L, Map.of())))
                .contains("start_precheck");
        assertThat(namesFor(AiContext.of("import", "IMPORT", "PRECHECK", 1L, Map.of())))
                .doesNotContain("start_import");
        // S4.4d 既有工具披露调整①：任务中心（向导入口）也披露 start_export，
        // 使"在任务中心一句话驱动导出"能在同一轮内跑到启动作业（理由见 AiTools#startExport）
        assertThat(namesFor(AiContext.of("tasks", null, null, null, null))).contains("start_export");
        // 同一口径：导入的「检查配置」步也要能从任务中心触达（理由见 AiTools#startPrecheck）
        assertThat(namesFor(AiContext.of("tasks", null, null, null, null))).contains("start_precheck");
        // 写暂存与发布不放宽（发布另有确认门）：任务中心不披露
        assertThat(namesFor(AiContext.of("tasks", null, null, null, null)))
                .doesNotContain("start_import", "start_publish");
    }

    @Test
    void wizardToolBodiesAreSentinelStubs() {
        // 后端桩体永不执行：任何直接调用都只能拿到哨兵文本，绝不产生副作用
        assertThat(aiTools.navigateTo("export", 1L)).isEqualTo(AiTools.FRONTEND_STUB);
        assertThat(aiTools.downloadExportFiles(1L, new String[] { "CURRENCY" })).isEqualTo(AiTools.FRONTEND_STUB);
        assertThat(aiTools.selectDefinitions(new String[] { "CURRENCY" }, "REPLACE")).isEqualTo(AiTools.FRONTEND_STUB);
        assertThat(aiTools.setCondition("CURRENCY", new AiTools.FieldQueryCondition(List.of("XN"),
                List.of(new AiTools.FieldCondition("code", "EQ", "CNY"))))).isEqualTo(AiTools.FRONTEND_STUB);
        assertThat(aiTools.confirmStep(null)).isEqualTo(AiTools.FRONTEND_STUB);
    }

    // ── S4.4e 裁决②：create_task 的披露范围与风险 ───────────────────────────────

    @Test
    void createTaskIsDisclosedAtTaskCenterAndWizardPages() {
        assertThat(namesFor(AiContext.of("tasks", null, null, null, null))).contains("create_task");
        assertThat(namesFor(AiContext.of("export", "EXPORT", "SELECT_DEFS", 1L, Map.of()))).contains("create_task");
        assertThat(namesFor(AiContext.of("import", "IMPORT", "UPLOAD", 1L, Map.of()))).contains("create_task");
        // 非向导页面与空上下文不披露：创建任务只在"任务现场"（任务中心/向导）有意义
        assertThat(namesFor(AiContext.of("definitions", null, null, null, null))).doesNotContain("create_task");
        assertThat(namesFor(AiContext.of("data", null, null, null, null))).doesNotContain("create_task");
        assertThat(namesFor(AiContext.empty())).doesNotContain("create_task");
        // 风险 DANGER（AR-03）：创建占任务号/进列表/可被引用，进确认门；执行通道仍是后端
        assertThat(toolRegistry.riskOf("create_task")).isEqualTo(ToolMeta.RiskLevel.DANGER);
        assertThat(toolRegistry.channelOf("create_task")).isEqualTo(ToolMeta.Channel.BACKEND);
    }

    @Test
    void completedExportTaskHidesStartExportButKeepsDownload() {
        AiContext completed = AiContext.of("export", "EXPORT", "EXPORT", 1L, "COMPLETED", Map.of());
        AiContext active = AiContext.of("export", "EXPORT", "EXPORT", 1L, "ACTIVE", Map.of());

        assertThat(namesFor(completed)).as("已完成导出任务不再披露再导出入口").doesNotContain("start_export");
        assertThat(toolRegistry.matchesContext("start_export", completed))
            .as("披露面与执行面同判据").isFalse();
        assertThat(namesFor(completed)).as("下载/查看已有结果的入口必须保留").contains("download_export_files");
        assertThat(namesFor(active)).as("ACTIVE 任务照旧披露").contains("start_export");
        assertThat(toolRegistry.matchesContext("start_export", active)).isTrue();
        // 导入任务的 COMPLETED 可能只是"预检查通过"，不得误杀后续导入/发布
        AiContext completedImport = AiContext.of("import", "IMPORT", "IMPORT", 2L, "COMPLETED", Map.of());
        assertThat(namesFor(completedImport)).contains("start_import");
    }

    @Test
    void everyRegisteredCallbackHasMetadataAndScope() {
        for (var callback : toolRegistry.allCallbacks()) {
            String name = callback.getToolDefinition().name();
            assertThat(toolRegistry.getMeta(name)).as("%s 必须有元数据", name).isNotNull();
            assertThat(toolRegistry.getMeta(name).getScopePatterns()).as("%s 必须有 scope", name).isNotEmpty();
        }
    }

    // ── S4.4e 裁决⑥：启动类工具一律经确认门（FR-5.3） ──────────────────────────

    @Test
    void startToolsRequireConfirmGate() {
        for (String name : GATED_START_TOOLS) {
            assertThat(toolRegistry.riskOf(name)).as("%s 必须经确认门（FR-5.3 明文清单）", name)
                    .isEqualTo(ToolMeta.RiskLevel.DANGER);
            assertThat(toolRegistry.channelOf(name)).as("%s 仍由后端执行（放行后创建作业）", name)
                    .isEqualTo(ToolMeta.Channel.BACKEND);
        }
        // 风险元数据 → 挂起种类 → 确认门 的整条链路由 ConfirmGateWiringTest 锁（无 Redis 的行为用例）
    }

    // ── S4.4e 裁决③：set_condition 入参 = 前端契约（conditions 对象 + 内层结构） ──

    @Test
    void setConditionSchemaMirrorsFrontendContract() throws Exception {
        ToolCallback callback = toolRegistry.getCallback("set_condition");
        assertThat(callback).isNotNull();
        String schema = callback.getToolDefinition().inputSchema();
        JsonNode root = new ObjectMapper().readTree(schema);

        // 顶层只有 defCode + conditions —— 前端 normalizeActionArgs 只读这两个键
        assertThat(fieldNames(root.path("properties"))).containsExactlyInAnyOrder("defCode", "conditions");
        assertThat(textValues(root.path("required"))).containsExactlyInAnyOrder("defCode", "conditions");

        // conditions 的内层结构显式出现在 Schema 里（模型不必猜形状）
        JsonNode conditions = root.path("properties").path("conditions");
        assertThat(conditions.path("type").asText()).isEqualTo("object");
        assertThat(fieldNames(conditions.path("properties"))).containsExactlyInAnyOrder("scopeKeys", "fields");
        JsonNode item = conditions.path("properties").path("fields").path("items");
        assertThat(item.path("type").asText()).isEqualTo("object");
        assertThat(fieldNames(item.path("properties"))).containsExactlyInAnyOrder("fieldCode", "operator", "value");
        // value 是可选的（EMPTY/NOT_EMPTY 不带值），fieldCode/operator 是必填
        assertThat(textValues(item.path("required"))).containsExactlyInAnyOrder("fieldCode", "operator");

        // 运行期接受前端契约形态的实参：模型按契约传参时入参能正常绑定（不会因参数类型不符抛错），
        // 且后端仍只返回哨兵（副作用留给前端）。注：回调结果经 JSON 序列化，故用 contains 而非 isEqualTo。
        String args = "{\"defCode\":\"CURRENCY\",\"conditions\":{\"scopeKeys\":[\"XN\"],"
                + "\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"CNY\"}]}}";
        assertThat(callback.call(args)).contains(AiTools.FRONTEND_STUB);
    }

    // ── DC-14 T1：防线①（披露）与防线③（执行兜底）必须同源 ─────────────────────

    /**
     * 防线③复查用的 {@code matchesContext} 与防线①的披露集必须<b>完全一致</b> ——
     * 否则会出现"披露了却被拦"（功能缺失）或"没披露却放行"（防线③形同虚设）两种漂移。
     * 这里对全部注册工具 × 全部代表性上下文做一次全量对账。
     */
    @Test
    void scopeGuardMatchesDisclosedSet() {
        List<AiContext> contexts = List.of(
                AiContext.empty(),
                AiContext.of("tasks", null, null, null, null),
                AiContext.of("definitions", null, null, null, null),
                AiContext.of("data", null, null, null, null),
                AiContext.of("export", "EXPORT", "SELECT_DEFS", 1L, Map.of()),
                AiContext.of("export", "EXPORT", "QUERY_COND", 1L, Map.of()),
                AiContext.of("export", "EXPORT", "EXPORT", 1L, Map.of()),
                AiContext.of("import", "IMPORT", "UPLOAD", 1L, Map.of()),
                AiContext.of("import", "IMPORT", "PRECHECK", 1L, Map.of()),
                AiContext.of("import", "IMPORT", "IMPORT", 1L, Map.of()),
                AiContext.of("import", "IMPORT", "PUBLISH", 1L, Map.of()));
        List<String> allTools = List.of("list_config_defs", "get_config_def", "list_tasks", "get_workspace_state",
                "check_job_status", "get_row_count", "create_task", "start_export", "start_precheck", "start_import",
                "start_publish", "open_export_file_editor", "download_export_files", "navigate_to",
                "select_definitions", "set_condition", "confirm_step",
                com.example.configmgr.ai.form.GenerativeFormRules.TOOL_NAME,
                "read_sheet_summary", "aggregate_sheet", "inspect_columns", "review_cells", "find_rows",
                "normalize_column", "replace_values", "update_rows", "undo_last_sheet_edit",
                "ui_snapshot", "ui_click", "ui_fill", "ui_select", "ui_scroll", "ui_highlight",
                "ui_request_upload");

        for (AiContext context : contexts) {
            List<String> disclosed = namesFor(context);
            for (String tool : allTools) {
                assertThat(toolRegistry.matchesContext(tool, context))
                        .as("上下文 %s 下 %s 的执行兜底判定应与披露集一致", context.getContextKey(), tool)
                        .isEqualTo(disclosed.contains(tool));
            }
        }
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /** JSON 数组节点的元素文本（{@code required} 等数组型关键字）。 */
    private static List<String> textValues(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(element -> values.add(element.asText()));
        return values;
    }

    private List<String> namesFor(AiContext context) {
        return toolRegistry.forContext(context).stream()
                .map(ToolCallback::getToolDefinition)
                .map(definition -> definition.name())
                .toList();
    }

}
