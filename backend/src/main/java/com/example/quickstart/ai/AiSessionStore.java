package com.example.quickstart.ai;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 会话内存存储（D9）：ConcurrentHashMap + TTL 定时清理，不持久化。
 * 模型消息历史由 ChatMemory（conversationId=sessionId）承载，此处只保存
 * 展示用消息列表与 FRONTEND 工具暂停现场（pendingCall）。
 */
@Component
public class AiSessionStore {

    /**
     * FRONTEND 工具暂停现场：恢复 Loop 所需的全部信息。
     * roundCalls 为暂停轮全部 toolCalls（含已执行的 BACKEND 与未应答的其余 FRONTEND 调用），
     * completedResponses 为本轮已执行的 BACKEND 工具应答；恢复时按 roundCalls 顺序合成
     * 一条 ToolResponseMessage，未应答的调用补错误 ToolResponse 防 400。
     */
    public record PendingCall(String callId, String name, String argumentsJson, boolean needConfirm,
                              List<AssistantMessage.ToolCall> roundCalls,
                              List<ToolResponseMessage.ToolResponse> completedResponses) {
    }

    public static class AiSession {
        /** 展示用消息：[{role, content, toolName?}] */
        public final List<Map<String, Object>> displayMessages = new ArrayList<>();
        public volatile PendingCall pendingCall;
        public volatile boolean cancelRequested;
        public volatile boolean running;
        public volatile Instant lastAccess = Instant.now();
    }

    private final Map<String, AiSession> sessions = new ConcurrentHashMap<>();
    private final long ttlMinutes;

    public AiSessionStore(@Value("${app.ai.session-ttl-minutes:120}") long ttlMinutes) {
        this.ttlMinutes = ttlMinutes;
    }

    public AiSession getOrCreate(String sessionId) {
        return sessions.computeIfAbsent(sessionId, k -> new AiSession());
    }

    public AiSession find(String sessionId) {
        return sessionId == null ? null : sessions.get(sessionId);
    }

    @Scheduled(fixedDelay = 300_000)
    public void cleanup() {
        Instant cutoff = Instant.now().minus(ttlMinutes, ChronoUnit.MINUTES);
        int before = sessions.size();
        sessions.entrySet().removeIf(e -> !e.getValue().running && e.getValue().lastAccess.isBefore(cutoff));
        int removed = before - sessions.size();
        if (removed > 0) {
            org.slf4j.LoggerFactory.getLogger(AiSessionStore.class)
                    .info("AI 会话 TTL 清理：移除 {} 个过期会话", removed);
        }
    }

    static Map<String, Object> displayMsg(String role, String content, String toolName) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", role);
        msg.put("content", content == null ? "" : content);
        if (toolName != null) {
            msg.put("toolName", toolName);
        }
        return msg;
    }
}
