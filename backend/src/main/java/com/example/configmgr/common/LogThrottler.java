package com.example.configmgr.common;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.slf4j.Logger;

import java.time.Duration;

public final class LogThrottler {

    private static final Cache<String, Boolean> RECENT_ENTRIES = CacheBuilder.newBuilder()
            .maximumSize(1024)
            .expireAfterWrite(Duration.ofMinutes(1))
            .build();

    private LogThrottler() {
    }

    public static void warn(Logger logger, String key, String message, Object... args) {
        if (RECENT_ENTRIES.asMap().putIfAbsent(key, Boolean.TRUE) == null) {
            logger.warn(message, args);
        }
    }

    public static void error(Logger logger, String key, String message, Object... args) {
        if (RECENT_ENTRIES.asMap().putIfAbsent(key, Boolean.TRUE) == null) {
            logger.error(message, args);
        }
    }
}
