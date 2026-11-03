package com.example.configmgr.ai.kb;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 知识缓存的门面（追加 3）：键族、字段名、版本与刷新权都在这里收口（手写 cache-aside）。
 *
 * <h2>键族（会话无关的全局知识域）</h2>
 * <ul>
 * <li>{@code kb:cache:doc:<id>}（HASH：{@code content/etag/sha/updatedAt}）—— 单篇正文；</li>
 * <li>{@code kb:cache:docs}（HASH：field = 文档 id，value = 写入代次）—— <b>全局文档 id 索引</b>：
 *     "本缓存家族应当有哪些正文键"的事实源，下线回收按它与当前 manifest 的差集重试；</li>
 * <li>{@code kb:cache:manifest}（HASH：{@code etag/version/json}）—— 清单原文与版本；</li>
 * <li>{@code kb:cache:manifest:version}（INCR）—— 跨实例传播"知识已更新"；</li>
 * <li>{@code kb:cache:manifest:generation}（INCR）—— 写入/回收 fencing 代次，不提前暴露半份清单；</li>
 * <li>{@code kb:cache:refresh:lock}（{@code SET NX PX 30000}）—— 刷新权（单飞）。</li>
 * </ul>
 *
 * <p><b>TTL</b>：文档、文档 id 索引与清单键<b>不设 TTL</b>（定稿：刷新权属显式刷新，版本号兜底；过期会让
 * 缓存无声消失，冷启动重新拉远端，反而把"远端故障"放大成"知识空白"）。只有锁键有 30s TTL。
 *
 * <p><b>下线回收（收敛，不靠一次性成功）</b>：不设 TTL 意味着文档下线后必须显式删键。
 * {@code CachedKbSource#syncToCache} 每轮按 {@code kb:cache:docs} 与当前 manifest 的差集逐篇删除，
 * <b>条件删除成功后才按同一上限从索引摘除</b>：正文与索引均带代次，旧实例不能回收新实例重写的正文。
 * 删除失败或进程在两者之间崩溃时条目仍留在索引里，下一轮自动重试。
 * 全部是单键命令（{@code HGETALL/HSET/HDEL/DEL} 或等义单键 Lua），不使用 {@code KEYS}/{@code SCAN}/跨槽多键 Lua；
 * 索引键与文档键同属<b>全局公共知识域</b>（无租户段），一次刷新覆盖全部实例。
 *
 * <p><b>写入顺序</b>：先记文档 id 索引 → 写文档 → 写清单 → {@code INCR} 版本（最后回收下线文档）。
 * 索引先于正文是硬约束：正文键无 TTL，只有索引能把它找回来删；反过来"索引有、正文无"无害，
 * 下一轮补写正文或删空键后摘索引。读侧发现版本变化后若清单里的 {@code version} 仍落后（写窗），
 * {@code CachedKbSource} 会短暂重试，避免"看到新版本却读到旧清单"。
 */
public class KbCache {

    public static final String DOC_PREFIX = "kb:cache:doc:";

    /** 全局文档 id 索引（HASH 当 SET 用：field = 文档 id，value = 写入代次；<b>无 TTL</b>）。 */
    public static final String DOC_IDS_KEY = "kb:cache:docs";

    public static final String MANIFEST_KEY = "kb:cache:manifest";

    public static final String VERSION_KEY = "kb:cache:manifest:version";

    /** 写入/回收的 fencing 代次；与读侧刷新通知版本分离，避免提前暴露半份清单。 */
    public static final String GENERATION_KEY = "kb:cache:manifest:generation";

    public static final String LOCK_KEY = "kb:cache:refresh:lock";

    static final String F_CONTENT = "content";

    static final String F_ETAG = "etag";

    static final String F_SHA = "sha";

    static final String F_UPDATED = "updatedAt";

    static final String F_GENERATION = "generation";

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
        putDoc(docId, entry, 0L);
    }

    public void putDoc(String docId, DocEntry entry, long generation) {
        long fencedGeneration = Math.max(0L, generation);
        Map<String, String> hash = new LinkedHashMap<>();
        hash.put(F_CONTENT, entry.content());
        hash.put(F_ETAG, nullSafe(entry.etag()));
        hash.put(F_SHA, nullSafe(entry.sha()));
        hash.put(F_UPDATED, nullSafe(entry.updatedAt()));
        hash.put(F_GENERATION, String.valueOf(fencedGeneration));
        this.store.hashPutAllIfGenerationAtMost(docKey(docId), F_GENERATION, fencedGeneration, hash);
    }

    /** 内容未变但清单仍引用该文档时，只提升 fencing 代次；false = 正文已回收或属于更高代次。 */
    public boolean markDocGeneration(String docId, long generation) {
        return this.store.hashPutFieldIfPresentAndValueAtMost(docKey(docId), F_GENERATION,
                Math.max(0L, generation));
    }

    /**
     * 删除单篇文档键（文档已从清单下线时的回收路径）。
     *
     * <p>单键 DEL：Cluster 下不存在跨槽多键命令/KEYS 扫描；调用方按索引差集逐篇调用。
     */
    public void deleteDoc(String docId) {
        this.store.delete(docKey(docId));
    }

    /** 旧刷新不能删除更高代次重新写入的正文；false = 本键由新刷新所有，保留索引待其对账。 */
    public boolean deleteDoc(String docId, long generation) {
        return this.store.deleteDocIfGenerationAtMost(docKey(docId), F_GENERATION, Math.max(0L, generation));
    }

    /**
     * 全局文档 id 索引里的全部 id（键不存在 = 空集）。
     *
     * <p>索引是"本缓存家族应有哪些正文键"的事实源：读侧不从索引取正文，只用它做下线回收的差集。
     */
    public Set<String> docIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (String id : this.store.hashGetAll(DOC_IDS_KEY).keySet()) {
            if (id != null && !id.isBlank()) {
                ids.add(id);
            }
        }
        return ids;
    }

    /**
     * 把文档 id 记入全局索引（幂等；代次只允许单调提升）。
     *
     * <p>必须在写正文与写新清单<b>之前</b>成功：这样"正文有 ⇒ 索引有""清单里有 ⇒ 索引里有"，
     * 否则将来它下线时就没有可回收的线索（正文键无 TTL，会永久残留）。
     */
    public void putDocIds(Collection<String> docIds) {
        putDocIds(docIds, 0L);
    }

    public void putDocIds(Collection<String> docIds, long generation) {
        if (docIds == null || docIds.isEmpty()) {
            return;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (String id : docIds) {
            if (id != null && !id.isBlank()) {
                fields.put(id, String.valueOf(Math.max(0L, generation)));
            }
        }
        if (!fields.isEmpty()) {
            this.store.hashPutFieldsIfValueAtMost(DOC_IDS_KEY, fields);
        }
    }

    /**
     * 把单个文档 id 记入全局索引（单条 {@code HSET}）。
     *
     * <p>供"写单篇正文"的路径使用：调用方必须<b>先</b>调本方法、<b>再</b>写正文键，
     * 否则正文可能没有任何回收线索（正文键无 TTL，只能靠索引找回）。
     */
    public void putDocId(String docId) {
        putDocId(docId, 0L);
    }

    public void putDocId(String docId, long generation) {
        if (docId == null || docId.isBlank()) {
            return;
        }
        putDocIds(List.of(docId), generation);
    }

    /** 从全局索引摘除文档 id（正文键回收成功后调用；失败由调用方留待下一轮重试）。 */
    public void removeDocId(String docId) {
        if (docId == null || docId.isBlank()) {
            return;
        }
        this.store.hashDeleteFields(DOC_IDS_KEY, List.of(docId));
    }

    /** 与正文同一道代次闸：旧回收不能摘掉新刷新刚重建的回收线索。 */
    public boolean removeDocId(String docId, long generation) {
        if (docId == null || docId.isBlank()) {
            return true;
        }
        return this.store.hashDeleteFieldIfValueAtMost(DOC_IDS_KEY, docId, Math.max(0L, generation));
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

    /** 清单与文档共用代次 fencing；旧刷新不得覆盖新清单，也不得再推进通知版本。 */
    public boolean putManifest(ManifestEntry entry, long generation) {
        long fencedGeneration = Math.max(0L, generation);
        Map<String, String> hash = new LinkedHashMap<>();
        hash.put(F_ETAG, nullSafe(entry.etag()));
        hash.put(F_VERSION, nullSafe(entry.version()));
        hash.put(F_JSON, entry.json());
        hash.put(F_GENERATION, String.valueOf(fencedGeneration));
        return this.store.hashPutAllIfGenerationAtMost(MANIFEST_KEY, F_GENERATION, fencedGeneration, hash);
    }

    /** 清单内容未变时只提升 fencing 代次，拒绝仍在飞的旧清单写回。 */
    public boolean markManifestGeneration(long generation) {
        return this.store.hashPutFieldIfPresentAndValueAtMost(MANIFEST_KEY, F_GENERATION,
                Math.max(0L, generation));
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

    public long generation() {
        String raw = this.store.get(GENERATION_KEY);
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

    /** 在读取本轮回收差集之前取号；迟到的旧刷新因此只能回收自己这一代。 */
    public long nextGeneration() {
        return this.store.incr(GENERATION_KEY);
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
