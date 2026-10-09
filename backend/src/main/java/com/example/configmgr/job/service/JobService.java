package com.example.configmgr.job.service;

import com.example.configmgr.common.ConflictException;
import com.example.configmgr.common.ResourceNotFoundException;
import com.example.configmgr.file.FileStorageService;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.entity.ValidationIssue;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.job.repo.ValidationIssueRepository;
import com.example.configmgr.task.entity.TaskFile;
import com.example.configmgr.task.entity.TaskItem;
import com.example.configmgr.task.repo.TaskFileRepository;
import com.example.configmgr.task.repo.TaskItemRepository;
import com.example.configmgr.task.service.TaskService;
import com.example.configmgr.task.service.TaskSseService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class JobService {

    /** "在途"作业状态（互斥守卫的判定范围）：到终态（COMPLETED/FAILED/CANCELLED）即不再互斥。 */
    private static final List<Job.JobStatus> IN_FLIGHT =
            List.of(Job.JobStatus.PENDING, Job.JobStatus.RUNNING);

    /**
     * 导入侧实际读取的文件类型（T3-4）：{@link ImportJobRunner} <b>只读 UPLOAD、无 EXPORT 回落</b>，
     * 故指纹比对一律对 UPLOAD 项取值（预检查侧可能记的是 EXPORT 回落项 —— 那属于"UPLOAD 指纹缺失"，
     * 按从严口径走 {@code PRECHECK_STALE}，绝不拿 EXPORT 指纹比 UPLOAD）。
     */
    private static final String IMPORT_FILE_TYPE = "UPLOAD";

    /** 新增机器可读码（A5）：预检查文件指纹缺失或与当前 UPLOAD 件不一致 —— 视同"未预检查"。 */
    public static final String PRECHECK_STALE_CODE = "PRECHECK_STALE";

    private final JobRepository jobRepository;
    private final ValidationIssueRepository issueRepository;
    private final TaskSseService taskSseService;
    private final JobCancellationRegistry cancellationRegistry;
    private final AsyncJobExecutor asyncJobExecutor;
    private final TaskService taskService;
    private final TaskItemRepository taskItemRepository;
    private final TaskFileRepository taskFileRepository;
    private final FileStorageService fileStorageService;
    private final ObjectMapper objectMapper;

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
        assertNoJobInFlight(taskId, jobType);
        assertPrecheckPassed(taskId, jobType);

        Job job = new Job();
        job.setTaskId(taskId);
        job.setJobType(jobType);
        job.setStatus(Job.JobStatus.PENDING);
        job = jobRepository.save(job);
        cancellationRegistry.register(job.getId());

        // 关键：必须在事务提交之后再启动异步执行。
        // 否则执行线程可能先于本事务提交就开始更新 Job 行，
        // 触发 "Row was updated or deleted by another transaction" 竞态失败。
        final Job savedJob = job;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                asyncJobExecutor.execute(savedJob);
            }
        });
        return job;
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
        jobRepository.findFirstByTaskIdAndJobTypeAndStatusInOrderByIdDesc(taskId, jobType, IN_FLIGHT)
                .ifPresent(job -> {
                    throw new ConflictException("JOB_ALREADY_RUNNING",
                            "任务 #" + taskId + " 已有同类作业在途：作业 #" + job.getId() + " [" + jobType
                                    + "] 状态 " + job.getStatus()
                                    + "（JOB_ALREADY_RUNNING）。并发执行会互相覆盖同一批文件/暂存行，"
                                    + "请等待其结束，或先取消该作业再发起");
                });
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
     * {@link #assertImportFingerprintFresh} 的文件指纹比对（409 {@code PRECHECK_STALE}）；
     * PUBLISH 读 staging 不读文件，维持原状态守卫。
     */
    private void assertPrecheckPassed(Long taskId, Job.JobType jobType) {
        if (jobType != Job.JobType.IMPORT && jobType != Job.JobType.PUBLISH) {
            return;
        }
        Job precheck = jobRepository
                .findTopByTaskIdAndJobTypeOrderByCreatedAtDescIdDesc(taskId, Job.JobType.PRECHECK)
                .orElseThrow(() -> new ConflictException("PRECHECK_NOT_PASSED",
                        "任务 #" + taskId + " 尚无预检查记录，不能发起 " + jobType
                                + "（PRECHECK_NOT_PASSED）：请先上传数据文件并执行预检查，通过后再继续"));
        if (precheck.getStatus() != Job.JobStatus.COMPLETED || precheck.getErrorCount() > 0) {
            throw new ConflictException("PRECHECK_NOT_PASSED",
                    "任务 #" + taskId + " 最近一次预检查未通过：作业 #" + precheck.getId() + " 状态 "
                            + precheck.getStatus() + "，错误数 " + precheck.getErrorCount()
                            + "（PRECHECK_NOT_PASSED）。请先修复数据并重跑预检查通过后再发起 " + jobType);
        }
        if (jobType == Job.JobType.IMPORT) {
            assertImportFingerprintFresh(taskId, precheck);
        }
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
    private void assertImportFingerprintFresh(Long taskId, Job precheck) {
        JsonNode summary = parseFingerprintSummary(precheck.getResultJson());
        for (TaskItem item : taskItemRepository.findByTaskIdOrderBySortOrder(taskId)) {
            String defCode = item.getDefCode();
            JsonNode recorded = summary.path(defCode).path(IMPORT_FILE_TYPE);
            if (!recorded.isObject()) {
                throw stale(defCode, "最近一次通过预检查的作业 #" + precheck.getId()
                        + " 没有记下 " + IMPORT_FILE_TYPE + " 件指纹（历史预检查或预检查记的是其它 fileType）");
            }
            TaskFile file = taskFileRepository
                    .findByTaskIdAndDefCodeAndFileType(taskId, defCode, IMPORT_FILE_TYPE)
                    .orElseThrow(() -> stale(defCode, "当前任务没有 " + IMPORT_FILE_TYPE + " 件"));
            FileStorageService.FileDigest current;
            try {
                current = fileStorageService.digest(file.getStoragePath());
            }
            catch (IOException e) {
                throw stale(defCode, IMPORT_FILE_TYPE + " 件不可读：" + e.getMessage());
            }
            String expectedSha = recorded.path("sha256").asText(null);
            long expectedSize = recorded.path("size").asLong(-1L);
            if (expectedSha == null || !expectedSha.equals(current.sha256()) || expectedSize != current.size()) {
                throw stale(defCode, IMPORT_FILE_TYPE + " 件已被替换（预检查时 sha256="
                        + (expectedSha == null ? "null" : expectedSha.substring(0, Math.min(12, expectedSha.length())))
                        + " size=" + expectedSize + "；当前 sha256="
                        + current.sha256().substring(0, 12) + " size=" + current.size() + "）");
            }
        }
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
            log.debug("解析预检查指纹摘要失败（按缺失处理）：{}", e.getMessage());
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
     * <li><b>③ 取消标志 Redis 共享为准</b>：写 {@code job:cancel:{jobId}}，
     *     持有作业的实例在分片/事件边界轮询（延迟 ≤1 分片）；进程内直通知仅是加速，
     *     写 Redis 失败也不视为错误；</li>
     * <li><b>⑥ 任务态联动</b>：作业取消 → 任务 CANCELLED。</li>
     * </ul>
     *
     * @return 取消落地形态（供端点/验证取证）
     */
    @Transactional
    public JobCancellationRegistry.CancelResult cancel(Long jobId, String requestedBy) {
        Job job = findById(jobId);
        if (job.getStatus() != Job.JobStatus.PENDING && job.getStatus() != Job.JobStatus.RUNNING) {
            throw new ConflictException("JOB_ALREADY_FINAL",
                    "作业 #" + jobId + " 已是终态 " + job.getStatus() + "，不可取消");
        }

        JobCancellationRegistry.CancelResult result = cancellationRegistry.cancel(jobId, requestedBy);
        if (!result.redisWritten()) {
            log.warn("取消作业 #{} 的共享标志未写入 Redis（降级：仅本进程可见）", jobId);
        }

        if (job.getStatus() == Job.JobStatus.RUNNING || job.getStatus() == Job.JobStatus.PENDING) {
            // 立即置终态（取消对快照即刻可见）；执行器随后在分片边界停手并再写一次终态（同值，幂等）
            job.setStatus(Job.JobStatus.CANCELLED);
            jobRepository.save(job);
        }
        taskService.applyJobOutcome(job.getTaskId(), Job.JobStatus.CANCELLED);
        taskSseService.publish(job.getTaskId(), "JOB_DONE", Map.of(
                "jobId", jobId, "status", "CANCELLED"));
        return result;
    }
}
