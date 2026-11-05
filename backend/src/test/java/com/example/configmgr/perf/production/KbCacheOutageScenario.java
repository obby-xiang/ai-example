package com.example.configmgr.perf.production;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.configmgr.ai.kb.CachedKbSource;
import com.example.configmgr.ai.kb.KbCache;
import com.example.configmgr.ai.kb.KbDocMeta;
import com.example.configmgr.ai.kb.KbHashing;
import com.example.configmgr.ai.kb.KbManifest;
import com.example.configmgr.ai.kb.KbSource;
import com.example.configmgr.ai.kb.KbSourceException;
import com.example.configmgr.ai.kb.RedisKbCacheStore;
import com.example.configmgr.perf.redis.Manifest;

final class KbCacheOutageScenario implements ProductionScenario {

    @Override
    public String id() {
        return "kb_cache_outage_fallback";
    }

    @Override
    public Manifest.Dataset generate(ProductionSupport support, Manifest.Dataset dataset) {
        String outageId = "outage-doc-" + support.cfg.namespace;
        String writeId = "write-failure-doc-" + support.cfg.namespace;
        for (String id : List.of(outageId, writeId)) {
            support.own(dataset, KbCache.docKey(id));
            support.ownHashField(dataset, KbCache.DOC_IDS_KEY, id);
        }
        dataset.attributes.put("outageId", outageId);
        dataset.attributes.put("writeId", writeId);
        String control = "perf:control:kb-outage:" + support.cfg.namespace;
        support.redis.opsForValue().set(control, "scenario-owned-control");
        support.own(dataset, control);
        KbProductionScenarioSupport.snapshot(support, dataset);
        return dataset;
    }

    @Override
    public void measure(ProductionSupport support, Manifest.Dataset dataset) throws Exception {
        KbCache cache = new KbCache(new RedisKbCacheStore(support.redis));
        ScriptedSource source = new ScriptedSource(String.valueOf(dataset.attributes.get("outageId")),
                String.valueOf(dataset.attributes.get("writeId")));
        CachedKbSource cached = new CachedKbSource(source, cache, support.mapper, support.aiProperties.getKb());
        try {
            stage(support, dataset, "kb_missing_sha_single_source_fetch", () -> {
                KbSource.ManifestFetch initial = cached.fetchManifest(null, true,
                        KbSource.RefreshBudget.within(Duration.ofSeconds(5)));
                KbDocMeta meta = initial.manifest().docs().get(0);
                support.require(dataset, "missing_sha_computed", meta.sha() != null && !meta.sha().isBlank(),
                        "first download computes an effective SHA");
                support.require(dataset, "missing_sha_downloaded_once", source.calls(source.outageId) == 1,
                        "cold publication fetches the body exactly once");
                support.require(dataset, "cached_body_returned_without_second_fetch",
                        cached.fetchContent(meta).equals(source.content(source.outageId))
                                && source.calls(source.outageId) == 1,
                        "the cached body is reused without another source request");
            });

            if (support.faultEnabled("eval")) {
                stage(support, dataset, "kb_cache_write_failure_returns_fetched_body", () -> {
                    String id = source.writeId;
                    support.redis.delete(KbCache.docKey(id));
                    support.faults.failNext("eval", KbCache.DOC_IDS_KEY);
                    String content;
                    try {
                        content = cached.fetchContent(source.meta(id, true));
                    }
                    finally {
                        support.faults.clear();
                    }
                    support.require(dataset, "body_returned_after_cache_write_failure",
                            content.equals(source.content(id)) && source.calls(id) == 2,
                            "one initial fetch and one lazy fetch; the failed cache write causes no third source request");
                });
            }
            else {
                support.faultSkipped(dataset, "eval");
            }

            if (support.faultEnabled("hgetall")) {
                stage(support, dataset, "kb_direct_v2_then_redis_recovery_reconciles", () -> {
                    source.publishV2();
                    support.faults.failNext("hgetall", KbCache.MANIFEST_KEY);
                    KbSource.ManifestFetch direct;
                    try {
                        direct = cached.fetchManifest("etag-v1", true, KbSource.RefreshBudget.within(Duration.ofSeconds(5)));
                    }
                    finally {
                        support.faults.clear();
                    }
                    support.require(dataset, "direct_v2_served_during_outage", "v2".equals(direct.manifest().version()),
                            "Redis outage serves the newer direct source snapshot");

                    String other = "perf-other-owner-" + support.cfg.namespace;
                    KbProductionScenarioSupport.registerLockToken(dataset, other);
                    support.require(dataset, "other_owner_holds_lease", cache.tryLock(other, Duration.ofSeconds(10)),
                            "hold the lease to prove reconcile cannot short-circuit to V1");
                    boolean rejected = false;
                    try {
                        cached.fetchManifest("etag-v2", true, KbSource.RefreshBudget.within(Duration.ofSeconds(5)));
                    }
                    catch (KbSourceException ex) {
                        rejected = true;
                    }
                    finally {
                        cache.unlock(other);
                    }
                    support.require(dataset, "reconcile_lease_failure_keeps_current_snapshot", rejected,
                            "after direct V2, lease failure must not return shared V1");

                    KbSource.ManifestFetch reconciled = cached.fetchManifest("etag-v2", true,
                            KbSource.RefreshBudget.within(Duration.ofSeconds(5)));
                    support.require(dataset, "recovery_publishes_v2", "v2".equals(reconciled.manifest().version()),
                            "304 mismatch triggers an unconditional fetch and cache publication");
                    support.require(dataset, "conditional_then_unconditional_manifest_calls", source.manifestCalls == 4,
                            "initial V1, direct V2, conditional 304 and unconditional V2 are the only manifest calls");
                    support.require(dataset, "cached_v2_visible_atomically", "v2".equals(KbManifest.parse(
                            cache.manifest().orElseThrow().json().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            support.mapper).version()), "restored Redis exposes V2 rather than rolling back to V1");
                });
            }
            else {
                support.faultSkipped(dataset, "hgetall");
            }

            stage(support, dataset, "kb_stale_if_error_after_reconcile", () -> {
                source.failManifest = true;
                KbSource.ManifestFetch stale = cached.fetchManifest("etag-v2", true,
                        KbSource.RefreshBudget.within(Duration.ofSeconds(5)));
                support.require(dataset, "stale_cache_retained_on_source_failure", "v2".equals(stale.manifest().version()),
                        "after reconciliation, a source failure may retain the matching V2 cache");
                source.failManifest = false;
            });
            support.require(dataset, "kb_outage_zero_keyspace_scan", support.commandMetrics.keyspaceScanPages() == 0L,
                    "fallback and recovery use exact keys only");
            dataset.state = "measured";
        }
        finally {
            source.failManifest = false;
            cached.close();
            KbProductionScenarioSupport.noteSharedState(support, dataset);
            String control = "perf:control:kb-outage:" + support.cfg.namespace;
            support.redis.delete(control);
            support.require(dataset, "scenario_control_key_removed", !support.redis.hasKey(control),
                    "scenario-owned control key is deleted after fallback evidence is complete");
        }
    }

