package com.example.configmgr.ai.hitl;

import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Human-in-the-loop interaction request.
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

    // The future to complete when user responds
    private transient CompletableFuture<InteractionResult> future;

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
