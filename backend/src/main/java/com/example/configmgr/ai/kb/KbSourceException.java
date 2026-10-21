package com.example.configmgr.ai.kb;

/**
 * 知识源不可用（manifest 拉不到/解析失败/文档正文拉不到/HTTP 非 2xx）。
 *
 * <p>消息只允许包含<b>可行动的中文原因 + HTTP 状态码</b>：不得包含鉴权头、URL 凭据或响应体原文
 * （{@code HttpKbSource} 的日志/错误面同此口径，用例锁定）。
 */
public class KbSourceException extends Exception {

    public KbSourceException(String message) {
        super(message);
    }

    public KbSourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
