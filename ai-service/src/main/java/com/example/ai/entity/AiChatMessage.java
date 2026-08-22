package com.example.ai.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI对话消息 - 跨步骤持久化,按任务或全局保存
 */
@Entity
@Table(name = "ai_chat_messages", indexes = {
        @Index(name = "idx_task_id", columnList = "task_id"),
        @Index(name = "idx_created", columnList = "created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属任务ID,可为null(全局) */
    @Column(name = "task_id")
    private Long taskId;

    /** 会话编号(浏览器session/前端生成),用于前端匹配 */
    @Column(name = "session_id", length = 64)
    private String sessionId;

    /** 角色: user / assistant / system */
    @Column(nullable = false, length = 20)
    private String role;

    /** 文本内容 */
    @Lob
    @Column(name = "content", columnDefinition = "CLOB")
    private String content;

    /** AI建议执行的动作(JSON格式) {action, payload} */
    @Lob
    @Column(name = "tool_call", columnDefinition = "CLOB")
    private String toolCall;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
