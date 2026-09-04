package com.example.ai.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Agent Loop 运行时状态 —— 用于"前端工具暂停-恢复"机制。
 *
 * 设计依据（响应经验 100029435 + 业界 LangGraph checkpointer / OpenAI Assistants Thread）：
 *   1. 当 agent loop 遇到前端工具（FRONTEND kind）时，将当前 loop 上下文序列化保存，
 *      返回 resumeToken 给前端；前端执行完工具后通过 token 回灌结果，恢复 loop。
 *   2. 同一 sessionId 同一时间只能有一个 WAITING_TOOL 状态的 state（避免并发混乱）。
 *   3. 24h 未恢复自动清理（由定时任务或下次启动清理）。
 *
 * ====== Phase 2 扩展（Plan-Execute + 状态机 + 工具分级）======
 *   - planJson             - 计划 JSON（Planner 生成的步骤序列，含 status）
 *   - stepArtifactsJson    - 各步骤产物 JSON（跨步骤数据传递，业界 LangGraph state 黑板模式）
 *   - currentScenario      - 当前场景 ID（EXPORT/IMPORT/ADD/MODIFY）
 *   - currentStep          - 当前步骤 ID（SELECT_SCENARIO/SELECT_DEFS/...）
 *   - mode                 - 当前模式：PLAN/EXECUTE/AUTO/CONFIRM（决定前端如何渲染）
 *
 * 字段说明：
 *   messagesJson        - 完整 OpenAI 协议 messages 数组（含 tool_calls + tool results）
 *   pendingToolCallsJson - 待前端执行的工具调用列表（FrontendToolCall DTO JSON）
 *   status              - WAITING_TOOL / RUNNING / COMPLETED / EXPIRED
 */
@Entity
@Table(name = "ai_agent_state", indexes = {
        @Index(name = "idx_agent_session", columnList = "session_id"),
        @Index(name = "idx_agent_status", columnList = "status"),
        @Index(name = "idx_agent_expires", columnList = "expires_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentState {

    @Id
    @Column(name = "resume_token", length = 64)
    private String resumeToken;

    @Column(name = "task_id")
    private Long taskId;

    @Column(name = "session_id", length = 64)
    private String sessionId;

    /** OpenAI 协议 messages 数组 JSON: [{role, content, tool_calls?, tool_call_id?}, ...] */
    @Lob
    @Column(name = "messages_json", columnDefinition = "CLOB")
    private String messagesJson;

    /** 待前端执行的工具调用列表 JSON (List<FrontendToolCall>) */
    @Lob
    @Column(name = "pending_tool_calls_json", columnDefinition = "CLOB")
    private String pendingToolCallsJson;

    /** 模型本次响应中的自然语言 content（如有，暂停时一并返回给前端展示） */
    @Lob
    @Column(name = "interim_content", columnDefinition = "CLOB")
    private String interimContent;

    /** ====== Phase 2 扩展字段 ====== */
    /** 计划 JSON: [{step, action, status, expectedTools}, ...] */
    @Lob
    @Column(name = "plan_json", columnDefinition = "CLOB")
    private String planJson;

    /** 步骤产物 JSON: { STEP_ID: { ...产物... }, ... }（跨步骤数据传递） */
    @Lob
    @Column(name = "step_artifacts_json", columnDefinition = "CLOB")
    private String stepArtifactsJson;

    /** 当前场景 ID: EXPORT / IMPORT / ADD / MODIFY */
    @Column(name = "current_scenario", length = 32)
    private String currentScenario;

    /** 当前步骤 ID: SELECT_SCENARIO / SELECT_DEFS / VIEW_DEFS / QUERY_COND / PRECHECK / REVIEW / PUBLISH / RESULT */
    @Column(name = "current_step", length = 32)
    private String currentStep;

    /** 当前模式: PLAN(规划中) / EXECUTE(执行中) / AUTO(自动执行) / CONFIRM(待用户确认) */
    @Column(name = "mode", length = 16)
    private String mode;

    /** ====== 改造 D3b ====== 挂起时的工作区数据版本号（回灌时对比，检测挂起期间用户手动修改） */
    @Column(name = "data_version")
    private Long dataVersion;

    /** WAITING_TOOL / RUNNING / COMPLETED / EXPIRED */
    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (expiresAt == null) {
            expiresAt = createdAt.plusHours(24);
        }
    }
}
