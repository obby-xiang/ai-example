package com.example.configmgr.ai.observe;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.context.ContextSummaryClient;
import com.example.configmgr.ai.exec.AiExecutorConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MDC 跨线程传递用例（AI 可观测性专项·日志链路追踪批）。
 *
 * <p>缺口来自两处真实边界，本类各用一条用例直证：
 * <ul>
 * <li>{@link AiMdcTaskDecorator} × {@code ThreadPoolTaskExecutor}（挂起池 / 续跑触发池）；</li>
 * <li>{@link ContextSummaryClient}：摘要请求跑在<b>虚拟线程池</b>里，MDC 同样不继承。</li>
 * </ul>
 */
class AiMdcPropagationTest {

	private static final String RUN_ID = "run-mdc-pool";

	private static final String SESSION_ID = "s-mdc-pool";

	@AfterEach
	void tearDown() {
		AiTrace.clear();
	}

	@Test
	void taskDecoratorCarriesTheAnchorIntoThePool() throws Exception {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		executor.setQueueCapacity(4);
		executor.setThreadNamePrefix("mdc-decorator-");
		executor.setTaskDecorator(new AiMdcTaskDecorator());
		executor.initialize();
		try {
			AiTrace.bind(RUN_ID, SESSION_ID);
			AtomicReference<String> runIdInPool = new AtomicReference<>();
			AtomicReference<String> sessionIdInPool = new AtomicReference<>();
			CountDownLatch done = new CountDownLatch(1);

			executor.execute(() -> {
				runIdInPool.set(MDC.get(AiTrace.RUN_ID));
				sessionIdInPool.set(MDC.get(AiTrace.SESSION_ID));
				done.countDown();
			});

			assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(runIdInPool.get()).as("池里跑的任务带上了提交时刻的 runId").isEqualTo(RUN_ID);
			assertThat(sessionIdInPool.get()).isEqualTo(SESSION_ID);
		}
		finally {
			executor.shutdown();
		}
	}

	@Test
	void productionSuspendPoolIsWiredWithTheDecorator() throws Exception {
		ThreadPoolTaskExecutor executor = new AiExecutorConfig().aiRunExecutor(new AiProperties());
		try {
			AiTrace.bind(RUN_ID, SESSION_ID);
			AtomicReference<String> runIdInPool = new AtomicReference<>();
			CountDownLatch done = new CountDownLatch(1);

			executor.execute(() -> {
				runIdInPool.set(MDC.get(AiTrace.RUN_ID));
				done.countDown();
			});

			assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(runIdInPool.get()).as("挂起池（aiRunExecutor）必须装 AiMdcTaskDecorator").isEqualTo(RUN_ID);
		}
		finally {
			executor.shutdown();
		}
	}

	@Test
	void summaryCallRunsWithTheCallerAnchor() throws Exception {		AiProperties properties = new AiProperties();
		AtomicReference<String> runIdSeenBySummary = new AtomicReference<>();
		AtomicReference<String> sessionIdSeenBySummary = new AtomicReference<>();
		ContextSummaryClient client = new ContextSummaryClient(properties, (system, user, runtime) -> {
			runIdSeenBySummary.set(MDC.get(AiTrace.RUN_ID));
			sessionIdSeenBySummary.set(MDC.get(AiTrace.SESSION_ID));
			return "<conversation_summary>摘要</conversation_summary>";
		});
		try {
			AiTrace.bind(RUN_ID, SESSION_ID);

			assertThat(client.summarize("系统", "请压缩", com.example.configmgr.ai.context.TestProviderRuntime.runtime()))
				.isEqualTo("摘要");

			assertThat(runIdSeenBySummary.get()).as("摘要旁路线程同样带着这一轮的锚点").isEqualTo(RUN_ID);
			assertThat(sessionIdSeenBySummary.get()).isEqualTo(SESSION_ID);
		}
		finally {
			client.close();
		}
	}

}
