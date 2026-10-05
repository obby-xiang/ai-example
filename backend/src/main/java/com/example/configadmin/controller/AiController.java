package com.example.configadmin.controller;

import com.example.configadmin.ai.AiRuntimeService;
import com.example.configadmin.ai.AiSession;
import com.example.configadmin.ai.AiSessionStore;
import com.example.configadmin.common.R;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
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
 * POST /api/ai/confirm  确认/取消破坏性工具（释放 HITL 确认门，原 chat 流继续）
 * POST /api/ai/stop     停止生成
 * POST /api/ai/clear    清空会话（关闭页签/手动重置）
 * GET  /api/ai/history  按页签会话恢复对话历史（刷新页面不丢失）
 * GET  /api/ai/tools    查看某页面披露的工具清单
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiRuntimeService runtime;
    private final AiSessionStore store;
    private final ObjectMapper mapper;

    public AiController(AiRuntimeService runtime, AiSessionStore store, ObjectMapper mapper) {
        this.runtime = runtime;
        this.store = store;
        this.mapper = mapper;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody Map<String, Object> body) {
        String sessionId = String.valueOf(body.getOrDefault("sessionId", ""));
        String message = String.valueOf(body.getOrDefault("message", ""));
        @SuppressWarnings("unchecked")
        Map<String, Object> context = body.get("context") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : new LinkedHashMap<>();
        return runtime.doChat(sessionId, message, context);
    }

    @PostMapping("/confirm")
    public R<?> confirm(@RequestBody Map<String, Object> body) {
        runtime.confirm(String.valueOf(body.getOrDefault("sessionId", "")),
                Boolean.TRUE.equals(body.get("approved")));
        return R.ok();
    }

    @PostMapping("/stop")
    public R<?> stop(@RequestBody Map<String, Object> body) {
        runtime.stop(String.valueOf(body.getOrDefault("sessionId", "")));
        return R.ok();
    }

    @PostMapping("/clear")
    public R<?> clear(@RequestBody Map<String, Object> body) {
        store.remove(String.valueOf(body.getOrDefault("sessionId", "")));
        return R.ok();
    }

    /**
     * 刷新页面恢复对话历史：按页签 sessionId 返回可展示的消息序列
     * （用户消息 + 助手文本 + 工具卡片摘要；系统提示与推理内容不返回）。
     * 会话持久化于 H2，刷新与后端重启后均可恢复；无记录时返回空列表（新页签）。
     */
    @GetMapping("/history")
    public R<List<Map<String, Object>>> history(@RequestParam String sessionId) {
        var s = store.getQuiet(sessionId);
        if (s == null) {
            return R.ok(List.of());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        List<Message> msgs = s.getMessages();
        for (int i = 0; i < msgs.size(); i++) {
            Message m = msgs.get(i);
            if (m.getMessageType() == MessageType.USER) {
                out.add(Map.of("role", "user", "content", m.getText() == null ? "" : m.getText()));
                continue;
            }
            if (m instanceof AssistantMessage am) {
                String content = am.getText() == null ? "" : am.getText();
                List<Map<String, Object>> tools = new ArrayList<>();
                if (am.hasToolCalls()) {
                    for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                        Map<String, Object> tool = new LinkedHashMap<>();
                        tool.put("name", tc.name());
                        tool.put("args", parseArgs(tc.arguments()));
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
            // TOOL 结果消息已合并进工具卡片，跳过
        }
        return R.ok(out);
    }

    @GetMapping("/tools")
    public R<List<Map<String, Object>>> tools(@RequestParam(defaultValue = "export") String page) {
        return R.ok(runtime.toolsForPage(page));
    }

    @GetMapping("/sessions/count")
    public R<Map<String, Object>> sessionsCount() {
        return R.ok(Map.of("count", store.size()));
    }

    private String findToolResult(List<Message> msgs, int from, String toolCallId) {
        for (int j = from + 1; j < msgs.size(); j++) {
            Message m = msgs.get(j);
            if (m instanceof ToolResponseMessage trm) {
                for (ToolResponseMessage.ToolResponse tr : trm.getResponses()) {
                    if (toolCallId != null && toolCallId.equals(tr.id())) {
                        String c = tr.responseData() == null ? "" : tr.responseData();
                        return c.length() > 500 ? c.substring(0, 500) + "…" : c;
                    }
                }
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
}
