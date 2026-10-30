package com.example.configmgr.ai.run;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;

/**
 * 模型化 {@code RedisFrameWakeBus} <b>代次语义</b>的唤醒总线替身（测试专用，<b>不放断言</b>：
 * 断言抛在订阅线程/回调线程里会被吞掉，用例不会变红）。
 *
 * <p>建模面 = 产品代码实际用到的那几条命令：
 * <ul>
 * <li>{@code subscribe(runId, generation, onWake)}：代次比当前订阅<b>旧</b> ⇒ 拒绝（返回 false，
 *     不顶掉新订阅）；</li>
 * <li>{@code unsubscribe(runId, generation)}：只有"当前订阅就是这一代"才摘（旧代次是 no-op）。</li>
 * </ul>
 *
 * <p>另有两个确定性阻塞点，用来把"订阅在途""退订晚到"钉成可控的一拍：
 * {@link #blockSubscribeCall}、{@link #blockUnsubscribe}。
 */
final class GenerationWakeBus implements FrameWakeBus {

	/** 一次频道订阅：代次 + 回调。 */
	private record Sub(long generation, LongConsumer onWake) {
	}

	/** 无代次调用（只有旧调用方会走）的虚拟代次起点：始终高于用例里的代次。 */
	private static final long LEGACY_GENERATION_BASE = 1_000_000_000L;

	private final Map<String, Sub> subscriptions = new ConcurrentHashMap<>();

	private final AtomicInteger legacyGenerations = new AtomicInteger();

	/** 被调用的订阅次数（1 起）。 */
	final AtomicInteger subscribeCalls = new AtomicInteger();

	/** 非 0 = 第 N 次 subscribe 阻塞在 {@link #subscribeEntered}，直到 {@link #releaseSubscribe()}。 */
	volatile int blockSubscribeCall;

	final CountDownLatch subscribeEntered = new CountDownLatch(1);

	private final CountDownLatch subscribeGate = new CountDownLatch(1);

	/** 被阻塞那次订阅的结局（false = 订阅失败、不安装）。 */
	volatile boolean blockedSubscribeSucceeds = true;

	/** true = 下一次 unsubscribe 阻塞在 {@link #unsubscribeEntered}，直到 {@link #releaseUnsubscribe()}。 */
	volatile boolean blockUnsubscribe;

	final CountDownLatch unsubscribeEntered = new CountDownLatch(1);

	private final CountDownLatch unsubscribeGate = new CountDownLatch(1);

	/** 订阅/退订流水（诊断用）。 */
	final List<String> journal = new CopyOnWriteArrayList<>();

	@Override
	public void publish(String runId, long upperBoundSeq) {
		Sub sub = this.subscriptions.get(runId);
		if (sub != null) {
			sub.onWake().accept(upperBoundSeq);
		}
	}

	@Override
	public boolean subscribe(String runId, LongConsumer onWake) {
		return subscribe(runId, LEGACY_GENERATION_BASE + this.legacyGenerations.incrementAndGet(), onWake);
	}

	@Override
	public boolean subscribe(String runId, long generation, LongConsumer onWake) {
		int call = this.subscribeCalls.incrementAndGet();
		this.journal.add("subscribe:" + generation);
		if (this.blockSubscribeCall == call) {
			this.subscribeEntered.countDown();
			await(this.subscribeGate);
			if (!this.blockedSubscribeSucceeds) {
				this.journal.add("failed:" + generation);
				return false;
			}
		}
		synchronized (this.subscriptions) {
			Sub current = this.subscriptions.get(runId);
			if (current != null && current.generation() > generation) {
				this.journal.add("rejected:" + generation);
				return false;
			}
			this.subscriptions.put(runId, new Sub(generation, onWake));
		}
		return true;
	}

	@Override
	public void unsubscribe(String runId) {
		this.subscriptions.remove(runId);
	}

	@Override
	public void unsubscribe(String runId, long generation) {
		this.journal.add("unsubscribe:" + generation);
		if (this.blockUnsubscribe) {
			this.blockUnsubscribe = false;
			this.unsubscribeEntered.countDown();
			await(this.unsubscribeGate);
		}
		synchronized (this.subscriptions) {
			Sub current = this.subscriptions.get(runId);
			if (current == null || current.generation() != generation) {
				this.journal.add("unsubscribe-noop:" + generation);
				return;
			}
			this.subscriptions.remove(runId);
		}
	}

	void releaseSubscribe() {
		this.subscribeGate.countDown();
	}

	void releaseUnsubscribe() {
		this.unsubscribeGate.countDown();
	}

	/** 模拟别的实例发来的唤醒（无订阅时静默丢弃，由用例的断言暴露）。 */
	void inject(String runId, long upperBoundSeq) {
		Sub sub = this.subscriptions.get(runId);
		if (sub != null) {
			sub.onWake().accept(upperBoundSeq);
		}
	}

	boolean subscribed(String runId) {
		return this.subscriptions.containsKey(runId);
	}

	long generationOf(String runId) {
		Sub sub = this.subscriptions.get(runId);
		return sub == null ? -1L : sub.generation();
	}

	private static void await(CountDownLatch gate) {
		try {
			gate.await(5, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

}
