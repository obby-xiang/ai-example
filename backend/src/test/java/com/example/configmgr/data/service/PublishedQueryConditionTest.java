package com.example.configmgr.data.service;

import com.example.configmgr.data.entity.ConfigDataRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S5.a 用例（移植自 glm-5.3 {@code ConfigDataServiceTest}）：已发布数据的查询条件引擎。
 *
 * <p>glm 侧被测类是 {@code ConfigDataService#queryPublished(code, List<ConditionDTO.Condition>)}；
 * 本仓库同职责的实现是 {@link ConfigDataService#findPagedFiltered} / {@link ConfigDataService#countFiltered}
 * 与 {@link ConditionEvaluator}（导出作业、{@code /count}、列表端点三处同源）。故断言直接落在这一对上，
 * 条件对象用 {@link QueryCondition}。
 *
 * <p><b>条件模型差异（改写点）</b>：
 * <ul>
 * <li>glm 的 {@code BETWEEN} 在本仓库不存在 → 用 {@code GTE} + {@code LTE} 两条条件表达同一区间；</li>
 * <li>glm 的 {@code __scope} 伪字段 → 本仓库用 {@link QueryCondition#getScopeKeys()} 范围过滤
 *     （求值器读行内 {@code regionCode}/{@code projectCode}）；</li>
 * <li>glm 的"非法操作符/非法字段必须抛错"没有对应实现（见证据文档"不适用清单"），故不移植该两条。</li>
 * </ul>
 *
 * <p>数据来自 glm 种子并集（{@code GlmSeedService}）：SYS_PARAM 40 行、METRIC_DICT 30 行、
 * ROLE_DICT 8 行、ALARM_THRESHOLD 120 行、REGION_NETWORK 每地区 3 行、REGION_TARIFF 每地区 5 行。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:s5a-published-query;DB_CLOSE_DELAY=-1"
})
class PublishedQueryConditionTest {

    private static final String SYS_PARAM = "SYS_PARAM";
    private static final String METRIC_DICT = "METRIC_DICT";
    private static final String ROLE_DICT = "ROLE_DICT";
    private static final String REGION_NETWORK = "REGION_NETWORK";
    private static final String REGION_TARIFF = "REGION_TARIFF";

    @Autowired
    private ConfigDataService dataService;

    // ── 用例 1：无条件 = 全部已发布行 ──

    @Test
    void noConditionReturnsAllPublishedRows() {
        assertThat(rows(SYS_PARAM, null)).hasSize(40);
        assertThat(rows(METRIC_DICT, null)).hasSize(30);
        // 列表口径与 /count 口径同源（同一份过滤逻辑），命中数必须相等
        assertThat(dataService.countFiltered(SYS_PARAM, null, null)).isEqualTo(40);
    }

    // ── 用例 2：文本 CONTAINS ──

    @Test
    void textContainsMatchesEveryRowAndPrefixFamily() {
        List<Map<String, Object>> rows = rows(SYS_PARAM, field("paramKey", "CONTAINS", "param.1"));

        // param.1 应命中 param.1、param.10~19（前缀包含）
        assertThat(rows).hasSize(11);
        assertThat(rows).allSatisfy(r -> assertThat(String.valueOf(r.get("paramKey"))).contains("param.1"));
    }

    // ── 用例 3：数字区间（glm BETWEEN → GTE + LTE）──

    @Test
    void numberRangeFiltersPermissionLevel() {
        QueryCondition cond = cond(
                fieldCondition("permissionLevel", "GTE", "2"),
                fieldCondition("permissionLevel", "LTE", "3"));

        List<Map<String, Object>> rows = rows(ROLE_DICT, cond);

        assertThat(rows).isNotEmpty();
        assertThat(rows).allSatisfy(r -> assertThat(Integer.parseInt(String.valueOf(r.get("permissionLevel"))))
                .isBetween(2, 3));
    }

    // ── 用例 4：数字 GT ──

    @Test
    void numberGreaterThanFiltersBandwidth() {
        // 种子带宽 每地区 100 / 150 / 200（步进 50），GT 150 只应命中 200 那一行 × 4 个地区
        List<Map<String, Object>> rows = rows(REGION_NETWORK, field("bandwidthMbps", "GT", "150"));

        assertThat(rows).hasSize(4);
        assertThat(rows).allSatisfy(r ->
                assertThat(Long.parseLong(String.valueOf(r.get("bandwidthMbps")))).isGreaterThan(150L));
    }

    // ── 用例 5：枚举 EQ ──

    @Test
    void enumEqualsFiltersBillingCycle() {
        List<Map<String, Object>> rows = rows(REGION_TARIFF, field("billingCycle", "EQ", "月付"));

        assertThat(rows).isNotEmpty();
        assertThat(rows).allSatisfy(r -> assertThat(r.get("billingCycle")).isEqualTo("月付"));
    }

    // ── 用例 6：布尔 EQ ──

    @Test
    void booleanEqualsFiltersAlarmEnabled() {
        List<Map<String, Object>> rows = rows(METRIC_DICT, field("alarmEnabled", "EQ", "true"));

        assertThat(rows).isNotEmpty();
        assertThat(rows).allSatisfy(r -> assertThat(Boolean.parseBoolean(String.valueOf(r.get("alarmEnabled"))))
                .isTrue());
    }

    // ── 用例 7：范围过滤（glm 的 __scope → scopeKeys）──

    @Test
    void scopeKeysFilterRegionLevelRows() {
        QueryCondition cond = new QueryCondition();
        cond.setScopeKeys(List.of("HB"));

        List<Map<String, Object>> rows = rows(REGION_NETWORK, cond);

        assertThat(rows).isNotEmpty();
        assertThat(rows).allSatisfy(r -> assertThat(r.get("regionCode")).isEqualTo("HB"));
    }

    // ── 用例 8：多条件 AND（字段条件 + 范围条件）──

    @Test
    void multipleConditionsAreCombinedWithAnd() {
        QueryCondition cond = new QueryCondition();
        cond.setScopeKeys(List.of("HE"));
        cond.setFields(List.of(fieldCondition("billingCycle", "EQ", "年付")));

        List<Map<String, Object>> rows = rows(REGION_TARIFF, cond);

        assertThat(rows).isNotEmpty();
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("billingCycle")).isEqualTo("年付");
            assertThat(r.get("regionCode")).isEqualTo("HE");
        });
    }

    // ── 用例 9：IN ──

    @Test
    void inOperatorMatchesExactKeys() {
        List<Map<String, Object>> rows = rows(SYS_PARAM,
                field("paramKey", "IN", List.of("param.1", "param.2", "param.3")));

        assertThat(rows).hasSize(3);
    }

    // ───────────────────────── helpers ─────────────────────────

    /** 条件命中行（内容已解析为动态 JSON map），口径与 {@code /count} 同源。 */
    private List<Map<String, Object>> rows(String defCode, QueryCondition cond) {
        List<ConfigDataRow> matched = dataService.findPagedFiltered(defCode, null, cond, 0, 10_000).getContent();
        List<Map<String, Object>> out = new ArrayList<>(matched.size());
        for (ConfigDataRow row : matched) {
            try {
                out.add(dataService.parseRow(row));
            } catch (Exception e) {
                throw new IllegalStateException("已发布行解析失败: " + row.getRowKey(), e);
            }
        }
        return out;
    }

    private QueryCondition field(String fieldCode, String operator, Object value) {
        return cond(fieldCondition(fieldCode, operator, value));
    }

    private QueryCondition.FieldCondition fieldCondition(String fieldCode, String operator, Object value) {
        QueryCondition.FieldCondition fc = new QueryCondition.FieldCondition();
        fc.setFieldCode(fieldCode);
        fc.setOperator(operator);
        fc.setValue(value);
        return fc;
    }

    private QueryCondition cond(QueryCondition.FieldCondition... fields) {
        QueryCondition cond = new QueryCondition();
        cond.setFields(List.of(fields));
        return cond;
    }
}
