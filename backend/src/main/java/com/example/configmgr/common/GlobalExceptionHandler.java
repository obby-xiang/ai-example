package com.example.configmgr.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<?>> handleIllegalArg(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<?>> handleIllegalState(IllegalStateException e) {
        return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<?>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst().orElse("参数校验失败");
        return ResponseEntity.badRequest().body(ApiResponse.error(msg));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<?>> handleConstraint(ConstraintViolationException e) {
        return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<?>> handleNotFound(ResourceNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
    }

    /** 业务冲突 → 409 + 机器可读错误码（ADR-8 W1 边界：取消已终态作业返回 409 JOB_ALREADY_FINAL）。 */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiResponse<?>> handleConflict(ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<?>> handleGeneral(Exception e, HttpServletRequest request,
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
        return ResponseEntity.internalServerError().body(ApiResponse.error("服务器内部错误: " + e.getMessage()));
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
