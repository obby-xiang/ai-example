package com.example.configmgr.job.service;

import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.repo.JobItemRepository;
import com.example.configmgr.job.repo.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 作业终态守卫用例族（R-96 批 B 收口修 F1，红队 M2/m2）：{@link JobProgressService#finish}
 * 仅当作业当前为 PENDING/RUNNING 才写入终态 —— 看门狗判死 FAILED 与作业自然完成 COMPLETED
 * 的 TOCTOU 窗口内先到者胜、后到者不覆盖（含计数/进度等伴随字段整体不写入）。
 *
 * <p>纯 Mockito：事务管理器直通（{@link SimpleTransactionStatus}）。并发用例中
 * {@code getTransaction→commit} 持<b>同一把事务锁</b>，模拟生产库"单行读-判-写"在
 * REQUIRES_NEW 事务内的串行化语义（等价于 DB 行锁），使两线程竞争成为确定性断言
 * —— 真实库的窗口收敛由守卫本身把"无守卫的覆盖写"降级为"整段跳过"。
 *
 * <p>另含终审微补丁 F1b（2026-10-11）用例：被守卫拦下的调用与写入成功路径同样清理
 * {@link JobHeartbeatRegistry} —— 入口的无条件 {@code beat} 会把已终态作业的条目重新
 * 塞回登记簿，该路径不清则条目永无清理点。
 */
class JobProgressServiceTerminalGuardTest {

    private final JobRepository jobRepository = mock(JobRepository.class);
    private final JobItemRepository jobItemRepository = mock(JobItemRepository.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final JobHeartbeatRegistry heartbeatRegistry = new JobHeartbeatRegistry();

    private final JobProgressService progressService = new JobProgressService(
            this.jobRepository, this.jobItemRepository, this.transactionManager, this.heartbeatRegistry);

    /** 事务级锁：getTransaction 获取、commit/rollback 释放 —— 整段回调串行（模拟 DB 行锁）。 */
    private final ReentrantLock txLock = new ReentrantLock();

    @BeforeEach
    void passThroughTransactionWithRowLockSemantics() {
        doAnswer(invocation -> {
            this.txLock.lock();
            return new SimpleTransactionStatus();
        }).when(this.transactionManager).getTransaction(any());
        doAnswer(invocation -> {
            this.txLock.unlock();
            return null;
        }).when(this.transactionManager).commit(any());
        doAnswer(invocation -> {
            this.txLock.unlock();
            return null;
        }).when(this.transactionManager).rollback(any());
    }

    @Test
    void completedThenFailedKeepsCompletedIgnoringSecondWriteEntirely() {
        Job job = new Job();
        job.setId(1L);
        job.setStatus(Job.JobStatus.RUNNING);
        when(this.jobRepository.findById(1L)).thenReturn(Optional.of(job));

        this.progressService.finish(1L, Job.JobStatus.COMPLETED, 0, 0, 10, 10);
        assertThat(job.getStatus()).isEqualTo(Job.JobStatus.COMPLETED);
        assertThat(job.getFinishedAt()).isNotNull();

        this.progressService.finish(1L, Job.JobStatus.FAILED, 9, 0, 10, 10);
        assertThat(job.getStatus()).as("终态守卫：后到者不覆盖先到终态").isEqualTo(Job.JobStatus.COMPLETED);
        assertThat(job.getErrorCount()).as("第二次写入被整体忽略（含计数）").isZero();
        assertThat(job.getFinishedAt()).isNotNull();
    }

    @Test
    void failedThenCompletedKeepsFailedIgnoringSecondWriteEntirely() {
        Job job = new Job();
        job.setId(2L);
        job.setStatus(Job.JobStatus.RUNNING);
        when(this.jobRepository.findById(2L)).thenReturn(Optional.of(job));

        this.progressService.finish(2L, Job.JobStatus.FAILED, 1, 0, 3, 10);
        this.progressService.finish(2L, Job.JobStatus.COMPLETED, 0, 0, 10, 10);

        assertThat(job.getStatus()).as("看门狗判死 FAILED 在先时，作业线程晚到的 COMPLETED 不得翻回")
                .isEqualTo(Job.JobStatus.FAILED);
        assertThat(job.getErrorCount()).isEqualTo(1);
    }

    @Test
    void concurrentFinishFromTwoThreadsNeverFlipsTerminalState() throws Exception {
        Job job = new Job();
        job.setId(3L);
        job.setStatus(Job.JobStatus.RUNNING);
        when(this.jobRepository.findById(3L)).thenReturn(Optional.of(job));

        CountDownLatch start = new CountDownLatch(1);
        Thread completedWriter = finishThread(start, 3L, Job.JobStatus.COMPLETED, 0);
        Thread failedWriter = finishThread(start, 3L, Job.JobStatus.FAILED, 1);
        completedWriter.start();
        failedWriter.start();
        start.countDown();
        completedWriter.join();
        failedWriter.join();

        // 终态稳定不翻转：无论谁先到，伴随字段（errorCount）与终态自洽，且只有一方写入了终态
        assertThat(job.getStatus()).isIn(Job.JobStatus.COMPLETED, Job.JobStatus.FAILED);
        assertThat(job.getErrorCount())
                .as("终态与计数必须来自同一次写入（先到者胜，后到者整段跳过）")
                .isEqualTo(job.getStatus() == Job.JobStatus.FAILED ? 1 : 0);
        assertThat(job.getFinishedAt()).isNotNull();
    }

    @Test
    void guardedSecondFinishAlsoClearsTheHeartbeatEntryReinsertedByEntryBeat() {
        Job job = new Job();
        job.setId(4L);
        job.setStatus(Job.JobStatus.RUNNING);
        when(this.jobRepository.findById(4L)).thenReturn(Optional.of(job));

        this.progressService.finish(4L, Job.JobStatus.COMPLETED, 0, 0, 10, 10);
        assertThat(this.heartbeatRegistry.lastBeatAtMs(4L)).as("终态写入成功后条目已清（F3）").isNull();

        // 终态作业再被 finish 调用：入口的无条件 beat 先把条目塞回登记簿，随后被终态守卫拦下
        this.progressService.finish(4L, Job.JobStatus.FAILED, 9, 0, 10, 10);

        assertThat(job.getStatus()).as("终态守卫：后到者不覆盖先到终态").isEqualTo(Job.JobStatus.COMPLETED);
        assertThat(this.heartbeatRegistry.lastBeatAtMs(4L))
                .as("被守卫拦下的路径同样清理登记簿 —— 入口 beat 不得留下无人清理的条目（F1b）").isNull();
    }

    private Thread finishThread(CountDownLatch start, Long jobId, Job.JobStatus status, int errorCount) {
        return new Thread(() -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            this.progressService.finish(jobId, status, errorCount, 0, 10, 10);
        });
    }
}
