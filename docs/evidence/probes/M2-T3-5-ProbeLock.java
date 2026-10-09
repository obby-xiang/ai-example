/*
 * M2-T3-5 压测定值探针（仓库外）—— S3「锁内 Redis 往返量化」。
 *
 * 执行者：DS-V4-Flash 施工棒
 * 日期：2026-10-09
 * 运行方式：仓库外另存为 `ProbeLock.java` 后编译运行（classpath = backend/target/classes + backend/target/test-classes
 *          + `mvn dependency:build-classpath` 产物）。本文件仅为随证留存源码，不参与仓库构建与测试计数。
 * 结论指针：docs/evidence/M2-T3-5-压测证据-DS-V4-Flash.md §S3。
 *
 * 口径：
 *  - 真 Redis（Memurai 127.0.0.1:6379）经 StringRedisTemplate；禁用 InMemoryRunStore 冒充。
 *  - 读数与本机环境绑定（本机 Memurai RTT 基线随 PING 一并列报）。
 *  - p50/p95/p99/max 单位 µs（redis 单次往返常在亚毫秒级，ms 粒度会全变 0）。
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ProbeLock {

	static final ObjectMapper MAPPER = new ObjectMapper();

	static StringRedisTemplate template;

	static RunStore store;

	static Map<String, Object> stats(String name, long[] nanos) {
		long[] sorted = nanos.clone();
		java.util.Arrays.sort(sorted);
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("op", name);
		row.put("iters", sorted.length);
		row.put("p50us", sorted[sorted.length / 2] / 1000.0);
		row.put("p95us", sorted[(int) (sorted.length * 0.95)] / 1000.0);
		row.put("p99us", sorted[Math.min(sorted.length - 1, (int) (sorted.length * 0.99))] / 1000.0);
		row.put("maxus", sorted[sorted.length - 1] / 1000.0);
		row.put("meanus", java.util.Arrays.stream(sorted).average().orElse(0) / 1000.0);
		return row;
	}

	static long[] measure(int iters, Runnable body) {
		long[] out = new long[iters];
		for (int i = 0; i < iters; i++) {
			long start = System.nanoTime();
			body.run();
			out[i] = System.nanoTime() - start;
		}
		return out;
	}

	/** 归档语料：状态帧（非 delta/heartbeat ⇒ 落档）。 */
	static Map<String, Object> stateFrame(int i) {
		Map<String, Object> f = new LinkedHashMap<>();
		f.put("type", "tool_start");
		f.put("seq", (long) i);
		f.put("toolCallId", "c-" + i);
		f.put("name", "start_export");
		f.put("args", "{\"taskId\":" + i + "}");
		return f;
	}

	static void preload(String runId, int count) {
		for (int i = 1; i <= count; i++) {
			store.appendEvent(runId, stateFrame(i));
		}
	}

	static void flush(String prefix) {
		java.util.Set<String> keys = template.keys(prefix + "*");
		if (keys != null && !keys.isEmpty()) {
			template.delete(keys);
		}
	}

	public static void main(String[] args) throws Exception {
		RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration("127.0.0.1", 6379);
		LettuceConnectionFactory factory = new LettuceConnectionFactory(cfg);
		factory.afterPropertiesSet();
		template = new StringRedisTemplate(factory);
		template.afterPropertiesSet();
		AiProperties props = new AiProperties();
		store = new RunStore(template, MAPPER, props);

		String run = "t35-lock-" + System.currentTimeMillis();
		flush("t35-lock-");
		List<Map<String, Object>> rows = new ArrayList<>();

		// ── 0. Redis RTT 基线 ───────────────────────────────────────────────────
		long[] ping = measure(1000, () -> template.execute(
				(org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
					connection.ping();
					return null;
				}));
		rows.add(stats("PING(基线RTT)", ping));

		// ── 1. 空归档态 ────────────────────────────────────────────────────────
		store.appendEvent(run, stateFrame(1));
		rows.add(stats("lastEventSeq(空归档)", measure(1000, () -> store.lastEventSeq(run))));
		rows.add(stats("lastIssuedSeq", measure(1000, () -> store.lastIssuedSeq(run))));
		rows.add(stats("recordIssuedSeq(SET)", measure(1000, () -> store.recordIssuedSeq(run, 42L))));
		rows.add(stats("touchActivity(SET beat)", measure(1000, () -> store.touchActivity(run))));
		AtomicInteger counter = new AtomicInteger(1000);
		rows.add(stats("appendEvent(rightPush+trim+expire, 空起)",
				measure(1000, () -> store.appendEvent(run, stateFrame(counter.incrementAndGet())))));

		// ── 2. 归档满 3000 态 ──────────────────────────────────────────────────
		String full = run + "-full";
		preload(full, 3000);
		Long llen = template.opsForList().size(store.eventsKey(full));
		rows.add(stats("lastEventSeq(归档=3000)", measure(1000, () -> store.lastEventSeq(full))));
		AtomicInteger fullCounter = new AtomicInteger(3000);
		rows.add(stats("appendEvent(归档=3000，trim 生效)",
				measure(1000, () -> store.appendEvent(full, stateFrame(fullCounter.incrementAndGet())))));
		rows.add(stats("events(LRANGE 0..-1, 3000 帧 + 反序列化)",
				measure(50, () -> store.events(full))));

		// ── 3. 端到端 emit（真 Redis，生产形投递池） ────────────────────────────
		AtomicInteger poolNo = new AtomicInteger();
		ThreadPoolExecutor pool = new ThreadPoolExecutor(4, 4, 60L, TimeUnit.SECONDS,
				new LinkedBlockingQueue<>(4 * 64), r -> {
					Thread t = new Thread(r, "probe-lock-" + poolNo.incrementAndGet());
					t.setDaemon(true);
					return t;
				});
		String emitRun = run + "-emit";
		SseChatEmitter emitter = new SseChatEmitter(emitRun, store, MAPPER, pool, 256);
		CountingEmitter sink = new CountingEmitter();
		emitter.attach(sink);

		AtomicInteger seqNo = new AtomicInteger();
		long[] statusEmit = measure(1000, () -> emitter.retry(Map.of("i", seqNo.incrementAndGet())));
		rows.add(stats("emit(状态帧:取号+SET+落档+入队)", statusEmit));
		long[] heartbeatEmit = measure(1000, () -> emitter.heartbeat(Map.of("phase", "suspend")));
		rows.add(stats("emit(心跳帧:beat SET+入队)", heartbeatEmit));
		long[] deltaEmit = measure(1000, () -> emitter.delta("x"));
		rows.add(stats("emit(delta帧:取号+SET+跳档+入队)", deltaEmit));

		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		while (sink.sent.get() < 3000 && System.nanoTime() < deadline) {
			TimeUnit.MILLISECONDS.sleep(20);
		}

		for (Map<String, Object> row : rows) {
			System.out.println("METRIC " + MAPPER.writeValueAsString(row));
		}
		System.out.println("LLEN_FULL=" + llen + " LLEN_AFTER_TRIM=" + template.opsForList().size(store.eventsKey(full)));
		System.out.println("EMIT_DELIVERED=" + sink.sent.get());
		System.out.println("METRICS_JSON=" + MAPPER.writeValueAsString(rows));

		flush("t35-lock-");
		pool.shutdownNow();
		factory.destroy();
		System.out.println("PROBE DONE");
	}

	static class CountingEmitter extends SseEmitter {

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

	static List<Integer> unused() {
		return Collections.emptyList();
	}

}
