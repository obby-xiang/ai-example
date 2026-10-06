package com.example.spike.web;

import com.example.spike.core.ResilientChatService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final ResilientChatService chat;

    public ChatController(ResilientChatService chat) {
        this.chat = chat;
    }

    /** SseEmitter 流式对话：strategy = guarded（默认）/ ref（参照实现原序）/ plain（无超时无重试） */
    @PostMapping(path = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody Map<String, Object> body) {
        String sessionId = String.valueOf(body.getOrDefault("sessionId", "s-" + System.currentTimeMillis()));
        String message = String.valueOf(body.getOrDefault("message", "你好"));
        String strategy = String.valueOf(body.getOrDefault("strategy", "guarded"));
        String system = body.get("system") == null ? null : String.valueOf(body.get("system"));
        return chat.chat(sessionId, message, strategy, system);
    }

    @PostMapping("/chat/{sessionId}/cancel")
    public Map<String, Object> cancel(@PathVariable String sessionId) {
        return chat.cancel(sessionId);
    }

    @GetMapping("/chat/{sessionId}/state")
    public Map<String, Object> state(@PathVariable String sessionId) {
        ResilientChatService.Session s = chat.session(sessionId);
        if (s == null) {
            return Map.of("found", false);
        }
        Map<String, Object> m = new LinkedHashMap<>(s.snapshot());
        m.put("found", true);
        return m;
    }

    @GetMapping("/dev/info")
    public Map<String, Object> info() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("port", System.getProperty("spike.port", System.getenv().getOrDefault("SPIKE_PORT", "18303")));
        m.put("pid", ProcessHandle.current().pid());
        m.put("javaVersion", System.getProperty("java.version"));
        m.put("sessions", chat.sessionIds());
        m.put("jvmName", ManagementFactory.getRuntimeMXBean().getVmName());
        return m;
    }
}
