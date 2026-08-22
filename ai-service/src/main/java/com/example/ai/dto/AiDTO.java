package com.example.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

public class AiDTO {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChatReq {
        private Long taskId;
        private String sessionId;
        private String message;
        /** 当前工作区状态快照,由前端提供 */
        private Map<String, Object> workspaceState;
        /** 是否需要系统自动提示(工作区触发) */
        @Builder.Default
        private Boolean autoPrompt = false;
        /** 触发自动提示的事件: select_scenario / enter_step / data_change 等 */
        private String triggerEvent;
        /** ====== Phase 2 新增 ====== 当前场景 ID（用于工具动态发现） */
        private String currentScenario;
        /** ====== Phase 2 新增 ====== 当前步骤 ID（用于工具动态发现） */
        private String currentStep;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChatResp {
        private Long messageId;
        /** AI面向用户的自然语言回复。注意：**永远不包含工具调用JSON/标记**，工具调用见 toolCalls 字段。 */
        private String reply;
        /** 结构化前端工具调用（Function Calling 主路径）。前端优先消费此字段。 */
        private List<FrontendToolCall> toolCalls;
        /** ===== 降级兼容字段（老协议 actions）=====
         *  仅当 LLM 供应商接口未返回 tool_calls 或解析失败时，
         *  后端通过文本兜底解析填充此字段。前端可作为 fallback 展示。*/
        private List<AiAction> actions;

        /**
         * ====== Agent Loop 暂停-恢复机制字段（方案二核心）======
         * 当 agent loop 遇到前端工具（FRONTEND kind）时，后端会保存 loop 状态，
         * 返回 resumeToken 给前端。前端执行完工具后，POST /api/ai/tool-result
         * 携带 resumeToken + 结果，后端恢复 loop 继续推理。
         *
         * done=false 表示 loop 尚未结束，前端执行完工具后必须回灌结果。
         * done=true  表示对话已完成，前端不再需要回灌。
         */
        private String resumeToken;
        @Builder.Default
        private Boolean done = true;
        /** 当前等待回灌结果的 callId（前端可据此渲染"执行中"状态） */
        private String pendingCallId;

        /** ====== Phase 2 新增（Plan-Execute + 工具分级）====== */
        /** 当前计划（首次响应时由 Planner 生成，前端据此渲染 Plan 卡片让用户一次确认） */
        private List<PlanStep> plan;
        /** 当前模式：PLAN(规划中)/EXECUTE(执行中)/AUTO(自动执行)/CONFIRM(待用户确认)/DONE(完成) */
        private String mode;
        /** 当前场景（前端可据此更新工作区） */
        private String currentScenario;
        /** 当前步骤（前端可据此更新工作区） */
        private String currentStep;
    }

