package com.example.configmgr.task.service;

import com.example.configmgr.common.CodedIllegalArgumentException;
import com.example.configmgr.common.CodedIllegalStateException;
import com.example.configmgr.common.ConflictException;
import com.example.configmgr.common.ErrorCodes;
import com.example.configmgr.common.ResourceNotFoundException;
import com.example.configmgr.data.repo.ConfigStagingRowRepository;
import com.example.configmgr.file.FileStorageService;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.repo.JobItemRepository;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.job.repo.ValidationIssueRepository;
import com.example.configmgr.job.service.JobHeartbeatRegistry;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

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
    private final JobHeartbeatRegistry heartbeatRegistry;
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
     * R1-13：事务内锁任务行（{@code SELECT ... FOR UPDATE}）。
     *
     * <p>删除与建作业共用此锁，调用方必须已处于事务中。锁只覆盖任务行，作业状态写入走各自的
     * 短事务/条件 UPDATE，不反向往等待任务行，故不形成 job → task 的反向锁环。
     *
     * @throws ResourceNotFoundException 任务不存在（删除后又被建作业并发读到的情形）
     */
    public Task lockForUpdate(Long id) {
        return taskRepository.findByIdForUpdate(id)
                .orElseThrow(() -> ResourceNotFoundException.of("任务", id));
    }

    /**
     * 启动作业恢复等“任务可能已被删除”的路径：锁不到任务行时返回 empty，不抛 404。
     */
    public Optional<Task> tryLockForUpdate(Long id) {
        return taskRepository.findByIdForUpdate(id);
    }

    /**
     * R-137：仅供状态判定的裸取（不水合 items）—— 守卫只读 status/type，
     * 不该为一次布尔判定拉整份任务条目集合。
     */
    public Optional<Task> findByIdRaw(Long id) {
        return taskRepository.findById(id);
    }

    @Transactional
    public Task create(Task.TaskType type, String title) {
        Task task = new Task();
        task.setType(type);
        task.setTitle(title);
        task.setCurrentStep(TaskSteps.first(type));
        task = taskRepository.save(task);
        return task;
    }

    /**
     * AR-03 宿主幂等 + 单轮上限 1：AI 创建任务<b>只走这一条</b>。
     *
     * <p>幂等键 = 规范化标题（name）+ 本轮 runId（parent/父作用域）+ 稳定参数（type）的 sha256；
     * <b>不以标题为唯一依据</b>。同一轮同一键 ⇒ 复用已有任务（{@code reused=true}，绝不二次创建）；
     * 同一轮不同键 ⇒ 明确冲突（{@code CREATE_TASK_CONFLICT}，本轮只允许创建一次）。
     *
     * <p>幂等标记落既有的 {@code settings_json}（{@code {"aiCreateKey":…,"aiCreateRun":…}}）：
     * 不新增表/列，跨进程重启仍可判定；其它设置（如 {@code importMode}）原样保留。
     * runId 缺失（旧调用/直调测试）时退化为"键级幂等"，行为见 {@link #findByCreateKey}。
     */
    @Transactional
    public CreateOutcome createIdempotent(Task.TaskType type, String title, String runId) {
        String normalizedTitle = normalizeCreateName(title);
        String runToken = runId == null || runId.isBlank() ? null : runId.trim();
        String key = createIdempotencyKey(type, normalizedTitle, runToken);
        Object lock = createLock(runToken == null ? "key:" + key : "run:" + runToken);
        synchronized (lock) {
            if (runToken != null) {
                Optional<Task> inRun = findByRun(runToken);
                if (inRun.isPresent()) {
                    Task existing = inRun.get();
                    if (key.equals(settingsValue(existing, "aiCreateKey"))) {
                        log.info("create_task 命中同轮幂等键，复用任务 #{}（未二次创建）runId={}", existing.getId(),
                                runToken);
                        return new CreateOutcome(existing, true);
                    }
                    throw new ConflictException(ErrorCodes.CREATE_TASK_CONFLICT,
                            "本轮已创建任务 #" + existing.getId() + "，同一轮只允许创建一次；本次未创建。"
                                    + "请用 navigate_to 打开已有任务，或在新一轮再创建。");
                }
            }
            else {
                Optional<Task> sameKey = findByCreateKey(key);
                if (sameKey.isPresent()) {
                    Task existing = sameKey.get();
                    log.info("create_task 命中幂等键，复用任务 #{}（未二次创建）", existing.getId());
                    return new CreateOutcome(existing, true);
                }
            }

            Task task = new Task();
            task.setType(type);
            task.setTitle(normalizedTitle);
            task.setCurrentStep(TaskSteps.first(type));
            Map<String, Object> settings = new LinkedHashMap<>();
            settings.put("aiCreateKey", key);
            if (runToken != null) {
                settings.put("aiCreateRun", runToken);
            }
            try {
                task.setSettingsJson(objectMapper.writeValueAsString(settings));
            }
            catch (Exception ex) {
                throw new CodedIllegalStateException(ErrorCodes.TASK_SETTINGS_UNREADABLE,
                        "任务设置序列化失败: " + ex.getMessage(), ex);
            }
            Task saved = taskRepository.save(task);
            log.info("create_task 落库任务 #{}（key={}，runId={}）", saved.getId(), key, runToken);
            return new CreateOutcome(saved, false);
        }
    }

    /** 幂等创建结果（{@code reused=true} = 命中已有键，未二次创建）。 */
    public record CreateOutcome(Task task, boolean reused) {
    }

    /** 分片锁：按 run/key 串行化同进程内的并发创建（跨实例由 run 租约与"单轮 1"共同约束）。 */
    private static final Object[] CREATE_LOCKS = createLocks();

    private static Object[] createLocks() {
        Object[] locks = new Object[64];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
        return locks;
    }

    private static Object createLock(String scope) {
        return CREATE_LOCKS[Math.floorMod(scope.hashCode(), CREATE_LOCKS.length)];
    }

    private static String normalizeCreateName(String title) {
        String normalized = title == null ? "" : title.trim().replaceAll("\\s+", " ");
        return normalized.isEmpty() ? "未命名任务" : normalized;
    }

    /** 幂等键：name（规范化标题）+ parent（runId）+ 稳定参数（type）的 sha256。 */
    static String createIdempotencyKey(Task.TaskType type, String normalizedTitle, String runToken) {
        String material = "create_task|name=" + normalizedTitle + "|parent=" + (runToken == null ? "-" : runToken)
                + "|params=" + type.name();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest) {
                hex.append(String.format(Locale.ROOT, "%02x", value));
            }
            return hex.toString();
        }
        catch (NoSuchAlgorithmException ex) {
            throw new CodedIllegalStateException(ErrorCodes.SHA256_UNAVAILABLE, "SHA-256 不可用，无法生成创建幂等键", ex);
        }
    }

    private Optional<Task> findByRun(String runToken) {
        String needle = "\"aiCreateRun\":\"" + runToken + "\"";
        try {
            for (Task task : taskRepository.findBySettingsJsonContaining(needle)) {
                if (runToken.equals(settingsValue(task, "aiCreateRun"))) {
                    return Optional.of(task);
                }
            }
        }
        catch (RuntimeException ex) {
            // fail-closed：查不了幂等面时宁可拒绝二次创建，也不能重复落库。
            log.warn("读取同轮创建幂等键失败 runId={}：{}", runToken, ex.getMessage());
            throw new ConflictException(ErrorCodes.CREATE_TASK_CONFLICT,
                    "无法确认本轮是否已创建任务（幂等查询失败），为避免重复创建已拒绝本次创建，请稍后重试");
        }
        return Optional.empty();
    }

    private Optional<Task> findByCreateKey(String key) {
        String needle = "\"aiCreateKey\":\"" + key + "\"";
        for (Task task : taskRepository.findBySettingsJsonContaining(needle)) {
            if (key.equals(settingsValue(task, "aiCreateKey"))) {
                return Optional.of(task);
            }
        }
        return Optional.empty();
    }

    /** 从 {@code settings_json} 取一个字符串键（缺失/坏 JSON 返回 null）。 */
    private String settingsValue(Task task, String key) {
        if (task == null || task.getSettingsJson() == null || task.getSettingsJson().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(task.getSettingsJson()).path(key).asText(null);
        }
        catch (Exception ex) {
            return null;
        }
    }

    @Transactional
    public Task selectDefs(Long taskId, List<String> defCodes) {
        // R1-13 / P1-5：入口先取 task 行锁，与 delete 统一 task → child 的加锁顺序。
        // 若先读后写，delete 持 task 锁等 task_items、本方法持 task_items 行锁等 task 行，
        // H2 会判死锁并回滚一方（500）。lockForUpdate 返回的就是锁后读到的任务行。
        Task task = lockForUpdate(taskId);

        // 槽位/选择批：终态守卫（与 goToStep 同口径，2026-10-28）—— CANCELLED 恒拒；
        // COMPLETED 按类型核最终作业（EXPORT→EXPORT 成功、IMPORT→PUBLISH 成功）。
        // 选择变更会回落权威步骤，终态任务上做这件事等于把已结束的任务重新拉回向导。
        // 只拦"选择变更"，不改在途作业语义（FAILED/ACTIVE 照旧）。
        assertTaskNotFinalized(task);

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

        // 勾选后的权威步骤回落**该类型的首步**（见 {@link TaskSteps#first}）：EXPORT=SELECT_DEFS，
        // IMPORT=UPLOAD。导入序列里没有 SELECT_DEFS；写成它会让 AI 上下文按
        // task:IMPORT/SELECT_DEFS 刷新，该步不披露 select_definitions 与表格工具。
        task.setCurrentStep(TaskSteps.first(task.getType()));
        // 必须先 flush 再 refresh：refresh 会以库里的状态覆盖内存，未 flush 的改动会被丢弃
        // （旧实现即因此从未真正写回步骤）；flush 后 refresh 仍按原意重读 EAGER 的 items 快照。
        task = taskRepository.saveAndFlush(task);
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
     * R-137（T4）步骤白名单：<b>事实源已上收到 {@link TaskSteps}</b>（步骤名/序列/首步/前后关系
     * 只有那一份；本类不再保留副本）。{@code DONE} 是两类任务通用的完成态。
     * ExportJobRunner 写 {@code EXPORT}、PublishJobRunner 写 {@code PUBLISH}，均在各自序列内。
     */
    private static final java.util.regex.Pattern STEP_FORMAT = java.util.regex.Pattern.compile("^[A-Z][A-Z0-9_]*$");

    @Transactional
    public Task goToStep(Long taskId, String step) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> ResourceNotFoundException.of("任务", taskId));
        // R-137 双守卫：①终态守卫（与 JobService.assertTaskNotFinalized / 前端 isCurrentTaskReadOnly 同口径）
        assertTaskNotFinalized(task);
        // ②step 校验：非空 + 格式 + 按任务类型白名单（非法 → IllegalArgumentException → 400）
        if (step == null || step.isBlank()) {
            throw new CodedIllegalArgumentException(ErrorCodes.STEP_REQUIRED, "目标步骤不得为空");
        }
        String normalized = step.trim();
        if (!STEP_FORMAT.matcher(normalized).matches()) {
            throw new CodedIllegalArgumentException(ErrorCodes.STEP_INVALID, "目标步骤格式不合法：" + step
                    + "（应为全大写字母/数字/下划线，如 " + TaskSteps.SELECT_DEFS + "、" + TaskSteps.QUERY_COND + "）");
        }
        if (!TaskSteps.isKnownFor(task.getType(), normalized)) {
            throw new CodedIllegalArgumentException(ErrorCodes.STEP_NOT_IN_SEQUENCE,
                    "目标步骤不在该任务类型的步骤序列内：" + step
                    + "（" + task.getType() + " 可选：" + TaskSteps.sequence(task.getType()) + "）");
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
            throw new ConflictException(ErrorCodes.TASK_FINALIZED,
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
            throw new ConflictException(ErrorCodes.TASK_FINALIZED,
                    "任务 #" + task.getId() + " 已 COMPLETED（" + finalJobType + " 作业成功），禁止跳转步骤（TASK_FINALIZED）");
        }
    }

    /**
     * 设置导入任务的导入模式：MERGE（增量合并）或 REPLACE（整体替换）。
     */
    @Transactional
    public Task setImportMode(Long taskId, String mode) {
        if (!"MERGE".equals(mode) && !"REPLACE".equals(mode)) {
            throw new CodedIllegalArgumentException(ErrorCodes.IMPORT_MODE_INVALID, "导入模式只支持 MERGE 或 REPLACE");
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
            throw new CodedIllegalStateException(ErrorCodes.TASK_SETTINGS_UNREADABLE, "任务设置序列化失败: " + e.getMessage(), e);
        }
        task = taskRepository.save(task);
        return task;
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
     *
     * <p><b>runner 存活守卫（R1-13 / C1）</b>：存在 PENDING/RUNNING 作业时拒绝删除（409
     * {@code JOB_ALREADY_RUNNING}）；作业已 CANCELLED/FAILED/COMPLETED 但 runner 尚未退出时同样拒绝，
     * 文案为“作业正在收尾，请稍后重试”。runner 生命周期由 {@link JobHeartbeatRegistry} 的
     * {@code beginRunner/endRunner} 跟踪：作业创建事务内登记（回滚注销），{@code AsyncJobExecutor}
     * 的 finally（正常/异常/取消）注销。只有注销后才允许级联删除。
     *
     * <p><b>语义变更</b>：CANCELLED <b>不再立即可删</b>——取消只改 job 行，runner 的长事务仍可能在
     * 分片边界之后落 {@code config_staging_rows} / {@code task_files} / {@code validation_issues}。
     * 必须等 runner 自己退出（或进程重启后内存登记簿为空）才放行。
     *
     * <p><b>心跳/看门狗边界</b>：{@link JobHeartbeatRegistry#clear} 会被 {@code JobProgressService.finish}
     * 和 {@code JobWatchdog.judge} 调用；看门狗清心跳时 runner 线程可能仍活着，故删除闸门<b>不以心跳为准</b>，
     * 而以 runner 登记为准，避免把“卡死”误当成“可删”。V6 子表 FK 仍保留为二阶防线（迟到 INSERT 被拒），
     * 但不再承担主保证；物理文件（写盘成功、落行失败）仍可能残留。
     *
     * <p><b>建作业互斥（R1-13 / Important 1）</b>：本方法以任务行锁作为删除与
     * {@link com.example.configmgr.job.service.JobService#createAndStart} 的共同互斥点，
     * 锁顺序固定 task → job；先建作业则删除看到 PENDING 返回 409，先删任务则建作业 404。
     */
    @Transactional
    public void delete(Long taskId) {
        // 先锁任务行（与 JobService.createAndStart 同一互斥点）：先建则这里看到 PENDING 返回 409；
        // 先删则建作业拿不到任务行返回 404。锁顺序固定 task → job，不反向等 job 行。
        Task task = lockForUpdate(taskId);

        List<Job> jobs = jobRepository.findByTaskId(taskId);
        Job inFlight = jobs.stream()
                .filter(job -> job.getStatus() == Job.JobStatus.PENDING
                        || job.getStatus() == Job.JobStatus.RUNNING)
                .findFirst()
                .orElse(null);
        if (inFlight != null) {
            throw new ConflictException(ErrorCodes.JOB_ALREADY_RUNNING,
                    "任务 #" + taskId + " 仍有未结束作业：作业 #" + inFlight.getId() + " ["
                            + inFlight.getJobType() + "] 状态 " + inFlight.getStatus()
                            + "（JOB_ALREADY_RUNNING），暂时无法删除；删除任务会级联清理作业/暂存/文件，"
                            + "请等待作业结束或先取消作业后再删除");
        }

        // C1：CANCELLED/FAILED 等终态也只是“状态已收”，runner 可能仍在自己的长事务里写子表。
        // 只要 runner 未退出（含看门狗清掉心跳但线程仍活着），一律拒绝级联；只有 AsyncJobExecutor
        // 的 finally 注销 runner 后才放行。心跳过期/看门狗清心跳不等于 runner 已退出。
        Job finishing;
        try {
            finishing = jobs.stream()
                    .filter(job -> heartbeatRegistry.isRunnerAlive(job.getId()))
                    .findFirst()
                    .orElse(null);
        } catch (JobHeartbeatRegistry.RunnerActiveStateUnavailableException e) {
            // C1 / 多实例：Redis 不可用时不能把“查不到”当成“没有 runner”。fail-closed。
            throw new ConflictException(ErrorCodes.JOB_ALREADY_RUNNING,
                    "任务 #" + taskId + " 的作业收尾状态无法确认（Redis 不可用），"
                            + "为避免产生孤儿数据已拒绝删除，请稍后重试");
        }
        if (finishing != null) {
            throw new ConflictException(ErrorCodes.JOB_ALREADY_RUNNING,
                    "任务 #" + taskId + " 的作业 #" + finishing.getId() + " ["
                            + finishing.getJobType() + "] 状态 " + finishing.getStatus()
                            + " 正在收尾，请稍后重试（作业进程退出后才允许删除）");
        }

        List<TaskFile> files = taskFileRepository.findByTaskId(taskId);
        List<Long> jobIds = jobs.stream().map(Job::getId).toList();
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
                    deletePhysicalFiles(files);
                }
            });
        } else {
            deletePhysicalFiles(files);
        }
    }

    /** Minor 6：单个文件删除失败不阻断其余文件（FileStorageService 自身吞 IOException，这里兜运行时异常）。 */
    private void deletePhysicalFiles(List<TaskFile> files) {
        for (TaskFile file : files) {
            try {
                fileStorage.delete(file.getStoragePath());
            } catch (RuntimeException e) {
                log.warn("删除任务物理文件失败（继续清理其余文件）path={}：{}", file.getStoragePath(), e.getMessage());
            }
        }
    }

    public List<TaskFile> getFiles(Long taskId) {
        return taskFileRepository.findByTaskId(taskId);
    }
}
