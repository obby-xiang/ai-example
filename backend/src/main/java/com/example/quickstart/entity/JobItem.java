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

/** 作业明细：每个配置项一条，seq 为拓扑序 */
@Getter
@Setter
@Entity
@Table(name = "job_item", indexes = @Index(name = "idx_job_item_job", columnList = "jobId"))
public class JobItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long jobId;

    @Column(nullable = false, length = 64)
    private String defCode;

    /** 拓扑序（从 1 开始） */
    @Column(nullable = false)
    private Integer seq;

    /** PENDING / RUNNING / SUCCESS / FAILED / SKIPPED / CANCELLED */
    @Column(nullable = false, length = 16)
    private String status;

    private Integer totalRows;

    private Integer okRows;

    private Integer errorRows;

    @Column(length = 2048)
    private String message;

    /** 逐行错误 JSON：[{rowNo,field,message}]（≤100 条）；导出为 null */
    @Lob
    private String detail;

    /** 该配置项的作业输入 JSON：导出为 {conditions:[...]}；检查/导入为 {rows:[...]} */
    @Lob
    private String requestJson;
}
