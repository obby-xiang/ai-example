package com.example.configmgr.task.service;

import com.example.configmgr.common.ResourceNotFoundException;
import com.example.configmgr.data.repo.ConfigStagingRowRepository;
import com.example.configmgr.file.FileStorageService;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.repo.JobItemRepository;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.job.repo.ValidationIssueRepository;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskItem;
import com.example.configmgr.task.entity.TaskFile;
import com.example.configmgr.task.repo.TaskRepository;
import com.example.configmgr.task.repo.TaskItemRepository;
import com.example.configmgr.task.repo.TaskFileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    @PersistenceContext
    private EntityManager entityManager;

    private final TaskRepository taskRepository;
    private final TaskItemRepository taskItemRepository;
    private final TaskFileRepository taskFileRepository;
    private final JobRepository jobRepository;
    private final JobItemRepository jobItemRepository;
    private final ValidationIssueRepository issueRepository;
    private final ConfigStagingRowRepository stagingRowRepository;
    private final FileStorageService fileStorage;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public List<Task> findAll() {
        return taskRepository.findAllOrdered();
    }

    /**
     * 历史任务检索（任务中心列表）。
     *
     * <p>关键词里的 LIKE 通配符在此转义（Q16②）：调换 {@code \} 在前，避免二次转义；
     * 转义后的 {@code %} / {@code _} 只作字面量匹配，配合查询里的 {@code ESCAPE '\'}。
     */
    public Page<Task> search(Task.TaskType type, Task.TaskStatus status, String keyword, Pageable pageable) {
        return taskRepository.search(type, status, escapeLike(keyword), pageable);
    }

    static String escapeLike(String keyword) {
        if (keyword == null) {
            return null;
        }
        return keyword.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
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
        // EAGER 集合在方法入口 findById 时已快照；一级缓存里还是旧对象，
        // 用 refresh 强制从数据库重读，保证返回的 items 包含刚保存的条目。
        entityManager.refresh(task);
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

    /**
     * 只更新任务状态（不改步骤），供作业完成/失败时流转。
     */
    @Transactional
    public void updateStatus(Long taskId, Task.TaskStatus status) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> ResourceNotFoundException.of("任务", taskId));
        task.setStatus(status);
        taskRepository.save(task);
        publishTaskChanged(task, "任务状态更新: " + status);
    }

    /**
     * ⑥ 任务态机断头路补齐：作业终态联动任务态（ADR-8 修正②/Q16④）。
     *
     * <p>三处调用点：取消联动（{@link com.example.configmgr.job.service.JobService#cancel}）、
     * 作业失败联动（各作业执行器终态）、僵尸恢复联动
     * （{@link com.example.configmgr.job.service.StaleJobRecoveryRunner}）。
     * 作业 PENDING/RUNNING 不改任务态；任务不存在（已删除级联）静默跳过；
     * 目标态与当前态相同时不动库、不发事件（幂等，重复重启/重复终态回写无副作用）。
     */
    @Transactional
    public void applyJobOutcome(Long taskId, Job.JobStatus jobStatus) {
        Task.TaskStatus target = switch (jobStatus) {
            case COMPLETED -> Task.TaskStatus.COMPLETED;
            case FAILED -> Task.TaskStatus.FAILED;
            case CANCELLED -> Task.TaskStatus.CANCELLED;
            default -> null;
        };
        if (target == null) {
            return;
        }
        taskRepository.findById(taskId).ifPresent(task -> {
            if (task.getStatus() == target) {
                return;
            }
            task.setStatus(target);
            taskRepository.save(task);
            publishTaskChanged(task, "任务状态联动作业终态: " + jobStatus);
        });
    }

    /**
     * 更新任务内某配置项的状态（CHECKED/IMPORTED/PUBLISHED/COMPLETED/FAILED 等）。
     */
    @Transactional
    public void updateItemStatus(Long taskId, String defCode, String status) {
        taskItemRepository.findByTaskIdAndDefCode(taskId, defCode).ifPresent(item -> {
            item.setStatus(status);
            taskItemRepository.save(item);
        });
    }

    /**
     * ⑦ 任务删除级联清理（FR-4.4，S4.3a 遗留裁决 ⑥）。
     *
     * <p>级联范围 = 作业（jobs）+ 作业条目（job_items）+ 校验问题（validation_issues）
     * + 暂存行（config_staging_rows，基座缺陷：残留）+ 任务条目/文件（task_files：上传件与导出结果）
     * + 任务本体。物理文件（上传件/导出结果）在事务提交后再删盘，
     * 避免事务回滚后库里还有行、盘上文件已丢。
     *
     * <p>删除动作的操作流水（无主体操作流水，ADR-11b）见本类 {@link #publishTaskChanged}
     * ——删除前发一条 TASK_CHANGED（含动作摘要），流水记录不含主体维度。
     */
    @Transactional
    public void delete(Long taskId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> ResourceNotFoundException.of("任务", taskId));

        List<TaskFile> files = taskFileRepository.findByTaskId(taskId);
        List<Long> jobIds = jobRepository.findByTaskId(taskId).stream().map(Job::getId).toList();
        long stagingRows = stagingRowRepository.findByTaskId(taskId).size();

        publishTaskChanged(task, "删除任务（级联清理作业/暂存/导出结果）");
        // FR-4.4「删除动作入操作流水」本期落地形态：结构化流水行（动作/对象/前值摘要/来源/时间，
        // 无主体维度，ADR-11b）+ TASK_CHANGED 事件；落库的 AuditLog 表属 DC-10 的 P2，见证据文档【待裁决】。
        log.info("操作流水: 动作=DELETE_TASK 对象=task#{} 前值摘要=type={},status={},jobs={},stagingRows={},files={} 来源=界面/API 时间={}",
                taskId, task.getType(), task.getStatus(), jobIds.size(), stagingRows, files.size(), LocalDateTime.now());

        if (!jobIds.isEmpty()) {
            issueRepository.deleteByJobIdIn(jobIds);
            jobItemRepository.deleteByJobIdIn(jobIds);
        }
        jobRepository.deleteByTaskId(taskId);
        stagingRowRepository.deleteByTaskId(taskId);
        taskItemRepository.deleteByTaskId(taskId);
        taskFileRepository.deleteByTaskId(taskId);
        taskRepository.deleteById(taskId);

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    files.forEach(f -> fileStorage.delete(f.getStoragePath()));
                }
            });
        } else {
            files.forEach(f -> fileStorage.delete(f.getStoragePath()));
        }
    }

    public List<TaskFile> getFiles(Long taskId) {
        return taskFileRepository.findByTaskId(taskId);
    }

    public void publishTaskChanged(Task task, String summary) {
        eventPublisher.publishEvent(new TaskChangedEvent(this, task, summary));
    }
}
