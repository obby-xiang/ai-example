package com.example.configmgr.ai.tools;

import com.example.configmgr.data.service.ConfigDataService;
import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.entity.ConfigField;
import com.example.configmgr.definition.service.DefinitionService;
import com.example.configmgr.job.service.JobService;
import com.example.configmgr.task.service.TaskService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code list_config_defs} 截断上限（50）与截断告知的回归锁 —— 与
 * {@code AiToolsListTasksTruncationTest} 同一口径（同一个 50 行预算、同一种"告知必须真"的要求）。
 *
 * <h2>为什么必须锁</h2>
 * 配置定义条数随业务随时可增，而模型上下文是有限预算：工具必须**有界披露**，且超限时要如实告诉模型
 * "这不是全集"。两个方向都会制造错误结论：
 * <ul>
 * <li><b>静默截断</b> —— 模型把不完整目录当完整目录（"系统里只有这 50 个"）；</li>
 * <li><b>假告知</b>（恰好 50 条也追加"仅显示前 50 个"）—— 模型以为还有更多，白跑一轮过滤。</li>
 * </ul>
 * 因此判据是三条：行数 / 告知文案 / 边界不告知，落在两个用例里（51 条截断并告知、恰 50 条不告知）。
 *
 * <p>纯单测：只桩 {@link DefinitionService#findAll()}（排序由服务层负责，用例按稳定顺序喂入），
 * 不经 Spring 容器。
 */
class AiToolsListConfigDefsTruncationTest {

    /** 规格值：与 {@link AiTools#LIST_CONFIG_DEFS_MAX_ROWS} 互为锁（常量为实现侧，本值为裁决侧）。 */
    private static final int SPEC_MAX_ROWS = 50;

    private final DefinitionService definitionService = mock(DefinitionService.class);

    private final AiTools aiTools = new AiTools(definitionService, mock(TaskService.class),
            mock(ConfigDataService.class), mock(JobService.class));

    @Test
    void truncatesToMaxRowsAndAppendsTotalCountNotice() {
        when(definitionService.findAll()).thenReturn(definitions(SPEC_MAX_ROWS + 1));

        String text = aiTools.listConfigDefs(null);
        List<String> lines = List.of(text.split("\n", -1));

        assertThat(AiTools.LIST_CONFIG_DEFS_MAX_ROWS).isEqualTo(SPEC_MAX_ROWS);
        // 50 行结果 + 1 行告知
        assertThat(lines).hasSize(SPEC_MAX_ROWS + 1);
        assertThat(lines.get(lines.size() - 1)).isEqualTo(
                AiTools.LIST_CONFIG_DEFS_TOTAL_PREFIX + (SPEC_MAX_ROWS + 1) + AiTools.LIST_CONFIG_DEFS_TOTAL_SUFFIX);
        assertThat(text).contains("共 51 个").contains("仅显示前 50 个");
        assertThat(lines.stream().filter(line -> line.startsWith(AiTools.LIST_CONFIG_DEFS_ROW_PREFIX)).count())
                .isEqualTo(SPEC_MAX_ROWS);
        // 第 51 条只允许出现在告知里的数字中，不得作为定义行出现
        assertThat(text).doesNotContain("DEF_51");
    }

    @Test
    void doesNotAppendNoticeWhenRowCountEqualsMaxRows() {
        when(definitionService.findAll()).thenReturn(definitions(SPEC_MAX_ROWS));

        String text = aiTools.listConfigDefs(null);

        assertThat(List.of(text.split("\n", -1))).hasSize(SPEC_MAX_ROWS);
        assertThat(text).doesNotContain("仅显示前").doesNotContain(AiTools.LIST_CONFIG_DEFS_TOTAL_PREFIX);
        assertThat(text).contains("DEF_50");
    }

    /** 与产品同形的行：`- <code>（<name>）层级:<LEVEL> 字段数:<n>`（字段数取实际字段列表大小）。 */
    private static List<ConfigDefinition> definitions(int count) {
        List<ConfigDefinition> defs = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            ConfigDefinition def = new ConfigDefinition();
            def.setCode(String.format("DEF_%d", i));
            def.setName("定义 " + i);
            def.setLevel(ConfigDefinition.ConfigLevel.values()[i % 3]);
            def.setSortOrder(i);
            def.setFields(List.of(field("f" + i)));
            defs.add(def);
        }
        return defs;
    }

    private static ConfigField field(String code) {
        ConfigField field = new ConfigField();
        field.setCode(code);
        field.setLabel(code);
        field.setFieldType(ConfigField.FieldType.STRING);
        return field;
    }
}
