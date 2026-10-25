package com.example.configmgr.ai.kb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;

/**
 * 知识库 manifest（{@code manifest.json}）：一次刷新里"有哪些文档、各自版本是什么"的唯一事实源。
 *
 * <p>HTTP 源用它做增量（按 {@code sha}）与 ETag 短路；local 源用它承载标题/别名/术语等元数据。
 * 解析失败必须抛 {@link KbSourceException}（由 {@code KbService} fail-open：保留旧索引 + WARN），
 * <b>不能</b>当作"空知识库"——那会让一次网络抖动清空线上检索面。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KbManifest(
        String version,
        @JsonProperty("generated_at") String generatedAt,
        List<KbDocMeta> docs) {

    public KbManifest {
        version = version == null ? "" : version;
        generatedAt = generatedAt == null ? "" : generatedAt;
        docs = docs == null ? List.of() : List.copyOf(docs);
    }

    public static KbManifest empty() {
        return new KbManifest("", "", List.of());
    }

    /** 解析 manifest 原文；任何结构问题（坏 JSON / docs 为空 / id 重复）都视为源不可用。 */
    public static KbManifest parse(byte[] json, ObjectMapper mapper) throws KbSourceException {
        try {
            KbManifest manifest = mapper.readValue(json, KbManifest.class);
            if (manifest == null) {
                throw new KbSourceException("manifest.json 解析为 null（文件内容不是对象）");
            }
            return manifest;
        }
        catch (IOException e) {
            throw new KbSourceException("manifest.json 解析失败：" + e.getMessage(), e);
        }
    }

    /** 校验 manifest 自身的一致性（docs 非空、id 非空且唯一、标题非空）；返回可行动的中文原因。 */
    public void validate() throws KbSourceException {
        if (docs.isEmpty()) {
            // 空清单不是"空知识库"：一次发布/同步故障就会把线上检索面清空，
            // 调用方按源不可用处理（保留旧索引 + WARN），就绪态不得成立。
            throw new KbSourceException("manifest.json 里没有任何文档（docs 为空）：按源不可用处理，保留旧索引");
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (KbDocMeta doc : docs) {
            if (doc == null || doc.id() == null || doc.id().isBlank()) {
                throw new KbSourceException("manifest.json 存在缺少 id 的文档条目");
            }
            if (!seen.add(doc.id())) {
                throw new KbSourceException("manifest.json 的文档 id 重复：" + doc.id());
            }
            if (doc.title() == null || doc.title().isBlank()) {
                throw new KbSourceException("manifest.json 的文档 " + doc.id() + " 缺少 title");
            }
        }
    }
}
