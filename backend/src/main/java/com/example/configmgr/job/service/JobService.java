package com.example.configmgr.job.service;

import com.example.configmgr.common.ResourceNotFoundException;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.entity.ValidationIssue;
import com.example.configmgr.job.repo.JobRepository;
import com.example.configmgr.job.repo.ValidationIssueRepository;
import com.example.configmgr.task.service.TaskSseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class JobService {

    private final JobRepository jobRepository;
    private final ValidationIssueRepository issueRepository;
    private final ExportJobRunner exportJobRunner;
    private final PrecheckJobRunner precheckJobRunner;
    private final ImportJobRunner importJobRunner;
    private final PublishJobRunner publishJobRunner;
    private final TaskSseService taskSseService;
    private final JobCancellationRegistry cancellationRegistry;

    @Transactional
    public Job createAndStart(Long taskId, Job.JobType jobType) {
        Job job = new Job();
        job.setTaskId(taskId);
        job.setJobType(jobType);
        job.setStatus(Job.JobStatus.PENDING);
        job = jobRepository.save(job);
        cancellationRegistry.register(job.getId());
        runAsync(job);
        return job;
    }

    @Async
    public void runAsync(Job job) {
        log.info("Starting job {} type={} taskId={}", job.getId(), job.getJobType(), job.getTaskId());
        try {
            switch (job.getJobType()) {
                case EXPORT -> exportJobRunner.run(job);
                case PRECHECK -> precheckJobRunner.run(job);
                case IMPORT -> importJobRunner.run(job);
                case PUBLISH -> publishJobRunner.run(job);
            }
        } catch (Exception e) {
            log.error("Job {} failed unexpectedly: {}", job.getId(), e.getMessage(), e);
        } finally {
            cancellationRegistry.unregister(job.getId());
        }
    }

    public Job findById(Long jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> ResourceNotFoundException.of("作业", jobId));
    }

    public List<Job> findByTaskId(Long taskId) {
        return jobRepository.findByTaskIdOrderByCreatedAtDesc(taskId);
    }

    public Page<ValidationIssue> findIssues(Long jobId, int page, int size) {
        return issueRepository.findByJobId(jobId, PageRequest.of(page, size));
    }

    @Transactional
    public void cancel(Long jobId) {
        Job job = findById(jobId);
        if (job.getStatus() == Job.JobStatus.RUNNING || job.getStatus() == Job.JobStatus.PENDING) {
            cancellationRegistry.cancel(jobId);
            job.setStatus(Job.JobStatus.CANCELLED);
            jobRepository.save(job);
            taskSseService.publish(job.getTaskId(), "JOB_DONE", Map.of(
                    "jobId", jobId, "status", "CANCELLED"));
        }
    }
}
