package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.common.sse.SseWriteBudget;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B23L1 批① · 跨实例唤醒扇出（M8/M9/M11/M12）。
 *
 * <p>
 * 形态：两个写出器（= 两个实例对同一个 run 的持有者）共享一份 {@link FrameStore} 与一条
 * {@link FrameWakeBus}；实例 A 出帧 → 发布唤醒 → 实例 B 的 {@link FrameWakeListener} 收到 →
 * 从事件日志补差量推给 B 的连接。
 *
 * <p>
 * 三条硬约束在这里被钉住：<b>差量</b>（只推 &gt; 已推上界）、<b>幂等</b>（同一帧不二次下发）、
 * <b>独立进度</b>（两个持有者的上界互不影响）。
 */
class SseChatEmitterCrossInstanceWakeTest {

	private static final String RUN_ID = "run-multi";

	private final List<String> journal = new CopyOnWriteArrayList<>();

	private final InMemoryFrameStore frames = new InMemoryFrameStore(this.journal);

	private final FakeWakeBus bus = new FakeWakeBus(this.journal);

	private final FrameWakeListener listener = new FrameWakeListener(this.bus);

	private final InMemoryRunStore store = new InMemoryRunStore();

	@Test
	void frameEmittedOnOneInstanceReachesTheOther() {
		SseChatEmitter instanceB = emitter("b");
		FrameWire wireB = FrameWire.attachTo(instanceB);
		SseChatEmitter instanceA = emitter("a");

		instanceA.start("s-1");
		instanceA.delta("一");
		instanceA.delta("二");

		assertThat(this.bus.subscribes).as("B 有订阅者 ⇒ 建立该 run 的唤醒订阅").isEqualTo(1);
		assertThat(seqs(FrameWire.normalize(wireB.frames()))).as("A 的帧经 wake 扇出到 B 的连接")
				.containsExactly(1L, 2L, 3L);
	}

	/**
	 * M9：重复唤醒（上界不高于已推上界）幂等 —— 同一帧不二次下发。
	 *
	 * <p>
	 * kill：去掉 {@code lastFanoutSeq} 判据 ⇒ 第二次唤醒会把 1..3 再推一遍，本用例变红。
	 */
	@Test
	void repeatedWakeIsIdempotent() {
		SseChatEmitter instanceB = emitter("b");
		FrameWire wireB = FrameWire.attachTo(instanceB);
		SseChatEmitter instanceA = emitter("a");
		instanceA.delta("一");
		instanceA.delta("二");

		assertThat(wireB.frames()).hasSize(2);
		this.bus.inject(RUN_ID, 2);
		this.bus.inject(RUN_ID, 1);
		this.listener.dispatch(RUN_ID, 2);

		assertThat(FrameWire.normalize(wireB.frames())).as("重复唤醒不重复推帧").hasSize(2);
	}

	/**
	 * M11：两个持有者各自记"已推上界" —— 一个已看到 3、一个只看到 1 时，同一拍唤醒给出的差量不同。
	 */
	@Test
	void subscribersKeepIndependentProgress() {
		SseChatEmitter instanceA = emitter("a");
		instanceA.start("s-1");
		instanceA.delta("一");
		instanceA.delta("二");

		// 持有者 1：回放到日志头（已推上界 = 3）
		SseChatEmitter first = emitter("b1");
		FrameWire firstWire = new FrameWire();
		first.replayAndAttach(firstWire.emitter(), null, false);

		// 持有者 2：只回放到 1（差量 = 2..3）
		SseChatEmitter second = emitter("b2");
		FrameWire secondWire = new FrameWire();
		second.replayAndAttach(secondWire.emitter(), 1L, false);

		assertThat(seqs(FrameWire.normalize(firstWire.frames()))).containsExactly(1L, 2L, 3L);
		assertThat(seqs(FrameWire.normalize(secondWire.frames()))).as("回放差量").containsExactly(2L, 3L);

		// 新帧到来：两者都只收到 4
		instanceA.suspended(List.of(), "k1", "k2", "i1");
		assertThat(seqs(FrameWire.normalize(firstWire.frames()))).containsExactly(1L, 2L, 3L, 4L);
		assertThat(seqs(FrameWire.normalize(secondWire.frames()))).containsExactly(2L, 3L, 4L);
	}

