package com.example.configmgr.ai.tools;

import com.example.configmgr.ai.tool.ToolScope;
import com.example.configmgr.ai.tool.ToolRisk;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.service.DefinitionService;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Tools available globally (all pages/steps).
 */
@Component
@RequiredArgsConstructor
public class GlobalTools {

    private final DefinitionService definitionService;
    private final TaskService taskService;

    @Tool(name = "list_config_defs", description = "列出所有配置定义，可按层级筛选（GLOBAL/REGION/PROJECT）")
    @ToolScope("*")
    @ToolRisk(ToolMeta.RiskLevel.READ)
    public String listConfigDefs(
            @ToolParam(description = "层级过滤：GLOBAL/REGION/PROJECT，不传返回全部", required = false) String level) {
        List<ConfigDefinition> defs = level != null && !level.isBlank()
                ? definitionService.findByLevel(ConfigDefinition.ConfigLevel.valueOf(level.toUpperCase()))
                : definitionService.findAll();
        if (defs.isEmpty()) return "没有找到配置定义";
        return defs.stream().map(d ->
                String.format("- %s（%s）层级:%s 字段数:%d",
                        d.getCode(), d.getName(), d.getLevel().name(),
                        d.getFields() != null ? d.getFields().size() : 0))
                .collect(Collectors.joining("\n"));
    }

    @Tool(name = "get_config_def", description = "获取指定配置定义的字段详情")
    @ToolScope("*")
    @ToolRisk(ToolMeta.RiskLevel.READ)
    public String getConfigDef(
            @ToolParam(description = "配置定义编码，例如 CURRENCY") String code) {
        try {
            ConfigDefinition def = definitionService.findByCode(code);
            StringBuilder sb = new StringBuilder();
            sb.append("配置定义: ").append(def.getCode()).append("（").append(def.getName()).append("）\n");
            sb.append("层级: ").append(def.getLevel()).append("\n");
            if (def.getDescription() != null) sb.append("说明: ").append(def.getDescription()).append("\n");
            sb.append("字段:\n");
            if (def.getFields() != null) {
                def.getFields().forEach(f -> {
                    sb.append("  - ").append(f.getCode()).append("（").append(f.getLabel()).append("）")
                            .append(" 类型:").append(f.getFieldType())
                            .append(f.isRequired() ? " [必填]" : "")
                            .append(f.isKey() ? " [主键]" : "")
                            .append(f.getRefDefCode() != null ? " 引用:" + f.getRefDefCode() : "")
                            .append("\n");
                });
            }
            return sb.toString();
        } catch (Exception e) {
            return "未找到配置定义: " + code;
        }
    }

    @Tool(name = "list_tasks", description = "列出最近的任务列表")
    @ToolScope({"*", "page:tasks"})
    @ToolRisk(ToolMeta.RiskLevel.READ)
    public String listTasks() {
        List<Task> tasks = taskService.findAll();
        if (tasks.isEmpty()) return "当前没有任务，可以创建一个新任务";
        return tasks.stream().limit(10).map(t ->
                String.format("- #%d [%s] %s 步骤:%s 状态:%s",
                        t.getId(), t.getType(), t.getTitle(), t.getCurrentStep(), t.getStatus()))
                .collect(Collectors.joining("\n"));
    }

    @Tool(name = "get_workspace_state", description = "获取当前工作区的任务状态快照")
    @ToolScope("*")
    @ToolRisk(ToolMeta.RiskLevel.READ)
    public String getWorkspaceState(
            @ToolParam(description = "任务ID", required = false) Long taskId) {
        if (taskId == null) return "当前未打开任何任务，请先创建或选择一个任务";
        try {
            Task task = taskService.findById(taskId);
            StringBuilder sb = new StringBuilder();
            sb.append("任务 #").append(task.getId()).append(": ").append(task.getTitle()).append("\n");
            sb.append("类型: ").append(task.getType()).append("，步骤: ").append(task.getCurrentStep())
                    .append("，状态: ").append(task.getStatus()).append("\n");
            if (!task.getItems().isEmpty()) {
                sb.append("配置项: ").append(task.getItems().stream()
                        .map(i -> i.getDefCode() + "(" + i.getStatus() + ")")
                        .collect(Collectors.joining(", "))).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "任务 #" + taskId + " 不存在或无法访问";
        }
    }
}
