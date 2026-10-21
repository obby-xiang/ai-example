package com.example.configmgr.ai.kb;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 用例内存版 {@link KbCacheStore}：保留 TTL 语义（无 TTL = -1，缺失 = -2），
 * 让"缓存键不设 TTL""锁 30s"这类断言不依赖真 Redis。
 */
final class InMemoryKbCacheStore implements KbCacheStore {

    private final Map<String, Map<String, String>> hashes = new ConcurrentHashMap<>();

    private final Map<String, String> values = new ConcurrentHashMap<>();

    private final Map<String, Long> expiresAt = new ConcurrentHashMap<>();

    private final AtomicLong clock = new AtomicLong(1_000L);

    @Override
    public Map<String, String> hashGetAll(String key) {
        Map<String, String> hash = this.hashes.get(key);
        return hash == null ? Map.of() : new LinkedHashMap<>(hash);
    }

    @Override
    public void hashPutAll(String key, Map<String, String> values) {
        this.hashes.put(key, new LinkedHashMap<>(values));
    }

    @Override
    public String get(String key) {
        expireIfNeeded(key);
        return this.values.get(key);
    }

    @Override
    public long incr(String key) {
        expireIfNeeded(key);
        long next = parse(this.values.get(key)) + 1;
        this.values.put(key, String.valueOf(next));
        return next;
    }

    @Override
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        expireIfNeeded(key);
        // 原子判存+写入（等价 Redis SETNX）：非原子的 containsKey→put 会让并发两线程同时拿到锁，
        // 冷启动单飞用例在负载下随机红（见 CachedKbSourceTest#twoConcurrentColdStartsDoOnlyOneRemotePull）。
        if (this.values.putIfAbsent(key, value) != null) {
            return false;
        }
        if (ttl != null && !ttl.isZero() && !ttl.isNegative()) {
            this.expiresAt.put(key, this.clock.get() + ttl.toMillis());
        }
        return true;
    }

    @Override
    public boolean compareAndDelete(String key, String value) {
        expireIfNeeded(key);
        String current = this.values.get(key);
        if (current == null || !current.equals(value)) {
            return false;
        }
        this.values.remove(key);
        this.expiresAt.remove(key);
        return true;
    }

    @Override
    public void delete(String key) {
        this.values.remove(key);
        this.hashes.remove(key);
        this.expiresAt.remove(key);
    }

    @Override
    public Duration ttl(String key) {
        expireIfNeeded(key);
        if (!this.values.containsKey(key) && !this.hashes.containsKey(key)) {
            return Duration.ofMillis(-2);
        }
        Long expiry = this.expiresAt.get(key);
        return expiry == null ? Duration.ofMillis(-1) : Duration.ofMillis(Math.max(0, expiry - this.clock.get()));
    }

    /** 推进时钟（锁过期用例用）。 */
    void advance(Duration duration) {
        this.clock.addAndGet(duration.toMillis());
    }

    private void expireIfNeeded(String key) {
        Long expiry = this.expiresAt.get(key);
        if (expiry != null && expiry <= this.clock.get()) {
            this.values.remove(key);
            this.expiresAt.remove(key);
        }
    }

    private static long parse(String value) {
        try {
            return value == null ? 0L : Long.parseLong(value);
        }
        catch (NumberFormatException e) {
            return 0L;
        }
    }
}
