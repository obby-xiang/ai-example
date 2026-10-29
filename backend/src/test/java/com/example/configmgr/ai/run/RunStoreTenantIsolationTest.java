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

/**
 * <b>同一 runId 落在两个租户</b>时的键面隔离（租户任务2 的最小失败单点）。
 *
 * <h2>被钉住的缺陷</h2>
 * run 级键原先是 {@code ai:run:{run:<runId>}}：tag 只有 runId。两个租户各自生成同形 runId
 * 是合法且必然发生的（{@code runId} 不是跨租户唯一性证明），于是：
 * <ul>
 * <li>后建的一方 {@code GET} 会读到<b>别人的快照</b>（历史、context、挂起工具调用全在里面）；</li>
 * <li>{@code deleteRun} 的 13 键 {@code DEL} 会删掉<b>别人那一轮</b>的全部运行态；</li>
 * <li>{@code ai:runs} 索引只记 runId，启动发现与列表分不清归属。</li>
 * </ul>
 *
 * <h2>本组口径</h2>
 * tag 同时含租户、用户与 runId（{<tenantId>:<userId>:run:<runId>}）（{@code {t<tenantId>:u<userId>:run:<runId>}}）；索引成员是复合成员
 * {@code tenantId/userId/runId}。读写删因此天然按身份分开，不依赖"每次都记得查归属"。
 */
class RunStoreTenantIsolationTest {

	private static final String RUN_ID = "run-shared-20261030";

	private static final String SESSION_A = "s-tenant-a";

	private static final String SESSION_B = "s-tenant-b";

	private static final long TENANT_A = 1L;

	private static final long USER_A = 101L;

	private static final long TENANT_B = 2L;

	private static final long USER_B = 202L;

	private final ObjectMapper mapper = new ObjectMapper();

	private final AiProperties properties = new AiProperties();

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunStore store = new RunStore(this.fake.redis, this.mapper, this.properties);

