package com.example.configmgr.ai.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * G5：<b>双通道结果预算的单一配置对象</b>（{@code app.ai.result-budget.*}）缺省与启动期校验。
 *
 * <p>
 * 为什么必须收敛成一个对象：模型侧字符上限、UI 单帧字节上限、前端回灌上限此前分居
 * {@code app.ai.tool-result} / {@code app.ai.frame} / {@code AiController} 的常量，
 * 三处各改各的 —— 模型侧悄悄领先（8000 vs 前端全文）就是这么来的。收敛后两侧阈值
 * <b>独立可调但同源可查</b>；坏值仍然 fail-fast（配置面不能对运行面说谎）。
 */
class ResultBudgetPropertiesTest {

    @Test
    void defaultsMatchTheAlignedContract() {
        AiProperties.ResultBudget budget = new AiProperties().getResultBudget();

        assertThat(budget.getModel().getMaxRows()).isEqualTo(200);
        assertThat(budget.getModel().getDefaultMaxChars()).isEqualTo(20_000);
        assertThat(budget.getModel().getFallbackMaxChars()).isEqualTo(8_000);
        assertThat(budget.getModel().getPerTool()).isEmpty();
        assertThat(budget.getUi().getMaxFrameBytes()).isEqualTo(64 * 1024);
        assertThat(budget.getCallback().getMaxChars()).isEqualTo(8_000);
    }

    @Test
    void defaultsPassStartupVerification() {
        assertThatCode(() -> new AiProperties().verifyResultBudget()).doesNotThrowAnyException();
    }

    @Test
    void nonPositiveUiFrameBudgetFailsFast() {
        AiProperties properties = new AiProperties();
        properties.getResultBudget().getUi().setMaxFrameBytes(0);

        assertThatThrownBy(properties::verifyResultBudget).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("app.ai.result-budget.ui.max-frame-bytes");
    }

    /**
     * 拆帧专项：{@code max-frame-bytes} 有<b>启动下限 1024</b>。
     *
     * <p>低于 1KiB 时"拆帧"会把一条正常正文切成成百上千片（帧数爆炸、前端逐帧渲染成本失控），
     * 而极小值（例如 64）连一个最小合法帧都装不下 ⇒ 正文被清空。两者都不是"性能降级"，
     * 而是运行面事实被配置面改写，故 fail-fast 并点名键与当前值。
     */
    @Test
    void tooSmallUiFrameBudgetFailsFastWithTheLowerBound() {
        AiProperties below = new AiProperties();
        below.getResultBudget().getUi().setMaxFrameBytes(AiProperties.ResultBudget.Ui.MIN_FRAME_BYTES - 1);
        assertThatThrownBy(below::verifyResultBudget)
            .as("低于 1KiB ⇒ 帧数爆炸或正文被清空")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("app.ai.result-budget.ui.max-frame-bytes")
            .hasMessageContaining(String.valueOf(AiProperties.ResultBudget.Ui.MIN_FRAME_BYTES));

        AiProperties atFloor = new AiProperties();
        atFloor.getResultBudget().getUi().setMaxFrameBytes(AiProperties.ResultBudget.Ui.MIN_FRAME_BYTES);
        assertThatCode(atFloor::verifyResultBudget).doesNotThrowAnyException();
    }

    @Test
    void nonPositiveDefaultCharsFailsFast() {
        AiProperties properties = new AiProperties();
        properties.getResultBudget().getModel().setDefaultMaxChars(-1);

        assertThatThrownBy(properties::verifyResultBudget).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("app.ai.result-budget.model.default-max-chars");
    }

    @Test
    void nonPositiveCallbackBudgetFailsFast() {
        AiProperties properties = new AiProperties();
        properties.getResultBudget().getCallback().setMaxChars(0);

        assertThatThrownBy(properties::verifyResultBudget).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("app.ai.result-budget.callback.max-chars");
    }

    @Test
    void negativePerToolBudgetFailsFastAndNamesTheTool() {
        AiProperties properties = new AiProperties();
        properties.getResultBudget().getModel().getPerTool().put("read_doc", -5);

        assertThatThrownBy(properties::verifyResultBudget).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("per-tool").hasMessageContaining("read_doc");
    }

    @Test
    void perToolBudgetsAreMutableConfiguration() {
        AiProperties properties = new AiProperties();
        properties.getResultBudget().getModel().setPerTool(new java.util.LinkedHashMap<>(Map.of("read_doc", 12_000)));

        assertThatCode(properties::verifyResultBudget).doesNotThrowAnyException();
        assertThat(properties.getResultBudget().getModel().getPerTool()).containsEntry("read_doc", 12_000);
    }

}
