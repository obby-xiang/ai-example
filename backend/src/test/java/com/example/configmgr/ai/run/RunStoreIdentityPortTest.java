package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tool.AiContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * run 级<b>身份端口</b>的四条单点（真实 {@link RunStore} + 真实键形）：
 * <ol>
 * <li>{@link RunStore#get(String, long, long)}：无身份线程上也读得到带身份的轮次（键由<b>入参身份</b>拼）；</li>
 * <li>{@link RunStore#deleteRun(String, long, long)}：身份不符<b>响亮失败</b>，不得静默 no-op；</li>
 * <li>{@link RunStore#pruneRunIndex()}：legacy 裸成员按<b>旧键形</b>判活，不因调用线程有身份而被判死；</li>
 * <li>快照写口：0/0 记录在<b>有身份</b>的线程上必须 fail-closed，<b>不得 adopt</b> 成当前身份。</li>
 * </ol>
 */
class RunStoreIdentityPortTest {

	private static final String RUN_ID = "run-identity-port";

	private static final String SESSION_ID = "s-identity-port";

	private static final long TENANT_A = 1L;

	private static final long USER_A = 101L;

	private static final long TENANT_B = 2L;

	private static final long USER_B = 202L;

	private final ObjectMapper mapper = new ObjectMapper();

	private final AiProperties properties = new AiProperties();

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final RunStore store = new RunStore(this.fake.redis, this.mapper, this.properties);

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	@Test
	void explicitIdentityReadPortReadsTheRecordFromANonIdentityThread() {
		createRun(TENANT_A, USER_A);
		assertThat(RequestContextUtils.currentOrNull()).as("前置：读线程没有身份").isNull();

		assertThat(this.store.get(RUN_ID)).as("线程身份读口在无身份时只能命中 legacy 键").isNull();
		assertThat(this.store.get(RUN_ID, TENANT_A, USER_A)).as("显式身份读口必须命中本身份的记录").isNotNull();
		assertThat(this.store.get(RUN_ID, TENANT_B, USER_B)).as("别人的身份读不到这一轮（键面 + 归属复核）").isNull();
		assertThatThrownBy(() -> this.store.get(RUN_ID, 0L, 0L))
			.as("显式读口没有'无身份'这种合法形态：缺身份 fail-closed")
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void deleteRunWithAMismatchedIdentityFailsLoudlyInsteadOfNoOp() {
		createRun(TENANT_A, USER_A);
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_A, USER_A)) {
			assertThatThrownBy(() -> this.store.deleteRun(RUN_ID, TENANT_B, USER_B))
				.as("身份不符的删除必须响亮失败（静默 no-op 会让销毁报告成功而数据仍在）")
				.isInstanceOf(IllegalStateException.class);
			assertThat(this.store.get(RUN_ID)).as("拒绝之后数据仍在").isNotNull();
			assertThat(this.fake.setsByKey.getOrDefault(RunStore.RUN_INDEX, Set.of()))
				.as("拒绝时不得摘掉任何索引成员").containsExactly(TENANT_A + "/" + USER_A + "/" + RUN_ID);

			this.store.deleteRun(RUN_ID, TENANT_A, USER_A);
			assertThat(this.store.get(RUN_ID)).isNull();
			assertThat(this.fake.setsByKey.getOrDefault(RunStore.RUN_INDEX, Set.of())).isEmpty();
		}
	}

	@Test
	void pruneRunIndexKeepsLegacyMembersAliveOnTheLegacyKey() {
		// pre-deploy 轮次：索引成员是裸 runId，快照在 legacy tag 上。
		this.fake.setsByKey.computeIfAbsent(RunStore.RUN_INDEX, ignored -> java.util.concurrent.ConcurrentHashMap
			.newKeySet()).add(RUN_ID);
		this.fake.strings.put(RunStore.RUN_PREFIX + "{run:" + RUN_ID + "}", "{}");

		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_A, USER_A)) {
			assertThat(this.store.pruneRunIndex())
				.as("调用线程有身份，但 legacy 成员的活/死必须按旧键形判定").isZero();
			assertThat(this.fake.setsByKey.getOrDefault(RunStore.RUN_INDEX, Set.of()))
				.as("机会式清理不得删掉迁移前的发现面").containsExactly(RUN_ID);
		}
	}

	@Test
	void writingAZeroIdentityRecordUnderAnIdentityFailsClosedInsteadOfAdopting() {
		RunSnapshot legacy = new RunSnapshot();
		legacy.setRunId(RUN_ID);
		legacy.setSessionId(SESSION_ID);
		legacy.setStatus(RunSnapshot.RUNNING);

		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_A, USER_A)) {
			assertThatThrownBy(() -> this.store.save(legacy))
				.as("0/0 记录不得以调用者身份 adopt 进新身份命名空间").isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("不得以调用者身份 adopt");
		}

		assertThat(this.fake.strings.keySet()).as("拒绝不得留下任何键").noneMatch(key -> key.contains(RUN_ID));
	}

	private void createRun(long tenantId, long userId) {
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(tenantId, userId)) {
			this.store.create(RUN_ID, SESSION_ID, AiContext.empty(), List.of(), this.codec);
		}
	}

}
