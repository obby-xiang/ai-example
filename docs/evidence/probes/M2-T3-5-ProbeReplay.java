/*
 * M2-T3-5 压测定值探针（仓库外）—— S4「stageReplay 锁内序列化 3000 帧成本量化」。
 *
 * 执行者：DS-V4-Flash 施工棒
 * 日期：2026-10-09
 * 运行方式：仓库外另存为 `ProbeReplay.java` 后编译运行（classpath = backend/target/classes + backend/target/test-classes
 *          + `mvn dependency:build-classpath` 产物）。本文件仅为随证留存源码，不参与仓库构建与测试计数。
 * 结论指针：docs/evidence/M2-T3-5-压测证据-DS-V4-Flash.md §S4。
 *
 * 判据直译（"回放 3000 帧不阻塞 emit"）拆两半，分别断言、如实报告：
 *   ① 网络写出不阻塞（异步投递池，drain 在锁外）—— 期望成立；
 *   ② 锁内收集 + 逐帧序列化是否可忽略 —— 由 emit 阻塞时长与 replayAndAttach 墙钟说话，不许替设计卡圆场。
 * 口径：真 Redis（Memurai）；回放语料 = 3000 条状态帧（tool_start）；投递池 = 生产形 4 线程 + 队列 256。
 */
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
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

public class ProbeReplay {

	static final ObjectMapper MAPPER = new ObjectMapper();

	static StringRedisTemplate template;

	static RunStore store;

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

	static int liveQueued(SseChatEmitter out, SseEmitter emitter) throws Exception {
		for (Object sub : (List<?>) field(out, "subscribers")) {
			if (field(sub, "emitter") == emitter) {
				return ((AtomicInteger) field(sub, "liveQueued")).get();
			}
		}
		return -1;
	}

	static Map<String, Object> pctl(String name, List<Long> nanos) {
		List<Long> sorted = new ArrayList<>(nanos);
		Collections.sort(sorted);
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("metric", name);
		row.put("n", sorted.size());
		row.put("p50us", sorted.get(sorted.size() / 2) / 1000.0);
		row.put("p95us", sorted.get((int) (sorted.size() * 0.95)) / 1000.0);
		row.put("p99us", sorted.get(Math.min(sorted.size() - 1, (int) (sorted.size() * 0.99))) / 1000.0);
		row.put("maxus", sorted.get(sorted.size() - 1) / 1000.0);
		return row;
	}

	static Map<String, Object> stateFrame(int i) {
		Map<String, Object> f = new LinkedHashMap<>();
		f.put("type", "tool_start");
		f.put("seq", (long) i);
		f.put("toolCallId", "c-" + i);
		f.put("name", "start_export");
		f.put("args", "{\"taskId\":" + i + "}");
		return f;
	}

	static long evicted() {
		return ((Number) SseChatEmitter.deliveryPoolMetrics().get("evicted")).longValue();
	}

