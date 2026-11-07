package com.example.configmgr.ai.context;

import com.example.configmgr.ai.config.AiProperties;
import org.apache.commons.lang3.ObjectUtils;

/**
 * 摘要压缩熔断（上下文统计与压缩专项批③；设计稿 §3.5"熔断"）。
 *
 * <h2>只被"摘要尝试"驱动</h2>
 * 只有 L2/L3 的<b>摘要请求</b>成功/失败会改这套状态；L1（清工具结果）不进熔断 —— 可用性优先：
 * 摘要模型挂了就只清结果，对话照常跑。若 L1 也参与计数，"摘要坏 + L1 一直可用"会让计数永远清零，
 * 熔断形同虚设。
 *
 * <h2>三态</h2>
 * <ul>
 * <li>{@code closed}：正常；</li>
 * <li>{@code open}：连续失败达阈值 ⇒ 冷却窗内<b>跳过</b> L2/L3（只留 L1）；</li>
 * <li>{@code half_open}：冷却到点后放<b>一次</b>尝试 —— 成功清零闭合、失败重新计满并开一个新窗。
 * 不升指数退避：长尾失败场景下指数会把 L3 锁死数小时，而 L1 仍然是可用的兜底。</li>
 * </ul>
 *
 * <p>生产存储以 version CAS 抢占 half-open 名额，并用单键 Lua 原子累加失败计数；
 * 多实例不会再用陈旧快照覆盖熔断状态。
 */
public final class CompactionFuse {

	/** 熔断态：闭合（正常）。 */
	public static final String CLOSED = "closed";

	/** 熔断态：打开（跳过 L2/L3，只留 L1）。 */
	public static final String OPEN = "open";

	/** 熔断态：半开（冷却到点，放一次摘要尝试）。 */
	public static final String HALF_OPEN = "half_open";

	private final AiProperties.Context config;

	public CompactionFuse(AiProperties.Context config) {
		this.config = ObjectUtils.getIfNull(config, AiProperties.Context::new);
	}

	/** 本次是否允许摘要（L2/L3）。{@code open} 且未到冷却点 ⇒ false。 */
	public boolean allowsSummary(ContextState state, long nowMs) {
		return canSummarize(state, nowMs);
	}

	public boolean canSummarize(ContextState state, long nowMs) {
		if (state == null) {
			return true;
		}
		String circuit = ObjectUtils.defaultIfNull(state.circuit(), CLOSED);
		if (OPEN.equals(circuit)) {
			return state.openUntilMs() > 0L && nowMs >= state.openUntilMs();
		}
		return true;
	}

	/** 摘要成功：清零并闭合。 */
	public ContextState onSuccess(ContextState state, long nowMs) {
		ContextState base = ObjectUtils.defaultIfNull(state, ContextState.EMPTY);
		return base.withFuse(CLOSED, 0, 0L);
	}

	/** 冷却到点：标记半开（只放一次摘要尝试）。 */
	public ContextState halfOpen(ContextState state, long nowMs) {
		ContextState base = ObjectUtils.defaultIfNull(state, ContextState.EMPTY);
		return base.withFuse(HALF_OPEN, base.fuseFails(), base.openUntilMs());
	}

	/** 摘要失败：计数 +1，达阈值即打开并给出冷却窗。 */
	public ContextState onFailure(ContextState state, long nowMs) {
		ContextState base = ObjectUtils.defaultIfNull(state, ContextState.EMPTY);
		int fails = base.fuseFails() + 1;
		if (fails >= Math.max(1, this.config.getFuseThreshold())) {
			long openUntil = nowMs + this.config.getFuseCooldown().toMillis();
			return base.withFuse(OPEN, fails, openUntil);
		}
		return base.withFuse(CLOSED, fails, 0L);
	}

}
