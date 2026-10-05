package com.example.configadmin.dto;

/** 校验问题（检查/导入/发布阶段的明细条目）。 */
public class Issue {

    /** ERROR(阻断) / WARN(警告不阻断) */
    private String level;
    /** Excel 中的行号（1 为表头，数据从 2 开始） */
    private int rowIndex;
    /** 字段编码（或表头名） */
    private String field;
    private String message;
    private String value;

    public Issue() {
    }

    public Issue(String level, int rowIndex, String field, String message, String value) {
        this.level = level;
        this.rowIndex = rowIndex;
        this.field = field;
        this.message = message;
        this.value = value;
    }

    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public int getRowIndex() { return rowIndex; }
    public void setRowIndex(int rowIndex) { this.rowIndex = rowIndex; }
    public String getField() { return field; }
    public void setField(String field) { this.field = field; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
}
