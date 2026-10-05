package com.example.configadmin.dto;

import com.example.configadmin.entity.Level;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.ArrayList;
import java.util.List;

/** 配置定义 新增/更新 请求体。 */
public class DefSaveRequest {

    @NotBlank(message = "配置编码不能为空")
    private String code;
    @NotBlank(message = "配置名称不能为空")
    private String name;
    @NotNull(message = "层级不能为空")
    private Level level;
    private String description;
    private List<FieldDef> fields = new ArrayList<>();
    private List<String> dependsOn = new ArrayList<>();
    private Integer sortOrder = 0;
    private Boolean enabled = true;

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Level getLevel() { return level; }
    public void setLevel(Level level) { this.level = level; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<FieldDef> getFields() { return fields; }
    public void setFields(List<FieldDef> fields) { this.fields = fields; }
    public List<String> getDependsOn() { return dependsOn; }
    public void setDependsOn(List<String> dependsOn) { this.dependsOn = dependsOn; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
}
