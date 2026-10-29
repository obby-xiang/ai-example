package com.example.configmgr.ai.session;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.form.FormAttachmentStore;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.memory.RedisChatMemoryRepository;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.file.FileStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>销毁完整性：记忆面的 staging 与写回竞态不得在 {@code complete=true} 之后留下残留</b>。
 *
 * <h2>缺陷形态</h2>
 * <ol>
 * <li><b>staging 键不在删除面内</b>：{@code chat:mem:staging:{<sid>}:<uuid>} 是在飞 {@code saveAll}
 * 的中间态（正常路径 RENAME 后消失，清理失败/进程中断时按 TTL 自清）。销毁只删 tagged + legacy +
 * 索引成员 ⇒ 只带 staging 的会话既报 {@code found=false} 又"complete=true 后残留"；</li>
 * <li><b>删除与写回交错</b>：销毁删完记忆后，一笔"入口守卫已通过"的 {@code saveAll} 仍可能把
 * staging RENAME 成 final。索引成员此时已被 SREM，{@code isConversationIndexed} 看不见这份残留 ——
 * 即"索引缺成员"不能当作"已删净"的证明。</li>
 * </ol>
 *
 * <h2>本批口径</h2>
 * 枚举阶段扫出本会话的 staging 键（与归档键同一 Cluster 安全扫描，精确前缀 + 只认本会话），
 * 删除阶段逐个单键 DEL，删完<b>复核</b> tagged / legacy / staging 是否真的都不在了；
 * 仍有残留 ⇒ {@code complete=false} + 可重试事实（pending 保留）。
 */
class AiSessionPurgeMemoryResidueTest {

	private static final String SESSION_A = "sess-a";

	private static final String SESSION_B = "sess-b";

	private final PurgeFakeRedis fake = new PurgeFakeRedis();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final AiProperties properties = new AiProperties();

	private final SessionGate gate = mock(SessionGate.class);

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final FormAttachmentStore attachments = mock(FormAttachmentStore.class);

	private final FileStorageService fileStorage = mock(FileStorageService.class);

	private final SessionPurgeDatabase database = mock(SessionPurgeDatabase.class);

	private final SessionPurgeGuard guard = new SessionPurgeGuard(this.fake.redis);

	private final SessionPurgePendingStore pendingStore = new SessionPurgePendingStore(this.fake.redis, this.mapper,
			this.properties);

	private final RunStore store = new RunStore(this.fake.redis, this.mapper, this.properties);

	private RedisChatMemoryRepository memory;

	private AiSessionPurgeService service;

	@BeforeEach
	void setUp() {
		this.memory = new RedisChatMemoryRepository(this.fake.redis, this.codec, Duration.ofHours(24), this.guard);
		when(this.gate.currentRunId(anyString())).thenReturn(null);
		when(this.cancellations.keyFor(anyString())).thenAnswer(invocation -> "ai:cancel:" + invocation.getArgument(0));
		when(this.attachments.findBySessionId(anyString())).thenReturn(List.of());
		when(this.database.snapshot(anyString())).thenReturn(new SessionPurgeDatabase.Snapshot(0));
		when(this.database.delete(anyString()))
			.thenReturn(new SessionPurgeDatabase.Deleted(0));
		this.service = serviceWith(this.memory);
	}

	@Test
	void stagingKeysOfThePurgedSessionAreDeletedAndReportCompleteOnlyAfterwards() {
		seedMemory(SESSION_A);
		seedStaging(SESSION_A, "inflight-a");
		seedStaging(SESSION_B, "inflight-b");

		AiSessionPurgeService.PurgeResult result = this.service.purge(SESSION_A, "test");

		assertThat(result.complete()).as("staging 清干净了才允许 complete").isTrue();
		assertThat(this.fake.lists).as("本会话的 staging 键必须删除（否则 RENAME 一落地就是残留）")
			.doesNotContainKey(stagingKey(SESSION_A, "inflight-a"));
		assertThat(this.fake.lists).as("他会话的 staging 键一个字节都不许动")
			.containsKey(stagingKey(SESSION_B, "inflight-b"));
	}

