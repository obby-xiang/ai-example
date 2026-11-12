package com.example.configmgr.ai.kb;

import com.example.configmgr.ai.config.AiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 追加 3 矩阵：冷启动从缓存重建、单飞、版本号传播、stale-if-error、
 * local 过缓存等价性、缓存键无 TTL。
 */
class CachedKbSourceTest {

    private final InMemoryKbCacheStore store = new InMemoryKbCacheStore();

    private final KbCache cache = new KbCache(this.store);

    private CachedKbSource cached(KbSource delegate) {
        AiProperties.Kb kb = new AiProperties().getKb();
        return new CachedKbSource(delegate, this.cache, KbTestSupport.mapper(), kb);
    }

    private KbService service(KbSource source) throws Exception {
        Path index = KbTestSupport.tempDir("kb-cache-idx-");
        AiProperties properties = KbTestSupport.props(KbTestSupport.samples(), index);
        return new KbService(properties, KbTestSupport.mapper(), source);
    }

    @Test
    void leaseSchedulerUsesDedicatedDaemonThreadAndKeepsLifecyclePolicies() throws Exception {
        CachedKbSource source = cached(new FakeKbSource());
        ScheduledExecutorService scheduler = (ScheduledExecutorService) ReflectionTestUtils.getField(
                source, "leaseScheduler");
        try {
            ScheduledThreadPoolExecutor raw = (ScheduledThreadPoolExecutor) scheduler;
            assertThat(raw.getKeepAliveTime(TimeUnit.SECONDS)).isEqualTo(60L);
            assertThat(raw.allowsCoreThreadTimeOut()).isTrue();
            assertThat(raw.getRemoveOnCancelPolicy()).isTrue();
            Thread thread = scheduler.submit(Thread::currentThread).get(1L, TimeUnit.SECONDS);
            assertThat(thread.getName()).startsWith("kb-cache-lease-");
            assertThat(thread.isDaemon()).isTrue();
        } finally {
            source.close();
        }
        assertThat(scheduler.isShutdown()).isTrue();
    }

    @Test
    void localSourceGoesThroughCacheAndStaysEquivalent() throws Exception {
        KbService plain = KbTestSupport.samplesService();
        CachedKbSource cachedLocal = cached(new LocalDirKbSource(KbTestSupport.samples(), KbTestSupport.mapper()));
        KbService viaCache = service(cachedLocal);

        assertThat(viaCache.refreshNow()).isTrue();

        assertThat(viaCache.status().docCount()).isEqualTo(plain.status().docCount());
        assertThat(viaCache.status().chunkCount()).isEqualTo(plain.status().chunkCount());
        assertThat(viaCache.search("区域费率怎么计算", 5).hits().get(0).docId())
                .isEqualTo(plain.search("区域费率怎么计算", 5).hits().get(0).docId());

        assertThat(this.cache.manifest()).isPresent();
        assertThat(this.cache.doc("region-tariff")).isPresent();
        assertThat(cachedLocal.cachedDocIds()).hasSize(20);
        // TTL 策略：文档与清单键无 TTL
        assertThat(this.cache.ttl(KbCache.docKey("region-tariff"))).isEqualTo(Duration.ofMillis(-1));
        assertThat(this.cache.ttl(KbCache.MANIFEST_KEY)).isEqualTo(Duration.ofMillis(-1));
    }

    @Test
    void coldStartRebuildsFromCacheWhenDelegateIsDown() throws Exception {
        FakeKbSource first = new FakeKbSource().put("doc-a", "文档A", "# 文档A\n## 定义\n缓存冷启动内容。\n");
        KbService warm = service(cached(first));
        assertThat(warm.refreshNow()).isTrue();
        assertThat(warm.ready()).isTrue();

        FakeKbSource dead = new FakeKbSource();
        dead.failManifest = true;
        KbService cold = service(cached(dead));

        assertThat(cold.refreshNow()).isTrue();
        assertThat(cold.ready()).isTrue();
        assertThat(cold.status().docCount()).isEqualTo(1);
        assertThat(cold.search("缓存冷启动内容", 5).hits()).extracting(KbSearchHit::docId).contains("doc-a");
        assertThat(dead.manifestCalls.get()).isZero();
    }

    @Test
    void twoConcurrentColdStartsDoOnlyOneRemotePull() throws Exception {
        FakeKbSource fake = new FakeKbSource().put("doc-a", "文档A", "# 文档A\n## 定义\n单飞内容。\n");
        fake.delayMillis = 150;
        CachedKbSource a = cached(fake);
        CachedKbSource b = cached(fake);

        CountDownLatch start = new CountDownLatch(1);
        Throwable[] errors = new Throwable[2];
        Thread t1 = new Thread(() -> {
            try {
                start.await(5, TimeUnit.SECONDS);
                a.fetchManifest(null, true);
            }
            catch (Throwable e) {
                errors[0] = e;
            }
        });
        Thread t2 = new Thread(() -> {
            try {
                start.await(5, TimeUnit.SECONDS);
                b.fetchManifest(null, true);
            }
            catch (Throwable e) {
                errors[1] = e;
            }
        });
        t1.start();
        t2.start();
        start.countDown();
        t1.join(5000);
        t2.join(5000);

        assertThat(errors).containsOnlyNulls();
        assertThat(fake.manifestCalls.get()).isEqualTo(1);
        assertThat(this.cache.manifest()).isPresent();
    }

