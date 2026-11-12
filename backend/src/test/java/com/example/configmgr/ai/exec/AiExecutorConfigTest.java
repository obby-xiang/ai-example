package com.example.configmgr.ai.exec;

import com.alibaba.ttl.spi.TtlWrapper;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AiExecutorConfigTest {

    private final AiExecutorConfig config = new AiExecutorConfig();

    @Test
    void watchdogPoolsUseDefaultThreadsAndCancellationPolicies() throws Exception {
        ScheduledExecutorService watchdog = this.config.aiStreamWatchdog();
        ScheduledExecutorService cancelPoll = this.config.aiCancelPoll();
        try {
            assertWatchdogPool(watchdog);
            assertWatchdogPool(cancelPoll);
            assertThat(watchdog.submit(() -> Thread.currentThread().getName()).get())
                    .startsWith("pool-");
            assertThat(cancelPoll.submit(() -> Thread.currentThread().getName()).get())
                    .startsWith("pool-");
        } finally {
            watchdog.shutdownNow();
            cancelPoll.shutdownNow();
        }
    }

    @Test
    void purgeSchedulerUsesOneThreadWithCancellationPolicies() {
        ScheduledExecutorService scheduler = this.config.aiSessionPurgeRetryScheduler();
        try {
            ScheduledThreadPoolExecutor raw = raw(scheduler);
            assertThat(raw.getCorePoolSize()).isEqualTo(1);
            assertThat(raw.getKeepAliveTime(TimeUnit.SECONDS)).isEqualTo(60L);
            assertThat(raw.allowsCoreThreadTimeOut()).isTrue();
            assertThat(raw.getRemoveOnCancelPolicy()).isTrue();
            assertThat(raw.getExecuteExistingDelayedTasksAfterShutdownPolicy()).isFalse();
            assertThat(raw.getContinueExistingPeriodicTasksAfterShutdownPolicy()).isFalse();
        } finally {
            scheduler.shutdownNow();
        }
    }

    private static void assertWatchdogPool(ScheduledExecutorService scheduler) {
        ScheduledThreadPoolExecutor raw = raw(scheduler);
        assertThat(raw.getCorePoolSize()).isEqualTo(2);
        assertThat(raw.getKeepAliveTime(TimeUnit.SECONDS)).isEqualTo(60L);
        assertThat(raw.allowsCoreThreadTimeOut()).isTrue();
        assertThat(raw.getRemoveOnCancelPolicy()).isTrue();
        assertThat(raw.getExecuteExistingDelayedTasksAfterShutdownPolicy()).isFalse();
        assertThat(raw.getContinueExistingPeriodicTasksAfterShutdownPolicy()).isFalse();
    }

    @SuppressWarnings("unchecked")
    private static ScheduledThreadPoolExecutor raw(ScheduledExecutorService scheduler) {
        if (scheduler instanceof TtlWrapper<?> wrapper) {
            return (ScheduledThreadPoolExecutor) wrapper.unwrap();
        }
        return (ScheduledThreadPoolExecutor) scheduler;
    }
}
