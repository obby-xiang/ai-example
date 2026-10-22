package com.example.configmgr.ai.gate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 高置信敏感凭据模式的<b>单一事实源</b>（安全防护专项批②③ · B3-1/B3-2）。
 *
 * <h2>口径（用户裁决 2026-10-21 第 2 条）</h2>
 * <b>默认 mask 后继续</b>；只有"高置信 + 高危"的一档走 block：
 * <ol>
 * <li><b>mask</b>：命中即替换为 {@code [REDACTED:<规则 id>]}，请求/回复照常继续。
 * 覆盖 API key 前缀、云访问密钥、私钥头（无完整块时）、Bearer 长串、带口令的 JDBC/连接串；</li>
 * <li><b>block</b>：仅<b>完整私钥块</b>（{@code BEGIN … PRIVATE KEY} 到 {@code END … PRIVATE KEY}
 * 之间有内容）与<b>未闭合的私钥头</b>（截断的私钥同样等于泄露私钥本体）。
 * 只有这一档会让输出以 {@code SENSITIVE_OUTPUT_BLOCKED} 收尾。</li>
 * </ol>
 *
 * <h2>为什么规则全部"拼接构造"</h2>
 * {@code scripts/githooks/scan-lib.sh} 的约定是"扫描器不自报"：规则前缀以 {@code "s" + "k-"} 这种
 * 形式拼出来，源码里就不会出现能被自家扫描器命中的完整字面量。本类遵守同一纪律。
 *
 * <h2>只记 id，不记原文</h2>
 * 调用方（日志 / {@code ai:safety:} 台账 / {@code done.safety}）只允许出现规则 id、偏移与长度，
 * 命中原文一律不落盘（OWASP 日志纪律）。
 */
public final class SafetyRules {

	/** API key（{@code sk-} 前缀 + ≥20 位）。 */
	public static final String RULE_API_KEY = "API_KEY";

	/** 云访问密钥 id（{@code AKIA} 前缀 + 16 位大写字母数字）。 */
	public static final String RULE_AWS_KEY = "AWS_ACCESS_KEY";

	/** 完整私钥块（BEGIN 头到 END 尾之间有内容）—— 唯一走 block 的规则。 */
	public static final String RULE_PRIVATE_KEY = "PRIVATE_KEY";

	/** 私钥头（没有配对 END 时只脱敏头部，不 block —— 见 {@link #openPrivateKeyHeader}）。 */
	public static final String RULE_PRIVATE_KEY_HEADER = "PRIVATE_KEY_HEADER";

	/** {@code Bearer} 长令牌。 */
	public static final String RULE_BEARER = "BEARER_TOKEN";

	/** 带口令的 JDBC 连接串（{@code user:pass@} 或 Oracle thin {@code user/pass@}）。 */
	public static final String RULE_JDBC = "JDBC_CREDENTIAL";

	/** 带 userinfo 的连接串（{@code scheme://user:pass@host}）。 */
	public static final String RULE_URL_USERINFO = "URL_CREDENTIAL";

	/** {@code password=} / {@code pwd=} 之类的显式口令赋值（值 ≥8 字符、非占位符形态）。 */
	public static final String RULE_PASSWORD_ASSIGN = "PASSWORD_ASSIGN";

	/**
	 * 尾部保留窗口（字符）：输出侧必须留住"最后 N 个字符"才能保证跨分片的模式不被漏检。
	 *
	 * <p>
	 * 取 128 = 最长<b>定长</b>规则跨度的上界（私钥头 + 最长口令赋值都远小于它）。
	 * 不用调研 §4.7 的"512 字符整段缓冲"：那会让短回复的首字节等到 512 字符才出现，
	 * 用户能直接感到卡顿（用户裁决 2026-10-21 第 5 条）。
	 */
	public static final int TAIL_WINDOW = 128;

	/** 私钥块最多吞掉的字符数（超过即按"未闭合"处理，防一条假头吞掉整篇回复）。 */
	private static final int PRIVATE_KEY_BODY_MAX = 8192;

	// ── 模式字面量的拼接构造（见类注"扫描器不自报"） ────────────────────────────
	private static final String P_SK = "s" + "k-";
	private static final String P_AKIA = "AK" + "IA";
	private static final String P_PEM_HEAD = "-----BEGIN ";
	private static final String P_PEM_FOOT = "-----END ";
	private static final String P_PEM_KEY = "PRIVATE KEY-----";
	private static final String P_BEARER = "B" + "earer";

	private static final String PEM_ALGO = "[A-Z0-9 ]{0,32}";

