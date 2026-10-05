package com.example.configadmin.dto;

import java.util.HashMap;
import java.util.Map;

/** 进度事件：SSE 推送 + 任务快照中的统一结构。 */
public class ProgressEvent {

    private String status;
    private int progress;
    private String message;
    private Map<String, Object> detail = new HashMap<>();

    public ProgressEvent() {
    }

    public ProgressEvent(String status, int progress, String message) {
        this.status = status;
        this.progress = progress;
        this.message = message;
    }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getProgress() { return progress; }
    public void setProgress(int progress) { this.progress = progress; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Map<String, Object> getDetail() { return detail; }
    public void setDetail(Map<String, Object> detail) { this.detail = detail; }
}
