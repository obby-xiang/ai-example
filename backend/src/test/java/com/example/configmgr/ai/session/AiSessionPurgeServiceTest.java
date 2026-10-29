package com.example.configmgr.ai.session;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.form.FormAttachmentStore;
import com.example.configmgr.ai.form.RedisFormAttachmentStore;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.memory.RedisChatMemoryRepository;
import com.example.configmgr.ai.observe.SettlementObservationStore;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.LeaseService;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.RunKeyScope;
import com.example.configmgr.ai.tenant.TenantUser;
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

	private static final long TENANT_A = 1L;

	private static final long USER_A = 101L;

	private static final long TENANT_B = 2L;

	private static final long USER_B = 201L;

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
			public Deleted delete(String sessionId) {
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
		// RT-28：销毁<b>枚举</b>阶段仍只用本会话索引（不逐个反序列化他会话快照做候选发现）；
		// 但删除尾部必须按全局索引做一次"本会话 run 残留"复核（哨兵在、成员缺时唯一的兜底）——
		// 他会话的 run 因此至多被读一次快照，且**不会被当成候选**。
		assertThat(result.runs()).extracting(AiSessionPurgeService.RunView::runId).containsExactly("run-a");
		org.mockito.Mockito.verify(spyStore, org.mockito.Mockito.atMost(1)).get("run-b");
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
		// fix4：complete 后不再立刻移除台账 —— 保留为"一次性记忆复核"（崩溃残留补偿），到点后才移除。
		// API 语义不变：report.completed()/PurgeResult.complete() 仍然是"删除主体完成"。
		assertThat(this.fixture.pendingStore.current(SESSION_A))
			.as("完成后保留一次性记忆复核台账（fix4）").isNotNull();
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
		// fix4：完成的那一轮会把台账改排成一次性记忆复核，因此台账此刻仍在（到点复核成功才移除）。
		assertThat(this.fixture.pendingStore.current(SESSION_A)).isNotNull();
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
			public Deleted delete(String sessionId) {
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
		// fix4：同上 —— 保留一次性记忆复核台账。
		assertThat(this.fixture.pendingStore.current(SESSION_A)).isNotNull();
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

	// ── 身份隔离（租户任务 2）：跨租户同 sessionId ──────────────────────────

	/**
	 * <b>跨租户相同 sessionId 的销毁隔离</b>：A 的删除只能碰 A 身份下的键/文件/台账。
	 *
	 * <p>覆盖面：会话记录、销毁台账/守卫、附件登记与落盘文件、run 快照与 13 键、浏览器清理指令键形。
	 */
	@Test
	void purgeInOneIdentityDoesNotTouchAnotherIdentityWithTheSameSessionId() throws Exception {
		Path fileA;
		Path fileB;
		FormAttachmentStore.Attachment attachmentA;
		FormAttachmentStore.Attachment attachmentB;
		try (RequestContextUtils.SafeCloseable scope = RequestContextUtils.useContext(TENANT_A, USER_A)) {
			seedIdentitySession("run-a", "call-a");
			this.fixture.jdbc.update("INSERT INTO ai_feedback (message_id, session_id, rating, tenant_id, user_id) "
					+ "VALUES (?, ?, ?, ?, ?)", "msg-a", SESSION_A, 1, TENANT_A, USER_A);
			fileA = writeFile("att-a.pdf", "attachment-a");
			attachmentA = this.fixture.attachments.register("run-a", "call-a", SESSION_A, "att-a.pdf", 10L,
					"application/pdf", fileA.toString());
		}
		try (RequestContextUtils.SafeCloseable scope = RequestContextUtils.useContext(TENANT_B, USER_B)) {
			seedIdentitySession("run-b", "call-b");
			this.fixture.jdbc.update("INSERT INTO ai_feedback (message_id, session_id, rating, tenant_id, user_id) "
					+ "VALUES (?, ?, ?, ?, ?)", "msg-b", SESSION_A, -1, TENANT_B, USER_B);
			fileB = writeFile("att-b.pdf", "attachment-b");
			attachmentB = this.fixture.attachments.register("run-b", "call-b", SESSION_A, "att-b.pdf", 10L,
					"application/pdf", fileB.toString());
		}

		AiSessionPurgeService.PurgeResult result;
		try (RequestContextUtils.SafeCloseable scope = RequestContextUtils.useContext(TENANT_A, USER_A)) {
			result = this.fixture.service.purge(SESSION_A, "test");
		}

		assertThat(result.complete()).as("A 自己的数据应当删净").isTrue();
		assertThat(result.found()).isTrue();
		assertThat(result.clientCleanup().mirrorKey())
			.as("镜像键形与前端一致：ai-mirror:<t>:<u>:<sid>")
			.isEqualTo("ai-mirror:" + TENANT_A + ":" + USER_A + ":" + SESSION_A);
		assertThat(result.clientCleanup().draftPrefix())
			.as("草稿前缀与前端一致：cm.ai.form.draft.<t>:<u>.")
			.isEqualTo("cm.ai.form.draft." + TENANT_A + ":" + USER_A + ".");
		assertThat(result.clientCleanup().draftToolCallIds()).containsExactly("call-a");

		// A 面确实删了（会话记录 / 台账 / 守卫 / run / 附件登记 / 文件）
		assertThat(this.fixture.fake.strings)
			.doesNotContainKey(AiSessionService.keyFor(SESSION_A, TENANT_A, USER_A));
		// fix4：complete 之后台账仍留一条"记忆面待复核"，且它带 A 的身份（不是 B 的）。
		assertThat(this.fixture.fake.strings)
			.containsKey(SessionPurgePendingStore.pendingKey(SESSION_A, TENANT_A, USER_A));
		assertThat(this.fixture.fake.strings.get(SessionPurgePendingStore.pendingKey(SESSION_A, TENANT_A, USER_A)))
			.contains("\"tenantId\":1", "\"userId\":101", "\"verifyAtMs\"");
		assertThat(this.fixture.fake.strings)
			.containsKey(SessionPurgeGuard.keyFor(SESSION_A, TENANT_A, USER_A));
		assertThat(this.fixture.fake.strings).doesNotContainKey(this.fixture.store.runKey("run-a"));
		assertThat(Files.exists(fileA)).as("A 的文件必须删").isFalse();
		assertThat(this.fixture.fake.strings).doesNotContainKey(this.attachmentsRegistrationKey(TENANT_A, USER_A,
				attachmentA.fileId()));

		// B 面一条都没动（同 sessionId、不同身份）
		try (RequestContextUtils.SafeCloseable scope = RequestContextUtils.useContext(TENANT_B, USER_B)) {
			assertThat(this.fixture.fake.strings)
				.as("B 的会话记录必须保留")
				.containsKey(AiSessionService.keyFor(SESSION_A, TENANT_B, USER_B));
			assertThat(this.fixture.fake.strings)
				.as("B 的 run 快照必须保留")
				.containsKey(this.fixture.store.runKey("run-b"));
			assertThat(this.fixture.fake.strings)
				.as("B 的销毁台账必须保留")
				.containsKey(SessionPurgePendingStore.pendingKey(SESSION_A, TENANT_B, USER_B));
			assertThat(this.fixture.guard.isArmed(SESSION_A)).as("B 的守卫必须仍然 armed").isTrue();
			assertThat(this.fixture.attachments.find(attachmentB.fileId()))
				.as("B 的附件登记必须仍然可用")
				.isNotNull();
		}
		assertThat(Files.exists(fileB)).as("B 的文件不得被 A 的销毁删除").isTrue();

		// H2 面的同一口径（V11）：反馈按 sessionId + tenantId + userId 删，跨租户同 sessionId 不误删
		assertThat(countFeedbackFor(SESSION_A, TENANT_A, USER_A)).as("A 的反馈行必须删").isZero();
		assertThat(countFeedbackFor(SESSION_A, TENANT_B, USER_B)).as("B 的反馈行不得被 A 的销毁误删").isEqualTo(1);
	}

	private int countFeedbackFor(String sessionId, long tenantId, long userId) {
		Integer rows = this.fixture.jdbc.queryForObject(
				"SELECT COUNT(*) FROM ai_feedback WHERE session_id = ? AND tenant_id = ? AND user_id = ?",
				Integer.class, sessionId, tenantId, userId);
		return rows == null ? 0 : rows;
	}

	/**
	 * <b>销毁面的观测/取消键（租户任务 2 收尾组）</b>：{@code ai:cancel:} / {@code ai:observed:} 带
	 * {@code <tenantId>:<userId>:} 身份段，删除必须与写入方是<b>同一把键</b> —— 跨租户同 runId 不误删。
	 *
	 * <p>证红：把 {@code deleteObservabilityKeys} 里的 cancel/observed 键改回裸 runId 形 ⇒
	 * A 的两把键删不掉、B 的两把键被误删，本用例立刻红。
	 */
	@Test
	void observabilityKeysAreDeletedOnlyForTheCurrentIdentity() throws Exception {
		SettlementObservationStore observations = new SettlementObservationStore();
		try (RequestContextUtils.SafeCloseable scope = RequestContextUtils.useContext(TENANT_A, USER_A)) {
			seedIdentitySession("run-a", "call-a");
			this.fixture.fake.strings.put(CancellationRegistry.cancelKey("run-a"), "inst-a");
			this.fixture.fake.hashes.put(observations.observedKey("run-a"), new LinkedHashMap<>(Map.of("call-a",
					"1|inst-a")));
		}
		String cancelB;
		String observedB;
		try (RequestContextUtils.SafeCloseable scope = RequestContextUtils.useContext(TENANT_B, USER_B)) {
			cancelB = CancellationRegistry.cancelKey("run-a");
			observedB = observations.observedKey("run-a");
			this.fixture.fake.strings.put(cancelB, "inst-b");
			this.fixture.fake.hashes.put(observedB, new LinkedHashMap<>(Map.of("call-a", "2|inst-b")));
		}
		String cancelA = CancellationRegistry.CANCEL_PREFIX + TENANT_A + ":" + USER_A + ":run-a";
		String observedA = SettlementObservationStore.KEY_PREFIX + TENANT_A + ":" + USER_A + ":run-a";
		assertThat(cancelB).as("同 runId 不同身份必须落在不同键上").isNotEqualTo(cancelA);
		assertThat(observedB).isNotEqualTo(observedA);

		AiSessionPurgeService.PurgeResult result;
		try (RequestContextUtils.SafeCloseable scope = RequestContextUtils.useContext(TENANT_A, USER_A)) {
			result = this.fixture.service.purge(SESSION_A, "test");
		}

		assertThat(result.complete()).isTrue();
		assertThat(this.fixture.fake.strings).as("A 的取消旗标必须随销毁删除").doesNotContainKey(cancelA);
		assertThat(this.fixture.fake.hashes).as("A 的结算观测位必须随销毁删除").doesNotContainKey(observedA);
		assertThat(this.fixture.fake.strings).as("B 的取消旗标不得被 A 的销毁误删").containsKey(cancelB);
		assertThat(this.fixture.fake.hashes).as("B 的结算观测位不得被 A 的销毁误删").containsKey(observedB);
	}

	/** 一个身份下的一整套最小可删面（会话记录 + run + 台账 + 记忆 + 守卫）。 */
	private void seedIdentitySession(String runId, String toolCallId) throws Exception {
		TenantUser identity = RequestContextUtils.require();
		this.fixture.fake.sets
			.computeIfAbsent(RunStore.RUN_INDEX, ignored -> java.util.concurrent.ConcurrentHashMap.newKeySet())
			.add(RunKeyScope.indexMember(identity.tenantId(), identity.userId(), runId));
		for (String key : thirteenKeys(runId)) {
			this.fixture.fake.strings.put(key, "seed");
		}
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(runId);
		snapshot.setSessionId(SESSION_A);
		snapshot.setStatus(RunSnapshot.DONE);
		snapshot.setTenantId(identity.tenantId());
		snapshot.setUserId(identity.userId());
		this.fixture.fake.strings.put(this.fixture.store.runKey(runId),
				this.fixture.mapper.writeValueAsString(snapshot));
		this.fixture.fake.strings.put(this.fixture.store.beatKey(runId), "1");
		this.fixture.fake.strings.put(this.fixture.store.activityKey(runId), "1");
		seedPending(runId, toolCallId);
		this.fixture.fake.strings.put(AiSessionService.keyFor(SESSION_A), "{}");
		this.fixture.fake.strings.put(this.fixture.store.sessionCurrentKey(SESSION_A), runId);
		// 记忆面直接落"已是最终形态"的键（saveAll 走 staging 脚本，替身只建模销毁侧脚本）。
		this.fixture.fake.lists.put(this.fixture.memory.keyFor(SESSION_A), new ArrayList<>(List.of("{}")));
		this.fixture.pendingStore.register(SESSION_A, "purge-" + runId, "tester", System.currentTimeMillis());
		this.fixture.guard.arm(SESSION_A, "purge-" + runId, Duration.ofHours(1));
	}

	private String attachmentsRegistrationKey(long tenantId, long userId, String fileId) {
		return "ai:form:att:" + tenantId + ":" + userId + ":" + fileId;
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

	// ── fix4：complete 之后的一次有界"记忆面"复核（crash 残留补偿） ────────────

	/**
	 * <b>RED（fix4）：complete=true 之后必须保留一次"只复核记忆面"的台账</b>。
	 *
	 * <h2>缺陷形态</h2>
	 * 写者在 <b>RENAME 已生效、finally 复检完成之前</b> crash（JVM 死/线程被 Error 打断）时，
	 * 销毁早已报 {@code complete=true} 并把 pending 移除 —— 那份额外落地到 {@code chat:mem:*} 的历史
	 * <b>再没有人会清</b>，只能等 TTL。
	 *
	 * <h2>本批口径</h2>
	 * complete 时不再立刻移除台账：把它保留到一次<b>短期</b> {@code verifyAt}（静态常量，不新增配置面），
	 * 由既有 runner 到点只做记忆面 delete + staging + 删后复核，成功才移除台账。
	 * <b>API 语义不变</b>：{@code complete=true} 仍表示"本次删除主体已完成"。
	 */
	@Test
	void completedPurgeKeepsAOneShotVerifyPendingForCrashResidue() throws Exception {
		seedFullSession(SESSION_A, "run-a", "call-a");

		AiSessionPurgeService.PurgeResult result = this.fixture.service.purge(SESSION_A, "test");

		assertThat(result.complete()).as("API 语义不变：删除主体仍然完成").isTrue();
		assertThat(this.fixture.pendingStore.current(SESSION_A))
			.as("complete 后必须保留一次记忆复核台账（否则 crash 残留永远无人清）").isNotNull();
		assertThat(this.fixture.guard.isArmed(SESSION_A)).as("复核窗口内守卫必须仍 armed").isTrue();
	}

	/**
	 * <b>RED（fix4）：崩溃残留只在"到点"后被清掉，且复核只碰记忆面（DB/附件/业务数据全不动）</b>。
	 *
	 * <p>崩溃残留直接用"最终键还在"表达（写者已死，没有任何代码再运行）——不依赖 sleep/线程运气；
	 * 时间由 {@code runner.runOnce(nowMs)} 注入。
	 */
	@Test
	void crashResidueIsRemovedOnlyAfterTheOneShotVerifyAndDbAttachmentsBusinessAreUntouched() throws Exception {
		seedFullSession(SESSION_A, "run-a", "call-a");
		assertThat(this.fixture.service.purge(SESSION_A, "test").complete()).isTrue();
		// 模拟"RENAME 生效后、finally 复检前 crash"：最终键上留着整窗历史（写者不再运行）
		List<String> lateHistory = List.of("{\"type\":\"USER\",\"content\":\"崩溃前的写回\"}",
				"{\"type\":\"ASSISTANT\",\"content\":\"崩溃前的回答\"}");
		this.fixture.fake.lists.put(this.fixture.memory.keyFor(SESSION_A), new ArrayList<>(lateHistory));
		Path businessFile = writeFile("business-fix4.bin", "keep");
		this.fixture.fake.strings.put("jobs", "business");
		// 复核前后可能还有新的 DB 行（例如别的写口）：复核绝不能再删/增它们
		this.fixture.jdbc.update("INSERT INTO ai_feedback (message_id, session_id, rating) VALUES (?, ?, ?)",
				"msg-after-" + SESSION_A, SESSION_A, 1);
		int feedbackBefore = countFeedback(SESSION_A);

		SessionPurgeRetryRunner runner = new SessionPurgeRetryRunner(this.fixture.pendingStore,
				this.fixture.service, grantedLease());
		long completeAt = System.currentTimeMillis();

		SessionPurgeRetryRunner.RetryReport early = runner.runOnce(completeAt);
		assertThat(early.attempted()).as("未到 verifyAt：一条都不许执行").isZero();
		assertThat(this.fixture.fake.lists).as("未到点不得删（窗口内旧写者可能仍在收尾）")
			.containsKey(this.fixture.memory.keyFor(SESSION_A));

		runner.runOnce(completeAt + 120_000L);

		assertThat(this.fixture.fake.lists).as("到点后崩溃残留必须删净")
			.doesNotContainKey(this.fixture.memory.keyFor(SESSION_A));
		assertThat(this.fixture.pendingStore.current(SESSION_A)).as("复核成功后台账才移除").isNull();
		assertThat(countFeedback(SESSION_A)).as("复核不得再删 DB（行数不变）").isEqualTo(feedbackBefore);
		assertThat(Files.exists(businessFile)).as("复核不得碰业务文件").isTrue();
		assertThat(this.fixture.fake.strings).as("复核不得碰业务键").containsKey("jobs");
		assertThat(this.fixture.guard.isArmed(SESSION_A)).as("复核后守卫仍按保留期 armed").isTrue();
	}

	/**
	 * <b>复核失败沿用既有有界退避/MAX_ATTEMPTS 语义</b>：删不掉就不删台账、不 disarm，
	 * 故障恢复后由同一条台账继续复核并删净（不重跑整轮销毁）。
	 */
	@Test
	void failedVerifyKeepsThePendingAndSucceedsAfterTheFailureClears() throws Exception {
		seedFullSession(SESSION_A, "run-a", "call-a");
		assertThat(this.fixture.service.purge(SESSION_A, "test").complete()).isTrue();
		this.fixture.fake.lists.put(this.fixture.memory.keyFor(SESSION_A), new ArrayList<>(List.of("{}")));
		this.fixture.fake.failDeleteContaining = "chat:mem:";
		SessionPurgeRetryRunner runner = new SessionPurgeRetryRunner(this.fixture.pendingStore,
				this.fixture.service, grantedLease());
		long completeAt = System.currentTimeMillis();

		SessionPurgeRetryRunner.RetryReport failed = runner.runOnce(completeAt + 120_000L);

		assertThat(failed.failed()).isEqualTo(1);
		assertThat(this.fixture.pendingStore.current(SESSION_A)).as("复核失败不得删台账").isNotNull();
		assertThat(this.fixture.fake.lists).as("残留仍在（如实失败）").containsKey(this.fixture.memory.keyFor(SESSION_A));
		assertThat(this.fixture.guard.isArmed(SESSION_A)).as("失败不得 disarm").isTrue();

		this.fixture.fake.failDeleteContaining = null;
		runner.runOnce(completeAt + 240_000L);

		assertThat(this.fixture.fake.lists).as("恢复后同一条台账继续复核并删净")
			.doesNotContainKey(this.fixture.memory.keyFor(SESSION_A));
		assertThat(this.fixture.pendingStore.current(SESSION_A)).isNull();
	}

	/**
	 * <b>老台账 JSON（没有 {@code verifyAtMs}）必须逐字兼容</b>：解析成"普通待重试"，
	 * 由 runner 走原有 retry 路径（不是复核态）；retry 完成后同样转入一次性记忆复核。
	 */
	@Test
	void legacyPendingJsonWithoutVerifyFieldIsTreatedAsANormalRetry() {
		String legacyJson = "{\"sessionId\":\"" + SESSION_A + "\",\"purgeId\":\"purge-old\",\"requestedBy\":\"t\","
				+ "\"attempts\":0,\"nextAtMs\":1000,\"createdAtMs\":1000,\"lastError\":null}";
		this.fixture.fake.strings.put(SessionPurgeGuard.PENDING_PREFIX + SESSION_A, legacyJson);

		SessionPurgeRetryRunner runner = new SessionPurgeRetryRunner(this.fixture.pendingStore,
				this.fixture.service, grantedLease());
		SessionPurgeRetryRunner.RetryReport report = runner.runOnce(10_000L);

		assertThat(report.attempted()).as("老 JSON 必须能解析，并走普通 retry（本会话无数据也照样收敛）")
			.isEqualTo(1);
		assertThat(report.completed()).isEqualTo(1);
		assertThat(this.fixture.pendingStore.current(SESSION_A)).as("retry 完成后转入一次性记忆复核").isNotNull();
	}

	// ── 复核阶段的独立预算（fix5 / M-7） ────────────────────────────────────

	/**
	 * <b>RED（fix5 / M-7）：台账已经耗尽 {@code MAX_ATTEMPTS}，之后那次成功销毁排出的 60s 记忆复核仍必须执行</b>。
	 *
	 * <p>真实路径：销毁主体连续失败/延后把 {@code attempts} 推到上限（这里用台账 API 精确铺出同一状态，
	 * 不靠 sleep/时钟运气）→ 运维再发一次 DELETE，这次主体成功 → {@code scheduleVerify} 排一次记忆复核 →
	 * runner 到点执行。修复前 {@code scheduleVerify} 原样保留 {@code attempts=10}，而 runner 的
	 * {@code attempts >= MAX_ATTEMPTS} 短路排在复核分支<b>之前</b> ⇒ 复核 <b>0 次</b>：
	 * 崩溃残留只能等记忆键自身 TTL，pending/守卫/队列项永久驻留。
	 */
	@Test
	void verifyStillRunsAfterTheLedgerAlreadyBurnedItsRetryBudget() throws Exception {
		seedFullSession(SESSION_A, "run-a", "call-a");
		String purgeId = "purge-exhausted";
		this.fixture.pendingStore.register(SESSION_A, purgeId, "test", 1_000L);
		for (int attempt = 0; attempt < SessionPurgeRetryRunner.MAX_ATTEMPTS; attempt++) {
			assertThat(this.fixture.pendingStore.recordFailure(SESSION_A, purgeId, "boom")).isTrue();
		}
		assertThat(this.fixture.pendingStore.current(SESSION_A).attempts())
			.as("前置：台账已耗尽历史自动尝试")
			.isEqualTo(SessionPurgeRetryRunner.MAX_ATTEMPTS);

		// 耗尽之后重发 DELETE（复用同一台账），这次删除主体成功 ⇒ 排一次记忆复核。
		AiSessionPurgeService.PurgeResult result = this.fixture.service.purge(SESSION_A, "test");
		assertThat(result.complete()).as("API 语义不变：删除主体仍然完成").isTrue();
		SessionPurgePendingStore.Pending scheduled = this.fixture.pendingStore.current(SESSION_A);
		assertThat(scheduled).as("complete 后仍留一条待复核台账").isNotNull();
		assertThat(scheduled.verifyAtMs()).isNotNull();
		int feedbackAfterPurge = countFeedback(SESSION_A);

		// 崩溃残留：RENAME 已生效、写者在复检完成前死掉 ⇒ 最终键上留着历史。
		this.fixture.fake.lists.put(this.fixture.memory.keyFor(SESSION_A),
				new ArrayList<>(List.of("{\"type\":\"USER\",\"content\":\"崩溃前的写回\"}")));
		SessionPurgeRetryRunner runner = new SessionPurgeRetryRunner(this.fixture.pendingStore,
				this.fixture.service, grantedLease());
		long completeAt = System.currentTimeMillis();

		SessionPurgeRetryRunner.RetryReport report = runner.runOnce(completeAt + 120_000L);

		assertThat(report.attempted()).as("复核必须真的被执行（修复前被 attempts 短路，恒为 0）").isEqualTo(1);
		assertThat(report.completed()).isEqualTo(1);
		assertThat(scheduled.attempts()).as("复核是新阶段：自带一次有界预算，不从耗尽的旧计数继续").isZero();
		assertThat(this.fixture.fake.lists).as("到点后崩溃残留必须删净").doesNotContainKey(
				this.fixture.memory.keyFor(SESSION_A));
		assertThat(this.fixture.pendingStore.current(SESSION_A)).as("复核成功后台账才移除（不永久驻留）").isNull();
		assertThat(countFeedback(SESSION_A)).as("复核不得再删 DB").isEqualTo(feedbackAfterPurge);
	}

	/**
	 * <b>反例锁（fix5）：普通待重试台账的上限与退避逐字不变</b>。
	 *
	 * <p>复核的新预算只属于"删除主体已完成"这一新阶段；普通 pending（{@code verifyAtMs == null}）
	 * 到 {@code MAX_ATTEMPTS} 后仍然只 defer —— 不执行 retry（一个业务数据都不动）、不重置 attempts、
	 * 下一轮也不会偷偷再试（没有无限重试）。
	 */
	@Test
	void exhaustedNormalPendingStillOnlyDefersAndKeepsItsAttemptBudget() throws Exception {
		seedFullSession(SESSION_A, "run-a", "call-a");
		String purgeId = "purge-normal";
		this.fixture.pendingStore.register(SESSION_A, purgeId, "test", 1_000L);
		for (int attempt = 0; attempt < SessionPurgeRetryRunner.MAX_ATTEMPTS; attempt++) {
			assertThat(this.fixture.pendingStore.recordFailure(SESSION_A, purgeId, "boom")).isTrue();
		}
		SessionPurgeRetryRunner runner = new SessionPurgeRetryRunner(this.fixture.pendingStore,
				this.fixture.service, grantedLease());
		long later = System.currentTimeMillis() + 3_600_000L;

		SessionPurgeRetryRunner.RetryReport report = runner.runOnce(later);

		assertThat(report.attempted()).as("普通台账耗尽后不得再执行第 11 次").isZero();
		assertThat(report.skipped()).isEqualTo(1);
		SessionPurgePendingStore.Pending pending = this.fixture.pendingStore.current(SESSION_A);
		assertThat(pending).as("耗尽项仍是有主的 durable 台账（手动 DELETE 可续）").isNotNull();
		assertThat(pending.attempts()).as("defer 不得重置 attempts").isEqualTo(SessionPurgeRetryRunner.MAX_ATTEMPTS);
		assertThat(pending.verifyAtMs()).isNull();
		assertThat(pending.nextAtMs()).as("nextAt 必须推到有界未来").isGreaterThan(later);
		assertThat(this.fixture.fake.lists).as("不得执行 retry：记忆仍在")
			.containsKey(this.fixture.memory.keyFor(SESSION_A));
		assertThat(countFeedback(SESSION_A)).as("不得执行 retry：DB 行仍在").isEqualTo(1);
		assertThat(this.fixture.fake.strings).as("没有成功销毁就不得装墓碑（守卫只因 pending 仍 armed）")
			.doesNotContainKey(this.fixture.guard.keyFor(SESSION_A));

		SessionPurgeRetryRunner.RetryReport next = runner.runOnce(later + 1_000L);
		assertThat(next.attempted()).as("推到有界未来后没到点 ⇒ 依然一次都不执行").isZero();
		assertThat(this.fixture.pendingStore.current(SESSION_A).attempts())
			.as("跨轮也不得重置 attempts")
			.isEqualTo(SessionPurgeRetryRunner.MAX_ATTEMPTS);
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
					+ "message_id VARCHAR(64) NOT NULL, session_id VARCHAR(64) NOT NULL, rating INT NOT NULL, "
					+ "tenant_id BIGINT NOT NULL DEFAULT 0, user_id BIGINT NOT NULL DEFAULT 0)");

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
