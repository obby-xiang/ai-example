package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.common.sse.SseWriteBudget;
import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包②：{@link SseChatEmitter} 写侧超时预算的定点单测（{@code app.ai.sse.write-timeout}）。
 *
 * <p>
 * 与 {@link SseChatEmitterDeliveryTest}（T3-1 慢订阅者隔离）的分工：那一类锚定的是"锁内取号/落档/
 * 入队 + 锁外异步写出"的<b>队列与摘除</b>语义；本类锚定的是"写出本身没有按帧预算"这一半 ——
 * 写侧唯一的超时是容器兜底那条（= {@code server.tomcat.connection-timeout} 派生，本配置 120s；
 * 2026-10-10 复核按 tomcat-embed-core 10.1.54 字节码更正口径，早期"无写超时"的说法已作废）——
 * 桩件特意做成<b>与真实 {@code ResponseBodyEmitter} 同监视器语义</b>（{@code send}/{@code complete}
 * 都在 emitter 实例上 {@code synchronized}，且写出可被卡住），否则用例会假绿：
 * <ul>
 * <li>{@code stuckSubscriberNoLongerStarvesTheSingleThreadedDeliveryPool}：投递池只有 1 条工作线程
 * （{@code M == 池容量} 的最坏态），一条卡住的写出不得再占住它 —— 修复前健康订阅者收不到任何帧
 * （红→绿的真实判据）；</li>
 * <li>{@code writeTimeoutEvictsTheSubscriberAndClosesItWhenTheStuckWriteFinallyReturns}：超时即摘除，
 * 但<b>不</b>在调用线程 complete（写线程持有 emitter 监视器）；收尾由"卡住的那次写返回时"的写出线程
 * 补做；</li>
 * <li>{@code overflowEvictionDuringAnInFlightWriteDoesNotBlockTheCallingThread}：写在飞期间的收尾请求
 * （队列溢出摘除）不得阻塞出帧线程 —— 修复前它会挂在 emitter 监视器上；</li>
 * <li>{@code budgetedWritePathKeepsTheFrameAndT43Contract}：新路径下 T4-3 的帧口径
 * （atMs 全帧覆盖、expiresAt 同钟、心跳不占业务 seq、事件顺序、实时原文与归档同 atMs）逐条不变。</li>
 * </ul>
 */
class SseChatEmitterWriteBudgetTest {

	private static final String RUN_ID = "run-write-budget";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** 投递池（每用例自定义线程数；见各用例注释）。 */
	private final ThreadPoolExecutor deliveryPool = new ThreadPoolExecutor(1, 1, 60L, TimeUnit.SECONDS,
			new LinkedBlockingQueue<>(), daemon("budget-delivery"));

	/** 写出池（弹性，模拟生产的 sse-write-* 池；线程名固定便于断言"收尾发生在写出线程"）。 */
	private final ExecutorService writerPool = new ThreadPoolExecutor(0, 8, 60L, TimeUnit.SECONDS,
			new SynchronousQueue<>(), daemon("budget-writer"));

	@AfterEach
	void tearDown() {
		this.deliveryPool.shutdownNow();
		this.writerPool.shutdownNow();
	}

	// ── S1 红→绿：卡住的写不再占住共享投递池 ────────────────────────────────

