package com.example.ai.service;

import com.example.ai.entity.Task;
import com.example.ai.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class TaskService {

    private final TaskRepository taskRepository;

    /** 场景步骤定义 */
    public static final Map<String, List<String>> SCENARIO_STEPS = Map.of(
            "EXPORT", List.of("SELECT_SCENARIO", "SELECT_DEFS", "QUERY_COND", "RESULT"),
            "IMPORT", List.of("SELECT_SCENARIO", "VIEW_DEFS", "PRECHECK", "REVIEW", "PUBLISH"),
            "ADD",    List.of("SELECT_SCENARIO", "VIEW_DEFS", "PRECHECK", "REVIEW", "PUBLISH"),
            "MODIFY", List.of("SELECT_SCENARIO", "VIEW_DEFS", "PRECHECK", "REVIEW", "PUBLISH")
    );

    public static final Map<String, String> SCENARIO_NAME = Map.of(
            "EXPORT", "导出配置",
            "IMPORT", "导入配置",
            "ADD", "新增配置",
            "MODIFY", "修改配置"
    );

    public List<Task> listAll() {
        return taskRepository.findAllByOrderByUpdatedAtDesc();
    }

    public Optional<Task> getById(Long id) {
        return taskRepository.findById(id);
    }

    @Transactional
    public Task create(String name) {
        String actualName = (name == null || name.isBlank()) ? "未命名任务-" + System.currentTimeMillis() : name;
        Task t = Task.builder()
                .name(actualName)
                .status("DRAFT")
                .currentStep("SELECT_SCENARIO")
                .build();
        return taskRepository.save(t);
    }

    @Transactional
    public Task selectScenario(Long taskId, String scenario) {
        Task t = taskRepository.findById(taskId).orElseThrow(() -> new RuntimeException("任务不存在:" + taskId));
        if (!SCENARIO_STEPS.containsKey(scenario)) {
            throw new RuntimeException("未知场景:" + scenario);
        }
        t.setScenario(scenario);
        t.setCurrentStep(SCENARIO_STEPS.get(scenario).get(1)); // 跳转到场景后第二步
        t.setStatus("IN_PROGRESS");
        return taskRepository.save(t);
    }

    @Transactional
    public Task updateStepData(Long taskId, String step, Object stepData) {
        Task t = taskRepository.findById(taskId).orElseThrow(() -> new RuntimeException("任务不存在:" + taskId));
        t.getStepData().put(step, stepData);
        t.setCurrentStep(step);
        return taskRepository.save(t);
    }

    @Transactional
    public Task updateSelectedDefs(Long taskId, List<Long> defIds) {
        Task t = taskRepository.findById(taskId).orElseThrow(() -> new RuntimeException("任务不存在:" + taskId));
        t.setSelectedDefIdList(defIds);
        return taskRepository.save(t);
    }

    @Transactional
    public Task gotoStep(Long taskId, String step) {
        Task t = taskRepository.findById(taskId).orElseThrow(() -> new RuntimeException("任务不存在:" + taskId));
        // 跨场景跳步防护：步骤序列由场景决定。未选场景时只允许停留在 SELECT_SCENARIO，
        // 否则前端 taskStore.steps 会因 scenario=null 返回空数组，导致 el-steps 不渲染（进度条不同步 bug 根因）。
        // 合法的初始化跳步（selectScenario 直接 setCurrentStep 到第二步）不走本方法，故不受影响。
        if (t.getScenario() == null) {
            if (!"SELECT_SCENARIO".equals(step)) {
                throw new RuntimeException("请先选择场景再进入后续步骤（当前任务尚未选定场景）");
            }
        } else {
            List<String> steps = SCENARIO_STEPS.get(t.getScenario());
            if (steps != null && !steps.contains(step)) {
                throw new RuntimeException("场景" + t.getScenario() + " 不包含步骤: " + step);
            }
        }
        t.setCurrentStep(step);
        return taskRepository.save(t);
    }

    @Transactional
    public Task complete(Long taskId) {
        Task t = taskRepository.findById(taskId).orElseThrow(() -> new RuntimeException("任务不存在:" + taskId));
        t.setStatus("COMPLETED");
        return taskRepository.save(t);
    }

    @Transactional
    public Task updateChanges(Long taskId, Map<String, Object> changes) {
        Task t = taskRepository.findById(taskId).orElseThrow(() -> new RuntimeException("任务不存在:" + taskId));
        t.setChanges(changes);
        return taskRepository.save(t);
    }

    @Transactional
    public void delete(Long taskId) {
        taskRepository.deleteById(taskId);
    }
}
