package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.common.sse.SseWriteBudget;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /api/ai/health} 的写预算指标必须读<b>配置面的实际值</b>（超时面治理批① 第 3 项）。
 *
 * <p>修复前 {@code deliveryPoolMetrics().writeTimeoutMs} 恒为编译期缺省 15000 ——
 * 运维把 {@code app.ai.sse.write-timeout} 改成 8s 之后，可观测面仍说 15000（配置面与运行面两套话）。
 *
 * <p>静态态卫生同 {@code ThreeArgAssemblyConformanceTest}：进程静态池按用例前后复位。
 */
class SseChatEmitterWriteTimeoutMetricTest {

	private ThreadPoolExecutor poolBeforeClass;

	private Object writeTimeoutBeforeClass;

	@BeforeEach
	void resetStaticPool() throws Exception {
		this.poolBeforeClass = (ThreadPoolExecutor) staticField("sharedDeliveryPool");
		this.writeTimeoutBeforeClass = staticField("sharedWriteTimeoutMs");
		setStaticField("sharedDeliveryPool", null);
	}

	@AfterEach
	void restoreStaticPool() throws Exception {
		ThreadPoolExecutor pool = (ThreadPoolExecutor) staticField("sharedDeliveryPool");
		if (pool != null && pool != this.poolBeforeClass) {
			pool.shutdownNow();
		}
		setStaticField("sharedDeliveryPool", this.poolBeforeClass);
		setStaticField("sharedWriteTimeoutMs", this.writeTimeoutBeforeClass);
	}

	@Test
	void metricReportsTheConfiguredWriteTimeout() {
		SseChatEmitter.configureSharedDeliveryPool(2, 8, 24_000L);

		Map<String, Object> metrics = SseChatEmitter.deliveryPoolMetrics();
		assertThat(metrics.get("writeTimeoutMs")).as("指标 = 装配进来的实际值").isEqualTo(24_000L);
	}

	@Test
	void registryWiringFeedsTheBoundWriteTimeoutIntoTheMetric() {
		AiProperties properties = new AiProperties();
		properties.getSse().setWriteTimeout(Duration.ofSeconds(8));
		new RunRegistry(new InMemoryRunStore(), new ObjectMapper(), properties);

		assertThat(SseChatEmitter.deliveryPoolMetrics().get("writeTimeoutMs"))
			.as("RunRegistry 装配时把 app.ai.sse.write-timeout 的真实值登记进指标")
			.isEqualTo(8_000L);
	}

	@Test
	void nonPositiveValueFallsBackToTheCompiledDefault() {
		SseChatEmitter.configureSharedDeliveryPool(2, 8, 0L);

		assertThat(SseChatEmitter.deliveryPoolMetrics().get("writeTimeoutMs"))
			.as("非正值与启动期 fail-fast 守卫同口径：回落缺省")
			.isEqualTo(SseWriteBudget.DEFAULT_TIMEOUT_MILLIS);
	}

	private static Object staticField(String name) throws Exception {
		Field field = SseChatEmitter.class.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(null);
	}

	private static void setStaticField(String name, Object value) throws Exception {
		Field field = SseChatEmitter.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(null, value);
	}

}
