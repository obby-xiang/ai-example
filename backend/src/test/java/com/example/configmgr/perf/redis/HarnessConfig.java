package com.example.configmgr.perf.redis;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * harness 配置：全部来自进程环境变量，不自动读取 .env，不打印/落盘密码。
 */
public final class HarnessConfig {

	public enum Mode {
		STANDALONE, CLUSTER
	}

	public final Mode mode;

	public final String host;

	public final int port;

	public final int db;

	public final List<String> clusterNodes;

	public final String username;

	public final String password;

	public final boolean tls;

	public final boolean tlsInsecure;

	public final Duration commandTimeout;

	public final Duration connectTimeout;

	public final String namespace;

	public final Path outputDir;

	public final String profile;

	public final List<String> scenarios;

	public final int repeat;

	public final int warmup;

	public final int batchSize;

	public final int streamPageSize;

	public final int streamMaxEntries;

	public final Duration scenarioBudget;

	public final Duration totalBudget;

	public final long keyTtlSeconds;

	public final long tenantId;

	public final long userId;

	public final boolean isolatedEnv;

	public final boolean confirmRemote;

	public final boolean confirmNonDefaultDb;

	public final boolean allowLargeScale;

	public final boolean allowBackground100k;

	public final int existingBackgroundScale;

	public final long leaseTtlMs;

	public final boolean finalizeAllowed;

	public final GateEvaluator.Thresholds gates;

	public HarnessConfig(Map<String, String> env) {
		this.mode = Mode.valueOf(env.getOrDefault("REDIS_MODE", "standalone").trim().toUpperCase());
		this.host = env.getOrDefault("REDIS_HOST", "127.0.0.1").trim();
		this.port = intEnv(env, "REDIS_PORT", 6379);
		this.db = intEnv(env, "REDIS_DB", 0);
		this.clusterNodes = split(env.getOrDefault("REDIS_CLUSTER_NODES", ""));
		this.username = blankToNull(env.get("REDIS_USERNAME"));
		this.password = blankToNull(env.get("REDIS_PASSWORD"));
		this.tls = boolEnv(env, "REDIS_TLS", false);
		this.tlsInsecure = boolEnv(env, "REDIS_TLS_INSECURE", false);
		this.commandTimeout = Duration.ofMillis(longEnv(env, "REDIS_COMMAND_TIMEOUT_MS", 5_000L));
		this.connectTimeout = Duration.ofMillis(longEnv(env, "REDIS_CONNECT_TIMEOUT_MS", 5_000L));
		this.namespace = blankToNull(env.get("PERF_NAMESPACE"));
		this.outputDir = Path.of(env.getOrDefault("PERF_OUTPUT_DIR", "target/redis-protocol-out").trim());
		this.profile = env.getOrDefault("PERF_PROFILE", "smoke").trim().toLowerCase();
		this.scenarios = split(env.getOrDefault("PERF_SCENARIOS", ""));
		this.repeat = intEnv(env, "PERF_REPEAT", 2);
		this.warmup = intEnv(env, "PERF_WARMUP", 1);
		this.batchSize = intEnv(env, "PERF_BATCH_SIZE", 500);
		this.streamPageSize = intEnv(env, "PERF_STREAM_PAGE_SIZE", 256);
		this.streamMaxEntries = intEnv(env, "PERF_STREAM_MAX_ENTRIES", 10_000);
		this.scenarioBudget = Duration.ofSeconds(longEnv(env, "PERF_SCENARIO_TIMEOUT_SECONDS", 60L));
		this.totalBudget = Duration.ofSeconds(longEnv(env, "PERF_TOTAL_TIMEOUT_SECONDS", 600L));
		this.keyTtlSeconds = longEnv(env, "PERF_KEY_TTL_SECONDS", 43_200L);
		this.tenantId = longEnv(env, "PERF_TENANT_ID", 1L);
		this.userId = longEnv(env, "PERF_USER_ID", 101L);
		this.isolatedEnv = boolEnv(env, "PERF_ISOLATED_ENV", false);
		this.confirmRemote = boolEnv(env, "PERF_CONFIRM_REMOTE", false);
		this.confirmNonDefaultDb = boolEnv(env, "PERF_CONFIRM_NON_DEFAULT_DB", false);
		this.allowLargeScale = boolEnv(env, "PERF_ALLOW_LARGE_SCALE", false);
		this.allowBackground100k = boolEnv(env, "PERF_ALLOW_BACKGROUND_100K", false);
		this.existingBackgroundScale = intEnv(env, "PERF_EXISTING_BG_SCALE", 0);
		this.leaseTtlMs = longEnv(env, "PERF_LEASE_TTL_MS", 3_000L);
		this.finalizeAllowed = boolEnv(env, "PERF_FINALIZE", true);
		this.gates = new GateEvaluator.Thresholds(
				longEnv(env, "PERF_GATE_EMPTY_P95_MS", 250L),
				longEnv(env, "PERF_GATE_RUNS50_P95_MS", 2_000L),
				longEnv(env, "PERF_GATE_ARCHIVE2000_P95_MS", 3_000L),
				longEnv(env, "PERF_GATE_MAX_SCAN_PAGES", 0L),
				longEnv(env, "PERF_GATE_MAX_BG_COMMAND_GROWTH", 0L));
	}

