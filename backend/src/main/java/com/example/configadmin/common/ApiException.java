package com.example.configadmin.common;

/** 业务异常：携带 HTTP 状态码与面向用户的中文提示。 */
public class ApiException extends RuntimeException {

    private final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public ApiException(String message) {
        this(400, message);
    }

    public int getStatus() {
        return status;
    }

    public static ApiException notFound(String msg) {
        return new ApiException(404, msg);
    }

    public static ApiException badRequest(String msg) {
        return new ApiException(400, msg);
    }
}
