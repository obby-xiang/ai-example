package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.TenantUser;
import com.example.configmgr.ai.tool.AiContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
		// 于这一轮没有可恢复的身份 —— 投递线程上的残留身份不得被沿用。
		seedRun();
		ExecutorService deliveryPool = poolThreadCarrying(STALE_POOL_IDENTITY);
		try {
			AtomicReference<TenantUser> observed = new AtomicReference<>();
			CountDownLatch sent = new CountDownLatch(1);
			SseChatEmitter out = new SseChatEmitter(RUN_ID, this.store, this.mapper, deliveryPool, 256);
			out.attach(sseEmitter(observed, sent));
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

	private static TenantUser resolveIdentity(Method deliveryIdentity, SseChatEmitter out) {
		try {
			return (TenantUser) deliveryIdentity.invoke(out);
		}
		catch (ReflectiveOperationException ex) {
			throw new IllegalStateException("调用 deliveryIdentity 失败", ex);
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
