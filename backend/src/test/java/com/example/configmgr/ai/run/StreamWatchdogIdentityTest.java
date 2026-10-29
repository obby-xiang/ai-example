package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.TenantUser;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 看门狗节拍的身份恢复（租户任务3）：节拍跑在 {@code aiStreamWatchdog} 调度线程上，那里没有请求身份。
 *
 * <p>被钉住的形态：取消检查 {@link CancellationRegistry#cancelState(String)} 是节拍里唯一的 Redis 读，
 * 它必须在<b>本轮身份</b>下执行。用例把调度器换成"只捕获任务"的替身，再在一个无身份的裸线程上跑
 * 那一拍，断言节拍体看到的身份 = 建立看门狗时的身份（而不是 {@code null} 或上一位调用者）。
 */
class StreamWatchdogIdentityTest {

	private static final String RUN_ID = "run-watchdog-identity";

	@Test
	void scheduledTickRestoresRunIdentityOnTheWatchdogThread() throws Exception {
		AtomicReference<Runnable> tick = new AtomicReference<>();
		ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
		when(scheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
			.thenAnswer(invocation -> {
				tick.set(invocation.getArgument(0));
				return mock(ScheduledFuture.class);
			});
		CancellationRegistry cancellations = mock(CancellationRegistry.class);
		AtomicReference<TenantUser> observed = new AtomicReference<>();
		when(cancellations.cancelState(RUN_ID)).thenAnswer(invocation -> {
			observed.compareAndSet(null, RequestContextUtils.currentOrNull());
			return CancellationRegistry.CancelState.NOT_CANCELLED;
		});
		ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

		StreamWatchdog watchdog;
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(9001L, 7L)) {
			watchdog = new StreamWatchdog(RUN_ID, scheduler, new AiProperties().getResilience(), beacon,
					cancellations, System.currentTimeMillis(), violation -> {
					});
		}
		assertThat(tick.get()).as("构造时应排上节拍").isNotNull();
		assertThat(RequestContextUtils.currentOrNull()).as("前置：建立线程的作用域已关闭").isNull();

		Thread schedulerThread = new Thread(tick.get(), "test-ai-stream-watchdog");
		schedulerThread.start();
		schedulerThread.join(5_000L);

		assertThat(observed.get()).as("看门狗节拍必须以本轮身份读取取消事实")
			.isEqualTo(new TenantUser(9001L, 7L));
		watchdog.close();
	}

}
