package com.example.configmgr.ai.web;

/**
 * POST /api/ai/confirm 请求体（确认门的人工决策）。
 *
 * @param runId 进行中那一轮的 runId（SSE {@code confirm_request} 帧携带）
 * @param toolCallId 待决工具调用 id
 * @param approved true=放行（执行），false=拒绝（不执行、原因回填）
 * @param reason 拒绝原因（放行时可空），经输入归一化后回填给模型
 * @param context 挂起期的最新工作区上下文（<b>可选</b>；旧客户端不带 = 不更新快照）：
 *                用户在挂起期间可能已在向导里改了步骤/勾选，续跑前按它更新快照，
 *                由 {@code TaskSlotResolver} 重算槽位。同一套 {@code ContextFieldGate} 消毒。
 */
public record ConfirmRequest(String runId, String toolCallId, Boolean approved, String reason,
		AiChatRequest.Context context) {

	/** 兼容构造器：不带工作区上下文的旧调用点（含既有单测）无需改动。 */
	public ConfirmRequest(String runId, String toolCallId, Boolean approved, String reason) {
		this(runId, toolCallId, approved, reason, null);
	}
}
