package com.example.configmgr.ai.memory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 记忆面集群安全契约（身份键形 / staging / final / 当前脚本写序）。
 *
 * <p>本类锁定（与当前 {@link RedisChatMemoryRepository} 对齐）：
 * <ol>
 * <li><b>身份键形</b>：final = {@code chat:mem:<t>:<u>:{<cid>}}，staging 同 tag；
 * 任意非空 sessionId 转义后 final/staging 同槽；空白 id 在任何 Redis 调用前拒绝；</li>
 * <li><b>保存</b>：{@code STAGE_WRITE}（RPUSH + SADD token + 两键 PEXPIRE）→
 * {@code STAGING_COMMIT}（RENAME + SREM + 索引 TTL）；旧的裸 legacy 键不再读取/迁移/删除；</li>
 * <li><b>清空</b>：一条 {@code MEMORY_CLEAR} 同槽脚本删数据并推进代次/版本；不碰裸 legacy；</li>
 * <li><b>失败安全</b>：push / TTL / RENAME 失败保留旧历史；finally 的 {@code STAGING_DISCARD}
 * 清理 staging；清理 DEL 再失败时 staging 仍带 TTL，不会永久孤儿；</li>
 * <li><b>并发</b>：两次保存使用不同 staging，读侧只看到旧窗或某个完整新窗，不会半窗。</li>
 * </ol>
 *
 * <p>全部用例跑 {@link FakeMemoryRedis}（进程内替身），不需要真实 Redis / 密钥。
 */
class RedisChatMemoryRepositoryClusterSafetyTest {

	private static final String CID = "s-1";

	private static final String TENANT_SEGMENT = "1:101:";

	private static final Duration TTL = Duration.ofHours(6);

	private final FakeMemoryRedis fake = new FakeMemoryRedis();

	private final RedisChatMemoryRepository repository = new RedisChatMemoryRepository(this.fake.redis,
			new MessageJsonCodec(new ObjectMapper()), TTL);

	private com.example.configmgr.ai.tenant.RequestContextUtils.SafeCloseable identity;

	@BeforeEach
	void bindIdentity() {
		this.identity = com.example.configmgr.ai.tenant.RequestContextUtils.useContext(1L, 101L);
	}

	@AfterEach
	void releaseIdentity() {
		if (this.identity != null) {
			this.identity.close();
		}
	}

	// ── 1. 清空：一条同槽脚本（删数据 + 推进代次/版本）；裸 legacy 不受影响 ──────────

	@Test
	void deleteClearsIdentityKeyWithOneSameSlotScript() {
		this.fake.enableClusterMode();
		this.repository.saveAll(CID, List.of(new UserMessage("tagged")));
		String bareLegacy = bareLegacyKey();
		this.fake.lists.put(bareLegacy, new java.util.concurrent.CopyOnWriteArrayList<>(List.of("legacy")));
		this.fake.resetCounters();

		assertDoesNotThrow(() -> this.repository.deleteByConversationId(CID), "清空不得跨槽");

		assertFalse(this.fake.hasKey(finalKey()), "身份数据键应被删除");
		assertTrue(this.fake.hasKey(bareLegacy), "裸 legacy 不是当前命名空间，清空不得删除它");
		assertEquals(1, this.fake.scriptCalls.get(),
				"清空用一条同槽脚本（拆成多条命令会留下『代次已推进、数据未删』的复活窗口）");
		assertEquals(List.of(finalKey()), this.fake.deletedKeys,
				"脚本内只 DEL 身份数据键；计数器保留并推进");
		assertEquals(0, this.fake.indexRemoves.get(), "全局索引已退役，清空不再 SREM");
	}

	@Test
	void blankSessionIdIsRejectedBeforeAnyRedisCall() {
		assertThrows(IllegalArgumentException.class, () -> this.repository.keyFor(""));
		assertThrows(IllegalArgumentException.class, () -> this.repository.keyFor("   "));
		assertThrows(IllegalArgumentException.class, () -> this.repository.findByConversationId(null));
		assertThrows(IllegalArgumentException.class,
				() -> this.repository.saveAll("", List.of(new UserMessage("x"))));
		assertThrows(IllegalArgumentException.class, () -> this.repository.deleteByConversationId(" "));
		assertEquals(0, this.fake.scriptCalls.get());
		assertEquals(0, this.fake.deletes.get());
	}

