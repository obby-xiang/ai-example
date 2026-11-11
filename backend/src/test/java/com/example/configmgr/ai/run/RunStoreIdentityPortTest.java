package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tool.AiContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * run 级<b>身份端口</b>的四条单点（真实 {@link RunStore} + 真实键形）：
 * <ol>
 * <li>{@link RunStore#get(String, long, long)}：无身份线程上也读得到带身份的轮次（键由<b>入参身份</b>拼）；</li>
 * <li>{@link RunStore#deleteRun(String, long, long)}：身份不符<b>响亮失败</b>，不得静默 no-op；
 *     发现面已从全局 {@code ai:runs} SET 改为<b>按身份 ZSET</b>（{@code ai:runs:<t>:<u>}）+
 *     全局/身份恢复 due 索引，因此索引断言直接看 ZSET；</li>
 * <li>{@link RunStore#pruneRunIndex()}：恢复 due 索引里的<b>裸 runId</b>（迁移前遗留，无法还原身份）
 *     被机会式摘掉；仍存活的复合成员原样保留（不得误删活轮次的发现面）；</li>
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

		assertThatThrownBy(() -> this.store.get(RUN_ID))
			.as("线程身份读口在无身份线程上直接失败（不存在无身份 run 键；显式身份读口才是唯一读法）")
			.isInstanceOf(IllegalStateException.class);
		assertThat(this.store.get(RUN_ID, TENANT_A, USER_A)).as("显式身份读口必须命中本身份的记录").isNotNull();
		assertThat(this.store.get(RUN_ID, TENANT_B, USER_B)).as("别人的身份读不到这一轮（键面 + 归属复核）").isNull();
		assertThatThrownBy(() -> this.store.get(RUN_ID, 0L, 0L))
			.as("显式读口没有'无身份'这种合法形态：缺身份 fail-closed")
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void deleteRunWithAMismatchedIdentityFailsLoudlyInsteadOfNoop() {
		createRun(TENANT_A, USER_A);
		String listKey = RunStore.RUN_LIST_INDEX_PREFIX + TENANT_A + ":" + USER_A;
		String identityDueKey = RunStore.RECOVERY_DUE_IDENTITY_PREFIX + TENANT_A + ":" + USER_A;
		String member = TENANT_A + "/" + USER_A + "/" + RUN_ID;

		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_A, USER_A)) {
			assertThat(this.fake.zsetsByKey.getOrDefault(listKey, Map.of())).as("创建时登记身份列表索引")
				.containsKey(RUN_ID);
			assertThat(this.fake.zsetsByKey.getOrDefault(RunStore.RECOVERY_DUE_INDEX, Map.of()))
				.as("创建时登记全局恢复发现面").containsKey(member);

			assertThatThrownBy(() -> this.store.deleteRun(RUN_ID, TENANT_B, USER_B))
				.as("身份不符的删除必须响亮失败（静默 no-op 会让销毁报告成功而数据仍在）")
				.isInstanceOf(IllegalStateException.class);
			assertThat(this.store.get(RUN_ID)).as("拒绝之后数据仍在").isNotNull();
			assertThat(this.fake.zsetsByKey.getOrDefault(listKey, Map.of()))
				.as("拒绝时不得摘掉身份列表索引").containsKey(RUN_ID);
			assertThat(this.fake.zsetsByKey.getOrDefault(RunStore.RECOVERY_DUE_INDEX, Map.of()))
				.as("拒绝时不得摘掉全局恢复发现面").containsKey(member);

			this.store.deleteRun(RUN_ID, TENANT_A, USER_A);
			assertThat(this.store.get(RUN_ID)).isNull();
			assertThat(this.fake.zsetsByKey.getOrDefault(listKey, Map.of()))
				.as("删除后身份列表索引清空").doesNotContainKey(RUN_ID);
			assertThat(this.fake.zsetsByKey.getOrDefault(identityDueKey, Map.of()))
				.as("删除后身份恢复 due 索引清空").doesNotContainKey(RUN_ID);
			assertThat(this.fake.zsetsByKey.getOrDefault(RunStore.RECOVERY_DUE_INDEX, Map.of()))
				.as("删除后全局恢复发现面清空").doesNotContainKey(member);
		}
	}

	@Test
	void pruneRunIndexRemovesLegacyBareMembersButKeepsLiveDiscovery() {
		// pre-deploy 遗留：恢复 due 索引里是裸 runId（无身份复合成员，无法还原身份）⇒ 机会式清理摘掉。
		this.fake.zsetsByKey.computeIfAbsent(RunStore.RECOVERY_DUE_INDEX,
				ignored -> new java.util.concurrent.ConcurrentHashMap<>()).put(RUN_ID, 0.0);
		createRun(TENANT_A, USER_A);
		String member = TENANT_A + "/" + USER_A + "/" + RUN_ID;

		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_A, USER_A)) {
			assertThat(this.store.pruneRunIndex()).as("只摘无法还原身份的裸成员").isEqualTo(1);
			assertThat(this.fake.zsetsByKey.getOrDefault(RunStore.RECOVERY_DUE_INDEX, Map.of()))
				.as("活轮次的恢复成员不得被删（调用线程有身份不改变判活口径）")
				.containsKey(member)
				.doesNotContainKey(RUN_ID);
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
				.as("0/0 记录不得以调用者身份 adopt 进新身份命名空间")
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("without ownership (0/0)")
				.hasMessageContaining("fails closed");
		}

		assertThat(this.fake.strings.keySet()).as("拒绝不得留下任何键").noneMatch(key -> key.contains(RUN_ID));
	}

	private void createRun(long tenantId, long userId) {
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(tenantId, userId)) {
			this.store.create(RUN_ID, SESSION_ID, AiContext.empty(), List.of(), this.codec, 1L, "test-model",
					new RunSnapshot.FrozenParams(null, null, 4096, false), "client-test", "请求文本", 1000L);
		}
	}

}
