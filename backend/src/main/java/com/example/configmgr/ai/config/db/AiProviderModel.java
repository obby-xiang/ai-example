package com.example.configmgr.ai.config.db;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 模型 allowlist（{@code ai_model}）：一行 = 某 provider 下一个可选模型 + 能力位 + 窗口。
 *
 * <p>
 * {@link #maxContext} 是上下文统计专项"占用条分母"的落点；{@link #supportsToolCalling} 是
 * <b>硬约束</b>的落点（false ⇒ 本轮不下发 tools，见设计稿 §3.4）。唯一约束
 * {@code (provider_id, model_id)} 与 V1 空库基线同口径。
 */
@Getter
@Setter
@ToString
@Entity
@Table(name = "ai_model",
        uniqueConstraints = @UniqueConstraint(name = "uq_ai_model_provider_model",
                columnNames = { "provider_id", "model_id" }))
public class AiProviderModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @JsonProperty("id")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long id;

    /** 逻辑外键（不加 FK 约束：provider 删除走软删，避免级联阻塞）。 */
    @Column(name = "provider_id", nullable = false)
    @JsonProperty("providerId")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long providerId;

    /** 请求体里的 {@code model} 原值。 */
    @Column(name = "model_id", nullable = false, length = 128)
    @JsonProperty("modelId")
    private String modelId;

    /** 卡片/选择器展示名；缺省 = {@link #modelId}。 */
    @Column(name = "display_name", length = 128)
    @JsonProperty("displayName")
    private String displayName;

    /** 上下文窗口（prompt_tokens 上限，上下文统计专项的分母）。 */
    @Column(name = "max_context")
    @JsonProperty("maxContext")
    private Integer maxContext;

    /** 请求 {@code max_tokens} 的上界。 */
    @Column(name = "max_output")
    @JsonProperty("maxOutput")
    private Integer maxOutput;

    @Column(name = "supports_tool_calling", nullable = false)
    @JsonProperty("supportsToolCalling")
    private boolean supportsToolCalling = true;

    @Column(name = "supports_vision")
    @JsonProperty("supportsVision")
    private Boolean supportsVision = Boolean.FALSE;

    @Column(name = "supports_reasoning")
    @JsonProperty("supportsReasoning")
    private Boolean supportsReasoning = Boolean.FALSE;

    @Column(name = "enabled", nullable = false)
    @JsonProperty("enabled")
    private boolean enabled = true;

    /** 无 modelId 时的落点。 */
    @Column(name = "is_default", nullable = false)
    @JsonProperty("default")
    private boolean isDefault;

    @Column(name = "sort_order", nullable = false)
    @JsonProperty("sortOrder")
    private int sortOrder;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    @JsonProperty("createdAt")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    @JsonProperty("updatedAt")
    private LocalDateTime updatedAt;
}
