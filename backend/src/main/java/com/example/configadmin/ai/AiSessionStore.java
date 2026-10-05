package com.example.configadmin.ai;

import com.example.configadmin.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 会话存储：内存 Map + 空闲超时清理（不持久化，符合“页签会话”需求）。 */
@Component
public class AiSessionStore {

    private static final Logger log = LoggerFactory.getLogger(AiSessionStore.class);

    private final Map<String, AiSession> sessions = new ConcurrentHashMap<>();

    @Value("${app.ai.session-ttl-minutes:30}")
    private long ttlMinutes;

    public AiSession getOrCreate(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || sessionId.length() > 64) {
            throw ApiException.badRequest("会话标识无效");
        }
        return sessions.computeIfAbsent(sessionId, AiSession::new);
    }

    public AiSession get(String sessionId) {
        AiSession s = sessions.get(sessionId);
        if (s == null) {
            throw ApiException.notFound("会话不存在或已过期：" + sessionId);
        }
        return s;
    }

    public void remove(String sessionId) {
        sessions.remove(sessionId);
    }

    public int size() {
        return sessions.size();
    }

    /** 每分钟清理空闲超时会话。 */
    @Scheduled(fixedDelay = 60_000)
    public void cleanup() {
        LocalDateTime threshold = LocalDateTime.now().minus(Duration.ofMinutes(ttlMinutes));
        int before = sessions.size();
        sessions.entrySet().removeIf(e -> e.getValue().getLastAccess().isBefore(threshold));
        int removed = before - sessions.size();
        if (removed > 0) {
            log.info("清理空闲 AI 会话 {} 个（剩余 {}）", removed, sessions.size());
        }
    }
}
