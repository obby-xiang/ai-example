package com.example.quickstart.service;

import com.example.quickstart.BaseIntegrationTest;
import com.example.quickstart.entity.Task;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 查询条件引擎测试（FR-C03 / FR-E02）。
 */
class ConfigDataServiceTest extends BaseIntegrationTest {

    @Autowired
    private ConfigDataService dataService;

    @Autowired
    private TaskService taskService;

    private List<Map<String, Object>> all(String code) {
        return dataService.queryPublished(code, List.of());
    }

    @Test
    @Order(1)
    void noConditionReturnsAll() {
        assertEquals(40, all("SYS_PARAM").size());
        assertEquals(30, all("METRIC_DICT").size());
    }

    @Test
    @Order(2)
    void textContains() {
        List<ConditionDTO.Condition> c = List.of(cond("paramKey", "CONTAINS", "param.1"));
        List<Map<String, Object>> rows = dataService.queryPublished("SYS_PARAM", c);
        assertFalse(rows.isEmpty());
        assertTrue(rows.stream().allMatch(r -> String.valueOf(r.get("paramKey")).contains("param.1")));
        // param.1 应命中 param.1、param.10~19（前缀包含）
        assertTrue(rows.size() >= 10);
    }

    @Test
    @Order(3)
    void numericBetween() {
        // permissionLevel 是 INT 1..5
        List<ConditionDTO.Condition> c = List.of(cond("permissionLevel", "BETWEEN", "2", "3"));
        List<Map<String, Object>> rows = dataService.queryPublished("ROLE_DICT", c);
        assertTrue(rows.stream().allMatch(r -> {
            int v = Integer.parseInt(String.valueOf(r.get("permissionLevel")));
            return v >= 2 && v <= 3;
        }));
    }

    @Test
    @Order(4)
    void numericGt() {
        // 带宽种子值 100..750（步进50），GT 400 应命中部分行
        List<ConditionDTO.Condition> c = List.of(cond("bandwidthMbps", "GT", "400"));
        List<Map<String, Object>> rows = dataService.queryPublished("REGION_NETWORK", c);
        assertFalse(rows.isEmpty());
        assertTrue(rows.stream().allMatch(r ->
                Long.parseLong(String.valueOf(r.get("bandwidthMbps"))) > 400));
    }

    @Test
    @Order(5)
    void enumEq() {
        List<ConditionDTO.Condition> c = List.of(cond("billingCycle", "EQ", "月付"));
        List<Map<String, Object>> rows = dataService.queryPublished("REGION_TARIFF", c);
        assertTrue(rows.stream().allMatch(r -> "月付".equals(r.get("billingCycle"))));
    }

    @Test
    @Order(6)
    void boolEq() {
        List<ConditionDTO.Condition> c = List.of(cond("alarmEnabled", "EQ", "true"));
        List<Map<String, Object>> rows = dataService.queryPublished("METRIC_DICT", c);
        assertFalse(rows.isEmpty());
        assertTrue(rows.stream().allMatch(r -> Boolean.parseBoolean(String.valueOf(r.get("alarmEnabled")))));
    }

    @Test
    @Order(7)
    void scopeEqOnRegionLevel() {
        List<ConditionDTO.Condition> c = List.of(cond("__scope", "EQ", "REGION_NORTH"));
        List<Map<String, Object>> rows = dataService.queryPublished("REGION_NETWORK", c);
        assertFalse(rows.isEmpty());
        assertTrue(rows.stream().allMatch(r -> "REGION_NORTH".equals(r.get("__scope"))));
    }

    @Test
    @Order(8)
    void multipleConditionsAnd() {
        List<ConditionDTO.Condition> c = List.of(
                cond("billingCycle", "EQ", "年付"),
                cond("__scope", "EQ", "REGION_EAST"));
        List<Map<String, Object>> rows = dataService.queryPublished("REGION_TARIFF", c);
        assertTrue(rows.stream().allMatch(r ->
                "年付".equals(r.get("billingCycle")) && "REGION_EAST".equals(r.get("__scope"))));
    }

    @Test
    @Order(9)
    void invalidOperatorRejected() {
        List<ConditionDTO.Condition> c = List.of(cond("paramKey", "GT", "x"));
        assertThrows(com.example.quickstart.common.BizException.class,
                () -> dataService.queryPublished("SYS_PARAM", c));
    }

    @Test
    @Order(10)
    void invalidFieldRejected() {
        List<ConditionDTO.Condition> c = List.of(cond("no_such_field", "EQ", "x"));
        assertThrows(com.example.quickstart.common.BizException.class,
                () -> dataService.queryPublished("SYS_PARAM", c));
    }

    @Test
    @Order(11)
    void textIn() {
        List<ConditionDTO.Condition> c = List.of(new ConditionDTO.Condition() {{
            setField("paramKey");
            setOp("IN");
            setValues(List.of("param.1", "param.2", "param.3"));
        }});
        List<Map<String, Object>> rows = dataService.queryPublished("SYS_PARAM", c);
        assertEquals(3, rows.size());
    }

    private ConditionDTO.Condition cond(String field, String op, String... values) {
        ConditionDTO.Condition c = new ConditionDTO.Condition();
        c.setField(field);
        c.setOp(op);
        if (values.length == 1) {
            c.setValue(values[0]);
        } else if (values.length > 1) {
            c.setValue(values[0]);
            c.setValues(List.of(values));
        }
        return c;
    }
}
