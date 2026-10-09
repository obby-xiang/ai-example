/*
 * M2-T3-5 压测定值探针（仓库外）—— 场景 E「N 挂起 × M 慢订阅者」矩阵 + 场景 A 并发阶梯 + 场景 D 8 路并发 0 丢消息。
 *
 * 执行者：DS-V4-Flash 施工棒
 * 日期：2026-10-09
 * 运行方式：仓库外另存为 `ProbeMatrix.java` 后编译运行（classpath = backend/target/classes + backend/target/test-classes
 *          + `mvn dependency:build-classpath` 产物）。本文件仅为随证留存源码，不参与仓库构建与测试计数。
 * 结论指针：docs/evidence/M2-T3-5-压测证据-DS-V4-Flash.md §S1。
 *
 * provenance 注记（T3-5 修正棒按红队 F11.2 补入；注释级，探针逻辑未改）：
 *  - 本文件是**修订后**版本；附件清单中的 `T3-5-附件-S1-矩阵原始stdout.log`（矩阵原始 stdout）
 *    由**修订前**版本产出，并以未捕获异常收尾（`IllegalArgumentException at ProbeMatrix.runLadder`，
 *    无 `PROBE DONE` 行）⇒"随证留存源码"与该附件的二进制不同源。
 *  - 场景 A/D 的数据来自附件二（`T3-5-附件-S1-场景AD与联动公式.log`）的另一次重跑，非该崩溃附件。
 *
 * 口径说明（报告须并列复述）：
 *  - S1 = 进程内替身口径（RecordingEmitter 造慢 SseEmitter 模拟 TCP 背压），不是真实 HTTP/TCP；
 *    真实 HTTP 口径见 S8。两处结论不互相替代。
 *  - EVICTED_TOTAL 是 static final AtomicLong，进程级累计值（溢出/写出失败/池拒绝共用），
 *    非窗口值、非本轮值、不区分摘除原因。本探针只报"格内增量"。
 *  - 投递池为每格新建（不读进程静态 sharedDeliveryPool），以隔离"首个装配者胜"的静态污染。
 */
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class ProbeMatrix {

	static final ObjectMapper MAPPER = new ObjectMapper();

	// ── 订阅者替身 ────────────────────────────────────────────────────────────

	/** 慢订阅者：首帧写出阻塞在 latch 上（模拟 TCP 背压），release 后按正常速度排出。 */
	static class SlowEmitter extends SseEmitter {

		final CountDownLatch sendStarted = new CountDownLatch(1);

		final CountDownLatch release = new CountDownLatch(1);

		final AtomicInteger sends = new AtomicInteger();

		final AtomicInteger completes = new AtomicInteger();

		volatile boolean completed;

		final boolean block;

		SlowEmitter(boolean block) {
			this.block = block;
		}

		@Override
		public void send(Object object) throws IOException {
			if (this.completed) {
				throw new IllegalStateException("ResponseBodyEmitter has already completed");
			}
			if (this.block && this.sends.incrementAndGet() == 1) {
				this.sendStarted.countDown();
				try {
					this.release.await(60, TimeUnit.SECONDS);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
				return;
			}
			this.sends.incrementAndGet();
		}

		@Override
		public synchronized void complete() {
			this.completed = true;
			this.completes.incrementAndGet();
		}

		void release() {
			this.release.countDown();
		}

	}

	/** 健康订阅者：记录每帧到达时刻。 */
	static class FastEmitter extends SseEmitter {

		final List<Long> arrivalNanos = Collections.synchronizedList(new ArrayList<>());

		final AtomicInteger completes = new AtomicInteger();

		volatile boolean completed;

		@Override
		public void send(Object object) throws IOException {
			if (this.completed) {
				throw new IllegalStateException("ResponseBodyEmitter has already completed");
			}
			this.arrivalNanos.add(System.nanoTime());
		}

		@Override
		public synchronized void complete() {
			this.completed = true;
			this.completes.incrementAndGet();
		}

	}

	// ── 反射读数（探针口径，非生产 API） ──────────────────────────────────────

	static Object field(Object target, String name) throws Exception {
		Class<?> type = target.getClass();
		while (type != null) {
			try {
				Field f = type.getDeclaredField(name);
				f.setAccessible(true);
				return f.get(target);
			}
			catch (NoSuchFieldException ex) {
				type = type.getSuperclass();
			}
		}
		throw new IllegalStateException("no field " + name);
	}

	static int liveQueuedOf(SseChatEmitter out, SseEmitter emitter) throws Exception {
		for (Object sub : (List<?>) field(out, "subscribers")) {
			if (field(sub, "emitter") == emitter) {
				return ((AtomicInteger) field(sub, "liveQueued")).get();
			}
		}
		return -1;
	}

	static long evictedTotal() {
		return ((Number) SseChatEmitter.deliveryPoolMetrics().get("evicted")).longValue();
	}

	// ── 池 ────────────────────────────────────────────────────────────────────

	static ThreadPoolExecutor pool(int size, int queueCapacity, String prefix) {
		AtomicInteger n = new AtomicInteger();
		return new ThreadPoolExecutor(size, size, 60L, TimeUnit.SECONDS,
				new LinkedBlockingQueue<>(queueCapacity), r -> {
					Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
					t.setDaemon(true);
					return t;
				}, new ThreadPoolExecutor.AbortPolicy());
	}

	static Map<String, Object> statusFrame(String runId, int index) {
		Map<String, Object> f = new LinkedHashMap<>();
		f.put("type", "tool_start");
		f.put("toolCallId", runId + "-" + index);
		return f;
	}

	static void sleep(long ms) {
		try {
			TimeUnit.MILLISECONDS.sleep(ms);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	/** 等健康侧累计到 target 帧，返回达标的健康订阅者数。 */
	static int awaitHealthy(List<FastEmitter> healthy, int target, long timeoutMs) {
		long deadline = System.currentTimeMillis() + timeoutMs;
		int count = 0;
		while (System.currentTimeMillis() < deadline) {
			count = 0;
			for (FastEmitter fast : healthy) {
				if (fast.arrivalNanos.size() >= target) {
					count++;
				}
			}
			if (count >= healthy.size()) {
				return count;
			}
			sleep(5);
		}
		return count;
	}

	// ── 场景 E：N 挂起 × M 慢订阅者 ──────────────────────────────────────────

	static Map<String, Object> runCell(int totalRuns, int slowCount, int poolSize, int queueCapacity,
			long healthyWaitMs) throws Exception {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("N", totalRuns);
		row.put("M", slowCount);
		row.put("poolSize", poolSize);

		ThreadPoolExecutor pool = pool(poolSize, queueCapacity, "mix-pool");
		InMemoryRunStore store = new InMemoryRunStore();
		List<FastEmitter> healthy = new ArrayList<>();
		List<SlowEmitter> slow = new ArrayList<>();
		List<SseChatEmitter> emitters = new ArrayList<>();
		long evictedBefore = evictedTotal();

		for (int i = 0; i < totalRuns; i++) {
			SseChatEmitter out = new SseChatEmitter("run-" + i, store, MAPPER, pool, 256);
			emitters.add(out);
			FastEmitter fast = new FastEmitter();
			healthy.add(fast);
			out.attach(fast);
		}
		// M 个慢订阅者轮转挂到 N 个 run 上（每 run 至少 1 个健康订阅者）
		for (int m = 0; m < slowCount; m++) {
			SlowEmitter slowEmitter = new SlowEmitter(true);
			slow.add(slowEmitter);
			emitters.get(m % totalRuns).attach(slowEmitter);
		}

		// 预热：每个 run 一帧，让 M 个慢 drain 卡在首帧写出上（占住池线程）
		for (SseChatEmitter out : emitters) {
			out.retry(Map.of("kind", "warmup"));
		}
		int expectedBlocked = Math.min(slowCount, poolSize);
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		int blocked = 0;
		while (System.nanoTime() < deadline) {
			blocked = 0;
			for (SlowEmitter s : slow) {
				if (s.sendStarted.getCount() == 0) {
					blocked++;
				}
			}
			if (blocked >= expectedBlocked) {
				break;
			}
			sleep(5);
		}
		row.put("slowDrainsBlocked", blocked);
		row.put("poolActiveDuringBlock", pool.getActiveCount());
		row.put("poolQueueDepthDuringBlock", pool.getQueue().size());

		// 健康订阅者投递节奏：t0 到"第 2 帧到达"的时延（测量边界 = 摘除前）
		// 先让健康侧收到第 1 帧（基线），再以 t0 为起点发第 2 帧并量到达滞后。
		sleep(120); // 给被阻塞的慢 drain 一个稳定的饱和窗口
		long batch1 = System.nanoTime();
		for (SseChatEmitter out : emitters) {
			out.retry(Map.of("kind", "cadence-1"));
		}
		int receivedAfterBatch1 = awaitHealthy(healthy, 1, healthyWaitMs);
		long t0 = System.nanoTime();
		for (SseChatEmitter out : emitters) {
			out.retry(Map.of("kind", "cadence-2"));
		}
		int receivedAfterBatch2 = awaitHealthy(healthy, 2, healthyWaitMs);
		long maxDeviationMs = -1;
		int healthyReceived = 0;
		for (FastEmitter fast : healthy) {
			List<Long> arrivals = new ArrayList<>(fast.arrivalNanos);
			if (arrivals.size() >= 2) {
				healthyReceived++;
				maxDeviationMs = Math.max(maxDeviationMs, (arrivals.get(1) - t0) / 1_000_000L);
			}
		}
		row.put("healthyFramesAfterBatch1", receivedAfterBatch1);
		row.put("healthyReceivedBeforeEviction", receivedAfterBatch2);
		row.put("healthyCadenceDeviationMsMax", maxDeviationMs);
		row.put("cadenceUnderOneSecond", maxDeviationMs >= 0 && maxDeviationMs < 1000);
		row.put("healthyStarved", healthyReceived < totalRuns);
		row.put("batch1EmitUs", (t0 - batch1) / 1000);

		// 慢订阅者 liveQueued 峰值（回放批不适用；本格全是实时帧）
		int livePeak = 0;
		for (int i = 0; i < slow.size(); i++) {
			int runIndex = i % totalRuns;
			livePeak = Math.max(livePeak, liveQueuedOf(emitters.get(runIndex), slow.get(i)));
		}
		row.put("slowLiveQueuedPeak", livePeak);

		// 摘除子探针：专用 run + 1 个慢订阅者，灌 257 条实时帧（容量 256）
		SseChatEmitter overflowOut = new SseChatEmitter("run-overflow-" + totalRuns + "-" + slowCount, store, MAPPER,
				pool, 256);
		SlowEmitter overflowSlow = new SlowEmitter(true);
		overflowOut.attach(overflowSlow);
		overflowOut.retry(Map.of("kind", "overflow-warmup"));
		for (int i = 0; i < 260; i++) {
			overflowOut.retry(Map.of("kind", "overflow", "i", i));
		}
		long evictedAfter = evictedTotal();
		row.put("evictedDeltaInCell", evictedAfter - evictedBefore);
		row.put("overflowSlowCompleteCalls", overflowSlow.completes.get());
		row.put("overflowEmitterStillSubscribed", overflowOut.subscriberCount());

		for (SlowEmitter s : slow) {
			s.release();
		}
		overflowSlow.release();
		sleep(200);
		pool.shutdownNow();
		return row;
	}

	// ── 场景 A：并发挂起阶梯（AiExecutorConfig 的同形模型：core=max=20，队列 0，AbortPolicy） ──

	static Map<String, Object> runLadder(int concurrency, int suspendPoolSize) throws Exception {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("concurrency", concurrency);
		// AiExecutorConfig 的同形模型：core=max=pool-size，队列容量 0（ThreadPoolTaskExecutor#setQueueCapacity(0)
		// ⇒ SynchronousQueue），AbortPolicy ⇒ 池满即拒绝（生产映射为 503 SUSPEND_POOL_SATURATED）
		ThreadPoolExecutor executor = new ThreadPoolExecutor(suspendPoolSize, suspendPoolSize, 60L,
				TimeUnit.SECONDS, new java.util.concurrent.SynchronousQueue<>(), r -> {
					Thread t = new Thread(r, "suspend-probe");
					t.setDaemon(true);
					return t;
				}, new ThreadPoolExecutor.AbortPolicy());
		CountDownLatch hold = new CountDownLatch(1);
		AtomicInteger admitted = new AtomicInteger();
		AtomicInteger rejected = new AtomicInteger();
		List<Thread> drivers = new ArrayList<>();
		long start = System.nanoTime();
		CountDownLatch firstAccepted = new CountDownLatch(1);
		AtomicLong firstAcceptedNanos = new AtomicLong();
		for (int i = 0; i < concurrency; i++) {
			Thread t = new Thread(() -> {
				try {
					executor.execute(() -> {
						admitted.incrementAndGet();
						firstAcceptedNanos.compareAndSet(0, System.nanoTime());
						firstAccepted.countDown();
						try {
							hold.await(30, TimeUnit.SECONDS);
						}
						catch (InterruptedException ex) {
							Thread.currentThread().interrupt();
						}
					});
				}
				catch (java.util.concurrent.RejectedExecutionException ex) {
					rejected.incrementAndGet();
				}
			}, "driver-" + i);
			drivers.add(t);
		}
		for (Thread t : drivers) {
			t.start();
		}
		for (Thread t : drivers) {
			t.join();
		}
		firstAccepted.await(5, TimeUnit.SECONDS);
		// 池任务体是异步启动的：等一拍再读计数，否则 admitted 会被"还没来得及 start 的任务"少计
		TimeUnit.MILLISECONDS.sleep(500L);
		long firstByteMs = firstAcceptedNanos.get() == 0 ? -1 : (firstAcceptedNanos.get() - start) / 1_000_000L;
		row.put("admitted", admitted.get());
		row.put("rejected", rejected.get());
		row.put("rejectedStatusEquivalent", "503 SUSPEND_POOL_SATURATED");
		row.put("firstAdmittedLatencyMs", firstByteMs);
		hold.countDown();
		executor.shutdownNow();
		return row;
	}

	// ── 场景 D：8 路并发 0 丢消息回归 ─────────────────────────────────────────

	static Map<String, Object> runEightWay() throws Exception {
		Map<String, Object> row = new LinkedHashMap<>();
		int runs = 8;
		int framesPerRun = 200;
		ThreadPoolExecutor pool = pool(4, 4 * 64, "eightway-pool");
		InMemoryRunStore store = new InMemoryRunStore();
		List<FastEmitter> subs = new ArrayList<>();
		List<SseChatEmitter> emitters = new ArrayList<>();
		for (int i = 0; i < runs; i++) {
			SseChatEmitter out = new SseChatEmitter("eight-" + i, store, MAPPER, pool, 256);
			FastEmitter fast = new FastEmitter();
			subs.add(fast);
			emitters.add(out);
			out.attach(fast);
		}
		List<Thread> threads = new ArrayList<>();
		for (int i = 0; i < runs; i++) {
			final int index = i;
			Thread t = new Thread(() -> {
				SseChatEmitter out = emitters.get(index);
				for (int f = 1; f <= framesPerRun; f++) {
					out.retry(Map.of("i", f));
				}
			}, "producer-" + i);
			threads.add(t);
			t.start();
		}
		for (Thread t : threads) {
			t.join();
		}
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		boolean complete = false;
		while (System.nanoTime() < deadline) {
			complete = true;
			for (FastEmitter fast : subs) {
				if (fast.arrivalNanos.size() < framesPerRun) {
					complete = false;
					break;
				}
			}
			if (complete) {
				break;
			}
			sleep(10);
		}
		int minDelivered = Integer.MAX_VALUE;
		int totalDelivered = 0;
		long minArchived = Long.MAX_VALUE;
		for (int i = 0; i < runs; i++) {
			int delivered = subs.get(i).arrivalNanos.size();
			minDelivered = Math.min(minDelivered, delivered);
			totalDelivered += delivered;
			minArchived = Math.min(minArchived, store.events("eight-" + i).size());
		}
		row.put("runs", runs);
		row.put("framesPerRun", framesPerRun);
		row.put("minDeliveredPerRun", minDelivered);
		row.put("totalDelivered", totalDelivered);
		row.put("expectedTotal", runs * framesPerRun);
		row.put("archivedFramesPerRun", minArchived);
		row.put("lostMessages", runs * framesPerRun - totalDelivered);
		row.put("evictedDelta", evictedTotal());
		pool.shutdownNow();
		return row;
	}

	// ── 容量联动公式 ──────────────────────────────────────────────────────────

	static Map<String, Object> linkage(int suspendPoolSize, int deliveryPoolSize) {
		Map<String, Object> row = new LinkedHashMap<>();
		int cores = Runtime.getRuntime().availableProcessors();
		int boundedElastic = readBoundedElasticSize();
		int required = suspendPoolSize * 2 + deliveryPoolSize;
		row.put("cpuCores", cores);
		row.put("boundedElasticCapacity", boundedElastic);
		row.put("boundedElasticSource", BE_SOURCE);
		row.put("required", suspendPoolSize + " x 2 + " + deliveryPoolSize + " = " + required);
		row.put("holds", required <= boundedElastic);
		row.put("headroom", boundedElastic - required);
		return row;
	}

	static String BE_SOURCE = "unknown";

	static int readBoundedElasticSize() {
		try {
			Class<?> schedulers = Class.forName("reactor.core.scheduler.Schedulers");
			Field f = schedulers.getDeclaredField("DEFAULT_BOUNDED_ELASTIC_SIZE");
			f.setAccessible(true);
			BE_SOURCE = "reactor.core.scheduler.Schedulers#DEFAULT_BOUNDED_ELASTIC_SIZE (实测反射)";
			return ((Number) f.get(null)).intValue();
		}
		catch (Exception ex) {
			BE_SOURCE = "兜底公式 10 x CPU（反射失败：" + ex + "）";
			return 10 * Runtime.getRuntime().availableProcessors();
		}
	}

	public static void main(String[] args) throws Exception {
		boolean ladderOnly = args.length > 0 && "ladder-only".equals(args[0]);
		int[] ns = { 1, 5, 10, 20, 25 };
		int[] ms = { 1, 4, 8, 16 };
		int[] coreCells = { 1, 5, 10, 20, 25 };
		int[] coreSlow = { 1, 1, 4, 8, 16 };
		long healthyWaitMs = 1500L;
		List<Map<String, Object>> matrix = new ArrayList<>();
		if (!ladderOnly) {
			long startAll = System.currentTimeMillis();
			for (int n : ns) {
				for (int m : ms) {
					boolean core = false;
					for (int i = 0; i < coreCells.length; i++) {
						if (coreCells[i] == n && coreSlow[i] == m) {
							core = true;
						}
					}
					Map<String, Object> row = runCell(n, m, 4, 4 * 64, healthyWaitMs);
					row.put("coreCell", core);
					matrix.add(row);
					System.out.println("CELL " + MAPPER.writeValueAsString(row));
				}
			}
			System.out.println("MATRIX_MS=" + (System.currentTimeMillis() - startAll));
			System.out.println("MATRIX_JSON=" + MAPPER.writeValueAsString(matrix));

			// ── 池容量扫描（S5 定值依据）：N=10 固定，扫 (poolSize, M) ────────────────
			List<Map<String, Object>> sizing = new ArrayList<>();
			for (int poolSize : new int[] { 4, 8, 16, 20 }) {
				for (int m : new int[] { 1, 4, 8, 16 }) {
					Map<String, Object> row = runCell(10, m, poolSize, poolSize * 64, healthyWaitMs);
					row.put("kind", "sizing");
					sizing.add(row);
					System.out.println("SIZING " + MAPPER.writeValueAsString(row));
				}
			}
			System.out.println("SIZING_JSON=" + MAPPER.writeValueAsString(sizing));
		}

		List<Map<String, Object>> ladder = new ArrayList<>();
		for (int c : new int[] { 1, 5, 10, 20, 25 }) {
			Map<String, Object> row = runLadder(c, 20);
			ladder.add(row);
			System.out.println("LADDER " + MAPPER.writeValueAsString(row));
		}
		System.out.println("LADDER_JSON=" + MAPPER.writeValueAsString(ladder));

		Map<String, Object> d = runEightWay();
		System.out.println("SCENARIO_D " + MAPPER.writeValueAsString(d));

		System.out.println("LINKAGE " + MAPPER.writeValueAsString(linkage(20, 4)));
		System.out.println("PROBE DONE");
	}

	static List<Integer> asList(int... values) {
		List<Integer> out = new ArrayList<>();
		Arrays.stream(values).forEach(out::add);
		return out;
	}

}
