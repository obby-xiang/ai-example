package com.example.configmgr.ai.context;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 轮内上下文预算判定（工具轮内上下文预算批）。
 *
 * <h2>三档口径（固定输入，断言可读）</h2>
 * 统一 {@code limit=10000 / reserve=0}，于是"占比"与"估算"同数：
 * <ul>
 * <li>70 档 ⇒ {@link TurnBudgetPlan.Action#CLEAR_TOOL_RESULTS}（只清旧工具结果）；</li>
 * <li>95 档 ⇒ {@link TurnBudgetPlan.Action#WRAP_UP}（模型收尾 + armed）；</li>
 * <li>100 档 ⇒ {@link TurnBudgetPlan.Action#OVERFLOW}（先落定再报错，不发这一发）。</li>
 * </ul>
 *
 * <p><b>100 档为什么钉"硬上限"而不是 {@code limit − reserve}</b>：{@code reserve} 的语义是
 * 给<b>输出</b>留的空间（13000 &gt; max-tokens 4096），{@code nextEst} 只是 prompt 估算 ——
 * prompt 到 {@code limit − reserve} 并不注定 400。提前判死会白白丢掉一次本可成功的回答，
 * 所以 OVERFLOW 只在 {@code nextEst >= limitTokens}（prompt 已吃掉整窗）时成立；
 * {@code limit − reserve} 只参与 70/95 两档的比例（与 {@code ContextTrigger} 同口径）。
 */
class TurnBudgetPlanTest {

	private static final int LIMIT = 10000;

	@Test
	void seventyPercentTierClearsToolResults() {
		TurnBudgetPlan.Decision decision = TurnBudgetPlan.plan(request(7000, 0, false, 0));

		assertThat(decision.action()).isEqualTo(TurnBudgetPlan.Action.CLEAR_TOOL_RESULTS);
		assertThat(decision.reason()).isEqualTo(TurnBudgetPlan.REASON_L1);
		assertThat(decision.ratio()).isCloseTo(0.70d, org.assertj.core.data.Offset.offset(1e-9));
	}

	@Test
	void justBelowL1Proceeds() {
		TurnBudgetPlan.Decision decision = TurnBudgetPlan.plan(request(6999, 0, false, 0));

		assertThat(decision.action()).isEqualTo(TurnBudgetPlan.Action.PROCEED);
		assertThat(decision.reason()).isEqualTo(TurnBudgetPlan.REASON_UNDER_THRESHOLD);
	}

	@Test
	void ninetyFivePercentTierWrapsUp() {
		TurnBudgetPlan.Decision decision = TurnBudgetPlan.plan(request(9500, 0, false, 0));

		assertThat(decision.action()).isEqualTo(TurnBudgetPlan.Action.WRAP_UP);
		assertThat(decision.reason()).isEqualTo(TurnBudgetPlan.REASON_WRAP_UP);
	}

	@Test
	void hundredPercentTierOverflows() {
		TurnBudgetPlan.Decision decision = TurnBudgetPlan.plan(request(LIMIT, 0, false, 0));

		assertThat(decision.action()).isEqualTo(TurnBudgetPlan.Action.OVERFLOW);
		assertThat(decision.reason()).isEqualTo(TurnBudgetPlan.REASON_OVERFLOW);
	}

	@Test
	void overflowIsTheHardWindowNotTheReserveAdjustedWindow() {
		// reserve=2000：9500/(10000-2000) = 1.19 > 1，但 prompt 还没吃掉整窗 ⇒ 仍只是收尾
		TurnBudgetPlan.Decision decision = TurnBudgetPlan.plan(request(9500, 2000, false, 0));

		assertThat(decision.action()).isEqualTo(TurnBudgetPlan.Action.WRAP_UP);
		assertThat(decision.ratio()).isCloseTo(9500d / 8000d, org.assertj.core.data.Offset.offset(1e-9));
	}

	@Test
	void reserveIsSubtractedBeforeTheRatio() {
		// 6000/(10000-2000)=0.75 ⇒ CLEAR；若忘了扣 reserve 会算成 0.60 ⇒ PROCEED
		TurnBudgetPlan.Decision decision = TurnBudgetPlan.plan(request(6000, 2000, false, 0));

		assertThat(decision.action()).isEqualTo(TurnBudgetPlan.Action.CLEAR_TOOL_RESULTS);
	}

	@Test
	void realUsageBeatsTheEstimate() {
		// 真值 4000 + 0 + 0，估算却报 9000：判据取真值 ⇒ 未触发
		TurnBudgetPlan.Request request = new TurnBudgetPlan.Request(LIMIT, 0, 70, 95, true, 4000, 0, 9000, 0, false,
				0);

		TurnBudgetPlan.Decision decision = TurnBudgetPlan.plan(request);

		assertThat(decision.action()).isEqualTo(TurnBudgetPlan.Action.PROCEED);
		assertThat(decision.nextEst()).isEqualTo(4000);
	}

	@Test
	void realUsageCountsTheBatchDelta() {
		// 9000 + 100 + 500 = 9600 ⇒ 95 档（本批新增工具结果必须计入，否则晚一跳才发现）
		TurnBudgetPlan.Request request = new TurnBudgetPlan.Request(LIMIT, 0, 70, 95, true, 9000, 100, 1200, 500,
				false, 0);

		TurnBudgetPlan.Decision decision = TurnBudgetPlan.plan(request);

		assertThat(decision.nextEst()).isEqualTo(9600);
		assertThat(decision.action()).isEqualTo(TurnBudgetPlan.Action.WRAP_UP);
	}

	@Test
	void withoutUsageTheEstimateDecides() {
		TurnBudgetPlan.Request request = new TurnBudgetPlan.Request(LIMIT, 0, 70, 95, false, 0, 0, 7200, 0, false, 0);

		TurnBudgetPlan.Decision decision = TurnBudgetPlan.plan(request);

		assertThat(decision.nextEst()).isEqualTo(7200);
		assertThat(decision.action()).isEqualTo(TurnBudgetPlan.Action.CLEAR_TOOL_RESULTS);
	}

	@Test
	void completionOnlyUsageFallsBackToTheEstimate() {
		// 只有 completion 的 usage 帧（prompt=0）不能当"空请求"：否则越窗被误判成未触发
		TurnBudgetPlan.Request request = new TurnBudgetPlan.Request(LIMIT, 0, 70, 95, true, 0, 500, 7200, 0, false, 0);

		TurnBudgetPlan.Decision decision = TurnBudgetPlan.plan(request);

		assertThat(decision.nextEst()).isEqualTo(7200);
		assertThat(decision.action()).isEqualTo(TurnBudgetPlan.Action.CLEAR_TOOL_RESULTS);
	}

	@Test
	void sameInputYieldsTheSameDecision() {
		TurnBudgetPlan.Request request = request(9600, 0, false, 0);

		TurnBudgetPlan.Decision first = TurnBudgetPlan.plan(request);
		TurnBudgetPlan.Decision second = TurnBudgetPlan.plan(request);

		assertThat(second).isEqualTo(first);
	}

	/**
	 * C13：轮内也按<b>实际窗口</b>收口预留 —— 15000 窗口 + 13000 预留时，未收口的分母只剩 2000，
	 * 6000 的请求会被判成 300%（收尾/硬停），收口后是 80%（只清旧结果）。
	 */
	@Test
	void c13OversizedReserveIsClampedToTheRealWindowInRound() {
		TurnBudgetPlan.Decision decision = TurnBudgetPlan.plan(request(6000, 13000, false, 0, 15000));

		assertThat(decision.reserveTokens()).isEqualTo(7500);
		assertThat(decision.ratio()).isCloseTo(0.8d, org.assertj.core.data.Offset.offset(1e-9));
		assertThat(decision.action()).isEqualTo(TurnBudgetPlan.Action.CLEAR_TOOL_RESULTS);
	}

	@Test
	void unknownLimitDegradesInsteadOfJudging() {
		TurnBudgetPlan.Decision decision = TurnBudgetPlan.plan(request(999999, 0, false, 0, 0));

		assertThat(decision.action()).isEqualTo(TurnBudgetPlan.Action.PROCEED);
		assertThat(decision.degraded()).isTrue();
		assertThat(decision.reason()).isEqualTo(TurnBudgetPlan.REASON_LIMIT_UNKNOWN);
	}

	@Test
	void armedWrapUpThatIsIgnoredStopsAtTheCap() {
		TurnBudgetPlan.Decision decision = TurnBudgetPlan.plan(request(9600, 0, true,
				TurnBudgetPlan.MAX_ARMED_BATCHES));

		assertThat(decision.action()).isEqualTo(TurnBudgetPlan.Action.OVERFLOW);
		assertThat(decision.reason()).isEqualTo(TurnBudgetPlan.REASON_WRAP_UP_IGNORED);
	}

	@Test
	void armedBelowTheCapStaysInWrapUp() {
		TurnBudgetPlan.Decision decision = TurnBudgetPlan.plan(request(9600, 0, true, 1));

		assertThat(decision.action()).isEqualTo(TurnBudgetPlan.Action.WRAP_UP);
	}

	@Test
	void armedCapAppliesEvenWhenTheNewEstimateFallsBelowTheThresholds() {
		// 清过结果后估算可能回落；"armed 了还在要工具"本身就说明模型没在收尾
		TurnBudgetPlan.Decision decision = TurnBudgetPlan.plan(request(2000, 0, true,
				TurnBudgetPlan.MAX_ARMED_BATCHES));

		assertThat(decision.action()).isEqualTo(TurnBudgetPlan.Action.OVERFLOW);
		assertThat(decision.reason()).isEqualTo(TurnBudgetPlan.REASON_WRAP_UP_IGNORED);
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	private static TurnBudgetPlan.Request request(int nextEst, int reserve, boolean armed, int armedBatches) {
		return request(nextEst, reserve, armed, armedBatches, LIMIT);
	}

	private static TurnBudgetPlan.Request request(int nextEst, int reserve, boolean armed, int armedBatches,
			int limit) {
		return new TurnBudgetPlan.Request(limit, reserve, 70, 95, false, 0, 0, nextEst, 0, armed, armedBatches);
	}

}
