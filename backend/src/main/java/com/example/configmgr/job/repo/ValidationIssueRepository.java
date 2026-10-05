package com.example.configmgr.job.repo;

import com.example.configmgr.job.entity.ValidationIssue;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ValidationIssueRepository extends JpaRepository<ValidationIssue, Long> {
    List<ValidationIssue> findByJobIdAndDefCode(Long jobId, String defCode);
    Page<ValidationIssue> findByJobId(Long jobId, Pageable pageable);
    long countByJobIdAndSeverity(Long jobId, ValidationIssue.Severity severity);
    void deleteByJobId(Long jobId);
}
