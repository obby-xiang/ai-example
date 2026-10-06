package com.example.configmgr.ai.session;

import com.example.configmgr.ai.hitl.InteractionRequest;
import lombok.Data;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory AI session (per browser tab).
 * 会话记忆使用 Spring AI 的 ChatMemory（MessageWindowChatMemory），
 * 窗口裁剪、消息存储均复用 Spring AI 能力，不自管消息列表。
 */
@Data
public class AiSession {

    private final String id;
    private volatile Instant lastActivity = Instant.now();
    private volatile boolean runActive = false;

    // Spring AI 会话记忆（窗口 100 条，覆盖最大迭代数的完整对话）
    private final ChatMemory memory = MessageWindowChatMemory.builder()
            .maxMessages(100)
            .build();

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

