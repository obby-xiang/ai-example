package com.example.configmgr.ai.kb;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Redis 实现（追加 3）：只用 StringRedisTemplate 的原语，<b>不引入 Spring Cache</b>。
 *
 * <p>比对删除用 Lua（与 {@code ai/run/LeaseService} 的 RELEASE_SCRIPT 同款口径）：
 * GET 比对 token 后才 DEL —— 锁过期被别的实例重抢时，旧持有者不会误删新锁。
 *
 * <p>任何 Redis 访问异常都转 {@link KbCacheException}：由 {@code CachedKbSource} 降级直连知识源
 * （单实例仍可用），不让缓存设施成为知识问答的单点。
 */
@Slf4j
public class RedisKbCacheStore implements KbCacheStore {

    private static final RedisScript<Long> COMPARE_DELETE = new DefaultRedisScript<>("""
            -- KB_CACHE_COMPARE_DELETE
            local current = redis.call('GET', KEYS[1])
            if (not current) or current ~= ARGV[1] then
              return 0
            end
            redis.call('DEL', KEYS[1])
            return 1
            """, Long.class);

    private static final RedisScript<Long> COMPARE_EXPIRE = new DefaultRedisScript<>("""
            -- KB_CACHE_COMPARE_EXPIRE
            local current = redis.call('GET', KEYS[1])
            if (not current) or current ~= ARGV[1] then
              return 0
            end
            return redis.call('PEXPIRE', KEYS[1], ARGV[2])
            """, Long.class);

    private static final RedisScript<Long> PUT_HASH_IF_LEASE_AND_GENERATION_AT_MOST = new DefaultRedisScript<>("""
            -- KB_CACHE_PUT_HASH_IF_LEASE_AND_GENERATION_AT_MOST
            local owner = redis.call('GET', KEYS[2])
            if (not owner) or owner ~= ARGV[1] then
              return 0
            end
            local current = redis.call('HGET', KEYS[1], ARGV[2])
            if current then
              local generation = tonumber(current)
              if (not generation) or generation > tonumber(ARGV[3]) then
                return 0
              end
            end
            for i = 4, #ARGV, 2 do
              redis.call('HSET', KEYS[1], ARGV[i], ARGV[i + 1])
            end
            return 1
            """, Long.class);

    private static final RedisScript<Long> PUT_HASH_IF_GENERATION_AT_MOST = new DefaultRedisScript<>("""
            -- KB_CACHE_PUT_HASH_IF_GENERATION_AT_MOST
            local current = redis.call('HGET', KEYS[1], ARGV[1])
            if current then
              local generation = tonumber(current)
              if (not generation) or generation > tonumber(ARGV[2]) then
                return 0
              end
            end
            for i = 3, #ARGV, 2 do
              redis.call('HSET', KEYS[1], ARGV[i], ARGV[i + 1])
            end
            return 1
            """, Long.class);

    private static final RedisScript<Long> PUT_INDEX_FIELDS_IF_GENERATION_AT_MOST = new DefaultRedisScript<>("""
            -- KB_CACHE_PUT_INDEX_FIELDS_IF_GENERATION_AT_MOST
            for i = 1, #ARGV, 2 do
              local current = redis.call('HGET', KEYS[1], ARGV[i])
              local generation = tonumber(current)
              if (not current) or (generation and generation <= tonumber(ARGV[i + 1])) then
                redis.call('HSET', KEYS[1], ARGV[i], ARGV[i + 1])
              end
            end
            return 1
            """, Long.class);

    private static final RedisScript<Long> PUT_FIELD_IF_PRESENT_AND_GENERATION_AT_MOST = new DefaultRedisScript<>("""
            -- KB_CACHE_PUT_FIELD_IF_PRESENT_AND_GENERATION_AT_MOST
            if redis.call('EXISTS', KEYS[1]) == 0 then
              return 0
            end
            local current = redis.call('HGET', KEYS[1], ARGV[1])
            local generation = tonumber(current)
            if current and ((not generation) or generation > tonumber(ARGV[2])) then
              return 0
            end
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
            return 1
            """, Long.class);

    private static final RedisScript<Long> DELETE_DOC_IF_GENERATION_AT_MOST = new DefaultRedisScript<>("""
            -- KB_CACHE_DELETE_DOC_IF_GENERATION_AT_MOST
            local current = redis.call('HGET', KEYS[1], ARGV[1])
            if current then
              local generation = tonumber(current)
              if (not generation) or generation > tonumber(ARGV[2]) then
                return 0
              end
            end
            redis.call('DEL', KEYS[1])
            return 1
            """, Long.class);

