package com.example.configmgr.ai.history;

import com.example.configmgr.ai.history.AiChatHistoryService.MessageView;
import com.example.configmgr.ai.run.ResilientChatService;
import com.example.configmgr.ai.session.SessionPurgeGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 第二阶段：完整历史从聊天落定点到游标接口的<b>真实 H2</b>集成路面。
 *
 * <p>覆盖：重复 converge 不重复插入、工具 assistant 与 final assistant 不撞 messageId、
 * 重启重放不重复、历史文本/工具响应与源消息一致、取消半截、新游标接口分页与现有 history 形状。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:ai-chat-history-phase2;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.ai.openai.api-key=",
        "app.ai.config.reload-interval=0s",
        "app.ai.resume.on-startup=false",
        "app.ai.kb.enabled=false" })
class AiChatHistoryRecorderTest {

    private static final String SESSION = "sess-phase2";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private AiChatHistoryRecorder recorder;

    @Autowired
    private AiChatHistoryService history;

    @Autowired
    private AiChatMessageRepository repository;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SessionPurgeGuard purgeGuard;

    @BeforeEach
    void clean() {
        this.repository.deleteAll();
    }

    @Test
    void repeatedConvergeDoesNotInsertDuplicates() {
        String runId = "run-converge";
        UserMessage user = user("查任务");
        AssistantMessage toolAssistant = toolAssistant("call-1");
        ToolResponseMessage toolResponse = toolResponse("call-1", "{\"ok\":true}");
        AssistantMessage finalAssistant = finalAssistant("最终回答");
        List<Message> runHistory = List.of(user, toolAssistant, toolResponse, finalAssistant);

        this.recorder.recordUser(SESSION, runId, user);
        this.recorder.recordRunTail(SESSION, runId, runHistory);
        this.recorder.recordRunTail(SESSION, runId, runHistory);

        assertThat(this.history.count(SESSION)).isEqualTo(4);
        List<MessageView> rows = this.history.pageAfter(SESSION, 0L, 10).items();
        assertThat(rows).extracting(MessageView::seq).containsExactly(1L, 2L, 3L, 4L);
        assertThat(rows).extracting(MessageView::role).containsExactly("USER", "ASSISTANT", "TOOL", "ASSISTANT");
    }

    @Test
    void toolAssistantAndFinalAssistantUseDistinctMessageIds() {
        String runId = "run-ids";
        this.recorder.recordToolRound(SESSION, runId, toolAssistant("call-1"),
                toolResponse("call-1", "{\"ok\":true}"));
        this.recorder.recordFinalAssistant(SESSION, runId, finalAssistant("最终回答"));

        List<MessageView> rows = this.history.pageAfter(SESSION, 0L, 10).items();
        assertThat(rows).extracting(MessageView::messageId)
                .containsExactly("assistant-" + runId + "-tool-call-1", "tool-call-1",
                        "assistant-" + runId);
        assertThat(rows).extracting(MessageView::messageId).doesNotHaveDuplicates();
    }

    @Test
    void truncatedFinalAssistantMetadataIsPersistedAndExposed() throws Exception {
        AssistantMessage truncated = AssistantMessage.builder()
                .content("被截断的回答")
                .properties(Map.of(ResilientChatService.TRUNCATED_KEY, true))
                .build();
        AssistantMessage stopped = AssistantMessage.builder()
                .content("停掉的半截")
                .properties(Map.of(ResilientChatService.STOPPED_KEY, true))
                .build();

        this.recorder.recordFinalAssistant(SESSION, "run-truncated", truncated);
        this.recorder.recordFinalAssistant(SESSION, "run-stopped", stopped);

        List<MessageView> rows = this.history.pageAfter(SESSION, 0L, 10).items();
        assertThat(rows).hasSize(2);
        JsonNode truncatedMeta = JSON.readTree(rows.get(0).metadataJson());
        assertThat(truncatedMeta.path(ResilientChatService.TRUNCATED_KEY).asBoolean()).isTrue();
        JsonNode stoppedMeta = JSON.readTree(rows.get(1).metadataJson());
        assertThat(stoppedMeta.has(ResilientChatService.TRUNCATED_KEY)).isFalse();

        this.mockMvc.perform(get("/api/ai/history/" + SESSION + "/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages[0].stopped").value(true))
                .andExpect(jsonPath("$.data.messages[0].truncated").doesNotExist())
                .andExpect(jsonPath("$.data.messages[1].truncated").value(true))
                .andExpect(jsonPath("$.data.messages[1].stopped").doesNotExist());
    }

    @Test
    void restartReplayDoesNotInsertDuplicates() {
        String runId = "run-restart";
        AssistantMessage toolAssistant = toolAssistant("call-1");
        ToolResponseMessage toolResponse = toolResponse("call-1", "{\"ok\":true}");
        AssistantMessage finalAssistant = finalAssistant("最终回答");

        this.recorder.recordToolRound(SESSION, runId, toolAssistant, toolResponse);
        this.recorder.recordFinalAssistant(SESSION, runId, finalAssistant);
        long before = this.history.count(SESSION);

        // 模拟重启后的新实例：同一批消息按原身份重放。
        AiChatHistoryRecorder restarted = new AiChatHistoryRecorder(this.history);
        restarted.recordToolRound(SESSION, runId, toolAssistant, toolResponse);
        restarted.recordFinalAssistant(SESSION, runId, finalAssistant);

        assertThat(this.history.count(SESSION)).isEqualTo(before).isEqualTo(3);
    }

