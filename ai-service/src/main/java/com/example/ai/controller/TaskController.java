package com.example.ai.controller;

import com.example.ai.dto.R;
import com.example.ai.dto.TaskDTO;
import com.example.ai.entity.Task;
import com.example.ai.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

    @GetMapping
    public R<List<Task>> list() {
        return R.ok(taskService.listAll());
    }

    @GetMapping("/{id}")
    public R<Task> get(@PathVariable Long id) {
        return R.ok(taskService.getById(id).orElseThrow(() -> new RuntimeException("任务不存在:" + id)));
    }

    @PostMapping
    public R<Task> create(@RequestBody TaskDTO.CreateReq req) {
        return R.ok(taskService.create(req.getName()));
    }

    @PostMapping("/select-scenario")
    public R<Task> selectScenario(@RequestBody TaskDTO.SelectScenarioReq req) {
        return R.ok(taskService.selectScenario(req.getTaskId(), req.getScenario()));
    }

    @PostMapping("/update-step")
    public R<Task> updateStep(@RequestBody TaskDTO.UpdateStepReq req) {
        return R.ok(taskService.updateStepData(req.getTaskId(), req.getStep(), req.getStepData()));
    }

    @PostMapping("/update-selected")
    public R<Task> updateSelected(@RequestBody TaskDTO.UpdateSelectedReq req) {
        return R.ok(taskService.updateSelectedDefs(req.getTaskId(), req.getDefIds()));
    }

    @PostMapping("/goto-step")
    public R<Task> gotoStep(@RequestBody Map<String, Object> body) {
        Long taskId = Long.valueOf(String.valueOf(body.get("taskId")));
        String step = String.valueOf(body.get("step"));
        return R.ok(taskService.gotoStep(taskId, step));
    }

    @PostMapping("/{id}/complete")
    public R<Task> complete(@PathVariable Long id) {
        return R.ok(taskService.complete(id));
    }

    @PostMapping("/{id}/changes")
    public R<Task> updateChanges(@PathVariable Long id, @RequestBody Map<String, Object> changes) {
        return R.ok(taskService.updateChanges(id, changes));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        taskService.delete(id);
        return R.ok();
    }

    @GetMapping("/meta/scenarios")
    public R<Map<String, Object>> scenarios() {
        Map<String, Object> res = Map.of(
                "scenarioName", TaskService.SCENARIO_NAME,
                "scenarioSteps", TaskService.SCENARIO_STEPS
        );
        return R.ok(res);
    }
}
