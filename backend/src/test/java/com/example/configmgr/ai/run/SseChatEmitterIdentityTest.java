package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.RunIdentityScope;
import com.example.configmgr.ai.tenant.TenantUser;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.common.sse.SseWriteBudget;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * SSE 写出器的身份来源（租户任务）：投递池线程上没有请求身份，而帧/序号/归档的键由身份拼出。
 *
 * <h2>被钉住的两点（都用真实 {@link RunStore} + 真实键形）</h2>
 * <ol>
 * <li><b>记录身份是唯一权威</b>：写出器在记录身份下创建、投递却发生在一条<b>残留着别人身份</b>的
 *     池化线程上时，投递必须按<b>记录身份</b>执行（旧实现的末行 {@code currentOrNull()} 回退会把
 *     池化线程上一位调用者的身份当成这一轮的身份）；</li>
 * <li><b>无记录身份 ⇒ 不沿用调用者</b>：写出器创建时没有任何身份、也读不到记录（跨实例唤醒路径
 *     没有请求身份），投递线程上的残留身份<b>不得</b>被当成这一轮的身份 —— fail-closed 到"无身份"，
 *     而不是"借"一个来用。</li>
 * </ol>
 */
class SseChatEmitterIdentityTest {

	private static final String RUN_ID = "run-sse-identity";

	private static final String SESSION_ID = "s-sse-identity";

	private static final TenantUser RECORD_IDENTITY = new TenantUser(9001L, 7L);

	/** 池化线程上残留的"上一位调用者"身份（与记录无关）。 */
	private static final TenantUser STALE_POOL_IDENTITY = new TenantUser(3111L, 42L);

	private final ObjectMapper mapper = new ObjectMapper();

	private final AiProperties properties = new AiProperties();

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final RunStore store = new RunStore(this.fake.redis, this.mapper, this.properties);

