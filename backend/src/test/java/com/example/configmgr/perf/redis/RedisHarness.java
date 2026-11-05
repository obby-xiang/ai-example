package com.example.configmgr.perf.redis;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SslOptions;
import io.lettuce.core.event.command.CommandListener;
import io.lettuce.core.resource.DefaultClientResources;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisServerCommands;
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
		return open(cfg, null);
	}

	public static RedisHarness open(HarnessConfig cfg, CommandListener commandListener) {
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
		if (commandListener != null) {
			factory.getRequiredNativeClient().addListener(commandListener);
		}
		StringRedisTemplate template = new StringRedisTemplate(factory);
		template.afterPropertiesSet();
		return new RedisHarness(factory, template, resources, cfg.commandTimeout);
	}

	public LettuceConnectionFactory connectionFactory() {
		return this.factory;
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
		return this.template.execute((RedisCallback<ServerInfo>) connection -> fromInfo(readInfo(connection),
				dbSize(connection)));
	}

	/**
	 * 读真实服务端 INFO。standalone 与 cluster 走同一条路径：先发原生命令再解析 bulk 字符串，
	 * 因为 cluster 的结构化 {@code info(section)} 会把各节点结果合并成 {@code host:port.key} 前缀键。
	 * 原生命令不可用或返回空时才回退到结构化结果。
	 */
	private static Map<String, String> readInfo(RedisConnection connection) {
		Map<String, String> parsed = parseInfo(rawInfo(connection));
		return parsed.isEmpty() ? propertiesInfo(connection) : parsed;
	}

	private static String rawInfo(RedisConnection connection) {
		try {
			Object raw = connection.execute("INFO");
			if (raw instanceof byte[] bytes) {
				return new String(bytes, StandardCharsets.UTF_8);
			}
			return raw == null ? null : raw.toString();
		}
		catch (RuntimeException ex) {
			return null;
		}
	}

	private static Map<String, String> propertiesInfo(RedisConnection connection) {
		try {
			RedisServerCommands commands = connection.serverCommands();
			return commands == null ? Map.of() : flattenProperties(commands.info());
		}
		catch (RuntimeException ex) {
			return Map.of();
		}
	}

	private static Long dbSize(RedisConnection connection) {
		try {
			RedisServerCommands commands = connection.serverCommands();
			return commands == null ? null : commands.dbSize();
		}
		catch (RuntimeException ex) {
			return null;
		}
	}

	static ServerInfo fromInfo(Map<String, String> info, Long dbSize) {
		String version = textOrUnknown(info.get("redis_version"));
		String mode = textOrUnknown(info.get("redis_mode"));
		String clusterEnabled = info.get("cluster_enabled");
		boolean clustered = clusterEnabled != null ? "1".equals(clusterEnabled.trim())
				: "cluster".equalsIgnoreCase(mode);
		return new ServerInfo(version, mode, dbSize == null ? -1L : dbSize, clustered);
	}

	private static String textOrUnknown(String value) {
		return value == null || value.isBlank() ? "unknown" : value.trim();
	}

	static Map<String, String> parseInfo(String bulk) {
		if (bulk == null || bulk.isBlank()) {
			return Map.of();
		}
		Map<String, String> out = new LinkedHashMap<>();
		for (String line : bulk.split("\\R")) {
			String trimmed = line.trim();
			if (trimmed.isEmpty() || trimmed.startsWith("#")) {
				continue;
			}
			int colon = trimmed.indexOf(':');
			if (colon <= 0) {
				continue;
			}
			out.putIfAbsent(trimmed.substring(0, colon).trim(), trimmed.substring(colon + 1).trim());
		}
		return out;
	}

	static Map<String, String> flattenProperties(Properties properties) {
		if (properties == null || properties.isEmpty()) {
			return Map.of();
		}
		Map<String, String> out = new LinkedHashMap<>();
		for (String name : properties.stringPropertyNames()) {
			int dot = name.lastIndexOf('.');
			String key = dot >= 0 ? name.substring(dot + 1) : name;
			out.putIfAbsent(key, properties.getProperty(name, "").trim());
		}
		return out;
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
