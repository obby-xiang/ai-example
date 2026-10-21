package com.example.configmgr.ai.kb;

/**
 * 知识源抽象（追加 2）：{@code KbService} 只依赖本接口，生产可切 HTTP 源而不动检索/工具/提示词。
 *
 * <h2>两个实现的契约差异</h2>
 * <ul>
 * <li>{@link LocalDirKbSource}（默认）：目录里 {@code manifest.json} + {@code *.md}；每次刷新按文件
 * 内容<b>实算 sha</b>（manifest 声明值过期只告警）；{@code etag} 恒 null（不需要整份短路）；</li>
 * <li>{@link HttpKbSource}（生产）：先拉 {@code manifest.json}（带 {@code If-None-Match}），
 * 304 即"整份未变"；按 manifest 的 sha 决定哪些文档要重新拉正文。</li>
 * </ul>
 *
 * <p>失败语义统一为抛 {@link KbSourceException}：<b>由调用方 fail-open</b>（保留旧索引 + WARN），
 * 源本身不清空、不返回空 manifest。
 */
public interface KbSource {

    /** 源类型（诊断面展示：{@code local} / {@code http}）。 */
    String kind();

    /**
     * 拉取 manifest。
     *
     * @param previousEtag 上一次成功拉取拿到的 ETag（可空）；HTTP 源据此发 {@code If-None-Match}
     * @return 拉取结果（{@code notModified=true} 表示可复用旧索引）
     */
    ManifestFetch fetchManifest(String previousEtag) throws KbSourceException;

    /**
     * 带"强制回源"语义的重载（默认忽略 force）：只有 {@link CachedKbSource} 需要它跳过刷新节流；
     * 直连源（local/http）本来就是"调用即回源"，无须区分。
     */
    default ManifestFetch fetchManifest(String previousEtag, boolean force) throws KbSourceException {
        return fetchManifest(previousEtag);
    }

    /** 拉取单篇正文（UTF-8 文本）。 */
    String fetchContent(KbDocMeta meta) throws KbSourceException;

    /**
     * manifest 拉取结果。
     *
     * @param manifest    解析后的 manifest（{@code notModified=true} 时可为 null）
     * @param etag        本次响应的 ETag（可空）
     * @param notModified 304 / 整份未变
     */
    record ManifestFetch(KbManifest manifest, String etag, boolean notModified) {

        public static ManifestFetch modified(KbManifest manifest, String etag) {
            return new ManifestFetch(manifest, etag, false);
        }

        public static ManifestFetch notModified(String etag) {
            return new ManifestFetch(null, etag, true);
        }
    }
}
