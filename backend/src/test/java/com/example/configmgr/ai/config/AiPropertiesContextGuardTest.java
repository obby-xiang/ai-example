package com.example.configmgr.ai.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 上下文配置的启动期校验（上下文统计与压缩专项批① 收口 M-1）。
 *
 * <p>
 * 这组用例的存在理由：`verifyContext` 的每条闸都对应一个"配置面看起来正常、运行面语义已被改写"
 * 的坏值。M-1 补的 `chunk-tokens < reserve-tokens` 尤其隐蔽 —— 摘要是<b>另一次</b>模型请求，
 * 分块大于预留缓冲时，摘要请求自身就可能越窗，而失败路径要等真跑到批③ 的熔断才发现。
 * <b>类注与 NUMSPEC 都已承诺这条约束</b>，没有闸 = 承诺与实现不一致（本组即该承诺的锁）。
 */
class AiPropertiesContextGuardTest {

	private static AiProperties props() {
		return new AiProperties();
	}

	/** 默认配置必须能过闸（防"加了校验把默认值判死"）。 */
	@Test
	void defaultsPass() {
		AiProperties properties = props();
		assertThatCode(properties::verifyContext).doesNotThrowAnyException();
	}

	/** M-1：chunk-tokens 必须小于 reserve-tokens（摘要请求自身不越窗）。 */
	@Test
	void chunkTokensMustBeSmallerThanReserve() {
		AiProperties properties = props();
		properties.getContext().setChunkTokens(properties.getContext().getReserveTokens());

		assertThatThrownBy(properties::verifyContext).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("chunk-tokens")
			.hasMessageContaining("reserve-tokens");
	}

	/** 边界：chunk = reserve − 1 合法（判据是严格小于，不是 ≤）。 */
	@Test
	void chunkJustBelowReserveIsAccepted() {
		AiProperties properties = props();
		properties.getContext().setChunkTokens(properties.getContext().getReserveTokens() - 1);

		assertThatCode(properties::verifyContext).doesNotThrowAnyException();
	}

	/** reserve ≥ limit：分母被钳到 1 ⇒ 每轮都判已满。 */
	@Test
	void reserveMustBeSmallerThanLimit() {
		AiProperties properties = props();
		properties.getContext().setReserveTokens(properties.getContext().getLimitTokens());

		assertThatThrownBy(properties::verifyContext).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("reserve-tokens");
	}

	/** 阈值倒挂：L2 比 L1 先到 ⇒ 最轻的一级永远不触发。 */
	@Test
	void thresholdsMustBeStrictlyIncreasing() {
		AiProperties properties = props();
		properties.getContext().setL2Percent(properties.getContext().getL1Percent());

		assertThatThrownBy(properties::verifyContext).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("l1/l2/l3-percent");
	}

	/** 滞回高于触发线 ⇒ 压缩后立刻满足再次触发（抖动链）。 */
	@Test
	void hysteresisMustNotExceedL1() {
		AiProperties properties = props();
		properties.getContext().setHysteresisPercent(properties.getContext().getL1Percent() + 1);

		assertThatThrownBy(properties::verifyContext).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("hysteresis-percent");
	}

	/** 熔断阈值 0：第一次失败即永久熔断。 */
	@Test
	void fuseThresholdMustBePositive() {
		AiProperties properties = props();
		properties.getContext().setFuseThreshold(0);

		assertThatThrownBy(properties::verifyContext).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("fuse-threshold");
	}

	/** 摘要超时 0：摘要永不超时（熔断判据失去输入）。 */
	@Test
	void summaryTimeoutMustBePositive() {
		AiProperties properties = props();
		properties.getContext().setSummaryTimeout(Duration.ZERO);

		assertThatThrownBy(properties::verifyContext).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("summary-timeout");
	}

}
