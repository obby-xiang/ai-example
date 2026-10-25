package com.example.configmgr.ai.kb;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.configmgr.ai.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 知识库生命周期健壮性（Task 7 + fix1）：空 manifest 不假 ready、失败的重建不把版本标记为"已见"
 * 且重试有界、停机后取快照不得自旋。
 *
 * <p>三个已确认缺陷：
 * <ul>
 * <li><b>空 manifest 变成 ready</b>：{@code KbManifest.validate()} 的文档口径写着"docs 为空视为源不可用"，
 * 实现只查 id/title，于是 {@code docs:[]} 建出一份空索引，{@code ready()=true}、检索面"在线但什么都没"；</li>
 * <li><b>失败的重建被标记为已见</b>：{@code pollVersionQuietly} 先推 {@code lastObservedVersion} 再刷新，
 * 重建失败后同一版本号不再触发，最长要等 10m 定时刷新；</li>
 * <li><b>停机后 acquire 自旋</b>：{@code stop()} 只 retire 快照、不摘掉 {@code snapshot} 引用，而
 * {@code acquire()} 的循环条件是"当前快照 != null"；retired 快照的 {@code tryAcquire()} 恒 false，
 * 于是循环永不退出（100% CPU），停机期间的检索/读取请求就此卡死。</li>
 * </ul>
 *
 * <p>fix1（复审 I1）补上"重试有界"：失败可重试，但不能变成每 30s 一次全量回源 + 全量重建 + 一条 WARN。
 * 同一版本最多自动重建 {@value #VERSION_RETRY_BUDGET} 次（含退避），成功即停，新版本重新给预算。
 */
class KbLifecycleRobustnessTest {

    /**
     * 版本轮询的自动重试预算（= {@code KbService.MAX_VERSION_REBUILD_ATTEMPTS} 的行为契约）。
     * 刻意写成字面量：这样本用例在"预算尚未实现"的树上也能编译，从而把缺陷跑成 RED。
     */
    private static final int VERSION_RETRY_BUDGET = 3;

    // ── 空 manifest 不得 ready ──────────────────────────────────────────────

    @Test
    void emptyManifestNeverBecomesReady() throws Exception {
        Path dir = KbTestSupport.tempDir("kb-empty-manifest-");
        Files.writeString(dir.resolve("manifest.json"),
                "{\"version\":\"v1\",\"generated_at\":\"2026-10-25\",\"docs\":[]}", StandardCharsets.UTF_8);

        KbService service = new KbService(KbTestSupport.props(dir, KbTestSupport.tempDir("kb-idx-")),
                KbTestSupport.mapper(), new LocalDirKbSource(dir, KbTestSupport.mapper()));

        assertThat(service.refreshNow()).isFalse();
        assertThat(service.ready()).isFalse();
        assertThat(service.status().ready()).isFalse();
        assertThat(service.status().lastError()).isNotBlank();
        assertThat(service.search("费率", 5).state()).isEqualTo(KbSearchResult.State.NOT_READY);
        assertThat(service.catalog()).isEmpty();
    }

    @Test
    void emptyManifestKeepsTheOldIndexInsteadOfWipingIt() throws Exception {
        Path dir = KbTestSupport.copySamples();
        KbService service = new KbService(KbTestSupport.props(dir, KbTestSupport.tempDir("kb-idx-")),
                KbTestSupport.mapper(), new LocalDirKbSource(dir, KbTestSupport.mapper()));
        assertThat(service.refreshNow()).isTrue();
        int docsBefore = service.status().docCount();

        Files.writeString(dir.resolve("manifest.json"),
                "{\"version\":\"v2\",\"generated_at\":\"2026-10-25\",\"docs\":[]}", StandardCharsets.UTF_8);

        assertThat(service.refreshNow()).isFalse();
        assertThat(service.status().docCount()).isEqualTo(docsBefore);
        assertThat(service.status().ready()).isTrue();
        assertThat(service.status().lastError()).isNotBlank();
        assertThat(service.search("费率", 5).hits()).isNotEmpty();
    }

    // ── 重建失败不得把版本标记为已见 ─────────────────────────────────────────

    @Test
    void failedRebuildIsRetriedOnTheNextVersionPoll() throws Exception {
        Path dir = KbTestSupport.copySamples();
        AiProperties properties = KbTestSupport.props(dir, KbTestSupport.tempDir("kb-idx-"));
        properties.getKb().getCache().setVersionPollInterval(Duration.ofMillis(20));
        FlakyCachedSource source = new FlakyCachedSource(dir, KbTestSupport.mapper(), properties.getKb());
        KbService service = new KbService(properties, KbTestSupport.mapper(), source);
        try {
            service.start();
            awaitAtLeast(() -> source.attempts(), 1, 5000);
            assertThat(service.ready()).isFalse();

            int afterFirstFailure = source.attempts();
            // 失败后同一版本号必须还能再触发一次重建（RED：版本已被记为"已见"，轮询直接 return）
            awaitAtLeast(() -> source.attempts(), afterFirstFailure + 1, 3000);

            source.fail(false);
            await(() -> service.ready(), 5000);
            assertThat(service.status().lastError()).isEmpty();
            assertThat(service.status().docCount()).isEqualTo(20);

            // I1：成功后必须停止因轮询而重建 —— 版本已记为已见，后续每一拍都不再回源
            int attemptsAtReady = source.attempts();
            Thread.sleep(240L);   // ≈12 个轮询周期
            assertThat(source.attempts()).isEqualTo(attemptsAtReady);
            assertThat(service.ready()).isTrue();
        }
        finally {
            service.stop();
        }
    }

    /**
     * I1：永久失败不得变成"每 30s 一次全量回源 + 全量重建 + 一条 WARN"。
     *
     * <p>同一版本最多自动重建 {@code MAX_VERSION_REBUILD_ATTEMPTS} 次（含退避），用完即静默交回
     * 定时刷新；而且连续失败只留少量 WARN（其余 DEBUG）。
     */
    @Test
    void persistentFailureIsBoundedPerVersionAndDoesNotRebuildOnEveryPoll() throws Exception {
        Path dir = KbTestSupport.copySamples();
        AiProperties properties = KbTestSupport.props(dir, KbTestSupport.tempDir("kb-idx-"));
        properties.getKb().getCache().setVersionPollInterval(Duration.ofMillis(20));
        FlakyCachedSource source = new FlakyCachedSource(dir, KbTestSupport.mapper(), properties.getKb());
        KbService service = new KbService(properties, KbTestSupport.mapper(), source);
        LogCapture logs = LogCapture.attachTo(KbService.class);
        try {
            service.start();
            // 首刷 1 次 + 自动重试预算 MAX 次 = 4 次之后就该彻底安静
            awaitAtLeast(() -> source.attempts(), VERSION_RETRY_BUDGET + 1, 5000);
            int afterBudgetExhausted = source.attempts();
            assertThat(service.ready()).isFalse();
            assertThat(service.status().lastError()).isNotBlank();

            Thread.sleep(300L);   // ≈15 个轮询周期：预算用完 ⇒ 一拍都不再回源/重建
            assertThat(source.attempts()).isEqualTo(afterBudgetExhausted);

            // WARN 节流：首条失败 + "暂停自动重试" 各一条；后续失败不刷 WARN
            assertThat(logs.messages(Level.WARN))
                    .hasSize(2)
                    .anySatisfy(message -> assertThat(message).contains("暂停自动重试"));
        }
        finally {
            service.stop();
            logs.detach();
        }
    }

    /** I1：预算用完之后，**新版本**仍必须能立刻重试（版本变化重置预算）。 */
    @Test
    void aNewVersionGetsAFreshRetryBudget() throws Exception {
        Path dir = KbTestSupport.copySamples();
        AiProperties properties = KbTestSupport.props(dir, KbTestSupport.tempDir("kb-idx-"));
        properties.getKb().getCache().setVersionPollInterval(Duration.ofMillis(20));
        FlakyCachedSource source = new FlakyCachedSource(dir, KbTestSupport.mapper(), properties.getKb());
        KbService service = new KbService(properties, KbTestSupport.mapper(), source);
        try {
            service.start();
            awaitAtLeast(() -> source.attempts(), VERSION_RETRY_BUDGET + 1, 5000);
            int exhausted = source.attempts();

            source.version(2L);
            awaitAtLeast(() -> source.attempts(), exhausted + 1, 3000);

            source.fail(false);
            await(() -> service.ready(), 5000);
            assertThat(service.status().docCount()).isEqualTo(20);
        }
        finally {
            service.stop();
        }
    }

    // ── 停机后 acquire 不得自旋 ─────────────────────────────────────────────

    @Test
    void searchAfterStopReturnsNotReadyInsteadOfSpinning() throws Exception {
        KbService service = KbTestSupport.samplesService();
        assertThat(service.ready()).isTrue();

        service.stop();

        ExecutorService probe = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "kb-stop-probe");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<KbSearchResult> future = probe.submit(() -> service.search("费率", 5));
            KbSearchResult result;
            try {
                result = future.get(3, TimeUnit.SECONDS);
            }
            catch (TimeoutException e) {
                future.cancel(true);
                throw new AssertionError("stop() 之后 search() 仍在 acquire 自旋（3s 未返回）", e);
            }
            assertThat(result.state()).isEqualTo(KbSearchResult.State.NOT_READY);
            assertThat(service.readDoc("region-tariff", null, null, null).state())
                    .isEqualTo(KbReadResult.State.NOT_READY);
            assertThat(service.lookup("标准费率").notReady()).isTrue();
        }
        finally {
            probe.shutdownNow();
        }
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    /** 可控失败的缓存源：版本号由用例决定，重建可被"注入失败"。 */
    static final class FlakyCachedSource extends CachedKbSource {

        private final LocalDirKbSource delegate;

        private final AtomicInteger attempts = new AtomicInteger();

        private volatile boolean fail = true;

        private volatile long version = 1L;

        FlakyCachedSource(Path dir, ObjectMapper mapper, AiProperties.Kb kb) {
            this(new LocalDirKbSource(dir, mapper), mapper, kb);
        }

        private FlakyCachedSource(LocalDirKbSource delegate, ObjectMapper mapper, AiProperties.Kb kb) {
            super(delegate, null, mapper, kb);
            this.delegate = delegate;
        }

        void fail(boolean fail) {
            this.fail = fail;
        }

        int attempts() {
            return this.attempts.get();
        }

        void version(long version) {
            this.version = version;
        }

        @Override
        public String kind() {
            return "local";
        }

        @Override
        public long cachedVersion() {
            return this.version;
        }

        @Override
        public KbSource.ManifestFetch fetchManifest(String previousEtag, boolean force) throws KbSourceException {
            this.attempts.incrementAndGet();
            if (this.fail) {
                throw new KbSourceException("注入的重建失败（磁盘/Lucene 异常模拟）");
            }
            return this.delegate.fetchManifest(previousEtag, force);
        }

        @Override
        public String fetchContent(KbDocMeta meta) throws KbSourceException {
            return this.delegate.fetchContent(meta);
        }
    }

    /** 只读日志捕获：只断言 WARN 条数（DEBUG 是否产生取决于运行期日志级别，不断言）。 */
    static final class LogCapture {

        private final Logger logger;

        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        private LogCapture(Logger logger) {
            this.logger = logger;
        }

        static LogCapture attachTo(Class<?> type) {
            LogCapture capture = new LogCapture((Logger) LoggerFactory.getLogger(type));
            capture.appender.start();
            capture.logger.addAppender(capture.appender);
            return capture;
        }

        List<String> messages(Level level) {
            return List.copyOf(this.appender.list).stream()
                    .filter(event -> event.getLevel() == level)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
        }

        void detach() {
            this.logger.detachAppender(this.appender);
        }
    }

    private static void awaitAtLeast(java.util.function.IntSupplier actual, int expected, long timeoutMs)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (actual.getAsInt() < expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("等待条件超时：" + expected + " 次尝试（实际 " + actual.getAsInt() + "）");
            }
            Thread.sleep(10L);
        }
    }

    private static void await(java.util.function.BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("等待条件超时");
            }
            Thread.sleep(10L);
        }
    }

}
