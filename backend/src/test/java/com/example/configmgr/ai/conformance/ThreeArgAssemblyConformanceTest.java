package com.example.configmgr.ai.conformance;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三参装配路径的一致性锚定（源自 GLM S3-2 复核意见：{@code new RunRegistry(store, mapper, properties)}
 * 这条<b>生产</b>装配路径此前只有间接覆盖，无直接自动化用例）。
 *
 * <p>
 * 三参装配是 Spring 的唯一入口，它同时决定两件事：
 * <ol>
 * <li><b>投递执行器</b>：{@code properties != null} ⇒ 进程级共享有界投递池（异步，快慢订阅者隔离）；
 * 两参 ⇒ 固定 {@code Runnable::run}（同步直执，不读进程静态池，见
 * {@code SseChatEmitterDeliveryTest#twoArgRegistryAlwaysDeliversInlineAndNeverReadsTheSharedPool}）；</li>
 * <li><b>队列容量</b>：取 {@code AiProperties.Sse#getDeliveryQueueCapacity()}，缺省落在
 * {@link AiProperties.Sse#DEFAULT_DELIVERY_QUEUE_CAPACITY} 这一处单一事实源上。</li>
 * </ol>
 *
 * <h2>静态态卫生（本类必须自己负责）</h2>
 * {@code SseChatEmitter#sharedDeliveryPool} 与 {@code EVICTED_TOTAL} 都是<b>进程静态</b>，
 * 且 {@code configureSharedDeliveryPool} 幂等（首装配者胜，坑 4）。若不处理，本类的
 * "池容量 = 配置值"断言会随测试执行顺序漂移（另一个测试类先装配就用它的容量）。
 * 因此 {@link #resetStaticPool()} 在每个用例前后把静态池复位到本类进入时的原值 ——
 * <b>只反射改静态引用，不动生产代码</b>：这些字段的可见性是实现细节，而"跨用例静态污染"
 * 正是本类要排除的干扰项，用等价替身重造一遍反而把断言写在自己的假设上。
 */
class ThreeArgAssemblyConformanceTest {

	private static final String RUN_ID = "run-three-arg";

	private ThreadPoolExecutor poolBeforeClass;

	@BeforeEach
	void resetStaticPool() throws Exception {
		this.poolBeforeClass = (ThreadPoolExecutor) staticField("sharedDeliveryPool");
		setStaticField("sharedDeliveryPool", null);
	}

	@AfterEach
	void restoreStaticPool() throws Exception {
		ThreadPoolExecutor pool = (ThreadPoolExecutor) staticField("sharedDeliveryPool");
		if (pool != null && pool != this.poolBeforeClass) {
			pool.shutdownNow();
		}
		setStaticField("sharedDeliveryPool", this.poolBeforeClass);
	}

	// ── 1. 三参装配生效路径 = 异步共享投递池，队列容量取配置 ─────────────────────

	@Test
	void threeArgAssemblyUsesTheSharedAsyncPoolAndTheConfiguredQueueCapacity() throws Exception {
		AiProperties defaults = new AiProperties();
		RunRegistry registry = new RunRegistry(new InMemoryRunStore(), new ObjectMapper(), defaults);

		SseChatEmitter out = registry.of(RUN_ID);
		assertThat((Executor) fieldOf(out, "deliveryExecutor"))
				.as("三参装配（properties != null）⇒ 已装配进程级共享投递池，而非 Runnable::run")
				.isInstanceOf(ThreadPoolExecutor.class)
				.isSameAs(SseChatEmitter.sharedDeliveryExecutorOrDirect());
		assertThat(((ThreadPoolExecutor) SseChatEmitter.sharedDeliveryExecutorOrDirect()).getCorePoolSize())
				.as("池容量 = app.ai.sse.delivery-pool-size（R3 语义：容量即工作线程数）")
				.isEqualTo(defaults.getSse().getDeliveryPoolSize());
		assertThat(queueCapacityOf(out)).as("队列容量取配置缺省（单一事实源）")
				.isEqualTo(AiProperties.Sse.DEFAULT_DELIVERY_QUEUE_CAPACITY)
				.isLessThan(RunStore.EVENT_WINDOW);
	}

	@Test
	void threeArgAssemblyHonoursCustomDeliveryPoolAndQueueCapacity() throws Exception {
		AiProperties custom = new AiProperties();
		custom.getSse().setDeliveryPoolSize(3);
		custom.getSse().setDeliveryQueueCapacity(128);
		RunRegistry registry = new RunRegistry(new InMemoryRunStore(), new ObjectMapper(), custom);

		SseChatEmitter out = registry.of(RUN_ID);
		assertThat(((ThreadPoolExecutor) fieldOf(out, "deliveryExecutor")).getCorePoolSize())
				.as("自定义 delivery-pool-size 生效").isEqualTo(3);
		assertThat(queueCapacityOf(out)).as("自定义 delivery-queue-capacity 生效").isEqualTo(128);
	}

	// ── 2. 两参装配隔离性（隐性竞态：静态池已装配时不得被悄悄变异步） ─────────────

	@Test
	void twoArgAssemblyStaysInlineEvenAfterTheSharedPoolIsConfigured() throws Exception {
		AiProperties defaults = new AiProperties();
		// 先按生产路径装配静态池，构造"两参路径可能读到它"的前置状态
		SseChatEmitter.configureSharedDeliveryPool(defaults.getSse().getDeliveryPoolSize(),
				defaults.getSse().getDeliveryPoolSize() * 64);
		assertThat(SseChatEmitter.sharedDeliveryExecutorOrDirect())
				.as("前置：进程级静态池已装配").isInstanceOf(ThreadPoolExecutor.class);

		SseChatEmitter out = new RunRegistry(new InMemoryRunStore(), new ObjectMapper()).of(RUN_ID);
		assertThat((Executor) fieldOf(out, "deliveryExecutor"))
				.as("两参装配固定同步直执，不受静态池已装配影响").isNotInstanceOf(ThreadPoolExecutor.class);
		assertThat(queueCapacityOf(out)).as("两参装配回落配置缺省容量")
				.isEqualTo(AiProperties.Sse.DEFAULT_DELIVERY_QUEUE_CAPACITY);
	}

	// ── 3. 静态态卫生：跨用例不互相污染 ─────────────────────────────────────────

	@Test
	void staticPoolIsNotConfiguredByTheTwoArgPathAndEvictedTotalIsOnlyMonotonic() throws Exception {
		new RunRegistry(new InMemoryRunStore(), new ObjectMapper()).of("run-two-arg-static");
		assertThat(SseChatEmitter.sharedDeliveryExecutorOrDirect())
				.as("两参装配绝不装配进程静态池（否则同 JVM 内后续用例被悄悄变异步）")
				.isNotInstanceOf(ThreadPoolExecutor.class);

		// EVICTED_TOTAL 是进程级累计值（溢出/写出失败/池拒绝共用）：用例只断言单调不减，
		// 不断言绝对值 —— 绝对值随执行顺序漂移是它的既定语义，不是本用例的判据。
		long before = ((Number) SseChatEmitter.deliveryPoolMetrics().get("evicted")).longValue();
		AiProperties defaults = new AiProperties();
		RunRegistry registry = new RunRegistry(new InMemoryRunStore(), new ObjectMapper(), defaults);
		SseChatEmitter out = registry.of("run-evicted-monotonic");
		FrameWire wire = FrameWire.attachTo(out);
		out.messageBoundary("start", "text", null);
		awaitSize(wire, 1, 5_000L);

		long after = ((Number) SseChatEmitter.deliveryPoolMetrics().get("evicted")).longValue();
		assertThat(after).as("正常出帧不产生摘除；计数器单调不减（进程累计语义，非窗口值）").isGreaterThanOrEqualTo(before);
		assertThat(before).as("正常路径无摘除 ⇒ 计数器不动").isEqualTo(after);
	}

	// ── 4. 帧序语义不变：三参（异步池）路径下仍单订阅者 FIFO + seq 连续 ────────────

	@Test
	void threeArgAssemblyKeepsSingleSubscriberFifoAndContiguousSeq() throws Exception {
		AiProperties props = new AiProperties();
		props.getSse().setDeliveryPoolSize(4);
		SseChatEmitter out = new RunRegistry(new InMemoryRunStore(), new ObjectMapper(), props).of(RUN_ID);
		FrameWire wire = FrameWire.attachTo(out);

		int frames = 200;
		for (int index = 1; index <= frames; index++) {
			out.messageBoundary("start", "text", Map.of("index", index));
		}
		awaitSize(wire, frames, 15_000L);

		List<Map<String, Object>> received = FrameWire.normalize(wire.frames());
		assertThat(received).hasSize(frames);
		List<Long> seqs = new ArrayList<>();
		List<Long> indexes = new ArrayList<>();
		for (Map<String, Object> frame : received) {
			seqs.add(((Number) frame.get("seq")).longValue());
			indexes.add(((Number) frame.get("index")).longValue());
		}
		assertThat(seqs).as("三参（异步池）路径下 seq 连续无洞无重").containsExactlyElementsOf(
				java.util.stream.LongStream.rangeClosed(1, frames).boxed().toList());
		assertThat(indexes).as("单订阅者 FIFO：到达顺序 = 入队顺序（异步池不改变临界区内的入队序）")
				.containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, frames).boxed().toList());
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private static void awaitSize(FrameWire wire, int size, long timeoutMillis) throws InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMillis;
		while (wire.size() < size && System.currentTimeMillis() < deadline) {
			TimeUnit.MILLISECONDS.sleep(10L);
		}
		assertThat(wire.size()).as("帧线在超时前收到 %d 帧（实际 %d）", size, wire.size()).isEqualTo(size);
	}

	/**
	 * 该写出器的队列容量。{@code SseChatEmitter#queueCapacity()} 是包内可见（只给
	 * "容量 &lt; 归档窗口"不变量断言用），本类在 conformance 包内故走反射读同一字段 ——
	 * 读的是生产装配真正落下的值，而不是在用例里重算一遍。
	 */
	private static int queueCapacityOf(SseChatEmitter out) throws Exception {
		return ((Number) fieldOf(out, "queueCapacity")).intValue();
	}

	/** 读私有字段（白盒理由见类注：装配选择没有对外可观测面）。 */
	private static Object fieldOf(Object target, String name) throws Exception {
		Class<?> type = target.getClass();
		while (type != null) {
			try {
				Field field = type.getDeclaredField(name);
				field.setAccessible(true);
				return field.get(target);
			}
			catch (NoSuchFieldException ex) {
				type = type.getSuperclass();
			}
		}
		throw new IllegalStateException("找不到字段 " + name);
	}

	private static Object staticField(String name) throws Exception {
		Field field = SseChatEmitter.class.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(null);
	}

	private static void setStaticField(String name, Object value) throws Exception {
		Field field = SseChatEmitter.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(null, value);
	}

}
