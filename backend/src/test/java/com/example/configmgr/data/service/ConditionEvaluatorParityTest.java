package com.example.configmgr.data.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AI 表格操作专项 T-23b：predicate 前后端一致性（后端侧）。
 *
 * <p>同一份夹具与期望集合逐字出现在前端 `tests/sheet-condition-parity.spec.ts`
 * （`matchesCondition`）。两份实现任何一方改语义，都会让“卡上说命中 N 行、实际只改 M 行”，
 * 故两侧同时锁死（设计稿 §8 风险 12）。
 */
class ConditionEvaluatorParityTest {

    private static List<Map<String, Object>> rows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("CNY", "人民币", "XN", "100", "2026-01-05", ""));
        rows.add(row("USD", "美元", "SH", "20", "2026-02-10", "x"));
        rows.add(row("CNH", "离岸", "XN", "100", null, null));
        rows.add(row(null, "空码", "BJ", "0", null, "y"));
        return rows;
    }

    private static Map<String, Object> row(String code, String name, String region, String amount, String date, String note) {
        Map<String, Object> row = new HashMap<>();
        row.put("code", code);
        row.put("name", name);
        row.put("region", region);
        row.put("amount", amount);
        row.put("date", date);
        row.put("note", note);
        return row;
    }

    private static QueryCondition condition(String fieldCode, String operator, Object value) {
        QueryCondition.FieldCondition fc = new QueryCondition.FieldCondition();
        fc.setFieldCode(fieldCode);
        fc.setOperator(operator);
        fc.setValue(value);
        QueryCondition condition = new QueryCondition();
        condition.setFields(List.of(fc));
        return condition;
    }

    private static List<String> hits(QueryCondition condition) {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> row : rows()) {
            if (ConditionEvaluator.matches(row, condition)) {
                names.add(String.valueOf(row.get("name")));
            }
        }
        return names;
    }

    @Test
    void elevenOperatorsMatchTheFrontendFixture() {
        assertThat(hits(condition("region", "EQ", "XN"))).containsExactly("人民币", "离岸");
        assertThat(hits(condition("region", "NE", "XN"))).containsExactly("美元", "空码");
        assertThat(hits(condition("name", "CONTAINS", "人民"))).containsExactly("人民币");
        assertThat(hits(condition("name", "LIKE", "美"))).containsExactly("美元");
        assertThat(hits(condition("code", "STARTS_WITH", "CN"))).containsExactly("人民币", "离岸");
        assertThat(hits(condition("region", "IN", List.of("XN", "SH")))).containsExactly("人民币", "美元", "离岸");
        assertThat(hits(condition("note", "EMPTY", null))).containsExactly("人民币", "离岸");
        assertThat(hits(condition("note", "NOT_EMPTY", null))).containsExactly("美元", "空码");
        assertThat(hits(condition("amount", "GT", "20"))).containsExactly("人民币", "离岸");
        assertThat(hits(condition("amount", "GTE", "20"))).containsExactly("人民币", "美元", "离岸");
        assertThat(hits(condition("amount", "LT", "20"))).containsExactly("空码");
        assertThat(hits(condition("amount", "LTE", "20"))).containsExactly("美元", "空码");
    }

    @Test
    void datesCompareAsDatesBeforeNumbersAndStrings() {
        assertThat(hits(condition("date", "GT", "2026-02-01"))).containsExactly("美元");
    }

    @Test
    void multipleConditionsAreAnded() {
        QueryCondition condition = new QueryCondition();
        QueryCondition.FieldCondition region = new QueryCondition.FieldCondition();
        region.setFieldCode("region");
        region.setOperator("EQ");
        region.setValue("XN");
        QueryCondition.FieldCondition amount = new QueryCondition.FieldCondition();
        amount.setFieldCode("amount");
        amount.setOperator("EQ");
        amount.setValue("100");
        condition.setFields(List.of(region, amount));
        assertThat(hits(condition)).containsExactly("人民币", "离岸");
    }

    @Test
    void unknownOperatorThrowsInsteadOfSilentlyWidening() {
        assertThatThrownBy(() -> ConditionEvaluator.matches(rows().get(0), condition("code", "BETWEEN", "1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知的查询条件操作符");
    }

    @Test
    void emptyConditionMatchesEveryRow() {
        assertThat(ConditionEvaluator.matches(new LinkedHashMap<>(), null)).isTrue();
        assertThat(ConditionEvaluator.matches(row("X", "x", "XN", "1", null, null), new QueryCondition())).isTrue();
    }

}
