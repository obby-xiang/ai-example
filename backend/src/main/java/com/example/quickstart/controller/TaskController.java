package com.example.quickstart.controller;

import com.example.quickstart.dto.CreateTaskRequest;
import com.example.quickstart.dto.StepDataRequest;
import com.example.quickstart.dto.TaskDto;
import com.example.quickstart.service.JobService;
import com.example.quickstart.service.TaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;
    private final JobService jobService;

    @PostMapping
    public TaskDto create(@Valid @RequestBody CreateTaskRequest req) {
        return taskService.create(req.type(), req.name());
    }

    @GetMapping
    public List<TaskDto> list() {
        return taskService.list();
    }

    @GetMapping("/{id}")
    public TaskDto get(@PathVariable Long id) {
        return taskService.get(id);
    }

    @PutMapping("/{id}/step-data")
    public TaskDto updateStepData(@PathVariable Long id, @Valid @RequestBody StepDataRequest req) {
        return taskService.updateStepData(id, req.currentStep(), req.stepData());
    }

    @PostMapping("/{id}/cancel")
    public TaskDto cancel(@PathVariable Long id) {
        // 若有 RUNNING 作业则一并取消（轮次边界生效）
        jobService.cancelRunningJobsForTask(id);
        return taskService.markCancelled(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        taskService.deleteCascade(id);
        return ResponseEntity.noContent().build();
    }
}
