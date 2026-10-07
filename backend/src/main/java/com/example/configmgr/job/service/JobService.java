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

    private final JobRepository jobRepository;
    private final ValidationIssueRepository issueRepository;
    private final TaskSseService taskSseService;
    private final JobCancellationRegistry cancellationRegistry;
    private final AsyncJobExecutor asyncJobExecutor;
    private final TaskService taskService;

    @Transactional
    public Job createAndStart(Long taskId, Job.JobType jobType) {
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