	public static void main(String[] args) throws Exception {
		RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration("127.0.0.1", 6379);
		LettuceConnectionFactory factory = new LettuceConnectionFactory(cfg);
		factory.afterPropertiesSet();
		template = new StringRedisTemplate(factory);
		template.afterPropertiesSet();
		AiProperties props = new AiProperties();
		store = new RunStore(template, MAPPER, props);

		String base = "t35-replay-" + System.currentTimeMillis();
		java.util.Set<String> stale = template.keys("t35-replay-*");
		if (stale != null && !stale.isEmpty()) {
			template.delete(stale);
		}

		String run = base + "-full";
		for (int i = 1; i <= 3000; i++) {
			store.appendEvent(run, stateFrame(i));
		}
		long llen = template.opsForList().size(store.eventsKey(run));
		System.out.println("LLEN_BEFORE_REPLAY=" + llen);

		AtomicInteger poolNo = new AtomicInteger();
		ThreadPoolExecutor pool = new ThreadPoolExecutor(4, 4, 60L, TimeUnit.SECONDS,
				new LinkedBlockingQueue<>(4 * 64), r -> {
					Thread t = new Thread(r, "probe-replay-" + poolNo.incrementAndGet());
					t.setDaemon(true);
					return t;
				});

		// ── ① 纯锁内成本：replayAndAttach 墙钟（快 sink，drain 在锁外异步跑） ──────
		List<Long> replayWallAll = new ArrayList<>();
		List<Integer> stagedCounts = new ArrayList<>();
		List<Long> fullDelivery = new ArrayList<>();
		int iterations = 20;
		int warmup = 3;
		for (int i = 0; i < iterations; i++) {
			FastSink sink = new FastSink();
			long start = System.nanoTime();
			int staged = new SseChatEmitter(run, store, MAPPER, pool, 256).replayTo(sink, null);
			replayWallAll.add(System.nanoTime() - start);
			stagedCounts.add(staged);
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
			while (sink.sent.get() < staged && System.nanoTime() < deadline) {
				TimeUnit.MILLISECONDS.sleep(1);
			}
			fullDelivery.add(System.nanoTime() - start);
		}
		List<Long> replayWall = new ArrayList<>(replayWallAll.subList(warmup, iterations));
		List<Long> fullDeliverySteady = new ArrayList<>(fullDelivery.subList(warmup, iterations));
		System.out.println("REPLAY_WALL_ALL_US=" + replayWallAll);
		System.out.println("REPLAY_FULL_ALL_US=" + fullDelivery);
		System.out.println("REPLAY_WALL " + MAPPER.writeValueAsString(
				pctl("replayAndAttach 墙钟(锁内 LRANGE+3000 帧序列化+入队) 去预热", replayWall)));
		System.out.println("REPLAY_STAGED=" + stagedCounts);
		System.out.println("REPLAY_FULL_DELIVERY " + MAPPER.writeValueAsString(
				pctl("replayAndAttach + 3000 帧全量写出 端到端 去预热", fullDeliverySteady)));

		// ── ② 并发 emit 阻塞量化：一线程持续 emit，另一线程触发 3000 帧回放 ──────
		SseChatEmitter noise = new SseChatEmitter(base + "-noise", store, MAPPER, pool, 256);
		FastSink noiseSink = new FastSink();
		noise.attach(noiseSink);
		List<Long> emitOutside = Collections.synchronizedList(new ArrayList<>());
		List<Long> emitInside = Collections.synchronizedList(new ArrayList<>());
		AtomicLong replayStart = new AtomicLong();
		AtomicLong replayEnd = new AtomicLong();
		AtomicInteger seqNo = new AtomicInteger();

		Thread emitterThread = new Thread(() -> {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
			while (System.nanoTime() < deadline) {
				long start = System.nanoTime();
				noise.retry(Map.of("i", seqNo.incrementAndGet()));
				long elapsed = System.nanoTime() - start;
				long rs = replayStart.get();
				long re = replayEnd.get();
				long now = System.nanoTime();
				if (rs != 0 && now >= rs && (re == 0 || now <= re)) {
					emitInside.add(elapsed);
				}
				else {
					emitOutside.add(elapsed);
				}
				try {
					TimeUnit.MILLISECONDS.sleep(1);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		}, "emit-noise");
		emitterThread.start();
		TimeUnit.MILLISECONDS.sleep(800);

		// 20 轮回放：每轮开一个"卡在首帧写出"的订阅者，让 3000 帧真的压在锁内收集路径上
		List<Long> windows = new ArrayList<>();
		int stagedBlocking = 0;
		List<SlowEmitter> slows = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			SlowEmitter slow = new SlowEmitter();
			slows.add(slow);
			SseChatEmitter out = new SseChatEmitter(run, store, MAPPER, pool, 256);
			replayStart.set(System.nanoTime());
			stagedBlocking = out.replayAndAttach(slow, null, false);
			replayEnd.set(System.nanoTime());
			windows.add(replayEnd.get() - replayStart.get());
			TimeUnit.MILLISECONDS.sleep(50);
		}
		emitterThread.join();
		for (SlowEmitter s : slows) {
			s.release();
		}
		System.out.println("REPLAY_WINDOWS_MS=" + windows.stream().map(n -> Math.round(n / 1000.0) / 1000.0).toList());

		System.out.println("EMIT_OUTSIDE_REPLAY " + MAPPER.writeValueAsString(pctl("emit 单帧耗时（回放窗口外，基线）", emitOutside)));
		System.out.println("EMIT_INSIDE_REPLAY " + MAPPER.writeValueAsString(pctl("emit 单帧耗时（回放窗口内，含等 emitLock）", emitInside)));
		System.out.println("REPLAY_WINDOW_MS=" + (replayEnd.get() - replayStart.get()) / 1_000_000.0);
		System.out.println("STAGED_BLOCKING=" + stagedBlocking);

		// ── ②b 定点阻塞：回放线程持锁时，立刻发一帧，直测 emit 等锁时长 ──────────
		List<Long> lockWait = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			SlowEmitter s = new SlowEmitter();
			slows.add(s);
			SseChatEmitter o = new SseChatEmitter(run, store, MAPPER, pool, 256);
			Thread replayer = new Thread(() -> o.replayAndAttach(s, null, false), "replay-" + i);
			replayer.start();
			TimeUnit.MICROSECONDS.sleep(500);
			long st = System.nanoTime();
			noise.retry(Map.of("i", seqNo.incrementAndGet()));
			lockWait.add(System.nanoTime() - st);
			replayer.join();
			TimeUnit.MILLISECONDS.sleep(30);
		}
		System.out.println("EMIT_LOCK_WAIT " + MAPPER.writeValueAsString(
				pctl("emit 等 emitLock（回放线程持锁时定点触发）", lockWait)));

		// ── ③ 回放批豁免实时容量 + 回放 backlog 排空期实时帧不误摘 ────────────────
		SlowEmitter blocked = new SlowEmitter();
		SseChatEmitter exempt = new SseChatEmitter(run, store, MAPPER, pool, 256);
		long evictedBefore = evicted();
		int staged = exempt.replayAndAttach(blocked, null, false);
		blocked.awaitFirstSend(5);
		int liveAfterStage = liveQueued(exempt, blocked);
		int queueLenAfterStage = ((java.util.concurrent.BlockingQueue<?>) field(
				firstSubscription(exempt, blocked), "queue")).size();
		for (int i = 1; i <= 100; i++) {
			exempt.heartbeat(Map.of("tick", i));
		}
		int liveAfter100 = liveQueued(exempt, blocked);
		long evictedAfter100 = evicted();

		for (int i = 101; i <= 257; i++) {
			exempt.heartbeat(Map.of("tick", i));
		}
		int completesAfter257 = blocked.completes.get();
		long evictedAfter257 = evicted();

		Map<String, Object> exemption = new LinkedHashMap<>();
		exemption.put("staged", staged);
		exemption.put("liveQueuedAfterStage", liveAfterStage);
		exemption.put("queueAbsLenAfterStage", queueLenAfterStage);
		exemption.put("liveQueuedAfter100Heartbeats", liveAfter100);
		exemption.put("evictedDeltaAfter100Heartbeats", evictedAfter100 - evictedBefore);
		exemption.put("completesAfter257Heartbeats", completesAfter257);
		exemption.put("evictedDeltaAfter257Heartbeats", evictedAfter257 - evictedBefore);
		exemption.put("subscribersLeft", exempt.subscriberCount());
		System.out.println("EXEMPTION " + MAPPER.writeValueAsString(exemption));

		blocked.release();
		TimeUnit.MILLISECONDS.sleep(300);
		java.util.Set<String> keys = template.keys("t35-replay-*");
		if (keys != null && !keys.isEmpty()) {
			template.delete(keys);
		}
		pool.shutdownNow();
		factory.destroy();
		System.out.println("PROBE DONE");
	}

