package com.example.configmgr.ai.run;

import org.junit.jupiter.api.Test;
import com.example.configmgr.config.RedisClusterTopologyGuard;

import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cluster profile 的形状与<b>绑定</b>（B23L1 批① Cluster 硬阻断 / ④；评审 Minor 4）。
 *
 * <p>为什么光验 YAML 形状不够：profile 是"运维开关"，写错了不会在单测里响 —— 默认文件若带 cluster
 * 拓扑，standalone 部署会被改道；cluster 文件若给 {@code 127.0.0.1:7000} 这类<b>默认节点</b>，
 * 生产漏配 env 就会"静默连本机"（起不来但看不出原因）。这里钉三件事：
 * <ol>
 * <li>默认文件有 host/port、<b>没有</b> cluster 拓扑；</li>
 * <li>cluster 文件只在 {@code cluster} profile 下激活，节点清单是<b>无默认值</b>的占位符；</li>
 * <li><b>真的走 Spring 绑定</b>：配了 env ⇒ {@link RedisProperties} 拿到 cluster 拓扑；
 * 漏配 ⇒ 上下文启动失败并点名 {@code AI_REDIS_CLUSTER_NODES}（fail-fast，不再静默连本机）。</li>
 * </ol>
 */
class ClusterProfileConfigTest {

	/** 只装配 RedisProperties：绑定失败会在 refresh 时抛出（正是要断言的 fail-fast 面）。 */
	@Configuration
	@EnableConfigurationProperties(RedisProperties.class)
	static class RedisPropertiesOnly {

	}

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withInitializer(new ConfigDataApplicationContextInitializer())
		.withUserConfiguration(RedisPropertiesOnly.class, RedisClusterTopologyGuard.class)
		.withPropertyValues("spring.profiles.active=cluster");

	@Test
	void defaultConfigStaysStandalone() throws Exception {
		Map<String, Object> redis = redisSection(load("application.yml"));
		assertThat(redis).as("默认走单节点 host/port").containsKeys("host", "port");
		assertThat(redis).as("默认不得配置 cluster 拓扑（否则 standalone 部署会被改道）")
			.doesNotContainKey("cluster");
	}

	@SuppressWarnings("unchecked")
	@Test
	void clusterProfileSuppliesSeedNodesWithoutADefault() throws Exception {
		Map<String, Object> root = load("application-cluster.yml");
		Map<String, Object> spring = (Map<String, Object>) root.get("spring");
		assertThat(spring).isNotNull();

		Map<String, Object> config = (Map<String, Object>) spring.get("config");
		Map<String, Object> activate = (Map<String, Object>) config.get("activate");
		assertThat(activate).as("激活条件必须是 cluster profile（叠加在默认配置上）")
			.containsEntry("on-profile", "cluster");

		Map<String, Object> redis = redisSection(root);
		Map<String, Object> cluster = (Map<String, Object>) redis.get("cluster");
		assertThat(cluster).as("cluster profile 必须给种子节点清单").isNotNull();
		assertThat(String.valueOf(cluster.get("nodes")))
			.as("节点清单必须是<b>无默认值</b>的占位符（漏配即 fail-fast，不许静默连本机）")
			.isEqualTo("${AI_REDIS_CLUSTER_NODES}");
		assertThat(redis).as("超时口径与 standalone 同值（换拓扑不改 2s 可用性前提）")
			.containsKeys("timeout", "connect-timeout");
	}

	/** 配了 env ⇒ 绑定成功，且拓扑就是那三个种子（证明 profile 真的进到 RedisProperties）。 */
	@Test
	void clusterProfileBindsSeedNodesWhenTheEnvironmentVariableIsSet() {
		this.runner
			.withPropertyValues("AI_REDIS_CLUSTER_NODES=10.0.0.11:6379,10.0.0.12:6379,10.0.0.13:6379")
			.run(context -> {
				assertThat(context).hasNotFailed();
				RedisProperties redis = context.getBean(RedisProperties.class);
				assertThat(redis.getCluster()).as("cluster 拓扑已绑定").isNotNull();
				assertThat(redis.getCluster().getNodes()).containsExactly("10.0.0.11:6379", "10.0.0.12:6379",
						"10.0.0.13:6379");
			});
	}

	/**
	 * 漏配 env ⇒ <b>启动失败</b>且报错点名该变量。
	 *
	 * <p>这是"去掉默认 127.0.0.1"的验收判据：若要保留默认值，这条用例就只能是绿的上下文 ——
	 * 而生产会在一个没人监听的地址上安静地连不上。
	 */
	@Test
	void clusterProfileFailsFastWhenTheEnvironmentVariableIsMissing() {
		this.runner.run(context -> {
			assertThat(context).as("漏配 AI_REDIS_CLUSTER_NODES 必须 fail-fast").hasFailed();
			Throwable failure = context.getStartupFailure();
			assertThat(failure).isNotNull();
			Throwable root = failure;
			while (root.getCause() != null) {
				root = root.getCause();
			}
			assertThat(root).isInstanceOf(IllegalStateException.class);
			assertThat(root.getMessage()).as("报错必须点名缺失的变量（否则运维无从下手）")
				.contains(RedisClusterTopologyGuard.NODES_ENV);
		});
	}

	/**
	 * 节点形状也要 fail-fast（评审 Minor 5）：漏端口的 {@code 10.0.0.11} 能过"非空"检查，
	 * 却要到建连时才报一句看不出原因的错。这里钉住它同样在启动期被点名。
	 */
	@Test
	void clusterProfileRejectsANodeWithoutAPort() {
		this.runner
			.withPropertyValues("AI_REDIS_CLUSTER_NODES=10.0.0.11,10.0.0.12:6379")
			.run(context -> {
				assertThat(context).as("漏端口的种子节点必须 fail-fast").hasFailed();
				Throwable root = context.getStartupFailure();
				while (root != null && root.getCause() != null) {
					root = root.getCause();
				}
				assertThat(root).isInstanceOf(IllegalStateException.class);
				assertThat(root.getMessage()).contains("host:port").contains("10.0.0.11")
					.contains(RedisClusterTopologyGuard.NODES_ENV);
			});
	}

	// ── helpers ─────────────────────────────────────────────────────────────

	@SuppressWarnings("unchecked")
	private static Map<String, Object> load(String resource) throws Exception {
		try (InputStream in = ClusterProfileConfigTest.class.getClassLoader().getResourceAsStream(resource)) {
			assertThat(in).as("classpath 上必须有 " + resource).isNotNull();
			return new Yaml().load(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> redisSection(Map<String, Object> root) {
		Map<String, Object> spring = (Map<String, Object>) root.get("spring");
		Map<String, Object> data = (Map<String, Object>) spring.get("data");
		return (Map<String, Object>) data.get("redis");
	}

}
