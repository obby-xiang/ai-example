package com.example.configmgr.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * AI Runtime 运行参数（规格书 S4.2 §4 配置键表，逐键对应）。
 *
 * <p>
 * <b>唯一绑定</b>：{@code app.ai} 前缀只由本类绑定。第一棒时期基座
 * {@code AppProperties.Ai}（max-iterations / session-ttl-minutes / hitl-timeout-minutes /
 * thinking-disabled）与本类并列绑定同一前缀（P1 §5 遗留 N4 双绑定），本棒已删除基座那套：
 * {@code thinking-disabled} 在新运行时早已失效（新路径走"官方循环 + reasoning 补丁"，
 * 不设 {@code extraBody.thinking}），{@code max-iterations} 由官方循环的退出条件取代，
 * 两个 ttl/timeout 键则被本类的 Duration 形态取代。
 *
 * <p>
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

	private Session session = new Session();

	@Data
	public static class Memory {

		/** MessageWindowChatMemory 窗口大小（官方实现负责裁剪）。 */
		private int maxMessages = 40;
	}

	@Data
	public static class Suspend {

		/**
		 * 挂起专用有界平台线程池大小（DC-12：禁用虚拟线程；ADR-2 重审 N1：池容量即挂起并发上限）。
		 *
		 * <p>
		 * BLOCKING 形态下每次挂起实际占用本池 1 条 + 官方硬编码的 {@code boundedElastic} 1 条，
		 * 容量联动约束为"本值 × 2 ≤ boundedElastic 容量"（§13.2 #2 压测范围）。
		 */
		private int poolSize = 20;
	}

	@Data
	public static class Hitl {

		/**
		 * 挂起等待上限（ADR-2 上限约束）：确认门与前端工具挂起共用。
		 * 超过本上限即自动取消（{@code status=TIMEOUT}，工具未执行），循环继续。
		 */
		private Duration timeout = Duration.ofSeconds(120);
	}

	@Data
	public static class Resilience {

		/**
		 * 双超时 + 有界重试（ADR-6）。阈值待技术方案 §13.2 #2 压测裁决，
		 * 本棒只提供配置位与"单轮总预算"兜底；首字节/事件间隔的判定与三条件重试
		 * 属韧性棒次（ResilientChatService）的落地范围。
		 */
		private Duration firstByteTimeout = Duration.ofSeconds(30);

		private Duration interEventTimeout = Duration.ofSeconds(30);

		private int maxAttempts = 2;

		/** 单轮总预算（墙钟）：SSE 超时与流式 {@code blockLast} 的上限。 */
		private Duration totalBudget = Duration.ofMinutes(10);
	}

	@Data
	public static class Session {

		private Lock lock = new Lock();

		@Data
		public static class Lock {

			/** 会话锁 TTL = 轮上限（ADR-5）：正常由 watchdog 续期、轮终态主动释放。 */
			private Duration ttl = Duration.ofMinutes(10);

			/** watchdog 续期间隔（ADR-5 补记 CH-P4：锁须带续期）。 */
			private Duration watchdog = Duration.ofSeconds(30);
		}
	}

}
