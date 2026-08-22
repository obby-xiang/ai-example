package com.example.ai.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 配置数据行 - 某个配置定义下的具体业务数据
 * rowData 存储以字段key为键的行数据 JSON
 */
@Entity
@Table(name = "config_data_rows", indexes = {
        @Index(name = "idx_cfg_def_id", columnList = "config_def_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfigDataRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "config_def_id", nullable = false)
    private Long configDefId;

    /** 行数据 JSON */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "row_data", columnDefinition = "JSON", nullable = false)
    private Map<String, Object> rowData;

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
}
