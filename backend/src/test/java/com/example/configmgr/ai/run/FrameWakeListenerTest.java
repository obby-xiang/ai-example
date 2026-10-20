package com.example.configmgr.ai.run;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B23L1 批① · 唤醒监听的生命周期与扇出（M8/M10）。
 *
 * <p>
 * 两条硬约束：<b>按连接</b>（首个目标订阅、最后一个目标退订，重复登记不重复订阅）与
 * <b>单目标失败不拖累其他目标</b>（一个 run 的坏目标不能把同拍其他 run 的唤醒吞掉）。
 */
class FrameWakeListenerTest {

	private static final String RUN_ID = "run-wake";

	private final FakeWakeBus bus = new FakeWakeBus();

	private final FrameWakeListener listener = new FrameWakeListener(this.bus);

	@Test
	void firstTargetSubscribesAndLastTargetUnsubscribes() {
		RecordingTarget first = new RecordingTarget("first");
		RecordingTarget second = new RecordingTarget("second");

		this.listener.register(RUN_ID, first);
		assertThat(this.bus.subscribes).as("首个目标 ⇒ 建订阅").isEqualTo(1);
		this.listener.register(RUN_ID, second);
		assertThat(this.bus.subscribes).as("第二个目标不重复订阅（按 run 一条订阅）").isEqualTo(1);
		assertThat(this.listener.targetCount(RUN_ID)).isEqualTo(2);

		this.listener.unregister(RUN_ID, first);
		assertThat(this.bus.unsubscribes).as("还有目标在 ⇒ 不退订").isZero();
		assertThat(this.bus.subscribed(RUN_ID)).isTrue();

		this.listener.unregister(RUN_ID, second);
		assertThat(this.bus.unsubscribes).as("最后一个目标 ⇒ 退订").isEqualTo(1);
		assertThat(this.bus.subscribed(RUN_ID)).isFalse();
		assertThat(this.listener.targetCount(RUN_ID)).isZero();
	}

	@Test
	void duplicateRegistrationIsIdempotent() {
		RecordingTarget target = new RecordingTarget("t");
		this.listener.register(RUN_ID, target);
		this.listener.register(RUN_ID, target);

		assertThat(this.listener.targetCount(RUN_ID)).isEqualTo(1);
		this.listener.unregister(RUN_ID, target);
		assertThat(this.bus.subscribed(RUN_ID)).as("注销一次即释放（目标集按引用去重）").isFalse();
	}

	@Test
	void wakeIsDispatchedToEveryRegisteredTarget() {
		RecordingTarget first = new RecordingTarget("first");
		RecordingTarget second = new RecordingTarget("second");
		this.listener.register(RUN_ID, first);
		this.listener.register(RUN_ID, second);

		this.bus.publish(RUN_ID, 7L);

		assertThat(first.wakes).containsExactly(7L);
		assertThat(second.wakes).containsExactly(7L);
	}

	@Test
	void aFailingTargetDoesNotSwallowTheWake() {
		RecordingTarget broken = new RecordingTarget("broken") {
			@Override
			public void onWake(String runId, long upperBoundSeq) {
				throw new IllegalStateException("目标自己的故障");
			}
		};
		RecordingTarget healthy = new RecordingTarget("healthy");
		this.listener.register(RUN_ID, broken);
		this.listener.register(RUN_ID, healthy);

		this.bus.publish(RUN_ID, 3L);

		assertThat(healthy.wakes).as("一个目标抛异常不影响其他目标").containsExactly(3L);
	}

	@Test
	void unregisterOfAnUnknownTargetIsNoOp() {
		this.listener.unregister(RUN_ID, new RecordingTarget("ghost"));
		assertThat(this.bus.unsubscribes).isZero();
		assertThat(this.listener.subscribedRuns()).isZero();
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private static class RecordingTarget implements FrameWakeListener.WakeTarget {

		final List<Long> wakes = new CopyOnWriteArrayList<>();

		final String name;

		RecordingTarget(String name) {
			this.name = name;
		}

		@Override
		public void onWake(String runId, long upperBoundSeq) {
			this.wakes.add(upperBoundSeq);
		}

	}

}
