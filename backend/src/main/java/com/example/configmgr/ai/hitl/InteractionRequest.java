package com.example.configmgr.ai.hitl;

import lombok.Data;

import java.util.Map;

/**
 * Human-in-the-loop interaction request（纯 DTO，可安全序列化）。
 * 等待响应的 CompletableFuture 由 HitlManager 单独持有，不放在这里。
 */
@Data
public class InteractionRequest {

    private String iid;
    private String runId;
    private String toolCallId;
    private InteractionType interactionType;
    private String summary;
    private String details;
    private Map<String, Object> params;

    public enum InteractionType {
        CONFIRM,   // approve/reject dangerous action
        CHOICE,    // pick one of several options
        UPLOAD,    // request file upload
        INFO       // informational (no response needed)
    }

    @Data
    public static class InteractionResult {
        private boolean approved;
        private String rejectionReason;
        private Map<String, Object> data;
    }
}
