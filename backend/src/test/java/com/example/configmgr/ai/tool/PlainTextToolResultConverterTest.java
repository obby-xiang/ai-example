package com.example.configmgr.ai.tool;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L-88 的边界：只有 <b>String（CharSequence）返回型</b>走原文分支；
 * 结构化对象（Map/record 等）仍按官方 {@code DefaultToolCallResultConverter} 做 JSON 编码 —— 线上形态不变。
 */
class PlainTextToolResultConverterTest {

    /** 两种返回型各一个工具（同一注册路径，只差返回类型）。 */
    static class SampleTools {

        @Tool(name = "sample_text", description = "返回文本", resultConverter = PlainTextToolResultConverter.class)
        public String text() {
            return "<knowledge>\n第一行\n第二行\n</knowledge>";
        }

        @Tool(name = "sample_structured", description = "返回结构化对象",
                resultConverter = PlainTextToolResultConverter.class)
        public Map<String, Object> structured() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("ok", true);
            value.put("count", 3);
            return value;
        }
    }

    private static ToolCallback callback(String name) {
        return Arrays.stream(MethodToolCallbackProvider.builder().toolObjects(new SampleTools()).build()
                .getToolCallbacks())
                .filter(candidate -> name.equals(candidate.getToolDefinition().name()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void stringReturnsAreNotJsonEncoded() {
        assertThat(callback("sample_text").call("{}"))
                .isEqualTo("<knowledge>\n第一行\n第二行\n</knowledge>");
    }

    @Test
    void structuredReturnsKeepTheOfficialJsonEncoding() {
        assertThat(callback("sample_structured").call("{}")).isEqualTo("{\"ok\":true,\"count\":3}");
    }

    @Test
    void converterDelegatesNonTextResultsAndPassesTextThrough() {
        PlainTextToolResultConverter converter = new PlainTextToolResultConverter();

        assertThat(converter.convert("{\"a\":1}", String.class)).isEqualTo("{\"a\":1}");
        assertThat(converter.convert(Map.of("a", 1), Map.class)).isEqualTo("{\"a\":1}");
    }
}
