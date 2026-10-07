package com.example.configmgr.job.repo;

import com.example.configmgr.job.entity.JobItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface JobItemRepository extends JpaRepository<JobItem, Long> {
    List<JobItem> findByJobId(Long jobId);
    Optional<JobItem> findByJobIdAndDefCode(Long jobId, String defCode);

    @Modifying
    @Query("DELETE FROM JobItem i WHERE i.jobId IN :jobIds")
    void deleteByJobIdIn(@Param("jobIds") List<Long> jobIds);
}
