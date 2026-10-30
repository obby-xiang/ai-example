package com.example.configmgr.ai.session;

import com.example.configmgr.ai.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>登记不确认 = 不可用，不是"创建成功"</b>（发号入口不得 200 返回一个未登记的会话）。
 *
 * <h2>缺陷形态</h2>
 * 会话登记原先只回一个 boolean（{@code true = 已存在}），Redis 异常被 catch 成
 * {@code false} —— 与"首次登记成功"<b>逐字同义</b>。发号入口因此把"Redis 不可用、记录根本没写"
 * 读成"新会话已创建"，回 200 与一个只有前端记得的号码：该会话在本实例之外不存在，
 * 后续所有按 sessionId 的读写（记忆窗口、会话归属、并发门）都落在空处。
 *
 * <p>本用例只钉一条：登记无法确认时必须给出独立的结局（{@link AiSessionService.Registration#UNAVAILABLE}），
 * 让发号入口有办法拒绝——而不是把它当成创建成功。
 */
class AiSessionServiceRegistrationUnavailableTest {

	private static final String SESSION = "1700000000123";

	@Test
	void anUnavailableRedisYieldsUnavailableInsteadOfACreatedSession() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		@SuppressWarnings("unchecked")
		ValueOperations<String, String> values = mock(ValueOperations.class);
		when(redis.opsForValue()).thenReturn(values);
		when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
			.thenThrow(new IllegalStateException("Redis 不可用（用例）"));
		AiSessionService sessions = new AiSessionService(redis, new ObjectMapper(), new AiProperties());

		AiSessionService.Registration result = sessions.register(SESSION, 1L, 101L);

		assertThat(result).as("记录没有写成 ⇒ 不得报 CREATED（发号入口据此返回不可用）")
			.isEqualTo(AiSessionService.Registration.UNAVAILABLE);
	}

}