	/**
	 * 同实例"自唤醒"：本实例既是发布者又是该 run 的订阅者时，唤醒会同步（或异步）回到自己身上 ——
	 * 那一帧<b>只能投一次</b>（本地快路径已投）。
	 *
	 * <p>
	 * 形态说明：生产总线是异步容器线程，这里的替身是同步回调，属于"更严的时序"；
	 * 水位在<b>发布之前</b>登记，因此即使回调在 emit 内部就发生，自唤醒也只会看到"无差量"。
	 *
	 * <p>kill：把水位登记挪到本地扇出之后（或删掉）⇒ 本用例收到两帧，变红。
	 */
	@Test
	void selfWakeOnTheSameInstanceDoesNotDuplicateTheFrame() {
		SseChatEmitter instance = emitter("self");
		FrameWire wire = FrameWire.attachTo(instance);
		this.journal.clear();

		instance.delta("一");

		assertThat(seqs(FrameWire.normalize(wire.frames()))).as("自唤醒不重复投递").containsExactly(1L);
		assertThat(this.journal).containsSequence("append:" + RUN_ID, "publish:" + RUN_ID);
	}

	@Test
	void lastSubscriberDetachUnsubscribesTheChannel() {
		SseChatEmitter instanceB = emitter("b");
		instanceB.attach(FrameWire.attachTo(instanceB).emitter());

		assertThat(this.bus.subscribed(RUN_ID)).isTrue();

		instanceB.complete();

		assertThat(this.bus.unsubscribes).as("最后一个订阅者断开 ⇒ 退订（不泄漏频道订阅）").isEqualTo(1);
		assertThat(this.bus.subscribed(RUN_ID)).isFalse();
		assertThat(this.listener.targetCount(RUN_ID)).isZero();
	}

	/**
	 * I-1：<b>唤醒订阅必须先于回放读</b>。旧顺序（读档 → 挂订阅）之间落档的帧谁也接不住 ——
	 * 回放快照已读完、订阅还没建立，而挂起/终局帧之后不会再有下一次唤醒。
	 *
	 * <p>两段断言：①回读发生的那一刻订阅已建立（由 {@link InMemoryFrameStore#onNextRead} 钩子观测）；
	 * ②"回读之后、返回之前"落档的那一帧，随后的唤醒能把它作为差量补齐且只补一次。
	 *
	 * <p>kill：把 {@code syncWakeSubscription()}（在 {@code attachSubscription} 内）挪回
	 * {@code stageReplay} 之后 ⇒ ①的观测变 false，本用例变红。
	 */
	@Test
	void wakeSubscriptionIsEstablishedBeforeTheReplayRead() {
		SseChatEmitter instanceA = emitter("a");
		instanceA.start("s-1");

		SseChatEmitter instanceB = emitter("b");
		FrameWire wireB = new FrameWire();
		java.util.concurrent.atomic.AtomicBoolean subscribedDuringRead = new java.util.concurrent.atomic.AtomicBoolean();
		this.frames.onNextRead(() -> {
			subscribedDuringRead.set(this.bus.subscribed(RUN_ID));
			// 回读已收集完、尚未返回：此时落一帧（不发布，模拟"落档与唤醒之间"的窗口）
			this.frames.seed(RUN_ID, frameMap("delta", 2L));
		});

		instanceB.replayAndAttach(wireB.emitter(), 0L, false);

		assertThat(subscribedDuringRead).as("回读时唤醒订阅已建立（I-1）").isTrue();
		assertThat(seqs(FrameWire.normalize(wireB.frames()))).as("回放快照只含 1").containsExactly(1L);

		// 窗口内那一帧的唤醒随后到达：作为差量补上，且只补一次
		this.bus.inject(RUN_ID, 2L);
		this.bus.inject(RUN_ID, 2L);
		assertThat(seqs(FrameWire.normalize(wireB.frames()))).as("窗口内落档的帧经唤醒补齐且不重复")
				.containsExactly(1L, 2L);
	}

