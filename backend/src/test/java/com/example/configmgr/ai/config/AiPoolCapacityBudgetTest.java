package com.example.configmgr.ai.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 启动期容量联动校验的单测（T3-5 定值 2026-10-09）。
 *
 * <p>
 * 判据两条：<b>满足 ⇒ 不产生 WARN</b>；<b>不满足 ⇒ 恰好一条 WARN 且不 fail-fast</b>（校验只告警，
 * 不阻断启动 —— 低核数部署下后果是 boundedElastic 排队而非正确性破坏）。
 * 默认参数取 T3-5 定值（挂起专用池 20 / 投递池 8）：16 核机 48 ≤ 160 成立，
 * 与压测证据文档 §S1.5 同口径；4 核机 10 × 4 = 40 &lt; 48 不成立（同文档 §【待裁决】3）。
 */
class AiPoolCapacityBudgetTest {

	private ListAppender<ILoggingEvent> appender;

	@BeforeEach
	void attachAppender() {
		this.appender = new ListAppender<>();
		this.appender.start();
		((Logger) LoggerFactory.getLogger(AiPoolCapacityBudget.class)).addAppender(this.appender);
	}

	@AfterEach
	void detachAppender() {
		((Logger) LoggerFactory.getLogger(AiPoolCapacityBudget.class)).detachAppender(this.appender);
	}

	@Test
	void satisfiedBudgetProducesNoWarning() {
		assertThat(AiPoolCapacityBudget.warnMessage(20, 8, 16))
				.as("20 × 2 + 8 = 48 ≤ 10 × 16 = 160（T3-5 实测口径）⇒ 无告警文案")
				.isNull();

		AiPoolCapacityBudget.verify(20, 8, 16);
		assertThat(warnings()).as("满足 ⇒ 一条 WARN 也不打").isEmpty();
	}

	@Test
	void budgetSatisfiedExactlyAtTheBoundaryProducesNoWarning() {
		// 边界：要求值 == 容量（不是 <）时仍判满足 —— 公式是 ≤，不是 <
		assertThat(AiPoolCapacityBudget.requiredThreads(17, 6)).isEqualTo(40);
		assertThat(AiPoolCapacityBudget.warnMessage(17, 6, 4)).as("40 ≤ 10 × 4 = 40 成立").isNull();
		assertThat(warnings()).isEmpty();
	}

	@Test
	void unsatisfiedBudgetEmitsExactlyOneWarningWithMeasuredRequiredAndMinProcessors() {
		String message = AiPoolCapacityBudget.warnMessage(20, 8, 4);

		assertThat(message)
				.as("WARN 文案必须自带三项可裁决数据：实测值 / 要求值 / 建议最低核数")
				.contains("实测 CPU 核数 = 4")
				.contains("boundedElastic 缺省容量实测值 = 40")
				.contains("要求值 = 挂起专用池 20 × 2 + 投递池 8 = 48")
				.contains("建议最低核数 = ceil(要求值 / 10) = 5");
		assertThat(AiPoolCapacityBudget.minRequiredProcessors(20, 8)).isEqualTo(5);

		assertThatCode(() -> AiPoolCapacityBudget.verify(20, 8, 4))
				.as("不满足只告警，不 fail-fast（不抛异常）")
				.doesNotThrowAnyException();
		assertThat(warnings()).extracting(ILoggingEvent::getFormattedMessage)
				.as("不满足 ⇒ 恰好一条 WARN")
				.containsExactly(message);
	}

	@Test
	void warningIsWarnLevelAndNamesTheTwoConfigKeysToTune() {
		AiPoolCapacityBudget.verify(20, 8, 1);

		List<ILoggingEvent> events = warnings();
		assertThat(events).hasSize(1);
		assertThat(events.get(0).getLevel())
				.as("容量不足属性能降级：WARN 级（后端日志零 ERROR 门禁不受影响）")
				.isEqualTo(Level.WARN);
		assertThat(events.get(0).getFormattedMessage())
				.as("处置建议必须指向真实配置键（改键名时本用例先红）")
				.contains("app.ai.suspend.pool-size")
				.contains("app.ai.sse.delivery-pool-size");
	}

	@Test
	void boundedElasticCapacityMatchesTheDerivationOnThisMachine() {
		int capacity = AiPoolCapacityBudget.boundedElasticCapacity();
		assertThat(capacity)
				.as("反射实读 reactor 常量；本机与推导口径 10 × availableProcessors() 等价（§S1.5 同源）")
				.isEqualTo(AiPoolCapacityBudget.derivedBoundedElasticCapacity(
						Runtime.getRuntime().availableProcessors()));
		assertThat(capacity).as("reactor 缺省容量恒为正").isPositive();
	}

	private List<ILoggingEvent> warnings() {
		return this.appender.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
	}

}
