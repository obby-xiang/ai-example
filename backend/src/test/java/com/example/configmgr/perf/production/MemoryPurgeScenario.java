package com.example.configmgr.perf.production;

import java.util.List;
import java.util.function.Supplier;

import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.memory.RedisChatMemoryRepository;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.perf.redis.Manifest;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisClusterNode;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

final class MemoryPurgeScenario {

	private MemoryPurgeScenario() {
	}

	static final class FindIdsScan implements ProductionScenario {
		@Override
		public String id() {
			return "memory_find_ids_scan";
		}

		@Override
		public Manifest.Dataset generate(ProductionSupport support, Manifest.Dataset dataset) {
			RedisChatMemoryRepository repository = repository(support);
			List<String> ids = List.of(dataset.sessionId + "-a", dataset.sessionId + "-%{x}", dataset.sessionId + "-z");
			dataset.attributes.put("memoryConversationIds", ids);
			support.stage(dataset, "memory_spi_seed_special_ids", () -> isolatedIdentity(support, () -> {
				for (String id : ids) {
					repository.saveAll(id, List.of(new UserMessage("memory-" + id)));
					String key = repository.keyFor(id);
					support.own(dataset, key);
					support.own(dataset, key + ":ver");
					support.own(dataset, key + ":gen");
					support.own(dataset, RedisChatMemoryRepository.stagingIndexKey(id));
				}
			}));
			return dataset;
		}

		@Override
		public void measure(ProductionSupport support, Manifest.Dataset dataset) {
			RedisChatMemoryRepository repository = repository(support);
			@SuppressWarnings("unchecked")
			List<String> expected = ((List<String>) dataset.attributes.get("memoryConversationIds")).stream().sorted().toList();
			int maxKeyspace = support.intEnv("PERF_MEMORY_FIND_IDS_MAX_KEYSPACE", 200_000);
			if (maxKeyspace < 1) {
				throw new IllegalArgumentException("PERF_MEMORY_FIND_IDS_MAX_KEYSPACE must be positive");
			}
			long keyspaceSize = support.stage(dataset, "memory_spi_keyspace_preflight", () -> keyspaceSize(support));
			dataset.attributes.put("memorySpiObservedDbSize", keyspaceSize);
			dataset.attributes.put("memorySpiDbSizeScope",
					"CLUSTER".equals(support.cfg.mode.name()) ? "sum_of_master_dbSize" : "standalone");
			dataset.attributes.put("memorySpiMaxKeyspace", maxKeyspace);
			if (keyspaceSize < 0 || keyspaceSize > maxKeyspace) {
				String limit = keyspaceSize < 0
						? "Redis DBSIZE is unavailable; SPI keyspace SCAN was not executed; use an isolated small keyspace for behavioral coverage"
						: "Redis DBSIZE=" + keyspaceSize + " exceeds PERF_MEMORY_FIND_IDS_MAX_KEYSPACE=" + maxKeyspace
								+ "; SPI keyspace SCAN was not executed; use an isolated small keyspace for behavioral coverage";
				dataset.attributes.put("memorySpiExecution", "SKIPPED_LARGE_OR_UNKNOWN_KEYSPACE");
				dataset.attributes.put("memorySpiScanPages", 0L);
				dataset.attributes.put("coverageLimit", limit);
				support.coverageStatus = "PARTIAL";
				support.coverageReason = id() + ": " + limit;
				support.faultSkipped(dataset, "scan");
				support.check(dataset, "memory_spi_large_keyspace_scan_refused", true, limit);
				dataset.state = "measured";
				return;
			}

			dataset.attributes.put("memorySpiExecution", "EXECUTED_BOUNDED_KEYSPACE");
			long scanBefore = support.commandMetrics.keyspaceScanPages();
			List<String> actual = support.stage(dataset, "memory_spi_find_ids",
					() -> isolatedIdentity(support, repository::findConversationIds));
			dataset.attributes.put("memorySpiScanPages", support.commandMetrics.keyspaceScanPages() - scanBefore);
			support.require(dataset, "memory_spi_real_ids_sorted", actual.equals(expected),
					"real SPI enumeration returns this scenario's unescaped sorted conversation ids, excluding ver/gen/staging");
			support.require(dataset, "memory_spi_scan_measured", support.commandMetrics.keyspaceScanPages() > scanBefore,
					"the real SPI SCAN is included in command evidence");
			if (!support.faultEnabled("scan")) {
				support.faultSkipped(dataset, "scan");
			}
			else {
				support.faults.failNext("scan", "");
				List<String> degraded = support.stage(dataset, "memory_spi_scan_fault",
						() -> isolatedIdentity(support, repository::findConversationIds));
				support.requireFaultsDrained(dataset, "memory_spi_scan_rule_consumed");
				support.faults.clear();
				dataset.attributes.put("memorySpiFaultResultCount", degraded.size());
				dataset.attributes.put("memorySpiFaultConsumed", degraded.size() < expected.size());
				dataset.attributes.put("memorySpiFaultEvidence",
						"production catch returns only ids collected before the injected failure (possibly empty), not the complete expected set");
				support.require(dataset, "memory_spi_fault_result_is_incomplete", degraded.size() < expected.size(),
						"production SPI degradation returns fewer ids than the complete expected set, proving the injected point was reached");
				support.require(dataset, "memory_spi_recovers_after_fault", support.stage(dataset, "memory_spi_recover",
						() -> isolatedIdentity(support, repository::findConversationIds)).equals(expected),
						"SPI enumeration returns the exact ids again after the fault clears");
			}
			dataset.state = "measured";
		}

		private static RedisChatMemoryRepository repository(ProductionSupport support) {
			return new RedisChatMemoryRepository(support.redis, new MessageJsonCodec(support.mapper), support.ttl());
		}

		private static long keyspaceSize(ProductionSupport support) {
			RedisConnectionFactory factory = support.harness.template().getConnectionFactory();
			if (factory == null) {
				return -1L;
			}
			try (RedisConnection connection = factory.getConnection()) {
				if (connection instanceof RedisClusterConnection cluster) {
					long total = 0L;
					boolean foundMaster = false;
					for (RedisClusterNode node : cluster.clusterGetNodes()) {
						if (!node.isMaster()) {
							continue;
						}
						foundMaster = true;
						Long size = cluster.serverCommands().dbSize(node);
						if (size == null || size < 0) {
							return -1L;
						}
						total += size;
					}
					return foundMaster ? total : -1L;
				}
				if (connection.serverCommands() == null) {
					return -1L;
				}
				Long size = connection.serverCommands().dbSize();
				return size == null ? -1L : size;
			}
			catch (RuntimeException ex) {
				return -1L;
			}
		}

		private static <T> T isolatedIdentity(ProductionSupport support, Supplier<T> body) {
			try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(support.cfg.tenantId,
					scenarioUserId(support))) {
				return body.get();
			}
		}

		private static void isolatedIdentity(ProductionSupport support, Runnable body) {
			isolatedIdentity(support, () -> {
				body.run();
				return null;
			});
		}

		private static long scenarioUserId(ProductionSupport support) {
			return support.cfg.userId + 1_000_000_000L + (support.cfg.namespace.hashCode() & 0x3fffffffL);
		}
	}
}
