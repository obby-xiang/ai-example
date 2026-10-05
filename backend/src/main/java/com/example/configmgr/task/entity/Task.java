package com.example.configmgr.task.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Entity
@Table(name = "tasks")
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

    @Version
    private Long version = 1L;

    @OneToMany(mappedBy = "taskId", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
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
