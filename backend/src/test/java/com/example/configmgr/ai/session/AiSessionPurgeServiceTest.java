package com.example.configmgr.ai.session;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.form.FormAttachmentStore;
import com.example.configmgr.ai.form.RedisFormAttachmentStore;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.memory.RedisChatMemoryRepository;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.LeaseService;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.config.AppProperties;
import com.example.configmgr.file.FileStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisClusterNode;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话销毁的编排/存储语义（真 RunStore + 真 Redis 记忆仓储 + 真附件登记簿 + H2 + 进程内 Redis 替身）。
 *
 * <p>覆盖用户裁决的验收面：A/B 只删 A、abc/abcd 前缀安全、业务数据全在、重复删除幂等、
 * 枚举失败不半删、Redis 部分失败如实且可重试、附件只按登记路径删、feedback/chat 删除、
 * 活跃 run 先取消且 13 键延后、销毁后记忆不被写回、本地镜像/草稿给出客户端清理指令。
 */
class AiSessionPurgeServiceTest {

	private static final String SESSION_A = "sess-a";

	private static final String SESSION_B = "sess-b";

	@TempDir
	Path tempDir;

	private Fixture fixture;

	@BeforeEach
	void setUp() {
		this.fixture = new Fixture(this.tempDir);
	}

	// ── A/B 与业务隔离 ───────────────────────────────────────────────────────

	@Test
	void purgeDeletesOnlySessionAAndLeavesSessionBAndBusinessDataUntouched() throws Exception {
		seedFullSession(SESSION_A, "run-a", "call-a");
		seedFullSession(SESSION_B, "run-b", "call-b");
		Path businessFile = writeFile("business.bin", "keep");
		seedBusinessKeys();
		this.fixture.jdbc.execute("CREATE TABLE jobs (id BIGINT PRIMARY KEY, name VARCHAR(50))");
		this.fixture.jdbc.update("INSERT INTO jobs (id, name) VALUES (1, 'keep-job')");

		AiSessionPurgeService.PurgeResult result = this.fixture.service.purge(SESSION_A,
				this.fixture.store.instanceId());

		assertThat(result.complete()).as("静态数据全删成功").isTrue();
		assertThat(result.found()).isTrue();
		assertThat(result.errors()).isEmpty();
		assertThat(result.deferred()).isEmpty();
		assertThat(result.databaseDeleted().feedbackRows()).isEqualTo(1);
		assertThat(result.databaseDeleted().chatMessageRows()).isEqualTo(1);
		assertThat(result.attachments().registrationsDeleted()).isEqualTo(1);
		assertThat(result.attachments().filesDeleted()).isEqualTo(1);
		assertThat(result.clientCleanup().mirrorKey()).isEqualTo("ai-mirror:" + SESSION_A);

		// A 的 AI 数据全没了
		for (String key : thirteenKeys("run-a")) {
			assertThat(this.fixture.fake.strings).as("run 级 13 键之一必须删除：%s", key)
				.doesNotContainKey(key);
		}
		for (String key : List.of(this.fixture.store.beatKey("run-a"), this.fixture.store.activityKey("run-a"),
				this.fixture.cancellations.keyFor("run-a"))) {
			assertThat(this.fixture.fake.strings).as("观测/取消键也必须删除：%s", key).doesNotContainKey(key);
		}
		assertThat(this.fixture.fake.strings).doesNotContainKey(this.fixture.store.runKey("run-a"));
		assertThat(this.fixture.fake.hashes).doesNotContainKey(RunStore.CONTEXT_PREFIX + SESSION_A);
		assertThat(this.fixture.fake.lists).doesNotContainKey(RunStore.CONTEXT_LEDGER_PREFIX + SESSION_A);
		assertThat(this.fixture.fake.strings)
			.doesNotContainKey(RunStore.CONTEXT_ARCHIVE_PREFIX + SESSION_A + ":1");
		assertThat(this.fixture.fake.lists).doesNotContainKey(RunStore.SAFETY_PREFIX + SESSION_A);
		assertThat(this.fixture.fake.lists).doesNotContainKey(this.fixture.memory.keyFor(SESSION_A));
		assertThat(this.fixture.fake.strings).doesNotContainKey(AiSessionService.KEY_PREFIX + SESSION_A);
		assertThat(this.fixture.fake.strings).doesNotContainKey(RunStore.SESSION_CURRENT_PREFIX + SESSION_A);
		assertThat(this.fixture.fake.sets.getOrDefault(RunStore.RUN_INDEX, Set.of())).doesNotContain("run-a");
		assertThat(this.fixture.store.allRunIds()).as("重启扫描的发现面只剩 B（A 不会复活）")
			.containsExactly("run-b");
		assertThat(this.fixture.store.get("run-a")).isNull();
		assertThat(this.fixture.store.sessionCurrentRunId(SESSION_A)).isNull();
		assertThat(countFeedback(SESSION_A)).isZero();
		assertThat(countChat(SESSION_A)).isZero();
		assertThat(this.fixture.attachments.find(this.fixture.attachmentA.fileId())).isNull();
		assertThat(Files.exists(this.fixture.fileA)).isFalse();

		// B 的 AI 数据一条都没动
		for (String key : thirteenKeys("run-b")) {
			assertThat(this.fixture.fake.strings).as("B 的 run 级键必须保留：%s", key).containsKey(key);
		}
		assertThat(this.fixture.fake.strings).containsKey(this.fixture.store.runKey("run-b"));
		assertThat(this.fixture.fake.hashes).containsKey(RunStore.CONTEXT_PREFIX + SESSION_B);
		assertThat(this.fixture.fake.lists).containsKey(this.fixture.memory.keyFor(SESSION_B));
		assertThat(this.fixture.fake.strings).containsKey(AiSessionService.KEY_PREFIX + SESSION_B);
		assertThat(this.fixture.fake.sets.getOrDefault(RunStore.RUN_INDEX, Set.of())).contains("run-b");
		assertThat(countFeedback(SESSION_B)).isEqualTo(1);
		assertThat(countChat(SESSION_B)).isEqualTo(1);
		assertThat(this.fixture.attachments.find(this.fixture.attachmentB.fileId())).isNotNull();
		assertThat(Files.exists(this.fixture.fileB)).isTrue();

		// 业务面：键、文件、表都不得被碰
		for (String key : businessKeys()) {
			assertThat(this.fixture.fake.strings).as("业务键 %s 必须原样保留", key).containsKey(key);
		}
		assertThat(Files.exists(businessFile)).as("业务文件不得被删").isTrue();
		assertThat(this.fixture.jdbc.queryForObject("SELECT COUNT(*) FROM jobs", Integer.class)).isEqualTo(1);
	}

