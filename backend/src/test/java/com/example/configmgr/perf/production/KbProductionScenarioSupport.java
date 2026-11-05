package com.example.configmgr.perf.production;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.configmgr.ai.kb.KbCache;
import com.example.configmgr.ai.kb.RedisKbCacheStore;
import com.example.configmgr.perf.redis.Manifest;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

final class KbProductionScenarioSupport {

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

    private static final RedisScript<Long> CONDITIONAL_GENERATION_RESTORE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then
              return 0
            end
            redis.call('SET', KEYS[1], ARGV[2])
            return 1
            """, Long.class);

    private KbProductionScenarioSupport() {
    }

    static void snapshot(ProductionSupport support, Manifest.Dataset dataset) {
        RedisKbCacheStore store = new RedisKbCacheStore(support.redis);
        dataset.attributes.put("originalManifest", store.hashGetAll(KbCache.MANIFEST_KEY));
        dataset.attributes.put("originalGeneration", store.get(KbCache.GENERATION_KEY));
        dataset.attributes.put("originalLock", store.get(KbCache.LOCK_KEY));
        dataset.attributes.put("scenarioLockTokens", new ArrayList<String>());
    }

    static void noteSharedState(ProductionSupport support, Manifest.Dataset dataset) {
        RedisKbCacheStore store = new RedisKbCacheStore(support.redis);
        Map<String, String> manifest = store.hashGetAll(KbCache.MANIFEST_KEY);
        if (!manifest.isEmpty()) {
            dataset.attributes.put("publishedManifest", manifest);
        }
        dataset.attributes.put("latestGeneration", store.get(KbCache.GENERATION_KEY));
    }

    static void registerLockToken(Manifest.Dataset dataset, String token) {
        lockTokens(dataset).add(token);
    }

    static long cleanupSharedState(ProductionSupport support, Manifest.Dataset dataset) {
        RedisKbCacheStore store = new RedisKbCacheStore(support.redis);
        long changed = 0L;
        if (dataset.attributes.containsKey("publishedManifest")) {
            Map<String, String> published = stringMap(dataset.attributes.get("publishedManifest"));
            Map<String, String> original = stringMap(dataset.attributes.get("originalManifest"));
            Long restored = support.redis.execute(CONDITIONAL_MANIFEST_RESTORE, List.of(KbCache.MANIFEST_KEY),
                    json(support, published), json(support, original));
            if (restored != null) {
                changed += restored;
            }
        }
        Object originalGeneration = dataset.attributes.get("originalGeneration");
        Object latestGeneration = dataset.attributes.get("latestGeneration");
        if (latestGeneration != null) {
            String expected = String.valueOf(latestGeneration);
            if (originalGeneration == null) {
                if (expected.equals(store.get(KbCache.GENERATION_KEY))
                        && store.compareAndDelete(KbCache.GENERATION_KEY, expected)) {
                    changed++;
                }
            }
            else {
                Long restored = support.redis.execute(CONDITIONAL_GENERATION_RESTORE, List.of(KbCache.GENERATION_KEY),
                        expected, String.valueOf(originalGeneration));
                if (restored != null) {
                    changed += restored;
                }
            }
        }
        for (String token : lockTokens(dataset)) {
            if (token.equals(store.get(KbCache.LOCK_KEY)) && store.compareAndDelete(KbCache.LOCK_KEY, token)) {
                changed++;
            }
        }
        dataset.attributes.put("sharedGenerationAfterCleanup", store.get(KbCache.GENERATION_KEY));
        return changed;
    }

    static List<String> sharedStateResidue(ProductionSupport support, Manifest.Dataset dataset) {
        RedisKbCacheStore store = new RedisKbCacheStore(support.redis);
        List<String> residue = new ArrayList<>();
        if (dataset.attributes.containsKey("publishedManifest")
                && store.hashGetAll(KbCache.MANIFEST_KEY).equals(stringMap(dataset.attributes.get("publishedManifest")))) {
            residue.add(KbCache.MANIFEST_KEY + "#scenario-published-value");
        }
        Object latestGeneration = dataset.attributes.get("latestGeneration");
        if (latestGeneration != null && String.valueOf(latestGeneration).equals(store.get(KbCache.GENERATION_KEY))) {
            residue.add(KbCache.GENERATION_KEY + "#scenario-generation");
        }
        for (String token : lockTokens(dataset)) {
            if (token.equals(store.get(KbCache.LOCK_KEY))) {
                residue.add(KbCache.LOCK_KEY + "#scenario-token");
            }
        }
        return residue;
    }

    private static String json(ProductionSupport support, Map<String, String> value) {
        try {
            return support.mapper.writeValueAsString(value);
        }
        catch (Exception ex) {
            throw new IllegalStateException("unable to encode KB cleanup snapshot", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> stringMap(Object value) {
        return value == null ? new LinkedHashMap<>() : new LinkedHashMap<>((Map<String, String>) value);
    }

    @SuppressWarnings("unchecked")
    private static List<String> lockTokens(Manifest.Dataset dataset) {
        return (List<String>) dataset.attributes.computeIfAbsent("scenarioLockTokens",
                ignored -> new ArrayList<String>());
    }
}
