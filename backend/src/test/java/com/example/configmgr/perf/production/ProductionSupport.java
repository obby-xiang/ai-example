package com.example.configmgr.perf.production;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.perf.redis.CountingRedis;
import com.example.configmgr.perf.redis.HarnessConfig;
import com.example.configmgr.perf.redis.HarnessMetrics;
import com.example.configmgr.perf.redis.Manifest;
import com.example.configmgr.perf.redis.RedisHarness;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;

final class ProductionSupport implements AutoCloseable {

	final HarnessConfig cfg;

	final HarnessMetrics metrics;

	final ProductionCommandMetrics commandMetrics = new ProductionCommandMetrics();

	final RedisHarness harness;

	final StringRedisTemplate redis;

	final CountingRedis direct;

	final FaultInjectingConnectionFactory faults;

	final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

	final AiProperties aiProperties = new AiProperties();

	String coverageStatus = "PARTIAL";

	String coverageReason = "coverage not evaluated";

	ProductionSupport(HarnessConfig cfg, HarnessMetrics metrics) {
		this.cfg = cfg;
		this.metrics = metrics;
		this.harness = RedisHarness.open(cfg, this.commandMetrics);
		this.faults = new FaultInjectingConnectionFactory(this.harness.connectionFactory());
		this.redis = new StringRedisTemplate(this.faults);
		this.redis.afterPropertiesSet();
		this.direct = this.harness.redis();
	}

	<T> T stage(Manifest.Dataset dataset, String name, Supplier<T> body) {
		CountingRedis.Snapshot before = this.commandMetrics.snapshot();
		long start = System.nanoTime();
		try {
			T result = body.get();
			recordStage(dataset, name, before, start, "ok", "");
			return result;
		}
		catch (RuntimeException ex) {
			recordStage(dataset, name, before, start, "failed", ex.getClass().getSimpleName() + ": " + ex.getMessage());
			throw ex;
		}
	}

	void stage(Manifest.Dataset dataset, String name, Runnable body) {
		stage(dataset, name, () -> {
			body.run();
			return null;
		});
	}

	private void recordStage(Manifest.Dataset dataset, String name, CountingRedis.Snapshot before, long start,
			String status, String detail) {
		long duration = System.nanoTime() - start;
		CountingRedis.Snapshot delta = CountingRedis.delta(before, this.commandMetrics.snapshot());
		this.metrics.stageExternal(dataset.scenario, dataset.copy, name, duration, delta, status, detail);
	}

	boolean check(Manifest.Dataset dataset, String name, boolean pass, String detail) {
		this.metrics.protocol(dataset.scenario, name, pass, detail, 0L);
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> assertions = (List<Map<String, Object>>) dataset.attributes.computeIfAbsent(
				"assertions", ignored -> new ArrayList<Map<String, Object>>());
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("name", name);
		row.put("pass", pass);
		row.put("detail", detail);
		assertions.add(row);
		return pass;
	}

	void require(Manifest.Dataset dataset, String name, boolean pass, String detail) {
		if (!check(dataset, name, pass, detail)) {
			throw new IllegalStateException(name + ": " + detail);
		}
	}

	<T> T identity(Supplier<T> body) {
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(this.cfg.tenantId,
				this.cfg.userId)) {
			return body.get();
		}
	}

	void identity(Runnable body) {
		identity(() -> {
			body.run();
			return null;
		});
	}

	Manifest.Dataset dataset(String scenario) {
		Manifest.Dataset dataset = new Manifest.Dataset(scenario, 0,
				"p-" + this.cfg.namespace + "-" + scenario + "-c0");
		dataset.protocol = true;
		dataset.attributes.put("namespace", this.cfg.namespace);
		dataset.attributes.put("tenantId", this.cfg.tenantId);
		dataset.attributes.put("userId", this.cfg.userId);
		dataset.attributes.put("redisMode", this.cfg.mode.name().toLowerCase());
		return dataset;
	}

	void own(Manifest.Dataset dataset, String key) {
		if (key != null && !dataset.keys.contains(key)) {
			dataset.keys.add(key);
		}
	}

	void retain(Manifest.Dataset dataset, String key) {
		if (key != null && !dataset.retainedKeys.contains(key)) {
			dataset.retainedKeys.add(key);
		}
	}

	void ownHashField(Manifest.Dataset dataset, String key, String field) {
		List<String> fields = dataset.ownedHashFields.computeIfAbsent(key, ignored -> new ArrayList<>());
		if (!fields.contains(field)) {
			fields.add(field);
		}
	}

	long jobId(Manifest.Dataset dataset, int offset) {
		return ((this.cfg.namespace + ":" + dataset.scenario).hashCode() & 0x3fffffffL) * 100L + offset;
	}

	int intEnv(String name, int fallback) {
		String raw = System.getenv(name);
		return raw == null || raw.isBlank() ? fallback : Integer.parseInt(raw.trim());
	}

	boolean boolEnv(String name, boolean fallback) {
		String raw = System.getenv(name);
		return raw == null || raw.isBlank() ? fallback : Boolean.parseBoolean(raw.trim());
	}

	boolean faultEnabled(String operation) {
		if (!boolEnv("PERF_FAULT_INJECTION", true)) {
			return false;
		}
		String selected = System.getenv("PERF_FAULT_OPERATIONS");
		if (selected == null || selected.isBlank()) {
			return true;
		}
		for (String candidate : selected.split(",")) {
			if ("all".equalsIgnoreCase(candidate.trim()) || operation.equalsIgnoreCase(candidate.trim())) {
				return true;
			}
		}
		return false;
	}

	void faultSkipped(Manifest.Dataset dataset, String operation) {
		@SuppressWarnings("unchecked")
		List<String> skipped = (List<String>) dataset.attributes.computeIfAbsent("faultsSkipped",
				ignored -> new ArrayList<String>());
		skipped.add(operation);
	}

	void requireFaultsDrained(Manifest.Dataset dataset, String name) {
		List<String> pending = this.faults.pendingRules();
		dataset.attributes.put(name + "Pending", pending);
		require(dataset, name, pending.isEmpty(), "unconsumed fault rules=" + pending);
	}

	Duration ttl() {
		return Duration.ofSeconds(this.cfg.keyTtlSeconds);
	}

	@Override
	public void close() {
		this.faults.clear();
		this.harness.close();
	}

}
