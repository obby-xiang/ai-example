package com.example.configmgr.common;

import com.example.configmgr.common.ErrorCodes;

/**
 * 业务对象不存在（HTTP 404）。默认携带机器可读码 {@value #DEFAULT_CODE}；
 * 需要更细的码时用两参构造（AI 工具执行路径据此把"未找到"如实交给统一信封，而不是吞成字符串）。
 */
public class ResourceNotFoundException extends RuntimeException {

    /** 未指定具体码时的默认码（前后端与工具信封共用）。 */
    public static final String DEFAULT_CODE = ErrorCodes.RESOURCE_NOT_FOUND;

    private final String code;

    public ResourceNotFoundException(String message) {
        this(DEFAULT_CODE, message);
    }

    public ResourceNotFoundException(String code, String message) {
        super(message);
        this.code = code;
    }

    public static ResourceNotFoundException of(String entity, Object id) {
        return new ResourceNotFoundException(entity + " 不存在: " + id);
    }

    public String getCode() {
        return code;
    }
}
