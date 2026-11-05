package com.example.configmgr.perf.production;

import java.lang.reflect.Constructor;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.config.RedisClusterTopologyGuard;
import com.example.configmgr.health.HealthController;
import com.example.configmgr.health.ProbeResult;
import com.example.configmgr.health.ReadinessProbe;
import com.example.configmgr.health.ReadinessProbeRunner;
import com.example.configmgr.health.ReadinessService;
import com.example.configmgr.perf.redis.CountingRedis;
import com.example.configmgr.perf.redis.Manifest;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;

final class TopologyHealthScenario {

	private TopologyHealthScenario() {
	}

	static final class StartupTopologyContract implements ProductionScenario {

		@Override
		public String id() {
			return "startup_topology_contract";
		}

		@Override
		public Manifest.Dataset generate(ProductionSupport support, Manifest.Dataset dataset) {
			dataset.attributes.put("contract", "real YAML property binding only");
			dataset.attributes.put("noevictionChecked", false);
			dataset.attributes.put("noevictionCheckReason", "no runtime CONFIG command is part of this topology contract");
			return dataset;
		}

		@Override
		public void measure(ProductionSupport support, Manifest.Dataset dataset) {
			try {
			CountingRedis.Snapshot before = support.commandMetrics.snapshot();
			support.stage(dataset, "standalone_yaml_binding_contract", () -> {
				MockEnvironment environment = new MockEnvironment();
				TopologyHealthScenarioSupport.addYaml(environment, "application.yml");
				RedisProperties properties = Binder.get(environment).bind("spring.data.redis", RedisProperties.class).get();
				support.require(dataset, "standalone_host_port", "127.0.0.1".equals(properties.getHost())
						&& properties.getPort() == 6379 && properties.getCluster() == null,
						"application.yml binds standalone host/port without a cluster topology");
				support.require(dataset, "standalone_timeouts", Duration.ofSeconds(2L).equals(properties.getTimeout())
						&& Duration.ofSeconds(2L).equals(properties.getConnectTimeout()),
						"standalone command/connect timeouts remain 2s");
				support.require(dataset, "standalone_shutdown_timeout",
						Duration.ofMillis(200L).equals(properties.getLettuce().getShutdownTimeout()),
						"standalone Lettuce shutdown timeout is 200ms");
			});
			support.stage(dataset, "cluster_yaml_binding_contract", () -> {
				MockEnvironment environment = new MockEnvironment().withProperty("AI_REDIS_CLUSTER_NODES",
						"10.0.0.11:7000,10.0.0.12:7000,10.0.0.13:7000");
				TopologyHealthScenarioSupport.addYaml(environment, "application-cluster.yml");
				new RedisClusterTopologyGuard(environment);
				RedisProperties properties = Binder.get(environment).bind("spring.data.redis", RedisProperties.class).get();
				support.require(dataset, "cluster_seed_nodes_bound", properties.getCluster() != null
						&& properties.getCluster().getNodes().equals(
								List.of("10.0.0.11:7000", "10.0.0.12:7000", "10.0.0.13:7000")),
						"cluster nodes resolve from AI_REDIS_CLUSTER_NODES without a localhost default");
				support.require(dataset, "cluster_redirects_and_timeouts", properties.getCluster().getMaxRedirects() == 3
						&& Duration.ofSeconds(2L).equals(properties.getTimeout())
						&& Duration.ofSeconds(2L).equals(properties.getConnectTimeout())
						&& Duration.ofMillis(200L).equals(properties.getLettuce().getShutdownTimeout()),
						"cluster preserves redirects, 2s timeouts and 200ms shutdown timeout");
			});
			support.stage(dataset, "cluster_missing_nodes_fail_startup_guard", () -> {
				MockEnvironment environment = new MockEnvironment();
				TopologyHealthScenarioSupport.addYaml(environment, "application-cluster.yml");
				boolean failed = false;
				String detail = "";
				try {
					new RedisClusterTopologyGuard(environment);
				}
				catch (IllegalStateException ex) {
					failed = String.valueOf(ex.getMessage()).contains(RedisClusterTopologyGuard.NODES_ENV);
					detail = ex.getMessage();
				}
				support.require(dataset, "missing_cluster_nodes_fail_fast", failed,
						"production startup guard must name the missing variable: " + detail);
			});
			support.stage(dataset, "cluster_unresolved_placeholder_fails_startup_guard", () -> {
				MockEnvironment environment = new MockEnvironment().withProperty(
						"spring.data.redis.cluster.nodes", "${AI_REDIS_CLUSTER_NODES}");
				TopologyHealthScenarioSupport.addYaml(environment, "application-cluster.yml");
				boolean failed = false;
				String detail = "";
				try {
					new RedisClusterTopologyGuard(environment);
				}
				catch (IllegalStateException ex) {
					failed = String.valueOf(ex.getMessage()).contains("未解析")
							&& String.valueOf(ex.getMessage()).contains(RedisClusterTopologyGuard.NODES_ENV);
					detail = ex.getMessage();
				}
				support.require(dataset, "unresolved_placeholder_not_a_legal_node", failed,
						"literal ${AI_REDIS_CLUSTER_NODES} must be rejected before any connection attempt: " + detail);
			});
			support.stage(dataset, "cluster_invalid_host_port_fails_startup_guard", () -> {
				MockEnvironment environment = new MockEnvironment().withProperty("AI_REDIS_CLUSTER_NODES", "10.0.0.11");
				TopologyHealthScenarioSupport.addYaml(environment, "application-cluster.yml");
				boolean failed = false;
				String detail = "";
				try {
					new RedisClusterTopologyGuard(environment);
				}
				catch (IllegalStateException ex) {
					failed = String.valueOf(ex.getMessage()).contains("host:port")
							&& String.valueOf(ex.getMessage()).contains(RedisClusterTopologyGuard.NODES_ENV);
					detail = ex.getMessage();
				}
				support.require(dataset, "invalid_host_port_fail_fast", failed,
						"node without a port must fail the production startup guard: " + detail);
			});
			CountingRedis.Snapshot after = support.commandMetrics.snapshot();
			support.require(dataset, "topology_binding_emits_no_redis_commands", before.equals(after),
					"configuration binding did not connect to Redis or issue CONFIG/SCAN/PING");
			support.stage(dataset, "single_master_targeted_fault_coverage_limit", () -> {
				boolean cluster = "CLUSTER".equals(support.cfg.mode.name());
				String limit = cluster
						? "FaultInjectingConnectionFactory has no node-targeted failure injection; slot/master coverage is not executed"
						: "standalone has no master-slot semantics; targeted single-master coverage is not applicable";
				dataset.attributes.put("singleMasterCoverage", "PARTIAL");
				dataset.attributes.put("coverageLimit", limit);
				support.coverageStatus = "PARTIAL";
				support.coverageReason = limit;
				support.faultSkipped(dataset, "single_master_targeted");
			});
			dataset.state = "measured";
			}
			finally {
				support.faults.clear();
			}
		}
	}

