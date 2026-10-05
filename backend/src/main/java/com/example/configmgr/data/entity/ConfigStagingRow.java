package com.example.configmgr.data.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "config_staging_rows")
public class ConfigStagingRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "def_code", nullable = false, length = 64)
    private String defCode;

    @Column(name = "op_type", nullable = false, length = 16)
    private String opType = "UPSERT"; // UPSERT/DELETE

    @Column(name = "row_key", nullable = false, length = 256)
    private String rowKey;

    @Column(name = "scope_type", nullable = false, length = 16)
    private String scopeType = "GLOBAL";

    @Column(name = "scope_key", length = 64)
    private String scopeKey;

    @Column(name = "data_json", nullable = false, columnDefinition = "CLOB")
    private String dataJson;

    @Column(nullable = false, length = 16)
    private String status = "STAGED"; // STAGED/PUBLISHED/FAILED

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
