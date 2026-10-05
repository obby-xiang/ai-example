package com.example.quickstart.controller;

import com.example.quickstart.ai.AiChatService;
import com.example.quickstart.common.ApiResponse;
import com.example.quickstart.common.BizException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Validated
public class AiController {

    private final AiChatService chatService;

    @Data
    public static class ChatReq {
        @NotBlank
        private String sessionId;
        @NotBlank
        private String message;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@Valid @RequestBody ChatReq req) {
        return chatService.chat(req.getSessionId(), req.getMessage(), false);
    }

    @GetMapping("/history")
    public ApiResponse<List<Map<String, Object>>> history(@RequestParam @NotBlank String sessionId) {
        return ApiResponse.ok(chatService.history(sessionId));
    }

    @DeleteMapping("/history")
    public ApiResponse<Void> clearHistory(@RequestParam @NotBlank String sessionId) {
        chatService.clearHistory(sessionId);
        return ApiResponse.ok();
    }
}
