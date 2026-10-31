package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 会话级 {@code create_task} 候选草案（{@code ai:ctdraft}）：<b>会话一个 HASH、字段 = taskId</b>。
 *
 * <p>锁四件事：
 * <ol>
 * <li><b>同会话跨 run 按 taskId 读写</b>：run 只是会话号的解析入口，任务号才是字段 ——
 * 换一轮 run 仍读到同一份事实，不同任务号互不覆盖；</li>
 * <li><b>空值不覆盖</b>：空的 {@code conditions={}} / 空 defCodes 不表示"已知"，不得抹掉先前的明确候选；</li>
 * <li><b>身份隔离</b>：同一 sessionId 在不同 tenant/user 下落在不同键上，互不可读；</li>
 * <li><b>坏值/读失败是内部失败</b>：内容损坏、Redis 读失败、快照归属不可读一律抛
 * {@link IllegalStateException}，不得伪装成"没有候选"。</li>
 * </ol>
 */
@SuppressWarnings("unchecked")
class CreateTaskDraftStoreTest {

	private static final String SESSION_ID = "sess-draft";

	private static final long TASK_ID = 42L;

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final ObjectMapper mapper = new ObjectMapper();

	private final RunStore runStore = mock(RunStore.class);

	private CreateTaskDraftStore store;

