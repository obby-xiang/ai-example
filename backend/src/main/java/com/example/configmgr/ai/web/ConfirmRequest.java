package com.example.configmgr.ai.web;

/**
 * POST /api/ai/confirm 请求体（确认门的人工决策）。
 *
 * @param runId 进行中那一轮的 runId（SSE {@code confirm_request} 帧携带）
 * @param toolCallId 待决工具调用 id
 * @param approved true=放行（执行），false=拒绝（不执行、原因回填）
 * @param reason 拒绝原因（放行时可空），会原样回填给模型
 */
public record ConfirmRequest(String runId, String toolCallId, Boolean approved, String reason) {
}