	private static final List<Rule> RULES = List.of(
			// 私钥块排第一：同一位置既有整块又有头时，整块（block 档）优先
			new Rule(RULE_PRIVATE_KEY, Pattern.compile(P_PEM_HEAD + PEM_ALGO + P_PEM_KEY + "[\\s\\S]{1,"
					+ PRIVATE_KEY_BODY_MAX + "}?" + P_PEM_FOOT + PEM_ALGO + P_PEM_KEY), true),
			new Rule(RULE_PRIVATE_KEY_HEADER, Pattern.compile(P_PEM_HEAD + PEM_ALGO + P_PEM_KEY), false),
			new Rule(RULE_API_KEY,
					Pattern.compile("(?<![A-Za-z0-9])" + P_SK + "[A-Za-z0-9_\\-]{20,}"), false),
			new Rule(RULE_AWS_KEY, Pattern.compile("(?<![A-Za-z0-9])" + P_AKIA + "[0-9A-Z]{16}(?![0-9A-Z])"),
					false),
			new Rule(RULE_BEARER, Pattern.compile("(?i)\\b" + P_BEARER + "\\s+[A-Za-z0-9\\-._~+/]{24,}=*"),
					false),
			// Oracle thin：jdbc:oracle:thin:user/pass@host —— userinfo 形态的 JDBC
			new Rule(RULE_JDBC, Pattern.compile(
					"(?i)\\bjdbc:[a-z0-9]+:([a-z0-9]+:)?[A-Za-z0-9_.\\-]{1,64}[:/][^\\s@/]{1,128}@"), false),
			new Rule(RULE_JDBC, Pattern.compile(
					"(?i)\\bjdbc:[a-z0-9]+://[^\\s/:@]{1,64}:[^\\s/@]{1,128}@"), false),
			new Rule(RULE_PASSWORD_ASSIGN, Pattern.compile(
					"(?i)\\b(?:password|passwd|pwd)\\s*=\\s*(?![<{$\\[])[^\\s&;,\"']{8,}"), false),
			new Rule(RULE_URL_USERINFO, Pattern.compile(
					"(?i)\\b[a-z][a-z0-9+.\\-]{1,20}://[^\\s/:@]{1,64}:[^\\s/@]{1,128}@"), false));

	private SafetyRules() {
	}

	/** 一条规则的命中（{@code [start, end)}，{@code block} = 走阻断而不是脱敏）。 */
	public record Hit(String ruleId, int start, int end, boolean block) {
	}

	/** 整体脱敏的结果：{@code text} 是唯一下游可见产物，其余只用于观测（不含原文）。 */
	public record Masked(String text, int redacted, List<String> rules, boolean blocked) {
	}

	/** 脱敏占位符（机器生成，前端可据此提示"已隐去 N 处"）。 */
	public static String token(String ruleId) {
		return "[REDACTED:" + ruleId + "]";
	}

	/**
	 * 全部命中（已按起点排序、重叠已合并为一条）。<b>不</b>含"未闭合私钥头吞到末尾"的特例
	 * —— 那个特例由 {@link #openPrivateKeyHeader} 单独判定（输出侧要据此暂停发送）。
	 */
	public static List<Hit> hits(String text) {
		if (text == null || text.isEmpty()) {
			return List.of();
		}
		List<Hit> raw = new ArrayList<>();
		for (Rule rule : RULES) {
			Matcher matcher = rule.pattern().matcher(text);
			while (matcher.find()) {
				raw.add(new Hit(rule.id(), matcher.start(), matcher.end(), rule.block()));
			}
		}
		if (raw.isEmpty()) {
			return List.of();
		}
		// 起点升序；同起点取更长（整块优先于头）；重叠的后来者丢弃（先前者的规则语义更强）
		raw.sort(Comparator.comparingInt(Hit::start).thenComparing(Comparator.comparingInt(Hit::end).reversed()));
		List<Hit> merged = new ArrayList<>(raw.size());
		int covered = -1;
		for (Hit hit : raw) {
			if (hit.start() >= covered) {
				merged.add(hit);
				covered = hit.end();
			}
		}
		return merged;
	}

