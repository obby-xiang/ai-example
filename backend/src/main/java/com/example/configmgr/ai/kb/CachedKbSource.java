package com.example.configmgr.ai.kb;

import com.example.configmgr.ai.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Redis cache-aside 知识源（追加 3）：位于 {@link KbSource} 抽象与 {@link KbService} 之间。
 *
 * <h2>读取链（索引永远从缓存重建）</h2>
 * <ul>
 * <li><b>local</b>：读盘（manifest + 逐篇 sha）→ 先把文档 id 记进索引 → 变更文档写缓存 → 写清单 →
 *     {@code INCR} 版本 → 按索引差集回收下线文档；{@link #fetchContent} 再从缓存取正文。
 *     多实例因此看到同一份字节（即使各自挂载不同盘）；</li>
 * <li><b>http</b>：缓存命中即用；版本号与刷新权决定谁去回源（{@code SET NX PX} 单飞）；
 *     回源成功才写缓存并 {@code INCR}；其它实例下一拍轮询到版本变化，直接从缓存增量重建；
 *     远端 5xx/超时 ⇒ <b>stale-if-error</b>：继续供旧料 + WARN，<b>不清空缓存</b>；
 *     远端 304 ⇒ 清单不变，但仍按索引补做下线回收（残留不依赖"远端再改一次"才收敛）。</li>
 * </ul>
 *
 * <h2>失败语义</h2>
 * <ul>
 * <li>远端不可用且缓存为空 ⇒ 抛 {@link KbSourceException}（没有可供的旧料，fail-open 无对象）；</li>
 * <li>远端不可用但缓存非空 ⇒ 返回缓存（{@code notModified=false}，由 KbService 比对后 no-op）；</li>
 * <li>Redis 不可用 ⇒ 降级直连 delegate + WARN：单实例知识问答照常，只是失去跨实例共享。</li>
 * </ul>
 *
 * <p><b>安全</b>：缓存只存业务知识原文（非对话数据）；鉴权头由 {@code HttpKbSource} 从环境变量读取，
 * 本层不接触、不落缓存、不进日志。定界与 8000 字符闸仍在工具渲染层（{@code KbText}）。
 */
@Slf4j
public class CachedKbSource implements KbSource {

    private final KbSource delegate;

    private final KbCache cache;

    private final ObjectMapper mapper;

    private final AiProperties.Kb kb;

    /** 本实例已见过的版本号（-1 = 尚未同步）。 */
    private volatile long lastSeenVersion = -1L;

    /** 上次真正回源 HTTP 的时刻（local 源不走这个节拍）。 */
    private volatile Instant lastRemoteCheck = Instant.EPOCH;

    private volatile boolean cacheDownLogged;

    public CachedKbSource(KbSource delegate, KbCache cache, ObjectMapper mapper, AiProperties.Kb kb) {
        this.delegate = delegate;
        this.cache = cache;
        this.mapper = mapper;
        this.kb = kb;
    }

    @Override
    public String kind() {
        return this.delegate.kind();
    }

    /** 首启自播种属于源准备：有缓存层时同样要在第一次回源前完成。 */
    @Override
    public void prepare() {
        this.delegate.prepare();
    }

    /** 当前缓存版本（诊断/轮询用；缓存不可用返回 -1）。 */
    public long cachedVersion() {
        try {
            return this.cache.version();
        }
        catch (RuntimeException e) {
            noteCacheDown(e);
            return -1L;
        }
    }

    @Override
    public KbSource.ManifestFetch fetchManifest(String previousEtag) throws KbSourceException {
        return fetchManifest(previousEtag, false);
    }

    @Override
    public KbSource.ManifestFetch fetchManifest(String previousEtag, boolean force) throws KbSourceException {
        try {
            return "local".equalsIgnoreCase(this.delegate.kind())
                    ? syncLocal(previousEtag, force)
                    : refreshHttp(previousEtag, force);
        }
        catch (KbCacheException e) {
            noteCacheDown(e);
            return this.delegate.fetchManifest(previousEtag);
        }
    }

    @Override
    public String fetchContent(KbDocMeta meta) throws KbSourceException {
        try {
            Optional<KbCache.DocEntry> cached = this.cache.doc(meta.id());
            if (cached.isPresent() && shaMatches(cached.get().sha(), meta.sha())) {
                return cached.get().content();
            }
            String content = this.delegate.fetchContent(meta);
            cacheDoc(meta, content);
            return content;
        }
        catch (KbCacheException e) {
            noteCacheDown(e);
            return this.delegate.fetchContent(meta);
        }
    }

    // ---------------------------------------------------------------- local

    /**
     * local 源：每次刷新读盘（磁盘是权威），变更写缓存；索引侧再从缓存取正文。
     * 这样"本地文件改了"能被发现，同时多实例共享同一份缓存字节。
     */
    private KbSource.ManifestFetch syncLocal(String previousEtag, boolean force) throws KbSourceException {
        KbSource.ManifestFetch local = this.delegate.fetchManifest(previousEtag, force);
        if (local.notModified() || local.manifest() == null) {
            return local;
        }
        syncToCache(local.manifest(), local.etag());
        return fromCache("local 缓存未写入");
    }

    // ---------------------------------------------------------------- http

    private KbSource.ManifestFetch refreshHttp(String previousEtag, boolean force) throws KbSourceException {
        Optional<KbCache.ManifestEntry> cached = this.cache.manifest();
        if (cached.isEmpty()) {
            return coldStartHttp(previousEtag);
        }
        long version = this.cache.version();
        if (version != this.lastSeenVersion) {
            // 别人刷新过：直接从缓存重建（不回源）
            this.lastSeenVersion = version;
            return fromCache("版本已变化但清单不可读");
        }
        if (!force && Duration.between(this.lastRemoteCheck, Instant.now())
                .compareTo(jitteredRefreshInterval()) < 0) {
            return fromCache("缓存清单不可读");
        }
        String token = UUID.randomUUID().toString();
        if (!this.cache.tryLock(token, this.kb.getCache().getLockTtl())) {
            // 单飞：别的实例正在回源，本实例先用缓存（下一拍版本号会告诉我结果）
            return fromCache("缓存清单不可读");
        }
        try {
            KbSource.ManifestFetch remote;
            try {
                remote = this.delegate.fetchManifest(previousEtag, true);
            }
            catch (KbSourceException e) {
                // stale-if-error：远端失败不清空缓存，继续供旧料
                log.warn("KB source refresh failed (stale-if-error; serving stale cache): {}", e.getMessage());
                return fromCache("缓存清单不可读");
            }
            this.lastRemoteCheck = Instant.now();
            if (remote.notModified()) {
                // 远端整份未变也要推进下线回收：上一轮删除失败/崩溃的残留不能等"远端再改一次"才收敛
                reconcileCachedRemoval();
                return fromCache("缓存清单不可读");
            }
            syncToCache(remote.manifest(), remote.etag());
            return fromCache("缓存清单不可读");
        }
        finally {
            try {
                this.cache.unlock(token);
            }
            catch (RuntimeException e) {
                // 锁按 TTL 自行过期；这里只记一笔，不影响返回
                log.debug("Failed to release KB refresh token: {}", e.getClass().getSimpleName());
            }
        }
    }

    private KbSource.ManifestFetch coldStartHttp(String previousEtag) throws KbSourceException {
        String token = UUID.randomUUID().toString();
        if (this.cache.tryLock(token, this.kb.getCache().getLockTtl())) {
            try {
                KbSource.ManifestFetch remote;
                try {
                    remote = this.delegate.fetchManifest(previousEtag, true);
                }
                catch (KbSourceException e) {
                    throw new KbSourceException("知识缓存为空且远端不可用：" + e.getMessage(), e);
                }
                if (remote.notModified() || remote.manifest() == null) {
                    throw new KbSourceException("知识缓存为空但远端返回未修改，无法重建");
                }
                this.lastRemoteCheck = Instant.now();
                syncToCache(remote.manifest(), remote.etag());
                return fromCache("缓存清单写入失败");
            }
            finally {
                try {
                    this.cache.unlock(token);
                }
                catch (RuntimeException e) {
                    log.debug("Failed to release KB refresh token: {}", e.getClass().getSimpleName());
                }
            }
        }
        // 其它实例正在冷启动刷新：短暂等待缓存出现（锁窗口 30s，本地等待给 3s）
        for (int i = 0; i < 30; i++) {
            Optional<KbCache.ManifestEntry> entry = this.cache.manifest();
            if (entry.isPresent()) {
                this.lastSeenVersion = this.cache.version();
                return fromCache("缓存清单不可读");
            }
            sleep(100L);
        }
        throw new KbSourceException("知识缓存为空且其它实例正在刷新，请稍后重试");
    }

    // ---------------------------------------------------------------- cache sync

    /**
     * 写单篇正文的<b>唯一入口</b>：先把文档 id 记进全局索引，再写正文键。
     *
     * <p>正文键无 TTL，只有索引能把它找回来删除。因此不允许出现"正文已写、索引还没记"的中间态：
     * 两步之间进程被杀只可能留下"索引有、正文无"，下一轮要么按清单补写正文，要么按差集删空键后
     * 摘索引 —— 两种都能收敛。读路径（{@link #fetchContent} 的惰性补写）与刷新路径共用本方法，
     * 不再各自直接 {@code putDoc}。
     */
    private void cacheDoc(KbDocMeta meta, String content) {
        this.cache.putDocId(meta.id());
        this.cache.putDoc(meta.id(), new KbCache.DocEntry(content, meta.sha(), meta.sha(), meta.updatedAt()));
    }

    /**
     * 把一份 manifest 同步进缓存：读索引 → 已知 id 全量登记进索引 → 变更文档写入（每篇仍先索引后正文）
     * → 写清单 → {@code INCR} 版本 → 按索引差集回收下线文档。
     *
     * <p><b>顺序不变式</b>：
     * <ol>
     * <li>文档 id 在<b>写正文之前</b>进索引 —— "正文有 ⇒ 索引有"：正文键无 TTL，只有索引能把它
     *     找回来删；反过来"索引有、正文无"无害（下一轮补写正文或删空键后摘索引）；</li>
     * <li>清单在<b>索引与正文之后</b>落地 —— 读侧不会拿到"清单已指向、正文还没写"的文档；</li>
     * <li>回收发生在<b>清单落地之后</b> —— 读侧不再引用下线文档，避免"删了正文、旧清单还在供索引"；</li>
     * <li>回收成功才摘索引 —— 删除失败/进程崩溃都留痕，下一轮按索引与当前 manifest 的差集重试。</li>
     * </ol>
     *
     * @return true = 缓存内容确有变化（版本已递增）
     */
    private boolean syncToCache(KbManifest manifest, String sourceEtag) throws KbSourceException {
        boolean changed = false;
        // 上一版清单与全局索引都必须在写新清单前读：前者用于变更判定（也在首次升级时补偿索引），
        // 后者是"已知文档 id"的主来源。
        Optional<KbCache.ManifestEntry> previous = this.cache.manifest();
        Set<String> current = manifest.docs().stream()
                .map(KbDocMeta::id)
                .collect(Collectors.toUnmodifiableSet());
        Set<String> indexed = new LinkedHashSet<>(this.cache.docIds());
        Set<String> known = new LinkedHashSet<>(indexed);
        // 首次升级补偿：索引出现之前写下的正文只在上一版清单里留痕
        known.addAll(docIdsOf(previous));
        known.addAll(current);
        // 索引先于任何正文落盘：已知 id（上一版清单补偿 + 本次清单）全部登记。
        // 失败会抛出 ⇒ 正文/清单都不写 ⇒ 下一轮重来，不留下无法回收的正文键。
        Set<String> toIndex = new LinkedHashSet<>(known);
        toIndex.removeAll(indexed);
        this.cache.putDocIds(toIndex);
        for (KbDocMeta meta : manifest.docs()) {
            Optional<KbCache.DocEntry> existing = this.cache.doc(meta.id());
            boolean needFetch = existing.isEmpty() || !shaMatches(existing.get().sha(), meta.sha());
            if (needFetch) {
                String content = this.delegate.fetchContent(meta);
                cacheDoc(meta, content);
                changed = true;
            }
        }
        String json;
        try {
            json = this.mapper.writeValueAsString(manifest);
        }
        catch (Exception e) {
            throw new KbSourceException("知识清单序列化失败：" + e.getMessage(), e);
        }
        String etag = sourceEtag != null && !sourceEtag.isBlank()
                ? sourceEtag : KbHashing.sha256Hex(json);
        if (previous.isEmpty() || !json.equals(previous.get().json())
                || !etag.equals(previous.get().etag())) {
            long next = this.cache.version() + 1;
            this.cache.putManifest(new KbCache.ManifestEntry(etag, String.valueOf(next), json));
            long bumped = this.cache.bumpVersion();
            this.lastSeenVersion = bumped;
            changed = true;
        }
        else if (this.lastSeenVersion < 0) {
            this.lastSeenVersion = this.cache.version();
        }
        pruneRemovedDocs(known, current);
        return changed;
    }

    /** 清单里的文档 id（解析失败 = 空集：宁可不删，也不按坏数据误删）。 */
    private Set<String> docIdsOf(Optional<KbCache.ManifestEntry> entry) {
        if (entry.isEmpty()) {
            return Set.of();
        }
        try {
            return KbManifest.parse(entry.get().json().getBytes(StandardCharsets.UTF_8), this.mapper)
                    .docs().stream()
                    .map(KbDocMeta::id)
                    .collect(Collectors.toUnmodifiableSet());
        }
        catch (Exception e) {
            log.warn("Failed to parse previous KB manifest; this round relies on the global doc index only for retirements: {}", e.getMessage());
            return Set.of();
        }
    }

    /**
     * 远端返回 304（清单未变）时的下线回收补做：用缓存里的清单与索引做差集重试。
     *
     * <p>没有这一步，"删除失败/崩溃"的残留要等远端下次真的改动才收敛；而 HTTP 源常态就是 304。
     * 这里只做回收（不写文档、不写清单、不动版本号），失败一律只记 WARN —— 不能把一次成功的
     * 轮询变成降级，残留留到下一轮继续重试。
     */
    private void reconcileCachedRemoval() {
        try {
            Optional<KbCache.ManifestEntry> cached = this.cache.manifest();
            if (cached.isEmpty()) {
                return;
            }
            Set<String> current;
            try {
                current = KbManifest.parse(cached.get().json().getBytes(StandardCharsets.UTF_8), this.mapper)
                        .docs().stream().map(KbDocMeta::id).collect(Collectors.toUnmodifiableSet());
            }
            catch (Exception e) {
                log.warn("Failed to parse cached KB manifest; skipping retirement reconcile this round: {}", e.getMessage());
                return;
            }
            Set<String> indexed = new LinkedHashSet<>(this.cache.docIds());
            Set<String> toIndex = new LinkedHashSet<>(current);
            toIndex.removeAll(indexed);
            this.cache.putDocIds(toIndex);
            Set<String> known = new LinkedHashSet<>(indexed);
            known.addAll(current);
            pruneRemovedDocs(known, current);
        }
        catch (RuntimeException e) {
            log.debug("KB cache unavailable; skipping retirement reconcile this round (retried next round): {}", e.getMessage());
        }
    }

    /**
     * 回收已从清单下线的文档键（R-2）：差集来自全局索引 {@code kb:cache:docs}，不是"上一版清单"。
     *
     * <p><b>为什么要索引</b>：上一版清单只存在于"上一次成功刷新"里；一旦删除失败或进程在
     * 写清单与删键之间崩溃，那份差集就永久丢失，正文键（无 TTL）无人再删。索引是显式留痕：
     * 删除成功才摘除条目，失败/崩溃时条目仍在，下一轮 sync 自然重试（DEL 幂等）。
     *
     * <p><b>Cluster 安全</b>：逐篇单键 DEL，索引侧是单键 HDEL（{@code KbCache#removeDocId}），
     * 不使用 {@code KEYS}/{@code SCAN}，也不做跨槽多键 Lua。
     *
     * <p>失败只记 WARN、不抛：缓存缺一篇正文时 {@link #fetchContent} 会回源补齐，
     * 不能让"回收旧键"把整次刷新打成降级。
     */
    private void pruneRemovedDocs(Set<String> known, Set<String> current) {
        if (known.isEmpty()) {
            return;
        }
        for (String docId : known) {
            if (current.contains(docId)) {
                continue;
            }
            try {
                this.cache.deleteDoc(docId);
            }
            catch (RuntimeException e) {
                // 索引条目保留 ⇒ 下一轮重试，收敛到删除成功为止
                log.warn("KB cache failed to delete retired doc (index entry kept; retried next round) docId={}: {}", docId, e.getMessage());
                continue;
            }
            try {
                this.cache.removeDocId(docId);
                log.debug("KB cache deleted retired doc: docId={}", docId);
            }
            catch (RuntimeException e) {
                // 正文已删、索引未摘：下一轮会再 DEL（幂等空操作）并重试摘索引
                log.warn("Retired doc deleted but its index entry remains (retried next round) docId={}: {}", docId, e.getMessage());
            }
        }
    }

    private KbSource.ManifestFetch fromCache(String emptyMessage) throws KbSourceException {
        long version = this.cache.version();
        KbCache.ManifestEntry entry = null;
        // 写窗容错：版本已增但清单还是上一版时短暂重试（doc → manifest → INCR 的可见性差）
        for (int i = 0; i < 6; i++) {
            entry = this.cache.manifest().orElse(null);
            if (entry != null && versionOf(entry) >= version) {
                break;
            }
            if (entry == null && i == 0) {
                break;
            }
            sleep(25L);
        }
        if (entry == null) {
            throw new KbSourceException(emptyMessage);
        }
        KbManifest manifest;
        try {
            manifest = KbManifest.parse(entry.json().getBytes(StandardCharsets.UTF_8), this.mapper);
        }
        catch (KbSourceException e) {
            throw new KbSourceException("缓存清单解析失败：" + e.getMessage(), e);
        }
        manifest.validate();
        return KbSource.ManifestFetch.modified(manifest, entry.etag());
    }

    private static long versionOf(KbCache.ManifestEntry entry) {
        try {
            return entry.version() == null || entry.version().isBlank()
                    ? 0L : Long.parseLong(entry.version().strip());
        }
        catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static boolean shaMatches(String cachedSha, String manifestSha) {
        if (manifestSha == null || manifestSha.isBlank()) {
            // 无声明 sha 时只认"缓存有内容"，避免每轮都回源
            return cachedSha != null && !cachedSha.isBlank();
        }
        return cachedSha != null && cachedSha.equalsIgnoreCase(manifestSha);
    }

    private Duration jitteredRefreshInterval() {
        long base = Math.max(1L, this.kb.getRefreshInterval().toMillis());
        double factor = 0.9 + ThreadLocalRandom.current().nextDouble() * 0.2;
        return Duration.ofMillis((long) (base * factor));
    }

    private void noteCacheDown(RuntimeException e) {
        if (!this.cacheDownLogged) {
            this.cacheDownLogged = true;
            log.warn("KB cache unavailable; degrading to direct source access (single instance still works): {}", e.getMessage());
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 诊断：缓存里的文档 id 集合（用例/排障用）。 */
    public Set<String> cachedDocIds() {
        return this.cache.manifest().map(entry -> {
            try {
                return KbManifest.parse(entry.json().getBytes(StandardCharsets.UTF_8), this.mapper)
                        .docs().stream().map(KbDocMeta::id).collect(java.util.stream.Collectors.toSet());
            }
            catch (Exception e) {
                return Set.<String>of();
            }
        }).orElse(Set.of());
    }

    /** 单篇缓存条目（队列/排障用）。 */
    public Optional<Map<String, String>> rawDoc(String docId) {
        return this.cache.doc(docId).map(doc -> Map.of(
                "content", doc.content(), "sha", nullSafe(doc.sha()), "updatedAt", nullSafe(doc.updatedAt())));
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
