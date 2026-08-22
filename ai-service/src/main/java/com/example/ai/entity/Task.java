package com.example.ai.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务实体 - 草稿持久化，存储场景、当前步骤、各步骤数据
 */
@Entity
@Table(name = "tasks")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 任务名称 */
    @Column(nullable = false, length = 200)
    private String name;

    /** 场景: EXPORT / IMPORT / ADD / MODIFY */
    @Column(name = "scenario", length = 30)
    private String scenario;

    /** 当前步骤代号 */
    @Column(name = "current_step", length = 50)
    private String currentStep;

    /** 状态: DRAFT / IN_PROGRESS / COMPLETED / CANCELLED */
    @Column(length = 30)
    @Builder.Default
    private String status = "DRAFT";

    /** 已选配置项定义ID列表(逗号分隔) */
    @Column(name = "selected_def_ids", columnDefinition = "CLOB")
    @Lob
    private String selectedDefIds;

    /**
     * 步骤数据快照,key为步骤代号,value为该步骤JSON数据
     * 用于页面刷新后恢复
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "step_data", columnDefinition = "JSON")
    @Builder.Default
    private Map<String, Object> stepData = new HashMap<>();

    /** 变更追踪(仅MODIFY场景) JSON格式 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "changes", columnDefinition = "JSON")
    @Builder.Default
    private Map<String, Object> changes = new HashMap<>();

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @Transient
    public List<Long> getSelectedDefIdList() {
        List<Long> list = new ArrayList<>();
        if (selectedDefIds != null && !selectedDefIds.isBlank()) {
            for (String s : selectedDefIds.split(",")) {
                try {
                    list.add(Long.parseLong(s.trim()));
                } catch (NumberFormatException ignored) {}
            }
        }
        return list;
    }

    public void setSelectedDefIdList(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            this.selectedDefIds = "";
        } else {
            this.selectedDefIds = String.join(",", ids.stream().map(String::valueOf).toList());
        }
    }
}
