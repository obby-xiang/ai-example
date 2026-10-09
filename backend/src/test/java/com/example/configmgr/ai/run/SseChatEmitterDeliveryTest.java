package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * T3-1 慢订阅者隔离的新机制单测（设计卡 R1 裁决 + 施工注记 R2/R3 的锚定）。
 *
 * <p>
 * 与既有帧协议测试不同，本类一律显式传入<b>真实异步投递池</b>（五参构造），
 * 专门覆盖"锁内取号/落档/入队、锁外异步写出"的并发形态；既有测试走三参构造的
 * 同步直执路径，保持确定性。
 *
 * <ul>
 * <li><b>双不变量锚定（R1/S2-2 + 修复批第 2 轮口径更正）</b>：实时段 256（本类用小容量等价缩放）
 * 溢出即摘除，<b>实时闸按"实时段计数"判定而与队列绝对长度无关</b>；回放段豁免实时容量，
 * 3000 帧全量可入队（上界 = 归档窗口 EVENT_WINDOW）。<b>注意</b>：
 * {@code replayBatchBypassesLiveCapacityUpToTheEventWindow} 的构造是"回放批先排空、再发实时帧"，
 * 它锚定的只是 {@code stageReplay} 的入队豁免，<b>证明不了</b>"回放 backlog 未排空期间实时帧
 * 不摘除"——后者由 {@code liveFramesDoNotEvictAReattachingSubscriberWhileTheReplayBacklogIsDraining}
 * 定点锚定（红队 S1 击破后补；修复前的实现按队列绝对长度判定，该用例必红）；</li>
 * <li><b>R1 修复批第 2 轮（红队 S1）</b>：慢重挂者回放 backlog 未排空期间混入实时帧（含心跳）
 * 不摘除；实时段<b>自身</b>计数超限（&gt; 容量）仍摘除（S2-1 语义不变）；</li>
 * <li><b>R2</b>：drain 收尾与入队交叠不丢帧（多生产者压力 + 慢订阅者）；</li>
 * <li><b>R3/S2-1</b>：池拒绝与队列溢出的摘除语义 = 显式 complete（前端可重挂，seq 无洞）；
 * 池拒绝分支的定点用例见 {@code rejectedDrainSubmissionEvictsOnlyTheRejectedSubscriber}；</li>
 * <li><b>S2-4</b>：{@code ai:seq} 登记失败仅 WARN + 继续出帧（序号发放不中断）。</li>
 * <li><b>S1-1（修复批）</b>：轮终态 {@code complete()} 必须让慢订阅者把<b>已入队</b>的尾帧
 * 排空后才收尾，池饱和（小池 + 慢写出）下同样不丢终帧；两分支定点用例见
 * {@code terminalCompleteOnAnIdleDrainedSubscriberCompletesInline} 与
 * {@code terminalCompleteWithFramesWaitingRunsItsOwnDrainBeforeClosing}；</li>
 * <li><b>S3-1（修复批）</b>：实时队列容量 &lt; 归档窗口 {@code EVENT_WINDOW} 的显式锚定，
 * 且缺省值只有一处（AiProperties 配置项）；<b>修复批第 2 轮补齐</b>：emitter 生命周期三回调
 * （{@code onCompletion/onTimeout/onError}）必须与 {@code evict/completeOne} 同走清理路径
 * （队列清空 + 实时段计数归零 + drain 独占标志复位），否则队列里的千级回放帧在连接超时/断开后
 * 无人释放 = 内存滞留。</li>
 * </ul>
 */
class SseChatEmitterDeliveryTest {

	private static final String RUN_ID = "run-delivery";

	private final ThreadPoolExecutor pool = new ThreadPoolExecutor(4, 4, 60L, TimeUnit.SECONDS,
			new LinkedBlockingQueue<>(), runnable -> {
				Thread thread = new Thread(runnable, "delivery-test");
				thread.setDaemon(true);
				return thread;
			});

	@AfterEach
	void tearDown() {
		this.pool.shutdownNow();
	}

	// ── 慢订阅者隔离：一条慢连接不阻塞同轮其他订阅者 ─────────────────────────

	@Test
	void slowSubscriberDoesNotBlockOthersInTheSameRound() throws Exception {
		InMemoryRunStore store = new InMemoryRunStore();
		SseChatEmitter out = asyncEmitter(store, 256);
		CountDownLatch firstSendStarted = new CountDownLatch(1);
		CountDownLatch releaseSlow = new CountDownLatch(1);
		// 慢订阅者：第一帧的写出被卡住（模拟 TCP 背压）
		SseEmitter slow = blockingEmitter(firstSendStarted, releaseSlow);
		out.attach(slow);
		FrameWire fast = FrameWire.attachTo(out);

		out.delta("第一段");
		assertThat(firstSendStarted.await(5, TimeUnit.SECONDS)).as("慢订阅者的 drain 已卡在首帧写出").isTrue();

		// 慢订阅者仍卡着：同轮其他订阅者必须照常收到后续帧（投递节奏偏差 < 1s 的验收口径）
		long before = System.currentTimeMillis();
		out.delta("第二段");
		awaitSize(fast, 2, 1_000L);
		assertThat(System.currentTimeMillis() - before).as("快订阅者的投递不被慢订阅者拖住").isLessThan(1_000L);
		assertThat(FrameWire.normalize(fast.frames()).get(1)).containsEntry("text", "第二段");

		releaseSlow.countDown();
		awaitSize(fast, 2, 5_000L);
		assertThat(FrameWire.normalize(fast.frames())).extracting(frame -> frame.get("seq"))
				.containsExactly(1L, 2L);
	}

