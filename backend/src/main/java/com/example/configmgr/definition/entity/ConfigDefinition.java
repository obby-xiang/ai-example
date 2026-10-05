package com.example.configmgr.definition.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
@Data
@Entity
@Table(name = "config_definitions")
public class ConfigDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private ConfigLevel level;

    @Column(length = 512)
    private String description;

    @Column(name = "sort_order")
    private int sortOrder = 0;

    // 单向一对多：config_fields.def_code = config_definitions.code。
    // 不能用 mappedBy（mappedBy 只能指向实体关联属性，指向标量字段会导致
    // Hibernate 6.6 集合加载时参数类型推断错误）；FK 由业务代码显式写入 def_code。
    @OneToMany(fetch = FetchType.EAGER, cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "def_code", referencedColumnName = "code",
            insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    @OrderBy("sortOrder ASC")
    private List<ConfigField> fields = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum ConfigLevel {
        GLOBAL, REGION, PROJECT
    }
}
