package com.example.configmgr.ai.context;

import java.util.List;
import java.util.Locale;

/**
 * 上游"上下文越窗"错误的识别（上下文统计与压缩专项批③；设计稿 §3.5 L3 末段）。
 *
 * <p>
 * L3 丢到 pin 下限后仍超，就<b>照发请求</b>，让上游如实报错；本类负责把那条报错从
 * "泛泛的上游流错误"里认出来，映射成 {@code CONTEXT_OVERFLOW}：
 * <ul>
 * <li>不重试 —— 同一份超大请求重发一次只会再超一次（浪费预算、拖长用户等待）；</li>
 * <li>不静默 —— 用户/运维看到的必须是"上下文超窗"，而不是含糊的 500。</li>
 * </ul>
 * 识别是<b>文本匹配</b>（各网关的措辞不统一，且拿不到统一错误码），故只收窄到明确的越窗措辞。
 */
public final class ContextOverflow {

	/**
	 * 越窗措辞（小写比较）。选取原则 = "只在这一种情况下出现"的短语：
	 * 广泛匹配 {@code length} / {@code token} 会把"token 无效/计费"之类的错误误判成越窗，
	 * 而那类错误重试/换路是有意义的。
	 */
	public static final List<String> MARKERS = List.of("context_length_exceeded", "maximum context length",
			"context window", "context length", "prompt is too long", "maximum number of tokens",
			"too many tokens", "reduce the length of the messages", "上下文长度", "输入过长", "超出上下文");

	private ContextOverflow() {
	}

	/** 是否像"上下文越窗"（任一文本命中即算）。 */
	public static boolean matches(String... texts) {
		if (texts == null) {
			return false;
		}
		for (String text : texts) {
			if (text == null || text.isEmpty()) {
				continue;
			}
			String lower = text.toLowerCase(Locale.ROOT);
			for (String marker : MARKERS) {
				if (lower.contains(marker)) {
					return true;
				}
			}
		}
		return false;
	}

}
