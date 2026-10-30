package com.example.configmgr.ai.run;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>返修第 3 条（并入 Task 4 复审遗留）</b>：最后一个<b>已确认</b>目标离开时，
 * 若桶里还有<b>存活但未确认</b>的客人，必须<b>提升一个客人</b>为确认者并保留现有订阅，
 * 不得整桶摘掉。
 *
 * <h2>缺陷形态（现状）</h2>
 * 订阅在途时加入的目标拿的是"未确认"（false），而调用方（{@code SseChatEmitter}）在 false 时
 * <b>不会再主动退订</b> —— 它只是把登记位回退，等下一次 attach/出帧再登记。
 * 于是"最后一个确认者离开"若把整桶摘掉：订阅被释放，而桶里还有客人在正常收流的轮次，
 * 跨实例唤醒从这一刻起<b>静默缺席</b>，直到这个客人自己再出一次帧/重挂才补上 ——
 * 期间别的实例写的帧只能靠前端看门狗/重挂兜底。
 *
 * <p><b>本批口径</b>：确认者离开后若还有存活目标 ⇒ 直接<b>就地提升</b>一个为确认者，
 * 订阅保持不变（同代次、零次 unsubscribe）；这是"自愈"—— 不需要等下一次出帧/attach。
 * 真正的"没有目标"才退订。
 *
 * <p>kill：保持"整桶摘掉"⇒ 首条（unsubscribe 次数为 0）与"客人继续收到 wake"两条同时红。
 */
class FrameWakeListenerPromotionTest {

	private static final String RUN_ID = "run-wake-promotion";

	private final GenerationWakeBus bus = new GenerationWakeBus();

	private final FrameWakeListener listener = new FrameWakeListener(this.bus);

	@Test
	void whenTheLastConfirmedTargetLeavesASurvivingGuestIsPromotedAndKeepsReceivingWakes() throws Exception {
		this.bus.blockSubscribeCall = 1;
		RecordingTarget owner = new RecordingTarget();
		AtomicBoolean ownerResult = new AtomicBoolean();
		Thread submitting = new Thread(() -> ownerResult.set(this.listener.register(RUN_ID, owner)),
				"promotion-owner");
		submitting.start();
		assertThat(this.bus.subscribeEntered.await(5, TimeUnit.SECONDS)).as("发起方订阅必须在途").isTrue();

		RecordingTarget guest = new RecordingTarget();
		AtomicBoolean guestResult = new AtomicBoolean();
		Thread joining = new Thread(() -> guestResult.set(this.listener.register(RUN_ID, guest)),
				"promotion-guest");
		joining.start();
		joining.join(TimeUnit.SECONDS.toMillis(1));
		assertThat(guestResult.get()).as("在途登记 ⇒ 未确认（false）").isFalse();

		this.bus.releaseSubscribe();
		submitting.join(TimeUnit.SECONDS.toMillis(5));
		assertThat(ownerResult.get()).as("发起方订阅成功").isTrue();
		assertThat(this.bus.subscribeCalls.get()).as("只订阅了一次").isEqualTo(1);

		this.listener.unregister(RUN_ID, owner);

		assertThat(this.bus.journal).as("确认者离开时不得退订（客人还在收流）")
			.noneMatch(entry -> entry.startsWith("unsubscribe:"));
		assertThat(this.listener.targetCount(RUN_ID)).as("客人仍在桶里（被提升为确认者）").isEqualTo(1);

		this.bus.inject(RUN_ID, 23L);
		assertThat(guest.wakes).as("提升后客人继续收到跨实例唤醒（自愈，不必等下一次出帧/attach）")
			.containsExactly(23L);

		// 客人再次登记 ⇒ 已确认（幂等，不产生新订阅）
		assertThat(this.listener.register(RUN_ID, guest)).as("提升后再登记返回已确认").isTrue();
		assertThat(this.bus.subscribeCalls.get()).as("重复登记不新建订阅").isEqualTo(1);
	}

	/**
	 * <b>churn 回归</b>：确认者反复进出（attach/断连的高频形态）时，
	 * 只要客人还在就<b>不能</b>产生新的订阅/退订抖动；客人也离开后才退订一次。
	 *
	 * <p>为什么重要：整桶摘掉 + 客人重新登记会形成"订阅/退订"每帧一次的抖动
	 * （Redis 侧 SUBSCRIBE/UNSUBSCRIBE 是容器级 I/O），既放大延迟也放大日志。
	 */
	@Test
	void churnOfConfirmedTargetsDoesNotFlapTheSubscriptionWhileAGuestRemains() throws Exception {
		RecordingTarget anchor = new RecordingTarget();
		assertThat(this.listener.register(RUN_ID, anchor)).as("首个目标直接确认").isTrue();

		// 造一个"未确认"客人：在订阅在途时登记。这里先把订阅退掉（anchor 离开会退订），
		// 再用一次在途订阅把客人灌进桶里。
		this.listener.unregister(RUN_ID, anchor);
		assertThat(this.bus.journal).as("没有目标时正常退订").anyMatch(entry -> entry.startsWith("unsubscribe:"));

		this.bus.blockSubscribeCall = this.bus.subscribeCalls.get() + 1;
		RecordingTarget owner = new RecordingTarget();
		AtomicBoolean ownerResult = new AtomicBoolean();
		Thread submitting = new Thread(() -> ownerResult.set(this.listener.register(RUN_ID, owner)), "churn-owner");
		submitting.start();
		assertThat(this.bus.subscribeEntered.await(5, TimeUnit.SECONDS)).isTrue();
		RecordingTarget guest = new RecordingTarget();
		Thread joining = new Thread(() -> this.listener.register(RUN_ID, guest), "churn-guest");
		joining.start();
		joining.join(TimeUnit.SECONDS.toMillis(1));
		this.bus.releaseSubscribe();
		submitting.join(TimeUnit.SECONDS.toMillis(5));
		assertThat(ownerResult.get()).isTrue();

		int subscribeCallsBefore = this.bus.subscribeCalls.get();
		long unsubscribesBefore = countUnsubscribes();
		for (int i = 0; i < 3; i++) {
			this.listener.unregister(RUN_ID, owner);
			this.listener.register(RUN_ID, owner);
		}

		assertThat(this.bus.subscribeCalls.get()).as("churn 期间不得新建订阅（客人还在）").isEqualTo(subscribeCallsBefore);
		assertThat(countUnsubscribes()).as("churn 期间不得退订").isEqualTo(unsubscribesBefore);

		this.listener.unregister(RUN_ID, owner);
		this.listener.unregister(RUN_ID, guest);
		assertThat(this.listener.targetCount(RUN_ID)).as("真正没有目标才收尾").isZero();
		assertThat(countUnsubscribes()).as("最后一对目标离开后只退订一次").isEqualTo(unsubscribesBefore + 1);
	}

	private long countUnsubscribes() {
		return this.bus.journal.stream().filter(entry -> entry.startsWith("unsubscribe:")).count();
	}

	private static final class RecordingTarget implements FrameWakeListener.WakeTarget {

		final List<Long> wakes = new CopyOnWriteArrayList<>();

		@Override
		public void onWake(String runId, long upperBoundSeq) {
			this.wakes.add(upperBoundSeq);
		}

	}

}
