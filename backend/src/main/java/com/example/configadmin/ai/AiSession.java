package com.example.configadmin.ai;

import org.springframework.ai.openai.api.OpenAiApi;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI 会话：以浏览器页签为生命周期（sessionStorage 生成 sessionId），
 * 仅存内存、不持久化、不看历史。包含 OpenAI 协议消息与工作区上下文快照（影子状态）。
 */
public class AiSession {

    private final String id;
    private final LocalDateTime createdAt;
    private volatile LocalDateTime lastAccess;

    /** OpenAI 协议消息（首条为 system，含渐进式披露的工具清单） */
    private final List<OpenAiApi.ChatCompletionMessage> messages = new ArrayList<>();

    /** 工作区上下文快照（业务→AI 同步；工具写操作同时更新影子状态，AI→业务经 ui_event 镜像） */
    private volatile Map<String, Object> context = new java.util.LinkedHashMap<>();

    /** 待确认的工具调用批次（HITL：确认后继续执行批次剩余调用） */
    private volatile PendingConfirm pending;

    public AiSession(String id) {
        this.id = id;
        this.createdAt = LocalDateTime.now();
        this.lastAccess = createdAt;
    }

    public String getId() { return id; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getLastAccess() { return lastAccess; }
    public void touch() { this.lastAccess = LocalDateTime.now(); }
    public List<OpenAiApi.ChatCompletionMessage> getMessages() { return messages; }
    public Map<String, Object> getContext() { return context; }
    public void setContext(Map<String, Object> context) { this.context = context; }
    public PendingConfirm getPending() { return pending; }
    public void setPending(PendingConfirm pending) { this.pending = pending; }

    /** 待确认批次：calls 为整批工具调用，index 为当前待执行位置。 */
    public record PendingConfirm(List<OpenAiApi.ChatCompletionMessage.ToolCall> calls, int index) {
    }
}
