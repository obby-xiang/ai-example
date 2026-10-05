package com.example.quickstart.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** 导出结果暂存：每个作业每个配置项一条 */
@Getter
@Setter
@Entity
@Table(name = "export_result", indexes = @Index(name = "idx_export_result_job", columnList = "jobId"))
public class ExportResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long jobId;

    @Column(nullable = false, length = 64)
    private String defCode;

    /** 数据行数组 JSON：[{字段名: 值}] */
    @Lob
    @Column(nullable = false)
    private String rowsJson;
}
