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
 * <li><b>任务创建</b>：{@code create_task}（{@code @ToolRisk(WRITE)}）—— 创建即落库，返回任务 id
 * （S4.4e 裁决②，见 {@link #createTask}）；</li>
 * <li><b>作业发起（确认门）</b>：{@code start_export} / {@code start_precheck} / {@code start_import} /
 * {@code start_publish} 四个工具统一为 {@code @ToolRisk(DANGER)} —— 执行前挂起、渲染确认卡片，
 * 放行才创建作业，拒绝/超时以"未执行"语义回填（FR-5.3 明文，S4.4e 裁决⑥，
 * 机制见 {@code gate/ConfirmGate}，无需工具名清单）；</li>
 * <li><b>前端指令</b>：{@code open_export_file_editor} / {@code download_export_file} /
 * {@code navigate_to} / {@code select_definitions} / {@code set_condition} / {@code confirm_step} /
 * {@code generative_form}
 * （{@code @ToolChannel(FRONTEND)}）—— 副作用在浏览器里，后端方法体是哨兵桩，
 * 由 {@code SpToolCallingManager} 挂起并由 {@code POST /api/ai/frontend-tool-result} 回灌结果。
 * 其中 {@code generative_form}（DC-15）额外挂了两道后端闸门（schema 白名单校验 / 回灌类型复核），
 * 见 {@link #generativeForm}。</li>
 * </ul>
 *
 * <h2>工作区动作（S4.4d：AI 一句话驱动向导；S4.4e：条件参数镜像前端契约）</h2>
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
 * <p><b>S4.4e 裁决③</b>：{@code set_condition} 的 {@code conditions} 入参改为与前端契约
 * （{@code frontend/src/types/condition.ts} 的 {@code FieldQueryCondition}）逐字段一致的
 * <b>嵌套对象</b>（{@link FieldQueryCondition} / {@link FieldCondition}）—— 前端只读
 * {@code args.conditions} 这一个键（{@code utils/frontend-tools.ts#normalizeActionArgs}），
 * 扁平参数（{@code fieldCode/operator/value} 平铺在顶层）会被静默丢弃，产生"看似成功、实为空条件"
 * 的错误；类型化入参同时让官方 JSON Schema 生成出 {@code fields[].fieldCode/operator/value}
 * 的完整结构，模型不必猜内层形状。见 {@link #setCondition}。
 *
 * <p>创建任务的缺口已在 S4.4e 补齐（{@link #createTask}）：{@code create_task} 走 BACKEND 通道，
 * 与任务中心/REST 同一服务层，P1 N7 到此收口。
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

    // ==================== 任务创建（S4.4e 裁决②：AI 可从零创建任务） ====================

    /**
     * 任务创建：AI 从零建任务（P1 遗留 N7 的最后一个缺口接回）。
     *
     * <p><b>与任务中心/REST 同一服务层（FR-5.4 单一事实源）</b>：直接调 {@link TaskService#create}，
     * 与 {@code POST /api/tasks}（{@code TaskController#create}）是同一条路径，标题缺省规则也一致
     * —— 因此"AI 建的任务"与"手点建的任务"在库里的形态没有差别（含 {@code currentStep} 初值与任务变更事件）。
     *
     * <p><b>为什么需要它</b>：S44d 的 V2/V3 实测里 AI 只能 {@code list_tasks} 选已有任务，
     * 空任务中心的"帮我新建一个导出任务并导出 CURRENCY"走不通（当年登记为【待裁决】2）。
     * 补齐后的链路：{@code create_task} → {@code navigate_to(page, taskId)}
     * → {@code select_definitions} →（{@code set_condition}）→ {@code confirm_step}
     * → {@code start_export}（确认门）。
     *
     * <p><b>披露范围（{@code page:tasks} + 两类向导页）</b>：任务中心是"没有任务时建任务"的现场；
     * 向导页一并披露，是为了在已有任务时也能按用户要求再建一条（否则模型得先导航回任务中心，
     * 而一轮内的披露集在请求边界冻结，跨页会导致工具子集不一致）。
     *
     * <p><b>风险等级 {@code WRITE}</b>（不进确认门）：创建任务本身可逆（任务中心/REST 既有删除机制），
     * 不直接改动业务数据；FR-5.3 的确认清单是"启动导出/检查/导入/发布 + 删除任务"，不含"创建任务"。
     *
     * <p>返回值刻意带上任务 id 与下一个动作（{@code navigate_to} 的目标页面），
     * 让模型不必猜"下一步该拿哪个 id 做什么"。
     */
    @Tool(name = "create_task", description = "从零创建一个配置管理任务（EXPORT 导出 / IMPORT 导入），创建即落库并返回任务 id；随后用 navigate_to(page=..., taskId=...) 打开它的向导")
    @ToolScope({ "page:tasks", "task:EXPORT", "task:IMPORT" })
    @ToolRisk(ToolMeta.RiskLevel.WRITE)
    public String createTask(
            @ToolParam(description = "任务类型：EXPORT（导出配置）/ IMPORT（导入配置）") String type,
            @ToolParam(description = "任务标题，不传则按类型生成默认标题", required = false) String title) {
        try {
            Task.TaskType taskType = Task.TaskType.valueOf(type == null ? "" : type.trim().toUpperCase());
            boolean export = taskType == Task.TaskType.EXPORT;
            String effectiveTitle = title == null || title.isBlank() ? (export ? "导出任务" : "导入任务") : title.trim();
            Task task = taskService.create(taskType, effectiveTitle);
            return String.format(
                    "已创建%s任务 #%d（标题：%s，当前步骤：%s）。下一步：navigate_to(page=\"%s\", taskId=%d) 打开向导，"
                            + "再用 select_definitions 勾选配置项。",
                    export ? "导出" : "导入", task.getId(), task.getTitle(), task.getCurrentStep(),
                    export ? "export" : "import", task.getId());
        } catch (IllegalArgumentException e) {
            return "任务类型不合法：" + type + "（只支持 EXPORT / IMPORT）";
        } catch (Exception e) {
            return "创建任务失败: " + e.getMessage();
        }
    }

    // ==================== 作业发起：导出 / 预检查 / 导入 / 发布（确认门） ====================

    /**
     * 导出作业发起。
     *
     * <p><b>S4.4d 披露范围补 {@code page:tasks}（两处既有工具披露调整之一，另一处为
     * {@link #startPrecheck}，同一口径）</b>：
     * 一轮对话的工具子集在请求边界冻结（模型在同一轮里发起的多次调用共享同一份披露集），
     * 而"在任务中心一句话驱动导出"这条路径的起点上下文就是 {@code page:tasks}（无 taskType）——
     * 不补这一档，模型能在同一轮里把向导驱动到「导出执行」步，却<b>拿不到启动作业的入口</b>
     * （S44d 证据 V2-a 实测：模型如实回复"披露的工具里没有可以直接触发导出的入口"）。
     * 作业创建仍走与 REST 相同的服务层（{@link JobService}，FR-5.4 单一事实源），
     * 该调整不影响既有的 {@code task:EXPORT/EXPORT} 披露（向导内照旧可用），见 S44d 证据【待裁决】1。
     *
     * <p><b>S4.4e 裁决⑥：风险等级由 {@code WRITE} 升为 {@code DANGER}，纳入确认门（FR-5.3 明文）</b>——
     * 与 {@link #startPublish} 同一套 {@code gate/ConfirmGate}：执行前挂起并向前端渲染确认卡片，
     * <b>放行</b>才创建作业、<b>拒绝</b>以"用户拒绝 + 原因"回填且不执行、<b>超时</b>自动取消同样不执行
     * （三结局一致）；机制完全由风险等级驱动（{@code SpToolCallingManager#kindOf}），
     * 此处不新增工具名清单。披露范围不变。
     */
    @Tool(name = "start_export", description = "启动配置导出作业，开始将数据导出为Excel文件（高风险，需要用户确认）")
    @ToolScope({ "page:tasks", "task:EXPORT/EXPORT" })
    @ToolRisk(ToolMeta.RiskLevel.DANGER)
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
     * 预检查作业发起：与 {@link #startExport} 同一口径 —— 披露范围补 {@code page:tasks}
     * （导入向导的「检查配置」步同样要有从任务中心出发的入口，否则模型的"检查"意图无处落地，
     * 会退化成继续 confirm_step 推进步骤 —— S44d 证据 V3-a 实测到了这一退化），
     * 风险等级同为 {@code DANGER}（确认门，S4.4e 裁决⑥；FR-5.3 把"启动检查"与"启动导出/导入/发布"
     * 并列写进确认清单）。
     */
    @Tool(name = "start_precheck", description = "启动预检查作业，验证上传的Excel文件格式和数据（需要用户确认）")
    @ToolScope({ "page:tasks", "task:IMPORT/PRECHECK" })
    @ToolRisk(ToolMeta.RiskLevel.DANGER)
    public String startPrecheck(
            @ToolParam(description = "任务ID") Long taskId) {
        try {
            Job job = jobService.createAndStart(taskId, Job.JobType.PRECHECK);
            return String.format("预检查已启动（作业 #%d），正在检查数据格式和依赖关系…", job.getId());
        } catch (Exception e) {
            return "启动预检查失败: " + e.getMessage();
        }
    }

    /**
     * 写暂存（导入执行）发起：披露范围保持 {@code task:IMPORT/IMPORT} 不变（不在 S4.4d 放宽的两处之列，
     * 只有向导走到「导入执行」步才披露）；风险等级 {@code DANGER}（确认门，S4.4e 裁决⑥）——
     * 它会真正写暂存区，人工确认的必要性不低于导出。
     */
    @Tool(name = "start_import", description = "启动导入作业，将数据写入暂存区（高风险，需要用户确认）")
    @ToolScope("task:IMPORT/IMPORT")
    @ToolRisk(ToolMeta.RiskLevel.DANGER)
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

    // ==================== 前端指令：工作区动作（S4.4d 向导驱动；S4.4e 条件契约对齐） ====================

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
     * 查询条件（S4.4e 裁决③：入参形态 = 前端契约，逐字段对齐）。
     *
     * <p><b>为什么不能用扁平四参</b>：前端动作表只读 {@code args.defCode} 与 {@code args.conditions}
     * 两个键（{@code frontend/src/utils/frontend-tools.ts#normalizeActionArgs}），
     * 若把 {@code fieldCode/operator/value} 平铺成顶层参数，它们会被静默丢弃 —— 表现为
     * "工具调用成功、条件却是空的"（{@link FieldQueryCondition} 的形态才是
     * {@code types/condition.ts#parseConditionJson} 认得的）。
     *
     * <p><b>类型化入参的第二个收益</b>：官方 JSON Schema 由方法签名生成，嵌套 record 让
     * Schema 里直接出现 {@code conditions.fields[].fieldCode/operator/value} 与
     * {@code conditions.scopeKeys}（{@code Map<String,Object>} 只能生成一个无内层结构的 object），
     * 模型不必从描述文字里猜内层形状。
     *
     * <p>顺序约束（前端/后端的既有事实，不是本工具的约束）：{@code set_condition} 只对<b>已勾选</b>
     * 的配置项有效 —— 未选中时表单里没有该卡片（页面 handler 只写表单模型），
     * 而落库（{@code conditionJson}）发生在向导推进步骤时（前端 {@code persistStepData}）。
     */
    @Tool(name = "set_condition", description = "在导出向导的「查询条件」步骤为指定配置项设置数据过滤条件（不设条件即导出全部；需已导航到导出向导并已勾选该配置项）")
    @ToolScope({ "page:tasks", "task:EXPORT" })
    @ToolRisk(ToolMeta.RiskLevel.READ)
    @ToolChannel(ToolMeta.Channel.FRONTEND)
    public String setCondition(
            @ToolParam(description = "配置定义编码，例如 CURRENCY") String defCode,
            @ToolParam(description = "查询条件对象，形如 {\"scopeKeys\":[\"XN\"],\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"CNY\"}]}") FieldQueryCondition conditions) {
        return FRONTEND_STUB;
    }

    /**
     * 查询条件对象 —— 前端契约 {@code frontend/src/types/condition.ts#FieldQueryCondition} 的逐字段镜像
     * （也是后端 {@code data/service/QueryCondition} 的同一形态；字段名一个字都不能改，
     * 前端按这些键名读取，改名即静默失配）。
     *
     * @param scopeKeys 范围过滤：地区/项目编码列表（GLOBAL 级配置忽略）；省略表示全部范围
     * @param fields    字段级过滤条件（多条之间 AND）；省略表示不按字段过滤
     */
    public record FieldQueryCondition(
            @ToolParam(description = "范围过滤：地区/项目编码列表，例如 [\"XN\"]（GLOBAL 级配置忽略）；省略表示全部范围", required = false) List<String> scopeKeys,
            @ToolParam(description = "字段级过滤条件（多条之间 AND），省略表示不按字段过滤", required = false) List<FieldCondition> fields) {
    }

    /**
     * 单字段条件 —— 前端契约 {@code types/condition.ts#FieldCondition} 的逐字段镜像。
     *
     * @param fieldCode 字段编码（必须是该配置定义的字段，如 CURRENCY 的 {@code code}）
     * @param operator  操作符（前端 {@code ConditionOperator} + 后端 {@code ConditionEvaluator} 的交集）
     * @param value     条件值；EMPTY/NOT_EMPTY 不带值，IN 传字符串数组
     */
    public record FieldCondition(
            @ToolParam(description = "字段编码，例如 code") String fieldCode,
            @ToolParam(description = "操作符：EQ/NE/CONTAINS/LIKE/STARTS_WITH/IN/EMPTY/NOT_EMPTY/GT/GTE/LT/LTE") String operator,
            @ToolParam(description = "条件值（文本/数字）；EMPTY/NOT_EMPTY 不传，IN 传字符串数组", required = false) Object value) {
    }

    @Tool(name = "confirm_step", description = "确认并推进向导步骤（当前步骤校验通过时前端才前进；需已导航到导出/导入向导）")
    @ToolScope({ "page:tasks", "task:EXPORT", "task:IMPORT" })
    @ToolRisk(ToolMeta.RiskLevel.READ)
    @ToolChannel(ToolMeta.Channel.FRONTEND)
    public String confirmStep(
            @ToolParam(description = "目标步骤（可选）：导出向导 SELECT_DEFS/QUERY_COND/EXPORT；导入向导 SELECT_DEFS/UPLOAD/PRECHECK/IMPORT/PUBLISH。不传则推进到下一步", required = false) String step) {
        return FRONTEND_STUB;
    }

    // ==================== 生成式表单（DC-15：限定形态生成式 UI 的后端工具） ====================

    /**
     * 生成式表单：在对话内渲染一张<b>白名单 schema 驱动</b>的表单，等用户填写后回灌值、继续本轮。
     *
     * <h2>两个场景（DC-15 明文）</h2>
     * <ul>
     * <li>{@code scenario=FILTER} —— <b>筛选条件填写</b>：用户不必跳到导出向导的「查询条件」步，
     * 在对话里就能填（AI 收到值后可继续走 {@code set_condition} 或直接启动作业）；</li>
     * <li>{@code scenario=CLARIFY} —— <b>询问澄清</b>：信息不足时给用户一张结构化选择/表单，
     * 避免自由文本往返（对应 DC-15 的"模型向用户提问时给出结构化选择/表单"）。</li>
     * </ul>
     *
     * <h2>披露范围（{@code page:tasks} + {@code task:*}）</h2>
     * 两个场景的现场都在"任务驱动"这条链路上：
     * <ul>
     * <li>场景①的现场是<b>任务中心</b>（AI 面板所在页，用户在这里让 AI 导出/查询）与
     * <b>导出向导各步骤</b>（{@code task:EXPORT}，含「查询条件」步：条件可以不跳转就在对话里填）；</li>
     * <li>场景②与任务类型无关（任何任务上下文都可能信息不足），故用 {@code task:*}
     * （与 {@code check_job_status} 同一标签），顺带覆盖导入向导。</li>
     * </ul>
     * 刻意<b>不</b>用 {@code *}（与 {@code navigate_to} 那样的任意页面工具不同）：{@code definitions/data}
     * 等"只看不改"的页面没有需要收集的条件/澄清动作，而表单一旦下发就会挂起等用户提交（
     * 与 DC-06⑤ 渐进披露的"最小必要工具集"同向）。若后续实测确有"任意页面都要澄清表单"的需求，
     * 改这一处注解即可（一行，且披露与防线③同源，不会漂移）。
     *
     * <h2>三道后端闸门（本工具与其它前端工具的区别）</h2>
     * <ol>
     * <li><b>schema 白名单</b>（{@code form/GenerativeFormRules}，下发前）：
     * 类型仅限 text/number/boolean/date/enum/multi_select，属性仅限白名单那几个，其余一律
     * {@code FORM_SCHEMA_REJECTED} 且<b>不挂起、不下发</b>（前端永远看不到非法表单）；</li>
     * <li><b>回灌类型复核</b>（回灌时）：值必须与 schema 的类型逐字段相符（number 是数、date 是
     * {@code yyyy-MM-dd} 合法日期、enum/multi_select 的值必须在选项内、未知字段拒绝、必填缺失拒绝），
     * 不符即 400 {@code FORM_RESULT_REJECTED} 且<b>挂起保持未决</b>（可修正后重试）；</li>
     * <li><b>取消终态</b>：用户关闭表单 ⇒ 前端以 {@code POST /api/ai/frontend-tool-result{...,cancelled:true}}
     * 回灌，待决条目落 {@code FRONTEND_CANCELLED}（明确终态，不会无限悬置），模型收到
     * "用户取消，未获得数据"后自行收尾。</li>
     * </ol>
     * 时间上限沿用既有前端通道口径（{@code app.ai.hitl.timeout}，默认 120s）：用户长时间不提交即
     * {@code FRONTEND_TIMEOUT}（同一条"未执行"语义）。
     *
     * <p><b>方法体是哨兵桩</b>（{@link #FRONTEND_STUB}）：副作用（渲染表单、收集值）在前端，
     * 后端只挂起与回灌；schema 与回灌的校验在挂起机制里（{@code SpToolCallingManager}/
     * {@code ConfirmGate} + {@code FrontendToolGuard}），不在此处。
     */
    @Tool(name = "generative_form", description = "在对话内渲染一张结构化表单并等待用户填写/选择（用于向用户收集筛选条件，"
            + "或在信息不足时向用户澄清）；字段类型仅限白名单 text/number/boolean/date/enum/multi_select，"
            + "禁止 HTML/脚本/自定义属性；用户提交后你会收到字段 key→值的 JSON，用户关闭表单则收到取消终态（无数据）")
    @ToolScope({ "page:tasks", "task:*" })
    @ToolRisk(ToolMeta.RiskLevel.READ)
    @ToolChannel(ToolMeta.Channel.FRONTEND)
    public String generativeForm(
            @ToolParam(description = "表单 schema：{\"scenario\":\"FILTER|CLARIFY\",\"title\":\"标题(可选)\",\"fields\":[{\"key\":\"字段键\",\"label\":\"标签\",\"type\":\"text|number|boolean|date|enum|multi_select\",\"required\":true(可选),\"defaultValue\":默认值(可选),\"options\":[{\"value\":\"值\",\"label\":\"文本\"}](enum/multi_select 必填),\"placeholder\":\"占位提示(仅 text/number/date,可选)\"}]}（字段 ≤20）") FormSpec form) {
        return FRONTEND_STUB;
    }

    /**
     * 表单 schema —— 白名单的<b>类型化镜像</b>（只为让官方 JSON Schema 生成器把内层形状写进
     * 工具 Schema，见 S4.4e 裁决③同一手法：模型不必从描述文字里猜形状）。
     *
     * <p>注意：<b>校验的事实源不是这个 record</b>，而是 {@code GenerativeFormRules} 对
     * 入参<b>原文</b>的判定 —— record 反序列化会静默丢掉未知属性，而"白名单外的属性一律拒绝"
     * 恰恰要求看得见它们。两者的属性名由用例对账锁定（防止改了 record 却忘了白名单）。
     *
     * @param scenario 场景：{@code FILTER}（收集筛选/查询条件）/ {@code CLARIFY}（信息不足时澄清）
     * @param title    表单标题（可选）
     * @param fields   字段列表（1..20）
     */
    public record FormSpec(
            @ToolParam(description = "场景：FILTER（收集筛选/查询条件）或 CLARIFY（信息不足时向用户澄清）") String scenario,
            @ToolParam(description = "表单标题（可选，≤100 字符）", required = false) String title,
            @ToolParam(description = "字段列表（1..20 个）") List<FormField> fields) {
    }

    /**
     * 单个表单字段（白名单属性集：key/label/type/required/defaultValue/options/placeholder）。
     *
     * @param key          字段键（回灌结果按此键给值；字母/下划线开头，≤40 字符，表单内唯一）
     * @param label        字段标签（≤100 字符）
     * @param type         字段类型（白名单六型）
     * @param required     是否必填（缺省 false）
     * @param defaultValue 默认值（类型必须与 {@code type} 相符；enum/multi_select 必须在选项内）
     * @param options      选项（enum/multi_select 必填；其余类型禁止带）
     * @param placeholder  占位提示（仅 text/number/date 可带）
     */
    public record FormField(
            @ToolParam(description = "字段键（字母或下划线开头，≤40 字符，表单内唯一）") String key,
            @ToolParam(description = "字段标签（≤100 字符）") String label,
            @ToolParam(description = "字段类型：text/number/boolean/date/enum/multi_select") String type,
            @ToolParam(description = "是否必填（缺省 false）", required = false) Boolean required,
            @ToolParam(description = "默认值（类型必须与 type 相符；enum/multi_select 必须在选项内）", required = false) Object defaultValue,
            @ToolParam(description = "选项（enum/multi_select 必填，其余类型禁止带）", required = false) List<FormOption> options,
            @ToolParam(description = "占位提示（仅 text/number/date 可带，≤100 字符）", required = false) String placeholder) {
    }

    /**
     * 选项 —— 统一为 {@code {"value":"…","label":"…"}} 对象（<b>不接受裸字符串</b>：
     * 一种形态一条契约，模型不必猜"这次能不能只给字符串"）。
     *
     * @param value 选项值（回灌时按此值判"是否在选项内"）
     * @param label 选项展示文本（缺省用 value）
     */
    public record FormOption(
            @ToolParam(description = "选项值（≤100 字符）") String value,
            @ToolParam(description = "选项展示文本（可选，≤100 字符，缺省用 value）", required = false) String label) {
    }

}
