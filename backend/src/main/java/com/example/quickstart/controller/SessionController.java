package com.example.quickstart.controller;

import com.example.quickstart.common.ApiResponse;
import com.example.quickstart.runtime.SessionHolder;
import com.example.quickstart.runtime.SessionRegistry;
import com.example.quickstart.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会话通道：SSE 事件流 + 会话-任务绑定。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SessionController {

    private final SessionRegistry registry;
    private final SessionHolder sessionHolder;
    private final TaskService taskService;

    @GetMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sse(@RequestParam String sessionId) {
        return registry.register(sessionId);
    }

    @GetMapping("/session/state")
    public ApiResponse<Map<String, Object>> state(@RequestParam String sessionId) {
        SessionHolder.TaskRef ref = sessionHolder.get(sessionId);
        Map<String, Object> m = new LinkedHashMap<>();
        if (ref != null && ref.taskId() != null) {
            m.put("activeTaskId", ref.taskId());
            try {
                m.put("task", taskService.taskMap(ref.taskId()));
            } catch (Exception e) {
                m.put("task", null);
            }
        } else {
            m.put("activeTaskId", null);
            m.put("task", null);
        }
        return ApiResponse.ok(m);
    }

    public record BindReq(String taskId) {
    }

    @PostMapping("/session/bind")
    public ApiResponse<Map<String, Object>> bind(@RequestBody BindReq req,
                                                 @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new com.example.quickstart.common.BizException("缺少 X-Session-Id 请求头");
        }
        taskService.require(req.taskId());
        sessionHolder.bind(sessionId, req.taskId());
        return ApiResponse.ok(Map.of("activeTaskId", req.taskId()));
    }

    @PostMapping("/session/unbind")
    public ApiResponse<Void> unbind(@RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (sessionId != null) {
            sessionHolder.unbind(sessionId);
        }
        return ApiResponse.ok();
    }
}
