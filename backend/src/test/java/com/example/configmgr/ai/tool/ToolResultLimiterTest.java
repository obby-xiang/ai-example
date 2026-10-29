package com.example.configmgr.ai.tool;

import com.example.configmgr.ai.config.AiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工具结果<b>回填模型</b>前的预算用例（G5 双通道对齐：
 * {@code app.ai.result-budget.model.*}）。
 *
 * <p>
 * 五条被锁住的契约：
 * <ol>
 * <li><b>默认档 20000 字符</b>（具名工具），<b>无名/未知工具兜底 8000</b>，{@code per-tool} 可逐工具覆盖；</li>
 * <li>行数上限 200 仍然生效（JSON 转义形态也算行）；</li>
 * <li><b>截断标记计入预算</b>：返回文本总长 ≤ 上限（上限小到装不下标记时只返回标记）；</li>
 * <li><b>结构化结果（JSON）不做字符截断</b>：整体标记成一份合法 JSON（含 preview），绝不产出半截 JSON；</li>
 * <li>统一截断字段契约：{@code truncated/strategy/originalChars/returnedChars/notice} 由
 * {@link ToolResultLimiter.Truncation} 一次给出，帧侧同名字段复用同一判定。</li>
 * </ol>
 */
class ToolResultLimiterTest {

    private static final String TOOL = "list_config_defs";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AiProperties properties = new AiProperties();

    private final ToolResultLimiter limiter = new ToolResultLimiter(this.properties);

    // ── ① 默认档 / 兜底档 / 逐工具覆盖 ───────────────────────────────────

    @Test
    void defaultBudgetIs200RowsAnd20000CharsForNamedTools() {
        AiProperties.ResultBudget.Model model = this.properties.getResultBudget().getModel();
        assertThat(model.getMaxRows()).isEqualTo(200);
        assertThat(model.getDefaultMaxChars()).as("G5：默认档 8000 → 20000").isEqualTo(20_000);
        assertThat(model.getFallbackMaxChars()).as("未知工具兜底档保留 8000").isEqualTo(8_000);

        ToolResultLimiter.Truncation truncation = this.limiter.forModel(TOOL, "x".repeat(30_000));

        assertThat(truncation.truncated()).isTrue();
        assertThat(truncation.text()).hasSizeLessThanOrEqualTo(20_000);
        assertThat(truncation.originalChars()).isEqualTo(30_000);
        assertThat(truncation.returnedChars()).isEqualTo(truncation.text().length());
    }

    @Test
    void unknownOrMissingToolNameFallsBackTo8000() {
        ToolResultLimiter.Truncation unnamed = this.limiter.forModel(null, "x".repeat(9_000));
        ToolResultLimiter.Truncation blank = this.limiter.forModel("  ", "x".repeat(9_000));

        assertThat(unnamed.truncated()).isTrue();
        assertThat(unnamed.text()).hasSizeLessThanOrEqualTo(8_000);
        assertThat(blank.truncated()).isTrue();
        assertThat(blank.text()).hasSizeLessThanOrEqualTo(8_000);
    }

    @Test
    void unregisteredToolNameAlsoFallsBackTo8000() {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getMeta(TOOL)).thenReturn(new ToolMeta());
        ToolResultLimiter aware = new ToolResultLimiter(this.properties, registry);

