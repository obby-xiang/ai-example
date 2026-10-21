package com.example.configmgr.ai.context;

import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * L3 硬截断兜底（上下文统计与压缩专项批③；设计稿 §3.5"按 Turn 丢最旧"）。
 *
 * <h2>与 L2 的分工</h2>
 * L2 用摘要"换掉"旧轮（信息还在，只是变短）；L3 是<b>最后一道</b>：摘要不可用或摘要后仍越 95% 线时，
 * 按 Turn 整轮丢弃最旧的非 pin 段。<b>丢完仍超就照发请求</b>，让上游如实报错（映射
 * {@code CONTEXT_OVERFLOW}）—— 绝不静默截断：静默截断会让"模型看到的"和"留下的"两套历史分叉。
 *
 * <h2>安全边界</h2>
 * <ul>
 * <li>单位是 Turn：切口永远落在 {@code UserMessage} 上，配对骨架不会被拆开；</li>
 * <li>pin 集（最近 {@code keep-recent-turns} 轮 + 最后一段 + 未决 HITL 所在轮）一刀不剪；</li>
 * <li>单次调用一次到位（丢到 pin 下限即停），不做循环重试 —— 循环在"pin 集本身就超限"时
 * 会空转成死循环。</li>
 * </ul>
 */
public final class HardTrim {

	/** 硬截断事件原因（帧/台账/日志同一字符串）。 */
	public static final String REASON_HARD_TRIM = "l3_hard_trim";

	/** 无可丢弃的 Turn（pin 集已覆盖全部）。 */
	public static final String REASON_NOTHING_TO_TRIM = "nothing_to_trim";

	/** 截断方案（{@code changed=false} ⇒ 消息同引用）。 */
	public record Plan(List<Message> messages, int droppedTurns, int lostTokens, String reason) {

		public boolean changed() {
			return this.droppedTurns > 0;
		}

	}

	private HardTrim() {
	}

	/**
	 * 规划一次 L3 截断（<b>纯函数</b>：不写任何存储）。
	 *
	 * @param messages             当前历史（原消息对象不被修改）
	 * @param keepRecentTurns      最近多少轮逐字保留（P4）
	 * @param protectedToolCallIds 未决 HITL 条目的 toolCallId（所在 Turn 一刀不剪；可为 null）
	 */
	public static Plan plan(List<Message> messages, int keepRecentTurns, Set<String> protectedToolCallIds) {
		if (messages == null || messages.isEmpty()) {
			return new Plan(messages, 0, 0, REASON_NOTHING_TO_TRIM);
		}
		List<ConversationTurns.Segment> segments = ConversationTurns.split(messages);
		int cut = ConversationTurns.firstPinnedTurn(messages, segments, keepRecentTurns, protectedToolCallIds);
		if (cut <= 0) {
			return new Plan(messages, 0, 0, REASON_NOTHING_TO_TRIM);
		}
		int cutIndex = segments.get(cut).startIndex();
		List<Message> dropped = messages.subList(0, cutIndex);
		List<Message> kept = new ArrayList<>(messages.subList(cutIndex, messages.size()));
		return new Plan(kept, cut, ContextEstimator.estimateMessages(dropped), REASON_HARD_TRIM);
	}

}
