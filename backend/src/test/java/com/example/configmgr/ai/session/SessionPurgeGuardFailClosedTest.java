package com.example.configmgr.ai.session;

import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.memory.RedisChatMemoryRepository;
import com.example.configmgr.common.AiStateUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 守卫读失败必须 <b>fail-closed</b>：销毁场景下"守卫状态不可知"不等于"没装守卫"。
 *
 * <p>证红：把 {@link SessionPurgeGuard#isArmed(String)} 的 catch 改回 {@code return false}，
 * 两条用例都会红（读失败被当成"可以写"，记忆/历史就会在销毁后复活）。
 */
class SessionPurgeGuardFailClosedTest {

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	@Test
	void readFailureThrowsInsteadOfPretendingNotArmed() {
		when(this.redis.hasKey(anyString())).thenThrow(new IllegalStateException("Redis 抖动"));
		SessionPurgeGuard guard = new SessionPurgeGuard(this.redis);

		assertThatThrownBy(() -> guard.isArmed("s-1"))
			.as("销毁场景：读不出来必须拒绝写回，不能按未装守卫放行")
			.isInstanceOf(AiStateUnavailableException.class);
	}

	@Test
	void memorySaveAllDoesNotWriteWhenGuardStateIsUnknown() {
		when(this.redis.hasKey(anyString())).thenThrow(new IllegalStateException("Redis 抖动"));
		@SuppressWarnings("unchecked")
		org.springframework.data.redis.core.ValueOperations<String, String> values = mock(
				org.springframework.data.redis.core.ValueOperations.class);
		when(this.redis.opsForValue()).thenReturn(values);
		SessionPurgeGuard guard = new SessionPurgeGuard(this.redis);
		RedisChatMemoryRepository repository = new RedisChatMemoryRepository(this.redis,
				new MessageJsonCodec(new ObjectMapper()), Duration.ofHours(24), guard);

		assertThatThrownBy(() -> repository.saveAll("s-1", List.of(new UserMessage("收盘写回"))))
			.as("守卫状态不可知时不得写回记忆")
			.isInstanceOf(AiStateUnavailableException.class);
		// 守卫在读失败后立刻抛出，后续任何 Redis 写口都不应被触达（写入路径已中止）
		verifyNoInteractions(values);
	}

	@Test
	void blankSessionIsNotArmedAndTrueMeansArmed() {
		when(this.redis.hasKey("ai:purge:guard:s-1")).thenReturn(Boolean.TRUE);
		SessionPurgeGuard guard = new SessionPurgeGuard(this.redis);

		assertThat(guard.isArmed(" ")).isFalse();
		assertThat(guard.isArmed("s-1")).isTrue();
	}

	@Test
	void pendingKeepsArmedAndRetentionCannotBeStolenByOldPurgeId() {
		PurgeFakeRedis fake = new PurgeFakeRedis();
		SessionPurgeGuard guard = new SessionPurgeGuard(fake.redis);
		fake.strings.put(SessionPurgeGuard.PENDING_PREFIX + "s-1", "{\"purgeId\":\"p-new\"}");

		assertThat(guard.isArmed("s-1")).as("guard key 不存在时 pending 仍使其 armed").isTrue();

		guard.arm("s-1", "p-new|tester", Duration.ofHours(24));
		assertThat(guard.retainOrArmIfOwner("s-1", "p-old", "old", Duration.ofHours(1)))
			.as("旧 purgeId 不得改新守卫").isFalse();
		assertThat(fake.strings.get("ai:purge:guard:s-1")).isEqualTo("p-new|tester");
		assertThat(guard.retainOrArmIfOwner("s-1", "p-new", "tester", Duration.ofHours(1))).isTrue();
		assertThat(fake.ttls.get("ai:purge:guard:s-1")).isEqualTo(Duration.ofHours(1));
	}

	@Test
	void disarmOnlyRemovesGuardOwnedBySamePurgeId() {
		PurgeFakeRedis fake = new PurgeFakeRedis();
		SessionPurgeGuard guard = new SessionPurgeGuard(fake.redis);
		guard.arm("s-1", "p-new|tester", Duration.ofHours(1));

		assertThat(guard.disarmQuietly("s-1", "p-old")).as("旧 purgeId 不得删新守卫").isFalse();
		assertThat(fake.strings).as("他主守卫必须保留").containsKey(SessionPurgeGuard.KEY_PREFIX + "s-1");

		assertThat(guard.disarmQuietly("s-1", "p-new")).isTrue();
		assertThat(fake.strings).doesNotContainKey(SessionPurgeGuard.KEY_PREFIX + "s-1");
	}

}
