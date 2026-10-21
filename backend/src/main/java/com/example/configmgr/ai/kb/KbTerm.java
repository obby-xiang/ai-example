package com.example.configmgr.ai.kb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * manifest 里的预制术语解释（{@code kb_lookup_term} 的答案源）。
 *
 * <p><b>只做精确/别名命中</b>：{@code name} 与 {@code aliases} 归一化后逐字比较，
 * 未命中就如实回 {@code found=false}，<b>禁止</b>静默模糊兜底（那会把"没查到"伪装成"查到了"，
 * 模型会据此编造条款）。模糊召回是 {@code kb_search} 的职责，两者不互相顶替。
 *
 * @param name    术语名（正式叫法）
 * @param aliases 别名 / 口头叫法
 * @param section 答案所在章节（可空 = 文档级）
 * @param answer  预制答案（纯文本；渲染时同样过定界与安全剥离）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KbTerm(String name, List<String> aliases, String section, String answer) {

    public KbTerm {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        answer = answer == null ? "" : answer;
    }
}
