package com.example.configmgr.ai.run;

import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.common.sse.SseWriteBudget;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>【L-143 / W-01】</b>drain 交接与收尾的确定性交错：<b>旧 drain 不得在新 drain 已取走尾帧、
 * 尚未写出时收尾</b>。
 *
 * <p>
 * 交错（由测试队列与执行器复现，不依赖时序运气、不新增生产钩子）：
 * <ol>
 * <li>drain1 写完第一帧，最后一次 {@code poll} 观察到空队列（测试队列在"返回 null 前"暂停）；</li>
 * <li>暂停期间第二帧入队（模拟 {@code ConfirmGate.await} 先 emit 请求帧、再
 *     {@code completeSubscriptions()}），随后 {@code completeSubscriptions()} 置
 *     {@code sub.terminating=true}（draining 仍被 drain1 持有 ⇒ 收尾只能交给 drain1）；</li>
 * <li>放行 drain1：它进 finally，{@code draining=false} → 看到第二帧 → CAS 抢回并提交 drain2；</li>
 * <li>drain2 取走第二帧（测试队列在"返回帧之前"暂停）⇒ 队列变空，但帧还没写出；</li>
 * <li>旧实现随后执行 {@code (terminal||terminating) && queue.isEmpty()} ⇒ {@code completeOne}
 *     抢在 drain2 写出前关闭连接；生产 {@code SseEmitter} 上这次写出必然失败 ⇒ 请求帧静默丢失
 *     （W-01 的"未收到 frontend_tool_request"形态）。</li>
 * </ol>
 *
 * <p>
 * 断言口径：第二帧必须在收尾之前真正写进 emitter（{@link TerminalEmitter} 在 complete 之后
 * {@code send} 抛错，与真实 {@code ResponseBodyEmitter} 同语义）。
 *
 * <p>
 * kill：把 {@code drain} 的 finally 改回"提交接管 drain 之后仍继续判空收尾"⇒ 本用例在
 * {@code ["start", "frontend_tool_request"]} 断言处变红（第二帧丢失）。
 */
class SseChatEmitterDrainHandoffTest {

	private static final String RUN_ID = "run-drain-handoff";

	@Test
	void frameTakenByHandoffDrainIsNotLostWhenOldDrainSeesAnEmptyQueue() throws Exception {
		GatedQueue queue = new GatedQueue();
		HandoffExecutor executor = new HandoffExecutor(queue);
		TerminalEmitter emitter = new TerminalEmitter();

		SseChatEmitter out = new SseChatEmitter(RUN_ID, new InMemoryRunStore(), new ObjectMapper(), executor, 256,
				AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES, SseWriteBudget.direct());
		out.attach(emitter);
		replaceQueue(out, queue);

		out.start("s-1");
		assertThat(queue.awaitFirstEmptyPoll(5)).as("drain1 跑到最后一次 poll 且看到空队列").isTrue();

		// 请求帧在 completeSubscriptions 之前 emit（ConfirmGate.await 的真实顺序）
		out.frontendToolRequest(pending(), 120);
		out.completeSubscriptions();

		queue.holdNextTailPoll();
		queue.releaseFirstEmptyPoll();
		assertThat(queue.awaitTailTaken(5)).as("接管 drain 已取走尾帧（尚未写出）").isTrue();

		// drain1 的 finally 必须在此刻已经跑完（旧实现会在这一步 complete，修复后跳过）。
		// 以线程 join 定序，而不是等 emitter.completed —— 修复后的正确行为恰恰是不在这里 complete。
		assertThat(executor.awaitTaskFinished(0, 5)).as("旧 drain 已退出 finally").isTrue();

		queue.releaseTailPoll();
		assertThat(emitter.awaitTwoSendAttempts(5)).as("接管 drain 已尝试写出第二帧").isTrue();
		assertThat(emitter.awaitCompleted(5)).as("接管 drain 排空后收尾（连接不得悬挂）").isTrue();

		assertThat(emitter.types()).as("第二帧必须在收尾之前送达；被旧 drain 的 complete 抢跑即丢失")
				.containsExactly("start", "frontend_tool_request");
	}

