package com.example.configmgr.ai.kb;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.TenantUser;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KbServiceSchedulerTest {

    @Test
    void kbServiceHasNoCustomExecutorImplementation() {
        assertThat(java.util.Arrays.stream(KbService.class.getDeclaredClasses())
                .filter(ScheduledExecutorService.class::isAssignableFrom)
                .toList())
                .isEmpty();
    }

    @Test
    void startUsesConfiguredPeriodsAndStopCancelsOnlyServiceTasks() {
        ThreadPoolTaskScheduler scheduler = mock(ThreadPoolTaskScheduler.class);
        ScheduledFuture<?> initial = mock(ScheduledFuture.class);
        ScheduledFuture<?> refresh = mock(ScheduledFuture.class);
        ScheduledFuture<?> poll = mock(ScheduledFuture.class);
        org.mockito.Mockito.doReturn(initial).when(scheduler).submit(any(Runnable.class));
        org.mockito.Mockito.doReturn(refresh, poll).when(scheduler)
                .scheduleWithFixedDelay(any(Runnable.class), any(Duration.class));
        AiProperties properties = properties();
        properties.getKb().setRefreshInterval(Duration.ofSeconds(17));
        properties.getKb().getCache().setVersionPollInterval(Duration.ofMillis(23));
        KbService service = new KbService(properties, KbTestSupport.mapper(), mock(CachedKbSource.class), scheduler);

        service.start();
        service.stop();

        verify(scheduler).submit(any(Runnable.class));
        verify(scheduler).scheduleWithFixedDelay(any(Runnable.class),
                org.mockito.ArgumentMatchers.eq(Duration.ofSeconds(17)));
        verify(scheduler).scheduleWithFixedDelay(any(Runnable.class),
                org.mockito.ArgumentMatchers.eq(Duration.ofMillis(23)));
        verify(initial).cancel(false);
        verify(refresh).cancel(false);
        verify(poll).cancel(false);
        verify(scheduler, never()).shutdown();
    }

    @Test
    void periodicPollDoesNotInheritCallerIdentity() {
        ThreadPoolTaskScheduler scheduler = mock(ThreadPoolTaskScheduler.class);
        AtomicReference<Runnable> poll = new AtomicReference<>();
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), any(Duration.class)))
                .thenAnswer(invocation -> {
                    poll.set(invocation.getArgument(0));
                    return mock(ScheduledFuture.class);
                });
        CachedKbSource source = mock(CachedKbSource.class);
        AtomicReference<TenantUser> observed = new AtomicReference<>();
        when(source.cachedCorpusVersion()).thenAnswer(invocation -> {
            observed.set(RequestContextUtils.currentOrNull());
            return null;
        });
        KbService service = new KbService(properties(), KbTestSupport.mapper(), source, scheduler);
        service.start();

        try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(7L, 71L)) {
            poll.get().run();
        }

        assertThat(observed.get()).isNull();
        service.stop();
    }

    @Test
    void refreshNowKeepsTheWaitBudget() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("kb-refresh-test-");
        scheduler.initialize();
        AiProperties properties = properties();
        properties.getKb().setRefreshWaitBudget(Duration.ofMillis(30));
        KbSource blockedSource = new KbSource() {
            @Override
            public String kind() {
                return "local";
            }

            @Override
            public ManifestFetch fetchManifest(String previousEtag) throws KbSourceException {
                try {
                    Thread.sleep(300L);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new KbSourceException("interrupted", ex);
                }
                return ManifestFetch.notModified(null);
            }

            @Override
            public String fetchContent(KbDocMeta meta) {
                return "";
            }
        };
        KbService service = new KbService(properties, KbTestSupport.mapper(), blockedSource, scheduler);
        long started = System.nanoTime();

        boolean refreshed = service.refreshNow();

        assertThat(refreshed).isFalse();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(250L);
        assertThat(service.status().lastError()).contains("exceeded budget");
        service.stop();
        scheduler.shutdown();
    }

    private static AiProperties properties() {
        return KbTestSupport.props(Path.of("build", "kb-scheduler-test"),
                Path.of("build", "kb-scheduler-index-test"));
    }
}
