package com.example.configmgr.ai.memory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
 * 记忆面 R1-04 / R1-06 / R1-07 的根修契约（fix round 1）。
 *
 * <p>本类锁定：
 * <ol>
 * <li><b>任意非空 sessionId</b>：{@code %}/{@code {}/{@code }} 无碰撞转义后，
 * tagged final 与 staging 始终共享同一个非空 hash tag ⇒ 同槽；空白 id 直接拒绝；</li>
 * <li><b>旧键迁移</b>：读侧 tagged 优先、缺失回落 legacy {@code chat:mem:<cid>}；
 * 保存成功 RENAME 后删除 legacy；删除同时清 tagged + legacy，并单键 {@code SREM}；</li>
 * <li><b>失败安全</b>：staging 写入与 TTL 由单键脚本原子完成；push / TTL / RENAME 失败只清理
 * staging，旧历史可读；清理 DEL 再失败，staging 也带 TTL，不会永久孤儿；</li>
 * <li><b>索引</b>：SADD 失败只 WARN（含 cid + final key），不回退已提交历史，下次保存自愈；</li>
 * <li><b>并发</b>：两次保存使用不同 staging，读侧只可能看到旧窗或某个完整新窗，不会半窗。</li>
 * </ol>
 *
 * <p>全部用例跑 {@link FakeMemoryRedis}（进程内替身），<b>不需要真实 Redis / 任何真实密钥</b>。
 */
class RedisChatMemoryRepositoryClusterSafetyTest {

	private static final String CID = "s-1";

	private static final String IDS = RedisChatMemoryRepository.CONVERSATION_IDS_KEY;

	private static final Duration TTL = Duration.ofHours(6);

	private final FakeMemoryRedis fake = new FakeMemoryRedis();

	private final RedisChatMemoryRepository repository = new RedisChatMemoryRepository(this.fake.redis,
			new MessageJsonCodec(new ObjectMapper()), TTL);

	// ── 1. 删除：tagged + legacy 各自单键 DEL，再单键 SREM ──────────────────

