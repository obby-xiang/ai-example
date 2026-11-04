package com.example.configmgr.perf.redis;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Properties;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SslOptions;
import io.lettuce.core.resource.DefaultClientResources;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 直连 Redis 的连接门面。standalone 与 cluster 只改连接配置，不改任何业务脚本/键形。
 */
public final class RedisHarness implements AutoCloseable {

	private final LettuceConnectionFactory factory;

	private final StringRedisTemplate template;

	private final CountingRedis redis;

	private final DefaultClientResources resources;

	private RedisHarness(LettuceConnectionFactory factory, StringRedisTemplate template,
			DefaultClientResources resources, Duration commandTimeout) {
		this.factory = factory;
		this.template = template;
		this.redis = new CountingRedis(template, commandTimeout);
		this.resources = resources;
	}

	public static RedisHarness open(HarnessConfig cfg) {
		DefaultClientResources resources = DefaultClientResources.create();
		LettuceClientConfiguration.LettuceClientConfigurationBuilder client = LettuceClientConfiguration.builder()
				.clientResources(resources)
				.commandTimeout(cfg.commandTimeout)
				.shutdownTimeout(Duration.ofMillis(200));
		if (cfg.tls) {
			client.useSsl();
		}
		if (cfg.tlsInsecure) {
			client.clientOptions(ClientOptions.builder()
					.sslOptions(SslOptions.builder()
							.jdkSslProvider()
							.trustManager(InsecureTrustManagerFactory.INSTANCE)
							.build())
					.build());
		}
		LettuceConnectionFactory factory;
		if (cfg.mode == HarnessConfig.Mode.CLUSTER) {
			RedisClusterConfiguration cluster = new RedisClusterConfiguration(cfg.clusterNodes);
			cluster.setMaxRedirects(3);
			if (cfg.username != null) {
				cluster.setUsername(cfg.username);
			}
			if (cfg.password != null) {
				cluster.setPassword(RedisPassword.of(cfg.password));
			}
			factory = new LettuceConnectionFactory(cluster, client.build());
		}
		else {
			RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(cfg.host, cfg.port);
			standalone.setDatabase(cfg.db);
			if (cfg.username != null) {
				standalone.setUsername(cfg.username);
			}
			if (cfg.password != null) {
				standalone.setPassword(RedisPassword.of(cfg.password));
			}
			factory = new LettuceConnectionFactory(standalone, client.build());
		}
		factory.afterPropertiesSet();
		factory.start();
		StringRedisTemplate template = new StringRedisTemplate(factory);
		template.afterPropertiesSet();
		return new RedisHarness(factory, template, resources, cfg.commandTimeout);
	}

	public CountingRedis redis() {
		return this.redis;
	}

	public StringRedisTemplate template() {
		return this.template;
	}

	public String ping() {
		return this.template.execute((RedisCallback<String>) connection -> connection.ping());
	}

	public ServerInfo serverInfo() {
		return this.template.execute((RedisCallback<ServerInfo>) connection -> {
			Properties server = connection.serverCommands().info("server");
			String version = server == null ? "unknown" : server.getProperty("redis_version", "unknown");
			String mode = server == null ? "unknown" : server.getProperty("redis_mode", "unknown");
			Properties cluster = connection.serverCommands().info("cluster");
			String clusterEnabled = cluster == null ? null : cluster.getProperty("cluster_enabled");
			boolean clustered = clusterEnabled != null ? "1".equals(clusterEnabled.trim())
					: "cluster".equalsIgnoreCase(mode);
			Long dbsize = connection.serverCommands().dbSize();
			return new ServerInfo(version, mode, dbsize == null ? -1L : dbsize, clustered);
		});
	}

	/** 用 Redis 自己的槽算法校验本地 CRC16（每条键 1 条命令）。 */
	public long clusterKeyslot(String key) {
		return this.redis.clusterKeyslot(key);
	}

	public List<String> clusterNodes() {
		try {
			Object raw = this.template.execute((RedisCallback<Object>) connection -> connection
					.execute("CLUSTER", "NODES".getBytes(StandardCharsets.UTF_8)));
			if (raw == null) {
				return List.of();
			}
			String nodes = raw instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8)
					: String.valueOf(raw);
			return nodes.lines().filter(line -> !line.isBlank()).toList();
		}
		catch (RuntimeException ex) {
			return List.of();
		}
	}

	@Override
	public void close() {
		try {
			this.factory.destroy();
		}
		finally {
			this.resources.shutdown();
		}
	}

	public record ServerInfo(String version, String mode, long dbSize, boolean clusterEnabled) {
	}

}
