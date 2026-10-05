package com.example.quickstart.ai;

import com.example.quickstart.BaseIntegrationTest;
import com.example.quickstart.common.BizException;
import com.example.quickstart.entity.Task;
import com.example.quickstart.runtime.SessionHolder;
import com.example.quickstart.service.TaskService;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AI 工具直调测试（不经过模型）：验证工具行为、步骤守卫、渐进式披露与联动事件。
 * 发布会改写已发布数据，@DirtiesContext 保证其他测试类不受影响。
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AiToolsTest extends BaseIntegrationTest {

    private static final String SID = "sess-ai-tools";

    @Autowired
    private BasicAiTools basicTools;
    @Autowired
    private ExportAiTools exportTools;
    @Autowired
    private ImportAiTools importTools;
    @Autowired
    private TaskService taskService;
    @Autowired
    private SessionHolder sessionHolder;

    private ToolContext ctx() {
        return new ToolContext(Map.of("sessionId", SID));
    }

    @Test
    @Order(1)
    void listConfigItemsReturnsCatalog() {
        List<Map<String, Object>> items = basicTools.list_config_items();
        assertEquals(8, items.size());
        assertTrue(items.stream().anyMatch(i -> "ALARM_THRESHOLD".equals(i.get("code"))
                && ((List<?>) i.get("dependsOn")).size() == 1));
    }

    @Test
    @Order(2)
    void noTaskToolsReject() {
        sessionHolder.unbind(SID);
        assertEquals(Map.of("hasActiveTask", false,
                        "hint", "当前无任务，可先 list_config_items 浏览配置项，再用 create_task 创建任务"),
                basicTools.get_current_state(ctx()));
        BizException e = assertThrows(BizException.class, () -> exportTools.start_export(ctx()));
        assertTrue(e.getMessage().contains("没有进行中的任务"));
    }

    @Test
    @Order(3)
    void createTaskBindsSession() {
        Map<String, Object> m = basicTools.create_task("EXPORT_CONFIG", ctx());
        String taskId = (String) m.get("id");
        assertNotNull(taskId);
        assertEquals(taskId, sessionHolder.get(SID).taskId());

        // get_current_state 现在应看到任务
        Map<String, Object> state = basicTools.get_current_state(ctx());
        assertEquals(taskId, state.get("id"));
        assertEquals("SELECT_CONFIG", state.get("currentStep"));
    }

    @Test
    @Order(4)
    void selectionMovesToNextStep() {
        Map<String, Object> m = basicTools.set_selected_configs(List.of("SYS_PARAM", "ROLE_DICT"), ctx());
        assertEquals("SET_CONDITION", m.get("currentStep"));
        assertEquals(List.of("SYS_PARAM", "ROLE_DICT"), m.get("selection"));

        // 再次选择应被步骤守卫拒绝（已不在 SELECT_CONFIG）
        BizException e = assertThrows(BizException.class,
                () -> basicTools.set_selected_configs(List.of("SYS_PARAM"), ctx()));
        assertTrue(e.getMessage().contains("当前步骤不允许"));
    }

    @Test
    @Order(5)
    void exportToolsProgressiveDisclosure() {
        // 查询字段
        List<Map<String, Object>> fields = exportTools.get_query_fields("SYS_PARAM");
        assertTrue(fields.size() >= 4);
        assertTrue(fields.stream().anyMatch(f -> "paramKey".equals(f.get("field"))));

        // 设置条件
        Map<String, Object> saved = exportTools.set_query_conditions(
                "{\"SYS_PARAM\":[{\"field\":\"paramKey\",\"op\":\"CONTAINS\",\"value\":\"param.1\"}]}",
                ctx());
        assertEquals(Boolean.TRUE, saved.get("saved"));

        // 启动导出（异步，等待完成）
        Map<String, Object> started = exportTools.start_export(ctx());
        assertEquals(Boolean.TRUE, started.get("started"));
        awaitTerminal();

        Map<String, Object> summary = exportTools.get_export_summary(ctx());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> configs = (List<Map<String, Object>>) summary.get("configs");
        assertEquals(2, configs.size());
        Map<String, Object> sys = configs.stream()
                .filter(c -> "SYS_PARAM".equals(c.get("configCode"))).findFirst().orElseThrow();
        assertEquals(11, ((Number) sys.get("rowCount")).intValue());
    }

    @Test
    @Order(6)
    void importToolsFlowWithGuards() {
        sessionHolder.unbind(SID);
        basicTools.create_task("IMPORT_CONFIG", ctx());
        basicTools.set_selected_configs(List.of("ROLE_DICT"), ctx());

        // 未提交数据直接检查：允许（会产生警告），但先提交一份数据
        Map<String, Object> submitted = importTools.submit_config_data("ROLE_DICT",
                "[{\"roleCode\":\"NEW_R\",\"roleName\":\"新角色\",\"permissionLevel\":\"3\",\"builtin\":\"false\"}]",
                "AI生成", ctx());
        assertEquals(1, ((Number) submitted.get("rowCount")).intValue());

        Map<String, Object> status = importTools.get_prepare_status(ctx());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> configs = (List<Map<String, Object>>) status.get("configs");
        assertEquals(Boolean.TRUE, configs.get(0).get("ready"));

        importTools.start_check(ctx());
        awaitStep("CHECK");

        Map<String, Object> check = importTools.get_check_results(ctx());
        assertEquals(Boolean.FALSE, check.get("hasError"));

        importTools.start_import(ctx());
        awaitStep("IMPORT");

        Map<String, Object> summary = importTools.get_import_summary(ctx());
        assertNotNull(summary.get("configs"));

        importTools.start_publish(ctx());
        awaitTerminal();

        // 发布后任务成功，活动任务解绑
        Task task = taskService.require(sessionHolder.get(SID).taskId());
        assertEquals(TaskService.SUCCESS, task.getStatus());
    }

    @Test
    @Order(7)
    void stepGuardBlocksPublishBeforeImport() {
        sessionHolder.unbind(SID + "2");
        // 用新会话创建导入任务
        ToolContext ctx2 = new ToolContext(Map.of("sessionId", SID + "2"));
        basicTools.create_task("IMPORT_CONFIG", ctx2);
        basicTools.set_selected_configs(List.of("ROLE_DICT"), ctx2);
        importTools.submit_config_data("ROLE_DICT",
                "[{\"roleCode\":\"R_X\",\"roleName\":\"X\",\"permissionLevel\":\"1\",\"builtin\":\"true\"}]",
                null, ctx2);
        importTools.start_check(ctx2);
        awaitStep(SID + "2", "CHECK");

        // CHECK 步骤直接发布应被守卫拒绝
        BizException e = assertThrows(BizException.class, () -> importTools.start_publish(ctx2));
        assertTrue(e.getMessage().contains("当前步骤不允许") || e.getMessage().contains("IMPORT"));
    }

    @Test
    @Order(8)
    void wrongConfigCodeRejected() {
        BizException e = assertThrows(BizException.class,
                () -> basicTools.get_config_item_fields("NO_SUCH"));
        assertTrue(e.getMessage().contains("不存在"));
    }

    private void awaitTerminal() {
        awaitTerminal(SID);
    }

    private void awaitStep(String step) {
        awaitStep(SID, step);
    }

    /** 等待任务到达指定步骤（检查/导入完成后任务回到 WAITING 而非终态） */
    private void awaitStep(String sid, String step) {
        SessionHolder.TaskRef ref = sessionHolder.get(sid);
        assertNotNull(ref);
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            Task t = taskService.require(ref.taskId());
            if (step.equals(t.getCurrentStep()) && !TaskService.EXECUTING.equals(t.getStatus())) {
                return;
            }
            if (TaskService.FAILED.equals(t.getStatus()) || TaskService.CANCELLED.equals(t.getStatus())) {
                fail("任务意外终止：" + t.getStatus());
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        fail("等待步骤 " + step + " 超时");
    }

    private void awaitTerminal(String sid) {
        SessionHolder.TaskRef ref = sessionHolder.get(sid);
        assertNotNull(ref);
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            Task t = taskService.require(ref.taskId());
            if (TaskService.SUCCESS.equals(t.getStatus()) || TaskService.FAILED.equals(t.getStatus())
                    || TaskService.CANCELLED.equals(t.getStatus())) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        fail("等待任务终态超时");
    }
}
