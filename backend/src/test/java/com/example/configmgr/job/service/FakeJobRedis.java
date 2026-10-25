package com.example.configmgr.job.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 作业侧共享态的 Redis 替身（多实例用例底座）：只建模产品代码实际发出的命令面
 * —— {@code SET PX} / {@code SET NX PX} / {@code GET} / {@code HASKEY} / {@code DEL}。
 *
 * <p>TTL 用<b>注入的 {@link Clock}</b> 判到期，且只在读写时惰性判定（真 Redis 的过期清理同样是
 * 惰性 + 定期抽样）：用例用可控时钟推进即可断言"共享心跳过期"，不 sleep 真实秒数。
 * {@link #goDown()} 让此后所有命令抛异常，用于 fail-closed 用例。
 */
final class FakeJobRedis {

    /** STRING 键面（runner 活跃键、共享心跳、判死单飞键）。 */
    final Map<String, String> strings = new ConcurrentHashMap<>();

    /** 键 → 到期时刻（毫秒，{@link Clock} 口径）。 */
    final Map<String, Long> deadlines = new ConcurrentHashMap<>();

    /** 键 → 最近一次被写入时的 TTL（断言"短 TTL"用）。 */
    final Map<String, Duration> ttls = new ConcurrentHashMap<>();

    /** 模拟宕机：所有命令抛出。 */
    volatile boolean down;

    /** 单键 DEL 的尝试顺序（断言"先删 beat、后删 anchor"用）。 */
    final List<String> deleteAttempts = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** 成功的单键 DEL 顺序（半失败时可能短于 attempts）。 */
    final List<String> successfulDeletes = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** 需要模拟"单键删除失败"的键（Redis 部分命令失败，而非整体宕机）。 */
    final java.util.Set<String> failingDeletes = ConcurrentHashMap.newKeySet();

    private final Clock clock;

    final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    private final ValueOperations<String, String> values = mock(ValueOperations.class);

    FakeJobRedis(Clock clock) {
        this.clock = clock;
        when(this.redis.opsForValue()).thenReturn(this.values);
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            put(key, invocation.getArgument(1), invocation.getArgument(2));
            return null;
        }).when(this.values).set(anyString(), anyString(), any(Duration.class));
        when(this.values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(invocation -> {
                    checkAlive();
                    String key = invocation.getArgument(0);
                    if (live(key) != null) {
                        return false;
                    }
                    put(key, invocation.getArgument(1), invocation.getArgument(2));
                    return true;
                });
        when(this.values.get(anyString())).thenAnswer(invocation -> {
            checkAlive();
            return live((String) invocation.getArgument(0));
        });
        when(this.redis.hasKey(anyString())).thenAnswer(invocation -> {
            checkAlive();
            return live((String) invocation.getArgument(0)) != null;
        });
        when(this.redis.delete(anyString())).thenAnswer(invocation -> {
            checkAlive();
            String key = invocation.getArgument(0);
            this.deleteAttempts.add(key);
            if (this.failingDeletes.contains(key)) {
                throw new IllegalStateException("DEL 失败（模拟单键半失败）：" + key);
            }
            boolean removed = remove(key);
            if (removed) {
                this.successfulDeletes.add(key);
            }
            return removed;
        });
        when(this.redis.delete(anyList())).thenAnswer(invocation -> {
            checkAlive();
            List<String> keys = invocation.getArgument(0);
            long removed = 0L;
            for (String key : keys) {
                if (remove(key)) {
                    removed++;
                }
            }
            return removed;
        });
    }

    /** 模拟宕机（此后读/写/删全部抛异常）。 */
    void goDown() {
        this.down = true;
    }

    void heal() {
        this.down = false;
    }

    /** 只让指定键的 DEL 失败（模拟 anchor/beat 单键半失败，其余命令正常）。 */
    void failDeleteOf(String key) {
        this.failingDeletes.add(key);
    }

    /** 直接铺一个键（不走 TTL 时钟，用于"另一实例写入的旧心跳"语料）。 */
    void seed(String key, String value, Duration ttl) {
        this.strings.put(key, value);
        this.deadlines.put(key, this.clock.millis() + ttl.toMillis());
        this.ttls.put(key, ttl);
    }

    boolean has(String key) {
        return live(key) != null;
    }

    String get(String key) {
        return live(key);
    }

    Duration ttl(String key) {
        return this.ttls.get(key);
    }

    private void put(String key, String value, Duration ttl) {
        this.strings.put(key, value);
        this.deadlines.put(key, this.clock.millis() + ttl.toMillis());
        this.ttls.put(key, ttl);
    }

    private boolean remove(String key) {
        this.deadlines.remove(key);
        this.ttls.remove(key);
        return this.strings.remove(key) != null;
    }

    /** 惰性过期：到期即视为不存在。 */
    private String live(String key) {
        Long deadline = this.deadlines.get(key);
        if (deadline != null && deadline <= this.clock.millis()) {
            this.strings.remove(key);
            this.deadlines.remove(key);
            return null;
        }
        return this.strings.get(key);
    }

    private void checkAlive() {
        if (this.down) {
            throw new IllegalStateException("Redis 不可用（模拟宕机）");
        }
    }
}
