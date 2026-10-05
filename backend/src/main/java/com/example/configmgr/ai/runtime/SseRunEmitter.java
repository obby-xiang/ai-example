package com.example.configmgr.ai.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

/**
 * Emits AG-UI style SSE events for a single agent run.
 */
@Slf4j
public class SseRunEmitter {

    private final SseEmitter emitter;
    private final ObjectMapper objectMapper;

    public SseRunEmitter(SseEmitter emitter, ObjectMapper objectMapper) {
        this.emitter = emitter;
        this.objectMapper = objectMapper;
    }

    public void runStarted(String runId) {
        send(Map.of("type", "RUN_STARTED", "runId", runId));
    }

    public void runCompleted(String runId) {
        send(Map.of("type", "RUN_COMPLETED", "runId", runId));
    }

    public void textDelta(String delta) {
        send(Map.of("type", "TEXT_DELTA", "delta", delta));
    }

    public void toolStart(String callId, String toolName, String displayName) {
        send(Map.of("type", "TOOL_START", "toolCallId", callId,
                "toolName", toolName, "title", displayName));
    }

    public void toolDone(String callId, boolean success, String summary) {
        send(Map.of("type", "TOOL_DONE", "toolCallId", callId,
                "success", success, "summary", summary));
    }

    public void interactionRequest(String summary) {
        send(Map.of("type", "INTERACTION_REQUEST_RESULT", "summary", summary));
    }

    public void error(String message) {
        send(Map.of("type", "ERROR", "message", message));
    }

    public void heartbeat() {
        send(Map.of("type", "HEARTBEAT"));
    }

    private void send(Map<String, Object> data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            emitter.send(SseEmitter.event().data(json));
        } catch (IOException e) {
            log.debug("SSE send failed (client disconnected?): {}", e.getMessage());
        } catch (Exception e) {
            log.warn("SSE send error: {}", e.getMessage());
        }
    }
}
