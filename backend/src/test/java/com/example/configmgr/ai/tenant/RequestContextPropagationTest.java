package com.example.configmgr.ai.tenant;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 身份传播基座（{@link RequestContextTaskDecorator}）的单点：提交线程上的身份在池线程执行时可见。
 *
 * <p>
 * 本批只要求基座 + {@link RequestContextUtils} 同步/嵌套成立；各执行器逐项接入由后续任务分批完成。
 * 这里验证的是"接入后确实能传播"，不代替各执行器的接入验收。
 */
class RequestContextPropagationTest {

	@Test
	void decoratorCarriesTheIdentityIntoThePoolThread() throws Exception {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		executor.setQueueCapacity(1);
		executor.setThreadNamePrefix("tenant-ctx-");
		executor.setTaskDecorator(new RequestContextTaskDecorator());
		executor.initialize();
		try {
			AtomicLong tenantSeenInPool = new AtomicLong();
			AtomicLong userSeenInPool = new AtomicLong();
			CountDownLatch done = new CountDownLatch(1);
			try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(2L, 202L)) {
				executor.execute(() -> {
					tenantSeenInPool.set(RequestContextUtils.getTenantId());
					userSeenInPool.set(RequestContextUtils.getUserId());
					done.countDown();
				});
			}

			assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(tenantSeenInPool.get()).isEqualTo(2L);
			assertThat(userSeenInPool.get()).isEqualTo(202L);

			AtomicReference<Throwable> missing = new AtomicReference<>();
			CountDownLatch second = new CountDownLatch(1);
			executor.execute(() -> {
				try {
					RequestContextUtils.getTenantId();
				}
				catch (Throwable ex) {
					missing.set(ex);
				}
				second.countDown();
			});
			assertThat(second.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(missing.get()).as("提交时没有身份 ⇒ 池线程也不得凭空产生身份")
				.isInstanceOf(IllegalStateException.class);
		}
		finally {
			executor.shutdown();
		}
	}

}
