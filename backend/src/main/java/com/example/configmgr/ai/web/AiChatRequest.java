package com.example.configmgr.ai.web;

import java.util.Map;

/**
 * POST /api/ai/chat 请求体。
 *
 * @param sessionId 页签会话 id（记忆窗口的 CONVERSATION_ID）
 * @param message 用户本轮输入
 * @param context 工作区上下文（可空）：决定本轮披露给模型的工具子集
 */
public record AiChatRequest(String sessionId, String message, Context context) {

	public record Context(String page, String taskType, String step, Long taskId, Map<String, Object> extra) {
	}

}
