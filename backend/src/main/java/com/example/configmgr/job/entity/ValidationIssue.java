package com.example.configmgr.job.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "validation_issues")
public class ValidationIssue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private Long jobId;

    @Column(name = "def_code", nullable = false, length = 64)
    private String defCode;

    @Column(name = "row_key", length = 256)
    private String rowKey;

    @Column(name = "field_code", length = 64)
    private String fieldCode;

    @Column(nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private Severity severity;

    @Column(nullable = false, length = 1024)
    private String message;

    @Column(name = "row_index")
    private Integer rowIndex;

    public enum Severity { ERROR, WARNING, INFO }
}
