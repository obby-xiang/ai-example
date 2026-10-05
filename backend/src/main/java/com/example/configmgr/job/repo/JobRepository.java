package com.example.configmgr.job.repo;

import com.example.configmgr.job.entity.Job;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface JobRepository extends JpaRepository<Job, Long> {
    List<Job> findByTaskIdOrderByCreatedAtDesc(Long taskId);
    Optional<Job> findTopByTaskIdAndJobTypeOrderByCreatedAtDesc(Long taskId, Job.JobType jobType);
}
