package com.example.configadmin.dto;

/** 导出文件清单元素。 */
public class ExportFileEntry {

    private String defCode;
    private String defName;
    /** 文件名：{defCode}.xlsx（可直接作为导入文件回传，形成导出→导入闭环） */
    private String fileName;
    private long size;
    private int rowCount;
    private String level;

    public String getDefCode() { return defCode; }
    public void setDefCode(String defCode) { this.defCode = defCode; }
    public String getDefName() { return defName; }
    public void setDefName(String defName) { this.defName = defName; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }
    public int getRowCount() { return rowCount; }
    public void setRowCount(int rowCount) { this.rowCount = rowCount; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
}
