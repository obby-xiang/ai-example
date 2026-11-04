package com.example.configmgr.job.repo;

import com.example.configmgr.job.entity.Job;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
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

    /**
     * 启动僵尸恢复的候选分页（keyset，按 id 升序，{@code LIMIT} 由 {@link Pageable} 施加）。
     *
     * <p>只投影恢复真正要用的标量列：{@code findAll()} 会把<b>全库</b>作业连同 EAGER 的
     * {@code job_items} 与 {@code result_json}（CLOB）一并水合进一级缓存，堆占用与启动时长
     * 随历史作业数线性放大；恢复实际只关心在途作业。
     *
     * <p>keyset（{@code id > afterId}）而非 offset：恢复会把命中行改为 FAILED（移出
     * {@code status IN} 集合），offset 分页会因此跳行；按 id 前进则与集合变化无关。
     */
    @Query("SELECT j.id AS id, j.taskId AS taskId, j.jobType AS jobType "
            + "FROM Job j WHERE j.status IN :statuses AND j.id > :afterId ORDER BY j.id ASC")
    List<InFlightJobRef> findInFlightRefsAfter(@Param("afterId") long afterId,
                                               @Param("statuses") Collection<Job.JobStatus> statuses,
                                               Pageable pageable);

    /** {@link #findInFlightRefsAfter} 的标量投影（恢复只需要这些列；进度等列不读，避免旧快照回写）。 */
    interface InFlightJobRef {
        Long getId();

        Long getTaskId();

        Job.JobType getJobType();
    }

    /** 在途作业（看门狗扫描面，R-96 批 B）：只取指定状态，不 findAll() 全表。 */
    List<Job> findByStatusIn(Collection<Job.JobStatus> statuses);

    /** 取消/终态权威快照：避免为 runner 边界检查水合 EAGER items 与 resultJson。 */
    @Query("SELECT j.status AS status, j.cancelVersion AS cancelVersion FROM Job j WHERE j.id = :jobId")
    Optional<CancelState> findCancelStateById(@Param("jobId") Long jobId);

    interface CancelState {
        Job.JobStatus getStatus();

        long getCancelVersion();
    }

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

    /** 终态同时推进持久取消版本，旧 runner 的共享写与本地加速都可被该版本围栏。 */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Job j SET j.status = :status, j.errorCount = :errorCount, "
            + "j.warningCount = :warningCount, j.progress = :processed, j.total = :total, "
            + "j.finishedAt = :finishedAt, j.cancelVersion = j.cancelVersion + 1 "
            + "WHERE j.id = :jobId AND j.status IN :inFlight")
    int finishIfInFlight(@Param("jobId") Long jobId,
                         @Param("status") Job.JobStatus status,
                         @Param("errorCount") int errorCount,
                         @Param("warningCount") int warningCount,
                         @Param("processed") int processed,
                         @Param("total") int total,
                         @Param("finishedAt") LocalDateTime finishedAt,
                         @Param("inFlight") Collection<Job.JobStatus> inFlight);

    /** 启动恢复只迁移状态、完成时刻与取消版本，不回写扫描时刻的旧进度。 */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Job j SET j.status = :status, j.finishedAt = :finishedAt, "
            + "j.cancelVersion = j.cancelVersion + 1 "
            + "WHERE j.id = :jobId AND j.status IN :inFlight")
    int finishInterruptedIfInFlight(@Param("jobId") Long jobId,
                                    @Param("status") Job.JobStatus status,
                                    @Param("finishedAt") LocalDateTime finishedAt,
                                    @Param("inFlight") Collection<Job.JobStatus> inFlight);

    /** 取消状态与权威版本在同一条件 UPDATE 中提交。 */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Job j SET j.status = :status, j.cancelVersion = j.cancelVersion + 1 "
            + "WHERE j.id = :jobId AND j.status IN :inFlight")
    int updateStatusIfInFlight(@Param("jobId") Long jobId,
                               @Param("status") Job.JobStatus status,
                               @Param("inFlight") Collection<Job.JobStatus> inFlight);
}
