package com.example.quickstart.controller;

import com.example.quickstart.common.ApiResponse;
import com.example.quickstart.common.BizException;
import com.example.quickstart.entity.Task;
import com.example.quickstart.service.*;
import com.example.quickstart.runtime.ProgressManager;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
@Validated
public class TaskController {

    private final TaskService taskService;
    private final CatalogService catalogService;
    private final ConfigDataService dataService;
    private final ExportRunner exportRunner;
    private final ImportRunner importRunner;
    private final ProgressManager progress;
    private final AsyncRunner asyncRunner;

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody CreateReq req,
                                                   @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Task task = taskService.create(req.getType(), sessionId);
        return ApiResponse.ok(taskService.taskMap(task));
    }

    @Data
    public static class CreateReq {
        @NotBlank
        private String type;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam(required = false) String type,
                                                       @RequestParam(required = false) String status) {
        return ApiResponse.ok(taskService.listTasks(type, status));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable String id) {
        return ApiResponse.ok(taskService.taskMap(taskService.require(id)));
    }

    @PostMapping("/{id}/open")
    public ApiResponse<Map<String, Object>> open(@PathVariable String id,
                                                 @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        taskService.require(id);
        return ApiResponse.ok(taskService.taskMap(taskService.require(id)));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<Map<String, Object>> cancel(@PathVariable String id,
                                                   @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Task task = taskService.require(id);
        ProgressManager.Job job = progress.get(id);
        if (job != null && !job.isDone()) {
            progress.requestCancel(id);
        } else {
            taskService.assertNotTerminal(task);
            taskService.markTerminal(task, TaskService.CANCELLED);
        }
        taskService.publishUpdated(sessionId, id);
        return ApiResponse.ok(taskService.taskMap(task));
    }

    /* ---------- 步骤1：选择配置 ---------- */

    @Data
    public static class SelectionReq {
        private List<String> codes;
    }

    @PostMapping("/{id}/selection")
    public ApiResponse<Map<String, Object>> setSelection(@PathVariable String id,
                                                         @RequestBody SelectionReq req,
                                                         @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Task task = taskService.require(id);
        taskService.assertNotTerminal(task);
        taskService.assertRunningNotAllowed(task);
        List<String> codes = req.getCodes();
        if (codes == null || codes.isEmpty()) {
            throw new BizException("请至少选择一个配置项");
        }
        for (String c : codes) {
            catalogService.listConfigs().stream().filter(x -> x.getCode().equals(c)).findFirst()
                    .orElseThrow(() -> new BizException("配置项不存在：" + c));
        }
        Map<String, Object> params = taskService.paramsOf(task);
        if (params.get("selection") instanceof List<?> old && !codes.equals(old)) {
            // 选择变化：清空后续步骤数据
            if (taskService.EXPORT_CONFIG.equals(task.getType())) {
                params.put("conditions", new java.util.LinkedHashMap<String, Object>());
                params.put("exportResult", null);
            } else {
                params.put("uploads", new java.util.LinkedHashMap<String, Object>());
                params.put("checkResult", null);
                params.put("importResult", null);
                params.put("publishResult", null);
            }
        }
        params.put("selection", codes);
        taskService.updateParams(task, params);
        // 选完即可进入下一步
        String next = taskService.EXPORT_CONFIG.equals(task.getType()) ? "SET_CONDITION" : "PREPARE";
        taskService.moveToStep(task, next);
        taskService.publishUpdated(sessionId, id);
        return ApiResponse.ok(taskService.taskMap(task));
    }

    /* ---------- 步骤2（导出）：查询条件 ---------- */

    @Data
    public static class ConditionsReq {
        private Map<String, List<ConditionDTO.Condition>> perConfig;
    }

    @PostMapping("/{id}/conditions")
    public ApiResponse<Map<String, Object>> setConditions(@PathVariable String id,
                                                          @RequestBody ConditionsReq req,
                                                          @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Task task = taskService.require(id);
        taskService.assertNotTerminal(task);
        taskService.assertRunningNotAllowed(task);
        taskService.assertStep(task, "SET_CONDITION");
        Map<String, List<ConditionDTO.Condition>> per = req.getPerConfig() == null ? Map.of() : req.getPerConfig();
        for (String code : taskService.selectionOf(task)) {
            List<ConditionDTO.Condition> conds = per.getOrDefault(code, List.of());
            dataService.queryPublished(code, conds);
        }
        Map<String, Object> params = taskService.paramsOf(task);
        params.put("conditions", per);
        taskService.updateParams(task, params);
        taskService.publishUpdated(sessionId, id);
        return ApiResponse.ok(taskService.taskMap(task));
    }

    /* ---------- 步骤3（导出）：执行 ---------- */

    @PostMapping("/{id}/export/start")
    public ApiResponse<Map<String, Object>> startExport(@PathVariable String id,
                                                        @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Task task = taskService.require(id);
        taskService.assertNotTerminal(task);
        taskService.assertStep(task, "SET_CONDITION");
        taskService.assertSelection(taskService.paramsOf(task));
        asyncRunner.runExport(task, sessionId);
        return ApiResponse.ok(Map.of("started", true));
    }

    @GetMapping("/{id}/export/results")
    public ApiResponse<Object> exportResults(@PathVariable String id) {
        Task task = taskService.require(id);
        Map<String, Object> params = taskService.paramsOf(task);
        return ApiResponse.ok(params.get("exportResult"));
    }

    /* ---------- 步骤2（导入）：数据提交 ---------- */

    @Data
    public static class DataReq {
        private String configCode;
        private String fileName;
        private List<Map<String, Object>> rows;
    }

    @PostMapping("/{id}/data")
    public ApiResponse<Map<String, Object>> submitData(@PathVariable String id,
                                                       @RequestBody DataReq req,
                                                       @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Task task = taskService.require(id);
        taskService.assertNotTerminal(task);
        taskService.assertRunningNotAllowed(task);
        taskService.assertStep(task, "PREPARE");
        if (req.getRows() != null && req.getRows().size() > 5000) {
            throw new BizException("单配置项数据超过 5000 行上限");
        }
        List<String> selection = taskService.selectionOf(task);
        if (!selection.contains(req.getConfigCode())) {
            throw new BizException("配置项 " + req.getConfigCode() + " 不在本任务选择范围内");
        }
        Map<String, Object> params = taskService.paramsOf(task);
        @SuppressWarnings("unchecked")
        Map<String, Object> uploads = (Map<String, Object>) params.computeIfAbsent("uploads",
                k -> new java.util.LinkedHashMap<String, Object>());
        Map<String, Object> one = new java.util.LinkedHashMap<>();
        one.put("fileName", req.getFileName() == null ? "" : req.getFileName());
        one.put("rows", req.getRows() == null ? List.of() : req.getRows());
        uploads.put(req.getConfigCode(), one);
        // 数据变化后，后续检查/导入/发布结果失效
        params.put("checkResult", null);
        params.put("importResult", null);
        params.put("publishResult", null);
        taskService.updateParams(task, params);
        taskService.publishUpdated(sessionId, id);
        return ApiResponse.ok(taskService.taskMap(task));
    }

    /* ---------- 步骤3-5（导入）：检查/导入/发布 ---------- */

    @PostMapping("/{id}/check/start")
    public ApiResponse<Map<String, Object>> startCheck(@PathVariable String id,
                                                       @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Task task = taskService.require(id);
        taskService.assertNotTerminal(task);
        taskService.assertStep(task, "PREPARE");
        taskService.assertSelection(taskService.paramsOf(task));
        asyncRunner.runCheck(task, sessionId);
        return ApiResponse.ok(Map.of("started", true));
    }

    @PostMapping("/{id}/import/start")
    public ApiResponse<Map<String, Object>> startImport(@PathVariable String id,
                                                        @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Task task = taskService.require(id);
        taskService.assertNotTerminal(task);
        taskService.assertStep(task, "CHECK");
        Map<String, Object> params = taskService.paramsOf(task);
        taskService.assertSelection(params);
        Object check = params.get("checkResult");
        if (!(check instanceof Map<?, ?> cm) || Boolean.TRUE.equals(cm.get("hasError"))) {
            throw new BizException("检查未通过，不能导入（请先执行检查并修复错误）");
        }
        asyncRunner.runImport(task, sessionId);
        return ApiResponse.ok(Map.of("started", true));
    }

    @PostMapping("/{id}/publish/start")
    public ApiResponse<Map<String, Object>> startPublish(@PathVariable String id,
                                                         @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Task task = taskService.require(id);
        taskService.assertNotTerminal(task);
        taskService.assertStep(task, "IMPORT");
        Map<String, Object> params = taskService.paramsOf(task);
        taskService.assertSelection(params);
        if (!(params.get("importResult") instanceof Map<?, ?>)) {
            throw new BizException("尚未导入，不能发布");
        }
        asyncRunner.runPublish(task, sessionId);
        return ApiResponse.ok(Map.of("started", true));
    }
}
