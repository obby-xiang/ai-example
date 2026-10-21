package com.example.configmgr.common;

/** 状态不允许且带机器可读码（is-a {@link IllegalStateException}，既有 catch 分支不受影响）。 */
public class CodedIllegalStateException extends IllegalStateException implements CodedException {

    private final String code;

    public CodedIllegalStateException(String code, String message) {
        super(message);
        this.code = code;
    }

    public CodedIllegalStateException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }
}