    @Test
    void versionBumpPropagatesAndRebuildsFromCacheWithoutRemote() throws Exception {
        FakeKbSource producer = new FakeKbSource().put("doc-a", "文档A", "# 文档A\n## 定义\n版本一。\n");
        CachedKbSource producerCached = cached(producer);
        KbService producerService = service(producerCached);
        assertThat(producerService.refreshNow()).isTrue();

        FakeKbSource consumerSource = new FakeKbSource();
        consumerSource.failManifest = true;
        KbService consumer = service(cached(consumerSource));
        assertThat(consumer.refreshNow()).isTrue();
        assertThat(consumer.search("版本一", 5).hits()).extracting(KbSearchHit::docId).contains("doc-a");

        producer.contents.put("doc-a", "# 文档A\n## 定义\n版本二的新内容。\n");
        producer.metas.put("doc-a", new KbDocMeta("doc-a", "文档A", "文档A 摘要", List.of("示例"), List.of(),
                KbHashing.sha256Hex(producer.contents.get("doc-a")), "2026-10-02", "doc-a.md", List.of(), List.of()));
        producer.etag = "etag-v2";
        producer.version = "v2";
        long versionBefore = this.cache.generation();

        assertThat(producerCached.fetchManifest("etag-v1", true)).isNotNull();
        assertThat(this.cache.generation()).isGreaterThan(versionBefore);
        int consumerRemoteCallsBefore = consumerSource.manifestCalls.get();

        assertThat(consumer.refreshNow()).isTrue();
        assertThat(consumer.search("版本二", 5).hits()).extracting(KbSearchHit::docId).contains("doc-a");
        assertThat(consumerSource.manifestCalls.get()).isEqualTo(consumerRemoteCallsBefore);
        assertThat(consumerSource.contentCalls.get()).isZero();
    }

    @Test
    void staleIfErrorKeepsServingOldContent() throws Exception {
        FakeKbSource fake = new FakeKbSource().put("doc-a", "文档A", "# 文档A\n## 定义\n旧料内容。\n");
        CachedKbSource source = cached(fake);
        KbService service = service(source);
        assertThat(service.refreshNow()).isTrue();

        fake.failManifest = true;
        // 越过刷新间隔（手动 force 等价）回源失败：不得抛异常、不得清空缓存
        KbSource.ManifestFetch fetched = source.fetchManifest("etag-v1", true);

        assertThat(fetched.manifest().docs()).hasSize(1);
        assertThat(this.cache.doc("doc-a")).isPresent();
        assertThat(service.refreshNow()).isFalse();
        assertThat(service.ready()).isTrue();
        assertThat(service.search("旧料内容", 5).hits()).extracting(KbSearchHit::docId).contains("doc-a");
    }

    @Test
    void noOpManifestSyncFencesOffOlderManifestWrite() throws Exception {
        FakeKbSource current = new FakeKbSource().put("doc-a", "文档A", "# 文档A\n## 定义\n当前正文。\n");
        CountDownLatch staleManifestWrite = new CountDownLatch(1);
        CountDownLatch releaseStaleManifestWrite = new CountDownLatch(1);
        KbCache interleavingCache = new KbCache(this.store) {
            @Override
            public boolean publishManifest(ManifestEntry entry, long generation, String leaseToken) {
                if (generation == 2L) {
                    staleManifestWrite.countDown();
                    try {
                        if (!releaseStaleManifestWrite.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("旧刷新等待放行超时");
                        }
                    }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                }
                return super.publishManifest(entry, generation, leaseToken);
            }
        };
        CachedKbSource warm = new CachedKbSource(local(current), interleavingCache, KbTestSupport.mapper(),
                new AiProperties().getKb());
        warm.fetchManifest(null, true);

        FakeKbSource stale = new FakeKbSource().put("doc-a", "文档A", "# 文档A\n## 定义\n旧刷新正文。\n");
        stale.etag = "etag-v2";
        stale.version = "v2";
        CachedKbSource staleSource = new CachedKbSource(local(stale), interleavingCache, KbTestSupport.mapper(),
                new AiProperties().getKb());
        Throwable[] staleError = new Throwable[1];
        Thread staleRefresh = new Thread(() -> {
            try {
                staleSource.fetchManifest(null, true);
            }
            catch (Throwable e) {
                staleError[0] = e;
            }
        });
        staleRefresh.start();
        assertThat(staleManifestWrite.await(5, TimeUnit.SECONDS)).isTrue();

        try {
            CachedKbSource noOp = new CachedKbSource(local(current), interleavingCache, KbTestSupport.mapper(),
                    new AiProperties().getKb());
            noOp.fetchManifest(null, true);
            long noOpGeneration = interleavingCache.generation();
            assertThat(noOpGeneration).isGreaterThan(2L);
        }
        finally {
            releaseStaleManifestWrite.countDown();
        }
        staleRefresh.join(5000);

        assertThat(staleRefresh.isAlive()).isFalse();
        assertThat(staleError).containsOnlyNulls();
        KbCache.ManifestEntry manifest = interleavingCache.manifest().orElseThrow();
        assertThat(manifest.etag()).isEqualTo("etag-v1");
        assertThat(manifest.json()).contains("\"version\":\"v1\"");
        assertThat(interleavingCache.generation()).isEqualTo(1L);
        assertThat(interleavingCache.doc("doc-a").orElseThrow().content()).contains("当前正文");
        String generation = String.valueOf(interleavingCache.generation());
        assertThat(this.store.hashGetAll(KbCache.MANIFEST_KEY).get(KbCache.F_GENERATION)).isEqualTo(generation);
        assertThat(this.store.hashGetAll(KbCache.docKey("doc-a")).get(KbCache.F_GENERATION)).isEqualTo(generation);
        assertThat(this.store.hashGetAll(KbCache.DOC_IDS_KEY).get("doc-a")).isEqualTo(generation);
    }

