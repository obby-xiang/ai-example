package com.example.configmgr.perf.production;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.example.configmgr.ai.kb.CachedKbSource;
import com.example.configmgr.ai.kb.KbCache;
import com.example.configmgr.ai.kb.KbDocMeta;
import com.example.configmgr.ai.kb.KbHashing;
import com.example.configmgr.ai.kb.KbManifest;
import com.example.configmgr.ai.kb.KbService;
import com.example.configmgr.ai.kb.KbStatus;
import com.example.configmgr.ai.kb.LocalDirKbSource;
import com.example.configmgr.ai.kb.RedisKbCacheStore;
import com.example.configmgr.perf.redis.Manifest;

final class KbCorpusScenario implements ProductionScenario {

    @Override
    public String id() {
        return "kb_refresh_large_corpus";
    }

    @Override
    public Manifest.Dataset generate(ProductionSupport support, Manifest.Dataset dataset) throws Exception {
        int docCount = Math.min(256, Math.max(65, support.intEnv("PERF_KB_CORPUS_DOCS", 130)));
        int bodyBytes = Math.min(2048, Math.max(64, support.intEnv("PERF_KB_CORPUS_BODY_BYTES", 256)));
        Path root = support.cfg.outputDir.resolve("kb-corpus-" + support.cfg.namespace).toAbsolutePath().normalize();
        Path corpus = root.resolve("corpus");
        Files.createDirectories(corpus);
        List<KbDocMeta> docs = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < docCount; i++) {
            String id = support.cfg.namespace + "-doc-" + String.format("%03d", i);
            String file = String.format("doc-%03d.md", i);
            String content = "## Section\n" + "知识".repeat(Math.max(1, bodyBytes / 6)) + "-" + i + "\n";
            Files.writeString(corpus.resolve(file), content, StandardCharsets.UTF_8);
            docs.add(new KbDocMeta(id, "Title " + id, "Summary " + id, List.of("tag-" + (i % 3)),
                    List.of("alias-" + i), KbHashing.sha256Text(content), "2026-01-01", file, List.of(), List.of()));
            ids.add(id);
            support.own(dataset, KbCache.docKey(id));
            support.ownHashField(dataset, KbCache.DOC_IDS_KEY, id);
        }
        String retired = support.cfg.namespace + "-retired-doc";
        ids.add(retired);
        support.own(dataset, KbCache.docKey(retired));
        support.ownHashField(dataset, KbCache.DOC_IDS_KEY, retired);
        writeManifest(support, corpus, new KbManifest("corpus-v1", "instance-generated-time", docs));

