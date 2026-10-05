package com.example.quickstart.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "TASK")
public class Task {

    @Id
    @Column(length = 40)
    private String id;

    /** EXPORT_CONFIG / IMPORT_CONFIG */
    @Column(nullable = false, length = 32)
    private String type;

    /** WAITING / EXECUTING / SUCCESS / FAILED / CANCELLED */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(nullable = false, length = 32)
    private String currentStep;

    @Column(nullable = false, length = 128)
    private String title;

    /** 各步骤状态+选择+条件+结果 JSON CLOB */
    @Lob
    @Column(columnDefinition = "CLOB")
    private String params;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private LocalDateTime finishedAt;
}
