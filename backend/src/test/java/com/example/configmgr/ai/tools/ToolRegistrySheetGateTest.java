package com.example.configmgr.ai.tools;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.SheetSwitches;
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
 * 表格开关接入披露谓词（AI 表格工具治理方案 2026-10-22 §2；S-01/S-02/S-05 的容器级证据）。
 *
 * <p>
 * 本类跑<b>缺省配置</b>（写闸关）：读 5 个工具在表格页披露，写 3 个 + 撤销 + {@code normalize_column}
 * 一律不披露 —— 这是本批唯一**故意的对外语义变更**（此前默认披露全部 9 个）。
 *
 * <p>
 * 三条一起锁：
 * <ol>
 * <li>{@code forContext} 的披露集与 {@code matchesContext} 的执行兜底<b>逐工具一致</b>
 * （同一谓词的两个出口，防"披露了却被拦 / 没披露却放行"）；</li>
 * <li>{@code sheetDisabled} 精确标出"没披露是因为关闸"（不是越 scope）——执行兜底据此选码；</li>
 * <li>{@code /health.sheet} 的有效值与 {@link AiProperties.Sheet} 逐值一致（S-05）。</li>
 * </ol>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:sheet-gate-default;DB_CLOSE_DELAY=-1"
})
class ToolRegistrySheetGateTest {

    private static final List<String> READ_TOOLS = List.of("read_sheet_summary", "aggregate_sheet",
            "inspect_columns", "review_cells", "find_rows");

    private static final List<String> WRITE_TOOLS = List.of("replace_values", "update_rows",
            "undo_last_sheet_edit", "normalize_column");

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private AiProperties properties;

    @Autowired
    private SheetSwitches sheetSwitches;

    @Test
    void readToolsAreDisclosedByDefault() {
        List<String> names = namesFor(AiContext.of("export", "EXPORT", "EXPORT", 1L, Map.of()));

        assertThat(names).containsAll(READ_TOOLS);
        assertThat(namesFor(AiContext.of("import", "IMPORT", "UPLOAD", 1L, Map.of()))).containsAll(READ_TOOLS);
        for (String name : READ_TOOLS) {
            assertThat(toolRegistry.matchesContext(name, AiContext.of("export", "EXPORT", "EXPORT", 1L, Map.of())))
                    .as("%s 读工具在表格页可执行", name).isTrue();
            assertThat(toolRegistry.sheetDisabled(name)).as("%s 未被开关挡住", name).isFalse();
        }
    }

    /** 语义变更：写 4（含撤销与 normalize 双档）缺省不披露 —— "改状态的通道默认关"。 */
    @Test
    void writeToolsAreNotDisclosedByDefault() {
        AiContext exportStep = AiContext.of("export", "EXPORT", "EXPORT", 1L, Map.of());
        List<String> names = namesFor(exportStep);

        assertThat(names).doesNotContainAnyElementsOf(WRITE_TOOLS);
        for (String name : WRITE_TOOLS) {
            assertThat(toolRegistry.matchesContext(name, exportStep))
                    .as("%s 写工具缺省不可执行（旧缓存调用会被执行兜底拦下）", name).isFalse();
            assertThat(toolRegistry.sheetDisabled(name))
                    .as("%s 未披露的原因是关闸（不是越 scope）", name).isTrue();
        }
    }

    /** 关闸不改变"页面不对"的判定：非表格页的读工具仍是 scope 越界，不是 SHEET_DISABLED。 */
    @Test
    void outOfScopeStillWinsOverTheSwitch() {
        AiContext dataPage = AiContext.of("data", null, null, null, null);

        assertThat(toolRegistry.matchesContext("read_sheet_summary", dataPage)).isFalse();
        assertThat(namesFor(dataPage)).doesNotContainAnyElementsOf(READ_TOOLS);
        // 读闸是开的 ⇒ 未披露的理由只能是 scope（执行兜底走 SCOPE_NOT_DISCLOSED）
        assertThat(toolRegistry.sheetDisabled("read_sheet_summary")).isFalse();
        assertThat(toolRegistry.undisclosedCode("read_sheet_summary")).isEqualTo("SCOPE_NOT_DISCLOSED");
        // 写闸是关的 ⇒ 理由码是 SHEET_DISABLED（即使页面也不对，先回答"功能没开"更可行动）
        assertThat(toolRegistry.undisclosedCode("replace_values")).isEqualTo("SHEET_DISABLED");
    }

    /** 披露集与执行兜底逐工具对账（同一条 {@code allows} 的两个出口，不允许漂移）。 */
    @Test
    void disclosureAndExecutionGuardAgree() {
        List<AiContext> contexts = List.of(
                AiContext.empty(),
                AiContext.of("tasks", null, null, null, null),
                AiContext.of("data", null, null, null, null),
                AiContext.of("export", "EXPORT", "SELECT_DEFS", 1L, Map.of()),
                AiContext.of("export", "EXPORT", "EXPORT", 1L, Map.of()),
                AiContext.of("import", "IMPORT", "UPLOAD", 1L, Map.of()),
                AiContext.of("import", "IMPORT", "PRECHECK", 1L, Map.of()));
        List<String> sheetTools = List.of("read_sheet_summary", "aggregate_sheet", "inspect_columns",
                "review_cells", "find_rows", "normalize_column", "replace_values", "update_rows",
                "undo_last_sheet_edit");

        for (AiContext context : contexts) {
            List<String> disclosed = namesFor(context);
            for (String tool : sheetTools) {
                assertThat(toolRegistry.matchesContext(tool, context))
                        .as("上下文 %s 下 %s 的披露与执行兜底必须同结论", context.getContextKey(), tool)
                        .isEqualTo(disclosed.contains(tool));
            }
        }
    }

    /** S-05：{@code /health.sheet} 报的就是用的（有效值，不是配置原值）。 */
    @Test
    void healthEchoesThePropertiesActuallyInUse() {
        Map<String, Object> health = this.sheetSwitches.health();

        assertThat(health).containsEntry("readEnabled", this.properties.getSheet().isReadEnabled())
                .containsEntry("writeEnabled", this.properties.getSheet().isWriteEnabled())
                .containsEntry("sampleMaxRows", 20)
                .containsEntry("projectionMaxColumns", 5);
    }

    private List<String> namesFor(AiContext context) {
        return this.toolRegistry.forContext(context).stream()
                .map(ToolCallback::getToolDefinition)
                .map(definition -> definition.name())
                .toList();
    }

}