    private static final RedisScript<Long> DELETE_INDEX_IF_GENERATION_AT_MOST = new DefaultRedisScript<>("""
            -- KB_CACHE_DELETE_INDEX_IF_GENERATION_AT_MOST
            local current = redis.call('HGET', KEYS[1], ARGV[1])
            if not current then
              return 1
            end
            local generation = tonumber(current)
            if (not generation) or generation > tonumber(ARGV[2]) then
              return 0
            end
            redis.call('HDEL', KEYS[1], ARGV[1])
            return 1
            """, Long.class);

    private static final RedisScript<Long> PUT_FIELD_IF_PRESENT = new DefaultRedisScript<>("""
            -- KB_CACHE_PUT_FIELD_IF_PRESENT
            if redis.call('EXISTS', KEYS[1]) == 0 then
              return 0
            end
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;

    public RedisKbCacheStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Map<String, String> hashGetAll(String key) {
        try {
            Map<Object, Object> raw = this.redis.opsForHash().entries(key);
            Map<String, String> result = new LinkedHashMap<>();
            raw.forEach((field, value) -> result.put(String.valueOf(field), String.valueOf(value)));
            return result;
        }
        catch (RuntimeException e) {
            throw failure("读缓存 HASH 失败", key, e);
        }
    }

    @Override
    public String hashGet(String key, String field) {
        try {
            Object value = this.redis.opsForHash().get(key, field);
            return value == null ? null : String.valueOf(value);
        }
        catch (RuntimeException e) {
            throw failure("读缓存 HASH 字段失败", key, e);
        }
    }

    @Override
    public void hashPutAll(String key, Map<String, String> values) {
        try {
            // 刻意不设 TTL：知识文档/清单/文档 id 索引键按定稿常驻（版本号兜底刷新）
            this.redis.opsForHash().putAll(key, values);
        }
        catch (RuntimeException e) {
            throw failure("写缓存 HASH 失败", key, e);
        }
    }

    @Override
    public void hashPutFields(String key, Map<String, String> values) {
        try {
            // 单条 HSET：只合并给定字段，不读改写（无丢失更新），不设 TTL
            this.redis.opsForHash().putAll(key, values);
        }
        catch (RuntimeException e) {
            throw failure("合并写缓存 HASH 字段失败", key, e);
        }
    }

    @Override
    public boolean hashPutFieldIfPresent(String key, String field, String value) {
        try {
            Long updated = this.redis.execute(PUT_FIELD_IF_PRESENT, List.of(key), field, value);
            return updated != null && updated > 0;
        }
        catch (RuntimeException e) {
            throw failure("按存在性更新缓存代次失败", key, e);
        }
    }

    @Override
    public boolean hashPutAllIfGenerationAtMost(String key, String generationField, long generation,
            Map<String, String> values) {
        List<String> args = new java.util.ArrayList<>(2 + values.size() * 2);
        args.add(generationField);
        args.add(String.valueOf(generation));
        values.forEach((field, value) -> {
            args.add(field);
            args.add(value);
        });
        try {
            Long written = this.redis.execute(PUT_HASH_IF_GENERATION_AT_MOST, List.of(key), args.toArray());
            return written != null && written > 0;
        }
        catch (RuntimeException e) {
            throw failure("按代次写缓存 HASH 失败", key, e);
        }
    }

    @Override
    public boolean hashPutAllIfLeaseAndGenerationAtMost(String key, String lockKey, String token,
            String generationField, long generation, Map<String, String> values) {
        List<String> args = new java.util.ArrayList<>(3 + values.size() * 2);
        args.add(token);
        args.add(generationField);
        args.add(String.valueOf(generation));
        values.forEach((field, value) -> {
            args.add(field);
            args.add(value);
        });
        try {
            Long written = this.redis.execute(PUT_HASH_IF_LEASE_AND_GENERATION_AT_MOST,
                    List.of(key, lockKey), args.toArray());
            return written != null && written > 0;
        }
        catch (RuntimeException e) {
            throw failure("按 lease 与代次发布缓存清单失败", key, e);
        }
    }

    @Override
    public void hashPutFieldsIfValueAtMost(String key, Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        List<String> args = new java.util.ArrayList<>(values.size() * 2);
        values.forEach((field, value) -> {
            args.add(field);
            args.add(value);
        });
        try {
            this.redis.execute(PUT_INDEX_FIELDS_IF_GENERATION_AT_MOST, List.of(key), args.toArray());
        }
        catch (RuntimeException e) {
            throw failure("按代次写缓存索引失败", key, e);
        }
    }

    @Override
    public boolean hashPutFieldIfPresentAndValueAtMost(String key, String field, long generation) {
        try {
            Long updated = this.redis.execute(PUT_FIELD_IF_PRESENT_AND_GENERATION_AT_MOST, List.of(key), field,
                    String.valueOf(generation));
            return updated != null && updated > 0;
        }
        catch (RuntimeException e) {
            throw failure("按代次提升缓存正文代次失败", key, e);
        }
    }

    @Override
    public long hashDeleteFields(String key, Collection<String> fields) {
        // 空集合守卫：HDEL 至少需要一个字段，Redis 对"零字段"直接报语法错误
        if (fields == null || fields.isEmpty()) {
            return 0L;
        }
        try {
            // 单条 HDEL：单键、无跨槽问题；不用 KEYS/SCAN
            Long removed = this.redis.opsForHash().delete(key, fields.toArray());
            return removed == null ? 0L : removed;
        }
        catch (RuntimeException e) {
            throw failure("删除缓存 HASH 字段失败", key, e);
        }
    }

    @Override
    public String get(String key) {
        try {
            return this.redis.opsForValue().get(key);
        }
        catch (RuntimeException e) {
            throw failure("读缓存失败", key, e);
        }
    }

    @Override
    public long incr(String key) {
        try {
            Long value = this.redis.opsForValue().increment(key);
            return value == null ? 0L : value;
        }
        catch (RuntimeException e) {
            throw failure("缓存版本号 INCR 失败", key, e);
        }
    }

    @Override
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        try {
            Boolean acquired = this.redis.opsForValue().setIfAbsent(key, value, ttl);
            return Boolean.TRUE.equals(acquired);
        }
        catch (RuntimeException e) {
            throw failure("抢缓存锁失败", key, e);
        }
    }

    @Override
    public boolean compareAndDelete(String key, String value) {
        try {
            Long deleted = this.redis.execute(COMPARE_DELETE, List.of(key), value);
            return deleted != null && deleted > 0;
        }
        catch (RuntimeException e) {
            throw failure("释放缓存锁失败", key, e);
        }
    }

    @Override
    public boolean compareAndExpire(String key, String value, Duration ttl) {
        try {
            Long renewed = this.redis.execute(COMPARE_EXPIRE, List.of(key), value,
                    String.valueOf(Math.max(1L, ttl.toMillis())));
            return renewed != null && renewed > 0;
        }
        catch (RuntimeException e) {
            throw failure("续租缓存锁失败", key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            this.redis.delete(key);
        }
        catch (RuntimeException e) {
            throw failure("删除缓存键失败", key, e);
        }
    }

    @Override
    public boolean deleteDocIfGenerationAtMost(String key, String generationField, long generation) {
        try {
            Long deleted = this.redis.execute(DELETE_DOC_IF_GENERATION_AT_MOST, List.of(key), generationField,
                    String.valueOf(generation));
            return deleted != null && deleted > 0;
        }
        catch (RuntimeException e) {
            throw failure("按代次回收缓存正文失败", key, e);
        }
    }

    @Override
    public boolean hashDeleteFieldIfValueAtMost(String key, String field, long generation) {
        try {
            Long deleted = this.redis.execute(DELETE_INDEX_IF_GENERATION_AT_MOST, List.of(key), field,
                    String.valueOf(generation));
            return deleted != null && deleted > 0;
        }
        catch (RuntimeException e) {
            throw failure("按代次回收缓存索引失败", key, e);
        }
    }

    @Override
    public Duration ttl(String key) {
        try {
            Long millis = this.redis.getExpire(key, TimeUnit.MILLISECONDS);
            return millis == null ? null : Duration.ofMillis(millis);
        }
        catch (RuntimeException e) {
            throw failure("读取缓存 TTL 失败", key, e);
        }
    }

    private static KbCacheException failure(String action, String key, RuntimeException cause) {
        // 只报动作与键族（键名不含凭据/内容）；不含异常堆栈给模型可见面
        log.debug("KB cache operation failed: key={} cause={}", key, cause.getClass().getSimpleName());
        return new KbCacheException(action + "（key=" + key + "）：" + cause.getClass().getSimpleName(), cause);
    }
}
