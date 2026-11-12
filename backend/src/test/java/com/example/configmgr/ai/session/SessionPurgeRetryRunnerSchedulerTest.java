package com.example.configmgr.ai.session;

import com.example.configmgr.ai.config.AiProperties;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SessionPurgeRetryRunnerSchedulerTest {

    @Test
    void onReadyRegistersTheConfiguredPeriodAndShutdownCancelsOnlyTheRunnerTask() {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> task = mock(ScheduledFuture.class);
        org.mockito.Mockito.doReturn(task).when(scheduler)
                .scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));
        SessionPurgeRetryRunner runner = new SessionPurgeRetryRunner(mock(SessionPurgePendingStore.class),
                mock(AiSessionPurgeService.class), null, new AiProperties(), scheduler);

        runner.onReady();
        runner.shutdown();

        verify(scheduler).scheduleWithFixedDelay(any(Runnable.class),
                org.mockito.ArgumentMatchers.eq(10_000L),
                org.mockito.ArgumentMatchers.eq(30_000L),
                org.mockito.ArgumentMatchers.eq(TimeUnit.MILLISECONDS));
        verify(task).cancel(false);
        verify(scheduler, never()).shutdown();
        verify(scheduler, never()).shutdownNow();
    }

    @Test
    void periodicRegistrationOffDoesNotTouchTheInjectedScheduler() {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        AiProperties properties = new AiProperties();
        properties.getSession().getPurgeRetry().setPeriodicEnabled(false);
        SessionPurgeRetryRunner runner = new SessionPurgeRetryRunner(mock(SessionPurgePendingStore.class),
                mock(AiSessionPurgeService.class), null, properties, scheduler);

        runner.onReady();
        runner.shutdown();

        verifyNoInteractions(scheduler);
    }
}