        String control = "perf:control:kb-corpus:" + support.cfg.namespace;
        support.redis.opsForValue().set(control, "scenario-owned-control");
        support.own(dataset, control);
        KbProductionScenarioSupport.snapshot(support, dataset);
        dataset.attributes.put("root", root.toString());
        dataset.attributes.put("docCount", docCount);
        dataset.attributes.put("docIds", ids);
        dataset.attributes.put("retiredDoc", retired);
        dataset.attributes.put("expectedIndexBatches", (docCount + 63) / 64);
        dataset.attributes.put("bodyBytes", bodyBytes);
        return dataset;
    }

    @Override
    public void measure(ProductionSupport support, Manifest.Dataset dataset) throws Exception {
        Path root = Path.of(String.valueOf(dataset.attributes.get("root")));
        Path corpus = root.resolve("corpus");
        String originalDir = support.aiProperties.getKb().getDir();
        String originalIndexDir = support.aiProperties.getKb().getIndexDir();
        support.aiProperties.getKb().setDir(corpus.toString());
        support.aiProperties.getKb().setIndexDir(root.resolve("index").toString());
        KbCache cache = new KbCache(new RedisKbCacheStore(support.redis));
        LocalDirKbSource local = new LocalDirKbSource(corpus, support.mapper);
        CachedKbSource cached = new CachedKbSource(local, cache, support.mapper, support.aiProperties.getKb());
        KbService service = new KbService(support.aiProperties, support.mapper, cached);
        String retired = String.valueOf(dataset.attributes.get("retiredDoc"));
        long scanBefore = support.commandMetrics.keyspaceScanPages();
        try {
            support.stage(dataset, "kb_large_corpus_initial_reload", () -> {
                service.start();
                awaitReady(service);
                int expected = ((Number) dataset.attributes.get("docCount")).intValue();
                support.require(dataset, "large_corpus_ready", service.status().ready()
                        && service.status().docCount() == expected, "real Lucene index contains every generated document");
                assertCanonicalManifest(support, dataset, cache, "corpus-v1");
            });

            support.stage(dataset, "kb_lease_token_renewal_and_atomic_reject", () -> {
                String token = "perf-lease-" + support.cfg.namespace;
                KbProductionScenarioSupport.registerLockToken(dataset, token);
                support.require(dataset, "lease_acquired", cache.tryLock(token, Duration.ofMillis(150)),
                        "real SET NX PX lease");
                support.require(dataset, "lease_token_renewed", cache.renewLock(token, Duration.ofSeconds(2)),
                        "owner token PEXPIRE renewal");
                support.require(dataset, "foreign_token_cannot_renew", !cache.renewLock("foreign", Duration.ofSeconds(2)),
                        "foreign token must not extend the lease");
                support.require(dataset, "lease_released_by_owner", cache.unlock(token), "owner token release");
                RedisKbCacheStore store = new RedisKbCacheStore(support.redis);
                Map<String, String> before = store.hashGetAll(KbCache.MANIFEST_KEY);
                long generation = cache.nextGeneration();
                support.require(dataset, "foreign_token_cannot_publish", !cache.publishManifest(
                        new KbCache.ManifestEntry("bad", "bad", "{}"), generation, "foreign"),
                        "same-slot Lua rejects manifest/version publication without the owner token");
                support.require(dataset, "manifest_unchanged_after_reject", before.equals(store.hashGetAll(KbCache.MANIFEST_KEY)),
                        "failed publication cannot expose a partial manifest or version");
            });

            support.stage(dataset, "kb_changed_corpus_then_noop_reload", () -> {
                KbManifest current = readManifest(support, cache.manifest().orElseThrow().json());
                List<KbDocMeta> changed = new ArrayList<>(current.docs());
                KbDocMeta first = changed.get(0);
                String body = "## Updated\n" + "新正文".repeat(64) + "\n";
                writeBody(corpus.resolve(first.effectiveFile()), body);
                changed.set(0, first.withSha(KbHashing.sha256Text(body)));
                writeManifest(support, corpus, new KbManifest("corpus-v2", "another-instance-time", changed));
                support.require(dataset, "changed_corpus_rebuilt", service.refreshNow(true),
                        "one changed body and manifest version trigger a real Lucene rebuild");
                support.require(dataset, "changed_manifest_version_visible", "corpus-v2".equals(service.status().version()),
                        "status uses the same canonical manifest version");
                assertCanonicalManifest(support, dataset, cache, "corpus-v2");
                var builtAtBefore = service.status().builtAt();
                String canonicalBefore = cache.manifest().orElseThrow().json();
                boolean noOp = !service.refreshNow(true);
                support.require(dataset, "identical_reload_is_noop", noOp && service.status().lastError().isEmpty()
                        && builtAtBefore.equals(service.status().builtAt())
                        && canonicalBefore.equals(cache.manifest().orElseThrow().json()),
                        "false must mean no error and identical canonical bytes with the same live Lucene build");
            });

            support.stage(dataset, "kb_reload_wait_budget_keeps_current_snapshot", () -> {
                support.aiProperties.getKb().setRefreshWaitBudget(Duration.ofNanos(1));
                java.util.concurrent.atomic.AtomicReference<String> waitError = new java.util.concurrent.atomic.AtomicReference<>();
                java.util.concurrent.CountDownLatch watcherStarted = new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.ExecutorService watcher = java.util.concurrent.Executors.newSingleThreadExecutor();
                try {
                    java.util.concurrent.Future<?> observed = watcher.submit(() -> {
                        watcherStarted.countDown();
                        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                        while (System.nanoTime() < deadline && waitError.get() == null) {
                            String current = service.status().lastError();
                            if (current != null && current.contains("wait exceeded budget")) {
                                waitError.set(current);
                            }
                            try {
                                Thread.sleep(1L);
                            }
                            catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(ex);
                            }
                        }
                    });
                    watcherStarted.await();
                    support.require(dataset, "expired_wait_budget_returns_unconfirmed", !service.refreshNow(true),
                            "expired refresh wait budget returns false");
                    observed.get(6L, java.util.concurrent.TimeUnit.SECONDS);
                    dataset.attributes.put("budgetLastError", waitError.get());
                    support.require(dataset, "budget_error_uses_wait_semantics", waitError.get() != null,
                            "cooperative budget failure is visible in status");
                }
                catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
                finally {
                    watcher.shutdownNow();
                }
                support.require(dataset, "current_snapshot_retained_after_budget_failure",
                        "corpus-v2".equals(service.status().version()), "budget failure keeps the current index");
                support.aiProperties.getKb().setRefreshWaitBudget(Duration.ofSeconds(10));
            });

            support.stage(dataset, "kb_generation_delete_prune_and_retry", () -> {
                long oldGeneration = cache.nextGeneration();
                cache.putDocId(retired, oldGeneration);
                cache.putDoc(retired, new KbCache.DocEntry("old", "old", "old", "2026-01-01"), oldGeneration);
                long currentGeneration = cache.nextGeneration();
                cache.putDoc(retired, new KbCache.DocEntry("new", "new", "new", "2026-01-01"), currentGeneration);
                support.require(dataset, "stale_delete_rejected", !cache.deleteDoc(retired, oldGeneration),
                        "old generation cannot delete the newer body");
                support.require(dataset, "current_body_deleted", cache.deleteDoc(retired, currentGeneration),
                        "current generation body deletion succeeds");
                if (support.faultEnabled("hdel")) {
                    support.faults.failNext("hdel", KbCache.DOC_IDS_KEY);
                    boolean failed = false;
                    try {
                        cache.removeDocId(retired, currentGeneration);
                    }
                    catch (RuntimeException ex) {
                        failed = true;
                    }
                    finally {
                        support.requireFaultsDrained(dataset, "index_fault_consumed");
                        support.faults.clear();
                    }
                    support.require(dataset, "index_fault_observed", failed, "real HDEL failure after body deletion");
                    support.require(dataset, "index_retained_for_retry", cache.docIds().contains(retired),
                            "failed index deletion leaves a retryable field");
                    support.require(dataset, "next_prune_converges", cache.removeDocId(retired, currentGeneration),
                            "next generation-fenced prune removes the retained field");
                }
                else {
                    support.faultSkipped(dataset, "hdel");
                    cache.removeDocId(retired, currentGeneration);
                }
                support.require(dataset, "retired_doc_fully_pruned", cache.doc(retired).isEmpty()
                        && !cache.docIds().contains(retired), "body and exact index field converge without SCAN");
            });

            support.require(dataset, "kb_zero_keyspace_scan",
                        support.commandMetrics.keyspaceScanPages() == scanBefore,
                    "large corpus, publish and prune must not use keyspace SCAN");
            dataset.state = "measured";
        }
        finally {
            support.aiProperties.getKb().setRefreshWaitBudget(Duration.ofSeconds(30));
            support.aiProperties.getKb().setDir(originalDir);
            support.aiProperties.getKb().setIndexDir(originalIndexDir);
            service.stop();
            cached.close();
            KbProductionScenarioSupport.noteSharedState(support, dataset);
            String control = "perf:control:kb-corpus:" + support.cfg.namespace;
            support.redis.delete(control);
            deleteTree(root);
            support.require(dataset, "scenario_control_key_and_corpus_removed",
                    !support.redis.hasKey(control) && !Files.exists(root),
                    "scenario-owned control key and isolated corpus are removed after evidence collection");
        }
    }

    static long cleanupSharedState(ProductionSupport support, Manifest.Dataset dataset) {
        return KbProductionScenarioSupport.cleanupSharedState(support, dataset);
    }

    static List<String> sharedStateResidue(ProductionSupport support, Manifest.Dataset dataset) {
        return KbProductionScenarioSupport.sharedStateResidue(support, dataset);
    }

    private static void assertCanonicalManifest(ProductionSupport support, Manifest.Dataset dataset, KbCache cache,
            String expectedVersion) {
        KbCache.ManifestEntry entry = cache.manifest().orElseThrow();
        KbManifest manifest = readManifest(support, entry.json());
        support.require(dataset, "cv3_version_published", entry.version().startsWith("cv3:")
                && entry.version().equals(cache.corpusVersion()), "manifest HASH version and JSON are visible together");
        support.require(dataset, "canonical_manifest_version", expectedVersion.equals(manifest.version()),
                "source manifest version is retained");
        support.require(dataset, "generated_at_excluded", manifest.generatedAt().isEmpty(),
                "canonical manifest excludes generatedAt");
        List<String> ids = manifest.docs().stream().map(KbDocMeta::id).toList();
        List<String> sorted = ids.stream().sorted(Comparator.naturalOrder()).toList();
        support.require(dataset, "canonical_docs_sorted", ids.equals(sorted), "canonical docs use stable id ordering");
    }

    private static KbManifest readManifest(ProductionSupport support, String json) {
        try {
            return KbManifest.parse(json.getBytes(StandardCharsets.UTF_8), support.mapper);
        }
        catch (Exception ex) {
            throw new IllegalStateException("unable to read KB manifest evidence", ex);
        }
    }

    private static void writeManifest(ProductionSupport support, Path corpus, KbManifest manifest) {
        try {
            Files.writeString(corpus.resolve("manifest.json"), support.mapper.writeValueAsString(manifest),
                    StandardCharsets.UTF_8);
        }
        catch (Exception ex) {
            throw new IllegalStateException("unable to write isolated KB manifest", ex);
        }
    }

    private static void writeBody(Path path, String body) {
        try {
            Files.writeString(path, body, StandardCharsets.UTF_8);
        }
        catch (IOException ex) {
            throw new IllegalStateException("unable to update isolated KB body", ex);
        }
    }

    private static void awaitReady(KbService service) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            KbStatus status = service.status();
            if (status.ready()) {
                return;
            }
            if (status.lastError() != null && !status.lastError().isBlank()) {
                throw new IllegalStateException("KB initial refresh failed: " + status.lastError());
            }
            try {
                Thread.sleep(10L);
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while awaiting KB readiness", ex);
            }
        }
        throw new IllegalStateException("KB initial refresh did not become ready within 15s");
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(path);
            }
        }
    }
}
