package com.example.configadmin.dto;

import com.example.configadmin.entity.Level;

import java.util.ArrayList;
import java.util.List;

/** 配置定义视图（含字段、依赖、生效行数）。 */
public class DefView {

    private Long id;
    private String code;
    private String name;
    private Level level;
    private String description;
    private List<FieldDef> fields = new ArrayList<>();
    private List<String> dependsOn = new ArrayList<>();
    private int sortOrder;
    private boolean enabled;
    private long publishedRowCount;
    private long draftRowCount;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
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
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getPublishedRowCount() { return publishedRowCount; }
    public void setPublishedRowCount(long publishedRowCount) { this.publishedRowCount = publishedRowCount; }
    public long getDraftRowCount() { return draftRowCount; }
    public void setDraftRowCount(long draftRowCount) { this.draftRowCount = draftRowCount; }
}
