package com.example.quickstart.ai;

import com.example.quickstart.common.BizException;
import com.example.quickstart.common.JsonUtil;
import com.example.quickstart.entity.Task;
import com.example.quickstart.runtime.EventPublisher;
import com.example.quickstart.runtime.SessionHolder;
import com.example.quickstart.service.AsyncRunner;
import com.example.quickstart.service.CatalogService;
import com.example.quickstart.service.TaskService;
import com.example.quickstart.service.ValidationService;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 导入任务工具：仅在当前会话绑定的任务为导入类型时注册（渐进式披露第一层）。
 */
@Component
public class ImportAiTools extends AiToolSupport {

    private final EventPublisher events;
    private final AsyncRunner asyncRunner;

    public ImportAiTools(TaskService taskService, CatalogService catalogService,
                         SessionHolder sessionHolder, EventPublisher events,
                         AsyncRunner asyncRunner) {
        super(taskService, catalogService, sessionHolder);
        this.events = events;
        this.asyncRunner = asyncRunner;
    }

    @Tool(description = "【导入任务】查看各配置项数据就绪情况（是否已提交数据、行数、来源文件名）")
    public Map<String, Object> get_prepare_status(ToolContext ctx) {
        String sessionId = sid(ctx);
        Task task = requireActive(sessionId);
        taskService.assertStepAtLeast(task, "PREPARE");
        Map<String, Object> params = taskService.paramsOf(task);
        List<Map<String, Object>> status = new ArrayList<>();
        Object uploads = params.get("uploads");
        for (String code : taskService.selectionOf(task)) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("configCode", code);
            if (uploads instanceof Map<?, ?> m && m.get(code) != null) {
                ValidationService.UploadData d = ValidationService.toUploadData(m.get(code));
                s.put("ready", true);
                s.put("rowCount", d.rows().size());
                s.put("fileName", d.fileName());
            } else {
                s.put("ready", false);
                s.put("hint", "用户可在工作区上传 Excel/zip，或让我用 submit_config_data 直接提交数据");
            }
            status.add(s);
        }
        return Map.of("configs", status);
    }

    @Tool(description = "【导入任务】为某配置项直接提交数据（在线编辑通路）。rowsJson 为行数组，每行是 {字段编码: 值}；字段编码请先用 get_config_item_fields 查询")
    public Map<String, Object> submit_config_data(
            @ToolParam(description = "配置项编码") String configCode,
            @ToolParam(description = "行数据 JSON 字符串，如 [{\"paramKey\":\"a\",\"paramValue\":\"1\"}]") String rowsJson,
            @ToolParam(description = "来源说明，如 用户口述 或 AI生成", required = false) String source,
            ToolContext ctx) {
        String sessionId = sid(ctx);
        Task task = requireActive(sessionId);
        taskService.assertStep(task, "PREPARE");
        List<Map<String, Object>> rows;
        try {
            rows = JsonUtil.read(rowsJson, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            throw new BizException("rowsJson 不是合法的 JSON 数组");
        }
        if (rows.size() > 5000) {
            throw new BizException("单配置项数据超过 5000 行上限");
        }
        List<String> selection = taskService.selectionOf(task);
        if (!selection.contains(configCode)) {
            throw new BizException("配置项 " + configCode + " 不在本任务选择范围内");
        }
        Map<String, Object> params = taskService.paramsOf(task);
        @SuppressWarnings("unchecked")
        Map<String, Object> uploads = (Map<String, Object>) params.computeIfAbsent("uploads",
                k -> new LinkedHashMap<String, Object>());
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("fileName", source == null ? "AI在线编辑" : source);
        one.put("rows", rows);
        uploads.put(configCode, one);
        params.put("checkResult", null);
        params.put("importResult", null);
        params.put("publishResult", null);
        taskService.updateParams(task, params);
        taskService.publishUpdated(sessionId, task.getId());
        events.publishActivity(sessionId, "submit_config_data",
                configCode + " 已提交 " + rows.size() + " 行数据");
        return Map.of("saved", true, "configCode", configCode, "rowCount", rows.size());
    }

    @Tool(description = "【导入任务】启动检查（异步执行，含结构校验与跨配置依赖校验，进度会推送给工作区）")
    public Map<String, Object> start_check(ToolContext ctx) {
        String sessionId = sid(ctx);
        Task task = requireActive(sessionId);
        taskService.assertStep(task, "PREPARE");
        taskService.assertSelection(taskService.paramsOf(task));
        events.publishActivity(sessionId, "start_check", "已启动配置检查");
        asyncRunner.runCheck(task, sessionId);
        return Map.of("started", true, "hint", "检查已开始，稍后用 get_check_results 查看结果");
    }

    @Tool(description = "【导入任务】查看检查结果：各配置项错误/警告数与错误明细")
    public Map<String, Object> get_check_results(ToolContext ctx) {
        String sessionId = sid(ctx);
        Task task = requireActive(sessionId);
        taskService.assertStepAtLeast(task, "CHECK");
        Object check = taskService.paramsOf(task).get("checkResult");
        if (!(check instanceof Map<?, ?> m)) {
            return Map.of("hint", "尚未执行检查");
        }
        return castMap(m);
    }

    @Tool(description = "【导入任务】启动导入（异步执行，数据进入暂存区不影响已发布数据。务必先向用户确认）")
    public Map<String, Object> start_import(ToolContext ctx) {
        String sessionId = sid(ctx);
        Task task = requireActive(sessionId);
        taskService.assertStep(task, "CHECK");
        Map<String, Object> params = taskService.paramsOf(task);
        Object check = params.get("checkResult");
        if (!(check instanceof Map<?, ?> cm) || Boolean.TRUE.equals(cm.get("hasError"))) {
            throw new BizException("检查未通过，不能导入（请先执行检查并修复错误）");
        }
        events.publishActivity(sessionId, "start_import", "已启动导入（暂存）");
        asyncRunner.runImport(task, sessionId);
        return Map.of("started", true, "hint", "导入已开始（暂存不发布），完成后可用 get_import_summary 查看");
    }

    @Tool(description = "【导入任务】查看导入结果摘要：各配置项暂存行数与原已发布行数对比")
    public Map<String, Object> get_import_summary(ToolContext ctx) {
        String sessionId = sid(ctx);
        Task task = requireActive(sessionId);
        taskService.assertStepAtLeast(task, "IMPORT");
        Object result = taskService.paramsOf(task).get("importResult");
        if (!(result instanceof Map<?, ?> m)) {
            return Map.of("hint", "尚未导入");
        }
        return castMap(m);
    }

    @Tool(description = "【导入任务】启动发布（异步执行，按配置项+适用范围替换已发布数据，正式生效，务必先得到用户明确确认）")
    public Map<String, Object> start_publish(ToolContext ctx) {
        String sessionId = sid(ctx);
        Task task = requireActive(sessionId);
        taskService.assertStep(task, "IMPORT");
        Map<String, Object> params = taskService.paramsOf(task);
        if (!(params.get("importResult") instanceof Map<?, ?>)) {
            throw new BizException("尚未导入，不能发布");
        }
        events.publishActivity(sessionId, "start_publish", "已启动发布");
        asyncRunner.runPublish(task, sessionId);
        return Map.of("started", true, "hint", "发布已开始，完成后任务即成功");
    }
}
