package com.example.configmgr.ai.context;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import com.example.configmgr.ai.memory.RunMemoryMerge;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 会话历史的 Turn 切分（上下文统计与压缩专项批③；设计稿 §3.5 L2"裁剪单位 = Turn"）。
 *
 * <p>
 * 口径与 Spring AI 的 {@code TurnWindow} 一致：<b>一条 {@code UserMessage} 起、到下一条
 * {@code UserMessage} 止</b>。裁剪单位是 Turn 而不是消息条数 —— 按条数裁会在
 * {@code assistant(tool_calls)} 与 {@code tool} 之间下刀，制造孤儿对（上游 400 的经典成因）。
 *
 * <p>
 * 第一条 {@code UserMessage} 之前若有残留（记忆窗口按条数裁过、重建历史从工具回环起），
 * 归入第 0 段：它们与后面的 Turn 同属"可压缩前缀"，整段处理也不会拆散配对。
 */
public final class ConversationTurns {

	private ConversationTurns() {
	}

	/** 一个 Turn 段（{@code [startIndex, endIndex)}，end 独占）。 */
	public record Segment(int startIndex, int endIndex) {

		public int size() {
			return this.endIndex - this.startIndex;
		}

	}

	/** 按"一条 {@code UserMessage} 起、到下一条 {@code UserMessage} 止"切分。 */
	public static List<Segment> split(List<Message> messages) {
		if (messages == null || messages.isEmpty()) {
			return List.of();
		}
		List<Segment> segments = new ArrayList<>();
		int start = 0;
		for (int i = 1; i < messages.size(); i++) {
			Message message = messages.get(i);
			if (message != null && message.getMessageType() == MessageType.USER) {
				segments.add(new Segment(start, i));
				start = i;
			}
		}
		segments.add(new Segment(start, messages.size()));
		return segments;
	}

	/**
	 * 第一个 pin 段的下标 —— 它之前的段（{@code [0, 返回值)}）是可压缩前缀。
	 *
	 * <p>
	 * pin 集由三部分取<b>最靠前</b>者决定：
	 * <ol>
	 * <li>最近 {@code keepRecentTurns} 轮（P4）；</li>
	 * <li>最后一段（P2：本轮输入永不动，即便 {@code keepRecentTurns = 0}）；</li>
	 * <li>含未决 HITL 条目的段（P3/P7：待审批的 Turn 与它的配对骨架逐字保留）。</li>
	 * </ol>
	 * 取"最靠前"而不是取并集后逐段跳过：摘要是置于窗口最前的一段合成消息，它只能替代
	 * <b>连续前缀</b>。中间夹一个受保护段时停在它之前 —— 少压一点，但摘要与实际被替代的内容
	 * 严格一致（不会出现"摘要说做了 A，A 的原文却还在"的自相矛盾）。
	 */
	public static int firstPinnedTurn(List<Message> messages, List<Segment> segments, int keepRecentTurns,
			Set<String> protectedToolCallIds) {
		return firstPinnedTurn(messages, segments, keepRecentTurns, protectedToolCallIds, Set.of());
	}

	public static int firstPinnedTurn(List<Message> messages, List<Segment> segments, int keepRecentTurns,
			Set<String> protectedToolCallIds, Set<String> protectedRunIds) {
		if (segments == null || segments.isEmpty()) {
			return 0;
		}
		int cut = Math.max(0, segments.size() - Math.max(0, keepRecentTurns));
		cut = Math.min(cut, segments.size() - 1);
		Set<String> activeRuns = protectedRunIds == null ? Set.of() : protectedRunIds;
		for (int i = 0; i < cut; i++) {
			if (containsRun(messages, segments.get(i), activeRuns)) {
				return i;
			}
		}
		Set<String> protectedIds = protectedToolCallIds == null ? Set.of() : protectedToolCallIds;
		if (protectedIds.isEmpty()) {
			return cut;
		}
		for (int i = 0; i < cut; i++) {
			if (containsProtected(messages, segments.get(i), protectedIds)) {
				return i;
			}
		}
		return cut;
	}

	public static boolean containsRun(List<Message> messages, Segment segment, Set<String> runIds) {
		for (int i = segment.startIndex(); i < segment.endIndex(); i++) {
			String runId = RunMemoryMerge.runIdOf(messages.get(i));
			if (runId != null && runIds.contains(runId)) {
				return true;
			}
		}
		return false;
	}

	/** 段内是否含受保护的 toolCallId（assistant 的 tool_calls 与 tool 响应两侧都算）。 */
	private static boolean containsProtected(List<Message> messages, Segment segment, Set<String> protectedIds) {
		for (int i = segment.startIndex(); i < segment.endIndex(); i++) {
			Message message = messages.get(i);
			if (message instanceof AssistantMessage assistant) {
				for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
					if (protectedIds.contains(call.id())) {
						return true;
					}
				}
			}
			if (message instanceof ToolResponseMessage toolResponse) {
				for (ToolResponseMessage.ToolResponse response : toolResponse.getResponses()) {
					if (protectedIds.contains(response.id())) {
						return true;
					}
				}
			}
		}
		return false;
	}

}
