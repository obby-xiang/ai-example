package com.example.configmgr.ai.config.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 配置变更审计（{@code ai_config_audit}，设计稿 §3.1 P2）。
 *
 * <p>
 * <b>现状（批② 起）</b>：写路径每次变更都落一行 —— {@code AiConfigWriter} 在"数据行 + 版本号"
 * 的<b>同一个事务</b>里写审计，动作见 {@code ACTION_} 常量（PROVIDER_CREATE/UPDATE/DELETE、
 * MODEL_ADD/UPDATE/DELETE、DEFAULTS_UPDATE），{@code target_type = provider|model|setting}。
 * 只建表不写数据的时期（批①）已结束。
 *
 * <p>
 * 纪律（调研 §3.1e）：{@link #diffJson} 对 key 只写 {@code {"key":"updated","last4":"ab12"}}，
 * 永远不写密文或明文（{@code AiConfigWriterTest#auditNeverCarriesTheKeyOrTheCipher} 与
 * {@code AiConfigWritePersistenceTest#auditRowsCarryOnlyTheLast4AndTheAction} 两处锁死）。
 */
@Data
@Entity
@Table(name = "ai_config_audit")
public class AiConfigAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 单用户本地应用暂无登录体系，预留操作者位（同 {@code AiFeedback#createdBy} 先例）。 */
    @Column(name = "actor", length = 64)
    private String actor;

    @Column(name = "action", nullable = false, length = 32)
    private String action;

    @Column(name = "target_type", nullable = false, length = 32)
    private String targetType;

    @Column(name = "target_id", length = 64)
    private String targetId;

    @Column(name = "diff_json", length = 4096)
    private String diffJson;

    @CreationTimestamp
    @Column(name = "at", updatable = false)
    private LocalDateTime at;
}
