package com.example.quickstart.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "CONFIG_FIELD", uniqueConstraints = @UniqueConstraint(columnNames = {"DEF_ID", "FIELD_CODE"}))
public class ConfigField {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "DEF_ID", nullable = false)
    private Long defId;

    @Column(name = "FIELD_CODE", nullable = false, length = 64)
    private String fieldCode;

    @Column(name = "FIELD_NAME", nullable = false, length = 128)
    private String fieldName;

    /** TEXT/INT/DECIMAL/DATE/BOOL/ENUM/SCOPE */
    @Column(nullable = false, length = 16)
    private String dataType;

    @Column(nullable = false)
    private Boolean required;

    /** ENUM 的选项 JSON 数组；SCOPE 由范围字典提供 */
    @Column(length = 4096)
    private String options;

    @Column(nullable = false)
    private Boolean isKey;

    @Column(nullable = false)
    private Integer sortNo;

    @Column(length = 256)
    private String sampleValue;
}
