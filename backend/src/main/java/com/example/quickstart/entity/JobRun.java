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

import java.time.LocalDateTime;

/** 长耗时操作（导出/检查/导入/发布）统一抽象的作业 */
@Getter
@Setter
@Entity
@Table(name = "job_run", indexes = @Index(name = "idx_job_task", columnList = "taskId"))
public class JobRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long taskId;

    /** EXPORT / CHECK / IMPORT / PUBLISH */
    @Column(nullable = false, length = 16)
    private String kind;

    /** PENDING / RUNNING / SUCCESS / FAILED / CANCELLED */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(nullable = false)
    private Integer total;

    @Column(nullable = false)
    private Integer processed;

    /** 正在处理的配置项编码 */
    @Column(length = 64)
    private String currentItem;

    @Lob
    private String result;

    @Column(length = 2048)
    private String error;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;
}
