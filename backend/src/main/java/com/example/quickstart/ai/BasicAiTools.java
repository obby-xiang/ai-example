package com.example.quickstart.ai;

import com.example.quickstart.common.BizException;
import com.example.quickstart.dto.CatalogDTO;
import com.example.quickstart.entity.Task;
import com.example.quickstart.runtime.EventPublisher;
import com.example.quickstart.runtime.ProgressManager;
import com.example.quickstart.runtime.SessionHolder;
import com.example.quickstart.service.CatalogService;
import com.example.quickstart.service.TaskService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基础工具：任何状态下都可用（目录查询/任务创建与管理/第一步选择配置）。
 */
@Component
public class BasicAiTools extends AiToolSupport {

    private final EventPublisher events;
    private final ProgressManager progress;

    public BasicAiTools(TaskService taskService, CatalogService catalogService,
                        SessionHolder sessionHolder, EventPublisher events,
                        ProgressManager progress) {
        super(taskService, catalogService, sessionHolder);
        this.events = events;
        this.progress = progress;
    }

    @Tool(description = "获取当前会话状态：是否有进行中的任务、任务类型、当前步骤、已选配置、数据就绪情况等摘要")
    public Map<String, Object> get_current_state(ToolContext ctx) {
        String sessionId = sid(ctx);
        Task task = activeTask(sessionId);
        if (task == null) {
            return Map.of("hasActiveTask", false,
                    "hint", "当前无任务，可先 list_config_items 浏览配置项，再用 create_task 创建任务");
        }
        return taskService.taskMap(task);
    }

    @Tool(description = "列出系统支持的任务类型（导出配置/导入配置）及其步骤")
    public List<Map<String, Object>> list_task_types() {
        List<Map<String, Object>> r = new ArrayList<>();
        r.add(Map.of("code", "EXPORT_CONFIG", "name", "导出配置",
                "steps", List.of("选择配置", "查询配置", "导出配置"),
                "description", "选择要导出的配置项，设置查询条件后导出 Excel"));
        r.add(Map.of("code", "IMPORT_CONFIG", "name", "导入配置",
                "steps", List.of("选择配置", "上传配置", "检查配置", "导入配置", "发布配置"),
                "description", "上传/在线编辑配置数据，经检查、导入（暂存）后发布生效"));
        return r;
    }

    @Tool(description = "列出全部配置项：编码、名称、层级（GLOBAL/REGION/PROJECT）、数据行数、字段数、依赖关系")
    public List<Map<String, Object>> list_config_items() {
        List<Map<String, Object>> r = new ArrayList<>();
        for (CatalogDTO.ConfigItem c : catalogService.listConfigs()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", c.getCode());
            m.put("name", c.getName());
            m.put("level", c.getLevel());
            m.put("rowCount", c.getRowCount());
            m.put("fieldCount", c.getFields().size());
            List<String> deps = c.getDependsOn() == null ? List.of()
                    : c.getDependsOn().stream().map(d -> d.def() + "." + d.field() + "→" + d.refField()).toList();
            m.put("dependsOn", deps);
            m.put("description", c.getDescription());
            r.add(m);
        }
        return r;
    }

    @Tool(description = "查看某配置项的字段定义：字段编码/名称/数据类型/是否必填/枚举选项/示例值，用于设置查询条件或构造导入数据")
    public Map<String, Object> get_config_item_fields(
            @ToolParam(description = "配置项编码，如 SYS_PARAM") String configCode) {
        CatalogDTO.ConfigItem item = requireItem(configCode);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", item.getCode());
        m.put("name", item.getName());
        m.put("level", item.getLevel());
        m.put("fields", item.getFields());
        m.put("dependsOn", item.getDependsOn());
        return m;
    }