	@Test
	void stuckSubscriberNoLongerStarvesTheSingleThreadedDeliveryPool() throws Exception {
		InMemoryRunStore store = new InMemoryRunStore();
		// 投递池 1 条工作线程 = "慢连接数 == 池容量"的最坏态：修复前这一个线程会被卡住的写永久占住
		SseChatEmitter out = budgeted(store, 256, 300L);
		MonitorFaithfulEmitter slow = new MonitorFaithfulEmitter(true, false);
		out.attach(slow.emitter());
		FrameWire healthy = FrameWire.attachTo(out);

		out.delta("慢订阅者的第一帧");
		assertThat(slow.awaitFirstSend(5L)).as("慢订阅者的写出已卡住").isTrue();

		long before = System.currentTimeMillis();
		out.delta("健康订阅者的帧");
		awaitSize(healthy, 2, 3_000L);

		assertThat(System.currentTimeMillis() - before)
				.as("健康订阅者的投递不被卡住的写拖住（修复前 = 投递池唯一线程被占 ⇒ 一帧都收不到）")
				.isLessThan(3_000L);
		assertThat(FrameWire.normalize(healthy.frames())).extracting(frame -> frame.get("type"))
				.containsExactly("delta", "delta");
		assertThat(FrameWire.normalize(healthy.frames()).get(1)).containsEntry("text", "健康订阅者的帧");
		// 超预算 ⇒ 慢订阅者被摘除（主动断连，前端带 lastSeq 重挂差量补发）
		assertThat(awaitSubscriberCount(out, 1, 3L)).as("慢订阅者已被摘除，订阅表只剩健康订阅者").isTrue();
		slow.release();
	}

	// ── 超时处置：摘除 + 由写出线程收尾（不在调用线程 complete） ──────────────

