package com.example.configmgr.ai.tool;

import com.example.configmgr.common.CodedIllegalArgumentException;
import com.example.configmgr.common.CodedIllegalStateException;
import com.example.configmgr.common.ErrorCodes;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具结果的统一信封（P0-1）。
 *
 * <p>
 * 同一份信封服务三个面：模型侧 {@code ToolResponse.responseData}、SSE {@code tool_result} 帧
 * （取出 outcome/code/retryable/target 四个字段）、执行台账（outcome/code 落档）。
 * 字段口径：
 * <ul>
 * <li>{@code ok}：{@code outcome == ok} 的派生值；</li>
 * <li>{@code code}：机器可读错误码（成功为 null）；</li>
 * <li>{@code outcome}：{@link ToolOutcome} 的线上字面量；</li>
 * <li>{@code retryable}：由 outcome + code 推导（见 {@link ToolOutcome#retryable(String)}）；</li>
 * <li>{@code target}：从工具参数 JSON 提取的 {@code {taskId?, defCode?, step?, page?}}，提取不到为 null；</li>
 * <li>{@code evidence}：一期恒为 null（证据面后续单独施工）；</li>
 * <li>{@code message}：原有的人读文本（越 scope/拒绝/超时文案一字不改）。</li>
 * </ul>
 *
 * <p>
 * <b>裁剪顺序</b>：模型侧必须先对 {@code message} 跑 {@link ToolResultLimiter#forModel}，
 * 再序列化整份 JSON（{@link #toModelJson(ToolResultLimiter)}）—— 先组装后截断会把 JSON 本身截坏。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ToolResultEnvelope(boolean ok, String code, String outcome, boolean retryable,
		Map<String, Object> target, Object evidence, String message) {

	/** 目标资源提取的字段白名单（顺序即序列化顺序）。 */
	private static final List<String> TARGET_KEYS = List.of("taskId", "defCode", "step", "page");

	private static final ObjectMapper JSON = new ObjectMapper();

	public static ToolResultEnvelope ok(String message, String argumentsJson) {
		return of(ToolOutcome.OK, null, message, argumentsJson);
	}

	public static ToolResultEnvelope failed(String code, String message, String argumentsJson) {
		return of(ToolOutcome.FAILED, code, message, argumentsJson);
	}

	public static ToolResultEnvelope rejected(String code, String message, String argumentsJson) {
		return of(ToolOutcome.REJECTED, code, message, argumentsJson);
	}

	public static ToolResultEnvelope blocked(String code, String message, String argumentsJson) {
		return of(ToolOutcome.BLOCKED, code, message, argumentsJson);
	}

	public static ToolResultEnvelope timeout(String code, String message, String argumentsJson) {
		return of(ToolOutcome.TIMEOUT, code, message, argumentsJson);
	}

	public static ToolResultEnvelope cancelled(String code, String message, String argumentsJson) {
		return of(ToolOutcome.CANCELLED, code, message, argumentsJson);
	}

	/** 统一工厂：outcome 为 null 时按 {@link ToolOutcome#UNKNOWN}（未知不等于失败）。 */
	public static ToolResultEnvelope of(ToolOutcome outcome, String code, String message, String argumentsJson) {
		ToolOutcome effective = outcome == null ? ToolOutcome.UNKNOWN : outcome;
		return new ToolResultEnvelope(effective.ok(), code, effective.wire(), effective.retryable(code),
				targetOf(argumentsJson), null, message == null ? "" : message);
	}

	/** 从参数 JSON 提取目标资源；没有可提取字段/解析失败一律 null。 */
	public static Map<String, Object> targetOf(String argumentsJson) {
		if (argumentsJson == null || argumentsJson.isBlank()) {
			return null;
		}
		try {
			JsonNode node = JSON.readTree(argumentsJson);
			if (node == null || !node.isObject()) {
				return null;
			}
			Map<String, Object> target = new LinkedHashMap<>();
			for (String key : TARGET_KEYS) {
				JsonNode value = node.get(key);
				if (value != null && !value.isNull()) {
					target.put(key, JSON.convertValue(value, Object.class));
				}
			}
			return target.isEmpty() ? null : target;
		}
		catch (Exception ex) {
			return null;
		}
	}

	/** 信封 JSON（含 null 字段，供模型/台账读取固定键面）。 */
	public String toJson() {
		try {
			return JSON.writeValueAsString(this);
		}
		catch (JsonProcessingException ex) {
			throw new CodedIllegalStateException(ErrorCodes.TOOL_ENVELOPE_SERIALIZE_FAILED, "工具结果信封序列化失败：" + this.message, ex);
		}
	}

	/**
	 * 模型侧形态：<b>先裁剪 message，再组装 JSON</b>（防截断破坏 JSON 结构）。
	 */
	public String toModelJson(ToolResultLimiter limiter) {
		String limited = limiter == null ? this.message : limiter.forModel(this.message);
		return new ToolResultEnvelope(this.ok, this.code, this.outcome, this.retryable, this.target, this.evidence,
				limited).toJson();
	}

	/** 反序列化（测试/诊断/续跑读取）。 */
	public static ToolResultEnvelope fromJson(String json) {
		if (json == null || json.isBlank()) {
			return null;
		}
		try {
			return JSON.readValue(json, ToolResultEnvelope.class);
		}
		catch (JsonProcessingException ex) {
			throw new CodedIllegalArgumentException(ErrorCodes.TOOL_ENVELOPE_PARSE_FAILED, "工具结果信封解析失败：" + json, ex);
		}
	}

}
