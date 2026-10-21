package com.example.configmgr.ai.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 表格操作专项 T-21 / T-28：{@link SheetWriteGuard} 的入参闸与回灌复核。
 *
 * 这是“不挂起、不发帧”的前置闸：非法入参在到达浏览器之前就被拒，模型收到逐条原因清单
 * （设计稿 §3.4 六项校验）。
 */
class SheetWriteGuardTest {

    private final SheetWriteGuard guard = new SheetWriteGuard();

    @Test
    void supportsTheFourWriteToolsOnly() {
        for (String name : new String[] { "replace_values", "update_rows", "normalize_column", "undo_last_sheet_edit" }) {
            assertThat(guard.supports(name)).as(name).isTrue();
        }
        assertThat(guard.supports("read_sheet_summary")).isFalse();
        assertThat(guard.supports("generative_form")).isFalse();
    }

    @Test
    void acceptsAWellFormedReplace() {
        String args = """
                {"defCode":"CURRENCY","scope":"sheet","columnCodes":["status"],
                 "match":"wholeCell","ignoreCase":true,"search":"停用","replace":"禁用","preview":"count"}
                """;
        assertThat(guard.inspectArguments("replace_values", args).accepted()).isTrue();
    }

    @Test
    void rejectsUnknownPredicateOperator() {
        String args = """
                {"defCode":"CURRENCY","scope":"sheet","columnCodes":["status"],"search":"a","replace":"b",
                 "predicate":{"fields":[{"fieldCode":"status","operator":"BETWEEN","value":"x"}]}}
                """;
        SheetWriteGuard.Verdict verdict = guard.inspectArguments("replace_values", args);
        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.code()).isEqualTo("SHEET_ARGUMENTS_REJECTED");
        assertThat(String.join("\n", verdict.reasons())).contains("operator");
    }

    @Test
    void rejectsMoreThanFiveColumns() {
        String args = """
                {"defCode":"CURRENCY","scope":"sheet","columnCodes":["a","b","c","d","e","f"],"search":"x"}
                """;
        SheetWriteGuard.Verdict verdict = guard.inspectArguments("replace_values", args);
        assertThat(verdict.accepted()).isFalse();
        assertThat(String.join("\n", verdict.reasons())).contains("最多 5 列");
    }

    @Test
    void rejectsWildcardAndRegexTogether() {
        String args = """
                {"defCode":"CURRENCY","scope":"sheet","columnCodes":["status"],"match":["wildcard","regex"],
                 "search":"停*","replace":"x"}
                """;
        SheetWriteGuard.Verdict verdict = guard.inspectArguments("replace_values", args);
        assertThat(verdict.accepted()).isFalse();
        assertThat(String.join("\n", verdict.reasons())).contains("互斥");
    }

    @Test
    void rejectsIllegalRegex() {
        String args = """
                {"defCode":"CURRENCY","scope":"sheet","columnCodes":["status"],"match":"regex",
                 "search":"(","replace":"x"}
                """;
        SheetWriteGuard.Verdict verdict = guard.inspectArguments("replace_values", args);
        assertThat(verdict.accepted()).isFalse();
        assertThat(String.join("\n", verdict.reasons())).contains("正则");
    }

    @Test
    void rejectsCommitTrueWithSuggestOnlyRule() {
        // T-28：commit=true + rules=['date'] ⇒ 拒绝（日期归一化只能出建议，不能猜业务事实）
        String args = """
                {"defCode":"CURRENCY","scope":"sheet","columnCodes":["code"],"rules":["date"],"commit":true}
                """;
        SheetWriteGuard.Verdict verdict = guard.inspectArguments("normalize_column", args);
        assertThat(verdict.accepted()).isFalse();
        assertThat(String.join("\n", verdict.reasons())).contains("trim / case / width");
    }

    @Test
    void acceptsCommitTrueWithExecutableRules() {
        String args = """
                {"defCode":"CURRENCY","scope":"sheet","columnCodes":["code"],"rules":["trim","case"],
                 "caseMode":"upper","commit":true,"preview":"sample-diff"}
                """;
        assertThat(guard.inspectArguments("normalize_column", args).accepted()).isTrue();
    }

    @Test
    void rejectsPredicateScopeWithoutPredicate() {
        String args = """
                {"defCode":"CURRENCY","scope":"predicate",
                 "set":[{"columnCode":"status","mode":"literal","value":"NEW"}]}
                """;
        SheetWriteGuard.Verdict verdict = guard.inspectArguments("update_rows", args);
        assertThat(verdict.accepted()).isFalse();
        assertThat(String.join("\n", verdict.reasons())).contains("predicate");
    }

    @Test
    void acceptsFillDownWithPredicate() {
        String args = """
                {"defCode":"CURRENCY","scope":"predicate",
                 "predicate":{"fields":[{"fieldCode":"region","operator":"EQ","value":"XN"}]},
                 "set":[{"columnCode":"status","mode":"fillDown"}],"preview":"count"}
                """;
        assertThat(guard.inspectArguments("update_rows", args).accepted()).isTrue();
    }

    @Test
    void inspectResultRequiresTheCountTailOnSuccess() {
        assertThat(guard.inspectResult("{}", "已应用（用户已确认）：改动 2 格").accepted()).isFalse();
        assertThat(guard.inspectResult("{}", "已应用（用户已确认）：改动 2 格\n共 2 格").accepted()).isTrue();
        assertThat(guard.inspectResult("{}", "用户取消了本次修改，未改动任何单元格。").accepted()).isTrue();
        assertThat(guard.inspectResult("{}", "表格在预览期间已变动，本次未应用任何改动。").accepted()).isTrue();
        assertThat(guard.inspectResult("{}", "").accepted()).isFalse();
    }

}
