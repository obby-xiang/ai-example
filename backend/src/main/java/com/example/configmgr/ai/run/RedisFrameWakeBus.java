package com.example.configmgr.ai.run;

import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.RunIdentityScope;
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
 * {@link FrameWakeBus} 的 Redis pub/sub 实现：{@code PUBLISH ai:wake:<tenantId>:<userId>:<runId> <上界seq>}
 * + 一个按需启动的 {@link RedisMessageListenerContainer}。
 *
 * <p><b>频道只存在身份形</b>：发布/订阅都要求显式 tenant/user 作用域；没有身份时本拍直接跳过，
 * 不生成裸 {@code ai:wake:<runId>} 频道。
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
 * 失败口径：publish/subscribe 的 Redis 异常只记日志并降级（唤醒可丢；非驱动方按 lastSeq 游标
 * 低频对账，客户端 {@code ?lastSeq} 重连仍是最终兜底）。容器自身带重连，Redis 恢复后订阅自动重建。
 */
@Slf4j
@Component
public class RedisFrameWakeBus implements FrameWakeBus {

	private final StringRedisTemplate redis;

	private final Map<String, PublishState> publishStates = new ConcurrentHashMap<>();

	private static final long PUBLISH_COALESCE_MILLIS = 10L;

	private static final class PublishState {
		private long pendingSeq;
		private boolean terminal;
		private boolean running;
	}

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
	 * 订阅/退订操作的独立有界执行器（T-07）。
	 *
	 * <p>{@link RedisMessageListenerContainer} 有<b>两个</b>执行器：{@code taskExecutor} 跑消息派发，
	 * {@code subscriptionExecutor} 跑 subscribe/unsubscribe。<b>后者的缺省不是一个新的
	 * {@code SimpleAsyncTaskExecutor}</b>：{@code RedisMessageListenerContainer#afterPropertiesSet}
	 * 在未设置时执行 {@code subscriptionExecutor = taskExecutor}（spring-data-redis 3.5.11）。
	 * 本类已把 {@code taskExecutor} 设成上面的派发池，所以"不设 {@code subscriptionExecutor}"
	 * 的真实后果是<b>与派发共用同一个有界池</b>，而不是线程风暴。
	 *
	 * <p>仍然必须显式分开：订阅侧的任务不是派发，其中<b>订阅失败恢复</b>在容器里是一段
	 * {@code Thread.sleep(recoveryInterval)}（缺省 5s；仅当执行器本身是
	 * {@code ScheduledExecutorService} 才改为异步排期），而本类给的是普通有界池 ⇒ 每次恢复尝试
	 * 会在订阅线程上睡满一个退避间隔。若与派发共池，这段睡眠会直接占住派发线程（core=2），
	 * 把跨实例唤醒一起拖慢，重连风暴时更明显。因此这里给它一条<b>独立、有界</b>的时间线：
	 * {@code core=1 / max=2 / queue=64}，守护线程 {@code sse-wake-subscribe-N}，与容器同寿命，
	 * 在 {@link #stop()} 里一起关闭；饱和时调用线程内联执行（不丢订阅操作，容器内部的等待不会悬挂）。
	 */
	private volatile ThreadPoolExecutor subscriptionExecutor;

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

	/** 订阅操作执行器：core/max/queue（订阅任务极短，1–2 条线程足够；队列有界以免重连风暴堆积）。 */
	private static final int SUBSCRIBE_CORE_THREADS = 1;

	private static final int SUBSCRIBE_MAX_THREADS = 2;

	private static final int SUBSCRIBE_QUEUE_CAPACITY = 64;

	/** 派发线程编号（{@code sse-wake-dispatch-N}）。 */
	private static final AtomicLong DISPATCH_THREAD_NO = new AtomicLong();

	/** 订阅线程编号（{@code sse-wake-subscribe-N}）。 */
	private static final AtomicLong SUBSCRIBE_THREAD_NO = new AtomicLong();

	/** 唤醒派发饱和（内联执行）累计次数（诊断/健康指标）。 */
	static long dispatchSaturations() {
		return WAKE_DISPATCH_SATURATIONS.get();
	}

	public RedisFrameWakeBus(StringRedisTemplate redis) {
		this.redis = redis;
	}

	@Override
	public void publish(String runId, long upperBoundSeq, boolean terminal) {
		if (this.closed || upperBoundSeq <= 0L) {
			return;
		}
		TenantUser identity = RequestContextUtils.currentOrNull();
		if (identity == null) {
			log.warn("frame wake publish skipped runId={} seq={} (no explicit tenant/user identity; "
					+ "never publishing a bare-run channel)", runId, upperBoundSeq);
			return;
		}
		String channel = channel(identity, runId);
		while (true) {
			PublishState state = this.publishStates.computeIfAbsent(channel, ignored -> new PublishState());
			boolean submit = false;
			synchronized (state) {
				if (this.publishStates.get(channel) != state) {
					continue;
				}
				state.pendingSeq = Math.max(state.pendingSeq, upperBoundSeq);
				state.terminal |= terminal;
				if (!state.running) {
					state.running = true;
					submit = true;
				}
				if (terminal) {
					state.notifyAll();
				}
			}
			if (submit) {
				try {
					dispatchExecutor().execute(() -> drainPublish(channel, state));
				}
				catch (RuntimeException ex) {
					synchronized (state) {
						state.running = false;
						this.publishStates.remove(channel, state);
					}
					log.debug("frame wake coalesced publish not dispatched runId={} seq={}: {}", runId, upperBoundSeq,
							ex.getMessage());
				}
			}
			return;
		}
	}

	private void drainPublish(String channel, PublishState state) {
		try {
			while (true) {
				long seq;
				synchronized (state) {
					if (state.pendingSeq <= 0L) {
						state.running = false;
						this.publishStates.remove(channel, state);
						return;
					}
					if (!this.closed && !state.terminal) {
						state.wait(PUBLISH_COALESCE_MILLIS);
					}
					seq = state.pendingSeq;
					state.pendingSeq = 0L;
					state.terminal = false;
				}
				publishNow(channel, seq);
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		finally {
			synchronized (state) {
				if (state.running) {
					state.running = false;
					this.publishStates.remove(channel, state);
				}
			}
		}
	}

	private void publishNow(String channel, long upperBoundSeq) {
		try {
			this.redis.convertAndSend(channel, Long.toString(upperBoundSeq));
		}
		catch (Exception ex) {
			log.debug("frame wake publish failed channel={} seq={} (wake is best-effort; archive reconcile is authoritative): {}",
					channel, upperBoundSeq, ex.getMessage());
		}
	}

	@Override
	public Subscription subscribeHandle(String runId, long generation, LongConsumer onWake) {
		// 订阅侧的身份在这一刻固定（调用方是出帧/挂起路径，带着请求或恢复身份）；
		// 监听回调跑在 Redis 监听线程上，那里没有请求身份 ⇒ 回调内按这份身份 useContext。
		TenantUser identity = RequestContextUtils.currentOrNull();
		if (identity == null) {
			log.warn("frame wake subscribe skipped runId={} (no explicit tenant/user identity; "
					+ "never subscribing a bare-run channel)", runId);
			return null;
		}
		String channel = channel(identity, runId);
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
			log.warn("frame wake subscribe failed runId={} (cross-instance wake unavailable; reattach replays): {}", runId, ex.getMessage());
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
				log.debug("frame wake unsubscribe failed runId={}: {}", runId, ex.getMessage());
			}
		};
	}

	/**
	 * 唤醒频道名：{@code ai:wake:<tenantId>:<userId>:<runId>}。
	 *
	 * <p>身份必须显式给出：不存在 {@code ai:wake:<runId>} 的无身份频道（裸 runId 频道会与
	 * 身份频道形成两套唤醒面，跨实例补帧静默缺席）。
	 */
	public static String channel(TenantUser identity, String runId) {
		if (identity == null) {
			throw new IllegalArgumentException("frame wake channel requires explicit tenant/user identity");
		}
		return CHANNEL_PREFIX + identity.tenantId() + ":" + identity.userId() + ":" + runId;
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
			log.debug("no Redis connection factory: frame wake runs without subscription (single-instance semantics unaffected)");
			return null;
		}
		try {
			RedisMessageListenerContainer container = new RedisMessageListenerContainer();
			container.setConnectionFactory(factory);
			// P2-06：唤醒派发显式有界（缺省 SimpleAsyncTaskExecutor 是无界线程）。
			container.setTaskExecutor(dispatchExecutor());
			// T-07：订阅/退订单独一条有界时间线 —— 容器缺省会直接把 subscriptionExecutor 指向
			// taskExecutor（即上面的派发池），订阅失败恢复里的 Thread.sleep(5s) 会占住派发线程。
			container.setSubscriptionExecutor(subscriptionExecutor());
			container.afterPropertiesSet();
			container.start();
			return container;
		}
		catch (Exception ex) {
			log.warn("frame wake container start failed (cross-instance wake unavailable; reattach replays): {}", ex.getMessage());
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
							log.debug("frame wake dispatch executor saturated (threads {} / queue {}): running inline on caller (no notification lost)",
									executor.getMaximumPoolSize(), executor.getQueue().size());
							runnable.run();
						});
				this.dispatchExecutor.allowCoreThreadTimeOut(true);
			}
			return this.dispatchExecutor;
		}
	}

	/**
	 * 有界订阅执行器（懒建、幂等）：{@code core=1 / max=2 / queue=64}，守护线程。
	 *
	 * <p>与派发执行器同样的拒绝语义：队列满时<b>调用线程内联执行</b>。不用
	 * {@code CallerRunsPolicy} 是因为它在执行器已关闭时会<b>静默丢弃</b>任务 —— 订阅 task 被丢掉，
	 * 调用方（可能已持有容器内部锁/等待 future）就会悬挂；内联执行至少保证"这次订阅真的做完了"。
	 */
	private ThreadPoolExecutor subscriptionExecutor() {
		ThreadPoolExecutor current = this.subscriptionExecutor;
		if (current != null) {
			return current;
		}
		synchronized (this) {
			if (this.subscriptionExecutor == null) {
				this.subscriptionExecutor = new ThreadPoolExecutor(SUBSCRIBE_CORE_THREADS, SUBSCRIBE_MAX_THREADS,
						60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(SUBSCRIBE_QUEUE_CAPACITY), runnable -> {
							Thread thread = new Thread(runnable,
									"sse-wake-subscribe-" + SUBSCRIBE_THREAD_NO.incrementAndGet());
							thread.setDaemon(true);
							return thread;
						}, (runnable, executor) -> {
							log.debug("frame wake subscription executor saturated (threads {} / queue {}): running inline on caller",
									executor.getMaximumPoolSize(), executor.getQueue().size());
							runnable.run();
						});
				this.subscriptionExecutor.allowCoreThreadTimeOut(true);
			}
			return this.subscriptionExecutor;
		}
	}

	/** 唤醒派发饱和（内联执行）的累计次数（诊断/断言用）。 */
	long dispatchDrops() {
		return dispatchSaturations();
	}

	/**
	 * <b>应用停机</b>：destroy 按需启动的订阅容器与两个有界执行器（派发 P2-06 / 订阅 T-07）。
	 *
	 * <p>C1：与 {@link #container()} 的创建路径<b>同一把锁</b>（{@code synchronized(this)}）并复查
	 * {@code closed}，避免"stop 读到 null → 并发创建路径把容器建起来并 start"后容器无人 destroy。
	 *
	 * <p>旧实现只把容器创建出来、从不 destroy：守护线程不阻止 JVM 退出，但容器/连接/派发线程会随
	 * Spring Context 一起泄漏（多上下文/嵌入式部署）。停机顺序：先置 closed 拦新发布/订阅，再
	 * destroy 容器（停止订阅连接、释放监听器），最后关闭订阅与派发执行器（等在飞任务有界收尾）。
	 */
	@PreDestroy
	void stop() {
		RedisMessageListenerContainer current;
		ThreadPoolExecutor dispatch;
		ThreadPoolExecutor subscription;
		synchronized (this) {
			// 与 container()/dispatchExecutor()/subscriptionExecutor() 的创建互斥：拿到锁时要么创建
			// 已完成（下面能 destroy），要么之后创建方会看到 closed=true 直接返回 null。
			this.closed = true;
			current = this.container;
			this.container = null;
			dispatch = this.dispatchExecutor;
			this.dispatchExecutor = null;
			subscription = this.subscriptionExecutor;
			this.subscriptionExecutor = null;
		}
		for (PublishState state : this.publishStates.values()) {
			synchronized (state) {
				state.notifyAll();
			}
		}
		if (current != null) {
			try {
				current.destroy();
			}
			catch (Exception ex) {
				log.debug("frame wake container close failed: {}", ex.getMessage());
			}
		}
		shutdownBounded(subscription);
		shutdownBounded(dispatch);
		this.publishStates.clear();
	}

	/** 有界停机：先 {@code shutdown()}（不中断在飞任务），2s 内未收尾才 {@code shutdownNow()}。 */
	private static void shutdownBounded(ThreadPoolExecutor executor) {
		if (executor == null) {
			return;
		}
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
