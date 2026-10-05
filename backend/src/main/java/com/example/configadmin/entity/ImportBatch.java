package com.example.configadmin.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 导入批次：一次导入任务的工作单元。filesJson 中每个文件经历
 * UPLOADED → CHECKED → IMPORTED → PUBLISHED（或 ERROR/SKIPPED）。
 */
@Entity
@Table(name = "import_batch")
public class ImportBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    /** 批次包含的配置编码 JSON：List<String> */
    @Lob
    @Column(nullable = false, columnDefinition = "CLOB")
    private String defCodesJson = "[]";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BatchStatus status = BatchStatus.CREATED;

    /** 整体进度 0-100 */
    @Column(nullable = false)
    private int progress = 0;

    @Column(length = 1000)
    private String message = "";

    /** 每文件状态 JSON：List<ImportFileEntry> */
    @Lob
    @Column(columnDefinition = "CLOB")
    private String filesJson = "[]";

    /** 依赖拓扑顺序 JSON：List<String> */
    @Lob
    @Column(columnDefinition = "CLOB")
    private String orderJson = "[]";

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
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDefCodesJson() { return defCodesJson; }
    public void setDefCodesJson(String defCodesJson) { this.defCodesJson = defCodesJson; }
    public BatchStatus getStatus() { return status; }
    public void setStatus(BatchStatus status) { this.status = status; }
    public int getProgress() { return progress; }
    public void setProgress(int progress) { this.progress = progress; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getFilesJson() { return filesJson; }
    public void setFilesJson(String filesJson) { this.filesJson = filesJson; }
    public String getOrderJson() { return orderJson; }
    public void setOrderJson(String orderJson) { this.orderJson = orderJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
