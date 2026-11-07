package com.example.configmgr.job.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

@Data
@Entity
@Table(name = "job_items",
       uniqueConstraints = @UniqueConstraint(columnNames = {"job_id", "def_code"}))
public class JobItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private Long jobId;

    @Column(name = "def_code", nullable = false, length = 64)
    private String defCode;

    @Column(nullable = false, length = 32)
    private String status = "PENDING";

    private int processed = 0;
    private int total = 0;
}
