package com.example.configmgr.ai.config.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 全局配置键值（{@code ai_setting}，单行 = 一个键）。
 *
 * <p>
 * 首期键：{@code default.providerId} / {@code default.modelId} / {@code default.temperature} /
 * {@code default.topP} / {@code default.maxTokens} / {@code allow.userParams} / {@code config.version}。
 * 批① 只读：迁移里预置 {@code config.version=1}（快照版本号的 DB 真源）。
 */
@Data
@Entity
@Table(name = "ai_setting")
public class AiSetting {

    /** 快照版本号键（设计稿 §3.4：DB 真源；跨实例失效信号走 Redis {@code ai:cfg:ver}）。 */
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
     * 缺该键 = 未绑定，此时才按旧配置的模型归属回退（见 {@code AiConfigService}）。
     */
    public static final String DEFAULT_PROVIDER_ID = "default.providerId";

    public static final String DEFAULT_TEMPERATURE = "default.temperature";

    public static final String DEFAULT_TOP_P = "default.topP";

    public static final String DEFAULT_MAX_TOKENS = "default.maxTokens";

    public static final String ALLOW_USER_PARAMS = "allow.userParams";

    @Id
    @Column(name = "setting_key", length = 64)
    private String settingKey;

    @Column(name = "value_json", length = 2048)
    private String valueJson;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