    /**
     * 前端工具执行结果回灌请求（POST /api/ai/tool-result）。
     *
     * 字段：
     *   resumeToken - 来自 ChatResp.resumeToken，用于恢复 loop 状态
     *   callId      - 来自 FrontendToolCall.callId，标识本次回灌对应哪个工具
     *   result      - 工具执行结果 { ok: bool, message: string, data?: any }
     *
     * 当用户取消工具时，前端应 POST { ok:false, message:"user_cancelled" }，
     * 后端将这个语义回灌给模型，让模型知道被拒绝并调整后续策略。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolResultReq {
        private String resumeToken;
        private String callId;
        private Map<String, Object> result;
    }

    /**
     * ====== 结构化前端工具调用（Function Calling 主协议）======
     * 由 LLM 标准 tool_calls 字段 + FrontendTools 声明派生。
     * 这是前后端交互的"主契约"，替代了旧的文本 JSON 代码块解析方案。
     *
     * 字段同源说明：
     *   toolName / args —— 来自 LLM 返回的 tool_calls.function{name,arguments}
     *   needConfirm     —— 来自 FrontendTools.defaultNeedConfirm(toolName)，前端可覆盖
     *   impact          —— 来自 FrontendTools.summarizeImpact(toolName, args)，给确认弹窗使用
     *
     * Phase 2 新增：
     *   autoExec            —— 是否自动执行（无需用户点击），来自 ToolDiscoveryService
     *   requireDoubleConfirm —— 是否需要二次确认弹框（高危流程）
     *   mode                —— 当前模式 AUTO/CONFIRM（前端据此决定渲染 loading 还是卡片）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FrontendToolCall {
        /** 本次调用唯一ID（由后端生成，用于前端确认/取消/日志追踪） */
        private String callId;
        /** 工具名，必须与前端 frontend-tool-registry.js 和后端 FrontendTools.ALL 一致 */
        private String toolName;
        /** 工具参数。结构必须符合 FrontendTools.ALL 中对应工具的 JSON Schema */
        private Map<String, Object> args;
        /** 中文标题（人类可读，供卡片展示） */
        private String title;
        /** 影响范围摘要（供确认弹窗展示） */
        private String impact;
        /** 是否需要用户确认（破坏性/批量工具默认 true） */
        @Builder.Default
        private Boolean needConfirm = true;
        /** 是否来自旧协议兜底转换（前端可据此打标，方便排障） */
        @Builder.Default
        private Boolean legacy = false;
        /** ====== Phase 2 新增 ====== 是否自动执行（无需用户点击，前端直接执行并自动回灌） */
        @Builder.Default
        private Boolean autoExec = false;
        /** ====== Phase 2 新增 ====== 是否需要二次确认弹框（高危流程如 run_flow） */
        @Builder.Default
        private Boolean requireDoubleConfirm = false;
        /** ====== Phase 2 新增 ====== 模式：AUTO（自动执行）/CONFIRM（待用户确认） */
        @Builder.Default
        private String mode = "CONFIRM";
    }

    /**
     * ====== Phase 2 新增：Plan-Execute 模式的计划步骤 ======
     * 业界 Plan-and-Execute 模式核心数据结构（响应经验 100029435）。
     *
     * 设计原则：
     *   1. Planner 生成完整 plan（只描述步骤，不指定具体参数）
     *   2. Execute 阶段逐步执行，每步基于前一步结果推理（避免参数依赖）
     *   3. 用户在 Plan 阶段一次确认整个计划，代替每步确认
     *
     * 字段：
     *   stepId         - 步骤 ID（对应 scenarios.yaml 中的步骤）
     *   description    - 步骤描述（人类可读）
     *   expectedTools  - 该步骤预期使用的工具（参考，非约束）
     *   status         - PENDING / RUNNING / COMPLETED / SKIPPED / FAILED
     *   autoExec       - 该步骤是否自动执行（只读/导航类 true，破坏性 false）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlanStep {
        private Integer order;
        private String stepId;
        private String description;
        private List<String> expectedTools;
        @Builder.Default
        private String status = "PENDING";
        @Builder.Default
        private Boolean autoExec = true;
        /** 步骤完成后的产物摘要（执行时填充，跨步骤传递） */
        private Map<String, Object> artifacts;
    }

    /**
     * 旧协议 AI 推荐动作（降级兜底专用）。新代码直接使用 FrontendToolCall。
     *
     * @deprecated 保留仅为了兼容 Spring AI Function Calling 不可用时的文本解析路径。
     */
    @Deprecated(forRemoval = false)
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AiAction {
        /** 唯一ID */
        private String id;
        /** 动作类型: navigate / select_defs / table_update / table_delete / table_replace / flow / confirm_complete */
        private String type;
        /** 人类可读的描述 */
        private String title;
        /** 预计影响范围描述 */
        private String impact;
        /** 载荷参数 */
        private Map<String, Object> payload;
        /** 是否需要用户确认 */
        @Builder.Default
        private Boolean needConfirm = true;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HistoryReq {
        private Long taskId;
        private String sessionId;
    }
}
