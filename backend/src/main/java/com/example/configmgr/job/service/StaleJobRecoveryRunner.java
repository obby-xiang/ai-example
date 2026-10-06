package com.example.configmgr.job.service;

import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.repo.JobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 服务重启后的作业恢复：把停留在 RUNNING/PENDING 的作业标记为 FAILED，
 * 避免出现永远"运行中"的僵尸作业。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StaleJobRecoveryRunner implements ApplicationRunner {

    private final JobRepository jobRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<Job> stale = jobRepository.findAll().stream()
                .filter(j -> j.getStatus() == Job.JobStatus.RUNNING || j.getStatus() == Job.JobStatus.PENDING)
                .toList();
        for (Job job : stale) {
            job.setStatus(Job.JobStatus.FAILED);
            job.setFinishedAt(LocalDateTime.now());
            jobRepository.save(job);
            log.warn("作业 #{} [{}] 在服务重启时中断，已标记为 FAILED", job.getId(), job.getJobType());
        }
    }
}
