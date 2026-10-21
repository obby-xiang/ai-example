package com.example.configmgr.ai.config.db;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * provider 主档（{@code ai_provider}，一行 = 一个 OpenAI 兼容端点）。
 *
 * <p>
 * 范式照 {@code ai.feedback.AiFeedback}（{@code @Data @Entity @Table} + IDENTITY + 时间戳）；
 * 本表对应的 DDL 在 {@code V4__ai_provider_config.sql}（{@code ddl-auto=validate} ⇒ 实体不许建表）。
 *
 * <h2>密钥纪律（设计稿 §3.2 三条硬约束之一）</h2>
 * {@link #keyCipher} 是<b>密文</b>，不进 {@code toString()}、不进 JSON —— 实体{@code @Data} 默认生成的
 * {@code toString()} 会把整行打出来（日志泄漏面），故显式 {@code @ToString.Exclude}；再加
 * {@code @JsonIgnore} 兜住"哪天有人直接序列化实体"的可能。是否配置了 key 由 {@code keyRef} +
 * {@code keyLast4} 表达，明文/密文一概不出口。
 */
@Data
@Entity
@Table(name = "ai_provider")
public class AiProvider {

    /** key 存放位置：密文在库。 */
    public static final String KEY_REF_DB = "DB";

    /** key 存放位置：运行时从进程环境读（env 种子行永远是这个值；env key 不落库）。 */
    public static final String KEY_REF_ENV = "ENV";

    /** key 存放位置：没有可用 key（新建但还没填 key 的行）。 */
    public static final String KEY_REF_NONE = "NONE";

    /** 来源标签：由 env 种子写入。 */
    public static final String SOURCE_ENV_SEED = "ENV_SEED";

    /** 来源标签：UI 写入。 */
    public static final String SOURCE_DB = "DB";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 稳定机读名（{@code deepseek} / {@code custom-<n>}）；唯一约束的落点。 */
    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    /** 预留列：本期只实现 {@code OPENAI_COMPAT}。 */
    @Column(name = "protocol", nullable = false, length = 32)
    private String protocol = "OPENAI_COMPAT";

    @Column(name = "base_url", nullable = false, length = 512)
    private String baseUrl;

    @Column(name = "completions_path", nullable = false, length = 128)
    private String completionsPath = "/chat/completions";

    @Column(name = "models_path", nullable = false, length = 128)
    private String modelsPath = "/models";

    /** 信封串 {@code v1.<kid>.<iv>.<ct+tag>}；env 种子行恒为 null。 */
    @ToString.Exclude
    @JsonIgnore
    @Column(name = "key_cipher", length = 2048)
    private String keyCipher;

    @Column(name = "key_last4", length = 8)
    private String keyLast4;

    /** HMAC-SHA256(KEK, key) 前 16 hex（不是裸 SHA-256：指纹也是运行时缓存键的一部分）。 */
    @Column(name = "key_fingerprint", length = 64)
    private String keyFingerprint;

    @Column(name = "key_ref", nullable = false, length = 8)
    private String keyRef = KEY_REF_NONE;

    @Column(name = "source", nullable = false, length = 16)
    private String source = SOURCE_DB;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** 是否"有 key 可用"的<b>声明面</b>（真正可解析与否要运行时解一遍，见 AiConfigService）。 */
    public boolean isKeyDeclared() {
        return !KEY_REF_NONE.equals(this.keyRef) && this.keyRef != null;
    }
}
