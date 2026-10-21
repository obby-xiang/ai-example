package com.example.configmgr.ai.context;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会话级上下文真值（上下文统计与压缩专项批①；设计稿 §3.4(b)）。
 *
 * <p>
 * 落 Redis HASH {@code ai:ctx:<sessionId>}，TTL = {@code app.ai.session-ttl} 且每次写/读 touch
 * （与会话同滑动）。字段名与设计稿逐键对应：{@code used/completion/turnNo/model/est/atMs/level/
 * turns/lost/reason/circuit/fails}，另加批① 需要的三个分项锚点
 * {@code systemTokens/toolsTokens/inputTokens}（端点重建分项时不必重新装配 system/tools ——
 * 那两个类目来自装配点，历史里没有）。
 *
 * <p>
 * {@code circuit}/{@code fails}/{@code openUntilMs} 由批③ 的 {@code CompactionFuse} 维护；
 * 批① 阶段它们恒为 {@code closed}/0/0（字段先在契约里，端点因此能给出形状固定的 {@code compaction} 对象）。
 *
 * @param usedTokens       展示用真值（{@code usage.promptTokens}；压缩后先置估算并 {@code est=true}）
 * @param completionTokens 上次成功请求的输出 token（{@code nextEst} 的一项）
 * @param turnNo           会话内已完成的轮数（压缩归档键名的一维）
 * @param est              {@code usedTokens} 是否来自估算（{@code ≈} 的唯一来源）
 * @param atMs             最后一次记账时刻（服务端钟）
 * @param model            记账时的模型名（端点按它解析分母）
 * @param level            最近一次压缩级别（{@code none}/{@code L1}/{@code L2}/{@code L3}）
 * @param compactedTurns   最近一次压缩折叠掉的 Turn 数（L1 恒 0：只清结果不丢轮）
 * @param lostTokens       最近一次压缩丢掉的 token（L1 恒 0）
 * @param reason           最近一次压缩/跳过原因
 * @param circuit          熔断态（{@code closed}/{@code open}/{@code half_open}；批③ 写入）
 * @param fuseFails        连续失败计数（批③ 写入）
 * @param systemTokens     人设 + 工作区快照的估算（装配点写入）
 * @param toolsTokens      本轮披露 schema 的估算（装配点写入）
 * @param inputTokens      本轮输入的估算（装配点写入）
 * @param openUntilMs      熔断冷却截止时刻（批③ 写入；0 = 未打开）
 */
public record ContextState(int usedTokens, int completionTokens, int turnNo, boolean est, long atMs, String model,
		String level, int compactedTurns, int lostTokens, String reason, String circuit, int fuseFails,
		int systemTokens, int toolsTokens, int inputTokens, long openUntilMs) {

	/** 空态（会话从未记账）。 */
	public static final ContextState EMPTY = new ContextState(0, 0, 0, false, 0L, null, "none", 0, 0, null, "closed", 0,
			0, 0, 0, 0L);

	/** 是否已有真值/估算可展示（否则端点回"未测"而不是"用了 0"）。 */
	public boolean measured() {
		return this.atMs > 0L;
	}

	/** 已压缩过（滞回判据的输入）。 */
	public boolean everCompacted() {
		return ContextTrigger.Level.fromWire(this.level) != ContextTrigger.Level.NONE;
	}

	/** 记一轮的计量真值（轮末记账）。 */
	public ContextState withUsage(int used, int completion, int turnNo, boolean estimated, long atMs, String model,
			int system, int tools, int input) {
		return new ContextState(Math.max(0, used), Math.max(0, completion), Math.max(0, turnNo), estimated, atMs, model,
				this.level, this.compactedTurns, this.lostTokens, this.reason, this.circuit, this.fuseFails,
				Math.max(0, system), Math.max(0, tools), Math.max(0, input), this.openUntilMs);
	}

	/** 记一次压缩（级别/折叠轮数/丢失量/原因）。 */
	public ContextState withCompaction(ContextTrigger.Level level, int turns, int lost, String reason, long atMs) {
		return new ContextState(this.usedTokens, this.completionTokens, this.turnNo, this.est, atMs, this.model,
				level.wire(), Math.max(0, turns), Math.max(0, lost), reason, this.circuit, this.fuseFails,
				this.systemTokens, this.toolsTokens, this.inputTokens, this.openUntilMs);
	}

	/** 记熔断态（{@code circuit} / 连续失败数 / 冷却截止时刻）。 */
	public ContextState withFuse(String circuit, int fails, long openUntilMs) {
		return new ContextState(this.usedTokens, this.completionTokens, this.turnNo, this.est, this.atMs, this.model,
				this.level, this.compactedTurns, this.lostTokens, this.reason, circuit, Math.max(0, fails),
				this.systemTokens, this.toolsTokens, this.inputTokens, Math.max(0L, openUntilMs));
	}

	/** Redis HASH 字段（值一律字符串：HASH 里不做类型推断，读侧按需解析）。 */
	public Map<String, String> toFields() {
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("used", String.valueOf(this.usedTokens));
		fields.put("completion", String.valueOf(this.completionTokens));
		fields.put("turnNo", String.valueOf(this.turnNo));
		fields.put("est", String.valueOf(this.est));
		fields.put("atMs", String.valueOf(this.atMs));
		if (this.model != null) {
			fields.put("model", this.model);
		}
		fields.put("level", this.level == null ? "none" : this.level);
		fields.put("turns", String.valueOf(this.compactedTurns));
		fields.put("lost", String.valueOf(this.lostTokens));
		if (this.reason != null) {
			fields.put("reason", this.reason);
		}
		fields.put("circuit", this.circuit == null ? "closed" : this.circuit);
		fields.put("fails", String.valueOf(this.fuseFails));
		fields.put("systemTokens", String.valueOf(this.systemTokens));
		fields.put("toolsTokens", String.valueOf(this.toolsTokens));
		fields.put("inputTokens", String.valueOf(this.inputTokens));
		fields.put("openUntil", String.valueOf(this.openUntilMs));
		return fields;
	}

	/** 读侧的宽松解析（缺字段/脏值一律按缺省；计量面不因一份脏 HASH 抛错）。 */
	public static ContextState fromFields(Map<String, String> raw) {
		if (raw == null || raw.isEmpty()) {
			return EMPTY;
		}
		return new ContextState(intOf(raw, "used", 0), intOf(raw, "completion", 0), intOf(raw, "turnNo", 0),
				Boolean.parseBoolean(raw.getOrDefault("est", "false")), longOf(raw, "atMs", 0L), raw.get("model"),
				raw.getOrDefault("level", "none"), intOf(raw, "turns", 0), intOf(raw, "lost", 0), raw.get("reason"),
				raw.getOrDefault("circuit", "closed"), intOf(raw, "fails", 0), intOf(raw, "systemTokens", 0),
				intOf(raw, "toolsTokens", 0), intOf(raw, "inputTokens", 0), longOf(raw, "openUntil", 0L));
	}

	private static int intOf(Map<String, String> raw, String key, int fallback) {
		String value = raw.get(key);
		if (value == null) {
			return fallback;
		}
		try {
			return Integer.parseInt(value.trim());
		}
		catch (NumberFormatException ex) {
			return fallback;
		}
	}

	private static long longOf(Map<String, String> raw, String key, long fallback) {
		String value = raw.get(key);
		if (value == null) {
			return fallback;
		}
		try {
			return Long.parseLong(value.trim());
		}
		catch (NumberFormatException ex) {
			return fallback;
		}
	}

}
