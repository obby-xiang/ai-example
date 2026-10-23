package com.example.configmgr.ai.kb;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.ai.tool.ToolResultEnvelope;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L-88：String 返回型工具的结果被<b>二次 JSON 编码</b>（模型看到 {@code "\"<knowledge>\\n…"}）。
 *
 * <p>证据面与真机一致：走官方的 {@link MethodToolCallbackProvider}（生产注册路径），
 * 再看 {@code role:tool} 消息里的 {@code message} 首字符 —— 它必须是正文，不是引号。
 */
class KbToolResultPlainTextTest {

    private static ToolCallback callback(KbTools tools, String name) {
        return java.util.Arrays.stream(MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks())
                .filter(candidate -> name.equals(candidate.getToolDefinition().name()))
                .findFirst()
                .orElseThrow();
    }

    private static String modelVisibleMessage(String toolName, String rawResult, String argumentsJson) {
        String modelJson = ToolResultEnvelope.of(ToolOutcome.OK, null, rawResult, argumentsJson)
                .toModelJson(new ToolResultLimiter(new AiProperties()), toolName);
        return ToolResultEnvelope.fromJson(modelJson).message();
    }

    @Test
    void kbToolsReachTheModelAsPlainTextNotJsonString() throws Exception {
        KbTools tools = new KbTools(KbTestSupport.samplesService());

        Map<String, String> calls = new LinkedHashMap<>();
        calls.put("kb_search", "{\"query\":\"费率\",\"topK\":5}");
        calls.put("kb_read_doc", "{\"docId\":\"region-tariff\"}");
        calls.put("kb_lookup_term", "{\"term\":\"基准费率\"}");

        for (Map.Entry<String, String> call : calls.entrySet()) {
            String raw = callback(tools, call.getKey()).call(call.getValue());

            assertThat(raw).as("%s 的执行结果必须是正文（不是再编码一次的 JSON 字符串）", call.getKey())
                    .doesNotStartWith("\"")
                    .startsWith("<knowledge>");

            String message = modelVisibleMessage(call.getKey(), raw, call.getValue());
            assertThat(message.charAt(0)).as("%s 信封 message 首字符必须是 '<'（对应 req-004.json 的 \" 证据）",
                    call.getKey()).isEqualTo('<');
            assertThat(message).contains("</knowledge>").doesNotStartWith("\"");
        }
    }
}
