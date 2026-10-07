package com.example.configmgr.ai.tools;

import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S4.4d 用例：工作区动作的通道/风险元数据 + 渐进披露（FR-5.2 三重防线的防线①）。
 *
 * <p>覆盖两件容易在改动中悄悄失守的事：
 * <ol>
 * <li><b>通道与风险</b>：4 个向导动作必须是 {@code FRONTEND} 通道（后端只挂起、不执行副作用）
 * 且为 {@code READ}（不得误升级成 DANGER —— 那会让前端动作多走一道确认门）；</li>
 * <li><b>披露边界</b>：向导动作在任务中心与对应任务类型下披露，非向导页面（配置定义/数据浏览）
 * 与空上下文不披露；同时锁住既有 BACKEND 工具的披露范围不回退（{@code start_import}/
 * {@code start_publish} 仍只在导入任务的对应步骤披露），并显式登记本棒放宽的两档
 * （{@code start_export}/{@code start_precheck} 增加 {@code page:tasks}）。</li>
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

    /** 全部前端通道工具（2 个既有 + 4 个新增）。 */
    private static final List<String> FRONTEND_TOOLS = List.of("open_export_file_editor", "download_export_file",
            "navigate_to", "select_definitions", "set_condition", "confirm_step");

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
        assertThat(names).contains("list_config_defs", "list_tasks", "navigate_to");
    }

    @Test
    void wizardToolsAreNotDisclosedOnNonWizardPages() {
        for (String page : List.of("definitions", "data")) {
            List<String> names = namesFor(AiContext.of(page, null, null, null, null));
            assertThat(names).as("页面 %s 不得披露向导动作", page)
                    .doesNotContainAnyElementsOf(SCOPED_WIZARD_TOOLS);
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
        assertThat(aiTools.setCondition("CURRENCY", Map.of())).isEqualTo(AiTools.FRONTEND_STUB);
        assertThat(aiTools.confirmStep(null)).isEqualTo(AiTools.FRONTEND_STUB);
    }

    private List<String> namesFor(AiContext context) {
        return toolRegistry.forContext(context).stream()
                .map(ToolCallback::getToolDefinition)
                .map(definition -> definition.name())
                .toList();
    }

}