    @Test
    void recordedTextAndToolResponseMatchSourceMessages() throws Exception {
        String runId = "run-text";
        AssistantMessage finalAssistant = finalAssistant("最终回答");
        this.recorder.recordToolRound(SESSION, runId, toolAssistant("call-1"),
                toolResponse("call-1", "{\"ok\":true}"));
        this.recorder.recordFinalAssistant(SESSION, runId, finalAssistant);

        List<MessageView> rows = this.history.pageAfter(SESSION, 0L, 10).items();
        MessageView assistantRow = rows.get(0);
        MessageView toolRow = rows.get(1);
        MessageView finalRow = rows.get(2);

        assertThat(assistantRow.text()).isEmpty();
        assertThat(toolRow.toolResponsesJson()).contains("\"responseData\":\"{\\\"ok\\\":true}\"");
        assertThat(finalRow.text()).isEqualTo("最终回答");

        Message reconstructedFinal = this.history.toMessage(finalRow);
        assertThat(reconstructedFinal.getText()).isEqualTo("最终回答");
        assertThat(reconstructedFinal.getMetadata()).containsEntry("reasoningContent", "最终思考");

        Message reconstructedTool = this.history.toMessage(toolRow);
        assertThat(reconstructedTool).isInstanceOf(ToolResponseMessage.class);
        ToolResponseMessage toolMessage = (ToolResponseMessage) reconstructedTool;
        assertThat(toolMessage.getResponses()).hasSize(1);
        assertThat(toolMessage.getResponses().get(0).id()).isEqualTo("call-1");
        assertThat(toolMessage.getResponses().get(0).responseData()).isEqualTo("{\"ok\":true}");

        JsonNode rowJson = JSON.readTree(toolRow.toolResponsesJson());
        assertThat(rowJson.get(0).get("responseData").asText()).isEqualTo("{\"ok\":true}");
    }

