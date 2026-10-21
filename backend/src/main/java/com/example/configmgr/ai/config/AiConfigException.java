package com.example.configmgr.ai.config;

import org.springframework.http.HttpStatus;

/**
 * 配置写路径的业务异常：只带<b>稳定 code</b> + 中文兜底 message（设计稿 §3.5 的稳定错误码表）。
 *
 * <p>
 * HTTP 状态由 {@link #getStatus()} 统一给出（控制器不再各自 if/else）：400 = 入参/环境不满足，
 * 404 = 目标不存在，409 = 与现状冲突（重名/仍被引用/不可原地改）。前端只认 {@code code}，
 * {@code message} 是给日志与 curl 的中文兜底（i18n 口径：文案由前端按 code 查表）。
 */
public class AiConfigException extends RuntimeException {

    private final String code;

    public AiConfigException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return this.code;
    }

    /** code → HTTP 状态（未知 code 按 400：宁可客户端少重试，也不落 500）。 */
    public HttpStatus getStatus() {
        return switch (this.code) {
            case AiConfigWriter.CODE_PROVIDER_NOT_FOUND, AiConfigWriter.CODE_MODEL_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case AiConfigWriter.CODE_PROVIDER_IN_USE, AiConfigWriter.CODE_PROVIDER_EXISTS,
                    AiConfigWriter.CODE_MODEL_EXISTS, AiConfigWriter.CODE_CODE_IMMUTABLE ->
                HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
