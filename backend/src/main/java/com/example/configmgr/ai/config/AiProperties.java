package com.example.configmgr.ai.config;

import com.example.configmgr.common.sse.SseWriteBudget;
import jakarta.annotation.PostConstruct;
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

	/**
	 * 会话记忆滑动 TTL（写入刷新；默认 24h，用户裁决 2026-10-20 第 4 条）。
	 *
	 * <p>滑动续期 = 活跃即续；整个会话维度，<b>无绝对上限</b>。run 级外置键（快照/待决/台账/帧/序号/心跳）
	 * 的 TTL 同取本值且每次写/心跳都整额续期 —— 挂起等人期间卡的背景数据不会先于会话过期。
	 */
	private Duration sessionTtl = Duration.ofHours(24);

	private Memory memory = new Memory();

	private Suspend suspend = new Suspend();

	private Hitl hitl = new Hitl();

	private Resilience resilience = new Resilience();

	private Session session = new Session();

	private Sse sse = new Sse();

	private Resume resume = new Resume();

	private ToolResult toolResult = new ToolResult();

	private Frame frame = new Frame();

	private Lease lease = new Lease();

	private Lock lock = new Lock();

	private Input input = new Input();

	private Limit limit = new Limit();

	private Config config = new Config();

	private Context context = new Context();

	/**
	 * 模型配置的读写开关（模型配置专项批①；设计稿 §3.3 / §3.4）。
	 *
	 * <p>
	 * 批① 只有两个键：{@link #seedForce}（strict seed 的显式运维逃生门）与
	 * {@link #reloadInterval}（读路径版本比对的最小间隔）。URL 闸与私网开关属于批②。
	 */
	@Data
	public static class Config {

		/**
		 * 强制重灌 env 种子（默认 false = strict seed：表非空时 env 完全不参与）。
		 *
		 * <p>
		 * 默认 false 是<b>刻意</b>的：表非空后改 env 不再改变线上行为（否则运维改 env 会静默
		 * 改掉 DB 真源，与"DB 为真源"自相矛盾）。需要重灌时显式打开本键并重启 —— 动作本身
		 * 就是"我知道会覆盖 seed 行的 base-url/model"。
		 */
		private boolean seedForce = false;

		/**
		 * 读路径版本比对的最小间隔（缺省 1s）。
		 *
		 * <p>
		 * 稳态 = 至多 1 次版本比对/秒/实例（Redis 有 {@code ai:cfg:ver} 时只 GET 一次；Redis 不可用
		 * 时退化为一次 DB 主键查询），<b>不</b>在 chat 热路径上逐轮打表。单测把它设 0 让版本比对
		 * 每次都做（确定性，不靠 sleep）。
		 */
		private Duration reloadInterval = Duration.ofSeconds(1);
	}

	/**
	 * 启动期校验 {@code app.ai.sse.write-timeout} 必须为正（2026-10-10 复核 S3-1 收口：守卫对称化）。
	 *
	 * <p>
	 * {@code SseWriteBudget.on} 对<b>非正</b>预算会<b>静默回落</b>缺省 15s：运维显式配
	 * {@code write-timeout: 0s}（意图"取消写预算"）或负值/笔误，都不会有任何告警，运行行为与配置文件
	 * 不符 —— 而本键的取值论证（{@link Sse#getWriteTimeout()} 与
	 * {@link com.example.configmgr.common.sse.SseWriteBudget} 的类注）全是按"真的有一个 15s 预算"
	 * 展开的，静默变值等于让配置面说谎。
	 *
	 * <p>
	 * 读的是<b>绑定后</b>的值（绑定先于 {@code @PostConstruct}），与任务通道
	 * {@code app.task.sse.write-timeout} 的守卫（{@code com.example.configmgr.config.AppProperties}）
	 * 对称：两条通道都是"非正即启动失败"。这里 <b>fail-fast</b>：
	 * 本项配错没有"性能降级"这种解释空间，它只会让配置面与运行面说两套话。
	 */
	@PostConstruct
	void verifySseWriteTimeout() {
		Duration writeTimeout = this.sse.getWriteTimeout();
		if (writeTimeout == null || writeTimeout.isZero() || writeTimeout.isNegative()) {
			throw new IllegalStateException("app.ai.sse.write-timeout 必须 > 0（当前 = " + writeTimeout
					+ "）：0/负值会被 SseWriteBudget 静默回落为缺省 15s，运行行为与配置文件不符"
					+ "（排障误导）；收紧写侧释放只需配一个更小的正值");
		}
	}

	/**
	 * 启动期校验 {@code app.ai.limit.*} 必须为正（复审 M1；与上面 {@link #verifySseWriteTimeout()} 同一姿态）。
	 *
	 * <p>
	 * 两个键配错都不是"性能降级"，而是<b>语义被静默改写</b>：
	 * <ul>
	 * <li>{@code session-per-minute / ip-per-minute ≤ 0}：Lua 判 {@code count < limit} 恒假 ⇒ <b>每条</b>
	 * {@code /chat} 都 429 —— 配置面看起来是"限流"，运行面是"关掉对话"；</li>
	 * <li>{@code window ≤ 0}：{@code PEXPIRE 0} 会立刻删键 ⇒ 计数永远归零、限流<b>静默失效</b>
	 * （运维以为有闸，实际没有 —— 比报错更危险）。</li>
	 * </ul>
	 * 故启动即失败，并点名具体键与当前值。
	 */
	@PostConstruct
	void verifyRateLimit() {
		Limit limit = this.limit;
		if (limit == null) {
			throw new IllegalStateException("app.ai.limit 段缺失（绑定为 null）：限流无法装配，启动失败");
		}
		if (limit.getSessionPerMinute() <= 0) {
			throw new IllegalStateException("app.ai.limit.session-per-minute 必须 > 0（当前 = "
					+ limit.getSessionPerMinute() + "）：0/负值会让每条 /chat 都 429（等于关掉对话）");
		}
		if (limit.getIpPerMinute() <= 0) {
			throw new IllegalStateException("app.ai.limit.ip-per-minute 必须 > 0（当前 = " + limit.getIpPerMinute()
					+ "）：0/负值会让每个来源的每条 /chat 都 429（等于关掉对话）");
		}
		Duration window = limit.getWindow();
		if (window == null || window.isZero() || window.isNegative()) {
			throw new IllegalStateException("app.ai.limit.window 必须 > 0（当前 = " + window
					+ "）：Lua 的 PEXPIRE 0/负值会立刻删键 ⇒ 计数永远归零、限流静默失效");
		}
	}

	/**
	 * 启动期校验 {@code app.ai.context.*}（上下文统计与压缩专项批①；与上面两个守卫同一姿态）。
	 *
	 * <p>
	 * 这里的每个坏值都不是"性能降级"，而是<b>判定被静默改写</b>：
	 * <ul>
	 * <li>{@code reserve >= limit}（且 limit &gt; 0）⇒ 分母 {@code limit − reserve <= 0} 被钳到 1，
	 * 任何一轮都判"已满"、每轮都触发压缩（前缀每轮重写、缓存全灭）；</li>
	 * <li>{@code l1/l2/l3} 非单调 ⇒ 谱系倒挂（L2 比 L1 先到 = 最轻的一级永远不触发）；</li>
	 * <li>{@code chunk-tokens >= reserve} ⇒ 摘要请求自身就可能越窗（批③ 的失败路径）；</li>
	 * <li>{@code fuse-threshold <= 0} ⇒ 第一次失败即永久熔断（L2/L3 再也不会尝试）。</li>
	 * </ul>
	 * 因此启动即失败并点名键与值。
	 */
	@PostConstruct
	void verifyContext() {
		Context context = this.context;
		if (context == null) {
			throw new IllegalStateException("app.ai.context 段缺失（绑定为 null）：上下文计量无法装配，启动失败");
		}
		if (context.getLimitTokens() < 0) {
			throw new IllegalStateException("app.ai.context.limit-tokens 不得为负（当前 = " + context.getLimitTokens()
					+ "）：0 = 主动声明未知（降级态），负值没有任何解释空间");
		}
		if (context.getReserveTokens() <= 0) {
			throw new IllegalStateException("app.ai.context.reserve-tokens 必须 > 0（当前 = "
					+ context.getReserveTokens() + "）：预留缓冲为 0 会让触发线与上下文上限重合，"
					+ "输出空间被压缩判定吃掉");
		}
		if (context.getLimitTokens() > 0 && context.getReserveTokens() >= context.getLimitTokens()) {
			throw new IllegalStateException("app.ai.context.reserve-tokens（" + context.getReserveTokens()
					+ "）必须小于 limit-tokens（" + context.getLimitTokens()
					+ "）：分母 limit − reserve 会被钳到 1 ⇒ 每轮都判已满、每轮都压缩");
		}
		checkPercent("l1-percent", context.getL1Percent());
		checkPercent("l2-percent", context.getL2Percent());
		checkPercent("l3-percent", context.getL3Percent());
		checkPercent("hysteresis-percent", context.getHysteresisPercent());
		if (!(context.getL1Percent() < context.getL2Percent() && context.getL2Percent() < context.getL3Percent())) {
			throw new IllegalStateException("app.ai.context 的 l1/l2/l3-percent 必须严格递增（当前 = "
					+ context.getL1Percent() + "/" + context.getL2Percent() + "/" + context.getL3Percent()
					+ "）：倒挂会让谱系失效（较轻的一级永远不触发）");
		}
		if (context.getHysteresisPercent() > context.getL1Percent()) {
			throw new IllegalStateException("app.ai.context.hysteresis-percent（" + context.getHysteresisPercent()
					+ "）不得大于 l1-percent（" + context.getL1Percent()
					+ "）：滞回线高于触发线 ⇒ 压缩后立刻满足再次触发条件（抖动链）");
		}
		if (context.getKeepToolPairs() < 0 || context.getKeepRecentTurns() < 0) {
			throw new IllegalStateException("app.ai.context.keep-tool-pairs / keep-recent-turns 不得为负（当前 = "
					+ context.getKeepToolPairs() + " / " + context.getKeepRecentTurns() + "）");
		}
		if (context.getChunkTokens() <= 0) {
			throw new IllegalStateException("app.ai.context.chunk-tokens 必须 > 0（当前 = " + context.getChunkTokens()
					+ "）：摘要分块为 0 会让摘要请求无限递归");
		}
		if (context.getChunkTokens() >= context.getReserveTokens()) {
			// M-1 收口：类注与 NUMSPEC 都承诺"分块 < 预留"，无闸则承诺与实现不一致。
			// 摘要本身也是一次模型请求：分块 ≥ 预留时，摘要请求会挤掉输出空间（越窗 ⇒ 失败 ⇒ 熔断）。
			throw new IllegalStateException("app.ai.context.chunk-tokens（" + context.getChunkTokens()
					+ "）必须小于 reserve-tokens（" + context.getReserveTokens()
					+ "）：摘要请求自身的输入分块不得吃掉预留缓冲（否则摘要请求会越窗）");
		}
		if (context.getSummaryTimeout() == null || context.getSummaryTimeout().isZero()
				|| context.getSummaryTimeout().isNegative()) {
			throw new IllegalStateException("app.ai.context.summary-timeout 必须 > 0（当前 = "
					+ context.getSummaryTimeout() + "）：0/负值等于摘要永不超时（熔断判据失去输入）");
		}
		if (context.getFuseThreshold() <= 0) {
			throw new IllegalStateException("app.ai.context.fuse-threshold 必须 > 0（当前 = " + context.getFuseThreshold()
					+ "）：0 = 第一次失败即熔断，L2/L3 再也不会被尝试");
		}
		if (context.getFuseCooldown() == null || context.getFuseCooldown().isZero()
				|| context.getFuseCooldown().isNegative()) {
			throw new IllegalStateException("app.ai.context.fuse-cooldown 必须 > 0（当前 = " + context.getFuseCooldown()
					+ "）：冷却为 0 = 一直半开，熔断形同虚设");
		}
	}

	private static void checkPercent(String key, int value) {
		if (value < 0 || value > 100) {
			throw new IllegalStateException("app.ai.context." + key + " 必须在 0–100（当前 = " + value + "）");
		}
	}

	@Data
	public static class Memory {
		/** MessageWindowChatMemory 窗口大小（官方实现负责裁剪）。 */		private int maxMessages = 40;
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
		 * 即时执行类前端工具（{@code navigate_to} 等）的回执等待上限（<b>等机器</b>，默认 120s）。
		 *
		 * <p><b>哪些工具读本键</b>（用户裁决 2026-10-20 第 2 条）：只有"下发了就等一个机器回执"的
		 * 即时执行类前端工具。{@code generative_form} <b>不读</b>本键 —— 它等的是人填表，
		 * 上限已退役（无超时），区分点见 {@code ConfirmGate#suspendWaitSeconds}。
		 * 确认门（等人决策）同样<b>没有上限</b>，原 {@code app.ai.hitl.confirm-timeout} 键已退役。
		 *
		 * <p>取值依据（业界依据 §6「前端工具调用超时（AI 工具调用执行预算）」）：MCP 规范只要求
		 * SHOULD 设超时、不规定数值；实现层默认常见 <b>60s</b>，Vercel AI SDK 官方 {@code toolMs}
		 * 区间为 5–60s（跨系统工具取 60s）。本键保留现状 120s，理由见下。
		 *
		 * <p>超过本上限即收口（{@code status=TIMEOUT, executed=false}），但结局语义只说明
		 * "回执没回来"、<b>结局未知</b>（副作用可能已经发生，如文件已落盘）——
		 * 见 {@code ConfirmGate#expireFrontend} 与 {@code ConfirmGateFrontendTimeoutTextTest} 的回归锁。
		 *
		 * <p><b>已知约束（为什么暂维 120s）</b>：前端执行器预算由帧内 {@code timeoutSeconds} 折算
		 * （{@code frontend/src/utils/frontend-tools.ts#deriveFrontendToolBudgetMs}，折算式
		 * `预算 = 服务端上限 − 回灌 POST 上限(60s)`）；本键若降到 60s，该式即为 0、预算落 5s 下限 ——
		 * 该文件自己把 ≤65s 列为病态配置，而实测最慢的一次**成功**回灌发生在 30.6s，5s 会掐掉
		 * 已知会成功的路径。故降到 60s 的前置 = 前端折算面（回灌 POST 单独给更短上限）专项裁决
		 * （数字规格清点 P-1 / 等待治理 R-99②），<b>本次不动</b>。
		 */
		private Duration frontendToolTimeout = Duration.ofSeconds(120);
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
		 * 关系：挂起等待期间工具处于"活跃"，
		 * {@link com.example.configmgr.ai.run.StreamWatchdog} 在工具执行期间<b>不计静默</b>、
		 * 退出后以退出时刻重新计时，因此本值小于等待上限仍正确（即时执行类前端工具 120s &gt; 90s
		 * 同属此理）；确认门与 {@code generative_form} 自 2026-10-20 起<b>无等待上限</b>，
		 * 不再参与本条的"上限关系"。
		 */
		private Duration interEventTimeout = Duration.ofSeconds(90);

		/** 单轮最大尝试次数（首轮 + 重试次数-1）。 */
		private int maxAttempts = 2;

		/**
		 * 单轮总预算（墙钟）：SSE 超时、流式等待上限与韧性看门狗的共同上界。
		 *
		 * <p>
		 * 初值 300s —— <b>待 §13.2 #2 压测裁决</b>（现网最长一轮的真实耗时）。
		 * <b>人机预算分离</b>（用户裁决 2026-10-20 第 3 条）：挂起等待（确认门 / 前端工具 /
		 * 生成式表单）的时长<b>不计入</b>本预算 —— 判据口径是"墙钟 − 挂起等待"，
		 * 见 {@link com.example.configmgr.ai.run.ToolActivityBeacon#gateWaitMs(String)}。
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

		/**
		 * 续跑触发池（T3-6a / A9 的"轻量执行器"）工作线程数。
		 *
		 * <p>承载的动作很短：一次 {@code ResumeService.resume} 调用（读快照/台账/待决 + 重建历史 +
		 * 转投挂起池）—— 它<b>不</b>跑模型、不阻塞等外部输入，故 2 条线程足够（同一时刻通常只有
		 * 一两次外部输入到来）。<b>不得</b>并入挂起池：{@code resume} 内部还要向挂起池提交一次，
		 * 同池提交即嵌套占用（R4/A9）。
		 */
		private int triggerPoolSize = 2;

		/**
		 * 续跑触发池的任务队列容量（有界）。满即 {@code AbortPolicy} 拒绝 ⇒ 调用方回
		 * {@code EXECUTOR_SATURATED}（外部输入本身已落库成功，绝不因此报错）。
		 */
		private int triggerQueueCapacity = 16;
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
		 *
		 * <p>
		 * <b>T3-5 压测定值（2026-10-09）：8</b>；依据
		 * {@code docs/evidence/M2-T3-5-压测证据-DS-V4-Flash.md} §S1.2/S5；判定等式为
		 * <b>不被饿死 ⟺ 慢连接数 &lt; 池容量</b>（{@code M == 池容量} 时全部工作线程被慢连接占满，
		 * 同轮健康订阅者含心跳一并停投）。原初值 4 的边界：同轮出现 ≥ 4 条慢连接即饿死全部
		 * 健康订阅者；8 在该矩阵（M ∈ {1,4}）全绿，且 20×2 + 8 = 48 ≤ 160 仍成立。
		 */
		private int deliveryPoolSize = 8;

		/**
		 * 每订阅者<b>实时</b>投递队列容量的缺省值（<b>单一事实源</b>）。
		 *
		 * <p>
		 * 写出器与注册表都引用本常量（S3-1 修复批：此前 256 同时写在本类与 {@code SseChatEmitter}
		 * 两处，改坏一处不必然变红）。R1 不变量：本值 &lt; 归档窗口
		 * {@code RunStore#EVENT_WINDOW}（只约束实时段，回放初始批豁免）。
		 *
		 * <p>
		 * <b>T3-5 压测定值（2026-10-09）：维持 256</b>；双向实测依据见
		 * {@code docs/evidence/M2-T3-5-压测证据-DS-V4-Flash.md} §S5.2（不误摘：3000 帧回放 backlog
		 * 在队 + 100 条实时心跳不摘除，第 257 条才摘；及时摘：真实 TCP 背压下慢客户端 414 ms 内摘除）。
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

		/**
		 * 单帧 SSE 写出的<b>超时预算</b>（包②：SSE 写侧超时预算；缺省
		 * {@link com.example.configmgr.common.sse.SseWriteBudget#DEFAULT_TIMEOUT_MILLIS}）。
		 *
		 * <p>
		 * 语义与选型见 {@link com.example.configmgr.common.sse.SseWriteBudget}：它约束的是
		 * <b>单帧写出</b>的时长（写出挪到弹性写出池，投递池工作线程最多等本预算），
		 * 而不是整条流的寿命（AI 通道的 emitter <b>不设传输层死线</b>：确认门/表单等
		 * 人工输入没有上限，{@code AiController} 用 {@code new SseEmitter(-1L)} 建流；
		 * 机器预算 {@code app.ai.resilience.total-budget} 仍由 {@code StreamWatchdog} 判）。
		 * 超预算即摘除该订阅者（主动断连，前端 onerror/onclose 带 lastSeq 重挂差量补发），
		 * 不静默丢帧（与 S2-1 溢出摘除同口径）。
		 *
		 * <p>
		 * <b>必须 &gt; 0</b>：非正值会被 {@code SseWriteBudget.on} 静默回落成缺省 15s，
		 * 故改由启动期 {@link AiProperties#verifySseWriteTimeout()} 拦下（与任务通道
		 * {@code app.task.sse.write-timeout} 同一 fail-fast 姿态）。
		 */
		private Duration writeTimeout = Duration.ofMillis(SseWriteBudget.DEFAULT_TIMEOUT_MILLIS);
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

	/**
	 * 租约（fencing）设施参数（B23L1 批②a；设计稿 §2.2）。
	 *
	 * <p>与本专项的 B3（续跑权 fencing）和 L1（目标资源互斥）共用同一组原语：epoch 由
	 * {@code INCR ai:lease:epoch} 全局发放（无 TTL，号不回退），租约键带 TTL 并由持有者续租。
	 *
	 * <p>取值口径：TTL 30s = "持租进程卡顿/死亡后，别的实例多快能接管" 的上界；续租 10s = TTL/3，
	 * 容忍两次连续续租失败（网络抖动）而不丢租约；重试 35s = TTL + 5s 余量，抢租失败的一方按它
	 * 退避重试（批②b 的续跑触发路径消费，本批只建键与设施）。{@code resource-ttl} 60s 是
	 * <b>另一条线</b>（L1 资源互斥，批③a），依据见字段注。
	 */
	@Data
	public static class Lease {

		/** 租约有效期（秒级冗余：续租间隔的三倍）。 */
		private Duration ttl = Duration.ofSeconds(30);

		/** 持有者的续租间隔。 */
		private Duration renewInterval = Duration.ofSeconds(10);

		/** 抢租失败后的退避重试间隔（TTL + 5s 余量）。 */
		private Duration retryDelay = Duration.ofSeconds(35);

		/**
		 * 资源互斥锁（L1：{@code ai:lock:task:<taskId>} / {@code ai:lock:job:<taskId>:<JOBTYPE>}，
		 * 键名见 {@link com.example.configmgr.ai.run.LeaseKeys}）的有效期（B23L1 批③a / 裁决 12）。
		 *
		 * <p>为什么是 60s 而不是跟续跑权一样 30s：这把锁包住的是一次<b>业务动作</b>（导入/发布/导出的
		 * 发起与收尾），最长耗时可到分钟级；但如果按动作时长设 TTL，持锁进程崩溃后别的实例要白等
		 * 整段动作时长才能接上。60s = 两倍续跑权 TTL，<b>不续租，TTL 仅作临界区上界</b>
		 * （B23L1 批③b 复审 I-1：动作真超过上界时宁可让锁按 TTL 放开，也不把"事实永锁"续出来；
		 * 崩溃后的白等窗因此恒不超过一分钟）。
		 *
		 * <p>与 {@link #ttl} 分开是刻意的：续跑权的 TTL 约束"多快能接管一轮"，资源锁的 TTL 约束
		 * "多快能重试同一动作"，两者的取值依据不同，不共用一键。
		 */
		private Duration resourceTtl = Duration.ofSeconds(60);
	}

	/**
	 * 资源互斥开关（B23L1 批③a / 裁决 13）：{@code app.ai.lock.enabled}。
	 *
	 * <p>默认 <b>true</b>。关掉的唯一正当场景是"确定只有一个实例、且要排除锁设施本身的干扰"
	 * （单实例冒烟/压测对照）；<b>不是</b>故障开关 —— 多实例下关掉它，任务级/作业级互斥整体失效
	 * （同一作业被两个实例同时发起）。因此它是显式配置项而不是"抢锁失败就降级"的兜底：
	 * 降级会让"锁坏了"与"锁关了"在日志里长得一样。
	 */
	@Data
	public static class Lock {

		/** 是否启用资源互斥（{@code ai:lock:*}）。false ⇒ 使用方不抢锁、不释放（本层不续租，见 {@link Lease#getResourceTtl()}）。 */
		private boolean enabled = true;
	}

	/**
	 * SSE 单帧上限（T3-10 裁定，{@code app.ai.frame.max-bytes}）。
	 *
	 * <p>判定口径（A19）：<b>整帧 JSON 的 UTF-8 字节数</b>；超限时对 {@code text}/{@code result}
	 * 的值做<b>字段级</b>字符截断 + 独立标记字段 {@code truncated}/{@code truncatedReason}，
	 * <b>严禁</b>帧字节级截断 —— 后者破坏 JSON，坏帧在回放读路径（{@code RunStore#events}）被
	 * 静默忽略，表现为 seq 洞。
	 *
	 * <p><b>口径变更（A21）</b>：本上限<b>优先于</b> T2"给前端看的帧全文不裁剪"的承诺 ——
	 * 前端帧从"永不裁剪"变为"受单帧上限约束的字段级截断 + 标记"
	 * （{@link com.example.configmgr.ai.tool.ToolResultLimiter} 的 javadoc 已同步修订）。
	 *
	 * <p>取值 64KB（A20 定案；"单键理论最大 ≈192MB，M1 不做额外限制"记观察项）。
	 */
	@Data
	public static class Frame {

		/** 单帧 JSON 的 UTF-8 字节上限的<b>单一事实源</b>（缺省 64KB；写出器装配路径与配置项同取本常量）。 */
		public static final int DEFAULT_MAX_BYTES = 64 * 1024;

		/** 单帧 JSON 的 UTF-8 字节上限（64KB）。 */
		private int maxBytes = DEFAULT_MAX_BYTES;
	}

	/**
	 * 输入闸门（安全防护专项批①；{@code 调研-AI输入输出安全防护业界惯例-2026-10-21.md} §5.1 #1
	 * + {@code 调研-AI上下文数据最小化业界惯例-2026-10-21.md} R5）。
	 *
	 * <p>
	 * <b>只约束用户输入的那一条 message</b>（不是整包请求体）：判据 = UTF-16 码元数，与前端
	 * {@code <textarea maxlength>} / JS {@code String#length} <b>同一把尺</b>，故中文不会因为
	 * UTF-8 三字节而被提前判死（8192 个汉字 = 24576 字节，仍须放行）。
	 *
	 * <p>
	 * 超限<b>拒绝</b>而非截断（用户裁决 A1 建议 400）：截断会让模型基于半句话下结论。
	 */
	@Data
	public static class Input {

		/** 单条 message 的字符上限的<b>单一事实源</b>（前端 {@code MESSAGE_MAX_CHARS} 与它同值）。 */
		public static final int DEFAULT_MAX_CHARS = 8192;

		/** 单条 message 的字符上限（8192）。 */
		private int maxChars = DEFAULT_MAX_CHARS;
	}

	/**
	 * 限流（安全防护专项批① §5.1 #3）：{@code /api/ai/chat} 的双维度滑动窗口。
	 *
	 * <p>
	 * 计数落在 Redis（{@code ai:rate:session:*} / {@code ai:rate:ip:*}），用 B23L1 已有的 Redis
	 * 原语（单键 Lua 原子），<b>不要</b>另起进程内计数：多实例下进程内计数每实例各算一份，等于把
	 * 额度放大 N 倍（§6.4）。
	 *
	 * <p>
	 * 取值口径（用户裁决 A5 草值）：会话 20 次/分 —— 本仓单用户、一轮对话动辄数秒，
	 * 20 次/分已远超正常手速，只拦"脚本连发"；IP 60 次/分 —— 兜住"换 sessionId 绕会话额度"，
	 * 也是 E2E（21 次 {@code /chat} 全程）不会被误伤的量级。
	 */
	@Data
	public static class Limit {

		/** 同一 sessionId 每窗口允许的对话请求数。 */
		private int sessionPerMinute = 20;

		/** 同一来源 IP 每窗口允许的对话请求数。 */
		private int ipPerMinute = 60;

		/** 窗口长度（滑动窗口：计数按时间戳落 ZSET，窗口滑过即回收额度）。 */
		private Duration window = Duration.ofMinutes(1);
	}

	/**
	 * 上下文计量与压缩（上下文统计与压缩专项批①；设计稿 §3.7 的 11 个键）。
	 *
	 * <p>
	 * <b>键名纪律（NUMSPEC）</b>：本段每个数字都在 {@code docs/evidence/补档-NUMSPEC-清单总账.md}
	 * 逐行登记了依据 —— "为什么是 128000 / 13000 / 70-80-95 / 60"这些结论不能只活在代码注释里。
	 *
	 * <p>
	 * <b>启动期校验</b>见 {@link AiProperties#verifyContext()}：与 limit/SSE 两段同一姿态 ——
	 * 0/负值不是"性能降级"而是语义被静默改写（例如 {@code reserve >= limit} 会让分母变成 1，
	 * 任何一轮都判"已满"、每轮都压缩）。
	 */
	@Data
	public static class Context {

		/**
		 * 上下文上限（token）的<b>过渡键</b>（默认 128000）。
		 *
		 * <p>取值依据：本仓当前默认模型（deepseek 系）的 128K 窗口；模型配置专项的
		 * {@code ai_model.max_context} 落地后由它覆盖（唯一消费点 = {@code ContextLimitResolver}）。
		 * {@code 0} = 主动声明"未知"（降级态：界面只显示已用、不显示百分比；压缩不做比例判定）。
		 */
		private int limitTokens = 128000;

		/**
		 * 预留缓冲（默认 13000 token）。
		 *
		 * <p>依据 = 调研 §① Claude Code 的 13K 预留缓冲；覆盖 {@code max-tokens 4096} 输出空间
		 * + 一轮工具回环余量。比例阈值对 {@code limit − reserve} 取比 ⇒ 触发线不会把输出空间吃掉
		 * （128K 下 L3@95% 仍剩 5.75K &gt; 4096）。
		 */
		private int reserveTokens = 13000;

		/** L1（工具结果清除）触发线（对 {@code limit − reserve} 的百分比）。 */
		private int l1Percent = 70;

		/** L2（滑窗 + 递归摘要）触发线（批③ 已消费）。 */
		private int l2Percent = 80;

		/** L3（硬截断兜底）触发线（批③ 已消费）。 */
		private int l3Percent = 95;

		/**
		 * 滞回线（默认 60）：压缩完成后 {@code used / limit < 60%} 才允许再次触发。
		 *
		 * <p>依据 = 调研 Q4"压缩到 60% 以下才允许再次触发"；防的是"压一次不够、立刻再压"的抖动链
		 * （每次压缩都要改写前缀 ⇒ 缓存整段重建）。
		 */
		private int hysteresisPercent = 60;

		/** 最近多少对工具结果不清理（L1 的 pin；调研"保留最近 3–5 对"取上限 = 保守）。 */
		private int keepToolPairs = 5;

		/** 最近多少轮逐字保留（L2 的 pin；调研"保留最近 6–8 轮"取下限 = 省 token 优先；批③ 已消费）。 */
		private int keepRecentTurns = 6;

		/** 摘要分块大小（token；批③ 已消费）。必须小于 {@link #reserveTokens}，保证摘要请求自身不越窗。 */
		private int chunkTokens = 8000;

		/** 一次摘要请求的硬上限（批③ 已消费；失败即熔断路径，不回退到截断）。 */
		private Duration summaryTimeout = Duration.ofSeconds(60);

		/** 连续失败多少次熔断（批③ 已消费；调研 §①"连续失败 3 次熔断"）。 */
		private int fuseThreshold = 3;

		/** 熔断冷却窗（批③ 已消费；到点半开重试一次，失败重新计数 —— 不升指数，避免长尾锁死）。 */
		private Duration fuseCooldown = Duration.ofMinutes(10);

		/**
		 * 递归摘要用的模型名（默认空 = 主模型；D3 裁决）。
		 *
		 * <p>留空即主模型是刻意的：本仓当前只有一套 provider 配置，先主模型 + 超时 + 熔断；
		 * 模型配置专项落地后可只改本键切到便宜模型（接缝已留，批③ 已消费）。
		 */
		private String summaryModel = "";

	}

}
