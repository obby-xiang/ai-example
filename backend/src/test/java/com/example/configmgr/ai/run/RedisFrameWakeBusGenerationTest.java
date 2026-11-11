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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>订阅句柄 + 身份频道</b>：晚到的旧操作不能动到新订阅。
 *
 * <h2>当前契约</h2>
 * {@link FrameWakeBus#subscribeHandle(String, long, java.util.function.LongConsumer)} 返回一个
 * {@code Subscription} 句柄，退订只摘<b>该句柄安装的那一个监听器</b>；频道按
 * {@code ai:wake:<tenantId>:<userId>:<runId>} 拼，<b>没有裸 runId 频道</b>。
 * 代次参数仍保留在接口形状上（调用方按桶代次传），但仲裁已由句柄承担 ——
 * 旧实现的“按频道顶替/摘当前监听器”会误伤继任者，正是本类要防的形态。
 *
 * <h2>两条判据</h2>
 * <ol>
 * <li>旧句柄 close 只摘自己的监听器：继任句柄仍能收到唤醒，旧句柄不再被投递；</li>
 * <li>订阅绑定调用线程的显式身份频道；无身份时 fail-closed（不安装监听器，绝不订阅裸 runId 频道）。</li>
 * </ol>
 */
class RedisFrameWakeBusGenerationTest {

	private static final String RUN_ID = "run-wake-generation";

	private static final TenantUser IDENTITY = new TenantUser(1L, 101L);

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	private final RedisFrameWakeBus bus = new RedisFrameWakeBus(this.redis);

	private final RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);

	@Test
	void closingAnOldHandleDoesNotRemoveTheSuccessorsListener() {
		ReflectionTestUtils.setField(this.bus, "container", this.container);

		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(
				IDENTITY.tenantId(), IDENTITY.userId())) {
			AtomicLong oldWoken = new AtomicLong(-1L);
			AtomicLong successorWoken = new AtomicLong(-1L);

			FrameWakeBus.Subscription old = this.bus.subscribeHandle(RUN_ID, 1L, seq -> oldWoken.set(seq));
			FrameWakeBus.Subscription successor = this.bus.subscribeHandle(RUN_ID, 2L, seq -> successorWoken.set(seq));
			assertThat(old).as("旧代次订阅（句柄）").isNotNull();
			assertThat(successor).as("继任订阅（句柄）").isNotNull();

			// 旧桶的补偿退订晚到：句柄只摘自己安装的监听器，不按频道摘“当前监听器”。
			old.close();

			ArgumentCaptor<MessageListener> installed = ArgumentCaptor.forClass(MessageListener.class);
			verify(this.container, org.mockito.Mockito.times(2))
				.addMessageListener(installed.capture(), any(ChannelTopic.class));
			ArgumentCaptor<MessageListener> removed = ArgumentCaptor.forClass(MessageListener.class);
			verify(this.container, org.mockito.Mockito.times(1)).removeMessageListener(removed.capture());

			assertThat(removed.getValue())
				.as("旧句柄只摘自己安装的监听器（不是按频道摘“当前监听器”）")
				.isSameAs(installed.getAllValues().get(0))
				.isNotSameAs(installed.getAllValues().get(1));

			deliver(installed.getAllValues().get(1), 9L);
			assertThat(successorWoken.get()).as("继任者必须仍能收到唤醒").isEqualTo(9L);

			successor.close();
		}
	}

	@Test
	void subscriptionsAreScopedToTheCallersIdentityChannel() {
		ReflectionTestUtils.setField(this.bus, "container", this.container);

		AtomicLong woken = new AtomicLong(-1L);
		FrameWakeBus.Subscription handle;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(
				IDENTITY.tenantId(), IDENTITY.userId())) {
			handle = this.bus.subscribeHandle(RUN_ID, 1L, seq -> woken.set(seq));
		}
		assertThat(handle).isNotNull();

		ArgumentCaptor<MessageListener> installed = ArgumentCaptor.forClass(MessageListener.class);
		ArgumentCaptor<ChannelTopic> topic = ArgumentCaptor.forClass(ChannelTopic.class);
		verify(this.container).addMessageListener(installed.capture(), topic.capture());
		assertThat(topic.getValue().getTopic())
			.as("频道必须带身份段（不存在裸 runId 频道）")
			.isEqualTo(RedisFrameWakeBus.channel(IDENTITY, RUN_ID))
			.isEqualTo("ai:wake:1:101:" + RUN_ID);

		deliver(installed.getValue(), 5L);
		assertThat(woken.get()).isEqualTo(5L);
		handle.close();
	}

	@Test
	void subscribingWithoutAnIdentityFailsClosedInsteadOfUsingABareRunChannel() {
		ReflectionTestUtils.setField(this.bus, "container", this.container);

		assertThat(this.bus.subscribeHandle(RUN_ID, 1L, seq -> { }))
			.as("无身份 ⇒ 不订阅（返回 null），而不是退回裸 runId 频道")
			.isNull();
		verify(this.container, never()).addMessageListener(any(MessageListener.class), any(ChannelTopic.class));
	}

	/** 模拟 Redis 投递一条唤醒给指定的监听器。 */
	private void deliver(MessageListener listener, long upperBoundSeq) {
		Message message = mock(Message.class);
		when(message.getBody()).thenReturn(Long.toString(upperBoundSeq).getBytes(StandardCharsets.UTF_8));
		listener.onMessage(message, null);
	}

}
