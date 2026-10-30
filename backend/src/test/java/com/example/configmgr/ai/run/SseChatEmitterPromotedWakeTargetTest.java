package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.common.sse.SseWriteBudget;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>返修第 1 条（四轮）</b>：被<b>提升</b>的客人在其连接关闭时必须被移除，并最终退订 ——
 * 不能只靠"<code>wakeRegistered</code> 的 CAS 成功"来决定要不要 {@code unregister}。
 *
 * <h2>缺陷形态</h2>
 * 客人在订阅在途时登记，{@code FrameWakeListener#register} 回 <b>false</b> ⇒ 写出器把
 * {@link SseChatEmitter} 的 {@code wakeRegistered} 回退为 false（它确实没拿到"已订阅"确认）。
 * 之后确认者离开，监听器按"提升"口径把客人留在桶里并保留订阅（正确）。
 * 但客人自己的连接关闭时，{@code applyWakeSync} 里写的是
 * {@code wakeRegistered.compareAndSet(true, false)} —— 客人的标志是 false，CAS 必然失败，
 * <b>于是永远不会调用 listener.unregister</b>：该 target 与登记句柄永久留在监听器里
 * （桶不空 ⇒ 订阅永不释放；写出器对象也被 map 引用，内存不回收）。
 *
 * <p><b>本批口径</b>：订阅表为空时<b>无条件</b>调用 {@code listener.unregister} ——
 * 监听器按 target 精确匹配，未登记时是 no-op（幂等），因此不依赖 CAS。
 *
 * <p>kill：保留"只在 CAS 成功时退订"⇒ 客人关闭后 {@code targetCount} 仍为 1、没有任何
 * {@code unsubscribe} 流水，两条同时红。
 */
class SseChatEmitterPromotedWakeTargetTest {

	private static final String RUN_ID = "run-promoted-wake-target";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final GenerationWakeBus bus = new GenerationWakeBus();

	private final FrameWakeListener listener = new FrameWakeListener(this.bus);

	private final AiProperties properties = new AiProperties();

	@Test
	void aPromotedGuestIsRemovedAndReleasesTheSubscriptionWhenItsOwnConnectionCloses() throws Exception {
		this.bus.blockSubscribeCall = 1;
		SseChatEmitter owner = emitter();
		Thread attachingOwner = new Thread(() -> owner.attach(new FrameWire().emitter()), "attach-owner");
		attachingOwner.start();
		assertThat(this.bus.subscribeEntered.await(5, TimeUnit.SECONDS)).as("所有者的订阅必须在途").isTrue();

		SseChatEmitter guest = emitter();
		guest.attach(new FrameWire().emitter());
		assertThat(this.listener.targetCount(RUN_ID)).as("客人在途登记：两个目标都在桶里").isEqualTo(2);

		this.bus.releaseSubscribe();
		attachingOwner.join(TimeUnit.SECONDS.toMillis(5));
		assertThat(this.bus.subscribeCalls.get()).as("只订阅了一次").isEqualTo(1);

		// 所有者离开：监听器把客人提升为确认者并保留订阅（返修第 3 条）。
		owner.completeSubscriptions();
		assertThat(this.listener.targetCount(RUN_ID)).as("客人被提升后仍在桶里").isEqualTo(1);
		assertThat(this.bus.journal).as("此时不得退订").noneMatch(entry -> entry.startsWith("unsubscribe:"));

		// 客人自己的连接关闭：必须把它从桶里摘掉，并在桶空后退订。
		guest.completeSubscriptions();

		assertThat(this.listener.targetCount(RUN_ID)).as("被提升的客人关闭后必须被移除").isZero();
		assertThat(this.listener.registrationCount()).as("登记句柄也必须清空（否则写出器被 map 引用）").isZero();
		assertThat(this.bus.journal.stream().filter(entry -> entry.startsWith("unsubscribe:")).count())
			.as("最后一个目标离开 ⇒ 恰好退订一次").isEqualTo(1);
	}

	/** 幂等：没有登记过的写出器（例如从未挂到监听器）关闭时不得抛、不得产生退订。 */
	@Test
	void closingAnEmitterThatNeverRegisteredIsANoOp() {
		SseChatEmitter lonely = emitter();
		lonely.attach(new FrameWire().emitter());
		assertThat(this.listener.targetCount(RUN_ID)).isEqualTo(1);

		lonely.completeSubscriptions();

		assertThat(this.listener.targetCount(RUN_ID)).isZero();
		assertThat(this.listener.registrationCount()).isZero();
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private SseChatEmitter emitter() {
		return new SseChatEmitter(RUN_ID, new InMemoryRunStore(), new InMemoryFrameStore(), MAPPER, Runnable::run,
				AiProperties.Sse.DEFAULT_DELIVERY_QUEUE_CAPACITY,
				AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES, SseWriteBudget.direct(), this.bus, this.listener);
	}

}
