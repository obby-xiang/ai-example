package com.example.configadmin.controller;

import com.example.configadmin.ai.AiRuntimeService;
import com.example.configadmin.ai.AiSessionStore;
import com.example.configadmin.ai.ToolDef;
import com.example.configadmin.ai.ToolRegistry;
import com.example.configadmin.common.R;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 对话接口（SSE 流式）：
 * POST /api/ai/chat     发起对话（sessionId=页签唯一，context=工作区快照）
 * POST /api/ai/confirm  确认/拒绝破坏性工具（续跑 Agent 循环，同样 SSE）
 * POST /api/ai/clear    清空会话（关闭页签/手动重置）
 * GET  /api/ai/history  按页签会话恢复对话历史（刷新页面不丢失）
 * GET  /api/ai/tools    查看某页面披露的工具清单
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiRuntimeService runtime;
    private final AiSessionStore store;
    private final ToolRegistry registry;
    private final ObjectMapper mapper;

    public AiController(AiRuntimeService runtime, AiSessionStore store, ToolRegistry registry, ObjectMapper mapper) {
        this.runtime = runtime;
        this.store = store;
        this.registry = registry;
        this.mapper = mapper;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody Map<String, Object> body) {
        String sessionId = String.valueOf(body.getOrDefault("sessionId", ""));
        String message = String.valueOf(body.getOrDefault("message", ""));
        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) body.getOrDefault("context", Map.of());
        SseEmitter emitter = new SseEmitter(0L);
        runtime.chat(sessionId, message, context, emitter);
        return emitter;
    }

    @PostMapping(value = "/confirm", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter confirm(@RequestBody Map<String, Object> body) {
        String sessionId = String.valueOf(body.getOrDefault("sessionId", ""));
        boolean approved = Boolean.parseBoolean(String.valueOf(body.getOrDefault("approved", "true")));
        SseEmitter emitter = new SseEmitter(0L);
        runtime.confirm(sessionId, approved, emitter);
        return emitter;
    }

    @PostMapping("/clear")
    public R<?> clear(@RequestBody Map<String, Object> body) {
        store.remove(String.valueOf(body.getOrDefault("sessionId", "")));
        return R.ok();
    }

    /**
     * 刷新页面恢复对话历史：按页签 sessionId 返回可展示的消息序列
     * （用户消息 + 助手文本 + 工具卡片摘要；系统提示与推理内容不返回）。
     * 会话为后端内存态，后端重启后历史自然消失，返回空列表由前端降级为新会话。
     */
    @GetMapping("/history")
    public R<List<Map<String, Object>>> history(@RequestParam String sessionId) {
        var s = store.getQuiet(sessionId);
        if (s == null) {
            return R.ok(List.of());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        List<OpenAiApi.ChatCompletionMessage> msgs = s.getMessages();
        for (int i = 0; i < msgs.size(); i++) {
            OpenAiApi.ChatCompletionMessage m = msgs.get(i);
            if (m.role() == OpenAiApi.ChatCompletionMessage.Role.SYSTEM) {
                continue;
            }
            if (m.role() == OpenAiApi.ChatCompletionMessage.Role.USER) {
                out.add(Map.of("role", "user", "content", m.content() == null ? "" : String.valueOf(m.content())));
                continue;
            }
            if (m.role() == OpenAiApi.ChatCompletionMessage.Role.ASSISTANT) {
                String content = m.content() == null ? "" : String.valueOf(m.content());
                List<Map<String, Object>> tools = new ArrayList<>();
                if (m.toolCalls() != null) {
                    for (OpenAiApi.ChatCompletionMessage.ToolCall tc : m.toolCalls()) {
                        Map<String, Object> tool = new LinkedHashMap<>();
                        tool.put("name", tc.function().name());
                        tool.put("args", parseArgs(tc.function().arguments()));
                        String summary = findToolResult(msgs, i, tc.id());
                        tool.put("summary", summary == null ? "该操作未执行（页面已刷新），请重新发起" : summary);
                        tool.put("status", summary == null ? "pending" : "ok");
                        tools.add(tool);
                    }
                }
                if (!content.isBlank() || !tools.isEmpty()) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("role", "assistant");
                    entry.put("content", content);
                    entry.put("tools", tools);
                    out.add(entry);
                }
            }
            // TOOL 角色消息已合并进工具卡片，跳过
        }
        return R.ok(out);
    }

    private String findToolResult(List<OpenAiApi.ChatCompletionMessage> msgs, int from, String toolCallId) {
        for (int j = from + 1; j < msgs.size(); j++) {
            OpenAiApi.ChatCompletionMessage m = msgs.get(j);
            if (m.role() == OpenAiApi.ChatCompletionMessage.Role.TOOL
                    && toolCallId != null && toolCallId.equals(m.toolCallId())) {
                String c = m.content() == null ? "" : String.valueOf(m.content());
                return c.length() > 500 ? c.substring(0, 500) + "…" : c;
            }
        }
        return null;
    }

    private Map<String, Object> parseArgs(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(argsJson, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of("_raw", argsJson);
        }
    }

    @GetMapping("/tools")
    public R<List<Map<String, Object>>> tools(@RequestParam(required = false) String page) {
        List<ToolDef> defs = page == null ? registry.all() : registry.forPage(page);
        return R.ok(defs.stream().map(d -> Map.of(
                "name", (Object) d.name(),
                "description", d.description(),
                "confirm", d.confirm(),
                "pages", d.pages() == null ? List.of() : d.pages())).toList());
    }

    @GetMapping("/sessions/count")
    public R<Map<String, Object>> sessionCount() {
        return R.ok(Map.of("count", store.size()));
    }
}
