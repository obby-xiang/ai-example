package com.example.configmgr.ai.run;

import com.example.configmgr.ai.gate.SafetyRules;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 输出侧 DLP（防护批③ B3-2/B3-3）：<b>流式脱敏 + 尾部保留窗口</b>。
 *
 * <h2>落点铁律</h2>
 * 唯一合法的调用点是 {@code ResilientChatService#consume}，且必须在 {@code Accumulator}
 * <b>之前</b>——脱敏后的文本同时喂给"累计器（终帧 {@code chars} / 落定正文 / 记忆）"与
 * {@code out.delta(...)}，两处同源。若改在 {@code SseChatEmitter#delta} 里脱敏，
 * {@code FrameContract#textSegmentContract}（{@code message_end.chars == Σ delta 长度}）立刻破。
 *
 * <h2>尾部保留窗口（不是整段缓冲）</h2>
 * 只压住"最后 {@link SafetyRules#TAIL_WINDOW} 个字符"：跨分片被切开的模式（{@code s} | {@code k-…}）
 * 照样能拼回来，而正文不必等到轮末才出现。调研 §4.7 的"512 字符整段缓冲"会让短回复首字延迟
 * 到 512 字符之后，用户裁决 2026-10-21 第 5 条明确禁止。
 *
 * <h2>分级</h2>
 * 默认 mask（替换为 {@code [REDACTED:<id>]}）后继续；只有私钥整块（含未闭合的私钥头）置
 * {@link #blocked()} —— 由 {@code ResilientChatService} 转成
 * {@code error{SENSITIVE_OUTPUT_BLOCKED}} 终帧（先 {@code textEnd} 收尾正文段，保持段闭合 + 单终帧）。
 *
 * <h2>状态作用域</h2>
 * <b>每次 attempt 一个新实例</b>（与 {@code Accumulator} 同寿）：重试不串尾巴，
 * 也不会把上一次尝试的半截缓冲带到下一次。
 */
public class OutputGuard {

	private final StringBuilder buffer = new StringBuilder();

	private final Set<String> rules = new LinkedHashSet<>();

	private final List<Event> events = new java.util.ArrayList<>();

	private long streamOffset;

	private int redacted;

	private boolean blocked;

	/**
	 * 一次命中（审计用）：{@code offset} 是<b>本轮正文里的绝对字符偏移</b>（从 0 起），
	 * {@code length} 是命中原文长度，{@code action} ∈ {@code masked}/{@code blocked}。
	 * <p><b>不含原文</b> —— 台账/日志只允许出现这些元数据（OWASP 日志纪律）。
	 */
	public record Event(String ruleId, long offset, int length, String action) {
	}

	/**
	 * 喂入一个上游分片，返回"现在可以安全输出"的前缀（可能为空串）。
	 *
	 * <p>被阻断后返回空串：已判定为私钥泄露的内容一律不再外发。
	 */
	public String feed(String chunk) {
		if (this.blocked) {
			return "";
		}
		if (chunk != null && !chunk.isEmpty()) {
			this.buffer.append(chunk);
		}
		if (this.buffer.isEmpty()) {
			return "";
		}
		// 只有"末尾可能落在模式中间"时才扣住尾巴（普通正文立刻全量发出 —— 短回复首字不延迟）；
		// 扣住的上界仍是 TAIL_WINDOW，绝不整段缓冲到轮末。
		int cut = SafetyRules.holdFrom(this.buffer.toString());
		int open = SafetyRules.openPrivateKeyHeader(this.buffer.toString());
		if (open >= 0) {
			// 私钥头已经出现、END 还没来：从头部起一律扣住（不能先发头、后发 base64）
			cut = Math.min(cut, open);
		}
		return emit(cut);
	}

	/** 轮末清仓：返回缓冲区剩余文本（脱敏后）。未闭合私钥按 block 处理。 */
	public String flush() {
		if (this.blocked) {
			this.buffer.setLength(0);
			return "";
		}
		String text = this.buffer.toString();
		int open = SafetyRules.openPrivateKeyHeader(text);
		if (open >= 0) {
			long blockOffset = this.streamOffset + open;
			String head = emit(open);
			this.blocked = true;
			this.redacted++;
			this.rules.add(SafetyRules.RULE_PRIVATE_KEY);
			this.events.add(new Event(SafetyRules.RULE_PRIVATE_KEY, blockOffset, text.length() - open, "blocked"));
			this.buffer.setLength(0);
			return head + SafetyRules.token(SafetyRules.RULE_PRIVATE_KEY);
		}
		return emit(text.length());
	}

	/**
	 * 输出 {@code [0, cut)}（并把越过 cut 的命中整段吃掉），剩下留待下次。
	 */
	private String emit(int cut) {
		if (cut <= 0) {
			return "";
		}
		String full = this.buffer.toString();
		StringBuilder out = new StringBuilder(Math.max(cut, 16));
		int last = 0;
		int consumed = cut;
		List<SafetyRules.Hit> hits = SafetyRules.hits(full);
		for (SafetyRules.Hit hit : hits) {
			if (hit.start() >= cut) {
				break;
			}
			out.append(full, last, hit.start());
			out.append(SafetyRules.token(hit.ruleId()));
			this.events.add(new Event(hit.ruleId(), this.streamOffset + hit.start(), hit.end() - hit.start(),
					hit.block() ? "blocked" : "masked"));
			last = hit.end();
			consumed = Math.max(consumed, last);
			this.rules.add(hit.ruleId());
			this.redacted++;
			if (hit.block()) {
				this.blocked = true;
			}
		}
		int upTo = Math.min(full.length(), consumed);
		out.append(full, last, upTo);
		this.buffer.delete(0, upTo);
		this.streamOffset += upTo;
		if (this.blocked) {
			this.buffer.setLength(0);
		}
		return out.toString();
	}

	/** 是否已判定阻断（私钥整块/未闭合私钥）。 */
	public boolean blocked() {
		return this.blocked;
	}

	/** 命中处数（写入 {@code done.safety.redacted} 与 {@link SecurityEvents}）。 */
	public int redacted() {
		return this.redacted;
	}

	/** 命中的规则 id（有序）。 */
	public List<String> rules() {
		return List.copyOf(this.rules);
	}

	/** 逐条命中的审计元数据（规则 id / 偏移 / 长度 / 动作；<b>无原文</b>）。 */
	public List<Event> events() {
		return List.copyOf(this.events);
	}

	/**
	 * 终帧 {@code safety} 段（{@code done.extra.safety}，additive 可选键；帧类型集不变）。
	 *
	 * <p>未命中返回 {@code null} —— "没发生"与"发生 0 次"必须可区分（与 {@code done.context} 同姿态）。
	 */
	public Map<String, Object> safety() {
		if (this.redacted == 0 && !this.blocked) {
			return null;
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("redacted", this.redacted);
		out.put("rules", List.copyOf(this.rules));
		out.put("blocked", this.blocked);
		return out;
	}

}
