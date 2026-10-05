package com.example.configmgr.task.repo;

import com.example.configmgr.task.entity.TaskItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface TaskItemRepository extends JpaRepository<TaskItem, Long> {
    List<TaskItem> findByTaskIdOrderBySortOrder(Long taskId);
    Optional<TaskItem> findByTaskIdAndDefCode(Long taskId, String defCode);
    void deleteByTaskId(Long taskId);

    @Modifying
    @Query("DELETE FROM TaskItem i WHERE i.taskId = :taskId AND i.defCode NOT IN :codes")
    void deleteByTaskIdAndDefCodeNotIn(@Param("taskId") Long taskId, @Param("codes") List<String> codes);
}
