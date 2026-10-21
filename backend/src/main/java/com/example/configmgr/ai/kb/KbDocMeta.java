package com.example.configmgr.ai.kb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * manifest 里的一篇知识文档（{@code manifest.json} 的 {@code docs[]} 元素）。
 *
 * <p>字段口径（定稿契约）：{@code id}（可读 slug）/ {@code title} / {@code tags} / {@code aliases} /
 * {@code sha} / {@code updated_at}。另有两个<b>可选</b>字段用于检索体验：
 * {@code summary}（提示词目录摘要）与 {@code terms}（{@code kb_lookup_term} 的预制答案）。
 *
 * <p>{@code sha} 是<b>增量重建的唯一判据</b>：local 源每次扫描都按文件内容重算并与声明值对账
 * （声明值过期只告警、以实算为准）；HTTP 源以 manifest 声明的 sha 为准（服务端负责算）。
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
        String id,
        String title,
        String summary,
        List<String> tags,
        List<String> aliases,
        String sha,
        @JsonProperty("updated_at") String updatedAt,
        String file,
        List<KbTerm> terms,
        @JsonProperty("aliases_en") List<String> aliasesEn) {

    public KbDocMeta {
        tags = tags == null ? List.of() : List.copyOf(tags);
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        terms = terms == null ? List.of() : List.copyOf(terms);
        aliasesEn = aliasesEn == null ? List.of() : List.copyOf(aliasesEn);
        summary = summary == null ? "" : summary;
    }

    /** 文档在源侧的相对路径（缺省 {@code <id>.md}）。 */
    public String effectiveFile() {
        return file == null || file.isBlank() ? id + ".md" : file;
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
        return summary == null || summary.isBlank() ? title : summary;
    }
}
