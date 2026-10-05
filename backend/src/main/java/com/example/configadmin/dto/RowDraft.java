package com.example.configadmin.dto;

import java.util.HashMap;
import java.util.Map;

/** 一行配置数据（新增/更新请求体或查询结果行）。 */
public class RowDraft {

    private Long id;
    /** 范围（REGION/PROJECT 必填） */
    private String scope;
    /** 业务数据：Map<字段编码, 值> */
    private Map<String, Object> data = new HashMap<>();
    private boolean published = true;
    private String batchId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public Map<String, Object> getData() { return data; }
    public void setData(Map<String, Object> data) { this.data = data; }
    public boolean isPublished() { return published; }
    public void setPublished(boolean published) { this.published = published; }
    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
}