	// ── 不变量①（实时段）：队列溢出 ⇒ 摘除 + 显式 complete（S2-1/R3） ─────────

	@Test
	void liveQueueOverflowEvictsTheSubscriberAndCompletesTheEmitter() throws Exception {
		RunStore store = mock(RunStore.class);
		SseChatEmitter out = asyncEmitter(store, 4);
		CountDownLatch sendStarted = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		SseEmitter slow = blockingEmitter(sendStarted, release);
		out.attach(slow);

		out.delta("f1");
		assertThat(sendStarted.await(5, TimeUnit.SECONDS)).as("drain 已取走 f1 并卡在写出").isTrue();
		// 队列容量 4：f2..f5 填满（drain 卡在 f1 上取不到后续），f6 触发溢出摘除
		out.delta("f2");
		out.delta("f3");
		out.delta("f4");
		out.delta("f5");
		assertThat(out.subscriberCount()).as("填满容量不等于摘除").isEqualTo(1);
		out.delta("f6");

		// S2-1：溢出摘除必须显式 complete —— 前端 onerror/onclose 后带 lastSeq 重挂补帧
		verify(slow).complete();
		assertThat(out.subscriberCount()).isZero();
		release.countDown();
	}

	// ── 不变量②（回放段）：stageReplay 入队豁免实时容量（R1 裁决） ────────────

	/**
	 * 本用例锚定的是 {@code stageReplay} 的<b>入队豁免</b>（回放批不查实时闸），因此构造上
	 * "回放批先排空、再发实时帧"——它<b>不</b>覆盖"回放 backlog 还在队列里时实时帧到达"这一竞态
	 * （修复批第 2 轮更正：修复前实现按队列绝对长度判定，本用例照样能过，属<b>假安全</b>）。
	 * 真正的双口径锚点是下面两条红队 S1 定点用例。
	 */
	@Test
	void replayBatchBypassesLiveCapacityUpToTheEventWindow() throws Exception {
		InMemoryRunStore store = new InMemoryRunStore();
		store.seedArchive(RUN_ID, archiveFrames(3000).toArray(new Map[0]));
		SseChatEmitter out = asyncEmitter(store, 256);

		FrameWire reattached = new FrameWire();
		int staged = out.replayAndAttach(reattached.emitter(), null, false);

		assertThat(staged).as("回放批入队不受实时容量 256 约束（R1 裁决）").isEqualTo(3000);
		awaitSize(reattached, 3000, 10_000L);
		List<Map<String, Object>> frames = FrameWire.normalize(reattached.frames());
		assertThat(frames).hasSize(3000);
		assertThat(frames.get(0)).containsEntry("seq", 1L);
		assertThat(frames.get(2999)).containsEntry("seq", 3000L);

		// 回放批之后实时帧照常续接（同一条订阅，seq 接着走）
		out.delta("续上");
		awaitSize(reattached, 3001, 5_000L);
		assertThat(FrameWire.normalize(reattached.frames()).get(3000)).containsEntry("seq", 3001L);
	}

	// ── 红队 S1（修复批第 2 轮）：实时闸按实时段计数，与回放 backlog 分离 ──────

	/**
	 * 红队击破场景（仓库外探针 ProbeR1 场景 1/2 的等价定点用例）：慢重挂者回放 3000 帧、
	 * 首帧写出被卡住（回放批还剩 2999 帧压在队列里）时，挂起期每 2s 的实时心跳 + 业务帧落到它。
	 *
	 * <p>
	 * 修复前：{@code enqueueLive} 的容量闸判 {@code sub.queue.size() >= 256}（队列绝对长度，
	 * 含回放帧）⇒ 第一条实时帧即摘除一个<b>健康</b>的慢订阅者；重挂后剩余回放 ≥256 再被摘除
	 * = 确定性饥饿（与 R1 裁决"回放批豁免有界容量"直接相悖）。修复后：闸判实时段计数
	 * （回放批不计入）⇒ 不摘除，且全量按 FIFO 混合序送达。
	 */
	@Test
	void liveFramesDoNotEvictAReattachingSubscriberWhileTheReplayBacklogIsDraining() throws Exception {
		InMemoryRunStore store = new InMemoryRunStore();
		store.seedArchive(RUN_ID, archiveFrames(3000).toArray(new Map[0]));
		SseChatEmitter out = asyncEmitter(store, 256);
		TerminalSemanticsEmitter slow = new TerminalSemanticsEmitter(true);

		assertThat(out.replayAndAttach(slow.emitter(), null, false)).as("回放批 3000 帧全部入队").isEqualTo(3000);
		assertThat(slow.awaitFirstSend(5L)).as("drain 已取走首帧并卡在写出：回放 backlog 未排空").isTrue();

		// 回放 backlog 未排空期间混入实时帧（心跳不占 seq；delta 占 seq）
		out.heartbeat(Map.of("phase", "streaming"));
		out.delta("实时一");
		out.heartbeat(Map.of("phase", "streaming"));
		TimeUnit.MILLISECONDS.sleep(200L); // 给修复前的实现足够时间走完摘除（红队探针用 300ms）

		assertThat(slow.completes()).as("回放 backlog 排空期间的实时帧不得摘除健康订阅者（红队 S1）").isZero();
		assertThat(out.subscriberCount()).as("订阅者仍在订阅表").isEqualTo(1);

		slow.release();
		assertThat(slow.awaitFrames(3003, 20L)).as("回放批 + 混入的实时帧全量送达").isTrue();
		List<String> types = typesOf(slow.payloads());
		assertThat(types.subList(0, 3000)).as("回放批保持原序").containsOnly("tool_start");
		assertThat(types.subList(3000, 3003)).as("实时帧按入队顺序续在回放批之后（单队列 FIFO 混合序）")
				.containsExactly("heartbeat", "delta", "heartbeat");
		assertThat(slow.payloads().get(3001)).as("实时业务帧续接归档末帧号（归档末帧 3000 ⇒ 3001）")
				.contains("\"seq\":3001");
	}

