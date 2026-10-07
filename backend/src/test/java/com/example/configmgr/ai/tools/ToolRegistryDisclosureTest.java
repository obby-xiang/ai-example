package com.example.configmgr.ai.tools;

import com.example.configmgr.ai.tool.AiContext;
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
 * <li><b>通道与风险（S4.4d）</b>：4 个向导动作必须是 {@code FRONTEND} 通道（后端只挂起、不执行副作用）
 * 且为 {@code READ}（不得误升级成 DANGER —— 那会让前端动作多走一道确认门）；</li>
 * <li><b>披露边界</b>：向导动作在任务中心与对应任务类型下披露，非向导页面（配置定义/数据浏览）
 * 与空上下文不披露；同时锁住既有 BACKEND 工具的披露范围不回退（{@code start_import}/
 * {@code start_publish} 仍只在导入任务的对应步骤披露），并显式登记 S4.4d 放宽的两档
 * （{@code start_export}/{@code start_precheck} 增加 {@code page:tasks}）；</li>
 * <li><b>S4.4e 三项</b>：{@code create_task} 的披露范围（任务中心 + 两类向导页，其余页面不披露）与
 * 风险（{@code WRITE}，不进确认门）；启动类四个工具一律 {@code DANGER}（确认门，FR-5.3）；
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

    /** 本棒新增的 4 个工作区动作。 */
    private static final List<String> WIZARD_TOOLS = List.of("navigate_to", "select_definitions", "set_condition",
            "confirm_step");

    /**
     * 受页面/任务类型约束的 3 个向导动作（{@code navigate_to} 是任意页面的导航入口，
     * 披露范围为 {@code *}，不参与"非向导页面不披露"的断言）。
     */
    private static final List<String> SCOPED_WIZARD_TOOLS = List.of("select_definitions", "set_condition",
            "confirm_step");

    /** 全部前端通道工具（2 个既有 + 4 个工作区动作 + DC-15 的 generative_form）。 */
    private static final List<String> FRONTEND_TOOLS = List.of("open_export_file_editor", "download_export_file",
            "navigate_to", "select_definitions", "set_condition", "confirm_step",
            com.example.configmgr.ai.form.GenerativeFormRules.TOOL_NAME);

    /** S4.4e 裁决⑥：必须经确认门的启动类工具（FR-5.3 明文清单）。 */
    private static final List<String> GATED_START_TOOLS = List.of("start_export", "start_precheck", "start_import",
            "start_publish");

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private AiTools aiTools;

    @Test
    void wizardToolsUseFrontendChannelAndReadRisk() {
        for (String name : FRONTEND_TOOLS) {
            assertThat(toolRegistry.channelOf(name))
                    .as("%s 的执行通道", name)
                    .isEqualTo(ToolMeta.Channel.FRONTEND);
            assertThat(toolRegistry.riskOf(name))
                    .as("%s 的风险等级（前端动作无后端副作用，不应进确认门）", name)
                    .isEqualTo(ToolMeta.RiskLevel.READ);
            assertThat(toolRegistry.getMeta(name)).as("%s 已注册元数据", name).isNotNull();
        }
    }

    @Test
    void wizardToolsAreDisclosedAtTaskCenter() {
        List<String> names = namesFor(AiContext.of("tasks", null, null, null, null));
        assertThat(names).containsAll(WIZARD_TOOLS);
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

    @Test
    void wizardToolsFollowTaskTypeAcrossSteps() {
        // 导出任务：整个向导内都在披露集（一轮内可跨步骤驱动）
        for (String step : List.of("SELECT_DEFS", "QUERY_COND", "EXPORT")) {
            List<String> names = namesFor(AiContext.of("export", "EXPORT", step, 1L, Map.of()));
            assertThat(names).as("EXPORT/%s 披露集", step).containsAll(WIZARD_TOOLS);
        }
        // 导入任务：条件步骤不适用（前端动作表也不允许），故不披露 set_condition
        List<String> importNames = namesFor(AiContext.of("import", "IMPORT", "UPLOAD", 1L, Map.of()));
        assertThat(importNames).contains("navigate_to", "select_definitions", "confirm_step");
        assertThat(importNames).doesNotContain("set_condition");
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
        // 风险 WRITE：创建任务可逆（删除机制既有），不进确认门；执行通道是后端（与 REST 同一服务层）
        assertThat(toolRegistry.riskOf("create_task")).isEqualTo(ToolMeta.RiskLevel.WRITE);
        assertThat(toolRegistry.channelOf("create_task")).isEqualTo(ToolMeta.Channel.BACKEND);
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
                "start_publish", "open_export_file_editor", "download_export_file", "navigate_to",
                "select_definitions", "set_condition", "confirm_step",
                com.example.configmgr.ai.form.GenerativeFormRules.TOOL_NAME);

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