    @Tool(description = "创建快速实施任务并绑定到当前会话。type 取值：EXPORT_CONFIG（导出配置）或 IMPORT_CONFIG（导入配置）")
    public Map<String, Object> create_task(
            @ToolParam(description = "任务类型：EXPORT_CONFIG 或 IMPORT_CONFIG") String type,
            ToolContext ctx) {
        String sessionId = sid(ctx);
        Task task = taskService.create(type, sessionId);
        sessionHolder.bind(sessionId, task.getId());
        events.publishActivity(sessionId, "create_task",
                ("EXPORT_CONFIG".equals(type) ? "创建导出任务" : "创建导入任务") + "（" + shortId(task.getId()) + "）");
        taskService.publishUpdated(sessionId, task.getId());
        Map<String, Object> m = taskService.taskMap(task);
        m.put("hint", "任务已创建，下一步请用 set_selected_configs 选择配置项");
        return m;
    }

    @Tool(description = "查看最近的历史任务列表：类型、状态、当前步骤、创建时间")
    public List<Map<String, Object>> list_recent_tasks() {
        return taskService.listTasks(null, null);
    }

    @Tool(description = "查看某任务的详细进度（含后台操作进度，如正在导出/检查到第几个配置项）")
    public Map<String, Object> get_task_progress(
            @ToolParam(description = "任务ID") String taskId) {
        Task task = taskService.require(taskId);
        Map<String, Object> m = taskService.summaryOf(task);
        ProgressManager.Job job = progress.get(taskId);
        if (job != null && !job.isDone()) {
            m.put("runningOp", job.getOp().name());
            m.put("percent", job.getPercent());
            m.put("message", job.getMessage());
            m.put("items", job.getItems());
        } else {
            m.put("runningOp", null);
        }
        return m;
    }

    @Tool(description = "取消任务（执行中的后台操作会在当前配置项处理完后停止）")
    public Map<String, Object> cancel_task(@ToolParam(description = "任务ID") String taskId,
                                           ToolContext ctx) {
        String sessionId = sid(ctx);
        Task task = taskService.require(taskId);
        ProgressManager.Job job = progress.get(taskId);
        if (job != null && !job.isDone()) {
            progress.requestCancel(taskId);
        } else {
            taskService.assertNotTerminal(task);
            taskService.markTerminal(task, TaskService.CANCELLED);
        }
        taskService.publishUpdated(sessionId, taskId);
        events.publishActivity(sessionId, "cancel_task", "已取消任务 " + shortId(taskId));
        return Map.of("cancelled", true);
    }

    @Tool(description = "第一步：选择本任务要处理的配置项（可多选）。会覆盖之前的选择")
    public Map<String, Object> set_selected_configs(
            @ToolParam(description = "配置项编码数组，如 [\"SYS_PARAM\",\"ROLE_DICT\"]") List<String> codes,
            ToolContext ctx) {
        String sessionId = sid(ctx);
        Task task = requireActive(sessionId);
        taskService.assertNotTerminal(task);
        taskService.assertRunningNotAllowed(task);
        taskService.assertStep(task, "SELECT_CONFIG");
        if (codes == null || codes.isEmpty()) {
            throw new BizException("请至少选择一个配置项");
        }
        List<String> all = catalogService.listConfigs().stream().map(CatalogDTO.ConfigItem::getCode).toList();
        for (String c : codes) {
            if (!all.contains(c)) {
                throw new BizException("配置项不存在：" + c);
            }
        }
        Map<String, Object> params = taskService.paramsOf(task);
        params.put("selection", codes);
        if (taskService.EXPORT_CONFIG.equals(task.getType())) {
            params.put("conditions", new LinkedHashMap<String, Object>());
            params.put("exportResult", null);
        } else {
            params.put("uploads", new LinkedHashMap<String, Object>());
            params.put("checkResult", null);
            params.put("importResult", null);
            params.put("publishResult", null);
        }
        taskService.updateParams(task, params);
        String next = taskService.EXPORT_CONFIG.equals(task.getType()) ? "SET_CONDITION" : "PREPARE";
        taskService.moveToStep(task, next);
        taskService.publishUpdated(sessionId, task.getId());
        events.publishActivity(sessionId, "set_selected_configs",
                "已选择 " + codes.size() + " 个配置项：" + String.join("、", codes));
        Map<String, Object> m = taskService.summaryOf(task);
        m.put("hint", "已进入下一步：" + ("SET_CONDITION".equals(next) ? "设置查询条件" : "上传/编辑配置数据"));
        return m;
    }
}
