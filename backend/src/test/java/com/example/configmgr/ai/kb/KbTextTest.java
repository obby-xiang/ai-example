package com.example.configmgr.ai.kb;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KbTextTest {

    @Test
    void snippetRejectsNegativeMaxCharsAndKeepsZeroBehavior() {
        assertThatThrownBy(() -> KbText.snippet("ae\u0301Z", List.of(), -1))
                .isInstanceOf(StringIndexOutOfBoundsException.class);
        assertThat(KbText.snippet("ae\u0301Z", List.of(), 0)).isEqualTo("…");
    }

    @Test
    void snippetWindowEndUsesLongWhenStartAndMaxCharsWouldOverflow() {
        assertThat(KbText.windowEnd(1, Integer.MAX_VALUE, 3)).isEqualTo(3);
        assertThat(KbText.snippet("ae\u0301Z", List.of(), Integer.MAX_VALUE)).isEqualTo("ae\u0301Z");
    }
}