	@BeforeEach
	void setUp() {
		ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);
		when(redisProvider.getIfAvailable()).thenReturn(this.fake.redis);
		ObjectProvider<RunStore> runStoreProvider = mock(ObjectProvider.class);
		when(runStoreProvider.getIfAvailable()).thenReturn(this.runStore);
		when(this.runStore.snapshotOwner("run-1")).thenReturn(owner(SESSION_ID));
		when(this.runStore.snapshotOwner("run-2")).thenReturn(owner(SESSION_ID));
		when(this.runStore.snapshotOwner("run-3")).thenReturn(owner(SESSION_ID));
		when(this.runStore.snapshotOwner("run-missing")).thenReturn(new RunStore.SnapshotOwner(
				RunStore.SnapshotOwner.State.MISSING, null));
		when(this.runStore.snapshotOwner("run-unreadable")).thenReturn(new RunStore.SnapshotOwner(
				RunStore.SnapshotOwner.State.UNREADABLE, null));
		this.store = new CreateTaskDraftStore(redisProvider, this.mapper, new AiProperties(), runStoreProvider);
	}

	@Test
	void sameSessionReadsAndWritesByTaskIdAcrossRuns() {
		Map<String, Object> conditions = conditions("code", "EQ", "CNY");
		Map<String, Object> otherConditions = conditions("code", "IN", List.of("USD", "EUR"));

		this.store.save("run-1", TASK_ID, List.of("CURRENCY"), conditions);
		this.store.save("run-2", TASK_ID + 1, List.of("FX_RATE"), otherConditions);

		CreateTaskDraftStore.Draft first = this.store.read("run-2", TASK_ID);
		assertThat(first).as("换一轮 run（同一会话）仍按 taskId 读到同一份草案").isNotNull();
		assertThat(first.defCodes()).containsExactly("CURRENCY");
		assertThat(first.conditions()).isEqualTo(conditions);

		CreateTaskDraftStore.Draft second = this.store.read("run-1", TASK_ID + 1);
		assertThat(second).as("不同 taskId 是不同字段，互不覆盖").isNotNull();
		assertThat(second.defCodes()).containsExactly("FX_RATE");
		assertThat(second.conditions()).isEqualTo(otherConditions);

		assertThat(this.store.read("run-3", TASK_ID + 99)).as("没有草案的任务号读不到").isNull();
		assertThat(this.fake.expiry(CreateTaskDraftStore.sessionKey(SESSION_ID)))
			.as("TTL 与会话同寿（app.ai.session-ttl 默认 24h）")
			.isEqualTo(Duration.ofHours(24));
	}

	@Test
	void emptyConditionsNeverOverwriteTheNonEmptyCandidate() {
		Map<String, Object> conditions = conditions("code", "EQ", "CNY");
		this.store.save("run-1", TASK_ID, List.of("CURRENCY"), conditions);

		this.store.save("run-2", TASK_ID, List.of("CURRENCY"), Map.of());
		CreateTaskDraftStore.Draft afterEmptyConditions = this.store.read("run-3", TASK_ID);
		assertThat(afterEmptyConditions.defCodes()).containsExactly("CURRENCY");
		assertThat(afterEmptyConditions.conditions()).as("空 conditions={} 不表示已知，不得覆盖明确候选")
			.isEqualTo(conditions);

		this.store.save("run-2", TASK_ID, List.of(), Map.of());
		CreateTaskDraftStore.Draft afterEmptyDraft = this.store.read("run-3", TASK_ID);
		assertThat(afterEmptyDraft.defCodes()).containsExactly("CURRENCY");
		assertThat(afterEmptyDraft.conditions()).isEqualTo(conditions);
	}

	@Test
	void identityScopesKeepTheSameSessionAndTaskApart() {
		Map<String, Object> aConditions = conditions("code", "EQ", "CNY");
		Map<String, Object> bConditions = conditions("code", "EQ", "USD");

		String keyA;
		try (RequestContextUtils.SafeCloseable scope = RequestContextUtils.useContext(1L, 101L)) {
			keyA = CreateTaskDraftStore.sessionKey(SESSION_ID);
			this.store.save("run-1", TASK_ID, List.of("CURRENCY"), aConditions);
		}
		String keyB;
		try (RequestContextUtils.SafeCloseable scope = RequestContextUtils.useContext(2L, 201L)) {
			keyB = CreateTaskDraftStore.sessionKey(SESSION_ID);
			assertThat(this.store.read("run-1", TASK_ID)).as("别的身份读不到这个草案").isNull();
			this.store.save("run-1", TASK_ID, List.of("FX_RATE"), bConditions);
		}
		assertThat(keyA).as("同一 sessionId 不同身份必须落在不同键上").isNotEqualTo(keyB);

		try (RequestContextUtils.SafeCloseable scope = RequestContextUtils.useContext(1L, 101L)) {
			CreateTaskDraftStore.Draft draft = this.store.read("run-2", TASK_ID);
			assertThat(draft.defCodes()).containsExactly("CURRENCY");
			assertThat(draft.conditions()).isEqualTo(aConditions);
		}
		try (RequestContextUtils.SafeCloseable scope = RequestContextUtils.useContext(2L, 201L)) {
			CreateTaskDraftStore.Draft draft = this.store.read("run-2", TASK_ID);
			assertThat(draft.defCodes()).containsExactly("FX_RATE");
			assertThat(draft.conditions()).isEqualTo(bConditions);
		}
	}

	@Test
	void corruptContentAndReadFailuresSurfaceAsInternalFailures() {
		this.store.save("run-1", TASK_ID, List.of("CURRENCY"), conditions("code", "EQ", "CNY"));
		String key = CreateTaskDraftStore.sessionKey(SESSION_ID);
		this.fake.hashTables.get(key).put(Long.toString(TASK_ID), "{broken");

		assertThatThrownBy(() -> this.store.read("run-2", TASK_ID))
			.as("草案内容损坏 = 内部失败（不是没有候选）")
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("不可解析");

		this.fake.goDown();
		assertThatThrownBy(() -> this.store.read("run-2", TASK_ID))
			.as("Redis 读失败必须上抛")
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("读取会话任务草案失败");
		this.fake.heal();

		assertThatThrownBy(() -> this.store.read("run-unreadable", TASK_ID))
			.as("快照归属不可读 = 内部失败")
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("无法解析轮次快照的会话归属");
		assertThat(this.store.read("run-missing", TASK_ID))
			.as("快照不存在/无归属 = 没有草案（不建键、不抛）")
			.isNull();
	}

	private static Map<String, Object> conditions(String fieldCode, String operator, Object value) {
		Map<String, Object> field = new LinkedHashMap<>();
		field.put("fieldCode", fieldCode);
		field.put("operator", operator);
		field.put("value", value);
		Map<String, Object> conditions = new LinkedHashMap<>();
		conditions.put("fields", List.of(field));
		return conditions;
	}

	private static RunStore.SnapshotOwner owner(String sessionId) {
		return new RunStore.SnapshotOwner(RunStore.SnapshotOwner.State.ATTRIBUTED, sessionId);
	}

}
