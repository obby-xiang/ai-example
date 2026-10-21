package com.example.configmgr.ai.kb;

import java.util.List;

/**
 * 一篇知识文档的章节（由 {@code MarkdownChunker} 按标题解析，供 {@code kb_read_doc} 的目录与定位用）。
 *
 * @param level       标题级别（1–6；2 级起才是章节）
 * @param title       标题文本
 * @param path        完整章节路径（{@code 文档标题 > 二级 > 三级 …}）
 * @param startOffset 章节起点（标题行的第一个字符，含标题）
 * @param bodyOffset  章节正文起点（标题行之后）
 * @param endOffset   章节终点（下一个同级或更高级标题的起点；末节 = 文档长度）
 */
public record KbSection(int level, String title, String path, int startOffset, int bodyOffset, int endOffset) {

    /** 章节正文（不含标题行）。 */
    public String body(String content) {
        int from = Math.min(Math.max(bodyOffset, 0), content.length());
        int to = Math.min(Math.max(endOffset, from), content.length());
        return content.substring(from, to);
    }

    /** 章节全文（含标题行）。 */
    public String fullText(String content) {
        int from = Math.min(Math.max(startOffset, 0), content.length());
        int to = Math.min(Math.max(endOffset, from), content.length());
        return content.substring(from, to);
    }

    /** 目录行（二级缩进 0、三级起缩进）。 */
    public String tocLine() {
        String indent = "  ".repeat(Math.max(0, level - 2));
        return indent + "- [§" + path + "] offset=" + bodyOffset;
    }

    public static List<KbSection> empty() {
        return List.of();
    }
}
