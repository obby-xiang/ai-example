package com.example.quickstart.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "config_definition")
public class ConfigDefinition {

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

    @Column(length = 1024)
    private String description;

    /** 字段定义数组 JSON：[{name,label,type,required,options?,maxLength?,ref?}] */
    @Lob
    @Column(nullable = false)
    private String fieldsJson;

    /** 依赖的配置项编码数组 JSON */
    @Lob
    private String dependsOnJson;
}
