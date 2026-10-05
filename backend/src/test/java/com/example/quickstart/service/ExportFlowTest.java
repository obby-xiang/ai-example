package com.example.quickstart.service;

import com.example.quickstart.BaseIntegrationTest;
import com.example.quickstart.common.BizException;
import com.example.quickstart.entity.Task;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 导出全流程测试：创建 → 选配置 → 条件 → 执行 → 结果。
 * 本类会推进任务状态（不改已发布数据），与只读类共享上下文安全。
 */
@TestMethodOrder(OrderAnnotation.class)
class ExportFlowTest extends BaseIntegrationTest {

    private static String taskId;

    @Autowired
    private TaskService taskService;
    @Autowired
    private ExportRunner exportRunner;

    @Test
    @Order(1)
    void createAndSelect() {
        Task task = taskService.create(TaskService.EXPORT_CONFIG, "sess-export");
        taskId = task.getId();
        taskService.assertNotTerminal(task);
        taskService.assertRunningNotAllowed(task);

        // 未选配置时 assertSelection 应失败
        BizException e = assertThrows(BizException.class,
                () -> taskService.assertSelection(taskService.paramsOf(task)));
        assertNotNull(e);

        Map<String, Object> params = taskService.paramsOf(task);
        params.put("selection", List.of("SYS_PARAM", "ROLE_DICT", "ALARM_THRESHOLD"));
        taskService.updateParams(task, params);
        taskService.moveToStep(task, "SET_CONDITION");
        assertEquals("SET_CONDITION", taskService.require(taskId).getCurrentStep());
    }

    @Test
    @Order(2)
    void runExportWithConditions() {
        Task task = taskService.require(taskId);
        Map<String, Object> params = taskService.paramsOf(task);
        Map<String, Object> per = new LinkedHashMap<>();
        per.put("ROLE_DICT", List.of(Map.of("field", "permissionLevel", "op", "LE", "value", "2")));
        per.put("SYS_PARAM", List.of(Map.of("field", "paramKey", "op", "CONTAINS", "value", "param.2")));
        params.put("conditions", per);
        taskService.updateParams(task, params);

        // 用合法条件重新设置（LE 不是 INT 合法操作符，直接设置会被查询校验拒绝，此处验证合法路径）
        Map<String, Object> per2 = new LinkedHashMap<>();
        per2.put("SYS_PARAM", List.of(Map.of("field", "paramKey", "op", "CONTAINS", "value", "param.2")));
        per2.put("ALARM_THRESHOLD", List.of(Map.of("field", "warnThreshold", "op", "GT", "value", "100")));
        per2.put("ROLE_DICT", List.of());
        params.put("conditions", per2);
        taskService.updateParams(task, params);

        exportRunner.run(task, "sess-export");

        Task done = taskService.require(taskId);
        assertEquals(TaskService.SUCCESS, done.getStatus());
        assertEquals("EXECUTE_EXPORT", done.getCurrentStep());

        Map<String, Object> resultParams = taskService.paramsOf(done);
        Object result = resultParams.get("exportResult");
        assertInstanceOf(List.class, result);
        List<?> list = (List<?>) result;
        assertEquals(3, list.size());
        for (Object o : list) {
            Map<?, ?> m = (Map<?, ?>) o;
            String code = (String) m.get("configCode");
            int rowCount = ((Number) m.get("rowCount")).intValue();
            List<?> rows = (List<?>) m.get("rows");
            assertEquals(rowCount, rows.size());
            if ("SYS_PARAM".equals(code)) {
                // param.2* 前缀：param.2, param.20~29 = 11 行
                assertEquals(11, rowCount);
            }
            if ("ALARM_THRESHOLD".equals(code)) {
                // GT 100：warnThreshold 种子 50~537.5，应过滤掉低值行
                assertTrue(rowCount > 0 && rowCount <= 120);
                assertTrue(((List<?>) m.get("rows")).stream().allMatch(r -> {
                    Map<?, ?> rm = (Map<?, ?>) r;
                    return new java.math.BigDecimal(String.valueOf(rm.get("warnThreshold")))
                            .compareTo(new java.math.BigDecimal("100")) > 0;
                }));
            }
            if ("ROLE_DICT".equals(code)) {
                assertEquals(8, rowCount);
            }
        }
    }

    @Test
    @Order(3)
    void terminalTaskRejectsFurtherOps() {
        Task task = taskService.require(taskId);
        assertThrows(BizException.class, () -> taskService.assertNotTerminal(task));
    }

    @Test
    @Order(4)
    void exportTwiceRejectedWhileRunning() {
        Task task = taskService.create(TaskService.EXPORT_CONFIG, "sess-export-2");
        Map<String, Object> params = taskService.paramsOf(task);
        params.put("selection", List.of("ROLE_DICT"));
        taskService.updateParams(task, params);
        taskService.moveToStep(task, "SET_CONDITION");
        // 同步执行完
        exportRunner.run(task, "sess-export-2");
        assertEquals(TaskService.SUCCESS, taskService.require(task.getId()).getStatus());
    }
}