	static final class HealthDegradationObservability implements ProductionScenario {

		@Override
		public String id() {
			return "health_degradation_observability";
		}

		@Override
		public Manifest.Dataset generate(ProductionSupport support, Manifest.Dataset dataset) {
			dataset.attributes.put("requiredIsolation", "Redis DOWN must degrade AI without taking readiness DOWN");
			return dataset;
		}

		@Override
		public void measure(ProductionSupport support, Manifest.Dataset dataset) {
			RedisAvailability availability = new RedisAvailability(support.redis);
			ReadinessProbe redisProbe = TopologyHealthScenarioSupport.redisProbe(availability);
			ReadinessProbe dbProbe = TopologyHealthScenarioSupport.requiredUp("db");
			try (ReadinessProbeRunner runner = ReadinessProbeRunner.defaults()) {
				ReadinessService readiness = new ReadinessService(List.of(dbProbe, redisProbe), runner);
				HealthController controller = new HealthController(readiness);
				support.stage(dataset, "health_baseline_real_ping", () -> {
					ResponseEntity<Map<String, Object>> response = controller.ready();
					support.require(dataset, "baseline_ready_http_200", response.getStatusCode().value() == 200,
							"healthy real Redis and isolated DB produce HTTP 200");
					support.require(dataset, "baseline_redis_up", TopologyHealthScenarioSupport.checkStatus(response, "redis", "UP"),
							"real PING produces checks.redis=UP");
					support.require(dataset, "baseline_not_degraded", TopologyHealthScenarioSupport.degraded(response).isEmpty()
							&& !TopologyHealthScenarioSupport.aiDegraded(response),
							"baseline has no Redis or AI degradation");
				});
				if (support.faultEnabled("ping")) {
					long failuresBefore = ((Number) availability.describe().get("failures")).longValue();
					support.stage(dataset, "health_redis_down_observability", () -> {
					support.faults.failNext("ping", "");
					ResponseEntity<Map<String, Object>> response = controller.ready();
					support.require(dataset, "redis_down_still_http_200", response.getStatusCode().value() == 200,
							"Redis is not a hard readiness dependency");
					support.require(dataset, "redis_down_reported", TopologyHealthScenarioSupport.checkStatus(response, "redis", "DOWN"),
							"failed real probe produces checks.redis=DOWN");
					support.require(dataset, "redis_listed_as_degraded", TopologyHealthScenarioSupport.degraded(response).contains("redis"),
							"degraded list identifies Redis");
					support.require(dataset, "ai_capability_degraded", TopologyHealthScenarioSupport.aiDegraded(response),
							"Redis DOWN marks only the AI capability degraded");
					support.require(dataset, "ready_body_remains_up", Boolean.TRUE.equals(response.getBody().get("ready"))
							&& "UP".equals(response.getBody().get("status")),
							"business readiness remains UP while Redis is degraded");
					support.require(dataset, "availability_failure_counted",
							((Number) availability.describe().get("failures")).longValue() == failuresBefore + 1L,
							"health and availability share the same failure counter");
					support.require(dataset, "live_endpoint_unaffected", controller.live().getStatusCode().value() == 200,
							"liveness performs no dependency probe and remains HTTP 200");
				});
				support.stage(dataset, "health_negative_cache_observability", () -> {
					CountingRedis.Snapshot before = support.commandMetrics.snapshot();
					ResponseEntity<Map<String, Object>> response = controller.ready();
					CountingRedis.Snapshot after = support.commandMetrics.snapshot();
					support.require(dataset, "cached_down_still_observable", TopologyHealthScenarioSupport.checkStatus(response, "redis", "DOWN")
							&& TopologyHealthScenarioSupport.aiDegraded(response),
							"negative-cache response still reports the real degraded state");
					support.require(dataset, "cached_health_emits_no_ping", before.equals(after),
							"repeated readiness inside negative TTL emits no Redis command");
				});
				support.stage(dataset, "health_real_recovery", () -> {
					ProductionDomainSupport.sleep(RedisAvailability.NEGATIVE_TTL_MS + 100L);
					ResponseEntity<Map<String, Object>> response = controller.ready();
					support.require(dataset, "recovered_ready_http_200", response.getStatusCode().value() == 200,
							"real PING recovery keeps readiness HTTP 200");
					support.require(dataset, "recovered_redis_up", TopologyHealthScenarioSupport.checkStatus(response, "redis", "UP"),
							"recovery is observed through the production readiness path");
					support.require(dataset, "recovery_clears_degradation", TopologyHealthScenarioSupport.degraded(response).isEmpty()
							&& !TopologyHealthScenarioSupport.aiDegraded(response),
							"real recovery clears Redis and AI degradation");
				});
				}
				else {
					support.faultSkipped(dataset, "ping");
				}
				dataset.state = "measured";
			}
			finally {
				support.faults.clear();
			}
		}
	}
}

