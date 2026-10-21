package com.example.configmgr.common;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 错误响应体的唯一出口：把 {@code code} + 参数 + locale 变成"给前端/脚本看的 message"。
 *
 * <p>
 * 两条硬口径（写在这里，避免各 handler 各写一套）：
 * <ol>
 * <li><b>zh 逐字不变</b>：中文（默认 locale）下 message = 调用方原文。存量 29 条 E2E 与
 * 前端断言按中文文案判定，任何"顺手把硬编码搬进 messages_zh 再回读"的做法都会改字节。</li>
 * <li><b>en 只做码级兜底</b>：非中文 locale 下取 {@code messages_en.properties} 的
 * {@code error.<CODE>}；没有该键就回落调用方原文（**绝不回显裸 key**）。动态文案
 * （"任务 #12 已是终态"）仍以原文为准——服务端不重排业务参数。</li>
 * </ol>
 *
 * <p>
 * {@code args} / {@code traceId} 为 {@code NON_NULL} 字段：存量响应不新增键，字节不变。
 */
@Component
public class ErrorBodyFactory {

    /** messages 键前缀（{@code error.TASK_FINALIZED}）。 */
    public static final String KEY_PREFIX = "error.";

    private final ObjectProvider<org.springframework.context.MessageSource> messageSource;

    public ErrorBodyFactory(ObjectProvider<org.springframework.context.MessageSource> messageSource) {
        this.messageSource = messageSource;
    }

    /** 业务信封形态（默认形态，字节与迁移前一致）。 */
    public ApiResponse<Object> envelope(String code, String fallbackMessage, Map<String, Object> args, Locale locale) {
        ApiResponse<Object> body = ApiResponse.error(code, message(code, fallbackMessage, args, locale));
        if (args != null && !args.isEmpty()) {
            body.setArgs(args);
        }
        return body;
    }

    /** 无码异常的类型缺省码：{@code IllegalArgumentException} → ILLEGAL_ARGUMENT 等。 */
    public static String codeOf(Throwable error, String fallbackCode) {
        if (error instanceof CodedException coded && coded.getCode() != null && !coded.getCode().isBlank()) {
            return coded.getCode();
        }
        return fallbackCode;
    }

    /** 本地化 message；缺键一律回落调用方原文。 */
    public String message(String code, String fallbackMessage, Map<String, Object> args, Locale locale) {
        if (locale == null || isChinese(locale)) {
            return fallbackMessage == null ? "" : fallbackMessage;
        }
        String localized = lookup(KEY_PREFIX + code, args, locale);
        if (localized != null) {
            return localized;
        }
        return fallbackMessage == null ? "" : fallbackMessage;
    }

    /**
     * RFC 9457 形态（仅在请求 {@code Accept} 含 {@code application/problem+json} 时使用）。
     *
     * <p>默认形态不变：{@code spring.mvc.problemdetails.enabled} 保持不设，Spring 内建异常的
     * 响应体仍是信封（开了会把 404/405 整体换形，直接打红 E2E 的形态断言）。
     */
    public ProblemDetail problem(String code, HttpStatus status, String fallbackMessage,
            Map<String, Object> args, String instance, String traceId, Locale locale) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, message(code, fallbackMessage, args, locale));
        detail.setType(URI.create("urn:cfg:error:" + code));
        detail.setTitle(lookup((KEY_PREFIX + code + ".title"), args, locale) != null
                ? lookup((KEY_PREFIX + code + ".title"), args, locale)
                : code);
        if (instance != null) {
            detail.setInstance(URI.create(instance));
        }
        detail.setProperty("code", code);
        if (args != null && !args.isEmpty()) {
            detail.setProperty("args", new LinkedHashMap<>(args));
        }
        if (traceId != null) {
            detail.setProperty("traceId", traceId);
        }
        return detail;
    }

    private String lookup(String key, Map<String, Object> args, Locale locale) {
        var source = messageSource.getIfAvailable();
        if (source == null || key == null) {
            return null;
        }
        try {
            Object[] values = args == null ? null : args.values().toArray();
            return source.getMessage(key, values, locale);
        } catch (org.springframework.context.NoSuchMessageException e) {
            return null;
        }
    }

    private static boolean isChinese(Locale locale) {
        return "zh".equalsIgnoreCase(locale.getLanguage());
    }
}
