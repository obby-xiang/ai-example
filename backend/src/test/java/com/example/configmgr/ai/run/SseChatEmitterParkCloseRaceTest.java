package com.example.configmgr.ai.run;

import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.common.sse.SseWriteBudget;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>【批次 8D / L-143】</b>挂起请求帧与订阅者关闭同毫秒竞态的确定性回归。
 *
 * <p>
 * 被钉住的窗口（E2E 的 W-01 形态）：{@code completeSubscriptions()} 在 {@code completeWhenDrained}
 * 里"判空为真"之后、真正 {@code emitter.complete()} 之前，另一线程才把请求帧落档并入队。旧实现
 * 直接清队列 + 关闭连接 ⇒ 请求帧<b>已落档却未送达</b>；客户端拿到的是"没有请求帧的收流"。
 *
 * <p>
 * 本用例用可控空队列把这一拍确定化（不靠时序运气）：
 * <ol>
 * <li>订阅者队列里没有待投帧，收尾线程在 {@code isEmpty()} 返回 true 之后暂停；</li>
 * <li>暂停期间另一线程发出 {@code confirm_request}（真实 {@code emit}：取号 → 落档 → 入队）；</li>
 * <li>放行收尾线程：旧实现据"先前的判空"关闭连接并清掉刚入队的帧；</li>
 * <li>修复后：收尾决策与 {@code emit} 的落档/入队串行化（且见在飞 emit 时延后关闭），请求帧
 *     必先在写出线程上送达，再关闭连接。</li>
 * </ol>
 *
 * <p>
 * 断言三件事：①归档里先有请求帧（重挂可补）；②请求帧在 {@code complete} 之前真正写出；
 * ③归档副本与实时副本的 {@code atMs} 同值（同一次取钟，不存在两套时间）。
 *
 * <p>
 * kill：把 {@code completeWhenDrained} 的"判空即收尾"改回不持 {@code emitLock}、不看在本线程的
 * emit 计数 ⇒ 本用例在 {@code ["send:start", "send:confirm_request", "complete"]} 断言处变红。
 */
class SseChatEmitterParkCloseRaceTest {

	private static final String RUN_ID = "run-park-close-race";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final int MAX_FRAME_BYTES = AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES;

	@Test
	void parkRequestFrameArchivedBeforeCloseIsDeliveredEvenWhenCloseObservedAnEmptyQueue() throws Exception {
		InMemoryRunStore store = new InMemoryRunStore();
		InMemoryFrameStore frames = new InMemoryFrameStore();
		SseChatEmitter out = new SseChatEmitter(RUN_ID, store, frames, MAPPER, Runnable::run, 256,
				MAX_FRAME_BYTES, SseWriteBudget.direct(), null, null);
		RecordingEmitter emitter = new RecordingEmitter();
		out.attach(emitter);
		// 同步直执：start 立刻写出，队列在收尾前是真空的（本用例要卡的就是这个"空"）。
		out.start("s-1");
		long startSeq = frames.lastSeq(RUN_ID);
		assertThat(startSeq).isPositive();

		GatedEmptyQueue queue = new GatedEmptyQueue();
		replaceQueue(out, queue);
		queue.arm();

		Thread closer = new Thread(out::completeSubscriptions, "park-close");
		closer.start();
		assertThat(queue.awaitEmptyObserved(5)).as("收尾线程已判空并停在这一拍").isTrue();

		Thread request = new Thread(() -> out.confirmRequest(pending()), "park-request");
		request.start();
		awaitRequestSettledInTheCloseWindow(request);

		queue.release();
		closer.join(5000);
		request.join(5000);

		assertThat(closer.isAlive()).as("收尾线程不得悬挂").isFalse();
		assertThat(request.isAlive()).as("请求帧线程不得悬挂").isFalse();
		assertThat(emitter.events())
				.as("请求帧必须先在已建立的连接上送达，再关闭；旧实现会在未送达时清队列并 complete")
				.containsExactly("send:start", "send:confirm_request", "complete");

		List<Map<String, Object>> archived = frames.readFrom(RUN_ID, startSeq, 0);
		assertThat(archived).extracting(frame -> frame.get("type")).containsExactly("confirm_request");
		Map<String, Object> archivedFrame = archived.get(0);
		Map<String, Object> deliveredFrame = emitter.frameOfType("confirm_request");
		assertThat(deliveredFrame.get("atMs"))
				.as("归档副本与实时副本是同一帧、同一取钟点（同毫秒口径）")
				.isEqualTo(archivedFrame.get("atMs"));

		FrameWire reattached = new FrameWire();
		int replayed = out.replayAndAttach(reattached.emitter(), startSeq, false);
		assertThat(replayed).as("关闭后带 lastSeq 重挂必须能补回请求帧").isEqualTo(1);
		assertThat(reattached.types()).containsExactly("confirm_request");
	}

	// ── 测试替身 ────────────────────────────────────────────────────────────

