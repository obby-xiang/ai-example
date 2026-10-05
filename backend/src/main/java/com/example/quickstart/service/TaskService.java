package com.example.quickstart.service;

import com.example.quickstart.common.BizException;
import com.example.quickstart.common.JsonUtil;
import com.example.quickstart.entity.Task;
import com.example.quickstart.repository.TaskRepository;
import com.example.quickstart.runtime.EventPublisher;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 任务服务：状态机 + 持久化 + 步骤守卫。
 * REST 与 AI 工具共用本层（单一事实源，联动解耦的关键）。
 */
@Service
@RequiredArgsConstructor
public class TaskService {

    public static final String EXPORT_CONFIG = "EXPORT_CONFIG";
    public static final String IMPORT_CONFIG = "IMPORT_CONFIG";

    public static final List<String> EXPORT_STEPS = List.of("SELECT_CONFIG", "SET_CONDITION", "EXECUTE_EXPORT");
    public static final List<String> IMPORT_STEPS = List.of("SELECT_CONFIG", "PREPARE", "CHECK", "IMPORT", "PUBLISH");

    public static final String WAITING = "WAITING";
    public static final String EXECUTING = "EXECUTING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    private final TaskRepository taskRepo;
    private final EventPublisher events;

    @Transactional
    public Task create(String type, String sessionId) {
        if (!EXPORT_CONFIG.equals(type) && !IMPORT_CONFIG.equals(type)) {
            throw new BizException("不支持的任务类型：" + type);
        }
        Task task = new Task();
        task.setId(UUID.randomUUID().toString().replace("-", ""));
        task.setType(type);
        task.setStatus(WAITING);
        task.setCurrentStep("SELECT_CONFIG");
        task.setTitle(EXPORT_CONFIG.equals(type) ? "导出配置" : "导入配置");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("selection", null);
        if (EXPORT_CONFIG.equals(type)) {
            params.put("conditions", new LinkedHashMap<String, Object>());
            params.put("exportResult", null);
        } else {
            params.put("uploads", new LinkedHashMap<String, Object>());
            params.put("checkResult", null);
            params.put("importResult", null);
            params.put("publishResult", null);
        }
        task.setParams(JsonUtil.write(params));
        LocalDateTime now = LocalDateTime.now();
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        task = taskRepo.save(task);
        events.publishState(sessionId, task.getId(), "CREATED");
        return task;
    }

    @Transactional(readOnly = true)
    public Task require(String taskId) {
        return taskRepo.findById(taskId)
                .orElseThrow(() -> new BizException("任务不存在：" + taskId));
    }

    @Transactional
    public Task save(Task task) {
        task.setUpdatedAt(LocalDateTime.now());
        return taskRepo.save(task);
    }

    public Map<String, Object> paramsOf(Task task) {
        return JsonUtil.read(task.getParams(), new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    @Transactional
    public Task updateParams(Task task, Map<String, Object> params) {
        task.setParams(JsonUtil.write(params));
        return save(task);
    }

    public void publishUpdated(String sessionId, String taskId) {
        events.publishState(sessionId, taskId, "UPDATED");
    }

    /* ---------- 状态机守卫 ---------- */

    public void assertNotTerminal(Task task) {
        if (SUCCESS.equals(task.getStatus()) || FAILED.equals(task.getStatus())
                || CANCELLED.equals(task.getStatus())) {
            throw new BizException("任务已结束（" + task.getStatus() + "），不能继续操作");
        }
    }

    public void assertRunningNotAllowed(Task task) {
        if (EXECUTING.equals(task.getStatus())) {
            throw new BizException("任务正在执行后台操作，请稍候");
        }
    }

    public List<String> stepsOf(Task task) {
        return EXPORT_CONFIG.equals(task.getType()) ? EXPORT_STEPS : IMPORT_STEPS;
    }

    public void assertStep(Task task, String expected) {
        if (!task.getCurrentStep().equals(expected)) {
            throw new BizException("当前步骤不允许该操作（当前：" + task.getCurrentStep()
                    + "，需要：" + expected + "）");
        }
    }

    public void assertStepAtLeast(Task task, String step) {
        List<String> steps = stepsOf(task);
        if (steps.indexOf(task.getCurrentStep()) < steps.indexOf(step)) {
            throw new BizException("操作前置步骤未完成（需要先完成 " + step + "）");
        }
    }

    public void assertSelection(Map<String, Object> params) {
        Object sel = params.get("selection");
        if (!(sel instanceof List<?> list) || list.isEmpty()) {
            throw new BizException("请先在第一步选择至少一个配置项");
        }
    }

    @SuppressWarnings("unchecked")
    public List<String> selectionOf(Task task) {
        Object sel = paramsOf(task).get("selection");
        return sel instanceof List ? (List<String>) sel : List.of();
    }

    @Transactional
    public Task moveToStep(Task task, String step) {
        List<String> steps = stepsOf(task);
        if (!steps.contains(step)) {
            throw new BizException("非法步骤：" + step);
        }
        task.setCurrentStep(step);
        return save(task);
    }

    @Transactional
    public Task markTerminal(Task task, String status) {
        task.setStatus(status);
        task.setFinishedAt(LocalDateTime.now());
        return save(task);
    }

    /* ---------- 查询 ---------- */

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listTasks(String type, String status) {
        List<Task> tasks;
        if (type != null && !type.isBlank() && status != null && !status.isBlank()) {
            tasks = taskRepo.findByTypeAndStatusOrderByCreatedAtDesc(type, status);
        } else if (type != null && !type.isBlank()) {
            tasks = taskRepo.findByTypeOrderByCreatedAtDesc(type);
        } else if (status != null && !status.isBlank()) {
            tasks = taskRepo.findByStatusOrderByCreatedAtDesc(status);
        } else {
            tasks = taskRepo.findTop50ByOrderByCreatedAtDesc();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Task t : tasks) {
            result.add(summaryOf(t));
        }
        return result;
    }

    public Map<String, Object> summaryOf(Task task) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", task.getId());
        m.put("type", task.getType());
        m.put("status", task.getStatus());
        m.put("currentStep", task.getCurrentStep());
        m.put("title", task.getTitle());
        m.put("createdAt", task.getCreatedAt() == null ? null : task.getCreatedAt().toString());
        m.put("updatedAt", task.getUpdatedAt() == null ? null : task.getUpdatedAt().toString());
        Object sel = paramsOf(task).get("selection");
        m.put("selection", sel);
        return m;
    }

    public Map<String, Object> taskMap(Task task) {
        Map<String, Object> m = summaryOf(task);
        m.put("params", paramsOf(task));
        return m;
    }

    public Map<String, Object> taskMap(String taskId) {
        return taskMap(require(taskId));
    }
}
