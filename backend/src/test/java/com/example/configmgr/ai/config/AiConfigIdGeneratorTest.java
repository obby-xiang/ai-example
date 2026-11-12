package com.example.configmgr.ai.config;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiConfigIdGeneratorTest {

    private static LettuceConnectionFactory connectionFactory;

    private static StringRedisTemplate redis;

    private final Set<String> keys = ConcurrentHashMap.newKeySet();

    private final String keyPrefix = "test:ai:config:id:" + System.nanoTime() + ":";

    @BeforeAll
    static void openRedis() {
        connectionFactory = new LettuceConnectionFactory("127.0.0.1", 6379);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void closeRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @AfterEach
    void deleteTestKeys() {
        if (!this.keys.isEmpty()) {
            redis.delete(this.keys);
        }
    }

    @Test
    void concurrentIdsAreUnique() throws Exception {
        AiConfigIdGenerator generator = generator();
        int workers = 4;
        int idsPerWorker = 25;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(workers);
        List<Long> ids = new CopyOnWriteArrayList<>();
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        try {
            for (int worker = 0; worker < workers; worker++) {
                pool.execute(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        for (int index = 0; index < idsPerWorker; index++) {
                            ids.add(generator.nextProviderId());
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        failures.add(ex);
                    } catch (Throwable ex) {
                        failures.add(ex);
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(done.await(15, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(failures).isEmpty();
        assertThat(ids).hasSize(workers * idsPerWorker);
        assertThat(ids).doesNotHaveDuplicates().allMatch(id -> id > 0L);
    }

    @Test
    void eachEntityHasAnIndependentCounterWithBoundedTtl() {
        AiConfigIdGenerator generator = generator();

        generator.nextProviderId();
        generator.nextModelId();
        generator.nextAuditId();

        for (String entity : List.of("provider", "model", "audit")) {
            Long ttl = redis.getExpire(key(entity), TimeUnit.MILLISECONDS);
            assertThat(ttl).isNotNull().isPositive().isLessThanOrEqualTo(TimeUnit.DAYS.toMillis(1));
        }
    }

    @Test
    void deletedCounterIsRebuiltAtRedisTimeWithTtl() {
        AiConfigIdGenerator generator = generator();
        long first = generator.nextProviderId();
        redis.delete(key("provider"));
        long beforeMs = redis.execute((RedisCallback<Long>) connection -> connection.serverCommands().time()) / 1000L;

        long rebuilt = generator.nextProviderId();

        assertThat(rebuilt).isGreaterThanOrEqualTo(beforeMs).isNotEqualTo(first);
        assertThat(redis.getExpire(key("provider"), TimeUnit.MILLISECONDS))
                .isPositive().isLessThanOrEqualTo(TimeUnit.DAYS.toMillis(1));
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisFailureIsPropagatedWithoutFallback() {
        StringRedisTemplate unavailable = mock(StringRedisTemplate.class);
        when(unavailable.execute(any(RedisScript.class), anyList(), any()))
                .thenThrow(new RedisConnectionFailureException("test redis unavailable"));
        AiConfigIdGenerator generator = new AiConfigIdGenerator(unavailable, this.keyPrefix);

        assertThatThrownBy(generator::nextProviderId)
                .isInstanceOf(RedisConnectionFailureException.class)
                .hasMessage("test redis unavailable");
    }

    private AiConfigIdGenerator generator() {
        AiConfigIdGenerator generator = new AiConfigIdGenerator(redis, this.keyPrefix);
        this.keys.add(key("provider"));
        this.keys.add(key("model"));
        this.keys.add(key("audit"));
        return generator;
    }

    private String key(String entity) {
        return this.keyPrefix + entity;
    }
}
