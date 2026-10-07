package com.example.configmgr.ai.tools;

import com.example.configmgr.ai.tool.ToolChannel;
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
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI 业务工具集（S4.2 §2 tools/AiTools）：基座业务工具到官方 {@code @Tool} 形态的桥接。
 *
 * <h2>与 REST 同服务层（单一事实源 FR-5.4/D2）</h2>
 * 每个工具方法都直接调用基座业务服务（DefinitionService/TaskService/ConfigDataService/JobService），
 * 与 REST 控制器走同一服务层，不复制业务逻辑、不另建数据访问路径；JSON Schema 由 Spring AI 从
 * 方法签名生成（不自建工具协议）。渐进披露标签见 {@link ToolScope}，风险等级见 {@link ToolRisk}，
 * 执行通道见 {@link ToolChannel} —— 这三份元数据正是 {@code gate/SpToolCallingManager}
 * 判定"要不要挂起、挂哪种"的依据（不在此处硬编码工具名清单）。
 *
 * <h2>本棒接回的工具（P1 遗留 N7 的收口）</h2>
 * <ul>
 * <li><b>只读</b>：查询定义/任务/数据行数/作业状态 —— 无副作用，直接执行；</li>
 * <li><b>作业发起</b>：start_export / start_precheck / start_import（WRITE，作业创建）；</li>
 * <li><b>破坏性</b>：{@code start_publish}（{@code @ToolRisk(DANGER)}）—— 必须经确认门，
 * 放行才执行、拒绝/超时以"未执行"语义回填；</li>
 * <li><b>前端指令</b>：{@code open_export_file_editor} / {@code download_export_file} /
 * {@code navigate_to} / {@code select_definitions} / {@code set_condition} / {@code confirm_step}
 * （{@code @ToolChannel(FRONTEND)}）—— 副作用在浏览器里，后端方法体是哨兵桩，
 * 由 {@code SpToolCallingManager} 挂起并由 {@code POST /api/ai/frontend-tool-result} 回灌结果。</li>
 * </ul>
 *
 * <h2>工作区动作（S4.4d：AI 一句话驱动向导）</h2>
 * {@code navigate_to} / {@code select_definitions} / {@code set_condition} / {@code confirm_step}
 * 是"任务向导的驱动动作"：按 S4.4c 已实测生效的前端动作表（{@code utils/frontend-tools.ts} 的
 * {@code WORKSPACE_ACTIONS}）登记，<b>参数名与前端契约逐字一致</b>（{@code page/taskId}、
 * {@code codes/mode}、{@code defCode/conditions}、{@code step}）—— 后端只负责披露，
 * 下发与执行沿用既有 {@code frontend_tool_request} 通道，前端零改动。
 *
 * <p>披露口径（三重防线之一，见 {@code ToolScope}）：向导动作在<b>任务中心</b>（向导入口页）与
 * <b>对应任务类型</b>下披露，{@code definitions/data} 等非向导页面不披露；一轮内模型发起的多次
 * 调用共享同一份披露集（上下文在请求边界冻结），因此按"任务类型"而非"单一步骤"披露 ——
 * 步骤级/页面级适配由前端动作表的 {@code allowPages}/{@code allowSteps} 兜底校验（防线三）。
 *
 * <p><b>仍未接回</b>（P1 N7 的剩余项）：{@code create_task} —— 任务创建仍走界面/API
 * （本工具集不含创建任务能力，证据文档 S44d 已登记【待裁决】）。
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

    /**
     * 导出作业发起。
     *
     * <p><b>S4.4d 披露范围补 {@code page:tasks}（本棒两处既有工具披露调整之一，另一处为
     * {@link #startPrecheck}，同一口径）</b>：
     * 一轮对话的工具子集在请求边界冻结（模型在同一轮里发起的多次调用共享同一份披露集），
     * 而"在任务中心一句话驱动导出"这条路径的起点上下文就是 {@code page:tasks}（无 taskType）——
     * 不补这一档，模型能在同一轮里把向导驱动到「导出执行」步，却<b>拿不到启动作业的入口</b>
     * （S44d 证据 V2-a 实测：模型如实回复"披露的工具里没有可以直接触发导出的入口"）。
     * 作业创建仍走与 REST 相同的服务层（{@link JobService}，FR-5.4 单一事实源），
     * 风险等级不变（WRITE，非 DANGER），作业是否可导由任务/配置项状态决定。
     * 该调整不影响既有的 {@code task:EXPORT/EXPORT} 披露（向导内照旧可用），见证据文档【待裁决】。
     */
    @Tool(name = "start_export", description = "启动配置导出作业，开始将数据导出为Excel文件")
    @ToolScope({ "page:tasks", "task:EXPORT/EXPORT" })
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

    /**
     * 预检查作业发起：与 {@link #startExport} 同一口径，披露范围补 {@code page:tasks}
     * （导入向导的「检查配置」步同样要有从任务中心出发的入口，否则模型的"检查"意图无处落地，
     * 会退化成继续 confirm_step 推进步骤 —— S44d 证据 V3-a 实测到了这一退化）。
     * 写暂存（{@code start_import}）与发布（{@code start_publish}，DANGER）保持原披露范围不动。
     */
    @Tool(name = "start_precheck", description = "启动预检查作业，验证上传的Excel文件格式和数据")
    @ToolScope({ "page:tasks", "task:IMPORT/PRECHECK" })
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

    @Tool(name = "start_publish", description = "启动发布作业，将暂存数据正式发布到生产数据（高风险，需要用户确认）")
    @ToolScope("task:IMPORT/PUBLISH")
    @ToolRisk(ToolMeta.RiskLevel.DANGER)
    public String startPublish(
            @ToolParam(description = "任务ID") Long taskId) {
        try {
            Job job = jobService.createAndStart(taskId, Job.JobType.PUBLISH);
            return String.format("发布作业已启动（作业 #%d），正在将数据写入正式库…", job.getId());
        } catch (Exception e) {
            return "启动发布失败: " + e.getMessage();
        }
    }

    // ==================== 前端指令（副作用在浏览器，后端桩体永不执行） ====================

    /**
     * 前端工具哨兵：正常路径下 {@code SpToolCallingManager} 会识别
     * {@code @ToolChannel(FRONTEND)} 并挂起，本方法体<b>不会</b>被执行。
     * 若某处看到这段文本，说明该工具被绕开挂起、按普通工具执行了（例如 runId/toolContext 缺失），
     * 是必须立刻定位的缺陷信号（SP-01ab §4 的同一手法）。
     */
    static final String FRONTEND_STUB = "FRONTEND_STUB_SHOULD_NOT_RUN";

    @Tool(name = "open_export_file_editor", description = "请求前端打开指定配置的导出文件在线编辑器（SpreadJS）")
    @ToolScope("task:EXPORT/EXPORT")
    @ToolRisk(ToolMeta.RiskLevel.READ)
    @ToolChannel(ToolMeta.Channel.FRONTEND)
    public String openExportFileEditor(
            @ToolParam(description = "任务ID") Long taskId,
            @ToolParam(description = "配置定义编码") String defCode) {
        return FRONTEND_STUB;
    }

    @Tool(name = "download_export_file", description = "触发浏览器下载指定配置的导出文件")
    @ToolScope("task:EXPORT/EXPORT")
    @ToolRisk(ToolMeta.RiskLevel.READ)
    @ToolChannel(ToolMeta.Channel.FRONTEND)
    public String downloadExportFile(
            @ToolParam(description = "任务ID") Long taskId,
            @ToolParam(description = "配置定义编码") String defCode) {
        return FRONTEND_STUB;
    }

    // ==================== 前端指令：工作区动作（S4.4d 向导驱动） ====================

    /**
     * 页面导航。披露范围为 {@code *}：它是任意页面进入向导的唯一入口动作，
     * 目标页面的合法性由前端动作表白名单（{@code allowPages}）兜底校验。
     */
    @Tool(name = "navigate_to", description = "驱动前端页面导航：切换到任务中心/导出向导/导入向导/配置定义/数据浏览页；带 taskId 时直接打开该任务的向导")
    @ToolScope("*")
    @ToolRisk(ToolMeta.RiskLevel.READ)
    @ToolChannel(ToolMeta.Channel.FRONTEND)
    public String navigateTo(
            @ToolParam(description = "目标页面：tasks（任务中心）/export（导出向导）/import（导入向导）/definitions（配置定义）/data（数据浏览）") String page,
            @ToolParam(description = "任务ID：打开已有任务的导出/导入向导时必填（可从 list_tasks 或任务中心获得）", required = false) Long taskId) {
        return FRONTEND_STUB;
    }

    @Tool(name = "select_definitions", description = "在导出/导入向导的「选择配置」步骤勾选配置项（按配置定义编码；需已导航到对应向导）")
    @ToolScope({ "page:tasks", "task:EXPORT", "task:IMPORT" })
    @ToolRisk(ToolMeta.RiskLevel.READ)
    @ToolChannel(ToolMeta.Channel.FRONTEND)
    public String selectDefinitions(
            @ToolParam(description = "配置定义编码列表，例如 [\"CURRENCY\"]") String[] codes,
            @ToolParam(description = "选择模式：REPLACE（替换，默认）/ ADD（追加）", required = false) String mode) {
        return FRONTEND_STUB;
    }

    /**
     * 查询条件。参数形态以前端动作为准（{@code conditions} 对象），
     * 前端只会读取 {@code defCode} 与 {@code conditions} 两个键（{@code normalizeActionArgs}）。
     */
    @Tool(name = "set_condition", description = "在导出向导的「查询条件」步骤为指定配置项设置数据过滤条件（不设条件即导出全部；需已导航到导出向导）")
    @ToolScope({ "page:tasks", "task:EXPORT" })
    @ToolRisk(ToolMeta.RiskLevel.READ)
    @ToolChannel(ToolMeta.Channel.FRONTEND)
    public String setCondition(
            @ToolParam(description = "配置定义编码，例如 CURRENCY") String defCode,
            @ToolParam(description = "查询条件对象，形如 {\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"CNY\"}]}；operator 取值 EQ/NE/CONTAINS/LIKE/GT/GTE/LT/LTE/IN/EMPTY/NOT_EMPTY") Map<String, Object> conditions) {
        return FRONTEND_STUB;
    }

    @Tool(name = "confirm_step", description = "确认并推进向导步骤（当前步骤校验通过时前端才前进；需已导航到导出/导入向导）")
    @ToolScope({ "page:tasks", "task:EXPORT", "task:IMPORT" })
    @ToolRisk(ToolMeta.RiskLevel.READ)
    @ToolChannel(ToolMeta.Channel.FRONTEND)
    public String confirmStep(
            @ToolParam(description = "目标步骤（可选）：导出向导 SELECT_DEFS/QUERY_COND/EXPORT；导入向导 SELECT_DEFS/UPLOAD/PRECHECK/IMPORT/PUBLISH。不传则推进到下一步", required = false) String step) {
        return FRONTEND_STUB;
    }

}
