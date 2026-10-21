package com.example.configmgr.ai.config;

/**
 * AI 运行链路"这次真的取不到模型实例"（无可用 provider / 快照为空 / 进程内没有 KEK 解 key）。
 *
 * <p>
 * 只在<b>控制器已经放行</b>之后的极窄窗口里可能抛（例如并发改配置把 provider 停用）：
 * 这时候给它一个带稳定 code 的异常，比抛裸 {@code IllegalStateException} 更利于排障。
 */
public class AiUnavailableException extends RuntimeException {

    private final String code;

    private final String reason;

    public AiUnavailableException(String code, String reason) {
        super(reason);
        this.code = code;
        this.reason = reason;
    }

    public String getCode() {
        return this.code;
    }

    public String getReason() {
        return this.reason;
    }
}
