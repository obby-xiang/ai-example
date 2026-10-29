package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.TenantUser;
import com.example.configmgr.ai.tool.AiContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 取消收尾交棒（含<b>重试</b>）的身份链路（真实 {@link RunStore} + 真实键形）。
 *
 * <h2>被钉住的缺陷</h2>
 * {@link CancelledRunFinalizer} 的执行体跑在 {@code aiResumeTrigger} / 自己的重试调度线程上，那里
 * <b>没有</b>请求身份。旧实现先 {@code store.get(runId)} 再 {@code RunIdentityScope.open(快照身份)}：
 * 快照键由身份拼出 ⇒ 无身份线程恒读到 {@code null} ⇒ 空作用域 ⇒ 取消收尾执行体永远看不到这一轮，
 * 重试<b>一次都不可用</b>（首拍靠 TTL 传播 + 记录作用域通常能成，所以故障只在重试面暴露）。
 *
 * <p>修法：<b>交棒时刻</b>（还有身份的线程）抓记录身份，随任务携带；执行体与重试都用它开作用域。
 */
class CancelledRunFinalizerIdentityTest {

	private static final String RUN_ID = "run-cancel-finalizer-identity";

	private static final String SESSION_ID = "s-cancel-finalizer-identity";

	private static final TenantUser RECORD_IDENTITY = new TenantUser(9001L, 7L);

	private final ObjectMapper mapper = new ObjectMapper();

	private final AiProperties properties = new AiProperties();

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final RunStore store = new RunStore(this.fake.redis, this.mapper, this.properties);

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	@Test
	void handOffAndItsRetryRunCancelFinalizeUnderTheRecordIdentity() throws Exception {
		seedSuspendedRun();
		ResumeService resumeService = mock(ResumeService.class);
		List<TenantUser> observed = new CopyOnWriteArrayList<>();
		// 第一次让收尾"这拍没落定"（触发有界重试），第二次成功 —— 两条路径都必须看到记录身份。
		when(resumeService.cancelConfirmed(eq(RUN_ID))).thenAnswer(invocation -> {
			observed.add(RequestContextUtils.currentOrNull());
			return observed.size() == 1 ? ResumeService.CancelFinalize.PERSIST_DENIED
					: ResumeService.CancelFinalize.FINALIZED;
		});
		@SuppressWarnings("unchecked")
		ObjectProvider<ResumeService> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(resumeService);

		List<Runnable> scheduled = new ArrayList<>();
		CancelledRunFinalizer.Scheduler immediate = (task, delay) -> {
			scheduled.add(task);
			return () -> {
			};
		};
		ExecutorService triggerPool = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "cancel-finalizer-identity-test");
			thread.setDaemon(true);
			return thread;
		});
		CancelledRunFinalizer finalizer = new CancelledRunFinalizer(provider, triggerPool, this.properties, this.store,
				immediate);
		try {
			// 交棒发生在还有身份的线程上（驱动线程 / HTTP 线程）
			boolean handed;
			try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(
					RECORD_IDENTITY.tenantId(), RECORD_IDENTITY.userId())) {
				handed = finalizer.handOff(RUN_ID, CancelledRunFinalizer.CAUSE_PARK_EXIT);
			}
			assertThat(handed).isTrue();
			assertThat(awaitAttempt(observed)).as("交棒执行体必须带本轮身份").isTrue();

			// 重试任务：在<b>无身份</b>线程上执行（调度线程的真实形态）
			assertThat(scheduled).as("首次未落定 ⇒ 排一次有界重试").hasSize(1);
			assertThat(RequestContextUtils.currentOrNull()).isNull();
			scheduled.get(0).run();
		}
		finally {
			triggerPool.shutdownNow();
		}

		assertThat(observed).as("首拍与重试都必须按记录身份执行取消收尾")
			.containsExactly(RECORD_IDENTITY, RECORD_IDENTITY);
		assertThat(RequestContextUtils.currentOrNull()).as("重试结束不得在执行线程残留身份").isNull();
	}

	private boolean awaitAttempt(List<TenantUser> observed) throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
		while (System.nanoTime() < deadline) {
			if (!observed.isEmpty()) {
				return true;
			}
			Thread.sleep(10L);
		}
		return false;
	}

	private void seedSuspendedRun() {
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(RECORD_IDENTITY.tenantId(),
				RECORD_IDENTITY.userId())) {
			this.store.create(RUN_ID, SESSION_ID, AiContext.empty(), List.of(), this.codec);
			RunSnapshot snapshot = this.store.get(RUN_ID);
			snapshot.setStatus(RunSnapshot.SUSPENDED);
			this.store.save(snapshot);
		}
	}

}
