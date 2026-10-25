package com.example.configmgr.ai.history;

import com.example.configmgr.ai.history.AiChatHistoryService.AppendCommand;
import com.example.configmgr.ai.history.AiChatHistoryService.ChatPage;
import com.example.configmgr.ai.history.AiChatHistoryService.MessageView;
import com.example.configmgr.ai.session.SessionPurgeGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 完整历史存储的<b>真实 H2</b>用例（真 Spring + Flyway + JPA + H2 mem）。
 *
 * <p>
 * 覆盖：60 轮可完整翻完、并发追加不重复/不跳号、同 run 多消息、工具/推理元数据 JSON 保真、
 * 会话物理删除、游标边界与无效 limit、V8 幂等键（重复 append 命中同一行）。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:ai-chat-history;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.ai.openai.api-key=",
        "app.ai.config.reload-interval=0s",
        "app.ai.resume.on-startup=false",
        "app.ai.kb.enabled=false" })
class AiChatHistoryServiceTest {

    private static final String SESSION = "sess-history";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private AiChatHistoryService history;

    @Autowired
    private AiChatMessageRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @MockitoBean
    private SessionPurgeGuard purgeGuard;

    @BeforeEach
    void clean() {
        this.repository.deleteAll();
    }

    @Test
    void v7CreatesTheHistoryTableAndExpectedConstraintsAndIndexes() {
        List<String> columns = this.jdbc.queryForList(
                "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'AI_CHAT_MESSAGE'", String.class);
        assertThat(columns).containsExactlyInAnyOrder("ID", "SESSION_ID", "RUN_ID", "SEQ", "MESSAGE_ID", "ROLE",
                "TEXT", "TOOL_CALLS_JSON", "TOOL_RESPONSES_JSON", "METADATA_JSON", "STOPPED", "CREATED_AT");
        assertThat(columns).noneMatch(name -> name.contains("KEY") || name.contains("SECRET") || name.contains("TOKEN"));

        Long unique = this.jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE TABLE_NAME = 'AI_CHAT_MESSAGE' "
                        + "AND CONSTRAINT_TYPE = 'UNIQUE' AND CONSTRAINT_NAME = 'UQ_AI_CHAT_MESSAGE_SESSION_SEQ'",
                Long.class);
        assertThat(unique).as("(session_id, seq) 唯一约束必须由 V8 建立").isEqualTo(1L);