	static Object firstSubscription(SseChatEmitter out, SseEmitter emitter) throws Exception {
		for (Object sub : (List<?>) field(out, "subscribers")) {
			if (field(sub, "emitter") == emitter) {
				return sub;
			}
		}
		throw new IllegalStateException("no subscription");
	}

	static class FastSink extends SseEmitter {

		final AtomicInteger sent = new AtomicInteger();

		@Override
		public void send(Object object) throws IOException {
			this.sent.incrementAndGet();
		}

		@Override
		public synchronized void complete() {
			// no-op
		}

	}

	static class SlowEmitter extends SseEmitter {

		final CountDownLatch firstSend = new CountDownLatch(1);

		final CountDownLatch release = new CountDownLatch(1);

		final AtomicInteger completes = new AtomicInteger();

		volatile boolean completed;

		@Override
		public void send(Object object) throws IOException {
			if (this.completed) {
				throw new IllegalStateException("completed");
			}
			if (this.firstSend.getCount() > 0) {
				this.firstSend.countDown();
				try {
					this.release.await(60, TimeUnit.SECONDS);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			}
		}

		@Override
		public synchronized void complete() {
			this.completed = true;
			this.completes.incrementAndGet();
		}

		void release() {
			this.release.countDown();
		}

		void awaitFirstSend(long seconds) throws InterruptedException {
			this.firstSend.await(seconds, TimeUnit.SECONDS);
		}

	}

	static class BlockingSlow extends SlowEmitter {

		SseEmitter emitter() {
			return this;
		}

	}

}
