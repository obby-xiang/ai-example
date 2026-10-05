package com.example.configmgr.task.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务级别的 SSE 事件推送服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskSseService {

    private final ObjectMapper objectMapper;
    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long taskId) {
        SseEmitter emitter = new SseEmitter(0L); // no timeout
        emitters.computeIfAbsent(taskId, k -> Collections.synchronizedList(new ArrayList<>())).add(emitter);

        emitter.onCompletion(() -> remove(taskId, emitter));
        emitter.onTimeout(() -> remove(taskId, emitter));
        emitter.onError(e -> remove(taskId, emitter));

        // Send initial heartbeat
        sendEvent(emitter, "HEARTBEAT", Map.of());
        return emitter;
    }

    @EventListener
    public void onTaskChanged(TaskChangedEvent event) {
        publish(event.getTask().getId(), "TASK_CHANGED", Map.of(
                "taskId", event.getTask().getId(),
                "version", event.getTask().getVersion(),
                "currentStep", event.getTask().getCurrentStep(),
                "status", event.getTask().getStatus(),
                "summary", event.getSummary()
        ));
    }

    public void publish(Long taskId, String type, Object data) {
        List<SseEmitter> list = emitters.get(taskId);
        if (list == null || list.isEmpty()) return;

        List<SseEmitter> dead = new ArrayList<>();
        synchronized (list) {
            for (SseEmitter emitter : list) {
                try {
                    String json = objectMapper.writeValueAsString(Map.of("type", type, "data", data));
                    emitter.send(SseEmitter.event().data(json));
                } catch (IOException e) {
                    dead.add(emitter);
                }
            }
            list.removeAll(dead);
        }
    }

    private void sendEvent(SseEmitter emitter, String type, Object data) {
        try {
            String json = objectMapper.writeValueAsString(Map.of("type", type, "data", data));
            emitter.send(SseEmitter.event().data(json));
        } catch (IOException e) {
            log.debug("Failed to send SSE: {}", e.getMessage());
        }
    }

    private void remove(Long taskId, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(taskId);
        if (list != null) list.remove(emitter);
    }
}
