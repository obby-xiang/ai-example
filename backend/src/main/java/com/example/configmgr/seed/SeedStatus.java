package com.example.configmgr.seed;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 种子播种的**进程内**状态（{@code /health/ready} 的 seed 维度取数点）。
 *
 * <h2>为什么是进程内而不是库里的表</h2>
 * "本实例有没有完成播种"是实例级事实：三个实例各自跑一遍播种逻辑（第二个起在行锁后判重跳过），
 * 每个实例都必须**自己**走到终态才算 ready。放进共享库反而会让"B 实例复用了 A 的结论"，
 * 掩盖 B 自己播种失败（例如 B 的迁移没跑、权限不同）。跨实例的互斥由库里的 {@code app_lock} 行锁负责，
 * 两件事不重叠。
 *
 * <h2>ready 判据（与 seed 相关的部分）</h2>
 * <ul>
 * <li>{@code BASE}（基座 7 定义 + 主数据）必须 DONE / SKIPPED —— 它失败时实例本来就起不来
 * （ApplicationRunner 抛异常 ⇒ 应用退出），这里只是把"还没跑完"如实报成未就绪；</li>
 * <li>{@code GLM}（glm 并集 8 定义）必须进入终态（DONE / SKIPPED / FAILED）。<b>FAILED 仍算 ready</b>：
 * 该类本来就是 fail-open 设计（失败只 WARN/ERROR，基座 7 个定义不受影响），若因它把实例永久摘流，
 * 等于把"少 8 个样例定义"升级成"全站不可用"；失败事实通过 {@link #describe()} 的
 * {@code failures} 暴露给运维，不静默。</li>
 * </ul>
 */
@Slf4j
@Component
public class SeedStatus {

	/** 播种单元（与 DataSeedRunner / GlmSeedRunner 一一对应）。 */
	public enum Seed {

		/** 基座种子（regions/projects/7 定义 + 价格/税率数据）。 */
		BASE("base"),

		/** glm 并集种子（8 定义 + 三层级范围数据）。 */
		GLM("glm");

		private final String key;

		Seed(String key) {
			this.key = key;
		}

		public String key() {
			return this.key;
		}

	}

	/** 播种阶段。PENDING/RUNNING 均视为"未就绪"。 */
	public enum State {
		PENDING, RUNNING, DONE, SKIPPED, FAILED
	}

	private final Map<Seed, State> states = new ConcurrentHashMap<>();

	private final Map<Seed, String> failures = new ConcurrentHashMap<>();

	public SeedStatus() {
		for (Seed seed : Seed.values()) {
			this.states.put(seed, State.PENDING);
		}
	}

	public synchronized void markRunning(Seed seed) {
		this.states.put(seed, State.RUNNING);
	}

	public synchronized void mark(Seed seed, State state, String detail) {
		this.states.put(seed, state);
		if (state == State.FAILED) {
			this.failures.put(seed, detail == null ? "unknown" : detail);
			log.warn("种子 {} 失败（ready 仍为可用，失败事实进 /health/ready）：{}", seed.key(), detail);
		}
		else {
			this.failures.remove(seed);
		}
	}

	public synchronized void markFailed(Seed seed, Throwable error) {
		String detail = error == null ? "unknown"
				: error.getClass().getSimpleName() + (error.getMessage() == null ? "" : ": " + error.getMessage());
		mark(seed, State.FAILED, detail);
	}

	public State stateOf(Seed seed) {
		return this.states.getOrDefault(seed, State.PENDING);
	}

	/**
	 * ready 判据（见类注）：BASE 成功终态 + GLM 任意终态。
	 *
	 * <p>注意 BASE 的 FAILED 也不算 ready —— 但那条路径下应用已退出，本判据只是不给出假绿。
	 */
	public boolean isReady() {
		State base = stateOf(Seed.BASE);
		State glm = stateOf(Seed.GLM);
		boolean baseOk = base == State.DONE || base == State.SKIPPED;
		boolean glmSettled = glm == State.DONE || glm == State.SKIPPED || glm == State.FAILED;
		return baseOk && glmSettled;
	}

	/** 观测/断言用：{@code {"base":"DONE","glm":"SKIPPED"}}，有失败时附加 {@code failures}。 */
	public Map<String, Object> describe() {
		Map<String, Object> out = new LinkedHashMap<>();
		for (Seed seed : Seed.values()) {
			out.put(seed.key(), stateOf(seed).name());
		}
		Map<String, Object> failed = new LinkedHashMap<>();
		this.failures.forEach((seed, detail) -> failed.put(seed.key(), detail));
		if (!failed.isEmpty()) {
			out.put("failures", failed);
		}
		return out;
	}

}
