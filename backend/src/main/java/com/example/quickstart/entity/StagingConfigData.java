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

/** 导入任务专用的暂存数据（未发布，按 taskId 隔离） */
@Getter
@Setter
@Entity
@Table(name = "staging_config_data", indexes = @Index(name = "idx_staging_task_def", columnList = "taskId,defCode"))
public class StagingConfigData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long taskId;

    @Column(nullable = false, length = 64)
    private String defCode;

    @Column(nullable = false)
    private Integer rowNo;

    @Lob
    @Column(nullable = false)
    private String dataJson;
}
