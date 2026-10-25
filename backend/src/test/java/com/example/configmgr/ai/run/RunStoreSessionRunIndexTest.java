package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.tool.AiContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话 → runId 的轻量索引（{@code ai:session:runs:<sessionId>}）。
 *
 * <p>销毁枚举优先读它，避免为了找"本会话的轮次"把所有 {@code ai:runs} 快照全量反序列化。
 * 证红：不写/不删此索引 ⇒ 两条断言都失败。
 */
class RunStoreSessionRunIndexTest {

	private static final String SESSION = "s-index";

	private static final String RUN = "run-index-1";

	private final ObjectMapper mapper = new ObjectMapper();

	private final AiProperties properties = new AiProperties();

	private final FakeLeaseRedis fake = new FakeLeaseRedis();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunStore store = new RunStore(this.fake.redis, this.mapper, this.properties);

	@Test
	void createAddsSessionIndexWithTtlAndDeleteRunRemovesIt() {
		this.store.create(RUN, SESSION, AiContext.empty(), List.of(), this.codec);

		String key = RunStore.SESSION_RUNS_PREFIX + SESSION;
		assertThat(this.store.sessionRunIds(SESSION)).containsExactly(RUN);
		assertThat(this.fake.setsByKey.getOrDefault(key, Set.of())).containsExactly(RUN);
		assertThat(this.fake.expiry(key)).as("轻量索引与会话/快照同寿").isEqualTo(this.properties.getSessionTtl());

		this.store.deleteRun(RUN);

		assertThat(this.fake.setsByKey).as("空 SET 由 Redis 自动消失").doesNotContainKey(key);
		assertThat(this.fake.setsByKey).doesNotContainKey(RunStore.RUN_INDEX);
		assertThat(this.store.sessionRunIds(SESSION)).isEmpty();
	}

	@Test
	void createDoesNotClaimCompletenessAndBackfillAddsHiddenSentinel() {
		this.store.create(RUN, SESSION, AiContext.empty(), List.of(), this.codec);

		RunStore.SessionRunIndex afterCreate = this.store.sessionRunIndex(SESSION);
		assertThat(afterCreate.readable()).isTrue();
		assertThat(afterCreate.complete()).as("create 只加新 run，不能宣称老数据已回填").isFalse();
		assertThat(afterCreate.runIds()).containsExactly(RUN);

		this.store.backfillSessionRuns(SESSION, Set.of(RUN, "run-old"));

		RunStore.SessionRunIndex backfilled = this.store.sessionRunIndex(SESSION);
		assertThat(backfilled.complete()).isTrue();
		assertThat(backfilled.runIds()).containsExactlyInAnyOrder(RUN, "run-old");
		assertThat(backfilled.runIds()).as("sentinel 不得暴露成 runId")
			.doesNotContain(RunStore.SESSION_RUNS_COMPLETE);
		assertThat(this.store.sessionRunIds(SESSION)).doesNotContain(RunStore.SESSION_RUNS_COMPLETE);
		assertThat(this.fake.expiry(RunStore.SESSION_RUNS_PREFIX + SESSION))
			.as("sentinel 与索引同 TTL").isEqualTo(this.properties.getSessionTtl());
	}

	@Test
	void optionalSessionIndexWriteFailureDoesNotFailCreate() {
		String key = RunStore.SESSION_RUNS_PREFIX + SESSION;
		org.springframework.data.redis.core.SetOperations<String, String> setOps = this.fake.redis.opsForSet();
		org.mockito.Mockito.doThrow(new IllegalStateException("可选索引不可用"))
			.when(setOps).add(key, RUN);

		org.assertj.core.api.Assertions.assertThatCode(
			() -> this.store.create(RUN, SESSION, AiContext.empty(), List.of(), this.codec))
			.doesNotThrowAnyException();
		assertThat(this.store.get(RUN)).isNotNull();
		assertThat(this.fake.setsByKey.get(RunStore.RUN_INDEX)).contains(RUN);
	}

	@Test
	void sessionIndexIsScopedAndOtherSessionsStayUntouched() {
		this.store.create(RUN, SESSION, AiContext.empty(), List.of(), this.codec);
		this.store.create("run-other", "s-other", AiContext.empty(), List.of(), this.codec);

		assertThat(this.store.sessionRunIds(SESSION)).containsExactly(RUN);
		assertThat(this.store.sessionRunIds("s-other")).containsExactly("run-other");

		this.store.deleteRun(RUN);

		assertThat(this.store.sessionRunIds(SESSION)).isEmpty();
		assertThat(this.store.sessionRunIds("s-other")).containsExactly("run-other");
	}

}