	/**
	 * <b>【8D / L-143 · 终帧面】（只写不跑）</b>轮终态 {@code done} 与 {@code complete()} 的
	 * 连接关闭顺序：投递池阻塞时，终帧必须先写出、再关闭；归档必须保留终帧供重挂补发。
	 *
	 * <p>
	 * 交错：{@code start} 的 drain 被投递池扣住 ⇒ {@code done} 只能入队 → {@code complete()}
	 * 置终态但把收尾交给持权 drain → 放行投递池 ⇒ 终帧写出后同一 drain 收尾。若关闭抢在写出前，
	 * {@code ["send:start", "send:done", "complete"]} 与 {@code ["done"]} 重挂断言都会变红。
	 */
	@Test
	void turnEndDoneIsDeliveredBeforeTheConnectionClosesWhenDeliveryIsBlocked() throws Exception {
		String runId = RUN_ID + "-done";
		InMemoryRunStore store = new InMemoryRunStore();
		InMemoryFrameStore frames = new InMemoryFrameStore();
		QueueingExecutor delivery = new QueueingExecutor();
		SseChatEmitter out = new SseChatEmitter(runId, store, frames, MAPPER, delivery, 256,
				MAX_FRAME_BYTES, SseWriteBudget.direct(), null, null);
		RecordingEmitter emitter = new RecordingEmitter();
		out.attach(emitter);

		out.start("s-1");
		out.done(Map.of(), "deepseek-flash", Map.of("cancelled", false));
		out.complete();

		assertThat(emitter.events()).as("订阅者还没拿到终帧前不得关闭").isEmpty();
		delivery.runAll();
		assertThat(emitter.events()).as("终帧必须先写出，complete 才能发生")
				.containsExactly("send:start", "send:done", "complete");

		long doneSeq = frames.lastSeq(runId);
		FrameWire reattached = new FrameWire();
		out.replayTo(reattached.emitter(), doneSeq - 1L);
		delivery.runAll();
		assertThat(reattached.types()).as("归档里的终帧必须能被 lastSeq 差量重挂补回")
				.containsExactly("done");
	}

	/** 可控投递：任务先入队，由测试决定何时一次性跑完（模拟投递池被扣住/拥塞）。 */
	static final class QueueingExecutor implements Executor {

		private final java.util.Deque<Runnable> tasks = new java.util.ArrayDeque<>();

		@Override
		public void execute(Runnable command) {
			synchronized (this.tasks) {
				this.tasks.add(command);
			}
		}

		void runAll() {
			while (true) {
				Runnable task;
				synchronized (this.tasks) {
					task = this.tasks.poll();
				}
				if (task == null) {
					return;
				}
				task.run();
			}
		}
	}

	/** 把当前订阅者的队列替换成可控队列（仅测试接缝，不改生产 API）。 */
	private static void replaceQueue(SseChatEmitter out, BlockingQueue<?> queue) throws Exception {
		Field queueField = subscription(out).getClass().getDeclaredField("queue");
		queueField.setAccessible(true);
		queueField.set(subscription(out), queue);
	}

	private static Object subscription(SseChatEmitter out) throws Exception {
		Field subscribersField = SseChatEmitter.class.getDeclaredField("subscribers");
		subscribersField.setAccessible(true);
		List<?> subscribers = (List<?>) subscribersField.get(out);
		assertThat(subscribers).hasSize(1);
		return subscribers.get(0);
	}

	/**
	 * 等请求线程进入"收尾窗口"：要么 emit 已被 emitLock 挡住（修复后：收尾线程持锁判空），
	 * 要么 emit 已经完成/落档（旧实现：判空与入队之间没有锁）。
	 */
	private static void awaitRequestSettledInTheCloseWindow(Thread request) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 2000L;
		while (System.currentTimeMillis() < deadline && request.isAlive()) {
			if (request.getState() == Thread.State.BLOCKED) {
				return;
			}
			Thread.sleep(1L);
		}
	}

	private static PendingToolCall pending() {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId("call-park-close-1");
		pending.setName("start_export");
		pending.setKind(PendingToolCall.KIND_CONFIRM);
		pending.setArguments("{\"taskId\":7}");
		pending.setStatus(PendingToolCall.PENDING);
		return pending;
	}

	/** 只在"第一次看到空队列"处暂停，并故意按暂停前的判空结果返回 true。 */
	static final class GatedEmptyQueue extends LinkedBlockingQueue<Object> {

		private final AtomicBoolean armed = new AtomicBoolean(false);

		private final CountDownLatch observed = new CountDownLatch(1);

		private final CountDownLatch release = new CountDownLatch(1);

		void arm() {
			this.armed.set(true);
		}

		@Override
		public boolean isEmpty() {
			boolean empty = super.isEmpty();
			if (empty && this.armed.compareAndSet(true, false)) {
				this.observed.countDown();
				await(this.release, 5);
				// 真实窗口：判空结果已为 true，随后（本线程做出收尾决定前）别的线程入队了。
				return true;
			}
			return empty;
		}

		boolean awaitEmptyObserved(long seconds) throws InterruptedException {
			return this.observed.await(seconds, TimeUnit.SECONDS);
		}

		void release() {
			this.release.countDown();
		}
	}

	/** 记录"写出/收尾"的事件序列，并保留帧原文（用于同毫秒口径断言）。 */
	static final class RecordingEmitter extends SseEmitter {

		private final List<String> events = new CopyOnWriteArrayList<>();

		private final List<String> payloads = new CopyOnWriteArrayList<>();

		RecordingEmitter() {
			super(-1L);
		}

		@Override
		public void send(Object object) throws IOException {
			String payload = String.valueOf(object);
			this.payloads.add(payload);
			this.events.add("send:" + MAPPER.readTree(payload).path("type").asText());
		}

		@Override
		public void complete() {
			this.events.add("complete");
		}

		List<String> events() {
			return List.copyOf(this.events);
		}

		Map<String, Object> frameOfType(String type) throws IOException {
			for (String payload : this.payloads) {
				@SuppressWarnings("unchecked")
				Map<String, Object> frame = MAPPER.readValue(payload, Map.class);
				if (type.equals(frame.get("type"))) {
					return new LinkedHashMap<>(frame);
				}
			}
			throw new AssertionError("没有收到 " + type + " 帧：" + this.payloads);
		}
	}

	private static void await(CountDownLatch latch, long seconds) {
		try {
			latch.await(seconds, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

}
