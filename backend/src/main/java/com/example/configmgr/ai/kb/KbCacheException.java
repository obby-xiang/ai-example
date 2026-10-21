package com.example.configmgr.ai.kb;

/** 缓存层不可用（Redis 宕机/超时/序列化失败）。由 {@code CachedKbSource} 降级直连知识源。 */
public class KbCacheException extends RuntimeException {

    public KbCacheException(String message, Throwable cause) {
        super(message, cause);
    }

    public KbCacheException(String message) {
        super(message);
    }
}