	/**
	 * 摘除必须<b>可被聚合</b>：只留一行中文 WARN 时，"这一轮被摘过几次、为什么被摘"只能人肉读日志。
	 * 结构化事件 {@code sse_detach} 把原因、预算、计数摊成可 grep 的字段。
	 */
	@Test
	void writeTimeoutEmitsAStructuredSseDetachEvent() throws Exception {
		InMemoryRunStore store = new InMemoryRunStore();
		SseChatEmitter out = budgeted(store, 256, 300L);
		MonitorFaithfulEmitter slow = new MonitorFaithfulEmitter(true, false);
		out.attach(slow.emitter());

		// 事件行由 AiTrace 的 logger 产出（与 WARN 不同源）—— 挂错 logger 会得到"实收 0 行"的假红
		Logger emitterLogger = (Logger) LoggerFactory.getLogger(com.example.configmgr.ai.observe.AiTrace.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		emitterLogger.addAppender(appender);
		try {
			out.delta("d1");
			assertThat(slow.awaitFirstSend(5L)).isTrue();
			assertThat(awaitSubscriberCount(out, 0, 3L)).as("超预算 ⇒ 摘除").isTrue();

			String detach = awaitEventLine(appender, "sse_detach");
			assertThat(detach).as("摘除的结构化出口").contains("kind=write_timeout")
				.contains("runId=" + RUN_ID).containsPattern("budgetMs=\\d+");
		}
		finally {
			emitterLogger.detachAppender(appender);
			slow.release();
		}
	}

	@Test
	void writeTimeoutEvictsTheSubscriberAndClosesItWhenTheStuckWriteFinallyReturns() throws Exception {
		InMemoryRunStore store = new InMemoryRunStore();
		SseChatEmitter out = budgeted(store, 256, 300L);
		MonitorFaithfulEmitter slow = new MonitorFaithfulEmitter(true, false);
		out.attach(slow.emitter());

		out.delta("d1");
		assertThat(slow.awaitFirstSend(5L)).isTrue();
		assertThat(awaitSubscriberCount(out, 0, 3L)).as("超预算 ⇒ 摘除（不再投递）").isTrue();
		assertThat(slow.completes())
				.as("超时时不得调用 complete：写线程仍持有 emitter 监视器，就地 complete 只会再挂一个线程")
				.isZero();

		slow.release();
		assertThat(slow.awaitCompletes(1, 5L)).as("写出返回后由写出线程补做收尾").isTrue();
		assertThat(slow.completeThreads()).as("收尾发生在写出线程（send 返回后监视器已释放）")
				.allMatch(name -> name.startsWith("budget-writer"));
		out.delta("摘除之后的帧");
		TimeUnit.MILLISECONDS.sleep(100L);
		assertThat(slow.payloads()).as("已摘除的订阅者不再收帧").hasSize(1);
	}

	// ── S3-② 收口：调用线程被中断也不静默丢帧（与 TIMED_OUT 同出口 = 摘除断连） ──

	@Test
	void interruptedWriteEvictsTheSubscriberInsteadOfSilentlyDroppingTheFrame() throws Exception {
		InMemoryRunStore store = new InMemoryRunStore();
		// 投递走本用例线程（Runnable::run）⇒ drain 在本线程里等 future.get，可由辅助线程精确打断它；
		// 写预算给到 30s ⇒ 唯一能让 write 提前返回的就是中断本身（不是超时）。
		SseChatEmitter out = new SseChatEmitter(RUN_ID, store, MAPPER, Runnable::run, 256,
				AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES, SseWriteBudget.on(this.writerPool, 30_000L));
		MonitorFaithfulEmitter slow = new MonitorFaithfulEmitter(true, false);
		out.attach(slow.emitter());

		Thread caller = Thread.currentThread();
		Thread interrupter = new Thread(() -> {
			try {
				if (slow.awaitFirstSend(5L)) {
					caller.interrupt();
				}
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}, "budget-interrupter");
		interrupter.setDaemon(true);
		interrupter.start();

		out.delta("中断落在这一帧的写出里");

		assertThat(Thread.interrupted()).as("中断位已由 SseWriteBudget.write 复位（读后即清除）").isTrue();
		assertThat(out.subscriberCount())
				.as("中断 ⇒ 与写超时同出口摘除订阅者（主动断连让前端重挂对账），而非丢帧且不断连")
				.isZero();
		assertThat(slow.completes()).as("写出仍在飞 ⇒ 不在调用线程 complete，交接给写出线程").isZero();

		slow.release();
		assertThat(slow.awaitCompletes(1, 5L)).as("写出返回后由写出线程补做收尾").isTrue();
	}

	// ── 写在飞期间的收尾请求：不得阻塞出帧线程 ───────────────────────────────

	@Test
	void overflowEvictionDuringAnInFlightWriteDoesNotBlockTheCallingThread() throws Exception {
		InMemoryRunStore store = new InMemoryRunStore();
		// 容量 1 + 长预算：本用例只验"溢出摘除的收尾不与在飞写出争 emitter 监视器"
		SseChatEmitter out = budgeted(store, 1, 30_000L);
		MonitorFaithfulEmitter slow = new MonitorFaithfulEmitter(true, false);
		out.attach(slow.emitter());

		out.delta("f1");
		assertThat(slow.awaitFirstSend(5L)).as("写在飞（写出线程持有 emitter 监视器）").isTrue();
		out.delta("f2");

		long before = System.nanoTime();
		out.delta("f3");
		long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);

		assertThat(elapsedMillis).as("溢出摘除的收尾不得阻塞出帧线程（修复前 = 挂在 emitter 监视器上）")
				.isLessThan(2_000L);
		assertThat(out.subscriberCount()).isZero();
		assertThat(slow.completes()).as("收尾交接给写出线程").isZero();

		slow.release();
		assertThat(slow.awaitCompletes(1, 5L)).as("写出返回后补做收尾").isTrue();
	}

	// ── 写池硬上限：饱和摘除"最后拿不到写线程"的那一条订阅者 ───────────────────

	/**
	 * <b>写池饱和 ⇒ 摘除触发本次写出的订阅者</b>（资源修复批）。
	 *
	 * <p>池的线程数是硬上限（{@code SseWriteBudget.DEFAULT_MAX_WRITER_THREADS}）。上限之下的
	 * 慢连接只会让"自己在飞的那一次写"卡住，不会让全进程停投；上限之上必须<b>不再开线程</b>，
	 * 而是把"挤不进来"的那条订阅者摘掉（前端 onerror/onclose 带 lastSeq 重挂差量补发）。
	 *
	 * <p><b>被摘者可能是健康订阅者</b>（本用例里就是：它一帧都没卡住，只是没抢到写线程），
	 * 也可能来自另一条通道（写池由 AI 通道与任务通道共用）。本用例的断言口径因此是
	 * 「<b>最后无法获得写线程者</b>」，不是「只摘慢订阅者」—— 代价是一次重挂，不是丢帧（帧都在归档里）。
	 *
	 * <p>本用例把上限压到 1（生产 = 256，形状相同）：
	 * <ol>
	 * <li>慢订阅者的首帧写出卡住唯一的写线程（直到释放）；</li>
	 * <li>健康订阅者的写出提交被拒 ⇒ {@code SATURATED} ⇒ 它被摘除，且<b>调用线程不等超时</b>；</li>
	 * <li>写线程数始终 ≤ 1（旧池在这里会再开一条线程、把帧发出去 —— 也就是"无界线程"）。</li>
	 * </ol>
	 */
	@Test
	void writerPoolSaturationEvictsTheSubscriberThatCouldNotBeWritten() throws Exception {
		InMemoryRunStore store = new InMemoryRunStore();
		ThreadPoolExecutor singleWriter = new ThreadPoolExecutor(0, 1, 60L, TimeUnit.SECONDS,
				new SynchronousQueue<>(), daemon("saturated-writer"), new ThreadPoolExecutor.AbortPolicy());
		try {
			SseChatEmitter out = new SseChatEmitter(RUN_ID, store, MAPPER, this.deliveryPool,
					AiProperties.Sse.DEFAULT_DELIVERY_QUEUE_CAPACITY, AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES,
					SseWriteBudget.on(singleWriter, 300L));
			MonitorFaithfulEmitter slow = new MonitorFaithfulEmitter(true, false);
			FrameWire healthy = FrameWire.attachTo(out);
			out.attach(slow.emitter());

			long saturationsBefore = SseWriteBudget.saturations();
			out.delta("占住唯一的写线程");
			assertThat(slow.awaitFirstSend(5L)).as("慢订阅者的写出卡在唯一的写线程上").isTrue();

			out.delta("健康订阅者的帧");
			assertThat(awaitSubscriberCount(out, 0, 5L)).as("两条订阅者最终都离开订阅表（超时 + 饱和各摘一条）")
				.isTrue();
			assertThat(SseWriteBudget.saturations()).as("饱和计数 +1（与写超时分开可观测）")
				.isGreaterThan(saturationsBefore);
			assertThat(FrameWire.normalize(healthy.frames())).as("饱和的那一帧没有写出（改由前端带 lastSeq 重挂补回）")
				.extracting(frame -> frame.get("type"))
				.containsExactly("delta");
			assertThat(healthy.size()).as("健康订阅者只收到饱和之前的那一帧").isEqualTo(1);
			assertThat(singleWriter.getPoolSize()).as("写线程数不超过硬上限（不再无界扩线程）").isLessThanOrEqualTo(1);

			slow.release();
		}
		finally {
			singleWriter.shutdownNow();
		}
	}

	// ── C1：饱和摘除必须真的 complete（否则前端永不重挂） ─────────────────────

	/**
	 * <b>饱和摘除必须走到 {@code emitter.complete()}</b> —— 订阅者离开表只是内部状态，
	 * 前端能不能重挂取决于<b>它是否收到 onerror/onclose</b>。
	 *
	 * <p>缺陷形态（评审 C1）：预算模式的写出先把 {@code Subscription#writeInFlight} 置真再提交；
	 * 池饱和时<b>提交被拒、任务 lambda 从未执行</b> ⇒ 置真的标志永远不会被写线程的 {@code finally}
	 * 复位 ⇒ {@link SseChatEmitter#completeOne} 把收尾"交接给一个不存在的写线程"（只置
	 * {@code completePending} 就返回）⇒ <b>永不 complete</b>。旧用例只查 {@code subscriberCount()}
	 * （被 {@code closeSubscription} 摘表后就归零），所以是假绿。
	 *
	 * <p>本用例的判据是<b>外部可观测行为</b>：①受影响的 emitter 恰好 complete 一次；
	 * ②前端重挂（这里用 {@code replayTo} 模拟带 {@code lastSeq} 的差量重挂）能拿回饱和时没写出的帧。
	 */
	@Test
	void saturatedWriteStillCompletesTheEmitterSoTheFrontendCanReattach() throws Exception {
		InMemoryRunStore store = new InMemoryRunStore();
		ThreadPoolExecutor singleWriter = new ThreadPoolExecutor(0, 1, 60L, TimeUnit.SECONDS,
				new SynchronousQueue<>(), daemon("saturated-writer"), new ThreadPoolExecutor.AbortPolicy());
		try {
			SseChatEmitter out = new SseChatEmitter(RUN_ID, store, MAPPER, this.deliveryPool,
					AiProperties.Sse.DEFAULT_DELIVERY_QUEUE_CAPACITY,
					AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES, SseWriteBudget.on(singleWriter, 300L));
			MonitorFaithfulEmitter slow = new MonitorFaithfulEmitter(true, false);
			MonitorFaithfulEmitter healthy = new MonitorFaithfulEmitter(false, false);
			out.attach(slow.emitter());
			out.attach(healthy.emitter());

			out.delta("占住唯一的写线程");
			assertThat(slow.awaitFirstSend(5L)).as("慢订阅者的写出卡在唯一的写线程上").isTrue();
			assertThat(awaitSubscriberCount(out, 0, 5L)).as("两条订阅者都离开订阅表（超时 + 饱和各摘一条）")
				.isTrue();

			// C1 判据：饱和摘除必须真的 complete —— 否则前端收不到 onerror/onclose，不会重挂
			assertThat(healthy.awaitCompletes(1, 5L)).as("饱和摘除必须 complete 该 emitter（前端据此重挂）")
				.isTrue();
			assertThat(healthy.completes()).as("恰好 complete 一次（不重复 onCompletion）").isEqualTo(1);

			// 让出唯一的写线程（模拟"卡住的那次写最终返回"），慢订阅者的收尾仍由写出线程补做。
			// 注意：收尾发生在写线程的 finally 里、任务尚未返回 —— 必须等池真的空下来再重挂，
			// 否则重挂的写出仍会撞上"池忙"（那是另一条断言的事，不是本用例的被测点）。
			slow.release();
			assertThat(slow.awaitCompletes(1, 5L)).as("慢订阅者的收尾仍由写出线程在 send 返回后补做").isTrue();
			assertThat(slow.completes()).isEqualTo(1);
			assertThat(awaitWriterIdle(singleWriter, 5L)).as("写出线程已归还").isTrue();

			// 前端可重挂：饱和时没写出的帧仍在归档里，重挂的差量回放能补齐（seq 无洞）
			out.delta("饱和之后的新帧");
			FrameWire reattached = new FrameWire();
			SseChatEmitter.ReplayResult replay = out.replayToWithStatus(reattached.emitter(), 0L);
			assertThat(replay.staged()).as("重挂回放暂存帧数（归档里应有 2 帧）").isEqualTo(2);
			awaitSize(reattached, 2, 3_000L);
			assertThat(FrameWire.normalize(reattached.frames())).extracting(frame -> frame.get("seq"))
				.as("重挂回放拿到全部归档帧（饱和只断连，不丢帧）").containsExactly(1L, 2L);
		}
		finally {
			singleWriter.shutdownNow();
		}
	}

	// ── 兼容性：预算模式下的帧口径与 T4-3 逐条不变 ──────────────────────────

	@Test
	void budgetedWritePathKeepsTheFrameAndT43Contract() throws Exception {
		InMemoryRunStore store = new InMemoryRunStore();
		SseChatEmitter out = budgeted(store, 256, 5_000L);
		FrameWire wire = FrameWire.attachTo(out);

		out.start("s-wb");
		out.delta("正文");
		out.confirmRequest(pending("c-1"));
		out.heartbeat(Map.of("phase", "suspended"));
		out.done(Map.of(), "deepseek-flash", Map.of("cancelled", false));
		awaitSize(wire, 5, 5_000L);

		List<Map<String, Object>> live = FrameWire.normalize(wire.frames());
		// 事件顺序（S4.2 §3 终端契约）：start → delta → confirm_request → heartbeat → done
		assertThat(live).extracting(frame -> frame.get("type"))
				.containsExactly("start", "delta", "confirm_request", "heartbeat", "done");

		// T4-3 后半：atMs 在帧构造时刻定型 ⇒ 实时投递帧全部带 atMs
		for (Map<String, Object> frame : live) {
			assertThat(frame.get("atMs")).as("实时帧 %s 必须带 atMs", frame.get("type")).isInstanceOf(Number.class);
		}
		// 业务帧带单调 seq；心跳不占业务 seq（S5c-1），只在独立空间计数
		assertThat(live.get(0)).containsEntry("seq", 1L);
		assertThat(live.get(1)).containsEntry("seq", 2L);
		assertThat(live.get(2)).containsEntry("seq", 3L);
		assertThat(live.get(4)).containsEntry("seq", 4L);
		assertThat(live.get(3)).doesNotContainKey("seq").containsKey("heartbeatSeq");
		// T4：确认卡不再有到期时刻（2026-10-20 裁决第 1 条）—— 帧里不带 expiresAt/timeoutSeconds
		assertThat(live.get(2)).as("confirm_request 无到期时刻").doesNotContainKey("expiresAt")
				.doesNotContainKey("timeoutSeconds");

		// T4-3：归档（start / delta / confirm_request / done；B1 起 delta 入档）与实时投递帧
		// 取的是同一个 atMs
		List<Map<String, Object>> archived = FrameWire.normalize(store.events(RUN_ID));
		assertThat(archived).extracting(frame -> frame.get("type"))
				.containsExactly("start", "delta", "confirm_request", "done");
		for (Map<String, Object> archivedFrame : archived) {
			long seq = ((Number) archivedFrame.get("seq")).longValue();
			Map<String, Object> liveFrame = live.stream()
					.filter(frame -> frame.get("seq") instanceof Number number && number.longValue() == seq)
					.findFirst()
					.orElseThrow();
			assertThat(archivedFrame.get("atMs")).as("seq=%d 归档 atMs 与实时投递原文一致", seq)
					.isEqualTo(liveFrame.get("atMs"));
		}
	}

	// ── 直执预算（单测/非 Spring 路径）：与修复前逐字等价 ──────────────────

	@Test
	void directBudgetKeepsTheInlineSendPath() throws Exception {
		InMemoryRunStore store = new InMemoryRunStore();
		SseChatEmitter out = new SseChatEmitter(RUN_ID, store, MAPPER, this.deliveryPool, 256,
				AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES);
		MonitorFaithfulEmitter wire = new MonitorFaithfulEmitter(false, false);
		out.attach(wire.emitter());

		out.delta("直执");

		assertThat(wire.awaitFrames(1, 5L)).isTrue();
		assertThat(wire.sendThreads()).as("直执 = 投递池线程内写出（修复前行为不变，帧序用例的确定性依赖它）")
				.allMatch(name -> name.startsWith("budget-delivery"));
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private SseChatEmitter budgeted(InMemoryRunStore store, int queueCapacity, long writeTimeoutMillis) {
		return new SseChatEmitter(RUN_ID, store, MAPPER, this.deliveryPool, queueCapacity,
				AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES,
				SseWriteBudget.on(this.writerPool, writeTimeoutMillis));
	}

	/**
	 * 与真实 {@code ResponseBodyEmitter} <b>同监视器语义</b>的订阅者桩：{@code send} 与
	 * {@code complete} 都在实例上 {@code synchronized}（真实实现如此，实测 Spring 6.2
	 * {@code ResponseBodyEmitter.java:186/248}），且首帧写出可被卡住。
	 *
	 * <p>
	 * 为什么必须同步：本包修的正是"写线程卡在 {@code send} 时持有 emitter 监视器"这条耦合 ——
	 * 桩件若不同步（如 {@code SseChatEmitterDeliveryTest} 的 Mockito mock），
	 * "写在飞期间从别的线程 complete" 就不会挂住，用例对缺陷不敏感（假绿）。
	 */
	private static final class MonitorFaithfulEmitter extends SseEmitter {

		private final boolean blockFirstSend;

		private final boolean failAfterBlockedSend;

		private final CountDownLatch firstSendStarted = new CountDownLatch(1);

		private final CountDownLatch release = new CountDownLatch(1);

		private final List<String> payloads = new CopyOnWriteArrayList<>();

		private final List<String> sendThreads = new CopyOnWriteArrayList<>();

		private final AtomicInteger sends = new AtomicInteger();

		private final AtomicInteger completes = new AtomicInteger();

		private final List<String> completeThreads = new CopyOnWriteArrayList<>();

		private volatile boolean completed;

		MonitorFaithfulEmitter(boolean blockFirstSend, boolean failAfterBlockedSend) {
			this.blockFirstSend = blockFirstSend;
			this.failAfterBlockedSend = failAfterBlockedSend;
		}

		SseEmitter emitter() {
			return this;
		}

		@Override
		public synchronized void send(Object object) throws IOException {
			if (this.completed) {
				throw new IllegalStateException("ResponseBodyEmitter has already completed");
			}
			if (this.blockFirstSend && this.sends.incrementAndGet() == 1) {
				this.firstSendStarted.countDown();
				// 真实 Tomcat 阻塞写对线程中断不敏感：NioEndpoint#doWrite 捕获 InterruptedException
				// 后继续等（源码 NioEndpoint.java:1399 的 `catch (InterruptedException e) { // Continue }`）。
				// 桩件必须同口径忽略中断 —— 否则写预算的 future.cancel(true) 一打断就"写出结束"，
				// "写卡住期间不得 complete" 的判据会被掩盖（假绿）。
				long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10L);
				boolean released = false;
				while (!released && System.nanoTime() < deadline) {
					try {
						released = this.release.await(50L, TimeUnit.MILLISECONDS);
					}
					catch (InterruptedException ex) {
						// 忽略：真实阻塞写同样继续等
					}
				}
				if (this.failAfterBlockedSend) {
					throw new IOException("客户端断开（写超时后连接失败的模拟）");
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

	private static ThreadFactory daemon(String prefix) {
		AtomicInteger threadNo = new AtomicInteger();
		return runnable -> {
			Thread thread = new Thread(runnable, prefix + "-" + threadNo.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		};
	}

	private static boolean awaitSubscriberCount(SseChatEmitter out, int expected, long seconds)
			throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
		while (out.subscriberCount() != expected && System.nanoTime() < deadline) {
			TimeUnit.MILLISECONDS.sleep(5L);
		}
		return out.subscriberCount() == expected;
	}

	/** 等写出池真的空下来（收尾发生在写线程的 finally 里，任务返回前池仍算忙）。 */
	private static boolean awaitWriterIdle(ThreadPoolExecutor pool, long seconds) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
		while (pool.getActiveCount() > 0 && System.nanoTime() < deadline) {
			TimeUnit.MILLISECONDS.sleep(5L);
		}
		return pool.getActiveCount() == 0;
	}

	private static void awaitSize(FrameWire wire, int size, long timeoutMillis) throws InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMillis;
		while (wire.size() < size && System.currentTimeMillis() < deadline) {
			TimeUnit.MILLISECONDS.sleep(10L);
		}
		assertThat(wire.size()).as("帧线在超时前收到 %d 帧（实际 %d）", size, wire.size()).isEqualTo(size);
	}

	/** 等一条结构化事件行出现（摘除由投递/写出线程产出，与调用线程不同步）。 */
	private static String awaitEventLine(ListAppender<ILoggingEvent> appender, String name)
			throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5000L;
		while (System.currentTimeMillis() < deadline) {
			for (ILoggingEvent event : new CopyOnWriteArrayList<>(appender.list)) {
				String message = event.getFormattedMessage();
				if (message.startsWith("event=" + name + " ")) {
					return message;
				}
			}
			TimeUnit.MILLISECONDS.sleep(20L);
		}
		throw new AssertionError("缺少事件行 event=" + name + "；实收 = " + appender.list.size() + " 行");
	}

	private static PendingToolCall pending(String toolCallId) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(toolCallId);
		pending.setName("start_export");
		pending.setKind(PendingToolCall.KIND_CONFIRM);
		pending.setArguments("{\"taskId\":7}");
		return pending;
	}
}
