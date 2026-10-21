package com.example.configmgr.ai.kb;

import java.util.List;

/**
 * {@code kb_read_doc} 的结果。
 *
 * <p><b>分页契约</b>：{@code offset} 是"相对被读范围"的字符偏移 —— 省略 {@code section} 时相对正文，
 * 指定 {@code section} 时相对该章节正文（不含标题行）。{@code truncated=true} 时
 * {@code nextOffset} 是下一页的 offset；{@code truncated=false} 时 {@code nextOffset=-1}。
 * 首屏（{@code offset==0 && section==null}）额外给目录（TOC），正文只取默认长度（前 1200 字）。
 *
 * @param state      状态
 * @param docId      文档 id
 * @param title      标题
 * @param section    本次读取的章节路径（整篇读时为 null）
 * @param text       正文片段
 * @param toc        目录行（仅首屏非空）
 * @param truncated  是否还有下一页
 * @param nextOffset 下一页 offset（无下一页 = -1）
 * @param candidates 候选（id/section 不存在时给出，不抛异常）
 * @param error      可行动错误消息（不含堆栈）
 */
public record KbReadResult(State state, String docId, String title, String section, String text,
        List<String> toc, boolean truncated, int nextOffset, List<String> candidates, String error) {

    public enum State {
        OK, DOC_NOT_FOUND, SECTION_NOT_FOUND, NOT_READY, DISABLED, ERROR
    }

    public KbReadResult {
        toc = toc == null ? List.of() : List.copyOf(toc);
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        text = text == null ? "" : text;
        error = error == null ? "" : error;
    }

    public static KbReadResult ok(String docId, String title, String section, String text,
            List<String> toc, boolean truncated, int nextOffset) {
        return new KbReadResult(State.OK, docId, title, section, text, toc, truncated, nextOffset,
                List.of(), "");
    }

    public static KbReadResult docNotFound(String docId, List<String> candidates) {
        return new KbReadResult(State.DOC_NOT_FOUND, docId, "", null, "", List.of(), false, -1,
                candidates, "");
    }

    public static KbReadResult sectionNotFound(String docId, String title, String section,
            List<String> candidates) {
        return new KbReadResult(State.SECTION_NOT_FOUND, docId, title, section, "", List.of(), false, -1,
                candidates, "");
    }

    public static KbReadResult notReady() {
        return new KbReadResult(State.NOT_READY, null, "", null, "", List.of(), false, -1, List.of(), "");
    }

    public static KbReadResult disabled() {
        return new KbReadResult(State.DISABLED, null, "", null, "", List.of(), false, -1, List.of(), "");
    }

    public static KbReadResult error(String message) {
        return new KbReadResult(State.ERROR, null, "", null, "", List.of(), false, -1, List.of(), message);
    }
}
