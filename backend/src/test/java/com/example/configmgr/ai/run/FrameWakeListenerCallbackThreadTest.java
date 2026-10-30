package com.example.configmgr.ai.run;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>Redis 订阅连接的分发线程绝不能在 {@code register} 里阻塞</b>（I-A）。
 *
 * <h2>缺陷形态</h2>
 * {@code SseChatEmitter.onWakeScoped} 的 finally 会走 {@code flushWakeSyncIfUnlocked → applyWakeSync →
 * ensureWakeSubscribed → register}，也就是说<b>唤醒回调线程本身</b>会进 {@code register}。
 * 若 {@code register} 为了"不丢目标"而在订阅在途时等待（上一版的 2s wait），这条线程就同时
 * 耽误了<b>所有频道</b>的唤醒投递：任意一个 run 的首次订阅在途 ⇒ 全实例唤醒最多停摆 2s；
 * 更糟的是订阅确认也可能要在同一条订阅连接上推进（等待方等订阅、订阅等确认）。
 *
 * <p>本用例把"在途订阅"钉住：发起方的 {@code subscribe} 阻塞在总线上时，模拟 Redis 回调线程
 * 去登记同一 run 的第二个目标。断言它<b>立刻返回</b>（不等待），并且目标<b>留在桶里</b>
 * （订阅成功后仍会真实收到唤醒）。
 */
class FrameWakeListenerCallbackThreadTest {

	private static final String RUN_ID = "run-callback-thread";

	private final GenerationWakeBus bus = new GenerationWakeBus();

	private final FrameWakeListener listener = new FrameWakeListener(this.bus);

	@Test
	void registeringFromARedisCallbackThreadNeverBlocksOnAnInFlightSubscribe() throws Exception {
		this.bus.blockSubscribeCall = 1;
		RecordingTarget owner = new RecordingTarget();
		AtomicBoolean ownerResult = new AtomicBoolean();
		Thread submitting = new Thread(() -> ownerResult.set(this.listener.register(RUN_ID, owner)),
				"subscribe-owner");
		submitting.start();
		assertThat(this.bus.subscribeEntered.await(5, TimeUnit.SECONDS)).as("发起方订阅必须在途").isTrue();

		RecordingTarget late = new RecordingTarget();
		AtomicBoolean lateResult = new AtomicBoolean();
		Thread callback = new Thread(() -> lateResult.set(this.listener.register(RUN_ID, late)), "redis-callback");
		long startedAt = System.nanoTime();
		callback.start();
		callback.join(500L);
		long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

		assertThat(callback.isAlive()).as("Redis 回调线程不得被在途订阅阻塞").isFalse();
		assertThat(elapsedMillis).as("回调线程上的登记必须立即返回（旧实现要等 2s 超时）").isLessThan(500L);
		assertThat(lateResult.get()).as("未确认 ⇒ 不报已订阅").isFalse();
		assertThat(this.listener.targetCount(RUN_ID)).as("目标必须保留在桶里（不得丢弃）").isEqualTo(2);

		this.bus.releaseSubscribe();
		submitting.join(TimeUnit.SECONDS.toMillis(5));
		assertThat(ownerResult.get()).isTrue();
		assertThat(this.bus.subscribed(RUN_ID)).isTrue();
	}

	private static final class RecordingTarget implements FrameWakeListener.WakeTarget {

		final List<Long> wakes = new CopyOnWriteArrayList<>();

		@Override
		public void onWake(String runId, long upperBoundSeq) {
			this.wakes.add(upperBoundSeq);
		}

	}

}
