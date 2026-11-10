package com.example.configmgr.perf.production;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.example.configmgr.ai.kb.KbCache;
import com.example.configmgr.ai.kb.RedisKbCacheStore;
import com.example.configmgr.perf.redis.Manifest;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

final class KbGenerationPruneScenario implements ProductionScenario {

	private static final RedisScript<Long> CONDITIONAL_MANIFEST_RESTORE = new DefaultRedisScript<>("""
			local expected = cjson.decode(ARGV[1])
			local original = cjson.decode(ARGV[2])
			local expectedCount = 0
			for field, value in pairs(expected) do
			  expectedCount = expectedCount + 1
			  if redis.call('HGET', KEYS[1], field) ~= value then return 0 end
			end
			local fields = redis.call('HKEYS', KEYS[1])
			if #fields ~= expectedCount then return 0 end
			if next(original) == nil then
			  redis.call('DEL', KEYS[1])
			  return 1
			end
			for _, field in ipairs(fields) do
			  if original[field] == nil then redis.call('HDEL', KEYS[1], field) end
			end
			for field, value in pairs(original) do
			  redis.call('HSET', KEYS[1], field, value)
			end
			return 1
			""", Long.class);

	@Override
	public String id() {
		return "kb_generation_prune_recovery";
	}

	@Override
	public Manifest.Dataset generate(ProductionSupport support, Manifest.Dataset dataset) {
		String online = "doc-" + support.cfg.namespace + "-online";
		String offline = "doc-" + support.cfg.namespace + "-offline";
		dataset.attributes.put("onlineDoc", online);
		dataset.attributes.put("offlineDoc", offline);
		support.own(dataset, KbCache.docKey(online));
		support.own(dataset, KbCache.docKey(offline));
		support.ownHashField(dataset, KbCache.DOC_IDS_KEY, online);
		support.ownHashField(dataset, KbCache.DOC_IDS_KEY, offline);
		RedisKbCacheStore store = new RedisKbCacheStore(support.redis);
		dataset.attributes.put("originalManifest", store.hashGetAll(KbCache.MANIFEST_KEY));
		dataset.attributes.put("originalGeneration", store.get(KbCache.GENERATION_KEY));
		dataset.attributes.put("originalLock", store.get(KbCache.LOCK_KEY));
		dataset.attributes.put("lockToken", "perf-" + support.cfg.namespace);
		support.stage(dataset, "kb_seed_generation_1", () -> {
			KbCache cache = cache(support);
			long generation = cache.nextGeneration();
			noteGeneration(dataset, generation);
			cache.putDocIds(List.of(online, offline), generation);
			cache.putDoc(online, entry("online-v1", generation), generation);
			cache.putDoc(offline, entry("offline-v1", generation), generation);
			dataset.attributes.put("seedGeneration", generation);
		});
		return dataset;
	}

	@Override
	public void measure(ProductionSupport support, Manifest.Dataset dataset) {
		String online = String.valueOf(dataset.attributes.get("onlineDoc"));
		String offline = String.valueOf(dataset.attributes.get("offlineDoc"));
		KbCache cache = cache(support);
		long seed = ((Number) dataset.attributes.get("seedGeneration")).longValue();
		long newGeneration = support.stage(dataset, "kb_concurrent_refresh", () -> {
			long next = cache.nextGeneration();
			noteGeneration(dataset, next);
			ExecutorService pool = Executors.newFixedThreadPool(2);
			try {
				CyclicBarrier barrier = new CyclicBarrier(2);
				Future<?> old = pool.submit(() -> {
					barrier.await();
					cache.putDocId(offline, seed);
					cache.putDoc(offline, entry("old-writer", seed), seed);
					return null;
				});
				Future<?> current = pool.submit(() -> {
					barrier.await();
					cache.putDocId(offline, next);
					cache.putDoc(offline, entry("new-writer", next), next);
					return null;
				});
				old.get();
				current.get();
			}
			catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
			finally {
				pool.shutdownNow();
			}
			return next;
		});
		support.require(dataset, "new_generation_wins_concurrent_refresh",
				cache.doc(offline).orElseThrow().content().equals("new-writer"),
				"old writer must not overwrite the newer generation");

		support.stage(dataset, "kb_stale_prune_after_new_write", () -> {
			support.require(dataset, "old_instance_cannot_delete_new_body", !cache.deleteDoc(offline, seed),
					"stale DEL must be rejected");
			support.require(dataset, "old_instance_cannot_remove_new_index", !cache.removeDocId(offline, seed),
					"stale HDEL must be rejected");
		});
		support.require(dataset, "new_body_survives_stale_prune",
				cache.doc(offline).orElseThrow().content().equals("new-writer") && cache.docIds().contains(offline),
				"new body and index must both survive");

		if (support.faultEnabled("hdel")) {
			support.stage(dataset, "kb_del_hdel_interruption", () -> {
				support.faults.failNext("hdel", KbCache.DOC_IDS_KEY);
				support.require(dataset, "body_deleted_before_index_failure", cache.deleteDoc(offline, newGeneration),
						"current generation body delete succeeds");
				boolean failed = false;
				try {
					cache.removeDocId(offline, newGeneration);
				}
				catch (RuntimeException ex) {
					failed = true;
				}
				support.requireFaultsDrained(dataset, "index_delete_fault_consumed");
				support.faults.clear();
				support.require(dataset, "index_delete_fault_observed", failed, "real HDEL was interrupted once");
			});
			support.require(dataset, "residual_index_retained_for_retry", cache.docIds().contains(offline),
					"crash between DEL and HDEL leaves a retryable index entry");
			support.stage(dataset, "kb_next_round_converges", () -> {
				support.require(dataset, "next_generation_removes_missing_body", cache.deleteDoc(offline, newGeneration),
						"conditional delete is idempotent for an absent body");
				support.require(dataset, "next_round_index_converges", cache.removeDocId(offline, newGeneration),
						"the retained index entry is removed on the next round");
			});
		}
		else {
			support.faultSkipped(dataset, "hdel");
			support.stage(dataset, "kb_prune_without_injected_fault", () -> {
				cache.deleteDoc(offline, newGeneration);
				cache.removeDocId(offline, newGeneration);
			});
		}
		support.require(dataset, "offline_fully_pruned_without_ttl",
				!cache.docIds().contains(offline) && cache.doc(offline).isEmpty(),
				"offline document converges without relying on TTL");
		support.require(dataset, "online_document_untouched",
				cache.docIds().contains(online) && cache.doc(online).isPresent(),
				"unrelated online document remains cached");

		support.stage(dataset, "kb_manifest_lease_and_recovery", () -> {
			String token = String.valueOf(dataset.attributes.get("lockToken"));
			support.require(dataset, "refresh_lock_acquired", cache.tryLock(token, Duration.ofSeconds(30)),
					"real KB refresh lock");
			long generation = cache.nextGeneration();
			noteGeneration(dataset, generation);
			boolean published = cache.publishManifest(new KbCache.ManifestEntry("etag-" + generation,
					"v-" + generation, "{\"docs\":[\"" + online + "\"]}"), generation, token);
			if (published) {
				dataset.attributes.put("publishedManifest", new RedisKbCacheStore(support.redis)
						.hashGetAll(KbCache.MANIFEST_KEY));
			}
			support.require(dataset, "manifest_published_under_lease", published, "manifest and version publish atomically");
			support.require(dataset, "refresh_lock_released", cache.unlock(token), "owner releases refresh lock");
		});
		dataset.attributes.put("finalGeneration", cache.generation());
		dataset.state = "measured";
	}

