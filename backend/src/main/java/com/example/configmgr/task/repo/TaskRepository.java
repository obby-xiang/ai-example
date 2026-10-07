package com.example.configmgr.task.repo;

import com.example.configmgr.task.entity.Task;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TaskRepository extends JpaRepository<Task, Long> {
    /** createdAt 相同（同秒批量创建）时按 id 兜底，保证跨页顺序稳定（Q16①）。 */
    @Query("SELECT t FROM Task t ORDER BY t.createdAt DESC, t.id DESC")
    List<Task> findAllOrdered();

    /**
     * 历史任务检索：类型 / 状态 / 关键词（标题或 ID）筛选 + 分页，按创建时间倒序、id 兜底。
     *
     * <p>关键词里的 {@code %} / {@code _} / {@code \} 由调用方按 ESCAPE '\' 口径转义后传入
     * （Q16②：防止 {@code %} 命中全表）。{@code CAST(t.id AS string)} 只可能命中数字，
     * 转义后的通配符不会在其中产生命中。
     */
    @Query("SELECT t FROM Task t WHERE (:type IS NULL OR t.type = :type) "
            + "AND (:status IS NULL OR t.status = :status) "
            + "AND (:kw IS NULL OR :kw = '' OR t.title LIKE CONCAT('%', :kw, '%') ESCAPE '\\' "
            + "     OR CAST(t.id AS string) LIKE CONCAT('%', :kw, '%') ESCAPE '\\') "
            + "ORDER BY t.createdAt DESC, t.id DESC")
    Page<Task> search(@Param("type") Task.TaskType type,
                      @Param("status") Task.TaskStatus status,
                      @Param("kw") String kw,
                      Pageable pageable);
}
