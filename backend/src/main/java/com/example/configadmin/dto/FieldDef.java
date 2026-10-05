package com.example.configadmin.dto;

import java.util.ArrayList;
import java.util.List;

/** 字段定义（表头）：配置定义下的一个动态字段。 */
public class FieldDef {

    public enum FieldType {
        TEXT, TEXTAREA, NUMBER, BOOLEAN, DATE, SELECT, REFERENCE
    }

    /** 字段编码（英文/下划线，配置内唯一） */
    private String code;
    /** 字段名称（中文表头） */
    private String label;
    private FieldType type = FieldType.TEXT;
    /** 是否必填 */
    private boolean required = false;
    /** SELECT 下拉选项 */
    private List<String> options = new ArrayList<>();
    /** NUMBER 最小/最大值（可选） */
    private Double min;
    private Double max;
    /** REFERENCE：引用配置编码 */
    private String refDefCode;
    /** REFERENCE：引用字段编码 */
    private String refFieldCode;
    /** 默认值 */
    private String defaultValue;

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public FieldType getType() { return type; }
    public void setType(FieldType type) { this.type = type; }
    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }
    public List<String> getOptions() { return options; }
    public void setOptions(List<String> options) { this.options = options; }
    public Double getMin() { return min; }
    public void setMin(Double min) { this.min = min; }
    public Double getMax() { return max; }
    public void setMax(Double max) { this.max = max; }
    public String getRefDefCode() { return refDefCode; }
    public void setRefDefCode(String refDefCode) { this.refDefCode = refDefCode; }
    public String getRefFieldCode() { return refFieldCode; }
    public void setRefFieldCode(String refFieldCode) { this.refFieldCode = refFieldCode; }
    public String getDefaultValue() { return defaultValue; }
    public void setDefaultValue(String defaultValue) { this.defaultValue = defaultValue; }
}
