package com.example.configmgr.ai.config;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 探活的连接超时必须<b>每次调用读当前值</b>（超时面治理批① 第 10 项）。
 *
 * <p>此前 HttpClient 在首次构造时固化当时的 {@code probe-timeout}，之后改配置无效 ——
 * 连接超时（旧值）与请求超时（新值）来自两个时刻，配置面与运行面分叉。
 */
class AiConnectionProbeTimeoutTest {

	private final AiProperties properties = new AiProperties();

	private final AiConnectionProbe probe = new AiConnectionProbe(this.properties);

	@Test
	void connectTimeoutFollowsTheCurrentConfigurationValue() {
		HttpClient slow = this.probe.client(Duration.ofMillis(900));
		assertThat(slow.connectTimeout()).as("第一次：按传入值建").contains(Duration.ofMillis(900));

		HttpClient fast = this.probe.client(Duration.ofMillis(150));
		assertThat(fast.connectTimeout()).as("改小后必须重建（不是继续用 900ms 的实例）")
			.contains(Duration.ofMillis(150));
		assertThat(fast).isNotSameAs(slow);
	}

	@Test
	void sameValueReusesTheCachedClient() {
		HttpClient first = this.probe.client(Duration.ofMillis(300));
		HttpClient second = this.probe.client(Duration.ofMillis(300));

		assertThat(second).as("同值复用（不每次 /test 都新建 selector 线程）").isSameAs(first);
	}

	@Test
	void fetchModelsUsesTheConfiguredValueForEachCall() {
		// 放行回环（127.0.0.1:1 无监听 ⇒ 立即 ECONNREFUSED，不涉及任何外部服务）
		this.properties.getConfig().setAllowPrivateEndpoints(true);
		this.properties.getConfig().setProbeTimeout(Duration.ofMillis(120));
		AiConnectionProbe.ProbeResult first = this.probe.fetchModels("http://127.0.0.1:1", "/models", "", false);
		assertThat(first.code()).as("连接拒绝 ⇒ 稳定的 UNREACHABLE 档").isEqualTo(AiConnectionProbe.CODE_UNREACHABLE);
		assertThat(this.probe.client(this.properties.getConfig().getProbeTimeout()).connectTimeout())
			.contains(Duration.ofMillis(120));

		this.properties.getConfig().setProbeTimeout(Duration.ofMillis(800));
		this.probe.fetchModels("http://127.0.0.1:1", "/models", "", false);
		assertThat(this.probe.client(this.properties.getConfig().getProbeTimeout()).connectTimeout())
			.as("改大后同一实例的下一次探活必须用新值").contains(Duration.ofMillis(800));
	}

}
