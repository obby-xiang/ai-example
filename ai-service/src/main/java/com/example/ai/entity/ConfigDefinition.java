package com.example.ai.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 配置项定义 - 描述一种配置项的元数据(字段定义、类型、校验、下拉选项等)
 */
@Entity
@Table(name = "config_definitions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfigDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 配置项代号,如 CONFIG_A, CONFIG_B */
    @Column(name = "code", nullable = false, unique = true, length = 64)
    private String code;

    /** 配置项中文名称,如 配置项A、渠道配置 */
    @Column(name = "name", nullable = false, length = 128)
    private String name;

    /** 描述 */
    @Column(name = "description", length = 500)
    private String description;

    /**
     * 字段定义,JSON数组:
     * [
     *   {"key":"a","label":"字段A","type":"string","required":true,"options":[],"defaultValue":""},
     *   {"key":"b","label":"字段B","type":"number","required":false,"options":[],"defaultValue":0},
     *   {"key":"c","label":"字段C","type":"select","required":true,"options":["A","B","C"],"defaultValue":"A"}
     * ]
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "columns", columnDefinition = "JSON", nullable = false)
    private List<Map<String, Object>> columns;

    /** 是否启用 */
    @Builder.Default
    private Boolean enabled = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @Transient
    public List<String> getFieldKeys() {
        List<String> keys = new ArrayList<>();
        if (columns != null) {
            for (Map<String, Object> col : columns) {
                Object k = col.get("key");
                if (k != null) keys.add(k.toString());
            }
        }
        return keys;
    }
}
