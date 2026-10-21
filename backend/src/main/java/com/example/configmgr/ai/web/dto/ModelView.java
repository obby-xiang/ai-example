package com.example.configmgr.ai.web.dto;

/**
 * 模型 allowlist 的单行视图（{@code GET /api/ai/providers} 内嵌；批③ 的 {@code GET /api/ai/models}
 * 复用同一字段口径）。
 *
 * <p>
 * {@code maxContext} 是上下文统计专项占用条的分母；{@code supportsToolCalling=false} 时本轮
 * 不下发 tools（能力位硬约束）。
 */
public record ModelView(Long id, String modelId, String displayName, boolean supportsToolCalling,
        boolean supportsVision, boolean supportsReasoning, Integer maxContext, Integer maxOutput, boolean enabled,
        boolean isDefault, int sortOrder) {
}
