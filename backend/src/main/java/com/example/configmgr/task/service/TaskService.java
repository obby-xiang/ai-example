package com.example.configmgr.task.service;

import com.example.configmgr.common.ConflictException;
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

    /**
     * R-137：仅供状态判定的裸取（不水合 items）—— 守卫只读 status/type，
     * 不该为一次布尔判定拉整份任务条目集合。
     */
    public java.util.Optional<Task> findByIdRaw(Long id) {
        return taskRepository.findById(id);
    }

    @Transactional
    public Task create(Task.TaskType type, String title) {
        Task task = new Task();
        task.setType(type);
        task.setTitle(title);
        task.setCurrentStep(type == Task.TaskType.EXPORT ? "SELECT_DEFS" : "UPLOAD");
        task = taskRepository.save(task);
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
    }

    /**
     * R-137（T4）步骤白名单（单处集中定义）：与前端 {@code types/task.ts#TaskSteps} 同构
     * （注释明示"与后端 @ToolScope 的 task:&lt;type&gt;/&lt;step&gt; 标签一致"），完成态 DONE 通用。
     * ExportJobRunner 写 "EXPORT"、PublishJobRunner 写 "PUBLISH"，均在各自序列内。
     */
    private static final Map<Task.TaskType, List<String>> STEP_WHITELIST = Map.of(
            Task.TaskType.EXPORT, List.of("SELECT_DEFS", "QUERY_COND", "EXPORT"),
            Task.TaskType.IMPORT, List.of("UPLOAD", "PRECHECK", "IMPORT", "PUBLISH"));
    private static final String STEP_DONE = "DONE";
    private static final java.util.regex.Pattern STEP_FORMAT = java.util.regex.Pattern.compile("^[A-Z][A-Z0-9_]*$");

    @Transactional
    public Task goToStep(Long taskId, String step) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> ResourceNotFoundException.of("任务", taskId));
        // R-137 双守卫：①终态守卫（与 JobService.assertTaskNotFinalized / 前端 isCurrentTaskReadOnly 同口径）
        assertTaskNotFinalized(task);
        // ②step 校验：非空 + 格式 + 按任务类型白名单（非法 → IllegalArgumentException → 400）
        if (step == null || step.isBlank()) {
            throw new IllegalArgumentException("目标步骤不得为空");
        }
        String normalized = step.trim();
        if (!STEP_FORMAT.matcher(normalized).matches()) {
            throw new IllegalArgumentException("目标步骤格式不合法：" + step
                    + "（应为全大写字母/数字/下划线，如 SELECT_DEFS、QUERY_COND）");
        }
        if (!STEP_DONE.equals(normalized)
                && !STEP_WHITELIST.getOrDefault(task.getType(), List.of()).contains(normalized)) {
            throw new IllegalArgumentException("目标步骤不在该任务类型的步骤序列内：" + step
                    + "（" + task.getType() + " 可选：" + STEP_WHITELIST.get(task.getType()) + "）");
        }
        task.setCurrentStep(normalized);
        task = taskRepository.save(task);
        return task;
    }

    /**
     * R-153 B1：作业完成链的步骤收口（仅供 {@code JobTerminalWriter} 落写成功分支调用）。
     *
     * <p>与 {@link #goToStep} 的差别 = <b>不走终态守卫</b>：完成链里本作业刚刚落成 COMPLETED，
     * 「任务 COMPLETED + 最终作业成功」的守卫形态在此恒成立（先 finish 后联动，作业终态已先行
     * 可见——IMPORT→PUBLISH 链上任务在 IMPORT 完成时即已 COMPLETED），外部跳步守卫会把主路径
     * 误杀；完成步骤是本作业自身的收口写，属守卫的非目标面。CANCELLED 吸收态仍拒（B2 双入口之外的
     * 第三写面同口径）；step 为内部常量（EXPORT/PUBLISH），不经外部输入。
     */
    @Transactional
    public void writeCompletionStep(Long taskId, String step) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> ResourceNotFoundException.of("任务", taskId));
        if (task.getStatus() == Task.TaskStatus.CANCELLED) {
            log.info("任务 #{} 已 CANCELLED（吸收态单调闸），拒绝完成步骤 {} 写入", taskId, step);
            return;
        }
        task.setCurrentStep(step);
        taskRepository.save(task);
    }

    /**
     * R-137 终态守卫（goToStep 版，口径与 {@code JobService#createAndStart} 的
     * {@code assertTaskNotFinalized} 同构）：CANCELLED 恒拒；COMPLETED 按类型核最终作业
     * （IMPORT→PUBLISH 成功、EXPORT→EXPORT 成功）；FAILED 放行。预检查 0 错误即置
     * COMPLETED 的导入任务跳步是主路径，不得误杀。
     */
    private void assertTaskNotFinalized(Task task) {
        if (task.getStatus() == Task.TaskStatus.CANCELLED) {
            throw new ConflictException("TASK_FINALIZED",
                    "任务 #" + task.getId() + " 已 CANCELLED，禁止跳转步骤（TASK_FINALIZED）");
        }
        if (task.getStatus() != Task.TaskStatus.COMPLETED) {
            return;
        }
        Job.JobType finalJobType = task.getType() == Task.TaskType.IMPORT
                ? Job.JobType.PUBLISH : Job.JobType.EXPORT;
        boolean hasFinalJob = jobRepository
                .findFirstByTaskIdAndJobTypeAndStatusInOrderByIdDesc(task.getId(), finalJobType,
                        java.util.Set.of(Job.JobStatus.COMPLETED))
                .isPresent();
        if (hasFinalJob) {
            throw new ConflictException("TASK_FINALIZED",
                    "任务 #" + task.getId() + " 已 COMPLETED（" + finalJobType + " 作业成功），禁止跳转步骤（TASK_FINALIZED）");
        }
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
        return task;
    }

    @Transactional
    public void complete(Long taskId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> ResourceNotFoundException.of("任务", taskId));
        task.setStatus(Task.TaskStatus.COMPLETED);
        task.setCurrentStep("DONE");
        taskRepository.save(task);
    }

    /**
     * 只更新任务状态（不改步骤），供作业完成/失败时流转。
     *
     * <p>R-153 B2：CANCELLED 为<b>吸收态</b>——COMPLETED/FAILED 不得覆写（含执行器迟到写、
     * 僵尸恢复联动等任何路径），拒绝写入并留日志；FAILED↔COMPLETED 既有流转保留
     * （{@code JobService.assertTaskNotFinalized} 的「COMPLETED 但无成功终作业」放行分支依赖它）。
     */
    @Transactional
    public void updateStatus(Long taskId, Task.TaskStatus status) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> ResourceNotFoundException.of("任务", taskId));
        if (task.getStatus() == Task.TaskStatus.CANCELLED
                && (status == Task.TaskStatus.COMPLETED || status == Task.TaskStatus.FAILED)) {
            log.info("任务 #{} 已 CANCELLED（吸收态单调闸），拒绝 {} 覆写", taskId, status);
            return;
        }
        task.setStatus(status);
        taskRepository.save(task);
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
            // R-153 B2：CANCELLED 吸收态单调闸（双入口之二）——COMPLETED/FAILED 不得覆写已取消任务
            //（覆盖 StaleJobRecoveryRunner:68 等残余无守卫联动写点）；CANCELLED→CANCELLED 已被上方幂等短路放行。
            if (task.getStatus() == Task.TaskStatus.CANCELLED
                    && (target == Task.TaskStatus.COMPLETED || target == Task.TaskStatus.FAILED)) {
                log.info("任务 #{} 已 CANCELLED（吸收态单调闸），拒绝联动目标 {} 覆写", taskId, target);
                return;
            }
            task.setStatus(target);
            taskRepository.save(task);
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
     * <p>删除动作的操作流水（无主体操作流水，ADR-11b）：删除前落一条结构化流水行
     * （动作/对象/前值摘要/来源/时间），流水记录不含主体维度；落库的 AuditLog 表属 DC-10 的 P2。
     */
    @Transactional
    public void delete(Long taskId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> ResourceNotFoundException.of("任务", taskId));

        List<TaskFile> files = taskFileRepository.findByTaskId(taskId);
        List<Long> jobIds = jobRepository.findByTaskId(taskId).stream().map(Job::getId).toList();
        long stagingRows = stagingRowRepository.findByTaskId(taskId).size();

        // FR-4.4「删除动作入操作流水」本期落地形态：结构化流水行（动作/对象/前值摘要/来源/时间，
        // 无主体维度，ADR-11b）；落库的 AuditLog 表属 DC-10 的 P2，见证据文档【待裁决】。
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
}
