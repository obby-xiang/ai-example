package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.common.AiStateUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 会话 → runId 的轻量索引（{@code ai:session:runs:<tenantId>:<userId>:<sessionId>}）。
 *
 * <p><b>它是销毁/恢复枚举的唯一发现面</b>：因此登记失败现在 <b>fail-closed</b>（create 抛
 * {@link AiStateUnavailableException}，不留"有轮次但索引里没有"的半成品）；索引带完整身份段，
 * 同 sessionId 跨身份互不可见；删除只摘本人身份这一条成员，其他会话原样保留。
 *
 * <p>证红：不写索引 ⇒ create 失败用例红；摘成员用错身份 ⇒ 跨身份/跨会话用例红。
 */
class RunStoreSessionRunIndexTest {

	private static final String SESSION = "s-index";

	private static final String RUN = "run-index-1";

	private static final long TENANT_A = 1L;

	private static final long USER_A = 101L;

	private static final long TENANT_B = 2L;

	private static final long USER_B = 202L;

	private final ObjectMapper mapper = new ObjectMapper();

	private final AiProperties properties = new AiProperties();

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunStore store = new RunStore(this.fake.redis, this.mapper, this.properties);

	private RequestContextUtils.SafeCloseable tenantScope;

	@BeforeEach
	void enterTenantIdentity() {
		this.tenantScope = RequestContextUtils.useContext(TENANT_A, USER_A);
	}

	@AfterEach
	void leaveTenantIdentity() {
		if (this.tenantScope != null) {
			this.tenantScope.close();
		}
	}

	@Test
	void createAddsSessionIndexWithTtlAndDeleteRunRemovesIt() throws Exception {
		createAs(TENANT_A, USER_A, RUN, SESSION);

		String key = this.store.sessionRunsKey(SESSION);
		assertThat(key).as("会话级键带身份段").isEqualTo(RunStore.SESSION_RUNS_PREFIX + "1:101:" + SESSION);
		assertThat(this.store.sessionRunIds(SESSION)).containsExactly(RUN);
		assertThat(this.fake.setsByKey.getOrDefault(key, Set.of())).containsExactly(RUN);
		assertThat(this.fake.expiry(key)).as("轻量索引与会话/快照同寿").isEqualTo(this.properties.getSessionTtl());
		assertThat(this.store.allRunIds()).as("身份 run 列表同时登记").containsExactly(RUN);

		this.store.deleteRun(RUN);

		assertThat(this.fake.setsByKey).as("空 SET 由 Redis 自动消失").doesNotContainKey(key);
		assertThat(this.store.sessionRunIds(SESSION)).isEmpty();
		assertThat(this.fake.zsetsByKey).as("身份 run 列表成员一并摘除")
			.doesNotContainKey(RunStore.RUN_LIST_INDEX_PREFIX + "1:101");
	}

	/** 索引按 (身份, sessionId) 分桶：同 sessionId/同 runId 跨身份、以及同身份不同会话都互不污染。 */
	@Test
	void sessionIndexIsIdentityAndSessionScoped() throws Exception {
		createAs(TENANT_A, USER_A, "run-a", SESSION);
		createAs(TENANT_B, USER_B, "run-b", SESSION);
		createAs(TENANT_A, USER_A, "run-c", "s-other");

		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_A, USER_A)) {
			assertThat(this.store.sessionRunIds(SESSION)).containsExactly("run-a");
			assertThat(this.store.sessionRunIds("s-other")).containsExactly("run-c");
		}
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_B, USER_B)) {
			assertThat(this.store.sessionRunIds(SESSION)).as("B 读取同 sessionId 只看到自己的成员")
				.containsExactly("run-b");
		}

		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_A, USER_A)) {
			this.store.deleteRun("run-a");
		}

		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_A, USER_A)) {
			assertThat(this.store.sessionRunIds(SESSION)).isEmpty();
			assertThat(this.store.sessionRunIds("s-other")).as("其他会话不受影响").containsExactly("run-c");
		}
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_B, USER_B)) {
			assertThat(this.store.sessionRunIds(SESSION)).as("B 的成员不受 A 的删除影响")
				.containsExactly("run-b");
		}
	}

	/** 会话索引是唯一发现面 ⇒ 登记失败必须 fail-closed（不留无索引的轮次）。 */
	@Test
	void sessionIndexWriteFailureFailsCreateClosed() {
		this.fake.failSessionRunsRegister = true;

		assertThatThrownBy(() -> this.store.create(RUN, SESSION, AiContext.empty(), List.of(), this.codec, 1L,
				"model", new RunSnapshot.FrozenParams(null, null, 4096, false), "client-test", "请求文本", 1_000L))
			.isInstanceOf(AiStateUnavailableException.class)
			.hasMessageContaining("session->run index");

		assertThat(this.store.get(RUN)).as("索引没登记 ⇒ 不得留下快照（否则永远发现不了）").isNull();
		assertThat(this.store.sessionRunIds(SESSION)).isEmpty();
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	/** 直接在给定身份下建一轮（create 需要显式身份作用域）。 */
	private void createAs(long tenantId, long userId, String runId, String sessionId) {
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(tenantId, userId)) {
			this.store.create(runId, sessionId, AiContext.empty(), List.of(), this.codec, 1L, "model",
					new RunSnapshot.FrozenParams(null, null, 4096, false), "client-" + runId, "请求文本",
					1_000L);
		}
	}

}
