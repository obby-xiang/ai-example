package com.example.configmgr.ai.run;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>订阅在途时登记的目标必须被保留，并在订阅成功后真实收到唤醒</b>（I-B）。
 *
 * <h2>缺陷形态</h2>
 * 订阅在锁外做（Redis I/O）。上一版把在途期间来登记的目标<b>拒掉</b>（回 false 且不登记）；
 * 再上一版让调用方<b>等 2s</b> —— 前者丢目标（调用方只回退登记位，同一次 attach 内不再重试），
 * 后者把 Redis 回调线程拖住。两者都不是"不丢目标"的正确解。
 *
 * <p>本用例钉住非阻塞状态机的契约：在途登记的目标<b>进桶并拿"未确认"（false）</b>；
 * 订阅一旦成功，该目标与发起方<b>一样收到唤醒</b>；之后再用同一目标登记会得到"已确认"（true）。
 * 失败分支见 {@link FrameWakeListenerSubscribeRollbackTest}。
 */
class FrameWakeListenerInFlightRegistrationTest {

	private static final String RUN_ID = "run-in-flight-registration";

	private final GenerationWakeBus bus = new GenerationWakeBus();

	private final FrameWakeListener listener = new FrameWakeListener(this.bus);

	@Test
	void aTargetRegisteredWhileSubscribingIsKeptAndReceivesWakesOnceTheSubscriptionSucceeds() throws Exception {
		this.bus.blockSubscribeCall = 1;
		RecordingTarget owner = new RecordingTarget();
		AtomicBoolean ownerResult = new AtomicBoolean();
		Thread submitting = new Thread(() -> ownerResult.set(this.listener.register(RUN_ID, owner)),
				"subscribe-owner");
		submitting.start();
		assertThat(this.bus.subscribeEntered.await(5, TimeUnit.SECONDS)).as("发起方订阅必须在途").isTrue();

		RecordingTarget late = new RecordingTarget();
		AtomicBoolean lateResult = new AtomicBoolean();
		Thread joining = new Thread(() -> lateResult.set(this.listener.register(RUN_ID, late)), "subscribe-late");
		joining.start();
		joining.join(TimeUnit.SECONDS.toMillis(1));

		assertThat(joining.isAlive()).as("在途登记不得阻塞").isFalse();
		assertThat(lateResult.get()).as("在途 ⇒ 未确认（false），但目标已保留").isFalse();
		assertThat(this.listener.targetCount(RUN_ID)).as("两个目标都必须在桶里").isEqualTo(2);

		this.bus.releaseSubscribe();
		submitting.join(TimeUnit.SECONDS.toMillis(5));
		assertThat(ownerResult.get()).as("发起方订阅成功").isTrue();

		this.bus.inject(RUN_ID, 11L);
		assertThat(owner.wakes).containsExactly(11L);
		assertThat(late.wakes).as("在途登记的目标必须真实收到唤醒（不得丢目标）").containsExactly(11L);

		assertThat(this.listener.register(RUN_ID, late)).as("订阅建立后再登记 ⇒ 已确认").isTrue();
		assertThat(this.listener.targetCount(RUN_ID)).as("重复登记不产生第二个条目").isEqualTo(2);
	}

	private static final class RecordingTarget implements FrameWakeListener.WakeTarget {

		final List<Long> wakes = new CopyOnWriteArrayList<>();

		@Override
		public void onWake(String runId, long upperBoundSeq) {
			this.wakes.add(upperBoundSeq);
		}

	}

}
