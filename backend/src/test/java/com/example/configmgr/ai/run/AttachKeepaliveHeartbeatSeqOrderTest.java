package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * 心跳的 {@code heartbeatSeq} <b>数值序必须等于入队序</b>：同一写出器上存在两个心跳生产者 ——
 * 驱动侧的 {@link SseChatEmitter#heartbeat(Map)}（取号在 {@code emitLock} 内，见 {@code emitInternal}）
 * 与"本实例不是驱动方"时的本地保活节拍（{@link SseChatEmitter} 的 attach keepalive）。
 *
 * <h2>被钉住的缺陷</h2>
 * 保活节拍旧实现<b>在锁外</b> {@code heartbeatSeq.incrementAndGet()}，再序列化、再进锁入队。
 * 于是"取号"与"入队"之间有一段窗口：另一个生产者可以在这段窗口里拿到更大的号并先入队，
 * 订阅者收到的 {@code heartbeatSeq} 就会<b>回退</b>（2 之后再来 1）。心跳不占业务 {@code seq}、
 * 也不参与去重，所以不影响 seq 连续性；但"心跳序号随到达顺序单调"是可观测面的事实，
 * 一旦前端/排障拿它当时间线就会误判（看起来像时间倒流）。
 *
 * <h2>本用例怎么把窗口变成确定性事件</h2>
 * 窗口里恰好有一次 JSON 序列化（{@code writeValueAsBytes}）。用例用一个<b>可闸门</b>的
 * {@link ObjectMapper} 把"第一个心跳（{@code heartbeatSeq=1}）的序列化"卡住：
 * <ol>
 * <li>保活节拍走到序列化 ⇒ 闸门拦住（旧实现：<b>没有</b>持锁；修复后：<b>持锁</b>）；</li>
 * <li>此时驱动侧再发一个心跳（另一个线程）—— 旧实现能进锁并先入队（号更大），修复后卡在锁上；</li>
 * <li>放行闸门，按到达顺序收集 {@code heartbeatSeq}：必须是递增（旧实现是 2 → 1）。</li>
 * </ol>
 *
 * <p>mapping 语义不变：{@code liveSeq = 0} 的"心跳扇出分支"（不补齐、不推进游标、不发布唤醒）照旧。
 */
class AttachKeepaliveHeartbeatSeqOrderTest {

	private static final String RUN_ID = "run-keepalive-heartbeat-seq";

	private static final ObjectMapper PLAIN = new ObjectMapper();

	@Test
	void heartbeatSequenceIsMonotonicInArrivalOrderAcrossBothProducers() throws Exception {
		HeartbeatGate gate = new HeartbeatGate();
		SseChatEmitter out = new SseChatEmitter(RUN_ID, new InMemoryRunStore(), new BlockingMapper(gate),
				Runnable::run, AiProperties.Sse.DEFAULT_DELIVERY_QUEUE_CAPACITY);
		List<String> delivered = new CopyOnWriteArrayList<>();
		ScheduledExecutorService keepalive = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "test-attach-keepalive");
			thread.setDaemon(true);
			return thread;
		});
		Thread competing = null;
		try {
			out.enableAttachKeepalive(keepalive, 5L);
			out.attach(capturingEmitter(delivered));

			assertThat(gate.awaitEntered(5, TimeUnit.SECONDS))
				.as("保活节拍应发出第一枚心跳并走到序列化（闸门就在这一步）").isTrue();

			competing = new Thread(() -> out.heartbeat(Map.of("phase", "driving")), "test-driving-heartbeat");
			competing.setDaemon(true);
			competing.start();
			// 给"另一个生产者"足够时间在旧实现里完成取号 + 入队（修复后它会卡在 emitLock 上）
			Thread.sleep(150L);
			gate.release();

			competing.join(5_000L);
			awaitHeartbeats(delivered, 3, 5_000L);
			List<Long> seqs = heartbeatSeqs(delivered);
			assertThat(seqs).as("本轮应至少投递三枚心跳（保活 + 驱动侧 + 后续节拍）").hasSizeGreaterThanOrEqualTo(3);
			assertThat(seqs)
				.as("heartbeatSeq 的数值序必须与到达（入队）序一致：取号不得在 emitLock 之外"
						+ "（旧实现的到达序是 2 → 1）")
				.isSorted();
		}
		finally {
			keepalive.shutdownNow();
			if (competing != null) {
				competing.interrupt();
			}
		}
	}

	/** 有界等待：至少收到 {@code wanted} 枚心跳（保活节拍是 5ms 一次，避免"读得太早"的用例竞争）。 */
	private static void awaitHeartbeats(List<String> delivered, int wanted, long timeoutMs) throws Exception {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			if (heartbeatSeqs(delivered).size() >= wanted) {
				return;
			}
			Thread.sleep(10L);
		}
	}

	/** 取出投递帧里的 {@code heartbeatSeq}（到达顺序）。 */
	private static List<Long> heartbeatSeqs(List<String> delivered) throws Exception {
		List<Long> seqs = new ArrayList<>();
		for (String payload : delivered) {
			Map<?, ?> frame = PLAIN.readValue(payload, Map.class);
			if (!RunStore.HEARTBEAT_TYPE.equals(String.valueOf(frame.get("type")))) {
				continue;
			}
			Object seq = frame.get("heartbeatSeq");
			assertThat(seq).as("心跳帧必须带 heartbeatSeq：%s", payload).isInstanceOf(Number.class);
			seqs.add(((Number) seq).longValue());
		}
		return seqs;
	}

	private static SseEmitter capturingEmitter(List<String> delivered) {
		SseEmitter emitter = mock(SseEmitter.class);
		try {
			doAnswer(invocation -> {
				Object payload = invocation.getArgument(0);
				delivered.add(String.valueOf(payload));
				return null;
			}).when(emitter).send(any(Object.class));
		}
		catch (java.io.IOException ex) {
			throw new IllegalStateException("桩装配失败", ex);
		}
		return emitter;
	}

	/** 只卡一次的闸门：把 "第一个心跳的序列化" 变成可控的会合点。 */
	private static final class HeartbeatGate {

		private final CountDownLatch entered = new CountDownLatch(1);

		private final CountDownLatch released = new CountDownLatch(1);

		private final AtomicBoolean armed = new AtomicBoolean(true);

		boolean awaitEntered(long timeout, TimeUnit unit) throws InterruptedException {
			return this.entered.await(timeout, unit);
		}

		void release() {
			this.released.countDown();
		}

		void onHeartbeatSerialization(Map<?, ?> frame) {
			Object seq = frame.get("heartbeatSeq");
			if (!(seq instanceof Number number) || number.longValue() != 1L) {
				return;
			}
			if (!this.armed.compareAndSet(true, false)) {
				return;
			}
			this.entered.countDown();
			try {
				if (!this.released.await(10, TimeUnit.SECONDS)) {
					throw new IllegalStateException("闸门未在 10s 内放行（用例自身超时，避免线程悬挂）");
				}
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("闸门等待被中断", ex);
			}
		}

	}

	/**
	 * 可闸门的 mapper：{@link SseChatEmitter} 构造时会对 mapper 做 {@code copy()}，因此这里必须
	 * 覆写 {@code copy()}（Jackson 的默认实现只复制 {@code ObjectMapper} 自身，会丢掉本类的闸门）。
	 */
	private static final class BlockingMapper extends ObjectMapper {

		private final HeartbeatGate gate;

		BlockingMapper(HeartbeatGate gate) {
			this.gate = gate;
		}

		private BlockingMapper(BlockingMapper source) {
			super(source);
			this.gate = source.gate;
		}

		@Override
		public ObjectMapper copy() {
			return new BlockingMapper(this);
		}

		@Override
		public byte[] writeValueAsBytes(Object value) throws com.fasterxml.jackson.core.JsonProcessingException {
			if (value instanceof Map<?, ?> frame && RunStore.HEARTBEAT_TYPE.equals(String.valueOf(frame.get("type")))) {
				this.gate.onHeartbeatSerialization(frame);
			}
			return super.writeValueAsBytes(value);
		}

	}

}
