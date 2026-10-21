package com.example.configmgr.ai.context;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * L1 工具结果清除（上下文统计与压缩专项批①，压缩谱系最轻一级；设计稿 §3.5）。
 *
 * <h2>为什么最轻一级先做</h2>
 * 工具结果通常占上下文的最大一块（一次 {@code list_*} 就能吃掉 8000 字符），而它对"下一步做什么"
 * 的价值随轮次快速衰减 —— 清掉它省的是最大的一块，改写的却是最小的一段（不动前缀、不动配对骨架）。
 *
 * <h2>四条硬约束</h2>
 * <ol>
 * <li><b>不删消息、不改 id</b>：只把 {@code responseData} 换成占位符。消息条数不变 ⇒ 构造上不可能
 * 出现"assistant(tool_calls) 没有对应 tool 响应"的孤儿对（上游 400 的经典成因）；</li>
 * <li><b>不动 {@code assistant(tool_calls)}</b>：配对骨架原样保留；</li>
 * <li><b>最近 {@code keep-tool-pairs} 对不清理</b>（默认 5）：模型当下最可能要复用的就是这几对；</li>
 * <li><b>未决 HITL 条目的工具结果不清理</b>：挂起期间用户还要在卡片上决策，清掉结果会让"批的是
 * 什么"无从复核（P3 的可执行投影）。</li>
 * </ol>
 *
 * <h2>原文不丢</h2>
 * 占位符给出 {@code toolCallId} 与台账键（{@code ai:ledger:<runId>} 本就是全文，见
 * {@code RunStore#appendLedger}）。占位符<b>刻意不复用</b>
 * {@code ToolResultLimiter.TRUNCATION_MARKER}：那个标记的语义是"上游结果过长被截断"，
 * 复用会让 {@code ToolResultLimiter#truncated} 把"已清理"误判成"被截断"。
 */
public final class ToolResultClearing {

	/** 占位符前缀（幂等判据：已清除的一条不再重复清除/重复计费）。 */
	public static final String PLACEHOLDER_PREFIX = "[tool_result 已清理：";

	/**
	 * 一对被清除的工具结果。
	 *
	 * @param toolCallId 调用 id（回取原文的键）
	 * @param name       工具名（人读）
	 * @param tokens     被清掉的原文本估算
	 */
	public record Cleared(String toolCallId, String name, int tokens) {
	}

	/**
	 * 清除方案。
	 *
	 * @param messages    清后的消息列表（{@link #changed()} 为 false 时与原列表同引用）
	 * @param cleared     被清除的条目（按"最旧优先"）
	 * @param savedTokens 省下的估算 token（原文本 − 占位符）
	 */
	public record Plan(List<Message> messages, List<Cleared> cleared, int savedTokens) {

		public boolean changed() {
			return !this.cleared.isEmpty();
		}

		public List<String> clearedIds() {
			return this.cleared.stream().map(Cleared::toolCallId).toList();
		}
	}

	private ToolResultClearing() {
	}

	/**
	 * 规划一次 L1 清除（<b>纯函数</b>：不写任何存储，调用方负责归档/写回）。
	 *
	 * @param messages              当前历史（原消息对象不被修改）
	 * @param keepToolPairs         最近多少对被 pin（{@code <=0} 表示不 pin）
	 * @param protectedToolCallIds  未决 HITL 条目的 toolCallId（永不清除；可为 null）
	 * @param ledgerRunId           台账键里的 runId（原文回取路径）
	 */
	public static Plan plan(List<Message> messages, int keepToolPairs, Set<String> protectedToolCallIds,
			String ledgerRunId) {
		if (messages == null || messages.isEmpty()) {
			return new Plan(messages, List.of(), 0);
		}
		Set<String> protectedIds = protectedToolCallIds == null ? Set.of() : protectedToolCallIds;
		List<Slot> slots = new ArrayList<>();
		for (int i = 0; i < messages.size(); i++) {
			Message message = messages.get(i);
			if (!(message instanceof ToolResponseMessage toolResponse)) {
				continue;
			}
			List<ToolResponseMessage.ToolResponse> responses = toolResponse.getResponses();
			for (int j = 0; j < responses.size(); j++) {
				ToolResponseMessage.ToolResponse response = responses.get(j);
				slots.add(new Slot(i, j, response.id(), response.name(), response.responseData()));
			}
		}
		if (slots.isEmpty()) {
			return new Plan(messages, List.of(), 0);
		}
		// pin 窗口 = 最后 keepToolPairs 条（按"工具结果条数"数对，与 keep-tool-pairs 的键名一致）
		int pinnedFrom = keepToolPairs <= 0 ? slots.size() : Math.max(0, slots.size() - keepToolPairs);
		List<Cleared> cleared = new ArrayList<>();
		Map<Integer, Map<Integer, String>> replacements = new HashMap<>();
		int savedTokens = 0;
		for (int index = 0; index < slots.size(); index++) {
			Slot slot = slots.get(index);
			if (index >= pinnedFrom) {
				continue;
			}
			if (slot.toolCallId() != null && protectedIds.contains(slot.toolCallId())) {
				continue;
			}
			if (isAlreadyCleared(slot.data())) {
				continue;
			}
			int tokens = ContextEstimator.estimate(slot.data());
			String placeholder = placeholder(slot.name(), tokens, slot.toolCallId(), ledgerRunId);
			replacements.computeIfAbsent(slot.messageIndex(), key -> new HashMap<>()).put(slot.responseIndex(),
					placeholder);
			cleared.add(new Cleared(slot.toolCallId(), slot.name(), tokens));
			savedTokens += Math.max(0, tokens - ContextEstimator.estimate(placeholder));
		}
		if (cleared.isEmpty()) {
			return new Plan(messages, List.of(), 0);
		}
		List<Message> out = new ArrayList<>(messages.size());
		for (int i = 0; i < messages.size(); i++) {
			Map<Integer, String> patch = replacements.get(i);
			if (patch == null) {
				out.add(messages.get(i));
				continue;
			}
			out.add(rebuild((ToolResponseMessage) messages.get(i), patch));
		}
		return new Plan(out, List.copyOf(cleared), savedTokens);
	}

	/** 是否已是占位符（幂等判据）。 */
	public static boolean isAlreadyCleared(String data) {
		return data != null && data.startsWith(PLACEHOLDER_PREFIX);
	}

	/** 占位符正文（格式即契约：C-16 断言工具名 + 估算 token + 台账键）。 */
	public static String placeholder(String name, int tokens, String toolCallId, String ledgerRunId) {
		return PLACEHOLDER_PREFIX + (name == null ? "(unknown)" : name) + " · ≈" + tokens + " tokens · toolCallId="
				+ (toolCallId == null ? "(none)" : toolCallId) + " · 原文见台账 ai:ledger:" + (ledgerRunId == null ? "(unknown)" : ledgerRunId)
				+ "]";
	}

	private static ToolResponseMessage rebuild(ToolResponseMessage original, Map<Integer, String> patch) {
		List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>(original.getResponses());
		for (Map.Entry<Integer, String> entry : patch.entrySet()) {
			ToolResponseMessage.ToolResponse existing = responses.get(entry.getKey());
			responses.set(entry.getKey(),
					new ToolResponseMessage.ToolResponse(existing.id(), existing.name(), entry.getValue()));
		}
		ToolResponseMessage.Builder builder = ToolResponseMessage.builder().responses(responses);
		if (original.getMetadata() != null && !original.getMetadata().isEmpty()) {
			builder.metadata(original.getMetadata());
		}
		return builder.build();
	}

	private record Slot(int messageIndex, int responseIndex, String toolCallId, String name, String data) {
	}

}
