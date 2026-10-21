package com.example.configmgr.ai.kb;

import java.time.Duration;
import java.util.Map;

/**
 * 缓存存储原语（追加 3）：生产用 Redis，用例用内存实现。
 *
 * <p>只暴露 cache-aside 需要的六个动作：HASH 读写、字符串读、INCR、NX 加锁、比对删除。
 * <b>不提供"带 TTL 的写"</b>（除锁）—— 知识文档/清单键按定稿不设 TTL，接口层就不给误用的口子。
 *
 * <p>失败统一抛 {@link KbCacheException}（unchecked）：调用方 {@code CachedKbSource} 据此降级直连
 * 知识源，不把"缓存抖动"变成"知识问答不可用"。
 */
public interface KbCacheStore {

    /** 读 HASH 全部字段（键不存在 = 空 Map）。 */
    Map<String, String> hashGetAll(String key);

    /** 写 HASH 全部字段（<b>不设 TTL</b>）。 */
    void hashPutAll(String key, Map<String, String> values);

    String get(String key);

    long incr(String key);

    /** {@code SET key value NX PX ttl}；true = 本次抢到。 */
    boolean setIfAbsent(String key, String value, Duration ttl);

    /** Lua 比对值后删除（只删自己的锁；值不匹配返回 false）。 */
    boolean compareAndDelete(String key, String value);

    void delete(String key);

    /** 诊断/用例断言用：键的剩余 TTL；{@code -1} = 无 TTL，{@code -2} = 不存在。 */
    default Duration ttl(String key) {
        return null;
    }
}