	public HarnessConfig withNamespace(String resolvedNamespace) {
		Map<String, String> copy = new LinkedHashMap<>();
		copy.put("REDIS_MODE", this.mode.name().toLowerCase());
		copy.put("REDIS_HOST", this.host);
		copy.put("REDIS_PORT", String.valueOf(this.port));
		copy.put("REDIS_DB", String.valueOf(this.db));
		copy.put("REDIS_CLUSTER_NODES", String.join(",", this.clusterNodes));
		copy.put("REDIS_USERNAME", this.username == null ? "" : this.username);
		copy.put("REDIS_PASSWORD", this.password == null ? "" : this.password);
		copy.put("REDIS_TLS", String.valueOf(this.tls));
		copy.put("REDIS_TLS_INSECURE", String.valueOf(this.tlsInsecure));
		copy.put("REDIS_COMMAND_TIMEOUT_MS", String.valueOf(this.commandTimeout.toMillis()));
		copy.put("REDIS_CONNECT_TIMEOUT_MS", String.valueOf(this.connectTimeout.toMillis()));
		copy.put("PERF_NAMESPACE", resolvedNamespace);
		copy.put("PERF_OUTPUT_DIR", this.outputDir.toString());
		copy.put("PERF_PROFILE", this.profile);
		copy.put("PERF_SCENARIOS", String.join(",", this.scenarios));
		copy.put("PERF_REPEAT", String.valueOf(this.repeat));
		copy.put("PERF_WARMUP", String.valueOf(this.warmup));
		copy.put("PERF_BATCH_SIZE", String.valueOf(this.batchSize));
		copy.put("PERF_STREAM_PAGE_SIZE", String.valueOf(this.streamPageSize));
		copy.put("PERF_STREAM_MAX_ENTRIES", String.valueOf(this.streamMaxEntries));
		copy.put("PERF_SCENARIO_TIMEOUT_SECONDS", String.valueOf(this.scenarioBudget.toSeconds()));
		copy.put("PERF_TOTAL_TIMEOUT_SECONDS", String.valueOf(this.totalBudget.toSeconds()));
		copy.put("PERF_KEY_TTL_SECONDS", String.valueOf(this.keyTtlSeconds));
		copy.put("PERF_TENANT_ID", String.valueOf(this.tenantId));
		copy.put("PERF_USER_ID", String.valueOf(this.userId));
		copy.put("PERF_ISOLATED_ENV", String.valueOf(this.isolatedEnv));
		copy.put("PERF_CONFIRM_REMOTE", String.valueOf(this.confirmRemote));
		copy.put("PERF_CONFIRM_NON_DEFAULT_DB", String.valueOf(this.confirmNonDefaultDb));
		copy.put("PERF_ALLOW_LARGE_SCALE", String.valueOf(this.allowLargeScale));
		copy.put("PERF_ALLOW_BACKGROUND_100K", String.valueOf(this.allowBackground100k));
		copy.put("PERF_EXISTING_BG_SCALE", String.valueOf(this.existingBackgroundScale));
		copy.put("PERF_LEASE_TTL_MS", String.valueOf(this.leaseTtlMs));
		copy.put("PERF_FINALIZE", String.valueOf(this.finalizeAllowed));
		copy.put("PERF_GATE_EMPTY_P95_MS", String.valueOf(this.gates.emptyP95Ms()));
		copy.put("PERF_GATE_RUNS50_P95_MS", String.valueOf(this.gates.runs50P95Ms()));
		copy.put("PERF_GATE_ARCHIVE2000_P95_MS", String.valueOf(this.gates.archive2000P95Ms()));
		copy.put("PERF_GATE_MAX_SCAN_PAGES", String.valueOf(this.gates.maxScanPages()));
		copy.put("PERF_GATE_MAX_BG_COMMAND_GROWTH", String.valueOf(this.gates.maxBackgroundCommandGrowth()));
		return new HarnessConfig(copy);
	}

