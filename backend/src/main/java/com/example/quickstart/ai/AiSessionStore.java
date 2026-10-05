package com.example.quickstart.ai;

import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionMessage;
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
 * 会话保存模型消息列表（不含 system，system 每次按当前上下文重建）、
 * 展示用消息列表、以及 FRONTEND 工具暂停现场（pendingCall）。
 */
@Component
public class AiSessionStore {

    /** FRONTEND 工具暂停现场：恢复 Loop 所需的全部信息 */
    public record PendingCall(String callId, String name, String argumentsJson, boolean needConfirm) {
    }

    public static class AiSession {
        /** 模型消息（user/assistant/tool，不含 system） */
        public final List<ChatCompletionMessage> messages = new ArrayList<>();
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
