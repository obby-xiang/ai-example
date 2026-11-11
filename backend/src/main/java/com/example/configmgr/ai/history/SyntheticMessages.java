package com.example.configmgr.ai.history;

import com.example.configmgr.ai.gate.SpToolCallingManager;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <b>synthetic（合成）消息的唯一判据</b>（AR-01 第 5 条 / 批次2 Task 4 Step 4）。
 *
 * <h2>什么算 synthetic</h2>
 * 这些消息在模型记忆里是真实消息（模型侧结构不改），但它们<b>不是</b>用户原话、也不是模型答复，
 * 而是系统级提示：
 * <ul>
 * <li>上下文压缩摘要（metadata {@code compactedFrom} 或占位正文 {@code [上下文压缩摘要]}）；</li>
 * <li>工具步数上限的收尾提示（正文以 {@code 已达到本轮工具调用步数上限} 开头）；</li>
 * <li>轮内上下文预算的收尾提示（{@link SpToolCallingManager#TURN_BUDGET_WRAP_UP}）。</li>
 * </ul>
 *
 * <h2>判据只保留一份（读侧标记 + 槽位解析必须同源）</h2>
 * 本判据有两个消费面，二者<b>必须同源</b>，否则会出现"一处当合成、另一处当用户原话"的分叉：
 * <ol>
 * <li><b>写侧过滤</b> {@code TranscriptEntries#fromRunTerminal}：合成提示<b>不写进 transcript</b>
 *     （不画用户气泡、也不画摘要/灰条；真实压缩节点只由已提交压缩事件恢复）；</li>
 * <li><b>槽位解析（写侧输入面）</b> {@code TaskIntent#lastRealUserText} 与
 *     {@code TaskSlotResolver#enrich}：以"最后一个<b>真实</b> user"取用户原话，合成提示不得当成
 *     用户输入 —— 否则压缩摘要/步数收尾会把"最后一句话变成了系统提示"，让旧选择凭空复活。</li>
 * </ol>
 * 标记本身不改变模型记忆里的既有结构（AR-01 明确保留这条边界）：消息照旧在窗口里，
 * 只是回显时打标、解析时跳过。
 */
public final class SyntheticMessages {

	/** RollingSummarizer 写在摘要消息 metadata 上的键。 */
	public static final String COMPACTED_FROM = "compactedFrom";

	/** 消息/转录条目的内部来源键，不进入公开历史载荷。 */
	public static final String ORIGIN = "origin";

	/** 上下文压缩摘要的内部来源值。 */
	public static final String ORIGIN_COMPACTION_SUMMARY = "COMPACTION_SUMMARY";

	/** RollingSummarizer 的摘要占位正文。 */
	public static final String SUMMARY_PLACEHOLDER = "[上下文压缩摘要]";

	/** SpToolCallingManager 的步数收尾 user 正文前缀。 */
	public static final String STEP_LIMIT_PREFIX = "已达到本轮工具调用步数上限";

	/**
	 * 导出收尾一轮纠正的内部指令前缀（Task 1）。它是系统级提示：不是用户原话、不是模型答复，
	 * 不得写进快照/记忆/历史气泡，也不得被 {@code TaskIntent.lastRealUserText} 采用。
	 */
	public static final String EXPORT_CORRECTION_PREFIX = "（内部纠正，不要向用户复述本段）";

	/** 合成提示的明确类别（additive 诊断；前端不据类别补造展示节点）。 */
	public enum Kind {

		/** 上下文压缩摘要（正文永不上屏）。 */
		COMPACTION("compaction"),

		/** 工具步数上限收尾提示。 */
		STEP_LIMIT("step_limit"),

		/** 轮内上下文预算收尾提示。 */
		TURN_BUDGET("turn_budget"),

		/** 导出/创建收尾的内部纠正指令。 */
		CORRECTION("correction");

		private final String wire;

		Kind(String wire) {
			this.wire = wire;
		}

		public String wire() {
			return this.wire;
		}

	}

	private SyntheticMessages() {
	}

	/** 只按明确内部来源识别压缩摘要，不读取正文、标题或固定话术。 */
	public static boolean isCompactionSummary(Message message) {
		Map<String, Object> metadata = message == null ? null : message.getMetadata();
		return metadata != null && ORIGIN_COMPACTION_SUMMARY.equals(metadata.get(ORIGIN));
	}

	/**
	 * 该消息的合成类别（{@code null} = 不是合成提示）。
	 *
	 * <p>判据只有这一份：{@link #isSynthetic(Message)} 由它派生，展示/槽位解析不得再各写一套前缀表。
	 * 压缩优先于其它前缀（摘要正文本身可能包含任意文本）。
	 */
	public static Kind kindOf(Message message) {
		if (message == null) {
			return null;
		}
		Map<String, Object> metadata = message.getMetadata();
		if (metadata != null && metadata.containsKey(COMPACTED_FROM)) {
			return Kind.COMPACTION;
		}
		String text = message.getText();
		if (text == null) {
			return null;
		}
		if (StringUtils.startsWith(text, SUMMARY_PLACEHOLDER)) {
			return Kind.COMPACTION;
		}
		if (text.startsWith(STEP_LIMIT_PREFIX)) {
			return Kind.STEP_LIMIT;
		}
		if (text.startsWith(SpToolCallingManager.TURN_BUDGET_WRAP_UP)) {
			return Kind.TURN_BUDGET;
		}
		if (text.startsWith(EXPORT_CORRECTION_PREFIX)) {
			return Kind.CORRECTION;
		}
		return null;
	}

	/** 该消息是不是合成/系统提示（判据见类注；{@code null} 安全）。 */
	public static boolean isSynthetic(Message message) {
		return kindOf(message) != null;
	}

	/**
	 * 去掉"导出收尾纠正"内部指令（写快照/记忆前的过滤口）：只过滤这一种合成消息，
	 * 压缩摘要/步数收尾等既有合成消息<b>照旧保留</b>（它们本来就是模型记忆的一部分）。
	 */
	public static List<Message> withoutCorrectionInstruction(List<Message> messages) {
		if (CollectionUtils.isEmpty(messages)) {
			return List.of();
		}
		List<Message> out = new ArrayList<>(messages.size());
		for (Message message : messages) {
			String text = message == null ? null : message.getText();
			if (!StringUtils.startsWith(text, EXPORT_CORRECTION_PREFIX)) {
				out.add(message);
			}
		}
		return out.isEmpty() ? List.of() : out;
	}
}
