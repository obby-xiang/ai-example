package com.example.configmgr.ai.web;

/**
 * POST /api/ai/frontend-tool-result 请求体（前端工具结果回灌）。
 *
 * @param runId 进行中那一轮的 runId（SSE {@code frontend_tool_request} 帧携带）
 * @param toolCallId 待决工具调用 id
 * @param result 前端执行结果文本（原样作为工具结果回填给模型）
 * @param source 结果来源标注（默认 http-post），仅用于留档
 */
public record FrontendToolResultRequest(String runId, String toolCallId, String result, String source) {
}
