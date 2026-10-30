package com.example.configmgr.ai.history;

import com.example.configmgr.ai.gate.SpToolCallingManager;
import org.springframework.ai.chat.messages.Message;

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
 * <h2>判据只保留一份（展示 + 槽位解析必须同源）</h2>
 * 本判据有两个消费面，二者<b>必须同源</b>，否则会出现"一处当合成、另一处当用户原话"的分叉：
 * <ol>
 * <li><b>展示（读侧）</b> {@code AiController#toHistoryItem}：给历史回显条目加 {@code synthetic=true}，
 *     前端据此按"系统消息"呈现（<b>摘要不冒充用户</b>，也不显示内部配置键）；</li>
 * <li><b>槽位解析（写侧输入面）</b> {@code TaskIntent#lastRealUserText} 与
 *     {@code TaskSlotResolver#enrich}：以"最后一个<b>真实</b> user"取用户原话，合成提示不得当成
 *     用户输入 —— 否则压缩摘要/步数收尾会把"最后一句话变成了系统提示"，让旧选择凭空复活。</li>
 * </ol>
 * 展示过滤本身不改变模型记忆里的既有结构（AR-01 明确保留这条边界）：消息照旧在窗口里，
 * 只是回显时打标、解析时跳过。
 */
public final class SyntheticMessages {

	/** RollingSummarizer 写在摘要消息 metadata 上的键。 */
	public static final String COMPACTED_FROM = "compactedFrom";

	/** RollingSummarizer 的摘要占位正文。 */
	public static final String SUMMARY_PLACEHOLDER = "[上下文压缩摘要]";

	/** SpToolCallingManager 的步数收尾 user 正文前缀。 */
	public static final String STEP_LIMIT_PREFIX = "已达到本轮工具调用步数上限";

	/**
	 * 导出收尾一轮纠正的内部指令前缀（Task 1）。它是系统级提示：不是用户原话、不是模型答复，
	 * 不得写进快照/记忆/历史气泡，也不得被 {@code TaskIntent.lastRealUserText} 采用。
	 */
	public static final String EXPORT_CORRECTION_PREFIX = "（内部纠正，不要向用户复述本段）";

	/**
	 * 历史回显通道的展示标记键（additive；{@code true} = 该条是合成/系统提示）。
	 *
	 * <p>前端契约：<b>带该键的条目按系统消息呈现</b>（不画成用户气泡、不当成助手答复），
	 * 正文仍原样透出（不做二次改写、不暴露内部配置键）。
	 */
	public static final String DISPLAY_KEY = "synthetic";

	private SyntheticMessages() {
	}

	/** 该消息是不是合成/系统提示（判据见类注；{@code null} 安全）。 */
	public static boolean isSynthetic(Message message) {
		if (message == null) {
			return false;
		}
		Map<String, Object> metadata = message.getMetadata();
		if (metadata != null && metadata.containsKey(COMPACTED_FROM)) {
			return true;
		}
		String text = message.getText();
		return text != null && (SUMMARY_PLACEHOLDER.equals(text) || text.startsWith(STEP_LIMIT_PREFIX)
				|| text.startsWith(SpToolCallingManager.TURN_BUDGET_WRAP_UP)
				|| text.startsWith(EXPORT_CORRECTION_PREFIX));
	}

	/**
	 * 去掉"导出收尾纠正"内部指令（写快照/记忆前的过滤口）：只过滤这一种合成消息，
	 * 压缩摘要/步数收尾等既有合成消息<b>照旧保留</b>（它们本来就是模型记忆的一部分）。
	 */
	public static java.util.List<Message> withoutCorrectionInstruction(java.util.List<Message> messages) {
		if (messages == null || messages.isEmpty()) {
			return java.util.List.of();
		}
		java.util.List<Message> out = new java.util.ArrayList<>(messages.size());
		for (Message message : messages) {
			String text = message == null ? null : message.getText();
			if (text == null || !text.startsWith(EXPORT_CORRECTION_PREFIX)) {
				out.add(message);
			}
		}
		return out;
	}
}
