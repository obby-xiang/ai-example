package com.example.configmgr.definition.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "config_fields",
       uniqueConstraints = @UniqueConstraint(columnNames = {"def_code", "code"}))
public class ConfigField {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "def_code", nullable = false, length = 64)
    private String defCode;

    @Column(nullable = false, length = 64)
    private String code;

    @Column(nullable = false, length = 128)
    private String label;

    @Column(name = "field_type", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private FieldType fieldType;

    @Column(nullable = false)
    private boolean required = false;

    @Column(name = "is_key", nullable = false)
    private boolean isKey = false;

    @Column(name = "sort_order")
    private int sortOrder = 0;

    @Column(name = "options_json", columnDefinition = "CLOB")
    private String optionsJson; // JSON array [{value, label}] for ENUM

    @Column(name = "ref_def_code", length = 64)
    private String refDefCode;

    @Column(name = "ref_field_code", length = 64)
    private String refFieldCode;

    public enum FieldType {
        STRING, NUMBER, DATE, ENUM, BOOLEAN, REFERENCE
    }
}
