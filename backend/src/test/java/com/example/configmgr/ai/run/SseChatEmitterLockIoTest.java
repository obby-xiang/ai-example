package com.example.configmgr.ai.run;

import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.common.sse.SseWriteBudget;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>{@code emitLock} 内不得做 Redis 侧 I/O</b>（资源修复批）。
 *
 * <h2>缺陷形态</h2>
 * 唤醒订阅的生命周期（{@code subscribe/unsubscribe}）与唤醒发布（{@code PUBLISH}）都是 Redis I/O：
 * 前者要起容器/连接（时长不可控），后者是一次网络往返。它们此前落在 {@code emitLock} 里 ——
 * 于是"最后一个订阅者断开触发的退订"或"另一实例的唤醒发布"慢一拍，就会把<b>同一轮的所有出帧</b>
 * （含心跳、含其它订阅者的帧）一起顶在锁后面。修复口径：<b>锁内只置位，I/O 让到锁外</b>，
 * 目标状态按"当前订阅表"收敛（`有连接 ⇒ 已登记；无连接 ⇒ 已退订`），因此事件顺序不变。
 *
 * <p>判据（本类的全部断言）都用一个可观测事实表达：{@link SseChatEmitter#insideEmitLock()}
 * 在订阅/退订/发布的回调瞬间必须为 {@code false}。替身总线把每次回调连同该标志一起记账，
 * 于是"锁内 I/O"是可断言的，不靠注释承诺。
 *
 * <p>相邻路径（顺序不退化）也在本类：帧的到达顺序仍是 seq 升序，且"落档 → 发布"的先后不变。
 */
class SseChatEmitterLockIoTest {

	private static final String RUN_ID = "run-lock-io";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final InMemoryFrameStore frames = new InMemoryFrameStore();

	private final RecordingWakeBus bus = new RecordingWakeBus();

	private final FrameWakeListener listener = new FrameWakeListener(this.bus);

	@Test
	void wakeSubscriptionIsEstablishedOutsideTheEmitLock() {
		SseChatEmitter out = emitter();
		FrameWire wire = FrameWire.attachTo(out);

		assertThat(this.bus.callbacks).as("有订阅者 ⇒ 恰好登记一次，且发生在锁外")
			.containsExactly("subscribe:insideLock=false");
		assertThat(wire.size()).isZero();
	}

	@Test
	void wakeSubscriptionDuringReattachIsEstablishedOutsideTheEmitLock() {
		SseChatEmitter out = emitter();
		out.start("s-1");
		this.bus.callbacks.clear();

		FrameWire reattached = new FrameWire();
		out.replayAndAttach(reattached.emitter(), 0L, false);

		assertThat(this.bus.callbacks).as("重挂路径的登记也在锁外（且先于回放读）")
			.containsExactly("subscribe:insideLock=false");
		assertThat(reattached.size()).as("回放照常").isEqualTo(1);
	}

	@Test
	void wakeUnsubscribeHappensOutsideTheEmitLock() {
		SseChatEmitter out = emitter();
		FrameWire wire = FrameWire.attachTo(out);
		out.start("s-1");
		assertThat(wire.size()).isEqualTo(1);
		this.bus.callbacks.clear();

		out.complete();

		assertThat(this.bus.callbacks).as("最后一个订阅者离开 ⇒ 退订，且发生在锁外").containsExactly(
				"unsubscribe:insideLock=false");
	}

	@Test
	void wakePublishHappensOutsideTheEmitLock() {
		SseChatEmitter out = emitter();
		FrameWire wire = FrameWire.attachTo(out);
		this.bus.callbacks.clear();

		out.start("s-1");
		out.delta("一");
		out.delta("二");

		assertThat(this.bus.callbacks).as("PUBLISH 全部在锁外（锁内只算号）")
			.containsExactly("publish:insideLock=false", "publish:insideLock=false", "publish:insideLock=false");
		assertThat(FrameWire.normalize(wire.frames())).extracting(frame -> frame.get("seq"))
			.as("相邻路径：让位 I/O 不得改变帧的到达顺序（seq 升序）").containsExactly(1L, 2L, 3L);
		assertThat(this.bus.published).as("发布上界与落档号逐条一致").containsExactly(1L, 2L, 3L);
		assertThat(wire.size()).as("本地扇出不依赖唤醒（快路径）").isEqualTo(3);
	}

	private SseChatEmitter emitter() {
		SseChatEmitter out = new SseChatEmitter(RUN_ID, this.store, this.frames, MAPPER, Runnable::run,
				AiProperties.Sse.DEFAULT_DELIVERY_QUEUE_CAPACITY, AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES,
				SseWriteBudget.direct(), this.bus, this.listener);
		this.bus.emitter.set(out);
		return out;
	}

	/**
	 * 记账总线：每次订阅/退订/发布都记下 {@code 动作:insideLock=...}。
	 *
	 * <p>发布仍<b>同步</b>回调本地订阅者（与生产 {@code RedisFrameWakeBus} 的"另起线程投递"相比
	 * 更严格：它把"本线程内重入 onWake"这条最难的路径也压进断言）。
	 */
	private static final class RecordingWakeBus implements FrameWakeBus {

		final List<String> callbacks = new CopyOnWriteArrayList<>();

		final List<Long> published = new CopyOnWriteArrayList<>();

		final AtomicReference<SseChatEmitter> emitter = new AtomicReference<>();

		private final Map<String, LongConsumer> subscribers = new ConcurrentHashMap<>();

		@Override
		public boolean subscribe(String runId, LongConsumer onWake) {
			record("subscribe");
			this.subscribers.put(runId, onWake);
			return true;
		}

		@Override
		public void unsubscribe(String runId) {
			record("unsubscribe");
			this.subscribers.remove(runId);
		}

		@Override
		public void publish(String runId, long upperBoundSeq) {
			record("publish");
			this.published.add(upperBoundSeq);
			LongConsumer target = this.subscribers.get(runId);
			if (target != null) {
				target.accept(upperBoundSeq);
			}
		}

		private void record(String action) {
			SseChatEmitter current = this.emitter.get();
			this.callbacks.add(action + ":insideLock=" + (current != null && current.insideEmitLock()));
		}

	}

}
