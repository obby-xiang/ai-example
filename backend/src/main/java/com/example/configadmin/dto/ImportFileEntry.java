package com.example.configadmin.dto;

import java.util.ArrayList;
import java.util.List;

/** 单个导入文件状态（批次 filesJson 的元素）。 */
public class ImportFileEntry {

    /** 配置编码 */
    private String defCode;
    private String defName;
    /** 上传文件名（含扩展名） */
    private String fileName;
    /** UPLOADED / CHECKED / IMPORTED / PUBLISHED / ERROR / SKIPPED */
    private String status = "UPLOADED";
    private int rowCount = 0;
    private int errorCount = 0;
    private int warnCount = 0;
    private String message = "";
    /** 错误明细 JSON 落盘路径（相对 import-dir） */
    private String detailPath;
    /** 检查/导入/发布的结论摘要（错误样例） */
    private List<Issue> sampleIssues = new ArrayList<>();

    public String getDefCode() { return defCode; }
    public void setDefCode(String defCode) { this.defCode = defCode; }
    public String getDefName() { return defName; }
    public void setDefName(String defName) { this.defName = defName; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getRowCount() { return rowCount; }
    public void setRowCount(int rowCount) { this.rowCount = rowCount; }
    public int getErrorCount() { return errorCount; }
    public void setErrorCount(int errorCount) { this.errorCount = errorCount; }
    public int getWarnCount() { return warnCount; }
    public void setWarnCount(int warnCount) { this.warnCount = warnCount; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getDetailPath() { return detailPath; }
    public void setDetailPath(String detailPath) { this.detailPath = detailPath; }
    public List<Issue> getSampleIssues() { return sampleIssues; }
    public void setSampleIssues(List<Issue> sampleIssues) { this.sampleIssues = sampleIssues; }
}
