package com.example.configmgr.ai.kb;

import com.example.configmgr.ai.config.AiProperties;
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
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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

    /** 字段权重（标题/章节/别名高于正文：定稿要求标题域加权）。 */
    private static final Map<String, Float> BOOSTS = Map.of(
            F_TITLE, 3.0f, F_SECTION, 2.0f, F_ALIASES, 4.0f, F_ALIASES_EN, 4.0f,
            F_TAGS, 1.0f, F_BODY, 1.0f);

    /** 首屏正文长度（定稿：省略 section 时"目录 + 前 1200 字"）。 */
    static final int FIRST_SCREEN_CHARS = 1200;

    /** 一次检索内部多取的候选数（去重到文档级后仍够 top_k）。 */
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

    /** 最近一次观察到的缓存版本（版本轮询用；-1 = 尚未同步）。 */
    private volatile long lastObservedVersion = -1L;

    /**
     * 同一版本最多自动重建几次（版本轮询通道）。用完即停，把该版本交回 10m 定时刷新 ——
     * 否则"manifest 改了但建索引必失败"的故障会变成每 30s 一次全量回源 + 全量重建 + 一条 WARN。
     */
    static final int MAX_VERSION_REBUILD_ATTEMPTS = 3;

    /** 连续失败满几次再打一条 WARN（其余降到 DEBUG：排障看得到、日志不被刷）。 */
    private static final int FAILURE_WARN_EVERY = 4;

    // 下面三个字段只在 kb-refresh 单线程（pollVersionQuietly）里读写，故不加同步；
    // 与 refreshNow（管理端点线程也会调）共享的只有 lastError / consecutiveFailures。

    /** 正在退避重试的缓存版本（-1 = 没有在重试）。 */
    private long retryVersion = -1L;

    /** 该版本已被自动重试失败了几次（0 = 尚未失败）。 */
    private int retryAttempts;

    /** 下一次允许重试该版本的时刻（{@link System#nanoTime()} 基准：单调时钟，不受墙钟跳变影响）。 */
    private long retryNotBeforeNanos;

    /** 连续刷新失败次数（WARN 节流用；任一成功路径清零）。管理端点线程也会调 refreshNow，故 volatile。 */
    private volatile int consecutiveFailures;

    private ScheduledExecutorService scheduler;

    @Getter
    private volatile boolean stopped;

    public KbService(AiProperties properties, ObjectMapper mapper, KbSource source) {
        this.properties = properties;
        this.mapper = mapper;
        this.source = source;
    }

    // ---------------------------------------------------------------- lifecycle

    @PostConstruct
    public void start() {
        if (!enabled()) {
            log.info("业务知识库未启用（app.ai.kb.enabled=false）");
            return;
        }
        // 首启自播种（local 源且目录为空时复制随包样例；http 源无操作）必须在第一次回源前完成
        this.source.prepare();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "kb-refresh");
            thread.setDaemon(true);
            return thread;
        });
        // 首版索引由 refreshNow 的成功路径自己记 lastObservedVersion；失败时**不**记（下一拍重试同一版本）
        this.scheduler.submit(this::refreshQuietly);
        long interval = Math.max(1, this.properties.getKb().getRefreshInterval().toMillis());
        this.scheduler.scheduleWithFixedDelay(this::refreshQuietly, interval, interval, TimeUnit.MILLISECONDS);
        if (this.source instanceof CachedKbSource) {
            long poll = Math.max(1, this.properties.getKb().getCache().getVersionPollInterval().toMillis());
            this.scheduler.scheduleWithFixedDelay(this::pollVersionQuietly, poll, poll, TimeUnit.MILLISECONDS);
        }
        log.info("业务知识库已启动：source={} dir={} index={} refresh={}", source.kind(),
                this.properties.getKb().getDir(), this.properties.getKb().getIndexDir(),
                this.properties.getKb().getRefreshInterval());
    }

    @PreDestroy
    public void stop() {
        this.stopped = true;
        if (this.scheduler != null) {
            this.scheduler.shutdownNow();
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
        }
        catch (Exception e) {
            log.debug("关闭分析器失败：{}", e.getMessage());
        }
    }

    private void refreshQuietly() {
        try {
            refreshNow();
        }
        catch (Throwable e) {
            // doRefresh 已兜住业务失败；这里只兜"兜底逻辑自身"（绝不让定时线程死亡）
            log.warn("知识库定时刷新异常（保留旧索引）：{}", safeMessage(e));
        }
    }

    /**
     * 版本号轮询（追加 3）：其它实例 {@code INCR kb:cache:manifest:version} 后，本实例在
     * 一个轮询周期内发现并触发"从缓存增量重建"，不需要等 10m 的定时刷新。
     *
     * <p><b>只把重建成功的版本记为已见</b>：{@code refreshNow} 在成功路径上落
     * {@code lastObservedVersion}；失败时保持旧值，同一版本还能重试（否则一次磁盘/Lucene
     * 失败会让这个版本永久沉默，最长要等 10m 定时刷新）。
     *
     * <p><b>但重试是有界的</b>：同一版本最多自动重建 {@value #MAX_VERSION_REBUILD_ATTEMPTS} 次，
     * 且失败后退避（{@code poll × 次数}，封顶定时刷新间隔）。用完即静默交回 10m 定时刷新，
     * 换新版本（{@code INCR} 之后）又能立刻重试 —— 既不会"一次失败后永久沉默"，也不会
     * "每 30s 一次全量回源 + 全量重建 + 一条 WARN"。
     */
    private void pollVersionQuietly() {
        if (!(this.source instanceof CachedKbSource cached)) {
            return;
        }
        long version = cached.cachedVersion();
        if (version < 0 || version == this.lastObservedVersion) {
            return;
        }
        if (version != this.retryVersion) {
            // 新版本：重置重试预算（旧版本的连续失败不牵连新版本）
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
        if (this.lastObservedVersion == version) {
            // 成功（重建或"整份未变"短路都会记版本）：预算清零，本版本不再因轮询而重建
            this.retryVersion = -1L;
            this.retryAttempts = 0;
            this.retryNotBeforeNanos = 0L;
            return;
        }
        this.retryAttempts++;
        this.retryNotBeforeNanos = System.nanoTime() + backoffMillis(this.retryAttempts) * 1_000_000L;
        if (this.retryAttempts >= MAX_VERSION_REBUILD_ATTEMPTS) {
            log.warn("知识库版本 {} 连续 {} 次重建失败，暂停自动重试（保留旧索引；改由 {} 定时刷新或新版本触发）：{}",
                    version, this.retryAttempts, this.properties.getKb().getRefreshInterval(),
                    this.lastError);
        }
    }

    /** 第 {@code attempt} 次失败后的退避：{@code poll × attempt}，封顶定时刷新间隔（都用既有配置口径）。 */
    private long backoffMillis(int attempt) {
        long poll = Math.max(1L, this.properties.getKb().getCache().getVersionPollInterval().toMillis());
        long cap = Math.max(poll, this.properties.getKb().getRefreshInterval().toMillis());
        return Math.min(cap, poll * Math.max(1, attempt));
    }

    // ---------------------------------------------------------------- refresh

    /**
     * 同步刷新（管理端点/定时任务共用）。
     *
     * @return true = 本次真的重建了索引；false = 未变化或失败（失败保留旧索引，原因见 {@link #status()})
     */
    public synchronized boolean refreshNow() {
        return refreshNow(false);
    }

    /**
     * 同步刷新。
     *
     * @param force true = 手动热重载：跳过"距上次回源不足刷新间隔"的节流（HTTP 源仍走 ETag/单飞）
     */
    public synchronized boolean refreshNow(boolean force) {
        this.lastRefreshAt = Instant.now();
        if (!enabled()) {
            return false;
        }
        Path buildDir = null;
        try {
            KbSource.ManifestFetch fetched = this.source.fetchManifest(this.lastEtag, force);
            if (fetched.notModified()) {
                if (fetched.etag() != null) {
                    this.lastEtag = fetched.etag();
                }
                this.consecutiveFailures = 0;
                this.lastError = "";
                return false;
            }
            KbManifest manifest = fetched.manifest();
            if (manifest == null) {
                throw new KbSourceException("知识源未返回 manifest");
            }
            manifest.validate();

            Snapshot old = this.snapshot;
            Map<String, KbDoc> nextDocs = new LinkedHashMap<>();
            for (KbDocMeta meta : manifest.docs()) {
                KbDoc previous = old == null ? null : old.docs.get(meta.id());
                if (previous != null && sameSha(previous.meta().sha(), meta.sha())) {
                    // 正文未变：元数据也没变就原样复用（实例级复用，切块/章节引用不变）；
                    // 元数据变了（标题/别名/摘要）才换壳，切块与章节仍复用
                    nextDocs.put(meta.id(), previous.meta().equals(meta) ? previous : previous.withMeta(meta));
                    continue;
                }
                String content = MarkdownChunker.normalize(this.source.fetchContent(meta));
                String actualSha = KbHashing.sha256Hex(content);
                if (meta.sha() != null && !meta.sha().isBlank() && !meta.sha().equalsIgnoreCase(actualSha)) {
                    log.warn("知识文档内容 sha 与 manifest 声明不一致（以 manifest 声明做增量判据）：doc={}",
                            meta.id());
                }
                List<KbSection> sections = MarkdownChunker.sections(content, meta.title());
                List<KbChunk> chunks = MarkdownChunker.chunk(meta.id(), meta.title(), content,
                        this.properties.getKb().getChunkMinChars(), this.properties.getKb().getChunkMaxChars());
                nextDocs.put(meta.id(), new KbDoc(meta, content, sections, chunks));
            }

            if (old != null && sameDocuments(old, nextDocs)
                    && Objects.equals(old.manifest.version(), manifest.version())) {
                // 文档集合/元数据/版本都没变：不重建索引（本地源每次都会重算 sha，这里做整份短路）
                this.lastEtag = fetched.etag();
                this.consecutiveFailures = 0;
                this.lastError = "";
                if (this.source instanceof CachedKbSource cached) {
                    this.lastObservedVersion = cached.cachedVersion();
                }
                return false;
            }

            buildDir = newBuildDir();
            Snapshot built = buildSnapshot(manifest, nextDocs, buildDir);
            Snapshot previous = this.snapshot;
            this.snapshot = built;
            this.lastEtag = fetched.etag();
            this.consecutiveFailures = 0;
            this.lastError = "";
            if (this.source instanceof CachedKbSource cached) {
                this.lastObservedVersion = cached.cachedVersion();
            }
            if (previous != null) {
                previous.retire();
            }
            log.info("业务知识库索引已重建：docs={} chunks={} source={} dir={}", nextDocs.size(),
                    built.chunkCount, source.kind(), buildDir.getFileName());
            return true;
        }
        catch (Exception e) {
            this.lastError = safeMessage(e);
            this.consecutiveFailures++;
            // WARN 节流：第一条（1）与之后每 FAILURE_WARN_EVERY 条（5/9…）留 WARN，其余降 DEBUG ——
            // 排障看得到，日志也不会被 30s 的重试节奏刷屏；lastError/status 始终是最新原因。
            if ((this.consecutiveFailures - 1) % FAILURE_WARN_EVERY == 0) {
                log.warn("知识库刷新失败（保留旧索引，不变更检索面，连续第 {} 次）：{}",
                        this.consecutiveFailures, this.lastError);
            }
            else {
                log.debug("知识库刷新仍失败（保留旧索引，连续第 {} 次）：{}", this.consecutiveFailures,
                        this.lastError);
            }
            if (buildDir != null) {
                deleteRecursively(buildDir);
            }
            return false;
        }
    }

    private Path newBuildDir() throws IOException {
        Path indexDir = Path.of(this.properties.getKb().getIndexDir()).toAbsolutePath().normalize();
        Files.createDirectories(indexDir);
        cleanupStaleBuildDirs(indexDir);
        return Files.createDirectories(indexDir.resolve("build-" + System.nanoTime()));
    }

    private void cleanupStaleBuildDirs(Path indexDir) {
        Path live = this.snapshot == null ? null : this.snapshot.dir;
        try (Stream<Path> children = Files.list(indexDir)) {
            children.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("build-"))
                    .filter(path -> live == null || !path.equals(live))
                    .forEach(KbService::deleteRecursively);
        }
        catch (IOException e) {
            log.debug("清理知识库旧构建目录失败：{}", e.getMessage());
        }
    }

    private Snapshot buildSnapshot(KbManifest manifest, Map<String, KbDoc> docs, Path buildDir)
            throws Exception {
        Directory directory = FSDirectory.open(buildDir);
        DirectoryReader reader = null;
        try {
            IndexWriterConfig config = new IndexWriterConfig(this.analyzer);
            config.setSimilarity(new BM25Similarity());
            config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
            try (IndexWriter writer = new IndexWriter(directory, config)) {
                for (KbDoc doc : docs.values()) {
                    String aliases = String.join(" ", doc.meta().aliases());
                    String aliasesEn = String.join(" ", doc.meta().aliasesEn());
                    String tags = String.join(" ", doc.meta().tags());
                    for (KbChunk chunk : doc.chunks()) {
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
                writer.commit();
            }
            reader = DirectoryReader.open(directory);
            Map<String, KbTermEntry> terms = buildTermIndex(manifest, docs);
            return new Snapshot(manifest, docs, reader, directory, buildDir, terms);
        }
        catch (Exception e) {
            if (reader != null) {
                try {
                    reader.close();
                }
                catch (IOException ignored) {
                    // 构建失败路径：清理优先
                }
            }
            try {
                directory.close();
            }
            catch (IOException ignored) {
                // 同上
            }
            throw e;
        }
    }

    private Map<String, KbTermEntry> buildTermIndex(KbManifest manifest, Map<String, KbDoc> docs) {
        Map<String, KbTermEntry> index = new LinkedHashMap<>();
        for (KbDocMeta meta : manifest.docs()) {
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
        String trimmed = query == null ? "" : query.strip();
        if (trimmed.isEmpty()) {
            return KbSearchResult.invalid("检索词不能为空：请给出要查的业务术语、章节名或一句话问题");
        }
        Snapshot acquired = acquire();
        if (acquired == null) {
            return KbSearchResult.notReady();
        }
        try {
            List<String> terms = analyzeTerms(trimmed);
            if (terms.isEmpty() || terms.stream().allMatch(String::isBlank)) {
                return KbSearchResult.invalid("检索词没有可检索的内容（只含标点或停用词），请补充业务关键词");
            }
            int limit = effectiveTopK(topK);
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
            if (ranked.isEmpty()) {
                return KbSearchResult.noMatch(terms, suggestTitles(trimmed));
            }
            List<KbSearchHit> hits = new ArrayList<>(ranked.size());
            boolean lowConfidence = ranked.size() <= 2;
            float highThreshold = topScore * (float) this.properties.getKb().getMatchHighRatio();
            float mediumThreshold = topScore * (float) this.properties.getKb().getMatchMediumRatio();
            for (ScoredHit scored : ranked) {
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
        catch (Exception e) {
            return KbSearchResult.error(safeMessage(e));
        }
        finally {
            acquired.release();
        }
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
                Query clause = new TermQuery(new org.apache.lucene.index.Term(field.getKey(), term));
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
        return new ArrayList<>(terms);
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

    /** 字面二元组重合度（0–1）；只对标题计算，用于"近似标题"排序，不参与命中判定。 */
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
        if (docId == null || docId.isBlank()) {
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
            if (section != null && !section.isBlank()) {
                SectionMatch match = resolveSection(doc, section);
                if (match.section == null) {
                    return KbReadResult.sectionNotFound(docId, doc.meta().title(), section,
                            match.candidates);
                }
                target = match.section;
            }
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
        catch (Exception e) {
            return KbReadResult.error(safeMessage(e));
        }
        finally {
            acquired.release();
        }
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
            return new SectionMatch(suffixMatches.get(0), List.of());
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
            return KbLookupResult.error(nullSafe(term), normalized, "术语不能为空：请给出要查的术语或口头叫法");
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
        }
        catch (Exception e) {
            return KbLookupResult.error(nullSafe(term), normalized, safeMessage(e));
        }
        finally {
            acquired.release();
        }
    }

    // ---------------------------------------------------------------- accessors

    public boolean enabled() {
        return this.properties.getKb().isEnabled();
    }

    public boolean ready() {
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

    /** 单篇已解析文档（诊断/增量用例用）。 */
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
     * <p>循环条件是"还有快照可试"，且<b>停机即退出</b>：{@code tryAcquire()} 只在快照已退休时返回
     * false，而 {@code this.snapshot} 只会指向活的快照或 null（{@link #stop()} 先摘引用再退休）。
     * 少了这两个前提（停机后仍留着已退休快照），失败分支重读到的还是同一个 retired 快照 ⇒ 死循环。
     */
    private Snapshot acquire() {
        while (!this.stopped) {
            Snapshot current = this.snapshot;
            if (current == null) {
                return null;
            }
            if (current.tryAcquire()) {
                return current;
            }
            // 读到的是刚被热重载替换掉的快照：重读一次（新快照可继续用；停机则下一轮退出）
        }
        return null;
    }

    private static boolean sameDocuments(Snapshot old, Map<String, KbDoc> next) {
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

    private static boolean sameSha(String left, String right) {
        return left != null && !left.isBlank() && Objects.equals(left, right);
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = error.getClass().getSimpleName();
        }
        // 不允许堆栈/内部对象串进入模型可见面；长度收敛到一行
        return message.length() > 300 ? message.substring(0, 300) + "…" : message;
    }

    private static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                }
                catch (IOException ignored) {
                    // 关闭失败/占用时保留目录，下次刷新再清（不影响检索面）
                }
            });
        }
        catch (IOException ignored) {
            // 同上
        }
    }

    private record SectionMatch(KbSection section, List<String> candidates) {
    }

    private record ScoredHit(KbSearchHit hit, float score) {
    }

    private record KbTermEntry(String answer, String docId, String title, String section,
            String updatedAt) {
    }

    /** 术语表（归一化键 → 预制答案）。 */
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

        private boolean tryAcquire() {
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
            }
            catch (IOException e) {
                // 关闭失败不掩盖检索面；目录清理仍继续
            }
            try {
                this.directory.close();
            }
            catch (IOException e) {
                // 同上
            }
            deleteRecursively(this.dir);
        }
    }
}