	@Test
	void prefixCollisionsDoNotDeleteNeighbourSessions() throws Exception {
		// abc / abcd / abc:1 三类碰撞：精确键 + 归档后缀纯数字判据
		seedContextAndMemory("abc", null);
		seedContextAndMemory("abcd", null);
		seedContextAndMemory("abc:1", null);
		seedContextAndMemory("a*b", null);
		seedContextAndMemory("axb", null);
		this.fixture.fake.strings.put(RunStore.CONTEXT_ARCHIVE_PREFIX + "a*b:1", "[]");
		this.fixture.fake.strings.put(RunStore.CONTEXT_ARCHIVE_PREFIX + "axb:1", "[]");

		this.fixture.service.purge("abc", "test");
		this.fixture.service.purge("a*b", "test");

		assertThat(this.fixture.fake.hashes).doesNotContainKey(RunStore.CONTEXT_PREFIX + "abc");
		assertThat(this.fixture.fake.lists).doesNotContainKey(RunStore.CONTEXT_LEDGER_PREFIX + "abc");
		assertThat(this.fixture.fake.lists).doesNotContainKey(RunStore.SAFETY_PREFIX + "abc");
		assertThat(this.fixture.fake.strings).doesNotContainKey(RunStore.CONTEXT_ARCHIVE_PREFIX + "abc:1");
		assertThat(this.fixture.fake.lists).doesNotContainKey(this.fixture.memory.keyFor("abc"));

		assertThat(this.fixture.fake.hashes).as("abcd 的上下文必须保留")
			.containsKey(RunStore.CONTEXT_PREFIX + "abcd");
		assertThat(this.fixture.fake.lists).as("abcd 的台账必须保留")
			.containsKey(RunStore.CONTEXT_LEDGER_PREFIX + "abcd");
		assertThat(this.fixture.fake.lists).as("abcd 的安全台账必须保留")
			.containsKey(RunStore.SAFETY_PREFIX + "abcd");
		assertThat(this.fixture.fake.strings).as("abcd 的归档必须保留")
			.containsKey(RunStore.CONTEXT_ARCHIVE_PREFIX + "abcd:1");
		assertThat(this.fixture.fake.lists).as("abcd 的记忆必须保留")
			.containsKey(this.fixture.memory.keyFor("abcd"));

		assertThat(this.fixture.fake.hashes).as("abc:1 的上下文必须保留")
			.containsKey(RunStore.CONTEXT_PREFIX + "abc:1");
		assertThat(this.fixture.fake.strings).as("abc 的扫描不得误删 abc:1 的 turn 2 归档")
			.containsKey(RunStore.CONTEXT_ARCHIVE_PREFIX + "abc:1:2");
		assertThat(this.fixture.fake.lists).as("abc:1 的记忆必须保留")
			.containsKey(this.fixture.memory.keyFor("abc:1"));

		// glob 元字符：a*b 的 sessionId 不得被当成通配符
		assertThat(this.fixture.fake.hashes).as("a*b 自己的上下文已删")
			.doesNotContainKey(RunStore.CONTEXT_PREFIX + "a*b");
		assertThat(this.fixture.fake.strings).as("a*b 自己的归档已删")
			.doesNotContainKey(RunStore.CONTEXT_ARCHIVE_PREFIX + "a*b:1");
		assertThat(this.fixture.fake.hashes).as("axb 的上下文不得被 a*b 的通配误删")
			.containsKey(RunStore.CONTEXT_PREFIX + "axb");
		assertThat(this.fixture.fake.strings).as("axb 的归档不得被 a*b 的通配误删")
			.containsKey(RunStore.CONTEXT_ARCHIVE_PREFIX + "axb:1");
	}

	@Test
	void sessionKeysWithEmbeddedCurrentPrefixAreNotDeletedAcrossSessions() {
		String foreignRecordKey = RunStore.SESSION_CURRENT_PREFIX + "s1"; // = ai:session:current:s1
		this.fixture.fake.strings.put(foreignRecordKey,
				"{\"sessionId\":\"current:s1\",\"lastActivity\":\"2026-10-25T00:00:00Z\",\"rounds\":1}");

		AiSessionPurgeService.PurgeResult first = this.fixture.service.purge("s1", "test");

		assertThat(first.phase()).isNull();
		assertThat(first.complete()).as("没有删到他会话的数据，本会话也没有残留").isTrue();
		assertThat(first.runs()).as("JSON 值不是 runId，不得被当成活跃轮次").isEmpty();
		assertThat(this.fixture.fake.strings).as("另一会话（current:s1）的会话记录不得被 s1 的销毁删掉")
			.containsKey(foreignRecordKey);

		AiSessionPurgeService.PurgeResult second = this.fixture.service.purge("current:s1", "test");

		assertThat(second.complete()).isTrue();
		assertThat(this.fixture.fake.strings).as("按自己的 sessionId 销毁时，该记录才应被删")
			.doesNotContainKey(foreignRecordKey);
	}

	@Test
	void repeatPurgeIsIdempotentAndSecondCallReportsNothingFound() throws Exception {
		seedFullSession(SESSION_A, "run-a", "call-a");

		AiSessionPurgeService.PurgeResult first = this.fixture.service.purge(SESSION_A, "test");
		AiSessionPurgeService.PurgeResult second = this.fixture.service.purge(SESSION_A, "test");

		assertThat(first.complete()).isTrue();
		assertThat(second.complete()).as("重复删除必须 200/complete=true").isTrue();
		assertThat(second.found()).as("第二次什么也没有").isFalse();
		assertThat(second.errors()).isEmpty();
		assertThat(second.deferred()).isEmpty();
		assertThat(second.databaseDeleted().feedbackRows()).isZero();
		assertThat(second.attachments().registrationsDeleted()).isZero();
	}

	// ── 失败口径：枚举失败不半删；部分失败如实可重试 ─────────────────────────

