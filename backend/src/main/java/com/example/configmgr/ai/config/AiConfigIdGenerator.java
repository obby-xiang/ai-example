package com.example.configmgr.ai.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
public class AiConfigIdGenerator {

    private static final String DEFAULT_KEY_PREFIX = "ai:config:id:";

    private static final long COUNTER_TTL_MILLIS = Duration.ofHours(24).toMillis();

    private static final RedisScript<Long> NEXT_ID_SCRIPT = new DefaultRedisScript<>("""
            local id = redis.call('INCR', KEYS[1])
            local now = redis.call('TIME')
            local floor = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
            if id < floor then
              id = floor
              redis.call('SET', KEYS[1], id)
            end
            redis.call('PEXPIRE', KEYS[1], ARGV[1])
            return id
            """, Long.class);

    private final StringRedisTemplate redis;

    private final String keyPrefix;

    @Autowired
    public AiConfigIdGenerator(StringRedisTemplate redis) {
        this(redis, DEFAULT_KEY_PREFIX);
    }

    AiConfigIdGenerator(StringRedisTemplate redis, String keyPrefix) {
        this.redis = redis;
        this.keyPrefix = keyPrefix;
    }

    public long nextProviderId() {
        return nextId("provider");
    }

    public long nextModelId() {
        return nextId("model");
    }

    public long nextAuditId() {
        return nextId("audit");
    }

    private long nextId(String entity) {
        Long id = this.redis.execute(NEXT_ID_SCRIPT, List.of(this.keyPrefix + entity),
                String.valueOf(COUNTER_TTL_MILLIS));
        if (id == null || id <= 0L) {
            throw new IllegalStateException("Redis config id allocation returned no valid id");
        }
        return id;
    }
}
