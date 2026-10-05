package com.example.quickstart.runtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话注册表：管理每个浏览器页签的 SSE 连接。
 * 职责仅是"把事件送达某个会话"，不持有业务状态（解耦）。
 */
@Slf4j
@Component
public class SessionRegistry {

    public static final long SSE_TIMEOUT_MS = 0L;

    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final Map<String, Long> lastAccess = new ConcurrentHashMap<>();

    public SseEmitter register(String sessionId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitters.put(sessionId, emitter);
        touch(sessionId);
        emitter.onCompletion(() -> emitters.remove(sessionId));
        emitter.onTimeout(() -> emitters.remove(sessionId));
        emitter.onError(e -> emitters.remove(sessionId));
        log.debug("SSE 会话注册：{}", sessionId);
        return emitter;
    }

    public void touch(String sessionId) {
        lastAccess.put(sessionId, System.currentTimeMillis());
    }

    public void send(String sessionId, String event, Object data) {
        SseEmitter emitter = emitters.get(sessionId);
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event)
                    .data(com.example.quickstart.common.JsonUtil.write(data)));
            touch(sessionId);
        } catch (Exception e) {
            log.debug("SSE 发送失败（会话可能已关闭）：{} {}", sessionId, e.getMessage());
            emitters.remove(sessionId);
        }
    }

    public void sendAll(String event, Object data) {
        for (String sessionId : emitters.keySet()) {
            send(sessionId, event, data);
        }
    }

    public void heartbeat() {
        for (String sessionId : emitters.keySet()) {
            send(sessionId, "ping", Map.of("t", System.currentTimeMillis()));
        }
    }

    /** 清理空闲会话连接（不清理 AI 记忆，那是 ChatMemory 的职责） */
    public int evictIdle(long ttlMs) {
        long now = System.currentTimeMillis();
        int removed = 0;
        for (var it = lastAccess.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (now - e.getValue() > ttlMs) {
                SseEmitter emitter = emitters.remove(e.getKey());
                it.remove();
                if (emitter != null) {
                    try {
                        emitter.complete();
                    } catch (Exception ignored) {
                    }
                }
                removed++;
            }
        }
        return removed;
    }
}