	// ── 2. 任意 id：转义后 final/staging 同槽 ────────────────────────────────

	@Test
	void anyNonBlankIdKeepsFinalAndStagingInOneSlot() {
		for (String id : List.of("}x", "x}y", "{a}b", "%{x}%", "a%b", "s-1")) {
			String tagged = this.repository.keyFor(id);
			String staging = RedisChatMemoryRepository.stagingKey(id, "token");
			assertEquals(FakeMemoryRedis.slot(tagged), FakeMemoryRedis.slot(staging), "同槽：" + id);
			assertFalse(FakeMemoryRedis.hashTagOf(tagged).isBlank(), "tag 不得为空：" + id);
			assertEquals(SessionKeyCodec.escapedId(id), FakeMemoryRedis.hashTagOf(tagged), "tag = 转义 id：" + id);
		}
		assertEquals("chat:mem:1:101:{%7Ba%7Db}", this.repository.keyFor("{a}b"));
		assertEquals("chat:mem:1:101:{%7Dx}", this.repository.keyFor("}x"));
		assertEquals("chat:mem:1:101:{x%7Dy}", this.repository.keyFor("x}y"));
		assertEquals("chat:mem:1:101:{%25%7Bx%7D%25}", this.repository.keyFor("%{x}%"));
		assertNotEquals(this.repository.keyFor("{a}b"), this.repository.keyFor("%7Ba%7Db"),
				"转义必须无碰撞");
	}

	@Test
	void dataKeyCarriesIdentityAndOnlyTheSessionHashTag() {
		assertEquals("chat:mem:1:101:{s-1}", this.repository.keyFor(CID),
				"final 键必须带身份段与 {<cid>} tag");
		assertTrue(FakeMemoryRedis.hashTagOf(this.repository.keyFor(CID)).equals("s-1"),
				"hash tag 只能是 session id，不能混入身份段以外的内容");
		assertTrue(this.repository.keyFor(CID).startsWith("chat:mem:" + TENANT_SEGMENT),
				"身份段必须进键");
	}

	// ── 3. 非空保存：STAGE_WRITE + STAGING_COMMIT；裸 legacy 不动 ─────────────

	@Test
	void saveAllStagesWithTtlThenCommitsWithoutTouchingBareLegacy() {
		this.fake.enableClusterMode();
		String bareLegacy = bareLegacyKey();
		this.fake.lists.put(bareLegacy, new java.util.concurrent.CopyOnWriteArrayList<>(List.of("old-legacy")));

		this.repository.saveAll(CID, List.of(new UserMessage("一")));

		assertEquals(2, this.fake.scriptCalls.get(), "保存 = STAGE_WRITE + STAGING_COMMIT 两条脚本");
		assertEquals(1, this.fake.stageWrites.get());
		assertTrue(this.fake.lastScriptBody.contains("STAGING_COMMIT"), "最后一条必须是提交脚本");
		assertEquals(this.fake.lastRenameSource, this.fake.lastScriptKeys.get(0));
		assertEquals(this.fake.lastStageKey, this.fake.lastRenameSource, "staging 写入与 RENAME 源必须是同一键");
		assertEquals(1, this.fake.renames.get(), "非空保存必须走 RENAME 原子替换");
		assertEquals(finalKey(), this.fake.lastRenameTarget);
		assertNotEquals(finalKey(), this.fake.lastRenameSource);
		assertEquals("s-1", FakeMemoryRedis.hashTagOf(this.fake.lastRenameSource), "staging 与 final 的 tag 必须相同");
		assertEquals(FakeMemoryRedis.slot(this.fake.lastRenameSource), FakeMemoryRedis.slot(finalKey()),
				"staging 与 final 必须同槽");
		assertFalse(this.fake.hasKey(this.fake.lastRenameSource), "提交后 staging 应消失");
		assertTrue(this.fake.hasKey(finalKey()));
		assertEquals(TTL, this.fake.expiry(finalKey()), "提交后 final TTL 必须等于配置值");
		assertTrue(this.fake.hasKey(bareLegacy), "裸 legacy 不在当前命名空间，保存不得删除它");
		assertTrue(this.fake.deletedKeys.isEmpty(), "当前保存不迁移/删除裸 legacy");
		assertEquals(1, this.fake.indexAdds.get(), "STAGE_WRITE 必须 SADD staging token");
		assertEquals(1, this.fake.indexRemoves.get(), "STAGING_COMMIT 必须 SREM staging token");
		assertFalse(this.fake.sets.containsKey(RedisChatMemoryRepository.stagingIndexKey(CID)),
				"提交后 staging 索引为空必须删除");
	}

