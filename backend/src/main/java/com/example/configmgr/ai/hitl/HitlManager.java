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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 人在回路（HITL）管理。
 *
 * 时序约定（务必保持）：先 createInteraction 并通过 SSE 把请求发给前端，
 * 再 awaitResponse 阻塞等待。若顺序颠倒，前端收不到请求卡片，只能等到超时。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HitlManager {

    private final AiSessionStore sessionStore;
    private final AppProperties appProperties;

    /** iid -> future，等待用户响应 */
    private final ConcurrentMap<String, CompletableFuture<InteractionRequest.InteractionResult>> futures =
            new ConcurrentHashMap<>();

    /**
     * 创建交互请求，挂到会话上，并返回带 iid 的 DTO。
     * 调用方必须先把该 DTO 通过 SSE 推送给前端，再调用 awaitResponse。
     */
    public InteractionRequest createInteraction(String sessionId, String runId, String toolCallId,
                                                 InteractionRequest.InteractionType type,
                                                 String summary, String details,
                                                 Map<String, Object> params) {
        AiSession session = sessionStore.getOrThrow(sessionId);

        String iid = UUID.randomUUID().toString();
        InteractionRequest request = new InteractionRequest();
        request.setIid(iid);
        request.setRunId(runId);
        request.setToolCallId(toolCallId);
        request.setInteractionType(type);
        request.setSummary(summary);
        request.setDetails(details);
        request.setParams(params);

        session.setPendingInteraction(request);
        futures.put(iid, new CompletableFuture<>());
        return request;
    }

    /**
     * 阻塞等待用户响应（调用方运行在虚拟线程上）。
     * 超时 / 取消 / 异常统一按拒绝处理，保证工具循环永远闭环。
     */
    public InteractionRequest.InteractionResult awaitResponse(String sessionId, InteractionRequest request) {
        CompletableFuture<InteractionRequest.InteractionResult> future = futures.get(request.getIid());
        int timeoutMinutes = appProperties.getAi().getHitlTimeoutMinutes();
        try {
            return future.get(timeoutMinutes, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            log.warn("HITL {} 超时未响应，自动拒绝", request.getIid());
            return rejected("操作超时，已自动拒绝（" + timeoutMinutes + " 分钟未响应）");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return rejected("对话被取消");
        } catch (ExecutionException e) {
            log.warn("HITL {} 等待异常", request.getIid(), e);
            return rejected("交互处理异常: " + e.getMessage());
        } finally {
            futures.remove(request.getIid());
            AiSession session = sessionStore.get(sessionId);
            if (session != null) {
                session.setPendingInteraction(null);
            }
        }
    }

    /**
     * REST 端点调用：提交用户对交互请求的响应。
     */
    public void submitResponse(String sessionId, String iid,
                                boolean approved, String reason,
                                Map<String, Object> data) {
        AiSession session = sessionStore.getOrThrow(sessionId);
        InteractionRequest pending = session.getPendingInteraction();
        if (pending == null || !pending.getIid().equals(iid)) {
            throw new IllegalStateException("没有待处理的交互请求（iid: " + iid + "），可能已超时或已处理");
        }

        CompletableFuture<InteractionRequest.InteractionResult> future = futures.get(iid);
        if (future == null) {
            throw new IllegalStateException("交互请求已失效（iid: " + iid + "）");
        }

        InteractionRequest.InteractionResult result = new InteractionRequest.InteractionResult();
        result.setApproved(approved);
        result.setRejectionReason(reason);
        result.setData(data);
        future.complete(result);
    }

    private InteractionRequest.InteractionResult rejected(String reason) {
        InteractionRequest.InteractionResult result = new InteractionRequest.InteractionResult();
        result.setApproved(false);
        result.setRejectionReason(reason);
        return result;
    }
}
