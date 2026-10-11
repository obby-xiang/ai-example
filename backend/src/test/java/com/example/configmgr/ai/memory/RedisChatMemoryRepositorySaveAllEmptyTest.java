package com.example.configmgr.ai.memory;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code saveAll} 空列表分支的原子性契约（o-R3，批 2）。
 *
 * <p>修复前该分支是 {@code delete} 数据键 + {@code SREM} 索引两次独立往返（非原子），
 * 与 {@code deleteByConversationId} Lua 化之前同构同漏洞。可达性（spring-ai 1.1.8 字节码实测 +
 * 本仓代码走查）：
 * <ul>
 * <li>{@code MessageWindowChatMemory.add} 对空入参<b>无短路</b>，永远
 * {@code findByConversationId} → {@code process} → {@code saveAll}；当既有记忆为空且
 * {@code add(id, List.of())} 时 {@code process} 产出空列表，直达本分支；</li>
 * <li>{@code ResilientChatService.convergeMemory} 的 {@code trimKeepingToolPairs} 在
 * "裁剪点起至末尾全是 TOOL 消息"时返回空列表（{@code subList(size, size)}），此时
 * {@code clear()} + {@code add(id, [])} 同样直达本分支（取消/空回复终局 + 全 TOOL 尾可构造）。</li>
 * </ul>
 * 修复 = 空列表分支委托已 Lua 化的 {@code deleteByConversationId}。本测试锁两件事：
 * 空分支只发一次 {@code execute}（Lua），拆分调用（{@code delete}/{@code opsForSet}）不再出现；
 * 真 Redis 上行为不变（数据键删除 + 索引成员移除）。
 */
class RedisChatMemoryRepositorySaveAllEmptyTest {

	private static final String IDS_KEY = "chat:mem:__ids__";

	@Test
	@SuppressWarnings("unchecked")
	void emptySaveAllDelegatesToAtomicScript() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		when(redis.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L);
		RedisChatMemoryRepository repository = repository(redis);

		repository.saveAll("s-1", List.of());

		ArgumentCaptor<RedisScript<?>> scriptCaptor = ArgumentCaptor.forClass(RedisScript.class);
		ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
		verify(redis, times(1)).execute(scriptCaptor.capture(), keysCaptor.capture(), eq("s-1"));
		assertEquals(List.of("chat:mem:s-1", IDS_KEY), keysCaptor.getValue(), "键序必须是 [数据键, 会话 id 索引键]");
		String body = scriptCaptor.getValue().getScriptAsString();
		assertTrue(body.matches("(?s).*redis\\.call\\('DEL'.*"), "脚本必须含数据键 DEL 段，实际为：" + body);
		assertTrue(body.matches("(?s).*redis\\.call\\('SREM'.*"), "脚本必须含索引 SREM 段，实际为：" + body);
		// 拆分调用一律不许再出现：出现即意味着原子性丢失
		verify(redis, never()).delete(anyString());
		verify(redis, never()).opsForSet();
	}

	@Test
	@SuppressWarnings("unchecked")
	void nullSaveAllDelegatesToAtomicScript() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		when(redis.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L);
		RedisChatMemoryRepository repository = repository(redis);

		repository.saveAll("s-1", null);

		verify(redis, times(1)).execute(any(RedisScript.class), anyList(), eq("s-1"));
		verify(redis, never()).delete(anyString());
		verify(redis, never()).opsForSet();
	}

	// ── 真 Redis 行为实证（本机 Memurai 127.0.0.1:6379；不可用时跳过，不算失败） ──

	private LettuceConnectionFactory connectionFactory;

	private StringRedisTemplate template;

	private String conversationId = "o-r3-" + UUID.randomUUID();

	@AfterEach
	void cleanup() {
		if (this.template != null) {
			this.template.delete("chat:mem:" + this.conversationId);
			this.template.opsForSet().remove(IDS_KEY, this.conversationId);
			this.connectionFactory.destroy();
		}
	}

	@Test
	void emptySaveAllOnRealRedisRemovesDataAndIndex() {
		this.connectionFactory = new LettuceConnectionFactory("127.0.0.1", 6379);
		this.connectionFactory.afterPropertiesSet();
		this.template = new StringRedisTemplate(this.connectionFactory);
		String pong = this.template.getConnectionFactory().getConnection().ping();
		Assumptions.assumeTrue("PONG".equalsIgnoreCase(pong), "本机 Redis 不可达（ping=" + pong + "），跳过真 Redis 实证");

		RedisChatMemoryRepository repository = new RedisChatMemoryRepository(this.template,
				new MessageJsonCodec(new ObjectMapper()), Duration.ofHours(6));

		// 前置：写入一条非空记忆，确认索引与数据键都在（证明空分支被测的是"真有一条可清的东西"）
		repository.saveAll(this.conversationId, List.of(new UserMessage("hello")));
		assertTrue(Boolean.TRUE.equals(this.template.hasKey("chat:mem:" + this.conversationId)), "前置：数据键应存在");
		assertTrue(repository.isConversationIndexed(this.conversationId), "前置：索引成员应存在");

		// 行为锁定：空列表覆盖写 = 数据键删除 + 索引成员移除（与修复前行为一致，实现换为原子脚本）
		repository.saveAll(this.conversationId, List.of());
		assertFalse(Boolean.TRUE.equals(this.template.hasKey("chat:mem:" + this.conversationId)), "数据键应被删除");
		assertFalse(repository.isConversationIndexed(this.conversationId), "索引成员应被移除");
	}

	private static RedisChatMemoryRepository repository(StringRedisTemplate redis) {
		return new RedisChatMemoryRepository(redis, new MessageJsonCodec(new ObjectMapper()), Duration.ofHours(6));
	}

}
