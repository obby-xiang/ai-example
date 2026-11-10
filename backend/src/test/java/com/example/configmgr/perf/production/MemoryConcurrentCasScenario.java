package com.example.configmgr.perf.production;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.example.configmgr.ai.memory.MemoryTurn;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.memory.RedisChatMemoryRepository;
import com.example.configmgr.perf.redis.Manifest;
import com.example.configmgr.perf.redis.RedisSlots;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

final class MemoryConcurrentCasScenario implements ProductionScenario {

	@Override
	public String id() {
		return "memory_concurrent_cas_and_staging";
	}

	@Override
	public Manifest.Dataset generate(ProductionSupport support, Manifest.Dataset dataset) {
		RedisChatMemoryRepository repository = repository(support);
		support.identity(() -> {
			String finalKey = repository.keyFor(dataset.sessionId);
			String indexKey = RedisChatMemoryRepository.stagingIndexKey(dataset.sessionId);
			String stagingPattern = RedisChatMemoryRepository.stagingKeyPrefix(dataset.sessionId) + "slot-probe";
			support.own(dataset, finalKey);
			support.own(dataset, finalKey + ":ver");
			support.own(dataset, finalKey + ":gen");
			support.own(dataset, indexKey);
			dataset.attributes.put("finalKey", finalKey);
			dataset.attributes.put("stagingIndexKey", indexKey);
			dataset.attributes.put("messageBytes", support.intEnv("PERF_MEMORY_MESSAGE_BYTES", 16_384));
			dataset.attributes.put("concurrentRuns", support.intEnv("PERF_MEMORY_CONCURRENT_RUNS", 4));
			support.require(dataset, "memory_final_staging_index_same_slot",
					RedisSlots.sameSlot(List.of(finalKey, indexKey, stagingPattern)),
					"final/staging/index share the session hash tag");
		});
		support.stage(dataset, "memory_seed_old_history", () -> support.identity(() -> {
			repository.saveAll(dataset.sessionId, List.of(new UserMessage("legacy-history"),
					new AssistantMessage("legacy-answer")));
		}));
		return dataset;
	}

