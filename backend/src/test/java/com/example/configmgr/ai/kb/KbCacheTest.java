package com.example.configmgr.ai.kb;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 追加 3 矩阵：键族、TTL 策略（文档/清单无 TTL、锁 30s）、版本号、比对删除锁。
 */
class KbCacheTest {

    private final InMemoryKbCacheStore store = new InMemoryKbCacheStore();

    private final KbCache cache = new KbCache(this.store);

    @Test
    void keyFamilyIsStable() {
        assertThat(KbCache.DOC_PREFIX).isEqualTo("kb:cache:doc:");
        assertThat(KbCache.docKey("region-tariff")).isEqualTo("kb:cache:doc:region-tariff");
        assertThat(KbCache.MANIFEST_KEY).isEqualTo("kb:cache:manifest");
        assertThat(KbCache.GENERATION_KEY).isEqualTo("kb:cache:manifest:generation");
        assertThat(KbCache.LOCK_KEY).isEqualTo("kb:cache:{kb:cache:manifest}:refresh:lock");
    }

    @Test
    void docAndManifestKeysHaveNoTtlWhileLockHas30s() {
        this.cache.putDoc("a", new KbCache.DocEntry("# A\\n正文", "etag-a", "sha-a", "2026-10-01"));
        long generation = this.cache.nextGeneration();
        assertThat(this.cache.tryLock("token-m", Duration.ofSeconds(30))).isTrue();
        assertThat(this.cache.publishManifest(new KbCache.ManifestEntry("etag-m", "1", "{\"docs\":[]}"),
                generation, "token-m")).isTrue();

        assertThat(this.cache.ttl(KbCache.docKey("a"))).isEqualTo(Duration.ofMillis(-1));
        assertThat(this.cache.ttl(KbCache.MANIFEST_KEY)).isEqualTo(Duration.ofMillis(-1));
        assertThat(this.cache.ttl(KbCache.LOCK_KEY)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void generationIsMonotonicAndManifestRoundTrips() {
        assertThat(this.cache.generation()).isZero();
        assertThat(this.cache.nextGeneration()).isEqualTo(1L);
        assertThat(this.cache.nextGeneration()).isEqualTo(2L);
        assertThat(this.cache.generation()).isEqualTo(2L);

        assertThat(this.cache.tryLock("token-v", Duration.ofSeconds(30))).isTrue();
        assertThat(this.cache.publishManifest(new KbCache.ManifestEntry("etag", "2",
                "{\"version\":\"v2\",\"docs\":[]}"), 2L, "token-v")).isTrue();
        KbCache.ManifestEntry entry = this.cache.manifest().orElseThrow();
        assertThat(entry.etag()).isEqualTo("etag");
        assertThat(entry.version()).isEqualTo("2");
        assertThat(entry.json()).contains("\"v2\"");
    }

    @Test
    void unlockOnlyDeletesOwnToken() {
        this.cache.tryLock("mine", Duration.ofSeconds(30));

        assertThat(this.cache.unlock("theirs")).isFalse();
        assertThat(this.cache.ttl(KbCache.LOCK_KEY)).isEqualTo(Duration.ofSeconds(30));
        assertThat(this.cache.unlock("mine")).isTrue();
        assertThat(this.cache.ttl(KbCache.LOCK_KEY)).isEqualTo(Duration.ofMillis(-2));
    }

    @Test
    void lockExpiresAfterTtl() {
        this.cache.tryLock("token", Duration.ofSeconds(30));
        this.store.advance(Duration.ofSeconds(31));

        assertThat(this.cache.tryLock("next", Duration.ofSeconds(30))).isTrue();
    }
}