    private static KbSource local(FakeKbSource delegate) {
        return new KbSource() {
            @Override
            public String kind() {
                return "local";
            }

            @Override
            public ManifestFetch fetchManifest(String previousEtag) throws KbSourceException {
                return delegate.fetchManifest(previousEtag, true);
            }

            @Override
            public String fetchContent(KbDocMeta meta) throws KbSourceException {
                return delegate.fetchContent(meta);
            }
        };
    }

    @Test
    void redisUnavailableFallsBackToDelegate() throws Exception {
        KbCacheStore broken = new KbCacheStore() {
            @Override
            public Map<String, String> hashGetAll(String key) {
                throw new KbCacheException("redis down");
            }

            @Override
            public void hashPutAll(String key, Map<String, String> values) {
                throw new KbCacheException("redis down");
            }

			@Override
			public boolean hashDeleteFieldIfValueAtMost(String key, String field, long generation) {
				throw new KbCacheException("redis down");
			}

			@Override
			public boolean deleteDocIfGenerationAtMost(String key, String field, long generation) {
				throw new KbCacheException("redis down");
			}
			@Override
			public void hashPutFields(String key, Map<String, String> values) {
				throw new KbCacheException("redis down");
			}

			@Override
			public boolean hashPutFieldIfPresent(String key, String field, String value) {
				throw new KbCacheException("redis down");
			}

			@Override
			public long hashDeleteFields(String key, java.util.Collection<String> fields) {
				throw new KbCacheException("redis down");
			}

			@Override
			public void touch(String key) {
				throw new KbCacheException("redis down");
			}




			@Override
			public boolean compareAndExpire(String key, String value, java.time.Duration ttl) {
				throw new KbCacheException("redis down");
			}

			@Override
			public boolean hashPutAllIfLeaseAndGenerationAtMost(String key, String lockKey, String token,
					String generationField, long generation, Map<String, String> values) {
				throw new KbCacheException("redis down");
			}


			@Override
			public boolean hashPutAllIfGenerationAtMost(String key, String generationField, long generation,
					Map<String, String> values) {
				throw new KbCacheException("redis down");
			}

			@Override
			public void hashPutFieldsIfValueAtMost(String key, Map<String, String> values) {
				throw new KbCacheException("redis down");
			}

			@Override
			public boolean hashPutFieldIfPresentAndValueAtMost(String key, String field, long generation) {
				throw new KbCacheException("redis down");
			}

            @Override
            public String get(String key) {
                throw new KbCacheException("redis down");
            }

            @Override
            public long incr(String key) {
                throw new KbCacheException("redis down");
            }

            @Override
            public boolean setIfAbsent(String key, String value, Duration ttl) {
                throw new KbCacheException("redis down");
            }

            @Override
            public boolean compareAndDelete(String key, String value) {
                throw new KbCacheException("redis down");
            }

            @Override
            public void delete(String key) {
                throw new KbCacheException("redis down");
            }
        };
        FakeKbSource fake = new FakeKbSource().put("doc-a", "文档A", "# 文档A\n## 定义\n降级内容。\n");
        CachedKbSource source = new CachedKbSource(fake, new KbCache(broken), KbTestSupport.mapper(),
                new AiProperties().getKb());

        KbSource.ManifestFetch fetched = source.fetchManifest(null);

        assertThat(fetched.manifest().docs()).hasSize(1);
        assertThat(source.fetchContent(fetched.manifest().docs().get(0))).contains("降级内容");
        assertThat(fake.manifestCalls.get()).isEqualTo(1);
    }
}
