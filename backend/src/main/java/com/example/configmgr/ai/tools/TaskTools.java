package com.example.configmgr.ai.tools;

import com.example.configmgr.ai.tool.ToolScope;
import com.example.configmgr.ai.tool.ToolRisk;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Task management tools.
 */
@Component
@RequiredArgsConstructor
public class TaskTools {

    private final TaskService taskService;

    @Tool(name = "create_task", description = "创建一个新的快速实施任务（导出或导入）")
    @ToolScope({"*", "page:tasks"})
    @ToolRisk(ToolMeta.RiskLevel.WRITE)
    public String createTask(
            @ToolParam(description = "任务类型: EXPORT（导出配置）或 IMPORT（导入配置）") String type,
            @ToolParam(description = "任务标题，简短描述本次操作目的") String title) {
        Task.TaskType taskType;
        try {
            taskType = Task.TaskType.valueOf(type.toUpperCase());
        } catch (Exception e) {
            return "无效的任务类型: " + type + "，请使用 EXPORT 或 IMPORT";
        }
        Task task = taskService.create(taskType, title);
        return String.format("任务创建成功！任务 #%d [%s] \"%s\"，当前步骤: %s",
                task.getId(), task.getType(), task.getTitle(), task.getCurrentStep());
    }

    @Tool(name = "select_defs", description = "为当前导出/导入任务选择配置项")
    @ToolScope({"task:EXPORT/SELECT_DEFS", "task:IMPORT/UPLOAD"})
    @ToolRisk(ToolMeta.RiskLevel.WRITE)
    public String selectDefs(
            @ToolParam(description = "任务ID") Long taskId,
            @ToolParam(description = "要选择的配置定义编码列表，如 [\"CURRENCY\", \"DOC_TYPE\"]") List<String> defCodes) {
        try {
            Task task = taskService.selectDefs(taskId, defCodes);
            return String.format("已为任务 #%d 选择 %d 个配置项: %s",
                    taskId, defCodes.size(), String.join(", ", defCodes));
        } catch (Exception e) {
            return "选择配置项失败: " + e.getMessage();
        }
    }

    @Tool(name = "navigate_to_step", description = "跳转到任务的指定步骤")
    @ToolScope("task:*")
    @ToolRisk(ToolMeta.RiskLevel.WRITE)
    public String navigateToStep(
            @ToolParam(description = "任务ID") Long taskId,
            @ToolParam(description = "目标步骤，如 QUERY_COND、EXPORT、UPLOAD、PRECHECK、IMPORT、PUBLISH") String step) {
        try {
            Task task = taskService.goToStep(taskId, step);
            return String.format("已跳转到步骤: %s（任务 #%d）", step, taskId);
        } catch (Exception e) {
            return "跳转步骤失败: " + e.getMessage();
        }
    }
}