	/**
	 * 反向锚定（红队 S1 的另一半）：回放批豁免的是"实时容量"这条闸，而不是把闸拆掉 ——
	 * 排空期实时段<b>自己</b>塞满 &gt; 256 条仍然摘除（S2-1 溢出 = 主动断连 + 前端带 lastSeq 重挂补帧）。
	 */
	@Test
	void liveSegmentOverflowDuringTheReplayBacklogStillEvictsTheSubscriber() throws Exception {
		InMemoryRunStore store = new InMemoryRunStore();
		store.seedArchive(RUN_ID, archiveFrames(3000).toArray(new Map[0]));
		SseChatEmitter out = asyncEmitter(store, 256);
		TerminalSemanticsEmitter slow = new TerminalSemanticsEmitter(true);
		out.replayAndAttach(slow.emitter(), null, false);
		assertThat(slow.awaitFirstSend(5L)).isTrue();

		for (int tick = 1; tick <= 256; tick++) {
			out.heartbeat(Map.of("tick", tick));
		}
		assertThat(slow.completes()).as("实时段计数到容量 256 仍未溢出（回放批不计入实时闸）").isZero();
		assertThat(out.subscriberCount()).isEqualTo(1);

		out.heartbeat(Map.of("tick", 257));
		assertThat(slow.completes()).as("第 257 条实时帧触发实时段溢出摘除（S2-1 语义不变）").isEqualTo(1);
		assertThat(out.subscriberCount()).isZero();
		slow.release();
	}

	// ── R2：drain 收尾与入队交叠不丢帧 ──────────────────────────────────────

