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
import java.util.concurrent.atomic.AtomicLong;
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
 * <b>订阅代次</b>：订阅/退订都带代次（{@link FrameWakeBus#subscribe(String, long, LongConsumer)}、
 * {@link FrameWakeBus#unsubscribe(String, long)}）。同一个（身份, run）的第 N 次订阅带代次 N，
 * 退订按<b>代次 + 监听器对象</b>精确匹配 —— 旧桶那条晚到的补偿退订因此不会摘掉继任桶刚建立的
 * 监听器，晚到的旧代次订阅也不会顶掉新订阅。按"频道当前监听器"摘/顶替的旧写法有这个洞。
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
	 * 频道 → 当前订阅（代次 + 监听器对象）。
	 *
	 * <p>退订按<b>代次 + 监听器对象</b>精确匹配，绝不按"频道当前监听器"摘：旧桶的补偿退订可能
	 * 晚于继任桶的订阅落地，按频道摘就会把继任者的监听器摘掉（该 run 整段静默失去跨实例唤醒）。
	 */
	private final Map<String, Subscription> listeners = new ConcurrentHashMap<>();

	/** 无代次调用（既有测试/诊断路径）的代次来源：保证"后调用的更新"，不被旧代次顶掉。 */
	private final AtomicLong generations = new AtomicLong();

	private volatile RedisMessageListenerContainer container;

	public RedisFrameWakeBus(StringRedisTemplate redis) {
		this.redis = redis;
	}

	/** 一条频道订阅：代次 + 具体监听器对象（两者一起构成退订的精确句柄）。 */
	private record Subscription(long generation, MessageListener listener) {
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
	public boolean subscribe(String runId, LongConsumer onWake) {
		// 无代次调用（既有测试/诊断路径）：代次取单调序号，等价于"这次是最新的"
		return subscribe(runId, this.generations.incrementAndGet(), onWake);
	}

	@Override
	public boolean subscribe(String runId, long generation, LongConsumer onWake) {
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
		Subscription incoming = new Subscription(generation, listener);
		Subscription previous;
		synchronized (this.listeners) {
			Subscription current = this.listeners.get(channel);
			if (current != null && current.generation() > generation) {
				// 晚到的旧代次订阅：新订阅已经生效，不得顶掉它（否则继任桶自认已订阅却收不到唤醒）
				log.debug("帧唤醒订阅代次过旧，忽略 runId={} generation={} current={}", runId, generation,
						current.generation());
				return false;
			}
			previous = this.listeners.put(channel, incoming);
		}
		RedisMessageListenerContainer target = container();
		if (target == null) {
			this.listeners.remove(channel, incoming);
			return false;
		}
		try {
			if (previous != null) {
				target.removeMessageListener(previous.listener());
			}
			target.addMessageListener(listener, new ChannelTopic(channel));
			return true;
		}
		catch (Exception ex) {
			this.listeners.remove(channel, incoming);
			log.warn("帧唤醒订阅失败 runId={}（本实例跨实例唤醒缺席，重挂仍可补帧）：{}", runId, ex.getMessage());
			return false;
		}
	}

	@Override
	public void unsubscribe(String runId) {
		// 无代次调用（既有测试/诊断路径）：摘当前订阅
		String channel = channel(runId);
		Subscription removed;
		synchronized (this.listeners) {
			removed = this.listeners.remove(channel);
		}
		removeListener(runId, removed);
	}

	@Override
	public void unsubscribe(String runId, long generation) {
		String channel = channel(runId);
		Subscription removed;
		synchronized (this.listeners) {
			Subscription current = this.listeners.get(channel);
			if (current == null || current.generation() != generation) {
				// 新订阅已接管（或本来就没订阅）：旧代次的退订是 no-op —— 绝不摘别人的监听器
				return;
			}
			this.listeners.remove(channel);
			removed = current;
		}
		removeListener(runId, removed);
	}

	/** 从容器上摘掉<b>这一条</b>监听器（按对象，不按频道）。 */
	private void removeListener(String runId, Subscription removed) {
		RedisMessageListenerContainer target = this.container;
		if (removed == null || target == null) {
			return;
		}
		try {
			target.removeMessageListener(removed.listener());
		}
		catch (Exception ex) {
			log.debug("帧唤醒退订失败 runId={}：{}", runId, ex.getMessage());
		}
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
