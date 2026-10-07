package com.example.configmgr.ai.web;

/**
 * POST /api/ai/frontend-tool-result 请求体（前端工具结果回灌）。
 *
 * @param runId 进行中那一轮的 runId（SSE {@code frontend_tool_request} 帧携带）
 * @param toolCallId 待决工具调用 id
 * @param result 前端执行结果文本（原样作为工具结果回填给模型）
 * @param source 结果来源标注（默认 http-post），仅用于留档
 * @param cancelled 前端是否报告"用户主动放弃"（关闭表单/取消对话框）——DC-15 取消终态：
 * 置 true 时 {@code result} 被忽略，待决条目落 {@code FRONTEND_CANCELLED}（明确终态，
 * 挂起立刻收敛而不是悬到超时）。可为 null（缺省按 false，老客户端零改动）。
 */
public record FrontendToolResultRequest(String runId, String toolCallId, String result, String source,
		Boolean cancelled) {
}
