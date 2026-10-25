package com.example.configmgr.health;

/**
 * 就绪探针（{@code /health/ready} 的一个维度）。
 *
 * <h2>实现约束</h2>
 * <ul>
 * <li><b>快</b>：探测由 LB/K8s 周期调用，实现应自带短超时（不许无限等待）。硬保证由
 * {@link ReadinessProbeRunner} 提供：所有维度共享一个总时限，超时维度记 DOWN，调用方按期返回；</li>
 * <li><b>只读</b>：不得改业务数据（可以幂等地确保目录存在，但不得写探针文件/业务表）；</li>
 * <li><b>不抛</b>：任何异常都收敛成 {@link ProbeResult#down}，由 {@link ReadinessProbeRunner} 兜底捕获；</li>
 * <li><b>无敏感值</b>：detail 会进 HTTP 响应体与日志，不得含 key/口令。</li>
 * </ul>
 *
 * <h2>required：硬依赖还是降级维度（由探针自己声明）</h2>
 * {@link #required()} 决定该维度 DOWN 时的后果：
 * <ul>
 * <li>{@code true}（默认）：<b>硬依赖</b>，DOWN ⇒ {@code ready=false} ⇒ 503 ⇒ LB 摘流；</li>
 * <li>{@code false}：<b>降级维度</b>，DOWN 只出现在 {@code checks.<name>} 与 {@code degraded} 列表，
 * 不改 ready（例如 Redis：AI 面降级，但读配置/导出/任务等业务仍可经统一地址访问）。</li>
 * </ul>
 * 这样"哪些依赖算准入"是探针的契约，而不是调用方按名字做字符串特判（名字只用于响应键与断言锚点）。
 *
 * <p>{@link ReadinessService} 依赖 Spring 的 {@code List<ReadinessProbe>} 注入收集全部实现，
 * 新增维度只需加一个 {@code @Component}。
 */
public interface ReadinessProbe {

	/** 维度名（响应体 {@code checks.<name>} 的键，同时是断言锚点，禁止改名）。 */
	String name();

	ProbeResult check();

	/**
	 * 该维度是否是 ready 的**硬依赖**（默认 {@code true}）。
	 *
	 * <p>只有明确"故障时仍应接业务流量"的设施才返回 {@code false}，且必须在实现的类注里写清
	 * 降级后的行为与用户可见影响。
	 */
	default boolean required() {
		return true;
	}

	/**
	 * 本维度 DOWN 时要标记降级的能力名（响应体 {@code capabilities.<name>.degraded}），
	 * {@code null} = 不影响能力面（默认）。
	 *
	 * <p>为什么不让 ReadinessService 按维度名/降级列表推断：能力面是**语义**，不是"任意一个
	 * required=false 维度挂了"。例如未来加入"指标推送"这类可选维度，它 DOWN 只该进 {@code degraded}
	 * 列表，不该把 {@code capabilities.ai.degraded} 点亮。由探针自己声明它承载哪个能力面，
	 * 调用方按能力名聚合，新增可选维度时不会误标。
	 */
	default String capability() {
		return null;
	}

	/** AI 能力面的名字（{@link #capability()} 的返回值之一）。 */
	String CAPABILITY_AI = "ai";

}
