package com.example.configmgr.ai.context;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * L2 按 Turn 滑窗 + 递归摘要（上下文统计与压缩专项批③；设计稿 §3.5）。
 *
 * <h2>为什么是"摘要"而不是"再清一点"</h2>
 * L1 只清工具结果，用户与助手的正文还在；长会话里正文本身就是大头。摘要用<b>一段短文本替代
 * 一串旧 Turn</b>：信息还在（六项保留清单里），体积却按数量级下降。代价是每次压缩都改写前缀、
 * 缓存必然 miss，所以它由"每次最多升一级 + 滞回 60%"约束，不做每轮小裁剪。
 *
 * <h2>递归</h2>
 * 旧前缀按 {@code chunk-tokens} 分块（每块一次请求，保证摘要请求自身不越窗），分块摘要再按同一
 * 预算递归合并，直到只剩一段。<b>不截断摘要正文</b>：超预算时继续合并，合并到单块仍超就接受
 * （摘要已比原文小一个量级，再截就成了静默截断）。
 *
 * <h2>失败即原样</h2>
 * 摘要请求异常/超时/产物空白 ⇒ {@code changed=false} + {@code reason=summary_failed}，历史
 * <b>逐字不变</b>。刻意不 catch 成"退回硬截断"：那会让同一份历史在两个实例/两个时刻走出不同分支，
 * 而且用户看到的是"压缩成功"。
 */
public final class RollingSummarizer {

	/** 摘要消息的正文占位（合成 {@code UserMessage} 的 text）。 */
	public static final String SUMMARY_PLACEHOLDER = "[上下文压缩摘要]";

	/** 摘要事件原因（帧/台账/日志同一字符串）。 */
	public static final String REASON_SUMMARY = "l2_turn_window_summary";

	/** 摘要失败（异常/超时/产物不可用）；调用方据此计数熔断，且历史逐字不变。 */
	public static final String REASON_SUMMARY_FAILED = "summary_failed";

	/** 没有可摘要的旧轮（全在 keep 窗口 / 受保护）。 */
	public static final String REASON_NOTHING_TO_SUMMARIZE = "nothing_to_summarize";

	/** 分块正文标记（摘要请求体 = 指令 + 本标记 + transcript）。 */
	public static final String BODY_MARKER = "=== TRANSCRIPT ===";

	/** 递归合并正文标记（请求体 = 合并指令 + 本标记 + 分段摘要）。 */
	public static final String MERGE_MARKER = "=== PARTIAL SUMMARIES ===";

	/**
	 * 摘要必须原文携带的六项保留清单（P6；顺序即段落顺序）。
	 *
	 * <p>
	 * 它是"压缩不丢关键信息"的<b>可执行定义</b>：只写"请摘要"的提示词会让模型自由发挥，
	 * 待审批项与资源 ID 是最容易被丢的两类 —— 而恰恰是后续轮次最需要的。
	 */
	public static final List<String> REQUIRED_SECTIONS = List.of("1) USER GOAL",
			"2) CONFIRMED WORKSPACE AND PAGE OPERATIONS",
			"3) EXECUTED IRREVERSIBLE ACTIONS AND RESOURCE IDS",
			"4) UNRESOLVED ERRORS AND NEXT STEPS",
			"5) PENDING APPROVALS (toolCallId + tool name + argument digest)",
			"6) COMPLETED - DO NOT REDO");

	/** 摘要消息 metadata 键：折叠掉的 Turn 数。 */
	public static final String META_TURNS = "compactedFrom";

	/** 摘要消息 metadata 键：丢掉的 token 估算。 */
	public static final String META_LOST = "lostTokens";

	/** 摘要消息 metadata 键：压缩时刻（服务端钟）。 */
	public static final String META_AT = "atMs";

	@FunctionalInterface
	public interface Summarizer {

		String summarize(String prompt) throws Exception;

	}

