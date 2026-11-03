package com.example.configmgr.ai.kb;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 缓存存储原语（追加 3）：生产用 Redis，用例用内存实现。
 *
 * <p>只暴露 cache-aside 需要的动作：HASH 读写（含按字段合并/删除）、字符串读、INCR、NX 加锁、比对删除。
 * <b>不提供"带 TTL 的写"</b>（除锁）—— 知识文档/清单/文档 id 索引键按定稿不设 TTL，接口层就不给误用的口子。
 *
 * <p><b>Cluster 口径</b>：所有动作都是<b>单键</b>命令；按字段读改写只碰同一个键，不做跨槽多键命令，
 * 也不提供 {@code KEYS}/{@code SCAN} 这类全库扫描（下线回收走显式 id 索引，见 {@code KbCache}）。
 *
 * <p>失败统一抛 {@link KbCacheException}（unchecked）：调用方 {@code CachedKbSource} 据此降级直连
 * 知识源，不把"缓存抖动"变成"知识问答不可用"。
 */
public interface KbCacheStore {

    /** 读 HASH 全部字段（键不存在 = 空 Map）。 */
    Map<String, String> hashGetAll(String key);

    /** 写 HASH 全部字段（<b>不设 TTL</b>）。 */
    void hashPutAll(String key, Map<String, String> values);

    /**
     * 合并写入 HASH 的<b>部分</b>字段（其它字段原样保留，<b>不设 TTL</b>）。
     *
     * <p>默认实现是「读全量 → 合并 → 写全量」：等价于单字段更新，且对"整表替换"语义的内存实现同样正确。
     * Redis 实现覆盖为单条 {@code HSET}，避免读改写之间的丢失更新。
     */
    default void hashPutFields(String key, Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        Map<String, String> merged = new LinkedHashMap<>(hashGetAll(key));
        merged.putAll(values);
        hashPutAll(key, merged);
    }

    /** 仅当 HASH 仍存在时更新一个字段；false = 键已不存在，调用方必须重写完整内容。 */
    default boolean hashPutFieldIfPresent(String key, String field, String value) {
        if (hashGetAll(key).isEmpty()) {
            return false;
        }
        hashPutFields(key, java.util.Map.of(field, value));
        return true;
    }

    /**
     * 删除 HASH 的<b>指定字段</b>（键不存在或无匹配字段 = 无操作）；返回实际删除的字段数。
     *
     * <p>默认实现是「读全量 → 摘字段 → 写全量」（全摘空则删整键）；Redis 实现覆盖为单条 {@code HDEL}。
     * 只操作单键，Cluster 下不存在跨槽问题。
     */
    default long hashDeleteFields(String key, Collection<String> fields) {
        if (fields == null || fields.isEmpty()) {
            return 0L;
        }
        Map<String, String> current = hashGetAll(key);
        if (current.isEmpty()) {
            return 0L;
        }
        Map<String, String> kept = new LinkedHashMap<>(current);
        long removed = 0L;
        for (String field : fields) {
            if (kept.remove(field) != null) {
                removed++;
            }
        }
        if (removed == 0L) {
            return 0L;
        }
        if (kept.isEmpty()) {
            delete(key);
        }
        else {
            hashPutAll(key, kept);
        }
        return removed;
    }

    String get(String key);

    long incr(String key);

    /** {@code SET key value NX PX ttl}；true = 本次抢到。 */
    boolean setIfAbsent(String key, String value, Duration ttl);

    /** Lua 比对值后删除（只删自己的锁；值不匹配返回 false）。 */
    boolean compareAndDelete(String key, String value);

    void delete(String key);

    /** 仅当代次字段不高于本次写入代次时合并 HASH；更高或不可判定的代次优先。 */
    default boolean hashPutAllIfGenerationAtMost(String key, String generationField, long generation,
            Map<String, String> values) {
        Long currentGeneration = numeric(hashGetAll(key).get(generationField));
        if (currentGeneration == null || currentGeneration > generation) {
            return false;
        }
        hashPutFields(key, values);
        return true;
    }

    /** 索引各字段分别按数值代次做单调写；旧刷新不得覆盖同字段的更高代次。 */
    default void hashPutFieldsIfValueAtMost(String key, Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        Map<String, String> current = hashGetAll(key);
        Map<String, String> writable = new LinkedHashMap<>();
        values.forEach((field, value) -> {
            Long next = numeric(value);
            Long existing = numeric(current.get(field));
            if (next != null && existing != null && next >= existing) {
                writable.put(field, String.valueOf(next));
            }
        });
        hashPutFields(key, writable);
    }

    /** 键仍存在且目标代次字段不高于新值时才提升代次。 */
    default boolean hashPutFieldIfPresentAndValueAtMost(String key, String field, long generation) {
        Map<String, String> current = hashGetAll(key);
        if (current.isEmpty()) {
            return false;
        }
        Long existing = numeric(current.get(field));
        if (existing == null || existing > generation) {
            return false;
        }
        hashPutFields(key, Map.of(field, String.valueOf(generation)));
        return true;
    }

    /** 文档代次不高于本次回收代次才删正文；键不存在视为已回收，代次更高或不可判定则保留。 */
    default boolean deleteDocIfGenerationAtMost(String key, String generationField, long generation) {
        Map<String, String> current = hashGetAll(key);
        if (current.isEmpty()) {
            return true;
        }
        Long currentGeneration = numeric(current.get(generationField));
        if (currentGeneration == null || currentGeneration > generation) {
            return false;
        }
        delete(key);
        return true;
    }

    /** 索引值不高于本次回收代次才摘字段；字段不存在视为已摘除。 */
    default boolean hashDeleteFieldIfValueAtMost(String key, String field, long generation) {
        Map<String, String> current = hashGetAll(key);
        String value = current.get(field);
        if (value == null) {
            return true;
        }
        Long currentGeneration = numeric(value);
        if (currentGeneration == null || currentGeneration > generation) {
            return false;
        }
        hashDeleteFields(key, java.util.List.of(field));
        return true;
    }

    private static Long numeric(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.valueOf(value.strip());
        }
        catch (NumberFormatException e) {
            return null;
        }
    }

    /** 诊断/用例断言用：键的剩余 TTL；{@code -1} = 无 TTL，{@code -2} = 不存在。 */
    default Duration ttl(String key) {
        return null;
    }
}
