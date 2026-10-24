package com.example.configmgr.ai.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R1-09 I-1：{@code zombie-age} 的启动校验必须把"合法静默 + 余量"落成硬约束。
 *
 * <p>
 * 只校验 {@code zombie-age > max(exec-timeout, inter-event-timeout)} 时，运维可以把阈值配成
 * 121s（工具上限 120s）—— 工具刚跑满上限就可能被判死接管（同一轮双执行）。本组按本仓
 * {@code AiProperties*GuardTest} 的既有写法，锁四种边界：
 * <ul>
 * <li>恰好等于 {@code max + margin} 合法；</li>
 * <li>比 {@code max + margin} 少 1ms 非法；</li>
 * <li>只等于 {@code max}（余量被抹平）非法；</li>
 * <li>{@code exec-timeout}/{@code inter-event-timeout} 为 0 或负值非法（0 = 永不超时，合法静默无上界）。</li>
 * </ul>
 */
class AiResumeZombieAgeGuardTest {

	private final AiProperties properties = new AiProperties();

	@Test
	void shippedDefaultsPassTheGuard() {
		assertThatCode(this.properties::verifyZombieAge).doesNotThrowAnyException();
	}

	@Test
	void exactlyFloorPlusMarginIsAccepted() {
		this.properties.getTool().setExecTimeout(Duration.ofSeconds(120));
		this.properties.getResilience().setInterEventTimeout(Duration.ofSeconds(90));
		this.properties.getResume().setZombieMargin(Duration.ofSeconds(60));
		this.properties.getResume().setZombieAge(Duration.ofSeconds(180));

		assertThatCode(this.properties::verifyZombieAge).doesNotThrowAnyException();
	}

	@Test
	void oneMillisecondBelowFloorPlusMarginIsRejected() {
		this.properties.getTool().setExecTimeout(Duration.ofSeconds(120));
		this.properties.getResilience().setInterEventTimeout(Duration.ofSeconds(90));
		this.properties.getResume().setZombieMargin(Duration.ofSeconds(60));
		this.properties.getResume().setZombieAge(Duration.ofMillis(179_999L));

		assertThatThrownBy(this.properties::verifyZombieAge)
			.as("只比 max+margin 少 1ms 也不允许：余量不是建议值")
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("zombie-age")
			.hasMessageContaining("zombie-margin");
	}

	@Test
	void equalToTheLegalSilenceFloorIsRejectedBecauseMarginIsMissing() {
		this.properties.getTool().setExecTimeout(Duration.ofSeconds(120));
		this.properties.getResilience().setInterEventTimeout(Duration.ofSeconds(90));
		this.properties.getResume().setZombieMargin(Duration.ofSeconds(60));
		this.properties.getResume().setZombieAge(Duration.ofSeconds(120));

		assertThatThrownBy(this.properties::verifyZombieAge)
			.as("等于 max(exec, inter) 时余量为 0，工具刚跑满上限就可能被判死")
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void aLargerMarginRaisesTheEffectiveFloor() {
		this.properties.getTool().setExecTimeout(Duration.ofSeconds(120));
		this.properties.getResilience().setInterEventTimeout(Duration.ofSeconds(90));
		this.properties.getResume().setZombieMargin(Duration.ofSeconds(120));
		this.properties.getResume().setZombieAge(Duration.ofSeconds(180));

		assertThatThrownBy(this.properties::verifyZombieAge)
			.as("余量调大后 180s 不再满足 120s + 120s")
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void zeroExecTimeoutIsRejected() {
		this.properties.getTool().setExecTimeout(Duration.ZERO);

		assertThatThrownBy(this.properties::verifyZombieAge)
			.as("exec-timeout=0 在生产语义里是“无超时”，合法静默没有上界，不能拿它做僵尸基线")
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("exec-timeout");
	}

	@Test
	void zeroInterEventTimeoutIsRejected() {
		this.properties.getResilience().setInterEventTimeout(Duration.ZERO);

		assertThatThrownBy(this.properties::verifyZombieAge)
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("inter-event-timeout");
	}

	@Test
	void negativeTimeoutsAreRejected() {
		this.properties.getTool().setExecTimeout(Duration.ofSeconds(-1));

		assertThatThrownBy(this.properties::verifyZombieAge)
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("exec-timeout");

		this.properties.getTool().setExecTimeout(Duration.ofSeconds(120));
		this.properties.getResilience().setInterEventTimeout(Duration.ofSeconds(-1));

		assertThatThrownBy(this.properties::verifyZombieAge)
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("inter-event-timeout");
	}

	@Test
	void negativeMarginIsRejected() {
		this.properties.getResume().setZombieMargin(Duration.ofSeconds(-1));

		assertThatThrownBy(this.properties::verifyZombieAge)
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("zombie-margin");
	}

}
