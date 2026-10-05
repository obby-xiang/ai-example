package com.example.configmgr.task.repo;

import com.example.configmgr.task.entity.TaskFile;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface TaskFileRepository extends JpaRepository<TaskFile, Long> {
    List<TaskFile> findByTaskId(Long taskId);
    Optional<TaskFile> findByTaskIdAndDefCodeAndFileType(Long taskId, String defCode, String fileType);
    void deleteByTaskId(Long taskId);
    void deleteByTaskIdAndDefCode(Long taskId, String defCode);
}