	@Test
	void enumerationFailureDeletesNothing() throws Exception {
		seedFullSession(SESSION_A, "run-a", "call-a");
		this.fixture.fake.failScan = true;

		AiSessionPurgeService.PurgeResult result = this.fixture.service.purge(SESSION_A, "test");

		assertThat(result.complete()).isFalse();
		assertThat(result.phase()).isEqualTo("enumerate");
		assertThat(this.fixture.fake.deleted).as("枚举失败一个数据键都不许删")
			.noneMatch(key -> !key.equals(SessionPurgeGuard.PENDING_PREFIX + SESSION_A));
		assertThat(this.fixture.fake.strings).doesNotContainKey(SessionPurgeGuard.PENDING_PREFIX + SESSION_A);
		assertThat(this.fixture.fake.strings).containsKey(this.fixture.store.runKey("run-a"));
		assertThat(this.fixture.fake.hashes).containsKey(RunStore.CONTEXT_PREFIX + SESSION_A);
		assertThat(countFeedback(SESSION_A)).isEqualTo(1);
		assertThat(countChat(SESSION_A)).isEqualTo(1);
		assertThat(Files.exists(this.fixture.fileA)).isTrue();
		assertThat(this.fixture.fake.strings).doesNotContainKey(SessionPurgeGuard.KEY_PREFIX + SESSION_A);
	}

	@Test
	void redisPartialFailureIsReportedThenRetryCompletes() throws Exception {
		seedFullSession(SESSION_A, "run-a", "call-a");
		this.fixture.fake.failDelete = true;

		AiSessionPurgeService.PurgeResult partial = this.fixture.service.purge(SESSION_A, "test");

		assertThat(partial.complete()).isFalse();
		assertThat(partial.errors()).as("部分失败必须如实列出").isNotEmpty();
		assertThat(partial.databaseDeleted().feedbackRows()).isEqualTo(1);
		assertThat(countFeedback(SESSION_A)).as("H2 已先删").isZero();
		assertThat(this.fixture.fake.strings).as("Redis 键仍在（可重试）")
			.containsKey(this.fixture.store.runKey("run-a"));

		this.fixture.fake.failDelete = false;
		AiSessionPurgeService.PurgeResult retry = this.fixture.service.purge(SESSION_A, "test");

		assertThat(retry.complete()).as("修复后重试应完成").isTrue();
		assertThat(retry.errors()).isEmpty();
		assertThat(this.fixture.fake.strings).doesNotContainKey(this.fixture.store.runKey("run-a"));
		assertThat(this.fixture.fake.hashes).doesNotContainKey(RunStore.CONTEXT_PREFIX + SESSION_A);
		assertThat(Files.exists(this.fixture.fileA)).isFalse();
	}

	// ── 活跃 run：取消一次 + 13 键延后 + 终态后补删 ─────────────────────────

	@Test
	void activeRunIsCancelledOnceAndItsRunKeysAreDeferredUntilTerminal() throws Exception {
		seedRun("run-a", SESSION_A, RunSnapshot.RUNNING);
		seedPending("run-a", "call-a");
		seedMemory(SESSION_A);
		when(this.fixture.gate.currentRunId(SESSION_A)).thenReturn("run-a");

		AiSessionPurgeService.PurgeResult first = this.fixture.service.purge(SESSION_A, "test");

		assertThat(first.complete()).isFalse();
		assertThat(first.deferred()).hasSize(1);
		assertThat(first.deferred().get(0).runId()).isEqualTo("run-a");
		assertThat(first.deferred().get(0).reason()).isEqualTo("RUN_ACTIVE");
		assertThat(this.fixture.fake.strings).as("活跃 run 的 13 键必须延后").containsKey(
				this.fixture.store.runKey("run-a"));
		assertThat(this.fixture.fake.strings).containsKey(this.fixture.store.pendingKey("run-a"));
		assertThat(this.fixture.fake.strings).as("取消标志是重启不复活的前提").containsKey(
				this.fixture.cancellations.keyFor("run-a"));
		assertThat(this.fixture.cancellations.cancelState("run-a"))
			.as("重启续跑扫描看到的是 CANCELLED（不会复活执行）")
			.isEqualTo(CancellationRegistry.CancelState.CANCELLED);
		assertThat(this.fixture.store.allRunIds()).as("活跃 run 仍可被发现，但带着取消标志").contains("run-a");
		this.fixture.memory.saveAll(SESSION_A, List.of(new UserMessage("终局迟到写回")));
		assertThat(this.fixture.fake.lists).as("活跃 run 延后期间，终局记忆写仍被 guard 拒绝")
			.doesNotContainKey(this.fixture.memory.keyFor(SESSION_A));
		assertThat(first.clientCleanup().draftToolCallIds()).containsExactly("call-a");
		verify(this.fixture.cancellations, times(1)).cancel("run-a", "test");

		// 第二次仍活跃：不得重复取消（真实 CancellationRegistry 从 Redis/本地加速态读到已取消）
		AiSessionPurgeService.PurgeResult second = this.fixture.service.purge(SESSION_A, "test");
		assertThat(second.deferred()).hasSize(1);
		verify(this.fixture.cancellations, times(1)).cancel("run-a", "test");

		// 终态后重试：13 键补删，不再调用 cancel
		writeSnapshot("run-a", SESSION_A, RunSnapshot.CANCELLED);
		AiSessionPurgeService.PurgeResult third = this.fixture.service.purge(SESSION_A, "test");

		assertThat(third.complete()).isTrue();
		assertThat(third.deferred()).isEmpty();
		assertThat(this.fixture.fake.strings).doesNotContainKey(this.fixture.store.runKey("run-a"));
		assertThat(this.fixture.fake.strings).doesNotContainKey(this.fixture.store.pendingKey("run-a"));
		assertThat(this.fixture.fake.strings).as("终态后取消标志随观测键一起清掉")
			.doesNotContainKey(this.fixture.cancellations.keyFor("run-a"));
		verify(this.fixture.cancellations, times(1)).cancel("run-a", "test");
	}

	@Test
	void purgeGuardBlocksLateMemoryWriteBack() throws Exception {
		seedMemory(SESSION_A);

		AiSessionPurgeService.PurgeResult result = this.fixture.service.purge(SESSION_A, "test");
		assertThat(result.complete()).isTrue();
		assertThat(this.fixture.fake.lists).doesNotContainKey(this.fixture.memory.keyFor(SESSION_A));

		this.fixture.memory.saveAll(SESSION_A, List.of(new UserMessage("收盘写回")));

		assertThat(this.fixture.fake.lists).as("销毁后终局 saveAll 不得复活记忆")
			.doesNotContainKey(this.fixture.memory.keyFor(SESSION_A));
	}

