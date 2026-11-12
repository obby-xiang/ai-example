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
    void rejectsArgumentsWhenToolNameIsMissingInsteadOfGuessingReplaceValues() {
        String args = """
                {"defCode":"CURRENCY","scope":"sheet","columnCodes":["status"],"search":"停用","replace":"禁用"}
                """;
        SheetWriteGuard.Verdict verdict = guard.inspectArguments(args);
        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.code()).isEqualTo("FRONTEND_TOOL_NAME_REQUIRED");
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
    void inspectResultRejectsAppliedCellCountMismatch() {
        String result = """
                {"kind":"applied","text":"已应用（用户已确认）：改动 1 格\\n共 1 格",
                 "cells":1,"rows":1,"plan":{"cells":2,"rows":2}}
                """;
        SheetWriteGuard.Verdict verdict = inspectOk(result);
        assertThat(verdict.accepted()).isFalse();
        assertThat(String.join("\n", verdict.reasons())).contains("cells").contains("计划数量");
    }

    @Test
    void inspectResultRejectsAppliedRowCountMismatch() {
        String result = """
                {"kind":"applied","text":"已应用（用户已确认）：改动 2 格\\n共 2 格",
                 "cells":2,"rows":1,"plan":{"cells":2,"rows":2}}
                """;
        SheetWriteGuard.Verdict verdict = inspectOk(result);
        assertThat(verdict.accepted()).isFalse();
        assertThat(String.join("\n", verdict.reasons())).contains("rows").contains("计划数量");
    }

    @Test
    void inspectResultRejectsMissingCells() {
        String result = """
                {"kind":"applied","text":"已完成","rows":2,"plan":{"cells":2,"rows":2}}
                """;
        assertThat(inspectOk(result).accepted()).isFalse();
    }

    @Test
    void inspectResultRejectsMissingRows() {
        String result = """
                {"kind":"applied","text":"已完成","cells":2,"plan":{"cells":2,"rows":2}}
                """;
        assertThat(inspectOk(result).accepted()).isFalse();
    }

    @Test
    void inspectResultRejectsNonNumericCells() {
        String result = """
                {"kind":"applied","text":"已完成","cells":"2","rows":2,"plan":{"cells":2,"rows":2}}
                """;
        assertThat(inspectOk(result).accepted()).isFalse();
    }

    @Test
    void inspectResultRejectsNegativeRows() {
        String result = """
                {"kind":"applied","text":"已完成","cells":2,"rows":-1,"plan":{"cells":2,"rows":2}}
                """;
        assertThat(inspectOk(result).accepted()).isFalse();
    }

    @Test
    void inspectResultRejectsFractionalPlanCells() {
        String result = """
                {"kind":"applied","text":"已完成","cells":2,"rows":2,"plan":{"cells":2.5,"rows":2}}
                """;
        assertThat(inspectOk(result).accepted()).isFalse();
    }

    @Test
    void inspectResultRejectsMissingKind() {
        String result = """
                {"text":"已完成","cells":2,"rows":2,"plan":{"cells":2,"rows":2}}
                """;
        assertThat(inspectOk(result).accepted()).isFalse();
    }

    @Test
    void inspectResultRejectsUnknownKind() {
        String result = """
                {"kind":"success","text":"已完成","cells":2,"rows":2,"plan":{"cells":2,"rows":2}}
                """;
        assertThat(inspectOk(result).accepted()).isFalse();
    }

    @Test
    void inspectResultRejectsMissingPlanCounts() {
        String result = """
                {"kind":"applied","text":"已完成","cells":2,"rows":2,"plan":{}}
                """;
        assertThat(inspectOk(result).accepted()).isFalse();
    }

    @Test
    void inspectResultRejectsLegacySuccessTextEvenWithCountTail() {
        assertThat(guard.inspectResult("{}", "写入完成：改动 2 格\\n共 2 格").accepted()).isFalse();
    }

    @Test
    void inspectResultAcceptsAppliedReceiptWithoutDependingOnItsText() {
        String result = """
                {"kind":"applied","text":"Completed with wording independent of business logic",
                 "cells":2,"rows":2,"plan":{"cells":2,"rows":2}}
                """;
        assertThat(inspectOk(result).accepted()).isTrue();
    }

    @Test
    void inspectResultAcceptsNormalReplaceValuesReceipt() {
        String args = """
                {"defCode":"CURRENCY","scope":"sheet","columnCodes":["status"],"search":"停用","replace":"禁用"}
                """;
        assertThat(inspectOk(args, appliedReceipt()).accepted()).isTrue();
    }

    @Test
    void inspectResultAcceptsNormalUpdateRowsReceipt() {
        String args = """
                {"defCode":"CURRENCY","scope":"sheet","set":[{"columnCode":"status","mode":"literal","value":"禁用"}]}
                """;
        assertThat(inspectOk(args, appliedReceipt()).accepted()).isTrue();
    }

    @Test
    void inspectResultAcceptsNormalNormalizeColumnReceipt() {
        String args = """
                {"defCode":"CURRENCY","scope":"sheet","columnCodes":["code"],"rules":["trim"],"commit":true}
                """;
        assertThat(inspectOk(args, appliedReceipt()).accepted()).isTrue();
    }

    @Test
    void inspectResultAcceptsNormalUndoReceipt() {
        String args = "{\"defCode\":\"CURRENCY\"}";
        assertThat(inspectOk(args, appliedReceipt()).accepted()).isTrue();
    }

    @Test
    void inspectResultAcceptsFailedReceiptWithActualCounts() {
        String result = """
                {"kind":"failed","text":"写入中途失败","cells":1,"rows":1,"plan":{"cells":2,"rows":2}}
                """;
        assertThat(inspectFailed(result).accepted()).isTrue();
    }

    @Test
    void inspectResultAcceptsNormalizeSuggestionText() {
        String args = """
                {"defCode":"CURRENCY","scope":"sheet","columnCodes":["code"],"rules":["trim"],"commit":false}
                """;
        assertThat(guard.inspectResult(args, "归一化建议（未做任何修改）\n共 1 格").accepted()).isTrue();
    }

    @Test
    void inspectResultRejectsPlainTextWhenArgumentsAlsoContainReplaceFields() {
        String args = """
                {"defCode":"CURRENCY","scope":"sheet","columnCodes":["code"],"search":"a","replace":"b",
                 "rules":["trim"],"commit":false}
                """;
        assertThat(guard.inspectResult(args, "已应用：改动 1 格\n共 1 格").accepted()).isFalse();
    }

    @Test
    void inspectResultRejectsFailedCountsAbovePlan() {
        String result = """
                {"kind":"failed","text":"部分写入","cells":3,"rows":2,"plan":{"cells":2,"rows":2}}
                """;
        assertThat(inspectFailed(result).accepted()).isFalse();
    }

    @Test
    void inspectResultRejectsCancelledWithNonzeroActualCounts() {
        String result = """
                {"kind":"cancelled","text":"用户取消","cells":1,"rows":1,"plan":{"cells":2,"rows":2}}
                """;
        assertThat(inspectCancelled(result).accepted()).isFalse();
    }

    @Test
    void inspectResultRejectsActualRowsAboveActualCells() {
        String result = """
                {"kind":"failed","text":"部分写入","cells":1,"rows":2,"plan":{"cells":3,"rows":2}}
                """;
        assertThat(inspectFailed(result).accepted()).isFalse();
    }

    @Test
    void inspectResultRejectsZeroCellsWithNonzeroRows() {
        String result = """
                {"kind":"failed","text":"部分写入","cells":0,"rows":1,"plan":{"cells":2,"rows":2}}
                """;
        assertThat(inspectFailed(result).accepted()).isFalse();
    }

    @Test
    void inspectResultRejectsPlanRowsAbovePlanCells() {
        String result = """
                {"kind":"failed","text":"部分写入","cells":1,"rows":1,"plan":{"cells":1,"rows":2}}
                """;
        assertThat(inspectFailed(result).accepted()).isFalse();
    }

    @Test
    void inspectResultRejectsMissingText() {
        String result = """
                {"kind":"failed","cells":1,"rows":1,"plan":{"cells":2,"rows":2}}
                """;
        assertThat(inspectFailed(result).accepted()).isFalse();
    }

    @Test
    void inspectResultRejectsNonStringText() {
        String result = """
                {"kind":"failed","text":3,"cells":1,"rows":1,"plan":{"cells":2,"rows":2}}
                """;
        assertThat(inspectFailed(result).accepted()).isFalse();
    }

    @Test
    void inspectResultRejectsBlankText() {
        String result = """
                {"kind":"failed","text":"  ","cells":1,"rows":1,"plan":{"cells":2,"rows":2}}
                """;
        assertThat(inspectFailed(result).accepted()).isFalse();
    }

    @Test
    void inspectResultRejectsEmptyResult() {
        assertThat(guard.inspectResult("{}", "").accepted()).isFalse();
    }

    private SheetWriteGuard.Verdict inspectOk(String result) {
        return inspectOk("{}", result);
    }

    private SheetWriteGuard.Verdict inspectOk(String arguments, String result) {
        return this.guard.inspectResult("run", "call", arguments, result, "ok", false);
    }

    private SheetWriteGuard.Verdict inspectFailed(String result) {
        return this.guard.inspectResult("run", "call", "{}", result, "failed", false);
    }

    private SheetWriteGuard.Verdict inspectCancelled(String result) {
        return this.guard.inspectResult("run", "call", "{}", result, null, true);
    }

    private static String appliedReceipt() {
        return """
                {"kind":"applied","text":"已应用（用户已确认）：改动 2 格\\n共 2 格",
                 "cells":2,"rows":2,"plan":{"cells":2,"rows":2}}
                """;
    }

}
