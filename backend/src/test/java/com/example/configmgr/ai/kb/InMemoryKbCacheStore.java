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

    /** 与生产 {@code RedisKbCacheStore.CACHE_TTL} 同值（30 分钟）。 */
    private static final long CACHE_TTL_MS = Duration.ofMinutes(30).toMillis();

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

    /**
     * {@code KB_CACHE_DELETE_INDEX_IF_GENERATION_AT_MOST} 的进程内复刻：
     * 字段缺失 ⇒ 返回 true（脚本 {@code return 1}）；值不是数字 ⇒ 不删、false；
     * 值 &gt; generation ⇒ 不删、false；否则删除字段并返回 true。
     */
    /**
     * {@code KB_CACHE_PUT_HASH_IF_LEASE_AND_GENERATION_AT_MOST} 的进程内复刻：
     * 锁键 owner 必须等于 token；generation 字段存在且（非数字 或 &gt; 本次 generation）⇒ 不写；
     * 否则整批写入并续 30min TTL。
     */
    @Override
    public boolean hashPutAllIfLeaseAndGenerationAtMost(String key, String lockKey, String token,
            String generationField, long generation, Map<String, String> values) {
        String owner = get(lockKey);
        if (owner == null || !owner.equals(token)) {
            return false;
        }
        Map<String, String> hash = this.hashes.get(key);
        String current = hash == null ? null : hash.get(generationField);
        if (current != null) {
            long value;
            try {
                value = Long.parseLong(current.trim());
            }
            catch (NumberFormatException ex) {
                return false;
            }
            if (value > generation) {
                return false;
            }
        }
        this.hashes.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>()).putAll(values);
        this.expiresAt.put(key, this.clock.get() + 1_800_000L);
        return true;
    }

    /**
     * {@code KB_CACHE_PUT_HASH_IF_GENERATION_AT_MOST} 的进程内复刻：
     * generation 字段存在且（非数字 或 &gt; 本次 generation）⇒ 整批不写、false；
     * 否则整批写入并续 30min TTL、true。
     */
    @Override
    public boolean hashPutAllIfGenerationAtMost(String key, String generationField, long generation,
            Map<String, String> values) {
        Map<String, String> hash = this.hashes.get(key);
        String current = hash == null ? null : hash.get(generationField);
        if (current != null) {
            long value;
            try {
                value = Long.parseLong(current.trim());
            }
            catch (NumberFormatException ex) {
                return false;
            }
            if (value > generation) {
                return false;
            }
        }
        this.hashes.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>()).putAll(values);
        this.expiresAt.put(key, this.clock.get() + 1_800_000L);
        return true;
    }

    /**
     * {@code KB_CACHE_PUT_INDEX_FIELDS_IF_GENERATION_AT_MOST} 的进程内复刻：
     * 字段缺失 ⇒ 写入；现值可解析且 ≤ 新值 ⇒ 写入；现值非数字或 &gt; 新值 ⇒ 保留原值；末尾续 30min TTL。
     */
    @Override
    public void hashPutFieldsIfValueAtMost(String key, Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        Map<String, String> hash = this.hashes.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>());
        values.forEach((field, newValue) -> {
            String current = hash.get(field);
            if (current == null) {
                hash.put(field, newValue);
                return;
            }
            long currentGeneration;
            long newGeneration;
            try {
                currentGeneration = Long.parseLong(current.trim());
                newGeneration = Long.parseLong(String.valueOf(newValue).trim());
            }
            catch (NumberFormatException ex) {
                return;
            }
            if (currentGeneration <= newGeneration) {
                hash.put(field, newValue);
            }
        });
        this.expiresAt.put(key, this.clock.get() + 1_800_000L);
    }

    /**
     * {@code KB_CACHE_PUT_FIELD_IF_PRESENT_AND_GENERATION_AT_MOST} 的进程内复刻：
     * 键不存在 ⇒ 0/false；现值非数字或 &gt; generation ⇒ 0/false；
     * 否则写 generation 并续 30min TTL，返回 true。
     */
    @Override
    public boolean hashPutFieldIfPresentAndValueAtMost(String key, String field, long generation) {
        Map<String, String> hash = this.hashes.get(key);
        if (hash == null) {
            return false;
        }
        String current = hash.get(field);
        if (current != null) {
            long value;
            try {
                value = Long.parseLong(current.trim());
            }
            catch (NumberFormatException ex) {
                return false;
            }
            if (value > generation) {
                return false;
            }
        }
        hash.put(field, Long.toString(generation));
        this.expiresAt.put(key, this.clock.get() + 1_800_000L);
        return true;
    }

    @Override
    public boolean hashDeleteFieldIfValueAtMost(String key, String field, long generation) {        Map<String, String> hash = this.hashes.get(key);
        if (hash == null) {
            return true;
        }
        String current = hash.get(field);
        if (current == null) {
            return true;
        }
        long value;
        try {
            value = Long.parseLong(current.trim());
        }
        catch (NumberFormatException ex) {
            return false;
        }
        if (value > generation) {
            return false;
        }
        hash.remove(field);
        return true;
    }

    /**
     * {@code KB_CACHE_DELETE_DOC_IF_GENERATION_AT_MOST} 的进程内复刻：generation 字段缺失 ⇒ 直接删；
     * 存在且不是数字、或大于本次 generation ⇒ 不删、false；否则整键删除并返回 true。
     */
    @Override
    public boolean deleteDocIfGenerationAtMost(String key, String generationField, long generation) {
        Map<String, String> hash = this.hashes.get(key);
        String current = hash == null ? null : hash.get(generationField);
        if (current != null) {
            long value;
            try {
                value = Long.parseLong(current.trim());
            }
            catch (NumberFormatException ex) {
                return false;
            }
            if (value > generation) {
                return false;
            }
        }
        this.hashes.remove(key);
        return true;
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
    public boolean compareAndExpire(String key, String value, Duration ttl) {
        expireIfNeeded(key);
        String current = this.values.get(key);
        if (current == null || !current.equals(value)) {
            return false;
        }
        this.expiresAt.put(key, this.clock.get() + Math.max(1L, ttl.toMillis()));
        return true;
    }

    @Override
    public void hashPutFields(String key, Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        this.hashes.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>()).putAll(values);
        this.expiresAt.put(key, this.clock.get() + CACHE_TTL_MS);
    }

    @Override
    public boolean hashPutFieldIfPresent(String key, String field, String value) {
        Map<String, String> hash = this.hashes.get(key);
        if (hash == null) {
            return false;
        }
        hash.put(field, value);
        this.expiresAt.put(key, this.clock.get() + CACHE_TTL_MS);
        return true;
    }

    @Override
    public long hashDeleteFields(String key, java.util.Collection<String> fields) {
        if (fields == null || fields.isEmpty()) {
            return 0L;
        }
        Map<String, String> hash = this.hashes.get(key);
        if (hash == null) {
            return 0L;
        }
        long removed = 0L;
        for (String field : fields) {
            if (hash.remove(field) != null) {
                removed++;
            }
        }
        return removed;
    }

    @Override
    public void touch(String key) {
        if (this.values.containsKey(key) || this.hashes.containsKey(key)) {
            this.expiresAt.put(key, this.clock.get() + CACHE_TTL_MS);
        }
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
            this.hashes.remove(key);
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
