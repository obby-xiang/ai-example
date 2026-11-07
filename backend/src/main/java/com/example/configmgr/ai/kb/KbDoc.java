package com.example.configmgr.ai.kb;

import org.apache.commons.lang3.ObjectUtils;

import java.util.List;

/**
 * 一篇已解析的知识文档：元数据 + 原文 + 章节 + 切块。
 *
 * <p><b>chunk 复用是增量的载体</b>：内容 sha 未变时，{@code KbService} 直接复用同一个 {@link KbDoc}
 * 实例（{@code withMeta} 只换元数据，chunks/sections 引用不变）—— 这就是"sha 变才重建该 doc"的
 * 可观测形态（用例按实例引用断言）。
 */
public record KbDoc(KbDocMeta meta, String content, List<KbSection> sections, List<KbChunk> chunks) {

    public KbDoc {
        sections = List.copyOf(ObjectUtils.defaultIfNull(sections, List.of()));
        chunks = List.copyOf(ObjectUtils.defaultIfNull(chunks, List.of()));
    }

    /** 只换元数据（标题/别名/摘要），正文与切块原样复用。 */
    public KbDoc withMeta(KbDocMeta newMeta) {
        return new KbDoc(newMeta, content, sections, chunks);
    }
}
