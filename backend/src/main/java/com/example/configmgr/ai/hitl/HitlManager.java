package com.example.configmgr.ai.hitl;

import com.example.configmgr.ai.session.AiSession;
import com.example.configmgr.ai.session.AiSessionStore;
import com.example.configmgr.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
@RequiredArgsConstructor
public class HitlManager {

    private final AiSessionStore sessionStore;
    private final AppProperties appProperties;

    /**
     * Creates a pending interaction request, suspends the calling thread, and waits for user response.
     * Must be called from a virtual thread (blocking is OK).
     */
    public InteractionRequest.InteractionResult awaitInteraction(
            String sessionId, String runId, String toolCallId,
            InteractionRequest.InteractionType type,
            String summary, String details, Map<String, Object> params) throws Exception {

        AiSession session = sessionStore.getOrThrow(sessionId);

        String iid = UUID.randomUUID().toString();
        CompletableFuture<InteractionRequest.InteractionResult> future = new CompletableFuture<>();

        InteractionRequest request = new InteractionRequest();
        request.setIid(iid);
        request.setRunId(runId);
        request.setToolCallId(toolCallId);
        request.setInteractionType(type);
        request.setSummary(summary);
        request.setDetails(details);
        request.setParams(params);
        request.setFuture(future);

        session.setPendingInteraction(request);

        try {
            int timeoutMinutes = appProperties.getAi().getHitlTimeoutMinutes();
            return future.get(timeoutMinutes, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            session.setPendingInteraction(null);
            InteractionRequest.InteractionResult rejected = new InteractionRequest.InteractionResult();
            rejected.setApproved(false);
            rejected.setRejectionReason("操作超时，已自动拒绝（" + appProperties.getAi().getHitlTimeoutMinutes() + " 分钟未响应）");
            return rejected;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            InteractionRequest.InteractionResult rejected = new InteractionRequest.InteractionResult();
            rejected.setApproved(false);
            rejected.setRejectionReason("对话被取消");
            return rejected;
        } finally {
            session.setPendingInteraction(null);
        }
    }

    /**
     * Called by the REST endpoint when user submits their response.
     */
    public void submitResponse(String sessionId, String iid,
                                boolean approved, String reason,
                                Map<String, Object> data) {
        AiSession session = sessionStore.getOrThrow(sessionId);
        InteractionRequest pending = session.getPendingInteraction();
        if (pending == null || !pending.getIid().equals(iid)) {
            throw new IllegalStateException("没有待处理的交互请求（iid: " + iid + "），可能已超时");
        }

        InteractionRequest.InteractionResult result = new InteractionRequest.InteractionResult();
        result.setApproved(approved);
        result.setRejectionReason(reason);
        result.setData(data);
        pending.getFuture().complete(result);
    }
}
