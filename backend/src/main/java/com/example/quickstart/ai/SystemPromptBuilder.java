package com.example.quickstart.ai;

import com.example.quickstart.dto.CatalogDTO;
import com.example.quickstart.entity.Task;
import com.example.quickstart.service.TaskService;
import com.example.quickstart.service.ValidationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 动态系统提示词：角色 + 行为准则 + 请求时刻的会话状态摘要 + 当前步骤可用操作。
 * 状态摘要刻意轻量（不含行数据），明细让模型用工具现查（pull 模式）。
 */
@Component
@RequiredArgsConstructor
public class SystemPromptBuilder {

    private final TaskService taskService;
    private final com.example.quickstart.service.CatalogService catalogService;

    public String build(String sessionId, Task activeTask) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是「配置快速实施平台」的智能助手，帮助用户完成配置的导出与导入。请始终使用中文回答。\n\n");

        sb.append("## 平台概念\n");
        sb.append("- 配置项：多种结构不同的配置数据类型，分全局/地区/项目三个层级，字段结构动态定义（用 list_config_items / get_config_item_fields 查询）。\n");
        sb.append("- 快速实施任务：多步骤向导。导出任务=选择配置→查询配置→导出配置；导入任务=选择配置→上传配置→检查配置→导入配置→发布配置。\n");
        sb.append("- 发布前数据处于暂存状态（不影响线上数据），发布后按配置项+适用范围替换已发布数据。\n\n");

        sb.append("## 行为准则\n");
        sb.append("1. 你的工具按当前任务状态自动限定：只能做当前步骤允许的操作，若被拒绝请向用户解释原因并引导。\n");
        sb.append("2. 决策永远基于工具返回的最新数据，不要凭对话记忆中的旧数据下结论。\n");
        sb.append("3. 用户在工作区手动操作后状态会变化，每轮对话开始时平台已为你注入最新状态摘要。\n");
        sb.append("4. 文件上传/下载（Excel/zip 文件本体）需要用户在左侧工作区操作；但配置数据的提交你可以直接完成——用 submit_config_data 工具即为「在线编辑」通路，无需用户上传文件。用户要求准备数据时优先自己提交，不要推给用户。\n");
        sb.append("5. 除非用户明确要求，不要主动开始破坏性操作（导入/发布）；启动前应简述将做什么并得到用户确认。\n");
        sb.append("6. 回答保持简洁，用列表归纳多配置项信息；数据明细不要全量罗列，摘要 + 追问即可。\n\n");

        sb.append("## 当前会话状态\n");
        if (activeTask == null) {
            sb.append("- 当前没有进行中的任务。用户想开始时，先用 list_task_types 或 list_config_items 了解意图，再用 create_task 创建。\n");
        } else {
            sb.append("- 活动任务：").append(activeTask.getTitle())
                    .append("（类型 ").append(activeTask.getType())
                    .append("，ID ").append(activeTask.getId()).append("）\n");
            sb.append("- 任务状态：").append(activeTask.getStatus())
                    .append("；当前步骤：").append(stepName(activeTask.getCurrentStep())).append("\n");
            List<String> selection = taskService.selectionOf(activeTask);
            if (selection.isEmpty()) {
                sb.append("- 尚未选择配置项（第一步未完成）\n");
            } else {
                sb.append("- 已选配置项：").append(String.join("、", selection)).append("\n");
            }
            Map<String, Object> params = taskService.paramsOf(activeTask);
            if (activeTask.getType().equals(TaskService.IMPORT_CONFIG)) {
                Object uploads = params.get("uploads");
                if (uploads instanceof Map<?, ?> m && !m.isEmpty()) {
                    StringBuilder ready = new StringBuilder();
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        Object v = e.getValue();
                        ValidationService.UploadData d = ValidationService.toUploadData(v);
                        ready.append(e.getKey()).append("(").append(d.rows().size()).append("行) ");
                    }
                    sb.append("- 已提交数据的配置项：").append(ready.toString().trim()).append("\n");
                } else {
                    sb.append("- 尚无任何配置项提交数据\n");
                }
                Object check = params.get("checkResult");
                if (check instanceof Map<?, ?> cm) {
                    sb.append("- 最近检查：").append(Boolean.TRUE.equals(cm.get("hasError"))
                            ? "存在错误 " + cm.get("totalErrors") + " 个"
                            : "通过（警告 " + cm.get("totalWarnings") + " 个）").append("\n");
                }
                if (params.get("importResult") instanceof Map<?, ?>) {
                    sb.append("- 已导入（暂存未发布）\n");
                }
            } else {
                Object conds = params.get("conditions");
                if (conds instanceof Map<?, ?> m && !m.isEmpty()) {
                    sb.append("- 已设置查询条件的配置项：").append(String.join("、",
                            m.keySet().stream().map(String::valueOf).toList())).append("\n");
                }
            }
        }
        sb.append('\n');

        sb.append("## 当前可用操作\n");
        sb.append(availableOps(activeTask));
        return sb.toString();
    }

    private String availableOps(Task task) {
        if (task == null) {
            return "- 查看任务类型、浏览配置项与字段（list_task_types / list_config_items / get_config_item_fields / get_current_state）\n"
                    + "- 创建任务（create_task，类型 EXPORT_CONFIG=导出 或 IMPORT_CONFIG=导入）\n"
                    + "- 查看历史任务与进度（list_recent_tasks / get_task_progress）\n";
        }
        String step = task.getCurrentStep();
        return switch (step) {
            case "SELECT_CONFIG" -> "- 选择配置项（set_selected_configs）\n- 查询配置项结构（list_config_items / get_config_item_fields）\n";
            case "SET_CONDITION" -> "- 设置查询条件（set_query_conditions）\n- 启动导出（start_export）\n- 查看导出结果摘要（get_export_summary）\n";
            case "EXECUTE_EXPORT" -> "- 查看导出结果摘要（get_export_summary）\n";
            case "PREPARE" -> "- 提交某配置项的数据（submit_config_data，你可以直接完成数据提交，这是在线编辑通路；只有用户明确要上传文件时才引导其到工作区）\n"
                    + "- 查看数据就绪情况（get_prepare_status）\n- 启动检查（start_check）\n";
            case "CHECK" -> "- 查看检查结果（get_check_results）\n- 启动导入（start_import，需用户确认）\n";
            case "IMPORT" -> "- 查看导入摘要（get_import_summary）\n- 启动发布（start_publish，需用户确认）\n";
            case "PUBLISH" -> "- 查看发布结果（get_task_progress）\n";
            default -> "";
        };
    }

    private String stepName(String step) {
        return switch (step) {
            case "SELECT_CONFIG" -> "选择配置";
            case "SET_CONDITION" -> "查询配置（设置导出条件）";
            case "EXECUTE_EXPORT" -> "导出配置";
            case "PREPARE" -> "上传配置";
            case "CHECK" -> "检查配置";
            case "IMPORT" -> "导入配置";
            case "PUBLISH" -> "发布配置";
            default -> step;
        };
    }
}
