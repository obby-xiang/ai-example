package com.example.configadmin.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** 导出任务：多配置批量导出，异步执行，文件落盘 ./data/exports/{id}/ */
@Entity
@Table(name = "export_task")
public class ExportTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 导出配置编码 JSON：List<String> */
    @Lob
    @Column(nullable = false, columnDefinition = "CLOB")
    private String defCodesJson = "[]";

    /** 查询条件 JSON：Map<defCode, Map<fieldCode, Cond>> */
    @Lob
    @Column(columnDefinition = "CLOB")
    private String conditionsJson = "{}";

    /** PENDING / RUNNING / SUCCESS / FAILED */
    @Column(nullable = false, length = 16)
    private String status = "PENDING";

    @Column(nullable = false)
    private int progress = 0;

    @Column(length = 1000)
    private String message = "";

    /** 文件清单 JSON：List<ExportFileEntry> */
    @Lob
    @Column(columnDefinition = "CLOB")
    private String filesJson = "[]";

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
    public String getDefCodesJson() { return defCodesJson; }
    public void setDefCodesJson(String defCodesJson) { this.defCodesJson = defCodesJson; }
    public String getConditionsJson() { return conditionsJson; }
    public void setConditionsJson(String conditionsJson) { this.conditionsJson = conditionsJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getProgress() { return progress; }
    public void setProgress(int progress) { this.progress = progress; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getFilesJson() { return filesJson; }
    public void setFilesJson(String filesJson) { this.filesJson = filesJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
