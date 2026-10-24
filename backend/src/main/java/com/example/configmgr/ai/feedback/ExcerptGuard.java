package com.example.configmgr.ai.feedback;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 反馈摘录的<b>归档守卫</b>：{@code excerpt} 一旦入库（H2）就是永久事实、误伤不可逆，
 * 因此命中高置信凭据时整条替换为 {@code [已隐去]}（而不是逐处脱敏）。
 *
 * <h2>L-99 裁定下的存留理由</h2>
 * 输入/输出 DLP 管道（{@code InputDlp} / {@code OutputGuard} / {@code SafetyRules} 的流式
 * 掩码与阻断）已按用户裁定<b>整套删除</b> —— 项目没有敏感信息暴露面。本类不是那条管道的一部分：
 * 它只服务"反馈摘录永久入库"这一个写点（{@code AiController} 的 {@code POST /api/feedback}），
 * 该写点被用户明确要求保留（"不要删，只需指出其存在供裁决"）。
 *
 * <p>
 * 与已删除的 {@code SafetyRules} 的差别（刻意保留最小面，不再扩张）：
 * <ul>
 * <li>只有"命中/未命中"一个判据，<b>没有</b> {@code TAIL_WINDOW} / {@code holdFrom} 之类的
 * 流式尾部保留窗口；</li>
 * <li>不产出 {@code [REDACTED:<id>]} 掩码文本、不记规则 id、不写台账 —— 归档侧只需要
 * "这条能不能原样存"；</li>
 * <li>规则表是<b>冻结快照</b>：不随 DLP 演进，改动必须先改用户裁定。</li>
 * </ul>
 *
 * <p>
 * 规则字面量仍按 {@code scripts/githooks/scan-lib.sh} 的"扫描器不自报"纪律拼接构造。
 */
public final class ExcerptGuard {

	/** 私钥块最多匹配的正文长度（防一条假头吞掉整篇）。 */
	private static final int PRIVATE_KEY_BODY_MAX = 8192;

	private static final String P_SK = "s" + "k-";
	private static final String P_AKIA = "AK" + "IA";
	private static final String P_PEM_HEAD = "-----BEGIN ";
	private static final String P_PEM_FOOT = "-----END ";
	private static final String P_PEM_KEY = "PRIVATE KEY-----";
	private static final String P_BEARER = "B" + "earer";
	private static final String PEM_ALGO = "[A-Z0-9 ]{0,32}";

	private static final List<Pattern> PATTERNS = List.of(
			// 私钥块整段
			Pattern.compile(P_PEM_HEAD + PEM_ALGO + P_PEM_KEY + "[\\s\\S]{1," + PRIVATE_KEY_BODY_MAX + "}?"
					+ P_PEM_FOOT + PEM_ALGO + P_PEM_KEY),
			// 裸头（截断/未闭合的私钥同样是私钥本体）
			Pattern.compile(P_PEM_HEAD + PEM_ALGO + P_PEM_KEY),
			Pattern.compile("(?<![A-Za-z0-9])" + P_SK + "[A-Za-z0-9_\\-]{20,}"),
			Pattern.compile("(?<![A-Za-z0-9])" + P_AKIA + "[0-9A-Z]{16}(?![0-9A-Z])"),
			Pattern.compile("(?i)\\b" + P_BEARER + "\\s+[A-Za-z0-9\\-._~+/]{24,}=*"),
			// Oracle thin：jdbc:oracle:thin:user/pass@host
			Pattern.compile("(?i)\\bjdbc:[a-z0-9]+:([a-z0-9]+:)?[A-Za-z0-9_.\\-]{1,64}[:/][^\\s@/]{1,128}@"),
			Pattern.compile("(?i)\\bjdbc:[a-z0-9]+://[^\\s/:@]{1,64}:[^\\s/@]{1,128}@"),
			Pattern.compile("(?i)\\b(?:password|passwd|pwd)\\s*=\\s*(?![<{$\\[])[^\\s&;,\"']{8,}"),
			Pattern.compile("(?i)\\b[a-z][a-z0-9+.\\-]{1,20}://[^\\s/:@]{1,64}:[^\\s/@]{1,128}@"));

	private ExcerptGuard() {
	}

	/**
	 * 摘录里是否出现高置信凭据（{@code null}/空串 = 不敏感）。
	 *
	 * <p>裸私钥头也算：截断的私钥同样是私钥本体。
	 */
	public static boolean sensitive(String text) {
		if (text == null || text.isEmpty()) {
			return false;
		}
		for (Pattern pattern : PATTERNS) {
			if (pattern.matcher(text).find()) {
				return true;
			}
		}
		return false;
	}

}
