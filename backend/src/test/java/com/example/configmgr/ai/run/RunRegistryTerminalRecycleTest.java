package com.example.configmgr.ai.run;

import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>终态 / 收流后的资源回收</b>（资源修复批）。
 *
 * <h2>缺陷形态</h2>
 * {@code RunRegistry.emitters}（{@code runId → SseChatEmitter}）只由
 * {@link RunRegistry#close(String)} / {@link RunRegistry#recycleIfIdle(String)} 显式摘除。
 * 终态出口一旦漏调（异常路径、新入口、脚本化恢复），条目就永久留在表里：
 * 它持有订阅者表、唤醒目标与队列引用，也是长跑实例上"每轮一条、只增不减"的又一处泄漏。
 * 现在把释放权交回写出器自己：<b>轮终态且订阅者排空</b>那一刻通过回收钩子把条目摘掉。
 *
 * <p>
 * 另外，终态还必须释放<b>事件日志侧的进程内簿记</b>（{@link FrameStore#forget}）——
 * 见 {@code RedisFrameStore} 的 per-run 簿记（降级号 / 观察到的最大载荷 / 告警位）。
 */
class RunRegistryTerminalRecycleTest {

	private static final String RUN_ID = "run-terminal-recycle";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final FakeWakeBus bus = new FakeWakeBus();

	private final FrameWakeListener listener = new FrameWakeListener(this.bus);

	/**
	 * 终态排空后条目<b>自动</b>回收：不需要外部再调 {@code close}。
	 *
	 * <p>回收是安全的：正在驱动的线程持有自己的写出器引用，帧照旧取号/落档；
	 * 之后的重挂会按 runId 新建写出器并从事件日志回放。
	 */
	@Test
	void terminalCompletionReleasesTheRegistryEntryAndTheWakeSubscription() {
		InMemoryFrameStore frames = new InMemoryFrameStore();
		RunRegistry registry = new RunRegistry(this.store, MAPPER, null, frames, this.bus, this.listener);
		SseChatEmitter out = registry.of(RUN_ID);
		FrameWire wire = FrameWire.attachTo(out);

		out.start("s-1");
		assertThat(registry.find(RUN_ID)).isSameAs(out);
		assertThat(registry.hasLiveSubscriber(RUN_ID)).isTrue();
		assertThat(this.bus.subscribed(RUN_ID)).as("有订阅者 ⇒ 唤醒订阅建立").isTrue();

		out.complete();

		assertThat(wire.size()).as("终态前已入队的帧被排空（不是抢断丢弃）").isEqualTo(1);
		assertThat(registry.find(RUN_ID)).as("终态排空后注册表条目自动回收（无需外部 close）").isNull();
		assertThat(registry.hasLiveSubscriber(RUN_ID)).isFalse();
		assertThat(this.bus.subscribed(RUN_ID)).as("最后一个订阅者离开 ⇒ 唤醒订阅已释放").isFalse();
		assertThat(this.listener.targetCount(RUN_ID)).isZero();

		// 相邻路径：回收之后的重挂拿到的是新写出器，仍能从事件日志回放同一轮
		SseChatEmitter reattached = registry.of(RUN_ID);
		assertThat(reattached).as("回收不是「忘掉这一轮」，而是「按需重建」").isNotSameAs(out);
		FrameWire replay = new FrameWire();
		reattached.replayAndAttach(replay.emitter(), 0L, true);
		assertThat(replay.size()).as("归档仍在（回收只清进程内簿记）").isEqualTo(1);
	}

	/**
	 * 相邻路径（<b>不得过度回收</b>）：{@code completeSubscriptions()}（B1 挂起即断）只收掉当前这批
	 * 连接，轮次仍在跑（线程驻留在确认门/表单等待里）—— 此时写出器<b>必须留在注册表</b>，
	 * 否则后续帧的本地扇出与重挂都拿不到同一个写出器。
	 */
	@Test
	void subscriberDetachWithoutTerminalKeepsTheEmitterForReattach() {
		InMemoryFrameStore frames = new InMemoryFrameStore();
		RunRegistry registry = new RunRegistry(this.store, MAPPER, null, frames, this.bus, this.listener);
		SseChatEmitter out = registry.of(RUN_ID);
		FrameWire wire = FrameWire.attachTo(out);

		out.start("s-1");
		int completed = out.completeSubscriptions();

		assertThat(completed).isEqualTo(1);
		assertThat(wire.size()).as("挂起请求帧已交付").isEqualTo(1);
		assertThat(out.subscriberCount()).isZero();
		assertThat(registry.find(RUN_ID)).as("轮未终态 ⇒ 写出器保留（供后续帧与重挂复用）").isSameAs(out);
		assertThat(this.bus.subscribed(RUN_ID)).as("无连接 ⇒ 唤醒订阅释放，但这不等于回收写出器").isFalse();
	}

	/**
	 * 终态出口释放事件日志侧的 <b>per-run 簿记</b>（{@link FrameStore#forget}）：
	 * 取号的锚点在 Redis，进程内的降级号/观察载荷/告警位只对"活着的这一轮"有意义。
	 */
	@Test
	void closingARunReleasesItsFrameStorePerRunBookkeeping() {
		FakeFrameRedis redis = new FakeFrameRedis();
		RedisFrameStore frames = new RedisFrameStore(redis.redis, MAPPER, new AiProperties(), 6000);
		RunRegistry registry = new RunRegistry(this.store, MAPPER, null, frames, null, null);

		SseChatEmitter out = registry.of(RUN_ID);
		out.start("s-1");
		assertThat(frames.trackedRuns()).as("出过帧 ⇒ 该轮簿记存在").isEqualTo(1);

		registry.close(RUN_ID);

		assertThat(frames.trackedRuns()).as("终态出口释放该轮簿记").isZero();
		assertThat(registry.find(RUN_ID)).isNull();
	}

	/**
	 * <b>回收钩子的窄窗收口</b>：{@code of()} 是"先建实例、后挂钩子"两步，
	 * 若实例在走上钩子之前就已经被 {@code complete()}（另一线程/异常路径抢先收尾），
	 * 只赋值钩子的实现会让它<b>永远留在表里</b>（终态、无订阅、钩子从未被调用）。
	 *
	 * <p>本用例把窄窗的<b>可观测量</b>钉住：给一个"已终态且已排空"的写出器挂钩子，
	 * 钩子必须<b>立刻</b>执行（且只一次）；已有钩子时不重复执行。
	 *
	 * <p>kill：{@code setRecycleHook} 只赋值、不补一次 {@code fireRecycleIfTerminal()} ⇒ 本用例变红。
	 */
	@Test
	void hookInstalledAfterTerminalCompletionRecyclesImmediately() {
		InMemoryFrameStore frames = new InMemoryFrameStore();
		SseChatEmitter late = new SseChatEmitter(RUN_ID, this.store, frames, MAPPER, Runnable::run,
				AiProperties.Sse.DEFAULT_DELIVERY_QUEUE_CAPACITY,
				AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES,
				com.example.configmgr.common.sse.SseWriteBudget.direct(), this.bus, this.listener);
		FrameWire wire = FrameWire.attachTo(late);
		late.start("s-1");
		late.complete();
		assertThat(wire.size()).isEqualTo(1);
		assertThat(late.subscriberCount()).isZero();

		java.util.concurrent.atomic.AtomicInteger recycled = new java.util.concurrent.atomic.AtomicInteger();
		late.setRecycleHook(recycled::incrementAndGet);

		assertThat(recycled.get()).as("挂上钩子时已终态排空 ⇒ 立刻回收（窄窗不会留下永久空壳）").isEqualTo(1);

		// 幂等：再挂一次不得重复回收（避免同一轮被摘两次、把新条目误摘）
		late.setRecycleHook(recycled::incrementAndGet);
		assertThat(recycled.get()).isEqualTo(1);
	}

}
