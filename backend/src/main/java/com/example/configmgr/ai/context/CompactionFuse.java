package com.example.configmgr.ai.context;

import com.example.configmgr.ai.config.AiProperties;

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
 * <p>
 * <b>多实例边界（DC-11 登记）</b>：状态在 {@code ai:ctx:<sessionId>} 里，读写非原子（读 → 判 → 写回），
 * 两个实例可能同时看到"冷却到点"并各放一次半开尝试。这是<b>双实例可达</b>的偏差，后果是多重试一次
 * 摘要（不会丢历史、不会越窗）；单实例路径上不存在该竞态。与设计稿 §8"首轮无租约"同一族边界，
 * 本棒不新增锁。
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
		this.config = config == null ? new AiProperties.Context() : config;
	}

	/** 本次是否允许摘要（L2/L3）。{@code open} 且未到冷却点 ⇒ false。 */
	public boolean allowsSummary(ContextState state, long nowMs) {
		if (state == null) {
			return true;
		}
		String circuit = state.circuit() == null ? CLOSED : state.circuit();
		if (OPEN.equals(circuit)) {
			return state.openUntilMs() > 0L && nowMs >= state.openUntilMs();
		}
		return true;
	}

	/** 摘要成功：清零并闭合。 */
	public ContextState onSuccess(ContextState state, long nowMs) {
		ContextState base = state == null ? ContextState.EMPTY : state;
		return base.withFuse(CLOSED, 0, 0L);
	}

	/** 冷却到点：标记半开（只放一次摘要尝试）。 */
	public ContextState halfOpen(ContextState state, long nowMs) {
		ContextState base = state == null ? ContextState.EMPTY : state;
		return base.withFuse(HALF_OPEN, base.fuseFails(), base.openUntilMs());
	}

	/** 摘要失败：计数 +1，达阈值即打开并给出冷却窗。 */
	public ContextState onFailure(ContextState state, long nowMs) {
		ContextState base = state == null ? ContextState.EMPTY : state;
		int fails = base.fuseFails() + 1;
		if (fails >= Math.max(1, this.config.getFuseThreshold())) {
			long openUntil = nowMs + this.config.getFuseCooldown().toMillis();
			return base.withFuse(OPEN, fails, openUntil);
		}
		return base.withFuse(CLOSED, fails, 0L);
	}

}