	@Test
	void deleteClearsTaggedLegacyAndIndexWithoutAnyScript() {
		this.fake.enableClusterMode();
		this.repository.saveAll(CID, List.of(new UserMessage("tagged")));
		String legacyKey = legacyKey();
		this.fake.lists.put(legacyKey, new java.util.concurrent.CopyOnWriteArrayList<>(List.of("legacy")));
		assertTrue(this.fake.set(IDS).contains(CID), "前置：索引成员存在");
		this.fake.resetCounters();

		assertDoesNotThrow(() -> this.repository.deleteByConversationId(CID), "三条单键命令不得跨槽");

		assertFalse(this.fake.hasKey(finalKey()), "tagged 数据键应被删除");
		assertFalse(this.fake.hasKey(legacyKey), "legacy 数据键应被删除");
		assertTrue(this.fake.set(IDS).isEmpty(), "SREM 应清掉索引成员（防无界增长）");
		assertEquals(0, this.fake.scriptCalls.get(), "删除不得再发 Lua");
		assertEquals(List.of(finalKey(), legacyKey), this.fake.deletedKeys, "删除顺序 = tagged、legacy");
		assertEquals(1, this.fake.indexRemoves.get());
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
		}
		assertEquals("chat:mem:{%7Ba%7Db}", this.repository.keyFor("{a}b"));
		assertEquals("chat:mem:{%7Dx}", this.repository.keyFor("}x"));
		assertEquals("chat:mem:{x%7Dy}", this.repository.keyFor("x}y"));
		assertEquals("chat:mem:{%25%7Bx%7D%25}", this.repository.keyFor("%{x}%"));
		assertNotEquals(this.repository.keyFor("{a}b"), this.repository.keyFor("%7Ba%7Db"),
				"转义必须无碰撞");
	}

	@Test
	void dataKeyShapeCarriesOnlyTheSessionHashTag() {
		assertEquals("chat:mem:{s-1}", this.repository.keyFor(CID), "final 键必须带 {<cid>} tag");
		assertFalse(IDS.contains("{"), "全局索引键不得加 tag（否则所有会话被压进同一槽）");
		assertNotEquals(IDS, this.repository.keyFor(CID));
	}

	// ── 3. 非空保存：同槽 staging（脚本内 TTL）+ RENAME + legacy 清理 ───────

	@Test
	void saveAllStagesWithTtlThenRenamesAndMigratesLegacy() {
		this.fake.enableClusterMode();
		String legacyKey = legacyKey();
		this.fake.lists.put(legacyKey, new java.util.concurrent.CopyOnWriteArrayList<>(List.of("old-legacy")));

		this.repository.saveAll(CID, List.of(new UserMessage("一")));

		assertEquals(1, this.fake.scriptCalls.get(), "staging 写入应走单键脚本（RPUSH + PEXPIRE）");
		assertEquals(1, this.fake.stageWrites.get());
		assertEquals(1, this.fake.lastScriptKeys.size(), "脚本只带 staging 一个键");
		assertEquals(this.fake.lastRenameSource, this.fake.lastScriptKeys.get(0));
		assertEquals(1, this.fake.renames.get(), "非空保存必须走 RENAME 原子替换");
		assertEquals(finalKey(), this.fake.lastRenameTarget);
		assertNotNull(this.fake.lastRenameSource, "必须经由 staging 键");
		assertNotEquals(finalKey(), this.fake.lastRenameSource);
		assertEquals(CID, FakeMemoryRedis.hashTagOf(this.fake.lastRenameSource), "staging 与 final 的 tag 必须相同");
		assertEquals(CID, FakeMemoryRedis.hashTagOf(finalKey()));
		assertEquals(FakeMemoryRedis.slot(this.fake.lastRenameSource), FakeMemoryRedis.slot(finalKey()),
				"staging 与 final 必须同槽");
		assertFalse(this.fake.hasKey(this.fake.lastRenameSource), "RENAME 后 staging 应消失");
		assertTrue(this.fake.hasKey(finalKey()));
		assertEquals(TTL, this.fake.expiry(finalKey()), "RENAME 后 final TTL 必须等于配置值");
		assertFalse(this.fake.hasKey(legacyKey), "RENAME 成功后应删除 legacy 完成自然迁移");
		assertEquals(List.of(legacyKey), this.fake.deletedKeys, "迁移删除只允许 legacy 键");
		assertTrue(this.fake.set(IDS).contains(CID));
	}

	@Test
	void ordinaryAndRepeatedSaveReplaceTheWindow() {
		this.repository.saveAll(CID, List.of(new UserMessage("一")));
		this.repository.saveAll(CID, List.of(new UserMessage("二"), new UserMessage("三")));
		this.repository.saveAll(CID, List.of(new UserMessage("二"), new UserMessage("三")));

		assertEquals(List.of("二", "三"), texts(this.repository.findByConversationId(CID)), "整窗口覆盖写，不追加");
		assertEquals(3, this.fake.renames.get(), "每次保存都是一次 RENAME");
		assertEquals(3, this.fake.scriptCalls.get(), "每次保存都是一次 staging 脚本");
		assertEquals(1, this.fake.lists.size(), "不得留下 staging 键");
	}

	// ── 4. 写失败：旧历史必须原样可读，且 staging 不留无 TTL 孤儿 ────────────

	@Test
	void failedStageWriteKeepsPreviousHistory() {
		this.repository.saveAll(CID, List.of(new UserMessage("旧")));
		this.fake.resetCounters();
		this.fake.failPush = true;

		assertThrows(RuntimeException.class, () -> this.repository.saveAll(CID, List.of(new UserMessage("新"))));

		assertEquals(List.of("旧"), texts(this.repository.findByConversationId(CID)), "写 staging 失败不得丢旧历史");
		assertTrue(this.fake.hasKey(finalKey()));
		assertFalse(this.fake.hasKey(this.fake.lastStageKey), "脚本失败不得留下 staging");
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
		this.fake.failDelete = true; // 即使 finally 的 DEL 也失败，也不允许留下无 TTL 的 staging

		assertThrows(RuntimeException.class, () -> this.repository.saveAll(CID, List.of(new UserMessage("新"))));

		assertEquals(List.of("旧"), texts(this.repository.findByConversationId(CID)), "TTL 失败不得丢旧历史");
		assertTrue(this.fake.hasKey(finalKey()));
		assertFalse(this.fake.hasKey(this.fake.lastStageKey), "staging 脚本失败即无键，不依赖 finally DEL");
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

	// ── 5. 索引失败：不回退已写新历史，WARN 含 cid + final key ───────────────

	@Test
	void indexFailureDoesNotRollBackCommittedHistoryAndIsObservable() {
		this.repository.saveAll(CID, List.of(new UserMessage("旧")));
		this.fake.sets.clear();
		this.fake.failIndexAdd = true;

		Logger logger = (Logger) LoggerFactory.getLogger(RedisChatMemoryRepository.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			assertDoesNotThrow(() -> this.repository.saveAll(CID, List.of(new UserMessage("新"))),
					"索引是辅助面：失败不得让已提交的数据写回报错");
		}
		finally {
			logger.detachAppender(appender);
		}

		assertEquals(List.of("新"), texts(this.repository.findByConversationId(CID)), "已写新历史不得回退");
		assertTrue(this.fake.hasKey(finalKey()));
		assertFalse(this.fake.set(IDS).contains(CID), "索引失败是事实，不得伪造成功");
		assertTrue(appender.list.stream().anyMatch(event -> event.getLevel() == Level.WARN
				&& event.getFormattedMessage().contains(CID) && event.getFormattedMessage().contains(finalKey())),
				"索引失败 WARN 必须同时含 conversationId 与 final key（可对账）");

		this.fake.failIndexAdd = false;
		this.repository.saveAll(CID, List.of(new UserMessage("新")));
		assertTrue(this.fake.set(IDS).contains(CID), "下一次成功保存应自愈全局索引");
	}

	// ── 6. 旧键迁移：读回落 + 保存迁移 ──────────────────────────────────────

	@Test
	void findByConversationIdFallsBackToLegacyKey() {
		MessageJsonCodec codec = new MessageJsonCodec(new ObjectMapper());
		this.fake.lists.put(legacyKey(),
				new java.util.concurrent.CopyOnWriteArrayList<>(List.of(codec.serialize(new UserMessage("legacy")))));
		this.fake.sets.computeIfAbsent(IDS, ignored -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(CID);
		assertTrue(this.fake.sets.getOrDefault(IDS, java.util.Set.of()).contains(CID), "前置：索引成员存在");

		assertEquals(List.of("legacy"), texts(this.repository.findByConversationId(CID)), "tagged 缺失应回落 legacy");
		assertTrue(this.repository.isConversationIndexed(CID), "legacy 存活也应判为在索引");
		assertTrue(this.repository.findConversationIds().contains(CID), "legacy 存活应仍被列出");
		assertTrue(this.fake.set(IDS).contains(CID), "legacy 存活时不得被当 stale 清掉");
	}

	@Test
	void findConversationIdsDropsBlankIndexMember() {
		this.fake.sets.computeIfAbsent(IDS, ignored -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add("");

		assertTrue(this.repository.findConversationIds().isEmpty(), "空白索引成员不得阻断枚举");
		assertTrue(this.fake.set(IDS).isEmpty(), "空白成员应被惰性清掉");
	}

	@Test
	void saveAllMigratesLegacyAfterRename() {
		MessageJsonCodec codec = new MessageJsonCodec(new ObjectMapper());
		this.fake.lists.put(legacyKey(),
				new java.util.concurrent.CopyOnWriteArrayList<>(List.of(codec.serialize(new UserMessage("legacy")))));
		this.fake.sets.computeIfAbsent(IDS, ignored -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(CID);

		this.repository.saveAll(CID, List.of(new UserMessage("新")));

		assertTrue(this.fake.hasKey(finalKey()));
		assertFalse(this.fake.hasKey(legacyKey()), "RENAME 成功后 legacy 应被删除");
		assertEquals(List.of("新"), texts(this.repository.findByConversationId(CID)));
	}

	// ── 7. 空列表：仍按既有删除语义 ─────────────────────────────────────────

	@Test
	void emptySaveAllClearsBothShapesAndIndex() {
		this.repository.saveAll(CID, List.of(new UserMessage("旧")));
		this.fake.lists.put(legacyKey(), new java.util.concurrent.CopyOnWriteArrayList<>(List.of("legacy")));
		this.fake.resetCounters();

		assertDoesNotThrow(() -> this.repository.saveAll(CID, List.of()), "空列表删除不得跨槽");

		assertFalse(this.fake.hasKey(finalKey()));
		assertFalse(this.fake.hasKey(legacyKey()));
		assertTrue(this.fake.set(IDS).isEmpty());
		assertEquals(0, this.fake.scriptCalls.get());
		assertEquals(0, this.fake.renames.get());
		assertEquals(List.of(finalKey(), legacyKey()), this.fake.deletedKeys);
		assertEquals(1, this.fake.indexRemoves.get());
	}

	// ── 8. 并发：不同 staging，读侧只见完整窗口 ─────────────────────────────

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

	// ── 替身自身的非空断言 ──────────────────────────────────────────────────

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
				() -> this.fake.redis.rename("chat:mem:{a}", "chat:mem:{b}"));
	}

	private String finalKey() {
		return this.repository.keyFor(CID);
	}

	private static String legacyKey() {
		return RedisChatMemoryRepository.KEY_PREFIX + CID;
	}

	private static List<String> texts(List<Message> messages) {
		return messages.stream().map(Message::getText).toList();
	}

}
