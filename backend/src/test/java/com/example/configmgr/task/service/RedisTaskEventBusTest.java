package com.example.configmgr.task.service;

import com.example.configmgr.ai.config.RedisAvailability;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RedisTaskEventBus} 的传输面用例：频道粒度、来源标识（防本实例二次投递）、
 * 故障降级可观测、以及"收到别的实例的帧 ⇒ 交给本机订阅者"的分发核心。
 *
 * <p><b>为什么分发用包内方法直呼</b>：真实 {@code RedisMessageListenerContainer} 需要活的
 * Redis 连接；本仓单测不起真 Redis（与 {@code FakeLeaseRedis} 同口径），故把"收到一帧"的
 * 纯逻辑抽成 {@code handleMessage}，容器回调只是它的搬运工。断言对象是分发语义，不是 Redis 本身。
 */
class RedisTaskEventBusTest {

    private static final long TASK_ID = 9L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @Test
    void publishUsesPerTaskChannelAndCarriesOrigin() throws Exception {
        RedisTaskEventBus bus = new RedisTaskEventBus(this.redis, MAPPER, "redis");
        String frame = "{\"type\":\"JOB_PROGRESS\",\"data\":{\"processed\":1}}";

        assertThat(bus.publish(TASK_ID, frame)).isTrue();

        verify(this.redis).convertAndSend(eq("task:events:" + TASK_ID), org.mockito.ArgumentMatchers.anyString());
        org.mockito.ArgumentCaptor<String> payload = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(this.redis).convertAndSend(eq("task:events:" + TASK_ID), payload.capture());
        JsonNode node = MAPPER.readTree(payload.getValue());
        assertThat(node.path("origin").asText()).as("来源标识 = 本实例（收侧据此忽略自己的回声）")
                .isEqualTo(bus.origin()).isNotEmpty();
        assertThat(node.path("frame").asText()).as("帧体原样搬运（线上形态不变）").isEqualTo(frame);
    }

    @Test
    void publishFailureIsExplicitlyDegraded() {
        RedisTaskEventBus bus = new RedisTaskEventBus(this.redis, MAPPER, "redis");
        doThrow(new IllegalStateException("Redis down"))
                .when(this.redis).convertAndSend(anyString(), anyString());

        assertThat(bus.publish(TASK_ID, "{}")).isFalse();
        assertThat(bus.metrics()).containsEntry("publishFailures", 1L)
                .containsEntry("publishedFrames", 0L);
    }

    @Test
    void subscribeWithoutConnectionFactoryReturnsFalseAndIsCounted() {
        when(this.redis.getConnectionFactory()).thenReturn((RedisConnectionFactory) null);
        RedisTaskEventBus bus = new RedisTaskEventBus(this.redis, MAPPER, "redis");

        assertThat(bus.subscribe(TASK_ID, frame -> { })).isFalse();
        assertThat(bus.subscribedTasks()).isZero();
        assertThat(bus.metrics()).containsEntry("subscribeFailures", 1L);
    }

    @Test
    void receivedFrameFromAnotherInstanceIsDeliveredToLocalSubscriber() throws Exception {
        RedisTaskEventBus bus = new RedisTaskEventBus(this.redis, MAPPER, "memory");
        List<String> received = new CopyOnWriteArrayList<>();
        assertThat(bus.subscribe(TASK_ID, received::add)).isTrue();

        bus.handleMessage(TASK_ID, envelope("other-instance", "{\"type\":\"JOB_DONE\",\"data\":{}}"));

        assertThat(received).containsExactly("{\"type\":\"JOB_DONE\",\"data\":{}}");
        assertThat(bus.metrics()).containsEntry("receivedFrames", 1L);
    }