	@Test
	void databaseFailureAbortsBeforeAnyRedisDeletionAndDisarmsGuard() throws Exception {
		seedFullSession(SESSION_A, "run-a", "call-a");
		SessionPurgeDatabase failing = new SessionPurgeDatabase(this.fixture.jdbc,
				new DataSourceTransactionManager(this.fixture.source)) {
			@Override
			public Deleted delete(String sessionId, Snapshot snapshot) {
				throw new IllegalStateException("模拟 H2 删除失败");
			}
		};
		AiSessionPurgeService service = new AiSessionPurgeService(this.fixture.fake.redis, this.fixture.store,
				this.fixture.gate, this.fixture.cancellations, this.fixture.memory, this.fixture.attachments,
				this.fixture.files, failing, this.fixture.guard, this.fixture.pendingStore, this.fixture.properties, Duration.ZERO);

		AiSessionPurgeService.PurgeResult result = service.purge(SESSION_A, "test");

		assertThat(result.complete()).isFalse();
		assertThat(result.phase()).isEqualTo("database");
		assertThat(this.fixture.fake.deleted).as("H2 失败时 Redis 数据面一个键都不许删")
			.noneMatch(key -> !key.equals(SessionPurgeGuard.KEY_PREFIX + SESSION_A)
					&& !key.equals(SessionPurgeGuard.PENDING_PREFIX + SESSION_A));
		assertThat(this.fixture.fake.deleted).doesNotContain(this.fixture.store.runKey("run-a"),
				RunStore.CONTEXT_PREFIX + SESSION_A);
		assertThat(this.fixture.fake.strings).containsKey(this.fixture.store.runKey("run-a"));
		assertThat(this.fixture.fake.hashes).containsKey(RunStore.CONTEXT_PREFIX + SESSION_A);
		assertThat(countFeedback(SESSION_A)).isEqualTo(1);
		assertThat(Files.exists(this.fixture.fileA)).isTrue();
		assertThat(this.fixture.fake.strings).as("未删任何数据时必须撤销守卫与 pending（否则会话记忆被误锁）")
			.doesNotContainKey(SessionPurgeGuard.KEY_PREFIX + SESSION_A)
			.doesNotContainKey(SessionPurgeGuard.PENDING_PREFIX + SESSION_A);
	}

	// ── 附件相对键：删前/删后必须同一条 resolve 后的真实路径 ─────────────────

	@Test
	void relativeStorageKeyDeleteFailureIsReportedAndRegistrationIsKept() throws Exception {
		Path base = this.tempDir.resolve("relative-store");
		Files.createDirectories(base);
		Path realFile = base.resolve("att-rel.pdf");
		Files.writeString(realFile, "relative-attachment");
		AppProperties app = new AppProperties();
		app.getFile().setStoragePath(base.toString());
		// delete 不真正删：模拟权限/占用等失败，但不依赖 Windows 权限。
		FileStorageService failing = new FileStorageService(app) {
			@Override
			public void delete(String keyOrPath) {
				// no-op：文件仍在
			}
		};
		AiSessionPurgeService service = serviceWithFiles(failing);
		FormAttachmentStore.Attachment attachment = this.fixture.attachments.register("run-rel", "call-rel",
				SESSION_A, "att-rel.pdf", 10L, "application/pdf", "att-rel.pdf");

		AiSessionPurgeService.PurgeResult result = service.purge(SESSION_A, "test");

		assertThat(result.complete()).as("文件删除失败不得报成功").isFalse();
		assertThat(result.errors()).as("必须留下可重试事实").isNotEmpty();
		assertThat(result.attachments().failedFileIds()).contains(attachment.fileId());
		assertThat(this.fixture.attachments.find(attachment.fileId())).as("登记必须保留以便重试").isNotNull();
		assertThat(Files.exists(realFile)).as("文件仍在（失败事实）").isTrue();
	}

	@Test
	void relativeStorageKeySuccessDeletesTheResolvedFile() throws Exception {
		Path base = this.tempDir.resolve("relative-store-ok");
		Files.createDirectories(base);
		Path realFile = base.resolve("att-ok.pdf");
		Files.writeString(realFile, "relative-attachment");
		AppProperties app = new AppProperties();
		app.getFile().setStoragePath(base.toString());
		AiSessionPurgeService service = serviceWithFiles(new FileStorageService(app));
		FormAttachmentStore.Attachment attachment = this.fixture.attachments.register("run-ok", "call-ok",
				SESSION_A, "att-ok.pdf", 10L, "application/pdf", "att-ok.pdf");

		AiSessionPurgeService.PurgeResult result = service.purge(SESSION_A, "test");

		assertThat(result.complete()).isTrue();
		assertThat(result.attachments().filesDeleted()).isEqualTo(1);
		assertThat(Files.exists(realFile)).isFalse();
		assertThat(this.fixture.attachments.find(attachment.fileId())).isNull();
	}

	private AiSessionPurgeService serviceWithFiles(FileStorageService fileStorage) {
		return new AiSessionPurgeService(this.fixture.fake.redis, this.fixture.store, this.fixture.gate,
				this.fixture.cancellations, this.fixture.memory, this.fixture.attachments, fileStorage,
				this.fixture.database, this.fixture.guard, this.fixture.pendingStore, this.fixture.properties, Duration.ZERO);
	}

	private AiSessionPurgeService serviceWithStore(RunStore runStore) {
		return new AiSessionPurgeService(this.fixture.fake.redis, runStore, this.fixture.gate,
				this.fixture.cancellations, this.fixture.memory, this.fixture.attachments, this.fixture.files,
				this.fixture.database, this.fixture.guard, this.fixture.pendingStore, this.fixture.properties, Duration.ZERO);
	}

	// ── 会话→runId 索引：有索引时不扫全量 run 快照 ───────────────────────────

