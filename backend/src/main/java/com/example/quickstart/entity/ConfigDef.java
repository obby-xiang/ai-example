package com.example.quickstart.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "CONFIG_DEF")
public class ConfigDef {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    /** GLOBAL / REGION / PROJECT */
    @Column(nullable = false, length = 16)
    private String level;

    @Column(length = 512)
    private String description;

    /** 依赖配置项编码数组 JSON，如 ["METRIC_DICT"] */
    @Column(length = 1024)
    private String dependsOn;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Version
    private Long version;
}
