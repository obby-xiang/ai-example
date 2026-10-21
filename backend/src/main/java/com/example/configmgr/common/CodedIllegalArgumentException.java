package com.example.configmgr.common;

/** 参数非法且带机器可读码（is-a {@link IllegalArgumentException}，既有 catch 分支不受影响）。 */
public class CodedIllegalArgumentException extends IllegalArgumentException implements CodedException {

    private final String code;

    public CodedIllegalArgumentException(String code, String message) {
        super(message);
        this.code = code;
    }

    public CodedIllegalArgumentException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }
}