	@Test
	void stagingOnlySessionIsFoundAndClearedInsteadOfReportedCompleteWithResidue() {
		// 只带 staging 的会话：索引里没有成员（SADD 失败/尚未 RENAME 完成），tagged/legacy 都不存在。
		seedStaging(SESSION_A, "orphan");

		AiSessionPurgeService.PurgeResult result = this.service.purge(SESSION_A, "test");

		assertThat(result.found()).as("只在 staging 上存在的会话也必须算 found").isTrue();
		assertThat(this.fake.lists).as("staging 残留必须删掉").doesNotContainKey(stagingKey(SESSION_A, "orphan"));
		assertThat(result.complete()).isTrue();
		assertThat(result.memory().deleted()).isTrue();
	}

	@Test
	void writeBackLandingAfterTheMemoryDeletionIsNotReportedAsComplete() {
		seedMemory(SESSION_A);
		List<String> lateWriteBack = this.codec
			.serializeAll(List.of(new org.springframework.ai.chat.messages.UserMessage("迟到写回")));
		// 在飞轮次的写回：入口守卫检查在销毁装守卫之前就通过了，于是它的 RENAME 落在销毁 DEL 之后
		// （索引成员此刻已被 SREM —— 只凭索引根本看不见这份残留）。
		this.memory = new RedisChatMemoryRepository(this.fake.redis, this.codec, Duration.ofHours(24), this.guard) {

			@Override
			public void deleteByConversationId(String conversationId) {
				super.deleteByConversationId(conversationId);
				AiSessionPurgeMemoryResidueTest.this.fake.lists.put(keyFor(SESSION_A),
						new CopyOnWriteArrayList<>(lateWriteBack));
			}

		};
		this.service = serviceWith(this.memory);

		AiSessionPurgeService.PurgeResult result = this.service.purge(SESSION_A, "test");

		assertThat(result.complete()).as("删除后又出现记忆残留 ⇒ 不得报 complete").isFalse();
		assertThat(result.memory().deleted()).as("记忆面未删净").isFalse();
		assertThat(result.errors()).as("残留必须如实列出（可重试）").anyMatch(error -> error.startsWith("memory"));
		assertThat(this.fake.strings).as("pending 保留 ⇒ 由调度补删").containsKey(
				SessionPurgeGuard.PENDING_PREFIX + SESSION_A);
	}

	/**
	 * <b>I1（review）：staging 扫描的 pattern 必须转义 glob 元字符</b>。
	 *
	 * <p>{@code escapedId} 只转义 {@code %/{/}}，不管 glob；真 Redis 里 {@code [} 开字符类、
	 * {@code \} 转义下一字符 ⇒ 不转义时连<b>自己的</b> staging 键都匹配不到（漏删 + 误报 complete）。
	 * 本用例用 {@code sid="a[b"}：修复前 staging 键留在 Redis 里且 complete=true。
	 */
	@Test
	void stagingScanEscapesGlobMetacharactersInSessionIds() {
		String braced = "a[b";
		seedStaging(braced, "inflight-bracket");
		seedStaging("axb", "other-session");

		AiSessionPurgeService.PurgeResult result = this.service.purge(braced, "test");

		assertThat(this.fake.lists).as("含 [ 的 sessionId 也必须删掉自己的 staging 键")
			.doesNotContainKey(stagingKey(braced, "inflight-bracket"));
		assertThat(this.fake.lists).as("字符类语义不得误伤邻会话").containsKey(stagingKey("axb", "other-session"));
		assertThat(result.complete()).as("自己的 staging 没漏才不会报 complete").isTrue();
	}

	/**
	 * <b>I1（review）第二支：{@code \} 同样是 glob 元字符</b>。
	 *
	 * <p>未转义时 Redis 把 {@code \b} 读成"字面 b"，于是 {@code sid="a\b"} 的 pattern 只能匹配
	 * {@code {ab}…}，<b>自己的键反而漏掉</b>。修复后 {@code \\} 被转义成字面反斜杠。
	 */
	@Test
	void stagingScanEscapesBackslashInSessionIds() {
		String backslash = "a\\b";
		seedStaging(backslash, "inflight-backslash");
		seedStaging("ab", "neighbour");

		AiSessionPurgeService.PurgeResult result = this.service.purge(backslash, "test");

		assertThat(this.fake.lists).as("含 \\ 的 sessionId 也必须删掉自己的 staging 键")
			.doesNotContainKey(stagingKey(backslash, "inflight-backslash"));
		assertThat(this.fake.lists).as("转义后不得命中 {ab} 的 staging").containsKey(stagingKey("ab", "neighbour"));
		assertThat(result.complete()).isTrue();
	}

