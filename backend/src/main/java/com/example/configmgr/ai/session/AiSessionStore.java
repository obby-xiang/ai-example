package com.example.configmgr.ai.session;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.example.configmgr.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class AiSessionStore {

    private final Cache<String, AiSession> cache;

    public AiSessionStore(AppProperties props) {
        int ttlMinutes = props.getAi().getSessionTtlMinutes();
        this.cache = Caffeine.newBuilder()
                .expireAfterAccess(ttlMinutes, TimeUnit.MINUTES)
                .maximumSize(1000)
                .build();
    }

    public AiSession create() {
        AiSession session = new AiSession(UUID.randomUUID().toString());
        cache.put(session.getId(), session);
        return session;
    }

    public AiSession get(String id) {
        AiSession session = cache.getIfPresent(id);
        if (session != null) session.touch();
        return session;
    }

    public AiSession getOrThrow(String id) {
        AiSession session = get(id);
        if (session == null) {
            throw new com.example.configmgr.common.ResourceNotFoundException("会话不存在或已过期，请刷新页面重新开始");
        }
        return session;
    }

    public void remove(String id) {
        cache.invalidate(id);
    }
}
