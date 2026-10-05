package com.example.configmgr.task.controller;

import com.example.configmgr.common.ApiResponse;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskFile;
import com.example.configmgr.task.service.TaskService;
import com.example.configmgr.task.service.TaskSseService;
import lombok.RequiredArgsConstructor;
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

    @GetMapping
    public ApiResponse<List<Task>> list() {
        return ApiResponse.ok(taskService.findAll());
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

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter taskEvents(@PathVariable Long id) {
        return taskSseService.subscribe(id);
    }

    @GetMapping("/{id}/files")
    public ApiResponse<List<TaskFile>> files(@PathVariable Long id) {
        return ApiResponse.ok(taskService.getFiles(id));
    }
}
