package com.example.configadmin.common;

import java.util.Map;

/**
 * 统一响应包装。code=0 成功；非 0 失败。
 */
public record R<T>(int code, String msg, T data) {

    public static <T> R<T> ok(T data) {
        return new R<>(0, "success", data);
    }

    public static R<Map<String, Object>> ok() {
        return new R<>(0, "success", Map.of());
    }

    public static <T> R<T> fail(String msg) {
        return new R<>(500, msg, null);
    }

    public static <T> R<T> fail(int code, String msg) {
        return new R<>(code, msg, null);
    }
}
