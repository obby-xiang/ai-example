package com.example.configmgr.ai.kb;

/**
 * 索引切块（检索与引用的最小单位）。
 *
 * <p>{@code body} 是不带路径前缀的原文片段（摘要/引用用）；{@code indexText} 是<b>实际入索引</b>的
 * 文本 = {@code 章节路径 + "\n" + body}（定稿要求"标题/章节路径前缀"）—— 这样同一段正文在
 * 不同章节下不会互相冒充，模型看到的 {@code section} 也与块的真实归属一致。
 *
 * @param docId       所属文档 id
 * @param sectionPath 章节路径（{@code 文档标题 > 二级 > 三级}）
 * @param ordinal     文档内块序号（0 起）
 * @param body        原文片段（含所在小节标题行；不含路径前缀）
 * @param startOffset 在文档正文中的起点
 * @param endOffset   在文档正文中的终点
 * @param indexText   入索引文本（路径前缀 + body）
 */
public record KbChunk(String docId, String sectionPath, int ordinal, String body,
        int startOffset, int endOffset, String indexText) {
}
