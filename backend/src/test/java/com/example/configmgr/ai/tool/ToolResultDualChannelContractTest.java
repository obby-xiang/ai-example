package com.example.configmgr.ai.tool;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G5 双通道对齐用例：<b>同一份工具结果的模型侧与帧侧截断判定必须一致</b>。
 *
 * <h2>被锁住的契约</h2>
 * <ol>
 * <li>两侧共用一套字段名（{@code truncated/strategy/originalChars/returnedChars/notice}）；</li>
 * <li><b>模型侧 true ⇒ 帧侧 true</b>：模型只看到 20000 字符时，前端也必须看到截断标记
 *     （此前模型侧静默裁到 8000、帧侧 {@code truncated=false}，人机看到的是两份不同的事实）；</li>
 * <li>帧侧 {@code result} 仍是<b>全文</b>（T2 双通道分离不变），标记只说明"模型视图被裁过"；</li>
 * <li>两侧的 {@code originalChars/returnedChars} 逐值相等 —— 决策来自同一份配置与同一段文本。</li>
 * </ol>
 */
class ToolResultDualChannelContractTest {

    private static final String RUN_ID = "run-dual-channel";

    private static final String TOOL = "list_config_defs";

    private final AiProperties properties = new AiProperties();

    private final ObjectMapper mapper = new ObjectMapper();

    private final InMemoryRunStore store = new InMemoryRunStore();

    private final RunRegistry registry = new RunRegistry(this.store, this.mapper, this.properties);

    private final SseChatEmitter out = this.registry.of(RUN_ID);

    private final ToolResultLimiter limiter = new ToolResultLimiter(this.properties);

    @Test
    void modelSideTruncationIsReportedOnTheFrameToo() throws Exception {
        String big = "x".repeat(30_000);

        this.out.toolResult(pending("c-1"), true, false, big);

        Map<String, Object> frame = this.store.events(RUN_ID).get(0);
        assertThat(frame).containsEntry(TruncationContract.TRUNCATED_FIELD, true)
            .containsEntry(TruncationContract.STRATEGY_FIELD, "head");
        assertThat(frame.get(TruncationContract.ORIGINAL_CHARS_FIELD)).isEqualTo(30_000);
        assertThat((Integer) frame.get(TruncationContract.RETURNED_CHARS_FIELD)).isLessThanOrEqualTo(20_000);
        assertThat((String) frame.get("result")).as("前端帧仍是全文").hasSize(30_000);
        assertThat(String.valueOf(frame.get(TruncationContract.NOTICE_FIELD))).isNotBlank();
        assertThat(frame.get(TruncationContract.LEGACY_REASON_FIELD))
            .as("truncatedReason 是 notice 的兼容别名（前端角标仍可读）").isEqualTo(frame.get(TruncationContract.NOTICE_FIELD));

        Map<String, Object> model = modelEnvelope(big);
        assertThat(model).containsEntry(TruncationContract.TRUNCATED_FIELD, true)
            .containsEntry(TruncationContract.STRATEGY_FIELD, frame.get(TruncationContract.STRATEGY_FIELD));
        assertThat(model.get(TruncationContract.ORIGINAL_CHARS_FIELD)).isEqualTo(frame.get(TruncationContract.ORIGINAL_CHARS_FIELD));
        assertThat(model.get(TruncationContract.RETURNED_CHARS_FIELD)).isEqualTo(frame.get(TruncationContract.RETURNED_CHARS_FIELD));
    }

    @Test
    void bothChannelsAgreeWhenUnderBudget() throws Exception {
        String small = "15 个配置定义";

        this.out.toolResult(pending("c-1"), true, false, small);

        Map<String, Object> frame = this.store.events(RUN_ID).get(0);
        assertThat(frame).doesNotContainKey(TruncationContract.TRUNCATED_FIELD)
            .doesNotContainKey(TruncationContract.STRATEGY_FIELD);
        assertThat(frame.get("result")).isEqualTo(small);

        Map<String, Object> model = modelEnvelope(small);
        assertThat(model).containsEntry(TruncationContract.TRUNCATED_FIELD, false);
        assertThat(model.get(TruncationContract.ORIGINAL_CHARS_FIELD)).isEqualTo(small.length());
        assertThat(model.get(TruncationContract.RETURNED_CHARS_FIELD)).isEqualTo(small.length());
    }

    @Test
    void structuredResultIsNeverHalfCutOnEitherChannel() throws Exception {
        StringBuilder sb = new StringBuilder("{\"rows\":[");
        // 700 条 ≈ 4.7 万字节：超模型侧 20000 字符、仍在单帧 64KB 以内（帧侧不做字节截断）
        for (int i = 1; i <= 700; i++) {
            if (i > 1) {
                sb.append(',');
            }
            sb.append("{\"code\":\"C-").append(i).append("\",\"title\":\"范围外租户配置定义 ").append(i).append("\"}");
        }
        sb.append("]}");
        String json = sb.toString();

        this.out.toolResult(pending("c-1"), true, false, json);

        Map<String, Object> frame = this.store.events(RUN_ID).get(0);
        assertThat(frame).containsEntry(TruncationContract.TRUNCATED_FIELD, true);
        assertThat((String) frame.get("result")).as("帧侧结果仍是完整的原 JSON").isEqualTo(json);
        this.mapper.readTree((String) frame.get("result"));

        Map<String, Object> model = modelEnvelope(json);
        assertThat(model).containsEntry(TruncationContract.TRUNCATED_FIELD, true);
        String message = String.valueOf(model.get("message"));
        assertThat(this.mapper.readTree(message).path("truncated").asBoolean())
            .as("模型侧 message 被整体标记成合法 JSON（不是半截 JSON）").isTrue();
        assertThat(message).doesNotContain("C-700");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> modelEnvelope(String text) throws Exception {
        String json = ToolResultEnvelope.of(ToolOutcome.OK, null, text, "{}").toModelJson(this.limiter, TOOL);
        return this.mapper.readValue(ToolResultEnvelope.unwrapModelJson(json), Map.class);
    }

    private static PendingToolCall pending(String toolCallId) {
        PendingToolCall pending = new PendingToolCall();
        pending.setToolCallId(toolCallId);
        pending.setName(TOOL);
        pending.setKind(PendingToolCall.KIND_BACKEND);
        pending.setStatus(PendingToolCall.EXECUTED);
        return pending;
    }

}
