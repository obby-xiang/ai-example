package com.example.configmgr.ai.kb;

import com.example.configmgr.ai.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/** Redis cache-aside 知识源：共享不可变语料版本，单机与 Cluster 使用同一套单键/同槽协议。 */
@Slf4j
public class CachedKbSource implements KbSource, AutoCloseable {

    private static final int CACHE_BATCH_SIZE = 64;

    private final KbSource delegate;

    private final KbCache cache;

    private final ObjectMapper mapper;

    private final AiProperties.Kb kb;

    private final ScheduledExecutorService leaseScheduler;

    private volatile String lastSeenVersion = "";

    private volatile Instant lastRemoteCheck = Instant.EPOCH;

    private volatile boolean cacheDownLogged;

    private volatile boolean reconcileAfterDirect;

    public CachedKbSource(KbSource delegate, KbCache cache, ObjectMapper mapper, AiProperties.Kb kb) {
        this.delegate = delegate;
        this.cache = cache;
        this.mapper = mapper;
        this.kb = kb;
        CustomizableThreadFactory threadFactory = new CustomizableThreadFactory("kb-cache-lease-");
        threadFactory.setDaemon(true);
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, threadFactory);
        scheduler.setKeepAliveTime(60L, TimeUnit.SECONDS);
        scheduler.allowCoreThreadTimeOut(true);
        scheduler.setRemoveOnCancelPolicy(true);
        this.leaseScheduler = scheduler;
    }

    @Override
    public String kind() {
        return this.delegate.kind();
    }

    @Override
    public void prepare() {
        this.delegate.prepare();
    }

    public String cachedCorpusVersion() {
        try {
            return this.cache.corpusVersion();
        }
        catch (RuntimeException e) {
            noteCacheDown(e);
            return null;
        }
    }

    @Override
    public KbSource.ManifestFetch fetchManifest(String previousEtag) throws KbSourceException {
        return fetchManifest(previousEtag, false);
    }

    @Override
    public KbSource.ManifestFetch fetchManifest(String previousEtag, boolean force) throws KbSourceException {
        return fetchManifest(previousEtag, force,
                KbSource.RefreshBudget.within(this.kb.getRefreshWaitBudget()));
    }

    @Override
    public KbSource.ManifestFetch fetchManifest(String previousEtag, boolean force, KbSource.RefreshBudget budget)
            throws KbSourceException {
        try {
            return refresh(previousEtag, force, budget);
        }
        catch (KbCacheException e) {
            noteCacheDown(e);
            KbSource.ManifestFetch direct = fetchDirect(previousEtag, force, budget);
            this.reconcileAfterDirect = true;
            return direct;
        }
    }

    private KbSource.ManifestFetch fetchDirect(String previousEtag, boolean force, KbSource.RefreshBudget budget)
            throws KbSourceException {
        KbSource.ManifestFetch remote = this.delegate.fetchManifest(previousEtag, force, budget);
        if (remote.notModified()) {
            remote = this.delegate.fetchManifest(null, force, budget);
        }
        if (remote.notModified()) {
            throw new KbSourceException("Knowledge source returned 304 without a cached manifest for validation");
        }
        return remote;
    }

    @Override
    public String fetchContent(KbDocMeta meta) throws KbSourceException {
        return fetchContent(meta, KbSource.RefreshBudget.within(this.kb.getRefreshWaitBudget()));
    }

    @Override
    public String fetchContent(KbDocMeta meta, KbSource.RefreshBudget budget) throws KbSourceException {
        Optional<KbCache.DocEntry> cached;
        try {
            cached = this.cache.doc(meta.id());
        }
        catch (KbCacheException e) {
            noteCacheDown(e);
            String content = this.delegate.fetchContent(meta, budget);
            withComputedSha(meta, content);
            this.reconcileAfterDirect = true;
            return content;
        }
        if (cached.isPresent() && isShaMatch(cached.get().sha(), meta.sha())) {
            withComputedSha(meta, cached.get().content());
            return cached.get().content();
        }

        String content = this.delegate.fetchContent(meta, budget);
        KbDocMeta effective = withComputedSha(meta, content);
        try {
            cacheDoc(effective, content, this.cache.nextGeneration());
        }
        catch (RuntimeException e) {
            noteCacheDown(e);
            this.reconcileAfterDirect = true;
        }
        return content;
    }

    private KbSource.ManifestFetch refresh(String previousEtag, boolean force, KbSource.RefreshBudget budget)
            throws KbSourceException {
        budget.check();
        Optional<KbCache.ManifestEntry> cached = this.cache.manifest();
        if (cached.isEmpty()) {
            return coldStart(previousEtag, budget);
        }
        String version = this.cache.corpusVersion();
        if (!this.reconcileAfterDirect && version != null && !version.equals(this.lastSeenVersion)) {
            return fromCache("Cached manifest is unreadable after a version change");
        }
        if (!this.reconcileAfterDirect && isHttp() && !force
                && Duration.between(this.lastRemoteCheck, Instant.now()).compareTo(jitteredRefreshInterval()) < 0) {
            return fromCache("Cached manifest is unreadable");
        }

        Lease lease = acquireLease();
        if (lease == null) {
            if (this.reconcileAfterDirect) {
                throw new KbSourceException("KB refresh lease is unavailable until direct-source fallback is reconciled");
            }
            return fromCache("Cached manifest is unreadable");
        }
        try (lease) {
            return refreshWithLease(previousEtag, lease, budget);
        }
    }

    private KbSource.ManifestFetch refreshWithLease(String previousEtag, Lease lease, KbSource.RefreshBudget budget)
            throws KbSourceException {
        KbSource.ManifestFetch remote;
        try {
            remote = this.delegate.fetchManifest(previousEtag, true, budget);
        }
        catch (KbSourceException e) {
            if (!isHttp() || this.reconcileAfterDirect) {
                throw e;
            }
            log.warn("KB source refresh failed (stale-if-error; serving stale cache)", e);
            return fromCache("Cached manifest is unreadable");
        }
        if (remote.notModified() && isCachedManifestMatch(previousEtag)) {
            this.lastRemoteCheck = Instant.now();
            reconcileCachedRemoval(lease, budget);
            touchCachedTtl(lease, budget);
            KbSource.ManifestFetch cachedResult = fromCache("Cached manifest is unreadable");
            this.reconcileAfterDirect = false;
            return cachedResult;
        }
        if (remote.notModified()) {
            remote = this.delegate.fetchManifest(null, true, budget);
        }
        this.lastRemoteCheck = Instant.now();
        if (remote.notModified() || remote.manifest() == null) {
            throw new KbSourceException("Knowledge source still returned no manifest after an unconditional reload");
        }
        try {
            return syncToCache(remote.manifest(), remote.etag(), remote.contents(), lease, budget);
        }
        catch (KbCacheException e) {
            throw new KbSourceException("Failed to publish KB cache; keeping the previous index", e);
        }
    }

    private KbSource.ManifestFetch coldStart(String previousEtag, KbSource.RefreshBudget budget)
            throws KbSourceException {
        Lease lease = acquireLease();
        if (lease != null) {
            try (lease) {
                KbSource.ManifestFetch remote = this.delegate.fetchManifest(previousEtag, true, budget);
                if (remote.notModified()) {
                    remote = this.delegate.fetchManifest(null, true, budget);
                }
                if (remote.notModified() || remote.manifest() == null) {
                    throw new KbSourceException("Knowledge cache is empty and the source still returned no manifest after an unconditional reload");
                }
                this.lastRemoteCheck = Instant.now();
                try {
                    return syncToCache(remote.manifest(), remote.etag(), remote.contents(), lease, budget);
                }
                catch (KbCacheException e) {
                    throw new KbSourceException("Knowledge cache is empty and cache publication failed", e);
                }
            }
        }
        if (this.reconcileAfterDirect) {
            throw new KbSourceException("KB refresh lease is unavailable for an empty cache until direct-source fallback is reconciled");
        }
        for (int i = 0; i < 30; i++) {
            budget.check();
            if (this.cache.manifest().isPresent()) {
                return fromCache("Cached manifest is unreadable");
            }
            sleep(100L, budget);
        }
        throw new KbSourceException("Knowledge cache is empty and another instance is refreshing; retry later");
    }

    private boolean isCachedManifestMatch(String previousEtag) {
        if (isBlank(previousEtag)) {
            return false;
        }
        return this.cache.manifest()
                .map(entry -> previousEtag.equals(entry.etag()))
                .orElse(false);
    }

    private Lease acquireLease() {
        String token = UUID.randomUUID().toString();
        Duration ttl = this.kb.getCache().getLockTtl();
        if (!this.cache.tryLock(token, ttl)) {
            return null;
        }
        try {
            return new Lease(token, ttl);
        }
        catch (RuntimeException e) {
            try {
                this.cache.unlock(token);
            }
            catch (RuntimeException ignored) {
                // 锁按 TTL 过期。
            }
            throw e;
        }
    }

    private void cacheDoc(KbDocMeta meta, String content, long generation) {
        this.cache.putDocId(meta.id(), generation);
        this.cache.putDoc(meta.id(), new KbCache.DocEntry(content, meta.sha(), meta.sha(), meta.updatedAt()), generation);
    }

    private KbSource.ManifestFetch syncToCache(KbManifest sourceManifest, String sourceEtag,
            Map<String, String> sourceContents, Lease lease, KbSource.RefreshBudget budget) throws KbSourceException {
        sourceManifest.validate();
        lease.requireValid(budget);
        long generation = this.cache.nextGeneration();
        Optional<KbCache.ManifestEntry> previous = this.cache.manifest();
        Set<String> current = sourceManifest.docs().stream()
                .map(KbDocMeta::id)
                .collect(Collectors.toUnmodifiableSet());
        Set<String> known = new LinkedHashSet<>(this.cache.docIds());
        known.addAll(docIdsOf(previous));
        known.addAll(current);
        putDocIdsBatched(known, generation, lease, budget);

        SyncRound round = collectRound(sourceManifest, sourceContents, generation, lease, budget);
        writeRound(round, generation, lease, budget);
        return publishRound(sourceManifest, sourceEtag, round, known, current, generation, lease, budget);
    }

    private SyncRound collectRound(KbManifest sourceManifest, Map<String, String> sourceContents, long generation,
            Lease lease, KbSource.RefreshBudget budget) throws KbSourceException {
        List<KbDocMeta> effectiveDocs = new ArrayList<>(sourceManifest.docs().size());
        List<String> fetchedContents = new ArrayList<>(sourceManifest.docs().size());
        Map<String, String> roundContents = new java.util.LinkedHashMap<>();
        int sinceLeaseCheck = 0;
        for (KbDocMeta declared : sourceManifest.docs()) {
            budget.check();
            if (++sinceLeaseCheck >= CACHE_BATCH_SIZE) {
                lease.requireValid(budget);
                sinceLeaseCheck = 0;
            }
            Optional<KbCache.DocEntry> existing = this.cache.doc(declared.id());
            if (existing.isPresent() && !isBlank(declared.sha())
                    && isShaMatch(existing.get().sha(), declared.sha())) {
                String content = existing.get().content();
                effectiveDocs.add(withComputedSha(declared, content));
                fetchedContents.add(null);
                roundContents.put(declared.id(), content);
            }
            else {
                String content = sourceContents.get(declared.id());
                if (content == null) {
                    content = this.delegate.fetchContent(declared, budget);
                }
                effectiveDocs.add(withComputedSha(declared, content));
                fetchedContents.add(content);
                roundContents.put(declared.id(), content);
            }
        }
        return new SyncRound(effectiveDocs, fetchedContents, roundContents);
    }

    private void writeRound(SyncRound round, long generation, Lease lease, KbSource.RefreshBudget budget)
            throws KbSourceException {
        int sinceLeaseCheck = 0;
        for (int i = 0; i < round.effectiveDocs().size(); i++) {
            budget.check();
            if (++sinceLeaseCheck >= CACHE_BATCH_SIZE) {
                lease.requireValid(budget);
                sinceLeaseCheck = 0;
            }
            KbDocMeta effective = round.effectiveDocs().get(i);
            String content = round.fetchedContents().get(i);
            if (content == null) {
                if (!this.cache.markDocGeneration(effective.id(), generation)) {
                    throw new KbSourceException("Cached KB content was reclaimed before publication; keeping the previous manifest and retrying: " + effective.id());
                }
            }
            else {
                cacheDoc(effective, content, generation);
            }
        }
    }

    private KbSource.ManifestFetch publishRound(KbManifest sourceManifest, String sourceEtag, SyncRound round,
            Set<String> known, Set<String> current, long generation, Lease lease, KbSource.RefreshBudget budget)
            throws KbSourceException {
        List<KbDocMeta> canonicalDocs = round.effectiveDocs().stream()
                .sorted(java.util.Comparator.comparing(KbDocMeta::id))
                .toList();
        KbManifest effectiveManifest = new KbManifest(sourceManifest.version(), "", canonicalDocs);
        String json;
        try {
            json = this.mapper.writeValueAsString(effectiveManifest);
        }
        catch (Exception e) {
            throw new KbSourceException("Failed to serialize knowledge manifest", e);
        }
        String etag = isBlank(sourceEtag) ? KbHashing.sha256Hex(json) : sourceEtag;
        String corpusVersion = "cv3:" + KbHashing.sha256Hex(json);
        lease.requireValid(budget);
        if (!this.cache.publishManifest(new KbCache.ManifestEntry(etag, corpusVersion, json), generation,
                lease.token)) {
            throw new KbSourceException("KB refresh lease or generation expired; corpus publication is forbidden");
        }
        this.lastSeenVersion = corpusVersion;
        pruneRemovedDocs(known, current, generation, lease, budget);
        this.reconcileAfterDirect = false;
        return KbSource.ManifestFetch.modified(effectiveManifest, etag, corpusVersion, round.roundContents());
    }

    private record SyncRound(List<KbDocMeta> effectiveDocs, List<String> fetchedContents,
            Map<String, String> roundContents) {
    }

    private void putDocIdsBatched(Set<String> ids, long generation, Lease lease, KbSource.RefreshBudget budget)
            throws KbSourceException {
        List<String> batch = new ArrayList<>(CACHE_BATCH_SIZE);
        for (String id : ids) {
            budget.check();
            batch.add(id);
            if (batch.size() >= CACHE_BATCH_SIZE) {
                lease.requireValid(budget);
                this.cache.putDocIds(batch, generation);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            lease.requireValid(budget);
            this.cache.putDocIds(batch, generation);
        }
    }

    private Set<String> docIdsOf(Optional<KbCache.ManifestEntry> entry) {
        if (entry.isEmpty()) {
            return Set.of();
        }
        try {
            return KbManifest.parse(entry.get().json().getBytes(StandardCharsets.UTF_8), this.mapper)
                    .docs().stream()
                    .map(KbDocMeta::id)
                    .collect(Collectors.toUnmodifiableSet());
        }
        catch (Exception e) {
            log.warn("Failed to parse previous KB manifest; retirement uses the global doc index only", e);
            return Set.of();
        }
    }

    private void touchCachedTtl(Lease lease, KbSource.RefreshBudget budget) throws KbSourceException {
        Optional<KbCache.ManifestEntry> cached = this.cache.manifest();
        if (cached.isEmpty()) {
            return;
        }
        KbManifest manifest = KbManifest.parse(cached.get().json().getBytes(StandardCharsets.UTF_8), this.mapper);
        for (int from = 0; from < manifest.docs().size(); from += CACHE_BATCH_SIZE) {
            lease.requireValid(budget);
            int to = Math.min(manifest.docs().size(), from + CACHE_BATCH_SIZE);
            for (KbDocMeta doc : manifest.docs().subList(from, to)) {
                this.cache.touchDoc(doc.id());
            }
        }
        lease.requireValid(budget);
        this.cache.touchGeneration();
        this.cache.touchDocIds();
        this.cache.touchManifest();
    }

    private void reconcileCachedRemoval(Lease lease, KbSource.RefreshBudget budget) {
        try {
            long generation = this.cache.generation();
            Optional<KbCache.ManifestEntry> cached = this.cache.manifest();
            if (cached.isEmpty()) {
                return;
            }
            Set<String> current = KbManifest.parse(cached.get().json().getBytes(StandardCharsets.UTF_8), this.mapper)
                    .docs().stream().map(KbDocMeta::id).collect(Collectors.toUnmodifiableSet());
            Set<String> indexed = new LinkedHashSet<>(this.cache.docIds());
            Set<String> toIndex = new LinkedHashSet<>(current);
            toIndex.removeAll(indexed);
            putDocIdsBatched(toIndex, generation, lease, budget);
            Set<String> known = new LinkedHashSet<>(indexed);
            known.addAll(current);
            pruneRemovedDocs(known, current, generation, lease, budget);
        }
        catch (Exception e) {
            // 退休索引协调失败时留待下一轮。
        }
    }

    private void pruneRemovedDocs(Set<String> known, Set<String> current, long generation, Lease lease,
            KbSource.RefreshBudget budget) {
        int visited = 0;
        for (String docId : known) {
            if (current.contains(docId)) {
                continue;
            }
            if (budget.expired() || !lease.valid.get()) {
                return;
            }
            if (++visited % CACHE_BATCH_SIZE == 0) {
                try {
                    lease.requireValid(budget);
                }
                catch (Exception e) {
                    return;
                }
            }
            final boolean deleted;
            try {
                deleted = this.cache.deleteDoc(docId, generation);
            }
            catch (RuntimeException e) {
                log.warn("KB cache failed to delete retired doc (index entry kept) docId={}", docId, e);
                continue;
            }
            if (!deleted) {
                continue;
            }
            try {
                this.cache.removeDocId(docId, generation);
            }
            catch (RuntimeException e) {
                log.warn("Retired doc deleted but its index entry remains docId={}", docId, e);
            }
        }
    }

    private KbSource.ManifestFetch fromCache(String emptyMessage) throws KbSourceException {
        KbCache.ManifestEntry entry = this.cache.manifest().orElse(null);
        if (entry == null || isBlank(entry.version())) {
            throw new KbSourceException(emptyMessage);
        }
        KbManifest manifest;
        try {
            manifest = KbManifest.parse(entry.json().getBytes(StandardCharsets.UTF_8), this.mapper);
        }
        catch (KbSourceException e) {
            throw new KbSourceException("Failed to parse cached manifest", e);
        }
        manifest.validate();
        this.lastSeenVersion = entry.version();
        return KbSource.ManifestFetch.modified(manifest, entry.etag(), entry.version());
    }

    private static KbDocMeta withComputedSha(KbDocMeta meta, String content) throws KbSourceException {
        String actualSha = KbHashing.sha256Text(content);
        if (!isBlank(meta.sha()) && !meta.sha().equalsIgnoreCase(actualSha)) {
            throw new KbSourceException("KB content SHA-256 (BOM stripped, LF normalized) does not match manifest: " + meta.id());
        }
        return isBlank(meta.sha()) ? meta.withSha(actualSha) : meta;
    }

    private static boolean isShaMatch(String cachedSha, String manifestSha) {
        if (isBlank(manifestSha)) {
            return !isBlank(cachedSha);
        }
        return cachedSha != null && cachedSha.equalsIgnoreCase(manifestSha);
    }

    private boolean isHttp() {
        return !"local".equalsIgnoreCase(this.delegate.kind());
    }

    private static boolean isBlank(String value) {
        return StringUtils.isBlank(value);
    }

    private Duration jitteredRefreshInterval() {
        long base = Math.max(1L, this.kb.getRefreshInterval().toMillis());
        double factor = 0.9 + ThreadLocalRandom.current().nextDouble() * 0.2;
        return Duration.ofMillis((long) (base * factor));
    }

    private void noteCacheDown(RuntimeException e) {
        if (!this.cacheDownLogged) {
            this.cacheDownLogged = true;
            log.warn("KB cache unavailable; degrading to direct source access", e);
        }
    }

    private static void sleep(long millis, KbSource.RefreshBudget budget) throws KbSourceException {
        long bounded = Math.min(millis, Math.max(1L, budget.remaining().toMillis()));
        try {
            Thread.sleep(bounded);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KbSourceException("KB refresh wait interrupted", e);
        }
    }

    public Set<String> cachedDocIds() {
        return this.cache.manifest().map(entry -> {
            try {
                return KbManifest.parse(entry.json().getBytes(StandardCharsets.UTF_8), this.mapper)
                        .docs().stream().map(KbDocMeta::id).collect(Collectors.toSet());
            }
            catch (Exception e) {
                return Set.<String>of();
            }
        }).orElse(Set.of());
    }

    public Optional<Map<String, String>> rawDoc(String docId) {
        return this.cache.doc(docId).map(doc -> Map.of(
                "content", doc.content(), "sha", nullSafe(doc.sha()), "updatedAt", nullSafe(doc.updatedAt())));
    }

    private static String nullSafe(String value) {
        return StringUtils.defaultString(value);
    }

    @Override
    public void close() {
        this.leaseScheduler.shutdownNow();
    }

    private final class Lease implements AutoCloseable {
        private final String token;
        private final Duration ttl;
        private final AtomicBoolean valid = new AtomicBoolean(true);
        private final ScheduledFuture<?> renewal;

        private Lease(String token, Duration ttl) {
            this.token = token;
            this.ttl = ttl;
            long interval = Math.max(1L, ttl.toMillis() / 3L);
            this.renewal = CachedKbSource.this.leaseScheduler.scheduleWithFixedDelay(this::renew,
                    interval, interval, TimeUnit.MILLISECONDS);
        }

        private void renew() {
            if (!this.valid.get()) {
                return;
            }
            try {
                if (!CachedKbSource.this.cache.renewLock(this.token, this.ttl)) {
                    this.valid.set(false);
                }
            }
            catch (RuntimeException e) {
                this.valid.set(false);
            }
        }

        private void requireValid(KbSource.RefreshBudget budget) throws KbSourceException {
            budget.check();
            if (!this.valid.get() || !CachedKbSource.this.cache.ownsLock(this.token)) {
                this.valid.set(false);
                throw new KbSourceException("KB refresh lease expired; further publication is forbidden");
            }
        }

        @Override
        public void close() {
            this.renewal.cancel(false);
            this.valid.set(false);
            try {
                CachedKbSource.this.cache.unlock(this.token);
            }
            catch (RuntimeException e) {
                // 租约已失效，释放失败由服务端 TTL 收口。
            }
        }
    }
}