	/** 摘要方案（{@code changed=false} ⇒ 消息同引用）。 */
	public record Plan(List<Message> messages, int summarizedTurns, int lostTokens, int summaryChars, int requests,
			String summary, String reason) {

		public boolean changed() {
			return this.summarizedTurns > 0;
		}

	}

	private RollingSummarizer() {
	}

	/**
	 * 规划一次 L2 压缩（<b>纯函数</b>：不写任何存储；摘要请求经 {@link Summarizer} 注入，便于单测）。
	 *
	 * @param messages             当前历史（原消息对象不被修改）
	 * @param keepRecentTurns      最近多少轮逐字保留（P4）
	 * @param chunkTokens          单次摘要请求的正文预算
	 * @param protectedToolCallIds 未决 HITL 条目的 toolCallId（所在 Turn 不参与摘要；可为 null）
	 * @param summarizer           摘要模型（null ⇒ 失败路径）
	 * @param nowMs                压缩时刻（写入摘要消息 metadata）
	 */
	public static Plan plan(List<Message> messages, int keepRecentTurns, int chunkTokens,
			Set<String> protectedToolCallIds, Summarizer summarizer, long nowMs) {
		if (messages == null || messages.isEmpty()) {
			return unchanged(messages, 0, REASON_NOTHING_TO_SUMMARIZE);
		}
		if (summarizer == null) {
			return unchanged(messages, 0, REASON_SUMMARY_FAILED);
		}
		List<ConversationTurns.Segment> segments = ConversationTurns.split(messages);
		int cut = ConversationTurns.firstPinnedTurn(messages, segments, keepRecentTurns, protectedToolCallIds);
		if (cut <= 0) {
			return unchanged(messages, 0, REASON_NOTHING_TO_SUMMARIZE);
		}
		int cutIndex = segments.get(cut).startIndex();
		List<Message> prefix = messages.subList(0, cutIndex);
		int chunk = Math.max(1, chunkTokens);
		int requests = 0;
		String summary;
		try {
			List<String> blocks = new ArrayList<>(prefix.size());
			for (Message message : prefix) {
				String rendered = render(message);
				if (!rendered.isBlank()) {
					blocks.add(rendered);
				}
			}
			List<String> partials = new ArrayList<>();
			for (List<String> group : group(blocks, chunk)) {
				partials.add(summarizer.summarize(chunkPrompt(group, chunk)));
				requests++;
			}
			while (partials.size() > 1) {
				List<List<String>> mergeGroups = group(partials, chunk);
				List<String> merged = new ArrayList<>(mergeGroups.size());
				for (List<String> group : mergeGroups) {
					merged.add(summarizer.summarize(mergePrompt(group, chunk)));
					requests++;
				}
				boolean shrank = merged.size() < partials.size();
				partials = merged;
				if (!shrank) {
					// 每段摘要自身就超过 chunk 预算：再合并也不会变小，停在这里（不截断）
					break;
				}
			}
			summary = partials.isEmpty() ? "" : partials.get(0);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return unchanged(messages, requests, REASON_SUMMARY_FAILED);
		}
		catch (Exception ex) {
			return unchanged(messages, requests, REASON_SUMMARY_FAILED);
		}
		if (summary == null || summary.isBlank()) {
			return unchanged(messages, requests, REASON_SUMMARY_FAILED);
		}
		summary = summary.trim();
		int prefixTokens = ContextEstimator.estimateMessages(prefix);
		int pairTokens = ContextEstimator.estimate(SUMMARY_PLACEHOLDER) + ContextEstimator.estimate(summary);
		int lost = Math.max(0, prefixTokens - pairTokens);
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put(ContextEstimator.COMPACTED_KEY, true);
		metadata.put(META_TURNS, cut);
		metadata.put(META_LOST, lost);
		metadata.put(META_AT, nowMs);
		UserMessage placeholder = UserMessage.builder().text(SUMMARY_PLACEHOLDER).metadata(metadata).build();
		AssistantMessage digest = AssistantMessage.builder().content(summary).properties(metadata).build();
		List<Message> out = new ArrayList<>(2 + messages.size() - cutIndex);
		out.add(placeholder);
		out.add(digest);
		out.addAll(messages.subList(cutIndex, messages.size()));
		return new Plan(List.copyOf(out), cut, lost, summary.length(), requests, summary, REASON_SUMMARY);
	}

