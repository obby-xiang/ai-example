package com.example.configmgr.ai;

import com.example.configmgr.ai.hitl.HitlManager;
import com.example.configmgr.ai.runtime.AgentRuntime;
import com.example.configmgr.ai.runtime.SseRunEmitter;
import com.example.configmgr.ai.session.AiSession;
import com.example.configmgr.ai.session.AiSessionStore;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiSessionStore sessionStore;
    private final AgentRuntime agentRuntime;
    private final HitlManager hitlManager;
    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;

    /**
     * 调试接口：列出指定上下文（页面/任务类型/步骤）下对 AI 可见的工具，
     * 用于验证渐进式披露。与 AgentRuntime 每次迭代的工具解析走同一路径。
     */
    @GetMapping("/tools")
    public ApiResponse<Map<String, Object>> availableTools(
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String taskType,
            @RequestParam(required = false) String step) {
        AiSession.WorkspaceContext ctx = new AiSession.WorkspaceContext();
        ctx.setPage(page != null ? page : "");
        ctx.setTaskType(taskType);
        ctx.setStep(step);
        List<ToolCallback> tools = toolRegistry.forContext(ctx);
        List<Map<String, Object>> list = tools.stream()
                .map(t -> Map.<String, Object>of(
                        "name", t.getToolDefinition().name(),
                        "description", t.getToolDefinition().description()))
                .collect(Collectors.toList());
        return ApiResponse.ok(Map.of("count", list.size(), "tools", list));
    }

    // POST /api/ai/sessions — create session
    @PostMapping("/sessions")
    public ResponseEntity<ApiResponse<Map<String, String>>> createSession() {
        AiSession session = sessionStore.create();
        return ResponseEntity.status(201).body(ApiResponse.ok(Map.of("sid", session.getId())));
    }

    // GET /api/ai/sessions/{sid} — session state（含对话显示条目，供同页签刷新后恢复）
    @GetMapping("/sessions/{sid}")
    public ApiResponse<Map<String, Object>> getSession(@PathVariable String sid) {
        AiSession session = sessionStore.getOrThrow(sid);
        return ApiResponse.ok(Map.of(
                "sid", sid,
                "runActive", session.isRunActive(),
                "hasPendingInteraction", session.getPendingInteraction() != null,
                "pendingInteraction", session.getPendingInteraction() != null
                        ? session.getPendingInteraction() : Map.of(),
                "messages", buildDisplayMessages(session),
                "usage", Map.of(
                        "promptTokens", session.getLastPromptTokens(),
                        "completionTokens", session.getLastCompletionTokens()),
                "context", session.getContext()
        ));
    }

    /**
     * 从会话的 LLM 消息流重建前端可渲染的显示条目：
     * 用户消息 → user；助手文本 → assistant；工具响应 → tool（工具卡片）。
     * 系统消息（上下文快照）不展示。
     */
    private List<Map<String, Object>> buildDisplayMessages(AiSession session) {
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        synchronized (session.getHistory()) {
            for (org.springframework.ai.chat.messages.Message m : session.getHistory()) {
                if (m instanceof org.springframework.ai.chat.messages.UserMessage u) {
                    items.add(Map.of("role", "user",
                            "text", u.getText() != null ? u.getText() : ""));
                } else if (m instanceof org.springframework.ai.chat.messages.AssistantMessage a) {
                    if (a.getText() != null && !a.getText().isBlank()) {
                        items.add(Map.of("role", "assistant", "text", a.getText()));
                    }
                    // 工具调用卡片与紧随其后的 ToolResponseMessage 配对展示
                } else if (m instanceof org.springframework.ai.chat.messages.ToolResponseMessage t) {
                    for (var r : t.getResponses()) {
                        String data = r.responseData();
                        items.add(Map.of(
                                "role", "tool",
                                "toolName", r.name(),
                                "summary", summarizeToolData(data)));
                    }
                }
            }
        }
        return items;
    }

    private String summarizeToolData(String data) {
        if (data == null) return "";
        String s = data.strip();
        if (s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
        if (s.startsWith("用户拒绝了此操作")) s = "操作已拒绝";
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }

    // DELETE /api/ai/sessions/{sid} — reset session
    @DeleteMapping("/sessions/{sid}")
    public ApiResponse<?> resetSession(@PathVariable String sid) {
        sessionStore.remove(sid);
        return ApiResponse.ok();
    }

    // PUT /api/ai/sessions/{sid}/context — update workspace context
    @PutMapping("/sessions/{sid}/context")
    public ApiResponse<?> updateContext(@PathVariable String sid,
                                         @RequestBody Map<String, Object> body) {
        AiSession session = sessionStore.getOrThrow(sid);
        AiSession.WorkspaceContext ctx = session.getContext();
        if (body.containsKey("page")) ctx.setPage((String) body.get("page"));
        if (body.containsKey("taskId") && body.get("taskId") != null) {
            ctx.setTaskId(Long.valueOf(body.get("taskId").toString()));
        }
        if (body.containsKey("taskType")) ctx.setTaskType((String) body.get("taskType"));
        if (body.containsKey("step")) ctx.setStep((String) body.get("step"));
        if (body.containsKey("extra") && body.get("extra") instanceof Map<?,?> extra) {
            @SuppressWarnings("unchecked") Map<String, Object> extraMap = (Map<String, Object>) extra;
            ctx.getExtra().putAll(extraMap);
        }
        session.touch();
        return ApiResponse.ok();
    }

    // POST /api/ai/sessions/{sid}/runs — start a run (returns SSE stream)
    @PostMapping(value = "/sessions/{sid}/runs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter startRun(@PathVariable String sid,
                                @RequestBody Map<String, Object> body) {
        String message = (String) body.getOrDefault("message", "");
        String runId = UUID.randomUUID().toString();

        SseEmitter emitter = new SseEmitter(600_000L); // 10 minutes
        SseRunEmitter runEmitter = new SseRunEmitter(emitter, objectMapper);

        // Run on virtual thread
        Thread.ofVirtual().start(() -> {
            try {
                agentRuntime.run(sid, runId, message, runEmitter);
            } catch (Exception e) {
                log.error("Run failed for session {}: {}", sid, e.getMessage(), e);
                runEmitter.error(e.getMessage());
            } finally {
                emitter.complete();
            }
        });

        return emitter;
    }

    // DELETE /api/ai/sessions/{sid}/runs/current — cancel current run
    @DeleteMapping("/sessions/{sid}/runs/current")
    public ApiResponse<?> cancelRun(@PathVariable String sid) {
        AiSession session = sessionStore.getOrThrow(sid);
        if (session.isRunActive()) {
            session.cancelRun();
            return ApiResponse.ok(Map.of("cancelled", true));
        }
        return ApiResponse.ok(Map.of("cancelled", false, "message", "没有活跃的运行"));
    }

    // POST /api/ai/sessions/{sid}/interactions/{iid} — HITL response
    @PostMapping("/sessions/{sid}/interactions/{iid}")
    public ApiResponse<?> submitInteraction(@PathVariable String sid,
                                              @PathVariable String iid,
                                              @RequestBody Map<String, Object> body) {
        boolean approved = Boolean.TRUE.equals(body.get("approved"));
        String reason = (String) body.getOrDefault("reason", "");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = body.containsKey("data") ? (Map<String, Object>) body.get("data") : Map.of();

        hitlManager.submitResponse(sid, iid, approved, reason, data);
        return ApiResponse.ok(Map.of("submitted", true));
    }

    // GET /api/ai/health — AI service health
    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.ok(Map.of(
                "status", "ok",
                "model", "deepseek-flash",
                "sessionCount", sessionStore.getClass().getSimpleName()
        ));
    }
}
