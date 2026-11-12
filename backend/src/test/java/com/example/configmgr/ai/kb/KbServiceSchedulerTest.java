package com.example.configmgr.ai.kb;

import ch.qos.logback.classic.Level;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.TenantUser;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
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
        doReturn(initial).when(scheduler).submit(any(Runnable.class));
        doReturn(refresh, poll).when(scheduler)
                .scheduleWithFixedDelay(any(Runnable.class), any(Duration.class));
        AiProperties properties = properties();
        properties.getKb().setRefreshInterval(Duration.ofSeconds(17));
        properties.getKb().getCache().setVersionPollInterval(Duration.ofMillis(23));
        KbService service = new KbService(properties, KbTestSupport.mapper(), mock(CachedKbSource.class), scheduler);

        service.start();
        service.stop();

        verify(scheduler).submit(any(Runnable.class));
        verify(scheduler).scheduleWithFixedDelay(any(Runnable.class), eq(Duration.ofSeconds(17)));
        verify(scheduler).scheduleWithFixedDelay(any(Runnable.class), eq(Duration.ofMillis(23)));
        verify(initial).cancel(false);
        verify(refresh).cancel(false);
        verify(poll).cancel(false);
        verify(scheduler, never()).shutdown();
    }

    @Test
    void initialRefreshExecutesWithoutResubmittingToSingleScheduler() throws Exception {
        SchedulerFixture fixture = new SchedulerFixture();
        KbSource source = notModifiedSource();
        KbService service = new KbService(properties(), KbTestSupport.mapper(), source, fixture.scheduler);
        service.start();

        fixture.initial.get().run();

        verify(fixture.scheduler, never()).submit(any(Callable.class));
        service.stop();
    }

    @Test
    void periodicRefreshExecutesWithoutResubmittingToSingleScheduler() throws Exception {
        SchedulerFixture fixture = new SchedulerFixture();
        KbService service = new KbService(properties(), KbTestSupport.mapper(), notModifiedSource(), fixture.scheduler);
        service.start();

        fixture.periodic.getFirst().run();

        verify(fixture.scheduler, never()).submit(any(Callable.class));
        service.stop();
    }

    @Test
    void versionPollRefreshExecutesWithoutResubmittingToSingleScheduler() throws Exception {
        SchedulerFixture fixture = new SchedulerFixture();
        CachedKbSource source = mock(CachedKbSource.class);
        when(source.cachedCorpusVersion()).thenReturn("cv-next");
        when(source.fetchManifest(any(), anyBoolean(), any())).thenReturn(
                new KbSource.ManifestFetch(null, null, true, "cv-next"));
        AiProperties properties = properties();
        properties.getKb().setRefreshInterval(Duration.ofSeconds(17));
        properties.getKb().getCache().setVersionPollInterval(Duration.ofMillis(23));
        KbService service = new KbService(properties, KbTestSupport.mapper(), source, fixture.scheduler);
        service.start();

        fixture.periodic.get(1).run();

        verify(fixture.scheduler, never()).submit(any(Callable.class));
        service.stop();
    }

    @Test
    void initialRefreshBuildsRealIndexOnSingleScheduler() throws Exception {
        Path corpus = KbTestSupport.corpus("scheduler-start", KbTestSupport.mapOf(
                "scheduler-doc", "# 调度恢复\n\n首刷必须直接构建可检索索引。"));
        AiProperties properties = KbTestSupport.props(corpus, KbTestSupport.tempDir("kb-scheduler-start-index-"));
        properties.getKb().setRefreshWaitBudget(Duration.ofSeconds(5));
        properties.getKb().setRefreshInterval(Duration.ofMinutes(1));
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("kb-refresh-test-");
        scheduler.initialize();
        LocalDirKbSource delegate = new LocalDirKbSource(corpus, KbTestSupport.mapper());
        CountDownLatch firstFetch = new CountDownLatch(1);
        KbSource source = new KbSource() {
            @Override
            public String kind() {
                return "local";
            }

            @Override
            public KbSource.ManifestFetch fetchManifest(String previousEtag) throws KbSourceException {
                firstFetch.countDown();
                return delegate.fetchManifest(previousEtag);
            }

            @Override
            public String fetchContent(KbDocMeta meta) throws KbSourceException {
                return delegate.fetchContent(meta);
            }
        };
        KbService service = new KbService(properties, KbTestSupport.mapper(), source, scheduler);
        try {
            service.start();

            assertThat(firstFetch.await(250L, TimeUnit.MILLISECONDS)).isTrue();
            await(service::isReady, 2000L);
            assertThat(service.status().docCount()).isEqualTo(1);
            assertThat(service.search("首刷", 5).hits()).isNotEmpty();
        } finally {
            service.stop();
            scheduler.shutdown();
        }
    }

    @Test
    void managementRefreshKeepsBoundedSubmission() throws Exception {
        SchedulerFixture fixture = new SchedulerFixture();
        when(fixture.management.get(anyLong(), any(TimeUnit.class))).thenReturn(true);
        KbService service = new KbService(properties(), KbTestSupport.mapper(), notModifiedSource(), fixture.scheduler);

        assertThat(service.refreshNow()).isTrue();

        verify(fixture.scheduler).submit(any(Callable.class));
        verify(fixture.management).get(anyLong(), eq(TimeUnit.NANOSECONDS));
        service.stop();
    }

    @Test
    void queuedRefreshTimeoutLogsSafeWarnAndCancellation() throws Exception {
        SchedulerFixture fixture = new SchedulerFixture();
        when(fixture.management.get(anyLong(), any(TimeUnit.class))).thenThrow(new TimeoutException());
        when(fixture.management.cancel(false)).thenReturn(true);
        KbService service = new KbService(properties(), KbTestSupport.mapper(), notModifiedSource(), fixture.scheduler);
        KbLifecycleRobustnessTest.LogCapture logs = KbLifecycleRobustnessTest.LogCapture.attachTo(KbService.class);
        try {
            assertThat(service.refreshNow()).isFalse();

            assertThat(service.status().lastError()).contains("exceeded budget");
            assertThat(logs.messages(Level.WARN))
                    .satisfiesExactly(message -> assertThat(message)
                            .contains("exceeded budget")
                            .contains("cancelled before execution")
                            .doesNotContain("manifest", "C:" + "\\", "content="));
        } finally {
            service.stop();
            logs.detach();
        }
    }

    @Test
    void runningRefreshTimeoutLogsSafeWarnWithoutInterruptingIo() throws Exception {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("kb-refresh-test-");
        scheduler.initialize();
        AiProperties properties = properties();
        properties.getKb().setRefreshWaitBudget(Duration.ofMillis(200));
        KbSource source = mock(KbSource.class);
        CountDownLatch allowSourceExit = new CountDownLatch(1);
        when(source.fetchManifest(any(), anyBoolean(), any())).thenAnswer(invocation -> {
            allowSourceExit.await(2L, TimeUnit.SECONDS);
            return KbSource.ManifestFetch.notModified(null);
        });
        KbService service = new KbService(properties, KbTestSupport.mapper(), source, scheduler);
        KbLifecycleRobustnessTest.LogCapture logs = KbLifecycleRobustnessTest.LogCapture.attachTo(KbService.class);
        try {
            assertThat(service.refreshNow()).isFalse();

            assertThat(service.status().lastError()).contains("exceeded budget");
            assertThat(logs.messages(Level.WARN))
                    .anySatisfy(message -> assertThat(message)
                            .contains("exceeded budget")
                            .contains("not forcibly interrupted")
                            .doesNotContain("manifest", "C:" + "\\", "content="));
        } finally {
            allowSourceExit.countDown();
            service.stop();
            scheduler.shutdown();
            logs.detach();
        }
    }

    @Test
    void stopWaitsForManagementRefreshBeforeClosingSourceAndKeepsSharedSchedulerAlive() throws Exception {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("kb-refresh-test-");
        scheduler.initialize();
        CountDownLatch refreshEntered = new CountDownLatch(1);
        CountDownLatch allowRefreshExit = new CountDownLatch(1);
        List<String> events = java.util.Collections.synchronizedList(new ArrayList<>());
        CachedKbSource source = mock(CachedKbSource.class);
        when(source.fetchManifest(any(), anyBoolean(), any())).thenAnswer(invocation -> {
            refreshEntered.countDown();
            if (!allowRefreshExit.await(2L, TimeUnit.SECONDS)) {
                throw new KbSourceException("test refresh release timed out");
            }
            events.add("refresh-finished");
            return KbSource.ManifestFetch.notModified(null);
        });
        doAnswer(invocation -> {
            events.add("source-closed");
            return null;
        }).when(source).close();
        KbService service = new KbService(properties(), KbTestSupport.mapper(), source, scheduler);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> management = callers.submit((Callable<Boolean>) service::refreshNow);
            assertThat(refreshEntered.await(1L, TimeUnit.SECONDS)).isTrue();

            Future<?> stopping = callers.submit(service::stop);
            Thread.sleep(75L);
            assertThat(stopping.isDone()).isFalse();
            assertThat(events).isEmpty();

            allowRefreshExit.countDown();
            stopping.get(1L, TimeUnit.SECONDS);
            assertThat(events).containsExactly("refresh-finished", "source-closed");
            assertThat(service.refreshNow()).isFalse();
            assertThat(scheduler.submit(() -> "alive").get(1L, TimeUnit.SECONDS)).isEqualTo("alive");
            management.get(1L, TimeUnit.SECONDS);
        } finally {
            allowRefreshExit.countDown();
            callers.shutdownNow();
            scheduler.shutdown();
        }
    }

    @Test
    void stopTimeoutIsObservableBeforeResourcesClose() throws Exception {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("kb-refresh-test-");
        scheduler.initialize();
        AiProperties properties = properties();
        properties.getKb().setRefreshWaitBudget(Duration.ofMillis(40));
        CountDownLatch refreshEntered = new CountDownLatch(1);
        CountDownLatch allowRefreshExit = new CountDownLatch(1);
        CachedKbSource source = mock(CachedKbSource.class);
        when(source.fetchManifest(any(), anyBoolean(), any())).thenAnswer(invocation -> {
            refreshEntered.countDown();
            allowRefreshExit.await(2L, TimeUnit.SECONDS);
            return KbSource.ManifestFetch.notModified(null);
        });
        KbService service = new KbService(properties, KbTestSupport.mapper(), source, scheduler);
        ExecutorService callers = Executors.newSingleThreadExecutor();
        KbLifecycleRobustnessTest.LogCapture logs = KbLifecycleRobustnessTest.LogCapture.attachTo(KbService.class);
        try {
            callers.submit((Callable<Boolean>) service::refreshNow);
            assertThat(refreshEntered.await(1L, TimeUnit.SECONDS)).isTrue();

            service.stop();

            assertThat(service.status().lastError()).contains("stop wait exceeded budget");
            assertThat(logs.messages(Level.WARN))
                    .anySatisfy(message -> assertThat(message).contains("KB stop wait exceeded budget"));
            verify(source).close();
        } finally {
            allowRefreshExit.countDown();
            callers.shutdownNow();
            scheduler.shutdown();
            logs.detach();
        }
    }

    @Test
    void kbSpringSchedulerUsesDedicatedDaemonThreadAndRetainsPolicies() throws Exception {
        ThreadPoolTaskScheduler scheduler = new KbSchedulerConfig().kbRefreshScheduler(runnable -> runnable);
        scheduler.initialize();
        try {
            java.util.concurrent.ScheduledThreadPoolExecutor raw =
                    (java.util.concurrent.ScheduledThreadPoolExecutor) scheduler.getScheduledExecutor();
            assertThat(raw.getRemoveOnCancelPolicy()).isTrue();
            Thread thread = scheduler.submit(Thread::currentThread).get(1L, TimeUnit.SECONDS);
            assertThat(thread.getName()).startsWith("kb-refresh-");
            assertThat(thread.isDaemon()).isTrue();
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void cancelledSubmissionCannotEnterRefreshAfterStopClosesResources() throws Exception {
        SchedulerFixture fixture = new SchedulerFixture();
        when(fixture.management.cancel(false)).thenReturn(true);
        CachedKbSource source = mock(CachedKbSource.class);
        KbService service = new KbService(properties(), KbTestSupport.mapper(), source, fixture.scheduler);
        assertThat(service.refreshNow()).isFalse();
        Object monitor = org.springframework.test.util.ReflectionTestUtils.getField(service, "refreshMonitor");
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> execution;
            synchronized (monitor) {
                execution = caller.submit(fixture.managementCall.get());
                Thread.sleep(50L);
                service.stop();
            }

            assertThat(execution.get(1L, TimeUnit.SECONDS)).isFalse();
            verify(source, never()).fetchManifest(any(), anyBoolean(), any());
        } finally {
            caller.shutdownNow();
        }
    }

    @Test
    void directConstructorSchedulerUsesDedicatedDaemonThread() throws Exception {
        AtomicReference<Thread> worker = new AtomicReference<>();
        KbSource source = mock(KbSource.class);
        when(source.fetchManifest(any(), anyBoolean(), any())).thenAnswer(invocation -> {
            worker.set(Thread.currentThread());
            return KbSource.ManifestFetch.notModified(null);
        });
        KbService service = new KbService(properties(), KbTestSupport.mapper(), source);
        try {
            assertThat(service.refreshNow()).isFalse();

            assertThat(worker.get().getName()).startsWith("kb-refresh-");
            assertThat(worker.get().isDaemon()).isTrue();
        } finally {
            service.stop();
        }
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
            public KbSource.ManifestFetch fetchManifest(String previousEtag) throws KbSourceException {
                try {
                    Thread.sleep(300L);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new KbSourceException("interrupted", ex);
                }
                return KbSource.ManifestFetch.notModified(null);
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

    private static KbSource notModifiedSource() throws KbSourceException {
        KbSource source = mock(KbSource.class);
        when(source.fetchManifest(any(), anyBoolean(), any())).thenReturn(KbSource.ManifestFetch.notModified(null));
        return source;
    }

    private static void await(java.util.function.BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition was not met within " + timeoutMs + "ms");
            }
            Thread.sleep(10L);
        }
    }

    private static AiProperties properties() {
        return KbTestSupport.props(Path.of("build", "kb-scheduler-test"),
                Path.of("build", "kb-scheduler-index-test"));
    }

    private static final class SchedulerFixture {
        private final ThreadPoolTaskScheduler scheduler = mock(ThreadPoolTaskScheduler.class);
        private final AtomicReference<Runnable> initial = new AtomicReference<>();
        private final List<Runnable> periodic = new ArrayList<>();
        private final Future<Boolean> management = mock(Future.class);
        private final AtomicReference<Callable<Boolean>> managementCall = new AtomicReference<>();

        private SchedulerFixture() throws Exception {
            Future<?> initialTask = mock(Future.class);
            doAnswer(invocation -> {
                this.initial.set(invocation.getArgument(0));
                return initialTask;
            }).when(this.scheduler).submit(any(Runnable.class));
            when(this.scheduler.scheduleWithFixedDelay(any(Runnable.class), any(Duration.class)))
                    .thenAnswer(invocation -> {
                        this.periodic.add(invocation.getArgument(0));
                        return mock(ScheduledFuture.class);
                    });
            doAnswer(invocation -> {
                this.managementCall.set(invocation.getArgument(0));
                return this.management;
            }).when(this.scheduler).submit(any(Callable.class));
            when(this.management.get(anyLong(), any(TimeUnit.class))).thenReturn(false);
        }
    }
}