	@Test
	void sessionRunIndexLimitsEnumerationToThisSessionAndLeavesOthersUntouched() throws Exception {
		seedRun("run-a", SESSION_A, RunSnapshot.DONE);
		seedRun("run-b", SESSION_B, RunSnapshot.DONE);
		this.fixture.fake.sets.put("ai:session:runs:" + SESSION_A, new java.util.LinkedHashSet<>(Set.of("run-a")));
		// 模拟“已完成一次全局枚举并回填”的状态：有 run-a + 完整标记，才允许走快路径。
		this.fixture.store.backfillSessionRuns(SESSION_A, Set.of("run-a"));
		RunStore spyStore = org.mockito.Mockito.spy(this.fixture.store);
		AiSessionPurgeService service = serviceWithStore(spyStore);

		AiSessionPurgeService.PurgeResult result = service.purge(SESSION_A, "test");

		assertThat(result.complete()).isTrue();
		org.mockito.Mockito.verify(spyStore, org.mockito.Mockito.never()).get("run-b");
		assertThat(this.fixture.fake.sets.getOrDefault(RunStore.RUN_INDEX, Set.of())).contains("run-b");
		assertThat(this.fixture.fake.strings).containsKey(this.fixture.store.runKey("run-b"));
		assertThat(this.fixture.store.sessionRunIndex(SESSION_A).runIds()).isEmpty();
		assertThat(this.fixture.fake.sets).as("会话销毁同时删除本会话索引及回填标记")
			.doesNotContainKey(RunStore.SESSION_RUNS_PREFIX + SESSION_A);
		assertThat(this.fixture.store.sessionRunIndex(SESSION_A).complete()).isFalse();

		// 重复删除：索引已空 ⇒ 回退全局索引；仍不得误删 B、仍幂等。
		AiSessionPurgeService.PurgeResult again = service.purge(SESSION_A, "test");
		assertThat(again.complete()).isTrue();
		assertThat(this.fixture.fake.strings).containsKey(this.fixture.store.runKey("run-b"));
	}

	// ── 老数据索引不完整：非空但未回填时必须回退全局 ───────────────────────

	@Test
	void legacyNonEmptyButUnbackfilledSessionIndexStillDeletesOldRuns() throws Exception {
		seedRun("run-old", SESSION_A, RunSnapshot.DONE);
		seedRun("run-new", SESSION_A, RunSnapshot.DONE);
		// 升级后 create 只 SADD 了新 run；老 run 仍在全局 ai:runs，但不在 session 索引。
		this.fixture.fake.sets.put("ai:session:runs:" + SESSION_A,
				new java.util.LinkedHashSet<>(Set.of("run-new")));

		AiSessionPurgeService.PurgeResult result = this.fixture.service.purge(SESSION_A, "test");

		assertThat(result.complete()).isTrue();
		assertThat(result.runs()).extracting(AiSessionPurgeService.RunView::runId)
			.containsExactlyInAnyOrder("run-old", "run-new");
		assertThat(this.fixture.fake.strings).doesNotContainKey(this.fixture.store.runKey("run-old"));
		assertThat(this.fixture.fake.strings).doesNotContainKey(this.fixture.store.runKey("run-new"));
	}

	@Test
	void sessionIndexReadFailureFallsBackToGlobalAndStillDeletesOldRuns() throws Exception {
		seedRun("run-old", SESSION_A, RunSnapshot.DONE);
		seedRun("run-new", SESSION_A, RunSnapshot.DONE);
		RunStore spyStore = org.mockito.Mockito.spy(this.fixture.store);
		when(spyStore.sessionRunIndex(SESSION_A))
			.thenReturn(new RunStore.SessionRunIndex(Set.of("run-new"), true, false));
		AiSessionPurgeService service = serviceWithStore(spyStore);

		AiSessionPurgeService.PurgeResult result = service.purge(SESSION_A, "test");

		assertThat(result.complete()).isTrue();
		assertThat(result.runs()).extracting(AiSessionPurgeService.RunView::runId)
			.containsExactlyInAnyOrder("run-old", "run-new");
		assertThat(this.fixture.fake.strings).doesNotContainKey(this.fixture.store.runKey("run-old"));
	}

	@Test
	void archiveScanNodeFailureAbortsPurgeWithoutComplete() throws Exception {
		seedFullSession(SESSION_A, "run-a", "call-a");
		this.fixture.fake.clusterMode = true;
		RedisClusterNode m1 = this.fixture.fake.addClusterMaster("10.0.0.1", 7001);
		RedisClusterNode m2 = this.fixture.fake.addClusterMaster("10.0.0.2", 7001);
		this.fixture.fake.assignToMaster(m1, RunStore.CONTEXT_ARCHIVE_PREFIX + SESSION_A + ":1");
		this.fixture.fake.assignToMaster(m2, RunStore.CONTEXT_ARCHIVE_PREFIX + SESSION_A + ":2");
		this.fixture.fake.failNode = m2;

		AiSessionPurgeService.PurgeResult result = this.fixture.service.purge(SESSION_A, "test");

		assertThat(result.complete()).isFalse();
		assertThat(result.phase()).isEqualTo("enumerate");
		assertThat(this.fixture.fake.strings).containsKey(this.fixture.store.runKey("run-a"));
		assertThat(this.fixture.fake.strings)
			.containsKey(RunStore.CONTEXT_ARCHIVE_PREFIX + SESSION_A + ":1");
		assertThat(this.fixture.fake.clusterConnectionCloses).as("失败路径关闭 cluster 连接").hasValue(1);
	}

	// ── durable pending + 自动重试闭环 ────────────────────────────────────────

	@Test
	void failedPurgeIsRetriedByRunnerToCompletionAndGuardEntersRetention() throws Exception {
		seedFullSession(SESSION_A, "run-a", "call-a");
		this.fixture.fake.failDelete = true;
		AiSessionPurgeService.PurgeResult first = this.fixture.service.purge(SESSION_A, "test");
		assertThat(first.complete()).isFalse();
		String purgeId = first.purgeId();
		assertThat(this.fixture.pendingStore.current(SESSION_A)).isNotNull();
		assertThat(this.fixture.pendingStore.current(SESSION_A).purgeId()).isEqualTo(purgeId);

		this.fixture.fake.failDelete = false;
		SessionPurgeRetryRunner runner = new SessionPurgeRetryRunner(this.fixture.pendingStore,
				this.fixture.service, grantedLease());

		SessionPurgeRetryRunner.RetryReport report = runner.runOnce(System.currentTimeMillis() + 3_600_000L);

		assertThat(report.completed()).isEqualTo(1);
		assertThat(report.failed()).isZero();
		assertThat(this.fixture.pendingStore.current(SESSION_A)).as("完成后 pending 必须移除").isNull();
		assertThat(this.fixture.fake.strings).doesNotContainKey(this.fixture.store.runKey("run-a"));
		assertThat(this.fixture.fake.strings).doesNotContainKey(RunStore.CONTEXT_PREFIX + SESSION_A);
		assertThat(this.fixture.guard.isArmed(SESSION_A)).as("完成后进入有限保留期").isTrue();
		assertThat(this.fixture.fake.strings).containsKey(SessionPurgeGuard.KEY_PREFIX + SESSION_A);
	}

