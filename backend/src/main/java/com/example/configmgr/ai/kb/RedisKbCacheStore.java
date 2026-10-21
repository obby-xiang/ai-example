package com.example.configmgr.ai.kb;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
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
    public void hashPutAll(String key, Map<String, String> values) {
        try {
            // 刻意不设 TTL：知识文档/清单键按定稿常驻（版本号兜底刷新）
            this.redis.opsForHash().putAll(key, values);
        }
        catch (RuntimeException e) {
            throw failure("写缓存 HASH 失败", key, e);
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
    public void delete(String key) {
        try {
            this.redis.delete(key);
        }
        catch (RuntimeException e) {
            throw failure("删除缓存键失败", key, e);
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
        log.debug("{}：key={} err={}", action, key, cause.getClass().getSimpleName());
        return new KbCacheException(action + "（key=" + key + "）：" + cause.getClass().getSimpleName(), cause);
    }
}
