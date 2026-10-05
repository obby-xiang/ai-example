package com.example.configadmin.repository;

import com.example.configadmin.entity.ImportBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ImportBatchRepository extends JpaRepository<ImportBatch, Long> {
    List<ImportBatch> findAllByOrderByIdDesc();
}