	@Test
	void pendingKeepsGuardArmedAfterGuardTtlWouldHaveExpired() {
		// 只有 pending、没有 guard key（模拟 guard TTL 已到）：仍必须 armed，且记忆写被拒绝。
		this.fixture.pendingStore.register(SESSION_A, "purge-pending", "test", 1_000L);
		this.fixture.fake.strings.remove(SessionPurgeGuard.KEY_PREFIX + SESSION_A);

		assertThat(this.fixture.guard.isArmed(SESSION_A)).isTrue();
		this.fixture.memory.saveAll(SESSION_A, List.of(new UserMessage("延迟写回")));
		assertThat(this.fixture.fake.lists).doesNotContainKey(this.fixture.memory.keyFor(SESSION_A));
	}

	@Test
	void runnerFailureDoesNotDisarmOrDropPending() throws Exception {
		seedFullSession(SESSION_A, "run-a", "call-a");
		this.fixture.fake.failDelete = true;
		this.fixture.service.purge(SESSION_A, "test");
		SessionPurgeRetryRunner runner = new SessionPurgeRetryRunner(this.fixture.pendingStore,
				this.fixture.service, grantedLease());

		SessionPurgeRetryRunner.RetryReport report = runner.runOnce(System.currentTimeMillis() + 3_600_000L);

		assertThat(report.failed()).isEqualTo(1);
		assertThat(this.fixture.pendingStore.current(SESSION_A)).as("失败不得删 pending").isNotNull();
		assertThat(this.fixture.guard.isArmed(SESSION_A)).as("失败不得 blind disarm").isTrue();
	}

	@Test
	void runnerRecoversOrphanPendingWhenQueueEntryWasLost() {
		this.fixture.pendingStore.register(SESSION_A, "purge-orphan", "test", 1_000L);
		this.fixture.fake.lists.remove(SessionPurgePendingStore.QUEUE_KEY);
		SessionPurgeRetryRunner runner = new SessionPurgeRetryRunner(this.fixture.pendingStore,
				this.fixture.service, grantedLease());

		SessionPurgeRetryRunner.RetryReport report = runner.runOnce(System.currentTimeMillis() + 3_600_000L);

		assertThat(report.recovered()).isEqualTo(1);
		assertThat(report.completed()).isEqualTo(1);
		assertThat(this.fixture.pendingStore.current(SESSION_A)).isNull();
	}

	@Test
	void runnerDoesNothingWhenAnotherInstanceHoldsTheLease() {
		SessionPurgePendingStore pending = mock(SessionPurgePendingStore.class);
		AiSessionPurgeService service = mock(AiSessionPurgeService.class);
		LeaseService leases = mock(LeaseService.class);
		when(leases.acquire(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(Duration.class)))
			.thenReturn(new LeaseService.AcquireResult(LeaseService.AcquireOutcome.HELD_ELSEWHERE, null, false));
		SessionPurgeRetryRunner runner = new SessionPurgeRetryRunner(pending, service, leases);

		SessionPurgeRetryRunner.RetryReport report = runner.runOnce(System.currentTimeMillis() + 3_600_000L);

		assertThat(report.lockAcquired()).isFalse();
		org.mockito.Mockito.verifyNoInteractions(service);
		org.mockito.Mockito.verifyNoInteractions(pending);
	}

	// ── 重复 DELETE：复用第一轮的 durable 义务（不得被后续中止抹掉） ─────────

	@Test
	void secondPurgeDatabaseFailureKeepsFirstRoundPendingAttemptsAndGuard() throws Exception {
		seedRun("run-a", SESSION_A, RunSnapshot.RUNNING);
		seedPending("run-a", "call-a");
		seedMemory(SESSION_A);
		when(this.fixture.gate.currentRunId(SESSION_A)).thenReturn("run-a");

		AiSessionPurgeService.PurgeResult first = this.fixture.service.purge(SESSION_A, "test");
		assertThat(first.complete()).isFalse();
		SessionPurgePendingStore.Pending firstPending = this.fixture.pendingStore.current(SESSION_A);
		assertThat(firstPending).isNotNull();
		assertThat(firstPending.attempts()).isPositive();
		String firstPurgeId = firstPending.purgeId();

		// 第二次 DELETE：枚举成功、H2 删除失败（真实成因：DB 重启/锁超时）。旧实现会在这里
		// completePendingQuietly + disarmQuietly，把第一轮的 pending 与守卫一起抹掉。
		AiSessionPurgeService failing = serviceWithDatabase(new SessionPurgeDatabase(this.fixture.jdbc,
				new DataSourceTransactionManager(this.fixture.source)) {
			@Override
			public Deleted delete(String sessionId, Snapshot snapshot) {
				throw new IllegalStateException("模拟 H2 删除失败");
			}
		});
		AiSessionPurgeService.PurgeResult second = failing.purge(SESSION_A, "test");

		assertThat(second.complete()).isFalse();
		assertThat(second.phase()).isEqualTo("database");
		assertThat(second.purgeId()).as("重复销毁必须复用第一轮 purgeId（不重开一轮）").isEqualTo(firstPurgeId);
		SessionPurgePendingStore.Pending secondPending = this.fixture.pendingStore.current(SESSION_A);
		assertThat(secondPending).as("中止回滚不得删除第一轮登记的 pending").isNotNull();
		assertThat(secondPending.purgeId()).isEqualTo(firstPurgeId);
		assertThat(secondPending.attempts()).as("复用 pending 不得重置 attempts").isEqualTo(firstPending.attempts());
		assertThat(this.fixture.guard.isArmed(SESSION_A)).as("不得 disarm 第一轮装的守卫").isTrue();
		assertThat(this.fixture.fake.strings.get(SessionPurgeGuard.KEY_PREFIX + SESSION_A))
			.startsWith(firstPurgeId + "|");
		assertThat(this.fixture.fake.strings).as("第一轮 deferred 的 run 13 键必须还在")
			.containsKey(this.fixture.store.runKey("run-a"));

		// 第一轮的 deferred 仍能由调度收尾
		writeSnapshot("run-a", SESSION_A, RunSnapshot.CANCELLED);
		SessionPurgeRetryRunner runner = new SessionPurgeRetryRunner(this.fixture.pendingStore,
				this.fixture.service, grantedLease());
		SessionPurgeRetryRunner.RetryReport report = runner.runOnce(System.currentTimeMillis() + 3_600_000L);

		assertThat(report.completed()).isEqualTo(1);
		assertThat(this.fixture.pendingStore.current(SESSION_A)).isNull();
		assertThat(this.fixture.fake.strings).doesNotContainKey(this.fixture.store.runKey("run-a"));
	}

