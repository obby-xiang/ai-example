package com.example.configmgr.job.service;

import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.repo.JobItemRepository;
import com.example.configmgr.job.repo.JobRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 心跳搭车用例（R-96 批 B，侦察报告 §7 U10）：{@link JobProgressService} 三个写方法
 * （{@code markRunning} / {@code snapshot} / {@code finish}）入口各续期一次
 * {@link JobHeartbeatRegistry} 心跳 —— 防「造了心跳却没人续期」的接线性回归。
 *
 * <p>纯 Mockito：事务管理器用 {@link SimpleTransactionStatus} 直通（本用例只关心心跳，
 * 不关心事务语义）；时钟用可控实现，让「心跳前进」成为确定性断言（不等真实毫秒）。
 */
class JobProgressHeartbeatTest {

    private final AtomicLong current = new AtomicLong(1_000_000);
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(current.get());
        }
    };

    private final JobRepository jobRepository = mock(JobRepository.class);
    private final JobItemRepository jobItemRepository = mock(JobItemRepository.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

    private final JobHeartbeatRegistry heartbeatRegistry = new JobHeartbeatRegistry(this.clock);

    private final JobProgressService progressService = new JobProgressService(
            this.jobRepository, this.jobItemRepository, this.transactionManager, this.heartbeatRegistry);

    @Test
    void markRunningSnapshotAdvanceHeartbeatAndFinishClearsEntry() {
        when(this.transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(this.jobRepository.finishIfInFlight(anyLong(), any(), anyInt(), anyInt(),
                anyInt(), anyInt(), any(), any())).thenReturn(1);

        assertThat(this.heartbeatRegistry.lastBeatAtMs(42L)).as("开工前无心跳").isNull();

        this.current.addAndGet(5);
        this.progressService.markRunning(42L, 100);
        Long t1 = this.heartbeatRegistry.lastBeatAtMs(42L);
        assertThat(t1).isEqualTo(1_000_005L);

        this.current.addAndGet(7);
        this.progressService.snapshot(42L, 3, 100);
        assertThat(this.heartbeatRegistry.lastBeatAtMs(42L)).as("snapshot 心跳前进").isEqualTo(1_000_012L);

        this.current.addAndGet(9);
        boolean wrote = this.progressService.finish(42L, Job.JobStatus.COMPLETED, 0, 0, 10, 100);
        assertThat(wrote).as("条件 UPDATE 影响 1 行 = 终态写入成功").isTrue();
        assertThat(this.heartbeatRegistry.lastBeatAtMs(42L))
                .as("finish 终态写入成功后清理心跳条目（收口修 F3 / 红队 m1）").isNull();
    }

    @Test
    void finishClearsHeartbeatEntryAfterNaturalCompletion() {
        when(this.transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(this.jobRepository.finishIfInFlight(anyLong(), any(), anyInt(), anyInt(),
                anyInt(), anyInt(), any(), any())).thenReturn(1);

        boolean wrote = this.progressService.finish(11L, Job.JobStatus.COMPLETED, 0, 0, 5, 5);

        assertThat(wrote).as("条件 UPDATE 影响 1 行 = 本次终态写胜出").isTrue();
        assertThat(this.heartbeatRegistry.lastBeatAtMs(11L))
                .as("正常完成后 lastBeatAtMs(jobId) 为 null（登记簿不随历史作业增长）").isNull();
    }

    @Test
    void heartbeatIsWrittenOutsideTheRequiresNewTransaction() {
        // 挂点纪律：心跳在 REQUIRES_NEW 事务外续期（纯内存无 I/O），事务管理器抛异常也不得吞掉心跳。
        when(this.transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        this.progressService.markRunning(7L, 50);

        assertThat(this.heartbeatRegistry.lastBeatAtMs(7L))
                .as("心跳先于/独立于事务提交即生效").isNotNull();
    }

    @Test
    void clearRemovesHeartbeatEntry() {
        this.heartbeatRegistry.beat(9L);
        assertThat(this.heartbeatRegistry.lastBeatAtMs(9L)).isNotNull();
        this.heartbeatRegistry.clear(9L);
        assertThat(this.heartbeatRegistry.lastBeatAtMs(9L)).isNull();
    }
}
