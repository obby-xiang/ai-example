package com.example.configmgr.job.repo;

import com.example.configmgr.job.entity.JobItem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface JobItemRepository extends JpaRepository<JobItem, Long> {
    List<JobItem> findByJobId(Long jobId);
    Optional<JobItem> findByJobIdAndDefCode(Long jobId, String defCode);
}
