package com.example.configmgr.ai.run;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.TenantUser;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.common.sse.SseWriteBudget;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 写出器的归档身份必须来自<b>记录/请求身份</b>；没有身份时<b>失败关闭</b>：
 * 不写任何键族（含旧的 legacy 裸 runId 键）、不占业务号、不交付。
 *
 * <h2>当前契约</h2>
 * <ul>
 * <li>身份解析唯一入口是 {@code SseChatEmitter#deliveryIdentity()}（不再暴露测试口
 *     {@code deliveryIdentityOutcome()/IdentityOutcome}）：构造线程的请求身份只用于拼记录键；</li>
 * <li><b>不可确认</b>（构造线程无身份提示）⇒ 返回 {@code null}，{@code emit}/{@code drain}/
 *     {@code publishWake}/{@code ensureWakeSubscribed} 走"本拍明确失败、绝不按无身份键写"分支；</li>
 * <li><b>不缓存失败</b>：每次出帧都重新解析（首帧 WARN、其后 DEBUG），不会退化成"确认无身份"；</li>
 * <li><b>单一套归档</b>：帧只写"身份 + runId"键族，不存在第二条无身份 legacy 归档。</li>
 * </ul>
 *
 * <p>本类用按身份分桶的事件日志替身（{@link IdentityScopedFrameStore}）把"写进哪套键族"变成
 * 可断言事实；RunStore 用真实实现（{@code FakeLeaseRedis}）只承担"记录身份读口"。
 */
class SseChatEmitterRecordIdentityRequiredTest {

	private static final String RUN_ID = "run-record-identity-required";

	private static final String SESSION_ID = "s-record-identity-required";

	private static final TenantUser RECORD_IDENTITY = new TenantUser(9001L, 7L);

	private final ObjectMapper mapper = new ObjectMapper();

	private final AiProperties properties = new AiProperties();

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final RunStore store = new RunStore(this.fake.redis, this.mapper, this.properties);

	private final IdentityScopedFrameStore frames = new IdentityScopedFrameStore();

	/**
	 * 构造线程没有任何身份上下文（生产装配下不该发生，但写出器必须失败关闭）：
	 * 不落任何键族的档、不占号、不交付，也不把"失败"缓存成"确认无身份"。
	 */
	@Test
	void withoutAnIdentityHintFramesAreNeitherArchivedNorDelivered() {
		assertThat(RequestContextUtils.currentOrNull()).isNull();
		SseChatEmitter out = emitter();

		out.delta("身份不可确认的一帧");

		assertThat(this.frames.archived(null, RUN_ID)).as("不得写无身份 legacy 事件键（那是第二套归档）").isEmpty();
		assertThat(this.frames.archived(RECORD_IDENTITY, RUN_ID)).as("也不得猜着写进记录身份键族").isEmpty();
		assertThat(out.lastIssuedSeq()).as("没有归档就没有交付：不得占用业务号").isZero();
	}

	/** 失败不得被缓存成"永久无身份"：下一拍仍会重新解析并再次走"不可确认"分支。 */
	@Test
	void theUnconfirmedIdentityIsNotCachedAsAConfirmedAbsence() {
		Logger logger = (Logger) LoggerFactory.getLogger(SseChatEmitter.class);
		Level previous = logger.getLevel();
		logger.setLevel(Level.DEBUG);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			SseChatEmitter out = emitter();
			out.delta("第一拍");
			out.delta("第二拍");

			long unconfirmed = appender.list.stream()
				.filter(event -> event.getFormattedMessage().contains("SSE delivery identity unconfirmed"))
				.count();
			assertThat(unconfirmed).as("两拍都重新解析并命中'不可确认'分支（首帧 WARN、其后 DEBUG）")
				.isGreaterThanOrEqualTo(2);
			assertThat(this.frames.archived(null, RUN_ID)).as("两拍都不得写 legacy 事件键").isEmpty();
			assertThat(this.frames.archived(RECORD_IDENTITY, RUN_ID)).isEmpty();
			assertThat(out.lastIssuedSeq()).isZero();
		}
		finally {
			logger.detachAppender(appender);
			logger.setLevel(previous);
		}
	}

	/** 有记录身份时，帧只进"记录身份 + runId"这一套归档；无身份 legacy 键族不再存在。 */
	@Test
	void framesArchiveUnderTheRecordIdentityAndNeverUnderLegacyKeys() {
		seedRun();

		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(
				RECORD_IDENTITY.tenantId(), RECORD_IDENTITY.userId())) {
			SseChatEmitter out = emitter();
			out.delta("记录身份的一帧");
		}

		assertThat(this.frames.archived(RECORD_IDENTITY, RUN_ID))
			.as("帧写进记录身份键族")
			.extracting(frame -> String.valueOf(frame.get("type")))
			.containsExactly("delta");
		assertThat(this.frames.archived(null, RUN_ID)).as("不存在第二套无身份 legacy 归档").isEmpty();
	}

	private SseChatEmitter emitter() {
		return new SseChatEmitter(RUN_ID, this.store, this.frames, this.mapper, Runnable::run, 256,
				AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES, SseWriteBudget.direct(), null, null, null);
	}

	private void seedRun() {
		MessageJsonCodec codec = new MessageJsonCodec(this.mapper);
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(RECORD_IDENTITY.tenantId(),
				RECORD_IDENTITY.userId())) {
			this.store.create(RUN_ID, SESSION_ID, AiContext.empty(), List.of(), codec, 1L, "test-model",
					new RunSnapshot.FrozenParams(null, null, 4096, false), "client-test", "请求文本", 1000L);
		}
	}

	/**
	 * 按"当前线程身份 + runId"分桶的事件日志替身：{@code null} 身份 = 无身份 legacy 桶。
	 *
	 * <p>真实 RunStore 的事件日志本身不再暴露读口（归档由生产 {@code RedisFrameStore} 承担），
	 * 因此这里只复刻断言需要的部分：取号、落档、按身份读。
	 */
	private static final class IdentityScopedFrameStore implements FrameStore {

		private final Map<String, TreeMap<Long, Map<String, Object>>> logs = new ConcurrentHashMap<>();

		private final Map<String, AtomicLong> seqs = new ConcurrentHashMap<>();

		@Override
		public long nextSeq(String runId) {
			return this.seqs.computeIfAbsent(currentKey(runId), ignored -> new AtomicLong()).incrementAndGet();
		}

		@Override
		public void append(String runId, Map<String, Object> frame) {
			if (RunStore.HEARTBEAT_TYPE.equals(String.valueOf(frame.get("type")))) {
				return;
			}
			Object seq = frame.get("seq");
			if (!(seq instanceof Number number) || number.longValue() <= 0L) {
				return;
			}
			this.logs.computeIfAbsent(currentKey(runId), ignored -> new TreeMap<>())
				.put(number.longValue(), new java.util.LinkedHashMap<>(frame));
		}

		@Override
		public List<Map<String, Object>> readFrom(String runId, long afterSeq, int limit) {
			return archived(RequestContextUtils.currentOrNull(), runId).stream()
				.filter(frame -> ((Number) frame.get("seq")).longValue() > afterSeq)
				.limit(limit <= 0 ? Long.MAX_VALUE : limit)
				.toList();
		}

		@Override
		public long lastSeq(String runId) {
			TreeMap<Long, Map<String, Object>> log = this.logs.get(currentKey(runId));
			return log == null || log.isEmpty() ? 0L : log.lastKey();
		}

		List<Map<String, Object>> archived(TenantUser identity, String runId) {
			TreeMap<Long, Map<String, Object>> log = this.logs.get(key(identity, runId));
			return log == null ? List.of() : new ArrayList<>(log.values());
		}

		private static String currentKey(String runId) {
			return key(RequestContextUtils.currentOrNull(), runId);
		}

		private static String key(TenantUser identity, String runId) {
			return (identity == null ? "legacy" : identity.tenantId() + ":" + identity.userId()) + "|" + runId;
		}

	}

}
