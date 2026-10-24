package com.example.configmgr.ai.memory;

import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code deleteByConversationId} 的 Cluster 安全契约（R1-04 + fix round 1）。
 *
 * <p>删除不再做「数据键 + 全局索引」两键 Lua（Cluster 下必然 {@code CROSSSLOT}），而是：
 * <b>tagged 单键 DEL → legacy 单键 DEL → 索引单键 SREM</b>。三条都是单键命令，任何一条都不跨槽；
 * SREM 让索引在删除时立即收敛，避免 {@code chat:mem:__ids__} 随会话数无界增长。
 */
class RedisChatMemoryRepositoryDeleteTest {

	private static final String CID = "s-1";

	private static final String IDS = RedisChatMemoryRepository.CONVERSATION_IDS_KEY;

	private static final String LEGACY_KEY = "chat:mem:s-1";

	private final FakeMemoryRedis fake = new FakeMemoryRedis();

	private final RedisChatMemoryRepository repository = new RedisChatMemoryRepository(this.fake.redis,
			new MessageJsonCodec(new ObjectMapper()), Duration.ofHours(6));

	@Test
	void deleteClearsTaggedLegacyAndIndexWithSingleKeyCommands() {
		this.fake.enableClusterMode();
		this.fake.lists.put(this.repository.keyFor(CID),
				new java.util.concurrent.CopyOnWriteArrayList<>(List.of("tagged")));
		this.fake.lists.put(LEGACY_KEY, new java.util.concurrent.CopyOnWriteArrayList<>(List.of("legacy")));
		this.fake.sets.computeIfAbsent(IDS, ignored -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(CID);

		this.repository.deleteByConversationId(CID);

		assertFalse(this.fake.hasKey(this.repository.keyFor(CID)), "tagged 键应删除");
		assertFalse(this.fake.hasKey(LEGACY_KEY), "legacy 键应删除");
		assertTrue(this.fake.set(IDS).isEmpty(), "索引成员应被 SREM");
		assertEquals(0, this.fake.scriptCalls.get(), "删除不得发 Lua（含跨槽两键脚本）");
		assertEquals(List.of(this.repository.keyFor(CID), LEGACY_KEY), this.fake.deletedKeys,
				"只允许 tagged / legacy 两条单键 DEL");
		assertEquals(1, this.fake.indexRemoves.get(), "索引只做一次单键 SREM");
	}

	@Test
	void readableKeyReturnsTheKeyThatActuallyExists() {
		assertEquals(this.repository.keyFor(CID), this.repository.readableKeyFor(CID),
				"两者都不存在时回落规范 tagged 键");

		this.fake.lists.put(LEGACY_KEY, new java.util.concurrent.CopyOnWriteArrayList<>(List.of("legacy")));
		assertEquals(LEGACY_KEY, this.repository.readableKeyFor(CID), "仅 legacy 时必须报 legacy");

		this.fake.lists.put(this.repository.keyFor(CID),
				new java.util.concurrent.CopyOnWriteArrayList<>(List.of("tagged")));
		assertEquals(this.repository.keyFor(CID), this.repository.readableKeyFor(CID),
				"tagged 存活时优先 tagged");
	}

	@Test
	void dataKeyCarriesSessionHashTag() {
		assertEquals("chat:mem:{s-1}", this.repository.keyFor(CID));
	}

	@Test
	void blankConversationIdIsRejectedWithoutTouchingRedis() {
		assertThrows(IllegalArgumentException.class, () -> this.repository.deleteByConversationId(""));
		assertThrows(IllegalArgumentException.class, () -> this.repository.deleteByConversationId("   "));
		assertThrows(IllegalArgumentException.class, () -> this.repository.deleteByConversationId(null));
		assertEquals(0, this.fake.deletes.get());
		assertEquals(0, this.fake.indexRemoves.get());
	}

}
