package com.example.configmgr.ai.tools;

import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolRisk;
import com.example.configmgr.ai.tool.ToolScope;
import com.example.configmgr.data.service.ConfigDataService;
import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.service.DefinitionService;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.service.JobService;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * AI 业务工具集（S4.2 §2 tools/AiTools）：基座业务工具到官方 {@code @Tool} 形态的桥接。
 *
 * <h2>与 REST 同服务层（单一事实源 FR-5.4/D2）</h2>
 * 每个工具方法都直接调用基座业务服务（DefinitionService/TaskService/ConfigDataService/JobService），
 * 与 REST 控制器走同一服务层，不复制业务逻辑、不另建数据访问路径；JSON Schema 由 Spring AI 从
 * 方法签名生成（不自建工具协议）。渐进披露标签见 {@link ToolScope}，风险等级见 {@link ToolRisk}。
 *
 * <h2>本棒只接两类工具（其余留给后续棒次）</h2>
 * <ul>
 * <li><b>只读工具</b>：查询定义/任务/数据行数/作业状态——无副作用，可直接执行；</li>
 * <li><b>导出/导入任务的发起工具</b>：start_export / start_precheck / start_import
 * （作业创建，副作用限于生成作业与暂存数据）。</li>
 * </ul>
 * <b>本棒不接（第二棒确认门落地后再接）</b>：
 * <ul>
 * <li>破坏性工具 {@code start_publish}（基座标 DANGER，必须经 HITL 确认门）；</li>
 * <li>写类工具 create_task / select_defs / set_query_condition / navigate_to_step
 * （状态变更，需与确认门/前端指令通道一起评审）；</li>
 * <li>前端指令类工具 open_export_file_editor / download_export_file
 * （依赖运行上下文里的 UI 指令通道，属渐进披露三重防线的执行兜底，随第二棒一起接）。</li>
 * </ul>
 * 被排除工具的原始实现（含 @ToolScope/@ToolRisk 标注）见基座 git 历史与 S4.2-P1 证据文档。
 */
@Component
@RequiredArgsConstructor
public class AiTools {

    private final DefinitionService definitionService;

    private final TaskService taskService;

    private final ConfigDataService dataService;

    private final JobService jobService;

    // ==================== 只读：配置定义 ====================

    @Tool(name = "list_config_defs", description = "列出系统中的配置定义（编码/名称/层级/字段数），可按层级筛选")
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

    @Tool(name = "get_config_def", description = "获取指定配置定义的字段详情（编码/标签/类型/必填/主键/引用）")
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

    // ==================== 只读：任务与作业 ====================

    @Tool(name = "list_tasks", description = "列出最近的任务列表（含类型/步骤/状态）")
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

    @Tool(name = "check_job_status", description = "查询作业状态和进度")
    @ToolScope("task:*")
    @ToolRisk(ToolMeta.RiskLevel.READ)
    public String checkJobStatus(
            @ToolParam(description = "作业ID") Long jobId) {
        try {
            Job job = jobService.findById(jobId);
            return String.format("作业 #%d [%s] 状态:%s 进度:%d/%d 错误:%d",
                    job.getId(), job.getJobType(), job.getStatus(),
                    job.getProgress(), job.getTotal(), job.getErrorCount());
        } catch (Exception e) {
            return "查询作业失败: " + e.getMessage();
        }
    }

    @Tool(name = "get_row_count", description = "预估指定配置项的数据行数")
    @ToolScope({"*", "task:EXPORT/QUERY_COND", "task:EXPORT/EXPORT"})
    @ToolRisk(ToolMeta.RiskLevel.READ)
    public String getRowCount(
            @ToolParam(description = "配置定义编码") String defCode,
            @ToolParam(description = "范围类型: GLOBAL/REGION/PROJECT", required = false) String scopeType,
            @ToolParam(description = "范围键值（地区或项目编码）", required = false) String scopeKey) {
        long count = dataService.count(defCode, scopeType, scopeKey);
        return String.format("%s 共有 %d 行数据", defCode, count);
    }

    // ==================== 作业发起：导出 / 预检查 / 导入 ====================

    @Tool(name = "start_export", description = "启动配置导出作业，开始将数据导出为Excel文件")
    @ToolScope("task:EXPORT/EXPORT")
    @ToolRisk(ToolMeta.RiskLevel.WRITE)
    public String startExport(
            @ToolParam(description = "任务ID") Long taskId) {
        try {
            Job job = jobService.createAndStart(taskId, Job.JobType.EXPORT);
            return String.format("导出作业已启动（作业 #%d），请稍后查看进度", job.getId());
        } catch (Exception e) {
            return "启动导出失败: " + e.getMessage();
        }
    }

    @Tool(name = "start_precheck", description = "启动预检查作业，验证上传的Excel文件格式和数据")
    @ToolScope("task:IMPORT/PRECHECK")
    @ToolRisk(ToolMeta.RiskLevel.WRITE)
    public String startPrecheck(
            @ToolParam(description = "任务ID") Long taskId) {
        try {
            Job job = jobService.createAndStart(taskId, Job.JobType.PRECHECK);
            return String.format("预检查已启动（作业 #%d），正在检查数据格式和依赖关系…", job.getId());
        } catch (Exception e) {
            return "启动预检查失败: " + e.getMessage();
        }
    }

    @Tool(name = "start_import", description = "启动导入作业，将数据写入暂存区")
    @ToolScope("task:IMPORT/IMPORT")
    @ToolRisk(ToolMeta.RiskLevel.WRITE)
    public String startImport(
            @ToolParam(description = "任务ID") Long taskId) {
        try {
            Job job = jobService.createAndStart(taskId, Job.JobType.IMPORT);
            return String.format("导入作业已启动（作业 #%d），数据将写入暂存区，发布前可预览", job.getId());
        } catch (Exception e) {
            return "启动导入失败: " + e.getMessage();
        }
    }

}
