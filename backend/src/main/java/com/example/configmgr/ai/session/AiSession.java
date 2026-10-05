package com.example.configmgr.ai.session;

import com.example.configmgr.ai.hitl.InteractionRequest;
import lombok.Data;
import org.springframework.ai.chat.messages.Message;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory AI session (per browser tab).
 */
@Data
public class AiSession {

    private final String id;
    private volatile Instant lastActivity = Instant.now();
    private volatile boolean runActive = false;

    // Conversation history for the LLM
    private final List<Message> history = new ArrayList<>();

    // 最近一轮对话的 token 消耗（供刷新后恢复显示）
    private volatile long lastPromptTokens = 0;
    private volatile long lastCompletionTokens = 0;

    // Context reported by the frontend
    private volatile WorkspaceContext context = new WorkspaceContext();

    // Pending HITL interaction
    private volatile InteractionRequest pendingInteraction;

    // For cancelling current run
    private final AtomicReference<Thread> currentRunThread = new AtomicReference<>();

    public synchronized void touch() {
        lastActivity = Instant.now();
    }

    public synchronized boolean tryStartRun() {
        if (runActive) return false;
        runActive = true;
        return true;
    }

    public synchronized void endRun() {
        runActive = false;
        currentRunThread.set(null);
    }

    public void cancelRun() {
        Thread t = currentRunThread.get();
        if (t != null) t.interrupt();
    }

    @Data
    public static class WorkspaceContext {
        private String page = "";
        private Long taskId;
        private String taskType;
        private String step;
        private Map<String, Object> extra = new ConcurrentHashMap<>();

        public String getContextKey() {
            if (taskType != null && step != null) return "task:" + taskType + "/" + step;
            if (page != null && !page.isBlank()) return "page:" + page;
            return "*";
        }
    }
}
