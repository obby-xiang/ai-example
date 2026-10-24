package com.example.configmgr.job.repo;

import com.example.configmgr.job.entity.Job;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface JobRepository extends JpaRepository<Job, Long> {
    /** createdAt 相同（同一批创建）时按 id 兜底，保证"最新作业"取用稳定。 */
    List<Job> findByTaskIdOrderByCreatedAtDescIdDesc(Long taskId);

    Optional<Job> findTopByTaskIdAndJobTypeOrderByCreatedAtDesc(Long taskId, Job.JobType jobType);

    /** 最近一次该类作业（同刻创建时按 id 兜底）；预检查阻断守卫据此判定（M1 收尾守卫①）。 */
    Optional<Job> findTopByTaskIdAndJobTypeOrderByCreatedAtDescIdDesc(Long taskId, Job.JobType jobType);

    /** 该任务该类型尚未到终态的作业（互斥守卫判定用，M1 收尾守卫②）。 */
    Optional<Job> findFirstByTaskIdAndJobTypeAndStatusInOrderByIdDesc(Long taskId, Job.JobType jobType,
                                                                     Collection<Job.JobStatus> statuses);

    List<Job> findByTaskId(Long taskId);

    /** 启动清理 runner-active 键用：只取 id，不水合 EAGER 的 job_items。 */
    @Query("SELECT j.id FROM Job j")
    List<Long> findAllIds();

    /** 在途作业（看门狗扫描面，R-96 批 B）：只取指定状态，不 findAll() 全表。 */
    List<Job> findByStatusIn(Collection<Job.JobStatus> statuses);

    @Modifying
    @Query("DELETE FROM Job j WHERE j.taskId = :taskId")
    void deleteByTaskId(@Param("taskId") Long taskId);

    /** 父作业行锁（I-3 子项插入路径）：与取消/终态条件 UPDATE 串行，避免检查后又被取消插进终态。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT j FROM Job j WHERE j.id = :jobId")
    Optional<Job> findByIdForUpdate(@Param("jobId") Long jobId);

    /**
     * 指纹摘要定向条件写（I-2）：只写 resultJson，且仅当父作业仍在途。
     *
     * <p>预检查的 {@code run} 是长事务；旧实现 {@code findById + setResultJson + save} 会把
     * 读出时刻的 status（RUNNING）随整行 UPDATE 写回，覆盖期间已提交的 CANCELLED。
     * 定向 UPDATE 不碰 status，且 {@code WHERE status IN (...)} 在语句执行时重判。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Job j SET j.resultJson = :resultJson "
            + "WHERE j.id = :jobId AND j.status IN :inFlight")
    int writeResultJsonIfInFlight(@Param("jobId") Long jobId,
                                  @Param("resultJson") String resultJson,
                                  @Param("inFlight") Collection<Job.JobStatus> inFlight);

    /**
     * 分片边界的行级进度快照（⑤，R1-11）：只写 progress/total，<b>不碰 status</b>，
     * 且仅当作业仍在途（PENDING/RUNNING）才写 —— 否则终态之后到达的旧快照会把进度写回低值。
     */
    @Modifying
    @Query("UPDATE Job j SET j.progress = :processed, j.total = :total "
            + "WHERE j.id = :jobId AND j.status IN :inFlight")
    int updateProgressIfInFlight(@Param("jobId") Long jobId,
                                 @Param("processed") int processed,
                                 @Param("total") int total,
                                 @Param("inFlight") Collection<Job.JobStatus> inFlight);

    /**
     * 开工条件写入（R1-11 同族）：只允许 PENDING → RUNNING。
     * 读-判-写会让取消在两步之间提交后被整行 save 翻回 RUNNING，故同样下沉为条件 UPDATE。
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Job j SET j.status = :running, j.startedAt = :startedAt, j.total = :total "
            + "WHERE j.id = :jobId AND j.status = :pending")
    int markRunningIfPending(@Param("jobId") Long jobId,
                             @Param("running") Job.JobStatus running,
                             @Param("pending") Job.JobStatus pending,
                             @Param("startedAt") LocalDateTime startedAt,
                             @Param("total") int total);

    /**
     * 终态条件写入（R1-11）：{@code WHERE status IN (PENDING, RUNNING)} 由数据库原子判定，
     * 返回影响行数 = 本次是否胜出。跨进程两个写者同时到达时，恰好一方拿到 1 行。
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Job j SET j.status = :status, j.errorCount = :errorCount, "
            + "j.warningCount = :warningCount, j.progress = :processed, j.total = :total, "
            + "j.finishedAt = :finishedAt "
            + "WHERE j.id = :jobId AND j.status IN :inFlight")
    int finishIfInFlight(@Param("jobId") Long jobId,
                         @Param("status") Job.JobStatus status,
                         @Param("errorCount") int errorCount,
                         @Param("warningCount") int warningCount,
                         @Param("processed") int processed,
                         @Param("total") int total,
                         @Param("finishedAt") LocalDateTime finishedAt,
                         @Param("inFlight") Collection<Job.JobStatus> inFlight);

    /** 取消条件写入（R1-12）：仅 PENDING/RUNNING 可取消，影响行数为 0 即「已被别人收终态」。 */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Job j SET j.status = :status "
            + "WHERE j.id = :jobId AND j.status IN :inFlight")
    int updateStatusIfInFlight(@Param("jobId") Long jobId,
                               @Param("status") Job.JobStatus status,
                               @Param("inFlight") Collection<Job.JobStatus> inFlight);
}
