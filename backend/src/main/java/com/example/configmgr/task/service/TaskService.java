package com.example.configmgr.task.service;

import com.example.configmgr.common.ResourceNotFoundException;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskItem;
import com.example.configmgr.task.entity.TaskFile;
import com.example.configmgr.task.repo.TaskRepository;
import com.example.configmgr.task.repo.TaskItemRepository;
import com.example.configmgr.task.repo.TaskFileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    private final TaskRepository taskRepository;
    private final TaskItemRepository taskItemRepository;
    private final TaskFileRepository taskFileRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public List<Task> findAll() {
        return taskRepository.findAllOrdered();
    }

    public Task findById(Long id) {
        Task task = taskRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("任务", id));
        task.setItems(taskItemRepository.findByTaskIdOrderBySortOrder(id));
        return task;
    }

    @Transactional
    public Task create(Task.TaskType type, String title) {
        Task task = new Task();
        task.setType(type);
        task.setTitle(title);
        task.setCurrentStep(type == Task.TaskType.EXPORT ? "SELECT_DEFS" : "UPLOAD");
        task = taskRepository.save(task);
        publishTaskChanged(task, "创建任务");
        return task;
    }

    @Transactional
    public Task selectDefs(Long taskId, List<String> defCodes) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> ResourceNotFoundException.of("任务", taskId));

        // Remove items not in new selection
        if (!defCodes.isEmpty()) {
            taskItemRepository.deleteByTaskIdAndDefCodeNotIn(taskId, defCodes);
        } else {
            taskItemRepository.deleteByTaskId(taskId);
        }

        // Add new items
        List<TaskItem> existing = taskItemRepository.findByTaskIdOrderBySortOrder(taskId);
        List<String> existingCodes = existing.stream().map(TaskItem::getDefCode).toList();
        for (int i = 0; i < defCodes.size(); i++) {
            String code = defCodes.get(i);
            if (!existingCodes.contains(code)) {
                TaskItem item = new TaskItem();
                item.setTaskId(taskId);
                item.setDefCode(code);
                item.setSortOrder(i);
                taskItemRepository.save(item);
            }
        }

        task.setCurrentStep("SELECT_DEFS");
        task = taskRepository.save(task);
        publishTaskChanged(task, "更新配置项选择");
        return task;
    }

    @Transactional
    public void setCondition(Long taskId, String defCode, String conditionJson) {
        TaskItem item = taskItemRepository.findByTaskIdAndDefCode(taskId, defCode)
                .orElseThrow(() -> ResourceNotFoundException.of("任务条目", defCode));
        item.setConditionJson(conditionJson);
        item.setStatus("READY");
        taskItemRepository.save(item);

        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> ResourceNotFoundException.of("任务", taskId));
        publishTaskChanged(task, "更新查询条件: " + defCode);
    }

    @Transactional
    public Task goToStep(Long taskId, String step) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> ResourceNotFoundException.of("任务", taskId));
        task.setCurrentStep(step);
        task = taskRepository.save(task);
        publishTaskChanged(task, "跳转到步骤: " + step);
        return task;
    }

    /**
     * 设置导入任务的导入模式：MERGE（增量合并）或 REPLACE（整体替换）。
     */
    @Transactional
    public Task setImportMode(Long taskId, String mode) {
        if (!"MERGE".equals(mode) && !"REPLACE".equals(mode)) {
            throw new IllegalArgumentException("导入模式只支持 MERGE 或 REPLACE");
        }
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> ResourceNotFoundException.of("任务", taskId));

        Map<String, Object> settings = new HashMap<>();
        if (task.getSettingsJson() != null && !task.getSettingsJson().isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = objectMapper.readValue(task.getSettingsJson(), Map.class);
                settings.putAll(parsed);
            } catch (Exception e) {
                log.warn("任务 {} 设置解析失败，将重建: {}", taskId, e.getMessage());
            }
        }
        settings.put("importMode", mode);
        try {
            task.setSettingsJson(objectMapper.writeValueAsString(settings));
        } catch (Exception e) {
            throw new IllegalStateException("任务设置序列化失败: " + e.getMessage(), e);
        }
        task = taskRepository.save(task);
        publishTaskChanged(task, "设置导入模式: " + mode);
        return task;
    }

    @Transactional
    public void complete(Long taskId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> ResourceNotFoundException.of("任务", taskId));
        task.setStatus(Task.TaskStatus.COMPLETED);
        task.setCurrentStep("DONE");
        taskRepository.save(task);
        publishTaskChanged(task, "任务完成");
    }

    @Transactional
    public void delete(Long taskId) {
        if (!taskRepository.existsById(taskId)) {
            throw ResourceNotFoundException.of("任务", taskId);
        }
        taskItemRepository.deleteByTaskId(taskId);
        taskFileRepository.deleteByTaskId(taskId);
        taskRepository.deleteById(taskId);
    }

    public List<TaskFile> getFiles(Long taskId) {
        return taskFileRepository.findByTaskId(taskId);
    }

    public void publishTaskChanged(Task task, String summary) {
        eventPublisher.publishEvent(new TaskChangedEvent(this, task, summary));
    }
}
