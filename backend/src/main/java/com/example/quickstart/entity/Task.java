package com.example.quickstart.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "qs_task")
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 任务编号，如 EXP-20261005-0001 */
    @Column(nullable = false, unique = true, length = 32)
    private String taskNo;

    /** EXPORT / IMPORT */
    @Column(nullable = false, length = 16)
    private String type;

    @Column(nullable = false, length = 128)
    private String name;

    /** DRAFT / IN_PROGRESS / COMPLETED / FAILED / CANCELLED */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(nullable = false)
    private Integer currentStep;

    /** 向导数据 JSON（已选配置项、查询条件等，内容前端自定） */
    @Lob
    private String stepData;

    @Column(nullable = false)
    private Integer progress;

    @Column(length = 1024)
    private String message;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
