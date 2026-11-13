package com.example.configmgr.ai.run;

import com.example.configmgr.ai.tool.AiToolNames;
import com.example.configmgr.ai.tool.ToolDefinitionOverride;
import com.example.configmgr.ai.tool.YamlToolDefinitionSource;
import com.example.configmgr.ai.tools.AiTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PromptRulePacksTest {

    @Test
    void createTaskRulesKeepOnlyActionPriorityAndKnownInformation() {
        String rules = PromptRulePacks.render(Set.of(AiToolNames.CREATE_TASK, AiToolNames.SET_CONDITIONS));

        assertThat(rules).contains("用户明确新建时直接 create_task")
                .contains("不先用列表摸底")
                .contains("已知")
                .doesNotContain("defCodes 与 conditions 独立判断")
                .doesNotContain("单配置且条件已知")
                .doesNotContain("多配置且条件已知")
                .doesNotContain("紧凑修饰也可构成条件")
                .doesNotContain("逐项各调用一次");
    }

    @Test
    void conditionRulesUseHostModeAndSingleBatchWithoutPerItemCalls() {
        String rules = PromptRulePacks.render(Set.of(AiToolNames.SET_CONDITIONS, AiToolNames.CONFIRM_STEP));

        assertThat(rules).contains("conditionMode=ALL/CUSTOM")
                .contains("ALL 或 CUSTOM 留空均由宿主直接 confirm_step")
                .contains("一次 set_conditions(items)")
                .doesNotContain("create_task 的 {} 只表示条件未决定")
                .doesNotContain("逐项各调用一次")
                .doesNotContain("单个非必填自由文本框");
    }

    @Test
    void toolDescriptionsAndYamlUseCurrentConditionWorkflowAndVersion() throws NoSuchMethodException {
        YamlToolDefinitionSource yamlSource = new YamlToolDefinitionSource();
        List<ToolDefinitionOverride> overrides = List.of(yamlSource.load().find(AiToolNames.CREATE_TASK),
                yamlSource.load().find(AiToolNames.CONFIRM_STEP));
        List<Tool> annotations = List.of(
                AiTools.class.getMethod("createTask", String.class, String.class, String[].class,
                        AiTools.FieldQueryCondition.class, Boolean.class, Boolean.class,
                        org.springframework.ai.chat.model.ToolContext.class).getAnnotation(Tool.class),
                AiTools.class.getMethod("confirmStep", String.class).getAnnotation(Tool.class));

        for (ToolDefinitionOverride override : overrides) {
            assertThat(override.description()).contains("conditionMode=ALL/CUSTOM")
                    .contains("ALL 或 CUSTOM 留空均由宿主直接 confirm_step")
                    .doesNotContain("单个非必填自由文本框");
        }
        for (Tool annotation : annotations) {
            assertThat(annotation.description()).contains("conditionMode=ALL/CUSTOM")
                    .contains("ALL 或 CUSTOM 留空均由宿主直接 confirm_step")
                    .doesNotContain("单个非必填自由文本框");
        }
        assertThat(yamlSource.version()).isEqualTo("2026-10-27.41");
    }
}
