package com.example.configmgr.ai.web.dto;

/**
 * {@code GET/PUT /api/ai/settings/defaults} 的视图（设计稿 §3.5）。
 *
 * <p>
 * {@code null} = 该项未设置（读侧按该 provider 自己的模型/env 缺省回落）。写侧是<b>整体替换</b>
 * 语义：把表单原样提交，{@code null} 即清掉该项。
 *
 * <p>
 * {@code providerId} 与 {@code modelId} 是<b>成对</b>的默认目标：回显时按这一对还原下拉选项，
 * 同名模型在不同 provider 下不会串台。
 *
 * @param version {@code config.version}（诊断用：改完能确认版本真的动了）
 * @param providerId {@code default.providerId}（{@code null} = 旧配置未绑定，读侧按模型归属解析）
 */
public record DefaultsView(String modelId, Double temperature, Double topP, Integer maxTokens,
        Boolean allowUserParams, long version, Long providerId) {
}