	/**
	 * I-3：{@code bus.subscribe} 失败 ⇒ 登记位回退，<b>下一个订阅者到达时重试</b>。
	 *
	 * <p>kill：订阅失败后不回退 {@code wakeRegistered} ⇒ 第二次 attach 不会再试，本用例变红。
	 */
	@Test
	void subscribeFailureIsRolledBackAndRetriedOnTheNextAttach() {
		this.bus.subscribeSucceeds = false;
		SseChatEmitter instanceB = emitter("b");

		instanceB.attach(FrameWire.attachTo(instanceB).emitter());

		assertThat(this.bus.subscribed(RUN_ID)).as("首次订阅降级").isFalse();
		assertThat(this.listener.targetCount(RUN_ID)).as("失败的登记已回退（不留空目标）").isZero();

		this.bus.subscribeSucceeds = true;
		instanceB.attach(FrameWire.attachTo(instanceB).emitter());

		assertThat(this.bus.subscribed(RUN_ID)).as("下一个订阅者到达时重试成功").isTrue();
		assertThat(this.listener.targetCount(RUN_ID)).isEqualTo(1);
	}

	/**
	 * M-4（publish 降级）：PUBLISH 抛异常时出帧不受影响 —— 本地扇对照常，只丢跨实例唤醒。
	 *
	 * <p>kill：去掉 {@code publishWake} 的 try/catch ⇒ 本用例的 {@code delta} 抛异常 / 帧不到，变红。
	 */
	@Test
	void publishFailureDoesNotBreakLocalFanout() {
		SseChatEmitter instance = emitter("self");
		FrameWire wire = FrameWire.attachTo(instance);
		this.bus.publishFails = true;

		instance.delta("一");

		assertThat(seqs(FrameWire.normalize(wire.frames()))).as("本地快路径不受 PUBLISH 失败影响")
				.containsExactly(1L);
		assertThat(this.frames.lastSeq(RUN_ID)).as("帧照常落档").isEqualTo(1L);
	}

	/**
	 * M-6：首/末订阅者并发 churn（8 线程 × 25 次 attach+complete）—— 订阅最终归零、
	 * 不残留目标、不抛异常；登记路径的 Redis I/O 已移出全局锁（结构性改动见 FrameWakeListener）。
	 */
	@Test
	void concurrentAttachDetachChurnConvergesToNoSubscription() throws Exception {
		int threads = 8;
		int perThread = 25;
		java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
		java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
		java.util.List<Throwable> failures = new java.util.concurrent.CopyOnWriteArrayList<>();
		try {
			for (int i = 0; i < threads; i++) {
				pool.execute(() -> {
					try {
						start.await();
						for (int j = 0; j < perThread; j++) {
							SseChatEmitter emitter = emitter("churn");
							FrameWire wire = new FrameWire();
							emitter.attach(wire.emitter());
							emitter.complete();
						}
					}
					catch (Throwable ex) {
						failures.add(ex);
					}
				});
			}
			start.countDown();
			pool.shutdown();
			assertThat(pool.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(failures).isEmpty();
		assertThat(this.listener.targetCount(RUN_ID)).as("无目标残留").isZero();
		assertThat(this.listener.subscribedRuns()).isZero();
		assertThat(this.bus.subscribed(RUN_ID)).as("订阅已释放（不泄漏）").isFalse();
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private static Map<String, Object> frameMap(String type, long seq) {
		Map<String, Object> frame = new java.util.LinkedHashMap<>();
		frame.put("type", type);
		frame.put("seq", seq);
		frame.put("atMs", 1_700_000_000_000L);
		return frame;
	}

	private SseChatEmitter emitter(String instance) {
		return new SseChatEmitter(RUN_ID, this.store, this.frames, new ObjectMapper(), Runnable::run,
				AiProperties.Sse.DEFAULT_DELIVERY_QUEUE_CAPACITY, AiProperties.Frame.DEFAULT_MAX_BYTES,
				SseWriteBudget.direct(), this.bus, this.listener);
	}

	private static List<Long> seqs(List<Map<String, Object>> frames) {
		List<Long> out = new ArrayList<>();
		for (Map<String, Object> frame : frames) {
			Object seq = frame.get("seq");
			if (seq instanceof Number number) {
				out.add(number.longValue());
			}
		}
		return out;
	}

}