	@Test
	void drainTailRaceNeverLosesFramesWhenEnqueueOverlapsDrainExit() throws Exception {
		InMemoryRunStore store = new InMemoryRunStore();
		// 容量远大于总帧数：本用例锚定 R2 收尾竞态，溢出摘除由专门的用例锚定
		SseChatEmitter out = asyncEmitter(store, 4096);
		AtomicLong sent = new AtomicLong();
		// 订阅者写出带毫秒级抖动：让"drain 判空退出"与"生产者入队"高频交叠
		FrameWire wire = new FrameWire(payload -> {
			sent.incrementAndGet();
			try {
				TimeUnit.MILLISECONDS.sleep(sent.get() % 3);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		});
		out.attach(wire.emitter());

		int producers = 4;
		int framesEach = 100;
		CountDownLatch done = new CountDownLatch(producers);
		for (int producer = 0; producer < producers; producer++) {
			int index = producer;
			Thread thread = new Thread(() -> {
				try {
					for (int i = 1; i <= framesEach; i++) {
						out.delta("p" + index + "-" + i);
					}
				}
				finally {
					done.countDown();
				}
			}, "producer-" + producer);
			thread.start();
		}
		assertThat(done.await(30, TimeUnit.SECONDS)).as("全部生产者完成出帧").isTrue();

		awaitSize(wire, producers * framesEach, 15_000L);
		List<Map<String, Object>> frames = FrameWire.normalize(wire.frames());
		assertThat(frames).hasSize(producers * framesEach);
		// 单订阅者 FIFO + 锁内入队 ⇒ 到达顺序恒等于 seq 顺序，一帧不丢、一帧不重
		List<Long> seqs = new ArrayList<>();
		for (Map<String, Object> frame : frames) {
			seqs.add(((Number) frame.get("seq")).longValue());
		}
		assertThat(seqs).containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, producers * framesEach)
				.boxed().toList());
	}

	// ── S2-1 端到端：摘除 → complete → 前端重挂补帧 seq 无洞 ─────────────────

	@Test
	void evictedSubscriberCanReattachAndBackfillWithoutSeqGaps() throws Exception {
		InMemoryRunStore store = new InMemoryRunStore();
		SseChatEmitter out = asyncEmitter(store, 4);
		CountDownLatch sendStarted = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		SseEmitter slow = blockingEmitter(sendStarted, release);
		out.attach(slow);

		// 用状态帧（会落归档）：delta 帧按 T3-2 跳过归档，重挂补帧本来就只覆盖状态帧
		out.suspended(List.of(pending("c-1")), "k1", "k2", "i1");
		assertThat(sendStarted.await(5, TimeUnit.SECONDS)).isTrue();
		out.toolStart(pending("c-2"));
		out.toolStart(pending("c-3"));
		out.toolStart(pending("c-4"));
		out.toolStart(pending("c-5"));
		out.toolStart(pending("c-6")); // 溢出摘除（容量 4，drain 卡在 f1）
		verify(slow).complete();
		release.countDown();

		// 前端已知 f1（lastSeq=1），重挂应补回 f2..f6 —— 断点续收 seq 无洞
		FrameWire reattached = new FrameWire();
		out.replayAndAttach(reattached.emitter(), 1L, false);
		awaitSize(reattached, 5, 5_000L);
		List<Long> seqs = new ArrayList<>();
		for (Map<String, Object> frame : FrameWire.normalize(reattached.frames())) {
			seqs.add(((Number) frame.get("seq")).longValue());
		}
		assertThat(seqs).containsExactly(2L, 3L, 4L, 5L, 6L);
	}

	// ── S2-4：ai:seq 登记失败仅 WARN + 继续出帧（序号发放不中断） ─────────────

	@Test
	void seqRecordFailureOnlyWarnsAndKeepsEmitting() throws Exception {
		RunStore store = mock(RunStore.class);
		org.mockito.Mockito.doThrow(new RuntimeException("SET 失败（模拟 Redis 抖动）")).when(store)
				.recordIssuedSeq(anyString(), org.mockito.ArgumentMatchers.anyLong());
		SseChatEmitter out = asyncEmitter(store, 256);
		FrameWire wire = FrameWire.attachTo(out);

		out.delta("一");
		out.delta("二");
		out.delta("三");

		awaitSize(wire, 3, 5_000L);
		assertThat(FrameWire.normalize(wire.frames())).extracting(frame -> frame.get("seq"))
				.containsExactly(1L, 2L, 3L);
	}

	// ── S1-1：轮终态收尾必须先排空队列（慢订阅者的尾帧不丢） ──────────────────

	@Test
	void terminalCompleteDrainsQueuedTailFramesBeforeClosingTheSubscriber() throws Exception {
		InMemoryRunStore store = new InMemoryRunStore();
		SseChatEmitter out = asyncEmitter(store, 256);
		TerminalSemanticsEmitter slow = new TerminalSemanticsEmitter(true);
		out.attach(slow.emitter());

		out.delta("尾一");
		assertThat(slow.awaitFirstSend(5L)).as("drain 已取走首帧并卡在写出").isTrue();
		out.delta("尾二");
		out.done(Map.of(), "deepseek-flash", Map.of("cancelled", false));

		// 轮终态：生产调用点在轮终态线程的 finally（AiController / ResumeService），
		// 此刻慢订阅者队列里还压着尾二 + done
		out.complete();
		slow.release();

		assertThat(slow.awaitFrames(3, 5L)).as("终态尾帧（含 done）必须送达慢订阅者").isTrue();
		assertThat(typesOf(slow.payloads())).containsExactly("delta", "delta", "done");
		assertThat(slow.awaitCompletes(1, 5L)).as("队列排空之后才收尾").isTrue();
		assertThat(slow.completes()).as("收尾恰好一次").isEqualTo(1);
		assertThat(out.subscriberCount()).isZero();
	}

	@Test
	void terminalCompleteUnderASaturatedDeliveryPoolStillDeliversEveryTerminalFrame() throws Exception {
		ThreadPoolExecutor single = new ThreadPoolExecutor(1, 1, 60L, TimeUnit.SECONDS,
				new LinkedBlockingQueue<>(), daemonFactory("delivery-single"));
		try {
			InMemoryRunStore store = new InMemoryRunStore();
			SseChatEmitter out = new SseChatEmitter(RUN_ID, store, new ObjectMapper(), single, 256);
			// 池只有一条工作线程：慢订阅者占住它，后一个订阅者的 drain 任务只能排在池队列里
			TerminalSemanticsEmitter slow = new TerminalSemanticsEmitter(true);
			TerminalSemanticsEmitter queued = new TerminalSemanticsEmitter(false);
			out.attach(slow.emitter());
			out.attach(queued.emitter());

			out.delta("尾一");
			assertThat(slow.awaitFirstSend(5L)).isTrue();
			out.delta("尾二");
			out.done(Map.of(), "deepseek-flash", Map.of("cancelled", false));

			out.complete();
			slow.release();

			assertThat(slow.awaitFrames(3, 5L)).as("被慢连接占住工作线程的订阅者：终帧不丢").isTrue();
			assertThat(typesOf(slow.payloads())).containsExactly("delta", "delta", "done");
			assertThat(slow.awaitCompletes(1, 5L)).isTrue();
			assertThat(queued.awaitFrames(3, 5L)).as("排在池队列里的订阅者：终帧同样不丢").isTrue();
			assertThat(typesOf(queued.payloads())).containsExactly("delta", "delta", "done");
			assertThat(queued.awaitCompletes(1, 5L)).isTrue();
		}
		finally {
			single.shutdownNow();
		}
	}

	// ── R3：池拒绝分支 —— 只摘除触发本次提交的订阅者，标志清干净 ──────────────

	@Test
	void rejectedDrainSubmissionEvictsOnlyTheRejectedSubscriber() throws Exception {
		ThreadPoolExecutor tiny = new ThreadPoolExecutor(1, 1, 60L, TimeUnit.SECONDS,
				new LinkedBlockingQueue<>(1), daemonFactory("delivery-tiny"));
		try {
			InMemoryRunStore store = new InMemoryRunStore();
			SseChatEmitter out = new SseChatEmitter(RUN_ID, store, new ObjectMapper(), tiny, 256);
			TerminalSemanticsEmitter slow = new TerminalSemanticsEmitter(true);
			TerminalSemanticsEmitter queued = new TerminalSemanticsEmitter(false);
			TerminalSemanticsEmitter rejected = new TerminalSemanticsEmitter(false);
			out.attach(slow.emitter());
			out.attach(queued.emitter());
			out.attach(rejected.emitter());
			// 摘除会把它移出订阅表，先在提交发生前抓住它的投递上下文（白盒，见 fieldOf）
			Object rejectedSub = subscriptionOf(out, rejected.emitter());

			// 池 = 1 工作线程 + 池队列容量 1：slow 占住工作线程、queued 占满池队列、rejected 必被拒
			out.delta("f1");
			assertThat(slow.awaitFirstSend(5L)).isTrue();

			assertThat(rejected.completes()).as("R3：池拒绝 ⇒ 显式 complete（前端可重挂补帧）").isEqualTo(1);
			assertThat(out.subscriberCount()).as("只摘除触发本次提交的那一个").isEqualTo(2);
			assertThat(((AtomicBoolean) fieldOf(rejectedSub, "draining")).get())
					.as("拒绝分支必须清干净 drain 独占标志（否则该订阅者永远不再投递）").isFalse();
			assertThat(rejected.payloads()).as("被拒订阅者一帧都收不到（重挂走差量补发）").isEmpty();

			// 其他订阅者不受影响：释放后，排在池队列里的 drain 照常写出
			slow.release();
			assertThat(slow.awaitFrames(1, 5L)).isTrue();
			assertThat(queued.awaitFrames(1, 5L)).isTrue();
			assertThat(typesOf(queued.payloads())).containsExactly("delta");
		}
		finally {
			tiny.shutdownNow();
		}
	}

	// ── S2-1：两参构造（单测/非 Spring）固定同步直执，不读进程静态池 ──────────

	/**
	 * S3-3（修复批第 2 轮）用例加固：原构造在"静态池未装配"的隔离运行时里<b>修复前代码也能过</b>
	 * （两参路径即使去读静态池，读到的也是 null ⇒ 回落同步直执），对缺陷不敏感。加固办法是
	 * 用例内先按生产 Spring 路径装配进程级静态池，再构造两参注册表 —— 修复前代码此时会拿到
	 * 静态池（异步投递 + 写线程 ≠ 调用线程），两条断言必红。
	 */
	@Test
	void twoArgRegistryAlwaysDeliversInlineAndNeverReadsTheSharedPool() throws Exception {
		AiProperties defaults = new AiProperties();
		SseChatEmitter.configureSharedDeliveryPool(defaults.getSse().getDeliveryPoolSize(),
				defaults.getSse().getDeliveryPoolSize() * 64);
		assertThat(SseChatEmitter.sharedDeliveryExecutorOrDirect())
				.as("前置：进程级静态池已装配（修复前的两参路径会读到它）").isInstanceOf(ThreadPoolExecutor.class);

		InMemoryRunStore store = new InMemoryRunStore();
		SseChatEmitter out = new RunRegistry(store, new ObjectMapper()).of(RUN_ID);
		TerminalSemanticsEmitter wire = new TerminalSemanticsEmitter(false);
		out.attach(wire.emitter());

		String callingThread = Thread.currentThread().getName();
		out.delta("直执");
		assertThat(wire.awaitFrames(1, 5L)).isTrue();
		assertThat(wire.sendThreads()).as("S2-1：两参构造的写出发生在调用线程（同步直执）")
				.containsExactly(callingThread);
		assertThat((Executor) fieldOf(out, "deliveryExecutor")).as("S2-1：两参构造不读进程静态池")
				.isNotInstanceOf(ThreadPoolExecutor.class);
	}

	// ── S3-1 不变量锚定：实时队列容量 < 归档窗口，且只有一处缺省 ──────────────

	@Test
	void liveQueueCapacityStaysBelowTheEventWindowAndComesFromASingleSource() {
		AiProperties configured = new AiProperties();
		assertThat(configured.getSse().getDeliveryQueueCapacity())
				.as("R1 不变量：每订阅者实时队列容量 < 归档窗口 EVENT_WINDOW")
				.isEqualTo(AiProperties.Sse.DEFAULT_DELIVERY_QUEUE_CAPACITY)
				.isLessThan(RunStore.EVENT_WINDOW);

		InMemoryRunStore store = new InMemoryRunStore();
		// 两条装配路径（写出器缺省 / 注册表缺省）必须落在同一处缺省：无第二处字面量
		assertThat(new SseChatEmitter(RUN_ID, store, new ObjectMapper()).queueCapacity())
				.as("写出器缺省容量 = 配置缺省").isEqualTo(AiProperties.Sse.DEFAULT_DELIVERY_QUEUE_CAPACITY);
		assertThat(new RunRegistry(store, new ObjectMapper()).of("run-capacity-default").queueCapacity())
				.as("注册表（无配置路径）容量 = 配置缺省").isEqualTo(AiProperties.Sse.DEFAULT_DELIVERY_QUEUE_CAPACITY);

		AiProperties custom = new AiProperties();
		custom.getSse().setDeliveryQueueCapacity(128);
		assertThat(new RunRegistry(store, new ObjectMapper(), custom).of("run-capacity-custom").queueCapacity())
				.as("配置项为准（单一事实源）").isEqualTo(128);
	}

	// ── S3-1（修复批第 2 轮）：生命周期三回调统一清理（队列/计数/标志/订阅表） ────

	@Test
	void timeoutCallbackClearsTheSubscriberQueueAndResetsDeliveryState() throws Exception {
		assertLifecycleCallbackCleansDeliveryState("onTimeout", TerminalSemanticsEmitter::fireTimeout);
	}

	@Test
	void completionCallbackClearsTheSubscriberQueueAndResetsDeliveryState() throws Exception {
		assertLifecycleCallbackCleansDeliveryState("onCompletion", TerminalSemanticsEmitter::fireCompletion);
	}

	@Test
	void errorCallbackClearsTheSubscriberQueueAndResetsDeliveryState() throws Exception {
		assertLifecycleCallbackCleansDeliveryState("onError",
				slow -> slow.fireError(new IOException("客户端断开")));
	}

	/**
	 * 三回调共用断言（S3-1，修复批第 2 轮）：修复前 {@code attachSubscription} 只做
	 * {@code subscribers.remove(sub)} —— 慢连接超时/断开时队列里滞留的回放帧（生产上是千级）
	 * 无人释放，且 drain 独占标志留在原地。用例白盒读三个内部面（队列 / 实时段计数 / 独占标志），
	 * 理由同 {@link #fieldOf}：这三者都没有对外可观测面，却正是本次清理的判据。
	 */
	private void assertLifecycleCallbackCleansDeliveryState(String callback,
			Consumer<TerminalSemanticsEmitter> fire) throws Exception {
		InMemoryRunStore store = new InMemoryRunStore();
		SseChatEmitter out = asyncEmitter(store, 256);
		TerminalSemanticsEmitter slow = new TerminalSemanticsEmitter(true);
		out.attach(slow.emitter());
		Object sub = subscriptionOf(out, slow.emitter());

		out.delta("d1");
		assertThat(slow.awaitFirstSend(5L)).as("首帧写出已卡住（d1 在途，d2..d5 滞留队列）").isTrue();
		out.delta("d2");
		out.delta("d3");
		out.delta("d4");
		out.delta("d5");
		assertThat(queueOf(sub)).as(callback + " 触发前：队列里应有滞留帧（否则断言无意义）").hasSize(4);

		fire.accept(slow);

		assertThat(queueOf(sub)).as(callback + "：必须清空订阅者队列（S3-1 不留内存滞留）").isEmpty();
		assertThat(liveQueuedOf(sub)).as(callback + "：实时段计数归零").isZero();
		assertThat(((AtomicBoolean) fieldOf(sub, "draining")).get())
				.as(callback + "：drain 独占标志复位（不留残）").isFalse();
		assertThat(out.subscriberCount()).as(callback + "：移出订阅表").isZero();

		slow.release();
		out.delta("回调之后的实时帧");
		TimeUnit.MILLISECONDS.sleep(100L);
		assertThat(slow.payloads()).as(callback + "：已终态的订阅者不再收帧（无滞留、无续投）").hasSize(1);
		assertThat(slow.completes()).as(callback + "：不重复 complete 已终态的 emitter").isZero();
	}

	// ── S3-5：completeWhenDrained 两分支定点（CAS 抢到 + 队空 / CAS 抢到 + 队非空） ──

	@Test
	void terminalCompleteOnAnIdleDrainedSubscriberCompletesInline() throws Exception {
		RecordingExecutor executor = new RecordingExecutor();
		InMemoryRunStore store = new InMemoryRunStore();
		SseChatEmitter out = new SseChatEmitter(RUN_ID, store, new ObjectMapper(), executor, 256);
		TerminalSemanticsEmitter idle = new TerminalSemanticsEmitter(false);
		out.attach(idle.emitter());

		out.complete();

		assertThat(idle.completes()).as("CAS 抢到 + 队空 ⇒ 直接 completeOne").isEqualTo(1);
		assertThat(idle.completeThreads()).as("收尾发生在调用线程（未换手给投递池）")
				.containsExactly(Thread.currentThread().getName());
		assertThat(executor.submitted()).as("队空分支不提交 drain 任务").isZero();
		assertThat(out.subscriberCount()).isZero();
	}

	/**
	 * "CAS 抢到 + 队非空"分支：{@code complete()} 发现没有 drain 在跑却还有帧没写出，
	 * 于是自己提交一个 drain 去排空，收尾交给那个 drain 的队空分支（不得提前 complete）。
	 *
	 * <p>
	 * 前提状态（队列非空 ∧ 无 drain 在跑）在生产上是 {@code emit} 内 enqueue 与 kickDrain 之间的
	 * 窄窗（两者同在 emitLock 内），公共 API 无法确定复现，故用白盒把独占标志复位来构造
	 * —— 这正是该分支存在的理由（理由同 {@link #fieldOf}）。
	 */
	@Test
	void terminalCompleteWithFramesWaitingRunsItsOwnDrainBeforeClosing() throws Exception {
		RecordingExecutor executor = new RecordingExecutor();
		InMemoryRunStore store = new InMemoryRunStore();
		SseChatEmitter out = new SseChatEmitter(RUN_ID, store, new ObjectMapper(), executor, 256);
		TerminalSemanticsEmitter slow = new TerminalSemanticsEmitter(false);
		out.attach(slow.emitter());
		Object sub = subscriptionOf(out, slow.emitter());

		out.delta("队内一帧");
		assertThat(executor.submitted()).as("入队后 CAS 抢到 drain 权 ⇒ 已提交一个 drain 任务").isEqualTo(1);
		((AtomicBoolean) fieldOf(sub, "draining")).set(false);

		out.complete();

		assertThat(executor.submitted()).as("CAS 抢到 + 队非空 ⇒ 自跑 submitDrain 去排空").isEqualTo(2);
		assertThat(slow.completes()).as("队列未空 ⇒ 不得提前收尾").isZero();

		executor.runSubmitted(1);
		assertThat(typesOf(slow.payloads())).as("自起的 drain 把队内帧写出").containsExactly("delta");
		assertThat(slow.awaitCompletes(1, 5L)).as("排空之后才收尾").isTrue();
		assertThat(out.subscriberCount()).isZero();
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	/**
	 * 订阅者桩：可让第一条帧的写出阻塞（模拟慢连接 TCP 背压），且 <b>{@code complete()} 之后的
	 * 写出像真实 {@code SseEmitter} 一样失败</b>。
	 *
	 * <p>
	 * 后面这半条是 S1-1 用例的判据所在：真实 {@code ResponseBodyEmitter#send} 在 complete 之后抛
	 * {@code IllegalStateException}（"已完成的响应"），即"complete 之后还送到的帧"在真实连接上
	 * 本来就到不了前端。桩件与真实连接同口径，用例才不会假绿 —— 若桩件照单全收，
	 * "先 complete 再排空"的错误实现也会通过。
	 */
	private static final class TerminalSemanticsEmitter extends SseEmitter {

		private final boolean blockFirstSend;

		private final CountDownLatch firstSendStarted = new CountDownLatch(1);

		private final CountDownLatch release = new CountDownLatch(1);

		private final List<String> payloads = new CopyOnWriteArrayList<>();

		private final List<String> sendThreads = new CopyOnWriteArrayList<>();

		private final AtomicInteger sends = new AtomicInteger();

		private final AtomicInteger completes = new AtomicInteger();

		private final List<String> completeThreads = new CopyOnWriteArrayList<>();

		/** 生命周期回调登记（S3-1 用例显式触发；真实连接上由响应生命周期触发）。 */
		private final List<Runnable> completionCallbacks = new CopyOnWriteArrayList<>();

		private final List<Runnable> timeoutCallbacks = new CopyOnWriteArrayList<>();

		private final List<Consumer<Throwable>> errorCallbacks = new CopyOnWriteArrayList<>();

		private volatile boolean completed;

		TerminalSemanticsEmitter(boolean blockFirstSend) {
			this.blockFirstSend = blockFirstSend;
		}

		SseEmitter emitter() {
			return this;
		}

		@Override
		public void onCompletion(Runnable callback) {
			this.completionCallbacks.add(callback);
		}

		@Override
		public void onTimeout(Runnable callback) {
			this.timeoutCallbacks.add(callback);
		}

		@Override
		public void onError(Consumer<Throwable> callback) {
			this.errorCallbacks.add(callback);
		}

		void fireCompletion() {
			this.completionCallbacks.forEach(Runnable::run);
		}

		void fireTimeout() {
			this.timeoutCallbacks.forEach(Runnable::run);
		}

		void fireError(Throwable ex) {
			this.errorCallbacks.forEach(callback -> callback.accept(ex));
		}

		@Override
		public void send(Object object) throws IOException {
			if (this.completed) {
				throw new IllegalStateException("ResponseBodyEmitter has already completed");
			}
			if (this.blockFirstSend && this.sends.incrementAndGet() == 1) {
				this.firstSendStarted.countDown();
				try {
					this.release.await(5L, TimeUnit.SECONDS);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			}
			this.sendThreads.add(Thread.currentThread().getName());
			this.payloads.add(String.valueOf(object));
		}

		@Override
		public synchronized void complete() {
			this.completed = true;
			this.completeThreads.add(Thread.currentThread().getName());
			this.completes.incrementAndGet();
		}

		boolean awaitFirstSend(long seconds) throws InterruptedException {
			return this.firstSendStarted.await(seconds, TimeUnit.SECONDS);
		}

		void release() {
			this.release.countDown();
		}

		List<String> payloads() {
			return List.copyOf(this.payloads);
		}

		List<String> sendThreads() {
			return List.copyOf(this.sendThreads);
		}

		List<String> completeThreads() {
			return List.copyOf(this.completeThreads);
		}

		int completes() {
			return this.completes.get();
		}

		boolean awaitFrames(int size, long seconds) throws InterruptedException {
			return awaitCount(this.payloads::size, size, seconds);
		}

		boolean awaitCompletes(int size, long seconds) throws InterruptedException {
			return awaitCount(this.completes::get, size, seconds);
		}

		private static boolean awaitCount(IntSupplier current, int target, long seconds)
				throws InterruptedException {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
			while (current.getAsInt() < target && System.nanoTime() < deadline) {
				TimeUnit.MILLISECONDS.sleep(5L);
			}
			return current.getAsInt() >= target;
		}
	}

	/** 帧原文的 type 序列（断言到达顺序与帧型，不看写出器内部状态）。 */
	@SuppressWarnings("unchecked")
	private static List<String> typesOf(List<String> payloads) {
		ObjectMapper mapper = new ObjectMapper();
		List<String> types = new ArrayList<>();
		for (String payload : payloads) {
			try {
				types.add(String.valueOf(mapper.readValue(payload, Map.class).get("type")));
			}
			catch (Exception ex) {
				throw new IllegalStateException("帧原文不是 JSON 对象：" + payload, ex);
			}
		}
		return types;
	}

	/** 用例自建小池用的守护线程工厂（不留线程拖住 surefire 退出）。 */
	private static ThreadFactory daemonFactory(String prefix) {
		AtomicInteger threadNo = new AtomicInteger();
		return runnable -> {
			Thread thread = new Thread(runnable, prefix + "-" + threadNo.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		};
	}

	/**
	 * 只记录、不执行的投递执行器（S3-5）：让"何时提交 drain"可观测、可定点驱动 ——
	 * 提交被推迟到用例显式 {@link #runSubmitted(int)}，于是"CAS 抢到 + 队非空 ⇒ 自跑 drain"
	 * 这一分支才可能在收尾尚未发生时被断言。
	 */
	private static final class RecordingExecutor implements Executor {

		private final List<Runnable> tasks = new CopyOnWriteArrayList<>();

		@Override
		public void execute(Runnable command) {
			this.tasks.add(command);
		}

		int submitted() {
			return this.tasks.size();
		}

		void runSubmitted(int index) {
			this.tasks.get(index).run();
		}

	}

	/**
	 * 读私有字段（S2-1 / R3 断言的判据面）。
	 *
	 * <p>
	 * 为什么允许白盒：{@code deliveryExecutor} 是"同步直执还是异步池"的装配选择，
	 * {@code Subscription#draining} 是拒绝分支必须清干净的内部不变量 —— 两者都没有对外可观测面，
	 * 却正是本次修复的判据。为断言另造一条等价实现，等于把断言写在测试自己的假设上。
	 */
	private static Object fieldOf(Object target, String name) throws Exception {
		Class<?> type = target.getClass();
		while (type != null) {
			try {
				Field field = type.getDeclaredField(name);
				field.setAccessible(true);
				return field.get(target);
			}
			catch (NoSuchFieldException ex) {
				type = type.getSuperclass();
			}
		}
		throw new IllegalStateException("找不到字段 " + name);
	}

	/** 按 emitter 身份取出该订阅者的投递上下文（白盒，理由见 {@link #fieldOf}）。 */
	@SuppressWarnings("unchecked")
	private static Object subscriptionOf(SseChatEmitter out, SseEmitter emitter) throws Exception {
		for (Object sub : (List<Object>) fieldOf(out, "subscribers")) {
			if (fieldOf(sub, "emitter") == emitter) {
				return sub;
			}
		}
		throw new IllegalStateException("找不到该 emitter 的订阅者上下文");
	}

	/** 走真实异步投递池的写出器（容量缩放以加速溢出用例）。 */
	private SseChatEmitter asyncEmitter(RunStore store, int queueCapacity) {
		return new SseChatEmitter(RUN_ID, store, new ObjectMapper(), this.pool, queueCapacity);
	}

	/** 订阅者队列（白盒，理由见 {@link #fieldOf}）。 */
	@SuppressWarnings("unchecked")
	private static BlockingQueue<?> queueOf(Object sub) throws Exception {
		return (BlockingQueue<Object>) fieldOf(sub, "queue");
	}

	/** 队列中的实时帧计数（S1 修复批第 2 轮的判据面：容量闸按它判定，白盒理由同上）。 */
	private static int liveQueuedOf(Object sub) throws Exception {
		return ((AtomicInteger) fieldOf(sub, "liveQueued")).get();
	}

	/** 第一帧写出即卡住的订阅者（模拟慢连接 TCP 背压）。 */
	private static SseEmitter blockingEmitter(CountDownLatch sendStarted, CountDownLatch release) throws Exception {
		SseEmitter emitter = mock(SseEmitter.class);
		doAnswer(invocation -> {
			sendStarted.countDown();
			release.await(5, TimeUnit.SECONDS);
			return null;
		}).when(emitter).send(anyString());
		return emitter;
	}

	private static Map<String, Object> archiveFrame(String type, long seq) {
		Map<String, Object> frame = new LinkedHashMap<>();
		frame.put("type", type);
		frame.put("seq", seq);
		return frame;
	}

	/** 一段状态帧归档语料（重挂回放用；delta/心跳不入档，回放批只发状态帧）。 */
	private static List<Map<String, Object>> archiveFrames(int count) {
		List<Map<String, Object>> archived = new ArrayList<>();
		for (long seq = 1; seq <= count; seq++) {
			archived.add(archiveFrame("tool_start", seq));
		}
		return archived;
	}

	private static PendingToolCall pending(String toolCallId) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(toolCallId);
		pending.setName("start_export");
		pending.setKind(PendingToolCall.KIND_CONFIRM);
		pending.setArguments("{\"taskId\":7}");
		return pending;
	}

	private static void awaitSize(FrameWire wire, int size, long timeoutMillis) throws InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMillis;
		while (wire.size() < size && System.currentTimeMillis() < deadline) {
			TimeUnit.MILLISECONDS.sleep(10L);
		}
		assertThat(wire.size()).as("帧线在超时前收到 %d 帧（实际 %d）", size, wire.size()).isEqualTo(size);
	}

}
