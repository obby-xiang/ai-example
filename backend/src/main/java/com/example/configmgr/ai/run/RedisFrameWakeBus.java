package com.example.configmgr.ai.run;

import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.RunIdentityScope;
import com.example.configmgr.ai.tenant.TenantKeyScope;
import com.example.configmgr.ai.tenant.TenantUser;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongConsumer;

/**
 * {@link FrameWakeBus} 的 Redis pub/sub 实现：{@code PUBLISH ai:wake:<runId> <上界seq>} +
 * 一个按需启动的 {@link RedisMessageListenerContainer}。
 *
 * <p>
 * <b>懒启动</b>：容器在第一次 {@link #subscribeHandle} 时才建并启动 —— 没有挂起/重挂连接的进程
 * （含绝大多数单测上下文）不建立任何订阅连接，也就不会因为 Redis 不可用而拖慢启动。
 *
 * <p>
 * <b>按 run 订阅</b>（设计稿 §2.1 的硬约束）：频道粒度是 run，
 * <b>不做"每实例订阅全量频道"</b>——那会让每个实例的每条连接都被无关轮的唤醒打扰。
 *
 * <p>
 * <b>精确句柄退订</b>：一次订阅返回的句柄捕获本次安装的监听器对象，退订按对象摘 ——
 * 与"频道当前监听器是谁"无关。旧桶的补偿退订因此不会摘掉继任桶刚建立的监听器，
 * 晚到的旧订阅也只是短暂多一条监听器（桶已消失 ⇒ 回调 no-op），随后被自己的补偿退订摘掉。
 *
 * <p>
 * 失败口径：publish/subscribe 的 Redis 异常只记日志并降级（唤醒可丢；正确性由客户端
 * {@code ?lastSeq} 重连兜底）。容器自身带重连，Redis 恢复后订阅自动重建。
 */
@Slf4j
@Component
public class RedisFrameWakeBus implements FrameWakeBus {

	private final StringRedisTemplate redis;

	/**
	 * 无句柄兼容口（{@link #subscribe(String, LongConsumer)}）的当前订阅：该 API 拿不到句柄，
	 * 只能按频道记住"这一次装的是哪条监听器"以便 {@link #unsubscribe(String)} 摘掉。
	 * <b>生产路径（{@link FrameWakeListener}）只走 {@link #subscribeHandle}，不经过这里。</b>
	 */
	private final Map<String, Subscription> noHandleSubscriptions = new ConcurrentHashMap<>();

	private volatile RedisMessageListenerContainer container;

	public RedisFrameWakeBus(StringRedisTemplate redis) {
		this.redis = redis;
	}

	@Override
	public void publish(String runId, long upperBoundSeq) {
		try {
			this.redis.convertAndSend(channel(runId), Long.toString(upperBoundSeq));
		}
		catch (Exception ex) {
			log.debug("帧唤醒发布失败 runId={} seq={}（唤醒可丢，正确性由 ?lastSeq 重连兜底）：{}", runId,
					upperBoundSeq, ex.getMessage());
		}
	}

	@Override
	public Subscription subscribeHandle(String runId, long generation, LongConsumer onWake) {
		String channel = channel(runId);
		// 订阅侧的身份在这一刻固定（调用方是出帧/挂起路径，带着请求或恢复身份）；
		// 监听回调跑在 Redis 监听线程上，那里没有请求身份 ⇒ 回调内按这份身份 useContext。
		TenantUser identity = RequestContextUtils.currentOrNull();
		MessageListener listener = (Message message, byte[] pattern) -> {
			long upper = parseUpperBound(message == null ? null : message.getBody());
			try (RunIdentityScope.Scope ignored = RunIdentityScope.open(identity)) {
				onWake.accept(upper);
			}
		};
		RedisMessageListenerContainer target = container();
		if (target == null) {
			return null;
		}
		try {
			target.addMessageListener(listener, new ChannelTopic(channel));
		}
		catch (Exception ex) {
			log.warn("帧唤醒订阅失败 runId={}（本实例跨实例唤醒缺席，重挂仍可补帧）：{}", runId, ex.getMessage());
			return null;
		}
		AtomicBoolean closed = new AtomicBoolean();
		return () -> {
			if (!closed.compareAndSet(false, true)) {
				return;
			}
			RedisMessageListenerContainer current = this.container;
			if (current == null) {
				return;
			}
			try {
				current.removeMessageListener(listener);
			}
			catch (Exception ex) {
				log.debug("帧唤醒退订失败 runId={}：{}", runId, ex.getMessage());
			}
		};
	}

	@Override
	public boolean subscribe(String runId, LongConsumer onWake) {
		Subscription incoming = subscribeHandle(runId, 0L, onWake);
		if (incoming == null) {
			return false;
		}
		closeQuietly(this.noHandleSubscriptions.put(channel(runId), incoming), runId);
		return true;
	}

	@Override
	public void unsubscribe(String runId) {
		closeQuietly(this.noHandleSubscriptions.remove(channel(runId)), runId);
	}

	/**
	 * 唤醒频道名：{@code ai:wake:<tenantId>:<userId>:<runId>}（无身份上下文 = 旧形 {@code ai:wake:<runId>}）。
	 *
	 * <p>频道粒度仍是 run，但<b>身份进了频道名</b>：两个租户同 runId 的轮次不会互相唤醒
	 * （否则 A 的帧会让 B 的重挂者去读自己那边并不存在的差量）。
	 */
	public static String channel(String runId) {
		return CHANNEL_PREFIX + TenantKeyScope.currentSegment() + runId;
	}

	/** 无句柄兼容口当前的订阅数（诊断/断言用）；句柄订阅由 {@link FrameWakeListener} 自己计数。 */
	public int subscribedRuns() {
		return this.noHandleSubscriptions.size();
	}

	private static void closeQuietly(Subscription subscription, String runId) {
		if (subscription == null) {
			return;
		}
		try {
			subscription.close();
		}
		catch (Exception ex) {
			log.debug("帧唤醒退订失败 runId={}：{}", runId, ex.getMessage());
		}
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
