package com.example.configmgr.ai.kb;

import com.example.configmgr.ai.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Redis cache-aside 知识源（追加 3）：位于 {@link KbSource} 抽象与 {@link KbService} 之间。
 *
 * <h2>读取链（索引永远从缓存重建）</h2>
 * <ul>
 * <li><b>local</b>：读盘（manifest + 逐篇 sha）→ 变更文档写缓存 → 写清单 → {@code INCR} 版本；
 *     {@link #fetchContent} 再从缓存取正文。多实例因此看到同一份字节（即使各自挂载不同盘）；</li>
 * <li><b>http</b>：缓存命中即用；版本号与刷新权决定谁去回源（{@code SET NX PX} 单飞）；
 *     回源成功才写缓存并 {@code INCR}；其它实例下一拍轮询到版本变化，直接从缓存增量重建；
 *     远端 5xx/超时 ⇒ <b>stale-if-error</b>：继续供旧料 + WARN，<b>不清空缓存</b>。</li>
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
            this.cache.putDoc(meta.id(), new KbCache.DocEntry(content, meta.sha(), meta.sha(),
                    meta.updatedAt()));
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
                log.warn("知识源刷新失败（stale-if-error，继续供旧料）：{}", e.getMessage());
                return fromCache("缓存清单不可读");
            }
            this.lastRemoteCheck = Instant.now();
            if (remote.notModified()) {
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
                log.debug("释放知识刷新权失败：{}", e.getClass().getSimpleName());
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
                    log.debug("释放知识刷新权失败：{}", e.getClass().getSimpleName());
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
     * 把一份 manifest 同步进缓存：变更文档写入 → 写清单 → {@code INCR} 版本。
     *
     * @return true = 缓存内容确有变化（版本已递增）
     */
    private boolean syncToCache(KbManifest manifest, String sourceEtag) throws KbSourceException {
        boolean changed = false;
        for (KbDocMeta meta : manifest.docs()) {
            Optional<KbCache.DocEntry> existing = this.cache.doc(meta.id());
            boolean needFetch = existing.isEmpty() || !shaMatches(existing.get().sha(), meta.sha());
            if (needFetch) {
                String content = this.delegate.fetchContent(meta);
                this.cache.putDoc(meta.id(), new KbCache.DocEntry(content, meta.sha(), meta.sha(),
                        meta.updatedAt()));
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
        Optional<KbCache.ManifestEntry> previous = this.cache.manifest();
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
        return changed;
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
            log.warn("知识缓存不可用，降级直连知识源（单实例仍可用）：{}", e.getMessage());
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
