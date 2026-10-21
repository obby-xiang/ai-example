package com.example.configmgr.ai.context;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 上下文类目拆分（上下文统计与压缩专项批①；设计稿 §3.3，裁决 D4 = 六项 + {@code free}）。
 *
 * <h2>为什么要六项而不是四项</h2>
 * 本仓工具走<b>渐进披露</b>：7–18 个工具的 schema 常驻请求前缀，不单列 {@code tools} 会让
 * "system 异常巨大"这一结论误导排障（真正的成本在 schema）；{@code free} 则是用户唯一能读懂的
 * "还剩多少"。{@code summary} 单列（不并入 {@code history}）是因为压缩后"历史变短、摘要顶上"
 * 这件事必须可读，否则压缩的效果在界面里看不出来。
 *
 * <h2>守恒（{@link #normalizeTo}）</h2>
 * 分项是<b>估算</b>、总量是<b>账单</b>（{@code usage.promptTokens}）。若直接展示估算分项，
 * 用户会看到"分项加起来 ≠ 已用"的加总矛盾。故一律按比例归一：
 * {@code scaled_i = floor(v_i × used / Σv)}，差额补给 {@code argmax(v_i)} ⇒ <b>恒有 Σ分项 == used</b>。
 *
 * <p>
 * {@code free = limit − used} 是<b>派生量</b>，不参与守恒（它不属于"已用"）。为负即"已超"，
 * 界面显示"已超 +N"。
 */
public final class ContextBreakdown {

	/** 人设 + 工作区快照（组装点 1 的 SystemMessage）。 */
	public static final String SYSTEM = "system";

	/** 本轮披露的工具 schema。 */
	public static final String TOOLS = "tools";

	/** 历史 user/assistant 正文（不含 tool 消息、不含压缩摘要）。 */
	public static final String HISTORY = "history";

	/** {@code role:tool} 的 responseData（含已清理占位符）。 */
	public static final String TOOL_RESULTS = "toolResults";

	/** 压缩摘要消息（metadata {@code compacted=true}）。 */
	public static final String SUMMARY = "summary";

	/** 本轮用户输入（最后一条 UserMessage）。 */
	public static final String INPUT = "input";

	/** {@code limit − used}（派生，可为负）。 */
	public static final String FREE = "free";

	/** 参与"Σ == used"守恒的六项（<b>顺序即界面展示顺序</b>，不得按对象键序渲染）。 */
	public static final List<String> USED_KEYS = List.of(HISTORY, TOOL_RESULTS, SUMMARY, SYSTEM, TOOLS, INPUT);

	/** 全部七项（六项 + {@code free}）。 */
	public static final List<String> ALL_KEYS = List.of(HISTORY, TOOL_RESULTS, SUMMARY, SYSTEM, TOOLS, INPUT, FREE);

	private ContextBreakdown() {
	}

	/**
	 * 把类目估算按比例归一到真值 {@code used}（守恒规则的唯一实现）。
	 *
	 * <p>
	 * 差额补给 {@code argmax(v_i)}（并列取 {@link #USED_KEYS} 序最前的一支），而不是补给"最后一项"：
	 * 差额来自取整地板，量级恒小于项数，补给最大项对展示的扰动最小。
	 *
	 * <p>
	 * {@code Σv == 0} 时全部差额补给 {@code history}：它是唯一"恒存在"的类目，比补给 {@code system}
	 * （首轮之后可能被重建得极小）更不误导。
	 *
	 * @param values 六项估算（缺失键按 0 处理；未列出的键被忽略 —— 不接受 {@code free} 混进来）
	 * @param used   真值（&lt;0 按 0）
	 * @return 六项归一后的新 Map（{@link #USED_KEYS} 序，值总和恰等于 {@code used}）
	 */
	public static Map<String, Integer> normalizeTo(Map<String, Integer> values, int used) {
		int target = Math.max(0, used);
		Map<String, Integer> out = new LinkedHashMap<>();
		long sum = 0L;
		for (String key : USED_KEYS) {
			int value = values == null || values.get(key) == null ? 0 : Math.max(0, values.get(key));
			out.put(key, value);
			sum += value;
		}
		if (sum == 0L) {
			out.put(HISTORY, target);
			return out;
		}
		long scaledSum = 0L;
		int argmax = 0;
		int maxValue = -1;
		int index = 0;
		for (String key : USED_KEYS) {
			int value = out.get(key);
			long scaled = (long) value * target / sum;
			out.put(key, (int) scaled);
			scaledSum += scaled;
			if (value > maxValue) {
				maxValue = value;
				argmax = index;
			}
			index++;
		}
		int remainder = (int) (target - scaledSum);
		String argmaxKey = USED_KEYS.get(argmax);
		out.put(argmaxKey, out.get(argmaxKey) + remainder);
		return out;
	}

	/**
	 * 按消息种类分项估算（六项，未归一）。
	 *
	 * <p>
	 * 归类规则（C-12 的口径）：{@code toolResults} 只吃 {@code ToolResponseMessage}、
	 * {@code input} 只吃<b>最后一条</b> {@code UserMessage}、{@code summary} 只吃 metadata
	 * {@code compacted=true} 的消息、其余 user/assistant 正文进 {@code history}；
	 * {@code system} 与 {@code tools} 由装配点直接给出（它们不在历史里）。
	 *
	 * @param messages     记忆窗口 / 重建后的历史
	 * @param systemTokens 人设 + 工作区快照的估算
	 * @param toolsTokens  本轮披露 schema 的估算
	 */
	public static Map<String, Integer> estimateCategories(List<Message> messages, int systemTokens, int toolsTokens) {
		Map<String, Integer> out = new LinkedHashMap<>();
		for (String key : USED_KEYS) {
			out.put(key, 0);
		}
		out.put(SYSTEM, Math.max(0, systemTokens));
		out.put(TOOLS, Math.max(0, toolsTokens));
		if (messages == null || messages.isEmpty()) {
			return out;
		}
		int lastUserIndex = -1;
		for (int i = 0; i < messages.size(); i++) {
			Message message = messages.get(i);
			if (message != null && message.getMessageType() == MessageType.USER) {
				lastUserIndex = i;
			}
		}
		for (int i = 0; i < messages.size(); i++) {
			Message message = messages.get(i);
			if (message == null || message.getMessageType() == MessageType.SYSTEM) {
				continue;
			}
			String key;
			if (message.getMessageType() == MessageType.TOOL) {
				key = TOOL_RESULTS;
			}
			else if (isCompacted(message)) {
				key = SUMMARY;
			}
			else if (i == lastUserIndex && message instanceof UserMessage) {
				key = INPUT;
			}
			else {
				key = HISTORY;
			}
			out.put(key, out.get(key) + ContextEstimator.estimateMessage(message));
		}
		return out;
	}

	/** 是否压缩摘要消息（metadata {@code compacted=true}）。 */
	public static boolean isCompacted(Message message) {
		if (message == null || message.getMetadata() == null) {
			return false;
		}
		Object flag = message.getMetadata().get(ContextEstimator.COMPACTED_KEY);
		return Boolean.TRUE.equals(flag) || "true".equalsIgnoreCase(String.valueOf(flag));
	}

	/**
	 * 端点的 {@code breakdown} 数组（七项，顺序固定 = {@link #ALL_KEYS}）。
	 *
	 * @param values     六项估算
	 * @param usedTokens 真值（分项按它归一）
	 * @param limitTokens 上限（{@code <=0} = 未知 ⇒ 不产 {@code free} 项：无从计算剩余）
	 */
	public static List<Map<String, Object>> parts(Map<String, Integer> values, int usedTokens, int limitTokens) {
		Map<String, Integer> normalized = normalizeTo(values, usedTokens);
		List<Map<String, Object>> out = new ArrayList<>(ALL_KEYS.size());
		for (Map.Entry<String, Integer> entry : normalized.entrySet()) {
			out.add(part(entry.getKey(), entry.getValue()));
		}
		if (limitTokens > 0) {
			out.add(part(FREE, limitTokens - usedTokens));
		}
		return out;
	}

	/** 分项的总和（守恒断言与诊断用）。 */
	public static int total(Map<String, Integer> values) {
		int sum = 0;
		for (String key : USED_KEYS) {
			Integer value = values == null ? null : values.get(key);
			if (value != null) {
				sum += value;
			}
		}
		return sum;
	}

	private static Map<String, Object> part(String key, int tokens) {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("key", key);
		item.put("tokens", tokens);
		return item;
	}

}
