package com.example.configmgr.ai.run;

import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.RunIdentityScope;
import com.example.configmgr.ai.tenant.TenantKeyScope;
import com.example.configmgr.ai.tenant.TenantUser;
import jakarta.annotation.PreDestroy;
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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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

	/**
	 * 唤醒派发的<b>有界</b>执行器（P2-06）。
	 *
	 * <p>为什么必须显式配置：Spring 缺省是 {@code SimpleAsyncTaskExecutor}（每条消息一个无界线程），
	 * 一旦某个目标的 {@code onWake} 变慢（读归档 + 入队），线程数就随唤醒速率无界增长。这里改成
	 * "有界线程 + 有界队列"；饱和时<b>调用线程内联执行</b>（C1：不丢订阅/取消通知，避免容器内部
	 * future.join 永久等待），饱和次数用 {@link #dispatchSaturations()} 观测。
	 */
	private volatile ThreadPoolExecutor dispatchExecutor;

	/**
	 * 唤醒派发执行器<b>饱和</b>次数（进程级；C1：饱和时改为调用线程内联执行，<b>不丢</b>订阅通知）。
	 *
	 * <p>静态计数便于从既有 {@code /api/ai/health} 的 SSE 段直接读出，无需注入本 bean。
	 */
	private static final AtomicLong WAKE_DISPATCH_SATURATIONS = new AtomicLong();

	/** 应用停机标志：置位后不再发布/订阅（容器与执行器已 destroy）。 */
	private volatile boolean closed;

	/** 唤醒派发线程数下限/上限与队列容量（显式有界；正常唤醒是毫秒级读档 + 入队）。 */
	private static final int DISPATCH_CORE_THREADS = 2;

	private static final int DISPATCH_MAX_THREADS = 8;

	private static final int DISPATCH_QUEUE_CAPACITY = 1024;

	/** 派发线程编号（{@code sse-wake-dispatch-N}）。 */
	private static final AtomicLong DISPATCH_THREAD_NO = new AtomicLong();

	/** 唤醒派发饱和（内联执行）累计次数（诊断/健康指标）。 */
	static long dispatchSaturations() {
		return WAKE_DISPATCH_SATURATIONS.get();
	}

	public RedisFrameWakeBus(StringRedisTemplate redis) {
		this.redis = redis;
	}

	@Override
	public void publish(String runId, long upperBoundSeq) {
		if (this.closed) {
			return;
		}
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
		if (this.closed) {
			return null;
		}
		RedisMessageListenerContainer current = this.container;
		if (current != null) {
			return current;
		}
		synchronized (this) {
			if (this.closed) {
				return null;
			}
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
			// P2-06：唤醒派发显式有界（缺省 SimpleAsyncTaskExecutor 是无界线程）。
			container.setTaskExecutor(dispatchExecutor());
			container.afterPropertiesSet();
			container.start();
			return container;
		}
		catch (Exception ex) {
			log.warn("帧唤醒容器启动失败（跨实例唤醒缺席，重挂仍可补帧）：{}", ex.getMessage());
			return null;
		}
	}

	/**
	 * 有界派发执行器（懒建、幂等）：{@code core=2 / max=8 / queue=1024}，守护线程。
	 *
	 * <p><b>C1：饱和不得丢订阅通知。</b>拒绝策略改为"计数 + 调用线程内联执行"（CallerRuns 等效）：
	 * 一是订阅/取消通知不能被丢（否则容器内部的 future.join 可能永久等待），二是唤醒消息本身也不能
	 * 静默消失。代价只是饱和时短暂占用 Redis 订阅线程（有界于单条回调）。
	 */
	private ThreadPoolExecutor dispatchExecutor() {
		ThreadPoolExecutor current = this.dispatchExecutor;
		if (current != null) {
			return current;
		}
		synchronized (this) {
			if (this.dispatchExecutor == null) {
				this.dispatchExecutor = new ThreadPoolExecutor(DISPATCH_CORE_THREADS, DISPATCH_MAX_THREADS, 60L,
						TimeUnit.SECONDS, new LinkedBlockingQueue<>(DISPATCH_QUEUE_CAPACITY), runnable -> {
							Thread thread = new Thread(runnable,
									"sse-wake-dispatch-" + DISPATCH_THREAD_NO.incrementAndGet());
							thread.setDaemon(true);
							return thread;
						}, (runnable, executor) -> {
							WAKE_DISPATCH_SATURATIONS.incrementAndGet();
							log.debug("帧唤醒派发执行器饱和（线程 {} / 队列 {}）：调用线程内联执行（不丢通知）",
									executor.getMaximumPoolSize(), executor.getQueue().size());
							runnable.run();
						});
				this.dispatchExecutor.allowCoreThreadTimeOut(true);
			}
			return this.dispatchExecutor;
		}
	}

	/** 唤醒派发饱和（内联执行）的累计次数（诊断/断言用）。 */
	long dispatchDrops() {
		return dispatchSaturations();
	}

	/**
	 * <b>应用停机</b>：destroy 按需启动的订阅容器与有界派发执行器（P2-06）。
	 *
	 * <p>C1：与 {@link #container()} 的创建路径<b>同一把锁</b>（{@code synchronized(this)}）并复查
	 * {@code closed}，避免"stop 读到 null → 并发创建路径把容器建起来并 start"后容器无人 destroy。
	 *
	 * <p>旧实现只把容器创建出来、从不 destroy：守护线程不阻止 JVM 退出，但容器/连接/派发线程会随
	 * Spring Context 一起泄漏（多上下文/嵌入式部署）。停机顺序：先置 closed 拦新发布/订阅，再
	 * destroy 容器（停止订阅连接、释放监听器），最后关闭派发执行器（等在飞回调有界收尾）。
	 */
	@PreDestroy
	void stop() {
		RedisMessageListenerContainer current;
		ThreadPoolExecutor executor;
		synchronized (this) {
			// 与 container()/dispatchExecutor() 的创建互斥：拿到锁时要么创建已完成（下面能 destroy），
			// 要么之后创建方会看到 closed=true 直接返回 null。
			this.closed = true;
			current = this.container;
			this.container = null;
			executor = this.dispatchExecutor;
			this.dispatchExecutor = null;
		}
		if (current != null) {
			try {
				current.destroy();
			}
			catch (Exception ex) {
				log.debug("帧唤醒容器关闭失败：{}", ex.getMessage());
			}
		}
		this.noHandleSubscriptions.clear();
		if (executor != null) {
			executor.shutdown();
			try {
				if (!executor.awaitTermination(2L, TimeUnit.SECONDS)) {
					executor.shutdownNow();
				}
			}
			catch (InterruptedException ex) {
				executor.shutdownNow();
				Thread.currentThread().interrupt();
			}
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
