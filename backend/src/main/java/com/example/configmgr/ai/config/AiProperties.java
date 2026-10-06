package com.example.configmgr.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * AI Runtime 运行参数（规格书 S4.2 §4）。
 *
 * <p>
 * 与基座遗留的 {@link com.example.configmgr.config.AppProperties.Ai} 并列绑定同一个前缀
 * {@code app.ai}：两边字段名互不重叠，各绑各的（基座键 session-ttl-minutes /
 * hitl-timeout-minutes / max-iterations / thinking-disabled 保持原样，本类只声明规格 §4 的键）。
 * 模型侧参数不在这里，走官方 {@code spring.ai.openai.*}（DC-01 全配置化）。
 */
@Data
@ConfigurationProperties(prefix = "app.ai")
public class AiProperties {

	/** 会话记忆滑动 TTL（写入刷新，D1/DC-02：会话不做长期留存）。 */
	private Duration sessionTtl = Duration.ofHours(6);

	private Memory memory = new Memory();

	private Suspend suspend = new Suspend();

	private Hitl hitl = new Hitl();

	private Resilience resilience = new Resilience();

	@Data
	public static class Memory {

		/** MessageWindowChatMemory 窗口大小（官方实现负责裁剪）。 */
		private int maxMessages = 40;
	}

	@Data
	public static class Suspend {

		/** 挂起专用有界平台线程池大小（DC-12：禁用虚拟线程）。 */
		private int poolSize = 20;

		/** 有界队列容量：满则拒绝（第二棒的 SUSPEND_POOL_SATURATED 语义）。 */
		private int queueCapacity = 100;
	}

	@Data
	public static class Hitl {

		/** 确认门超时（上限约束 ADR-2）；状态机与确认门在第二棒落地，本棒只保留配置位。 */
		private Duration timeout = Duration.ofSeconds(120);
	}

	@Data
	public static class Resilience {

		/**
		 * 单轮总预算（墙钟）。ADR-6 的双超时/重试三条件阈值待 §13.2 #2 压测裁决，
		 * 本棒只用总预算兜住"单轮不无限挂死"。
		 */
		private Duration totalBudget = Duration.ofMinutes(10);
	}

}
