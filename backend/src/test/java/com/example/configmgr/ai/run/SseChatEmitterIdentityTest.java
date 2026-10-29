package com.example.configmgr.ai.run;

import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.TenantUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * SSE 投递池的身份恢复（租户任务3）：帧写出跑在共享投递池线程上，那里没有请求身份；
 * 跨实例唤醒创建的写出器更是从来没有过请求身份。
 *
 * <p>被钉住的形态：写出器构造时没有身份（模拟唤醒线程），投递线程必须按 {@code ai:run:<runId>}
 * 快照恢复本轮身份 —— 帧面行为不变，但这条链路上的任何身份相关操作都不得落到"无身份"。
 */
class SseChatEmitterIdentityTest {

	private static final String RUN_ID = "run-sse-identity";

	private static final String SESSION_ID = "s-sse-identity";

	@Test
	void deliveryPoolRestoresRunIdentityFromSnapshotWhenConstructedWithoutOne() throws Exception {
		InMemoryRunStore store = new InMemoryRunStore();
		RunSnapshot snapshot;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(9001L, 7L)) {
			snapshot = store.seedRun(RUN_ID, SESSION_ID);
			snapshot.setTenantId(9001L);
			snapshot.setUserId(7L);
			store.save(snapshot);
		}
		ExecutorService deliveryPool = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "sse-delivery-identity-test");
			thread.setDaemon(true);
			return thread;
		});
		SseChatEmitter out = new SseChatEmitter(RUN_ID, store, new ObjectMapper(), deliveryPool, 256);
		CountDownLatch sent = new CountDownLatch(1);
		AtomicReference<TenantUser> observed = new AtomicReference<>();
		AtomicReference<String> threadName = new AtomicReference<>();
		SseEmitter emitter = mock(SseEmitter.class);
		doAnswer(invocation -> {
			observed.compareAndSet(null, RequestContextUtils.currentOrNull());
			threadName.set(Thread.currentThread().getName());
			sent.countDown();
			return null;
		}).when(emitter).send(any(Object.class));
		out.attach(emitter);
		assertThat(RequestContextUtils.currentOrNull()).as("前置：构造与出帧线程均无身份").isNull();

		try {
			out.delta("第一段");
			assertThat(sent.await(5, TimeUnit.SECONDS)).as("投递池应写出该帧").isTrue();
		}
		finally {
			deliveryPool.shutdownNow();
		}

		assertThat(threadName.get()).startsWith("sse-delivery-identity-test");
		assertThat(observed.get()).as("SSE 投递线程必须按快照恢复本轮身份")
			.isEqualTo(new TenantUser(9001L, 7L));
	}

}
