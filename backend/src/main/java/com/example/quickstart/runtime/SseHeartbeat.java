package com.example.quickstart.runtime;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * SSE 心跳与空闲连接清理。
 */
@Component
@RequiredArgsConstructor
public class SseHeartbeat {

    private final SessionRegistry registry;

    @Value("${app.ai.session-ttl-minutes:120}")
    private long sessionTtlMinutes;

    @Scheduled(fixedDelay = 25000)
    public void beat() {
        registry.heartbeat();
    }

    @Scheduled(fixedDelay = 300000)
    public void evict() {
        int n = registry.evictIdle(TimeUnit.MINUTES.toMillis(sessionTtlMinutes));
        if (n > 0) {
            org.slf4j.LoggerFactory.getLogger(SseHeartbeat.class)
                    .info("清理空闲 SSE 连接 {} 个", n);
        }
    }
}
