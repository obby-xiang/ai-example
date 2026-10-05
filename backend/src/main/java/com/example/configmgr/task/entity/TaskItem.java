package com.example.configmgr.task.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "task_items",
       uniqueConstraints = @UniqueConstraint(columnNames = {"task_id", "def_code"}))
public class TaskItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "def_code", nullable = false, length = 64)
    private String defCode;

    @Column(name = "sort_order")
    private int sortOrder = 0;

    @Column(name = "condition_json", columnDefinition = "CLOB")
    private String conditionJson;

    @Column(nullable = false, length = 32)
    private String status = "PENDING";
    // PENDING / READY / CHECKING / CHECKED / IMPORTING / IMPORTED / PUBLISHING / PUBLISHED / FAILED
}
