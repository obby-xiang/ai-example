package com.example.configmgr.ai.run;

import com.example.configmgr.common.CodedIllegalArgumentException;
import com.example.configmgr.common.ErrorCodes;

import java.util.Locale;

/**
 * 资源互斥键名的<b>唯一生成入口</b>（B23L1 批③a / 裁决 7 的 L1 面）。
 *
 * <h2>键形</h2>
 * <ul>
 * <li>{@code ai:lock:task:<taskId>} —— 任务级锁：同一个任务上的破坏性动作（导入/发布/导出补跑）
 * 按任务互斥；</li>
 * <li>{@code ai:lock:job:<taskId>:<JOBTYPE>} —— 作业级锁：同一任务上<b>不同类型的作业</b>可以并行
 * （导出与发布互不阻塞），同类型才互斥。{@code JOBTYPE} 出厂即大写归一
 * （{@code import} / {@code Import} / {@code IMPORT} 落到同一个键 —— 大小写差异不该让两把锁互不可见）。</li>
 * </ul>
 *
 * <h2>为什么单独成类</h2>
 * 键名一旦散落在调用点，两条路径拼出 {@code ai:lock:job:7:import} 与 {@code ai:lock:job:7:IMPORT}
 * 就互相看不见 —— 互斥静默失效，且排障时只看到"锁没生效"。本类把"键长什么样"收成一处，
 * 用例（{@code LeaseKeysTest}）与运维面（{@code ResumeService#describe} 的 {@code keys} 段）
 * 都从同一个入口取键。
 *
 * <h2>与续跑权租约的区别</h2>
 * 续跑权（{@code ai:lease:run:<runId>}，见 {@link RunStore#runLeaseKey(String)}）按 <b>runId</b> 记，
 * 定的是"谁来驱动这一轮"；本类的锁按 <b>taskId</b> 记，定的是"这个业务对象此刻能不能动"。
 * 因此轮次回收（{@link RunStore#deleteRun(String)}）<b>不</b>清本类的键：它们的寿命由使用方
 * 的 TTL（{@code app.ai.lease.resource-ttl}，默认 60s）决定，与 run 的生死无关。
 */
public final class LeaseKeys {

	/** 资源锁键的公共前缀。 */
	public static final String LOCK_PREFIX = "ai:lock:";

	/** 任务级锁前缀（{@code ai:lock:task:<taskId>}）。 */
	public static final String TASK_PREFIX = LOCK_PREFIX + "task:";

	/** 作业级锁前缀（{@code ai:lock:job:<taskId>:<JOBTYPE>}）。 */
	public static final String JOB_PREFIX = LOCK_PREFIX + "job:";

	private LeaseKeys() {
	}

	/** 任务级锁键：{@code ai:lock:task:<taskId>}。 */
	public static String task(long taskId) {
		if (taskId <= 0L) {
			throw new CodedIllegalArgumentException(ErrorCodes.LEASE_KEY_INVALID, "taskId 必须 > 0（当前 = " + taskId + "）：0/负值会拼出"
					+ "一个所有调用方都能命中的'空任务'锁，互斥面随之失效");
		}
		return TASK_PREFIX + taskId;
	}

	/** 作业级锁键：{@code ai:lock:job:<taskId>:<JOBTYPE>}（JOBTYPE 大写归一，见 {@link #normalizeJobType}）。 */
	public static String job(long taskId, String jobType) {
		task(taskId);
		return JOB_PREFIX + taskId + ":" + normalizeJobType(jobType);
	}

	/**
	 * JOBTYPE 归一：去首尾空白 + 大写（{@link Locale#ROOT}，避免土耳其语 {@code i} 之类的区域差异）。
	 *
	 * @throws IllegalArgumentException 空/空白（拼出 {@code ...:} 之类的键，所有"没传作业类型"的
	 * 调用方会共用一把锁 —— 看起来互斥，实际是误伤）
	 */
	public static String normalizeJobType(String jobType) {
		if (jobType == null || jobType.isBlank()) {
			throw new CodedIllegalArgumentException(ErrorCodes.LEASE_KEY_INVALID, "jobType 不能为空：作业级锁的键形是 "
					+ "ai:lock:job:<taskId>:<JOBTYPE>，缺 JOBTYPE 会让不同类型的作业共用一把锁");
		}
		return jobType.trim().toUpperCase(Locale.ROOT);
	}

}
