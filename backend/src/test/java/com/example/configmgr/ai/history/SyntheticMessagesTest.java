package com.example.configmgr.ai.history;

import com.example.configmgr.ai.gate.SpToolCallingManager;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SyntheticMessagesTest {

    @Test
    void explicitOriginKindAndMessageIdIdentifySyntheticMessagesWithoutMatchingText() {
        Message internal = UserMessage.builder().text("内部指令正文可以随时调整").metadata(Map.of(
                "origin", "SYNTHETIC",
                "kind", "correction",
                "messageId", "export-wrap-up")).build();
        Message realUser = new UserMessage(SyntheticMessages.EXPORT_CORRECTION_PREFIX + "这是用户自己输入的相同前缀");

        assertThat(SyntheticMessages.kindOf(internal)).isEqualTo(SyntheticMessages.Kind.CORRECTION);
        assertThat(SyntheticMessages.kindOf(realUser)).isNull();
        assertThat(SyntheticMessages.withoutCorrectionInstruction(List.of(internal, realUser)))
                .containsExactly(realUser);
    }

    @Test
    void currentExportConditionStepAndBudgetInstructionsKeepTheirMetadataThroughJson() {
        List<Message> messages = List.of(
                SyntheticMessages.internalUser(SyntheticMessages.EXPORT_CORRECTION_PREFIX + "导出纠正",
                        SyntheticMessages.Kind.CORRECTION, "export-wrap-up"),
                SyntheticMessages.internalUser(SyntheticMessages.EXPORT_CORRECTION_PREFIX + "条件交棒",
                        SyntheticMessages.Kind.CORRECTION, "condition-handoff"),
                SyntheticMessages.internalUser(SpToolCallingManager.STEP_LIMIT_WRAP_UP,
                        SyntheticMessages.Kind.STEP_LIMIT, "step-limit"),
                SyntheticMessages.internalUser(SpToolCallingManager.TURN_BUDGET_WRAP_UP,
                        SyntheticMessages.Kind.TURN_BUDGET, "turn-budget"));
        MessageJsonCodec codec = new MessageJsonCodec(new ObjectMapper());

        List<Message> restored = codec.deserializeAll(codec.serializeAll(messages));

        assertThat(restored).extracting(SyntheticMessages::kindOf).containsExactly(
                SyntheticMessages.Kind.CORRECTION, SyntheticMessages.Kind.CORRECTION,
                SyntheticMessages.Kind.STEP_LIMIT, SyntheticMessages.Kind.TURN_BUDGET);
        assertThat(restored).extracting(message -> message.getMetadata().get(SyntheticMessages.MESSAGE_ID))
                .containsExactly("export-wrap-up", "condition-handoff", "step-limit", "turn-budget");
        assertThat(SyntheticMessages.withoutCorrectionInstruction(restored))
                .extracting(SyntheticMessages::kindOf)
                .containsExactly(SyntheticMessages.Kind.STEP_LIMIT, SyntheticMessages.Kind.TURN_BUDGET);
    }

    @Test
    void legacySummaryPlaceholderAliasIsRemoved() {
        assertThat(java.util.Arrays.stream(com.example.configmgr.ai.context.RollingSummarizer.class.getDeclaredFields()))
                .noneMatch(field -> "SUMMARY_PLACEHOLDER".equals(field.getName()));
    }

}
