package com.example.configmgr.ai.kb;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * {@code kb_lookup_term} 的结果：<b>只做精确/别名命中</b>，未命中即 {@code found=false}。
 *
 * <p>{@code next} 给模型下一步动作（固定为"use kb_search"），不是模糊兜底结果 —— 定稿明确
 * "禁静默模糊兜底"：把近似词当成命中会把"没查到"伪装成"查到"，是最危险的编造入口。
 *
 * @param found      是否命中
 * @param term       原始输入
 * @param normalized 归一化后的输入（NFKC/小写/空白合并）
 * @param answer     预制答案
 * @param docId      来源文档 id
 * @param title      来源文档标题
 * @param section    来源章节（文档级别名命中时为空）
 * @param updatedAt  来源文档更新时间
 * @param suggestions 未命中时给的近似入口（仅提示，不是答案）
 * @param notReady   索引未就绪
 * @param disabled   知识库未启用
 * @param error      可行动错误
 */
public record KbLookupResult(@JsonProperty("found") boolean found, @JsonProperty("term") String term,
        @JsonProperty("normalized") String normalized, @JsonProperty("answer") String answer,
        @JsonProperty("docId") String docId, @JsonProperty("title") String title,
        @JsonProperty("section") String section, @JsonProperty("updatedAt") String updatedAt,
        @JsonProperty("suggestions") List<String> suggestions, @JsonProperty("notReady") boolean notReady,
        @JsonProperty("disabled") boolean disabled, @JsonProperty("error") String error) {

    public KbLookupResult {
        suggestions = List.copyOf(ObjectUtils.defaultIfNull(suggestions, List.of()));
        answer = StringUtils.defaultString(answer);
        error = StringUtils.defaultString(error);
    }

    public static KbLookupResult hit(String term, String normalized, String answer, String docId,
            String title, String section, String updatedAt) {
        return new KbLookupResult(true, term, normalized, answer, docId, title, section, updatedAt,
                List.of(), false, false, "");
    }

    public static KbLookupResult miss(String term, String normalized, List<String> suggestions) {
        return new KbLookupResult(false, term, normalized, "", null, "", null, "",
                suggestions, false, false, "");
    }

    public static KbLookupResult notReady(String term, String normalized) {
        return new KbLookupResult(false, term, normalized, "", null, "", null, "",
                List.of(), true, false, "");
    }

    public static KbLookupResult disabled(String term, String normalized) {
        return new KbLookupResult(false, term, normalized, "", null, "", null, "",
                List.of(), false, true, "");
    }

    public static KbLookupResult error(String term, String normalized, String message) {
        return new KbLookupResult(false, term, normalized, "", null, "", null, "",
                List.of(), false, false, message);
    }
}
