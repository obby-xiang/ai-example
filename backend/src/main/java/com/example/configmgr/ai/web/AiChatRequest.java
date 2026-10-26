package com.example.configmgr.ai.web;

import java.util.Map;

/**
 * POST /api/ai/chat 请求体。
 *
 * <h2>批③ 的两个<b>可选</b>字段（设计稿 §3.6）</h2>
 * <ul>
 * <li>{@code modelId}：本轮使用的模型（前端会话级选择，与 {@code ai_model} 的 allowlist 对账）；</li>
 * <li>{@code params}：本轮参数覆盖（temperature / top_p / max_tokens），受
 * {@code ai_setting['allow.userParams']} 约束。</li>
 * </ul>
 *
 * <p>
 * <b>兼容方向是单向的</b>：后端 {@code ObjectMapper} 是裸 {@code new ObjectMapper()}，
 * {@code FAIL_ON_UNKNOWN_PROPERTIES} 默认 true ⇒ <b>新前端 + 旧后端 = 400</b>，所以两个字段必须
 * 可选且部署顺序是"后端先上"。旧前端（不带这两个键）在新后端上照旧工作：两个字段为 null，
 * 解析口径退化成批③ 之前的同一条链（行为等价由 {@code AiChatTargetResolutionTest} 锁）。
 *
 * <p>
 * 三参构造器是<b>刻意保留</b>的：既有的 24 处 {@code new AiChatRequest(...)}（控制器归一化换新
 * record + 23 处测试）不必因为本批新增字段而改动。
 *
 * @param sessionId 页签会话 id（记忆窗口的 CONVERSATION_ID）
 * @param message 用户本轮输入
 * @param context 工作区上下文（可空）：决定本轮披露给模型的工具子集
 * @param modelId 本轮模型名（null/空白 = 用默认解析链）
 * @param params 本轮参数覆盖（null = 全部用全局默认）
 */
public record AiChatRequest(String sessionId, String message, Context context, String modelId, Params params) {

	/** 兼容构造器：批③ 之前的调用点（含控制器归一化后的换新 record）无需改动。 */
	public AiChatRequest(String sessionId, String message, Context context) {
		this(sessionId, message, context, null, null);
	}

	/**
	 * 工作区上下文。{@code ignoreUnknown} 是形状闸的前置条件：
	 * {@code ContextFieldGate} 的裁决是"未知字段一律降级（白名单化 + WARN），不拒绝请求"，
	 * 而默认的 Jackson 配置会在反序列化阶段就因未知键抛错（表现 = 500 INTERNAL_ERROR），
	 * 闸门根本轮不到执行。故这里显式放行未知键，由闸门负责丢弃与计数（X-06）。
	 */
	@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
	public record Context(String page, String taskType, String step, Long taskId, String taskStatus,
			Map<String, Object> extra) {

		/** 兼容构造器：{@code taskStatus} 之前的调用点（含测试）无需改动。 */
		public Context(String page, String taskType, String step, Long taskId, Map<String, Object> extra) {
			this(page, taskType, step, taskId, null, extra);
		}
	}

	/**
	 * 参数覆盖（键名与设计稿 §3.6 / `openai` 协议逐字一致）。
	 *
	 * <p>
	 * <b>键名即契约</b>：后端 record 的组件名就是 Jackson 认的键，改名 = 旧请求体 400。
	 * {@code AiChatRequestCompatTest#paramsKeySetIsFrozen} 用反射钉死这三个键。
	 *
	 * <p>
	 * 三个字段都可空 —— 只覆盖用户动过的那几项，其余沿用全局默认。
	 */
	public record Params(Double temperature, Double topP, Integer maxTokens) {
	}

}
