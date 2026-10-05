package com.example.configadmin.ai;

import org.springframework.ai.chat.messages.Message;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AI 会话：以浏览器页签为生命周期（sessionStorage 生成 sessionId）。
 * 消息以 Spring AI Message 表示（ChatClient + MessageChatMemoryAdvisor 官方机制维护），
 * 持久化由 AiSessionStore 落 H2（刷新/重启不丢）。
 */
public class AiSession {

    private final String id;
    private final LocalDateTime createdAt;
    private volatile LocalDateTime lastAccess;

    /** Spring AI 消息历史（用户/助手/工具结果；system 提示由 ChatClient 每轮注入，不入历史） */
    private final List<Message> messages = new ArrayList<>();

    /** 工作区上下文快照（业务→AI 同步；工具写操作同时更新影子状态，AI→业务经 ui_event 镜像） */
    private volatile Map<String, Object> context = new java.util.LinkedHashMap<>();

    /** HITL 确认门：破坏性工具执行前挂起，等待前端确认/取消（或超时视为取消） */
    private volatile ConfirmGate confirmGate;

    /** 停止标记：用户点击“停止”时置位，流式循环随之终止 */
    private volatile boolean cancelled;

    public AiSession(String id) {
        this.id = id;
        this.createdAt = LocalDateTime.now();
        this.lastAccess = createdAt;
    }

    public String getId() { return id; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getLastAccess() { return lastAccess; }
    public void touch() { this.lastAccess = LocalDateTime.now(); }
    public List<Message> getMessages() { return messages; }
    public Map<String, Object> getContext() { return context; }
    public void setContext(Map<String, Object> context) { this.context = context; }
    public ConfirmGate getConfirmGate() { return confirmGate; }
    public boolean isCancelled() { return cancelled; }
    public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }

    /** 注册待确认门（同一会话同时最多一个）。 */
    public synchronized ConfirmGate registerConfirm(String toolName, String summary) {
        ConfirmGate gate = new ConfirmGate(toolName, summary);
        this.confirmGate = gate;
        return gate;
    }

    /** 批准/拒绝当前待确认门。 */
    public synchronized boolean approveConfirm(boolean approved) {
        ConfirmGate gate = this.confirmGate;
        if (gate == null) {
            return false;
        }
        this.confirmGate = null;
        gate.release(approved);
        return true;
    }

    /** 新消息到来时自动拒绝遗留的未确认门（AG-UI 超时安全原则）。 */
    public synchronized void autoDenyPending() {
        if (this.confirmGate != null) {
            approveConfirm(false);
        }
    }

    /** 是否有待确认门。 */
    public synchronized boolean hasPendingConfirm() {
        return this.confirmGate != null && !this.confirmGate.isDone();
    }

    /** 确认门：latch 阻塞工具执行线程，等待 approve(true/false) 或超时（超时=取消）。 */
    public static final class ConfirmGate {

        private final String toolName;
        private final String summary;
        private final CountDownLatch latch = new CountDownLatch(1);
        private final AtomicReference<Boolean> approved = new AtomicReference<>(null);

        private ConfirmGate(String toolName, String summary) {
            this.toolName = toolName;
            this.summary = summary;
        }

        public String toolName() { return toolName; }
        public String summary() { return summary; }

        /** 阻塞等待确认结果：true=批准、false=取消/超时。等待期间每 20s 通过 sink 发心跳（保活流超时机制）。 */
        public boolean await(long timeoutMinutes, UiEventSink sink) {
            long deadline = System.currentTimeMillis() + timeoutMinutes * 60_000L;
            while (System.currentTimeMillis() < deadline) {
                try {
                    if (latch.await(20, TimeUnit.SECONDS)) {
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                // 心跳：确认等待期间流无元素，防止上游无事件超时/代理断连
                if (sink != null) {
                    try {
                        sink.emit(Map.of("type", "ping"));
                    } catch (Exception ignored) {
                    }
                }
            }
            return Boolean.TRUE.equals(approved.get());
        }

        public boolean isDone() {
            return latch.getCount() == 0;
        }

        private void release(boolean ok) {
            approved.set(ok);
            latch.countDown();
        }
    }
}
