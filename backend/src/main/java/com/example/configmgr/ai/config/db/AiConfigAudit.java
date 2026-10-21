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
 * 批① <b>只建表结构</b>（V4 迁移），不写数据 —— 写路径与审计落表在批②/批④。本类先落位，
 * 免得"表已建好但代码里没有对应口"造成下一批又要动迁移。
 *
 * <p>
 * 纪律（调研 §3.1e）：{@link #diffJson} 对 key 只写 {@code {"key":"updated","last4":"ab12"}}，
 * 永远不写密文或明文。
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
