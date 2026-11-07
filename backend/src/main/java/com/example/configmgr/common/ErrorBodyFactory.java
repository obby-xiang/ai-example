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
 * 中文 locale 使用调用方已确认安全的原文和动态业务文案，保留任务号、定义编码、字段编码等标识；
 * 非中文 locale 优先取 {@code messages_en.properties} 的 {@code error.<CODE>}，缺键才回落安全原文。
 * 底层异常消息只能由调用方按类型和安全白名单显式传入，工厂不做任意异常原文透出。
 *
 * <p>
 * {@code args} / {@code traceId} 为 {@code NON_NULL} 字段：不新增响应键，公开形状不变。
 */
@Component
public class ErrorBodyFactory {

    /** messages 键前缀（{@code error.TASK_FINALIZED}）。 */
    public static final String KEY_PREFIX = "error.";

    private final ObjectProvider<org.springframework.context.MessageSource> messageSource;

    public ErrorBodyFactory(ObjectProvider<org.springframework.context.MessageSource> messageSource) {
        this.messageSource = messageSource;
    }

    /** 业务信封形态（默认形态，字段形状不变）。 */
    public ApiResponse<Object> envelope(String code, String fallbackMessage, Map<String, Object> args, Locale locale) {
        return envelope(code, fallbackMessage, args, locale, true);
    }

    /** 同上；{@code localizeByCode=false} 时保留调用方已本地化的独立文案。 */
    public ApiResponse<Object> envelope(String code, String fallbackMessage, Map<String, Object> args, Locale locale,
            boolean localizeByCode) {
        ApiResponse<Object> body = ApiResponse.error(code,
                message(code, fallbackMessage, args, locale, localizeByCode));
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

    /**
     * 本地化 message；中文保留调用方安全动态文案，非中文缺键时回落同一安全文案。
     *
     * <p>{@code args} 保留在签名里兼容既有调用点：码级文案不含 {@code MessageFormat} 占位符，
     * 格式化参数不会改变输出；结构化参数仍由 {@link #envelope}/{@link #problem} 原样回填。
     */
    public String message(String code, String fallbackMessage, Map<String, Object> args, Locale locale) {
        return message(code, fallbackMessage, args, locale, true);
    }

    /** 同上；{@code localizeByCode=false} 用于按 HTTP 状态等独立语义选择的本地化文案。 */
    public String message(String code, String fallbackMessage, Map<String, Object> args, Locale locale,
            boolean localizeByCode) {
        if (isChinese(locale) || !localizeByCode) {
            return fallbackMessage == null ? "" : fallbackMessage;
        }
        String localized = lookup(KEY_PREFIX + code, locale);
        if (localized != null) {
            return localized;
        }
        return fallbackMessage == null ? "" : fallbackMessage;
    }

    static boolean isChinese(Locale locale) {
        return locale == null || "zh".equalsIgnoreCase(locale.getLanguage());
    }

    /**
     * RFC 9457 形态（仅在请求 {@code Accept} 含 {@code application/problem+json} 时使用）。
     *
     * <p>默认形态不变：{@code spring.mvc.problemdetails.enabled} 保持不设，Spring 内建异常的
     * 响应体仍是信封（开了会把 404/405 整体换形，直接打红 E2E 的形态断言）。
     */
    public ProblemDetail problem(String code, HttpStatus status, String fallbackMessage,
            Map<String, Object> args, String instance, String traceId, Locale locale) {
        return problem(code, status, fallbackMessage, args, instance, traceId, locale, true);
    }

    /** 同上；{@code localizeByCode=false} 时保留调用方已本地化的独立文案。 */
    public ProblemDetail problem(String code, HttpStatus status, String fallbackMessage,
            Map<String, Object> args, String instance, String traceId, Locale locale, boolean localizeByCode) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status,
                message(code, fallbackMessage, args, locale, localizeByCode));
        detail.setType(URI.create("urn:cfg:error:" + code));
        // 码级文案没有 .title 键：标题固定用稳定码，不再做必然落空的查找。
        detail.setTitle(code);
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

    private String lookup(String key, Locale locale) {
        var source = messageSource.getIfAvailable();
        if (source == null || key == null) {
            return null;
        }
        try {
            return source.getMessage(key, null, locale);
        } catch (org.springframework.context.NoSuchMessageException e) {
            return null;
        }
    }

    /**
     * 当前请求 locale（无上下文时硬编码 zh-CN，不回落 JVM 默认 —— CI 是 en-US 机）。
     *
     * <p>与 {@code GlobalExceptionHandler} 的拒绝出口同一取值口径：单一源放在这里，
     * 直接构造 controller 的调用（无 locale 上下文）也得到中文安全动态文案。
     */
    public static Locale requestLocale() {
        org.springframework.context.i18n.LocaleContext context =
                org.springframework.context.i18n.LocaleContextHolder.getLocaleContext();
        Locale locale = context == null ? null : context.getLocale();
        return locale == null ? Locale.SIMPLIFIED_CHINESE : locale;
    }
}
