package com.example.configmgr.common;

/**
 * 业务冲突（HTTP 409）。带一个机器可读的 {@code code}（如 {@code JOB_ALREADY_FINAL}），
 * 供前端按码分支处理、也供验证取证"不是靠文案猜的"。
 */
public class ConflictException extends RuntimeException {

    private final String code;

    public ConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
