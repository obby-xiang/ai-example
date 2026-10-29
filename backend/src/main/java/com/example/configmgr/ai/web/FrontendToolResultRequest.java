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
 * @param code 前端自报的机器码（批 A additive；{@code UI_*} 闭集，可空 = 旧客户端）。
 * 入口只认 {@code ^UI_[A-Z_]{2,40}$}，<b>非法值丢弃不报 400</b>；码随条目持久化，
 * 供 {@code tool_result} 帧 / 台账 / 卡面同源取码，且<b>永不写进用户可见文案</b>。
 * @param context 挂起期的最新工作区上下文（<b>可选</b>；旧客户端不带 = 不更新快照）：
 * 与 {@code /confirm} 同一口径（同一套 {@code ContextFieldGate} 消毒），续跑前据它更新快照并重算槽位。
 */
public record FrontendToolResultRequest(String runId, String toolCallId, String result, String source,
		Boolean cancelled, String outcome, Map<String, Object> sheetMeta, String code,
		AiChatRequest.Context context) {

	/** 兼容构造器：不带工作区上下文的旧调用点（含既有单测）无需改动。 */
	public FrontendToolResultRequest(String runId, String toolCallId, String result, String source,
			Boolean cancelled, String outcome, Map<String, Object> sheetMeta, String code) {
		this(runId, toolCallId, result, source, cancelled, outcome, sheetMeta, code, null);
	}

	/** 机器码白名单形态（批 A / A-14）：{@code UI_*} 闭集形态，长度 2..40。 */
	private static final java.util.regex.Pattern UI_CODE = java.util.regex.Pattern.compile("^UI_[A-Z_]{2,40}$");

	/**
	 * 入口归一：只接受 {@code UI_*} 形态的机器码，其余一律 {@code null}（<b>丢弃不报 400</b>）。
	 *
	 * <p>
	 * 为什么容错而不是拒绝：{@code code} 是诊断字段，回灌的成功判定不能被它拖垮；
	 * 非法值丢弃后回落到既有的状态推导，与旧客户端（不带该字段）同形。
	 */
	public static String normalizeCode(String raw) {
		if (raw == null) {
			return null;
		}
		return UI_CODE.matcher(raw).matches() ? raw : null;
	}
}