	@Test
	void ordinaryAndRepeatedSaveReplaceTheWindow() {
		this.repository.saveAll(CID, List.of(new UserMessage("一")));
		this.repository.saveAll(CID, List.of(new UserMessage("二"), new UserMessage("三")));
		this.repository.saveAll(CID, List.of(new UserMessage("二"), new UserMessage("三")));

		assertEquals(List.of("二", "三"), texts(this.repository.findByConversationId(CID)), "整窗口覆盖写，不追加");
		assertEquals(3, this.fake.renames.get(), "每次保存都是一次 RENAME");
		assertEquals(6, this.fake.scriptCalls.get(), "每次保存 = STAGE_WRITE + STAGING_COMMIT");
		assertEquals(3, this.fake.indexAdds.get());
		assertEquals(3, this.fake.indexRemoves.get());
		assertEquals(1, this.fake.lists.size(), "不得留下 staging 键");
		assertFalse(this.fake.sets.containsKey(RedisChatMemoryRepository.stagingIndexKey(CID)));
	}

	// ── 4. 写失败：旧历史必须原样可读，staging 清理/残留 TTL 可观测 ──────────

	@Test
	void failedStageWriteKeepsPreviousHistory() {
		this.repository.saveAll(CID, List.of(new UserMessage("旧")));
		this.fake.resetCounters();
		this.fake.failPush = true;

		assertThrows(RuntimeException.class, () -> this.repository.saveAll(CID, List.of(new UserMessage("新"))));

		assertEquals(List.of("旧"), texts(this.repository.findByConversationId(CID)), "写 staging 失败不得丢旧历史");
		assertTrue(this.fake.hasKey(finalKey()));
		assertFalse(this.fake.hasKey(this.fake.lastStageKey), "脚本失败不得留下 staging");
		assertEquals(0, this.fake.indexAdds.get(), "RPUSH 失败发生在 SADD 之前");
		assertFalse(this.fake.deletedKeys.contains(finalKey()), "失败路径不得删除已有 final");
		assertTrue(this.fake.deletedKeys.stream().allMatch(key -> key.equals(this.fake.lastStageKey)),
				"失败路径只允许清理 staging，实际：" + this.fake.deletedKeys);
		this.fake.failPush = false;
		this.repository.saveAll(CID, List.of(new UserMessage("新")));
		assertEquals(List.of("新"), texts(this.repository.findByConversationId(CID)), "故障恢复后照常覆盖");
	}

	@Test
	void failedStageTtlKeepsPreviousHistoryWithoutOrphan() {
		this.repository.saveAll(CID, List.of(new UserMessage("旧")));
		this.fake.resetCounters();
		this.fake.failExpire = true;

		assertThrows(RuntimeException.class, () -> this.repository.saveAll(CID, List.of(new UserMessage("新"))));

		assertEquals(List.of("旧"), texts(this.repository.findByConversationId(CID)), "TTL 失败不得丢旧历史");
		assertTrue(this.fake.hasKey(finalKey()));
		assertEquals(1, this.fake.indexAdds.get(), "PEXPIRE 失败发生在 RPUSH/SADD 之后（真实 Lua 不回滚）");
		assertFalse(this.fake.hasKey(this.fake.lastStageKey), "finally 的 STAGING_DISCARD 必须清掉 staging");
		assertEquals(1, this.fake.indexRemoves.get(), "discard 必须 SREM staging token");
		assertFalse(this.fake.sets.containsKey(RedisChatMemoryRepository.stagingIndexKey(CID)),
				"失败路径不得留下 staging 索引");
	}

