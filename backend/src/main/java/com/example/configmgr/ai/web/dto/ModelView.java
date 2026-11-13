package com.example.configmgr.ai.web.dto;

import com.example.configmgr.ai.config.EmptyLengthReasoningRecovery;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 模型 allowlist 的单行视图（{@code GET /api/ai/providers} 内嵌；批③ 的 {@code GET /api/ai/models}
 * 复用同一字段口径）。
 *
 * <p>
 * {@code maxContext} 是上下文统计专项占用条的分母；{@code supportsToolCalling=false} 时本轮
 * 不下发 tools（能力位硬约束）。
 */
public record ModelView(@JsonProperty("id") @JsonFormat(shape = JsonFormat.Shape.STRING) Long id,
        @JsonProperty("modelId") String modelId, @JsonProperty("displayName") String displayName,
        @JsonProperty("supportsToolCalling") boolean supportsToolCalling,
        @JsonProperty("supportsVision") boolean supportsVision,
        @JsonProperty("supportsReasoning") boolean supportsReasoning,
        @JsonProperty("maxContext") Integer maxContext, @JsonProperty("maxOutput") Integer maxOutput,
        @JsonProperty("enabled") boolean enabled, @JsonProperty("isDefault") boolean isDefault,
        @JsonProperty("sortOrder") int sortOrder,
        @JsonProperty("emptyLengthReasoningRecovery") RecoveryView emptyLengthReasoningRecovery) {

    public record RecoveryView(@JsonProperty("reasoningEffort") String reasoningEffort,
            @JsonProperty("requestExtraBodyKeys") List<String> requestExtraBodyKeys) {

        public static RecoveryView from(EmptyLengthReasoningRecovery recovery) {
            return recovery == null ? null
                    : new RecoveryView(recovery.reasoningEffort(), List.copyOf(recovery.requestExtraBody().keySet()));
        }
    }
}
