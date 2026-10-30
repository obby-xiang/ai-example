package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tenant.TenantUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 条件创建口（{@link RunStore#createIfAbsent}）的<b>真实键形</b>用例：恢复路径"读无 → 创建"不得覆盖活快照。
 *
 * <h2>被钉住的语义</h2>
 * <ul>
 * <li><b>已存在 ⇒ 不写</b>：返回 {@code false}，活快照的 {@code messageJson}/{@code round}/{@code toolSteps}
 * 一个字节都不变（旧实现无条件 {@code save} 会把它们覆盖成信封旧值）；</li>
 * <li><b>不存在 ⇒ 创建</b>：返回 {@code true} 且落库可读；第二次同样返回 {@code false}；</li>
 * <li><b>身份键语义不变</b>：快照写在记录身份的键上，无身份线程的 legacy 读口看不到它。</li>
 * </ul>
 */
class RunStoreCreateIfAbsentTest {

	private static final String RUN_ID = "run-create-if-absent";

	private static final String NEW_RUN_ID = "run-create-if-absent-new";

	private static final String SESSION_ID = "s-create-if-absent";

	private static final TenantUser RECORD_IDENTITY = new TenantUser(9001L, 7L);

	private final ObjectMapper mapper = new ObjectMapper();

	private final AiProperties properties = new AiProperties();

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final RunStore store = new RunStore(this.fake.redis, this.mapper, this.properties);

	@Test
	void conditionalCreateNeverOverwritesAnExistingSnapshot() {
		// 活快照：round=5 / 步数=9 / 有完整历史（信封里没有、且更旧的那一份）
		this.store.save(snapshot(RUN_ID, 5, 9, List.of("live-history")));
		assertThat(this.store.get(RUN_ID, RECORD_IDENTITY.tenantId(), RECORD_IDENTITY.userId())).isNotNull();

		assertThat(this.store.createIfAbsent(snapshot(RUN_ID, 2, 3, List.of())))
			.as("已存在 ⇒ 不创建（绝不覆盖活快照）")
			.isFalse();
		RunSnapshot after = this.store.get(RUN_ID, RECORD_IDENTITY.tenantId(), RECORD_IDENTITY.userId());
		assertThat(after).isNotNull();
		assertThat(after.getRound()).as("轮次不得回拨").isEqualTo(5);
		assertThat(after.getToolSteps()).as("步数不得回拨").isEqualTo(9);
		assertThat(after.getMessageJson()).as("完整历史不得被清空").containsExactly("live-history");
		assertThat(this.store.get(RUN_ID)).as("身份键语义不变：无身份读口只看 legacy 键").isNull();

		RunSnapshot fresh = snapshot(NEW_RUN_ID, 1, 0, List.of("first"));
		assertThat(this.store.createIfAbsent(fresh)).as("不存在 ⇒ 创建并落库").isTrue();
		RunSnapshot created = this.store.get(NEW_RUN_ID, RECORD_IDENTITY.tenantId(), RECORD_IDENTITY.userId());
		assertThat(created).isNotNull();
		assertThat(created.getRound()).isEqualTo(1);
		assertThat(created.getMessageJson()).containsExactly("first");
		assertThat(this.store.createIfAbsent(fresh)).as("第二次条件创建不再写").isFalse();
	}

	private RunSnapshot snapshot(String runId, int round, int toolSteps, List<String> messageJson) {
		RunSnapshot snapshot = new RunSnapshot();
		snapshot.setRunId(runId);
		snapshot.setSessionId(SESSION_ID);
		snapshot.setTenantId(RECORD_IDENTITY.tenantId());
		snapshot.setUserId(RECORD_IDENTITY.userId());
		snapshot.setStatus(RunSnapshot.RUNNING);
		snapshot.setRound(round);
		snapshot.setToolSteps(toolSteps);
		snapshot.setMessageJson(new ArrayList<>(messageJson));
		return snapshot;
	}

}
