package com.example.configmgr.ai.kb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * manifest 里的一篇知识文档（{@code manifest.json} 的 {@code docs[]} 元素）。
 *
 * <p>字段口径（定稿契约）：{@code id}（可读 slug）/ {@code title} / {@code tags} / {@code aliases} /
 * {@code sha} / {@code updated_at}。另有两个<b>可选</b>字段用于检索体验：
 * {@code summary}（提示词目录摘要）与 {@code terms}（{@code kb_lookup_term} 的预制答案）。
 *
 * <p>{@code sha} 是<b>增量重建的唯一判据</b>：local/HTTP 都会按实际取得的正文重算并核对声明值；
 * 已有声明不符时整轮失败，缺失声明时才以实算值补齐。
 *
 * @param id        文档 id（slug，如 {@code region-tariff}）
 * @param title     标题
 * @param summary   一句话摘要（提示词目录用；可空）
 * @param tags      标签（参与召回，权重低于标题）
 * @param aliases   口头叫法 / 同义词（高权重召回；{@code kb_lookup_term} 也认）
 * @param sha       内容 sha256（十六进制；可空 = 由源实算）
 * @param updatedAt 更新时间（ISO-8601 文本；冲突裁决用"新者为准"）
 * @param file      相对路径（可空 = {@code <id>.md}）
 * @param terms     预制术语解释（可空）
 * @param aliasesEn 英文术语 / 英文叫法（可空；索引进 {@code aliases_en} 字段，权重同 aliases，
 *                  用于"英文提问命中中文语料"的桥接——单语料原则：不建翻译副本）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KbDocMeta(
        @JsonProperty("id") String id,
        @JsonProperty("title") String title,
        @JsonProperty("summary") String summary,
        @JsonProperty("tags") List<String> tags,
        @JsonProperty("aliases") List<String> aliases,
        @JsonProperty("sha") String sha,
        @JsonProperty("updated_at") String updatedAt,
        @JsonProperty("file") String file,
        @JsonProperty("terms") List<KbTerm> terms,
        @JsonProperty("aliases_en") List<String> aliasesEn) {

    public KbDocMeta {
        tags = List.copyOf(ObjectUtils.defaultIfNull(tags, List.of()));
        aliases = List.copyOf(ObjectUtils.defaultIfNull(aliases, List.of()));
        terms = List.copyOf(ObjectUtils.defaultIfNull(terms, List.of()));
        aliasesEn = List.copyOf(ObjectUtils.defaultIfNull(aliasesEn, List.of()));
        summary = StringUtils.defaultString(summary);
    }

    /** 文档在源侧的相对路径（缺省 {@code <id>.md}）。 */
    public String effectiveFile() {
        return StringUtils.isBlank(file) ? id + ".md" : file;
    }

    /** 换一份内容 sha（只换元数据，不动正文/切块）。 */
    public KbDocMeta withSha(String newSha) {
        return new KbDocMeta(id, title, summary, tags, aliases, newSha, updatedAt, file, terms, aliasesEn);
    }

    /** 换一份摘要（提示词目录用）。 */
    public KbDocMeta withSummary(String newSummary) {
        return new KbDocMeta(id, title, newSummary, tags, aliases, sha, updatedAt, file, terms, aliasesEn);
    }

    /** 摘要的展示文本：优先 summary，其次标题（目录里不出现空摘要行）。 */
    public String displaySummary() {
        return StringUtils.isBlank(summary) ? title : summary;
    }
}
