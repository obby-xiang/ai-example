package com.example.configmgr.ai.kb;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 矩阵 M13/M14：sha 增量、chunk 复用、热重载原子性、失败 fail-open。
 */
class KbServiceIncrementalTest {

    /** 统计正文拉取次数的本地源。 */
    static final class CountingSource extends LocalDirKbSource {

        private final AtomicInteger fetches = new AtomicInteger();

        CountingSource(Path dir) {
            super(dir, KbTestSupport.mapper());
        }

        @Override
        public String fetchContent(KbDocMeta meta) throws KbSourceException {
            this.fetches.incrementAndGet();
            return super.fetchContent(meta);
        }
    }

    @Test
    void unchangedShaDoesNotRefetchContentAndReusesChunks() throws Exception {
        Path dir = KbTestSupport.copySamples();
        CountingSource source = new CountingSource(dir);
        KbService service = new KbService(KbTestSupport.props(dir, KbTestSupport.tempDir("kb-idx-")),
                KbTestSupport.mapper(), source);

        assertThat(service.refreshNow()).isTrue();
        int firstBuildFetches = source.fetches.get();
        assertThat(firstBuildFetches).isEqualTo(20);
        KbDoc before = service.document("region-tariff");

        assertThat(service.refreshNow()).isFalse();
        assertThat(source.fetches.get()).isEqualTo(firstBuildFetches);
        KbDoc after = service.document("region-tariff");
        assertThat(after).isSameAs(before);
        assertThat(after.chunks()).isSameAs(before.chunks());
    }

    @Test
    void changedShaRebuildsOnlyThatDocument() throws Exception {
        Path dir = KbTestSupport.copySamples();
        CountingSource source = new CountingSource(dir);
        KbService service = new KbService(KbTestSupport.props(dir, KbTestSupport.tempDir("kb-idx-")),
                KbTestSupport.mapper(), source);
        service.refreshNow();
        int baseFetches = source.fetches.get();
        KbDoc changedBefore = service.document("currency-scope");
        KbDoc untouchedBefore = service.document("region-tariff");

        Path target = dir.resolve("14-currency-scope.md");
        Files.writeString(target, Files.readString(target, StandardCharsets.UTF_8)
                + "\n补充一句：币种编码不允许包含小写字母。\n", StandardCharsets.UTF_8);

        assertThat(service.refreshNow()).isTrue();
        assertThat(source.fetches.get()).isEqualTo(baseFetches + 1);
        assertThat(service.document("currency-scope").meta().sha())
                .isNotEqualTo(changedBefore.meta().sha());
        assertThat(service.document("currency-scope").chunks())
                .isNotSameAs(changedBefore.chunks());
        assertThat(service.document("region-tariff")).isSameAs(untouchedBefore);
        assertThat(service.document("region-tariff").chunks()).isSameAs(untouchedBefore.chunks());
    }

    @Test
    void removedDocumentLeavesTheIndex() throws Exception {
        Path dir = KbTestSupport.copySamples();
        KbService service = new KbService(KbTestSupport.props(dir, KbTestSupport.tempDir("kb-idx-")),
                KbTestSupport.mapper(), new CountingSource(dir));
        service.refreshNow();
        assertThat(service.status().docCount()).isEqualTo(20);

        Path manifest = dir.resolve("manifest.json");
        String json = Files.readString(manifest, StandardCharsets.UTF_8);
        var mapper = KbTestSupport.mapper();
        var parsed = mapper.readTree(json);
        var docs = (com.fasterxml.jackson.databind.node.ArrayNode) parsed.get("docs");
        for (int i = docs.size() - 1; i >= 0; i--) {
            if ("currency-scope".equals(docs.get(i).get("id").asText())) {
                docs.remove(i);
            }
        }
        Files.writeString(manifest, mapper.writeValueAsString(parsed), StandardCharsets.UTF_8);

        assertThat(service.refreshNow()).isTrue();
        assertThat(service.status().docCount()).isEqualTo(19);
        assertThat(service.document("currency-scope")).isNull();
        KbSearchResult result = service.search("币种与范围口径", 5);
        assertThat(result.hits()).extracting(KbSearchHit::docId).doesNotContain("currency-scope");
    }

    @Test
    void brokenManifestKeepsOldIndexAndReportsError() throws Exception {
        Path dir = KbTestSupport.copySamples();
        KbService service = new KbService(KbTestSupport.props(dir, KbTestSupport.tempDir("kb-idx-")),
                KbTestSupport.mapper(), new CountingSource(dir));
        service.refreshNow();
        int docsBefore = service.status().docCount();

        Files.writeString(dir.resolve("manifest.json"), "{ not json", StandardCharsets.UTF_8);

        assertThat(service.refreshNow()).isFalse();
        assertThat(service.status().docCount()).isEqualTo(docsBefore);
        assertThat(service.status().ready()).isTrue();
        assertThat(service.status().lastError()).isNotBlank();
        assertThat(service.search("费率", 5).hits()).isNotEmpty();
    }

    @Test
    void concurrentSearchesNeverSeePartialIndexDuringReload() throws Exception {
        Path dir = KbTestSupport.copySamples();
        KbService service = new KbService(KbTestSupport.props(dir, KbTestSupport.tempDir("kb-idx-")),
                KbTestSupport.mapper(), new CountingSource(dir));
        service.refreshNow();

        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicInteger failures = new AtomicInteger();
        List<Thread> readers = new ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < 4; i++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                    while (!stop.get()) {
                        KbSearchResult result = service.search("费率 计算", 5);
                        if (result.hits().isEmpty() || result.state() == KbSearchResult.State.ERROR) {
                            failures.incrementAndGet();
                        }
                    }
                }
                catch (Throwable e) {
                    failures.incrementAndGet();
                }
            });
            thread.setDaemon(true);
            readers.add(thread);
            thread.start();
        }
        start.countDown();
        for (int i = 0; i < 3; i++) {
            service.refreshNow();
        }
        stop.set(true);
        for (Thread thread : readers) {
            thread.join(5000);
        }

        assertThat(failures.get()).isZero();
        assertThat(service.ready()).isTrue();
    }
}
