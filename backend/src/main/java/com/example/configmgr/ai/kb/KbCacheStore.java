package com.example.configmgr.ai.kb;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;

/**
 * 缓存存储原语。生产只使用 {@link RedisKbCacheStore} 的单命令或 Lua 原子实现；
 * 接口不提供 GET 后写的非原子兼容降级。
 *
 * <p>除 lease+manifest 使用同 hash tag 的双键 Lua 外，其余动作均为单键命令；
 * 不提供 {@code KEYS}/{@code SCAN} 或跨槽批量操作。
 */
public interface KbCacheStore {

    Map<String, String> hashGetAll(String key);

    default String hashGet(String key, String field) {
        return hashGetAll(key).get(field);
    }

    void hashPutAll(String key, Map<String, String> values);

    void hashPutFields(String key, Map<String, String> values);

    boolean hashPutFieldIfPresent(String key, String field, String value);

    long hashDeleteFields(String key, Collection<String> fields);

    String get(String key);

    long incr(String key);

    boolean setIfAbsent(String key, String value, Duration ttl);

    boolean compareAndDelete(String key, String value);

    boolean compareAndExpire(String key, String value, Duration ttl);

    boolean hashPutAllIfLeaseAndGenerationAtMost(String key, String lockKey, String token,
            String generationField, long generation, Map<String, String> values);

    void delete(String key);

    boolean hashPutAllIfGenerationAtMost(String key, String generationField, long generation,
            Map<String, String> values);

    void hashPutFieldsIfValueAtMost(String key, Map<String, String> values);

    boolean hashPutFieldIfPresentAndValueAtMost(String key, String field, long generation);

    boolean deleteDocIfGenerationAtMost(String key, String generationField, long generation);

    boolean hashDeleteFieldIfValueAtMost(String key, String field, long generation);

    /** 诊断用：{@code -1} = 无 TTL，{@code -2} = 不存在，{@code null} = 未提供。 */
    default Duration ttl(String key) {
        return null;
    }
}
