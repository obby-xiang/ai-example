package com.example.configmgr.ai.config;

import lombok.extern.slf4j.Slf4j;
import reactor.core.scheduler.Schedulers;

import java.lang.reflect.Field;

/**
 * 容量联动校验与 boundedElastic 容量实读（T3-5 定值回灌，2026-10-09）。
 *
 * <h2>为什么是"启动期校验"而不是"设计不变量"</h2>
 * 联动式 {@code 挂起专用池 × 2 + 投递池 ≤ boundedElastic 容量} 的右侧由 reactor 缺省常量推导为
 * {@code 10 × availableProcessors()}（可由系统属性 {@code reactor.schedulers.defaultBoundedElasticSize} 覆盖，
 * 见 {@link #boundedElasticCapacity()}），是一条
 * <b>随部署核数线性变化</b>的线 —— 16 核实测 48 ≤ 160 成立（余量 112），而 4 核机上
 * 10 × 4 = 40 &lt; 48 立即不成立。故 T3-5 起该式降级为<b>启动期校验 + 建议最低核数明示</b>：
 * 不满足只打 WARN，不 fail-fast（后果是 boundedElastic 排队拖慢流式，属性能降级而非正确性破坏）。
 *
 * <p>
 * 依据：{@code docs/evidence/M2-T3-5-压测证据-DS-V4-Flash.md} §S1.5（16 核实读 160、要求 48）、
 * §S5.1 约束 1、§S5.3 与 §【待裁决】3（推荐处置即本条）。
 *
 * <p>
 * 抽成独立类的理由：{@link AiProperties} 的 {@code @PostConstruct} 只做一行委派，判定逻辑与
 * 日志副作用都可在单测里直测（满足 ⇒ 无 WARN；不满足 ⇒ 一条 WARN 且不抛）。
 */
@Slf4j
public final class AiPoolCapacityBudget {

	/** reactor {@code boundedElastic} 缺省容量的推导系数（容量 = 系数 × 可用核数）。 */
	public static final int CPUS_PER_THREAD_BUDGET = 10;

	/** reactor 内部常量名（{@code Schedulers#DEFAULT_BOUNDED_ELASTIC_SIZE}）。 */
	private static final String REACTOR_BOUNDED_ELASTIC_SIZE_FIELD = "DEFAULT_BOUNDED_ELASTIC_SIZE";

	private AiPoolCapacityBudget() {
	}

	/** 联动式左侧：一次挂起实占专用池 1 条 + boundedElastic 1 条（ADR-2 重审 N1）。 */
	public static int requiredThreads(int suspendPoolSize, int deliveryPoolSize) {
		return suspendPoolSize * 2 + deliveryPoolSize;
	}

	/** 联动式右侧：boundedElastic 容量按 {@code 10 × CPU} 推导。 */
	public static int derivedBoundedElasticCapacity(int availableProcessors) {
		return CPUS_PER_THREAD_BUDGET * availableProcessors;
	}

	/** 满足联动式所需的最低核数 = ceil(要求线程数 / 10)。 */
	public static int minRequiredProcessors(int suspendPoolSize, int deliveryPoolSize) {
		return (int) Math.ceil(requiredThreads(suspendPoolSize, deliveryPoolSize)
				/ (double) CPUS_PER_THREAD_BUDGET);
	}

	/**
	 * 联动式校验：满足返回 {@code null}，不满足返回 WARN 文案（含实测值、要求值、建议最低核数）。
	 *
	 * <p>
	 * 纯函数（无日志副作用），供单测直接判"满足 ⇒ 无 WARN"。
	 */
	public static String warnMessage(int suspendPoolSize, int deliveryPoolSize, int availableProcessors) {
		int required = requiredThreads(suspendPoolSize, deliveryPoolSize);
		int capacity = derivedBoundedElasticCapacity(availableProcessors);
		if (required <= capacity) {
			return null;
		}
		int minProcessors = minRequiredProcessors(suspendPoolSize, deliveryPoolSize);
		return "容量联动校验不通过（T3-5 定值）：实测 CPU 核数 = " + availableProcessors
				+ "，boundedElastic 缺省容量实测值 = " + capacity
				+ "（推导口径：未设 reactor.schedulers.defaultBoundedElasticSize 系统属性时 = 10 × CPU）；"
				+ "要求值 = 挂起专用池 " + suspendPoolSize + " × 2 + 投递池 " + deliveryPoolSize + " = " + required
				+ " > " + capacity + " ⇒ 挂起并发攀升时 boundedElastic 会排队，拖慢全部 AI 流式（含无关会话）；"
				+ "建议最低核数 = ceil(要求值 / 10) = " + minProcessors
				+ "；处置：下调 app.ai.suspend.pool-size 或 app.ai.sse.delivery-pool-size，"
				+ "或改用核数 ≥ " + minProcessors + " 的部署机（不 fail-fast，仅告警）";
	}

	/** 校验并告警（不满足才打一条 WARN，不抛）。 */
	public static void verify(int suspendPoolSize, int deliveryPoolSize, int availableProcessors) {
		String warning = warnMessage(suspendPoolSize, deliveryPoolSize, availableProcessors);
		if (warning != null) {
			log.warn(warning);
		}
	}

	/**
	 * {@code boundedElastic} 缺省容量：反射实读 reactor 内部常量
	 * {@code Schedulers#DEFAULT_BOUNDED_ELASTIC_SIZE}（与 {@code /api/ai/health} 的观测面同源）。
	 *
	 * <p>
	 * 反射失败（reactor 内部字段改名/移除/不可访问）时回落为同一公式的推导值
	 * {@code 10 × availableProcessors()}，并打一条 WARN 标注取值口径 —— 该推导值在当前
	 * reactor 版本上与实读值等价（§S1.5 实测 16 核 ⇒ 160），回落不改变数值、只改变来源可信度。
	 */
	public static int boundedElasticCapacity() {
		try {
			Field field = Schedulers.class.getField(REACTOR_BOUNDED_ELASTIC_SIZE_FIELD);
			return field.getInt(null);
		}
		catch (ReflectiveOperationException | RuntimeException ex) {
			int derived = derivedBoundedElasticCapacity(Runtime.getRuntime().availableProcessors());
			log.warn("反射读取 reactor.core.scheduler.Schedulers#{} 失败（{}: {}）⇒ "
					+ "boundedElastic 容量回退为推导口径 10 × availableProcessors() = {}",
					REACTOR_BOUNDED_ELASTIC_SIZE_FIELD, ex.getClass().getSimpleName(), ex.getMessage(), derived);
			return derived;
		}
	}

}