        List<String> indexes = this.jdbc.queryForList(
                "SELECT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES WHERE TABLE_NAME = 'AI_CHAT_MESSAGE'", String.class);
        assertThat(indexes).contains("IX_AI_CHAT_MESSAGE_SESSION_RUN", "IX_AI_CHAT_MESSAGE_SESSION_MESSAGE",
                "UQ_AI_CHAT_MESSAGE_SESSION_RUN_MESSAGE");
    }

    @Test
    void v8AndV9ScriptsCanBeReplayedOnAnAlreadyMigratedDatabase() throws Exception {
        try (Connection connection = this.dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V8__ai_chat_message.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V8__ai_chat_message.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V9__ai_chat_message_idempotency.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V9__ai_chat_message_idempotency.sql"));
        }
        append("run-replay", "m-replay", "USER", "重放后仍可写", null, null, "{}", false);
        assertThat(this.history.count(SESSION)).isEqualTo(1);
    }

    @Test
    void duplicateAppendHitsTheSameRowByRunAndMessageId() {
        MessageView first = append("run-idem", "assistant-run-idem", "ASSISTANT", "最终回答", null, null,
                "{\"reasoningContent\":\"思考\"}", false);
        MessageView second = append("run-idem", "assistant-run-idem", "ASSISTANT", "最终回答", null, null,
                "{\"reasoningContent\":\"思考\"}", false);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.seq()).isEqualTo(first.seq());
        assertThat(this.history.count(SESSION)).isEqualTo(1);
    }

    @Test
    void stoppedFlagAndCreatedAtArePersisted() {
        MessageView row = append("run-stop", "assistant-stop", "ASSISTANT", "半截答案", null, null,
                "{\"stopped\":true}", true);
        assertThat(row.stopped()).isTrue();
        assertThat(row.createdAt()).isNotNull();

        MessageView reloaded = this.history.pageBefore(SESSION, null, 10).items().get(0);
        assertThat(reloaded.stopped()).isTrue();
        assertThat(reloaded.createdAt()).isNotNull();
    }

    @Test
    void sixtyRoundsCanBePagedThroughToTheOldestMessage() {
        for (int round = 1; round <= 60; round++) {
            append("run-" + round, "user-" + round, "USER", "问题 " + round, null, null, "{}", false);
            append("run-" + round, "assistant-" + round, "ASSISTANT", "回答 " + round, null, null,
                    "{\"reasoningContent\":\"思考 " + round + "\"}", false);
        }

        assertThat(this.history.count(SESSION)).isEqualTo(120);

        List<Long> seen = new ArrayList<>();
        Long cursor = null;
        int pages = 0;
        while (true) {
            ChatPage page = this.history.pageBefore(SESSION, cursor, 17);
            pages++;
            assertThat(page.items()).isNotEmpty();
            for (int i = 1; i < page.items().size(); i++) {
                assertThat(page.items().get(i - 1).seq())
                        .as("同一页必须稳定倒序")
                        .isGreaterThan(page.items().get(i).seq());
            }
            page.items().forEach(item -> seen.add(item.seq()));
            if (!page.hasMore()) {
                assertThat(page.nextCursor()).as("末页没有下一页游标").isNull();
                break;
            }
            assertThat(page.nextCursor()).isEqualTo(page.items().get(page.items().size() - 1).seq());
            cursor = page.nextCursor();
            assertThat(pages).isLessThan(20);
        }

        assertThat(pages).isEqualTo(8);
        assertThat(seen).hasSize(120).doesNotHaveDuplicates();
        assertThat(seen).isEqualTo(
                IntStream.rangeClosed(1, 120).mapToObj(i -> (long) i).sorted(Comparator.reverseOrder()).toList());
    }

    @Test
    void concurrentAppendsProduceContiguousUniqueSeq() throws Exception {
        int threads = 8;
        int perThread = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<Long>>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                final int worker = t;
                futures.add(pool.submit(() -> {
                    start.await();
                    List<Long> seqs = new ArrayList<>(perThread);
                    for (int i = 0; i < perThread; i++) {
                        seqs.add(append("run-concurrent", "msg-" + worker + "-" + i, "USER",
                                "并发 " + worker + "-" + i, null, null, "{}", false).seq());
                    }
                    return seqs;
                }));
            }
            start.countDown();

            List<Long> all = new ArrayList<>();
            for (Future<List<Long>> future : futures) {
                all.addAll(future.get(120, TimeUnit.SECONDS));
            }
            assertThat(all).hasSize(threads * perThread).doesNotHaveDuplicates();
            assertThat(all.stream().sorted().toList())
                    .as("成功提交的追加必须形成 1..N 连续 seq（冲突重试不得跳号）")
                    .isEqualTo(IntStream.rangeClosed(1, threads * perThread).mapToObj(i -> (long) i).toList());
            assertThat(this.history.count(SESSION)).isEqualTo(threads * perThread);
        }
        finally {
            pool.shutdownNow();
        }
    }

    @Test
    void oneRunMayPersistUserAssistantToolAndFinalMessagesWithoutConflict() {
        String runId = "run-multi";
        append(runId, "user-" + runId, "USER", "查任务", null, null, "{}", false);
        append(runId, "assistant-" + runId + "-tool-1", "ASSISTANT", "", "[" + toolCall("call-1") + "]",
                null, "{\"toolCallStates\":{\"call-1\":{\"status\":\"SUCCEEDED\"}}}", false);
        append(runId, "tool-call-1", "TOOL", null, null, "[" + toolResponse("call-1") + "]",
                "{\"stopped\":false}", false);
        append(runId, "assistant-" + runId, "ASSISTANT", "最终回答", null, null,
                "{\"reasoningContent\":\"最终思考\"}", false);

        List<MessageView> rows = this.history.pageAfter(SESSION, 0L, 10).items();
        assertThat(rows).extracting(MessageView::seq).containsExactly(1L, 2L, 3L, 4L);
        assertThat(rows).extracting(MessageView::runId).containsOnly(runId);
        assertThat(this.history.count(SESSION)).isEqualTo(4);
    }

    @Test
    void toolCallsToolResponsesAndReasoningMetadataRoundTripVerbatim() {
        String toolCalls = "[{\"id\":\"call-1\",\"type\":\"function\",\"name\":\"list_tasks\","
                + "\"arguments\":\"{\\\"page\\\":1}\",\"vendorExtra\":{\"n\":1}}]";
        String metadata = "{\"reasoningContent\":\"思考过程\",\"vendorUnknown\":{\"a\":[1,2],\"nested\":null},"
                + "\"unknownNumber\":3}";
        String toolResponses = "[{\"id\":\"call-1\",\"name\":\"list_tasks\","
                + "\"responseData\":\"{\\\"ok\\\":true}\",\"extra\":\"keep\"}]";

        append("run-json", "assistant-json", "ASSISTANT", "", toolCalls, null, metadata, false);
        append("run-json", "tool-json", "TOOL", null, null, toolResponses, "{\"stopped\":false}", false);

        List<MessageView> rows = this.history.pageAfter(SESSION, 0L, 10).items();
        assertThat(rows.get(0).toolCallsJson()).isEqualTo(toolCalls);
        assertThat(rows.get(0).metadataJson()).isEqualTo(metadata);
        assertThat(rows.get(1).toolResponsesJson()).isEqualTo(toolResponses);

        String storedMetadata = this.jdbc.queryForObject(
                "SELECT metadata_json FROM ai_chat_message WHERE session_id = ? AND seq = 1",
                (rs, rowNum) -> rs.getString(1), SESSION);
        assertThat(storedMetadata).as("未知推理元数据必须逐字保存，不做二次序列化").isEqualTo(metadata);
    }

    @Test
    void deleteSessionPhysicallyRemovesOnlyThatSessionAndIsIdempotent() {
        append("run-a", "user-a", "USER", "a", null, null, "{}", false, "sess-a");
        append("run-a", "assistant-a", "ASSISTANT", "a", null, null, "{}", false, "sess-a");
        append("run-a", "tool-a", "TOOL", null, null, null, "{}", false, "sess-a");
        append("run-b", "user-b", "USER", "b", null, null, "{}", false, "sess-b");
        append("run-b", "assistant-b", "ASSISTANT", "b", null, null, "{}", false, "sess-b");

        assertThat(this.history.deleteSession("sess-a")).isEqualTo(3);
        assertThat(this.history.count("sess-a")).isZero();
        assertThat(this.history.count("sess-b")).isEqualTo(2);
        assertThat(this.history.pageBefore("sess-a", null, 10).items()).isEmpty();

        Long physical = this.jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_chat_message WHERE session_id = 'sess-a'", Long.class);
        assertThat(physical).as("删除必须是物理 DELETE，不是软删标记").isZero();

        assertThat(this.history.deleteSession("sess-a")).as("重复删除幂等").isZero();
        assertThat(this.history.pageAfter("sess-b", 0L, 10).items()).extracting(MessageView::seq)
                .containsExactly(1L, 2L);
    }

    @Test
    void cursorBoundariesAreStable() {
        for (int i = 1; i <= 5; i++) {
            append("run-cursor", "m-" + i, "USER", "m" + i, null, null, "{}", false);
        }

        ChatPage latest = this.history.pageBefore(SESSION, null, 2);
        assertThat(latest.items()).extracting(MessageView::seq).containsExactly(5L, 4L);
        assertThat(latest.hasMore()).isTrue();
        assertThat(latest.nextCursor()).isEqualTo(4L);

        ChatPage older = this.history.pageBefore(SESSION, latest.nextCursor(), 2);
        assertThat(older.items()).extracting(MessageView::seq).containsExactly(3L, 2L);
        assertThat(older.hasMore()).isTrue();
        assertThat(older.nextCursor()).isEqualTo(2L);

        ChatPage oldest = this.history.pageBefore(SESSION, older.nextCursor(), 2);
        assertThat(oldest.items()).extracting(MessageView::seq).containsExactly(1L);
        assertThat(oldest.hasMore()).isFalse();
        assertThat(oldest.nextCursor()).isNull();

        assertThat(this.history.pageBefore(SESSION, 1L, 2).items()).isEmpty();
        assertThat(this.history.pageBefore(SESSION, 0L, 2).items()).isEmpty();

        ChatPage first = this.history.pageAfter(SESSION, null, 2);
        assertThat(first.items()).extracting(MessageView::seq).containsExactly(1L, 2L);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextCursor()).isEqualTo(2L);

        ChatPage next = this.history.pageAfter(SESSION, first.nextCursor(), 2);
        assertThat(next.items()).extracting(MessageView::seq).containsExactly(3L, 4L);
        assertThat(next.nextCursor()).isEqualTo(4L);

        ChatPage tail = this.history.pageAfter(SESSION, next.nextCursor(), 2);
        assertThat(tail.items()).extracting(MessageView::seq).containsExactly(5L);
        assertThat(tail.hasMore()).isFalse();
        assertThat(tail.nextCursor()).isNull();

        assertThat(this.history.pageAfter(SESSION, 5L, 2).items()).isEmpty();
        assertThat(this.history.pageAfter(SESSION, 0L, 2).items()).extracting(MessageView::seq)
                .containsExactly(1L, 2L);
    }

    @Test
    void invalidLimitsCursorsAndRequiredFieldsAreRejected() {
        assertThatThrownBy(() -> this.history.pageBefore(SESSION, null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
        assertThatThrownBy(() -> this.history.pageBefore(SESSION, null, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
        assertThatThrownBy(() -> this.history.pageBefore(SESSION, null, AiChatHistoryService.MAX_LIMIT + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
        assertThatThrownBy(() -> this.history.pageBefore(SESSION, -1L, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("beforeSeq");
        assertThatThrownBy(() -> this.history.pageAfter(SESSION, -1L, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("afterSeq");

        assertThatThrownBy(() -> append("run", "m", "USER", "x", null, null, "{}", false, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sessionId");
        assertThatThrownBy(() -> append("", "m", "USER", "x", null, null, "{}", false, SESSION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runId");
        assertThatThrownBy(() -> append("run", "", "USER", "x", null, null, "{}", false, SESSION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("messageId");
        assertThatThrownBy(() -> append("run", "m", "ROBOT", "x", null, null, "{}", false, SESSION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("role");
    }

    @Test
    void commandForUsesTheExistingMessageJsonFieldShape() throws Exception {
        UserMessage user = UserMessage.builder().text("你好").build();
        AppendCommand userCommand = this.history.commandFor("s", "r", "user-r", user, false);
        assertThat(userCommand.role()).isEqualTo("USER");
        assertThat(userCommand.text()).isEqualTo("你好");
        assertThat(userCommand.toolCallsJson()).isNull();
        assertThat(userCommand.toolResponsesJson()).isNull();
        assertThat(JSON.readTree(userCommand.metadataJson()).get("messageType").asText())
                .as("UserMessage 自带 messageType 元数据，原样保存")
                .isEqualTo("USER");
        assertThat(userCommand.stopped()).isFalse();

        AssistantMessage assistant = AssistantMessage.builder()
                .content("")
                .properties(Map.of("reasoningContent", "思考", "vendorUnknown", Map.of("k", "v")))
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "list_tasks",
                        "{\"page\":1}")))
                .build();
        AppendCommand assistantCommand = this.history.commandFor("s", "r", "assistant-r-step-1", assistant, false);
        assertThat(assistantCommand.role()).isEqualTo("ASSISTANT");
        JsonNode toolCalls = JSON.readTree(assistantCommand.toolCallsJson());
        assertThat(toolCalls.get(0).get("id").asText()).isEqualTo("call-1");
        assertThat(toolCalls.get(0).get("type").asText()).isEqualTo("function");
        assertThat(toolCalls.get(0).get("name").asText()).isEqualTo("list_tasks");
        assertThat(toolCalls.get(0).get("arguments").asText()).isEqualTo("{\"page\":1}");
        JsonNode metadata = JSON.readTree(assistantCommand.metadataJson());
        assertThat(metadata.get("reasoningContent").asText()).isEqualTo("思考");
        assertThat(metadata.get("vendorUnknown").get("k").asText()).isEqualTo("v");

        ToolResponseMessage tool = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "list_tasks",
                        "{\"ok\":true}")))
                .build();
        AppendCommand toolCommand = this.history.commandFor("s", "r", "tool-call-1", tool, false);
        assertThat(toolCommand.role()).isEqualTo("TOOL");
        assertThat(toolCommand.toolCallsJson()).isNull();
        JsonNode responses = JSON.readTree(toolCommand.toolResponsesJson());
        assertThat(responses.get(0).get("id").asText()).isEqualTo("call-1");
        assertThat(responses.get(0).get("name").asText()).isEqualTo("list_tasks");
        assertThat(responses.get(0).get("responseData").asText()).isEqualTo("{\"ok\":true}");

        AssistantMessage stopped = AssistantMessage.builder()
                .content("半截")
                .properties(Map.of("stopped", true))
                .build();
        AppendCommand stoppedCommand = this.history.commandFor("s", "r", "assistant-r-stopped", stopped, false);
        assertThat(stoppedCommand.stopped()).isTrue();
        assertThat(JSON.readTree(stoppedCommand.metadataJson()).get("stopped").asBoolean()).isTrue();
    }

    private MessageView append(String runId, String messageId, String role, String text, String toolCallsJson,
            String toolResponsesJson, String metadataJson, boolean stopped) {
        return append(runId, messageId, role, text, toolCallsJson, toolResponsesJson, metadataJson, stopped, SESSION);
    }

    private MessageView append(String runId, String messageId, String role, String text, String toolCallsJson,
            String toolResponsesJson, String metadataJson, boolean stopped, String sessionId) {
        return this.history.append(new AppendCommand(sessionId, runId, messageId, role, text, toolCallsJson,
                toolResponsesJson, metadataJson, stopped));
    }

    private static String toolCall(String id) {
        return "{\"id\":\"" + id + "\",\"type\":\"function\",\"name\":\"list_tasks\","
                + "\"arguments\":\"{\\\"page\\\":1}\"}";
    }

    private static String toolResponse(String id) {
        return "{\"id\":\"" + id + "\",\"name\":\"list_tasks\",\"responseData\":\"{\\\"ok\\\":true}\"}";
    }
}
