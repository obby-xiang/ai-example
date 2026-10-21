package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运维面（{@code ResumeService#describe}）的<b>资源锁键</b>（B23L1 批③a）。
 *
 * <h2>为什么要单列</h2>
 * {@code describe().keys} 是排障时"这个轮次的键长什么样"的唯一入口。批③a 之前它只有 runId 族的键；
 * 资源锁按 <b>taskId</b> 记（{@link LeaseKeys}），不在 runId 族里 —— 运维对着 runId 找不到锁，
 * 就会得出"锁没生效"的错误结论。本类断言两把锁的键与"上下文没有 taskId 时的空值形态"。
 *
 * <p>与 {@code ResumeServiceLeaseTest} 的分工：那个类锁定批②的续跑权键面（lease/fence），
 * <b>不改一行</b>；本类只覆盖批③a 新增的两个键，用最小装配（describe 只读 store + codec）。
 *
 * <p><b>实例面维度（M8 口径）</b>：键名只由快照上下文推导，与实例/owner 无关 ——
 * 两个实例对同一轮 describe 出同一组键（否则排障时两台机器看到的"锁键"不一样）。
 */
class ResumeDescribeLockKeysTest {

	private static final String RUN_ID = "run-describe-locks";

	private final InMemoryRunStore store = new InMemoryRunStore();

	private final AiProperties properties = new AiProperties();

	private final MessageJsonCodec codec = new MessageJsonCodec(new ObjectMapper());

	private final ResumeService service = new ResumeService(this.store, null, this.codec, null, null, null, null,
			this.properties, null, new ToolResultLimiter(this.properties));

	@Test
	void describeExposesTaskAndJobLockKeysDerivedFromTheContext() {
		seed(AiContext.of("export", "EXPORT", "EXPORT", 7L, null));

		@SuppressWarnings("unchecked")
		Map<String, Object> keys = (Map<String, Object>) this.service.describe(RUN_ID).get("keys");

		assertThat(keys).containsEntry("lockTask", "ai:lock:task:7")
			.containsEntry("lockJob", "ai:lock:job:7:EXPORT");
		assertThat(keys.get("lockTask")).isEqualTo(LeaseKeys.task(7L));
		assertThat(keys.get("lockJob")).isEqualTo(LeaseKeys.job(7L, "EXPORT"));
		// 批②的续跑权键面不受影响（同一个 keys 段里并存）
		assertThat(keys).containsKeys("lease", "fence", "state", "pending", "ledger", "events", "claim");
	}

	@Test
	void jobLockKeyFallsBackToTheTaskTypeWhenTheStepIsAbsent() {
		seed(AiContext.of("tasks", "IMPORT", null, 9L, null));

		@SuppressWarnings("unchecked")
		Map<String, Object> keys = (Map<String, Object>) this.service.describe(RUN_ID).get("keys");

		assertThat(keys).containsEntry("lockJob", "ai:lock:job:9:IMPORT");
	}

	@Test
	void pageTurnsWithoutATaskCarryNullLockKeysInsteadOfAMisleadingKey() {
		seed(AiContext.of("tasks", null, null, null, null));

		@SuppressWarnings("unchecked")
		Map<String, Object> keys = (Map<String, Object>) this.service.describe(RUN_ID).get("keys");

		assertThat(keys).as("键仍在（固定键面），值为 null（不可推导 ≠ 拼一个假键）")
			.containsKey("lockTask")
			.containsKey("lockJob")
			.doesNotContainValue("ai:lock:task:null");
		assertThat(keys.get("lockTask")).isNull();
		assertThat(keys.get("lockJob")).isNull();
	}

	private void seed(AiContext context) {
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, "s-describe-locks");
		snapshot.setContext(context.toMap());
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		this.store.save(snapshot);
	}

}
