package com.example.configmgr.task.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "task_files",
       uniqueConstraints = @UniqueConstraint(columnNames = {"task_id", "def_code", "file_type"}))
public class TaskFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "def_code", nullable = false, length = 64)
    private String defCode;

    @Column(name = "file_type", nullable = false, length = 32)
    private String fileType; // TEMPLATE / UPLOAD / EXPORT

    @Column(name = "storage_path", nullable = false, length = 512)
    private String storagePath;

    @Column(name = "original_path", length = 512)
    private String originalPath;

    @Column(name = "file_name", length = 256)
    private String fileName;

    @Column(name = "row_count")
    private Integer rowCount;

    @Version
    private Long version = 1L;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