final class TopologyHealthScenarioSupport {

	private TopologyHealthScenarioSupport() {
	}

	static void addYaml(MockEnvironment environment, String resource) {
		try {
			for (PropertySource<?> source : new YamlPropertySourceLoader().load("perf-" + resource,
					new ClassPathResource(resource))) {
				environment.getPropertySources().addLast(source);
			}
		}
		catch (java.io.IOException ex) {
			throw new IllegalStateException("unable to load " + resource, ex);
		}
	}

	static ReadinessProbe redisProbe(RedisAvailability availability) {
		try {
			Class<?> type = Class.forName("com.example.configmgr.health.RedisReadinessProbe");
			Constructor<?> constructor = type.getDeclaredConstructor(RedisAvailability.class);
			constructor.setAccessible(true);
			return (ReadinessProbe) constructor.newInstance(availability);
		}
		catch (ReflectiveOperationException ex) {
			throw new IllegalStateException("unable to assemble production Redis readiness probe", ex);
		}
	}

	static ReadinessProbe requiredUp(String name) {
		return new ReadinessProbe() {
			@Override
			public String name() {
				return name;
			}

			@Override
			public ProbeResult check() {
				return ProbeResult.up("isolated-up");
			}
		};
	}

	@SuppressWarnings("unchecked")
	static boolean checkStatus(ResponseEntity<Map<String, Object>> response, String name, String expected) {
		Map<String, Object> checks = (Map<String, Object>) response.getBody().get("checks");
		Map<String, Object> check = (Map<String, Object>) checks.get(name);
		return check != null && expected.equals(check.get("status"));
	}

	@SuppressWarnings("unchecked")
	static List<String> degraded(ResponseEntity<Map<String, Object>> response) {
		return (List<String>) response.getBody().get("degraded");
	}

	@SuppressWarnings("unchecked")
	static boolean aiDegraded(ResponseEntity<Map<String, Object>> response) {
		Map<String, Object> capabilities = (Map<String, Object>) response.getBody().get("capabilities");
		Map<String, Object> ai = (Map<String, Object>) capabilities.get("ai");
		return Boolean.TRUE.equals(ai.get("degraded"));
	}
}
