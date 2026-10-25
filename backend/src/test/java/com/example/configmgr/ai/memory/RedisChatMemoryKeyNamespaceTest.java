package com.example.configmgr.ai.memory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>记忆键命名空间不得互相穿透</b>（会话记忆键修复批）。
 *
 * <h2>缺陷形态</h2>
 * 数据键是带 hash tag 的 {@code chat:mem:{<escapedId>}}，而迁移期 legacy 键是
 * <b>原样 id</b> 拼出来的 {@code chat:mem:<id>}。两者对"特殊 id"会撞成同一个字符串：
 * <ul>
 * <li>{@code sessionId = "{x}"}：tagged = {@code chat:mem:{%7Bx%7D}（不撞），但 legacy =
 * {@code chat:mem:{x}} —— <b>正好是会话 {@code x} 的 tagged 键</b>。旧实现读侧回落 legacy、
 * 写侧成功后删 legacy、删侧删 legacy ⇒ 读/写/删 {@code "{x}"} 都会碰到 {@code x} 的记忆；</li>
 * <li>{@code sessionId = "__ids__"}：legacy = {@code chat:mem:__ids__} —— <b>正好是全局会话索引</b>。
 * 删 {@code "__ids__"} 会把所有会话的索引一起删掉。</li>
 * </ul>
 *
 * <h2>本批口径</h2>
 * legacy 键只在<b>不可能属于其它命名空间</b>时才可用：不以 {@code chat:mem:{} / chat:mem:staging:{} 前缀
 * 出现、也不是全局索引键本身。于是特殊 id 只操作自己的 tagged 键，另一个会话的数据与全局索引一个
 * 字节都不动（代价：这类 id 的历史不做 legacy 迁移 —— 见类注释 {@code 迁移注意}）。
 */
class RedisChatMemoryKeyNamespaceTest {

	/** 被保护的另一会话：{@code "{x}"} 的 legacy 键恰好等于它的 tagged 键。 */
	private static final String VICTIM = "x";

	private static final String BRACED = "{x}";

	private static final String IDS = RedisChatMemoryRepository.CONVERSATION_IDS_KEY;

	private final FakeMemoryRedis fake = new FakeMemoryRedis();

	private final MessageJsonCodec codec = new MessageJsonCodec(new ObjectMapper());

	private final RedisChatMemoryRepository repository = new RedisChatMemoryRepository(this.fake.redis, this.codec,
			Duration.ofHours(6));

	@Test
	void bracedSessionIdReadsNothingFromTheSessionItCollidesWith() {
		seedVictimMemory();

		assertEquals(List.of(), this.repository.findByConversationId(BRACED),
				"sessionId=\"{x}\" 不得读到会话 x 的记忆（legacy 键撞了 x 的 tagged 键）");
		assertEquals(this.repository.keyFor(BRACED), this.repository.readableKeyFor(BRACED),
				"诊断/报告用的真实键不得指向另一个会话的数据键");
	}

	@Test
	void bracedSessionIdDeleteKeepsTheCollidingSessionMemoryAndIndex() {
		seedVictimMemory();

		this.repository.deleteByConversationId(BRACED);

		assertTrue(this.fake.hasKey(tagged(VICTIM)), "会话 x 的记忆键必须保留");
		assertTrue(this.fake.set(IDS).contains(VICTIM), "会话 x 的索引成员必须保留");
		assertFalse(this.fake.deletedKeys.contains(tagged(VICTIM)), "不得对 x 的键发 DEL");
	}

	@Test
	void bracedSessionIdWriteKeepsTheCollidingSessionMemory() {
		List<String> seeded = seedVictimMemory();

		this.repository.saveAll(BRACED, List.of(new UserMessage("mine")));

		assertEquals(seeded, this.fake.list(tagged(VICTIM)),
				"会话 x 的记忆内容必须原样保留（写侧清理 legacy 时不得删到 x 的键）");
		assertFalse(this.fake.list(this.repository.keyFor(BRACED)).isEmpty(), "\"{x}\" 自己的 tagged 键写入成功");
	}

	@Test
	void reservedIndexNameIsNeverTreatedAsConversationMemory() {
		seedVictimMemory();
		this.fake.sets.computeIfAbsent(IDS, ignored -> ConcurrentHashMap.newKeySet()).add(VICTIM);

		this.repository.deleteByConversationId("__ids__");

		assertTrue(this.fake.hasKey(IDS), "sessionId=__ids__ 不得把全局索引键当 legacy 记忆键删掉");
		assertTrue(this.fake.set(IDS).contains(VICTIM), "索引成员必须保留");
	}

	@Test
	void reservedIndexNameWriteNeverDeletesTheGlobalIndex() {
		seedVictimMemory();
		this.fake.sets.computeIfAbsent(IDS, ignored -> ConcurrentHashMap.newKeySet()).add(VICTIM);

		this.repository.saveAll("__ids__", List.of(new UserMessage("index-lookalike")));

		assertTrue(this.fake.hasKey(IDS), "写回后的 legacy 清理不得删掉全局索引键");
		assertTrue(this.fake.set(IDS).contains(VICTIM), "索引成员必须保留");
	}

	@Test
	void ordinaryIdsKeepTheLegacyMigrationPath() {
		// 回归护栏：普通 id 的 legacy 迁移面不得被命名空间守卫一起关掉。
		this.fake.lists.put("chat:mem:s-1", new CopyOnWriteArrayList<>(this.codec.serializeAll(
				List.of(new UserMessage("legacy")))));

		assertEquals("chat:mem:s-1", this.repository.readableKeyFor("s-1"), "仅 legacy 时仍报 legacy");
		assertEquals(1, this.repository.findByConversationId("s-1").size(), "legacy 历史仍可读");

		this.repository.saveAll("s-1", List.of(new UserMessage("fresh")));

		assertFalse(this.fake.hasKey("chat:mem:s-1"), "成功保存后 legacy 键按迁移口径删除");
		assertFalse(this.fake.list(tagged("s-1")).isEmpty(), "新历史写入 tagged 键");
	}

	private List<String> seedVictimMemory() {
		List<String> payload = this.codec.serializeAll(List.of(new UserMessage("victim")));
		this.fake.lists.put(tagged(VICTIM), new CopyOnWriteArrayList<>(payload));
		this.fake.sets.computeIfAbsent(IDS, ignored -> ConcurrentHashMap.newKeySet()).add(VICTIM);
		return payload;
	}

	private String tagged(String conversationId) {
		return this.repository.keyFor(conversationId);
	}

}