	static long cleanupSharedState(ProductionSupport support, Manifest.Dataset dataset) {
		RedisKbCacheStore store = new RedisKbCacheStore(support.redis);
		long changed = 0L;
		Map<String, String> published = stringMap(dataset.attributes.get("publishedManifest"));
		if (published != null) {
			Map<String, String> original = stringMap(dataset.attributes.get("originalManifest"));
			Long restored = support.redis.execute(CONDITIONAL_MANIFEST_RESTORE, List.of(KbCache.MANIFEST_KEY),
					json(support, published), json(support, original));
			if (restored != null) {
				changed += restored;
			}
		}
		String originalGeneration = stringValue(dataset.attributes.get("originalGeneration"));
		Object latestGeneration = dataset.attributes.get("latestGeneration");
		if (originalGeneration == null && latestGeneration != null) {
			String expected = String.valueOf(latestGeneration);
			String current = store.get(KbCache.GENERATION_KEY);
			if (expected.equals(current) && store.compareAndDelete(KbCache.GENERATION_KEY, expected)) {
				changed++;
			}
		}
		String token = String.valueOf(dataset.attributes.get("lockToken"));
		if (token.equals(store.get(KbCache.LOCK_KEY)) && store.compareAndDelete(KbCache.LOCK_KEY, token)) {
			changed++;
		}
		dataset.attributes.put("sharedManifestAfterCleanup", store.hashGetAll(KbCache.MANIFEST_KEY));
		dataset.attributes.put("sharedGenerationAfterCleanup", store.get(KbCache.GENERATION_KEY));
		dataset.attributes.put("sharedLockAfterCleanup", store.get(KbCache.LOCK_KEY));
		return changed;
	}

	static List<String> sharedStateResidue(ProductionSupport support, Manifest.Dataset dataset) {
		RedisKbCacheStore store = new RedisKbCacheStore(support.redis);
		List<String> residue = new ArrayList<>();
		Map<String, String> published = stringMap(dataset.attributes.get("publishedManifest"));
		if (published != null && store.hashGetAll(KbCache.MANIFEST_KEY).equals(published)) {
			residue.add(KbCache.MANIFEST_KEY + "#scenario-published-value");
		}
		Object latestGeneration = dataset.attributes.get("latestGeneration");
		if (dataset.attributes.get("originalGeneration") == null && latestGeneration != null
				&& String.valueOf(latestGeneration).equals(store.get(KbCache.GENERATION_KEY))) {
			residue.add(KbCache.GENERATION_KEY + "#scenario-generation");
		}
		String token = String.valueOf(dataset.attributes.get("lockToken"));
		if (token.equals(store.get(KbCache.LOCK_KEY))) {
			residue.add(KbCache.LOCK_KEY + "#scenario-token");
		}
		return residue;
	}

	private static void noteGeneration(Manifest.Dataset dataset, long generation) {
		dataset.attributes.put("latestGeneration", generation);
	}

	private static String json(ProductionSupport support, Map<String, String> value) {
		try {
			return support.mapper.writeValueAsString(value);
		}
		catch (Exception ex) {
			throw new IllegalStateException("unable to encode KB manifest cleanup snapshot", ex);
		}
	}

	private static String stringValue(Object value) {
		return value == null ? null : String.valueOf(value);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, String> stringMap(Object value) {
		return value == null ? null : new java.util.LinkedHashMap<>((Map<String, String>) value);
	}

	private static KbCache cache(ProductionSupport support) {
		return new KbCache(new RedisKbCacheStore(support.redis));
	}

	private static KbCache.DocEntry entry(String content, long generation) {
		return new KbCache.DocEntry(content, "etag-" + generation, "sha-" + generation, "2026-01-01T00:00:00Z");
	}

}
