package com.example.configadmin.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 配置数据行：通用行表，业务数据以 JSON 存储，适配任意动态结构。
 * published=true 为生效数据；false 为某导入批次的草稿。
 */
@Entity
@Table(name = "config_row", indexes = {
        @Index(name = "idx_row_def_pub", columnList = "defCode, published"),
        @Index(name = "idx_row_def_scope", columnList = "defCode, scope, published"),
        @Index(name = "idx_row_batch", columnList = "batchId")
})
public class ConfigRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属配置定义编码 */
    @Column(nullable = false, length = 64)
    private String defCode;

    /** 范围（REGION/PROJECT 层级必填；GLOBAL 为空） */
    @Column(length = 128)
    private String scope;

    /** 业务数据 JSON：Map<字段编码, 值> */
    @Lob
    @Column(nullable = false, columnDefinition = "CLOB")
    private String dataJson = "{}";

    /** 所属导入批次（生效数据的历史批次或 null） */
    @Column(length = 64)
    private String batchId;

    /** 是否已发布生效 */
    @Column(nullable = false)
    private boolean published = false;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getDefCode() { return defCode; }
    public void setDefCode(String defCode) { this.defCode = defCode; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getDataJson() { return dataJson; }
    public void setDataJson(String dataJson) { this.dataJson = dataJson; }
    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public boolean isPublished() { return published; }
    public void setPublished(boolean published) { this.published = published; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
