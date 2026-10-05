package com.example.configadmin.repository;

import com.example.configadmin.entity.ExportTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExportTaskRepository extends JpaRepository<ExportTask, Long> {
    List<ExportTask> findAllByOrderByIdDesc();
}
