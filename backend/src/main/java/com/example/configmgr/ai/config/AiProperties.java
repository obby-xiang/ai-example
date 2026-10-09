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

	private Sse sse = new Sse();

	private Resume resume = new Resume();

	private ToolResult toolResult = new ToolResult();

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
		 * BLOCKING 形态下每次挂起实际占用本池 1 条 + 官方硬编码的 {@code boundedElastic} 1 条；
		 * 容量联动约束为「专用池 × 2 + 投递池 ≤ boundedElastic 容量」（T3-1 投递池
		 * {@link Sse#getDeliveryPoolSize()}，§13.2 #2 压测范围）。
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
		 * 首包静默上限：从"发起本轮请求"到"上游第一个事件"之间允许的最长静默。
		 *
		 * <p>
		 * 初值 30s —— <b>待技术方案 §13.2 #2 压测裁决</b>（模型排队/冷启动时的实际首包分布）。
		 */
		private Duration firstByteTimeout = Duration.ofSeconds(30);

		/**
		 * 事件间静默上限：两个上游事件之间允许的最长静默。
		 *
		 * <p>
		 * 初值 90s —— <b>待 §13.2 #2 压测裁决</b>。注意它与硬规范①（阈值 &gt; 最长工具耗时）的
		 * 关系：挂起等待（确认门默认 120s &gt; 90s）期间工具处于"活跃"，
		 * {@link com.example.configmgr.ai.run.StreamWatchdog} 在工具执行期间<b>不计静默</b>、
		 * 退出后以退出时刻重新计时，因此本值小挂起上限仍正确；但"配置值本身要不要直接
		 * 大于 {@code app.ai.hitl.timeout}"列为【待裁决】。
		 */
		private Duration interEventTimeout = Duration.ofSeconds(90);

		/** 单轮最大尝试次数（首轮 + 重试次数-1）。 */
		private int maxAttempts = 2;

		/**
		 * 单轮总预算（墙钟）：SSE 超时、流式等待上限与韧性看门狗的共同上界。
		 *
		 * <p>
		 * 初值 300s —— <b>待 §13.2 #2 压测裁决</b>（现网最长一轮的真实耗时）。
		 */
		private Duration totalBudget = Duration.ofSeconds(300);
	}

	@Data
	public static class Resume {

		/**
		 * 启动自动续跑（N3/N8）：应用就绪后扫描 {@link com.example.configmgr.ai.run.RunStore}
		 * 里仍存活的轮次，把"已有人类决策/后端工具在飞"的那些自动续跑。
		 *
		 * <p>
		 * 默认 false（保守：自动补执行破坏性工具需谨慎），由运维在 {@code application.yml}
		 * 显式打开；打开后仍<b>只</b>续跑"已有结论"的条目（{@code APPROVED/EXECUTED/REJECTED/TIMEOUT/}
		 * {@code FRONTEND_RESULT/FRONTEND_CANCELLED/REJECTED_ARGUMENTS/BLOCKED}）—— 仍 {@code PENDING}
		 * （人工/前端未给结论）的一律跳过，因此不会绕开人审。
		 */
		private boolean onStartup = false;

		/** 启动扫描的延迟（等 Web 容器与 Redis 连接就绪，避免与启动路径抢资源）。 */
		private Duration startupDelay = Duration.ofSeconds(8);

		/** 僵尸判定：状态 {@code RUNNING} 且 {@code updatedAtMs} 超过本值 ⇒ 判为僵尸轮并接管（N3）。 */
		private Duration zombieAge = Duration.ofSeconds(60);

		/** 单次启动扫描最多自动续跑多少轮（防止启动瞬间把挂起池占满）。 */
		private int maxOnStartup = 10;
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

	/**
	 * SSE 扇出投递参数（T3-1 慢订阅者隔离）。
	 *
	 * <p>
	 * 容量联动（S2-8）：{@code 专用池 × 2 + 投递池 ≤ boundedElastic 容量}，纳入 §13.2 #2
	 * 压测（T3-5 场景 E：N 挂起 × M 慢订阅者争抢 boundedElastic 的最坏态）。
	 */
	@Data
	public static class Sse {

		/**
		 * 共享有界投递池的<b>工作线程数</b>（R3 语义：池"容量"即线程数）。
		 *
		 * <p>
		 * 每订阅者一条<b>实时段有界</b>队列（回放初始批豁免，闸按实时段计数判定）+ CAS drain
		 * 独占标志，一条慢连接至多占一个工作线程；
		 * 池任务队列满时提交被拒 ⇒ 摘除触发本次提交的订阅者（显式 complete，前端重挂补帧）。
		 * 初值 4 —— <b>待 §13.2 #2 压测裁决</b>（T3-5 定值）。
		 */
		private int deliveryPoolSize = 4;

		/**
		 * 每订阅者<b>实时</b>投递队列容量的缺省值（<b>单一事实源</b>）。
		 *
		 * <p>
		 * 写出器与注册表都引用本常量（S3-1 修复批：此前 256 同时写在本类与 {@code SseChatEmitter}
		 * 两处，改坏一处不必然变红）。R1 不变量：本值 &lt; 归档窗口
		 * {@code RunStore#EVENT_WINDOW}（只约束实时段，回放初始批豁免）。
		 * 初值 256 —— <b>待 §13.2 #2 压测裁决</b>（T3-5 定值）。
		 */
		public static final int DEFAULT_DELIVERY_QUEUE_CAPACITY = 256;

		/**
		 * 每订阅者<b>实时</b>投递队列容量（缺省 {@link #DEFAULT_DELIVERY_QUEUE_CAPACITY}）。
		 *
		 * <p>
		 * R1 不变量：队列容量 &lt; 归档窗口 3000（只约束实时段）；回放初始批豁免本容量，
		 * 上界 = 归档窗口 {@code EVENT_WINDOW}。溢出即摘除（主动断连）+ complete。
		 * <b>判据口径（修复批第 2 轮）</b>：闸判「该订阅者队列中的<b>实时</b>帧条数」，
		 * <b>不</b>判队列绝对长度 —— 回放批与实时帧共用同一条队列（单队列 FIFO 混合序），
		 * 回放帧不计入本容量。
		 */
		private int deliveryQueueCapacity = DEFAULT_DELIVERY_QUEUE_CAPACITY;
	}

	/**
	 * 工具结果<b>回填模型</b>时的上限（DC-14 T2 / R10-1 落实，§13.2 #9"回填截断长度"的取值落点）。
	 *
	 * <p>
	 * <b>只约束"给模型看的"那一份</b>：工具结果进 {@code role:tool} 消息前按行数与字符数裁剪；
	 * 发给前端的 {@code tool_result} 帧与 Redis 台账里的 {@code resultText} 都是<b>全文</b>
	 * （两条通道分离，前端渲染/取证不受模型侧上限影响）。
	 *
	 * <p>
	 * 为什么必须配置化：万行级设计目标下，一个 {@code list_*} 类工具的输出可以轻易占满整轮上下文，
	 * 而"到底多少行合适"取决于模型窗口与业务密度，不能写死在代码里。初值 200 行 / 8000 字符
	 * 依 R10-1 建议值，实际取值随 §13.2 #9 压测复核。
	 */
	@Data
	public static class ToolResult {

		/** 回填模型的最大行数（超出即截断并附截断标记）。 */
		private int maxRows = 200;

		/** 回填模型的最大字符数（行数未超但字符超限时同样截断）。 */
		private int maxChars = 8000;
	}

}
