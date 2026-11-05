package com.example.configmgr.perf.production;

import java.util.List;

import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.memory.RedisChatMemoryRepository;
import com.example.configmgr.perf.redis.Manifest;
import org.springframework.ai.chat.messages.UserMessage;

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
			support.stage(dataset, "memory_spi_seed_special_ids", () -> support.identity(() -> {
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
			long scanBefore = support.commandMetrics.keyspaceScanPages();
			List<String> actual = support.stage(dataset, "memory_spi_find_ids", () -> support.identity(repository::findConversationIds));
			dataset.attributes.put("memorySpiScanPages", support.commandMetrics.keyspaceScanPages() - scanBefore);
			support.require(dataset, "memory_spi_real_ids_sorted", actual.equals(expected),
					"real SPI enumeration returns unescaped sorted conversation ids, excluding ver/gen/staging");
			support.require(dataset, "memory_spi_scan_measured", support.commandMetrics.keyspaceScanPages() > scanBefore,
					"the real SPI SCAN is included in command evidence");
			if (!support.faultEnabled("scan")) {
				support.faultSkipped(dataset, "scan");
			}
			else {
				support.faults.failNext("scan", "chat:mem:");
				List<String> degraded = support.stage(dataset, "memory_spi_scan_fault", () -> support.identity(repository::findConversationIds));
				support.faults.clear();
				dataset.attributes.put("memorySpiFaultResultCount", degraded.size());
				dataset.attributes.put("memorySpiFaultConsumed", degraded.size() < expected.size());
				dataset.attributes.put("memorySpiFaultEvidence",
						"production catch returns only ids collected before the injected failure (possibly empty), not the complete expected set");
				support.require(dataset, "memory_spi_fault_result_is_incomplete", degraded.size() < expected.size(),
						"production SPI degradation returns fewer ids than the complete expected set, proving the injected point was reached");
				support.require(dataset, "memory_spi_recovers_after_fault", support.stage(dataset, "memory_spi_recover", () -> support.identity(repository::findConversationIds)).equals(expected),
						"SPI enumeration returns the exact ids again after the fault clears");
			}
			dataset.state = "measured";
		}

		private static RedisChatMemoryRepository repository(ProductionSupport support) {
			return new RedisChatMemoryRepository(support.redis, new MessageJsonCodec(support.mapper), support.ttl());
		}
	}
}
