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
 * 首期键：{@code default.modelId} / {@code default.temperature} / {@code default.topP} /
 * {@code default.maxTokens} / {@code allow.userParams} / {@code config.version}。
 * 批① 只读：迁移里预置 {@code config.version=1}（快照版本号的 DB 真源）。
 */
@Data
@Entity
@Table(name = "ai_setting")
public class AiSetting {

    /** 快照版本号键（设计稿 §3.4：DB 真源；跨实例失效信号走 Redis {@code ai:cfg:ver}）。 */
    public static final String CONFIG_VERSION = "config.version";

    public static final String DEFAULT_MODEL_ID = "default.modelId";

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
