package com.example.configmgr.file;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * {@link FileKeyMigrationLock} 的 Redis 实现：{@code SET NX PX} 抢锁，
 * Lua compare-and-renew / compare-and-delete 续期与释放。
 *
 * <h2>为什么这样锁</h2>
 * <ul>
 * <li>{@code SET key token NX PX ttl} 是单条命令的原子抢锁（与 KB 刷新权、会话锁同一手法）；</li>
 * <li>续期/释放都<b>先比对 token</b>：租期过期后另一个实例可能已抢到锁，本实例绝不能续它的期、
 * 更不能删它的锁 —— 否则会变成"两个实例都以为自己在迁移"；</li>
 * <li>键是单键（无多键命令），Redis Cluster 下同样成立。</li>
 * </ul>
 *
 * <h2>Redis 不可用</h2>
 * 一律<b>fail-closed</b>：{@link #tryAcquire} 返回 false（本次迁移不执行并记 WARN），
 * 而不是降级成"进程内锁"—— 多实例部署下进程内锁挡不住另一个实例，宁可不动。
 */
@Slf4j
@Component
public class RedisFileKeyMigrationLock implements FileKeyMigrationLock {

    /** 迁移单飞锁键（单键；不含业务数据）。 */
    public static final String LOCK_KEY = "app:lock:file-key-migration";

    private static final RedisScript<Long> RENEW = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            return 0
            """, Long.class);

    private static final RedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;

    public RedisFileKeyMigrationLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean tryAcquire(String token, Duration ttl) {
        try {
            Boolean acquired = this.redis.opsForValue().setIfAbsent(LOCK_KEY, token, ttl);
            return Boolean.TRUE.equals(acquired);
        }
        catch (RuntimeException e) {
            log.warn("迁移单飞锁不可用（Redis 异常），本次迁移不执行：{}", e.toString());
            return false;
        }
    }

    @Override
    public boolean renew(String token, Duration ttl) {
        try {
            Long renewed = this.redis.execute(RENEW, List.of(LOCK_KEY), token, String.valueOf(ttl.toMillis()));
            return renewed != null && renewed > 0;
        }
        catch (RuntimeException e) {
            log.warn("迁移单飞锁续期失败（按锁失效处理）：{}", e.toString());
            return false;
        }
    }

    @Override
    public void release(String token) {
        try {
            this.redis.execute(RELEASE, List.of(LOCK_KEY), token);
        }
        catch (RuntimeException e) {
            // 释放失败只剩租期兜底：不能把已完成的迁移变成启动失败。
            log.warn("迁移单飞锁释放失败（等租期到期让位）：{}", e.toString());
        }
    }
}