    @Test
    void stoppedMessagesPersistWithStoppedFlagAndPairing() {
        String runId = "run-stop";
        AssistantMessage stopped = AssistantMessage.builder()
                .content("半截")
                .properties(Map.of("stopped", true, "reasoningContent", "思考到一半"))
                .build();
        ToolResponseMessage cancelledTool = toolResponse("call-1", "{\"outcome\":\"cancelled\"}");

        this.recorder.recordStopped(SESSION, runId, List.of(stopped, cancelledTool));

        List<MessageView> rows = this.history.pageAfter(SESSION, 0L, 10).items();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).role()).isEqualTo("ASSISTANT");
        assertThat(rows.get(0).stopped()).isTrue();
        assertThat(rows.get(0).messageId()).isEqualTo("assistant-" + runId);
        assertThat(rows.get(1).role()).isEqualTo("TOOL");
        assertThat(rows.get(1).stopped()).isFalse();
        assertThat(rows.get(1).messageId()).isEqualTo("tool-call-1");
    }

    @Test
    void fullHistoryEndpointPagesWithCursorsAndKeepsHistoryItemShape() throws Exception {
        for (int i = 1; i <= 5; i++) {
            String runId = "run-page-" + i;
            this.recorder.recordUser(SESSION, runId, user("问题 " + i));
            this.recorder.recordFinalAssistant(SESSION, runId, finalAssistant("回答 " + i));
        }

        MvcResult first = this.mockMvc
                .perform(get("/api/ai/history/{sessionId}/messages", SESSION).param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.count").value(2))
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andExpect(jsonPath("$.data.direction").value("before"))
                .andExpect(jsonPath("$.data.messages[0].seq").value(10))
                .andExpect(jsonPath("$.data.messages[0].type").value("ASSISTANT"))
                .andExpect(jsonPath("$.data.messages[0].text").value("回答 5"))
                .andExpect(jsonPath("$.data.messages[0].runId").value("run-page-5"))
                .andExpect(jsonPath("$.data.messages[0].messageId").value("assistant-run-page-5"))
                .andReturn();
        JsonNode firstBody = JSON.readTree(first.getResponse().getContentAsString()).path("data");
        long nextCursor = firstBody.path("nextCursor").asLong();
        assertThat(nextCursor).isPositive();

        this.mockMvc
                .perform(get("/api/ai/history/{sessionId}/messages", SESSION)
                        .param("beforeSeq", String.valueOf(nextCursor)).param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(8))
                .andExpect(jsonPath("$.data.hasMore").value(false))
                .andExpect(jsonPath("$.data.messages[7].text").value("问题 1"));

        this.mockMvc
                .perform(get("/api/ai/history/{sessionId}/messages", SESSION)
                        .param("afterSeq", "0").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.direction").value("after"))
                .andExpect(jsonPath("$.data.messages[0].seq").value(1))
                .andExpect(jsonPath("$.data.messages[0].text").value("问题 1"));

        this.mockMvc
                .perform(get("/api/ai/history/{sessionId}/messages", SESSION)
                        .param("beforeSeq", "2").param("afterSeq", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sixtyRoundsCanBePagedThroughTheFullHistoryEndpoint() throws Exception {
        for (int round = 1; round <= 60; round++) {
            String runId = "run-60-" + round;
            this.recorder.recordUser(SESSION, runId, user("问题 " + round));
            this.recorder.recordFinalAssistant(SESSION, runId, finalAssistant("回答 " + round));
        }

        List<Long> seen = new ArrayList<>();
        Long beforeSeq = null;
        int pages = 0;
        while (true) {
            var request = get("/api/ai/history/{sessionId}/messages", SESSION).param("limit", "17");
            if (beforeSeq != null) {
                request.param("beforeSeq", String.valueOf(beforeSeq));
            }
            MvcResult result = this.mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn();
            JsonNode data = JSON.readTree(result.getResponse().getContentAsString()).path("data");
            int count = data.path("count").asInt();
            assertThat(count).isPositive();
            for (JsonNode message : data.path("messages")) {
                seen.add(message.path("seq").asLong());
            }
            pages++;
            if (!data.path("hasMore").asBoolean()) {
                assertThat(data.path("nextCursor").isNull() || data.path("nextCursor").isMissingNode()).isTrue();
                break;
            }
            beforeSeq = data.path("nextCursor").asLong();
            assertThat(pages).isLessThan(20);
        }

        assertThat(seen).hasSize(120);
        assertThat(new HashSet<>(seen)).hasSize(120);
        assertThat(seen).isSortedAccordingTo((a, b) -> Long.compare(b, a));
        assertThat(seen).containsExactlyElementsOf(
                java.util.stream.LongStream.rangeClosed(1, 120).boxed()
                        .sorted(java.util.Comparator.reverseOrder()).toList());
    }

    @Test
    void turnBudgetWrapUpIsSyntheticAndNotRecordedAsRealUser() {
        String runId = "run-budget";
        UserMessage realUser = user("真实问题");
        UserMessage wrapUp = new UserMessage(com.example.configmgr.ai.gate.SpToolCallingManager.TURN_BUDGET_WRAP_UP);

        this.recorder.recordRunTail(SESSION, runId, List.of(realUser, wrapUp, finalAssistant("收尾回答")));

        List<MessageView> rows = this.history.pageAfter(SESSION, 0L, 10).items();
        assertThat(rows).extracting(MessageView::role).containsExactly("USER", "ASSISTANT");
        assertThat(rows.get(0).text()).isEqualTo("真实问题");
        assertThat(rows).extracting(MessageView::text)
                .doesNotContain(com.example.configmgr.ai.gate.SpToolCallingManager.TURN_BUDGET_WRAP_UP);
    }

    /**
     * 批次2 / AR-01 第 5 条：合成判据<b>只有一份</b>（{@link SyntheticMessages}）—— 写侧
     * （"最后一个真实 user"边界）与读侧（{@code /history} 的 {@code synthetic=true}）必须同源。
     * 这里直接锁判据本身：摘要占位与两种收尾提示都是合成，普通用户文本不是。
     */
    @Test
    void syntheticPredicateCoversSummaryAndWrapUpButNotRealUserText() {
        assertThat(SyntheticMessages.isSynthetic(user(SyntheticMessages.SUMMARY_PLACEHOLDER))).isTrue();
        assertThat(SyntheticMessages.isSynthetic(user(SyntheticMessages.STEP_LIMIT_PREFIX + "（10 步）"))).isTrue();
        assertThat(SyntheticMessages.isSynthetic(
                user(com.example.configmgr.ai.gate.SpToolCallingManager.TURN_BUDGET_WRAP_UP))).isTrue();
        assertThat(SyntheticMessages.isSynthetic(
                AssistantMessage.builder().content("摘要").properties(Map.of("compactedFrom", 7)).build()))
            .isTrue();
        assertThat(SyntheticMessages.isSynthetic(user("真的问题"))).isFalse();
        assertThat(SyntheticMessages.isSynthetic(finalAssistant("真的回答"))).isFalse();
    }

    private static UserMessage user(String text) {
        return UserMessage.builder().text(text).build();
    }

    private static AssistantMessage toolAssistant(String toolCallId) {
        return AssistantMessage.builder()
            .content("")
            .properties(Map.of("reasoningContent", "工具前思考"))
            .toolCalls(List.of(new AssistantMessage.ToolCall(toolCallId, "function", "list_tasks",
                    "{\"page\":1}")))
            .build();
    }

    private static ToolResponseMessage toolResponse(String toolCallId, String responseData) {
        return ToolResponseMessage.builder()
            .responses(List.of(new ToolResponseMessage.ToolResponse(toolCallId, "list_tasks", responseData)))
            .build();
    }

    private static AssistantMessage finalAssistant(String text) {
        return AssistantMessage.builder()
            .content(text)
            .properties(Map.of("reasoningContent", "最终思考"))
            .build();
    }
}
