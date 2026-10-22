package com.example.configmgr.ai.tools;

import com.example.configmgr.ai.tool.AiContext;
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
 * 批 A · A-11 / A-12（后端镜像）：7 个 {@code ui_*} 按区域存在性动态披露（D8-①）。
 *
 * <p>
 * 判据源 = {@code extra.uiRegionCount}：<b>缺失即披露</b>（fail-open，旧客户端 / curl / E2E 桩
 * 不带上报时工具不得静默消失）；<b>显式 0 才不披露</b>（页面确实没有可操作区域，模型不该空转快照）。
 * 该标签只用于<b>收窄</b>披露面，永远不能凭伪造值获得未披露工具。
 *
 * <p><b>证红</b>：把 {@code @ToolScope} 改回 {@code {"*"}} ⇒ 显式 0 用例红；把缺失解析成 0
 * ⇒ fail-open 用例红。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:ui-scope-region;DB_CLOSE_DELAY=-1"
})
class ToolRegistryUiScopeTest {

    private static final List<String> UI_TOOLS = List.of("ui_snapshot", "ui_click", "ui_fill", "ui_select",
            "ui_scroll", "ui_highlight", "ui_request_upload");

    @Autowired
    private ToolRegistry toolRegistry;

    @Test
    void explicitZeroRegionCountHidesUiTools() {
        AiContext ctx = AiContext.of("definitions", null, null, null, Map.of("uiRegionCount", 0));

        assertThat(namesFor(ctx)).as("显式 0：页面没有已注册区域时 ui_* 不在披露名单")
                .doesNotContainAnyElementsOf(UI_TOOLS);
        for (String name : UI_TOOLS) {
            assertThat(toolRegistry.matchesContext(name, ctx))
                    .as("%s 的披露面与执行兜底同源（显式 0 时都拒绝）", name)
                    .isFalse();
        }
    }

    @Test
    void missingRegionCountFailsOpen() {
        AiContext oldClient = AiContext.empty();
        AiContext definitions = AiContext.of("definitions", null, null, null, Map.of());

        assertThat(namesFor(oldClient)).as("缺失：旧客户端 / E2E 桩不带上报时仍然披露")
                .containsAll(UI_TOOLS);
        assertThat(namesFor(definitions)).as("缺失：非空上下文但不带 uiRegionCount 时仍然披露")
                .containsAll(UI_TOOLS);
        for (String name : UI_TOOLS) {
            assertThat(toolRegistry.matchesContext(name, definitions))
                    .as("%s 在缺失 uiRegionCount 时执行兜底同样放行（与披露集一致）", name)
                    .isTrue();
        }
    }

    @Test
    void positiveRegionCountDisclosesUiTools() {
        AiContext ctx = AiContext.of("import", "IMPORT", "UPLOAD", 1L, Map.of("uiRegionCount", 2));

        assertThat(namesFor(ctx)).containsAll(UI_TOOLS);
        for (String name : UI_TOOLS) {
            assertThat(toolRegistry.matchesContext(name, ctx)).as("%s 在有区域时披露且可执行", name).isTrue();
        }
    }

    @Test
    void explicitZeroOnlyNarrowsAndNeverWidens() {
        // 伪造 uiRegionCount 只能"关掉"工具，不能换来任何未披露工具：
        // 其他工具的披露判定与 uiRegionCount 无关（受 page/task 标签约束的仍然不披露）。
        AiContext ctx = AiContext.of("definitions", null, null, null, Map.of("uiRegionCount", 99));

        assertThat(namesFor(ctx)).contains("list_config_defs").doesNotContain("create_task");
    }

    private List<String> namesFor(AiContext context) {
        return toolRegistry.forContext(context).stream()
                .map(ToolCallback::getToolDefinition)
                .map(definition -> definition.name())
                .toList();
    }
}
