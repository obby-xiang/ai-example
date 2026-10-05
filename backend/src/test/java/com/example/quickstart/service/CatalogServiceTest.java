package com.example.quickstart.service;

import com.example.quickstart.BaseIntegrationTest;
import com.example.quickstart.dto.CatalogDTO;
import com.example.quickstart.entity.Task;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 目录与种子数据测试。
 */
class CatalogServiceTest extends BaseIntegrationTest {

    @Autowired
    private CatalogService catalogService;

    @Autowired
    private TaskService taskService;

    @Test
    @Order(1)
    void seedProvidesEightConfigsAcrossLevels() {
        List<CatalogDTO.ConfigItem> items = catalogService.listConfigs();
        assertEquals(8, items.size());
        long global = items.stream().filter(i -> "GLOBAL".equals(i.getLevel())).count();
        long region = items.stream().filter(i -> "REGION".equals(i.getLevel())).count();
        long project = items.stream().filter(i -> "PROJECT".equals(i.getLevel())).count();
        assertEquals(4, global);
        assertEquals(2, region);
        assertEquals(2, project);
        // 大表（验证导出进度）
        CatalogDTO.ConfigItem alarm = items.stream()
                .filter(i -> "ALARM_THRESHOLD".equals(i.getCode())).findFirst().orElseThrow();
        assertEquals(120, alarm.getRowCount());
        // 依赖声明
        assertEquals(1, alarm.getDependsOn().size());
        assertEquals("METRIC_DICT", alarm.getDependsOn().get(0).def());
        // 字段动态结构
        assertFalse(alarm.getFields().isEmpty());
        assertTrue(alarm.getFields().stream().anyMatch(f -> "DECIMAL".equals(f.getDataType())));
    }

    @Test
    @Order(2)
    void taskCreatePersistsImmediately() {
        Task task = taskService.create(TaskService.IMPORT_CONFIG, "sess-test-1");
        assertNotNull(task.getId());
        assertEquals(TaskService.WAITING, task.getStatus());
        assertEquals("SELECT_CONFIG", task.getCurrentStep());
        Task loaded = taskService.require(task.getId());
        assertEquals(task.getId(), loaded.getId());
        assertTrue(taskService.paramsOf(loaded).containsKey("uploads"));
    }

    @Test
    @Order(3)
    void taskCreateRejectsUnknownType() {
        com.example.quickstart.common.BizException e = assertThrows(
                com.example.quickstart.common.BizException.class,
                () -> taskService.create("UNKNOWN", "s"));
        assertTrue(e.getMessage().contains("不支持的任务类型"));
    }

    @Test
    @Order(4)
    void stepGuardsRejectWrongStep() {
        Task task = taskService.create(TaskService.EXPORT_CONFIG, "sess-test-2");
        // 未选择配置时直接设置条件应被拒绝
        com.example.quickstart.common.BizException e = assertThrows(
                com.example.quickstart.common.BizException.class,
                () -> taskService.assertStep(task, "SET_CONDITION"));
        assertNotNull(e);
    }

    @Test
    @Order(5)
    void listTasksReturnsCreated() {
        List<Map<String, Object>> tasks = taskService.listTasks(null, null);
        assertFalse(tasks.isEmpty());
        assertTrue(tasks.stream().allMatch(t -> t.get("status") != null && t.get("currentStep") != null));
    }
}
