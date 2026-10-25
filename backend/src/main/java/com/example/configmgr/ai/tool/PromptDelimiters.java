package com.example.configmgr.ai.tool;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提示词定界符的单一实现（防护口径：定界只是概率层，真正的边界仍在工具权限与确认门）。
 *
 * <p>
 * 三个数据段此前各自维护一份「中和」正则：{@code ContextBuilder} 的
 * {@code <workspace_state>}、{@link ToolResultEnvelope} 的 {@code <tool_result>}、
 * {@code KbText} 的 {@code <knowledge>}。同一件事写三遍 ⇒ 空白面、大小写、标签名
 * 三处口径迟早漂移，而任何一处漏掉都等于留了一个「提前收口、把后续文本顶到定界之外」的写入口。
 * 本类把<b>标签识别</b>收敛成一份：调用方只声明自己拥有哪些标签，以及要哪种中和形态。
 *
 * <h2>三种中和形态</h2>
 * <ul>
 * <li>{@link #neutralizeClosing}：规范形 {@code ＜/name＞}（标签名大小写保留、标签内空白折叠）——
 * {@code ContextBuilder} 的既有口径；</li>
 * <li>{@link #neutralizeTagEdges}：<b>等长</b>中和，只把两端的 {@code <}/{@code >} 换成全角 ——
 * 结果信封用（截断契约的 {@code originalChars}/{@code returnedChars} 必须与模型侧 message 长度
 * 逐值相等，中和不能顺手吃掉字符）；</li>
 * <li>{@link #neutralizeTag}：固定字面量替换 —— {@code KbText} 的 {@code ＜knowledge＞} 口径。</li>
 * </ul>
 *
 * <p>
 * 三种形态都<b>幂等</b>：中和后的全角尖括号不会再匹配任何标签正则，重复施加不改变结果。
 * 中和只作用于<b>字符串值</b>（调用方在序列化之前施加），不在已序列化的 JSON 文本上做二次转义。
 */
public final class PromptDelimiters {

	/** 标签名内外的空白面：{@code \s} 只覆盖 ASCII，NBSP / {@code U+2007} / {@code U+3000} 等在 {@code \p{Z}}。 */
	private static final String GAP = "[\\s\\p{Z}]*";

	/** 全角左尖括号（模型侧不是标签）。 */
	public static final char FULLWIDTH_LT = '＜';

	/** 全角右尖括号。 */
	public static final char FULLWIDTH_GT = '＞';

	private PromptDelimiters() {
	}

	/**
	 * 闭标签正则：{@code < / name >}（大小写不敏感；{@code <} 与 {@code /}、标签名内外都容忍空白）。
	 *
	 * <p>捕获组 1 = 标签名（仅供 {@link #neutralizeClosing} 回填，调用方不必依赖）。
	 */
	public static Pattern closingTags(String... names) {
		return Pattern.compile("(?i)<" + GAP + "/" + GAP + "(" + alternatives(names) + ")" + GAP + ">");
	}

	/**
	 * 开/闭标签正则：{@code <name>} 与 {@code </name>} 都匹配（{@code KbText} 的 knowledge 口径）。
	 *
	 * <p>捕获组 1 = 标签名。
	 */
	public static Pattern anyTag(String... names) {
		return Pattern.compile("(?i)<" + GAP + "/?" + GAP + "(" + alternatives(names) + ")" + GAP + ">");
	}

	/**
	 * 规范形中和：每个闭标签换成 {@code ＜/name＞}（保留标签名原大小写、标签内空白折叠）。
	 *
	 * <p>与 {@code ContextBuilder#neutralizeClosingDelimiters} 的既有输出逐字一致。
	 */
	public static String neutralizeClosing(String text, Pattern closingTags) {
		if (text == null || text.isEmpty()) {
			return "";
		}
		return closingTags.matcher(text).replaceAll(FULLWIDTH_LT + "/$1" + FULLWIDTH_GT);
	}

	/**
	 * 等长中和：只把匹配到的标签两端 {@code <}/{@code >} 换成全角，标签内部一字不动。
	 *
	 * <p>长度不变是刻意的：结果信封的截断字段（{@code originalChars}/{@code returnedChars}）
	 * 是按中和<b>前</b>的文本判定的，中和若吃掉字符，模型侧 message 长度就会与契约字段对不上。
	 */
	public static String neutralizeTagEdges(String text, Pattern tags) {
		if (text == null || text.isEmpty()) {
			return "";
		}
		Matcher matcher = tags.matcher(text);
		StringBuilder out = null;
		int last = 0;
		while (matcher.find()) {
			if (out == null) {
				out = new StringBuilder(text.length());
			}
			out.append(text, last, matcher.start());
			String tag = matcher.group();
			out.append(FULLWIDTH_LT).append(tag, 1, tag.length() - 1).append(FULLWIDTH_GT);
			last = matcher.end();
		}
		if (out == null) {
			return text;
		}
		return out.append(text, last, text.length()).toString();
	}

	/** 固定字面量替换（{@code KbText} 的 knowledge 口径：正反标签都替换成同一个全角形态）。 */
	public static String neutralizeTag(String text, Pattern tags, String replacement) {
		if (text == null || text.isEmpty()) {
			return "";
		}
		return tags.matcher(text).replaceAll(Matcher.quoteReplacement(replacement));
	}

	private static String alternatives(String... names) {
		if (names == null || names.length == 0) {
			throw new IllegalArgumentException("至少需要一个定界标签名");
		}
		StringBuilder sb = new StringBuilder();
		for (String name : names) {
			if (name == null || name.isBlank()) {
				throw new IllegalArgumentException("定界标签名不能为空");
			}
			if (sb.length() > 0) {
				sb.append('|');
			}
			sb.append(Pattern.quote(name));
		}
		return sb.toString();
	}

}
