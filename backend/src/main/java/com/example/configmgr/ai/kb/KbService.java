package com.example.configmgr.ai.kb;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tenant.RunIdentityScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * 知识库服务（定稿 §2）：索引生命周期 + BM25 检索 + 精确术语查表。
 *
 * <h2>启动与刷新</h2>
 * 启动期先做源准备（{@link KbSource#prepare()}：local 源在知识目录缺失/为空时从 classpath 播种
 * 样例），再<b>异步</b>建索引（{@link #start()} 只提交任务，不阻塞就绪）；未就绪时三个工具 fail-open 回
 * 「知识库加载中」。之后按 {@code app.ai.kb.refresh-interval}（默认 10m）定时刷新，管理端点可同步热重载。
 *
 * <h2>增量</h2>
 * 以 manifest 的 {@code sha} 为唯一判据：未变的文档<b>不重新拉取正文</b>，直接复用上一份
 * {@link KbDoc}（切块/章节引用不变，用例按实例断言）；变的只重建该篇。HTTP 源另用 ETag 做
 * "整份未变"短路（{@code notModified=true} ⇒ 直接返回，不重建）。
 *
 * <h2>热重载原子性</h2>
 * 每次重建都写一个独立的 {@code build-*} 子目录，建好后打开 reader 再<b>一次性换 volatile 快照</b>；
 * 旧快照按引用计数延迟关闭（并发检索持旧引用时不会被抽走 reader），最后一个使用者释放后关闭
 * reader/目录并删除旧构建目录 —— 检索侧永远只会看到"完整的旧索引"或"完整的新索引"。
 */
@Slf4j
@Service
public class KbService {

    private static final String F_DOC_ID = "doc_id";

    private static final String F_TITLE = "title";

    private static final String F_SECTION = "section";

    private static final String F_ALIASES = "aliases";

    private static final String F_ALIASES_EN = "aliases_en";

    private static final String F_TAGS = "tags";

    private static final String F_UPDATED = "updated_at";

    private static final String F_BODY = "body";

    /**
     * 字段权重（标题/章节/别名高于正文：定稿要求标题域加权）。
     */
    private static final Map<String, Float> BOOSTS = Map.of(
            F_TITLE, 3.0f, F_SECTION, 2.0f, F_ALIASES, 4.0f, F_ALIASES_EN, 4.0f,
            F_TAGS, 1.0f, F_BODY, 1.0f);

    /**
     * 首屏正文长度（定稿：省略 section 时"目录 + 前 1200 字"）。
     */
    static final int FIRST_SCREEN_CHARS = 1200;

    /**
     * 一次检索内部多取的候选数（去重到文档级后仍够 top_k）。
     */
    private static final int SEARCH_OVERFETCH = 4;

    private final AiProperties properties;

    private final ObjectMapper mapper;

    private final KbSource source;

    /**
     * 索引/查询分析器：默认 smartcn（中文），{@code aliases_en} 单独用 standard（英文词干外的
     * 小写/分词口径）。单语料原则下英文只经别名桥接，不建翻译副本。
     */
    private final Analyzer analyzer = new PerFieldAnalyzerWrapper(new SmartChineseAnalyzer(),
            Map.of(F_ALIASES_EN, new StandardAnalyzer()));

    private volatile Snapshot snapshot;

    private volatile String lastEtag;

    private volatile String lastError = "";

    private volatile Instant lastRefreshAt;

    /**
     * 最近一次成功构建的内容寻址语料版本（null = 尚未同步）。
     */
    private volatile String lastObservedVersion;

    /**
     * 同一版本最多自动重建几次（版本轮询通道）。用完即停，把该版本交回 10m 定时刷新 ——
     * 否则"manifest 改了但建索引必失败"的故障会变成每 30s 一次全量回源 + 全量重建 + 一条 WARN。
     */
    static final int MAX_VERSION_REBUILD_ATTEMPTS = 3;

    /**
     * 连续失败满几次再打一条 WARN，其余失败不重复记录。
     */
    private static final int FAILURE_WARN_EVERY = 4;

    // 下面三个字段只在 kb-refresh 单线程（pollVersionQuietly）里读写，故不加同步；
    // 与 refreshNow（管理端点线程也会调）共享的只有 lastError / consecutiveFailures。

    /**
     * 正在退避重试的缓存版本（null = 没有在重试）。
     */
    private String retryVersion;

    /**
     * 该版本已被自动重试失败了几次（0 = 尚未失败）。
     */
    private int retryAttempts;

    /**
     * 下一次允许重试该版本的时刻（{@link System#nanoTime()} 基准：单调时钟，不受墙钟跳变影响）。
     */
    private long retryNotBeforeNanos;

    /**
     * 连续刷新失败次数（WARN 节流用；任一成功路径清零）。管理端点线程也会调 refreshNow，故 volatile。
     */
    private volatile int consecutiveFailures;

    private final ThreadPoolTaskScheduler scheduler;

    private final boolean ownsScheduler;

    private volatile Future<?> initialRefreshTask;

    private volatile ScheduledFuture<?> refreshTask;

    private volatile ScheduledFuture<?> versionPollTask;

    private volatile RefreshSubmission submittedRefresh;

    private final Object refreshMonitor = new Object();

    private final ThreadLocal<Boolean> refreshWorker = ThreadLocal.withInitial(() -> false);

    private record RefreshSubmission(Future<Boolean> task, AtomicBoolean started, AtomicBoolean cancelled) {
    }

    public KbService(AiProperties properties, ObjectMapper mapper, KbSource source) {
        this.properties = properties;
        this.mapper = mapper;
        this.source = source;
        this.scheduler = new ThreadPoolTaskScheduler();
        this.scheduler.setPoolSize(1);
        this.scheduler.setThreadNamePrefix("kb-refresh-");
        this.scheduler.setDaemon(true);
        this.scheduler.setRemoveOnCancelPolicy(true);
        this.scheduler.initialize();
        this.ownsScheduler = true;
    }

    @Autowired
    public KbService(AiProperties properties, ObjectMapper mapper, KbSource source,
            @Qualifier("kbRefreshScheduler") ThreadPoolTaskScheduler scheduler) {
        this.properties = properties;
        this.mapper = mapper;
        this.source = source;
        this.scheduler = scheduler;
        this.ownsScheduler = false;
    }

    private final AtomicBoolean reloadRunning = new AtomicBoolean();

    @Getter
    private volatile boolean stopped;

    // ---------------------------------------------------------------- lifecycle

    @PostConstruct
    public void start() {
        if (!enabled()) {
            log.info("Business knowledge base disabled (app.ai.kb.enabled=false)");
            return;
        }
        // 首启自播种（local 源且目录为空时复制随包样例；http 源无操作）必须在第一次回源前完成
        this.source.prepare();
        // 首版索引由 refreshNow 的成功路径自己记 lastObservedVersion；失败时**不**记（下一拍重试同一版本）
        this.initialRefreshTask = this.scheduler.submit(this::refreshQuietly);
        long interval = Math.max(1, this.properties.getKb().getRefreshInterval().toMillis());
        this.refreshTask = this.scheduler.scheduleWithFixedDelay(withClearedIdentity(this::refreshQuietly),
                Duration.ofMillis(interval));
        if (this.source instanceof CachedKbSource) {
            long poll = Math.max(1, this.properties.getKb().getCache().getVersionPollInterval().toMillis());
            this.versionPollTask = this.scheduler.scheduleWithFixedDelay(withClearedIdentity(this::pollVersionQuietly),
                    Duration.ofMillis(poll));
        }
        log.info("Business KB started: source={} dir={} index={} refresh={}", source.kind(),
                this.properties.getKb().getDir(), this.properties.getKb().getIndexDir(),
                this.properties.getKb().getRefreshInterval());
    }

    @PreDestroy
    public void stop() {
        RefreshSubmission submission;
        synchronized (this.refreshMonitor) {
            this.stopped = true;
            submission = this.submittedRefresh;
        }
        cancelTask(this.initialRefreshTask);
        cancelTask(this.refreshTask);
        cancelTask(this.versionPollTask);
        cancelSubmittedRefresh(submission);
        awaitRefreshCompletion();
        if (this.ownsScheduler) {
            this.scheduler.shutdown();
        }
        if (this.source instanceof CachedKbSource cached) {
            cached.close();
        }
        // 先摘引用再退休：acquire() 的循环读的就是这个字段，留着已退休的快照 ⇒ tryAcquire 恒 false
        // 且快照引用不变 ⇒ 循环永不退出（停机期间的检索/读取请求 100% CPU 自旋）。
        Snapshot current = this.snapshot;
        this.snapshot = null;
        if (current != null) {
            current.retire();
        }
        try {
            this.analyzer.close();
        } catch (Exception e) {
            // 停机阶段继续释放其余资源。
        }
    }

    private void awaitRefreshCompletion() {
        Duration waitBudget = this.properties.getKb().getRefreshWaitBudget();
        long deadline = System.nanoTime() + Math.max(1L, waitBudget.toNanos());
        try {
            synchronized (this.refreshMonitor) {
                while (this.reloadRunning.get()) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0L) {
                        this.lastError = "KB stop wait exceeded budget " + waitBudget.toMillis()
                                + "ms; closing resources while refresh remains running";
                        log.warn("KB stop wait exceeded budget {}ms; refresh remains running", waitBudget.toMillis());
                        return;
                    }
                    TimeUnit.NANOSECONDS.timedWait(this.refreshMonitor, remaining);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            this.lastError = "KB stop wait was interrupted; closing resources while refresh remains running";
            log.warn("KB stop wait interrupted; refresh remains running", e);
        }
    }

    private void cancelSubmittedRefresh(RefreshSubmission submission) {
        if (submission != null) {
            cancelAwaitedRefresh(submission);
        }
    }

    private void clearReloadRunning() {
        synchronized (this.refreshMonitor) {
            this.reloadRunning.set(false);
            this.refreshMonitor.notifyAll();
        }
    }

    private static Runnable withClearedIdentity(Runnable task) {
        return () -> {
            try (RunIdentityScope.Scope ignored = RunIdentityScope.denied()) {
                task.run();
            }
        };
    }

    private static void cancelTask(Future<?> task) {
        if (task != null) {
            task.cancel(false);
        }
    }

    private void refreshQuietly() {
        this.refreshWorker.set(true);
        try {
            refreshNow();
        } catch (Throwable e) {
            // doRefresh 已兜住业务失败；这里只兜"兜底逻辑自身"（绝不让定时线程死亡）
            log.warn("KB scheduled refresh threw unexpectedly (old index kept)", e);
        } finally {
            this.refreshWorker.remove();
        }
    }

    /**
     * 其它实例发布新 corpus version 后，从同一缓存清单重建；只把成功构建的版本记为已见。
     */
    private void pollVersionQuietly() {
        if (!(this.source instanceof CachedKbSource cached)) {
            return;
        }
        String version = cached.cachedCorpusVersion();
        if (version == null || Objects.equals(version, this.lastObservedVersion)) {
            return;
        }
        if (!Objects.equals(version, this.retryVersion)) {
            this.retryVersion = version;
            this.retryAttempts = 0;
            this.retryNotBeforeNanos = 0L;
        }
        if (this.retryAttempts >= MAX_VERSION_REBUILD_ATTEMPTS) {
            return;
        }
        if (System.nanoTime() < this.retryNotBeforeNanos) {
            return;
        }
        refreshQuietly();
        if (Objects.equals(this.lastObservedVersion, version)) {
            this.retryVersion = null;
            this.retryAttempts = 0;
            this.retryNotBeforeNanos = 0L;
            return;
        }
        this.retryAttempts++;
        this.retryNotBeforeNanos = System.nanoTime() + backoffMillis(this.retryAttempts) * 1_000_000L;
        if (this.retryAttempts >= MAX_VERSION_REBUILD_ATTEMPTS) {
            log.warn("KB version {} failed to rebuild {} times in a row; auto-retry paused "
                            + "(old index kept, next attempt via scheduled refresh every {} or a new version)",
                    version, this.retryAttempts, this.properties.getKb().getRefreshInterval());
        }
    }

    /**
     * 第 {@code attempt} 次失败后的退避：{@code poll × attempt}，封顶定时刷新间隔（都用既有配置口径）。
     */
    private long backoffMillis(int attempt) {
        long poll = Math.max(1L, this.properties.getKb().getCache().getVersionPollInterval().toMillis());
        long cap = Math.max(poll, this.properties.getKb().getRefreshInterval().toMillis());
        return Math.min(cap, poll * Math.max(1, attempt));
    }

    // ---------------------------------------------------------------- refresh

    /**
     * 单飞刷新；管理端点按 refresh-wait-budget 有界等待，工作线程仅在可控点停止，不硬中断底层 I/O。
     *
     * @return true = 本次真的重建了索引；false = 未变化、已有刷新在飞、超时或失败
     */
    public boolean refreshNow() {
        return isRefreshNowPerformed();
    }

    public boolean refreshNow(boolean force) {
        return isRefreshNowPerformed(force);
    }

    public boolean isRefreshNowPerformed() {
        return isRefreshNowPerformed(false);
    }

    public boolean isRefreshNowPerformed(boolean force) {
        Duration refreshWaitBudget = this.properties.getKb().getRefreshWaitBudget();
        KbSource.RefreshBudget budget = KbSource.RefreshBudget.within(refreshWaitBudget);
        if (this.scheduler == null || Boolean.TRUE.equals(this.refreshWorker.get())) {
            synchronized (this.refreshMonitor) {
                if (!isRefreshAdmissionAllowed()) {
                    return false;
                }
            }
            return isRefreshPerformed(force, budget);
        }

        RefreshSubmission submission;
        synchronized (this.refreshMonitor) {
            if (!isRefreshAdmissionAllowed()) {
                return false;
            }
            submission = submitRefresh(force, budget);
        }
        if (submission == null) {
            return false;
        }
        return isRefreshAwaited(submission, refreshWaitBudget, budget);
    }

    private boolean isRefreshAdmissionAllowed() {
        return enabled() && !this.stopped && this.reloadRunning.compareAndSet(false, true);
    }

    private RefreshSubmission submitRefresh(boolean force, KbSource.RefreshBudget budget) {
        AtomicBoolean started = new AtomicBoolean();
        AtomicBoolean cancelled = new AtomicBoolean();
        try {
            Future<Boolean> task = this.scheduler.submit(() -> {
                synchronized (this.refreshMonitor) {
                    if (cancelled.get()) {
                        return false;
                    }
                    started.set(true);
                }
                return isRefreshPerformed(force, budget);
            });
            RefreshSubmission submission = new RefreshSubmission(task, started, cancelled);
            this.submittedRefresh = submission;
            return submission;
        } catch (RejectedExecutionException e) {
            clearReloadRunning();
            this.lastError = "KB refresh executor is stopped";
            return null;
        }
    }

    private boolean isRefreshAwaited(RefreshSubmission submission, Duration refreshWaitBudget,
                                     KbSource.RefreshBudget budget) {
        try {
            return submission.task.get(budget.remaining().toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            boolean cancelledBeforeExecution = cancelAwaitedRefresh(submission);
            this.lastError = "KB reload wait exceeded budget " + refreshWaitBudget.toMillis()
                    + "ms; running I/O was not forcibly interrupted";
            if (cancelledBeforeExecution) {
                log.warn("KB refresh wait exceeded budget {}ms; queued refresh was cancelled before execution",
                        refreshWaitBudget.toMillis(), e);
            } else {
                log.warn("KB refresh wait exceeded budget {}ms; running refresh was not forcibly interrupted",
                        refreshWaitBudget.toMillis(), e);
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelAwaitedRefresh(submission);
            this.lastError = "KB reload wait was interrupted";
            return false;
        } catch (ExecutionException | CancellationException e) {
            if (e instanceof CancellationException && !submission.started.get()) {
                clearReloadRunning();
            }
            this.lastError = "KB refresh failed; retry later";
            log.warn("KB refresh execution failed", ObjectUtils.defaultIfNull(e.getCause(), e));
            return false;
        } catch (KbSourceException e) {
            cancelAwaitedRefresh(submission);
            this.lastError = "KB refresh failed; retry later";
            log.warn("KB refresh failed", e);
            return false;
        }
    }

    private boolean cancelAwaitedRefresh(RefreshSubmission submission) {
        synchronized (this.refreshMonitor) {
            submission.cancelled.set(true);
            if (submission.task.cancel(false) && !submission.started.get()) {
                clearReloadRunning();
                return true;
            }
            return false;
        }
    }

    private boolean isRefreshPerformed(boolean force, KbSource.RefreshBudget budget) {
        this.refreshWorker.set(true);
        this.lastRefreshAt = Instant.now();
        Path buildDir = null;
        try {
            KbSource.ManifestFetch fetched = this.source.fetchManifest(this.lastEtag, force, budget);
            if (fetched.notModified()) {
                if (fetched.etag() != null) {
                    this.lastEtag = fetched.etag();
                }
                this.consecutiveFailures = 0;
                this.lastError = "";
                markObservedVersion(fetched);
                return false;
            }
            KbManifest manifest = fetched.manifest();
            if (manifest == null) {
                throw new KbSourceException("Knowledge source returned no manifest");
            }
            manifest.validate();

            Snapshot old = this.snapshot;
            Map<String, KbDoc> nextDocs = collectDocs(manifest, fetched, old, budget);
            if (old != null && isSameDocuments(old, nextDocs)
                    && Objects.equals(old.manifest.version(), manifest.version())) {
                this.lastEtag = fetched.etag();
                this.consecutiveFailures = 0;
                this.lastError = "";
                markObservedVersion(fetched);
                return false;
            }
            budget.check();
            buildDir = newBuildDir(budget);
            return isSnapshotPublished(manifest, nextDocs, buildDir, fetched, budget);
        } catch (Exception e) {
            handleRefreshFailure(e, buildDir, budget);
            return false;
        } finally {
            this.refreshWorker.remove();
            clearReloadRunning();
        }
    }

    private Map<String, KbDoc> collectDocs(KbManifest manifest, KbSource.ManifestFetch fetched, Snapshot old,
                                           KbSource.RefreshBudget budget) throws Exception {
        Map<String, KbDoc> nextDocs = new LinkedHashMap<>();
        for (KbDocMeta meta : manifest.docs()) {
            budget.check();
            KbDoc previous = old == null ? null : old.docs.get(meta.id());
            if (previous != null && isSameSha(previous.meta().sha(), meta.sha())) {
                nextDocs.put(meta.id(), previous.meta().equals(meta) ? previous : previous.withMeta(meta));
                continue;
            }
            String rawContent = fetched.contentFor(meta.id());
            if (rawContent == null) {
                rawContent = this.source.fetchContent(meta, budget);
            }
            String content = MarkdownChunker.normalize(rawContent);
            String actualSha = KbHashing.sha256Text(content);
            if (StringUtils.isNotBlank(meta.sha()) && !meta.sha().equalsIgnoreCase(actualSha)) {
                throw new KbSourceException("KB content SHA-256 (BOM stripped, LF normalized) does not match manifest: " + meta.id());
            }
            KbDocMeta effectiveMeta = StringUtils.isBlank(meta.sha()) ? meta.withSha(actualSha) : meta;
            List<KbSection> sections = MarkdownChunker.sections(content, effectiveMeta.title());
            List<KbChunk> chunks = MarkdownChunker.chunk(effectiveMeta.id(), effectiveMeta.title(), content,
                    this.properties.getKb().getChunkMinChars(), this.properties.getKb().getChunkMaxChars());
            budget.check();
            nextDocs.put(meta.id(), new KbDoc(effectiveMeta, content, sections, chunks));
        }
        return nextDocs;
    }

    private boolean isSnapshotPublished(KbManifest manifest, Map<String, KbDoc> nextDocs, Path buildDir,
                                        KbSource.ManifestFetch fetched, KbSource.RefreshBudget budget) throws Exception {
        Snapshot built = buildSnapshot(manifest, nextDocs, buildDir, budget);
        if (this.stopped) {
            built.retire();
            return false;
        }
        Snapshot previous = this.snapshot;
        this.snapshot = built;
        this.lastEtag = fetched.etag();
        this.consecutiveFailures = 0;
        this.lastError = "";
        markObservedVersion(fetched);
        if (previous != null) {
            previous.retire();
        }
        log.info("Business KB index rebuilt: docs={} chunks={} source={} dir={}", nextDocs.size(),
                built.chunkCount, source.kind(), buildDir.getFileName());
        return true;
    }

    private void handleRefreshFailure(Exception e, Path buildDir, KbSource.RefreshBudget budget) {
        this.lastError = "KB refresh failed; retry later";
        this.consecutiveFailures++;
        if ((this.consecutiveFailures - 1) % FAILURE_WARN_EVERY == 0) {
            log.warn("KB refresh failed (old index kept, search surface unchanged; consecutive failure #{})",
                    this.consecutiveFailures, e);
        }
        if (buildDir != null) {
            try {
                deleteRecursively(buildDir, budget);
            } catch (KbSourceException cleanupError) {
                this.lastError = "KB refresh failed; retry later";
                log.warn("KB build cleanup failed", cleanupError);
            }
        }
    }

    private void markObservedVersion(KbSource.ManifestFetch fetched) {
        if (StringUtils.isNotBlank(fetched.corpusVersion())) {
            this.lastObservedVersion = fetched.corpusVersion();
        }
    }

    private Path newBuildDir(KbSource.RefreshBudget budget) throws Exception {
        Path indexDir = Path.of(this.properties.getKb().getIndexDir()).toAbsolutePath().normalize();
        budget.check();
        Files.createDirectories(indexDir);
        budget.check();
        cleanupStaleBuildDirs(indexDir, budget);
        budget.check();
        return Files.createDirectories(indexDir.resolve("build-" + System.nanoTime()));
    }

    private void cleanupStaleBuildDirs(Path indexDir, KbSource.RefreshBudget budget) throws KbSourceException {
        Path live = this.snapshot == null ? null : this.snapshot.dir;
        try (Stream<Path> children = Files.list(indexDir)) {
            for (Path path : (Iterable<Path>) children::iterator) {
                budget.check();
                if (Files.isDirectory(path) && path.getFileName().toString().startsWith("build-")
                        && (live == null || !path.equals(live))) {
                    deleteRecursively(path, budget);
                }
            }
            budget.check();
        } catch (IOException e) {
            // 陈旧目录清理失败不影响当前索引，后续刷新会重试。
        }
    }

    private Snapshot buildSnapshot(KbManifest manifest, Map<String, KbDoc> docs, Path buildDir,
                                   KbSource.RefreshBudget budget) throws Exception {
        budget.check();
        Directory directory = FSDirectory.open(buildDir);
        DirectoryReader reader = null;
        try {
            writeDocuments(docs, directory, budget);
            reader = DirectoryReader.open(directory);
            budget.check();
            Map<String, KbTermEntry> terms = buildTermIndex(manifest, docs, budget);
            budget.check();
            return new Snapshot(manifest, docs, reader, directory, buildDir, terms);
        } catch (Exception e) {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                    // 构建失败路径：清理优先
                }
            }
            try {
                directory.close();
            } catch (IOException ignored) {
                // 同上
            }
            throw e;
        }
    }

    private void writeDocuments(Map<String, KbDoc> docs, Directory directory, KbSource.RefreshBudget budget)
            throws Exception {
        budget.check();
        IndexWriterConfig config = new IndexWriterConfig(this.analyzer);
        config.setSimilarity(new BM25Similarity());
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
        try (IndexWriter writer = new IndexWriter(directory, config)) {
            for (KbDoc doc : docs.values()) {
                budget.check();
                String aliases = String.join(" ", doc.meta().aliases());
                String aliasesEn = String.join(" ", doc.meta().aliasesEn());
                String tags = String.join(" ", doc.meta().tags());
                for (KbChunk chunk : doc.chunks()) {
                    budget.check();
                    Document document = new Document();
                    document.add(new StringField(F_DOC_ID, doc.meta().id(), Field.Store.YES));
                    document.add(new TextField(F_TITLE, doc.meta().title(), Field.Store.YES));
                    document.add(new TextField(F_SECTION, chunk.sectionPath(), Field.Store.YES));
                    document.add(new TextField(F_ALIASES, aliases, Field.Store.NO));
                    document.add(new TextField(F_ALIASES_EN, aliasesEn, Field.Store.NO));
                    document.add(new TextField(F_TAGS, tags, Field.Store.NO));
                    document.add(new StringField(F_UPDATED, nullSafe(doc.meta().updatedAt()), Field.Store.YES));
                    // 正文入索引的文本带章节路径前缀（定稿要求），展示/摘要仍用不带前缀的 body
                    document.add(new TextField(F_BODY, chunk.indexText(), Field.Store.YES));
                    writer.addDocument(document);
                }
            }
            budget.check();
            // commit/close 不可硬中断；等待方独立超时，工作线程只在下一可控点停止。
            writer.commit();
        }
        budget.check();
    }

    private Map<String, KbTermEntry> buildTermIndex(KbManifest manifest, Map<String, KbDoc> docs,
                                                    KbSource.RefreshBudget budget) throws KbSourceException {
        Map<String, KbTermEntry> index = new LinkedHashMap<>();
        for (KbDocMeta meta : manifest.docs()) {
            budget.check();
            KbDoc doc = docs.get(meta.id());
            if (doc == null) {
                continue;
            }
            for (KbTerm term : meta.terms()) {
                KbTermEntry entry = new KbTermEntry(term.answer(), meta.id(), meta.title(),
                        term.section(), meta.updatedAt());
                addTerm(index, term.name(), entry);
                for (String alias : term.aliases()) {
                    addTerm(index, alias, entry);
                }
            }
            // 文档级别名：答案回落到摘要/标题（"费率表"这类叫法至少能指向正确文档）
            KbTermEntry docEntry = new KbTermEntry(meta.displaySummary(), meta.id(), meta.title(),
                    null, meta.updatedAt());
            for (String alias : meta.aliases()) {
                addTerm(index, alias, docEntry);
            }
        }
        return index;
    }

    private static void addTerm(Map<String, KbTermEntry> index, String raw, KbTermEntry entry) {
        String normalized = KbText.normalizeTerm(raw);
        if (normalized.isEmpty()) {
            return;
        }
        // 先登记者优先（术语级在前、文档级别名在后），避免后登记的文档别名覆盖明确术语
        index.putIfAbsent(normalized, entry);
    }

    // ---------------------------------------------------------------- search

    public KbSearchResult search(String query, Integer topK) {
        if (!enabled()) {
            return KbSearchResult.disabled();
        }
        String trimmed = StringUtils.stripToEmpty(query);
        if (trimmed.isEmpty()) {
            return KbSearchResult.invalid("Search query is required; provide a business term, section name, or question");
        }
        Snapshot acquired = acquire();
        if (acquired == null) {
            return KbSearchResult.notReady();
        }
        try {
            List<String> terms = analyzeTerms(trimmed);
            if (terms.isEmpty() || terms.stream().allMatch(String::isBlank)) {
                return KbSearchResult.invalid("Search query has no searchable content because it contains only punctuation or stop words; add business keywords");
            }
            int limit = effectiveTopK(topK);
            RankedResult ranked = rankHits(acquired, terms, limit);
            if (ranked.hits().isEmpty()) {
                return KbSearchResult.noMatch(terms, suggestTitles(trimmed));
            }
            return toSearchResult(ranked, limit, terms);
        } catch (Exception e) {
            log.error("KB search failed", e);
            return KbSearchResult.error("Knowledge base search failed; retry later");
        } finally {
            acquired.release();
        }
    }

    private RankedResult rankHits(Snapshot acquired, List<String> terms, int limit) throws IOException {
        Query parsed = buildQuery(terms);
        TopDocs topDocs = acquired.searcher.search(parsed, Math.max(limit * SEARCH_OVERFETCH, 20));
        Map<String, ScoredHit> deduped = new LinkedHashMap<>();
        float topScore = 0f;
        for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
            topScore = Math.max(topScore, scoreDoc.score);
            Document stored = acquired.searcher.storedFields().document(scoreDoc.doc);
            String docId = stored.get(F_DOC_ID);
            if (docId == null || deduped.containsKey(docId)) {
                continue;
            }
            String section = stored.get(F_SECTION);
            String body = stored.get(F_BODY);
            KbSearchHit hit = new KbSearchHit(docId, stored.get(F_TITLE), section,
                    KbText.snippet(stripPathPrefix(body, section), terms, 160),
                    nullSafe(stored.get(F_UPDATED)), KbSearchHit.LOW);
            deduped.put(docId, new ScoredHit(hit, scoreDoc.score));
        }
        List<ScoredHit> ranked = new ArrayList<>(deduped.values());
        if (ranked.size() > limit) {
            ranked = new ArrayList<>(ranked.subList(0, limit));
        }
        return new RankedResult(ranked, topScore);
    }

    private KbSearchResult toSearchResult(RankedResult ranked, int limit, List<String> terms) {
        List<KbSearchHit> hits = new ArrayList<>(ranked.hits().size());
        boolean lowConfidence = ranked.hits().size() <= 2;
        float highThreshold = ranked.topScore() * (float) this.properties.getKb().getMatchHighRatio();
        float mediumThreshold = ranked.topScore() * (float) this.properties.getKb().getMatchMediumRatio();
        for (ScoredHit scored : ranked.hits()) {
            KbSearchHit hit = scored.hit;
            String match = lowConfidence ? KbSearchHit.LOW
                    : scored.score >= highThreshold ? KbSearchHit.HIGH
                    : scored.score >= mediumThreshold ? KbSearchHit.MEDIUM : KbSearchHit.LOW;
            hits.add(new KbSearchHit(hit.docId(), hit.title(), hit.section(), hit.snippet(),
                    hit.updatedAt(), match));
        }
        return lowConfidence ? KbSearchResult.lowConfidence(hits, terms)
                : KbSearchResult.ok(hits, terms);
    }

    private record RankedResult(List<ScoredHit> hits, float topScore) {
    }

    /**
     * 多字段 OR 查询（不引入 lucene-queryparser：依赖面只保留 core + analysis-common + smartcn）。
     *
     * <p>每个分析后的词在各字段上各生成一个 {@link TermQuery} 并按字段权重 {@link BoostQuery}
     * （标题 3 / 章节 2 / 别名 4 / 标签 1 / 正文 1）。词间是 OR —— 中文查询经 smartcn 切出多个词，
     * 要求全部命中会把"区域费率怎么计算"这类自然问句挡在门外；排序交给 BM25。
     */
    private Query buildQuery(List<String> terms) {
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        for (String term : terms) {
            for (Map.Entry<String, Float> field : BOOSTS.entrySet()) {
                Query clause = new TermQuery(new Term(field.getKey(), term));
                builder.add(new BoostQuery(clause, field.getValue()), BooleanClause.Occur.SHOULD);
            }
        }
        return builder.build();
    }

    private List<String> analyzeTerms(String query) throws IOException {
        Set<String> terms = new LinkedHashSet<>();
        try (TokenStream stream = this.analyzer.tokenStream(F_BODY, query)) {
            CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                String value = term.toString().strip();
                if (!value.isEmpty()) {
                    terms.add(value);
                }
                if (terms.size() >= 10) {
                    break;
                }
            }
            stream.end();
        }
        return terms.isEmpty() ? List.of() : new ArrayList<>(terms);
    }

    private static String stripPathPrefix(String body, String section) {
        if (body == null) {
            return "";
        }
        if (section != null && body.startsWith(section + "\n")) {
            return body.substring(section.length() + 1);
        }
        return body;
    }

    /**
     * 0 命中时的近似标题（<b>只给标题文本</b>，按字面二元组重合度排序；始终给 2–3 个可读入口）。
     *
     * <p>为什么不再拼 {@code id（title）}：真机 run 40220039653 的 K-10 —— 英文查询
     * {@code flurbox quota} 0 命中，候选串里的 doc_id 字母（{@code ta}/{@code qu}/{@code bo}）
     * 与 query 的二元组相撞，把 region-tariff / tariff-boundary / data-quality 顶到前 3；
     * 模型逐字复述了这些候选 doc_id；原文同时明确它们只是近似标题、并非命中。候选只服务"换词重查"，
     * 不需要也不应该携带可引用的 doc_id。
     */
    private List<String> suggestTitles(String query) {
        Snapshot current = this.snapshot;
        if (current == null) {
            return List.of();
        }
        List<String> titles = current.manifest.docs().stream().map(KbDocMeta::title).toList();
        List<String> ranked = new ArrayList<>(titles);
        ranked.sort(Comparator.comparingDouble((String title) -> overlap(query, title)).reversed());
        int count = Math.min(3, ranked.size());
        return count == 0 ? List.of() : new ArrayList<>(ranked.subList(0, count));
    }

    /**
     * 字面二元组重合度（0–1）；只对标题计算，用于"近似标题"排序，不参与命中判定。
     */
    static double overlap(String query, String title) {
        Set<String> queryGrams = bigrams(KbText.normalizeTerm(query));
        Set<String> titleGrams = bigrams(KbText.normalizeTerm(title));
        if (queryGrams.isEmpty() || titleGrams.isEmpty()) {
            return 0;
        }
        long common = queryGrams.stream().filter(titleGrams::contains).count();
        return (double) common / queryGrams.size();
    }

    private static Set<String> bigrams(String text) {
        Set<String> grams = new HashSet<>();
        String normalized = text.replaceAll("\\s+", "");
        for (int i = 0; i + 1 < normalized.length(); i++) {
            grams.add(normalized.substring(i, i + 2));
        }
        if (normalized.length() == 1) {
            grams.add(normalized);
        }
        return grams;
    }

    // ---------------------------------------------------------------- read

    public KbReadResult readDoc(String docId, String section, Integer offset, Integer maxChars) {
        return readDoc(docId, section, offset, maxChars, 0);
    }

    /**
     * 按 offset 读一页；{@code maxRows} 是<b>本页正文的行数上限</b>（0 = 不限）。
     *
     * <p>行预算与模型侧 {@code app.ai.result-budget.model.max-rows} 同源（由调用方
     * {@code KbTools} 从限流器取数）：<b>在行边界上切页</b>，于是 {@code next_offset} 恰好是
     * "这一页最后一个可见字符"的下一位 —— 模型照 next_offset 翻页不会跳读（L-87）。
     * 先按字符切、再按行切，两者谁先到就按谁切；空页不会发生（至少前进 1 字符）。
     */
    public KbReadResult readDoc(String docId, String section, Integer offset, Integer maxChars, int maxRows) {
        if (!enabled()) {
            return KbReadResult.disabled();
        }
        if (StringUtils.isBlank(docId)) {
            return KbReadResult.docNotFound("", List.of());
        }
        Snapshot acquired = acquire();
        if (acquired == null) {
            return KbReadResult.notReady();
        }
        try {
            KbDoc doc = acquired.docs.get(docId.strip());
            if (doc == null) {
                return KbReadResult.docNotFound(docId, docCandidates(acquired, docId));
            }
            int limit = effectiveMaxChars(maxChars);
            int from = offset == null || offset < 0 ? 0 : offset;
            KbSection target = null;
            if (StringUtils.isNotBlank(section)) {
                SectionMatch match = resolveSection(doc, section);
                if (match.section == null) {
                    return KbReadResult.sectionNotFound(docId, doc.meta().title(), section,
                            match.candidates);
                }
                target = match.section;
            }
            return readPage(doc, target, from, limit, maxRows);
        } catch (Exception e) {
            log.error("KB document read failed", e);
            return KbReadResult.error("Knowledge base read failed; retry later");
        } finally {
            acquired.release();
        }
    }

    private static KbReadResult readPage(KbDoc doc, KbSection target, int from, int limit, int maxRows) {
        int scopeStart = target == null ? 0 : Math.min(target.bodyOffset(), doc.content().length());
        int scopeEnd = target == null ? doc.content().length()
                : Math.min(Math.max(target.endOffset(), scopeStart), doc.content().length());
        int scopeLength = scopeEnd - scopeStart;
        if (from > scopeLength) {
            from = scopeLength;
        }
        boolean firstScreen = target == null && from == 0;
        int effectiveLimit = firstScreen ? Math.min(limit, FIRST_SCREEN_CHARS) : limit;
        int to = Math.min(scopeLength, from + effectiveLimit);
        if (maxRows > 0) {
            int cut = rowAlignedEnd(doc.content(), scopeStart + from, scopeStart + to, maxRows);
            to = Math.max(from, cut - scopeStart);
            if (to == from && from < scopeLength) {
                to = Math.min(scopeLength, from + 1);
            }
        }
        String text = doc.content().substring(scopeStart + from, scopeStart + to);
        boolean truncated = to < scopeLength;
        int nextOffset = truncated ? to : -1;
        List<String> toc = firstScreen ? doc.sections().stream().map(s -> s.tocLine()).toList()
                : List.of();
        return KbReadResult.ok(doc.meta().id(), doc.meta().title(),
                target == null ? null : target.path(), text, toc, truncated, nextOffset);
    }

    /**
     * 在行边界上切到前 {@code maxRows} 行，返回<b>绝对结束下标</b>（不含）。
     *
     * <p>行数口径 = 换行符数 + 1（与 {@code ToolResultLimiter} 的 {@code rowsOf} 同一把尺子）。
     * 切点落在第 {@code maxRows} 个换行符之前 ⇒ 本页恰好 {@code maxRows} 行。
     * 窗口内行数不足时返回 {@code end}（整段都要）。
     */
    private static int rowAlignedEnd(String content, int start, int end, int maxRows) {
        int newlines = 0;
        for (int i = start; i < end; i++) {
            if (content.charAt(i) != '\n') {
                continue;
            }
            if (newlines + 1 >= maxRows) {
                // 至少前进 1 字符：绝不返回空页（否则 next_offset 原地不动、翻页死循环）
                return i > start ? i : Math.min(end, start + 1);
            }
            newlines++;
        }
        return end;
    }

    private SectionMatch resolveSection(KbDoc doc, String section) {
        String wanted = section.strip();
        KbSection exact = null;
        List<KbSection> suffixMatches = new ArrayList<>();
        for (KbSection candidate : doc.sections()) {
            if (candidate.path().equals(wanted) || candidate.title().equals(wanted)) {
                exact = candidate;
                break;
            }
            if (candidate.path().endsWith(" > " + wanted)) {
                suffixMatches.add(candidate);
            }
        }
        if (exact != null) {
            return new SectionMatch(exact, List.of());
        }
        if (suffixMatches.size() == 1) {
            return new SectionMatch(suffixMatches.getFirst(), List.of());
        }
        List<String> candidates = (suffixMatches.isEmpty() ? nearSections(doc, wanted)
                : suffixMatches.stream().map(KbSection::path).toList());
        return new SectionMatch(null, candidates);
    }

    private static List<String> nearSections(KbDoc doc, String wanted) {
        List<KbSection> ranked = new ArrayList<>(doc.sections());
        ranked.sort(Comparator.comparingDouble((KbSection s) -> overlap(wanted, s.path())).reversed());
        return ranked.stream().limit(3).map(KbSection::path).toList();
    }

    private List<String> docCandidates(Snapshot snapshot, String docId) {
        List<KbDocMeta> metas = new ArrayList<>(snapshot.manifest.docs());
        metas.sort(Comparator.comparingDouble((KbDocMeta meta) -> overlap(docId, meta.id() + meta.title())).reversed());
        return metas.stream().limit(3).map(meta -> meta.id() + "（" + meta.title() + "）").toList();
    }

    // ---------------------------------------------------------------- lookup

    public KbLookupResult lookup(String term) {
        String normalized = KbText.normalizeTerm(term);
        if (!enabled()) {
            return KbLookupResult.disabled(nullSafe(term), normalized);
        }
        if (normalized.isEmpty()) {
            return KbLookupResult.error(nullSafe(term), normalized, "Term is required; provide a formal or colloquial term");
        }
        Snapshot acquired = acquire();
        if (acquired == null) {
            return KbLookupResult.notReady(nullSafe(term), normalized);
        }
        try {
            KbTermEntry entry = acquired.terms.get(normalized);
            if (entry == null) {
                List<String> suggestions = new ArrayList<>(acquired.terms.keySet());
                suggestions.sort(Comparator.comparingDouble((String key) -> overlap(normalized, key)).reversed());
                return KbLookupResult.miss(nullSafe(term), normalized,
                        suggestions.stream().limit(3).toList());
            }
            return KbLookupResult.hit(nullSafe(term), normalized, entry.answer, entry.docId,
                    entry.title, entry.section, entry.updatedAt);
        } catch (Exception e) {
            log.error("KB term lookup failed", e);
            return KbLookupResult.error(nullSafe(term), normalized, "Knowledge base term lookup failed; retry later");
        } finally {
            acquired.release();
        }
    }

    // ---------------------------------------------------------------- accessors

    public boolean enabled() {
        return this.properties.getKb().isEnabled();
    }

    public boolean ready() {
        return isReady();
    }

    public boolean isReady() {
        return this.snapshot != null;
    }

    public KbStatus status() {
        Snapshot current = this.snapshot;
        if (!enabled()) {
            return new KbStatus(false, false, source.kind(), "", 0, 0, null, this.lastRefreshAt, "");
        }
        if (current == null) {
            return new KbStatus(true, false, source.kind(), "", 0, 0, null, this.lastRefreshAt,
                    this.lastError);
        }
        return new KbStatus(true, true, source.kind(), current.manifest.version(),
                current.docs.size(), current.chunkCount, current.builtAt, this.lastRefreshAt,
                this.lastError);
    }

    public List<KbDocMeta> catalog() {
        Snapshot current = this.snapshot;
        return current == null ? List.of() : current.manifest.docs();
    }

    /**
     * 单篇已解析文档（诊断/增量用例用）。
     */
    public KbDoc document(String docId) {
        Snapshot current = this.snapshot;
        return current == null || docId == null ? null : current.docs.get(docId);
    }

    public int effectiveTopK(Integer requested) {
        AiProperties.Kb kb = this.properties.getKb();
        if (requested == null || requested <= 0) {
            return kb.getTopKDefault();
        }
        return Math.min(requested, kb.getTopKMax());
    }

    public int effectiveMaxChars(Integer requested) {
        AiProperties.Kb kb = this.properties.getKb();
        if (requested == null || requested <= 0) {
            return kb.getReadMaxCharsDefault();
        }
        return Math.min(requested, kb.getReadMaxCharsMax());
    }

    public String sourceKind() {
        return this.source.kind();
    }

    // ---------------------------------------------------------------- snapshot

    /**
     * 取一份可用快照（引用计数 +1，用完必须 {@link Snapshot#release()}）。
     *
     * <p>循环条件是"还有快照可试"，且<b>停机即退出</b>：{@code canAcquire()} 只在快照已退休时返回
     * false，而 {@code this.snapshot} 只会指向活的快照或 null（{@link #stop()} 先摘引用再退休）。
     * 少了这两个前提（停机后仍留着已退休快照），失败分支重读到的还是同一个 retired 快照 ⇒ 死循环。
     */
    private Snapshot acquire() {
        while (!this.stopped) {
            Snapshot current = this.snapshot;
            if (current == null) {
                return null;
            }
            if (current.canAcquire()) {
                return current;
            }
            // 读到的是刚被热重载替换掉的快照：重读一次（新快照可继续用；停机则下一轮退出）
        }
        return null;
    }

    private static boolean isSameDocuments(Snapshot old, Map<String, KbDoc> next) {
        if (!new ArrayList<>(old.docs.keySet()).equals(new ArrayList<>(next.keySet()))) {
            return false;
        }
        for (Map.Entry<String, KbDoc> entry : next.entrySet()) {
            KbDoc previous = old.docs.get(entry.getKey());
            if (previous == null || !previous.meta().equals(entry.getValue().meta())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSameSha(String left, String right) {
        return StringUtils.isNotBlank(left) && Objects.equals(left, right);
    }

    private static String nullSafe(String value) {
        return StringUtils.defaultString(value);
    }


    private static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                } catch (IOException ignored) {
                    // 关闭失败/占用时保留目录，下次刷新再清（不影响检索面）
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }

    private static void deleteRecursively(Path path, KbSource.RefreshBudget budget) throws KbSourceException {
        if (path == null) {
            return;
        }
        budget.check();
        if (!Files.exists(path)) {
            return;
        }
        budget.check();
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path item : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                budget.check();
                try {
                    Files.deleteIfExists(item);
                } catch (IOException ignored) {
                    // 单个文件系统调用不可硬中断；下一可控点再检查预算。
                }
            }
            budget.check();
        } catch (IOException ignored) {
            // 与无预算的快照清理一致：占用或删除失败留待后续收口。
        }
    }

    private record SectionMatch(KbSection section, List<String> candidates) {
    }

    private record ScoredHit(KbSearchHit hit, float score) {
    }

    private record KbTermEntry(String answer, String docId, String title, String section,
                               String updatedAt) {
    }

    /**
     * 术语表（归一化键 → 预制答案）。
     */
    private static final class Snapshot {

        private final KbManifest manifest;

        private final Map<String, KbDoc> docs;

        private final DirectoryReader reader;

        private final Directory directory;

        private final IndexSearcher searcher;

        private final Path dir;

        private final Instant builtAt;

        private final Map<String, KbTermEntry> terms;

        private final int chunkCount;

        private final AtomicInteger refs = new AtomicInteger();

        private final AtomicBoolean retired = new AtomicBoolean();

        private final AtomicBoolean closed = new AtomicBoolean();

        private Snapshot(KbManifest manifest, Map<String, KbDoc> docs, DirectoryReader reader,
                         Directory directory, Path dir, Map<String, KbTermEntry> terms) {
            this.manifest = manifest;
            this.docs = docs;
            this.reader = reader;
            this.directory = directory;
            this.dir = dir;
            this.searcher = new IndexSearcher(reader);
            this.searcher.setSimilarity(new BM25Similarity());
            this.terms = terms;
            this.chunkCount = docs.values().stream().mapToInt(doc -> doc.chunks().size()).sum();
            this.builtAt = Instant.now();
        }

        private boolean canAcquire() {
            this.refs.incrementAndGet();
            if (this.retired.get()) {
                release();
                return false;
            }
            return true;
        }

        private void release() {
            if (this.refs.decrementAndGet() == 0 && this.retired.get()) {
                close();
            }
        }

        private void retire() {
            if (this.retired.compareAndSet(false, true) && this.refs.get() == 0) {
                close();
            }
        }

        private void close() {
            if (!this.closed.compareAndSet(false, true)) {
                return;
            }
            try {
                this.reader.close();
            } catch (IOException e) {
                // 关闭失败不掩盖检索面；目录清理仍继续
            }
            try {
                this.directory.close();
            } catch (IOException e) {
                // 同上
            }
            deleteRecursively(this.dir);
        }
    }













}
