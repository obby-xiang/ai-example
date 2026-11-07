package com.example.configmgr.task.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Entity
@Table(name = "tasks", uniqueConstraints = {
        // 与 V12 的约束名逐字一致：唯一性的权威在 DB 迁移，实体注解只做同一份声明的镜像
        @UniqueConstraint(name = "uq_tasks_ai_create_run", columnNames = "ai_create_run"),
        @UniqueConstraint(name = "uq_tasks_ai_create_key", columnNames = "ai_create_key")
})
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private TaskType type;

    @Column(nullable = false, length = 256)
    private String title;

    @Column(name = "current_step", nullable = false, length = 64)
    private String currentStep;

    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private TaskStatus status = TaskStatus.ACTIVE;

    @Column(name = "settings_json", columnDefinition = "CLOB")
    private String settingsJson;

    /**
     * AI 创建幂等的数据库兜底（{@code settings_json.aiCreateRun} 的镜像列，仅供唯一约束判重）。
     *
     * <p>{@code settings_json} 是 CLOB，不能直接建唯一约束；幂等标记仍按原规格写在
     * {@code settings_json}，这两列只是它的可索引副本。普通（非 AI）创建留 NULL ——
     * 唯一约束允许多个 NULL，不互相阻塞。
     */
    @JsonIgnore
    @Column(name = "ai_create_run", length = 255)
    private String aiCreateRun;

    /** 同 {@link #aiCreateRun}：{@code settings_json.aiCreateKey} 的镜像列（无 runId 时的键级幂等）。 */
    @JsonIgnore
    @Column(name = "ai_create_key", length = 64)
    private String aiCreateKey;

    @Version
    private Long version = 1L;

    // 单向一对多：task_items.task_id = tasks.id（同 ConfigDefinition 原因，不用 mappedBy 标量）
    @OneToMany(fetch = FetchType.EAGER, cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "task_id", referencedColumnName = "id",
            insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    @OrderBy("sortOrder ASC")
    private List<TaskItem> items = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum TaskType { EXPORT, IMPORT }
    public enum TaskStatus { ACTIVE, COMPLETED, CANCELLED, FAILED }
}
