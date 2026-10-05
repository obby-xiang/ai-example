package com.example.configadmin.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 配置定义：一种配置项类型。字段结构与依赖均以 JSON 存储，完全数据驱动。
 */
@Entity
@Table(name = "config_def")
public class ConfigDef {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 配置编码（唯一；导入文件名匹配依据），如 SERVER_PARAM */
    @Column(nullable = false, unique = true, length = 64)
    private String code;

    /** 配置名称（中文），如 服务器参数配置 */
    @Column(nullable = false, length = 128)
    private String name;

    /** 所属层级：全局/地区/项目 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Level level;

    @Column(length = 500)
    private String description;

    /** 字段定义 JSON：List<FieldDef> */
    @Lob
    @Column(nullable = false, columnDefinition = "CLOB")
    private String fieldsJson = "[]";

    /** 依赖的配置编码 JSON：List<String>（导入时先于本配置） */
    @Lob
    @Column(columnDefinition = "CLOB")
    private String dependsOnJson = "[]";

    @Column(nullable = false)
    private int sortOrder = 0;

    @Column(nullable = false)
    private boolean enabled = true;

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

    // ---- getters / setters ----

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Level getLevel() { return level; }
    public void setLevel(Level level) { this.level = level; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getFieldsJson() { return fieldsJson; }
    public void setFieldsJson(String fieldsJson) { this.fieldsJson = fieldsJson; }
    public String getDependsOnJson() { return dependsOnJson; }
    public void setDependsOnJson(String dependsOnJson) { this.dependsOnJson = dependsOnJson; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
