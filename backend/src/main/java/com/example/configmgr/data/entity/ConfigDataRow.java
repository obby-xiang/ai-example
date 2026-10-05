package com.example.configmgr.data.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "config_data_rows",
       uniqueConstraints = @UniqueConstraint(columnNames = {"def_code","scope_type","scope_key","row_key"}))
public class ConfigDataRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "def_code", nullable = false, length = 64)
    private String defCode;

    @Column(name = "scope_type", nullable = false, length = 16)
    private String scopeType = "GLOBAL"; // GLOBAL/REGION/PROJECT

    @Column(name = "scope_key", length = 64)
    private String scopeKey;

    @Column(name = "row_key", nullable = false, length = 256)
    private String rowKey;

    @Column(name = "data_json", nullable = false, columnDefinition = "CLOB")
    private String dataJson;

    @Version
    private Long version = 1L;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