	private static Plan unchanged(List<Message> messages, int requests, String reason) {
		return new Plan(messages, 0, 0, 0, requests, null, reason);
	}

	/** 贪心分块：每块的估算不超过预算；单条超预算的消息自成一块（不切正文）。 */
	private static List<List<String>> group(List<String> items, int chunkTokens) {
		List<List<String>> groups = new ArrayList<>();
		List<String> current = new ArrayList<>();
		int tokens = 0;
		for (String item : items) {
			int itemTokens = ContextEstimator.estimate(item);
			if (!current.isEmpty() && tokens + itemTokens > chunkTokens) {
				groups.add(current);
				current = new ArrayList<>();
				tokens = 0;
			}
			current.add(item);
			tokens += itemTokens;
		}
		if (!current.isEmpty()) {
			groups.add(current);
		}
		return groups;
	}

	private static String chunkPrompt(List<String> blocks, int chunkTokens) {
		StringBuilder prompt = new StringBuilder();
		prompt.append("You summarize an AI assistant conversation so a later turn can continue without the full transcript.\n")
			.append("Follow the user's language (use the language of the conversation). ")
			.append("Do not invent facts; keep ids, file names and numbers verbatim.\n")
			.append("Aim for at most ")
			.append(chunkTokens)
			.append(" tokens.\n")
			.append("Keep exactly these six sections, in this order; write \"none\" for an empty section:\n");
		for (String section : REQUIRED_SECTIONS) {
			prompt.append("- ").append(section).append('\n');
		}
		prompt.append(BODY_MARKER).append('\n').append(String.join("\n", blocks));
		return prompt.toString();
	}

	private static String mergePrompt(List<String> partials, int chunkTokens) {
		StringBuilder prompt = new StringBuilder();
		prompt.append("You merge partial summaries of one AI assistant conversation into a single summary.\n")
			.append("Follow the user's language (use the language of the conversation). ")
			.append("Do not invent facts; keep ids, file names and numbers verbatim.\n")
			.append("Aim for at most ")
			.append(chunkTokens)
			.append(" tokens.\n")
			.append("Keep exactly these six sections, in this order; write \"none\" for an empty section:\n");
		for (String section : REQUIRED_SECTIONS) {
			prompt.append("- ").append(section).append('\n');
		}
		prompt.append(MERGE_MARKER).append('\n').append(String.join("\n\n", partials));
		return prompt.toString();
	}

	/** 一条消息的可读投影（摘要模型的输入形态；工具结果必须可见，资源 ID 常在里面）。 */
	private static String render(Message message) {
		if (message == null) {
			return "";
		}
		String text = message.getText() == null ? "" : message.getText();
		return switch (message.getMessageType()) {
			case USER -> "user: " + text;
			case ASSISTANT -> {
				StringBuilder line = new StringBuilder("assistant: ").append(text);
				if (message instanceof AssistantMessage assistant) {
					for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
						line.append("\nassistant.tool_call id=")
							.append(call.id())
							.append(" name=")
							.append(call.name())
							.append(" args=")
							.append(call.arguments());
					}
				}
				yield line.toString();
			}
			case TOOL -> {
				StringBuilder line = new StringBuilder();
				if (message instanceof ToolResponseMessage toolResponse) {
					for (ToolResponseMessage.ToolResponse response : toolResponse.getResponses()) {
						line.append("tool ")
							.append(response.name())
							.append(" id=")
							.append(response.id())
							.append(": ")
							.append(response.responseData())
							.append('\n');
					}
				}
				yield line.toString();
			}
			default -> "system: " + text;
		};
	}

}
