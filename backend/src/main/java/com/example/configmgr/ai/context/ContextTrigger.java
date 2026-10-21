package com.example.configmgr.ai.context;

import com.example.configmgr.ai.config.AiProperties;

/**
 * 触发判定（上下文统计与压缩专项批①；设计稿 §3.2）。
 *
 * <h2>两套口径分开（本设计的核心）</h2>
 * <ul>
 * <li><b>展示用真值</b>：{@code usedNow = 最后一次成功请求的 usage.promptTokens}；</li>
 * <li><b>触发用预估</b>：{@code nextEst = usedNow + lastCompletion + estimate(本轮新输入)
 * + Σ(上次真值之后新增的工具结果)}</b> —— 判的是"下一次请求会有多大"，而不是"上一次有多大"。
 * 若拿上一次的 {@code used} 判，轮内工具回环会一路撞到上游 400 才发现（晚一轮）。</li>
 * </ul>
 * 两者的偏差被"<b>触发看估算、展示看真值</b>"隔离；滞回只看真值，故不会因估算抖动反复触发。
 *
 * <h2>阈值</h2>
 * 对 {@code limit − reserve} 取比（先扣 13K 预留缓冲再取比）：L1 ≥70% / L2 ≥80% / L3 ≥95%。
 * 自洽性检查（写进 NUMSPEC 依据列）：128K 下 {@code limit − reserve = 115K}，L3@95% 剩
 * {@code 0.05 × 115K = 5.75K > max-tokens 4096} ⇒ 触发线不会把输出空间吃掉。
 *
 * <p>
 * <b>每次最多升一级</b>由调用方（{@link ContextCompactor}）执行：本类只回答"按占比该到哪一级"，
 * 从当前级往上最多推一格 —— 一次跳到 L2/L3 会把整个前缀改写、缓存整段重建（调研 Q3）。
 */
public final class ContextTrigger {

	/** 压缩级别。 */
	public enum Level {

		/** 不触发。 */
		NONE,

		/** L1：工具结果清除（最轻、ROI 最高，原文在台账）。 */
		L1,

		/** L2：按 Turn 滑窗 + 递归摘要（批③）。 */
		L2,

		/** L3：硬截断兜底（批③）。 */
		L3;

		/** 级别名（帧与台账的线上值：{@code none}/{@code L1}/{@code L2}/{@code L3}）。 */
		public String wire() {
			return this == NONE ? "none" : name();
		}

		public static Level fromWire(String raw) {
			if (raw == null) {
				return NONE;
			}
			for (Level level : values()) {
				if (level.wire().equalsIgnoreCase(raw.trim())) {
					return level;
				}
			}
			return NONE;
		}

		/** 本次最多升一级：{@code this} 与 {@code next} 之间只前进一格。 */
		public Level advanceTo(Level next) {
			if (next == null || next.ordinal() <= ordinal()) {
				return this;
			}
			return values()[ordinal() + 1];
		}
	}

	/**
	 * 一次判定结果。
	 *
	 * @param level      该占比对应的<b>目标</b>级别（调用方按"最多升一级"收敛）
	 * @param ratio      {@code nextEst / (limit − reserve)}；{@code limit <= 0} 时恒 0（降级态无分母）
	 * @param nextEst    触发用预估（下次请求的 prompt 估算）
	 * @param limitTokens 分母上限（{@code <=0} = 未知）
	 * @param reserveTokens 预留缓冲
	 */
	public record Decision(Level level, double ratio, int nextEst, int limitTokens, int reserveTokens) {

		public boolean triggered() {
			return this.level != Level.NONE;
		}

		/** 含 {@code limit <= 0} 的降级态（调用方据此不发百分比、不做比例判定）。 */
		public boolean degraded() {
			return this.limitTokens <= 0;
		}
	}

	private final AiProperties.Context config;

	public ContextTrigger(AiProperties.Context config) {
		this.config = config == null ? new AiProperties.Context() : config;
	}

	/**
	 * 触发判定。
	 *
	 * @param usedPrompt              上次成功请求的 {@code promptTokens}（无真值传 0）
	 * @param lastCompletion          上次成功请求的 {@code completionTokens}
	 * @param newInputTokens          本轮新输入的估算
	 * @param toolResultsSinceMeasure 上次真值之后新增的工具结果估算（轮起点通常为 0：
	 *                                窗口里的工具结果已经计在上次真值里；续跑重建的工具响应才是真增量）
	 * @param thresholdsArmed         是否允许按阈值触发（滞回锁，见 {@link #hysteresisUnlocked}）
	 */
	public Decision decide(int usedPrompt, int lastCompletion, int newInputTokens, int toolResultsSinceMeasure,
			boolean thresholdsArmed) {
		long nextEst = Math.max(0, usedPrompt) + Math.max(0, lastCompletion) + Math.max(0, newInputTokens)
				+ Math.max(0, toolResultsSinceMeasure);
		int limit = this.config.getLimitTokens();
		int reserve = this.config.getReserveTokens();
		if (limit <= 0) {
			return new Decision(Level.NONE, 0d, (int) nextEst, 0, reserve);
		}
		long denominator = Math.max(1L, (long) limit - reserve);
		double ratio = (double) nextEst / denominator;
		if (!thresholdsArmed) {
			return new Decision(Level.NONE, ratio, (int) nextEst, limit, reserve);
		}
		Level level = Level.NONE;
		if (ratio * 100d >= this.config.getL3Percent()) {
			level = Level.L3;
		}
		else if (ratio * 100d >= this.config.getL2Percent()) {
			level = Level.L2;
		}
		else if (ratio * 100d >= this.config.getL1Percent()) {
			level = Level.L1;
		}
		return new Decision(level, ratio, (int) nextEst, limit, reserve);
	}

	/**
	 * 滞回解锁：压缩完成后 {@code usedNow / limit < hysteresis-percent} 才允许再次触发。
	 *
	 * <p>
	 * 判据用<b>真值</b>（不是 nextEst）：估算抖动不该产生"压一次不够、立刻再压"的抖动链。
	 * 从未压缩过 ⇒ 直接解锁。上限未知 ⇒ 解锁（降级态由 L1/L3 的绝对阈值兜底，本批不实现）。
	 */
	public boolean hysteresisUnlocked(int usedNow, int limitTokens, boolean everCompacted) {
		if (!everCompacted) {
			return true;
		}
		if (limitTokens <= 0) {
			return true;
		}
		return (double) Math.max(0, usedNow) * 100d / limitTokens < this.config.getHysteresisPercent();
	}

}