	@Test
	void deliveryUsesTheRecordIdentityNotTheIdentityLeftOnThePoolThread() throws Exception {
		seedRun();
		ExecutorService deliveryPool = poolThreadCarrying(STALE_POOL_IDENTITY);
		try {
			AtomicReference<TenantUser> observed = new AtomicReference<>();
			CountDownLatch sent = new CountDownLatch(1);
			SseChatEmitter out;
			try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(RECORD_IDENTITY.tenantId(),
					RECORD_IDENTITY.userId())) {
				out = new SseChatEmitter(RUN_ID, this.store, this.mapper, deliveryPool, 256);
			}
			out.attach(sseEmitter(observed, sent));
			out.delta("第一段");

			assertThat(sent.await(5, TimeUnit.SECONDS)).as("投递池应写出该帧").isTrue();
			assertThat(observed.get()).as("投递身份必须是本条记录的身份（不是池化线程的残留身份）")
				.isEqualTo(RECORD_IDENTITY);
		}
		finally {
			deliveryPool.shutdownNow();
		}
	}

	@Test
	void withoutARecordIdentityThePoolIdentityIsNotAdopted() throws Exception {
		// 记录存在但写出器创建时没有任何身份（跨实例唤醒路径）：没有"取键提示"就读不到记录，
		// 于这一轮没有可恢复的身份 —— 投递线程上的残留身份不得被沿用（也不伪造默认租户）。
		seedRun();
		ExecutorService deliveryPool = poolThreadCarrying(STALE_POOL_IDENTITY);
		try {
			AtomicReference<TenantUser> observed = new AtomicReference<>();
			CountDownLatch sent = new CountDownLatch(1);
			SseChatEmitter out = new SseChatEmitter(RUN_ID, this.store, this.mapper, deliveryPool, 256);
			SseEmitter emitter = sseEmitter(observed, sent);
			out.attach(emitter);
			out.delta("第一段");

			assertThat(sent.await(5, TimeUnit.SECONDS)).as("投递池应写出该帧").isTrue();
			assertThat(observed.get()).as("没有记录身份 ⇒ 不得沿用投递线程上的残留身份").isNull();
		}
		finally {
			deliveryPool.shutdownNow();
		}
	}

	/**
	 * 窄窗身份竞态：首次解析还在读记录时，并发调用方<b>不得</b>返回"尚未落定"的 {@code null}。
	 *
	 * <p>旧实现的 {@code identityProbed.compareAndSet} 失败方直接 {@code return this.deliveryIdentity}：
	 * 首次解析者（CAS 成功）此时正阻塞在记录读取里、还没写回结果，失败方于是拿到 {@code null} ——
	 * 首批帧/seq/events 会按 legacy 键落档，与本轮记录身份分叉。这里把 CAS 之后的读取窗口
	 * 用闸门固定住：线程 A 进入 {@code store.get} 并阻塞，线程 B 在此窗口内调用同一方法。
	 * <p>断言：B 在 A 落定之前不得返回；A、B 拿到同一个已完成的解析结果，且只解析了一次。
	 */
	@Test
	void concurrentCallerWaitsForTheFirstResolutionInsteadOfSeeingAnUnsettledNull() throws Exception {
		seedRun();
		CountDownLatch resolutionEntered = new CountDownLatch(1);
		CountDownLatch releaseResolution = new CountDownLatch(1);
		AtomicInteger reads = new AtomicInteger();
		RunStore blockingStore = new RunStore(this.fake.redis, this.mapper, this.properties) {
			@Override
			public RunSnapshot get(String runId, long tenantId, long userId) {
				reads.incrementAndGet();
				resolutionEntered.countDown();
				try {
					if (!releaseResolution.await(5, TimeUnit.SECONDS)) {
						throw new IllegalStateException("测试未放行首次身份解析");
					}
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException("测试被中断", ex);
				}
				return super.get(runId, tenantId, userId);
			}
		};
		SseChatEmitter out;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(RECORD_IDENTITY.tenantId(),
				RECORD_IDENTITY.userId())) {
			out = new SseChatEmitter(RUN_ID, blockingStore, this.mapper, Runnable::run, 256);
		}

		Method deliveryIdentity = SseChatEmitter.class.getDeclaredMethod("deliveryIdentity");
		deliveryIdentity.setAccessible(true);

		AtomicReference<TenantUser> first = new AtomicReference<>();
		AtomicReference<TenantUser> second = new AtomicReference<>();
		ExecutorService workers = Executors.newFixedThreadPool(2);
		try {
			Future<?> firstCall = workers.submit(() -> first.set(resolveIdentity(deliveryIdentity, out)));
			assertThat(resolutionEntered.await(5, TimeUnit.SECONDS)).as("首次解析应进入记录读取").isTrue();

			CountDownLatch secondStarted = new CountDownLatch(1);
			CountDownLatch secondDone = new CountDownLatch(1);
			Future<?> secondCall = workers.submit(() -> {
				secondStarted.countDown();
				try {
					second.set(resolveIdentity(deliveryIdentity, out));
				}
				finally {
					secondDone.countDown();
				}
			});
			assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
			boolean returnedBeforeResolutionCompleted = secondDone.await(500, TimeUnit.MILLISECONDS);
			assertThat(returnedBeforeResolutionCompleted ? second.get() : RECORD_IDENTITY)
				.as("首次解析落定之前，并发调用方不得返回（旧实现返回尚未写回的 null）")
				.isEqualTo(RECORD_IDENTITY);

			releaseResolution.countDown();
			assertThat(secondDone.await(5, TimeUnit.SECONDS)).as("并发调用方应在首次解析落定后返回").isTrue();
			firstCall.get(5, TimeUnit.SECONDS);
			secondCall.get(5, TimeUnit.SECONDS);

			assertThat(first.get()).as("首次解析结果 = 记录身份").isEqualTo(RECORD_IDENTITY);
			assertThat(second.get()).as("并发调用方必须拿到同一次已完成的解析结果").isEqualTo(RECORD_IDENTITY);
			assertThat(reads.get()).as("身份只解析一次").isEqualTo(1);
		}
		finally {
			releaseResolution.countDown();
			workers.shutdownNow();
		}
	}

	/**
	 * 唤醒登记的窄窗身份竞态：容器回调线程（{@code onCompletion/onTimeout/onError}）没有请求身份。
	 * 若首次登记恰好发生在那里，{@link FrameWakeListener#register} 按"当前身份 = null"把频道落到
	 * legacy 形 {@code ai:wake:<runId>}，而发布侧写的是 {@code ai:wake:<tenantId>:<userId>:<runId>}
	 * —— 跨实例唤醒整段静默缺席（重挂时只能靠 {@code ?lastSeq} 兜底）。
	 *
	 * <p>复现窗口：先让订阅降级（登记失败、{@code wakeRegistered} 回退为 false，订阅者仍挂着），
	 * 再在<b>无身份</b>回调里关掉其中一个订阅者触发 {@code ensureWakeSubscribed}。登记必须复用
	 * 写出器按记录解析出的身份，而不是回调线程的当前上下文。
	 */
	@Test
	void wakeRegistrationFromAnIdentityLessCallbackUsesTheRecordIdentity() throws Exception {
		seedRun();
		ChannelRecordingBus bus = new ChannelRecordingBus();
		FrameWakeListener listener = new FrameWakeListener(bus);

		SseChatEmitter out;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(RECORD_IDENTITY.tenantId(),
				RECORD_IDENTITY.userId())) {
			out = new SseChatEmitter(RUN_ID, this.store, RunStoreFrameStore.of(this.store), this.mapper, Runnable::run,
					AiProperties.Sse.DEFAULT_DELIVERY_QUEUE_CAPACITY,
					AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES, SseWriteBudget.direct(), bus, listener);
		}

		// 订阅降级：两次登记都失败 ⇒ wakeRegistered 回退为 false，而两个订阅者仍然挂着
		bus.subscribeSucceeds = false;
		List<Runnable> completions = new ArrayList<>();
		out.attach(capturingEmitter(completions));
		out.attach(capturingEmitter(completions));
		assertThat(bus.subscribedChannels).as("降级期间没有任何订阅").isEmpty();
		assertThat(listener.subscribedRuns()).isZero();

		// 容器回调线程：没有请求身份；关掉一个订阅者，另一个还在 ⇒ 走 ensureWakeSubscribed
		bus.subscribeSucceeds = true;
		try (RunIdentityScope.Scope ignored = RunIdentityScope.denied()) {
			assertThat(RequestContextUtils.currentOrNull()).isNull();
			completions.get(1).run();
		}

		assertThat(bus.subscribedChannels).as("无身份回调里的登记必须复用本轮记录身份（不是 legacy 频道）")
			.containsExactly(channelOf(RECORD_IDENTITY));
	}

	/**
	 * 出帧身份兜底：{@code emit} 下游的 seq / events / activity / 唤醒频道全都由<b>调用线程身份</b>
	 * 拼键。任何漏开作用域的调用方（定时线程、池化线程、未来新增的入口）在没有请求身份时出帧，
	 * 这些写都会落到 legacy 键形 —— 帧"发出去了"，但重挂回放、跨实例唤醒与活动锚点全都读不到。
	 *
	 * <p>{@code emit} 入口按 {@link SseChatEmitter} 自己解析出的记录身份（{@code deliveryIdentity()}）
	 * 建立作用域后：无身份调用方的帧仍写在<b>本轮记录身份</b>的键/频道上，legacy 面保持干净。
	 */
	@Test
	void framesEmittedFromAnIdentityLessCallerUseTheRecordIdentityKeysAndWakeChannel() {
		seedRun();
		ChannelRecordingBus bus = new ChannelRecordingBus();
		SseChatEmitter out;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(RECORD_IDENTITY.tenantId(),
				RECORD_IDENTITY.userId())) {
			out = new SseChatEmitter(RUN_ID, this.store, RunStoreFrameStore.of(this.store), this.mapper, Runnable::run,
					AiProperties.Sse.DEFAULT_DELIVERY_QUEUE_CAPACITY,
					AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES, SseWriteBudget.direct(), bus, null);
		}

		// 漏开作用域的调用方：没有请求身份
		try (RunIdentityScope.Scope ignored = RunIdentityScope.denied()) {
			assertThat(RequestContextUtils.currentOrNull()).isNull();
			out.delta("无身份调用方");
		}

		SoftAssertions softly = new SoftAssertions();
		softly.assertThat(readFrames(RECORD_IDENTITY)).as("帧必须落在本轮记录身份的事件键上")
			.extracting(frame -> String.valueOf(frame.get("type")))
			.containsExactly("delta");
		softly.assertThat(readFrames(null)).as("legacy 事件键不得出现这一帧").isEmpty();
		softly.assertThat(issuedSeq(RECORD_IDENTITY)).as("序号登记必须落在记录身份的 ai:seq 键上").isPositive();
		softly.assertThat(issuedSeq(null)).as("legacy 序号键不得被写").isZero();
		softly.assertThat(activityAt(RECORD_IDENTITY)).as("业务活动锚点必须落在记录身份的键上").isPositive();
		softly.assertThat(bus.publishedChannels).as("唤醒必须发布到本轮记录身份的频道")
			.containsExactly(channelOf(RECORD_IDENTITY));
		softly.assertAll();
	}

	/** 按给定身份读事件日志（{@code null} = 显式无身份 ⇒ legacy 键）。 */
	private List<Map<String, Object>> readFrames(TenantUser identity) {
		try (RunIdentityScope.Scope ignored = RunIdentityScope.open(identity)) {
			return RunStoreFrameStore.of(this.store).readFrom(RUN_ID, 0L, 0);
		}
	}

	// ── Task 2：身份读取失败 ⇒ 不缓存 null、不写 legacy、下一拍重试 ───────────────

	/**
	 * <b>身份读取一次失败后必须恢复</b>（Task 2 第 4 条）。
	 *
	 * <h2>缺陷形态</h2>
	 * 旧实现把"读取抛异常"和"这一轮确实没有身份"折叠成同一个结果：catch 里
	 * {@code resolved = null}，随后 {@code deliveryIdentity = null; identityResolved = true}
	 * —— 一次 Redis 抖动就把整个写出器<b>永久</b>钉在"无身份"上，此后每一帧/每个唤醒都按 legacy 键族
	 * 走（{@code ai:xevents:{run:<id>}} 裸 tag）。重挂回放与跨实例唤醒读的是身份键族，于是整轮帧
	 * 既写不进本轮的键、也读不出来（看起来像"这一轮没有帧"）。
	 *
	 * <h2>本批口径</h2>
	 * 读取异常 = <b>不可知</b>，不是"无身份"：① 不缓存（下一拍重试）；② 这一拍用<b>已校验的 hint</b>
	 * （构造线程的请求身份，就是拼读记录键的那一份）继续写本轮的键族；③ <b>绝不</b>写 legacy 键。
	 *
	 * <p>判据：两帧都落在记录身份的事件键上、legacy 键族为空、身份读取确实重试过（读到第 2 次）。
	 *
	 * <p>kill：把 catch 改回"缓存 null + 置 identityResolved"⇒ 第 1 帧就落进 legacy 键族，
	 * "legacy 事件键为空"与"记录身份下 2 帧"同时红。
	 */
	@Test
	void identityReadFailureIsNotCachedAndDoesNotLeakToLegacyKeys() {
		seedRun();
		AtomicInteger reads = new AtomicInteger();
		RunStore flaky = new RunStore(this.fake.redis, this.mapper, this.properties) {
			@Override
			public RunSnapshot get(String runId, long tenantId, long userId) {
				if (reads.incrementAndGet() == 1) {
					throw new org.springframework.dao.QueryTimeoutException("身份读取失败（模拟 Redis 抖动）");
				}
				return super.get(runId, tenantId, userId);
			}
		};
		ChannelRecordingBus bus = new ChannelRecordingBus();
		SseChatEmitter out;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(RECORD_IDENTITY.tenantId(),
				RECORD_IDENTITY.userId())) {
			out = new SseChatEmitter(RUN_ID, flaky, RunStoreFrameStore.of(flaky), this.mapper, Runnable::run,
					AiProperties.Sse.DEFAULT_DELIVERY_QUEUE_CAPACITY,
					AiProperties.ResultBudget.Ui.DEFAULT_MAX_FRAME_BYTES, SseWriteBudget.direct(), bus, null);
		}
		FrameWire wire = FrameWire.attachTo(out);

		out.delta("第一拍：身份读取失败");
		out.delta("第二拍：Redis 已恢复");

		SoftAssertions softly = new SoftAssertions();
		softly.assertThat(reads.get()).as("身份读取失败不得被缓存：下一拍必须重试").isEqualTo(2);
		softly.assertThat(readFrames(RECORD_IDENTITY))
			.as("两帧都必须落在本轮记录身份的事件键上（失败那一拍用已校验 hint）")
			.extracting(frame -> String.valueOf(frame.get("type")))
			.containsExactly("delta", "delta");
		softly.assertThat(readFrames(null)).as("身份读取失败不得写 legacy 事件键").isEmpty();
		softly.assertThat(issuedSeq(null)).as("legacy 序号键不得被写").isZero();
		softly.assertThat(issuedSeq(RECORD_IDENTITY)).as("序号仍登记在本轮身份上").isPositive();
		softly.assertThat(wire.frames()).as("两帧都要实时投递（不因一次读抖动整段静音）").hasSize(2);
		softly.assertThat(bus.publishedChannels).as("唤醒仍发布到本轮身份的频道")
			.containsOnly(channelOf(RECORD_IDENTITY));
		softly.assertAll();
	}

	private long issuedSeq(TenantUser identity) {
		try (RunIdentityScope.Scope ignored = RunIdentityScope.open(identity)) {
			return this.store.lastIssuedSeq(RUN_ID);
		}
	}

	private long activityAt(TenantUser identity) {
		try (RunIdentityScope.Scope ignored = RunIdentityScope.open(identity)) {
			return this.store.lastActivityAtMs(RUN_ID);
		}
	}

	private static TenantUser resolveIdentity(Method deliveryIdentity, SseChatEmitter out) {
		try {
			return (TenantUser) deliveryIdentity.invoke(out);
		}
		catch (ReflectiveOperationException ex) {
			throw new IllegalStateException("调用 deliveryIdentity 失败", ex);
		}
	}

	private static String channelOf(TenantUser identity) {
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(identity.tenantId(),
				identity.userId())) {
			return RedisFrameWakeBus.channel(RUN_ID);
		}
	}

	private static SseEmitter capturingEmitter(List<Runnable> completions) {
		SseEmitter emitter = mock(SseEmitter.class);
		doAnswer(invocation -> {
			completions.add(invocation.getArgument(0));
			return null;
		}).when(emitter).onCompletion(any(Runnable.class));
		return emitter;
	}

	/** 按频道名记录订阅/发布的总线替身（频道名由当前线程身份算出，与生产实现同一拼法）。 */
	private static final class ChannelRecordingBus implements FrameWakeBus {

		private final List<String> subscribedChannels = new CopyOnWriteArrayList<>();

		private final List<String> publishedChannels = new CopyOnWriteArrayList<>();

		private volatile boolean subscribeSucceeds = true;

		@Override
		public void publish(String runId, long upperBoundSeq) {
			this.publishedChannels.add(RedisFrameWakeBus.channel(runId));
		}

		@Override
		public boolean subscribe(String runId, LongConsumer onWake) {
			if (!this.subscribeSucceeds) {
				return false;
			}
			this.subscribedChannels.add(RedisFrameWakeBus.channel(runId));
			return true;
		}

		@Override
		public void unsubscribe(String runId) {
		}

	}

	private void seedRun() {
		com.example.configmgr.ai.memory.MessageJsonCodec codec = new com.example.configmgr.ai.memory.MessageJsonCodec(
				this.mapper);
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(RECORD_IDENTITY.tenantId(),
				RECORD_IDENTITY.userId())) {
			this.store.create(RUN_ID, SESSION_ID, AiContext.empty(), List.of(), codec);
		}
	}

	/** 投递池线程<b>带着残留身份</b>启动（模拟池化线程上一位调用者没有清理）。 */
	private static ExecutorService poolThreadCarrying(TenantUser residue) {
		return Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(() -> {
				try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(residue.tenantId(),
						residue.userId())) {
					runnable.run();
				}
			}, "sse-delivery-identity-test");
			thread.setDaemon(true);
			return thread;
		});
	}

	private static SseEmitter sseEmitter(AtomicReference<TenantUser> observed, CountDownLatch sent) {
		SseEmitter emitter = mock(SseEmitter.class);
		try {
			doAnswer(invocation -> {
				observed.compareAndSet(null, RequestContextUtils.currentOrNull());
				sent.countDown();
				return null;
			}).when(emitter).send(any(Object.class));
		}
		catch (java.io.IOException ex) {
			throw new IllegalStateException("桩装配失败", ex);
		}
		return emitter;
	}

}
