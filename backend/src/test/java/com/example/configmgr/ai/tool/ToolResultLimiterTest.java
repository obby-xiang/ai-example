package com.example.configmgr.ai.tool;

import com.example.configmgr.ai.config.AiProperties;
import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DC-14 T2 用例：工具结果<b>回填模型</b>前的行数/字符上限
 * （{@code app.ai.tool-result.max-rows|max-chars}，初值 200 行 / 8000 字符）。
 *
 * <p>
 * 两条被锁住的契约：① 超限即截断且**必须留下截断标记**（静默截断会让模型基于缺数据自信下结论）；
 * ② 上限可配置为"不限制"（0/负数），且未超限的文本一字不动（不引入无谓的改造）。
 */
class ToolResultLimiterTest {

    private final AiProperties properties = new AiProperties();

    private final ToolResultLimiter limiter = new ToolResultLimiter(this.properties);

    @Test
    void defaultLimitsAre200RowsAnd8000Chars() {
        // 初值即契约：T2 的"初值 200 行 / 8000 字符"由本断言锁住（改初值必须改这里）
        assertThat(this.properties.getToolResult().getMaxRows()).isEqualTo(200);
        assertThat(this.properties.getToolResult().getMaxChars()).isEqualTo(8000);
    }

    @Test
    void truncatesByRowsAndMarksIt() {
        String text = rows(250);

        String limited = this.limiter.forModel(text);

        assertThat(limited).startsWith(rows(200));
        assertThat(limited).contains(ToolResultLimiter.TRUNCATION_MARKER)
                .contains("原 250 行")
                .contains("仅前 200 行");
        assertThat(this.limiter.truncated(limited)).isTrue();
    }

    /**
     * 后端工具结果的**实际形态**（实测）：官方 {@code DefaultToolCallResultConverter}
     * 把方法返回值序列化成 JSON 字符串，换行成了字面两字符 {@code \n}。
     * 行数上限必须在这种形态下也生效（否则行数限制在真实链路里形同虚设）。
     */
    @Test
    void truncatesRowsOnJsonEscapedText() {
        this.properties.getToolResult().setMaxRows(3);
        String escaped = "- A\\n- B\\n- C\\n- D\\n- E";

        String limited = this.limiter.forModel(escaped);

        assertThat(limited).startsWith("- A\\n- B\\n- C\\n");
        assertThat(limited).contains("原 5 行").contains("仅前 3 行");
        assertThat(limited).doesNotContain("- D");
    }

    @Test
    void truncatesByCharsWhenRowsAreFew() {
        this.properties.getToolResult().setMaxRows(0); // 关掉行数限制，只留字符限制
        this.properties.getToolResult().setMaxChars(50);

        String limited = this.limiter.forModel("x".repeat(120));

        assertThat(limited).startsWith("x".repeat(50));
        assertThat(limited).contains("仅前 50 字符");
    }

    @Test
    void shortTextAndUnlimitedConfigPassThrough() {
        assertThat(this.limiter.forModel("15 个定义")).isEqualTo("15 个定义");
        assertThat(this.limiter.truncated("15 个定义")).isFalse();
        assertThat(this.limiter.forModel(null)).isEmpty();

        this.properties.getToolResult().setMaxRows(0);
        this.properties.getToolResult().setMaxChars(0);
        String big = rows(500);
        assertThat(this.limiter.forModel(big)).isEqualTo(big);
    }

    private static String rows(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> "- 第 " + i + " 行")
                .reduce((a, b) -> a + "\n" + b)
                .orElseThrow();
    }

}
