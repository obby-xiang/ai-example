package com.example.configmgr.job.service;

import com.example.configmgr.common.ConflictException;
import com.example.configmgr.common.ErrorCodes;
import com.example.configmgr.common.ResourceNotFoundException;
import com.example.configmgr.file.FileStorageService;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.entity.ValidationIssue;
import com.example.configmgr.job.repo.JobItemRepository;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.job.repo.ValidationIssueRepository;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskFile;
import com.example.configmgr.task.entity.TaskItem;
import com.example.configmgr.task.repo.TaskFileRepository;
import com.example.configmgr.task.repo.TaskItemRepository;
import com.example.configmgr.task.service.TaskService;
import com.example.configmgr.task.service.TaskSseService;
import com.example.configmgr.task.service.TaskSteps;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class JobService {

    /**
     * "在途"作业状态：用于同类互斥、取消与启动失败收敛；runner 存活守卫独立扫描全部旧作业。
     */
    private static final List<Job.JobStatus> IN_FLIGHT =
            List.of(Job.JobStatus.PENDING, Job.JobStatus.RUNNING);

    /**
     * 导入侧实际读取的文件类型（T3-4）：{@link ImportJobRunner} <b>只读 UPLOAD、无 EXPORT 回落</b>，
     * 故指纹比对一律对 UPLOAD 项取值（预检查侧可能记的是 EXPORT 回落项 —— 那属于"UPLOAD 指纹缺失"，
     * 按从严口径走 {@code PRECHECK_STALE}，绝不拿 EXPORT 指纹比 UPLOAD）。
     */
    private static final String IMPORT_FILE_TYPE = "UPLOAD";

    /** 新增机器可读码（A5）：预检查文件指纹缺失或与当前 UPLOAD 件不一致 —— 视同"未预检查"。 */
    public static final String PRECHECK_STALE_CODE = ErrorCodes.PRECHECK_STALE;

    /**
     * R-137（T1/T2）任务终态守卫：任务已"真正结束"时拒绝新建作业（409 {@code TASK_FINALIZED}）。
     *
     * <p><b>终态判据与真实作业一致</b>：CANCELLED 恒拒；其余任务状态仅作展示，
     * 最终作业按任务类型核对 —— IMPORT 须存在 PUBLISH 成功作业、EXPORT 须存在 EXPORT 成功作业。
     * 预检查成功不构成最终作业，因此 start_import / start_precheck 仍可继续；
     * 任务联动写失败导致 ACTIVE/FAILED 残留时，已有成功最终作业同样拒绝重复创建。
     *
     * <p>与 {@link #assertNoJobInFlight} 同在落库之前抛出（被拒请求不留 PENDING 行）。
     * 观察项 O-a：守卫检查与落库之间的 TOCTOU 窗口与既有两守卫同风险基线，登记不改。
     */
    private static final Set<Job.JobStatus> FINAL_JOB_STATUSES = Set.of(Job.JobStatus.COMPLETED);

    private void assertTaskNotFinalized(Long taskId, Job.JobType jobType) {
        ConflictException conflict = finalizedConflict(taskId, jobType);
        if (conflict != null) {
            throw conflict;
        }
    }

    /**
     * 任务终态判据的<b>只读口径</b>（执行路径与 AI 预检共用同一份判据，见 {@code gate/ToolPreflightService}）：
     *
     * @return 冲突（非 null 即"已终态、禁止新建作业"）；{@code null} 表示通过
     * @throws ResourceNotFoundException 任务不存在（与执行路径同一口径，不吞成"通过"）
     */
    public ConflictException finalizedConflict(Long taskId, Job.JobType jobType) {
        Task task = task(taskId);
        if (task.getStatus() == Task.TaskStatus.CANCELLED) {
            return new ConflictException(ErrorCodes.TASK_FINALIZED,
                    "任务 #" + taskId + " 已 CANCELLED，禁止新建作业（TASK_FINALIZED）。"
                            + "任务取消是用户明确意志，如需重新开始请创建新任务");
        }
        // 以真实成功作业为准，任务状态只作展示：联动写失败时即使仍为 ACTIVE/FAILED 也不得重复创建。
        // IMPORT 的最终作业是 PUBLISH；预检查成功本身不构成终态，仍可继续导入/发布。
        Job.JobType finalJobType = task.getType() == Task.TaskType.IMPORT
                ? Job.JobType.PUBLISH : Job.JobType.EXPORT;
        boolean hasFinalJob = jobRepository
                .findFirstByTaskIdAndJobTypeAndStatusInOrderByIdDesc(taskId, finalJobType, FINAL_JOB_STATUSES)
                .isPresent();
        if (hasFinalJob) {
            return new ConflictException(ErrorCodes.TASK_FINALIZED,
                    "任务 #" + taskId + " 已有成功的 " + finalJobType + " 作业，禁止重复创建（TASK_FINALIZED）");
        }
        return null;
    }

    /** 任务原始记录（AI 预检要读标题/类型/状态做目标摘要；找不到即 empty）。 */
    public Optional<Task> findTask(Long taskId) {
        return taskId == null ? Optional.empty() : taskService.findByIdRaw(taskId);
    }

    private Task task(Long taskId) {
        return taskService.findByIdRaw(taskId)
                .orElseThrow(() -> ResourceNotFoundException.of("任务", taskId));
    }

    private final JobRepository jobRepository;
    private final ValidationIssueRepository issueRepository;
    private final TaskSseService taskSseService;
    private final JobCancellationRegistry cancellationRegistry;
    private final JobHeartbeatRegistry heartbeatRegistry;
    private final JobTerminalWriter terminalWriter;
    private final AsyncJobExecutor asyncJobExecutor;
    private final TaskService taskService;
    private final TaskItemRepository taskItemRepository;
    private final JobItemRepository jobItemRepository;
    private final TaskFileRepository taskFileRepository;
    private final FileStorageService fileStorageService;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;

    /**
     * 建作业并异步执行（REST {@code POST /api/tasks/{id}/jobs} 与 AI 工具的同一入口）。
     *
     * <h2>两道入口守卫（M1 收尾棒，S5.a/S5.b 待裁决 #1/#2 的裁决落地）</h2>
     * <ol>
     * <li><b>同类互斥 → 409 {@code JOB_ALREADY_RUNNING}</b>（{@link #assertNoJobInFlight}）：
     *     同任务 + 同类型已有 PENDING/RUNNING 作业时拒绝新建 —— 否则两个导出并发写同一
     *     {@code task_files(task, def, EXPORT)} 行（后发者覆盖），进度也互不感知；</li>
     * <li><b>预检查阻断 → 409 {@code PRECHECK_NOT_PASSED}</b>（{@link #assertPrecheckPassed}）：
     *     IMPORT/PUBLISH 前必须存在一次<b>通过</b>的 PRECHECK —— 否则未通过校验的数据可直接写暂存并发布。</li>
     * </ol>
     *
     * <p>两者都在<b>作业落库之前</b>抛出，故被拒的请求不会留下 PENDING 作业行（不会反过来把互斥守卫钉死）。
     * 与 DC-11 的 409 串行化风格一致：{@link ConflictException#getCode()} 为机器可读码，由
     * {@code GlobalExceptionHandler} 转成 409 + {@code code}。
     */
    @Transactional
    public Job createAndStart(Long taskId, Job.JobType jobType) {
        // R1-13：与 TaskService.delete 共用任务行锁，锁顺序固定 task → job。
        // 删除先提交时这里拿不到任务行 → 404；建作业先提交时删除看到 PENDING → 409。
        taskService.lockForUpdate(taskId);
        assertTaskNotFinalized(taskId, jobType);
        assertNoJobInFlight(taskId, jobType);
        // P1-3：同任务在途（非终态）作业的 runner 未退出时仍是写者；新建任何类型作业前都查共享 runner 存活。
        assertNoRunnerInFlight(taskId);
        assertPrecheckPassed(taskId, jobType);

        Job job = new Job();
        job.setTaskId(taskId);
        job.setJobType(jobType);
        job.setStatus(Job.JobStatus.PENDING);
        job = jobRepository.save(job);
        if (jobType == Job.JobType.EXPORT) {
            // select-defs 会回落到首步；作业已真实创建后恢复权威 EXPORT 步，失败/取消也不再回首步。
            taskService.goToStep(taskId, TaskSteps.EXPORT);
        }
        cancellationRegistry.register(job.getId());

        // P1-2：runner 登记必须在创建事务内、commit 之前完成，task 行锁覆盖“提交→登记”全窗口。
        // 回滚时由 afterCompletion 注销本地/Redis 登记，避免键泄漏。
        final Job savedJob = job;
        boolean synchronizationActive = TransactionSynchronizationManager.isSynchronizationActive();
        if (synchronizationActive) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != TransactionSynchronization.STATUS_COMMITTED) {
                        heartbeatRegistry.endRunner(savedJob.getId());
                        cancellationRegistry.unregister(savedJob.getId());
                    }
                }

                @Override
                public void afterCommit() {
                    // 提交后才允许启动执行体；beginRunner 已在事务内成功。
                    try {
                        asyncJobExecutor.execute(savedJob);
                    } catch (RuntimeException e) {
                        // P1-4：线程池拒绝等“根本没进入 runner”的失败——清 runner/取消注册，
                        // 作业落 FAILED，不把异常抛回请求线程（避免 500），也不留 PENDING。
                        heartbeatRegistry.endRunner(savedJob.getId());
                        heartbeatRegistry.clear(savedJob.getId());
                        cancellationRegistry.unregister(savedJob.getId());
                        log.error("Failed to start async job execution, converged to FAILED jobId={}", savedJob.getId(), e);
                        markRunnerStartFailed(savedJob);
                    }
                }
            });
        }

        final boolean runnerStarted;
        try {
            runnerStarted = heartbeatRegistry.beginRunner(savedJob.getId());
        } catch (JobHeartbeatRegistry.JobStateUnavailableException ex) {
            heartbeatRegistry.endRunner(savedJob.getId());
            if (!synchronizationActive) {
                cancellationRegistry.unregister(savedJob.getId());
            }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex);
        } catch (JobHeartbeatRegistry.JobNotInFlightException ex) {
            heartbeatRegistry.endRunner(savedJob.getId());
            if (!synchronizationActive) {
                cancellationRegistry.unregister(savedJob.getId());
            }
            throw new ConflictException(ErrorCodes.JOB_ALREADY_FINAL, ex.getMessage());
        }
        if (!runnerStarted) {
            heartbeatRegistry.endRunner(savedJob.getId());
            if (!synchronizationActive) {
                cancellationRegistry.unregister(savedJob.getId());
            }
            throw new ConflictException(ErrorCodes.JOB_ALREADY_RUNNING,
                    "作业 #" + savedJob.getId() + " 的 runner 活跃登记失败（Redis 不可用），"
                            + "作业未创建，请稍后重试");
        }

        if (!synchronizationActive) {
            try {
                asyncJobExecutor.execute(savedJob);
            } catch (RuntimeException e) {
                heartbeatRegistry.endRunner(savedJob.getId());
                heartbeatRegistry.clear(savedJob.getId());
                cancellationRegistry.unregister(savedJob.getId());
                log.error("Failed to start async job execution, converged to FAILED jobId={}", savedJob.getId(), e);
                markRunnerStartFailed(savedJob);
            }
        }
        return job;
    }

    /**
     * 异步启动失败（如线程池拒绝，作业已提交）的终态收敛：沿用 JobTerminalWriter 的
     * REQUIRES_NEW + 条件 finish（取消/其它终态先到者胜），并落一条作业级 issue 让失败可见。
     */
    private void markRunnerStartFailed(Job job) {
        Job current = jobRepository.findById(job.getId()).orElse(null);
        if (current == null || !IN_FLIGHT.contains(current.getStatus())) {
            log.info("Job left in-flight state, runner-start failure convergence skipped jobId={} state={}",
                    job.getId(), current == null ? "DELETED" : current.getStatus());
            return;
        }
        try {
            ValidationIssue issue = new ValidationIssue();
            issue.setJobId(job.getId());
            issue.setDefCode("-");
            issue.setSeverity(ValidationIssue.Severity.ERROR);
            issue.setMessage("作业异步启动失败（如线程池拒绝），作业未执行，已收敛为 FAILED");
            // afterCommit 里不能复用已完成的旧事务；issue 必须显式 REQUIRES_NEW 才真正落库。
            TransactionTemplate issueTx = new TransactionTemplate(transactionManager);
            issueTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            issueTx.executeWithoutResult(status -> issueRepository.save(issue));
        } catch (Exception e) {
            log.error("Runner-start failure issue write failed jobId={} error={}", job.getId(), e.getMessage(), e);
        }
        terminalWriter.completeNow(job, Job.JobStatus.FAILED, 1, 0, 0, 0,
                Map.of("jobId", job.getId(), "jobType", job.getJobType(),
                        "status", Job.JobStatus.FAILED, "errors", 1));
        // afterCommit 已把库里的 job 收敛；把返回值对象同步成最终态，避免 201 响应仍显示 PENDING。
        Job persisted = jobRepository.findById(job.getId()).orElse(null);
        if (persisted != null) {
            job.setStatus(persisted.getStatus());
            job.setFinishedAt(persisted.getFinishedAt());
            job.setErrorCount(persisted.getErrorCount());
        }
    }

    public Job findById(Long jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> ResourceNotFoundException.of("作业", jobId));
    }

    /**
     * 同类互斥守卫（409 {@code JOB_ALREADY_RUNNING}）：同任务 + 同类型已有在途（PENDING/RUNNING）作业 → 拒绝。
     *
     * <p>到终态即可再发起；确实需要打断在途作业时走取消通路
     * （{@link #cancel}，PENDING/RUNNING 均可取消），故不会把任务钉死。
     */
    private void assertNoJobInFlight(Long taskId, Job.JobType jobType) {
        ConflictException conflict = inFlightConflict(taskId, jobType);
        if (conflict != null) {
            throw conflict;
        }
    }

    /**
     * P1-3 / S1：同任务旧 runner 存活守卫（不区分作业类型或作业状态）。
     *
     * <p>看门狗或取消通路可能先把作业写成终态，而旧 runner 尚未退出；只要 runner 仍存活，
     * 就继续沿用 {@code JOB_ALREADY_RUNNING} 收尾冲突，禁止第二个写者。
     *
     * <p>Redis 读失败仍 fail-closed 409，绝不把“查不到”当“没有 runner”；调用点保持在
     * task 行锁内（{@link #createAndStart}）。
     */
    private void assertNoRunnerInFlight(Long taskId) {
        for (Job existing : jobRepository.findByTaskId(taskId)) {
            boolean alive;
            try {
                alive = heartbeatRegistry.isRunnerAlive(existing.getId());
            } catch (JobHeartbeatRegistry.RunnerActiveStateUnavailableException e) {
                throw new ConflictException(ErrorCodes.JOB_ALREADY_RUNNING,
                        "任务 #" + taskId + " 的旧作业收尾状态无法确认（Redis 不可用），"
                                + "为避免并发写者已拒绝新建；请稍后重试");
            }
            if (alive) {
                throw new ConflictException(ErrorCodes.JOB_ALREADY_RUNNING,
                        "任务 #" + taskId + " 的旧作业 #" + existing.getId() + " ["
                                + existing.getJobType() + "] 状态 " + existing.getStatus()
                                + " 正在收尾，请稍后重试（runner 退出后才允许新建作业）");
            }
        }
    }

    /**
     * 同类互斥判据的<b>只读口径</b>（执行路径与 AI 预检共用）。
     *
     * @return 冲突（非 null 即"已有同类作业在途"）；{@code null} 表示通过
     */
    public ConflictException inFlightConflict(Long taskId, Job.JobType jobType) {
        return jobRepository.findFirstByTaskIdAndJobTypeAndStatusInOrderByIdDesc(taskId, jobType, IN_FLIGHT)
                .map(job -> new ConflictException(ErrorCodes.JOB_ALREADY_RUNNING,
                        "任务 #" + taskId + " 已有同类作业在途：作业 #" + job.getId() + " [" + jobType
                                + "] 状态 " + job.getStatus()
                                + "（JOB_ALREADY_RUNNING）。并发执行会互相覆盖同一批文件/暂存行，"
                                + "请等待其结束，或先取消该作业再发起"))
                .orElse(null);
    }

    /**
     * 预检查阻断守卫（409 {@code PRECHECK_NOT_PASSED}）：{@code IMPORT}/{@code PUBLISH} 前必须有一次
     * <b>COMPLETED 且 errorCount==0</b> 的 PRECHECK。
     *
     * <p><b>从严口径</b>（指挥官裁决：先按从严实现，"无 PRECHECK 记录时从严/从宽"见证据文档【待裁决】）：
     * 该任务没有 PRECHECK 记录、最近一次 PRECHECK 未结束（PENDING/RUNNING）、被取消，
     * 或已结束但有错误 → 一律拒绝。即"预检查通过"是导入/发布的前置条件，而不是提示。
     *
     * <p>EXPORT/PRECHECK 不受本守卫约束（导出读已发布数据、预检查本身即该守卫的前置动作）。
     *
     * <p><b>T3-4 起补第二道判据（仅 IMPORT）</b>：状态是"最近一次通过"也不再充分 ——
     * 上传件在预检查通过后被替换时，旧的一次通过<b>不应</b>再放行。故 IMPORT 追加
     * {@link #importFingerprintConflict} 的文件指纹比对（409 {@code PRECHECK_STALE}）；
     * PUBLISH 读 staging 不读文件，维持原状态守卫。
     */
    private void assertPrecheckPassed(Long taskId, Job.JobType jobType) {
        ConflictException conflict = precheckConflict(taskId, jobType);
        if (conflict != null) {
            throw conflict;
        }
    }

    /**
     * 预检查阻断判据的<b>只读口径</b>（执行路径与 AI 预检共用）：
     *
     * @return 冲突（非 null 即"预检查未通过/已失效"）；{@code null} 表示通过
     */
    public ConflictException precheckConflict(Long taskId, Job.JobType jobType) {
        if (jobType != Job.JobType.IMPORT && jobType != Job.JobType.PUBLISH) {
            return null;
        }
        Job precheck = latestPrecheck(taskId).orElse(null);
        if (precheck == null) {
            return new ConflictException(ErrorCodes.PRECHECK_NOT_PASSED,
                    "任务 #" + taskId + " 尚无预检查记录，不能发起 " + jobType
                            + "（PRECHECK_NOT_PASSED）：请先上传数据文件并执行预检查，通过后再继续");
        }
        if (precheck.getStatus() != Job.JobStatus.COMPLETED || precheck.getErrorCount() > 0) {
            return new ConflictException(ErrorCodes.PRECHECK_NOT_PASSED,
                    "任务 #" + taskId + " 最近一次预检查未通过：作业 #" + precheck.getId() + " 状态 "
                            + precheck.getStatus() + "，错误数 " + precheck.getErrorCount()
                            + "（PRECHECK_NOT_PASSED）。请先修复数据并重跑预检查通过后再发起 " + jobType);
        }
        if (jobType == Job.JobType.IMPORT) {
            return importFingerprintConflict(taskId, precheck);
        }
        return null;
    }

    /** 最近一次预检查作业（AI 预检据此给出"预检指纹"的可读依据；找不到即 empty）。 */
    public Optional<Job> latestPrecheck(Long taskId) {
        return jobRepository.findTopByTaskIdAndJobTypeOrderByCreatedAtDescIdDesc(taskId, Job.JobType.PRECHECK);
    }

    /**
     * 导入件指纹比对（T3-4，仅 IMPORT）：最近一次<b>通过</b>的 PRECHECK 记下的
     * {@code {defCode: {fileType: {sha256, size}}}} 摘要里，逐配置项取 {@code UPLOAD} 指纹，
     * 与当前 UPLOAD 件现场流式重算的指纹比对。
     *
     * <p><b>从严口径（A4）</b>：摘要缺失、{@code resultJson} 为 null/非 JSON/结构不符、
     * 目标 fileType 指纹缺失、UPLOAD 件不在或不可读、指纹不一致 —— <b>一律</b>
     * 409 {@code PRECHECK_STALE}（视同"未预检查"）。理由：无指纹的历史 PRECHECK 无法证明
     * "校验的就是当前这份文件"，宽放等于让预检查形同虚设。
     *
     * <p>为什么按 fileType 取值：预检查读盘有 UPLOAD→EXPORT 回落，导入只读 UPLOAD ——
     * 若比对时跨类型取值，会出现"记的是 EXPORT、比的是 UPLOAD"的恒 409。
     *
     * <p><b>遗留（登记为观察项）</b>：guard→落库→afterCommit 之间的 TOCTOU 窗口不闭合
     * （校验与真实导入之间文件仍可被替换）。
     */
    private ConflictException importFingerprintConflict(Long taskId, Job precheck) {
        JsonNode summary = parseFingerprintSummary(precheck.getResultJson());
        for (TaskItem item : taskItemRepository.findByTaskIdOrderBySortOrder(taskId)) {
            String defCode = item.getDefCode();
            JsonNode recorded = summary.path(defCode).path(IMPORT_FILE_TYPE);
            if (!recorded.isObject()) {
                return stale(defCode, "最近一次通过预检查的作业 #" + precheck.getId()
                        + " 没有记下 " + IMPORT_FILE_TYPE + " 件指纹（历史预检查或预检查记的是其它 fileType）");
            }
            TaskFile file = taskFileRepository
                    .findByTaskIdAndDefCodeAndFileType(taskId, defCode, IMPORT_FILE_TYPE)
                    .orElse(null);
            if (file == null) {
                return stale(defCode, "当前任务没有 " + IMPORT_FILE_TYPE + " 件");
            }
            FileStorageService.FileDigest current;
            try {
                current = fileStorageService.digest(file.getStoragePath());
            }
            catch (IOException e) {
                return stale(defCode, IMPORT_FILE_TYPE + " 件不可读：" + e.getMessage());
            }
            String expectedSha = recorded.path("sha256").asText(null);
            long expectedSize = recorded.path("size").asLong(-1L);
            if (expectedSha == null || !expectedSha.equals(current.sha256()) || expectedSize != current.size()) {
                return stale(defCode, IMPORT_FILE_TYPE + " 件已被替换（预检查时 sha256="
                        + (expectedSha == null ? "null" : expectedSha.substring(0, Math.min(12, expectedSha.length())))
                        + " size=" + expectedSize + "；当前 sha256="
                        + current.sha256().substring(0, 12) + " size=" + current.size() + "）");
            }
        }
        return null;
    }

    /** 解析指纹摘要；任何形态异常（缺失/非 JSON/非对象）都回落为空对象 —— 从严分支由此触发。 */
    private JsonNode parseFingerprintSummary(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        }
        try {
            JsonNode node = this.objectMapper.readTree(resultJson);
            return node != null && node.isObject() ? node
                    : com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        }
        catch (Exception e) {
            log.debug("Precheck fingerprint summary unparseable, treated as missing error={}", e.getMessage());
            return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        }
    }

    private ConflictException stale(String defCode, String detail) {
        return new ConflictException(PRECHECK_STALE_CODE,
                "配置项 " + defCode + " 的预检查结果已失效：" + detail
                        + "（" + PRECHECK_STALE_CODE + "）。请重新上传该配置项的数据文件并重跑预检查，通过后再导入");
    }

    public List<Job> findByTaskId(Long taskId) {
        return jobRepository.findByTaskIdOrderByCreatedAtDescIdDesc(taskId);
    }

    public Page<ValidationIssue> findIssues(Long jobId, int page, int size) {
        return issueRepository.findByJobId(jobId, PageRequest.of(page, size));
    }

    /**
     * 取消作业（ADR-8 修正③④ + Q16④）。
     *
     * <ul>
     * <li><b>④ 已终态 → 409 JOB_ALREADY_FINAL</b>：不再静默 200；
     *     终态（COMPLETED/FAILED/CANCELLED）作业的取消请求没有语义，返回冲突码让调用方明确失败；</li>
     * <li><b>③ DB 保存权威取消状态与版本</b>：{@code job:cancel:{jobId}} 只镜像该版本，
     *     执行边界在 Redis 未命中或失败时回查 DB；进程内直通知同样仅是加速。</li>
     * <li><b>⑥ 任务态联动</b>：作业取消 → 任务 CANCELLED。</li>
     * </ul>
     *
     * <p><b>R1-12 / I-1 事务边界</b>：事务里只做状态 CAS（条件 UPDATE，影响行数判胜负）与任务态联动；
     * Redis 写与 SSE 推送放在<b>事务模板返回之后</b>执行 —— 此时提交、事务资源解绑与
     * EntityManager/连接清理都已完成，不再占着 Hikari 连接等网络。仍是调用线程同步执行
     * （不是 fire-and-forget）；旁路失败只记日志/降级，不回滚已提交的取消，
     * 响应字段（{@code redisWritten}/{@code wokeInProcess}）语义不变。
     *
     * <p><b>任务不存在语义（R1-13 Minor）</b>：先读 job 再锁 task；若任务已被并发删除（或历史孤儿 job），
     * 抛 {@link ResourceNotFoundException}（404），不返回 {@code JOB_ALREADY_FINAL}——前者是“对象没了”，
     * 后者是“作业已是终态”，不可混淆。
     *
     * @return 取消落地形态（供端点/验证取证）
     */
    public JobCancellationRegistry.CancelResult cancel(Long jobId, String requestedBy) {
        // 显式 REQUIRES_NEW：模板返回 = 已提交且已完成事务清理；之后才做 Redis/SSE。
        TransactionTemplate tx = new TransactionTemplate(this.transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Long taskId = tx.execute(status -> {
            Job job = findById(jobId);
            if (!IN_FLIGHT.contains(job.getStatus())) {
                throw finalConflict(jobId, job.getStatus());
            }
            // R1-13：任务态写路径统一先锁 task（再写 job），与删除的 task → job 顺序一致。
            // 删除先发生时这里拿不到任务行；随后 updateStatus 影响 0 行或任务态联动静默跳过。
            taskService.lockForUpdate(job.getTaskId());

            // 条件 UPDATE：读-判-写跨进程不成立；影响 0 行 = 另一个写者已先收终态
            int updated = jobRepository.updateStatusIfInFlight(jobId, Job.JobStatus.CANCELLED, IN_FLIGHT);
            if (updated == 0) {
                Job current = findById(jobId);
                throw finalConflict(jobId, current.getStatus());
            }
            taskService.applyJobOutcome(job.getTaskId(), Job.JobStatus.CANCELLED);
            // 取消也要收敛"未收终态"的条目：执行器可能已被 kill（SIGKILL 后不再有任何 finishItem），
            // 只写作业行会让条目永久停在 RUNNING（作业详情/任务中心与终态自相矛盾）。
            // 放在取消事务内（而不是 JobProgressService#finish）：取消语义下"条目记 CANCELLED"由取消方
            // 负责，事务原子提交 ⇒ 不会出现"作业 CANCELLED、条目还在 RUNNING"的中间态；条件 UPDATE
            // 只匹配 PENDING/RUNNING，已 COMPLETED 的条目一律不动（不覆盖成功终态）。
            int convergedItems = this.jobItemRepository.convergeOpenItems(jobId, "CANCELLED");
            if (convergedItems > 0) {
                log.info("Job cancelled, open items converged to CANCELLED jobId={} items={}",
                        jobId, convergedItems);
            }
            return job.getTaskId();
        });
        return publishCancellationSideEffects(jobId, requestedBy, taskId);
    }

    private ConflictException finalConflict(Long jobId, Job.JobStatus status) {
        return new ConflictException(ErrorCodes.JOB_ALREADY_FINAL,
                "作业 #" + jobId + " 已是终态 " + status + "，不可取消");
    }

    /**
     * 取消提交后的同步旁路：先写 Redis 加速镜像与心跳 fence，再推 JOB_DONE。
     *
     * <p>I-1：调用点已保证事务模板返回（提交 + 连接/EntityManager 清理完成），
     * 因此这里不再使用 {@code afterCommit}/{@code isSynchronizationActive} 判断；
     * 两段失败只归因到旁路本身，不回滚取消、不改变端点响应形态。
     */
    private JobCancellationRegistry.CancelResult publishCancellationSideEffects(
            Long jobId, String requestedBy, Long taskId) {
        JobCancellationRegistry.CancelResult result;
        try {
            result = cancellationRegistry.cancel(jobId, requestedBy);
        } catch (Exception e) {
            log.error("Cancel committed but shared cancel flag write failed jobId={} error={}",
                    jobId, e.getMessage(), e);
            result = new JobCancellationRegistry.CancelResult(
                    false, false, jobId, cancellationRegistry.keyFor(jobId));
        }
        if (!result.redisWritten()) {
            log.warn("Cancel acceleration mirror not written; runner boundaries will recheck DB jobId={}", jobId);
        }
        heartbeatRegistry.fenceCancelledRunner(jobId);
        try {
            taskSseService.publish(taskId, "JOB_DONE", Map.of(
                    "jobId", jobId, "status", "CANCELLED"));
        } catch (Exception e) {
            log.error("Cancel committed but JOB_DONE publish failed jobId={} error={}",
                    jobId, e.getMessage(), e);
        }
        return result;
    }
}
