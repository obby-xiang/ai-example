package com.example.configmgr.ai.run;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongConsumer;

/**
 * {@link FrameWakeBus} 的 Redis pub/sub 实现：{@code PUBLISH ai:wake:<runId> <上界seq>} +
 * 一个按需启动的 {@link RedisMessageListenerContainer}。
 *
 * <p>
 * <b>懒启动</b>：容器在第一次 {@link #subscribe} 时才建并启动 —— 没有挂起/重挂连接的进程
 * （含绝大多数单测上下文）不建立任何订阅连接，也就不会因为 Redis 不可用而拖慢启动。
 *
 * <p>
 * <b>按 run 订阅</b>（设计稿 §2.1 的硬约束）：频道粒度是 run，
 * <b>不做"每实例订阅全量频道"</b>——那会让每个实例的每条连接都被无关轮的唤醒打扰。
 *
 * <p>
 * 失败口径：publish/subscribe 的 Redis 异常只记日志并降级（唤醒可丢；正确性由客户端
 * {@code ?lastSeq} 重连兜底）。容器自身带重连，Redis 恢复后订阅自动重建。
 */
@Slf4j
@Component
public class RedisFrameWakeBus implements FrameWakeBus {

	private final StringRedisTemplate redis;

	private final Map<String, MessageListener> listeners = new ConcurrentHashMap<>();

	private volatile RedisMessageListenerContainer container;

	public RedisFrameWakeBus(StringRedisTemplate redis) {
		this.redis = redis;
	}

	@Override
	public void publish(String runId, long upperBoundSeq) {
		try {
			this.redis.convertAndSend(CHANNEL_PREFIX + runId, Long.toString(upperBoundSeq));
		}
		catch (Exception ex) {
			log.debug("帧唤醒发布失败 runId={} seq={}（唤醒可丢，正确性由 ?lastSeq 重连兜底）：{}", runId,
					upperBoundSeq, ex.getMessage());
		}
	}

	@Override
	public boolean subscribe(String runId, LongConsumer onWake) {
		String channel = CHANNEL_PREFIX + runId;
		MessageListener listener = (Message message, byte[] pattern) -> {
			long upper = parseUpperBound(message == null ? null : message.getBody());
			onWake.accept(upper);
		};
		MessageListener previous = this.listeners.put(runId, listener);
		RedisMessageListenerContainer target = container();
		if (target == null) {
			this.listeners.remove(runId, listener);
			return false;
		}
		try {
			if (previous != null) {
				target.removeMessageListener(previous);
			}
			target.addMessageListener(listener, new ChannelTopic(channel));
			return true;
		}
		catch (Exception ex) {
			this.listeners.remove(runId, listener);
			log.warn("帧唤醒订阅失败 runId={}（本实例跨实例唤醒缺席，重挂仍可补帧）：{}", runId, ex.getMessage());
			return false;
		}
	}

	@Override
	public void unsubscribe(String runId) {
		MessageListener listener = this.listeners.remove(runId);
		RedisMessageListenerContainer target = this.container;
		if (listener == null || target == null) {
			return;
		}
		try {
			target.removeMessageListener(listener);
		}
		catch (Exception ex) {
			log.debug("帧唤醒退订失败 runId={}：{}", runId, ex.getMessage());
		}
	}

	/** 当前被订阅的 run 数（诊断/断言用）。 */
	public int subscribedRuns() {
		return this.listeners.size();
	}

	private RedisMessageListenerContainer container() {
		RedisMessageListenerContainer current = this.container;
		if (current != null) {
			return current;
		}
		synchronized (this) {
			if (this.container == null) {
				this.container = startContainer();
			}
			return this.container;
		}
	}

	private RedisMessageListenerContainer startContainer() {
		RedisConnectionFactory factory = this.redis.getConnectionFactory();
		if (factory == null) {
			log.debug("无 Redis 连接工厂：帧唤醒退化为不订阅（单实例语义不受影响）");
			return null;
		}
		try {
			RedisMessageListenerContainer container = new RedisMessageListenerContainer();
			container.setConnectionFactory(factory);
			container.afterPropertiesSet();
			container.start();
			return container;
		}
		catch (Exception ex) {
			log.warn("帧唤醒容器启动失败（跨实例唤醒缺席，重挂仍可补帧）：{}", ex.getMessage());
			return null;
		}
	}

	private static long parseUpperBound(byte[] body) {
		if (body == null || body.length == 0) {
			return 0L;
		}
		try {
			return Long.parseLong(new String(body, StandardCharsets.UTF_8).trim());
		}
		catch (NumberFormatException ex) {
			return 0L;
		}
	}

}
