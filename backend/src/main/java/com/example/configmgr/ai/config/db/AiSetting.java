package com.example.configmgr.ai.config.db;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 全局配置键值（{@code ai_setting}，单行 = 一个键）。
 *
 * <p>
 * 首期键：{@code default.providerId} / {@code default.modelId} / {@code default.temperature} /
 * {@code default.topP} / {@code default.maxTokens} / {@code allow.userParams} / {@code config.version}。
 * V1 空库基线预置 {@code config.version=1}。
 */
@Getter
@Setter
@ToString
@Entity
@Table(name = "ai_setting")
public class AiSetting {

    /** 快照版本号键（唯一权威来源）。 */
    public static final String CONFIG_VERSION = "config.version";

    public static final String DEFAULT_MODEL_ID = "default.modelId";

    /**
     * 默认目标的 provider 绑定（{@code default.providerId}）。
     *
     * <p>与 {@link #DEFAULT_MODEL_ID} 成对使用：模型名只在<b>同一行</b> provider 的 allowlist 里才生效，
     * 避免"设置页保存 B 的模型、运行时却发给排序第一的 A"。
     *
     * <p><b>硬绑定</b>：该键存在时只认它指向的 provider —— 停用 / key 缺失 / key 解不开 / provider
     * 行不存在 / 绑定模型停用或删除，都明确不可用（{@code AI_UNAVAILABLE}、{@code AI_KEY_*} 或
     * {@code NO_MODEL_CONFIGURED}），不会静默换别家 provider、也不会在同一 provider 内换默认模型。
     * 缺该键 = 未设置默认目标：读侧按库序取第一个能给出自己默认模型的 provider；
     * 写侧不允许只提交 {@code modelId}（见 {@code AiConfigWriter}）。
     */
    public static final String DEFAULT_PROVIDER_ID = "default.providerId";

    public static final String DEFAULT_TEMPERATURE = "default.temperature";

    public static final String DEFAULT_TOP_P = "default.topP";

    public static final String DEFAULT_MAX_TOKENS = "default.maxTokens";

    public static final String ALLOW_USER_PARAMS = "allow.userParams";

    @Id
    @Column(name = "setting_key", length = 64)
    @JsonProperty("settingKey")
    private String settingKey;

    @Column(name = "value_json", length = 2048)
    @JsonProperty("valueJson")
    private String valueJson;

    @UpdateTimestamp
    @Column(name = "updated_at")
    @JsonProperty("updatedAt")
    private LocalDateTime updatedAt;
}
