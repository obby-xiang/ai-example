package com.example.configmgr.file;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RedisFileKeyMigrationLock}：{@code SET NX PX} 抢锁、compare-and-delete 释放；
 * Redis 异常一律 fail-closed（抢锁返回 false，续期按锁失效处理，释放不炸启动）。
 */
class RedisFileKeyMigrationLockTest {

    private static final Duration TTL = Duration.ofSeconds(30);

    private StringRedisTemplate redis;

    private ValueOperations<String, String> values;

    private RedisFileKeyMigrationLock lock;

    @BeforeEach
    void setUp() {
        this.redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        this.values = ops;
        when(this.redis.opsForValue()).thenReturn(ops);
        this.lock = new RedisFileKeyMigrationLock(this.redis);
    }

    @Test
    void tryAcquireUsesSetNxPxOnTheMigrationKey() {
        when(this.values.setIfAbsent(RedisFileKeyMigrationLock.LOCK_KEY, "token", TTL)).thenReturn(true);

        assertThat(this.lock.tryAcquire("token", TTL)).isTrue();
        verify(this.values).setIfAbsent(RedisFileKeyMigrationLock.LOCK_KEY, "token", TTL);
    }

    @Test
    void tryAcquireReturnsFalseWhenAnotherInstanceHoldsTheLock() {
        when(this.values.setIfAbsent(RedisFileKeyMigrationLock.LOCK_KEY, "token", TTL)).thenReturn(false);

        assertThat(this.lock.tryAcquire("token", TTL)).isFalse();
    }

    @Test
    void tryAcquireFailsClosedWhenRedisIsUnavailable() {
        when(this.values.setIfAbsent(RedisFileKeyMigrationLock.LOCK_KEY, "token", TTL))
            .thenThrow(new RedisConnectionFailureException("down"));

        assertThat(this.lock.tryAcquire("token", TTL))
            .as("Redis 不可用时不能降级成进程内锁（多实例挡不住）")
            .isFalse();
    }

    @Test
    void renewReturnsTrueOnlyWhenTheCompareAndPexpireScriptExtendedOurToken() {
        stubScript(1L);
        assertThat(this.lock.renew("token", TTL)).isTrue();

        stubScript(0L);
        assertThat(this.lock.renew("token", TTL)).isFalse();
    }

    @Test
    void renewFailsClosedWhenRedisIsUnavailable() {
        when(this.redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
            .thenThrow(new RedisConnectionFailureException("down"));

        assertThat(this.lock.renew("token", TTL)).isFalse();
    }

    @Test
    void releaseSwallowsRedisFailuresAndNeverBreaksStartup() {
        when(this.redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
            .thenThrow(new RedisConnectionFailureException("down"));

        assertThatCode(() -> this.lock.release("token")).doesNotThrowAnyException();
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void stubScript(long result) {
        when(this.redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(result);
    }
}
