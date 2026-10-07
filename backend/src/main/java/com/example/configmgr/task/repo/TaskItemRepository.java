package com.example.configmgr.task.repo;

import com.example.configmgr.task.entity.Task;
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

    /**
     * 有多少"未终态任务"选中了该配置（定义删除的被引用检查）：
     * 这类任务的检查/导入/发布作业随后仍会按 defCode 取定义，删掉即任务中途断裂。
     */
    @Query("SELECT COUNT(i) FROM TaskItem i, Task t WHERE t.id = i.taskId "
            + "AND i.defCode = :defCode AND t.status = :status")
    long countByDefCodeAndTaskStatus(@Param("defCode") String defCode, @Param("status") Task.TaskStatus status);
}