	/**
	 * 缓冲区末尾是否"可能还在某个模式的中间"（决定要不要扣住尾巴）。
	 *
	 * <p>
	 * 判据 = 尾部是否停在<b>某条规则的真实字面前缀</b>上（写全了的起手式 + 该起手式之后的续写区），
	 * 或停在<b>某个字面量前缀的未写全片段</b>上（分片正好切在 {@code sk-} / {@code -----BEGIN }
	 * 这类字面量中间）。只看最后 {@link #TAIL_WINDOW} 个字符。
	 *
	 * <p>
	 * <b>为什么不再用"起始锚字符集合"</b>（批 D · D1 根因）：旧实现的 {@code ANCHOR_CHARS}
	 * 是 {@code s/A/k/K/-/:/j/J/b/B/p/P/@//} 这十几个单字符，Markdown 的 {@code -}、
	 * 半角 {@code :} 与任何含 s/k/p/j 的英文词都在里面 —— 正文只要落在窗口内就恒被扣住，
	 * 输出侧因此"攒满 128 字符才吐"（用户看到的断续）。现在的扣留量是"前缀本身及其续写区"。
	 */
	public static boolean couldExtendMatch(String text) {
		return holdFrom(text) < (text == null ? 0 : text.length());
	}

	/**
	 * 应从哪个下标起扣住（{@code text.length()} = 无需扣住）。
	 *
	 * <p>扣住量恒 ≤ {@link #TAIL_WINDOW}：只看最后 {@code TAIL_WINDOW} 个字符。
	 */
	public static int holdFrom(String text) {
		if (text == null || text.isEmpty()) {
			return 0;
		}
		int len = text.length();
		int from = Math.max(0, len - TAIL_WINDOW);
		int hold = len;
		// ① 起手式已成形：前缀 + 其后仍可能是同一候选的续写字符
		for (Starter starter : STARTERS) {
			Matcher matcher = starter.opener().matcher(text);
			matcher.region(from, len);
			while (matcher.find()) {
				int end = matcher.end();
				if (starter.body().matcher(text).region(end, len).matches()) {
					hold = Math.min(hold, matcher.start());
					break;
				}
			}
		}
		// ② 字面量前缀只写出一半：只扣住已写出的那几位（分片切在字面量中间也不漏）
		for (Prefix prefix : PREFIXES) {
			hold = Math.min(hold, partialStart(text, prefix, from));
		}
		return hold;
	}

	/** 未写全前缀的起点（无匹配 ⇒ {@code text.length()}，即不扣）。 */
	private static int partialStart(String text, Prefix prefix, int from) {
		int len = text.length();
		String literal = prefix.literal();
		int maxK = Math.min(literal.length() - 1, len - from);
		for (int k = maxK; k >= prefix.minPartial(); k--) {
			int start = len - k;
			if (start < from) {
				return len;
			}
			if (!text.regionMatches(prefix.ignoreCase(), start, literal, 0, k)) {
				continue;
			}
			if (prefix.leftBoundary() && start > 0 && isAsciiAlnum(text.charAt(start - 1))) {
				continue;
			}
			return prefix.urlScheme() ? schemeStart(text, start) : start;
		}
		return len;
	}

	/**
	 * {@code ://} 只写出 {@code :} / {@code :/} 时，扣留点要退回**scheme 起点** ——
	 * 否则 {@code https:} 已被发出，下一帧的 {@code //user:pass@host} 里没有 scheme，
	 * URL 凭据规则认不出来（凭据就漏了）。
	 */
	private static int schemeStart(String text, int colonIndex) {
		int start = colonIndex;
		while (start > 0 && isSchemeChar(text.charAt(start - 1))) {
			start--;
		}
		return start;
	}

	/** 与规则里的 {@code (?<![A-Za-z0-9])} 同口径（中文不算 alnum —— 中英混排处照样起手）。 */
	private static boolean isAsciiAlnum(char c) {
		return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
	}

	/** URL scheme 允许的续写字符（{@code [a-z0-9+.-]}，大小写都算）。 */
	private static boolean isSchemeChar(char c) {
		return isAsciiAlnum(c) || c == '+' || c == '.' || c == '-';
	}

	/**
	 * 一条规则的"起手式"：{@code opener} 匹配可能是一条规则的头部（含左边界），
	 * {@code body} 是头部之后仍算"未写完"的字符类（匹配到缓冲区末尾 ⇒ 扣住）。
	 */
	private record Starter(Pattern opener, Pattern body) {
	}

	/**
	 * 一条规则的字面前缀（未写全的片段同样要扣住，否则分片正好切在字面量中间就漏）。
	 *
	 * @param minPartial  至少写出几位才认为是"未写全前缀"（私钥头取 2：单个 {@code -}
	 *                    是 Markdown 列表符/分隔线碎片，扣它只会制造无谓延迟；分片切在
	 *                    5 个连字符的第 1 位属于不现实的情形）
	 * @param urlScheme   命中点要退回 scheme 起点（只有 {@code ://} 用）
	 */
	private record Prefix(String literal, boolean ignoreCase, boolean leftBoundary, int minPartial,
			boolean urlScheme) {
	}

