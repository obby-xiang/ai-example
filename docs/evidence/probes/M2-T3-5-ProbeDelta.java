/*
 * M2-T3-5 压测定值探针（仓库外）—— S6「真实 RunStore delta 跳档分支直测」。
 *
 * 执行者：DS-V4-Flash 施工棒
 * 日期：2026-10-09
 * 运行方式：仓库外另存为 `ProbeDelta.java` 后编译运行（classpath = backend/target/classes + backend/target/test-classes
 *          + `mvn dependency:build-classpath` 产物）。本文件仅为随证留存源码，不参与仓库构建与测试计数。
 * 结论指针：docs/evidence/M2-T3-5-压测证据-DS-V4-Flash.md §S6。
 *
 * 覆盖：① delta 不入档（LLEN 不增）；② ai:seq 每帧刷新 + TTL=session-ttl；
 *      ③ 跳档分支（归档末帧落后于已发放 seq）跨写出器续号无重无跳；
 *      ④ SET 失败分支（真 Redis 不可达端点，非 mock）仅 WARN + 继续出帧；
 *      ⑤ 断流期正文尾部不可复原（S3-4 后果登记，不改设计口径）。
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ProbeDelta {

	static final ObjectMapper MAPPER = new ObjectMapper();

	static StringRedisTemplate template;

	static RunStore store;

	static Map<String, Object> frame(String type, long seq) {
		Map<String, Object> f = new LinkedHashMap<>();
		f.put("type", type);
		f.put("seq", seq);
		return f;
	}

	static class Sink extends SseEmitter {

		final List<String> payloads = new java.util.concurrent.CopyOnWriteArrayList<>();

		@Override
		public void send(Object object) throws IOException {
			this.payloads.add(String.valueOf(object));
		}

		@Override
		public synchronized void complete() {
			// no-op
		}

	}

	static long seqOf(String payload) {
		try {
			return ((Number) MAPPER.readValue(payload, Map.class).get("seq")).longValue();
		}
		catch (Exception ex) {
			return -1;
		}
	}

	static String typeOf(String payload) {
		try {
			return String.valueOf(MAPPER.readValue(payload, Map.class).get("type"));
		}
		catch (Exception ex) {
			return "?";
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

		String base = "t35-delta-" + System.currentTimeMillis();
		java.util.Set<String> stale = template.keys("t35-delta-*");
		if (stale != null && !stale.isEmpty()) {
			template.delete(stale);
		}
		List<String> notes = new ArrayList<>();

		// ── ① delta 不入档 + ② ai:seq 每帧刷新 / TTL ─────────────────────────────
		String run = base + "-skip";
		template.delete(store.eventsKey(run));
		template.delete(store.seqKey(run));
		for (int i = 1; i <= 5; i++) {
			store.appendEvent(run, frame("delta", i));
		}
		long llenAfterDelta = template.opsForList().size(store.eventsKey(run));
		store.appendEvent(run, frame("tool_start", 6));
		long llenAfterState = template.opsForList().size(store.eventsKey(run));
		store.appendEvent(run, frame("heartbeat", 7));
		long llenAfterHeartbeat = template.opsForList().size(store.eventsKey(run));
		store.recordIssuedSeq(run, 7L);
		Long seqTtl = template.getExpire(store.seqKey(run));
		Long eventsTtl = template.getExpire(store.eventsKey(run));
		Map<String, Object> skip = new LinkedHashMap<>();
		skip.put("llenAfter5Delta", llenAfterDelta);
		skip.put("llenAfterState", llenAfterState);
		skip.put("llenAfterHeartbeat", llenAfterHeartbeat);
		skip.put("seqKeyValue", template.opsForValue().get(store.seqKey(run)));
		skip.put("seqKeyTtlSeconds", seqTtl);
		skip.put("eventsKeyTtlSeconds", eventsTtl);
		skip.put("configuredSessionTtlSeconds", props.getSessionTtl().toSeconds());
		System.out.println("DELTA_SKIP " + MAPPER.writeValueAsString(skip));

		// ── ③ 跳档分支：归档末帧落后于已发放 seq ⇒ 跨写出器续号取 max+1 ────────────
		String run2 = base + "-jump";
		SseChatEmitter first = new SseChatEmitter(run2, store, MAPPER, Runnable::run, 256);
		Sink sink1 = new Sink();
		first.attach(sink1);
		first.retry(Map.of());          // seq 1（落档）
		first.delta("正文一");           // seq 2（跳档，不落档）
		first.delta("正文二");           // seq 3（跳档，不落档）
		TimeUnit.MILLISECONDS.sleep(100);
		long archivedLast = store.lastEventSeq(run2);
		long issued = store.lastIssuedSeq(run2);
		long llenRun2 = template.opsForList().size(store.eventsKey(run2));

		// 模拟进程重启：新建写出器（自己的 seq 计数器从 -1 起）
		SseChatEmitter resumed = new SseChatEmitter(run2, store, MAPPER, Runnable::run, 256);
		Sink sink2 = new Sink();
		resumed.attach(sink2);
		resumed.retry(Map.of());
		resumed.retry(Map.of());
		TimeUnit.MILLISECONDS.sleep(100);
		List<Long> resumedSeqs = new ArrayList<>();
		for (String payload : sink2.payloads) {
			resumedSeqs.add(seqOf(payload));
		}
		Map<String, Object> jump = new LinkedHashMap<>();
		jump.put("firstEmitterSeqs", sink1.payloads.stream().map(ProbeDelta::seqOf).toList());
		jump.put("archivedLastSeq", archivedLast);
		jump.put("issuedSeqAiSeq", issued);
		jump.put("llenAfterSkip", llenRun2);
		jump.put("resumedEmitterSeqs", resumedSeqs);
		jump.put("expectedResumeStart", Math.max(archivedLast, issued) + 1);
		System.out.println("DELTA_JUMP " + MAPPER.writeValueAsString(jump));

		// ── ④ SET 失败分支：真 Redis 不可达端点（端口 6399 无监听），非 mock ───────
		RedisStandaloneConfiguration dead = new RedisStandaloneConfiguration("127.0.0.1", 6399);
		LettuceConnectionFactory deadFactory = new LettuceConnectionFactory(dead);
		deadFactory.afterPropertiesSet();
		StringRedisTemplate deadTemplate = new StringRedisTemplate(deadFactory);
		deadTemplate.afterPropertiesSet();
		AiProperties deadProps = new AiProperties();
		RunStore deadStore = new RunStore(deadTemplate, MAPPER, deadProps);

		ch.qos.logback.classic.Logger runStoreLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
				.getLogger(RunStore.class);
		ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
				new ch.qos.logback.core.read.ListAppender<>();
		appender.start();
		runStoreLogger.addAppender(appender);

		String run3 = base + "-dead";
		SseChatEmitter deadEmitter = new SseChatEmitter(run3, deadStore, MAPPER, Runnable::run, 256);
		Sink sink3 = new Sink();
		deadEmitter.attach(sink3);
		boolean threw = false;
		String thrown = "";
		try {
			deadEmitter.retry(Map.of());
			deadEmitter.retry(Map.of());
			deadEmitter.delta("正文");
			deadEmitter.heartbeat(Map.of());
		}
		catch (Throwable ex) {
			threw = true;
			thrown = ex.toString();
		}
		TimeUnit.MILLISECONDS.sleep(200);
		List<String> warnings = new ArrayList<>();
		for (ch.qos.logback.classic.spi.ILoggingEvent event : appender.list) {
			if (event.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.WARN)) {
				warnings.add(event.getLevel() + " " + event.getFormattedMessage());
			}
		}
		Map<String, Object> setFailure = new LinkedHashMap<>();
		setFailure.put("threwException", threw);
		setFailure.put("exception", thrown);
		setFailure.put("framesDelivered", sink3.payloads.size());
		setFailure.put("deliveredSeqs", sink3.payloads.stream().map(ProbeDelta::seqOf).toList());
		setFailure.put("warnCount", warnings.size());
		setFailure.put("warnSamples", warnings.stream().limit(4).toList());
		System.out.println("DELTA_SET_FAILURE " + MAPPER.writeValueAsString(setFailure));
		runStoreLogger.detachAppender(appender);

		// ── ⑤ 断流期正文尾部不可复原（S3-4 后果登记） ─────────────────────────────
		String run4 = base + "-tail";
		SseChatEmitter tail = new SseChatEmitter(run4, store, MAPPER, Runnable::run, 256);
		Sink sink4 = new Sink();
		tail.attach(sink4);
		tail.retry(Map.of());        // 状态帧 → 落档
		for (int i = 0; i < 50; i++) {
			tail.delta("正文片段" + i);   // 50 帧正文 → 全部跳档
		}
		TimeUnit.MILLISECONDS.sleep(100);
		Sink replaySink = new Sink();
		int stagedTail = tail.replayTo(replaySink, null);
		TimeUnit.MILLISECONDS.sleep(100);
		Map<String, Object> tailLoss = new LinkedHashMap<>();
		tailLoss.put("deltasEmitted", 50);
		tailLoss.put("archivedFrames", template.opsForList().size(store.eventsKey(run4)));
		tailLoss.put("replayedFrameTypes", replaySink.payloads.stream().map(ProbeDelta::typeOf).toList());
		tailLoss.put("replayedStaged", stagedTail);
		System.out.println("DELTA_TAIL_LOSS " + MAPPER.writeValueAsString(tailLoss));
		notes.add("断流期正文尾部不可复原：delta 不落档 ⇒ 重挂回放只恢复状态帧，未写出的正文尾部永久丢失（S3-4 已登记后果）。");

		java.util.Set<String> keys = template.keys("t35-delta-*");
		if (keys != null && !keys.isEmpty()) {
			template.delete(keys);
		}
		deadFactory.destroy();
		factory.destroy();
		System.out.println("PROBE DONE");
	}

	static AtomicInteger unused() {
		return new AtomicInteger();
	}

}
