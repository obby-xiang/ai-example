package com.example.configmgr.task.repo;

import com.example.configmgr.task.entity.Task;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TaskRepository extends JpaRepository<Task, Long> {

    /**
     * 任务行锁（R1-13）：删除任务与建作业都以本行作为互斥点，统一 task → job 的加锁顺序。
     *
     * <p>只锁任务行，不锁作业行。前置条件（缺一不可）：
     * <ul>
     * <li>{@code JobProgressService.finish} 必须保持内部 {@code REQUIRES_NEW} 并先提交 job 行，
     *     {@code JobTerminalWriter} 写 task 前再显式锁 task；否则会形成“持 job 锁等 task”的反向环；</li>
     * <li>H2 的 FK 检查不取父行共享锁（实测）；若迁到 InnoDB，删除持 task X 锁、子表 INSERT 取父行 S 锁
     *     的行为需重新评估，但当前应用层锁顺序仍是 task → job。</li>
     * </ul>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Task t WHERE t.id = :id")
    Optional<Task> findByIdForUpdate(@Param("id") Long id);

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
