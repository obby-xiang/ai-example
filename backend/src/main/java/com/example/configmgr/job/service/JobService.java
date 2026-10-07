package com.example.configmgr.job.service;

import com.example.configmgr.common.ConflictException;
import com.example.configmgr.common.ResourceNotFoundException;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.entity.ValidationIssue;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.job.repo.ValidationIssueRepository;
import com.example.configmgr.task.service.TaskService;
import com.example.configmgr.task.service.TaskSseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class JobService {

    /** "在途"作业状态（互斥守卫的判定范围）：到终态（COMPLETED/FAILED/CANCELLED）即不再互斥。 */
    private static final List<Job.JobStatus> IN_FLIGHT =
            List.of(Job.JobStatus.PENDING, Job.JobStatus.RUNNING);

    private final JobRepository jobRepository;
    private final ValidationIssueRepository issueRepository;
    private final TaskSseService taskSseService;
    private final JobCancellationRegistry cancellationRegistry;
    private final AsyncJobExecutor asyncJobExecutor;
    private final TaskService taskService;

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
     * <p><b>已知边界</b>：判据是"最近一次 PRECHECK"，不追踪"预检查之后上传件是否又被改动"——
     * 上传件在预检查通过后被替换时，旧的一次通过仍会放行（见证据文档遗留项）。
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
