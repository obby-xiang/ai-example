package com.example.configmgr.common;

/**
 * Unicode 空白判定，包含 {@link Character#isWhitespace(int)} 不覆盖的全角空格等空白字符。
 */
public final class BlankText {

    private BlankText() {
    }

    public static boolean isBlank(String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            if (!isWhitespace(codePoint)) {
                return false;
            }
            offset += Character.charCount(codePoint);
        }
        return true;
    }

    public static boolean hasBoundaryWhitespace(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        return isWhitespace(value.codePointAt(0)) || isWhitespace(value.codePointBefore(value.length()));
    }

    private static boolean isWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

}