    @Test
    void ownEchoIsIgnoredAndMalformedPayloadDoesNotBreakSubscription() throws Exception {
        RedisTaskEventBus bus = new RedisTaskEventBus(this.redis, MAPPER, "memory");
        AtomicInteger deliveries = new AtomicInteger();
        bus.subscribe(TASK_ID, frame -> deliveries.incrementAndGet());

        bus.handleMessage(TASK_ID, envelope(bus.origin(), "{\"type\":\"JOB_PROGRESS\"}"));
        bus.handleMessage(TASK_ID, "not-json".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        bus.handleMessage(TASK_ID, new byte[0]);
        bus.handleMessage(TASK_ID, null);

        assertThat(deliveries.get()).as("自己发的回声不二次投递；坏载荷静默丢弃").isZero();
        assertThat(bus.metrics()).containsEntry("dispatchFailures", 0L);
    }

    @Test
    void throwingSubscriberDoesNotKillTheListener() throws Exception {
        RedisTaskEventBus bus = new RedisTaskEventBus(this.redis, MAPPER, "memory");
        bus.subscribe(TASK_ID, frame -> {
            throw new IllegalStateException("browser gone");
        });

        bus.handleMessage(TASK_ID, envelope("other-instance", "{\"type\":\"JOB_PROGRESS\"}"));

        assertThat(bus.metrics()).containsEntry("dispatchFailures", 1L);
    }

    @Test
    void unsubscribeStopsDelivery() throws Exception {
        RedisTaskEventBus bus = new RedisTaskEventBus(this.redis, MAPPER, "memory");
        List<String> received = new CopyOnWriteArrayList<>();
        bus.subscribe(TASK_ID, received::add);
        bus.unsubscribe(TASK_ID);

        bus.handleMessage(TASK_ID, envelope("other-instance", "{\"type\":\"JOB_PROGRESS\"}"));

        assertThat(received).isEmpty();
        assertThat(bus.subscribedTasks()).isZero();
    }

    @Test
    void memoryModeDoesNotTouchRedis() {
        RedisTaskEventBus bus = new RedisTaskEventBus(this.redis, MAPPER, "memory");

        assertThat(bus.subscribe(TASK_ID, frame -> { })).isTrue();
        assertThat(bus.publish(TASK_ID, "{}")).as("单实例降级：本机已直投，不发 Redis").isTrue();

        verify(this.redis, never()).convertAndSend(anyString(), anyString());
    }

    // ── 3) 已知 Redis 不可用：零命令快速降级，恢复后正常 ─────────────────────

    @Test
    void knownDownSkipsPublishWithoutAnyRedisCommand() {
        RedisAvailability availability = knownDown();
        RedisTaskEventBus bus = new RedisTaskEventBus(this.redis, MAPPER, "redis", availability);

        assertThat(bus.publish(TASK_ID, "{}")).isFalse();
        assertThat(bus.publish(TASK_ID, "{}")).isFalse();

        verify(this.redis, never()).convertAndSend(anyString(), anyString());
        assertThat(bus.metrics()).as("降级可观测：失败计数照加")
                .containsEntry("publishFailures", 2L)
                .containsEntry("publishedFrames", 0L);
    }

    @Test
    void knownDownDoesNotStartSubscriptionContainer() {
        RedisAvailability availability = knownDown();
        RedisTaskEventBus bus = new RedisTaskEventBus(this.redis, MAPPER, "redis", availability);

        assertThat(bus.subscribe(TASK_ID, frame -> { })).isFalse();

        verify(this.redis, never()).getConnectionFactory();
        assertThat(bus.subscribedTasks()).isZero();
        assertThat(bus.metrics()).containsEntry("subscribeFailures", 1L);
    }

    @Test
    void recoveredRedisPublishesNormallyAgain() {
        RedisAvailability availability = mock(RedisAvailability.class);
        when(availability.isKnownUnavailable()).thenReturn(false);
        RedisTaskEventBus bus = new RedisTaskEventBus(this.redis, MAPPER, "redis", availability);

        assertThat(bus.publish(TASK_ID, "{}")).isTrue();

        verify(this.redis).convertAndSend(eq("task:events:" + TASK_ID), anyString());
        assertThat(bus.metrics()).containsEntry("publishedFrames", 1L);
    }

    private RedisAvailability knownDown() {
        RedisAvailability availability = mock(RedisAvailability.class);
        when(availability.isKnownUnavailable()).thenReturn(true);
        when(availability.isAvailable()).thenReturn(false);
        return availability;
    }

    private byte[] envelope(String origin, String frame) throws Exception {
        return MAPPER.writeValueAsBytes(Map.of("origin", origin, "frame", frame));
    }
}
