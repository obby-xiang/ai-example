package com.example.quickstart.ai;

import com.example.quickstart.dto.AiContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * System prompt 组装：基础人设 + 当前场景说明 + context JSON + 可用工具清单摘要
 * + "只能用当前提供的工具"约束（渐进式披露第二道防线）。
 */
@Component
@RequiredArgsConstructor
public class PromptBuilder {

    private final ObjectMapper om;

    public String build(AiContext context, List<AiToolRegistry.AiTool> tools) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("你是「配置实施助手」，服务于中文的「配置快速实施平台」，帮助用户完成企业配置项的导出与导入实施工作。\n");
        sb.append("平台概念：配置项（如国家字典、税率、项目类型）结构由元数据动态定义；");
        sb.append("任务分导出向导（3 步：选择配置→查询配置→导出配置）与导入向导（4 步：上传配置→检查配置→导入配置→发布配置）；");
        sb.append("导出/检查/导入/发布均为后台异步作业，前端轮询进度。\n");

        sb.append("\n【当前场景】\n").append(scenarioText(context)).append("\n");

        sb.append("\n【当前上下文】\n");
        if (context == null) {
            sb.append("（无）\n");
        } else {
            try {
                sb.append(om.writeValueAsString(context)).append("\n");
            } catch (Exception e) {
                sb.append("（上下文序列化失败）\n");
            }
        }

        sb.append("\n【当前可用工具】\n");
        for (AiToolRegistry.AiTool tool : tools) {
            sb.append("- ").append(tool.name()).append("：").append(tool.description());
            if (tool.needConfirm()) {
                sb.append("（需用户在对话中确认后才真正执行）");
            }
            sb.append("\n");
        }

        sb.append("\n【工具使用准则 · 强制执行】\n");
        sb.append("1. 只能使用上面列出的工具，禁止编造工具或参数；用户想做当前页面不支持的操作时，先用 navigate_to 引导其跳转到对应页面。\n");
        sb.append("2. 所有动作一律通过 tool_calls 发起，绝不在正文中输出 JSON / 代码块 / 工具调用标记。\n");
        sb.append("3. 需要配置项或任务信息时，先调用后端查询工具获取，禁止臆测配置项编码与字段名。\n");
        sb.append("4. 需要更细的界面状态时调用 get_workspace_state 按需拉取；执行写操作前先确认工作区状态。\n");
        sb.append("5. start_export / start_check / start_import / start_publish 等作业类工具需用户确认后才会执行。\n");
        sb.append("6. 全部回复使用中文，简洁，不重复废话。\n");
        return sb.toString();
    }

    private String scenarioText(AiContext context) {
        String page = context == null || context.page() == null ? "TASKS" : context.page().toUpperCase();
        Integer step = context == null ? null : context.step();
        String base = switch (page) {
            case "EXPORT" -> switch (step == null ? 0 : step) {
                case 1 -> "导出向导第 1 步「选择配置」：可帮用户勾选配置项（select_config_defs），或引导进入下一步。";
                case 2 -> "导出向导第 2 步「查询配置」：可为每个配置项代填查询条件（set_query_conditions），用户确认后可启动导出（start_export）。";
                case 3 -> "导出向导第 3 步「导出配置」：可帮用户下载导出文件（download_export_files）或重新导出（start_export）。";
                default -> "导出向导：选择配置→查询配置→导出配置。";
            };
            case "IMPORT" -> switch (step == null ? 0 : step) {
                case 1 -> "导入向导第 1 步「上传配置」：可帮用户勾选配置项（select_config_defs）、下载模板（download_templates）。";
                case 2 -> "导入向导第 2 步「检查配置」：用户确认后可启动预检查作业（start_check），数据来自当前编辑区。";
                case 3 -> "导入向导第 3 步「导入配置」：用户确认后可启动导入作业（start_import），写入暂存区。";
                case 4 -> "导入向导第 4 步「发布配置」：用户确认后可启动发布作业（start_publish），暂存数据全量替换到正式区。";
                default -> "导入向导：上传配置→检查配置→导入配置→发布配置。";
            };
            default -> "任务列表页：可查询配置项与任务、创建导出/导入任务（create_task）、跳转到任务向导（navigate_to）。";
        };
        return base;
    }
}