    private static void stage(ProductionSupport support, Manifest.Dataset dataset, String name,
            ThrowingRunnable body) {
        support.stage(dataset, name, () -> {
            try {
                body.run();
            }
            catch (Exception ex) {
                if (ex instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new IllegalStateException(ex);
            }
        });
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    static long cleanupSharedState(ProductionSupport support, Manifest.Dataset dataset) {
        return KbProductionScenarioSupport.cleanupSharedState(support, dataset);
    }

    static List<String> sharedStateResidue(ProductionSupport support, Manifest.Dataset dataset) {
        return KbProductionScenarioSupport.sharedStateResidue(support, dataset);
    }

    private static final class ScriptedSource implements KbSource {
        private final String outageId;
        private final String writeId;
        private final Map<String, String> bodies = new LinkedHashMap<>();
        private final Map<String, Integer> calls = new LinkedHashMap<>();
        private String version = "v1";
        private String etag = "etag-v1";
        private int manifestCalls;
        private boolean failManifest;

        private ScriptedSource(String outageId, String writeId) {
            this.outageId = outageId;
            this.writeId = writeId;
            this.bodies.put(outageId, "## V1\nfirst body\n");
            this.bodies.put(writeId, "## Write\nwrite body\n");
        }

        @Override
        public String kind() {
            return "http";
        }

        @Override
        public ManifestFetch fetchManifest(String previousEtag) throws KbSourceException {
            this.manifestCalls++;
            if (this.failManifest) {
                throw new KbSourceException("injected source outage");
            }
            if (this.etag.equals(previousEtag)) {
                return ManifestFetch.notModified(this.etag);
            }
            List<KbDocMeta> docs = this.bodies.keySet().stream().map(id -> meta(id, false)).toList();
            return ManifestFetch.modified(new KbManifest(this.version, "source-generated-at", docs), this.etag);
        }

        @Override
        public String fetchContent(KbDocMeta meta) {
            this.calls.merge(meta.id(), 1, Integer::sum);
            return content(meta.id());
        }

        private KbDocMeta meta(String id, boolean declaredSha) {
            String content = content(id);
            return new KbDocMeta(id, "Title " + id, "Summary " + id, List.of(), List.of(),
                    declaredSha ? KbHashing.sha256Text(content) : null, "2026-01-01", id + ".md", List.of(), List.of());
        }

        private String content(String id) {
            return this.bodies.get(id);
        }

        private int calls(String id) {
            return this.calls.getOrDefault(id, 0);
        }

        private void publishV2() {
            this.version = "v2";
            this.etag = "etag-v2";
            this.bodies.put(this.outageId, "## V2\nnewer direct body\n");
            this.bodies.put(this.writeId, "## Write V2\nnewer write body\n");
        }
    }
}
