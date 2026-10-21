package com.example.configmgr.ai.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 可视可操作专项 T-41：界面原语的后端入参闸 / 回灌闸。
 *
 * <p>这是"模型产选择器"与"接受文件路径"两条明文禁令的**结构性**落点：
 * 不靠前端事后拒绝，而是在下发之前整条拒掉（不挂起、不发 {@code frontend_tool_request}）。
 */
class UiPrimitiveGuardTest {

    private final UiPrimitiveGuard guard = new UiPrimitiveGuard();

    @Test
    void claimsOnlyUiPrimitives() {
        assertThat(this.guard.supports("ui_snapshot")).isTrue();
        assertThat(this.guard.supports("ui_request_upload")).isTrue();
        for (String other : new String[] { "generative_form", "replace_values", "navigate_to", null }) {
            assertThat(this.guard.supports(other)).as("%s 不归本闸门", other).isFalse();
        }
    }

    @Test
    void acceptsLegalArgumentsIncludingUpperCaseAndUnderscoreRef() {
        // 裁决 1：ref 段字符集 = [A-Za-z0-9_-]+（CURRENCY / E2E_TEMP 是真实 defCode）
        assertThat(this.guard.inspectArguments(
                "{\"ref\":\"import.selectDefs.CURRENCY\",\"epoch\":\"a1b2c3-7\"}").accepted())
                .isTrue();
        assertThat(this.guard.inspectArguments(
                "{\"ref\":\"import.selectDefs.E2E_TEMP\",\"epoch\":\"a1b2c3-7\"}").accepted())
                .isTrue();
        assertThat(this.guard.inspectArguments("{}").accepted()).as("ui_snapshot 全参可选").isTrue();
        assertThat(this.guard.inspectArguments(
                "{\"regions\":[\"import.upload\"],\"kinds\":[\"button\",\"all\"],\"limit\":40}").accepted())
                .isTrue();
    }

    @Test
    void rejectsSelectorAndPathKeys() {
        String drive = "C";
        for (String json : new String[] {
                "{\"ref\":\"import.upload.choose\",\"selector\":\"#upload\"}",
                "{\"ref\":\"import.upload.choose\",\"xpath\":\"//button\"}",
                "{\"ref\":\"import.upload.choose\",\"html\":\"<b>x</b>\"}",
                "{\"ref\":\"import.upload.choose\",\"filePath\":\"" + drive + ":/tmp/a.xlsx\"}",
                "{\"ref\":\"import.upload.choose\",\"path\":\"/tmp/a.xlsx\"}" }) {
            FrontendToolGuard.Verdict verdict = this.guard.inspectArguments(json);
            assertThat(verdict.accepted()).as(json).isFalse();
            assertThat(verdict.render()).contains("禁止键");
        }
    }

    @Test
    void rejectsIllegalRefAndEpoch() {
        assertThat(this.guard.inspectArguments("{\"ref\":\"import\"}").accepted()).isFalse();
        assertThat(this.guard.inspectArguments("{\"ref\":\"import.upload\"}").accepted()).isFalse();
        assertThat(this.guard.inspectArguments("{\"ref\":\"import.upload.a.b.c\"}").accepted()).isTrue();
        assertThat(this.guard.inspectArguments("{\"ref\":\"#id .cls\"}").accepted()).isFalse();
        assertThat(this.guard.inspectArguments("{\"epoch\":\"ABC-1\"}").accepted()).isFalse();
        assertThat(this.guard.inspectArguments("{\"epoch\":\"a1b2c3\"}").accepted()).isFalse();
    }

    @Test
    void rejectsOversizedAndOutOfRangeValues() {
        String longValue = "x".repeat(201);
        assertThat(this.guard.inspectArguments("{\"value\":\"" + longValue + "\"}").accepted()).isFalse();
        assertThat(this.guard.inspectArguments("{\"value\":\"" + "x".repeat(200) + "\"}").accepted()).isTrue();
        assertThat(this.guard.inspectArguments("{\"limit\":41}").accepted()).isFalse();
        assertThat(this.guard.inspectArguments("{\"limit\":0}").accepted()).isFalse();
        assertThat(this.guard.inspectArguments("{\"delta\":2001}").accepted()).isFalse();
        assertThat(this.guard.inspectArguments("{\"note\":\"" + "n".repeat(61) + "\"}").accepted()).isFalse();
        assertThat(this.guard.inspectArguments("{\"block\":\"middle\"}").accepted()).isFalse();
        assertThat(this.guard.inspectArguments("{\"kinds\":[\"div\"]}").accepted()).isFalse();
    }

    @Test
    void acceptCanOnlyNarrowToXlsxOrZip() {
        assertThat(this.guard.inspectArguments("{\"accept\":[\"xlsx\"]}").accepted()).isTrue();
        assertThat(this.guard.inspectArguments("{\"accept\":[\"zip\"]}").accepted()).isTrue();
        assertThat(this.guard.inspectArguments("{\"accept\":[\"csv\"]}").accepted()).isFalse();
        assertThat(this.guard.inspectArguments("{\"accept\":\"xlsx\"}").accepted()).isFalse();
    }

    @Test
    void rejectsMalformedArgumentsJson() {
        assertThat(this.guard.inspectArguments("not json").accepted()).isFalse();
        assertThat(this.guard.inspectArguments("[]").accepted()).isFalse();
    }

    @Test
    void renderGivesNumberedReasons() {
        String rendered = this.guard.inspectArguments(
                "{\"selector\":\"#x\",\"limit\":99}").render();
        assertThat(rendered).contains("UI_PRIMITIVE_ARGUMENTS_REJECTED");
        assertThat(rendered).contains("被拒原因：");
        assertThat(rendered).contains("1.");
        assertThat(rendered).contains("2.");
    }

    @Test
    void rejectsPathsAndHtmlInResult() {
        String drive = "C";
        for (String bad : new String[] {
                "已选择 " + drive + ":\\tmp\\a.xlsx",
                "见 file:///tmp/a.xlsx",
                "结果 <script>alert(1)</script>",
                "javascript:void(0)" }) {
            assertThat(this.guard.inspectResult("{}", bad).accepted()).as(bad).isFalse();
        }
        assertThat(this.guard.inspectResult("{}", "共 9 个元素（本次回 9 个；上限 40 行 / 2000 字符）").accepted())
                .isTrue();
        assertThat(this.guard.inspectResult("{}", "用户已选择文件「币种.xlsx」并交给导入向导；匹配 1 个配置项").accepted())
                .isTrue();
    }
}