	/**
	 * <b>【L-143 / I-1】</b>{@code completeSubscriptions()} 的收尾意图落在"drain 已释放投递权、
	 * 正在判空"的窗口里，不得被丢掉。
	 *
	 * <p>
	 * 交错（由 {@link ReleaseWindowQueue} 卡住"释放后的第一次判空"复现）：
	 * <ol>
	 * <li>drain 在 finally 判空、读到 {@code terminating=false}，释放投递权后准备退出；</li>
	 * <li>此刻另一个持权者存在（测试 CAS 抢到标志，模拟 {@code completeWhenDrained} 失败时的
	 *     持权者），{@code completeSubscriptions()} 落下 {@code sub.terminating=true}，
	 *     {@code completeWhenDrained} 的 CAS 失败 ⇒ 收尾责任交给持权者；</li>
	 * <li>持权者随后离开（测试释放标志）且<b>没有</b> complete —— 旧实现只复查"帧面"
	 *     （{@code canTakeOverDrain}，队列空即 false），没有再读终态意图 ⇒ 无人 complete，
	 *     连接悬挂（{@code completeSubscriptions} 整拍被错过）。</li>
	 * </ol>
	 *
	 * <p>
	 * kill：把释放后的复查改回"只看 canTakeOverDrain"⇒ 本用例在"收尾意图必须落地"断言处
	 * 变红（超时未 complete）。
	 */
	@Test
	void completionIntentArrivingWhileDrainReleasesIsNotLost() throws Exception {
		TerminalEmitter emitter = new TerminalEmitter();
		AtomicBoolean draining = new AtomicBoolean(false);
		ReleaseWindowQueue queue = new ReleaseWindowQueue(draining);
		Executor executor = task -> {
			Thread thread = new Thread(task, "drain-release-window");
			thread.setDaemon(true);
			thread.start();
		};

		SseChatEmitter out = new SseChatEmitter(RUN_ID, new InMemoryRunStore(), new ObjectMapper(), executor, 256,
				AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES, SseWriteBudget.direct());
		out.attach(emitter);
		replaceQueue(out, queue);
		replaceDraining(out, draining);

		out.start("s-1");
		assertThat(queue.awaitReleaseWindow(5)).as("drain 已释放投递权并进入判空").isTrue();

		// 模拟"completeWhenDrained 的 CAS 失败"：另有一个持权者，随后它离开且不 complete。
		assertThat(draining.compareAndSet(false, true)).isTrue();
		out.completeSubscriptions();
		draining.compareAndSet(true, false);

		queue.releaseReleaseWindow();
		assertThat(emitter.awaitCompleted(2)).as("completeSubscriptions 的收尾意图必须落地，不能悬挂").isTrue();
	}

	// ── 测试替身 ────────────────────────────────────────────────────────────

	/** 把当前订阅者的队列替换成可控队列（仅测试接缝，不改生产 API）。 */
	private static void replaceQueue(SseChatEmitter out, BlockingQueue<?> queue) throws Exception {
		Field queueField = subscription(out).getClass().getDeclaredField("queue");
		queueField.setAccessible(true);
		queueField.set(subscription(out), queue);
	}

	/** 把订阅者的 drain 独占标志替换成可控 AtomicBoolean（仅测试接缝）。 */
	private static void replaceDraining(SseChatEmitter out, AtomicBoolean draining) throws Exception {
		Field field = subscription(out).getClass().getDeclaredField("draining");
		field.setAccessible(true);
		field.set(subscription(out), draining);
	}

	private static Object subscription(SseChatEmitter out) throws Exception {
		Field subscribersField = SseChatEmitter.class.getDeclaredField("subscribers");
		subscribersField.setAccessible(true);
		List<?> subscribers = (List<?>) subscribersField.get(out);
		assertThat(subscribers).hasSize(1);
		return subscribers.get(0);
	}

	/**
	 * 在"投递权已释放（{@code draining=false}）后的第一次空队列判空"处暂停。
	 * 旧实现这里只剩"帧面"复查；新实现必须在这一拍之后还能读到终态收尾意图。
	 */
	static final class ReleaseWindowQueue extends LinkedBlockingQueue<Object> {

		private final AtomicBoolean draining;

		private final AtomicBoolean paused = new AtomicBoolean(false);

		private final CountDownLatch reached = new CountDownLatch(1);

		private final CountDownLatch release = new CountDownLatch(1);

		ReleaseWindowQueue(AtomicBoolean draining) {
			this.draining = draining;
		}

		@Override
		public boolean isEmpty() {
			boolean empty = super.isEmpty();
			if (empty && !this.draining.get() && this.paused.compareAndSet(false, true)) {
				this.reached.countDown();
				await(this.release, 5);
				return super.isEmpty();
			}
			return empty;
		}

		boolean awaitReleaseWindow(long seconds) throws InterruptedException {
			return this.reached.await(seconds, TimeUnit.SECONDS);
		}

		void releaseReleaseWindow() {
			this.release.countDown();
		}
	}

	/**
	 * 可暂停的队列：两次接缝分别卡在"返回 null 之前"与"返回帧之前"。
	 *
	 * <p>
	 * 只覆写 {@code poll()}（drain 的唯一取帧入口）；{@code isEmpty()/clear()} 用
	 * {@link LinkedBlockingQueue} 的原实现，队列状态因此与真实路径一致。
	 */
	static final class GatedQueue extends LinkedBlockingQueue<Object> {

