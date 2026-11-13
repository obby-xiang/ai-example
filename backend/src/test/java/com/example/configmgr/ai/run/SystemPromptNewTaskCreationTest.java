package com.example.configmgr.ai.run;

import com.example.configmgr.ai.tool.AiToolNames;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SystemPromptNewTaskCreationTest {

    @Test
    void creationPromptKeepsActionPriorityWithoutReadOnlyPrerequisites() {
        String prompt = ResilientChatService.SYSTEM_PROMPT;

        assertThat(prompt).contains("用户明确要求创建任务时直接调用 create_task")
                .contains("已知配置名和条件直接复用")
                .contains("不得以任何只读工具作为创建前置")
                .contains("list_config_defs")
                .contains("get_config_def");
    }

    @Test
    void creationPromptKeepsZeroKnownInformationSemantics() {
        String prompt = ResilientChatService.SYSTEM_PROMPT;

        assertThat(prompt).contains("已知信息复用")
                .contains("直接用于对应工具")
                .contains("禁止重复收集")
                .contains("只有真实缺失或存在歧义时才做结构化补充");
    }

    @Test
    void detailedCreateSlotRulesLeaveSystemPromptAndRulePack() {
        String rules = PromptRulePacks.render(Set.of(AiToolNames.CREATE_TASK, AiToolNames.SET_CONDITIONS));

        for (String prompt : java.util.List.of(ResilientChatService.SYSTEM_PROMPT, rules)) {
            assertThat(prompt).contains("直接")
                    .doesNotContain("defCodes 与 conditions 独立判断")
                    .doesNotContain("单配置且条件已知")
                    .doesNotContain("多配置且条件已知")
                    .doesNotContain("用户提及的配置编码、名称或别名能唯一映射")
                    .doesNotContain("紧凑修饰也可构成条件")
                    .doesNotContain("只有用户明确把本次意图限定为创建后停止");
        }
    }
}
