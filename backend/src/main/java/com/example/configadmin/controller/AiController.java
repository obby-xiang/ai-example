package com.example.configadmin.controller;

import com.example.configadmin.ai.AiRuntimeService;
import com.example.configadmin.ai.AiSessionStore;
import com.example.configadmin.ai.ToolDef;
import com.example.configadmin.ai.ToolRegistry;
import com.example.configadmin.common.R;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * AI 对话接口（SSE 流式）：
 * POST /api/ai/chat     发起对话（sessionId=页签唯一，context=工作区快照）
 * POST /api/ai/confirm  确认/拒绝破坏性工具（续跑 Agent 循环，同样 SSE）
 * POST /api/ai/clear    清空会话（关闭页签/手动重置）
 * GET  /api/ai/tools    查看某页面披露的工具清单
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiRuntimeService runtime;
    private final AiSessionStore store;
    private final ToolRegistry registry;

    public AiController(AiRuntimeService runtime, AiSessionStore store, ToolRegistry registry) {
        this.runtime = runtime;
        this.store = store;
        this.registry = registry;
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
