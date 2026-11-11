package com.example.configmgr.ai.run;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 安全事件计数（进程内口径）：键面固定、只增不减、口径显式。
 *
 * <p>当前产品面只保留三条计数：输入拒绝、上下文降级、载荷截断。限流与输入/输出脱敏（DLP）
 * 已从产品移除，对应计数/入口也一并删除 —— 本类显式钉住“这些键不存在”，防止旧观测面悄悄复活。
 */
class SecurityEventsTest {

	@Test
	void metricsExposeTheCurrentKeySet() {
		Map<String, Object> metrics = new SecurityEvents().metrics();

		assertThat(metrics).containsOnlyKeys("scope", "inputRejected", "contextDowngraded", "payloadTruncated");
		assertThat(metrics).containsEntry("scope", "process");
		assertThat(metrics).as("限流 / DLP / 输出脱敏已从产品移除")
			.doesNotContainKeys("rateLimited", "inputRedacted", "outputRedacted", "outputBlocked");
	}

	@Test
	void countersAreMonotonic() {
		SecurityEvents events = new SecurityEvents();
		events.inputRejected();
		events.inputRejected();
		events.contextDowngraded(4);
		events.payloadTruncated();

		Map<String, Object> metrics = events.metrics();
		assertThat(metrics).containsEntry("inputRejected", 2L)
				.containsEntry("contextDowngraded", 4L)
				.containsEntry("payloadTruncated", 1L);
	}

	@Test
	void nonPositiveDeltaIsIgnored() {
		SecurityEvents events = new SecurityEvents();
		events.contextDowngraded(0);
		events.contextDowngraded(-5);

		assertThat(events.metrics()).containsEntry("contextDowngraded", 0L);
	}

}
