package com.example.quickstart.ai;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 会话注册表：会话互斥（同会话同时只跑一轮对话）+ TTL 清理（连记忆一起清）。
 */
@Slf4j
@Component
public class AiSessionRegistry {

    @Getter
    public static class AiSession {
        private final String id;
        private final long createdAt = System.currentTimeMillis();
        private volatile long lastAccess = System.currentTimeMillis();
        private final AtomicBoolean chatting = new AtomicBoolean(false);

        public AiSession(String id) {
            this.id = id;
        }

        public void touch() {
            this.lastAccess = System.currentTimeMillis();
        }
    }

    private final Map<String, AiSession> sessions = new ConcurrentHashMap<>();
    private final ChatMemory chatMemory;

    @Value("${app.ai.session-ttl-minutes:120}")
    private long ttlMinutes;

    public AiSessionRegistry(ChatMemory chatMemory) {
        this.chatMemory = chatMemory;
    }

    public AiSession getOrCreate(String sessionId) {
        return sessions.computeIfAbsent(sessionId, AiSession::new);
    }

    public AiSession peek(String sessionId) {
        AiSession s = sessions.get(sessionId);
        if (s != null) {
            s.touch();
        }
        return s;
    }

    public boolean tryBeginChat(String sessionId) {
        AiSession s = getOrCreate(sessionId);
        s.touch();
        return s.getChatting().compareAndSet(false, true);
    }

    public void endChat(String sessionId) {
        AiSession s = sessions.get(sessionId);
        if (s != null) {
            s.getChatting().set(false);
            s.touch();
        }
    }

    public void clearHistory(String sessionId) {
        chatMemory.clear(sessionId);
    }

    @Scheduled(fixedDelay = 300000)
    public void evictIdle() {
        long ttl = TimeUnit.MINUTES.toMillis(ttlMinutes);
        long now = System.currentTimeMillis();
        for (var it = sessions.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (now - e.getValue().getLastAccess() > ttl) {
                try {
                    chatMemory.clear(e.getKey());
                } catch (Exception ignored) {
                }
                it.remove();
                log.debug("AI 会话过期清理：{}", e.getKey());
            }
        }
    }
}
