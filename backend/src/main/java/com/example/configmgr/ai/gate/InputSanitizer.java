package com.example.configmgr.ai.gate;

import org.springframework.stereotype.Component;

import java.text.Normalizer;

/**
 * 用户输入归一化（安全防护专项批① §5.1 #2；{@code 调研-AI输入输出安全防护业界惯例} §4.5）。
 *
 * <h2>做什么</h2>
 * <ol>
 * <li><b>NFKC</b>：全角/兼容字符折叠到规范形态（{@code ＡＢＣ} → {@code ABC}、{@code ①} → {@code 1}、
 * 连字 {@code ﬁ} → {@code fi}）—— 同形字与"编码层绕过"的第一道平整；</li>
 * <li><b>剥离不可见字符</b>：Unicode 类别 {@code Cf}（零宽 U+200B/200C/200D、BOM U+FEFF、
 * 双向控制符 U+202A–202E、软连字符 U+00AD …）与<b>孤立代理项</b>（{@code Cs}）—— 它们肉眼不可见，
 * 却能切开关键词（{@code 忽\u200B略} 与 {@code 忽略} 在模型侧可能被当成两回事）；</li>
 * <li><b>剥离非法控制字符</b>：类别 {@code Cc}，<b>保留</b> {@code \n} 与 {@code \t}（换行/制表是正常
 * 输入的一部分），{@code \r} 一并剥离（CRLF 归一成 LF）。</li>
 * </ol>
 *
 * <h2>只归一，不拦截</h2>
 * 本类<b>不</b>判断"是不是注入"，也不改写语义：它把"同一句话的多种编码形态"收敛到一种，
 * 让下游（长度闸门、模型、审计）看到的是同一份文本。归一后内容变化只由调用方记一条 INFO
 * （长度差，不落原文 —— 原文可能是敏感数据）。
 *
 * <h2>幂等</h2>
 * 归一 = 一个不动点：{@code sanitize(sanitize(x)) == sanitize(x)}。实现为此做了收敛循环
 * （剥字符可能让原本被隔开的基字符与组合符相邻，从而产生新的可组合序列，需要再归一一次）。
 */
@Component
public class InputSanitizer {

	/** 收敛循环上限：剥字符 + NFKC 的实际收敛轮数是 1–2，3 只作防死循环的上界。 */
	private static final int MAX_PASSES = 3;

	/**
	 * 归一结果：{@code text} 是唯一下游可见的产物，其余字段只用于日志与观测（不含原文）。
	 *
	 * @param rewritten {@code text} 是否与原文不同（剥字符、NFKC 替换任一即 true ——
	 * 注意 NFKC 可能是<b>等长</b>替换，故不能只看长度）
	 */
	public record Result(String text, int originalLength, int removedFormat, int removedControl, boolean rewritten) {

		/** 内容是否被改动。 */
		public boolean changed() {
			return this.rewritten;
		}

		/** 归一前后的字符数差（负数 = 变短）。 */
		public int lengthDelta() {
			return this.text.length() - this.originalLength;
		}

	}

	/**
	 * 归一 {@code raw}（{@code null} 按空串处理；空串原样返回，不建对象）。
	 */
	public Result sanitize(String raw) {
		if (raw == null) {
			return new Result("", 0, 0, 0, false);
		}
		int removedFormat = 0;
		int removedControl = 0;
		String current = raw;
		for (int pass = 0; pass < MAX_PASSES; pass++) {
			int[] counts = new int[2];
			String stripped = stripInvisible(current, counts);
			removedFormat += counts[0];
			removedControl += counts[1];
			String normalized = Normalizer.normalize(stripped, Normalizer.Form.NFKC);
			boolean stable = normalized.equals(current);
			current = normalized;
			if (stable) {
				break;
			}
		}
		return new Result(current, raw.length(), removedFormat, removedControl, !current.equals(raw));
	}

	/**
	 * 单趟剥离：按<b>码点</b>遍历（{@code Character.getType(int)}），故合法的增补平面字符
	 * （emoji 等代理对）原样保留，只有<b>孤立</b>代理项会被当作垃圾剥掉。
	 *
	 * @param counts 出参：{@code [0]} = 剥离的格式/孤立代理字符数，{@code [1]} = 剥离的控制字符数
	 */
	private static String stripInvisible(String text, int[] counts) {
		StringBuilder out = new StringBuilder(text.length());
		for (int i = 0; i < text.length();) {
			int codePoint = text.codePointAt(i);
			int type = Character.getType(codePoint);
			boolean format = type == Character.FORMAT || type == Character.SURROGATE;
			boolean control = type == Character.CONTROL && codePoint != '\n' && codePoint != '\t';
			if (format) {
				counts[0]++;
			}
			else if (control) {
				counts[1]++;
			}
			else {
				out.appendCodePoint(codePoint);
			}
			i += Character.charCount(codePoint);
		}
		return out.toString();
	}

}
