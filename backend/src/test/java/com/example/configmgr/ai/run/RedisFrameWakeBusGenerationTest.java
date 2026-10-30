package com.example.configmgr.ai.run;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
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
 * <b>订阅代次让"晚到的旧操作"不能动到新订阅</b>（I-1 的根治面）。
 *
 * <h2>为什么必须有这一层</h2>
 * 订阅/退订都在锁外做（Redis I/O）。旧桶的"补偿退订"可能在继任桶已经订阅<b>之后</b>才落到
 * 容器上；旧桶那条晚到的 {@code subscribe} 也可能在继任订阅之后才安装。旧实现按<b>频道</b>
 * 摘"当前监听器"、按频道顶替，于是：
 * <ul>
 * <li>旧代次的 unsubscribe 会摘掉继任者的监听器（继任桶自认已订阅，总线上却没有监听器 ⇒
 *     该 run 整段静默失去跨实例唤醒）；</li>
 * <li>旧代次的 subscribe 会顶掉继任者的监听器（同上，且继任桶永远不知道）。</li>
 * </ul>
 *
 * <p>本类两条用例各钉一条：旧代次退订是 no-op（且不碰容器）；旧代次订阅被拒绝（新订阅继续收消息）。
 */
class RedisFrameWakeBusGenerationTest {

	private static final String RUN_ID = "run-wake-generation";

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	private final RedisFrameWakeBus bus = new RedisFrameWakeBus(this.redis);

	private final RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);

	@Test
	void aStaleGenerationsUnsubscribeDoesNotRemoveTheSuccessorsSubscription() {
		ReflectionTestUtils.setField(this.bus, "container", this.container);

		AtomicLong successorWoken = new AtomicLong(-1L);
		assertThat(this.bus.subscribe(RUN_ID, 2L, seq -> successorWoken.set(seq))).as("继任订阅（新代次）").isTrue();

		// 旧桶的补偿退订晚到：代次 1 已经过时
		this.bus.unsubscribe(RUN_ID, 1L);

		assertThat(this.bus.subscribedRuns()).as("旧代次的退订不得摘掉继任订阅").isEqualTo(1);
		verify(this.container, never()).removeMessageListener(any(MessageListener.class));

		deliver(9L);
		assertThat(successorWoken.get()).as("继任者必须仍能收到唤醒").isEqualTo(9L);
	}

	@Test
	void aStaleGenerationsSubscribeDoesNotReplaceTheNewerSubscription() {
		ReflectionTestUtils.setField(this.bus, "container", this.container);

		AtomicLong successorWoken = new AtomicLong(-1L);
		AtomicLong staleWoken = new AtomicLong(-1L);
		assertThat(this.bus.subscribe(RUN_ID, 2L, seq -> successorWoken.set(seq))).isTrue();

		// 旧桶的 subscribe 晚到：代次 1 已经过时
		assertThat(this.bus.subscribe(RUN_ID, 1L, seq -> staleWoken.set(seq)))
			.as("晚到的旧代次订阅不得顶掉新订阅")
			.isFalse();

		deliver(7L);
		assertThat(successorWoken.get()).as("新订阅仍是投递目标").isEqualTo(7L);
		assertThat(staleWoken.get()).as("被拒绝的旧订阅不得收到唤醒").isEqualTo(-1L);
	}

	/** 模拟 Redis 投递一条唤醒（取容器上最新安装的监听器）。 */
	private void deliver(long upperBoundSeq) {
		ArgumentCaptor<MessageListener> captured = ArgumentCaptor.forClass(MessageListener.class);
		verify(this.container, org.mockito.Mockito.atLeastOnce()).addMessageListener(captured.capture(),
				any(org.springframework.data.redis.listener.ChannelTopic.class));
		Message message = mock(Message.class);
		when(message.getBody()).thenReturn(Long.toString(upperBoundSeq).getBytes(StandardCharsets.UTF_8));
		captured.getValue().onMessage(message, null);
	}

}
