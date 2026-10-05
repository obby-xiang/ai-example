package com.example.configadmin.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * AI 页签会话持久化记录：以 sessionId（浏览器页签唯一）为键，
 * 消息与上下文快照落 H2。刷新页面或后端重启后对话历史均不丢失；
 * 新页签生成新 sessionId = 新会话（无历史）。
 */
@Entity
@Table(name = "ai_session")
public class AiSessionRecord {

    @Id
    @Column(length = 64, nullable = false)
    private String sessionId;

    /** 消息 JSON：[{role, content, toolCallId, name, toolCalls:[{id,name,arguments}]}]（不含系统提示与推理内容） */
    @Lob
    @Column(nullable = false, columnDefinition = "CLOB")
    private String messagesJson = "[]";

    /** 工作区上下文快照 JSON（影子状态） */
    @Lob
    @Column(columnDefinition = "CLOB")
    private String contextJson = "{}";

    private LocalDateTime lastAccess;

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getMessagesJson() { return messagesJson; }
    public void setMessagesJson(String messagesJson) { this.messagesJson = messagesJson; }
    public String getContextJson() { return contextJson; }
    public void setContextJson(String contextJson) { this.contextJson = contextJson; }
    public LocalDateTime getLastAccess() { return lastAccess; }
    public void setLastAccess(LocalDateTime lastAccess) { this.lastAccess = lastAccess; }
}
