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

/** 已发布的正式区配置数据行 */
@Getter
@Setter
@Entity
@Table(name = "config_data", indexes = @Index(name = "idx_config_data_def", columnList = "defCode"))
public class ConfigData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String defCode;

    @Column(nullable = false)
    private Integer rowNo;

    @Lob
    @Column(nullable = false)
    private String dataJson;
}