	@Test
	void sameRunIdInTwoTenantsNeverSharesSnapshotKeysOrDelete() {
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_A, USER_A)) {
			RunSnapshot created = this.store.create(RUN_ID, SESSION_A, AiContext.empty(), List.of(), this.codec);
			assertThat(created.getTenantId()).isEqualTo(TENANT_A);
			assertThat(this.store.runKey(RUN_ID))
				.as("run 级 tag 必须同时含租户、用户与 runId")
				.isEqualTo("ai:run:{1:101:run:" + RUN_ID + "}");
		}

		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_B, USER_B)) {
			assertThat(this.store.runKey(RUN_ID)).isEqualTo("ai:run:{2:202:run:" + RUN_ID + "}");
			assertThat(this.store.get(RUN_ID)).as("另一个租户的同名 runId 不得读到别人的快照").isNull();
			assertThat(this.store.allRunIds()).as("列表只看当前归属").isEmpty();

			RunSnapshot created = this.store.create(RUN_ID, SESSION_B, AiContext.empty(), List.of(), this.codec);
			assertThat(created.getTenantId()).isEqualTo(TENANT_B);
			assertThat(this.store.get(RUN_ID).getSessionId()).isEqualTo(SESSION_B);

			// 13 键 DEL 在自己的身份下发生：不得碰别人的同名轮次
			this.fake.enableClusterMode();
			this.store.deleteRun(RUN_ID);
			assertThat(this.store.get(RUN_ID)).isNull();
		}

		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_A, USER_A)) {
			RunSnapshot kept = this.store.get(RUN_ID);
			assertThat(kept).as("另一租户的删除不得删掉本例的轮次").isNotNull();
			assertThat(kept.getTenantId()).isEqualTo(TENANT_A);
			assertThat(kept.getUserId()).isEqualTo(USER_A);
			assertThat(kept.getSessionId()).isEqualTo(SESSION_A);
			assertThat(this.store.allRunIds()).containsExactly(RUN_ID);
		}

		Set<String> members = this.fake.setsByKey.getOrDefault(RunStore.RUN_INDEX, Set.of());
		assertThat(members).as("全局索引成员必须带身份（租户/用户/runId）")
			.containsExactly(TENANT_A + "/" + USER_A + "/" + RUN_ID);
	}

	/**
	 * 缺身份 = 失败，而不是"写进 0/0 兼容命名空间"：轮级 hash tag 由身份组成，
	 * 凭空补一个身份就是把这些键写进"谁都读不到"或"别人读得到"的地方。
	 */
	@Test
	void createWithoutIdentityFailsClosed() {
		org.assertj.core.api.Assertions
			.assertThatThrownBy(() -> this.store.create(RUN_ID, SESSION_A, AiContext.empty(), List.of(),
					this.codec))
			.as("没有身份上下文 ⇒ 不建轮次")
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("缺少租户/用户身份上下文");

		assertThat(this.fake.strings).as("失败不得留下任何快照").isEmpty();
		assertThat(this.fake.setsByKey).as("失败不得登记索引").doesNotContainKey(RunStore.RUN_INDEX);
	}

	/**
	 * 取号计数器与解析：{@code ai:lease:epoch:} 由租约键的 tag 推导 ⇒ 身份自动进键且与租约同槽；
	 * 反解口径只认带身份的键形（旧裸键/旧 tag 没有身份 ⇒ {@code null}，不得猜）。
	 */
	@Test
	void leaseEpochCounterAndLeaseParsingCarryTheSameIdentity() {
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_A, USER_A)) {
			String leaseKey = this.store.runLeaseKey(RUN_ID);
			String epochKey = this.store.runLeaseEpochKey(RUN_ID);

			assertThat(leaseKey).isEqualTo("ai:lease:run:{1:101:run:" + RUN_ID + "}");
			assertThat(epochKey).isEqualTo("ai:lease:epoch:{1:101:run:" + RUN_ID + "}");
			assertThat(FakeLeaseRedis.hashTagOf(epochKey))
				.as("取号计数器必须与租约键同 tag（号与占用同序，ACQUIRE 才能是一条 Lua）")
				.isEqualTo(FakeLeaseRedis.hashTagOf(leaseKey));

			com.example.configmgr.ai.tenant.RunKeyScope.RunRef ref = RunStore.runRefFromLeaseKey(leaseKey);
			assertThat(ref).isNotNull();
			assertThat(ref.tenantId()).isEqualTo(TENANT_A);
			assertThat(ref.userId()).isEqualTo(USER_A);
			assertThat(ref.runId()).isEqualTo(RUN_ID);
			assertThat(RunStore.runIdFromLeaseKey(leaseKey)).isEqualTo(RUN_ID);
		}

		// 旧裸键：runId 仍可反解（ConfirmGate 的唯一取用口），但<b>没有身份</b>可恢复
		assertThat(RunStore.runIdFromLeaseKey(RunStore.LEASE_RUN_PREFIX + RUN_ID)).isEqualTo(RUN_ID);
		assertThat(RunStore.runRefFromLeaseKey(RunStore.LEASE_RUN_PREFIX + RUN_ID))
			.as("无归属键不得被猜成任何身份")
			.isNull();
		assertThat(RunStore.runRefFromLeaseKey("ai:lease:run:{run:" + RUN_ID + "}"))
			.as("legacy tag 同样没有身份")
			.isNull();
	}

	/**
	 * 系统恢复面：{@code ai:runs} 的复合成员自带身份，恢复方按成员身份 {@code useContext} 就能
	 * 逐轮读回（不扫描租户列表、不靠"当前线程是谁"）。
	 */
	@Test
	void globalIndexRecordsCarryIdentitySoRestoreNeedsNoTenantScan() {
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_A, USER_A)) {
			this.store.create(RUN_ID, SESSION_A, AiContext.empty(), List.of(), this.codec);
		}
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_B, USER_B)) {
			this.store.create(RUN_ID, SESSION_B, AiContext.empty(), List.of(), this.codec);
		}

		var records = this.store.allRunRecords();
		assertThat(records).as("两个身份的同名轮次都要能被恢复面看见").hasSize(2);
		for (var record : records) {
			try (com.example.configmgr.ai.tenant.RunIdentityScope.Scope ignored =
					com.example.configmgr.ai.tenant.RunIdentityScope.open(record.tenantId(), record.userId())) {
				RunSnapshot snapshot = this.store.get(record.runId());
				assertThat(snapshot).isNotNull();
				assertThat(snapshot.getTenantId()).as("按记录身份读到的必须是该记录自己的快照")
					.isEqualTo(record.tenantId());
				assertThat(snapshot.getUserId()).isEqualTo(record.userId());
			}
		}
	}

	/** 13 键删除与多键 Lua 的同槽前提：全部 run 级键共享<b>同一个</b>含身份的 tag。 */
	@Test
	void everyRunLevelKeyOfOneTurnSharesTheIdentityScopedSlot() {
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_B, USER_B)) {
			this.store.create(RUN_ID, SESSION_B, AiContext.empty(), List.of(), this.codec);
		}
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(TENANT_B, USER_B)) {
			Set<Integer> slots = new java.util.LinkedHashSet<>();
			for (String key : List.of(this.store.runKey(RUN_ID), this.store.pendingKey(RUN_ID),
					this.store.claimKey(RUN_ID), this.store.ledgerKey(RUN_ID), this.store.idemKey(RUN_ID),
					this.store.eventsKey(RUN_ID), this.store.seqKey(RUN_ID), this.store.xeventsKey(RUN_ID),
					this.store.runLeaseKey(RUN_ID), this.store.fenceKey(RUN_ID), this.store.reqKey(RUN_ID),
					this.store.decidedKey(RUN_ID), this.store.runLeaseEpochKey(RUN_ID))) {
				slots.add(FakeLeaseRedis.slot(key));
			}
			assertThat(slots).as("一轮的 13 键必须同槽（deleteRun 才是一条原子 DEL）").hasSize(1);
		}
	}

}
