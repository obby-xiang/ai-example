package com.example.configmgr.task.controller;

import com.example.configmgr.common.ApiResponse;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskFile;
import com.example.configmgr.task.repo.TaskFileRepository;
import com.example.configmgr.task.repo.TaskRepository;
import com.example.configmgr.task.service.TaskService;
import com.example.configmgr.task.service.TaskSseService;
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
    private final TaskRepository taskRepository;
    private final JobRepository jobRepository;
    private final TaskFileRepository taskFileRepository;

    /**
     * 历史任务列表：类型/状态/关键词筛选 + 分页，每条附带配置项数、文件数与最新作业进度。
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
        Page<Task> result = taskRepository.search(tt, st, keyword, PageRequest.of(page, size));
        return ApiResponse.ok(result.map(this::toSummary));
    }

    /**
     * 任务详情概览：任务 + 全部作业 + 文件，供详情抽屉使用。
     */
    @GetMapping("/{id}/overview")
    public ApiResponse<Map<String, Object>> overview(@PathVariable Long id) {
        Task task = taskService.findById(id);
        List<Job> jobs = jobRepository.findByTaskIdOrderByCreatedAtDesc(id);
        List<TaskFile> files = taskFileRepository.findByTaskId(id);
        return ApiResponse.ok(Map.of("task", task, "jobs", jobs, "files", files));
    }

    private TaskSummary toSummary(Task task) {
        List<Job> jobs = jobRepository.findByTaskIdOrderByCreatedAtDesc(task.getId());
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
        String json = body.get("condition") != null ? body.get("condition").toString() : "{}";
        taskService.setCondition(id, defCode, json);
        return ApiResponse.ok();
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
