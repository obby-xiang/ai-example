package com.example.configmgr.task.repo;

import com.example.configmgr.task.entity.Task;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TaskRepository extends JpaRepository<Task, Long> {
    @Query("SELECT t FROM Task t ORDER BY t.createdAt DESC")
    List<Task> findAllOrdered();

    /**
     * 历史任务检索：类型 / 状态 / 关键词（标题或 ID）筛选 + 分页，按创建时间倒序。
     */
    @Query("SELECT t FROM Task t WHERE (:type IS NULL OR t.type = :type) "
            + "AND (:status IS NULL OR t.status = :status) "
            + "AND (:kw IS NULL OR :kw = '' OR t.title LIKE CONCAT('%', :kw, '%') "
            + "     OR CAST(t.id AS string) LIKE CONCAT('%', :kw, '%')) "
            + "ORDER BY t.createdAt DESC")
    Page<Task> search(@Param("type") Task.TaskType type,
                      @Param("status") Task.TaskStatus status,
                      @Param("kw") String kw,
                      Pageable pageable);
}
