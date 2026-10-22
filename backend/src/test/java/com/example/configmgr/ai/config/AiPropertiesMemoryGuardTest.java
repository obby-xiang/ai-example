package com.example.configmgr.ai.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 记忆窗口配置的启动期校验（记忆窗口批）。
 *
 * <p>
 * 与 {@link AiPropertiesContextGuardTest} 同一姿态：这组坏值都不是"性能降级"，而是
 * <b>判定被静默改写</b> ——
 * <ul>
 * <li>{@code min-messages > max-messages} ⇒ 两套兜底互相打穿：条数上限想裁到 40，下限却要求留 60，
 * 结果是"配了上限但永远不生效"（窗口按预算跑，条数兜底形同虚设）；</li>
 * <li>{@code token-budget < 0} ⇒ 负预算让每一轮都判"越线"，每轮都淘汰到下限（历史被钉死在最近几条）；</li>
 * <li>{@code min-messages <= 0} ⇒ 下限为 0 = 没有下限，预算可以把窗口压到"只剩最后一个 turn"，
 * 短会话在长历史里失忆（这正是本键要防的那件事）。</li>
 * </ul>
 */
class AiPropertiesMemoryGuardTest {

	private static AiProperties props() {
		return new AiProperties();
	}

	/** 默认配置必须能过闸（防"加了校验把默认值判死"）。 */
	@Test
	void defaultsPass() {
		AiProperties properties = props();
		assertThatCode(properties::verifyMemory).doesNotThrowAnyException();
	}

	/**
	 * 默认口径 = <b>min 40 / max 40</b>（2026-10-22 用户裁「业界主流的来吧，40」）。
	 *
	 * <p>两端同值不是笔误：条数固定 40，超预算只清工具结果；turn 淘汰只在"清完仍超且条数 &gt; 40"
	 * 时发生（见 {@code MemoryWindowTest#defaultFortyNeverTrimsBelowFortyAndTrimsTurnsOnlyWhenOverForty}）。
	 */
	@Test
	void defaultsAreFortyMessagesOnBothEnds() {
		AiProperties properties = props();

		assertThat(properties.getMemory().getMaxMessages()).isEqualTo(40);
		assertThat(properties.getMemory().getMinMessages()).as("条数下限与上限同为 40").isEqualTo(40);
	}

	/** 下限大于上限：条数兜底自相矛盾。 */
	@Test
	void minMessagesMustNotExceedMaxMessages() {
		AiProperties properties = props();
		properties.getMemory().setMinMessages(properties.getMemory().getMaxMessages() + 1);

		assertThatThrownBy(properties::verifyMemory).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("min-messages")
			.hasMessageContaining("max-messages");
	}

	/**
	 * 边界：min = max 合法（等价于"条数钉死"），判据是严格大于才失败。
	 *
	 * <p>取 60/60 而不是 40/40：默认值本身就是 40/40，用默认值等于没测这条边界。
	 */
	@Test
	void minEqualToMaxIsAccepted() {
		AiProperties properties = props();
		properties.getMemory().setMaxMessages(60);
		properties.getMemory().setMinMessages(60);

		assertThatCode(properties::verifyMemory).doesNotThrowAnyException();
	}

	/** 负预算 = 每轮都判越线。 */
	@Test
	void negativeTokenBudgetIsRejected() {
		AiProperties properties = props();
		properties.getMemory().setTokenBudget(-1);

		assertThatThrownBy(properties::verifyMemory).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("token-budget");
	}

	/** 0 = 关闭预算（只按条数），是合法逃生门；负值才是坏值。 */
	@Test
	void zeroTokenBudgetDisablesTheBudget() {
		AiProperties properties = props();
		properties.getMemory().setTokenBudget(0);

		assertThatCode(properties::verifyMemory).doesNotThrowAnyException();
	}

	/** 下限必须 ≥ 1：0 = 没有下限（预算可把窗口压到只剩最后一段）。 */
	@Test
	void nonPositiveMinMessagesIsRejected() {
		AiProperties properties = props();
		properties.getMemory().setMinMessages(0);

		assertThatThrownBy(properties::verifyMemory).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("min-messages");
	}

	/** 上限必须 ≥ 1（0 会让官方窗口什么都不留）。 */
	@Test
	void nonPositiveMaxMessagesIsRejected() {
		AiProperties properties = props();
		properties.getMemory().setMaxMessages(0);

		assertThatThrownBy(properties::verifyMemory).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("max-messages");
	}
}
