package com.example.configmgr.perf.production;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.configmgr.ai.config.AiConfigService;
import com.example.configmgr.ai.config.AiConnectionProbe;
import com.example.configmgr.ai.config.AiModelCatalog;
import com.example.configmgr.ai.config.db.AiProvider;
import com.example.configmgr.perf.redis.Manifest;
import org.mockito.Mockito;

final class AiModelCatalogScenario implements ProductionScenario {

    @Override
    public String id() {
        return "ai_model_catalog_cache";
    }

    @Override
    public Manifest.Dataset generate(ProductionSupport support, Manifest.Dataset dataset) {
        long providerId = support.jobId(dataset, 1);
        dataset.attributes.put("providerId", providerId);
        support.own(dataset, AiModelCatalog.CACHE_KEY_PREFIX + providerId);
        String control = "perf:control:model-catalog:" + support.cfg.namespace;
        support.redis.opsForValue().set(control, "scenario-owned-control");
        support.own(dataset, control);
        return dataset;
    }

    @Override
    public void measure(ProductionSupport support, Manifest.Dataset dataset) {
        long providerId = ((Number) dataset.attributes.get("providerId")).longValue();
        String key = AiModelCatalog.CACHE_KEY_PREFIX + providerId;
        AiProvider provider = new AiProvider();
        provider.setId(providerId);
        provider.setCode("perf-model-catalog");
        provider.setBaseUrl("https://models.invalid");
        provider.setSource(AiProvider.SOURCE_ENV_SEED);

        AiConfigService configService = Mockito.mock(AiConfigService.class);
        AiConnectionProbe probe = Mockito.mock(AiConnectionProbe.class);
        AtomicInteger upstreamCalls = new AtomicInteger();
        AtomicInteger fetches = new AtomicInteger();
        Mockito.when(configService.provider(providerId)).thenReturn(Optional.of(provider));
        Mockito.when(configService.resolveKey(provider)).thenReturn(new AiConfigService.KeyResolution(
                AiConfigService.KeyStatus.OK, "perf-key", "perf-fingerprint"));
        Mockito.when(probe.fetchModels(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean()))
                .thenAnswer(invocation -> {
                    int call = upstreamCalls.incrementAndGet();
                    return AiConnectionProbe.ProbeResult.ok(200, 1L, List.of("model-v" + call));
                });
        AiModelCatalog catalog = new AiModelCatalog(probe, configService, support.redis,
                support.aiProperties, support.mapper);
        Duration originalTtl = support.aiProperties.getConfig().getModelsCacheTtl();
        long scanBefore = support.commandMetrics.keyspaceScanPages();
        try {
            support.aiProperties.getConfig().setModelsCacheTtl(Duration.ofMillis(150));
            support.stage(dataset, "model_catalog_positive_ttl_and_hit", () -> {
                AiModelCatalog.CatalogResult first = fetch(catalog, providerId, true, fetches);
                support.require(dataset, "initial_upstream_fetch", first.ok() && !first.cached(),
                        "initial fetch uses the isolated upstream probe");
                long ttl = support.redis.getExpire(key, TimeUnit.MILLISECONDS);
                support.require(dataset, "positive_cache_ttl", ttl > 0L, "model cache is written with a positive TTL");
                AiModelCatalog.CatalogResult cached = fetch(catalog, providerId, false, fetches);
                support.require(dataset, "cache_hit_skips_upstream", cached.ok() && cached.cached()
                        && upstreamCalls.get() == 1, "second read is served by Redis without another upstream call");
            });

            if (support.faultEnabled("set")) {
                support.stage(dataset, "model_catalog_write_failure_keeps_old_cache", () -> {
                    List<String> old = cachedModels(support, key);
                    support.faults.failNext("set", key);
                    AiModelCatalog.CatalogResult refreshed;
                    try {
                        refreshed = fetch(catalog, providerId, true, fetches);
                    }
                    finally {
                        support.faults.clear();
                    }
                    support.require(dataset, "refresh_result_survives_cache_write_failure", refreshed.ok() && !refreshed.cached(),
                            "upstream result is returned even when Redis SET fails");
                    support.require(dataset, "old_cache_retained_after_write_failure",
                            cachedModels(support, key).equals(old),
                            "failed refresh does not erase the previous cached model list");
                });
            }
            else {
                support.faultSkipped(dataset, "set");
            }

            if (support.faultEnabled("get")) {
                support.stage(dataset, "model_catalog_read_failure_and_recovery", () -> {
                    support.faults.failNext("get", key);
                    AiModelCatalog.CatalogResult degraded;
                    try {
                        degraded = fetch(catalog, providerId, false, fetches);
                    }
                    finally {
                        support.faults.clear();
                    }
                    support.require(dataset, "read_failure_degrades_to_upstream", degraded.ok() && !degraded.cached(),
                            "GET failure becomes a cache miss rather than a fake hit");
                    AiModelCatalog.CatalogResult recovered = fetch(catalog, providerId, false, fetches);
                    support.require(dataset, "recovered_cache_hit", recovered.ok() && recovered.cached(),
                            "the next healthy read repopulates and then hits the cache");
                });
            }
            else {
                support.faultSkipped(dataset, "get");
            }

            support.stage(dataset, "model_catalog_ttl_expiry_and_refresh", () -> {
                support.aiProperties.getConfig().setModelsCacheTtl(Duration.ofMillis(40));
                fetch(catalog, providerId, true, fetches);
                sleep(90L);
                support.require(dataset, "ttl_expired", support.redis.opsForValue().get(key) == null,
                        "expired model cache is not served as fresh");
                AiModelCatalog.CatalogResult refreshed = fetch(catalog, providerId, false, fetches);
                support.require(dataset, "expired_cache_refreshed", refreshed.ok() && !refreshed.cached(),
                        "expiry triggers a fresh upstream pull");
                support.require(dataset, "refreshed_cache_has_positive_ttl",
                        support.redis.getExpire(key, TimeUnit.MILLISECONDS) > 0L,
                        "refresh writes a new bounded-lifetime cache entry");
            });

            Mockito.verify(configService, Mockito.times(fetches.get())).provider(providerId);
            Mockito.verify(configService, Mockito.times(fetches.get())).resolveKey(provider);
            Mockito.verifyNoMoreInteractions(configService);
            Mockito.verify(probe, Mockito.times(upstreamCalls.get())).fetchModels(Mockito.anyString(), Mockito.anyString(),
                    Mockito.anyString(), Mockito.anyBoolean());
            support.require(dataset, "no_db_allowlist_write", true,
                    "configService interactions are provider/key reads only; no model allowlist persistence is called");
            support.require(dataset, "model_catalog_zero_keyspace_scan",
                    support.commandMetrics.keyspaceScanPages() == scanBefore,
                    "model cache uses exact provider keys only");
            dataset.state = "measured";
        }
        finally {
            support.aiProperties.getConfig().setModelsCacheTtl(originalTtl);
            support.faults.clear();
            String control = "perf:control:model-catalog:" + support.cfg.namespace;
            support.redis.delete(control);
            support.require(dataset, "scenario_control_key_removed", !support.redis.hasKey(control),
                    "scenario-owned control key is deleted after cache evidence is complete");
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during model cache TTL check", ex);
        }
    }

    private static List<String> cachedModels(ProductionSupport support, String key) {
        try {
            String raw = support.redis.opsForValue().get(key);
            return raw == null ? List.of() : support.mapper.readValue(raw,
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
                    });
        }
        catch (Exception ex) {
            throw new IllegalStateException("cannot read cached model list", ex);
        }
    }

    private static AiModelCatalog.CatalogResult fetch(AiModelCatalog catalog, long providerId, boolean refresh,
            AtomicInteger fetches) {
        fetches.incrementAndGet();
        return catalog.fetch(providerId, refresh);
    }
}