	/** 起手式表：**与 {@link #RULES} 的前缀同源**（改规则必须同步改这里）。 */
	private static final List<Starter> STARTERS = List.of(
			new Starter(Pattern.compile(P_PEM_HEAD), Pattern.compile("[A-Z0-9 ]{0,40}")),
			new Starter(Pattern.compile("(?<![A-Za-z0-9])" + P_SK), Pattern.compile("[A-Za-z0-9_\\-]*")),
			new Starter(Pattern.compile("(?<![A-Za-z0-9])" + P_AKIA), Pattern.compile("[0-9A-Z]{0,17}")),
			new Starter(Pattern.compile("(?i)(?<![A-Za-z0-9])" + P_BEARER),
					Pattern.compile("\\s{0,3}[A-Za-z0-9\\-._~+/=]*")),
			new Starter(Pattern.compile("(?i)(?<![A-Za-z0-9])jdbc:"), Pattern.compile("[^\\s@]*")),
			new Starter(Pattern.compile("(?i)(?<![A-Za-z0-9])(?:password|passwd|pwd)\\s*=\\s*"),
					Pattern.compile("[^\\s&;,\"']{0,7}")),
			new Starter(Pattern.compile("(?i)[a-z][a-z0-9+.\\-]{0,20}://"), Pattern.compile("[^\\s/@]*")));

	/** 字面前缀表：与 {@link #STARTERS} 同一批字面量，只负责"没写全"的那一半。 */
	private static final List<Prefix> PREFIXES = List.of(
			new Prefix(P_PEM_HEAD, false, false, 2, false),
			new Prefix(P_SK, false, true, 1, false),
			new Prefix(P_AKIA, false, true, 1, false),
			new Prefix(P_BEARER, true, true, 1, false),
			new Prefix("jdbc:", true, true, 1, false),
			new Prefix("password", true, true, 1, false),
			new Prefix("passwd", true, true, 1, false),
			new Prefix("pwd", true, true, 1, false),
			new Prefix("://", false, false, 1, true));

	/**
	 * 第一个<b>没有配对 END</b> 的私钥头位置（无则 {@code -1}）。
	 *
	 * <p>输出侧据此把"尚未闭合的私钥"挡在缓冲区里（不能先发头、后发 base64）。
	 */
	public static int openPrivateKeyHeader(String text) {
		if (text == null || text.isEmpty()) {
			return -1;
		}
		Pattern head = Pattern.compile(P_PEM_HEAD + PEM_ALGO + P_PEM_KEY);
		Pattern foot = Pattern.compile(P_PEM_FOOT + PEM_ALGO + P_PEM_KEY);
		Matcher matcher = head.matcher(text);
		while (matcher.find()) {
			Matcher footMatcher = foot.matcher(text);
			if (!footMatcher.find(matcher.end())) {
				// 头之后没有 END：若已有私有块规则覆盖到末尾，这里不会走到（块规则要求有 END）
				return matcher.start();
			}
		}
		return -1;
	}

	/** 整段脱敏（输入侧 / 反馈摘录 / 任何非流式面）。 */
	public static Masked mask(String text) {
		if (text == null || text.isEmpty()) {
			return new Masked("", 0, List.of(), false);
		}
		List<Hit> hits = hits(text);
		int open = openPrivateKeyHeader(text);
		if (open >= 0) {
			// 未闭合私钥：头到末尾整段按 block 档处理（截断的私钥同样是私钥本体）。
			// 先丢掉落在头之后的命中，再由 block 段统一覆盖。
			List<Hit> withOpen = new ArrayList<>();
			for (Hit hit : hits) {
				if (hit.end() <= open) {
					withOpen.add(hit);
				}
			}
			withOpen.add(new Hit(RULE_PRIVATE_KEY, open, text.length(), true));
			return apply(text, withOpen);
		}
		return apply(text, hits);
	}

	private static Masked apply(String text, List<Hit> hits) {
		if (hits.isEmpty()) {
			return new Masked(text, 0, List.of(), false);
		}
		StringBuilder out = new StringBuilder(text.length() + 16);
		Set<String> rules = new LinkedHashSet<>();
		boolean blocked = false;
		int last = 0;
		for (Hit hit : hits) {
			out.append(text, last, hit.start()).append(token(hit.ruleId()));
			last = hit.end();
			rules.add(hit.ruleId());
			blocked = blocked || hit.block();
		}
		out.append(text, last, text.length());
		return new Masked(out.toString(), hits.size(), List.copyOf(rules), blocked);
	}

	/** 规则定义。{@code block} 只允许私钥整块为 true（见类注）。 */
	private record Rule(String id, Pattern pattern, boolean block) {
	}

}
