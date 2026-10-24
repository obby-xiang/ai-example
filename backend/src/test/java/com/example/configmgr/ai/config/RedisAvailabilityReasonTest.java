package com.example.configmgr.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * A10 R-11 残留收口：Redis 探活失败的 503 用户文案不得拼异常类名与连接地址，
 * <b>503 响应体里的诊断子对象也不得带 {@code lastError}</b>。
 *
 * <p>诊断不丢：{@code describe()}（仅 {@code /health} 运维面）仍保留原始类名与地址；
 * 503 体走 {@code describeForResponse()}，只留 available/probes/failures 等安全事实。
 */
class RedisAvailabilityReasonTest {

	@SuppressWarnings("unchecked")
	@Test
	void reasonIsUserFacingWhileLastErrorKeepsDiagnostics() {
		StringRedisTemplate template = mock(StringRedisTemplate.class);
		doThrow(new RedisConnectionFailureException("Unable to connect to Redis server: 127.0.0.1:6379"))
				.when(template).execute(any(RedisCallback.class));
		RedisAvailability availability = new RedisAvailability(template);

		assertThat(availability.isAvailable()).isFalse();

		assertThat(availability.reason())
				.as("用户文案：短句、可操作、不含类名/地址/SSE 等内部实现词")
				.isEqualTo("AI 服务暂不可用，请稍后重试。")
				.doesNotContain("Redis")
				.doesNotContain("127.0.0.1")
				.doesNotContain("RedisConnectionFailureException");
		assertThat((String) availability.describe().get("lastError"))
				.as("诊断字段保留原始异常与地址")
				.contains("RedisConnectionFailureException")
				.contains("127.0.0.1:6379");
	}

	@SuppressWarnings("unchecked")
	@Test
	void responseBodyDiagnosticsDropLastErrorButKeepSafeFacts() {
		StringRedisTemplate template = mock(StringRedisTemplate.class);
		doThrow(new RedisConnectionFailureException("Unable to connect to Redis server: 127.0.0.1:6379"))
				.when(template).execute(any(RedisCallback.class));
		RedisAvailability availability = new RedisAvailability(template);
		assertThat(availability.isAvailable()).isFalse();

		Map<String, Object> body = availability.describeForResponse();

		assertThat(body)
				.as("503 body 只留安全事实（判据、探活次数、失败次数、时间），异常类名/地址只进日志与 /health")
				.containsKey("available")
				.containsKey("probes")
				.containsKey("failures")
				.containsKey("checkedAgoMs")
				.doesNotContainKey("lastError");
		assertThat(String.valueOf(body))
				.doesNotContain("RedisConnectionFailureException")
				.doesNotContain("127.0.0.1:6379");
	}
}
