package com.example.configmgr.ai.run;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>旧桶的补偿退订不得误退继任桶，且继任订阅必须立刻建立</b>（I-1 的收口形态）。
 *
 * <h2>缺陷形态</h2>
 * 补偿退订（"订阅成功时桶已空 ⇒ 补一次退订"）在锁外做。旧实现按<b>频道</b>摘"当前监听器"：
 * <ul>
 * <li>旧连接：订阅（gen 1 在途或已建立）⇒ 连接断开、桶空 ⇒ 补偿退订；</li>
 * <li>继任连接：同一（身份, runId）重新登记 ⇒ 新桶（gen 2）订阅成功，监听器 L2 进容器；</li>
 * <li>旧补偿的 {@code unsubscribe} 此时才落地 ⇒ <b>摘掉的是 L2</b> ⇒ 继任桶自认已订阅，
 *     总线上却没有监听器，该连接整段静默失去跨实例唤醒。</li>
 * </ul>
 *
 * <p>上一版靠"补偿期间让继任者等待"避免误退 —— 代价是阻塞（Redis 回调线程也被拖住）。
 * 本版改成<b>按代次比较退订</b>：旧代次的 {@code unsubscribe} 在新订阅接管后是 no-op，
 * 因此继任者可以<b>立刻</b>订阅。本用例把旧补偿的退订钉在"比较之前"，
 * 断言继任订阅立即成功、且旧退订放行后仍能收到唤醒。
 */
class FrameWakeListenerCompensationIsolationTest {

	private static final String RUN_ID = "run-compensation-isolation";

	private final GenerationWakeBus bus = new GenerationWakeBus();

	private final FrameWakeListener listener = new FrameWakeListener(this.bus);

	@Test
	void theSuccessorSubscribesImmediatelyAndTheOldCompensationCannotUnsubscribeIt() throws Exception {
		RecordingTarget leaving = new RecordingTarget();
		assertThat(this.listener.register(RUN_ID, leaving)).as("旧连接先建立订阅（gen 1）").isTrue();
		long oldGeneration = this.bus.generationOf(RUN_ID);

		// 旧连接断连：最后一个目标注销 ⇒ 立刻摘桶并发出补偿退订（这里让退订"晚到"）
		this.bus.blockUnsubscribe = true;
		Thread closing = new Thread(() -> this.listener.unregister(RUN_ID, leaving), "old-connection-close");
		closing.start();
		assertThat(this.bus.unsubscribeEntered.await(5, TimeUnit.SECONDS)).as("旧补偿退订必须已发出").isTrue();

		// 继任连接：必须立刻订阅，不等待旧补偿
		RecordingTarget successor = new RecordingTarget();
		AtomicBoolean successorResult = new AtomicBoolean();
		Thread rejoin = new Thread(() -> successorResult.set(this.listener.register(RUN_ID, successor)),
				"subscribe-successor");
		long startedAt = System.nanoTime();
		rejoin.start();
		rejoin.join(500L);
		long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

		assertThat(rejoin.isAlive()).as("继任登记不得等待旧补偿").isFalse();
		assertThat(elapsedMillis).as("继任登记必须立即完成").isLessThan(500L);
		assertThat(successorResult.get()).as("继任订阅必须立即建立").isTrue();
		assertThat(this.listener.subscribedRuns()).isEqualTo(1);
		assertThat(this.bus.generationOf(RUN_ID)).as("总线上的订阅已是新代次").isGreaterThan(oldGeneration);

		// 旧补偿退订放行：按代次比较 ⇒ 不得摘掉继任订阅
		this.bus.releaseUnsubscribe();
		closing.join(TimeUnit.SECONDS.toMillis(5));
		assertThat(closing.isAlive()).isFalse();

		this.bus.inject(RUN_ID, 5L);
		assertThat(successor.wakes).as("旧补偿不得误退继任订阅").containsExactly(5L);
		assertThat(this.listener.subscribedRuns()).as("继任订阅仍在").isEqualTo(1);
	}

	private static final class RecordingTarget implements FrameWakeListener.WakeTarget {

		final List<Long> wakes = new CopyOnWriteArrayList<>();

		@Override
		public void onWake(String runId, long upperBoundSeq) {
			this.wakes.add(upperBoundSeq);
		}

	}

}
