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
