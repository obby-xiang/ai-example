package com.example.configmgr.ai.run;

import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.TenantUser;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 保活节拍的身份恢复（租户任务3）：心跳节拍跑在共享调度线程上，那里没有请求身份。
 *
 * <p>被钉住的形态：节拍体经 {@link SseChatEmitter#stage(String, long, int)} 出帧（取号/落档/入队），
 * 这条链路必须在<b>本轮身份</b>下执行。调度器换成"只捕获任务"的替身，再在无身份的裸线程上跑一拍。
 */
class TurnKeepaliveIdentityTest {

	private static final String RUN_ID = "run-keepalive-identity";

	@Test
	void keepaliveBeatRestoresRunIdentityOnTheSchedulerThread() throws Exception {
		AtomicReference<Runnable> beat = new AtomicReference<>();
		ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
		when(scheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
			.thenAnswer(invocation -> {
				beat.set(invocation.getArgument(0));
				return mock(ScheduledFuture.class);
			});
		SseChatEmitter out = mock(SseChatEmitter.class);
		AtomicReference<TenantUser> observed = new AtomicReference<>();
		doAnswer(invocation -> {
			observed.compareAndSet(null, RequestContextUtils.currentOrNull());
			return null;
		}).when(out).stage(anyString(), anyLong(), anyInt());

		TurnKeepalive keepalive;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(9001L, 7L)) {
			keepalive = TurnKeepalive.start(RUN_ID, out, scheduler, 1_000L, TurnKeepalive.PHASE_STREAMING);
		}
		assertThat(beat.get()).as("构造时应排上节拍").isNotNull();
		assertThat(RequestContextUtils.currentOrNull()).as("前置：建立线程的作用域已关闭").isNull();

		Thread schedulerThread = new Thread(beat.get(), "test-ai-stream-watchdog");
		schedulerThread.start();
		schedulerThread.join(5_000L);

		assertThat(observed.get()).as("保活帧必须以本轮身份写出").isEqualTo(new TenantUser(9001L, 7L));
		keepalive.close();
	}

}
