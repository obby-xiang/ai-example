package com.example.configmgr.ai.kb;

import java.util.List;

/**
 * {@code kb_search} 的结果（三态空结果 + fail-open 两态）。
 *
 * <p>状态语义（定稿契约，测试按字面量锁定）：
 * <ul>
 * <li>{@link State#OK}：≥3 条命中；</li>
 * <li>{@link State#LOW_CONFIDENCE}：1–2 条命中，且所有条目标 {@code match=low}
 * （"只有一两条"本身就是低置信的强信号，不能让模型当成确定答案）；</li>
 * <li>{@link State#NO_MATCH}：0 条 + 分析后的检索词 + 2–3 个近似标题（不是静默空数组）；</li>
 * <li>{@link State#ERROR}：检索异常，消息可行动、不含堆栈（fail-open：不打断整轮）；</li>
 * <li>{@link State#NOT_READY}：索引尚未建好（启动异步建索引），工具回"知识库加载中"；</li>
 * <li>{@link State#DISABLED}：{@code app.ai.kb.enabled=false}；</li>
 * <li>{@link State#INVALID_QUERY}：查询为空/全为停用字符（参数问题，由工具层转成参数异常）。</li>
 * </ul>
 */
public record KbSearchResult(State state, List<KbSearchHit> hits, List<String> searchedTerms,
        List<String> suggestions, String error) {

    public enum State {
        OK, LOW_CONFIDENCE, NO_MATCH, ERROR, NOT_READY, DISABLED, INVALID_QUERY
    }

    public KbSearchResult {
        hits = hits == null ? List.of() : List.copyOf(hits);
        searchedTerms = searchedTerms == null ? List.of() : List.copyOf(searchedTerms);
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
        error = error == null ? "" : error;
    }

    public static KbSearchResult ok(List<KbSearchHit> hits, List<String> terms) {
        return new KbSearchResult(State.OK, hits, terms, List.of(), "");
    }

    public static KbSearchResult lowConfidence(List<KbSearchHit> hits, List<String> terms) {
        return new KbSearchResult(State.LOW_CONFIDENCE, hits, terms, List.of(), "");
    }

    public static KbSearchResult noMatch(List<String> terms, List<String> suggestions) {
        return new KbSearchResult(State.NO_MATCH, List.of(), terms, suggestions, "");
    }

    public static KbSearchResult error(String message) {
        return new KbSearchResult(State.ERROR, List.of(), List.of(), List.of(), message);
    }

    public static KbSearchResult notReady() {
        return new KbSearchResult(State.NOT_READY, List.of(), List.of(), List.of(), "");
    }

    public static KbSearchResult disabled() {
        return new KbSearchResult(State.DISABLED, List.of(), List.of(), List.of(), "");
    }

    public static KbSearchResult invalid(String message) {
        return new KbSearchResult(State.INVALID_QUERY, List.of(), List.of(), List.of(), message);
    }
}