	@Test
	void secondPurgeEnumerateFailureKeepsFirstRoundPendingAndGuard() throws Exception {
		seedRun("run-a", SESSION_A, RunSnapshot.RUNNING);
		seedPending("run-a", "call-a");
		seedMemory(SESSION_A);
		when(this.fixture.gate.currentRunId(SESSION_A)).thenReturn("run-a");

		AiSessionPurgeService.PurgeResult first = this.fixture.service.purge(SESSION_A, "test");
		String firstPurgeId = this.fixture.pendingStore.current(SESSION_A).purgeId();
		int attempts = this.fixture.pendingStore.current(SESSION_A).attempts();

		this.fixture.fake.failScan = true;
		AiSessionPurgeService.PurgeResult second = this.fixture.service.purge(SESSION_A, "test");
		this.fixture.fake.failScan = false;

		assertThat(second.phase()).isEqualTo("enumerate");
		assertThat(second.complete()).isFalse();
		assertThat(second.purgeId()).isEqualTo(firstPurgeId);
		SessionPurgePendingStore.Pending pending = this.fixture.pendingStore.current(SESSION_A);
		assertThat(pending).as("枚举失败不得删掉第一轮的 pending").isNotNull();
		assertThat(pending.attempts()).as("不得重置 attempts").isEqualTo(attempts);
		assertThat(this.fixture.guard.isArmed(SESSION_A)).isTrue();
		assertThat(this.fixture.fake.strings).containsKey(this.fixture.store.runKey("run-a"));
	}

	// ── 前缀碰撞：ai:session:runs:<sid> 可能是 id="runs:<sid>" 的会话记录 ─────

	@Test
	void sessionRunsKeyHeldByAnotherSessionRecordIsNotDeleted() throws Exception {
		seedFullSession(SESSION_A, "run-a", "call-a");
		String collidingRecord = RunStore.SESSION_RUNS_PREFIX + SESSION_A;
		this.fixture.fake.strings.put(collidingRecord, "{\"sessionId\":\"runs:" + SESSION_A + "\",\"title\":\"t\"}");

		AiSessionPurgeService.PurgeResult result = this.fixture.service.purge(SESSION_A, "test");

		assertThat(this.fixture.fake.strings).as("他会话（id=runs:<sid>）的会话记录必须保留")
			.containsKey(collidingRecord);
		assertThat(result.warnings()).anyMatch(warning -> warning.contains("sessionRuns"));
		assertThat(result.complete()).as("跳过他会话键不算本会话删除失败").isTrue();
		assertThat(result.errors()).isEmpty();
	}

	private AiSessionPurgeService serviceWithDatabase(SessionPurgeDatabase database) {
		return new AiSessionPurgeService(this.fixture.fake.redis, this.fixture.store, this.fixture.gate,
				this.fixture.cancellations, this.fixture.memory, this.fixture.attachments, this.fixture.files,
				database, this.fixture.guard, this.fixture.pendingStore, this.fixture.properties, Duration.ZERO);
	}

	private LeaseService grantedLease() {
		LeaseService leases = mock(LeaseService.class);
		when(leases.acquire(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(Duration.class)))
			.thenReturn(new LeaseService.AcquireResult(LeaseService.AcquireOutcome.ACQUIRED,
					new LeaseService.Lease("ai:purge:retry:lock", 1L, "test"), true));
		when(leases.release(org.mockito.ArgumentMatchers.any())).thenReturn(true);
		return leases;
	}

	// ── 种子 ────────────────────────────────────────────────────────────────

	private void seedFullSession(String sessionId, String runId, String toolCallId) throws Exception {
		seedRun(runId, sessionId, RunSnapshot.DONE);
		seedPending(runId, toolCallId);
		seedContextAndMemory(sessionId, runId);
		this.fixture.fake.strings.put(AiSessionService.KEY_PREFIX + sessionId, "{}");
		this.fixture.fake.strings.put(RunStore.SESSION_CURRENT_PREFIX + sessionId, runId);
		this.fixture.jdbc.update("INSERT INTO ai_feedback (message_id, session_id, rating) VALUES (?, ?, ?)",
				"msg-" + sessionId, sessionId, 1);
		this.fixture.jdbc.update("INSERT INTO ai_chat_message (session_id, run_id, seq) VALUES (?, ?, ?)",
				sessionId, runId, 1L);
		String fileId = sessionId.equals(SESSION_A) ? "att-a" : "att-b";
		Path file = writeFile(fileId + ".pdf", "attachment-" + fileId);
		FormAttachmentStore.Attachment stored = this.fixture.attachments.register(runId, toolCallId, sessionId,
				fileId + ".pdf", 10L, "application/pdf", file.toString());
		if (sessionId.equals(SESSION_A)) {
			this.fixture.fileA = file;
			this.fixture.attachmentA = stored;
		}
		else {
			this.fixture.fileB = file;
			this.fixture.attachmentB = stored;
		}
	}

	private void seedContextAndMemory(String sessionId, String runId) {
		this.fixture.fake.hashes.put(RunStore.CONTEXT_PREFIX + sessionId,
				new LinkedHashMap<>(Map.of("turnNo", "0")));
		this.fixture.fake.lists.put(RunStore.CONTEXT_LEDGER_PREFIX + sessionId, new ArrayList<>());
		this.fixture.fake.strings.put(RunStore.CONTEXT_ARCHIVE_PREFIX + sessionId + ":1", "[]");
		this.fixture.fake.strings.put(RunStore.CONTEXT_ARCHIVE_PREFIX + sessionId + ":2", "[]");
		this.fixture.fake.lists.put(RunStore.SAFETY_PREFIX + sessionId, new ArrayList<>());
		if (runId != null) {
			this.fixture.fake.strings.put(RunStore.SESSION_CURRENT_PREFIX + sessionId, runId);
		}
		seedMemory(sessionId);
	}

