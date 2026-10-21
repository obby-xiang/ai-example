package com.example.configmgr.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.lang.reflect.Field;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * socket 兜底 300s（超时面治理批② 第 6 项；决策看板 {@code :16}）。
 *
 * <h2>锁的是什么</h2>
 * <ol>
 * <li>上游流式 {@link WebClient} 的连接器<b>显式</b>带 300s 读空闲超时 ——
 * 本仓没引 {@code reactor-netty-http}，缺省连接器实际是 JDK {@code HttpClient}（无读超时）；
 * 不显式设，这一层就是纯空白（盘点 §二 "流式上游读超时：未配置"）；</li>
 * <li><b>不遮蔽 watchdog</b>：300s &gt; {@code inter-event-timeout}（90s）⇒ 静默类失败总是
 * 看门狗先判，socket 层只在半开连接上兜底（"watchdog 判据优先"的可判定形态就是这条数值关系）。</li>
 * </ol>
 *
 * <p>为什么用反射读 {@code readTimeout}：Spring 只给了 setter、没有 getter；而这条断言的
 * 价值恰恰是"值真的设进去了"（不是设了一个默认 0/null 的空招）。
 */
class AiProviderRegistryUpstreamTimeoutTest {

	@Test
	void streamingWebClientHasReadTimeout() throws Exception {
		AiProviderRegistry registry = new AiProviderRegistry(null, null, null, null, null,
				new org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties());

		WebClient.Builder builder = registry.upstreamWebClientBuilder();
		assertThat(builder).isNotNull();
		Object connector = connectorOf(builder);
		assertThat(connector).as("连接器必须是 JDK 连接器（本仓无 reactor-netty）")
			.isInstanceOf(org.springframework.http.client.reactive.JdkClientHttpConnector.class);
		assertThat(readTimeoutOf(connector)).isEqualTo(AiProviderRegistry.UPSTREAM_SOCKET_IDLE_TIMEOUT);
		assertThat(AiProviderRegistry.UPSTREAM_SOCKET_IDLE_TIMEOUT).isEqualTo(Duration.ofSeconds(300));
	}

	@Test
	void socketBackstopNeverPreemptsTheWatchdog() {
		AiProperties properties = new AiProperties();
		assertThat(AiProviderRegistry.UPSTREAM_SOCKET_IDLE_TIMEOUT)
			.as("socket 兜底必须长于事件间静默（90s）：静默判定永远先到 ⇒ watchdog 判据优先")
			.isGreaterThan(properties.getResilience().getInterEventTimeout());
		assertThat(AiProviderRegistry.UPSTREAM_SOCKET_IDLE_TIMEOUT)
			.as("socket 兜底不小于段预算：段内持续吐字的长轮不该被读超时打断")
			.isGreaterThanOrEqualTo(properties.getResilience().getTotalBudget());
	}

	private static Object connectorOf(WebClient.Builder builder) throws Exception {
		WebClient client = builder.build();
		// 连接器落在 DefaultWebClient.builder（DefaultWebClientBuilder）的 connector 字段上：
		// 构建后的 client 不再单独持有它
		Field builderField = client.getClass().getDeclaredField("builder");
		builderField.setAccessible(true);
		Object innerBuilder = builderField.get(client);
		Field connectorField = innerBuilder.getClass().getDeclaredField("connector");
		connectorField.setAccessible(true);
		return connectorField.get(innerBuilder);
	}

	private static Duration readTimeoutOf(Object connector) throws Exception {
		Field field = connector.getClass().getDeclaredField("readTimeout");
		field.setAccessible(true);
		return (Duration) field.get(connector);
	}

}
