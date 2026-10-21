package com.example.configmgr.ai.kb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 用例假知识源：可控 manifest/正文、调用计数、故障注入、延迟（单飞/版本传播/stale-if-error）。
 */
final class FakeKbSource implements KbSource {

    final Map<String, KbDocMeta> metas = new LinkedHashMap<>();

    final Map<String, String> contents = new LinkedHashMap<>();

    final AtomicInteger manifestCalls = new AtomicInteger();

    final AtomicInteger contentCalls = new AtomicInteger();

    volatile String etag = "etag-v1";

    volatile String version = "v1";

    volatile boolean failManifest;

    volatile boolean failContent;

    volatile long delayMillis;

    FakeKbSource put(String id, String title, String content) {
        String sha = KbHashing.sha256Hex(content);
        this.metas.put(id, new KbDocMeta(id, title, title + " 摘要", List.of("示例"), List.of(),
                sha, "2026-10-01", id + ".md", List.of(), List.of()));
        this.contents.put(id, content);
        return this;
    }

    @Override
    public String kind() {
        return "http";
    }

    @Override
    public ManifestFetch fetchManifest(String previousEtag) throws KbSourceException {
        return fetchManifest(previousEtag, false);
    }

    @Override
    public ManifestFetch fetchManifest(String previousEtag, boolean force) throws KbSourceException {
        this.manifestCalls.incrementAndGet();
        sleep();
        if (this.failManifest) {
            throw new KbSourceException("模拟远端 500");
        }
        if (this.etag != null && this.etag.equals(previousEtag)) {
            return ManifestFetch.notModified(this.etag);
        }
        return ManifestFetch.modified(new KbManifest(this.version, "2026-10-21",
                new ArrayList<>(this.metas.values())), this.etag);
    }

    @Override
    public String fetchContent(KbDocMeta meta) throws KbSourceException {
        this.contentCalls.incrementAndGet();
        sleep();
        if (this.failContent) {
            throw new KbSourceException("模拟正文 500");
        }
        String content = this.contents.get(meta.id());
        if (content == null) {
            throw new KbSourceException("文档不存在：" + meta.id());
        }
        return content;
    }

    private void sleep() {
        if (this.delayMillis > 0) {
            try {
                Thread.sleep(this.delayMillis);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
