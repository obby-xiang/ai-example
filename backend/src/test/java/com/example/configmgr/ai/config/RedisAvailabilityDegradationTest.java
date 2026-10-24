package com.example.configmgr.ai.config;

import com.example.configmgr.ai.observe.RedisDegradation;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Redis 降级计数在健康面的暴露口径（Cluster / 5.0 可观测批）。
 *
 * <p>只进 {@code GET /api/ai/health} 的 {@code redis.degradations}（运维面），
 * <b>不进 503 响应体</b>（{@code describeForResponse()} 的键面与判定都不改）。
 */
class RedisAvailabilityDegradationTest {

	@Test
	void describeExposesTheUnifiedDegradationSnapshot() {
		RedisAvailability availability = new RedisAvailability(mock(StringRedisTemplate.class));

		Map<String, Object> described = availability.describe();

		assertThat(described).containsKey("degradations");
		@SuppressWarnings("unchecked")
		Map<String, Object> degradations = (Map<String, Object>) described.get("degradations");
		assertThat(degradations).containsKeys(RedisDegradation.A1_FENCE_READ,
				RedisDegradation.A3_FRAME_RANGE_READ, RedisDegradation.A4_FRAME_TAIL_READ,
				RedisDegradation.A5_CANCEL_READ, RedisDegradation.A6_DECISION_GUARD_READ,
				RedisDegradation.L139_RENEW_KEY, RedisDegradation.L139_RENEW_NOT_ATTEMPTED,
				RedisDegradation.B3_ATTACHMENT_REGISTER, RedisDegradation.B3_ATTACHMENT_READ,
				RedisDegradation.B3_ATTACHMENT_CONSUME, RedisDegradation.B3_ATTACHMENT_TOMBSTONE);
	}

	@Test
	void responseBodyStaysUnchanged() {
		RedisAvailability availability = new RedisAvailability(mock(StringRedisTemplate.class));

		assertThat(availability.describeForResponse()).as("503 判定与响应体键面不改")
			.containsOnlyKeys("available", "probes", "failures", "checkedAgoMs", "positiveTtlMs", "negativeTtlMs")
			.doesNotContainKey("lastError")
			.doesNotContainKey("degradations");
	}

}