	/**
	 * <b>I3（review）：删后复核的 SCAN 失败不得被当成"没有残留"</b>。
	 *
	 * <p>复核扫描抛错时 {@code memory.deleted} 必须为假（"查不出来"≠"确认删净"），
	 * 且 {@code complete=false}（可重试）。旧实现把异常吞成空列表，于是 deleted=true。
	 */
	@Test
	void residueScanFailureIsNotTreatedAsDeleted() {
		seedMemory(SESSION_A);
		// 枚举阶段的扫描必须成功（否则会按"枚举失败不半删"中止）⇒ 在删除阶段才打开 failScan：
		// 枚举（含 staging 扫描）已跑完，删除后的复核扫描才会撞上故障。
		this.memory = new RedisChatMemoryRepository(this.fake.redis, this.codec, Duration.ofHours(24), this.guard) {

			@Override
			public void deleteByConversationId(String conversationId) {
				super.deleteByConversationId(conversationId);
				AiSessionPurgeMemoryResidueTest.this.fake.failScan = true;
			}

		};
		this.service = serviceWith(this.memory);

		AiSessionPurgeService.PurgeResult result = this.service.purge(SESSION_A, "test");

		this.fake.failScan = false;
		assertThat(result.complete()).as("复核不可知 ⇒ 不得报 complete").isFalse();
		assertThat(result.memory().deleted()).as("复核不可知 ≠ 已删净").isFalse();
		assertThat(result.errors()).anyMatch(error -> error.startsWith("memory"));
	}

	/**
	 * <b>I4（review）：真正构造"索引缺成员 + 数据还在"的掩膜形态</b>。
	 *
	 * <p>旧用例只证明"按 sessionId 删、不依赖索引"（改前就绿）。这里让删除本身<b>没删到</b>
	 * （模拟键形变化/迁移期残留），而记忆索引里<b>从来就没有这个会话的成员</b>：
	 * {@code removedFromIndex=true} 会把"数据还在"掩盖过去 —— 只有"删后复核数据键"能揭穿它。
	 */
	@Test
	void missingIndexMemberCannotMaskResidualMemoryData() {
		seedMemory(SESSION_A);
		this.fake.sets.remove("chat:mem:__ids__");
		this.memory = new RedisChatMemoryRepository(this.fake.redis, this.codec, Duration.ofHours(24), this.guard) {

			@Override
			public void deleteByConversationId(String conversationId) {
				// 模拟"删口没命中真键"（迁移期键形/键族变更）：数据键原样留下。
			}

		};
		this.service = serviceWith(this.memory);

		AiSessionPurgeService.PurgeResult result = this.service.purge(SESSION_A, "test");

		assertThat(result.removedFromIndex()).as("索引里本来就没成员 ⇒ 该字段为真（正是掩膜来源）").isTrue();
		assertThat(this.fake.lists).as("数据键确实还在").containsKey(this.memory.keyFor(SESSION_A));
		assertThat(result.memory().deleted()).as("I4：不得用索引结论掩盖'数据还在'").isFalse();
		assertThat(result.complete()).as("有残留 ⇒ complete=false（可重试）").isFalse();
		assertThat(result.errors()).as("残留必须如实列出").anyMatch(error -> error.startsWith("memory"));
	}

	private AiSessionPurgeService serviceWith(RedisChatMemoryRepository repository) {
		return new AiSessionPurgeService(this.fake.redis, this.store, this.gate, this.cancellations, repository,
				this.attachments, this.fileStorage, this.database, this.guard, this.pendingStore, this.properties,
				Duration.ZERO);
	}

	private void seedMemory(String sessionId) {
		this.fake.lists.put(this.memory.keyFor(sessionId), new CopyOnWriteArrayList<>(
				this.codec.serializeAll(List.of(new org.springframework.ai.chat.messages.UserMessage("hi")))));
	}

	private void seedStaging(String sessionId, String token) {
		this.fake.lists.put(stagingKey(sessionId, token), new CopyOnWriteArrayList<>(List.of("{}")));
	}

	/** staging 键形（{@code chat:mem:staging:{<escapedId>}:<uuid>}）。 */
	private static String stagingKey(String sessionId, String token) {
		return "chat:mem:staging:{" + sessionId + "}:" + token;
	}

}