	@Test
	void failedRenameKeepsPreviousHistory() {
		this.repository.saveAll(CID, List.of(new UserMessage("旧")));
		this.fake.resetCounters();
		this.fake.failRename = true;

		assertThrows(RuntimeException.class, () -> this.repository.saveAll(CID, List.of(new UserMessage("新"))));

		assertEquals(List.of("旧"), texts(this.repository.findByConversationId(CID)), "RENAME 失败不得丢旧历史");
		assertTrue(this.fake.hasKey(finalKey()));
		assertFalse(this.fake.hasKey(this.fake.lastStageKey), "失败后应清理 staging");
		assertEquals(1, this.fake.indexAdds.get());
		assertEquals(1, this.fake.indexRemoves.get(), "失败提交后 discard 必须 SREM token");
		this.fake.failRename = false;
		this.repository.saveAll(CID, List.of(new UserMessage("新")));
		assertEquals(List.of("新"), texts(this.repository.findByConversationId(CID)));
	}

	@Test
	void stagingCleanupFailureLeavesTheKeyWithTtl() {
		this.repository.saveAll(CID, List.of(new UserMessage("旧")));
		this.fake.resetCounters();
		this.fake.failRename = true;
		this.fake.failDelete = true;

		assertThrows(RuntimeException.class, () -> this.repository.saveAll(CID, List.of(new UserMessage("新"))));

		assertEquals(List.of("旧"), texts(this.repository.findByConversationId(CID)), "旧历史仍可读");
		assertTrue(this.fake.hasKey(this.fake.lastStageKey), "finally DEL 失败时 staging 会残留");
		assertEquals(TTL, this.fake.expiry(this.fake.lastStageKey), "残留 staging 必须带 TTL，不能永久孤儿");
		this.fake.failRename = false;
		this.fake.failDelete = false;
		this.fake.redis.delete(this.fake.lastStageKey);
	}

	// ── 5. 裸 legacy：当前生产不再读取/迁移 ─────────────────────────────────

	@Test
	void bareLegacyKeyIsNotReadAsTheIdentityWindow() {
		MessageJsonCodec codec = new MessageJsonCodec(new ObjectMapper());
		this.fake.lists.put(bareLegacyKey(),
				new java.util.concurrent.CopyOnWriteArrayList<>(List.of(codec.serialize(new UserMessage("legacy")))));

		assertEquals(List.of(), texts(this.repository.findByConversationId(CID)),
				"裸 legacy 不属于身份命名空间，不得冒充当前身份窗口");
		assertFalse(this.repository.hasLiveMemory(CID));
	}

	@Test
	void saveAllWritesIdentityKeyAndLeavesBareLegacyUntouched() {
		MessageJsonCodec codec = new MessageJsonCodec(new ObjectMapper());
		this.fake.lists.put(bareLegacyKey(),
				new java.util.concurrent.CopyOnWriteArrayList<>(List.of(codec.serialize(new UserMessage("legacy")))));

		this.repository.saveAll(CID, List.of(new UserMessage("新")));

		assertTrue(this.fake.hasKey(finalKey()));
		assertTrue(this.fake.hasKey(bareLegacyKey()), "当前保存不迁移/删除裸 legacy");
		assertEquals(List.of("新"), texts(this.repository.findByConversationId(CID)));
	}

	// ── 6. 空列表：仍按既有删除语义（一条同槽脚本，裸 legacy 不动） ───────────