	public void validate(boolean writeCommand) {
		List<String> errors = new ArrayList<>();
		if (this.namespace != null) {
			validateNamespace(this.namespace, errors);
		}
		if (this.repeat < 1 || this.warmup < 0) {
			errors.add("PERF_REPEAT 必须 >=1，PERF_WARMUP 必须 >=0");
		}
		if (this.batchSize < 1 || this.batchSize > 1_000) {
			errors.add("PERF_BATCH_SIZE 必须在 1..1000（有界 pipeline）");
		}
		if (this.streamPageSize < 1 || this.streamMaxEntries < 1) {
			errors.add("PERF_STREAM_PAGE_SIZE/PERF_STREAM_MAX_ENTRIES 必须为正");
		}
		if (this.mode == Mode.CLUSTER) {
			if (this.clusterNodes.isEmpty()) {
				errors.add("REDIS_MODE=cluster 必须提供 REDIS_CLUSTER_NODES");
			}
			if (this.db != 0) {
				errors.add("Redis Cluster 只支持 db 0");
			}
		}
		else if (this.db != 0 && !this.confirmNonDefaultDb) {
			errors.add("standalone 非 0 库需要 PERF_CONFIRM_NON_DEFAULT_DB=true");
		}
		if (this.mode == Mode.CLUSTER) {
			for (String node : this.clusterNodes) {
				if (!isLoopback(node)) {
					if (!this.confirmRemote) {
						errors.add("远程 Cluster 节点需要 PERF_CONFIRM_REMOTE=true");
						break;
					}
				}
			}
		}
		else if (!isLoopback(this.host) && !this.confirmRemote) {
			errors.add("远程 Redis 需要 PERF_CONFIRM_REMOTE=true");
		}
		if (writeCommand && !this.isolatedEnv) {
			errors.add("写操作需要 PERF_ISOLATED_ENV=true（专用隔离实例）");
		}
		if (this.tlsInsecure && !this.tls) {
			errors.add("REDIS_TLS_INSECURE=true 需要 REDIS_TLS=true");
		}
		if (!List.of("smoke", "full", "stress").contains(this.profile)) {
			errors.add("PERF_PROFILE 只能是 smoke/full/stress");
		}
		if (!errors.isEmpty()) {
			throw new IllegalArgumentException(String.join("; ", errors));
		}
	}

	/**
	 * 批量处理租约心跳周期：取 TTL 的 1/3，让每次续期在到期前仍留有 2/3 TTL 的网络余量。
	 * 心跳脚本会重写 lease 值里的到期时间，不依赖放大 TTL。
	 */
	public long leaseRenewIntervalMs() {
		return Math.max(1L, this.leaseTtlMs / 3L);
	}

	public boolean needsLargeScaleGate(List<ScenarioCatalog.Scenario> selected) {
		return selected.stream().anyMatch(s -> s.large());
	}

	public Map<String, Object> redactedSummary() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("redis_mode", this.mode.name().toLowerCase());
		out.put("redis_host", this.host);
		out.put("redis_port", this.port);
		out.put("redis_db", this.db);
		out.put("cluster_node_count", this.clusterNodes.size());
		out.put("tls", this.tls);
		out.put("tls_insecure", this.tlsInsecure);
		out.put("username_present", this.username != null);
		out.put("password_present", this.password != null);
		out.put("config_digest", digest());
		out.put("namespace", this.namespace);
		out.put("profile", this.profile);
		out.put("repeat", this.repeat);
		out.put("warmup", this.warmup);
		out.put("batch_size", this.batchSize);
		out.put("scenario_budget_seconds", this.scenarioBudget.toSeconds());
		out.put("total_budget_seconds", this.totalBudget.toSeconds());
		out.put("command_timeout_ms", this.commandTimeout.toMillis());
		out.put("key_ttl_seconds", this.keyTtlSeconds);
		out.put("gates", Map.of(
				"empty_p95_ms", this.gates.emptyP95Ms(),
				"runs50_p95_ms", this.gates.runs50P95Ms(),
				"archive2000_p95_ms", this.gates.archive2000P95Ms(),
				"max_scan_pages", this.gates.maxScanPages(),
				"max_bg_command_growth", this.gates.maxBackgroundCommandGrowth()));
		return out;
	}

	private String digest() {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			String material = this.mode + "|" + this.host + "|" + this.port + "|" + this.db + "|"
					+ String.join(",", this.clusterNodes) + "|" + (this.username == null ? "" : this.username);
			byte[] hash = md.digest(material.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < 8; i++) {
				sb.append(String.format("%02x", hash[i]));
			}
			return sb.toString();
		}
		catch (Exception ex) {
			return "unavailable";
		}
	}

	private static void validateNamespace(String namespace, List<String> errors) {
		if (!namespace.matches("[A-Za-z0-9_.-]{2,41}")) {
			errors.add("PERF_NAMESPACE 只允许字母/数字/_.-，长度 2..41");
		}
	}

	private static boolean isLoopback(String hostPort) {
		String host = hostPort;
		int colon = hostPort.lastIndexOf(':');
		if (colon > 0) {
			host = hostPort.substring(0, colon);
		}
		host = host.replace("[", "").replace("]", "").trim();
		return "localhost".equalsIgnoreCase(host) || host.startsWith("127.") || "::1".equals(host);
	}

	private static List<String> split(String value) {
		List<String> out = new ArrayList<>();
		for (String part : value.split(",")) {
			if (!part.isBlank()) {
				out.add(part.trim());
			}
		}
		return List.copyOf(out);
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	private static int intEnv(Map<String, String> env, String key, int fallback) {
		return (int) longEnv(env, key, fallback);
	}

	private static long longEnv(Map<String, String> env, String key, long fallback) {
		String raw = env.get(key);
		if (raw == null || raw.isBlank()) {
			return fallback;
		}
		return Long.parseLong(raw.trim());
	}

	private static boolean boolEnv(Map<String, String> env, String key, boolean fallback) {
		String raw = env.get(key);
		if (raw == null || raw.isBlank()) {
			return fallback;
		}
		return Boolean.parseBoolean(raw.trim());
	}

}
