package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.tool.ContextBuilder;
import com.example.configmgr.ai.web.AiChatRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code context} 字段形状闸（防护批③ B2-5）：<b>白名单化 + 降级不回显</b>，不拒绝请求。
 *
 * <h2>为什么"降级"而不是 400</h2>
 * 用户裁决 2026-10-21 第 1 条：未知 page / 未知字段一律降级（白名单化 + WARN 计数），
 * 不拒绝请求 —— 前端版本错位、插件式页面上报未知 page 都是正常现象，
 * 一次 400 就会让整轮对话不可用；而"降级"没有任何安全损失：
 * <ul>
 * <li>{@code page} 不在 {@link ContextBuilder#PAGE_LABELS} 内 ⇒ 归一为 {@code unknown}，
 * 系统提示里渲染为「未知页面」<b>不回声原值</b>（此前是原样拼进系统提示，等于让客户端往最高信任位写文本）；</li>
 * <li>{@code step} 只保留"字母/数字/下划线/连字符/点/斜杠/空格"且截 64 字符；</li>
 * <li>{@code taskType} 只认 {@code EXPORT}/{@code IMPORT}（{@code Task.TaskType} 全集）；</li>
 * <li>{@code taskId} ≤ 0 视为未提供；</li>
 * <li>{@code taskStatus} 只认 {@code ACTIVE/COMPLETED/CANCELLED/FAILED}（未知值降级为 null =
 *     不做终态收窄）；</li>
 * <li>{@code extra} 只留 {@link ContextBuilder#ALLOWED_EXTRA_KEYS}（非白名单键丢弃并计数）
 *     <b>加上</b>两张旁路表：{@link ContextBuilder#TAG_ONLY_EXTRA_KEYS}（只进披露判定、不进模型载荷）
 *     与 {@link ContextBuilder#JUDGE_ONLY_EXTRA_KEYS}（只进槽位判定、值只认布尔）。</li>
 * </ul>
 *
 * <p>
 * 本类<b>不</b>做文本归一化（那是 {@link InputSanitizer} 对 {@code message} 的职责），也<b>不</b>
 * 承诺"闸后字段可信"：消费方（{@code ContextBuilder}）仍按 {@code pageLabel} 再兜一次未知值。
 */
@Slf4j
@Component
public class ContextFieldGate {

	/** 未知 page 的归一 id（故意不在 {@code PAGE_LABELS} 里 ⇒ 下游渲染为「未知页面」）。 */
	public static final String UNKNOWN_PAGE_ID = "unknown";

	/** {@code step} 的字符上限。 */
	public static final int STEP_MAX_CHARS = 64;

	/** {@code page} 的字符上限（合法 id 都是短标识符）。 */
	public static final int PAGE_MAX_CHARS = 32;

	private static final List<String> TASK_TYPES = List.of("EXPORT", "IMPORT");

	/** 任务状态闭集（与 {@code Task.TaskStatus} 同构）；未知值降级为 null（= 不做终态收窄）。 */
	private static final List<String> TASK_STATUSES = List.of("ACTIVE", "COMPLETED", "CANCELLED", "FAILED");

	/** 闸后结果：{@code downgraded} > 0 表示有字段被降级（日志/计数用，不含原文）。 */
	public record Result(AiChatRequest.Context context, int downgraded, List<String> reasons) {
	}

	/** 闸门（{@code raw == null} 原样返回 null —— "没有上下文"是合法状态）。 */
	public Result sanitize(AiChatRequest.Context raw) {
		if (raw == null) {
			return new Result(null, 0, List.of());
		}
		List<String> reasons = new ArrayList<>();
		String page = page(raw.page(), reasons);
		String taskType = taskType(raw.taskType(), reasons);
		String step = step(raw.step(), reasons);
		Long taskId = raw.taskId() == null ? null : raw.taskId();
		if (taskId != null && taskId <= 0) {
			taskId = null;
			reasons.add("taskId");
		}
		String taskStatus = taskStatus(raw.taskStatus(), reasons);
		Map<String, Object> extra = extra(raw.extra(), reasons);
		AiChatRequest.Context context = new AiChatRequest.Context(page, taskType, step, taskId, taskStatus, extra);
		if (!reasons.isEmpty()) {
			log.warn("context 字段降级（未回显原值）降级项={} 项数={}", reasons, reasons.size());
		}
		return new Result(context, reasons.size(), List.copyOf(reasons));
	}

	private static String page(String raw, List<String> reasons) {
		if (raw == null || raw.isBlank()) {
			return "";
		}
		String trimmed = raw.trim();
		if (ContextBuilder.PAGE_LABELS.containsKey(trimmed)) {
			return trimmed;
		}
		reasons.add("page");
		return UNKNOWN_PAGE_ID;
	}

	private static String taskType(String raw, List<String> reasons) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String upper = raw.trim().toUpperCase(java.util.Locale.ROOT);
		if (TASK_TYPES.contains(upper)) {
			return upper;
		}
		reasons.add("taskType");
		return null;
	}

	private static String taskStatus(String raw, List<String> reasons) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String upper = raw.trim().toUpperCase(java.util.Locale.ROOT);
		if (TASK_STATUSES.contains(upper)) {
			return upper;
		}
		reasons.add("taskStatus");
		return null;
	}

	private static String step(String raw, List<String> reasons) {
		if (raw == null || raw.isBlank()) {
			return raw == null ? null : raw;
		}
		StringBuilder out = new StringBuilder(Math.min(raw.length(), STEP_MAX_CHARS));
		for (int i = 0; i < raw.length() && out.length() < STEP_MAX_CHARS;) {
			int codePoint = raw.codePointAt(i);
			int type = Character.getType(codePoint);
			boolean invisible = type == Character.FORMAT || type == Character.SURROGATE || type == Character.CONTROL;
			boolean allowed = Character.isLetterOrDigit(codePoint) || codePoint == '_' || codePoint == '-'
					|| codePoint == '.' || codePoint == '/' || codePoint == ' ';
			if (!invisible && allowed) {
				out.appendCodePoint(codePoint);
			}
			i += Character.charCount(codePoint);
		}
		String text = out.toString();
		if (!text.equals(raw)) {
			reasons.add("step");
		}
		return text;
	}

	private static Map<String, Object> extra(Map<String, Object> raw, List<String> reasons) {
		if (raw == null || raw.isEmpty()) {
			return new LinkedHashMap<>();
		}
		Map<String, Object> kept = new LinkedHashMap<>();
		int dropped = 0;
		for (Map.Entry<String, Object> entry : raw.entrySet()) {
			if (ContextBuilder.ALLOWED_EXTRA_KEYS.contains(entry.getKey())) {
				kept.put(entry.getKey(), entry.getValue());
			}
			else if (ContextBuilder.JUDGE_ONLY_EXTRA_KEYS.contains(entry.getKey())) {
				// 只判定、不进模型载荷的页面事实（selectionSynced）：只认布尔值，其它形态按"缺失"
				// 处理（与旧客户端同形）；它不会出现在 renderExtra 里（不在 ALLOWED_EXTRA_KEYS）。
				if (entry.getValue() instanceof Boolean flag) {
					kept.put(entry.getKey(), flag);
				}
				else {
					dropped++;
				}
			}
			else if (ContextBuilder.TAG_ONLY_EXTRA_KEYS.contains(entry.getKey())) {
				// 标签专用旁路（缺口 0）：这些键不进模型载荷（ContextBuilder 的载荷白名单不含它们），
				// 但披露判定（ToolRegistry#buildTags）必须看得见 —— 在此丢弃等于 ui:on 恒失败开放。
				// 只放行可解析的有限数值；其它形态按"缺失"处理（与旧客户端同形，不制造新的拒执面）。
				Object value = tagValue(entry.getValue());
				if (value == null) {
					dropped++;
				}
				else {
					kept.put(entry.getKey(), value);
				}
			}
			else {
				dropped++;
			}
		}
		if (dropped > 0) {
			reasons.add("extra:" + dropped);
		}
		return kept;
	}

	/** 标签键的值形态闸：只认有限数值（Number / 数字字符串），其余一律 null（= 视为缺失）。 */
	private static Object tagValue(Object raw) {
		if (raw instanceof Number number) {
			return Double.isFinite(number.doubleValue()) ? number : null;
		}
		if (raw instanceof String text) {
			try {
				double value = Double.parseDouble(text.trim());
				return Double.isFinite(value) ? text.trim() : null;
			}
			catch (NumberFormatException ex) {
				return null;
			}
		}
		return null;
	}

}
