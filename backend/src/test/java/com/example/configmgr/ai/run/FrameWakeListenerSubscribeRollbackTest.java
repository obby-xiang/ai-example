package com.example.configmgr.ai.run;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>订阅失败必须按桶完整回滚</b>（含在途期间登记的目标）：失败方与在途方一起撤，
 * 后续目标重新走订阅而不是被当成"已有订阅"。
 *
 * <h2>缺陷形态</h2>
 * 旧实现只在"桶里恰好只剩我"时才撤登记（{@code set.size() == 1}）。订阅在途时第二个目标
 * 登记进同一个桶（{@code set.size() == 2}），订阅随后失败 ——
 * <ul>
 * <li>失败方的登记位留在表里（永远等不到退订）；</li>
 * <li>后来者被当成"已有订阅"，{@code wakeRegistered} 从此为真，跨实例唤醒整段静默缺席。</li>
 * </ul>
 *
 * <p>本用例把"订阅在途"钉成确定的一拍：发起方的 {@code subscribe} 阻塞在总线上，另一个目标
 * 在途登记（非阻塞、拿"未确认"、目标保留），随后让订阅失败。断言：失败后桶被<b>完整</b>回滚
 * （目标数/桶数为零），后续目标重新走订阅并<b>真的</b>收到唤醒。
 */
class FrameWakeListenerSubscribeRollbackTest {

	private static final String RUN_ID = "run-subscribe-rollback";

	private final GenerationWakeBus bus = new GenerationWakeBus();

	private final FrameWakeListener listener = new FrameWakeListener(this.bus);

	@Test
	void aFailedSubscribeRollsBackTheBucketIncludingTargetsRegisteredWhileSubscribing() throws Exception {
		this.bus.blockSubscribeCall = 1;
		this.bus.blockedSubscribeSucceeds = false;
		RecordingTarget owner = new RecordingTarget();
		AtomicBoolean ownerResult = new AtomicBoolean();
		Thread submitting = new Thread(() -> ownerResult.set(this.listener.register(RUN_ID, owner)),
				"subscribe-owner");
		submitting.start();
		assertThat(this.bus.subscribeEntered.await(5, TimeUnit.SECONDS)).as("订阅必须已经进入总线").isTrue();

		RecordingTarget late = new RecordingTarget();
		AtomicBoolean lateResult = new AtomicBoolean();
		Thread joining = new Thread(() -> lateResult.set(this.listener.register(RUN_ID, late)), "subscribe-late");
		joining.start();
		joining.join(TimeUnit.SECONDS.toMillis(1));

		assertThat(joining.isAlive()).as("在途登记不得阻塞").isFalse();
		assertThat(lateResult.get()).as("未确认 ⇒ false").isFalse();
		assertThat(this.listener.targetCount(RUN_ID)).as("在途目标已保留（失败时才一起回滚）").isEqualTo(2);

		this.bus.releaseSubscribe();
		submitting.join(TimeUnit.SECONDS.toMillis(5));

		assertThat(ownerResult.get()).as("订阅失败 ⇒ 发起方拿到 false 以便重试").isFalse();
		assertThat(this.listener.targetCount(RUN_ID)).as("失败后桶必须完整回滚（含在途期间的目标）").isZero();
		assertThat(this.listener.subscribedRuns()).as("不得留一条没人订阅的登记").isZero();
		assertThat(this.bus.subscribed(RUN_ID)).as("总线上也不得有订阅").isFalse();

		// 后续目标必须重新建订阅，而不是被当成"已有订阅"
		RecordingTarget later = new RecordingTarget();
		assertThat(this.listener.register(RUN_ID, later)).as("回滚后新目标必须真正建立订阅").isTrue();
		assertThat(this.bus.subscribeCalls.get()).as("首次失败 + 回滚后重建 = 两次订阅尝试").isEqualTo(2);
		this.bus.inject(RUN_ID, 42L);
		assertThat(later.wakes).as("新订阅必须能收到唤醒").containsExactly(42L);
	}

	private static final class RecordingTarget implements FrameWakeListener.WakeTarget {

		final List<Long> wakes = new CopyOnWriteArrayList<>();

		@Override
		public void onWake(String runId, long upperBoundSeq) {
			this.wakes.add(upperBoundSeq);
		}

	}

}
