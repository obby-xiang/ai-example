package com.example.quickstart.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "CONFIG_DATA", indexes = {
        @Index(name = "IDX_CONFIG_DATA_DEF", columnList = "DEF_ID"),
        @Index(name = "IDX_CONFIG_DATA_TASK", columnList = "TASK_ID")
})
public class ConfigData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "DEF_ID", nullable = false)
    private Long defId;

    @Column(name = "SCOPE_LEVEL", length = 16)
    private String scopeLevel;

    @Column(name = "SCOPE_VALUE", length = 64)
    private String scopeValue;

    /** {fieldCode: value} JSON */
    @Column(name = "FIELD_VALUES", nullable = false, length = 8192)
    private String fieldValues;

    /** PUBLISHED / STAGED */
    @Column(nullable = false, length = 16)
    private String status;

    /** 暂存数据来源任务 */
    @Column(length = 40)
    private String taskId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
