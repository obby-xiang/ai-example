package com.example.configmgr.ai.memory;

import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code deleteByConversationId} 的原子性契约（R-131 / R-133 共同消解）。
 *
 * <p>删除会话记忆原先拆成两次往返（{@code DEL} 数据键 + {@code SREM} 索引）。两步之间：
 * <ul>
 * <li><b>崩溃</b> → 索引里留下 stale 成员（R-131）；</li>
 * <li><b>并发交错</b> → 同一会话的 {@code saveAll} 的 {@code SADD} 落在两步之间，
 * 被迟到 {@code SREM} 抹掉，存活会话从索引消失且无兜底（R-133）。</li>
 * </ul>
 * 两条的根因同一个：非原子。解法是单次 Lua 脚本把 {@code DEL} + {@code SREM} 合成一次调用，
 * 本测试锁定三件事：只发一次 {@code execute}、键序与 ARGV 正确、不再出现拆分调用。
 */
class RedisChatMemoryRepositoryDeleteTest {

	private static final String DATA_KEY = "chat:mem:s-1";

	private static final String IDS_KEY = "chat:mem:__ids__";

	@Test
	@SuppressWarnings("unchecked")
	void deleteIssuesSingleAtomicScript() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		when(redis.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L);
		RedisChatMemoryRepository repository = repository(redis);

		repository.deleteByConversationId("s-1");

		ArgumentCaptor<RedisScript<?>> scriptCaptor = ArgumentCaptor.forClass(RedisScript.class);
		ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
		verify(redis).execute(scriptCaptor.capture(), keysCaptor.capture(), eq("s-1"));
		assertEquals(List.of(DATA_KEY, IDS_KEY), keysCaptor.getValue(), "键序必须是 [数据键, 会话 id 索引键]");
		// 拆分调用一律不许再出现：出现即意味着原子性丢失
		verify(redis, never()).delete(anyString());
		verify(redis, never()).opsForSet();
	}

	@Test
	@SuppressWarnings("unchecked")
	void deleteScriptContainsDelAndSrem() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		when(redis.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L);
		RedisChatMemoryRepository repository = repository(redis);

		repository.deleteByConversationId("s-1");

		ArgumentCaptor<RedisScript<?>> scriptCaptor = ArgumentCaptor.forClass(RedisScript.class);
		verify(redis).execute(scriptCaptor.capture(), anyList(), eq("s-1"));
		String body = scriptCaptor.getValue().getScriptAsString();
		assertTrue(body.matches("(?s).*redis\\.call\\('DEL'.*"), "脚本必须含数据键 DEL 段，实际为：" + body);
		assertTrue(body.matches("(?s).*redis\\.call\\('SREM'.*"), "脚本必须含索引 SREM 段，实际为：" + body);
	}

	private static RedisChatMemoryRepository repository(StringRedisTemplate redis) {
		return new RedisChatMemoryRepository(redis, new MessageJsonCodec(new ObjectMapper()), Duration.ofHours(6));
	}

}
