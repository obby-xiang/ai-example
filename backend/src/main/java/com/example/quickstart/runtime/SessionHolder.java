package com.example.quickstart.runtime;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话-任务绑定：页签会话当前打开的活动任务。
 * 与任务本体（H2 持久化）解耦：会话只是"窗口视图"，任务可在任意会话中打开。
 */
@Component
public class SessionHolder {

    public record TaskRef(String taskId, long boundAt) {
    }

    private final Map<String, TaskRef> bindings = new ConcurrentHashMap<>();

    public TaskRef get(String sessionId) {
        return bindings.get(sessionId);
    }

    public void bind(String sessionId, String taskId) {
        bindings.put(sessionId, new TaskRef(taskId, System.currentTimeMillis()));
    }

    public void unbind(String sessionId) {
        bindings.remove(sessionId);
    }
}
