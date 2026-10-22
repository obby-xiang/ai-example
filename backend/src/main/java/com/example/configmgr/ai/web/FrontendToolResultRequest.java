package com.example.configmgr.ai.web;

import java.util.Map;

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
 * @param outcome 前端自报的本次执行结局（P0-1：{@code ok|failed|rejected|blocked|timeout|cancelled|unknown}），
 * 可空 —— 缺省时后端按现行口径兜底为成功。仅用于推导结局帧的 {@code ok} 与持久化信封，
 * 不改 {@code result} 原文（模型读的仍是原文）。
 * @param sheetMeta 表格工具的采样元数据（AI 表格工具治理批；可空 = 非表格工具/旧前端）。
 * 只允许 {@code SheetAudit.ALLOWED_FIELDS} 白名单键（dataClass / sampleRows / sampleChars /
 * projCols / totalRows / sheetVer / planId / previewLevel / defCode / page）；<b>其余键在入口丢弃</b>
 * —— 单元格值、条件取值、scopeSummary 原文因此在结构上进不了日志与台账。
 */
public record FrontendToolResultRequest(String runId, String toolCallId, String result, String source,
		Boolean cancelled, String outcome, Map<String, Object> sheetMeta) {
}
