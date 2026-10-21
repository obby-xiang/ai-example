package com.example.configmgr.ai.run;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 资源互斥键名（B23L1 批③a / 裁决 7；S7）。
 *
 * <h2>为什么键名要有用例</h2>
 * 资源锁的失效方式<b>不是抛异常</b>，而是"两处拼出的键长得不一样"：{@code ai:lock:job:7:import}
 * 与 {@code ai:lock:job:7:IMPORT} 各自能抢到，互斥面静默消失，现场只看得到"锁没有生效"。
 * 本类把键形、归一与"前缀不冲突"钉住：{@link LeaseKeys} 是键名的<b>唯一</b>生成入口。
 *
 * <p><b>实例面维度（M8 口径）</b>：键名与实例无关 —— 两个实例拼同一个 taskId/JOBTYPE 必须得到
 * 同一个字符串，否则跨实例互斥不存在。本类只断言"同一个输入 ⇒ 同一个键"，不涉及 Redis。
 */
class LeaseKeysTest {

	@Test
	void taskKeyHasTheDocumentedShape() {
		assertThat(LeaseKeys.task(7L)).isEqualTo("ai:lock:task:7");
		assertThat(LeaseKeys.task(7L)).startsWith(LeaseKeys.TASK_PREFIX).startsWith(LeaseKeys.LOCK_PREFIX);
		assertThat(LeaseKeys.task(7L)).isNotEqualTo(LeaseKeys.task(8L));
	}

	@Test
	void jobKeyNormalizesTheJobTypeToUpperCase() {
		String expected = "ai:lock:job:7:IMPORT";

		assertThat(LeaseKeys.job(7L, "IMPORT")).isEqualTo(expected);
		assertThat(LeaseKeys.job(7L, "import")).as("小写与大写是同一把锁").isEqualTo(expected);
		assertThat(LeaseKeys.job(7L, "  Import  ")).as("首尾空白不影响键").isEqualTo(expected);
		assertThat(LeaseKeys.job(7L, "Import")).as("混合大小写同样归一").isEqualTo(expected);
		assertThat(LeaseKeys.normalizeJobType("export")).isEqualTo("EXPORT");
	}

	@Test
	void differentTaskOrJobTypeMapsToDifferentKeys() {
		assertThat(LeaseKeys.job(7L, "EXPORT")).isNotEqualTo(LeaseKeys.job(7L, "IMPORT"));
		assertThat(LeaseKeys.job(7L, "EXPORT")).isNotEqualTo(LeaseKeys.job(8L, "EXPORT"));
		assertThat(LeaseKeys.job(7L, "IMPORT")).isEqualTo("ai:lock:job:7:IMPORT");
	}

	@Test
	void lockPrefixesDoNotCollideWithEachOtherOrWithOtherKeyFamilies() {
		String task = LeaseKeys.task(7L);
		String job = LeaseKeys.job(7L, "IMPORT");

		assertThat(task).as("任务级锁不是作业级锁的前缀形态").doesNotStartWith(LeaseKeys.JOB_PREFIX);
		assertThat(job).as("作业级锁不是任务级锁的前缀形态").doesNotStartWith(LeaseKeys.TASK_PREFIX);
		assertThat(task).as("task:7 不得成为 task:70 的前缀（前缀扫描不会串）")
			.isNotEqualTo(LeaseKeys.task(70L));
		assertThat(LeaseKeys.task(7L).substring(LeaseKeys.TASK_PREFIX.length())).isEqualTo("7");

		// 与续跑权/认领/快照三族键不重叠：删除/扫描任何一族都不会误伤资源锁
		assertThat(task).doesNotStartWith(RunStore.LEASE_RUN_PREFIX)
			.doesNotStartWith(RunStore.CLAIM_PREFIX)
			.doesNotStartWith(RunStore.RUN_PREFIX);
		assertThat(job).doesNotStartWith(RunStore.LEASE_RUN_PREFIX);
		assertThat(LeaseKeys.LOCK_PREFIX).isNotEqualTo(RunStore.LEASE_RUN_PREFIX);
	}

	@Test
	void invalidInputsAreRejectedInsteadOfProducingAWildcardKey() {
		assertThatThrownBy(() -> LeaseKeys.task(0L)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("taskId");
		assertThatThrownBy(() -> LeaseKeys.task(-1L)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> LeaseKeys.job(7L, null)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("jobType");
		assertThatThrownBy(() -> LeaseKeys.job(7L, "  ")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> LeaseKeys.job(0L, "IMPORT")).as("taskId 的校验同样生效")
			.isInstanceOf(IllegalArgumentException.class);
	}

}
