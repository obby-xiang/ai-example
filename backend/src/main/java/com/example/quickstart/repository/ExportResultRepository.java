package com.example.quickstart.repository;

import com.example.quickstart.entity.ExportResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ExportResultRepository extends JpaRepository<ExportResult, Long> {

    List<ExportResult> findByJobIdOrderByIdAsc(Long jobId);

    Optional<ExportResult> findByJobIdAndDefCode(Long jobId, String defCode);

    void deleteByJobIdIn(Collection<Long> jobIds);
}
