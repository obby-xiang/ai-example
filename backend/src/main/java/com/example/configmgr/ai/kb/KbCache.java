package com.example.configmgr.ai.kb;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 知识缓存的门面（追加 3）：键族、字段名、版本与刷新权都在这里收口（手写 cache-aside）。
 *
 * <h2>键族（会话无关的全局知识域）</h2>
 * <ul>
 * <li>{@code kb:cache:doc:<id>}（HASH：{@code content/etag/sha/updatedAt}）—— 单篇正文；</li>
 * <li>{@code kb:cache:manifest}（HASH：{@code etag/version/json}）—— 清单原文与版本；</li>
 * <li>{@code kb:cache:manifest:version}（INCR）—— 跨实例传播"知识已更新"；</li>
 * <li>{@code kb:cache:refresh:lock}（{@code SET NX PX 30000}）—— 刷新权（单飞）。</li>
 * </ul>
 *
 * <p><b>TTL</b>：文档与清单键<b>不设 TTL</b>（定稿：刷新权属显式刷新，版本号兜底；过期会让缓存
 * 无声消失，冷启动重新拉远端，反而把"远端故障"放大成"知识空白"）。只有锁键有 30s TTL。
 *
 * <p><b>版本写入顺序</b>：先写文档 → 写清单 → {@code INCR} 版本。读侧发现版本变化后若清单里的
 * {@code version} 仍落后（写窗），{@code CachedKbSource} 会短暂重试，避免"看到新版本却读到旧清单"。
 */
public class KbCache {

    public static final String DOC_PREFIX = "kb:cache:doc:";

    public static final String MANIFEST_KEY = "kb:cache:manifest";

    public static final String VERSION_KEY = "kb:cache:manifest:version";

    public static final String LOCK_KEY = "kb:cache:refresh:lock";

    static final String F_CONTENT = "content";

    static final String F_ETAG = "etag";

    static final String F_SHA = "sha";

    static final String F_UPDATED = "updatedAt";

    static final String F_VERSION = "version";

    static final String F_JSON = "json";

    private final KbCacheStore store;

    public KbCache(KbCacheStore store) {
        this.store = store;
    }

    public static String docKey(String docId) {
        return DOC_PREFIX + docId;
    }

    /** 单篇缓存条目。 */
    public record DocEntry(String content, String etag, String sha, String updatedAt) {
    }

    /** 清单缓存条目（{@code json} = manifest 原文，供各实例零回源重建）。 */
    public record ManifestEntry(String etag, String version, String json) {
    }

    public Optional<DocEntry> doc(String docId) {
        Map<String, String> hash = this.store.hashGetAll(docKey(docId));
        if (hash.isEmpty() || !hash.containsKey(F_CONTENT)) {
            return Optional.empty();
        }
        return Optional.of(new DocEntry(hash.get(F_CONTENT), hash.get(F_ETAG),
                hash.get(F_SHA), hash.get(F_UPDATED)));
    }

    public void putDoc(String docId, DocEntry entry) {
        Map<String, String> hash = new LinkedHashMap<>();
        hash.put(F_CONTENT, entry.content());
        hash.put(F_ETAG, nullSafe(entry.etag()));
        hash.put(F_SHA, nullSafe(entry.sha()));
        hash.put(F_UPDATED, nullSafe(entry.updatedAt()));
        this.store.hashPutAll(docKey(docId), hash);
    }

    public Optional<ManifestEntry> manifest() {
        Map<String, String> hash = this.store.hashGetAll(MANIFEST_KEY);
        if (hash.isEmpty() || !hash.containsKey(F_JSON)) {
            return Optional.empty();
        }
        return Optional.of(new ManifestEntry(hash.get(F_ETAG), hash.get(F_VERSION), hash.get(F_JSON)));
    }

    public void putManifest(ManifestEntry entry) {
        Map<String, String> hash = new LinkedHashMap<>();
        hash.put(F_ETAG, nullSafe(entry.etag()));
        hash.put(F_VERSION, nullSafe(entry.version()));
        hash.put(F_JSON, entry.json());
        this.store.hashPutAll(MANIFEST_KEY, hash);
    }

    /** 当前版本号（键不存在 = 0）。 */
    public long version() {
        String raw = this.store.get(VERSION_KEY);
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(raw.strip());
        }
        catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** {@code INCR kb:cache:manifest:version}，返回新版本。 */
    public long bumpVersion() {
        return this.store.incr(VERSION_KEY);
    }

    /** 抢刷新权（{@code SET NX PX}）。 */
    public boolean tryLock(String token, Duration ttl) {
        return this.store.setIfAbsent(LOCK_KEY, token, ttl);
    }

    /** 放刷新权（Lua 比对 token 才删；不会删掉别人的锁）。 */
    public boolean unlock(String token) {
        return this.store.compareAndDelete(LOCK_KEY, token);
    }

    /** 诊断：键的 TTL（{@code -1} 无 TTL；{@code null} = 存储未实现）。 */
    public Duration ttl(String key) {
        return this.store.ttl(key);
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