        assertThat(aware.forModel(TOOL, "x".repeat(25_000)).text()).as("已注册工具仍走默认档 20000")
            .hasSizeLessThanOrEqualTo(20_000);
        ToolResultLimiter.Truncation ghost = aware.forModel("ghost_tool", "x".repeat(9_000));
        assertThat(ghost.truncated()).isTrue();
        assertThat(ghost.text()).as("未注册工具按未知处理 ⇒ 兜底 8000").hasSizeLessThanOrEqualTo(8_000);
    }

    @Test
    void perToolOverrideWinsOverDefaultAndFallback() {
        this.properties.getResultBudget().getModel().getPerTool().put("read_doc", 500);

        assertThat(this.limiter.forModel("read_doc", "x".repeat(1_000)).text()).hasSizeLessThanOrEqualTo(500);
        assertThat(this.limiter.forModel(TOOL, "x".repeat(1_000)).truncated()).as("别的具名工具仍走默认档")
            .isFalse();
        assertThat(this.limiter.forModel(TOOL, "x".repeat(25_000)).text()).hasSizeLessThanOrEqualTo(20_000);
    }

    // ── ② 行数上限 ──────────────────────────────────────────────────────

    @Test
    void truncatesByRowsAndMarksIt() {
        // 每行短 ⇒ 字符不超 20000，只触发行数闸
        String text = rows(250);

        ToolResultLimiter.Truncation truncation = this.limiter.forModel(TOOL, text);

        assertThat(truncation.truncated()).isTrue();
        assertThat(truncation.strategy()).isEqualTo("head");
        assertThat(truncation.text()).startsWith(rows(200));
        assertThat(truncation.text()).contains(ToolResultLimiter.TRUNCATION_MARKER)
            .contains("原 250 行")
            .contains("仅前 200 行");
        assertThat(this.limiter.truncated(truncation.text())).isTrue();
    }

    /**
     * 后端工具结果的**实际形态**（实测）：官方 {@code DefaultToolCallResultConverter}
     * 把方法返回值序列化成 JSON 字符串，换行成了字面两字符 {@code \n}。
     * 行数上限必须在这种形态下也生效（否则行数限制在真实链路里形同虚设）。
     */
    @Test
    void truncatesRowsOnJsonEscapedText() {
        this.properties.getResultBudget().getModel().setMaxRows(3);
        String escaped = "- A\\n- B\\n- C\\n- D\\n- E";

        String limited = this.limiter.forModel(TOOL, escaped).text();

        assertThat(limited).startsWith("- A\\n- B\\n- C\\n");
        assertThat(limited).contains("原 5 行").contains("仅前 3 行");
        assertThat(limited).doesNotContain("- D");
    }

    // ── ③ 字符闸：标记计入预算 ──────────────────────────────────────────

    @Test
    void markerLengthIsCountedInTheBudget() {
        this.properties.getResultBudget().getModel().getPerTool().put(TOOL, 2_000);

        ToolResultLimiter.Truncation truncation = this.limiter.forModel(TOOL, "x".repeat(5_000));

        assertThat(truncation.text()).as("截断标记本身占用的字符也在 2000 预算内").hasSizeLessThanOrEqualTo(2_000);
        assertThat(truncation.text()).startsWith("x").contains(ToolResultLimiter.TRUNCATION_MARKER);
        assertThat(truncation.returnedChars()).isEqualTo(truncation.text().length());
        assertThat(truncation.notice()).doesNotStartWith("\n").contains("结果已截断");
    }

    @Test
    void markerOnlyWhenTheBudgetCannotHoldAnyContent() {
        this.properties.getResultBudget().getModel().getPerTool().put(TOOL, 50);

        ToolResultLimiter.Truncation truncation = this.limiter.forModel(TOOL, "x".repeat(500));

        assertThat(truncation.truncated()).isTrue();
        assertThat(truncation.text().trim()).as("预算装不下正文 ⇒ 只给标记（绝不静默丢弃）")
            .startsWith(ToolResultLimiter.TRUNCATION_MARKER.trim());
        assertThat(truncation.text().length()).as("标记本身也不得超额度（I7）").isLessThanOrEqualTo(50);
        assertThat(truncation.notice()).isNotBlank();
    }

    @Test
    void shortTextAndUnlimitedConfigPassThrough() {
        ToolResultLimiter.Truncation small = this.limiter.forModel(TOOL, "15 个定义");
        assertThat(small.truncated()).isFalse();
        assertThat(small.text()).isEqualTo("15 个定义");
        assertThat(small.strategy()).isNull();
        assertThat(small.notice()).isNull();
        assertThat(this.limiter.truncated("15 个定义")).isFalse();
        assertThat(this.limiter.forModel(TOOL, null).text()).isEmpty();

        this.properties.getResultBudget().getModel().setMaxRows(0);
        this.properties.getResultBudget().getModel().setDefaultMaxChars(0);
        this.properties.getResultBudget().getModel().setFallbackMaxChars(0);
        String big = rows(500);
        assertThat(this.limiter.forModel(TOOL, big).truncated()).isFalse();
        assertThat(this.limiter.forModel(TOOL, big).text()).isEqualTo(big);
    }

    /**
     * C13：模型侧写入按<b>剩余窗口</b>收缩 —— 窗口额度比配置档更小时取窗口（更小者赢），
     * 窗口额度更大/未知（{@code <=0}）时保持配置档不变。
     *
     * <p>缺陷现场：小窗口模型（15000）下配置档仍是 20000 字符，一份大工具结果自己就能吃掉整窗，
     * 上游下一跳必然 400；而"最近 5 对不可清"让轮内 L1 也救不回来。
     */
    @Test
    void windowRemainingBudgetShrinksBelowTheConfiguredDefault() {
        String big = "x".repeat(30_000);

        ToolResultLimiter.Truncation windowed = this.limiter.forModel(TOOL, big, 2_000);

        assertThat(windowed.truncated()).isTrue();
        assertThat(windowed.text()).as("窗口额度 2000 < 配置档 20000 ⇒ 按窗口收缩").hasSizeLessThanOrEqualTo(2_000);
        assertThat(windowed.originalChars()).isEqualTo(30_000);
        assertThat(windowed.returnedChars()).isEqualTo(windowed.text().length());
        assertThat(windowed.notice()).contains("2000 字符");

        assertThat(this.limiter.forModel(TOOL, big, 0).text()).as("0 = 无窗口信息 ⇒ 走配置档")
            .hasSizeLessThanOrEqualTo(20_000);
        assertThat(this.limiter.forModel(TOOL, big, 500_000).text()).as("窗口比配置档大 ⇒ 配置档兜底")
            .hasSizeLessThanOrEqualTo(20_000);
        assertThat(this.limiter.forModel(TOOL, "15 个定义", 2_000).truncated()).as("本来就装得下 ⇒ 不裁").isFalse();
    }

    /** 窗口额度由 token 折算成字符时按 CJK 口径保守取值（1 token 最多 1.7 字符）。 */
    @Test
    void remainingCharsAreDerivedConservativelyFromTokens() {
        assertThat(ToolResultLimiter.charsForRemainingTokens(0)).isZero();
        assertThat(ToolResultLimiter.charsForRemainingTokens(-5)).isZero();
        assertThat(ToolResultLimiter.charsForRemainingTokens(100)).as("100 token ⇒ 最多 170 字符（保守）")
            .isEqualTo(170);
    }

    /**
     * C13 / I7：<b>极小额度</b>下也绝不超额 —— 普通文本与结构化结果最终长度都必须 ≤ 给定额度，
     * 且结构化结果仍是<b>合法 JSON</b>。
     *
     * <p>缺陷现场：额度比"截断标记"还小时，旧实现直接吐整段标记（`notice` 里带原文字数、
     * 行数、上限……），单份结果因此比额度大一个数量级 —— 窗口收缩/极小 per-tool 配置下这一条
     * 就足以把"预算"变成摆设。
     */
    @Test
    void tinyBudgetsNeverExceedTheQuotaAndKeepStructuredResultsValidJson() throws Exception {
        this.properties.getResultBudget().getModel().getPerTool().put(TOOL, 20);
        ToolResultLimiter.Truncation plain = this.limiter.forModel(TOOL, "x".repeat(500));
        assertThat(plain.truncated()).isTrue();
        assertThat(plain.text().length()).as("普通文本最终长度不得超过额度").isLessThanOrEqualTo(20);
        assertThat(plain.text().trim()).as("极小额仍以截断标记开头（模型必须知道这不是全文）")
            .startsWith(ToolResultLimiter.TRUNCATION_MARKER.trim());

        String json = "{\"rows\":[" + "{\"a\":1},".repeat(2_000) + "{\"a\":1}]}";
        this.properties.getResultBudget().getModel().getPerTool().put(TOOL, 40);
        ToolResultLimiter.Truncation structured = this.limiter.forModel(TOOL, json);
        assertThat(structured.truncated()).isTrue();
        assertThat(structured.text().length()).as("结构化结果同样 ≤ 额度").isLessThanOrEqualTo(40);
        assertThat(new ObjectMapper().readTree(structured.text())).as("绝不能为了额度产出坏 JSON").isNotNull();
    }

    // ── ④ 结构化结果：绝不字符截断 ──────────────────────────────────────
    @Test
    void structuredResultIsMarkedAsAWholeInsteadOfCharCut() throws Exception {
        String json = "{\"rows\":[" + IntStream.rangeClosed(1, 4_000)
            .mapToObj(i -> "{\"code\":\"C-" + i + "\",\"name\":\"定义 " + i + "\"}")
            .reduce((a, b) -> a + "," + b)
            .orElseThrow() + "]}";
        assertThat(json.length()).isGreaterThan(20_000);

        ToolResultLimiter.Truncation truncation = this.limiter.forModel(TOOL, json);

        assertThat(truncation.truncated()).isTrue();
        assertThat(truncation.text()).hasSizeLessThanOrEqualTo(20_000);
        JsonNode marker = MAPPER.readTree(truncation.text());
        assertThat(marker.path("truncated").asBoolean()).as("整体标记仍是合法 JSON").isTrue();
        assertThat(marker.path("strategy").asText()).isEqualTo("head");
        assertThat(marker.path("originalChars").asInt()).isEqualTo(json.length());
        assertThat(marker.path("returnedChars").asInt()).isEqualTo(truncation.text().length());
        assertThat(marker.path("notice").asText()).contains("结构化结果").contains("未做字符截断");
        assertThat(marker.path("preview").asText()).as("保留头部预览供模型判读").isNotEmpty();
        assertThat(truncation.text()).as("原文没有被按字符切开后直接塞回").doesNotContain("C-4000");
    }

    @Test
    void structuredResultWithinBudgetIsUntouched() {
        String json = "{\"rows\":[{\"code\":\"C-1\"}],\"total\":1}";

        ToolResultLimiter.Truncation truncation = this.limiter.forModel(TOOL, json);

        assertThat(truncation.truncated()).isFalse();
        assertThat(truncation.text()).isEqualTo(json);
        assertThat(truncation.strategy()).isNull();
    }

    // ── ⑤ 统一字段契约 ──────────────────────────────────────────────────

    @Test
    void truncationCarriesTheUnifiedFieldSet() {
        ToolResultLimiter.Truncation truncation = this.limiter.forModel(TOOL, "y".repeat(25_000));

        assertThat(truncation.truncated()).isTrue();
        assertThat(truncation.strategy()).isIn("head", "tail", "head_tail");
        assertThat(truncation.originalChars()).isEqualTo(25_000);
        assertThat(truncation.returnedChars()).isEqualTo(truncation.text().length());
        assertThat(truncation.notice()).isNotBlank();
        assertThat(TruncationContract.TRUNCATED_FIELD).isEqualTo("truncated");
        assertThat(TruncationContract.STRATEGY_FIELD).isEqualTo("strategy");
        assertThat(TruncationContract.ORIGINAL_CHARS_FIELD).isEqualTo("originalChars");
        assertThat(TruncationContract.RETURNED_CHARS_FIELD).isEqualTo("returnedChars");
        assertThat(TruncationContract.NOTICE_FIELD).isEqualTo("notice");
    }

    private static String rows(int count) {
        return IntStream.rangeClosed(1, count)
            .mapToObj(i -> "- 第 " + i + " 行")
            .reduce((a, b) -> a + "\n" + b)
            .orElseThrow();
    }

}
