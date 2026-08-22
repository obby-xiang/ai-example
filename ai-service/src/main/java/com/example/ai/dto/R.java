package com.example.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一响应结构
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class R<T> {
    private int code;
    private String msg;
    private T data;

    public static <T> R<T> ok(T data) {
        return R.<T>builder().code(0).msg("success").data(data).build();
    }

    public static <T> R<T> ok() {
        return R.<T>builder().code(0).msg("success").build();
    }

    public static <T> R<T> ok(String msg, T data) {
        return R.<T>builder().code(0).msg(msg).data(data).build();
    }

    public static <T> R<T> fail(String msg) {
        return R.<T>builder().code(1).msg(msg).build();
    }

    public static <T> R<T> fail(int code, String msg) {
        return R.<T>builder().code(code).msg(msg).build();
    }
}