	private void seedMemory(String sessionId) {
		this.fixture.fake.lists.put(this.fixture.memory.keyFor(sessionId), new ArrayList<>(List.of("{}")));
		this.fixture.fake.sets.computeIfAbsent("chat:mem:__ids__", ignored -> java.util.concurrent.ConcurrentHashMap.newKeySet())
			.add(sessionId);
	}

	private void seedRun(String runId, String sessionId, String status) throws Exception {
		this.fixture.fake.sets.computeIfAbsent(RunStore.RUN_INDEX, ignored -> java.util.concurrent.ConcurrentHashMap.newKeySet())
			.add(runId);
		for (String key : thirteenKeys(runId)) {
			this.fixture.fake.strings.put(key, "seed");
		}
		// 快照必须最后写：13 键清单里也含 ai:run:<runId>，先写会被占位值覆盖。
		writeSnapshot(runId, sessionId, status);
		this.fixture.fake.strings.put(this.fixture.store.beatKey(runId), "1");
		this.fixture.fake.strings.put(this.fixture.store.activityKey(runId), "1");
	}

	private void writeSnapshot(String runId, String sessionId, String status) throws Exception {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(runId);
		snapshot.setSessionId(sessionId);
		snapshot.setStatus(status);
		this.fixture.fake.strings.put(this.fixture.store.runKey(runId),
				this.fixture.mapper.writeValueAsString(snapshot));
	}

	private void seedPending(String runId, String toolCallId) throws Exception {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(toolCallId);
		pending.setName("start_export");
		pending.setKind(PendingToolCall.KIND_CONFIRM);
		pending.setStatus(PendingToolCall.PENDING);
		Map<String, String> table = new LinkedHashMap<>();
		table.put(toolCallId, this.fixture.mapper.writeValueAsString(pending));
		this.fixture.fake.hashes.put(this.fixture.store.pendingKey(runId), table);
	}

	private List<String> thirteenKeys(String runId) {
		return List.of(this.fixture.store.runKey(runId), this.fixture.store.pendingKey(runId),
				this.fixture.store.claimKey(runId), this.fixture.store.ledgerKey(runId),
				this.fixture.store.idemKey(runId), this.fixture.store.eventsKey(runId),
				this.fixture.store.seqKey(runId), this.fixture.store.xeventsKey(runId),
				this.fixture.store.runLeaseKey(runId), this.fixture.store.fenceKey(runId),
				this.fixture.store.reqKey(runId), this.fixture.store.decidedKey(runId),
				this.fixture.store.runLeaseEpochKey(runId));
	}

	private void seedBusinessKeys() {
		for (String key : businessKeys()) {
			this.fixture.fake.strings.put(key, "business");
		}
	}

	private static List<String> businessKeys() {
		return List.of("jobs", "jobs:1", "job_items:job-1", "task_files:task-1", "config_staging_rows:1",
				"ai:lock:task:7", "ai:lock:job:7:IMPORT", "ai:cfg:ver", "kb:cache:doc-1", "ai:config:audit:1",
				"ai:provider:1", "ai:model:1", "ai:setting:allow.userParams");
	}

	private Path writeFile(String name, String content) throws Exception {
		Path dir = this.tempDir.resolve("files");
		Files.createDirectories(dir);
		Path file = dir.resolve(name);
		Files.writeString(file, content);
		return file;
	}

	private int countFeedback(String sessionId) {
		return this.fixture.jdbc.queryForObject("SELECT COUNT(*) FROM ai_feedback WHERE session_id = ?",
				Integer.class, sessionId);
	}

	private int countChat(String sessionId) {
		return this.fixture.jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_message WHERE session_id = ?",
				Integer.class, sessionId);
	}

	// ── 装配 ────────────────────────────────────────────────────────────────

	private static final class Fixture {

		final PurgeFakeRedis fake = new PurgeFakeRedis();

		final ObjectMapper mapper = new ObjectMapper();

		final AiProperties properties = new AiProperties();

		final SessionGate gate = mock(SessionGate.class);

		final CancellationRegistry cancellations;

		final SessionPurgeGuard guard;

		final SessionPurgePendingStore pendingStore;

		final RunStore store;

		final RedisChatMemoryRepository memory;

		final RedisFormAttachmentStore attachments;

		final FileStorageService files;

		final JdbcTemplate jdbc;

		final JdbcDataSource source;

		final SessionPurgeDatabase database;

		final AiSessionPurgeService service;

		Path fileA;

		Path fileB;

		FormAttachmentStore.Attachment attachmentA;

		FormAttachmentStore.Attachment attachmentB;

		Fixture(Path tempDir) {
			JdbcDataSource source = new JdbcDataSource();
			source.setURL("jdbc:h2:mem:purge-service-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
			source.setUser("sa");
			this.source = source;
			this.jdbc = new JdbcTemplate(source);
			this.jdbc.execute("CREATE TABLE ai_feedback (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
					+ "message_id VARCHAR(64) NOT NULL, session_id VARCHAR(64) NOT NULL, rating INT NOT NULL)");
			this.jdbc.execute("CREATE TABLE ai_chat_message (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
					+ "session_id VARCHAR(128) NOT NULL, run_id VARCHAR(64) NOT NULL, seq BIGINT NOT NULL)");

			AppProperties app = new AppProperties();
			app.getFile().setStoragePath(tempDir.resolve("files").toString());
			this.files = new FileStorageService(app);
			this.guard = new SessionPurgeGuard(this.fake.redis);
			this.pendingStore = new SessionPurgePendingStore(this.fake.redis, this.mapper, this.properties);
			this.store = new RunStore(this.fake.redis, this.mapper, this.properties);
			this.memory = new RedisChatMemoryRepository(this.fake.redis, new MessageJsonCodec(this.mapper),
					Duration.ofHours(24), this.guard);
			@SuppressWarnings("unchecked")
			ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
			when(provider.getIfAvailable()).thenReturn(this.fake.redis);
			this.attachments = new RedisFormAttachmentStore(provider, this.mapper);
			this.database = new SessionPurgeDatabase(this.jdbc, new DataSourceTransactionManager(source));

			when(this.gate.currentRunId(anyString())).thenReturn(null);
			this.cancellations = org.mockito.Mockito.spy(
					new CancellationRegistry(this.fake.redis, this.properties));

			this.service = new AiSessionPurgeService(this.fake.redis, this.store, this.gate, this.cancellations,
					this.memory, this.attachments, this.files, this.database, this.guard, this.pendingStore,
					this.properties, Duration.ZERO);
		}

	}

}
