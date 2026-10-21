package com.example.configmgr.ai.context;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * token 估算器（上下文统计与压缩专项批①；设计稿 §3.1 末段）。
 *
 * <p>
 * <b>只用于两件事</b>：① 上游全程不给 usage 时的回退值（前端标 {@code ≈}）；
 * ② 六项类目拆分的分项体量。它<b>不</b>替代真值：只要有一次 {@code usage}，展示与记账一律用真值
 * （"拆分是估算、总量是账单"，见 {@link ContextBreakdown#normalizeTo}）。
 *
 * <h2>口径</h2>
 * {@code tokens = ceil(cjkChars / 1.7 + otherChars / 4.0)}。取值依据 = 调研
 * 《调研-上下文统计与压缩业界惯例-2026-10-21》§②：中文按字符/1.6~1.8 兜底，取区间中值 1.7；
 * 拉丁系按 4 字符/token（GPT 系分词器的通行经验值，对中文会低估，故中文单列）。
 *
 * <p>
 * 刻意<b>不</b>引入分词器依赖：估算是"防越窗"的粗判断与展示分项，差一两个 token 不影响任何判定
 * （阈值都留了 13K 预留缓冲）；引入 BPE 词表会让打包体积与启动开销多一份无收益的负担。
 */
public final class ContextEstimator {

	/** 中文/日文/韩文字符折算 token 的除数（字符每 token）。 */
	public static final double CJK_CHARS_PER_TOKEN = 1.7;

	/** 其余字符折算 token 的除数（字符每 token）。 */
	public static final double OTHER_CHARS_PER_TOKEN = 4.0;

	/** 压缩摘要消息的 metadata 标记（与 {@code ContextBreakdown} 的 {@code summary} 类目同源）。 */
	public static final String COMPACTED_KEY = "compacted";

	private ContextEstimator() {
	}

	/** 单段文本的 token 估算（null/空串 = 0）。 */
	public static int estimate(String text) {
		if (text == null || text.isEmpty()) {
			return 0;
		}
		int cjk = 0;
		int other = 0;
		for (int i = 0; i < text.length(); i++) {
			char ch = text.charAt(i);
			if (isCjk(ch)) {
				cjk++;
			}
			else {
				other++;
			}
		}
		return (int) Math.ceil(cjk / CJK_CHARS_PER_TOKEN + other / OTHER_CHARS_PER_TOKEN);
	}

	/**
	 * 一整条消息进请求体的体量：正文 + 工具调用入参（assistant）/ 工具结果（tool）。
	 *
	 * <p>
	 * 角色的"外壳"（{@code role} 键、分隔符）刻意不计：它每条消息恒定几个 token，
	 * 计入只会让分项看起来更"准"而总量并不会更准。
	 */
	public static int estimateMessage(Message message) {
		if (message == null) {
			return 0;
		}
		int tokens = estimate(message.getText());
		if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
			for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
				tokens += estimate(call.name()) + estimate(call.arguments());
			}
		}
		if (message instanceof ToolResponseMessage toolResponse) {
			for (ToolResponseMessage.ToolResponse response : toolResponse.getResponses()) {
				tokens += estimate(response.name()) + estimate(response.responseData());
			}
		}
		return tokens;
	}

	/** 一串消息的估算（跳过 {@code SYSTEM}：它在装配点单列，避免与 system 类目重复计）。 */
	public static int estimateMessages(List<Message> messages) {
		if (messages == null || messages.isEmpty()) {
			return 0;
		}
		int tokens = 0;
		for (Message message : messages) {
			if (message == null || message.getMessageType() == MessageType.SYSTEM) {
				continue;
			}
			tokens += estimateMessage(message);
		}
		return tokens;
	}

	/**
	 * 本轮披露的工具 schema 体量（{@code tools} 类目）。
	 *
	 * <p>本仓工具走<b>渐进披露</b>（{@code ToolRegistry#forContext}），schema 常驻请求前缀，
	 * 7–18 个工具的 JSON 可以轻松吃掉几千 token —— 不单列这一项，"system 巨大"的结论会误导排障。
	 */
	public static int estimateToolSchemas(List<ToolCallback> callbacks) {
		if (callbacks == null || callbacks.isEmpty()) {
			return 0;
		}
		int tokens = 0;
		for (ToolCallback callback : callbacks) {
			if (callback == null || callback.getToolDefinition() == null) {
				continue;
			}
			tokens += estimate(callback.getToolDefinition().name())
					+ estimate(callback.getToolDefinition().description())
					+ estimate(callback.getToolDefinition().inputSchema());
		}
		return tokens;
	}

	/**
	 * 是否按"中文/日文/韩文"口径折算。
	 *
	 * <p>覆盖 CJK 标点、假名、谚文、统一汉字与全角形式 —— 这些字符的 UTF-8 是三字节，
	 * 各家分词器通常 1 字 ≈ 1 token，按 4 字符/token 会低估约 4 倍。
	 */
	private static boolean isCjk(char ch) {
		return (ch >= 0x2E80 && ch <= 0x9FFF)     // CJK 部首/假名/统一汉字
				|| (ch >= 0xAC00 && ch <= 0xD7AF)  // 谚文音节
				|| (ch >= 0xF900 && ch <= 0xFAFF)  // 兼容汉字
				|| (ch >= 0xFE30 && ch <= 0xFE4F)  // 兼容形式
				|| (ch >= 0xFF00 && ch <= 0xFFEF); // 全角形式
	}

}
