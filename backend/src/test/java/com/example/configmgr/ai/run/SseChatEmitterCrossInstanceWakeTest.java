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
	 * kill：去掉 {@code onWake} 的"seq &lt;= 本订阅者 wakeFloor 即跳过"判据 ⇒ 第二次唤醒会把帧再推一遍，本用例变红。
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
	 * 形态说明（<b>资源修复批 I-3 口径更正</b>）：唤醒发布已移到 {@code emitLock} 之外，因此
	 * 本用例的时序是「落档 → <b>本地扇出</b>（入队 + 推进本订阅者游标）→ 发布 → 唤醒回到本实例」。
	 * 自唤醒的去重<b>不再</b>靠"唤醒先于扇出把游标顶上去"，而是靠两条既有判据：
	 * ①扇出成功入队后推进 {@code Subscription#wakeFloor}；②{@code onWake} 的
	 * "上界不高于最小游标 ⇒ 无差量"短路。生产总线是异步容器线程、替身是同步回调，
	 * 二者都落在同一套游标判据上，本用例因此对两者都成立（同步回调是更严的那一档）。
	 *
	 * <p>kill：去掉本地扇出里的 {@code sub.wakeFloor} 推进（或把它挪到入队<b>之前</b>）⇒
	 * 随后的唤醒从归档把这一帧再推一遍，本用例收到两帧（实测 [1,1]），变红。
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
	 * ②"回读之后、返回之前"落档的那一帧<b>不丢</b>—— I-2 起由回放后的差量追赶当场补齐
	 * （旧口径是"等下一次唤醒"，那正是 I-2 里"唤醒被撤掉就丢帧"的根因）；随后的重复唤醒按游标去重。
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
		assertThat(seqs(FrameWire.normalize(wireB.frames())))
				.as("回放 + 回放后的差量追赶：窗口内落档的那一帧当场补齐（I-2）").containsExactly(1L, 2L);

		// 唤醒随后到达（且重复到达）：按每订阅者游标去重，不重发
		this.bus.inject(RUN_ID, 2L);
		this.bus.inject(RUN_ID, 2L);
		assertThat(seqs(FrameWire.normalize(wireB.frames()))).as("窗口内落档的帧只投一次（唤醒不重复）")
				.containsExactly(1L, 2L);
	}

	/**
	 * <b>I-2：回放窗口内"唤醒被撤掉"落档的那一帧，必须由回放后的差量追赶补齐。</b>
	 *
	 * <p>竞态形态（评审 I-2）：重挂先 {@code ensureWakeSubscribed()} 再回放读。若旧连接的退订
	 * 恰好落在这段窗口里（例如它在本实例的订阅表短暂为空的瞬间结算），本实例的唤醒订阅会被撤掉；
	 * 回放读之后、唤醒重新登记之前落档的那一帧（N+1）因此<b>没有任何接收者</b>——
	 * 旧实现就把它丢了（SEQ 无洞的承诺失效，客户端停在 N）。
	 *
	 * <p>修复口径：回放结束、唤醒订阅按当前状态重新收敛之后，再用<b>同样的差量读</b>做一次追赶
	 * （{@code onWake(runId, Long.MAX_VALUE)} 的语义），去重仍由每订阅者游标 {@code wakeFloor} 负责 ——
	 * 因此"退订/重挂交错"下的帧不丢，且不会重复投递。
	 *
	 * <p>本用例用 {@code InMemoryFrameStore#onNextRead} 钩子把窗口确定化：钩子跑在回放读
	 * "已收集完、尚未返回"的那一刻，撤掉唤醒订阅并落一帧（它的唤醒没有接收者）。
	 * kill：去掉回放后的追赶 ⇒ 本用例只收到 1，变红。
	 */
	@Test
	void frameArchivedAfterTheReplayReadIsCaughtUpWhenTheWakeWasRevoked() {
		SseChatEmitter instanceA = emitter("a");
		instanceA.start("s-1");

		SseChatEmitter instanceB = emitter("b");
		FrameWire wireB = new FrameWire();
		this.frames.onNextRead(() -> {
			// 旧连接的退订把唤醒订阅撤掉（频道层面如实反映），随后落一帧 —— 唤醒没有接收者。
			this.bus.unsubscribe(RUN_ID);
			this.frames.seed(RUN_ID, frameMap("delta", 2L));
		});

		instanceB.replayAndAttach(wireB.emitter(), 0L, false);

		assertThat(seqs(FrameWire.normalize(wireB.frames())))
				.as("回放后的差量追赶补齐退订窗口内的 N+1 帧（且只补一次）").containsExactly(1L, 2L);
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
				AiProperties.Sse.DEFAULT_DELIVERY_QUEUE_CAPACITY, AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES,
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
