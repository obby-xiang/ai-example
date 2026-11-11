package com.example.configmgr.ai.run;

import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.TenantUser;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 帧唤醒频道与监听线程的身份（租户任务2 核心组）。
 *
 * <h2>被钉住的两点</h2>
 * <ol>
 * <li><b>频道名带身份</b>：{@code ai:wake:<tenantId>:<userId>:<runId>}。两个租户同 runId 的轮次
 *     否则会互相唤醒 —— A 的帧让 B 的重挂者去读自己那边并不存在的差量；</li>
 * <li><b>监听线程恢复身份</b>：pub/sub 回调跑在 Redis 监听线程上，那里没有请求身份。回调里的
 *     下游动作（投递/取帧）必须在本轮身份下执行，否则要么 fail-closed，要么串成"上一位调用者"。</li>
 * </ol>
 */
class RedisFrameWakeBusTenantIsolationTest {

	private static final String RUN_ID = "run-wake-tenant";

	private static final long TENANT = 2L;

	private static final long USER = 202L;

	private static final String CHANNEL = "ai:wake:2:202:" + RUN_ID;

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	private final RedisFrameWakeBus bus = new RedisFrameWakeBus(this.redis);

	@Test
	void publishAndSubscribeUseTheIdentityScopedChannel() {
		RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
		ReflectionTestUtils.setField(this.bus, "container", container);

		AtomicLong woken = new AtomicLong(-1L);
		AtomicReference<TenantUser> identityInsideCallback = new AtomicReference<>();
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT, USER)) {
			assertThat(RedisFrameWakeBus.channel(new TenantUser(TENANT, USER), RUN_ID)).isEqualTo(CHANNEL);

			this.bus.publish(RUN_ID, 7L, false);
			verify(this.redis, timeout(3000)).convertAndSend(CHANNEL, "7");

			FrameWakeBus.Subscription subscription = this.bus.subscribeHandle(RUN_ID, 0L, seq -> {
				woken.set(seq);
				identityInsideCallback.set(RequestContextUtils.currentOrNull());
			});
			assertThat(subscription).as("带身份订阅必须装上监听器").isNotNull();
		}

		ArgumentCaptor<MessageListener> listener = ArgumentCaptor.forClass(MessageListener.class);
		ArgumentCaptor<ChannelTopic> topic = ArgumentCaptor.forClass(ChannelTopic.class);
		verify(container).addMessageListener(listener.capture(), topic.capture());
		assertThat(topic.getValue().getTopic()).as("订阅的频道同样带身份").isEqualTo(CHANNEL);

		assertThat(RequestContextUtils.currentOrNull()).as("前置：回调触发时用例线程本无身份").isNull();

		Message message = mock(Message.class);
		when(message.getBody()).thenReturn("9".getBytes(StandardCharsets.UTF_8));
		listener.getValue().onMessage(message, null);

		assertThat(woken.get()).isEqualTo(9L);
		assertThat(identityInsideCallback.get()).as("监听回调必须在本轮身份下执行")
			.isEqualTo(new TenantUser(TENANT, USER));
		assertThat(RequestContextUtils.currentOrNull()).as("回调结束不得把身份留在监听线程上").isNull();
	}

	@Test
	void closingTheSubscriptionRemovesTheIdentityScopedListenerExactlyOnce() {
		RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
		ReflectionTestUtils.setField(this.bus, "container", container);

		FrameWakeBus.Subscription subscription;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT, USER)) {
			subscription = this.bus.subscribeHandle(RUN_ID, 0L, seq -> {
			});
			assertThat(subscription).as("带身份订阅必须装上监听器").isNotNull();
			subscription.close();
			subscription.close();
		}

		ArgumentCaptor<MessageListener> removed = ArgumentCaptor.forClass(MessageListener.class);
		verify(container, times(1)).removeMessageListener(removed.capture());
		assertThat(removed.getValue()).isNotNull();
	}

	@Test
	void withoutIdentityNeitherPublishNorSubscribeTouchesRedis() {
		RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
		ReflectionTestUtils.setField(this.bus, "container", container);

		assertThat(RequestContextUtils.currentOrNull()).isNull();
		this.bus.publish(RUN_ID, 7L, false);
		assertThat(this.bus.subscribeHandle(RUN_ID, 0L, seq -> {
		})).as("没有身份就不得订阅裸 runId 频道").isNull();

		verify(this.redis, times(0)).convertAndSend(org.mockito.ArgumentMatchers.anyString(),
				org.mockito.ArgumentMatchers.anyString());
		verify(container, times(0)).addMessageListener(org.mockito.ArgumentMatchers.any(MessageListener.class),
				org.mockito.ArgumentMatchers.any(ChannelTopic.class));
	}

}
