package com.example.configmgr.ai.context;

import com.example.configmgr.ai.config.AiProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 触发判定（L 组：C-09 / C-10，设计稿 §5.2）。
 *
 * <p>
 * 两个口径分开是本设计的核心：<b>展示用真值</b>（{@code usedNow}）、<b>触发用预估</b>（{@code nextEst}）。
 * 这组用例把 {@code nextEst} 的组成逐项钉死（漏加任一项都会在真实长会话里晚一轮才压缩，
 * 而晚一轮的代价是先撞上游 400），并把滞回的两个边界（59% 允许 / 62% 拒绝）钉死。
 */
class ContextTriggerTest {

	private static ContextTrigger trigger() {
		// 默认配置：limit 128000 / reserve 13000 / 70-80-95 / 滞回 60
		return new ContextTrigger(new AiProperties.Context());
	}

	/** C-09：nextEst = used + completion + input + toolResults（四项精确相加）。 */
	@Test
	void c09NextEstIsTheExactSumOfFourTerms() {
		ContextTrigger.Decision decision = trigger().decide(1000, 200, 50, 300, true);

		assertThat(decision.nextEst()).isEqualTo(1550);
		assertThat(decision.limitTokens()).isEqualTo(128000);
		assertThat(decision.reserveTokens()).isEqualTo(13000);
		assertThat(decision.level()).isEqualTo(ContextTrigger.Level.NONE);
	}

	/** C-09 ②：分母是 {@code limit − reserve}（先扣 13K 预留再取比，不是对 limit 取比）。 */
	@Test
	void c09RatioUsesLimitMinusReserve() {
		// 80500 / 115000 = 0.7 恰好压线
		ContextTrigger.Decision decision = trigger().decide(80500, 0, 0, 0, true);

		assertThat(decision.ratio()).isCloseTo(0.7d, org.assertj.core.data.Offset.offset(1e-9));
		assertThat(decision.level()).as("恰好 70% 就算到线（>= 不是 >）").isEqualTo(ContextTrigger.Level.L1);
		// 若误用 limit 做分母：80500/128000 = 62.9% < 70% ⇒ 这里会红
	}

	/** 谱系：80% / 95% 各自换级（批① 只执行 L1，但判级由本类如实给出）。 */
	@Test
	void ladderStepsAtEachThreshold() {
		ContextTrigger trigger = trigger();

		assertThat(trigger.decide(80499, 0, 0, 0, true).level()).isEqualTo(ContextTrigger.Level.NONE);
		assertThat(trigger.decide(92000, 0, 0, 0, true).level()).isEqualTo(ContextTrigger.Level.L2);
		assertThat(trigger.decide(109250, 0, 0, 0, true).level()).isEqualTo(ContextTrigger.Level.L3);
	}

	/** 每次最多升一级：从 L1 只能到 L2，不能一步跳到 L3。 */
	@Test
	void levelAdvancesAtMostOneStep() {
		assertThat(ContextTrigger.Level.NONE.advanceTo(ContextTrigger.Level.L3)).isEqualTo(ContextTrigger.Level.L1);
		assertThat(ContextTrigger.Level.L1.advanceTo(ContextTrigger.Level.L3)).isEqualTo(ContextTrigger.Level.L2);
		assertThat(ContextTrigger.Level.L2.advanceTo(ContextTrigger.Level.L3)).isEqualTo(ContextTrigger.Level.L3);
		assertThat(ContextTrigger.Level.L2.advanceTo(ContextTrigger.Level.NONE)).isEqualTo(ContextTrigger.Level.L2);
	}

	/** C-10：滞回 —— 压缩后真值 62% 不得再触发；59% 才允许。kill：删滞回 ⇒ 62% 仍触发。 */
	@Test
	void c10HysteresisBlocksReTriggerUntilBelowSixtyPercent() {
		ContextTrigger trigger = trigger();

		assertThat(trigger.hysteresisUnlocked(79360, 128000, true)).as("62% 未解锁").isFalse();
		assertThat(trigger.hysteresisUnlocked(75520, 128000, true)).as("59% 已解锁").isTrue();
		assertThat(trigger.hysteresisUnlocked(79360, 128000, false)).as("从未压缩过 ⇒ 直接解锁").isTrue();
		assertThat(trigger.hysteresisUnlocked(120000, 0, true)).as("上限未知 ⇒ 解锁").isTrue();
	}

	/** 滞回锁住时，即便占比已越线也不给级别（但 ratio 照实返回，供观测）。 */
	@Test
	void lockedThresholdsNeverReturnALevel() {
		ContextTrigger.Decision decision = trigger().decide(100000, 0, 0, 0, false);

		assertThat(decision.level()).isEqualTo(ContextTrigger.Level.NONE);
		assertThat(decision.triggered()).isFalse();
		assertThat(decision.ratio()).isGreaterThan(0.8d);
	}

	/** 上限未知（降级态）：不发比例判定，ratio 恒 0。 */
	@Test
	void degradedWhenLimitUnknown() {
		AiProperties properties = new AiProperties();
		properties.getContext().setLimitTokens(0);
		ContextTrigger.Decision decision = new ContextTrigger(properties.getContext()).decide(100000, 0, 0, 0, true);

		assertThat(decision.degraded()).isTrue();
		assertThat(decision.ratio()).isZero();
		assertThat(decision.level()).isEqualTo(ContextTrigger.Level.NONE);
	}

}