	@Test
	void emptySaveAllClearsIdentityKeyOnly() {
		this.repository.saveAll(CID, List.of(new UserMessage("旧")));
		this.fake.lists.put(bareLegacyKey(), new java.util.concurrent.CopyOnWriteArrayList<>(List.of("legacy")));
		this.fake.resetCounters();

		assertDoesNotThrow(() -> this.repository.saveAll(CID, List.of()), "空列表删除不得跨槽");

		assertFalse(this.fake.hasKey(finalKey()));
		assertTrue(this.fake.hasKey(bareLegacyKey()), "空保存不得删除裸 legacy");
		assertEquals(1, this.fake.scriptCalls.get(), "清空走一条同槽脚本（删数据 + 推进代次/版本）");
		assertEquals(0, this.fake.renames.get(), "清空不做 RENAME");
		assertTrue(this.fake.values.values().contains("1"), "清空推进了代次/版本（tombstone）");
		assertEquals(List.of(finalKey()), this.fake.deletedKeys);
		assertEquals(0, this.fake.indexRemoves.get(), "全局索引已退役，清空不再 SREM");
	}

	// ── 7. 并发：不同 staging，读侧只见完整窗口 ─────────────────────────────

	@Test
	void concurrentSavesUseDistinctStagingAndNeverExposeHalfWindow() throws Exception {
		this.repository.saveAll(CID, List.of(new UserMessage("旧")));
		this.fake.resetCounters();

		CountDownLatch firstInside = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		AtomicInteger stageCalls = new AtomicInteger();
		this.fake.afterStageWrite = () -> {
			if (stageCalls.incrementAndGet() == 1) {
				firstInside.countDown();
				try {
					releaseFirst.await(5, TimeUnit.SECONDS);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(ex);
				}
			}
		};

		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<?> first = pool.submit(() -> this.repository.saveAll(CID,
					List.of(new UserMessage("A1"), new UserMessage("A2"))));
			assertTrue(firstInside.await(5, TimeUnit.SECONDS), "第一次保存应停在 staging 之后、RENAME 之前");

			Future<?> second = pool.submit(() -> this.repository.saveAll(CID,
					List.of(new UserMessage("B1"), new UserMessage("B2"), new UserMessage("B3"))));
			second.get(5, TimeUnit.SECONDS);

			assertEquals(List.of("B1", "B2", "B3"), texts(this.repository.findByConversationId(CID)),
					"第一次尚未 RENAME，读侧只能看到第二次的完整窗口");

			releaseFirst.countDown();
			first.get(5, TimeUnit.SECONDS);
		}
		finally {
			releaseFirst.countDown();
			this.fake.afterStageWrite = null;
			pool.shutdownNow();
		}

		assertEquals(List.of("A1", "A2"), texts(this.repository.findByConversationId(CID)),
				"最后完成 RENAME 者胜，窗口必须完整");
		assertEquals(2, this.fake.renameSources.size());
		assertNotEquals(this.fake.renameSources.get(0), this.fake.renameSources.get(1),
				"两次并发保存必须使用不同 staging（UUID 隔离）");
		assertFalse(this.fake.hasKey(this.fake.renameSources.get(0)));
		assertFalse(this.fake.hasKey(this.fake.renameSources.get(1)));
	}

	// ── 8. 替身自身的非空断言 ──────────────────────────────────────────────

	@Test
	void slotAlgorithmMatchesRedis() {
		assertEquals(12182, FakeMemoryRedis.slot("foo"));
		assertEquals(5061, FakeMemoryRedis.slot("bar"));
		assertEquals(866, FakeMemoryRedis.slot("hello"));
		assertEquals(FakeMemoryRedis.slot("{user1000}.following"), FakeMemoryRedis.slot("{user1000}.followers"));
		assertEquals("foo{}{bar}", FakeMemoryRedis.hashTagOf("foo{}{bar}"), "空 tag 不算 tag");
	}

	@Test
	void crossSlotRenameIsRejected() {
		this.fake.enableClusterMode();
		assertThrows(FakeMemoryRedis.CrossSlotException.class,
				() -> this.fake.redis.rename("chat:mem:1:101:{a}", "chat:mem:1:101:{b}"));
	}

	private String finalKey() {
		return this.repository.keyFor(CID);
	}

	private static String bareLegacyKey() {
		return RedisChatMemoryRepository.KEY_PREFIX + CID;
	}

	private static List<String> texts(List<Message> messages) {
		return messages.stream().map(Message::getText).toList();
	}

}
