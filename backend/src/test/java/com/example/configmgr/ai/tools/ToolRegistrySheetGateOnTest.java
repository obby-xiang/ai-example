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
 * 写闸打开后的披露面（AI 表格工具治理方案 2026-10-22 §2；与 {@link ToolRegistrySheetGateTest} 配对）。
 *
 * <p>
 * 语义变更的另一半：管理员显式打开 {@code app.ai.sheet.write-enabled=true} 并重启之后，9 个表格工具
 * 回到"全部披露"—— 证明这不是"写侧永久下线"，而是"默认关、可开"。
 *
 * <p>
 * 顺带锁住 {@code normalize_column} 的联合键：它在读闸与写闸都开时才出现，缺任一闸即消失。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:sheet-gate-write-on;DB_CLOSE_DELAY=-1",
        "app.ai.sheet.write-enabled=true"
})
class ToolRegistrySheetGateOnTest {

    private static final List<String> SHEET_TOOLS = List.of("read_sheet_summary", "aggregate_sheet",
            "inspect_columns", "review_cells", "find_rows", "normalize_column", "replace_values", "update_rows",
            "undo_last_sheet_edit");

    @Autowired
    private ToolRegistry toolRegistry;

    @Test
    void allSheetToolsAreDisclosedWhenBothGatesAreOpen() {
        for (AiContext context : List.of(AiContext.of("export", "EXPORT", "EXPORT", 1L, Map.of()),
                AiContext.of("import", "IMPORT", "UPLOAD", 1L, Map.of()))) {
            List<String> names = namesFor(context);
            assertThat(names).as("上下文 %s 的全量披露", context.getContextKey()).containsAll(SHEET_TOOLS);
            for (String tool : SHEET_TOOLS) {
                assertThat(toolRegistry.matchesContext(tool, context)).as(tool).isTrue();
                assertThat(toolRegistry.sheetDisabled(tool)).as(tool).isFalse();
            }
        }
    }

    @Test
    void nonSheetPagesStillHideThemEvenWithTheGateOpen() {
        assertThat(namesFor(AiContext.of("data", null, null, null, null)))
                .doesNotContainAnyElementsOf(SHEET_TOOLS);
    }

    /** 写闸开 ⇒ 越 scope 的写工具回到 {@code SCOPE_NOT_DISCLOSED}（开关不再抢答）。 */
    @Test
    void outOfScopeCodeIsRestoredWhenTheWriteGateIsOpen() {
        assertThat(toolRegistry.undisclosedCode("replace_values")).isEqualTo("SCOPE_NOT_DISCLOSED");
    }

    private List<String> namesFor(AiContext context) {
        return this.toolRegistry.forContext(context).stream()
                .map(ToolCallback::getToolDefinition)
                .map(definition -> definition.name())
                .toList();
    }

}
