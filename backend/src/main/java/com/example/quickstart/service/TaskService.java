package com.example.quickstart.service;

import com.example.quickstart.dto.TaskDto;
import com.example.quickstart.entity.JobRun;
import com.example.quickstart.entity.Task;
import com.example.quickstart.repository.ExportResultRepository;
import com.example.quickstart.repository.JobItemRepository;
import com.example.quickstart.repository.JobRunRepository;
import com.example.quickstart.repository.StagingConfigDataRepository;
import com.example.quickstart.repository.TaskRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TaskService {

    private final TaskRepository taskRepository;
    private final JobRunRepository jobRunRepository;
    private final JobItemRepository jobItemRepository;
    private final ExportResultRepository exportResultRepository;
    private final StagingConfigDataRepository stagingRepository;
    private final ObjectMapper om;

    /** 选择任务类型创建任务时即持久化。初始 DRAFT / currentStep=1 / stepData={} */
    @Transactional
    public synchronized TaskDto create(String type, String name) {
        String normalizedType = type == null ? "" : type.trim().toUpperCase();
        if (!normalizedType.equals("EXPORT") && !normalizedType.equals("IMPORT")) {
            throw new IllegalArgumentException("不支持的任务类型: " + type + "（仅支持 EXPORT/IMPORT）");
        }
        Task task = new Task();
        task.setTaskNo(nextTaskNo(normalizedType));
        task.setType(normalizedType);
        task.setName(name.trim());
        task.setStatus("DRAFT");
        task.setCurrentStep(1);
        task.setStepData("{}");
        task.setProgress(0);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        return toDto(taskRepository.save(task));
    }

    public List<TaskDto> list() {
        return taskRepository.findAllByOrderByCreatedAtDesc().stream().map(this::toDto).toList();
    }

    public Task getOrThrow(Long id) {
        return taskRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("任务不存在: " + id));
    }

    public TaskDto get(Long id) {
        return toDto(getOrThrow(id));
    }

    @Transactional
    public TaskDto updateStepData(Long id, Integer currentStep, JsonNode stepData) {
        Task task = getOrThrow(id);
        if (currentStep != null) {
            task.setCurrentStep(currentStep);
        }
        task.setStepData(stepData == null || stepData.isNull() ? "{}" : stepData.toString());
        task.setUpdatedAt(LocalDateTime.now());
        return toDto(taskRepository.save(task));
    }

    @Transactional
    public TaskDto markCancelled(Long id) {
        Task task = getOrThrow(id);
        task.setStatus("CANCELLED");
        task.setUpdatedAt(LocalDateTime.now());
        return toDto(taskRepository.save(task));
    }

    /** 删除任务及关联作业、作业明细、导出结果、暂存数据 */
    @Transactional
    public void deleteCascade(Long id) {
        getOrThrow(id);
        List<Long> jobIds = jobRunRepository.findByTaskIdOrderByIdDesc(id).stream()
                .map(JobRun::getId).toList();
        if (!jobIds.isEmpty()) {
            exportResultRepository.deleteByJobIdIn(jobIds);
            jobItemRepository.deleteByJobIdIn(jobIds);
        }
        jobRunRepository.deleteByTaskId(id);
        stagingRepository.deleteByTaskId(id);
        taskRepository.deleteById(id);
    }

    public TaskDto toDto(Task task) {
        JsonNode stepData;
        try {
            stepData = (task.getStepData() == null || task.getStepData().isBlank())
                    ? om.createObjectNode() : om.readTree(task.getStepData());
        } catch (Exception e) {
            stepData = om.createObjectNode();
        }
        return new TaskDto(task.getId(), task.getTaskNo(), task.getType(), task.getName(),
                task.getStatus(), task.getCurrentStep(), stepData, task.getProgress(),
                task.getMessage(), task.getCreatedAt(), task.getUpdatedAt());
    }

    private String nextTaskNo(String type) {
        String prefix = ("EXPORT".equals(type) ? "EXP" : "IMP") + "-"
                + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        long seq = taskRepository.countByTaskNoStartingWith(prefix) + 1;
        return prefix + String.format("%04d", seq);
    }
}
