package com.example.configmgr.job.repo;

import com.example.configmgr.job.entity.Job;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface JobRepository extends JpaRepository<Job, Long> {
    /** createdAt 相同（同一批创建）时按 id 兜底，保证"最新作业"取用稳定。 */
    List<Job> findByTaskIdOrderByCreatedAtDescIdDesc(Long taskId);

    Optional<Job> findTopByTaskIdAndJobTypeOrderByCreatedAtDesc(Long taskId, Job.JobType jobType);

    List<Job> findByTaskId(Long taskId);

    @Modifying
    @Query("DELETE FROM Job j WHERE j.taskId = :taskId")
    void deleteByTaskId(@Param("taskId") Long taskId);

    /**
     * 分片边界的行级进度快照（⑤）：只写 progress/total，<b>不碰 status</b> ——
     * 若是读改写整行，会与并发的取消写入争锁并把状态改回 RUNNING；定向 UPDATE 没有这个问题。
     */
    @Modifying
    @Query("UPDATE Job j SET j.progress = :processed, j.total = :total WHERE j.id = :jobId")
    int updateProgress(@Param("jobId") Long jobId,
                       @Param("processed") int processed,
                       @Param("total") int total);
}
