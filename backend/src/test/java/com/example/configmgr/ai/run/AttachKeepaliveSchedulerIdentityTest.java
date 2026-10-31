package com.example.configmgr.ai.run;

import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.RunIdentityScope;
import com.example.configmgr.ai.tenant.TenantUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * {@code sse-attach-keepalive} 是<b>进程级</b>共享守护线程（{@link SseChatEmitter#sharedAttachKeepaliveScheduler()}），
 * 且它是<b>按需</b>创建：第一个挂上 attach 连接的请求线程调用时才会建出来。
 *
 * <h2>被钉住的缺陷</h2>
 * {@link RequestContextUtils} 的载体是 {@code TransmittableThreadLocal}（继承自
 * {@code InheritableThreadLocal}）：线程<b>创建那一刻</b>会把创建者的租户/用户复制给子线程。
 * 于是这条进程级线程会把"首个触发 attach 的调用者"的身份<b>长期</b>带在身上 —— 之后所有写出器的
 * 保活节拍都跑在别人的身份下（今天节拍体不读身份键，所以还看不出症状；一旦有人在节拍体里加一次
 * 带身份的 Redis 读写就是跨身份访问）。
 *
 * <p>同族的进程级 ticker 已经按这条纪律处理过（{@code JobHeartbeatRegistry#ensureTicker} 与
 * {@code tickSafely} 都用 {@link RunIdentityScope#denied()} 显式清空），本用例把 attach 保活调度器
 * 对齐到同一口径：<b>线程工厂里显式 denied，线程体不承载任何调用者身份</b>。
 *
 * <p>判据是行为式的：在带身份的线程上创建 + 首次提交，任务体读到的身份必须是 {@code null}
 * （而不是"创建者的身份"）。
 */
class AttachKeepaliveSchedulerIdentityTest {

	/** 第一个触发 attach 的调用者身份（不许被这条进程级线程继承下来）。 */
	private static final TenantUser FIRST_CALLER = new TenantUser(9001L, 7L);

	/** 之后再来一个身份完全不同的调用者（残留身份的第二个观测面）。 */
	private static final TenantUser SECOND_CALLER = new TenantUser(42L, 99L);

	private ScheduledExecutorService schedulerBeforeClass;

	/** 静态态卫生：本类要"从零创建"这条进程级线程，先把它摘下来，事后原样还回（同 ThreeArgAssembly 口径）。 */
	@BeforeEach
	void detachSharedScheduler() throws Exception {
		this.schedulerBeforeClass = (ScheduledExecutorService) staticField();
		setStaticField(null);
	}

	@AfterEach
	void restoreSharedScheduler() throws Exception {
		ScheduledExecutorService created = (ScheduledExecutorService) staticField();
		if (created != null && created != this.schedulerBeforeClass) {
			created.shutdownNow();
		}
		setStaticField(this.schedulerBeforeClass);
	}

	@Test
	void theProcessWideKeepaliveThreadDoesNotCarryTheFirstCallersIdentity() throws Exception {
		AtomicReference<TenantUser> observedOnFreshThread = new AtomicReference<>();
		AtomicReference<TenantUser> observedOnReusedThread = new AtomicReference<>();
		CountDownLatch firstDone = new CountDownLatch(1);
		CountDownLatch secondDone = new CountDownLatch(1);

		// 首次创建 + 首次提交都发生在"带身份的请求线程"上 —— 线程就在这一拍被创建出来。
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(FIRST_CALLER.tenantId(),
				FIRST_CALLER.userId())) {
			ScheduledExecutorService scheduler = SseChatEmitter.sharedAttachKeepaliveScheduler();
			scheduler.execute(() -> {
				observedOnFreshThread.set(RequestContextUtils.currentOrNull());
				firstDone.countDown();
			});
		}
		assertThat(await(firstDone, "首个任务应在进程级保活线程上执行")).as("首个任务未按期执行").isTrue();
		assertThat(observedOnFreshThread.get())
			.as("进程级保活线程不得继承首个调用者的租户/用户身份（线程工厂须显式 RunIdentityScope.denied()）")
			.isNull();

		// 复用同一线程：换成另一个身份提交，线程体仍然没有身份（既不是首个调用者，也不是本次调用者）。
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(SECOND_CALLER.tenantId(),
				SECOND_CALLER.userId())) {
			SseChatEmitter.sharedAttachKeepaliveScheduler().execute(() -> {
				observedOnReusedThread.set(RequestContextUtils.currentOrNull());
				secondDone.countDown();
			});
		}
		assertThat(await(secondDone, "第二个任务应在同一条线程上执行")).as("第二个任务未按期执行").isTrue();
		assertThat(observedOnReusedThread.get())
			.as("复用的进程级线程不得带上任何一个调用者的身份")
			.isNull();
	}

	private static boolean await(CountDownLatch latch, String what) throws InterruptedException {
		if (!latch.await(5, TimeUnit.SECONDS)) {
			fail(what);
		}
		return true;
	}

	private static Object staticField() throws Exception {
		Field field = SseChatEmitter.class.getDeclaredField("sharedAttachKeepaliveScheduler");
		field.setAccessible(true);
		return field.get(null);
	}

	private static void setStaticField(Object value) throws Exception {
		Field field = SseChatEmitter.class.getDeclaredField("sharedAttachKeepaliveScheduler");
		field.setAccessible(true);
		field.set(null, value);
	}

}
