package com.example.configmgr.common;

import java.text.BreakIterator;
import java.util.Locale;
import java.util.Objects;

/**
 * UTF-16 下标上的 Unicode 字素边界。每次调用使用独立的 {@link BreakIterator}，不共享可变状态。
 */
public final class GraphemeText {

    private GraphemeText() {
    }

    /** 返回 {@code offset} 处或其之前的字素边界；越界下标按首尾夹取。 */
    public static int precedingBoundary(String text, int offset) {
        Objects.requireNonNull(text, "text");
        int clamped = clamp(text, offset);
        BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
        iterator.setText(text);
        if (iterator.isBoundary(clamped)) {
            return clamped;
        }
        return iterator.preceding(clamped);
    }

    /** 返回严格位于 {@code offset} 之后的字素边界；末尾仍返回文本长度。 */
    public static int followingBoundary(String text, int offset) {
        Objects.requireNonNull(text, "text");
        int clamped = clamp(text, offset);
        if (clamped == text.length()) {
            return clamped;
        }
        BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
        iterator.setText(text);
        return iterator.following(clamped);
    }

    private static int clamp(String text, int offset) {
        return Math.max(0, Math.min(offset, text.length()));
    }
}