	@Override
	public void measure(ProductionSupport support, Manifest.Dataset dataset) throws Exception {
		RedisChatMemoryRepository repository = repository(support);
		String finalKey = String.valueOf(dataset.attributes.get("finalKey"));
		String stagingPrefix = support.identity(() -> RedisChatMemoryRepository.stagingKeyPrefix(dataset.sessionId));
		for (String operation : List.of("rpush", "sadd", "rename")) {
			if (!support.faultEnabled(operation)) {
				support.faultSkipped(dataset, operation);
				continue;
			}
			support.stage(dataset, "memory_failure_" + operation, () -> {
				support.faults.failNext(operation, stagingPrefix);
				boolean failed = expectFailure(() -> support.identity(() -> repository.saveAll(dataset.sessionId,
						List.of(new UserMessage("must-not-replace-old-history")))));
				support.faults.clear();
				support.require(dataset, operation + "_fault_observed", failed,
						"the real staging command path was interrupted");
				support.require(dataset, operation + "_old_history_preserved", oldHistory(support, repository, dataset),
						"old final history remains readable after " + operation);
			});
		}
		if (support.faultEnabled("cas")) {
			support.stage(dataset, "memory_failure_cas", () -> {
				support.faults.failNext("cas", finalKey);
				boolean failed = expectFailure(() -> support.identity(() -> repository.upsertRun(dataset.sessionId,
						new MemoryTurn("cas-fault-run", 1L), List.of(new UserMessage("cas-must-not-land")), 100, false)));
				support.faults.clear();
				support.require(dataset, "cas_fault_observed", failed, "the real MERGE_CAS script was interrupted");
				support.require(dataset, "cas_old_history_preserved", oldHistory(support, repository, dataset),
						"failed CAS cannot discard old history");
			});
		}
		else {
			support.faultSkipped(dataset, "cas");
		}
		if (!support.faultEnabled("staging_discard")) {
			support.faultSkipped(dataset, "staging_discard");
		}
		else {
			support.stage(dataset, "memory_failure_del_and_exact_recovery", () -> {
			support.faults.failNext("rename", stagingPrefix);
			support.faults.failNext("staging_discard", stagingPrefix);
			boolean failed = expectFailure(() -> support.identity(() -> repository.saveAll(dataset.sessionId,
					List.of(new UserMessage("del-failure-must-not-land")))));
			support.faults.clear();
			support.require(dataset, "del_fault_observed", failed, "staging DEL failed after the rejected write");
			support.require(dataset, "del_old_history_preserved", oldHistory(support, repository, dataset),
					"DEL failure does not remove old history");
			List<String> staging = support.identity(() -> repository.stagingKeys(dataset.sessionId));
			support.require(dataset, "del_left_precise_staging_evidence", !staging.isEmpty(),
					"the exact staging key and index member remain for reconciliation");
			for (String key : staging) {
				support.own(dataset, key);
			}
			support.identity(() -> repository.deleteStagingKeys(dataset.sessionId, staging));
			support.require(dataset, "staging_exact_cleanup", support.identity(() -> repository.stagingKeys(dataset.sessionId)).isEmpty(),
					"staging and its exact index converge without SCAN");
			});
		}

		int runs = ((Number) dataset.attributes.get("concurrentRuns")).intValue();
		int messageBytes = ((Number) dataset.attributes.get("messageBytes")).intValue();
		support.stage(dataset, "memory_multi_run_concurrent_cas", () -> {
			ExecutorService pool = Executors.newFixedThreadPool(runs);
			try {
				CyclicBarrier barrier = new CyclicBarrier(runs);
				List<Future<?>> futures = new ArrayList<>();
				for (int i = 0; i < runs; i++) {
					int run = i;
					String runId = dataset.sessionId + "-run-" + run;
					dataset.runIds.add(runId);
					futures.add(pool.submit(() -> {
						barrier.await();
						Thread.sleep((run + 1L) * 20L);
						for (int retry = 0; retry < 3; retry++) {
							String text = runId + "-retry-" + retry + "-" + "x".repeat(messageBytes);
							int currentRetry = retry;
							support.identity(() -> repository.upsertRun(dataset.sessionId,
									new MemoryTurn(runId, run + 1L),
									List.of(new UserMessage(text), new AssistantMessage("answer-" + currentRetry)),
									Math.max(100, runs * 8), false));
						}
						return null;
					}));
				}
				for (Future<?> future : futures) {
					future.get();
				}
			}
			catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
			finally {
				pool.shutdownNow();
			}
		});
		List<Message> merged = support.identity(() -> repository.findByConversationId(dataset.sessionId));
		for (int i = 0; i < runs; i++) {
			String expected = dataset.sessionId + "-run-" + i + "-retry-2-";
			support.require(dataset, "run_block_preserved_" + i,
					merged.stream().anyMatch(message -> message.getText() != null && message.getText().startsWith(expected)),
					"concurrent CAS retained the latest block for " + expected);
		}
		support.require(dataset, "legacy_history_preserved_after_concurrency",
				merged.stream().anyMatch(message -> "legacy-history".equals(message.getText())),
				"other run blocks and legacy history are not overwritten");
		support.require(dataset, "no_staging_after_successful_cas",
				support.identity(() -> repository.stagingKeys(dataset.sessionId)).isEmpty(),
				"successful CAS leaves no staging keys");
		dataset.attributes.put("mergedMessages", merged.size());
		dataset.state = "measured";
	}

	private static boolean oldHistory(ProductionSupport support, RedisChatMemoryRepository repository,
			Manifest.Dataset dataset) {
		return support.identity(() -> {
			List<Message> current = repository.findByConversationId(dataset.sessionId);
			return current.size() == 2 && "legacy-history".equals(current.get(0).getText())
					&& "legacy-answer".equals(current.get(1).getText());
		});
	}

	private static boolean expectFailure(Runnable body) {
		try {
			body.run();
			return false;
		}
		catch (RuntimeException ex) {
			return true;
		}
	}

	private static RedisChatMemoryRepository repository(ProductionSupport support) {
		return new RedisChatMemoryRepository(support.redis, new MessageJsonCodec(support.mapper), support.ttl());
	}

}
