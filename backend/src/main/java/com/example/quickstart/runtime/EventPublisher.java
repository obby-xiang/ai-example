package com.example.quickstart.runtime;

import com.example.quickstart.service.TaskService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 事件分发：业务层变更后调用，向会话推送 state/progress/activity。
 * 业务层不感知 SSE 细节（解耦）；无监听者时静默丢弃。
 * TaskService 用 @Lazy 打破构造循环（TaskService → EventPublisher → TaskService）。
 */
@Component
public class EventPublisher {

    private final SessionRegistry registry;
    private final TaskService taskService;

    public EventPublisher(SessionRegistry registry, @Lazy TaskService taskService) {
        this.registry = registry;
        this.taskService = taskService;
    }

    public void publishState(String sessionId, String taskId, String cause) {
        if (sessionId == null) {
            return;
        }
        registry.send(sessionId, "state", new Events.StateEvent(cause, taskService.taskMap(taskId)));
    }

    public void publishProgress(String sessionId, Events.ProgressEvent e) {
        if (sessionId == null) {
            return;
        }
        registry.send(sessionId, "progress", e);
    }

    public void publishActivity(String sessionId, String tool, String text) {
        if (sessionId == null) {
            return;
        }
        registry.send(sessionId, "activity", new Events.ActivityEvent("AI", tool, text));
    }

    public void publishNotice(String sessionId, String level, String text) {
        if (sessionId == null) {
            return;
        }
        registry.send(sessionId, "notice", new Events.NoticeEvent(level, text));
    }

    public Map<String, Object> snapshotOrEmpty(String taskId) {
        try {
            return taskId == null ? null : taskService.taskMap(taskId);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
