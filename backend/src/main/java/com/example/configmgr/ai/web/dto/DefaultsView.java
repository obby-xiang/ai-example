package com.example.configmgr.ai.web.dto;

/**
 * {@code GET/PUT /api/ai/settings/defaults} 的视图（设计稿 §3.5）。
 *
 * <p>
 * {@code null} = 该项未设置（读侧按 {@code ai_model} 默认 / env 缺省回落）。写侧是<b>整体替换</b>
 * 语义：把表单原样提交，{@code null} 即清掉该项。
 *
 * @param version {@code config.version}（诊断用：改完能确认版本真的动了）
 */
public record DefaultsView(String modelId, Double temperature, Double topP, Integer maxTokens,
        Boolean allowUserParams, long version) {
}
