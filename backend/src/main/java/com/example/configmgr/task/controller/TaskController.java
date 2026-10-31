package com.example.configmgr.task.controller;

import com.example.configmgr.common.CodedIllegalArgumentException;
import com.example.configmgr.common.ErrorCodes;

import com.example.configmgr.common.ApiResponse;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskFile;
import com.example.configmgr.task.repo.TaskFileRepository;
import com.example.configmgr.task.service.TaskService;
import com.example.configmgr.task.service.TaskSseService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;
    private final TaskSseService taskSseService;
    private final JobRepository jobRepository;
    private final TaskFileRepository taskFileRepository;
    /** B1：条件入参的 JSON 序列化/校验（不再用 Map.toString 落库）。 */
    private final ObjectMapper objectMapper;

    /**
     * 历史任务列表：类型/状态/关键词筛选 + 分页，每条附带配置项数、文件数与最新作业进度。
     *
     * <p>关键词的 LIKE 通配符转义在 {@link TaskService#search}（Q16②）。
     */
    @GetMapping
    public ApiResponse<Page<TaskSummary>> list(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Task.TaskType tt = (type == null || type.isBlank()) ? null : Task.TaskType.valueOf(type.toUpperCase());
        Task.TaskStatus st = (status == null || status.isBlank()) ? null : Task.TaskStatus.valueOf(status.toUpperCase());
        Page<Task> result = taskService.search(tt, st, keyword, PageRequest.of(page, size));
        return ApiResponse.ok(result.map(this::toSummary));
    }

    /**
     * 任务详情概览：任务 + 全部作业 + 文件。
     */
    @GetMapping("/{id}/overview")
    public ApiResponse<Map<String, Object>> overview(@PathVariable Long id) {
        Task task = taskService.findById(id);
        List<Job> jobs = jobRepository.findByTaskIdOrderByCreatedAtDescIdDesc(id);
        List<TaskFile> files = taskFileRepository.findByTaskId(id);
        return ApiResponse.ok(Map.of("task", task, "jobs", jobs, "files", files));
    }

    private TaskSummary toSummary(Task task) {
        List<Job> jobs = jobRepository.findByTaskIdOrderByCreatedAtDescIdDesc(task.getId());
        Job latest = jobs.isEmpty() ? null : jobs.get(0);
        int fileCount = taskFileRepository.findByTaskId(task.getId()).size();
        return new TaskSummary(task,
                task.getItems() != null ? task.getItems().size() : 0,
                fileCount, latest);
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Task>> create(@RequestBody Map<String, String> body) {
        Task.TaskType type = Task.TaskType.valueOf(body.getOrDefault("type", "EXPORT"));
        String title = body.getOrDefault("title", (type == Task.TaskType.EXPORT ? "导出任务" : "导入任务"));
        Task task = taskService.create(type, title);
        return ResponseEntity.status(201).body(ApiResponse.ok(task));
    }

    @GetMapping("/{id}")
    public ApiResponse<Task> get(@PathVariable Long id) {
        return ApiResponse.ok(taskService.findById(id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<?> delete(@PathVariable Long id) {
        taskService.delete(id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/select-defs")
    public ApiResponse<Task> selectDefs(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> codes = (List<String>) body.get("defCodes");
        return ApiResponse.ok(taskService.selectDefs(id, codes != null ? codes : List.of()));
    }

    @PutMapping("/{id}/items/{defCode}/condition")
    public ApiResponse<?> setCondition(@PathVariable Long id,
                                        @PathVariable String defCode,
                                        @RequestBody Map<String, Object> body) {
        taskService.setCondition(id, defCode, conditionJson(body.get("condition")));
        return ApiResponse.ok();
    }

    /**
     * 条件入参 → 落库的 JSON 文本（DC-14 B1 修复）。
     *
     * <p>
     * <b>为什么不能 {@code toString()}</b>：前端 {@code PUT /tasks/{id}/items/{defCode}/condition}
     * 的 {@code condition} 是<b>对象</b>（{@code frontend/src/api/tasks.ts#setCondition} 直接传
     * 契约事件里的 {@code conditions}），而 {@code Map.toString()} 会产出
     * {@code {scopeKeys=[XN], fields=[...]}} 这种"看着像 JSON、其实不是"的文本 ——
     * 落库后所有读侧（导出/导入作业、{@code data/service/QueryCondition}、
     * 前端 {@code parseConditionJson}）解析<b>全部</b>失败，且失败发生在<b>别的</b>请求里
     * （本请求 200 成功），排障成本极高。
     *
     * <p>
     * 两种入参形态都支持：字符串则按"已是 JSON 文本"处理，但**必须校验可解析**
     * （非法文本同样会造成上述脏数据）；对象/数组则用 Jackson 序列化。
     *
     * <p>
     * 2026-10-31（F5）：写入面只把<b>恰好 {@code {}}</b> 当"明确不设"。空数组形态
     * （{@code fields:[]} / {@code scopeKeys:[]}）以及非对象载荷一律 400、零落库 ——
     * 它们曾被读侧当成 {@code EXPLICIT_UNSET}（全量），与"明确不设"的唯一写法混淆。
     */
    private String conditionJson(Object condition) {
        if (condition == null) {
            return "{}";
        }
        String text;
        if (condition instanceof String raw) {
            text = raw.trim();
            if (text.isEmpty()) {
                return "{}";
            }
        }
        else {
            try {
                text = objectMapper.writeValueAsString(condition);
            }
            catch (Exception e) {
                throw new CodedIllegalArgumentException(ErrorCodes.CONDITION_JSON_INVALID, "condition 无法序列化为 JSON：" + e.getMessage());
            }
        }
        // F5（2026-10-31）：写入面只把**恰好 {}** 当"明确不设"。空数组形态
        // （fields:[] / scopeKeys:[]）是"有结构但没有条件"，落库后会被读侧当 EXPLICIT_UNSET（全量），
        // 与"恰好 {}"的语义混淆 —— 在这里直接 400，零落库（读侧也按未决定处理，见 ConditionAssessment）。
        JsonNode parsed;
        try {
            parsed = objectMapper.readTree(text);
        }
        catch (Exception e) {
            throw new CodedIllegalArgumentException(ErrorCodes.CONDITION_JSON_INVALID, "condition 不是合法 JSON：" + e.getMessage());
        }
        if (parsed == null || !parsed.isObject()) {
            throw new CodedIllegalArgumentException(ErrorCodes.CONDITION_JSON_INVALID,
                    "condition 必须是 JSON 对象（明确不设请恰好传 {}）：" + text);
        }
        if (parsed.size() > 0 && !hasConditionContent(parsed)) {
            throw new CodedIllegalArgumentException(ErrorCodes.CONDITION_JSON_INVALID,
                    "condition 不能是空数组形态（fields:[] / scopeKeys:[] 不是“明确不设”，明确不设请恰好传 {}）：" + text);
        }
        return text;
    }

    /** 条件是否至少有一项可提交的内容（与 {@code ConditionAssessment} 的"有事实"同口径）。 */
    private static boolean hasConditionContent(JsonNode condition) {
        JsonNode fields = condition.get("fields");
        if (fields != null && fields.isArray() && !fields.isEmpty()) {
            return true;
        }
        JsonNode scopeKeys = condition.get("scopeKeys");
        return scopeKeys != null && scopeKeys.isArray() && !scopeKeys.isEmpty();
    }

    @PutMapping("/{id}/step")
    public ApiResponse<Task> goToStep(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return ApiResponse.ok(taskService.goToStep(id, body.get("step")));
    }

    @PutMapping("/{id}/import-mode")
    public ApiResponse<Task> setImportMode(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return ApiResponse.ok(taskService.setImportMode(id, body.get("mode")));
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter taskEvents(@PathVariable Long id) {
        return taskSseService.subscribe(id);
    }

    @GetMapping("/{id}/files")
    public ApiResponse<List<TaskFile>> files(@PathVariable Long id) {
        return ApiResponse.ok(taskService.getFiles(id));
    }
}
