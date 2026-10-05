package com.example.configmgr.job.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Entity
@Table(name = "jobs")
public class Job {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "job_type", nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private JobType jobType;

    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private JobStatus status = JobStatus.PENDING;

    private int progress = 0;
    private int total = 0;

    @Column(name = "error_count")
    private int errorCount = 0;

    @Column(name = "warning_count")
    private int warningCount = 0;

    @Column(name = "result_json", columnDefinition = "CLOB")
    private String resultJson;

    // 单向一对多：job_items.job_id = jobs.id（同 ConfigDefinition 原因，不用 mappedBy 标量）
    @OneToMany(fetch = FetchType.EAGER, cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "job_id", referencedColumnName = "id",
            insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private List<JobItem> items = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    public enum JobType { EXPORT, PRECHECK, IMPORT, PUBLISH }
    public enum JobStatus { PENDING, RUNNING, COMPLETED, FAILED, CANCELLED }
}
