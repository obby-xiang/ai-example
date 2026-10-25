package com.example.configmgr.ai.history;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 用户可见的完整对话历史一行（{@code ai_chat_message}，V7）。
 *
 * <p>
 * 与模型侧记忆窗口（{@code ChatMemory} / Redis {@code chat:mem:*}）是<b>两个面</b>：
 * 记忆窗口按 40 条裁剪、带 24h 滑动 TTL；本表保存同一会话的全部消息，不随窗口消失，
 * 也不复用运行态 TTL。它不承担"给模型回放"的职责（回放仍走记忆窗口/快照）。
 *
 * <h2>字段口径</h2>
 * <ul>
 * <li>{@link #seq}：每会话单调递增（从 1 开始），{@code (session_id, seq)} 唯一，
 * 是游标分页的唯一排序键；</li>
 * <li>{@link #runId}：一轮对话的 id；同一 run 允许多条消息（user / assistant(tool_calls) /
 * tool / assistant(final)），因此只建普通索引；</li>
 * <li>{@link #messageId}：消息身份（与 SSE 帧的 {@code messageId} 同源），
 * {@code (session_id, run_id, message_id)} 唯一 —— 同一落定点重复执行（converge / 重启 resume）
 * 命中既有行即返回，不重复插入；</li>
 * <li>{@link #toolCallsJson} / {@link #toolResponsesJson} / {@link #metadataJson}：
 * 与 {@code MessageJsonCodec#toNode} 同字段名的<b>原文 JSON</b>，不做二次解析或裁剪。
 * {@code metadataJson} 保留 {@code reasoningContent} 等供应商元数据，未知键也原样保存；</li>
 * <li>{@link #stopped}：被用户停止的半截 assistant 消息标记（与消息 metadata 的
 * {@code stopped} 同义，这里做成可查询列）。</li>
 * </ul>
 *
 * <p>本表没有密钥/凭据列。DDL 在 {@code V8__ai_chat_message.sql}；幂等唯一键在
 * {@code V9__ai_chat_message_idempotency.sql}；{@code ddl-auto=validate} ⇒ 实体不建表。
 */
@Data
@Entity
@Table(name = "ai_chat_message",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_ai_chat_message_session_seq",
                        columnNames = { "session_id", "seq" }),
                @UniqueConstraint(name = "uq_ai_chat_message_session_run_message",
                        columnNames = { "session_id", "run_id", "message_id" }) },
        indexes = {
                @Index(name = "ix_ai_chat_message_session_run", columnList = "session_id,run_id"),
                @Index(name = "ix_ai_chat_message_session_message", columnList = "session_id,message_id") })
public class AiChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 128)
    private String sessionId;

    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;

    /** 每会话单调递增序号；唯一约束 + 追加重试保证不重复、不跳号。 */
    @Column(name = "seq", nullable = false)
    private long seq;

    @Column(name = "message_id", nullable = false, length = 128)
    private String messageId;

    /** {@code MessageType} 名：SYSTEM / USER / ASSISTANT / TOOL。 */
    @Column(name = "role", nullable = false, length = 16)
    private String role;

    @Column(name = "text", columnDefinition = "CLOB")
    private String text;

    @Column(name = "tool_calls_json", columnDefinition = "CLOB")
    private String toolCallsJson;

    @Column(name = "tool_responses_json", columnDefinition = "CLOB")
    private String toolResponsesJson;

    @Column(name = "metadata_json", columnDefinition = "CLOB")
    private String metadataJson;

    @Column(name = "stopped", nullable = false)
    private boolean stopped;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
