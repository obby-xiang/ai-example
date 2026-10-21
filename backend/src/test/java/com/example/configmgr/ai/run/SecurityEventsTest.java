package com.example.configmgr.ai.run;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 安全事件计数（防护批③ B3-T10 的进程内口径）：键面固定、只增不减、口径显式。
 */
class SecurityEventsTest {

	@Test
	void metricsExposeTheFullKeySet() {
		Map<String, Object> metrics = new SecurityEvents().metrics();

		assertThat(metrics).containsKeys("scope", "inputRejected", "inputRedacted", "outputRedacted",
				"outputBlocked", "rateLimited", "contextDowngraded", "payloadTruncated");
		assertThat(metrics).containsEntry("scope", "process");
	}

	@Test
	void countersAreMonotonic() {
		SecurityEvents events = new SecurityEvents();
		events.rateLimited();
		events.rateLimited();
		events.inputRejected();
		events.inputRedacted(3);
		events.outputRedacted(2);
		events.outputBlocked();
		events.contextDowngraded(4);
		events.payloadTruncated();

		Map<String, Object> metrics = events.metrics();
		assertThat(metrics).containsEntry("rateLimited", 2L)
				.containsEntry("inputRejected", 1L)
				.containsEntry("inputRedacted", 3L)
				.containsEntry("outputRedacted", 2L)
				.containsEntry("outputBlocked", 1L)
				.containsEntry("contextDowngraded", 4L)
				.containsEntry("payloadTruncated", 1L);
	}

	@Test
	void nonPositiveDeltaIsIgnored() {
		SecurityEvents events = new SecurityEvents();
		events.inputRedacted(0);
		events.inputRedacted(-5);

		assertThat(events.metrics()).containsEntry("inputRedacted", 0L);
	}

}
