package com.example.configmgr.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Locale;
import java.util.Map;

/**
 * 全局异常出口。
 *
 * <p>
 * 两个契约（本批新增，见 {@code ErrorCodes} / {@code ErrorBodyFactory}）：
 * <ol>
 * <li><b>错误响应必带 {@code code}</b>：带码异常用自己的码；无码的
 * {@code IllegalArgumentException/IllegalStateException} 按类型给稳定缺省码
 * （{@code ILLEGAL_ARGUMENT} / {@code ILLEGAL_STATE}），不再出现"只有中文 message、前端只能猜"的形态。</li>
 * <li><b>形态默认不变</b>：只有请求 {@code Accept} 含 {@code application/problem+json} 时才回
 * RFC 9457；其余一律业务信封。存量字节不变（{@code args}/{@code traceId} 是 NON_NULL）。</li>
 * </ol>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final ErrorBodyFactory bodyFactory;

    public GlobalExceptionHandler(ErrorBodyFactory bodyFactory) {
        this.bodyFactory = bodyFactory;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleIllegalArg(IllegalArgumentException e, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, ErrorBodyFactory.codeOf(e, ErrorCodes.ILLEGAL_ARGUMENT),
                e.getMessage(), null, request);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> handleIllegalState(IllegalStateException e, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, ErrorBodyFactory.codeOf(e, ErrorCodes.ILLEGAL_STATE),
                e.getMessage(), null, request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleValidation(MethodArgumentNotValidException e, HttpServletRequest request) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst().orElse("参数校验失败");
        return respond(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED, msg, null, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<?> handleConstraint(ConstraintViolationException e, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED, e.getMessage(), null, request);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<?> handleNotFound(ResourceNotFoundException e, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, e.getCode(), e.getMessage(), null, request);
    }

    /** 业务冲突 → 409 + 机器可读错误码（ADR-8 W1 边界：取消已终态作业返回 409 JOB_ALREADY_FINAL）。 */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<?> handleConflict(ConflictException e, HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, e.getCode(), e.getMessage(), null, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleGeneral(Exception e, HttpServletRequest request,
            HttpServletResponse response) {
        // Q8②：SSE 响应的内容类型在开流时已被固定为 text/event-stream（帧正在写）。这类请求上
        // 冒出来的异常（典型：客户端断开后写帧）若再以 ApiResponse(JSON) 写回，必然会抛
        // HttpMessageNotWritableException: No converter for [... ApiResponse] with preset
        // Content-Type 'text/event-stream' —— 除了把日志刷成噪音，什么也送达不了客户端
        // （连接已断）。故识别出来只记日志、不写体。
        if (isStreamingResponse(response)) {
            log.warn("SSE 响应上的未处理异常（内容类型 text/event-stream 或响应已提交，跳过 JSON 写回）uri={}：{}",
                    request.getRequestURI(), e.toString());
            log.debug("SSE 响应上的未处理异常（栈）uri={}", request.getRequestURI(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        log.error("Unhandled exception", e);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.INTERNAL_ERROR,
                "服务器内部错误: " + e.getMessage(), null, request);
    }

    /**
     * 统一出口：内容协商（problem+json vs 信封）+ locale（请求线程锁定，**不在池线程读**）+
     * traceId（沿用 servlet 容器已分配的请求 id，无则不塞键）。
     */
    private ResponseEntity<?> respond(HttpStatus status, String code, String message,
            Map<String, Object> args, HttpServletRequest request) {
        Locale locale = requestLocale();
        String traceId = request == null ? null : traceIdOf(request);
        if (wantsProblemJson(request)) {
            ProblemDetail problem = bodyFactory.problem(code, status, message, args,
                    request == null ? null : request.getRequestURI(), traceId, locale);
            return ResponseEntity.status(status)
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(problem);
        }
        ApiResponse<Object> body = bodyFactory.envelope(code, message, args, locale);
        if (traceId != null) {
            body.setTraceId(traceId);
        }
        return ResponseEntity.status(status).body(body);
    }

    /**
     * 请求线程上的 locale。**没有 LocaleContext 时回落 zh-CN，不读 JVM 默认 locale**。
     *
     * <p>{@code LocaleContextHolder.getLocale()} 在无上下文时返回 {@code Locale.getDefault()}
     * （CI 的 Ubuntu 是 en-US），会让"无 {@code Accept-Language} 头 = 默认 zh"的 S6 口径随机器漂移。
     * 正常请求由 {@code WebI18nConfig} 的 {@code AcceptHeaderLocaleResolver} 写入上下文（默认即 zh），
     * 故这里只兜底"没有协商结果"的情形。
     */
    private static Locale requestLocale() {
        LocaleContext context = LocaleContextHolder.getLocaleContext();
        Locale locale = context == null ? null : context.getLocale();
        return locale == null ? Locale.SIMPLIFIED_CHINESE : locale;
    }

    /** `Accept` 含 `application/problem+json` 才切 RFC 9457 形态（默认形态零变化）。 */
    private static boolean wantsProblemJson(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        String accept = request.getHeader("Accept");
        return accept != null && accept.toLowerCase(Locale.ROOT)
                .contains(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    }

    private static String traceIdOf(HttpServletRequest request) {
        Object trace = request.getAttribute("traceId");
        return trace instanceof String value && !value.isBlank() ? value : null;
    }

    /** 该响应是否已经"只能写流、不能再写 JSON 体"（内容类型已固定为 text/event-stream 或已提交）。 */
    private static boolean isStreamingResponse(HttpServletResponse response) {
        if (response == null) {
            return false;
        }
        String contentType = response.getContentType();
        return response.isCommitted()
                || (contentType != null && contentType.startsWith(MediaType.TEXT_EVENT_STREAM_VALUE));
    }
}
