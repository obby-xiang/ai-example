package com.example.configmgr.common;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalCause;
import com.google.common.cache.RemovalNotification;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public final class LogThrottler {

    private static final long SUPPRESS = -1L;

    private static final long WINDOW_NANOS = Duration.ofMinutes(1).toNanos();

    private static final Cache<String, Window> WINDOWS = CacheBuilder.newBuilder()
            .maximumSize(1024)
            .removalListener(LogThrottler::flushRemovedWindow)
            .build();

    private LogThrottler() {
    }

    public static void warn(Logger logger, String key, String message, Object... args) {
        write(logger, key, message, args, false);
    }

    public static void error(Logger logger, String key, String message, Object... args) {
        write(logger, key, message, args, true);
    }

    public static void flush() {
        WINDOWS.invalidateAll();
    }

    private static void write(Logger logger, String key, String message, Object[] args, boolean error) {
        long suppressedCount = nextSuppressedCount(key);
        if (suppressedCount == SUPPRESS) {
            return;
        }
        if (suppressedCount == 0) {
            if (error) {
                logger.error(message, args);
            } else {
                logger.warn(message, args);
            }
            return;
        }
        String messageWithCount = message + " suppressedCount={}";
        Object[] argsWithCount = withSuppressedCount(args, suppressedCount);
        if (error) {
            logger.error(messageWithCount, argsWithCount);
        } else {
            logger.warn(messageWithCount, argsWithCount);
        }
    }

    private static long nextSuppressedCount(String key) {
        long now = System.nanoTime();
        AtomicLong previousSuppressed = new AtomicLong(SUPPRESS);
        WINDOWS.asMap().compute(key, (ignored, current) -> {
            if (current == null || now - current.startedAtNanos >= WINDOW_NANOS) {
                previousSuppressed.set(current == null ? 0 : current.suppressedCount);
                return new Window(now);
            }
            if (current.suppressedCount < Long.MAX_VALUE) {
                current.suppressedCount++;
            }
            return current;
        });
        return previousSuppressed.get();
    }

    private static void flushRemovedWindow(RemovalNotification<Object, Object> notification) {
        if (notification.getCause() == RemovalCause.REPLACED) {
            return;
        }
        Window window = (Window) notification.getValue();
        if (window != null && window.suppressedCount > 0) {
            log.warn("Log throttle window removed key={} suppressedCount={}", notification.getKey(), window.suppressedCount);
        }
    }

    private static Object[] withSuppressedCount(Object[] args, long suppressedCount) {
        int length = args == null ? 0 : args.length;
        Object[] result = new Object[length + 1];
        boolean hasThrowable = length > 0 && args[length - 1] instanceof Throwable;
        int countIndex = hasThrowable ? length - 1 : length;
        if (countIndex > 0) {
            System.arraycopy(args, 0, result, 0, countIndex);
        }
        result[countIndex] = suppressedCount;
        if (hasThrowable) {
            result[length] = args[length - 1];
        }
        return result;
    }

    private static final class Window {

        private final long startedAtNanos;

        private long suppressedCount;

        private Window(long startedAtNanos) {
            this.startedAtNanos = startedAtNanos;
        }
    }
}
