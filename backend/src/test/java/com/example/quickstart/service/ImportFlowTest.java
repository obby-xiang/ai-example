package com.example.quickstart.service;

import com.example.quickstart.BaseIntegrationTest;
import com.example.quickstart.common.BizException;
import com.example.quickstart.entity.Task;
import com.example.quickstart.repository.ConfigDataRepository;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 导入全流程测试：选择 → 提交数据 → 检查（含依赖）→ 导入（暂存）→ 发布（范围内替换）。
 * 发布会改写已发布数据，@DirtiesContext 保证其他测试类不受影响。
 */
@TestMethodOrder(OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ImportFlowTest extends BaseIntegrationTest {

    private static String taskId;

    @Autowired
    private TaskService taskService;
    @Autowired
    private ImportRunner importRunner;
    @Autowired
    private ConfigDataRepository dataRepo;
    @Autowired
    private ConfigDataService dataService;

    private Map<String, Object> row(String... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    @Order(1)
    void createAndPrepare() {
        Task task = taskService.create(TaskService.IMPORT_CONFIG, "sess-import");
        taskId = task.getId();
        Map<String, Object> params = taskService.paramsOf(task);
        params.put("selection", List.of("SYS_PARAM", "ALARM_THRESHOLD", "REGION_NETWORK"));
        taskService.updateParams(task, params);
        taskService.moveToStep(task, "PREPARE");

        // 提交数据：SYS_PARAM 3 行（1 行非法 paramType 应报错）、ALARM_THRESHOLD 2 行（1 行依赖失败）、REGION_NETWORK 2 行
        Map<String, Object> uploads = new LinkedHashMap<>();
        List<Map<String, Object>> sysRows = new ArrayList<>();
        sysRows.add(row("paramKey", "new.param.1", "paramValue", "100", "paramType", "STRING",
                "description", "新参数1", "editable", "true"));
        sysRows.add(row("paramKey", "new.param.2", "paramValue", "200", "paramType", "NUMBER",
                "description", "新参数2", "editable", "false"));
        sysRows.add(row("paramKey", "new.param.3", "paramValue", "300", "paramType", "颜色",
                "description", "非法类型", "editable", "true"));
        uploads.put("SYS_PARAM", Map.of("fileName", "sys.xlsx", "rows", sysRows));

        List<Map<String, Object>> alarmRows = new ArrayList<>();
        alarmRows.add(row("thresholdName", "新阈值1", "metricCode", "metric.001", "warnThreshold", "90",
                "criticalThreshold", "95", "effectiveDate", "2026-06-01"));
        alarmRows.add(row("thresholdName", "新阈值2", "metricCode", "metric.notexist", "warnThreshold", "80",
                "criticalThreshold", "85", "effectiveDate", "2026-06-01"));
        uploads.put("ALARM_THRESHOLD", Map.of("fileName", "alarm.xlsx", "rows", alarmRows));

        List<Map<String, Object>> netRows = new ArrayList<>();
        netRows.add(row("__scope", "REGION_NORTH", "bandwidthMbps", "2000", "gateway", "10.1.0.1",
                "redundancy", "true"));
        netRows.add(row("__scope", "REGION_BAD_SCOPE", "bandwidthMbps", "3000", "gateway", "10.2.0.1",
                "redundancy", "false"));
        uploads.put("REGION_NETWORK", Map.of("fileName", "net.xlsx", "rows", netRows));

        params.put("uploads", uploads);
        taskService.updateParams(task, params);
    }

    @Test
    @Order(2)
    void checkDetectsAllRuleTypes() {
        Task task = taskService.require(taskId);
        importRunner.runCheck(task, "sess-import");

        Task checked = taskService.require(taskId);
        assertEquals("CHECK", checked.getCurrentStep());
        assertEquals(TaskService.WAITING, checked.getStatus());
        Map<String, Object> check = (Map<String, Object>) taskService.paramsOf(checked).get("checkResult");
        assertNotNull(check);
        assertEquals(Boolean.TRUE, check.get("hasError"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> configs = (List<Map<String, Object>>) check.get("configs");
        Map<String, Map<String, Object>> byCode = new LinkedHashMap<>();
        for (Map<String, Object> c : configs) {
            byCode.put((String) c.get("configCode"), c);
        }

        // SYS_PARAM：paramType 枚举非法
        Map<String, Object> sys = byCode.get("SYS_PARAM");
        assertEquals(1, ((Number) sys.get("errorCount")).intValue());
        assertTrue(issuesOf(sys).stream().anyMatch(i ->
                String.valueOf(i.get("message")).contains("不在可选范围")));

        // ALARM_THRESHOLD：metric.notexist 依赖失败
        Map<String, Object> alarm = byCode.get("ALARM_THRESHOLD");
        assertEquals(1, ((Number) alarm.get("errorCount")).intValue());
        assertTrue(issuesOf(alarm).stream().anyMatch(i ->
                String.valueOf(i.get("message")).contains("依赖校验失败")));

        // REGION_NETWORK：非法地区
        Map<String, Object> net = byCode.get("REGION_NETWORK");
        assertEquals(1, ((Number) net.get("errorCount")).intValue());
        assertTrue(issuesOf(net).stream().anyMatch(i ->
                String.valueOf(i.get("field")).contains("适用地区")));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> issuesOf(Map<String, Object> configCheck) {
        return (List<Map<String, Object>>) configCheck.get("issues");
    }

    @Test
    @Order(3)
    void importRejectedWhileCheckHasError() {
        Task task = taskService.require(taskId);
        // start 的守卫逻辑在 controller/工具层，这里直接验证 runImport 的前置校验：
        // 修复数据前 runImport 应抛异常
        BizException e = assertThrows(BizException.class, () -> importRunner.runImport(task, "sess-import"));
        assertTrue(e.getMessage().contains("校验未通过"));
    }

    @Test
    @Order(4)
    void fixDataThenCheckPass() {
        Task task = taskService.require(taskId);
        Map<String, Object> params = taskService.paramsOf(task);
        @SuppressWarnings("unchecked")
        Map<String, Object> uploads = (Map<String, Object>) params.get("uploads");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sysRows = (List<Map<String, Object>>) ((Map<?, ?>) uploads.get("SYS_PARAM")).get("rows");
        sysRows.get(2).put("paramType", "BOOLEAN");
        sysRows.get(2).put("description", "已修复");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> alarmRows = (List<Map<String, Object>>) ((Map<?, ?>) uploads.get("ALARM_THRESHOLD")).get("rows");
        alarmRows.get(1).put("metricCode", "metric.002");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> netRows = (List<Map<String, Object>>) ((Map<?, ?>) uploads.get("REGION_NETWORK")).get("rows");
        netRows.get(1).put("__scope", "REGION_SOUTH");

        taskService.updateParams(task, params);
        importRunner.runCheck(task, "sess-import");

        Map<String, Object> check = (Map<String, Object>) taskService.paramsOf(taskService.require(taskId)).get("checkResult");
        assertEquals(Boolean.FALSE, check.get("hasError"));
        assertEquals(0, ((Number) check.get("totalErrors")).intValue());
    }

    @Test
    @Order(5)
    void importStagesDataWithoutTouchingPublished() {
        Task task = taskService.require(taskId);
        long publishedBefore = countPublished("SYS_PARAM");
        importRunner.runImport(task, "sess-import");

        Task imported = taskService.require(taskId);
        assertEquals("IMPORT", imported.getCurrentStep());
        assertEquals(TaskService.WAITING, imported.getStatus());

        // 暂存数据存在且不影响已发布
        assertEquals(publishedBefore, countPublished("SYS_PARAM"));
        assertTrue(countStaged("SYS_PARAM") >= 3);

        Map<String, Object> importResult = (Map<String, Object>) taskService.paramsOf(imported).get("importResult");
        assertNotNull(importResult);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> summaries = (List<Map<String, Object>>) importResult.get("configs");
        assertEquals(3, summaries.size());
    }

    @Test
    @Order(6)
    void publishReplacesPublishedData() {
        Task task = taskService.require(taskId);
        importRunner.runPublish(task, "sess-import");

        Task published = taskService.require(taskId);
        assertEquals(TaskService.SUCCESS, published.getStatus());
        assertEquals("PUBLISH", published.getCurrentStep());

        // SYS_PARAM（全局）：发布后已发布行数 = 3（替换）
        assertEquals(3, countPublished("SYS_PARAM"));
        // 暂存清零（提升为 PUBLISHED 后 taskId 置空）
        assertEquals(0, countStaged("SYS_PARAM"));

        // REGION_NETWORK：仅替换 REGION_NORTH/REGION_SOUTH，其他地区保留
        List<Map<String, Object>> rows = dataService.queryPublished("REGION_NETWORK", List.of());
        assertTrue(rows.stream().anyMatch(r -> "REGION_NORTH".equals(r.get("__scope"))
                && "2000".equals(String.valueOf(r.get("bandwidthMbps")))));
        assertTrue(rows.stream().anyMatch(r -> "REGION_SOUTH".equals(r.get("__scope"))));
        assertTrue(rows.stream().noneMatch(r -> "REGION_BAD_SCOPE".equals(r.get("__scope"))));

        // 业务键生效：paramKey 应是新值
        assertTrue(rows2("SYS_PARAM").stream().allMatch(r ->
                String.valueOf(r.get("paramKey")).startsWith("new.param.")));
    }

    private List<Map<String, Object>> rows2(String code) {
        return dataService.queryPublished(code, List.of());
    }

    @Test
    @Order(7)
    void dependentsImportedAfterDependency() {
        // 拓扑排序：ALARM_THRESHOLD 依赖 METRIC_DICT；本次选择里 METRIC_DICT 不在范围，
        // 依赖从已发布数据解析。验证 topoOrder 本身的排序行为：
        List<String> order = importRunner.topoOrder(List.of("ALARM_THRESHOLD", "METRIC_DICT", "SYS_PARAM"));
        assertEquals("METRIC_DICT", order.get(0));
        assertTrue(order.indexOf("METRIC_DICT") < order.indexOf("ALARM_THRESHOLD"));
    }

    @Test
    @Order(8)
    void duplicateKeyDetected() {
        Task task = taskService.create(TaskService.IMPORT_CONFIG, "sess-dup");
        Map<String, Object> params = taskService.paramsOf(task);
        params.put("selection", List.of("ROLE_DICT"));
        taskService.updateParams(task, params);
        taskService.moveToStep(task, "PREPARE");
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("roleCode", "R1", "roleName", "角色一", "permissionLevel", "1", "builtin", "true"));
        rows.add(row("roleCode", "R1", "roleName", "角色二", "permissionLevel", "2", "builtin", "false"));
        params.put("uploads", Map.of("ROLE_DICT", Map.of("fileName", "dup.xlsx", "rows", rows)));
        taskService.updateParams(task, params);
        importRunner.runCheck(task, "sess-dup");
        Map<String, Object> check = (Map<String, Object>) taskService.paramsOf(taskService.require(task.getId())).get("checkResult");
        assertEquals(Boolean.TRUE, check.get("hasError"));
        assertTrue(issuesOf((Map<String, Object>) ((List<?>) check.get("configs")).get(0)).stream()
                .anyMatch(i -> String.valueOf(i.get("message")).contains("业务键重复")));
    }

    @Test
    @Order(9)
    void emptyUploadProducesWarning() {
        Task task = taskService.create(TaskService.IMPORT_CONFIG, "sess-empty");
        Map<String, Object> params = taskService.paramsOf(task);
        params.put("selection", List.of("SYS_PARAM"));
        taskService.updateParams(task, params);
        taskService.moveToStep(task, "PREPARE");
        params.put("uploads", Map.of("SYS_PARAM", Map.of("fileName", "", "rows", List.of())));
        taskService.updateParams(task, params);
        importRunner.runCheck(task, "sess-empty");
        Map<String, Object> check = (Map<String, Object>) taskService.paramsOf(taskService.require(task.getId())).get("checkResult");
        assertEquals(Boolean.FALSE, check.get("hasError"));
        assertTrue(((Number) check.get("totalWarnings")).intValue() >= 1);
    }

    @Test
    @Order(10)
    void missingRequiredFieldDetected() {
        Task task = taskService.create(TaskService.IMPORT_CONFIG, "sess-req");
        Map<String, Object> params = taskService.paramsOf(task);
        params.put("selection", List.of("SYS_PARAM"));
        taskService.updateParams(task, params);
        taskService.moveToStep(task, "PREPARE");
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("paramKey", "x.1");
        // paramValue 必填但缺失
        r.put("paramType", "STRING");
        r.put("editable", "true");
        rows.add(r);
        params.put("uploads", Map.of("SYS_PARAM", Map.of("fileName", "m.xlsx", "rows", rows)));
        taskService.updateParams(task, params);
        importRunner.runCheck(task, "sess-req");
        Map<String, Object> check = (Map<String, Object>) taskService.paramsOf(taskService.require(task.getId())).get("checkResult");
        assertTrue(issuesOf((Map<String, Object>) ((List<?>) check.get("configs")).get(0)).stream()
                .anyMatch(i -> String.valueOf(i.get("message")).contains("必填字段为空")));
    }

    private long countPublished(String code) {
        return dataService.queryPublished(code, List.of()).size();
    }

    private long countStaged(String code) {
        var def = dataService.requireDef(code);
        return dataRepo.findByDefIdAndStatusOrderByIdAsc(def.getId(), "STAGED").size();
    }
}
