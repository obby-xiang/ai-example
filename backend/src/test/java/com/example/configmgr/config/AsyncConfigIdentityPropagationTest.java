package com.example.configmgr.config;

import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.TenantUser;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 作业池（{@code applicationTaskExecutor} / {@code job-async-*}）的身份传播契约。
 *
 * <p>
 * TTL 是 {@code InheritableThreadLocal} 语义：池线程在"提交者带身份"的那一刻创建时，
 * 会把该身份永久继承下来。没有 {@code TaskDecorator} 时，A 创建了池线程之后，B 提交的任务
 * 仍然看到 A 的身份（串身份），无身份的任务也会沿用 A 的身份。
 *
 * <p>
 * 本单点先在 A 的身份期间把核心线程建出来（= "A 创建了池线程"），再锁三件事：
 * <ol>
 * <li>A 的任务看到 A；</li>
 * <li>B 的任务看到 B（不是 A 的残留）；</li>
 * <li>提交时没有身份的任务看到 {@code null}（不得沿用上一位调用者）。</li>
 * </ol>
 */
class AsyncConfigIdentityPropagationTest {

	@Test
	void poolThreadSeesEachSubmittersIdentityAndNoneForAnonymousTasks() throws Exception {
		ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) new AsyncConfig().getAsyncExecutor();
		try {
			// A 的身份期间把核心线程全部建出来 ⇒ 之后所有任务都跑在"A 创建"的池线程上，
			// 这些线程会把 A 的身份继承下来（TTL 的 InheritableThreadLocal 语义）。
			try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(1L, 101L)) {
				assertThat(executor.getThreadPoolExecutor().prestartAllCoreThreads())
					.as("作业池核心线程必须在 A 的身份期间建出来，才覆盖到继承/残留")
					.isGreaterThan(0);
			}

			Observation a = submit(executor, 1L, 101L);
			assertThat(a.threadName()).as("任务必须跑在作业池线程上").startsWith("job-async-");
			assertThat(a.identity()).as("A 自己的任务必须看到 A").isEqualTo(new TenantUser(1L, 101L));

			Observation b = submit(executor, 2L, 202L);
			assertThat(b.identity()).as("B 的任务必须看到 B，不得是 A 的残留身份").isEqualTo(new TenantUser(2L, 202L));

			Observation anonymous = submit(executor, null, null);
			assertThat(anonymous.identity()).as("提交时没有身份 ⇒ 池线程也不得沿用上一位调用者的身份").isNull();
		}
		finally {
			executor.shutdown();
		}
	}

	private static Observation submit(ThreadPoolTaskExecutor executor, Long tenantId, Long userId) throws Exception {
		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<TenantUser> identity = new AtomicReference<>();
		AtomicReference<String> threadName = new AtomicReference<>();
		Runnable task = () -> {
			try {
				threadName.set(Thread.currentThread().getName());
				identity.set(RequestContextUtils.currentOrNull());
			}
			finally {
				done.countDown();
			}
		};
		if (tenantId == null) {
			executor.execute(task);
		}
		else {
			try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(tenantId, userId)) {
				executor.execute(task);
			}
		}
		assertThat(done.await(5, TimeUnit.SECONDS)).as("池任务必须在 5s 内执行完").isTrue();
		return new Observation(threadName.get(), identity.get());
	}

	private record Observation(String threadName, TenantUser identity) {
	}

}