		private final CountDownLatch firstEmptyPoll = new CountDownLatch(1);

		private final CountDownLatch releaseFirstEmptyPoll = new CountDownLatch(1);

		private final CountDownLatch tailTaken = new CountDownLatch(1);

		private final CountDownLatch releaseTail = new CountDownLatch(1);

		private final AtomicBoolean pauseOnEmpty = new AtomicBoolean(true);

		private volatile boolean holdTail;

		@Override
		public Object poll() {
			Object frame = super.poll();
			if (frame == null) {
				if (this.pauseOnEmpty.compareAndSet(true, false)) {
					this.firstEmptyPoll.countDown();
					await(this.releaseFirstEmptyPoll, 5);
				}
				return null;
			}
			if (this.holdTail) {
				this.tailTaken.countDown();
				await(this.releaseTail, 5);
			}
			return frame;
		}

		boolean awaitFirstEmptyPoll(long seconds) throws InterruptedException {
			return this.firstEmptyPoll.await(seconds, TimeUnit.SECONDS);
		}

		void releaseFirstEmptyPoll() {
			this.releaseFirstEmptyPoll.countDown();
		}

		void holdNextTailPoll() {
			this.holdTail = true;
		}

		boolean awaitTailTaken(long seconds) throws InterruptedException {
			return this.tailTaken.await(seconds, TimeUnit.SECONDS);
		}

		void releaseTailPoll() {
			this.releaseTail.countDown();
		}
	}

	/**
	 * 每次提交一个任务到独立线程；<b>第二次提交会等到接管 drain 已取走尾帧才返回</b> ——
	 * 这样旧 drain 的"提交后继续判空收尾"与"新 drain 已取帧未写出"才成为确定性交错。
	 */
	static final class HandoffExecutor implements Executor {

		private final GatedQueue queue;

		private final AtomicInteger submissions = new AtomicInteger();

		private final List<Thread> threads = new CopyOnWriteArrayList<>();

		HandoffExecutor(GatedQueue queue) {
			this.queue = queue;
		}

		@Override
		public void execute(Runnable task) {
			int index = this.submissions.incrementAndGet();
			Thread thread = new Thread(task, "drain-" + index);
			thread.setDaemon(true);
			this.threads.add(thread);
			thread.start();
			if (index >= 2) {
				try {
					this.queue.awaitTailTaken(5);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			}
		}

		boolean awaitTaskFinished(int index, long seconds) throws InterruptedException {
			Thread thread = this.threads.get(index);
			thread.join(TimeUnit.SECONDS.toMillis(seconds));
			return !thread.isAlive();
		}
	}

	/**
	 * complete 之后再 {@code send} 必须抛错 —— 与真实 {@code ResponseBodyEmitter} 同语义
	 * （生产表现就是 W-01 的帧丢失，而不是"晚到但还能送达"）。
	 */
	static final class TerminalEmitter extends SseEmitter {

		private final ObjectMapper mapper = new ObjectMapper();

		private final List<String> types = new CopyOnWriteArrayList<>();

		private final AtomicBoolean completed = new AtomicBoolean(false);

		private final CountDownLatch completedLatch = new CountDownLatch(1);

		private final CountDownLatch twoSendAttempts = new CountDownLatch(2);

		TerminalEmitter() {
			super(-1L);
		}

		@Override
		public void send(Object object) throws IOException {
			if (this.completed.get()) {
				// 失败路径也要计"已尝试"，否则测试会一直等第二次写出
				this.twoSendAttempts.countDown();
				throw new IllegalStateException("ResponseBodyEmitter has already completed");
			}
			this.types.add(this.mapper.readTree(String.valueOf(object)).path("type").asText());
			// 成功路径先记录类型再计数：awaitSendAttempts 返回时 types 已是最新（无断言竞态）
			this.twoSendAttempts.countDown();
		}

		@Override
		public void complete() {
			this.completed.set(true);
			this.completedLatch.countDown();
		}

		List<String> types() {
			return List.copyOf(this.types);
		}

		boolean awaitTwoSendAttempts(long seconds) throws InterruptedException {
			return this.twoSendAttempts.await(seconds, TimeUnit.SECONDS);
		}

		boolean awaitCompleted(long seconds) throws InterruptedException {
			return this.completedLatch.await(seconds, TimeUnit.SECONDS);
		}
	}

	private static PendingToolCall pending() {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId("call-w01");
		pending.setName("ui_snapshot");
		pending.setKind(PendingToolCall.KIND_FRONTEND);
		pending.setArguments("{\"limit\":10}");
		pending.setStatus(PendingToolCall.PENDING);
		return pending;
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
