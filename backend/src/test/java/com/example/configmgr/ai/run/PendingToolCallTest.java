package com.example.configmgr.ai.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PendingToolCallTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void legacyDraftDefCodeIsRejectedAndHasNoAccessors() {
        String legacy = "{\"draftRunId\":\"run-1\",\"draftTaskId\":\"42\",\"draftDefCode\":\"CURRENCY\"}";

        assertThatThrownBy(() -> this.mapper.readValue(legacy, PendingToolCall.class))
                .isInstanceOf(UnrecognizedPropertyException.class);
        assertThat(Arrays.stream(PendingToolCall.class.getMethods()).map(Method::getName))
                .doesNotContain("getDraftDefCode", "setDraftDefCode");
    }
}
