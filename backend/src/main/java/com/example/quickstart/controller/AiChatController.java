package com.example.quickstart.controller;

import com.example.quickstart.ai.AiChatService;
import com.example.quickstart.dto.CancelRequest;
import com.example.quickstart.dto.ChatRequest;
import com.example.quickstart.dto.ToolResultRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiChatController {

    /** SSE 超时 10 分钟（Agent Loop 多轮 + 用户确认等待） */
    private static final long SSE_TIMEOUT_MS = 600_000L;

    private final AiChatService aiChatService;

    @PostMapping("/chat")
    public SseEmitter chat(@Valid @RequestBody ChatRequest req) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        aiChatService.chat(req, emitter);
        return emitter;
    }

    @PostMapping("/tool-result")
    public SseEmitter toolResult(@Valid @RequestBody ToolResultRequest req) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        aiChatService.toolResult(req, emitter);
        return emitter;
    }

    @GetMapping("/history")
    public Map<String, Object> history(@RequestParam String sessionId) {
        return aiChatService.history(sessionId);
    }

    @PostMapping("/cancel")
    public Map<String, Object> cancel(@Valid @RequestBody CancelRequest req) {
        aiChatService.cancel(req.sessionId());
        return Map.of("ok", true);
    }
}
