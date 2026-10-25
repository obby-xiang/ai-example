package com.example.configmgr.job.repo;

import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.entity.JobItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface JobItemRepository extends JpaRepository<JobItem, Long> {
    List<JobItem> findByJobId(Long jobId);
    Optional<JobItem> findByJobIdAndDefCode(Long jobId, String defCode);

    @Modifying
    @Query("DELETE FROM JobItem i WHERE i.jobId IN :jobIds")
    void deleteByJobIdIn(@Param("jobIds") List<Long> jobIds);

    /**
     * 条目收敛（作业终态落写时调用）：把该作业下仍未收终态的条目（{@code PENDING/RUNNING}）
     * 一次性改成 {@code :terminalStatus}。
     *
     * <p>两个调用点（各自在写作业终态的同一事务内，保证"作业终态 / 条目终态"原子）：
     * {@code JobProgressService#finish} → {@code "FAILED"}（判死/判超时/执行器失败）、
     * {@code JobService#cancel} → {@code "CANCELLED"}（取消；条目终态由取消方负责）。
     * 已 COMPLETED 的条目永不匹配 ⇒ 不覆盖成功终态。
     *
     * <p>为什么必须由终态写入方兜住：被 SIGKILL 打断的执行器不会再有任何
     * {@code finishItem}/{@code updateItem} 调用，条目行由 {@code startItem} 以 {@code RUNNING}
     * 独立提交（{@code REQUIRES_NEW}）⇒ 只剩数据库里那一行；看门狗/僵尸恢复只写作业行时，
     * 条目会永久停在 RUNNING（三实例验收 job-kill-a：job #5 FAILED 而 job_items 残留
     * {@code RUNNING, processed=1}）。
     *
     * <p>幂等：只匹配在途条目 ⇒ 重复调用/多实例并发都是 0 行；已 COMPLETED/CANCELLED/FAILED
     * 的条目一律不动（取消语义与失败补偿不被翻写）。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE JobItem i SET i.status = :terminalStatus "
            + "WHERE i.jobId = :jobId "
            + "AND i.status IN ('PENDING', 'RUNNING')")
    int convergeOpenItems(@Param("jobId") Long jobId, @Param("terminalStatus") String terminalStatus);

    /**
     * 条目终态/进度条件写入（R1-11 / I-3）：同一条 UPDATE 里核父作业是否接受本次条目状态，
     * 且条目自身必须仍未收终态。
     *
     * <p>允许集合 = 在途（PENDING/RUNNING）+ 与条目状态一致的父作业终态（如父作业 CANCELLED
     * 时仍允许把条目记为 CANCELLED）。父作业已 COMPLETED 而条目迟到写 COMPLETED/FAILED、
     * 或条目本身已 COMPLETED/FAILED/CANCELLED 时影响 0 行，避免迟到写翻写终态/进度。
     *
     * <p>并发口径：{@code EXISTS} 与父作业终态提交之间没有显式行锁，条件在语句执行时重判；
     * 与取消/终态条件 UPDATE 同为语句级守卫（极窄窗口内仍可能先后交错）。
     * 缺失条目的插入路径另用父作业行悲观锁收紧（见 {@code JobProgressService#markItemFinal}）。
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE JobItem i SET i.status = :itemStatus, i.processed = :processed, i.total = :total "
            + "WHERE i.id = :itemId "
            + "AND i.status IN :openItemStatuses "
            + "AND EXISTS (SELECT j.id FROM Job j WHERE j.id = i.jobId AND j.status IN :allowedJobStatuses)")
    int finishIfJobStatusAllowed(@Param("itemId") Long itemId,
                                 @Param("itemStatus") String itemStatus,
                                 @Param("processed") int processed,
                                 @Param("total") int total,
                                 @Param("openItemStatuses") Collection<String> openItemStatuses,
                                 @Param("allowedJobStatuses") Collection<Job.JobStatus> allowedJobStatuses);

    /**
     * 条目进度条件写（I-3）：父作业在途且条目自身未收终态才写。
     * 终态之后的旧分片进度影响 0 行。
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE JobItem i SET i.processed = :processed, i.total = :total "
            + "WHERE i.id = :itemId "
            + "AND i.status IN :openItemStatuses "
            + "AND EXISTS (SELECT j.id FROM Job j WHERE j.id = i.jobId AND j.status IN :inFlight)")
    int updateProgressIfJobInFlightAndItemOpen(@Param("itemId") Long itemId,
                                               @Param("processed") int processed,
                                               @Param("total") int total,
                                               @Param("openItemStatuses") Collection<String> openItemStatuses,
                                               @Param("inFlight") Collection<Job.JobStatus> inFlight);

    /**
     * 失败补偿专用写（I-4）：把仍在途作业下的条目降级为 FAILED。
     *
     * <p>与 {@link #finishIfJobStatusAllowed} 的“条目终态一次性”不同：执行器先写条目 COMPLETED、
     * 再写盘/更新任务项，后者失败时 catch 需要把 COMPLETED 降级为 FAILED。允许集合仅
     * {@code PENDING/RUNNING/COMPLETED}，且父作业必须仍在途 —— 不允许从 CANCELLED 翻写，
     * 也不允许父作业已终态后翻写；取消保护不因此放宽。
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE JobItem i SET i.status = 'FAILED', i.processed = :processed, i.total = :total "
            + "WHERE i.id = :itemId "
            + "AND i.status IN :compensableItemStatuses "
            + "AND EXISTS (SELECT j.id FROM Job j WHERE j.id = i.jobId AND j.status IN :inFlight)")
    int compensateFailureIfInFlight(@Param("itemId") Long itemId,
                                    @Param("processed") int processed,
                                    @Param("total") int total,
                                    @Param("compensableItemStatuses") Collection<String> compensableItemStatuses,
                                    @Param("inFlight") Collection<Job.JobStatus> inFlight);
}
