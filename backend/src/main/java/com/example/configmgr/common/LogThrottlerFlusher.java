package com.example.configmgr.common;

import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class LogThrottlerFlusher {

    @Scheduled(fixedDelayString = "PT1M")
    public void flush() {
        LogThrottler.flush();
    }

    @PreDestroy
    public void close() {
        LogThrottler.flush();
    }
}
