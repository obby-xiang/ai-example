package com.example.configmgr.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.StringUtils;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Collections;
import java.util.LinkedHashMap;
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

    /**
     * <b>L-96</b>：请求体读不出来（畸形 JSON / 类型不匹配 / 空体）⇒ <b>400</b>。
     *
     * <p>
     * 此前无专门 handler，被 {@link #handleGeneral} 兜成 {@code 500 INTERNAL_ERROR} —— 客户端把
     * "请求体写错了"读成"服务端炸了"（重试 / 告警 / 上报全指错方向）。口径与其它 400 一致：
     * 业务信封 {@code code+message}（{@code Accept} 含 {@code application/problem+json} 时切
     * RFC 9457）；码复用既有 {@link ErrorCodes#BAD_REQUEST}（不新增码 ⇒ 前端镜像与 messages 键集不动）；
     * zh 用本条原文，en 走 {@code messages_en} 的 {@code error.BAD_REQUEST} 兜底。
     *
     * <p>
     * 不回显 Jackson 的解析细节：那是英文技术栈文本，对终端用户无意义（需要细节时看 DEBUG 日志）。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> handleUnreadableBody(HttpMessageNotReadableException e, HttpServletRequest request) {
        log.debug("请求体不可读（畸形 JSON / 类型不匹配 / 空体）uri={}：{}",
                request == null ? null : request.getRequestURI(), e.getMessage());
        return respond(HttpStatus.BAD_REQUEST, ErrorCodes.BAD_REQUEST, "请求体格式不合法", null, request);
    }

    /** 异常族的一条映射：HTTP 状态 + 稳定码 + zh 固定文案（en 由 {@code messages_en} 的 {@code error.<CODE>} 兜底）。 */
    private record WebFamily(HttpStatus status, String code, String zhMessage) {
    }

    /**
     * 异常族映射表（<b>唯一事实源</b>）：运行时按"最具体的父类"命中，故只需登记族根，
     * 子类自动归族（例：{@code MethodArgumentTypeMismatchException} → {@code TypeMismatchException} 族）。
     *
     * <p>
     * 码值口径：<b>不新增码</b> —— 新增码要三方对账（{@code ErrorCodes} + {@code messages_zh/en} +
     * 前端 {@code types/api.ts} 镜像），本批纯后端。故 4xx 复用 {@link ErrorCodes#BAD_REQUEST}，
     * 404 复用 {@link ErrorCodes#RESOURCE_NOT_FOUND}；<b>状态码本身</b>才是客户端分流依据。
     */
    private static final Map<Class<? extends Throwable>, WebFamily> WEB_FAMILIES = webFamilies();

    /** 无 reason 的 {@link ResponseStatusException} 按状态给的 zh 缺省文案。 */
    private static final Map<HttpStatus, String> STATUS_MESSAGES = Map.of(
            HttpStatus.BAD_REQUEST, "请求无法完成",
            HttpStatus.NOT_FOUND, "请求的资源不存在",
            HttpStatus.CONFLICT, "请求与当前状态冲突",
            HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后再试",
            HttpStatus.SERVICE_UNAVAILABLE, "服务暂不可用，请稍后重试");

    private static Map<Class<? extends Throwable>, WebFamily> webFamilies() {
        Map<Class<? extends Throwable>, WebFamily> families = new LinkedHashMap<>();
        // 400 族：请求形状不对（不是服务端故障）
        families.put(ServletRequestBindingException.class,
                new WebFamily(HttpStatus.BAD_REQUEST, ErrorCodes.BAD_REQUEST, "缺少必需的请求参数"));
        families.put(MissingServletRequestPartException.class,
                new WebFamily(HttpStatus.BAD_REQUEST, ErrorCodes.BAD_REQUEST, "缺少必需的上传文件"));
        families.put(TypeMismatchException.class,
                new WebFamily(HttpStatus.BAD_REQUEST, ErrorCodes.BAD_REQUEST, "请求参数类型不匹配"));
        families.put(MultipartException.class,
                new WebFamily(HttpStatus.BAD_REQUEST, ErrorCodes.BAD_REQUEST, "上传请求格式不合法"));
        families.put(MaxUploadSizeExceededException.class,
                new WebFamily(HttpStatus.PAYLOAD_TOO_LARGE, ErrorCodes.BAD_REQUEST, "上传文件超出大小上限"));
        // 404 族
        families.put(NoResourceFoundException.class,
                new WebFamily(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND, "请求的资源不存在"));
        families.put(NoHandlerFoundException.class,
                new WebFamily(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND, "请求的资源不存在"));
        // 405 / 415 / 406 族
        families.put(HttpRequestMethodNotSupportedException.class,
                new WebFamily(HttpStatus.METHOD_NOT_ALLOWED, ErrorCodes.BAD_REQUEST, "该接口不支持此请求方法"));
        families.put(HttpMediaTypeNotSupportedException.class,
                new WebFamily(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCodes.BAD_REQUEST, "请求内容类型不受支持"));
        families.put(HttpMediaTypeNotAcceptableException.class,
                new WebFamily(HttpStatus.NOT_ACCEPTABLE, ErrorCodes.BAD_REQUEST, "无法生成客户端可接受的响应格式"));
        // 显式覆盖父族：@PathVariable 名字写错是服务端映射缺陷（500），不是客户端 400
        families.put(MissingPathVariableException.class,
                new WebFamily(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.INTERNAL_ERROR, "服务器内部错误"));
        // 显式覆盖父族：bean 属性没有可用的转换器 = 服务端配置缺陷（500），不是客户端参数错
        families.put(ConversionNotSupportedException.class,
                new WebFamily(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.INTERNAL_ERROR, "服务器内部错误"));
        // 显式状态异常：状态与 reason 由异常自带，下表值只是"无法解析时"的缺省
        families.put(ResponseStatusException.class,
                new WebFamily(HttpStatus.BAD_REQUEST, ErrorCodes.BAD_REQUEST, "请求无法完成"));
        return Collections.unmodifiableMap(families);
    }

    /**
     * <b>L-111</b>：Spring MVC 内建异常族按族收口（400 / 404 / 405 / 406 / 413 / 415）。
     *
     * <p>
     * 此前这些异常没有专门 handler，全部落 {@link #handleGeneral} 变成 {@code 500 INTERNAL_ERROR}
     * —— 与 L-96 同族（"状态码被吃掉"）：客户端把"参数发错了 / 方法用错了"读成"服务端炸了"。
     *
     * <p>
     * 修法：一条 handler + 一张映射表（见 {@link #WEB_FAMILIES}），按运行时类型向上找最近的族，
     * 不按异常逐个补 handler；未知异常仍由 {@link #handleGeneral} 兜 500（表不得吞未知异常）。
     *
     * <p>
     * 两个边界：<b>①</b> 406 只回状态不回体 —— 客户端已声明不接受该响应形态，再写 JSON 体会二次触发协商失败；
     * <b>②</b> 已开流的响应（SSE）不写体，与 {@link #handleGeneral} 同口径。
     */
    @ExceptionHandler({
            ServletRequestBindingException.class,
            MissingServletRequestPartException.class,
            TypeMismatchException.class,
            MultipartException.class,
            NoHandlerFoundException.class,
            NoResourceFoundException.class,
            HttpRequestMethodNotSupportedException.class,
            HttpMediaTypeNotSupportedException.class,
            HttpMediaTypeNotAcceptableException.class,
            ResponseStatusException.class
    })
    public ResponseEntity<?> handleWebFamily(Exception e, HttpServletRequest request, HttpServletResponse response) {
        if (isStreamingResponse(response)) {
            log.warn("SSE 响应上的 Web 异常（内容类型 text/event-stream 或响应已提交，跳过 JSON 写回）uri={}：{}",
                    request == null ? null : request.getRequestURI(), e.toString());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        WebFamily family = familyOf(e);
        if (family == null) {
            return handleGeneral(e, request, response);
        }
        HttpStatus status = family.status();
        String code = family.code();
        String message = family.zhMessage();
        if (e instanceof ResponseStatusException statusException) {
            HttpStatus resolved = HttpStatus.resolve(statusException.getStatusCode().value());
            status = resolved == null ? HttpStatus.INTERNAL_SERVER_ERROR : resolved;
            code = codeOfStatus(status);
            if (StringUtils.hasText(statusException.getReason())) {
                message = statusException.getReason();
            } else {
                message = STATUS_MESSAGES.getOrDefault(status, "请求无法完成");
            }
        }
        HttpHeaders headers = headersOf(e);
        log.debug("Web 异常族收口 uri={} {} → {} {}：{}",
                request == null ? null : request.getRequestURI(), e.getClass().getSimpleName(),
                status.value(), code, e.getMessage());
        if (status == HttpStatus.NOT_ACCEPTABLE) {
            // 不能再写 JSON 体（客户端不接受），否则二次协商失败
            return ResponseEntity.status(status).headers(headers).build();
        }
        return respond(status, code, message, null, request, headers);
    }

    /** 按运行时类型向上找最近的族；表外异常返回 {@code null}（交回兜底 500）。 */
    private static WebFamily familyOf(Throwable error) {
        for (Class<?> type = error.getClass(); type != null && Throwable.class.isAssignableFrom(type);
                type = type.getSuperclass()) {
            WebFamily family = WEB_FAMILIES.get(type);
            if (family != null) {
                return family;
            }
        }
        return null;
    }

    /** {@link ResponseStatusException} 的状态 → 稳定码（只认已登记的码，不新增）。 */
    private static String codeOfStatus(HttpStatus status) {
        if (status == HttpStatus.NOT_FOUND) {
            return ErrorCodes.RESOURCE_NOT_FOUND;
        }
        if (status == HttpStatus.TOO_MANY_REQUESTS) {
            return ErrorCodes.RATE_LIMITED;
        }
        return status.is4xxClientError() ? ErrorCodes.BAD_REQUEST : ErrorCodes.INTERNAL_ERROR;
    }

    /** Spring 的 {@link ErrorResponse} 自带响应头（405 的 {@code Allow}、415 的 {@code Accept}），照搬。 */
    private static HttpHeaders headersOf(Exception error) {
        if (error instanceof ErrorResponse response && response.getHeaders() != null) {
            return response.getHeaders();
        }
        return HttpHeaders.EMPTY;
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
        return respond(status, code, message, args, request, HttpHeaders.EMPTY);
    }

    /** 同上，额外带上响应头（L-111：405 的 {@code Allow}、415 的 {@code Accept} 等协议头照搬）。 */
    private ResponseEntity<?> respond(HttpStatus status, String code, String message,
            Map<String, Object> args, HttpServletRequest request, HttpHeaders headers) {
        Locale locale = requestLocale();
        String traceId = request == null ? null : traceIdOf(request);
        if (wantsProblemJson(request)) {
            ProblemDetail problem = bodyFactory.problem(code, status, message, args,
                    request == null ? null : request.getRequestURI(), traceId, locale);
            return ResponseEntity.status(status)
                    .headers(headers)
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(problem);
        }
        ApiResponse<Object> body = bodyFactory.envelope(code, message, args, locale);
        if (traceId != null) {
            body.setTraceId(traceId);
        }
        return ResponseEntity.status(status).headers(headers).body(body);
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
