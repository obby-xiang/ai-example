package com.example.configmgr.ai.memory;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code saveAll} 空列表分支 + 旧键迁移的真 Redis 契约（fix round 1）。
 *
 * <p>空（或 null）列表 = "清空该会话记忆"，委托 {@code deleteByConversationId}：
 * tagged 单键 DEL + legacy 单键 DEL + 索引单键 SREM；不再做「数据键 + 全局索引」两键 Lua。
 *
 * <p>真 Redis 用例使用<b>独立 DB 9</b>（`LettuceConnectionFactory#setDatabase(9)`），
 * 只清理本用例的随机 cid 键，<b>不碰开发库 DB 0</b>。
 */
class RedisChatMemoryRepositorySaveAllEmptyTest {

	private static final String IDS_KEY = "chat:mem:__ids__";

	private static final Duration TTL = Duration.ofHours(6);

	@Test
	void emptySaveAllClearsTaggedLegacyAndIndex() {
		FakeMemoryRedis fake = new FakeMemoryRedis();
		RedisChatMemoryRepository repository = new RedisChatMemoryRepository(fake.redis,
				new MessageJsonCodec(new ObjectMapper()), TTL);
		fake.lists.put("chat:mem:{s-1}", new java.util.concurrent.CopyOnWriteArrayList<>(List.of("tagged")));
		fake.lists.put("chat:mem:s-1", new java.util.concurrent.CopyOnWriteArrayList<>(List.of("legacy")));
		fake.sets.computeIfAbsent(IDS_KEY, ignored -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add("s-1");

		repository.saveAll("s-1", List.of());

		assertFalse(fake.hasKey("chat:mem:{s-1}"));
		assertFalse(fake.hasKey("chat:mem:s-1"));
		assertTrue(fake.set(IDS_KEY).isEmpty());
		assertEquals(1, fake.scriptCalls.get(), "清空是一条同槽脚本（删数据 + 推进代次/版本 + 续 TTL）");
		assertEquals(List.of("chat:mem:{s-1}", "chat:mem:s-1"), fake.deletedKeys,
				"数据键（脚本内 DEL）+ legacy（单键 DEL）；计数器保留并推进");
	}

	@Test
	void nullSaveAllClearsBothShapes() {
		FakeMemoryRedis fake = new FakeMemoryRedis();
		RedisChatMemoryRepository repository = new RedisChatMemoryRepository(fake.redis,
				new MessageJsonCodec(new ObjectMapper()), TTL);
		fake.lists.put("chat:mem:{s-1}", new java.util.concurrent.CopyOnWriteArrayList<>(List.of("tagged")));
		fake.lists.put("chat:mem:s-1", new java.util.concurrent.CopyOnWriteArrayList<>(List.of("legacy")));

		repository.saveAll("s-1", null);

		assertFalse(fake.hasKey("chat:mem:{s-1}"));
		assertFalse(fake.hasKey("chat:mem:s-1"));
		assertEquals(1, fake.scriptCalls.get(), "清空走一条同槽脚本（删数据 + 推进代次/版本）");
	}

	// ── 真 Redis 行为实证：独立 DB 9（不可用时跳过） ──────────────────────────

	// 端点默认同本机 Memurai（127.0.0.1:6379）；可被系统属性覆盖（-Dtest.redis.host / -Dtest.redis.port）。
	// 库号固定 DB 9，避免清理开发库 DB 0 的索引/会话键。
	private static final String REDIS_HOST = System.getProperty("test.redis.host", "127.0.0.1");

	private static final int REDIS_PORT = Integer.parseInt(System.getProperty("test.redis.port", "6379"));

	private static final int REDIS_DB = Integer.parseInt(System.getProperty("test.redis.db", "9"));

	private LettuceConnectionFactory connectionFactory;

	private StringRedisTemplate template;

	private final String conversationId = "o-r3-" + UUID.randomUUID();

	@AfterEach
	void cleanup() {
		if (this.template != null) {
			try {
				this.template.delete("chat:mem:{" + this.conversationId + "}");
				this.template.delete("chat:mem:" + this.conversationId);
				this.template.opsForSet().remove(IDS_KEY, this.conversationId);
			}
			catch (Exception ignore) {
				// 不可达态：用例已被 assumeTrue 跳过，清理同样无从执行 —— 不得因此把 SKIP 变成 ERROR
			}
			this.connectionFactory.destroy();
		}
	}

	@Test
	void emptySaveAllOnRealRedisRemovesBothShapesAndIndex() {
		Assumptions.assumeTrue(connect(),
				"Redis 不可达（" + REDIS_HOST + ":" + REDIS_PORT + "）或 DB " + REDIS_DB + " 不可用，跳过真 Redis 实证");
		MessageJsonCodec codec = new MessageJsonCodec(new ObjectMapper());
		RedisChatMemoryRepository repository = new RedisChatMemoryRepository(this.template, codec, TTL);
		String taggedKey = "chat:mem:{" + this.conversationId + "}";
		String legacyKey = "chat:mem:" + this.conversationId;

		// 前置：tagged 写入成功、带 TTL、legacy 尚不存在
		repository.saveAll(this.conversationId, List.of(new UserMessage("hello")));
		assertTrue(Boolean.TRUE.equals(this.template.hasKey(taggedKey)), "tagged 数据键应存在");
		Long ttl = this.template.getExpire(taggedKey);
		assertNotNull(ttl);
		assertTrue(ttl > 0 && ttl <= TTL.toSeconds(), "RENAME 后 final TTL 应为配置值，实际 " + ttl);
		assertFalse(Boolean.TRUE.equals(this.template.hasKey(legacyKey)), "新写入不应产生 legacy 键");
		assertTrue(repository.isConversationIndexed(this.conversationId));

		// 再铺一条 legacy，验证空列表会把两种键形一起清掉
		this.template.opsForList().rightPushAll(legacyKey, codec.serializeAll(List.of(new UserMessage("legacy"))));

		repository.saveAll(this.conversationId, List.of());
		assertFalse(Boolean.TRUE.equals(this.template.hasKey(taggedKey)), "tagged 应删除");
		assertFalse(Boolean.TRUE.equals(this.template.hasKey(legacyKey)), "legacy 应删除");
		assertFalse(Boolean.TRUE.equals(this.template.opsForSet().isMember(IDS_KEY, this.conversationId)),
				"索引成员应被单键 SREM");
		assertTrue(repository.findByConversationId(this.conversationId).isEmpty(), "历史应读空");
		assertEquals(List.of(), repository.findByConversationId(this.conversationId));
	}

	@Test
	void legacyReadFallbackAndNaturalMigrationOnRealRedis() {
		Assumptions.assumeTrue(connect(),
				"Redis 不可达（" + REDIS_HOST + ":" + REDIS_PORT + "）或 DB " + REDIS_DB + " 不可用，跳过真 Redis 实证");
		MessageJsonCodec codec = new MessageJsonCodec(new ObjectMapper());
		RedisChatMemoryRepository repository = new RedisChatMemoryRepository(this.template, codec, TTL);
		String taggedKey = "chat:mem:{" + this.conversationId + "}";
		String legacyKey = "chat:mem:" + this.conversationId;

		this.template.opsForList().rightPushAll(legacyKey, codec.serializeAll(List.of(new UserMessage("legacy"))));
		this.template.opsForSet().add(IDS_KEY, this.conversationId);

		assertEquals(List.of("legacy"), texts(repository.findByConversationId(this.conversationId)),
				"tagged 缺失时应回落 legacy");
		assertTrue(repository.isConversationIndexed(this.conversationId), "legacy 存活应判为在索引");

		repository.saveAll(this.conversationId, List.of(new UserMessage("新")));
		assertTrue(Boolean.TRUE.equals(this.template.hasKey(taggedKey)));
		assertFalse(Boolean.TRUE.equals(this.template.hasKey(legacyKey)), "保存成功后应删除 legacy");
		assertEquals(List.of("新"), texts(repository.findByConversationId(this.conversationId)));
		Long ttl = this.template.getExpire(taggedKey);
		assertNotNull(ttl);
		assertTrue(ttl > 0 && ttl <= TTL.toSeconds());
	}

	private boolean connect() {
		this.connectionFactory = new LettuceConnectionFactory(REDIS_HOST, REDIS_PORT);
		this.connectionFactory.setDatabase(REDIS_DB);
		this.connectionFactory.afterPropertiesSet();
		this.template = new StringRedisTemplate(this.connectionFactory);
		try {
			assertEquals(REDIS_DB, this.connectionFactory.getDatabase());
			return "PONG".equalsIgnoreCase(this.template.getConnectionFactory().getConnection().ping());
		}
		catch (Exception ex) {
			return false;
		}
	}

	private static List<String> texts(List<org.springframework.ai.chat.messages.Message> messages) {
		return messages.stream().map(org.springframework.ai.chat.messages.Message::getText).toList();
	}

}
