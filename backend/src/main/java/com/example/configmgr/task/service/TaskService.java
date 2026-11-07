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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.collections4.IterableUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

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
    private final PlatformTransactionManager transactionManager;

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
        return StringUtils.replaceEach(keyword,
                new String[]{"\\", "%", "_"}, new String[]{"\\\\", "\\%", "\\_"});
    }

    public Task findById(Long id) {
        Task task = taskRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Task", id));
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
                .orElseThrow(() -> ResourceNotFoundException.of("Task", id));
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
     * <p>幂等标记落既有的 {@code settings_json}（{@code aiCreateKey}/{@code aiCreateRun}；
     * 跨 run 继续另记 {@code aiCreateRuns}）：不改变业务规格，跨进程重启仍可判定；
     * 其它设置（如 {@code importMode}）原样保留。首轮创建的同值可索引副本写
     * {@code tasks.ai_create_run} / {@code tasks.ai_create_key}（唯一约束，见 V12）。
     *
     * <h2>锁与事务边界（L-1）</h2>
     * <b>不再</b>用 {@code @Transactional} 包住 {@code synchronized}：那样锁在方法体结束时释放，
     * 事务却要等代理返回后才提交，第二个调用者进监视器后读不到未提交的插入，仍会重复建任务。
     * 现在锁<b>在事务之外</b>持有，事务由 {@link TransactionTemplate} 以
     * {@link TransactionDefinition#PROPAGATION_REQUIRES_NEW}（独立短事务）显式开启并提交 ——
     * 模板返回（已提交、事务资源已清理）之后才离开同步块。独立事务意味着调用方事务回滚
     * <b>不会</b>撤销已创建的任务（幂等记忆与任务行必须同生共死，否则会退化成"可重复创建"）。
     *
     * <p>跨实例不依赖这把 JVM 锁：唯一约束是权威兜底。并发插入被拒时事务已回滚，随后在新事务里
     * 回查赢家，按同一幂等口径返回复用或冲突（因此"重复创建"在任何实例组合下都收敛为至多一个）。
     * 回查不到赢家（冲突来自别处）时<b>原样上抛</b>完整性错误，不包装成"可重试的创建冲突"。
     */
    public CreateOutcome createIdempotent(Task.TaskType type, String title, String runId) {
        return createIdempotent(type, title, runId, null);
    }

    /**
     * 带当前任务槽位的创建：跨 run 继续同一意图时复用槽位任务，不重复创建。
     * 只有任务仍是 ACTIVE、类型与规范化标题一致、且本身带 AI 创建幂等事实时才可复用；
     * 手工任务、不同意图与明确的新建仍走原创建路径。
     *
     * <p>续跑 run 记录在原任务 {@code settings_json.aiCreateRuns}，不改写原始
     * {@code ai_create_run}/{@code ai_create_key}。任务行锁让跨实例的同槽位续跑串行，
     * 因而同一续跑 run 的第二个不同意图仍由单轮上限拒绝。
     */
    public CreateOutcome createIdempotent(Task.TaskType type, String title, String runId, Long continuationTaskId) {
        String normalizedTitle = normalizeCreateName(title);
        String runToken = StringUtils.isBlank(runId) ? null : runId.trim();
        String key = createIdempotencyKey(type, normalizedTitle, runToken);
        Object lock = createLock(runToken == null ? "key:" + key : "run:" + runToken);
        synchronized (lock) {
            // 独立短事务（REQUIRES_NEW）：提交与锁释放都在本方法内完成，不并入调用方事务
            // （并入的话提交会晚于解锁，回到 L-1 的老问题）。
            TransactionTemplate tx = new TransactionTemplate(this.transactionManager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            try {
                return tx.execute(status -> createChecked(type, normalizedTitle, runToken, key, continuationTaskId));
            }
            catch (DataIntegrityViolationException conflict) {
                // 另一个实例（或另一线程）先落库：查重快照看不到未提交插入，由唯一约束拒绝。
                // 冲突事务已回滚，另开事务回查赢家 —— 同键复用、同轮不同键明确冲突。
                return resolveCreateConflict(runToken, key, conflict);
            }
        }
    }

    /** {@link #createIdempotent} 的事务体：查重 → 续跑复用/插入。 */
    private CreateOutcome createChecked(Task.TaskType type, String normalizedTitle, String runToken, String key,
                                        Long continuationTaskId) {
        if (runToken != null) {
            Optional<Task> inRun = this.taskRepository.findFirstByAiCreateRunOrderByIdAsc(runToken);
            if (inRun.isPresent()) {
                return reuseOrConflict(inRun.get(), key, runToken);
            }
            if (continuationTaskId != null && continuationTaskId > 0L) {
                Optional<CreateOutcome> continued = continueCurrentTask(type, normalizedTitle, runToken,
                        continuationTaskId);
                if (continued.isPresent()) {
                    return continued.get();
                }
            }
        }
        else {
            Optional<Task> sameKey = this.taskRepository.findFirstByAiCreateKeyOrderByIdAsc(key);
            if (sameKey.isPresent()) {
                Task existing = sameKey.get();
                log.info("create_task idempotency key hit, task reused taskId={}", existing.getId());
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
                    "Could not serialize task settings", ex);
        }
        task.setAiCreateKey(key);
        task.setAiCreateRun(runToken);
        // flush 而非 save：让唯一约束在本事务内立即裁决（冲突在锁内收敛，不留到提交时才发现）
        Task saved = this.taskRepository.saveAndFlush(task);
        log.info("create_task task persisted taskId={} idemKey={} runId={}", saved.getId(), key, runToken);
        return new CreateOutcome(saved, false);
    }

    private Optional<CreateOutcome> continueCurrentTask(Task.TaskType type, String normalizedTitle, String runToken,
                                                        Long continuationTaskId) {
        Optional<Task> found = this.taskRepository.findByIdForUpdate(continuationTaskId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Task existing = found.get();
        Map<String, Object> settings = readCreateSettings(existing);
        List<String> continuationRuns = continuationRuns(settings, existing.getId());
        boolean eligible = isContinuationEligible(existing, type, normalizedTitle);
        if (continuationRuns.contains(runToken)) {
            if (eligible) {
                return Optional.of(new CreateOutcome(existing, true));
            }
            throw new ConflictException(ErrorCodes.CREATE_TASK_CONFLICT,
                    "Task #" + existing.getId() + " was already continued in this turn; no task was created. "
                            + "Use navigate_to to open the existing task, or start a new turn.");
        }
        if (!eligible) {
            return Optional.empty();
        }
        continuationRuns.add(runToken);
        settings.put("aiCreateRuns", continuationRuns);
        try {
            existing.setSettingsJson(objectMapper.writeValueAsString(settings));
        }
        catch (Exception ex) {
            throw new CodedIllegalStateException(ErrorCodes.TASK_SETTINGS_UNREADABLE,
                    "Could not serialize the task continuation marker", ex);
        }
        Task saved = this.taskRepository.saveAndFlush(existing);
        log.info("create_task continuation slot reused taskId={} runId={}", saved.getId(), runToken);
        return Optional.of(new CreateOutcome(saved, true));
    }

    public static String effectiveCreateTitle(Task.TaskType type, String title) {
        if (StringUtils.isNotBlank(title)) {
            return title.trim();
        }
        if (type == Task.TaskType.EXPORT) {
            return "导出任务";
        }
        if (type == Task.TaskType.IMPORT) {
            return "导入任务";
        }
        return "未命名任务";
    }

    /** 预检与执行共用的续用资格：只有同意图且仍活跃的 AI 创建任务才可续用。 */
    public static boolean isContinuationEligible(Task task, Task.TaskType type, String title) {
        return task != null && task.getStatus() == Task.TaskStatus.ACTIVE
                && StringUtils.isNotBlank(task.getAiCreateKey())
                && isSameCreateIntent(task, type, normalizeCreateName(title));
    }

    private static boolean isSameCreateIntent(Task task, Task.TaskType type, String normalizedTitle) {
        return task.getType() == type && normalizeCreateName(task.getTitle()).equals(normalizedTitle);
    }

    private Map<String, Object> readCreateSettings(Task task) {
        if (StringUtils.isBlank(task.getSettingsJson())) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> settings = objectMapper.readValue(
                    task.getSettingsJson(), new TypeReference<LinkedHashMap<String, Object>>() {
                    });
            if (settings == null) {
                throw new CodedIllegalStateException(ErrorCodes.TASK_SETTINGS_UNREADABLE,
                        "Task settings are unreadable; the creation slot cannot be reused safely");
            }
            return settings;
        }
        catch (CodedIllegalStateException ex) {
            throw ex;
        }
        catch (Exception ex) {
            throw new CodedIllegalStateException(ErrorCodes.TASK_SETTINGS_UNREADABLE,
                    "Task settings are unreadable; the creation slot cannot be reused safely", ex);
        }
    }

    private static List<String> continuationRuns(Map<String, Object> settings, Long taskId) {
        Object raw = settings.get("aiCreateRuns");
        if (raw == null) {
            return new ArrayList<>();
        }
        if (!(raw instanceof List<?> values)) {
            throw new CodedIllegalStateException(ErrorCodes.TASK_SETTINGS_UNREADABLE,
                    "Task #" + taskId + " has a non-array aiCreateRuns value; "
                            + "the creation slot cannot be reused safely");
        }
        List<String> runs = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof String run) || run.isBlank()) {
                throw new CodedIllegalStateException(ErrorCodes.TASK_SETTINGS_UNREADABLE,
                        "Task #" + taskId + " has an invalid aiCreateRuns entry; "
                                + "the creation slot cannot be reused safely");
            }
            if (!runs.contains(run)) {
                runs.add(run);
            }
        }
        return runs;
    }

    /** 同轮已有任务：同键复用，不同键 = 本轮已创建过（单轮上限 1）。 */
    private CreateOutcome reuseOrConflict(Task existing, String key, String runToken) {
        if (key.equals(existing.getAiCreateKey())) {
            log.info("create_task same-run idempotency key hit, task reused taskId={} runId={}",
                    existing.getId(), runToken);
            return new CreateOutcome(existing, true);
        }
        throw new ConflictException(ErrorCodes.CREATE_TASK_CONFLICT,
                "Task #" + existing.getId() + " was already created in this turn; no task was created. "
                        + "Use navigate_to to open the existing task, or start a new turn.");
    }

    /**
     * 唯一约束冲突后的收敛：在<b>新事务</b>里回查赢家（冲突事务已回滚，不能复用其快照）。
     *
     * <p><b>回查不到赢家时不改写原因</b>：那说明冲突并非来自这两条幂等约束（或赢家已不可见），
     * 本轮没有创建任何任务，但把它包装成 {@code CREATE_TASK_CONFLICT}「请稍后重试」会把
     * "莫名的完整性错误"误导成"可重试的业务冲突"。这里保留原始 {@link DataIntegrityViolationException}
     * 上抛（由全局异常处理按 500 + 原文记录），fail-closed 且原因可追。
     */
    private CreateOutcome resolveCreateConflict(String runToken, String key,
                                                DataIntegrityViolationException conflict) {
        log.info("create_task unique constraint hit on concurrent create, re-querying runId={}", runToken);
        TransactionTemplate readTx = new TransactionTemplate(this.transactionManager);
        readTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        readTx.setReadOnly(true);
        CreateOutcome resolved;
        try {
            resolved = readTx.execute(status -> {
                if (runToken != null) {
                    return this.taskRepository.findFirstByAiCreateRunOrderByIdAsc(runToken)
                            .map(existing -> reuseOrConflict(existing, key, runToken))
                            .orElse(null);
                }
                return this.taskRepository.findFirstByAiCreateKeyOrderByIdAsc(key)
                        .map(existing -> new CreateOutcome(existing, true))
                        .orElse(null);
            });
        }
        catch (ConflictException e) {
            // 回查到了赢家且判定"本轮已创建过"：这是权威业务结论，不是回查失败
            throw e;
        }
        catch (RuntimeException requeryFailure) {
            // 回查自身失败：不改写成"可重试冲突"，保留原始完整性错误并附带回查失败
            log.error("create_task re-query failed after unique conflict idemKey={} runId={}",
                    key, runToken, requeryFailure);
            conflict.addSuppressed(requeryFailure);
            throw conflict;
        }
        if (resolved != null) {
            return resolved;
        }
        log.error("create_task no existing task found after unique conflict, rethrowing original error idemKey={} runId={}",
                key, runToken, conflict);
        throw conflict;
    }

    /** 幂等创建结果（{@code reused=true} = 命中已有键，未二次创建）。 */
    public record CreateOutcome(Task task, boolean reused) {
    }

    /**
     * 分片锁：按 run/key 串行化同进程内的并发创建。
     * <b>不是正确性来源</b>：跨实例（以及同进程并发提交）由 {@code tasks.ai_create_run} /
     * {@code tasks.ai_create_key} 的唯一约束兜底，见 {@link #createIdempotent}。
     */
    private static final Object[] CREATE_LOCKS = createLocks();

    private static Object[] createLocks() {
        Object[] locks = new Object[64];
        Arrays.setAll(locks, index -> new Object());
        return locks;
    }

    private static Object createLock(String scope) {
        return CREATE_LOCKS[Math.floorMod(scope.hashCode(), CREATE_LOCKS.length)];
    }

    private static String normalizeCreateName(String title) {
        String normalized = StringUtils.trimToEmpty(title).replaceAll("\\s+", " ");
        return StringUtils.defaultIfEmpty(normalized, "未命名任务");
    }

    /** 幂等键：name（规范化标题）+ parent（runId）+ 稳定参数（type）的 sha256。 */
    static String createIdempotencyKey(Task.TaskType type, String normalizedTitle, String runToken) {
        String material = "create_task|name=" + normalizedTitle + "|parent=" + StringUtils.defaultString(runToken, "-")
                + "|params=" + type.name();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return Hex.encodeHexString(digest);
        }
        catch (NoSuchAlgorithmException ex) {
            throw new CodedIllegalStateException(ErrorCodes.SHA256_UNAVAILABLE,
                    "SHA-256 is unavailable; cannot generate the creation idempotency key", ex);
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
                .orElseThrow(() -> ResourceNotFoundException.of("Task item", defCode));
        item.setConditionJson(conditionJson);
        item.setStatus("READY");
        taskItemRepository.save(item);

        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> ResourceNotFoundException.of("Task", taskId));
    }

    /**
     * R-137（T4）步骤白名单：<b>事实源已上收到 {@link TaskSteps}</b>（步骤名/序列/首步/前后关系
     * 只有那一份；本类不再保留副本）。{@code DONE} 是两类任务通用的完成态。
     * ExportJobRunner 写 {@code EXPORT}、PublishJobRunner 写 {@code PUBLISH}，均在各自序列内。
     */
    private static final Pattern STEP_FORMAT = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    @Transactional
    public Task goToStep(Long taskId, String step) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> ResourceNotFoundException.of("Task", taskId));
        // R-137 双守卫：①终态守卫（与 JobService.assertTaskNotFinalized / 前端 isCurrentTaskReadOnly 同口径）
        assertTaskNotFinalized(task);
        // ②step 校验：非空 + 格式 + 按任务类型白名单（非法 → IllegalArgumentException → 400）
        if (StringUtils.isBlank(step)) {
            throw new CodedIllegalArgumentException(ErrorCodes.STEP_REQUIRED, "Target step must not be empty");
        }
        String normalized = step.trim();
        if (!STEP_FORMAT.matcher(normalized).matches()) {
            throw new CodedIllegalArgumentException(ErrorCodes.STEP_INVALID, "Invalid target step format: " + step
                    + "; use uppercase letters, digits, and underscores (for example, "
                    + TaskSteps.SELECT_DEFS + " or " + TaskSteps.QUERY_COND + ")");
        }
        if (!TaskSteps.isKnownFor(task.getType(), normalized)) {
            throw new CodedIllegalArgumentException(ErrorCodes.STEP_NOT_IN_SEQUENCE,
                    "Target step is not in the sequence for this task type: " + step
                    + " (" + task.getType() + " allows " + TaskSteps.sequence(task.getType()) + ")");
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
                .orElseThrow(() -> ResourceNotFoundException.of("Task", taskId));
        if (task.getStatus() == Task.TaskStatus.CANCELLED) {
            log.info("Task already CANCELLED (absorbing-state monotonic gate), completion step write refused taskId={} step={}",
                    taskId, step);
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
                    "Task #" + task.getId() + " is CANCELLED; changing steps is forbidden (TASK_FINALIZED)");
        }
        if (task.getStatus() != Task.TaskStatus.COMPLETED) {
            return;
        }
        Job.JobType finalJobType = task.getType() == Task.TaskType.IMPORT
                ? Job.JobType.PUBLISH : Job.JobType.EXPORT;
        boolean hasFinalJob = jobRepository
                .findFirstByTaskIdAndJobTypeAndStatusInOrderByIdDesc(task.getId(), finalJobType,
                        Set.of(Job.JobStatus.COMPLETED))
                .isPresent();
        if (hasFinalJob) {
            throw new ConflictException(ErrorCodes.TASK_FINALIZED,
                    "Task #" + task.getId() + " is COMPLETED by a successful " + finalJobType
                            + " job; changing steps is forbidden (TASK_FINALIZED)");
        }
    }

    /**
     * 设置导入任务的导入模式：MERGE（增量合并）或 REPLACE（整体替换）。
     */
    @Transactional
    public Task setImportMode(Long taskId, String mode) {
        if (!"MERGE".equals(mode) && !"REPLACE".equals(mode)) {
            throw new CodedIllegalArgumentException(ErrorCodes.IMPORT_MODE_INVALID,
                    "Import mode must be MERGE or REPLACE");
        }
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> ResourceNotFoundException.of("Task", taskId));

        Map<String, Object> settings = new HashMap<>();
        if (StringUtils.isNotBlank(task.getSettingsJson())) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = objectMapper.readValue(task.getSettingsJson(), Map.class);
                settings.putAll(parsed);
            } catch (Exception e) {
                log.warn("Task settings unparseable, rebuilding taskId={}", taskId, e);
            }
        }
        settings.put("importMode", mode);
        try {
            task.setSettingsJson(objectMapper.writeValueAsString(settings));
        } catch (Exception e) {
            throw new CodedIllegalStateException(ErrorCodes.TASK_SETTINGS_UNREADABLE,
                    "Could not serialize task settings", e);
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
                .orElseThrow(() -> ResourceNotFoundException.of("Task", taskId));
        if (task.getStatus() == Task.TaskStatus.CANCELLED
                && (status == Task.TaskStatus.COMPLETED || status == Task.TaskStatus.FAILED)) {
            log.info("Task already CANCELLED (absorbing-state monotonic gate), status write refused taskId={} status={}",
                    taskId, status);
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
                log.info("Task already CANCELLED (absorbing-state monotonic gate), outcome sync refused taskId={} target={}",
                        taskId, target);
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
        Job inFlight = IterableUtils.find(jobs, job -> job.getStatus() == Job.JobStatus.PENDING
                || job.getStatus() == Job.JobStatus.RUNNING);
        if (inFlight != null) {
            throw new ConflictException(ErrorCodes.JOB_ALREADY_RUNNING,
                    "Task #" + taskId + " still has unfinished job #" + inFlight.getId() + " ["
                            + inFlight.getJobType() + "] with status " + inFlight.getStatus()
                            + " (JOB_ALREADY_RUNNING); deletion would cascade through jobs, staging rows, and files. "
                            + "Wait for the job to finish or cancel it before deleting the task");
        }

        // C1：CANCELLED/FAILED 等终态也只是“状态已收”，runner 可能仍在自己的长事务里写子表。
        // 只要 runner 未退出（含看门狗清掉心跳但线程仍活着），一律拒绝级联；只有 AsyncJobExecutor
        // 的 finally 注销 runner 后才放行。心跳过期/看门狗清心跳不等于 runner 已退出。
        Job finishing;
        try {
            finishing = IterableUtils.find(jobs,
                    job -> heartbeatRegistry.isRunnerAlive(job.getId()));
        } catch (JobHeartbeatRegistry.RunnerActiveStateUnavailableException e) {
            // C1 / 多实例：Redis 不可用时不能把“查不到”当成“没有 runner”。fail-closed。
            throw new ConflictException(ErrorCodes.JOB_ALREADY_RUNNING,
                    "Could not confirm whether jobs for task #" + taskId + " have finished because Redis is unavailable; "
                            + "deletion was refused to avoid orphan data, please retry later");
        }
        if (finishing != null) {
            throw new ConflictException(ErrorCodes.JOB_ALREADY_RUNNING,
                    "Job #" + finishing.getId() + " [" + finishing.getJobType() + "] for task #" + taskId
                            + " is still finishing with status " + finishing.getStatus()
                            + "; retry after the job runner exits");
        }

        List<TaskFile> files = taskFileRepository.findByTaskId(taskId);
        List<Long> jobIds = jobs.stream().map(Job::getId).toList();
        long stagingRows = stagingRowRepository.findByTaskId(taskId).size();

        // FR-4.4「删除动作入操作流水」本期落地形态：结构化流水行（动作/对象/前值摘要/来源/时间，
        // 无主体维度，ADR-11b）；落库的 AuditLog 表属 DC-10 的 P2，见证据文档【待裁决】。
        log.info("audit action=DELETE_TASK object=task#{} before=type={} status={} jobs={} stagingRows={} files={} source=ui/api at={}",
                taskId, task.getType(), task.getStatus(), jobIds.size(), stagingRows, files.size(), LocalDateTime.now());

        deleteTaskRecords(taskId, jobIds);

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

    private void deleteTaskRecords(Long taskId, List<Long> jobIds) {
        if (!jobIds.isEmpty()) {
            issueRepository.deleteByJobIdIn(jobIds);
            jobItemRepository.deleteByJobIdIn(jobIds);
        }
        jobRepository.deleteByTaskId(taskId);
        stagingRowRepository.deleteByTaskId(taskId);
        taskItemRepository.deleteByTaskId(taskId);
        taskFileRepository.deleteByTaskId(taskId);
        taskRepository.deleteById(taskId);
    }

    /** Minor 6：单个文件删除失败不阻断其余文件（FileStorageService 自身吞 IOException，这里兜运行时异常）。 */
    private void deletePhysicalFiles(List<TaskFile> files) {
        for (TaskFile file : files) {
            try {
                fileStorage.delete(file.getStoragePath());
            } catch (RuntimeException e) {
                log.warn("Failed to delete task physical file, continuing with remaining files path={}",
                        file.getStoragePath(), e);
            }
        }
    }

    public List<TaskFile> getFiles(Long taskId) {
        return taskFileRepository.findByTaskId(taskId);
    }
}
