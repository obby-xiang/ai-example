package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.form.GenerativeFormRules;
import com.example.configmgr.ai.observe.AiTrace;
import com.example.configmgr.ai.observe.SettlementObservationStore;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.CancelledRunFinalizer;
import com.example.configmgr.ai.run.LeaseService;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.ReplyLanguage;
import com.example.configmgr.ai.run.ResumeService;
import com.example.configmgr.ai.run.RunParkedException;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.tenant.RunIdentityScope;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tool.FrontendToolGuard;
import com.example.configmgr.ai.tool.SheetAudit;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.common.AiStateUnavailableException;
import com.example.configmgr.common.ErrorBodyFactory;
import com.example.configmgr.common.ErrorCodes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 确认门状态机（S4.2 §2 {@code gate/ConfirmGate}）：放行 / 拒绝（原因回填）。
 *
 * <h2>结局</h2>
 * <ul>
 * <li><b>放行</b>：{@code POST /api/ai/confirm {approved:true}} → Redis 里
 * {@code status=APPROVED, executed=false} → 唤醒循环内的等待 → 由循环执行工具并记台账；</li>
 * <li><b>拒绝</b>：{@code status=REJECTED}，结果文本回填"用户拒绝 + 原因"，<b>不执行</b>，
 * 官方循环照常继续（模型如实解释）；</li>
 * <li><b>取消</b>：整轮被取消（{@code ai:cancel:<runId>}）→ {@code status=CANCELLED}，不执行。</li>
 * </ul>
 *
 * <h2>等待没有时限（用户裁决 2026-10-20 第 1/2 条）</h2>
 * 确定性操作（确认门）与等**人**的前端工具（{@code generative_form} 与表格写工具，见
 * {@link #HUMAN_WAIT_FRONTEND_TOOLS}）的等待<b>不再有超时</b>：不再有 240s/120s 的死线，
 * 一直等到用户批准 / 拒绝 / 取消（前端另可 {@code cancelled=true} 放弃）。只有<b>其他前端工具</b>
 * （{@code navigate_to} 等即时执行类）保留 {@code app.ai.hitl.frontend-tool-timeout}（默认 120s）
 * 的后端回执兜底 —— 那等待的是机器回执，机器"不回"是故障，不能无限悬置。
 * 区分点只有一个：{@link #suspendWaitSeconds(PendingToolCall)}（返回 null = 不设上限）。
 * 等待期的时间不计入 {@code app.ai.resilience.total-budget}（人机预算分离，见
 * {@link ToolActivityBeacon#gateWaitMs}）。
 *
 * <h2>历史兼容</h2>
 * 已存在的 {@code TIMEOUT} 记录（旧轮的 {@code CONFIRM_TIMEOUT}）仍然可读：结局/码的推导照旧，
 * 前端"已超时"展示态不受影响；<b>新逻辑不再产生</b>确认门的超时帧
 * （{@code confirm_decision decision=timeout} 不再发出）。
 *
 * <h2>状态在哪、唤醒在哪（硬规范①）</h2>
 * 决策状态一律先写 Redis（{@link RunStore}），进程内的 {@link Latch} 只承担"唤醒"职责 ——
 * 因此跨进程决策（旧实例已死、新实例续跑）也能被可靠发现：等待循环在心跳节拍上重新读 Redis，
 * 而不是只等本地闸门。SP-02 §5.2 原文即"外部输入只写 Redis，再唤醒同进程的门"。
 * <b>S5c-6 补充</b>：三件事的次序固定为 <b>落库 → 发回执帧 → 唤醒</b>（且同一把锁内），
 * 否则被唤醒的等待方会先于回执帧产出"后果帧"（{@code tool_result} 等）。
 *
 * <h2>挂起期心跳保活（仅 park 关闭时）</h2>
 * 等待循环每 {@value #HEARTBEAT_SECONDS} 秒发一帧 {@code heartbeat}（带挂起种类与已等待秒数）：
 * ① 保活 SSE；② 续期轮键与 session 索引；③ 实测证据里"挂起期确实还有帧"由此可证。
 * 心跳<b>不再携带</b>等待上限（确认门/表单卡没有到期时刻，前端据此不武装倒计时）。
 * <b>park 开启（生产）时没有等待线程</b>：请求帧公布 + 收流后本轮执行即结束，
 * 不心跳、不续租、不进看门狗；恢复由决策落库后的续跑负责。
 *
 * <h2>刷新恢复口径（L0，2026-10-21 用户直裁）</h2>
 * {@code saveSuspended} 把整批条目一起外置 ⇒「条目存在」不等于「卡已送达」。两条口径由此而定：
 * ①<b>发帧前先置位</b>（{@link RunStore#markRequestEmitted} 落 {@code req=true} + 时刻），
 * 前端刷新只为 {@code req=true} 的 {@code CONFIRM}/{@code FRONTEND} 条目建卡；
 * ②<b>已决则不补发</b>（{@link #reserveRequestSlot}）：条目在到达 {@code await} 前已被决策时，跳过请求帧
 * 与挂起断连、直接消费既有决策 —— 否则前端会把已收敛的卡瞬时复活再被收敛。实时路径（发帧与 handler）不受影响。
 * <b>C1</b>：park 开启时整批请求帧由 {@link #publishRequestsForParkedBatch} 一次公布
 * （逐条发会让 park 后的第二张卡永远没有请求帧，也就永远无法被决策驱动）。
 *
 * <h2>执行的幂等（SP-02 E2 语义）</h2>
 * 本类只负责"决策落库"，<b>不在</b>决策写入时执行工具 —— 执行与"已执行"标记的关系见
 * {@link RunStore#claimExecution}：认领发生在执行<b>之前</b>且原子，从而消除
 * "执行完成才写标记 ⇒ 两个实例都执行一次"的重复执行窗口。
 *
 * <h2>前端工具回灌的两道附加闸门（DC-15）</h2>
 * <ul>
 * <li><b>结果复核</b>：认领了复核器的前端工具（{@link FrontendToolGuard#inspectResult}）在换状态
 * <b>之前</b>复核回灌内容；不合法即 {@link Outcome#REJECTED}（HTTP 400），
 * <b>状态不变</b>（仍 {@code PENDING}，可修正后重试）、不发帧、不唤醒 —— 一次不合法的提交
 * 不该把挂起消费掉，否则用户再没机会改对；</li>
 * <li><b>取消终态</b>：前端带 {@code cancelled=true} 回灌（用户关闭/放弃表单）⇒
 * {@link PendingToolCall#FRONTEND_CANCELLED}（明确终态，不是"未决悬置"），结局帧
 * {@code frontend_tool_result(ok=false)} 与"前端超时"同形，模型据此收尾。</li>
 * </ul>
 * 两道闸门都<b>按工具名自认领</b>（{@code supports}），本类对具体工具名零知识。
 *
 * <h2>续跑权 fencing（B23L1 批②b）</h2>
 * 多实例下"谁在等这张卡 / 谁能推进这一轮"必须有<b>跨进程</b>的答案，进程内的 {@code latches}
 * 与 {@code submitLock} 都只是实例内事实。两条接线的落点：
 * <ul>
 * <li><b>等待方持租</b>（{@link #await}）：进入挂起等待即抢 {@code ai:lease:run:<runId>}，
 * 心跳按 {@code lease.renew-interval} 续租；决策到达时<b>交棒不释放</b>（原循环接手驱动），
 * 取消/中断/前端超时等"本轮不驱动"的出口才释放（epoch scoped，错 epoch 不删别人的租约）；</li>
 * <li><b>触发方抢租</b>（{@link #maybeTriggerResume}）：死卡兜底派发前先抢同一把租约，
 * 抢不到就<b>不派发</b>（{@code LEASE_HELD_ELSEWHERE}）+ 排一次延迟重试 —— 决策本身早已落库，
 * "续跑没派出去"不是请求失败；</li>
 * <li><b>决策 guard</b>（{@code ai:decided:} 的 {@code HSETNX}）：两个实例并发提交同一张卡时
 * 恰一个 {@code ACCEPTED}、恰一个 {@code DUPLICATE}、回执帧恰一次。</li>
 * </ul>
 * 无租约设施（6/8 参兼容构造、无 Redis 的降级装配）时全部退回批②b 之前的口径：
 * 进程内互斥 + 原死卡兜底。
 */
@Slf4j
@Component
public class ConfirmGate {

	/** 心跳与"跨进程决策发现"的节拍（秒）。 */
	public static final int HEARTBEAT_SECONDS = 2;

	/**
	 * 等**人**的前端工具名单（等待无上限；设计稿 §3.4 / §7-裁 1 选项 A）。
	 *
	 * <ul>
	 * <li>{@code generative_form}：等用户填表（DC-15 既有先例）；</li>
	 * <li>{@code replace_values} / {@code update_rows} / {@code normalize_column}：等用户在预览卡上
	 * 点“应用/取消”（AI 表格操作专项）；</li>
	 * <li>{@code undo_last_sheet_edit}：撤销同样走前端、可能等待用户确认上下文（无时限）。</li>
	 * </ul>
	 *
	 * 其余前端工具（{@code navigate_to} 等即时执行类）仍用
	 * {@code app.ai.hitl.frontend-tool-timeout} 做机器回执兜底。
	 */
	static final Set<String> HUMAN_WAIT_FRONTEND_TOOLS = Set.of(
			GenerativeFormRules.TOOL_NAME,
			"replace_values", "update_rows", "normalize_column", "undo_last_sheet_edit",
			// AI 可视可操作专项：上传交接等的是**用户手势**（浏览器只在 transient activation 里
			// 给文件句柄），既不能判超时，也不能让 120s 机器回执兜底把它掐掉。
			"ui_request_upload");

	private final RunStore store;

	private final RunRegistry registry;

	private final AiProperties properties;

	private final CancellationRegistry cancellations;

	private final ToolActivityBeacon beacon;

	/**
	 * 前端工具的入参/回灌复核器（DC-15 安全闸；见 {@link FrontendToolGuard}）。
	 * 没有声明复核器的工具（既有 6 个前端工具）行为一字不变 —— {@link #inspectResult} 直接放行。
	 */
	private final List<FrontendToolGuard> frontendGuards;

	/**
	 * 续跑服务的<b>惰性</b>引用（T3-6a 第 4 方案解环①）。
	 *
	 * <p>依赖环 {@code ConfirmGate → ResumeService → SpToolCallingManager → ConfirmGate} 在 R4 诊断里
	 * 是硬事实，Spring 默认拒绝构造器环。故此处只持 {@link ObjectProvider}（与
	 * {@code AiController} 同一先例），在<b>触发时</b>才 {@code getIfAvailable()} ——
	 * 无 key 的降级装配下返回 null，本类静默跳过（AI 不可用时本就不存在续跑）。
	 */
	private final ObjectProvider<ResumeService> resumeServiceProvider;

	/**
	 * 续跑触发的<b>轻量执行器</b>（T3-6a / A9）。
	 *
	 * <p>为什么不能用 {@code suspendExecutor}：{@code ResumeService#resume} 内部
	 * <b>还会再向 suspendExecutor 提交一次</b>（真正的驱动任务），在 suspendExecutor 的线程里
	 * 再提交一次 = 嵌套占用，挂起池容量被同一次续跑吃掉两条线程，饱和时自锁。
	 * 故触发动作由本执行器（短任务：一次 Redis 读 + 重建 + 转投）承载，HTTP 线程立即返回。
	 */
	private final Executor resumeTriggerExecutor;

	/**
	 * toolCallId → 进程内唤醒闸门（不承载状态）。
	 *
	 * <p><b>F8</b>：闸门可以由 {@link #arm} 在 {@code suspended} 帧<b>之前</b>预登记 —— 帧一到前端
	 * 用户就能点击，那条路径上的 {@code signal} 必须能找到闸门；{@link #await} 复用同一把
	 * （{@code computeIfAbsent}），不再无条件新建（新建会丢掉预登记之后、等待之前发生的唤醒）。
	 */
	private final Map<String, Latch> latches = new ConcurrentHashMap<>();

	/** {@link #arm} 预登记、尚未被 {@link #await} 消费的键（{@link #disarm} 只回收这些，不误删他人的闸门）。 */
	private final Set<String> armedKeys = ConcurrentHashMap.newKeySet();

	/**
	 * runId → 本批挂起的起点（{@link #arm} 置位、{@link #disarm} 清除）。
	 *
	 * <p>用途只有一个：{@code approval_decided.suspendMs}（挂起开始 → 决策）。为什么不落 Redis：
	 * 它是<b>本实例视角</b>的时钟（谁驱动这一轮谁记），跨实例决策读不到时写 {@code -1} 而不是编一个数。
	 */
	private final Map<String, Long> suspensionStartedAtMs = new ConcurrentHashMap<>();

	/**
	 * 提交侧的互斥（S5c-6）：<b>状态落库 → 发回执帧 → 唤醒</b>是一次不可分割的动作。
	 *
	 * <p>
	 * 为什么必须有：唤醒一旦发出，等待方立刻会在"后果帧"（{@code tool_result} 等）里
	 * 表达决策的结果 —— 若回执帧还没写出去，客户端就会先看到工具结局、后看到人的决策，
	 * 极端调度下回执帧甚至落到 {@code done} 之后（违反"终帧之后不得再有帧"）。
	 * 等待侧的 Redis 轮询也读同一把锁：否则"决策已落库、回执帧还没发"这一瞬间，
	 * 跨进程轮询路径会把同一个窗口重新打开。
	 */
	private final Object submitLock = new Object();

	/**
	 * 派发失败的<b>有界</b>重试计数（park 批，按 runId，<b>按外部触发周期</b>）。
	 *
	 * <p>为什么需要：park 之后"推进这一轮"的责任在派发方。续跑池饱和（{@code POOL_SATURATED}）、
	 * 会话门被另一轮占着（{@code SESSION_BUSY}）或取消状态此刻不可确认（{@code CANCEL_STATE_UNKNOWN}）
	 * 时，如果只记一条日志，用户已经批准的卡会停在"已决 + 无人执行"（W-21 就是后者：已结算的前端
	 * 工具没有物化路径）。这里按 {@code app.ai.park.max-dispatch-retries} 有界重派发。
	 *
	 * <h2>生命周期（唯一允许清零的地方是外部触发入口）</h2>
	 * <ul>
	 * <li><b>清零</b>：只有<b>外部</b>触发才开新周期并清零 —— {@link #triggerResume} 的
	 *     {@code allowRetry=true} 调用方（决策/放弃/回灌、前端回执到期裁决、重挂自愈对账）。
	 *     这就是既有"下一次明确交互可重新触发"的语义。<b>启动扫描不是</b>这类调用方：
	 *     {@code StartupResumeRunner} 直接调 {@code ResumeService.resume}，不经本入口，也不重置预算；</li>
	 * <li><b>耗尽</b>：{@link #retryDispatch} 到 {@code max+1} 后把计数<b>钉死</b>（不 remove），
	 *     于是同一个周期内的其它链、以及已排期任务的后续拍都看见同一份"已耗尽"，既不新建预算也不
	 *     再排期。旧实现耗尽即 remove，晚醒的链会各自拿到一整套新预算（一个周期内 15 × 链数）；</li>
	 * <li><b>不清零</b>：调度器内部的续派（{@code allowRetry=false}）与任何 {@code runResume} 结局
	 *     都不清 —— 否则"有界"只成立到"下一条链醒过来"。代价是计数会留到下一次外部触发（每个 runId
	 *     一个小 AtomicInteger），这是有意的：宁可多留一行状态，也不要在故障期悄悄续命。</li>
	 * </ul>
	 */
	private final Map<String, java.util.concurrent.atomic.AtomicInteger> dispatchRetries =
			new ConcurrentHashMap<>();

	/**
	 * 续跑权租约设施（B23L1 批②b）。<b>可为 null</b>：6/8 参兼容构造器（单测、局部装配）
	 * 不注入它 —— 此时走"无租约降级"：派发不带租约、由 {@code ResumeService} 的进程内互斥兜底
	 * （单实例仍然正确；跨实例由批②a 的 {@code RunStore} fence 写与认领兜底）。
	 *
	 * <p>与 {@code RunStore} 注入的是<b>同一个 bean</b>：fence 写（{@code RunStore#save}）读
	 * {@code heldRunEpoch} 与本类抢/续/放操作的是同一份进程内视图。
	 */
	private final LeaseService leaseService;

	/** 抢租失败后的一次性延迟重试调度器（见 {@link RetryScheduler}）。 */
	private final RetryScheduler retryScheduler;

	/**
	 * 抢租失败后的<b>一次性</b>延迟重试调度缝（默认 = 守护线程单线程调度器）。
	 *
	 * <p>为什么是接口：用例要断言"35s 后重试一次、holder 还活着就不动作、且不再排下一次"，
	 * 真线程只能 sleep 真实时间（慢且不稳）。生产路径就是 {@code schedule(task, 35s)}。
	 */
	interface RetryScheduler {

		AutoCloseable schedule(Runnable task, Duration delay);

	}

	/** 默认重试调度器（守护线程，不阻止 JVM 退出；单线程足够：重试任务是"一次 Redis 读 + 转投"）。 */
	static final RetryScheduler DEFAULT_RETRY_SCHEDULER = defaultRetryScheduler();

	private static RetryScheduler defaultRetryScheduler() {
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "ai-resume-lease-retry");
			thread.setDaemon(true);
			return thread;
		});
		return (task, delay) -> {
			long millis = delay == null ? 0L : Math.max(1L, delay.toMillis());
			var future = executor.schedule(task, millis, TimeUnit.MILLISECONDS);
			return () -> future.cancel(false);
		};
	}

	/** 兼容构造（6 参：既有单测）：无续跑通路、无租约设施。 */
	public ConfirmGate(RunStore store, RunRegistry registry, AiProperties properties,
			CancellationRegistry cancellations, ToolActivityBeacon beacon, List<FrontendToolGuard> frontendGuards) {
		this(store, registry, properties, cancellations, beacon, frontendGuards, null, null, null,
				DEFAULT_RETRY_SCHEDULER);
	}

	/**
	 * 兼容构造（8 参，T3-6a）：有续跑通路、<b>无</b>租约设施 ⇒ 无租约降级
	 * （派发走 {@code resume(runId)}，由 {@code ResumeService} 自抢或进程内互斥）。
	 */
	public ConfirmGate(RunStore store, RunRegistry registry, AiProperties properties,
			CancellationRegistry cancellations, ToolActivityBeacon beacon, List<FrontendToolGuard> frontendGuards,
			ObjectProvider<ResumeService> resumeServiceProvider,
			@Qualifier("aiResumeTrigger") Executor resumeTriggerExecutor) {
		this(store, registry, properties, cancellations, beacon, frontendGuards, resumeServiceProvider,
				resumeTriggerExecutor, null, DEFAULT_RETRY_SCHEDULER);
	}

	/**
	 * Spring 装配路径（T3-6a + B23L1 批②b）：多出 {@code ObjectProvider<ResumeService>}（惰性，解环）、
	 * 续跑触发的轻量执行器（{@code aiResumeTrigger}，见 {@link #maybeTriggerResume}）与
	 * 续跑权租约设施（{@link LeaseService}）。
	 */
	@Autowired
	public ConfirmGate(RunStore store, RunRegistry registry, AiProperties properties,
			CancellationRegistry cancellations, ToolActivityBeacon beacon, List<FrontendToolGuard> frontendGuards,
			ObjectProvider<ResumeService> resumeServiceProvider,
			@Qualifier("aiResumeTrigger") Executor resumeTriggerExecutor, LeaseService leaseService) {
		this(store, registry, properties, cancellations, beacon, frontendGuards, resumeServiceProvider,
				resumeTriggerExecutor, leaseService, DEFAULT_RETRY_SCHEDULER);
	}

	/** 包内构造（测试注入重试调度器）：全部字段的唯一赋值点。 */
	ConfirmGate(RunStore store, RunRegistry registry, AiProperties properties, CancellationRegistry cancellations,
			ToolActivityBeacon beacon, List<FrontendToolGuard> frontendGuards,
			ObjectProvider<ResumeService> resumeServiceProvider, Executor resumeTriggerExecutor,
			LeaseService leaseService, RetryScheduler retryScheduler) {
		this.store = store;
		this.registry = registry;
		this.properties = properties;
		this.cancellations = cancellations;
		this.beacon = beacon;
		this.frontendGuards = frontendGuards == null ? List.of() : List.copyOf(frontendGuards);
		this.resumeServiceProvider = resumeServiceProvider;
		this.resumeTriggerExecutor = resumeTriggerExecutor;
		this.leaseService = leaseService;
		this.retryScheduler = retryScheduler == null ? DEFAULT_RETRY_SCHEDULER : retryScheduler;
	}

	/**
	 * 表格采样/改动审计面（AI 表格工具治理批）。null = 未注入（轻装配面 / 单元测试）——
	 * 此时只是少了计数与台账，{@code tool_call_end} 照常发射（字段来自条目自身的 {@code sheetMeta}）。
	 *
	 * <p>为什么确认门也要它：park 之后工具的结算发生在<b>决策/放弃的写入方</b>（本类），
	 * 不再经过 {@code SpToolCallingManager} 的内联批循环 —— 观测面必须跟着所有权走
	 * （见 {@link #settleParkedToolCall}）。
	 */
	private SheetAudit sheetAudit;

	/** 可选注入（{@code SheetAudit} 是观测面，不是判定面；缺失不影响任何决策语义）。 */
	@Autowired(required = false)
	public void setSheetAudit(SheetAudit audit) {
		this.sheetAudit = audit;
	}

	/**
	 * 结算观测位（跨实例恰好一次的仲裁面）。null = 未注入（轻装配/单测）：每个发点各自发一行，
	 * 与引入仲裁之前同形（单实例恒等价）。
	 */
	private SettlementObservationStore settlementObservations;

	/** 可选注入（观测面，不是判定面；缺失不影响任何决策语义）。 */
	@Autowired(required = false)
	public void setSettlementObservationStore(SettlementObservationStore observations) {
		this.settlementObservations = observations;
	}

	/**
	 * 取消收尾的交棒执行体（park 退出时"取消事实已成立、但本线程已无写权限"的接管者）。
	 *
	 * <p>{@code null} = 未注入（轻装配/单测）：行为与引入之前逐字同形 —— 只把本卡落 CANCELLED，
	 * 整轮快照的终态仍由驱动线程随后的 {@code cancelTerminal} 负责。生产装配必有它
	 * （{@link CancelledRunFinalizer} 是 {@code @Component}），于是"写权限不可确认"不再等于
	 * "这一轮没人管"（主 CI run 40211303032 · W-30）。
	 */
	private CancelledRunFinalizer cancelFinalizer;

	/** 可选注入（取消收尾的接管通路；缺失时退化为"本线程负责收尾"的旧口径）。 */
	@Autowired(required = false)
	public void setCancelFinalizer(CancelledRunFinalizer finalizer) {
		this.cancelFinalizer = finalizer;
	}

	/**
	 * 实际使用的租约设施：构造参数优先，其次 {@code RunStore} 持有的那个。
	 *
	 * <p>为什么要兜底：Spring 路径两者是同一个 bean；但测试里 {@code RunStore} 替身可能装配了
	 * 真实设施而 gate 用 8 参构造器构造（或反之）。以"同一份外置状态的持有者"为准可避免
	 * "gate 抢到的 epoch 与 fence 写看到的 epoch 不是一回事"这种假绿。
	 */
	private LeaseService leases() {
		return this.leaseService != null ? this.leaseService : this.store.leaseService();
	}


	/**
	 * 轮快照的 sessionId（索引续期用；快照缺失返回 null，调用方据此跳过续期）。
	 */
	private String snapshotSessionId(String runId) {
		try {
			RunSnapshot snapshot = this.store.get(runId);
			return snapshot == null ? null : snapshot.getSessionId();
		}
		catch (Exception ex) {
			log.debug("读取轮快照 sessionId 失败 runId={}：{}", runId, ex.getMessage());
			return null;
		}
	}

	/**
	 * 前端工具回执等待上限（秒）：{@code app.ai.hitl.frontend-tool-timeout}（等机器，默认 120s）。
	 *
	 * <p>只供<b>即时执行类</b>前端工具（{@code navigate_to} 等）的后端回执兜底使用；
	 * {@code generative_form} 与确认门<b>不读本键</b>（等待没有时限，见
	 * {@link #suspendWaitSeconds(PendingToolCall)}）。这等待的是机器回执（秒级）而不是人的决策
	 * （分钟级），台账里"回执没回来、结局未知"与"工具未执行"是两件事。
	 */
	public int frontendToolTimeoutSeconds() {
		Duration timeout = this.properties.getHitl().getFrontendToolTimeout();
		return (int) Math.max(1, timeout.toSeconds());
	}

	/**
	 * 该条目挂起等待的上限（秒）；<b>null = 不设上限</b>（用户裁决 2026-10-20）。
	 *
	 * <ul>
	 * <li>确认门（{@link PendingToolCall#KIND_CONFIRM}）：等的是人的决策 ⇒ 无上限；</li>
	 * <li>{@code generative_form} 与三个表格写工具（{@code replace_values}/{@code update_rows}/
	 * {@code normalize_column}）及 {@code undo_last_sheet_edit}：等的是人 ⇒ 无上限
	 * （与确认门同一路径，按<b>工具名</b>集合 {@link #HUMAN_WAIT_FRONTEND_TOOLS} 区分）；</li>
	 * <li>其余前端工具：{@link #frontendToolTimeoutSeconds()}（默认 120s，等机器回执的兜底）。</li>
	 * </ul>
	 *
	 * <p>等待上限只影响"什么时候系统收摊"，不影响"人还能不能提交"：无上限时唯一出口是人决策、
	 * 取消或前端主动放弃（{@code cancelled=true}）。
	 */
	public Integer suspendWaitSeconds(PendingToolCall pending) {
		if (pending == null || !PendingToolCall.KIND_FRONTEND.equals(pending.getKind())) {
			return null;
		}
		if (HUMAN_WAIT_FRONTEND_TOOLS.contains(pending.getName())) {
			return null;
		}
		return frontendToolTimeoutSeconds();
	}

	// ── 等待侧（在官方循环内阻塞） ──────────────────────────────────────────

	/**
	 * 预登记闸门（F8）：在 {@code saveSuspended} / {@code suspended} 帧<b>之前</b>，把本批将进入等待的
	 * 条目（{@code CONFIRM} 与 {@code FRONTEND}，两者都经 {@link #await} 登记闸门）先登记好。
	 *
	 * <p><b>为什么必须在帧之前</b>：挂起帧一到前端，确认卡就能被点击 —— 用户在"帧已到、等待还没进入"
	 * 的窗口里提交决策时，若闸门尚未登记，{@code signal} 返回 false，死卡兜底会判定"本进程无人等待"
	 * 并派发 {@code ResumeService.resume}，与原循环并发驱动同一轮（重复帧/settle/历史分叉）。
	 *
	 * <p><b>幂等</b>：同 key 多次 arm 复用同一把闸门（{@code computeIfAbsent}）—— 重复登记不能把
	 * 已经发生的唤醒丢掉。未被 {@link #await} 消费的键由 {@link #disarm} 回收。
	 */
	public void arm(String runId, List<String> toolCallIds) {
		if (runId == null || toolCallIds == null) {
			return;
		}
		// 挂起起点（AI 日志最小补齐批）：一批多卡时"挂起开始 → 每张卡决策"的间隔各不相同，
		// 而卡送达 → 决策（waitedMs）只覆盖单张卡。suspendMs 用它当减数。
		this.suspensionStartedAtMs.putIfAbsent(runId, System.currentTimeMillis());
		for (String toolCallId : toolCallIds) {
			if (toolCallId == null) {
				continue;
			}
			String key = key(runId, toolCallId);
			this.latches.computeIfAbsent(key, ignored -> new Latch());
			this.armedKeys.add(key);
		}
	}

	/**
	 * 回收 {@link #arm} 预登记但<b>未进入等待</b>的闸门（异常/提前返回路径），防止
	 * {@link #hasActiveLatch} 永远为真而不触发死卡兜底。
	 *
	 * <p><b>幂等</b>：键不存在、已被 {@link #await} 的 {@code finally} 消费、或从未 arm 过，都不报错也不
	 * 误删 —— 只回收仍带"预登记"标记的键。
	 */
	public void disarm(String runId, List<String> toolCallIds) {
		if (runId == null || toolCallIds == null) {
			return;
		}
		this.suspensionStartedAtMs.remove(runId);
		for (String toolCallId : toolCallIds) {
			if (toolCallId == null) {
				continue;
			}
			String key = key(runId, toolCallId);
			if (this.armedKeys.contains(key)) {
				// F8 收口（M1）：先摘闸门、后摘预登记标记。两步之间到达的 signal 会扑空（woke=false），
				// 交由死卡兜底续跑恢复；若反过来先摘标记，signal 会命中已无人接力的闸门 ⇒
				// woke=true 但无驱动，挂起永久卡死。残余窗口方向刻意选可恢复的一侧。
				Latch latch = this.latches.get(key);
				if (latch != null) {
					this.latches.remove(key, latch);
				}
				this.armedKeys.remove(key);
			}
		}
	}

	/**
	 * 本批请求帧是否会在执行循环之前被<b>整批</b>公布（= park 开启）。
	 *
	 * <p>帧序契约要求每张卡先 {@code tool_start} 再 {@code frontend_tool_request}/{@code confirm_request}。
	 * 请求帧被前移时，{@code tool_start} 必须同样前移（见 {@code SpToolCallingManager}）；
	 * 判定统一以本方法为准 —— 单一事实源就是 {@link #publishRequestsForParkedBatch} 的同一条件，
	 * 避免"管理器自己的 properties 实例"与闸门的实例不一致时帧序错位。
	 */
	public boolean publishesRequestsAheadOfLoop() {
		return this.properties.getPark().isEnabled();
	}

	/**
	 * <b>C1：park 前把本批每张卡的请求帧都公布出去</b>。
	 *
	 * <p>缺陷形态：帧原本是在各卡的 {@code await} 里<b>逐条</b>发的，而 park 让第一张卡的
	 * {@code await} 直接结束本次执行 —— 第二张卡既没发过请求帧、也没置 {@code req}，
	 * 前端永远看不到它，即便它被决策也无从驱动（"批准了却卡死"）。
	 *
	 * <p>因此 {@code SpToolCallingManager} 在 {@code suspended} 帧之后、执行循环之前调用本方法：
	 * 对每个仍 {@code PENDING} 的 {@code CONFIRM}/{@code FRONTEND} 条目<b>先置 {@code req} 再发帧</b>
	 * （与 {@link #reserveRequestSlot} 同序、同一把提交锁），并在内存副本上置 {@code req}
	 * —— 随后 {@code await} 看到 {@code req} 已置就不再重复发帧（见 {@code await} 的 {@code prePublished}）。
	 *
	 * <p>已决条目<b>不补发</b>：{@code markRequestEmitted} 之前的重判与 {@code await} 的 L0 口径一致，
	 * 避免把已收敛的卡瞬时复活。幂等：{@code req} 已置的条目直接跳过。
	 */
	public void publishRequestsForParkedBatch(String runId, List<PendingToolCall> pendings) {
		if (runId == null || pendings == null || pendings.isEmpty()
				|| !this.properties.getPark().isEnabled()) {
			return;
		}
		SseChatEmitter out = this.registry.of(runId);
		for (PendingToolCall pending : pendings) {
			boolean confirm = PendingToolCall.KIND_CONFIRM.equals(pending.getKind());
			if (!confirm && !PendingToolCall.KIND_FRONTEND.equals(pending.getKind())) {
				continue;
			}
			if (Boolean.TRUE.equals(pending.getReq())) {
				continue;
			}
			long atMs = System.currentTimeMillis();
			PendingToolCall marked;
			synchronized (this.submitLock) {
				PendingToolCall fresh = this.store.pendingLenient(runId, pending.getToolCallId());
				if (fresh == null || !PendingToolCall.PENDING.equals(fresh.getStatus())) {
					// 已决/已消失：交给 await 的 L0 分支直接消费既有决策，这里不补发
					continue;
				}
				marked = this.store.markRequestEmitted(runId, pending.getToolCallId(), atMs);
			}
			pending.setReq(Boolean.TRUE);
			pending.setReqAtMs(marked == null || marked.getReqAtMs() == 0L ? atMs : marked.getReqAtMs());
			if (confirm) {
				out.confirmRequest(pending);
			}
			else {
				out.frontendToolRequest(pending, suspendWaitSeconds(pending));
			}
		}
	}

	/**
	 * 确认门：阻塞等人工决策，返回 Redis 里的<b>最终条目</b>（调用方据此决定执行与否）。
	 *
	 * <p><b>无上限</b>：一直等到批准 / 拒绝 / 取消（用户裁决 2026-10-20 第 1 条）。
	 */
	public PendingToolCall awaitDecision(String runId, PendingToolCall pending) {
		return await(runId, pending, true);
	}

	/**
	 * 前端工具：阻塞等前端回灌结果。上限见 {@link #suspendWaitSeconds(PendingToolCall)} ——
	 * 即时执行类前端工具 120s（等机器回执的兜底）；{@code generative_form} 无上限（等的是人填表）。
	 */
	public PendingToolCall awaitFrontendResult(String runId, PendingToolCall pending) {
		return await(runId, pending, false);
	}

	private PendingToolCall await(String runId, PendingToolCall pending, boolean confirm) {
		// ===== C1：本批请求帧可能已在 park 前由 publishRequestsForParkedBatch 整批公布 =====
		// 那个入口已置 req 并发过帧；这里只登记闸门/收流/结束执行，<b>不重复发帧</b>
		// （否则重挂或续跑的重建执行会把同一张卡再弹一次）。
		boolean prePublished = this.properties.getPark().isEnabled() && Boolean.TRUE.equals(pending.getReq());
		// ===== L0（2026-10-21 裁决 A/C）：先判已决，再给请求帧"发车位" =====
		// ①已决（另一页签/旧客户端早已提交该 toolCallId）：<b>不补发请求帧</b>、不断流，直接消费既有决策
		//   —— 旧行为是无条件 emit，前端会把已收敛的卡瞬时复活成 pending、再被随后的结局帧收敛；
		// ②未决：先把 Redis 条目的 req 位置上，再发帧（刷新恢复只认 req=true 的卡，见
		//   AiController#runsCurrent 与前端 restoreSuspendedSnapshot）。
		PendingToolCall decided = reserveRequestSlot(runId, pending);
		if (decided != null) {
			return decided;
		}
		// 等待上限（null = 无上限；只有即时执行类前端工具有值）
		Integer timeoutSeconds = this.suspendWaitSeconds(pending);
		// ===== B23L1 批②b：<b>等待方持租</b>（设计稿 §2.2；方案第 1 条）=====
		// 从"进入等待"起，本进程是本轮续跑权的主人：另一实例的死卡兜底抢不到租约，于是不会与本
		// 等待方的原循环双驱动同一轮（重复帧 / settle 分叉）。抢不到（别的实例持有 / 无租约设施 /
		// Redis 降级）就照常等 —— "抢不到租约"绝不能变成"等不到人"；那条路径的互斥由
		// woke / ACTIVE_LATCH_IN_PROCESS 与 claimExecution 兜底。
		LeaseService leases = leases();
		// 带 fence 抢租（N-1）：三键同槽脚本会在取号时与 fence 对账，计数器先死也不会永久 STALE。
		LeaseService.Lease lease = leases == null ? null
				: leases.tryAcquireFenced(this.store.runLeaseKey(runId), this.store.fenceKey(runId),
						this.properties.getLease().getTtl(), null).orElse(null);
		int renewEveryTicks = leaseRenewEveryTicks();
		long heartbeatTicks = 0L;
		// 决策到达 = 把续跑权交棒给驱动方（原循环）；此刻<b>不释放</b>租约：释放会让另一个实例
		// 在"原循环正在执行已放行工具"的窗口里接管并重放同一轮。
		boolean handedOff = false;
		String key = key(runId, pending.getToolCallId());
		// 先登记闸门再发请求，避免"外部输入已到、闸门还没登记"白等到超时。
		// F8：复用 arm 的预登记（同一 key 只允许一把）—— 这里无条件 new 会盖掉预登记闸门，
		// 使"帧已发出后、进入等待前"到达的唤醒（已 countDown 在旧闸门上）丢失。
		Latch latch = this.latches.computeIfAbsent(key, ignored -> new Latch());
		// 取消的加速通道（ADR-8 修正③）：同进程取消立即解开等待，权威态仍在 ai:cancel:<runId>。
		// 跨进程取消则由下方循环里的 isCancelled 轮询发现（节拍 = 心跳节拍，属"分片边界"）。
		AutoCloseable cancelHandle = this.cancellations.onCancel(runId, latch::signal);
		SseChatEmitter out = this.registry.of(runId);
		if (!prePublished) {
			if (confirm) {
				// 确认卡不再有到期时刻（裁决第 1 条）：帧里不带 timeoutSeconds/expiresAt，
				// 前端据此不武装倒计时、不把按钮置灰。
				out.confirmRequest(pending);
			}
			else {
				out.frontendToolRequest(pending, timeoutSeconds);
			}
		}
		// ===== B1（2026-10-20 裁决 1）：请求帧发出之后、进入阻塞等待之前，主动完成本 run 的全部订阅 =====
		// 流派 B（挂起即断）：人机等待期不再占用 SSE 连接，连接在"卡已送达"这一刻收尾；
		// 决策到达后再由前端带 ?lastSeq 重连做差量补帧（delta 已入档，见 RunStore#appendEvent）。
		//
		// 断连点为什么在这里、不在 suspended 帧上：实测帧序是 suspended（SpToolCallingManager）
		// **早于** confirm_request / frontend_tool_request（本方法）—— 挂在 suspended 上会把
		// 请求帧截断在门外，用户根本收不到卡。
		//
		// 为什么不会截断本帧：completeSubscriptions 复用到"排空队列后才 complete"的收尾路径，
		// 刚 emit 的请求帧先写出、再关连接。
		//
		// 一批多 toolCall 的实施口径（C1 起）：park 开启时，整批请求帧由
		// {@link #publishRequestsForParkedBatch} 在 suspended 帧之后、执行循环之前<b>一次性公布</b>
		// （本方法看到 req 已置就不再重复发）—— 逐条发会让 park 后的第二张卡永远收不到请求帧。
		// park 关闭时仍按旧口径逐条发：每张卡在它自己的 await 里发帧，发完即断，然后阻塞等人。
		out.completeSubscriptions();
		long startedAt = System.currentTimeMillis();
		// T2b-D#1：挂起期续期 session→current 索引用的 sessionId（快照可能缺失，取一次缓存并判空）
		String sessionId = snapshotSessionId(runId);
		// ===== 人工等待 <b>park</b>（用户裁决 2026-10-25）=====
		// 请求帧已投递、本轮订阅已收流：此刻"等人"这件事没有任何本进程要做的事 ——
		// 决策会经 HTTP 落 Redis，届时由 ResumeService 重建执行（与跨实例续跑同一条通路）。
		// 因此<b>结束本次执行</b>：交还驱动租约（驱动方重抢）、不登记 latch 等待、不心跳、不续租、
		// 不进看门狗等待；快照保持 SUSPENDED（pending/req/decided/帧归档就是合法恢复状态）。
		//
		// 主动 stop 优先：取消标志已在 ⇒ 走取消终态（"普通断线/刷新只 park，主动 stop 才取消"）。
		// 注意异常出口：RunParkedException 由 SpToolCallingManager 原样穿出、ResilientChatService
		// 识别后直接退出（不写终帧、不判失败）。
		if (this.properties.getPark().isEnabled()) {
			if (this.cancellations.cancelState(runId) == CancellationRegistry.CancelState.CANCELLED) {
				// 主动 stop：先把本卡的结局落到条目上（未执行），再判"本线程能不能把整轮写进终态"。
				// 本进程此刻若已无写权限（租约设施抖动 / 已被接管 / fence 越过），本线程随后在
				// ResilientChatService#cancelTerminal 里的写会被同一判据拒绝 —— 于是"取消已确认"
				// 会停在 SUSPENDED 且无人再试（主 CI run 40211303032 · W-30）。这里就把收尾交棒给
				// 非驱动执行器（它重新取租，fencing 不绕过）；写权限正常时<b>不</b>交棒，仍由本线程
				// 随后的 cancelTerminal 收尾（不产生第二条终帧）。
				PendingToolCall cancelled = cancel(runId, pending, confirm, startedAt);
				handOffCancelFinalizationIfUnwritable(runId);
				return cancelled;
			}
			// ===== park 退出 = 一次原子生命周期切换（与决策写共用 submitLock）=====
			// 缺陷形态（主 CI run 40174289141 / H-01）：旧实现在"预登记闸门之后、还租之前"无锁地做
			// 还租（Redis 往返 1–3ms）。决策落在窗口内 ⇒ signal 命中这把<b>没人 await</b> 的闸门
			// （woke=true）⇒ 决策侧按 WOKE_IN_PROCESS_GATE 静默跳过派发；本线程随后还租、退出
			// ⇒ 一轮既没有驱动方也没有终帧，快照停在 SUSPENDED。
			// 切换在锁内复读条目，于是只有两个确定出口：已决 ⇒ 本线程接手驱动；未决 ⇒ 还租 + 摘闸门
			// 后 park（之后的 signal 必 miss，走死卡兜底）。
			PendingToolCall settled = switchParkedLifecycle(runId, pending, lease);
			if (settled != null) {
				// 决策已落库且本线程还在：由它接手驱动（不 park、不派发续跑）。
				// 决策侧此前的 woke=true 因此成立 —— 它唤醒的就是这个线程。
				// 本分支不在下方 try/finally 内，取消监听者要在这里注销。
				closeCancelHandle(cancelHandle, runId);
				handedOff = true;
				handOffLease(lease);
				return settled;
			}
			// 未决：还租与摘闸门已在锁内完成，lease 已归还，finally 不得重复释放。
			lease = null;
			// 同上：park 在 try 之前退出，取消监听者不会走 finally。
			closeCancelHandle(cancelHandle, runId);
			AiTrace.eventOn(runId, sessionId, "approval_park", "toolCallId", pending.getToolCallId(), "tool",
					pending.getName(), "kind", confirm ? "confirm" : "frontend");
			log.info("人工等待 park runId={} toolCallId={} tool={} kind={}（请求帧已投递并收流；线程交还，"
					+ "决策后由续跑重建执行）", runId, pending.getToolCallId(), pending.getName(),
					confirm ? "confirm" : "frontend");
			// ===== I4：park 之后即时前端工具的到期收敛 =====
			// 即时类前端工具（navigate_to 等）有回执上限；旧实现靠<b>等待线程</b>到期后写 TIMEOUT。
			// park 后没有等待线程 ⇒ 条目会永远 PENDING。这里按 expiresAt 排一次收敛：
			// 到点仍 PENDING ⇒ 写 TIMEOUT/FRONTEND_TIMEOUT + 发结局帧，并<b>派发一次续跑</b>让本轮收尾
			// （到期是系统裁决；不派发会留下"没有驱动方也没有终帧"的悬轮）。
			// 确认门无上限（timeoutSeconds == null）不排。
			boolean scheduled = false;
			for (PendingToolCall parked : this.store.pendingsLenient(runId)) {
				if (!PendingToolCall.PENDING.equals(parked.getStatus())
						|| !PendingToolCall.KIND_FRONTEND.equals(parked.getKind())) {
					continue;
				}
				Integer parkedTimeout = suspendWaitSeconds(parked);
				if (parkedTimeout != null) {
					scheduleParkedFrontendExpiry(runId, parked, parkedTimeout);
					scheduled = true;
				}
			}
			if (!scheduled && !confirm && timeoutSeconds != null) {
				scheduleParkedFrontendExpiry(runId, pending, timeoutSeconds);
			}
			throw new RunParkedException(runId, pending.getToolCallId(), pending.getKind());
		}
		// 人机预算分离（裁决第 3 条）：等待窗口内的时间累计到信标上，总预算判据据此扣除
		this.beacon.beginGateWait(runId);
		// 日志链路批：等待期间的心跳/续租/取消都是这条线程产的 —— 锚点在这里续上，
		// 于是"挂起期"的每一行都能按 runId 归到这一轮（跨实例续跑时由 ResumeService 的入口绑）
		if (sessionId != null) {
			AiTrace.bind(runId, sessionId);
		}
		// 日志链路批：卡已发出、等待开始 —— 这一行是"这一轮为什么慢"的第一个解释项
		AiTrace.eventOn(runId, sessionId, "approval_suspend", "toolCallId", pending.getToolCallId(), "tool",
				pending.getName(), "kind", confirm ? "confirm" : "frontend",
				"timeoutS", timeoutSeconds == null ? "none" : timeoutSeconds,
				"preflight", pending.getPreflight() != null);
		boolean cancelUnknownWarned = false;
		try {
			while (true) {
				CancellationRegistry.CancelState cancelState = this.cancellations.cancelState(runId);
				if (cancelState == CancellationRegistry.CancelState.CANCELLED) {
					return cancel(runId, pending, confirm, startedAt);
				}
				if (cancelState == CancellationRegistry.CancelState.UNKNOWN) {
					// C2：确认门不依赖 Redis 才能继续等（心跳续期另算）—— UNKNOWN 按"继续等待"处理，
					// 每张卡只 WARN 一次，避免 2s 一拍刷日志。
					if (!cancelUnknownWarned) {
						log.warn("确认门取消状态未知 runId={}（Redis 读失败；继续等待，不按取消收尾）", runId);
						cancelUnknownWarned = true;
					}
				}
				else {
					cancelUnknownWarned = false;
				}
				PendingToolCall fresh;
				// S5c-6：与提交侧读同一把锁 —— 提交方在锁内完成"落库 + 发回执帧 + 唤醒"，
				// 因此这里读到的"已决"状态一定伴随着已经写出去的回执帧。
				synchronized (this.submitLock) {
					// C2：guard 读失败不得让等待线程退出（卡仍 PENDING、工具会被记成执行失败）；
					// 用非抛读口，把"读不出来"降级为"这一拍没合并 guard"。
					fresh = this.store.pendingLenient(runId, pending.getToolCallId());
				}
				if (fresh != null && fresh.resolved()) {
					// 决策可能来自本进程（闸门唤醒）也可能来自另一实例（Redis 轮询发现）
					handedOff = true;
					handOffLease(lease);
					return fresh;
				}
				long sliceMs = HEARTBEAT_SECONDS * 1000L;
				if (timeoutSeconds != null) {
					long remain = startedAt + timeoutSeconds * 1000L - System.currentTimeMillis();
					if (remain <= 0) {
						// C1（复审必修）：前端超时<b>不是本轮结束</b> —— 结局文本回填给模型后，本线程继续驱动
						// （后续帧/工具/续跑都还在这一轮里）。所以超时出口与"决策到达"同形：交棒（不释放）。
						// 旧实现走 finally 的"未交棒即还租"，等于在轮次仍在跑的时候把续跑权扔回池子：
						// fencing 失效，另一实例的死卡兜底可以据此接管并双驱动同一轮。
						handedOff = true;
						handOffLease(lease);
						return expireFrontend(runId, pending, timeoutSeconds);
					}
					sliceMs = Math.min(sliceMs, remain);
				}
				boolean signalled = latch.await(sliceMs);
				if (!signalled) {
					heartbeatTicks++;
					// 工具活动脉冲（韧性看门狗据此在挂起期不计静默，硬规范①）
					this.beacon.pulse(runId);
					// T2b-D#1：与心跳同节拍续期 session→current 索引（TTL 对齐 session-ttl，与会话同寿）
					if (sessionId != null) {
						this.store.renewSessionCurrent(sessionId, runId);
					}
					// 裁决第 4 条：心跳同时续期卡的后台数据（快照/待决/归档/序号），
					// 于是"会话活着，卡的后台数据就活着"—— 等多久都不会出现"卡还在、挂起态已过期"。
					this.store.renewSuspensionTtl(runId);
					// 同拍续期复核器持有的一次性资源（表单附件登记）：卡活着，附件也得活着
					touchSuspendedResources(runId);
					// B23L1 批②b：续跑权租约按 lease.renew-interval 续期（默认 10s = 5 跳；
					// 与 TTL 30s 的关系是 3:1，容忍两次连续续租失败）。等多久都不会"把租约等丢"。
					if (lease != null && heartbeatTicks % renewEveryTicks == 0L) {
						renewWaitingLease(lease, runId);
					}
					out.heartbeat(heartbeatData(runId, pending, confirm, timeoutSeconds,
							(System.currentTimeMillis() - startedAt) / 1000));
				}
			}
		}
		catch (InterruptedException ex) {
			// 线程被中断（应用停止 / 池关闭）：<b>不写超时结局</b>（用户裁决 2026-10-20 第 1 条口径：
			// 确认门不设超时 —— 中断不是到期，不能把"没等到人"伪装成 TIMEOUT）。
			// 条目保持未决，进程重启后仍可由挂起态外置 + 人决策继续收敛；本轮按中断收尾。
			Thread.currentThread().interrupt();
			log.warn("挂起等待被中断 runId={} toolCallId={}（条目保持未决，不写超时结局）", runId, pending.getToolCallId());
			// C2：中断出口也不得再抛（否则原始中断被替换）；读不到就退回入参。
			PendingToolCall fresh = this.store.pendingLenient(runId, pending.getToolCallId());
			return fresh == null ? pending : fresh;
		}
		finally {
			this.beacon.endGateWait(runId);
			this.latches.remove(key, latch);
			this.armedKeys.remove(key);
			// 续跑权只在"没有交棒"的出口归还（R5：与 C1 对齐后的口径）：
			// · 决策到达 与 <b>前端超时</b> 都置 handedOff=true —— 两者之后本线程都还在驱动这一轮
			//   （已放行的工具要执行 / 超时文案回填给模型继续跑），租约交续租调度器、终态由
			//   RunStore.save 释放，这里<b>不动作</b>；
			// · 取消 与 中断 是真"本轮不驱动"（前者整轮终止、后者按收尾退出）⇒ 当场还租。
			// 释放是 epoch scoped（若是迟到的释放，不会删掉新持有者的租约）。
			if (lease != null && !handedOff) {
				releaseWaitingLease(lease, runId);
			}
			closeCancelHandle(cancelHandle, runId);
		}
	}

	/** 注销取消监听者（幂等；失败只留痕，不影响正在收尾的这一轮）。 */
	private static void closeCancelHandle(AutoCloseable cancelHandle, String runId) {
		try {
			cancelHandle.close();
		}
		catch (Exception ex) {
			log.debug("注销取消监听者失败 runId={}：{}", runId, ex.getMessage());
		}
	}

	/**
	 * <b>park 退出的原子生命周期切换</b>：与 {@code submitDecision}/{@code submitFrontendResult} 的
	 * "落库 → 回执帧 → 唤醒"共用 {@link #submitLock}。
	 *
	 * <p>为什么必须原子：旧实现把"还租"留在锁外，形成"闸门已预登记、但没人 {@code await}"的窗口。
	 * 决策落在窗口内时 {@code signal} 返回 true（唤醒的是预登记闸门），决策侧据此不再派发续跑；
	 * 随后 park 线程摘闸门退出 ⇒ 一轮没有任何驱动方（主 CI run 40174289141 的 H-01）。
	 *
	 * <p>两个出口：
	 * <ul>
	 * <li>锁内复读<b>已决</b> ⇒ 返回该条目：调用方按"决策到达"口径交棒并继续驱动 ——
	 *     恰一个驱动方，决策侧 {@code woke=true} 的跳过因此是正确的；</li>
	 * <li>锁内复读<b>未决</b> ⇒ 先还租、再摘本 run 全部闸门，返回 {@code null}，调用方 park。
	 *     顺序不可交换：先摘闸门再还租，决策方会以同 owner 重入抢到本线程手里的租约并派发，
	 *     随后本线程的还租又把这任租约删掉（fencing 被削弱）。两步同在锁内，决策要么看到
	 *     "闸门还在"（signal 命中 ⇒ 本线程接手），要么看到"闸门已摘"（signal 必 miss ⇒
	 *     死卡兜底派发）；不存在"命中却无人接力"的第三种。</li>
	 * </ul>
	 */
	private PendingToolCall switchParkedLifecycle(String runId, PendingToolCall pending, LeaseService.Lease lease) {
		synchronized (this.submitLock) {
			// C2：非抛读口 —— 读失败不得把"还租 + 摘闸门 + park"整条路变成异常出口。
			PendingToolCall fresh = this.store.pendingLenient(runId, pending.getToolCallId());
			if (fresh != null && fresh.resolved()) {
				return fresh;
			}
			releaseWaitingLease(lease, runId);
			clearWaiterLatches(runId);
			return null;
		}
	}

	/**
	 * 摘掉本 run 的<b>全部</b>等待闸门（先摘闸门、后摘预登记标记，与 {@link #disarm} / {@code await.finally}
	 * 同序）。只摘单张卡不够：同批其余卡也不会再被 {@code await}，留下的闸门会让
	 * {@link #hasActiveLatch} 恒真，挡住死卡兜底。
	 */
	private void clearWaiterLatches(String runId) {
		String prefix = runId + "|";
		for (String key : this.latches.keySet()) {
			if (!key.startsWith(prefix)) {
				continue;
			}
			Latch latch = this.latches.get(key);
			if (latch != null) {
				this.latches.remove(key, latch);
			}
			this.armedKeys.remove(key);
		}
	}

	/**
	 * 把续跑权<b>交棒</b>给驱动方（决策到达 / 前端超时两个出口同形）：不释放，续租转给调度器。
	 *
	 * <p>为什么含续租：等待方退出等待之后，继续驱动这一轮的是外层循环（已放行的工具要执行、
	 * 后续帧要产出），"持有 + 续租"必须跟着驱动方走；终态由 {@code RunStore.save} 释放
	 * （那里同时停掉续租任务）。
	 *
	 * <p><b>I7 存活判据</b>：续租任务每一拍先问"这一轮还需要我活着吗" —— 快照存在且<b>非终态</b>，
	 * 且本进程仍是这一任租约的主人（{@code heldRunEpoch == lease.epoch}）。判据转假 ⇒ 停续租 + 释放，
	 * 于是"驱动线程悄悄死掉、快照停在 RUNNING"不会留下一个永远续租的僵尸租约。
	 */
	private void handOffLease(LeaseService.Lease lease) {
		if (lease == null) {
			return;
		}
		String runId = runIdOf(lease);
		if (runId == null) {
			// 键形不认得（非本类拼的键）：没有可靠的存活判据，退回"调用方自己 release"的旧口径
			LeaseService holder = leases();
			if (holder != null) {
				holder.markDriving(lease);
			}
			return;
		}
		markDrivingGuarded(lease, runId);
	}

	/**
	 * 交续租调度器，<b>带存活判据</b>（R2：决策到达 / 前端超时 / 死卡兜底派发三条路径共用）。
	 *
	 * <p>判据 = {@link #stillDriving}（快照非终态 ∧ 本进程仍是该 epoch 主人）：判据转假 ⇒
	 * 停续租 + 归还租约。没有这一层的"恒真续租"会在驱动任务死掉时把租约永久攥住。
	 */
	private void markDrivingGuarded(LeaseService.Lease lease, String runId) {
		LeaseService holder = leases();
		if (holder == null || lease == null || runId == null) {
			return;
		}
		holder.markDriving(lease, () -> stillDriving(runId, lease));
	}

	/**
	 * 交棒后的存活判据（I7）：快照仍非终态，且本进程仍是该租约的主人。
	 *
	 * <p>读快照失败（Redis 抖动）按"仍活着"处理：判据误判为假会提前放掉一个正在跑的轮的续跑权，
	 * 代价远大于多续一拍（续租本身会失败并走 {@code renewTick} 的既有降级）。
	 */
	private boolean stillDriving(String runId, LeaseService.Lease lease) {
		try {
			RunSnapshot snapshot = this.store.get(runId);
			if (snapshot == null || RunSnapshot.isTerminal(snapshot.getStatus())) {
				return false;
			}
			return this.store.heldRunEpoch(runId) == lease.epoch();
		}
		catch (Exception ex) {
			return true;
		}
	}

	/**
	 * 租约键 → runId（键形 {@code ai:lease:run:{run:<runId>}}，迁移期也认旧的
	 * {@code ai:lease:run:<runId>}；不匹配返回 null = 判据退化为恒真）。
	 *
	 * <p>反解一律交给 {@link RunStore#runIdFromLeaseKey(String)} —— 键形知识只留在 RunStore 一处。
	 * 这里曾经自己 {@code substring(前缀长度)}，B23L1 批① 给租约键加 {@code {run:<runId>}} tag 之后，
	 * 那个写法会把 runId 反解成 {@code {run:<runId>}} ⇒ 存活判据读不到快照 ⇒ 每拍"判据转假"并释放
	 * 租约（续跑权静默丢失）。键形一变就要跟着改的反解点，只应存在于拼键的那个类里。
	 */
	private static String runIdOf(LeaseService.Lease lease) {
		return lease == null ? null : RunStore.runIdFromLeaseKey(lease.key());
	}

	/** 等待期续租的节拍（心跳跳数）= {@code lease.renew-interval} / {@link #HEARTBEAT_SECONDS}，下限 1 跳。 */
	private int leaseRenewEveryTicks() {
		Duration interval = this.properties.getLease().getRenewInterval();
		long millis = interval == null ? 0L : interval.toMillis();
		return (int) Math.max(1L, Math.round(millis / (HEARTBEAT_SECONDS * 1000.0)));
	}

	/** 等待期续租：失败只留痕（租约已被接管时，人等人的路径不受影响；fencing 写会拒掉陈旧写）。 */
	private void renewWaitingLease(LeaseService.Lease lease, String runId) {
		LeaseService leases = leases();
		if (leases == null) {
			return;
		}
		if (!leases.renew(lease, this.properties.getLease().getTtl())) {
			log.warn("等待期续租失败 runId={} epoch={}（租约可能已被另一实例接管：决策仍会正常收敛，"
					+ "但本进程不再拥有续跑权）", runId, lease.epoch());
		}
	}

	/** 等待方离开且<b>没有</b>交棒时的释放（epoch scoped：错 epoch 不删别人的租约）。 */
	private void releaseWaitingLease(LeaseService.Lease lease, String runId) {
		LeaseService leases = leases();
		if (leases == null) {
			return;
		}
		if (!leases.release(lease)) {
			log.debug("等待方释放续跑租约未生效 runId={} epoch={}（现值已不是自己那一任）", runId, lease.epoch());
		}
	}

	/**
	 * L0（2026-10-21 裁决 A/C）：请求帧的"发车位"。
	 *
	 * <p>两件事在<b>同一把提交锁内</b>完成（与 {@link #submitDecision} / {@link #submitFrontendResult}
	 * 的"落库 → 发回执帧 → 唤醒"共享同一互斥，因此"读到的已决"一定伴随已经写出去的回执帧）：
	 * <ol>
	 * <li><b>已决则不发</b>：条目状态已非 {@code PENDING} ⇒ 返回该条目，调用方直接消费既有决策
	 *     （批准 → 执行；拒绝 → 未执行回填；前端结果 → 用既有 resultText），<b>不发请求帧、不断流</b>
	 *     —— 旧行为在这里无条件 {@code confirmRequest}/{@code frontendToolRequest} + {@code completeSubscriptions}，
	 *     前端会把一张早已收敛的卡瞬时复活；</li>
	 * <li><b>未决则置位</b>：{@link RunStore#markRequestEmitted} 在 Redis 条目上落 {@code req=true} + 时刻，
	 *     随后 {@code await} 才发帧。刷新恢复据此只重建"真的发过请求帧"的卡。</li>
	 * </ol>
	 *
	 * <p>预登记闸门（{@link #arm}）在已决分支一并归还：本方法不会进入等待，闸门留着会让
	 * {@link #hasActiveLatch} 在整批结算前恒真，挡住本进程的死卡兜底。归还动作与 {@code await}
	 * 的 {@code finally} 同序（先摘闸门、后摘预登记标记）。
	 *
	 * <p><b>M-1（2026-10-21 收口修）：置位后要再判一次。</b>{@link RunStore#markRequestEmitted} 是
	 * 「读 → 判 → 写」而非原子 CAS：读时还是 {@code PENDING}、写前被另一实例决策，返回的就是一个
	 * <b>已决</b>条目（{@code RunStore} 原样返回、不覆写）。此时若照常发帧，裁决 C 的洞会在更窄的
	 * 跨进程窗口里复发 —— 故写后复判，已决则按已决消费（同样不发帧、不断流、归还闸门）。
	 *
	 * @return 已决条目（调用方直接消费）；未决返回 null（请求帧已获准发射、req 位已落）
	 */
	private PendingToolCall reserveRequestSlot(String runId, PendingToolCall pending) {
		synchronized (this.submitLock) {
			// C2：guard 读失败不得让"请求帧发射"路径整体失败；非抛读口仍能拿到原始条目。
			PendingToolCall fresh = this.store.pendingLenient(runId, pending.getToolCallId());
			if (fresh != null && fresh.resolved()) {
				return consumeDecided(runId, pending.getToolCallId(), fresh);
			}
			long atMs = System.currentTimeMillis();
			PendingToolCall marked = this.store.markRequestEmitted(runId, pending.getToolCallId(), atMs);
			if (marked != null && marked.resolved()) {
				// 读后写前被决（跨进程；单实例由本锁串行化，不可达）：置位作废，按已决消费
				return consumeDecided(runId, pending.getToolCallId(), marked);
			}
			// 内存副本同步：同一批里的日志/后续判断读到的是"已发帧"的条目，而不是 saveSuspended 那版
			pending.setReq(Boolean.TRUE);
			pending.setReqAtMs(marked == null || marked.getReqAtMs() == 0L ? atMs : marked.getReqAtMs());
			return null;
		}
	}

	/**
	 * 已决分支的统一收尾：归还 {@link #arm} 预登记但不会进入等待的闸门（先摘闸门、后摘预登记标记，
	 * 与 {@code disarm} / {@code await.finally} 同序），留日志后把条目交给调用方消费。
	 */
	private PendingToolCall consumeDecided(String runId, String toolCallId, PendingToolCall decided) {
		String key = key(runId, toolCallId);
		Latch latch = this.latches.get(key);
		if (latch != null) {
			this.latches.remove(key, latch);
		}
		this.armedKeys.remove(key);
		log.info("条目已决，跳过请求帧发射 runId={} toolCallId={} status={}（直接消费既有决策）", runId, toolCallId,
				decided.getStatus());
		return decided;
	}

	/**
	 * park 退出时的取消收尾交棒：<b>只有</b>本进程这一拍已无法写（租约不可确认 / 确定丢租 /
	 * fence 越过本进程）才交棒。
	 *
	 * <p>判据复用唯一写门 {@link RunStore#permittedToWrite(String, String)}（与快照、帧、台账
	 * 同一套）：写权限正常的 park 取消<b>不</b>交棒 —— 本线程随后的 {@code cancelTerminal} 会落
	 * CANCELLED 并补终帧，再交棒就是第二条终帧。
	 */
	private void handOffCancelFinalizationIfUnwritable(String runId) {
		if (this.cancelFinalizer == null) {
			return;
		}
		boolean writable;
		try {
			writable = this.store.permittedToWrite(runId, "取消交棒");
		}
		catch (RuntimeException ex) {
			writable = false;
		}
		if (writable) {
			return;
		}
		try {
			boolean handed = this.cancelFinalizer.handOff(runId, CancelledRunFinalizer.CAUSE_PARK_EXIT);
			log.warn("park 取消时本进程已无写权限，已交棒取消收尾 runId={} handed={}（交棒侧重新取租后补写"
					+ " CANCELLED + 终帧；本线程不再重复出帧）", runId, handed);
		}
		catch (RuntimeException ex) {
			log.warn("park 取消时交棒失败 runId={}：{}（取消事实仍在 ai:cancel:<runId>）", runId, ex.getMessage());
		}
	}

	/**
	 * 取消落地：把仍 {@code PENDING} 的挂起条目标为
	 * {@link PendingToolCall#CANCELLED}（未执行），发一帧与人工决策同形的结局帧，
	 * 然后由 {@code SpToolCallingManager} 在事件边界终止整轮（终态 {@code done{cancelled:true}}）。
	 */
	private PendingToolCall cancel(String runId, PendingToolCall pending, boolean confirm, long startedAt) {
		PendingToolCall fresh = this.store.pendingLenient(runId, pending.getToolCallId());
		if (fresh == null) {
			fresh = pending;
		}
		if (PendingToolCall.PENDING.equals(fresh.getStatus())) {
			fresh.setStatus(PendingToolCall.CANCELLED);
			fresh.setOutcome(ToolOutcome.CANCELLED.wire());
			fresh.setCode(ToolOutcome.CODE_RUN_CANCELLED);
			fresh.setReason("RUN_CANCELLED");
			fresh.setExecuted(false);
			fresh.setResolvedAtMs(System.currentTimeMillis());
			fresh.setResultText("本轮对话已被用户取消，该工具未执行。");
			this.store.putPending(runId, fresh);
		}
		SseChatEmitter out = this.registry.of(runId);
		if (confirm) {
			out.confirmDecision(fresh, "cancel", fresh.getReason(), 0L);
		}
		else {
			out.frontendToolResult(fresh, false, fresh.getResultText());
		}
		log.info("挂起等待被取消 runId={} toolCallId={} kind={} 已等待={}s", runId, pending.getToolCallId(),
				confirm ? "confirm" : "frontend-tool", (System.currentTimeMillis() - startedAt) / 1000);
		traceDecision(runId, pending.getToolCallId(), "cancel", fresh, "ACCEPTED", "user");
		return fresh;
	}

	/**
	 * 前端工具回执兜底收口（<b>只对有时限的前端工具</b>，见 {@link #suspendWaitSeconds}）：
	 * 把结局也写进外置状态（重启后仍可读）。
	 *
	 * <p>结局语义：等待的是前端的回执 ⇒ 超时只说明"回执没回来"，<b>结局未知</b>
	 * （{@code FRONTEND_TIMEOUT}）—— 副作用可能已经发生（如文件已落盘），故不再写
	 * "本次调用未获得数据"这种会被读成"什么都没发生"的措辞。
	 *
	 * <p><b>确认门没有这条路径</b>（裁决 2026-10-20 第 1 条）：确认卡只由人收摊，系统不再
	 * 产生 {@code decision=timeout} 帧。
	 */
	private PendingToolCall expireFrontend(String runId, PendingToolCall pending, int timeoutSeconds) {
		PendingToolCall fresh = this.store.pendingLenient(runId, pending.getToolCallId());
		if (fresh == null) {
			fresh = pending;
		}
		if (!PendingToolCall.PENDING.equals(fresh.getStatus())) {
			return fresh;
		}
		fresh.setStatus(PendingToolCall.TIMEOUT);
		fresh.setOutcome(ToolOutcome.TIMEOUT.wire());
		fresh.setCode(ToolOutcome.CODE_FRONTEND_TIMEOUT);
		fresh.setReason("FRONTEND_TIMEOUT");
		fresh.setExecuted(false);
		fresh.setResolvedAtMs(System.currentTimeMillis());
		fresh.setResultText(
				"前端工具 " + fresh.getName() + " 未在 " + timeoutSeconds + " 秒内收到前端回执，本次调用结局未知（不表示未发生）。");
		this.store.putPending(runId, fresh);
		this.registry.of(runId).frontendToolResult(fresh, false, fresh.getResultText());
		traceDecision(runId, pending.getToolCallId(), "timeout", fresh, "ACCEPTED", "system");
		return fresh;
	}

	/**
	 * <b>I4：park 之后按 {@code expiresAt} 排一次前端回执到期收敛</b>。
	 *
	 * <p>park 把等待线程交还了，到期判定不能随之消失：即时类前端工具（有
	 * {@code app.ai.hitl.frontend-tool-timeout} 上限）到点仍 {@code PENDING} ⇒ 走
	 * {@link #expireFrontend}（{@code TIMEOUT} + 结局帧 + 落库），并<b>派发一次续跑</b>。
	 *
	 * <p>为什么必须派发：到期是<b>系统裁决</b>（不是"无人时替用户继续"）—— 条目已收敛，这一轮如果
	 * 既不驱动也不终帧，用户只会看到一张超时卡、永远等不到回答（E2E S-09 的契约是"卡收敛为终态、
	 * 轮次继续到 done"）。派发与决策/回灌同一条有界通路（租约、池饱和重试、跨实例恢复都不变），
	 * park 仍成立：不驻留线程，恢复依据全在 Redis。
	 */
	private void scheduleParkedFrontendExpiry(String runId, PendingToolCall pending, int timeoutSeconds) {
		long atMs = pending.getAtMs() == null || pending.getAtMs() <= 0 ? System.currentTimeMillis() : pending.getAtMs();
		Long expiresAt = pending.getExpiresAt();
		long deadlineMs = expiresAt == null || expiresAt <= 0 ? atMs + timeoutSeconds * 1000L : expiresAt;
		long delayMs = Math.max(0L, deadlineMs - System.currentTimeMillis());
		String toolCallId = pending.getToolCallId();
		String toolName = pending.getName();
		try {
			this.retryScheduler.schedule(() -> {
				try {
					// 到期裁决跑在定时线程上：按本轮记录建立身份后再收敛条目/派发续跑。
					RunSnapshot snapshot = this.store.get(runId);
					withRunIdentity(snapshot, () -> {
						PendingToolCall fresh = this.store.pendingLenient(runId, toolCallId);
						if (fresh == null || !PendingToolCall.PENDING.equals(fresh.getStatus())) {
							return;
						}
						PendingToolCall expired = expireFrontend(runId, fresh, timeoutSeconds);
						settleParkedToolCall(runId, expired, expired.getResultText());
						AiTrace.eventOn(runId, snapshotSessionId(runId), "frontend_parked_expired", "toolCallId",
								toolCallId, "tool", toolName, "timeoutS", timeoutSeconds);
						log.info("park 后的前端回执到期，条目已收敛 runId={} toolCallId={} tool={} timeoutS={}"
								+ "（派发一次续跑：轮次必须继续收尾，不能停在「没有驱动方也没有终帧」）",
								runId, toolCallId, toolName, timeoutSeconds);
						// 到期 = 系统裁决：条目已收敛，这一轮必须继续；与决策/回灌同一条有界派发。
						triggerResume(runId, false, true);
					});
				}
				catch (RuntimeException ex) {
					log.warn("park 后的前端回执到期收敛失败 runId={} toolCallId={}：{}", runId, toolCallId,
							ex.getMessage());
				}
			}, Duration.ofMillis(delayMs));
		}
		catch (RuntimeException ex) {
			log.warn("park 后的前端回执到期收敛未排上 runId={} toolCallId={}：{}", runId, toolCallId,
					ex.getMessage());
		}
	}

	private Map<String, Object> heartbeatData(String runId, PendingToolCall pending, boolean confirm,
			Integer timeoutSeconds, long waitedSeconds) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("runId", runId);
		data.put("suspendKind", confirm ? "confirm" : "frontend-tool");
		data.put("toolCallId", pending.getToolCallId());
		data.put("name", pending.getName());
		data.put("status", pending.getStatus());
		data.put("waitedSeconds", waitedSeconds);
		// 无上限的挂起（确认门 / generative_form）报 null：前端据此不武装倒计时
		data.put("timeoutSeconds", timeoutSeconds);
		return data;
	}

	// ── 提交侧（HTTP 端点调用） ────────────────────────────────────────────

	public enum Outcome {

		ACCEPTED, DUPLICATE, NOT_FOUND,

		/**
		 * 错入口（入口 kind 校验批）：条目存在，但 {@code kind} 与本入口要求的种类不符
		 * （{@code /confirm} 只收 {@code CONFIRM}，{@code /frontend-tool-result} 只收 {@code FRONTEND}）。
		 *
		 * <p><b>什么都没变</b>：不写状态、不占决策 guard、不发回执帧、不唤醒 —— 错入口的请求
		 * 不该在条目上留下任何痕迹（否则"人没点过"会被写成"人批准了"）。
		 */
		TOOL_KIND_MISMATCH,

		/**
		 * 回灌被安全闸拒绝（DC-15）：状态<b>未变</b>（仍 {@code PENDING}），挂起继续等待
		 * —— 前端可修正后重试，或改以 {@code cancelled=true} 放弃。裁定理由见
		 * {@link Submission#verdict()}。
		 */
		REJECTED

	}

	/**
	 * 一次外部输入的处理结果。
	 *
	 * @param outcome 结局（ACCEPTED / DUPLICATE / NOT_FOUND / REJECTED）
	 * @param pending 落库后的条目（NOT_FOUND 时为 null；REJECTED 时为<b>未改动</b>的原条目）
	 * @param woke 是否唤醒了本进程内正在等待的闸门（false 说明等待方在<b>另一个进程</b>或已死，
	 * 由续跑接手）
	 * @param verdict 安全闸的裁定（仅 {@link Outcome#REJECTED} 时非空）
	 * @param resumeTrigger 死卡兜底的续跑触发结果（T3-6a；随响应体回给调用方，只读信息）
	 */
	public record Submission(Outcome outcome, PendingToolCall pending, boolean woke, FrontendToolGuard.Verdict verdict,
			ResumeTrigger resumeTrigger) {

		/** 兼容构造（非 ACCEPTED 分支与既有调用方）：不涉及续跑触发。 */
		public Submission(Outcome outcome, PendingToolCall pending, boolean woke, FrontendToolGuard.Verdict verdict) {
			this(outcome, pending, woke, verdict, ResumeTrigger.notApplicable());
		}
	}

	/**
	 * 续跑触发的结果（T3-6a）。
	 *
	 * <p><b>为什么 outcome 是"投递结论"而不是"续跑结论"</b>（A9）：触发动作是<b>异步</b>的
	 * （HTTP 线程必须立即返回），此刻 {@code ResumeService.resume} 的真实结局还不存在。
	 * 真实的续跑结局（{@code ACCEPTED}/{@code ALREADY_RUNNING}/{@code PENDING_UNRESOLVED}/
	 * {@code POOL_SATURATED}/异常）由触发任务在执行时打 INFO 日志留痕；
	 * 本记录只回答"这次外部输入有没有把续跑派出去、没派出是因为什么"，这正是调用方与排障需要知道的。
	 *
	 * @param triggered 是否已把续跑动作派给轻量执行器
	 * @param outcome   投递结论（机器可读；见 {@link #maybeTriggerResume} 的取值表）
	 * @param detail    人读补充（可空）
	 */
	public record ResumeTrigger(boolean triggered, String outcome, String detail) {

		public static ResumeTrigger notApplicable() {
			return new ResumeTrigger(false, "NOT_APPLICABLE", null);
		}

		private static ResumeTrigger skipped(String outcome, String detail) {
			return new ResumeTrigger(false, outcome, detail);
		}

		private static ResumeTrigger dispatched() {
			return new ResumeTrigger(true, "SCHEDULED", null);
		}
	}

	/**
	 * 人工决策（写入 + 结构化留痕）：语义与 {@link #doSubmitDecision} 逐字相同，
	 * 本方法只多一件事 —— 无论结局（含 {@code NOT_FOUND}/{@code DUPLICATE}）都落一行
	 * {@code event=approval_decided}，让"有人点过、但没生效"这类形态在日志里也有痕迹。
	 */
	public Submission submitDecision(String runId, String toolCallId, boolean approved, String reason) {
		Submission submission = doSubmitDecision(runId, toolCallId, approved, reason);
		traceDecision(runId, toolCallId, approved ? "approve" : "reject", submission.pending(),
				String.valueOf(submission.outcome()), "user");
		return submission;
	}

	/**
	 * 人工决策：<b>抢决策 guard</b> → 先写 Redis，再发回执帧，最后唤醒（帧先于它唤起的后果）。
	 * 重复提交按状态幂等拒绝（SP-01d V-d2B）。
	 *
	 * <p><b>入口 kind（入口 kind 校验批）</b>：本入口只改写 {@code kind=CONFIRM} 的条目。
	 * 前端工具的 toolCallId 发到这里 ⇒ {@link Outcome#TOOL_KIND_MISMATCH}，条目不变
	 * （详见 {@link #kindMismatch}）；判定在 PENDING 之前，故"错入口 + 已决"报 mismatch，
	 * "正确 kind + 已决"仍是 DUPLICATE。
	 *
	 * <h2>R4（B23L1 批②b）：全集群唯一写者</h2>
	 * 过去"读 PENDING → 判 → 改写 → 落库"只由 {@code submitLock} 串行化，而那是<b>实例内</b>的锁：
	 * 两个实例（两个 JVM / 两次请求落在不同实例）会各读一次 PENDING、各改一版、各自落库 ——
	 * 两次都成功、两帧回执、后写覆盖先写。现在第一件事是抢 {@code ai:decided:<runId>} 的
	 * {@code HSETNX}（批②a 建设施，单条命令即原子）：抢到的才是唯一写者，抢不到的按已决响应。
	 *
	 * <p><b>为什么在 {@code submitLock} 之外抢</b>：那把锁只在<b>本实例</b>内排他，对跨实例的
	 * 竞争毫无作用；把它包在 guard 外面只会让本实例的提交在锁内多做一次 Redis 往返
	 * （等待侧 2s 轮询也读这把锁，往返被拉长 = 决策可见性变慢）。guard 的互斥由 Redis 保证，
	 * 与本地锁正交；本地锁仍然只管"落库 → 发帧 → 唤醒"这三步的帧序。
	 *
	 * <p><b>失败/已决分支不写 guard</b>：{@code NOT_FOUND} 与"条目已非 PENDING"（旧客户端/取消/
	 * 超时先到）都不该占用 guard，否则一条本不该被决的卡会被永久判成"已决"。
	 *
	 * <p><b>{@code claim == null} 的处理</b>：真实 {@link RunStore} 永远返回结论或抛异常；
	 * 只有<b>未实现该口的替身</b>（既有 {@code mock(RunStore.class)} 用例）会返回 null。
	 * 此时按"本次抢到"继续 —— 语义回落到批②b 之前的"实例内互斥"口径，而不是把一次合法提交
	 * 打成 NPE。这是刻意的降级分支，不是给生产路径留的后门。
	 */
	private Submission doSubmitDecision(String runId, String toolCallId, boolean approved, String reason) {
		PendingToolCall pending = pendingForWrite(runId, toolCallId);
		if (pending == null) {
			return new Submission(Outcome.NOT_FOUND, null, false, null);
		}
		if (kindMismatch(pending, PendingToolCall.KIND_CONFIRM, "confirm")) {
			return new Submission(Outcome.TOOL_KIND_MISMATCH, pending, false, null);
		}
		if (!PendingToolCall.PENDING.equals(pending.getStatus())) {
			return new Submission(Outcome.DUPLICATE, pending, false, null);
		}
		String decidedStatus = approved ? PendingToolCall.APPROVED : PendingToolCall.REJECTED;
		RunStore.DecisionClaim claim = this.store.tryRecordDecision(runId, toolCallId, decidedStatus);
		if (claim != null) {
			if (!claim.won()) {
				return convergeOnDecided(runId, toolCallId, claim.recorded());
			}
			// guard 到手：读与 guard 之间可能已有"不带 guard 的写者"（取消/前端超时）落库，故复判一次。
			// 复判必须看<b>原始</b>条目：读侧合并会把我刚写的 guard 回灌进视图，于是"自己把自己判成已决"。
			// （guard 不存在时没有这件事可判 —— 替身/降级实现，保持批②b 之前的单读路径。）
			pending = pendingForWrite(runId, toolCallId);
			if (pending == null) {
				return new Submission(Outcome.NOT_FOUND, null, false, null);
			}
			if (!PendingToolCall.PENDING.equals(pending.getStatus())) {
				return new Submission(Outcome.DUPLICATE, pending, false, null);
			}
		}
		pending.setStatus(decidedStatus);
		pending.setReason(reason == null || reason.isBlank()
				? (approved ? "用户在确认门中批准" : "用户在确认门中拒绝") : reason);
		// 决策落库时"一定还没执行"：执行由循环内的认领（claimExecution）负责置位
		pending.setExecuted(false);
		pending.setExecutedBy(null);
		pending.setResolvedAtMs(System.currentTimeMillis());
		if (!approved) {
			pending.setOutcome(ToolOutcome.REJECTED.wire());
			pending.setCode(null);
			pending.setResultText("用户拒绝了该操作，工具未执行。拒绝原因：" + pending.getReason());
		}
		else {
			// 放行后的结局由执行决定（executeOnce 写入），此处清掉可能残留的旧结局
			pending.setOutcome(null);
			pending.setCode(null);
		}
		boolean woke;
		// S5c-6：落库 → 发回执帧 → 唤醒，一次不可分割（顺序不可交换：唤醒会让等待方立刻产出
		// "后果帧"，回执若晚于它就变成"工具结局先到、人的决策后到"，极端时晚于 done）。
		synchronized (this.submitLock) {
			this.store.putPending(runId, pending);
			this.registry.of(runId).confirmDecision(pending, approved ? "approve" : "reject", pending.getReason(), 0L);
			woke = signal(runId, toolCallId);
		}
		// ===== park 结算：拒绝也是"这张卡的结论"（独立复审 M-2′）=====
		// park 开启时整批 tool_start 已前移公布，而首张确认卡在 await 处直接穿出批循环
		// （RunParkedException）—— 循环末尾的 emitOnce 永远不会执行。用户拒绝落在另一个请求/实例上，
		// 若这里不结算，这条调用就永久停在"有 start、没有 end"，与"进程死在 start 与 end 之间"
		// 在日志里不可区分，ai:observed 的对应 field 也永远空着。
		// ① <b>只拒绝</b>：放行后由续跑的认领胜者（claimExecution）结算，决策侧补会双发；
		// ② <b>只 park</b>：park 关闭时等待线程在场，它接手后自会结算（同 submitFrontendResult /
		//    dismiss 的判据）；
		// ③ 必须在 submitLock <b>之外</b>（锁内不做 Redis 往返，理由同 submitFrontendResult）；
		// ④ 与批循环/续跑认领的那一条由 ai:observed 的首写者仲裁（决策恰落在交接窗口时只发一条）。
		if (!approved && publishesRequestsAheadOfLoop()) {
			settleParkedToolCall(runId, pending, pending.getResultText());
		}
		return new Submission(Outcome.ACCEPTED, pending, woke, null, maybeTriggerResume(runId, woke));
	}

	/**
	 * 决策落地的<b>唯一</b>结构化日志出口（日志链路批）：{@code event=approval_decided}。
	 *
	 * <p>四个决策来源（人工批准/拒绝、前端回灌、整轮取消、前端超时）都经过这里 —— 排查"这一轮到底
	 * 是卡在人身上、还是卡在机器上"只需要按 runId 拉出这些行看 {@code waitedMs} 与 {@code actor}。
	 *
	 * @param decision 决策类型（{@code approve}/{@code reject}/{@code frontend_result}/{@code cancel}/{@code timeout}）
	 * @param outcome  提交结局（{@code ACCEPTED}/{@code DUPLICATE}/{@code NOT_FOUND} …）
	 * @param actor    谁拍的板（{@code user}=人；{@code system}=超时等系统动作）
	 */
	private void traceDecision(String runId, String toolCallId, String decision, PendingToolCall pending,
			String outcome, String actor) {
		// waitedMs = 卡送达（req 位）→ 决策；取不到请求时刻时写 none（不写 -1：-1 会被 awk 当成"负数毫秒"）
		Object waitedMs = "none";
		if (pending != null && pending.getResolvedAtMs() > 0 && pending.getReqAtMs() > 0) {
			waitedMs = Math.max(0L, pending.getResolvedAtMs() - pending.getReqAtMs());
		}
		String kind = pending == null ? "none"
				: (PendingToolCall.KIND_FRONTEND.equals(pending.getKind()) ? "frontend" : "confirm");
		// timeoutS：本条目等待上限（none = 无上限）；与 approval_suspend 的同一把尺（suspendWaitSeconds）
		Integer timeoutS = pending == null ? null : suspendWaitSeconds(pending);
		long resolvedAtMs = pending == null ? 0L : pending.getResolvedAtMs();
		// suspendMs = 挂起开始（arm，即 suspended 帧之前）→ 决策；与 waitedMs（单张卡送达 → 决策）区分：
		// 一批多卡时后面的卡 suspendMs 明显更长（它在前几张卡的等待里排过队）
		long suspendStartedAtMs = this.suspensionStartedAtMs.getOrDefault(runId, 0L);
		// 同一毫秒内决策 = 0ms（用 >= 而不是 >：毫秒粒度下"同一毫秒"是常态，不能因此写成 -1）
		long suspendMs = suspendStartedAtMs > 0L && resolvedAtMs >= suspendStartedAtMs
				? resolvedAtMs - suspendStartedAtMs : -1L;
		AiTrace.eventOn(runId, sessionOf(runId), "approval_decided", "toolCallId", toolCallId, "decision",
				decision, "actor", actor, "outcome", outcome, "waitedMs", waitedMs, "suspendMs", suspendMs,
				"kind", kind, "timeoutS", timeoutS == null ? "none" : timeoutS,
				"tool", pending == null ? null : pending.getName(),
				"status", pending == null ? null : pending.getStatus());
	}

	/**
	 * 决策行的 sessionId：<b>先看当前线程的 MDC</b>（驱动线程/等待线程本来就有），
	 * 没有才回查快照 —— 决策路径对时延敏感，不为一行日志多付一次 Redis 读。
	 */
	private String sessionOf(String runId) {
		String fromMdc = AiTrace.currentSessionId();
		return fromMdc != null ? fromMdc : snapshotSessionId(runId);
	}

	/**
	 * <b>写路径</b>读条目：优先 {@link RunStore#pendingRaw}（不做 req/guard 读侧合并）。
	 *
	 * <p>为什么写路径必须看原始态：I2① 之后 {@code pending()} 会把 guard 结论合进返回值 ——
	 * 写者刚抢到 guard 就复读时，会看到"已决"（其实是自己刚写的那条 guard），于是把自己判成重复提交；
	 * 而 WAL 补齐要判断的也是"存储里还是不是 PENDING"。
	 *
	 * <p>替身（未实现 {@code pendingRaw} 的 mock）返回 null 时回落 {@code pending()}：
	 * 既有用例的桩因此零改动。
	 */
	private PendingToolCall pendingForWrite(String runId, String toolCallId) {
		PendingToolCall raw = this.store.pendingRaw(runId, toolCallId);
		return raw != null ? raw : this.store.pending(runId, toolCallId);
	}

	/**
	 * 入口 kind 校验（入口 kind 校验批）：<b>服务端条目</b>声明的种类是否与本入口要求相符。
	 *
	 * <ul>
	 * <li>条目带 kind 且不符 ⇒ {@code true}（调用方回 {@link Outcome#TOOL_KIND_MISMATCH}，
	 *     条目一个字节都不改）；</li>
	 * <li><b>{@code kind=null} 也 ⇒ {@code true} + WARN</b>（本批收紧）：kind 是服务端写入的通道事实，
	 *     缺失时无法证明这个条目该走确认门还是前端回灌。缺证据不授权 —— <b>不得批准、不得执行</b>，
	 *     显式回 {@code TOOL_KIND_MISMATCH}，条目保持原状（旧兼容口径"放行 + WARN"已废止）；</li>
	 * <li>相符 ⇒ {@code false}。</li>
	 * </ul>
	 *
	 * <p>位置刻意在 {@code PENDING} 判定<b>之前</b>：错入口是比"重复提交"更根本的事实 ——
	 * 一个已决的前端条目被发到 {@code /confirm} 时，报 mismatch 才能让调用方知道该换接口，
	 * 报 DUPLICATE 只会让它继续发错。而<b>正确</b> kind 的重复提交照旧 DUPLICATE（幂等口径不变）。
	 *
	 * @param endpoint 入口名（只进日志，便于定位是谁把哪张卡发错了）
	 */
	private boolean kindMismatch(PendingToolCall pending, String expectedKind, String endpoint) {
		if (!pending.hasKind()) {
			log.warn("条目缺 kind，拒绝批准/回灌（缺证据不授权）toolCallId={} name={} endpoint={} expected={}"
					+ "（不写状态、不占 guard、不发帧）", pending.getToolCallId(), pending.getName(), endpoint,
					expectedKind);
			return true;
		}
		if (pending.isKind(expectedKind)) {
			return false;
		}
		log.warn("入口与条目种类不匹配 toolCallId={} name={} kind={} endpoint={} expected={}（不写状态、不占 guard、不发帧）",
				pending.getToolCallId(), pending.getName(), pending.getKind(), endpoint, expectedKind);
		return true;
	}

	/**
	 * guard 已被别的写者抢到时的收敛路径（R4 的"恰一次"）：结论已定 ⇒ 按幂等回 {@code DUPLICATE}。
	 *
	 * <h2>WAL 修复（不补帧，刻意）</h2>
	 * guard 有值而条目仍是 {@code PENDING} = 上一写者死在"写 guard → 改条目"之间（{@code ai:decided}
	 * 就是那条 WAL）。此时按 guard 的值把条目补齐，让等待侧/续跑侧能收敛出结论。
	 *
	 * <p><b>这里不发回执帧</b>：抢到 guard 的写者可能只是"还在落库的路上"，本方法无法区分
	 * "它死了"与"它正在写" —— 发了就会在正常的双写竞争里出现两帧（R4 锁的正是"帧恰一次"）。
	 * 帧的发送权只属于 guard 的持有者；条目补齐后，后果帧（{@code tool_result} 等）照常由驱动方产出。
	 */
	private Submission convergeOnDecided(String runId, String toolCallId, String recorded) {
		String decidedStatus = statusOfDecision(recorded);
		// 用"原始"条目：pending() 已经把 guard 结论合进返回值（I2① 的读侧收敛），
		// 拿合并后的视图判断"要不要补齐"就永远看不到该补的那一态。
		PendingToolCall pending = this.store.pendingRaw(runId, toolCallId);
		if (pending == null) {
			return new Submission(Outcome.NOT_FOUND, null, false, null);
		}
		if (!PendingToolCall.PENDING.equals(pending.getStatus())) {
			return new Submission(Outcome.DUPLICATE, pending, false, null);
		}
		if (decidedStatus == null || !RunStore.mergeableDecisionStatus(decidedStatus)) {
			// I1：不可重建的结论（APPROVED / FRONTEND_RESULT）<b>不写裸状态</b> —— 前者需要执行侧字段
			// （executed=false 等由驱动方按 E2 语义补），后者的 resultText 就是用户填的值，裸写会把载荷抹掉。
			// 读侧（RunStore#applyDecisionMarker）对这两类同样不合并 ⇒ 卡继续显示为待处理，不出现"已决但没内容"。
			return new Submission(Outcome.DUPLICATE, pending, false, null);
		}
		// WAL 修复：条目补齐成 guard 记录的结论（执行/发帧都不在这里做）。读侧已据 guard 收敛，
		// 这里的写是<b>持久化</b>同一条结论（否则下次读走别的路径时结论会消失）。
		pending.setStatus(decidedStatus);
		pending.setExecuted(false);
		pending.setResolvedAtMs(System.currentTimeMillis());
		pending.setReason("由另一实例落库的决策（guard=" + decidedStatus + "）；本条为 WAL 补齐");
		if (PendingToolCall.REJECTED.equals(decidedStatus)) {
			pending.setOutcome(ToolOutcome.REJECTED.wire());
			pending.setCode(null);
			pending.setResultText("用户拒绝了该操作，工具未执行。");
		}
		else if (PendingToolCall.FRONTEND_CANCELLED.equals(decidedStatus)) {
			pending.setOutcome(ToolOutcome.CANCELLED.wire());
			pending.setCode(ToolOutcome.CODE_FRONTEND_CANCELLED);
		}
		else if (PendingToolCall.CANCELLED.equals(decidedStatus)) {
			pending.setOutcome(ToolOutcome.CANCELLED.wire());
			pending.setCode(ToolOutcome.CODE_RUN_CANCELLED);
		}
		synchronized (this.submitLock) {
			this.store.putPending(runId, pending);
		}
		log.warn("决策 guard 已被另一写者持有 runId={} toolCallId={} guard={}（按幂等拒绝；条目按 WAL 补齐，不补发回执帧）",
				runId, toolCallId, recorded);
		// I2②：补出来的结论必须有驱动者 —— 否则这张卡停在"已决 + 没人推进"（尤其确认门没有超时兜底）。
		// 走的就是死卡兜底同一条判定（woke=false：本进程确实没有等待方，否则会走到 WOKE/ACTIVE_LATCH 分支）。
		Submission converged = new Submission(Outcome.DUPLICATE, pending, false, null,
				maybeTriggerResume(runId, false));
		return converged;
	}

	/** guard 原值 {@code <status>|<atMs>|<instanceId>} → status；格式不认识返回 null。 */
	private static String statusOfDecision(String recorded) {
		if (recorded == null || recorded.isBlank()) {
			return null;
		}
		int sep = recorded.indexOf('|');
		String status = sep < 0 ? recorded : recorded.substring(0, sep);
		return status.isBlank() ? null : status;
	}

	/** 前端工具结果回灌（保持既有四参口径：非取消路径）。 */
	public Submission submitFrontendResult(String runId, String toolCallId, String result, String source) {
		return submitFrontendResult(runId, toolCallId, result, source, false, null);
	}

	/**
	 * 前端工具结果回灌：先写 Redis，再发回灌帧，最后唤醒（同 S5c-6 的口径）。
	 *
	 * <h2>三条分支（DC-15 起）</h2>
	 * <ol>
	 * <li><b>取消</b>（{@code cancelled=true}，用户关闭/放弃表单）→ 明确终态
	 * {@link PendingToolCall#FRONTEND_CANCELLED}，结局帧 {@code frontend_tool_result(ok=false)}，
	 * 结果文本用机器可读前缀标记"未获得数据"；</li>
	 * <li><b>结果复核不过</b>（{@link FrontendToolGuard#inspectResult}）→ {@link Outcome#REJECTED}：
	 * <b>状态不变、不发帧、不唤醒</b>，理由随 HTTP 400 返回给前端（可修正后重试）；</li>
	 * <li><b>通过</b> → 既有口径：{@link PendingToolCall#FRONTEND_RESULT} + 回灌帧 + 唤醒；
	 * 之后才调 {@link FrontendToolGuard#settleAcceptedResult}（一次性资源的消费口）。</li>
	 * </ol>
	 *
	 * <p><b>消费只发生在结论落库之后</b>（校验与消费分离批）：复核阶段拒绝时"可修正后重试"必须为真，
	 * 所以复核器在那里<b>不许</b>动一次性资源；消费/回收走决策后的 settle 钩子，失败只告警。
	 *
	 * <p>次序刻意是"取消 &gt; 复核"：放弃是用户的兜底出口，不该因为"值不合规"而被挡住
	 * （否则用户关掉一张填错一半的表单也被拒，挂起只能等到超时）。
	 *
	 * <p><b>入口 kind 永远第一</b>（入口 kind 校验批）：本入口只改写 {@code kind=FRONTEND} 的条目，
	 * 确认门条目的 toolCallId 发到这里 ⇒ {@link Outcome#TOOL_KIND_MISMATCH}（连
	 * {@code cancelled=true} 也拦 —— 它同样会改写状态）。{@code kind=null} 同样拒绝 + WARN
	 * （缺证据不授权：无法证明它属于前端通道）。
	 *
	 * @param cancelled 前端是否报告"用户主动放弃"（关闭表单/取消对话框）
	 */
	public Submission submitFrontendResult(String runId, String toolCallId, String result, String source,
			boolean cancelled) {
		return submitFrontendResult(runId, toolCallId, result, source, cancelled, null);
	}

	/**
	 * 前端工具结果回灌（P0-1：带可选 {@code outcome}）。
	 *
	 * <p>
	 * {@code outcome} 是前端对自己这次执行结局的自报（{@code ok/failed/rejected/...}）；
	 * 收到后用它推导结局帧的 {@code ok}（缺省/null 保持现行 true 兜底），并随条目持久化，
	 * 供模型信封与历史 toolCallStates 复用。{@code cancelled=true} 时 outcome 被忽略
	 * （放弃是更高的语义优先级）。
	 */
	public Submission submitFrontendResult(String runId, String toolCallId, String result, String source,
			boolean cancelled, String outcome) {
		return submitFrontendResult(runId, toolCallId, result, source, cancelled, outcome, null);
	}

	/**
	 * 前端工具结果回灌（AI 表格工具治理批：带可选的表格采样元数据）。
	 *
	 * <p>{@code sheetMeta} 只承载<b>元数据</b>（行数/字符数/列数/版本号/planId），写过白名单过滤后
	 * 随条目持久化，供 {@code tool_call_end} 的 additive 字段与 {@code ai:safety:} 台账读取。
	 * 它<b>不参与</b>任何判定：判定面（披露/复核/结算）一个字节都没变。
	 */
	public Submission submitFrontendResult(String runId, String toolCallId, String result, String source,
			boolean cancelled, String outcome, Map<String, Object> sheetMeta) {
		return submitFrontendResult(runId, toolCallId, result, source, cancelled, outcome, sheetMeta, null);
	}

	/**
	 * 前端工具结果回灌（批 A：带可选 {@code code}）。
	 *
	 * <p>{@code code} 由前端自报（{@code UI_* 闭集}），已在入口归一；它随条目持久化，
	 * 供 {@code tool_result} 帧 / 台账 / 卡面同源取码（{@link PendingToolCall#effectiveCode()}）。
	 * 未带时保持现状（按状态推导），旧客户端零改动。
	 */
	public Submission submitFrontendResult(String runId, String toolCallId, String result, String source,
			boolean cancelled, String outcome, Map<String, Object> sheetMeta, String code) {
		Submission submission = doSubmitFrontendResult(runId, toolCallId, result, source, cancelled, outcome,
				sheetMeta, code);
		traceDecision(runId, toolCallId, cancelled ? "frontend_cancel" : "frontend_result", submission.pending(),
				String.valueOf(submission.outcome()), "user");
		return submission;
	}

	/**
	 * 把入口已过白名单的表格元数据落到**当前这份**条目副本上（幂等：重复调用不叠加）。
	 *
	 * <p>必须在每次「重新读条目」之后调用一次 —— 外置存储读出来的是新对象，落值不会跟着走。
	 */
	private static void applySheetMeta(PendingToolCall pending, Map<String, Object> sheetMeta) {
		if (pending == null || sheetMeta == null || sheetMeta.isEmpty()) {
			return;
		}
		Map<String, Object> sanitized = SheetAudit.sanitize(sheetMeta);
		if (!sanitized.isEmpty()) {
			pending.setSheetMeta(sanitized);
		}
	}

	/** 回灌的写入本体（{@link #submitFrontendResult} 去掉结构化留痕的那一层）。 */
	private Submission doSubmitFrontendResult(String runId, String toolCallId, String result, String source,
			boolean cancelled, String outcome, Map<String, Object> sheetMeta, String code) {
		PendingToolCall pending = pendingForWrite(runId, toolCallId);
		if (pending == null) {
			return new Submission(Outcome.NOT_FOUND, null, false, null);
		}
		// 入口 kind 校验（与 submitDecision 同一把尺）：错入口在"取消/复核/写 guard"之前就返回 ——
		// 放弃（cancelled=true）也一样，否则确认门条目会被写成"用户放弃了前端表单"。
		if (kindMismatch(pending, PendingToolCall.KIND_FRONTEND, "frontend-tool-result")) {
			return new Submission(Outcome.TOOL_KIND_MISMATCH, pending, false, null);
		}
		if (!PendingToolCall.PENDING.equals(pending.getStatus())) {
			return new Submission(Outcome.DUPLICATE, pending, false, null);
		}
		// AI 表格工具治理批：采样元数据只写过白名单（入口即丢业务值），且不参与任何判定。
		// 位置刻意在"幂等判定之后、取消分支之前"—— 取消分支也要它（写侧取消计数按 planId 走台账），
		// 而重复提交在上一行就已返回，故不会覆盖已落的值。
		applySheetMeta(pending, sheetMeta);
		if (cancelled) {
			return dismiss(runId, pending, source, sheetMeta);
		}
		FrontendToolGuard.Verdict verdict = inspectResult(runId, pending, result);
		if (verdict != null && !verdict.accepted()) {
			// 复核不过<b>不落 guard</b>：状态不变、挂起继续等待，用户修正后还能重试
			// （占了 guard 就再也没有"第二次机会"了 —— 一次不合法的提交不该把卡消费掉）。
			log.warn("前端工具回灌被安全闸拒绝 runId={} toolCallId={} name={} code={}（状态不变，挂起继续等待）：{}",
					runId, toolCallId, pending.getName(), verdict.code(), verdict.reasons());
			return new Submission(Outcome.REJECTED, pending, false, verdict);
		}
		// R4：与 submitDecision 同一套 guard（详见该方法的"全集群唯一写者"）。
		RunStore.DecisionClaim claim = this.store.tryRecordDecision(runId, toolCallId,
				PendingToolCall.FRONTEND_RESULT);
		if (claim != null) {
			if (!claim.won()) {
				return convergeOnDecided(runId, toolCallId, claim.recorded());
			}
			pending = pendingForWrite(runId, toolCallId);
			if (pending == null) {
				return new Submission(Outcome.NOT_FOUND, null, false, null);
			}
			if (!PendingToolCall.PENDING.equals(pending.getStatus())) {
				return new Submission(Outcome.DUPLICATE, pending, false, null);
			}
			// R4 抢到 guard 后重读了条目（真实 Redis = 反序列化出新对象）：白名单副本要在新副本上
			// 再落一次，否则它随旧对象一起被丢弃 —— 后果是 tool_call_end 八字段与 ai:safety 台账
			// 双双缺席，而判定语义完全正常（观测面塌陷，最容易被当成"没数据"而不是"丢了"）。
			applySheetMeta(pending, sheetMeta);
		}
		pending.setStatus(PendingToolCall.FRONTEND_RESULT);
		ToolOutcome declared = ToolOutcome.fromWire(outcome);
		pending.setOutcome(declared == null ? ToolOutcome.OK.wire() : declared.wire());
		// 批 A：前端机器码随条目落库（入口已归一；未带 = null，回落状态推导）。
		pending.setCode(code);
		boolean ok = declared == null || declared.ok();
		pending.setReason("source=" + (source == null || source.isBlank() ? "http-post" : source));
		// G-E：复核器可在放行时补一段**元数据**（附件只暴露 fileName/fileId/size，内容永不进上下文）
		String resultText = result == null ? "" : result;
		if (verdict != null && verdict.resultSuffix() != null) {
			resultText = resultText + verdict.resultSuffix();
		}
		pending.setResultText(resultText);
		pending.setResolvedAtMs(System.currentTimeMillis());
		boolean woke;
		synchronized (this.submitLock) {
			this.store.putPending(runId, pending);
			// ===== 决策侧帧册收口（suspend-kill-a 的 FRONTEND_RESULT 残支）=====
			// 前端卡的结局帧由本方法发出、<b>早于</b>续跑 —— 续跑侧再补只会排在 result 之后，
			// 所以补发点必须在这里：同一把 submitLock 内、发结局帧之前。判据是事件日志（跨实例），
			// 原实例的 tool_start 已在归档则一条不补。写出器取 registry.of(runId) 这同一个身份，
			// 保证补发帧与结局帧走同一条取号/落档/订阅扇出路径。
			// 决策幂等不受影响：走到这里之前 tryRecordDecision 已经决出唯一写者，重复回灌在上面就返回。
			SseChatEmitter out = this.registry.of(runId);
			try {
				if (out.ensureToolStart(pending)) {
					log.info("决策侧补发缺失的 tool_start runId={} toolCallId={} name={}", runId, toolCallId,
							pending.getName());
				}
			}
			catch (RuntimeException ex) {
				// 补发是帧册修复面：失败只 WARN，绝不改变决策语义（落库与唤醒都必须照常完成）
				log.warn("决策侧补发 tool_start 失败（不影响决策）runId={} toolCallId={}：{}", runId, toolCallId,
						ex.getMessage());
			}
			out.frontendToolResult(pending, ok, resultText);
			woke = signal(runId, toolCallId);
		}
		// ===== park 结算：决策写胜者也是候选发点（在 submitLock 之外）=====
		// ① 不判 woke（那是实例内事实，见 settleParkedToolCall 的方法注）：park 开启时本行与源实例
		//    批循环可能先后都到，由 ai:observed 的首写者仲裁；非 park 时结算权始终在等待线程手上。
		// ② 必须在锁外：submitLock 是实例级（跨 run）决策/挂起锁，每次回灌都持锁做一次 Redis 往返
		//    会把无关 run 的决策一起排队（独立复审 I-2）。次序不变：落库 → 结局帧 → 唤醒 之后才发这一行，
		//    再往后才是续跑派发（maybeTriggerResume），因此"帧先于 end、end 先于续跑"仍然成立。
		if (publishesRequestsAheadOfLoop()) {
			settleParkedToolCall(runId, pending, resultText);
		}
		// ===== 校验与消费分离（2026-10-29 修复批）=====
		// 一次性资源（表单附件）的消费挪到这里：结论已落库（上面 putPending 成功）、结局帧已发、
		// 唤醒已完成。放在锁外是因为消费是 Redis + 删盘往返，不得占用实例级的 submitLock。
		// 钩子内部只告警不抛：走到这一步"可修正后重试"已经不可能，任何失败都不能改写结论。
		settleAccepted(runId, pending, result);
		return new Submission(Outcome.ACCEPTED, pending, woke, null, maybeTriggerResume(runId, woke));
	}

	/** 第一个认领该工具名的复核器的回灌判定；没有复核器返回 null（= 该工具不受闸门约束）。 */
	private FrontendToolGuard.Verdict inspectResult(String runId, PendingToolCall pending, String result) {
		for (FrontendToolGuard guard : this.frontendGuards) {
			if (guard.supports(pending.getName())) {
				// GF 优化批 G-E：带上归属上下文（附件"属本表单 + 一次性"校验需要 runId/toolCallId）；
				// 未覆写四参版本的复核器走默认实现，退回两参口径，行为不变。
				return guard.inspectResult(runId, pending.getToolCallId(), pending.getArguments(), result);
			}
		}
		return null;
	}

	/**
	 * 决策落库之后的"接受"钩子（一次性资源消费/回收）：每个工具至多一个复核器认领。
	 *
	 * <p>只在这里（{@code doSubmitFrontendResult} 的末尾，落库/发帧/唤醒都已完成之后）或
	 * {@link #dismiss} 末尾调用；失败只 WARN —— 判定已经定了。
	 */
	private void settleAccepted(String runId, PendingToolCall pending, String result) {
		FrontendToolGuard guard = guardFor(pending.getName());
		if (guard == null) {
			return;
		}
		try {
			guard.settleAcceptedResult(runId, pending.getToolCallId(), pending.getArguments(), result);
		}
		catch (RuntimeException ex) {
			log.warn("决策后的资源收口失败（不影响已落库的结论）toolCallId={} name={}：{}", pending.getToolCallId(),
					pending.getName(), ex.getMessage());
		}
	}

	/** 决策落库之后的"放弃"钩子（同 {@link #settleAccepted} 的口径：只在结论落库后调用）。 */
	private void settleDiscarded(String runId, PendingToolCall pending) {
		FrontendToolGuard guard = guardFor(pending.getName());
		if (guard == null) {
			return;
		}
		try {
			guard.settleDiscardedResult(runId, pending.getToolCallId());
		}
		catch (RuntimeException ex) {
			log.warn("放弃后的资源回收失败（不影响已落库的结论）toolCallId={} name={}：{}", pending.getToolCallId(),
					pending.getName(), ex.getMessage());
		}
	}

	private FrontendToolGuard guardFor(String toolName) {
		for (FrontendToolGuard guard : this.frontendGuards) {
			if (guard.supports(toolName)) {
				return guard;
			}
		}
		return null;
	}

	/**
	 * 挂起期续期：对该轮仍 {@code PENDING} 的条目，逐个调复核器的 {@link FrontendToolGuard#touchSuspension}。
	 *
	 * <p><b>公开</b>是因为它是"挂起续期链"的<b>可复用入口</b>：除本类的心跳（park 关闭时的等待线程）之外，
	 * 读侧（{@code AiController} 的 {@code /confirm}、{@code /frontend-tool-result}、
	 * {@code GET /events/{runId}}、{@code /runs/current}）在与 {@code RunStore#touchActiveRun}
	 * <b>同一位置</b>调用它。park 开启时没有心跳线程，待决条目靠读侧续命 —— 复核器持有的一次性资源
	 * （表单附件登记）必须跟着同一条链续，否则"卡还活着、附件先过期"。
	 *
	 * <p>只续 {@code PENDING}：决策/放弃之后一次性资源已被消费或回收，再续只会把已删的键重新养出来
	 * （Redis {@code EXPIRE} 对缺键虽是无操作，但语义上"已收口就不该再续"）。
	 * <b>不抛</b>：续期是养活性维护，读侧/心跳都不得因它失败而中断，失败只 DEBUG/WARN。
	 */
	public void touchSuspendedResources(String runId) {
		List<PendingToolCall> entries;
		try {
			entries = this.store.pendingsLenient(runId);
		}
		catch (RuntimeException ex) {
			log.debug("挂起期续期读待决条目失败 runId={}：{}", runId, ex.getMessage());
			return;
		}
		if (entries == null || entries.isEmpty()) {
			return;
		}
		for (PendingToolCall entry : entries) {
			if (entry == null || entry.getToolCallId() == null
					|| !PendingToolCall.PENDING.equals(entry.getStatus())) {
				continue;
			}
			FrontendToolGuard guard = guardFor(entry.getName());
			if (guard == null) {
				continue;
			}
			try {
				guard.touchSuspension(runId, entry.getToolCallId());
			}
			catch (RuntimeException ex) {
				log.warn("挂起期续期一次性资源失败（不影响等待）runId={} toolCallId={}：{}", runId,
						entry.getToolCallId(), ex.getMessage());
			}
		}
	}

	/**
	 * 用户主动放弃（关闭/取消表单）：与决策写入同一套次序（落库 → 发结局帧 → 唤醒）。
	 *
	 * <p>为什么要有这个终态：前端工具此前只有"回灌结果"与"超时/整轮取消"出口 ——
	 * 用户关掉表单时前端<b>没有合法动作</b>，挂起只能悬到该工具自己的上限（即时执行类前端工具
	 * {@code app.ai.hitl.frontend-tool-timeout}；{@code generative_form} 自 2026-10-20 起无上限）。
	 * 取消终态把"用户不打算填了"变成一次明确的、立刻的收敛
	 * （且与"没人响应"的超时语义分开，排障时看得清）。
	 */
	private Submission dismiss(String runId, PendingToolCall pending, String source, Map<String, Object> sheetMeta) {
		// R4：放弃同样是"这张卡的结论"，与决策/回灌争同一把 guard（两个页签同时关表单只落一次）
		RunStore.DecisionClaim claim = this.store.tryRecordDecision(runId, pending.getToolCallId(),
				PendingToolCall.FRONTEND_CANCELLED);
		if (claim != null) {
			if (!claim.won()) {
				return convergeOnDecided(runId, pending.getToolCallId(), claim.recorded());
			}
			// 抢到 guard 后<b>复读</b>条目：入参那份是抢 guard 之前读到的副本，中间可能已有"不带 guard
			// 的写者"落了库（前端超时收敛、整轮取消）。旧实现随后无条件把副本改成 FRONTEND_CANCELLED
			// 并发帧 ⇒ 覆盖别人写下的结局 + 同一张卡两帧。非 PENDING 一律按幂等收口：不覆盖、不发帧。
			pending = pendingForWrite(runId, pending.getToolCallId());
			if (pending == null) {
				return new Submission(Outcome.NOT_FOUND, null, false, null);
			}
			if (!PendingToolCall.PENDING.equals(pending.getStatus())) {
				return new Submission(Outcome.DUPLICATE, pending, false, null);
			}
			// 复读出来的是新副本（真实 Redis = 反序列化）：入口已过白名单的表格元数据要在新副本上
			// 再落一次，否则审计计数（writePlans/writeCancelled）会随旧对象一起丢掉。
			applySheetMeta(pending, sheetMeta);
		}
		pending.setStatus(PendingToolCall.FRONTEND_CANCELLED);
		pending.setOutcome(ToolOutcome.CANCELLED.wire());
		pending.setCode(ToolOutcome.CODE_FRONTEND_CANCELLED);
		pending.setReason("FRONTEND_DISMISSED" + (source == null || source.isBlank() ? "" : " source=" + source));
		pending.setExecuted(false);
		pending.setResolvedAtMs(System.currentTimeMillis());
		String text = "FRONTEND_CANCELLED：用户取消了该前端操作（关闭表单/放弃填写），"
				+ "本次调用未获得任何数据；如需继续，请改为向用户询问或换用其它方式。";
		pending.setResultText(text);
		boolean woke;
		synchronized (this.submitLock) {
			this.store.putPending(runId, pending);
			this.registry.of(runId).frontendToolResult(pending, false, text);
			woke = signal(runId, pending.getToolCallId());
		}
		// 与回灌同一口径：锁外发、按 ai:observed 仲裁、次序在结局帧与唤醒之后、续跑派发之前
		if (publishesRequestsAheadOfLoop()) {
			settleParkedToolCall(runId, pending, text);
		}
		// 放弃也是"这张卡的结论"：校验与消费分离批的回收钩子（该表单名下没提交的附件）。
		// 必须在结论落库之后调用 —— 失败只告警，不回滚取消结论。
		settleDiscarded(runId, pending);
		log.info("前端工具被用户取消 runId={} toolCallId={} name={}", runId, pending.getToolCallId(), pending.getName());
		return new Submission(Outcome.ACCEPTED, pending, woke, null, maybeTriggerResume(runId, woke));
	}

	/**
	 * <b>park 结算的观测收口</b>：{@code tool_call_end} + 表格审计计数/台账，恰一次。
	 *
	 * <h2>为什么发在这里</h2>
	 * park 之后"等人/等前端"这件事不再占用线程，工具的结算可能发生在决策落库之后的<b>续跑</b>里
	 * （{@code ResumeService} 重建历史 → 该条目走"复用结果"分支）。那条分支是重挂/重试的公共通道，
	 * 在它那里记账会被放大成"重挂次数"，故<b>不是</b>发点。真正的发点由
	 * {@link com.example.configmgr.ai.observe.SettlementObservationStore} 的首写者仲裁选出：
	 * 本类（决策 / 放弃 / 整轮取消 / 前端回执到期收敛的胜者）、{@code SpToolCallingManager}
	 * 的内联批循环与 abort 结算、续跑认领胜者共抢
	 * {@code ai:observed:<runId>} 的同一 field，抢到者发一行并记一次审计。
	 *
	 * <p><b>覆盖边界（如实登记）</b>：发点现已覆盖 <b>已决 / 放弃 / 整轮取消 / 前端回执到期（park 后
	 * 的 {@code expireFrontend} 收敛，见 {@link #scheduleParkedFrontendExpiry}）/ 执行认领</b> 五类 ——
	 * 超时与取消不再缺席（历史注释曾把它们写成"存量缺口"，已过时）。仍缺席的只有两条存量形态：
	 * ①{@code convergeOnDecided}（决策 guard 已被别的写者持有）<b>刻意不补</b> —— 发点归 guard 持有者，
	 * 胜者若死在"落库 → 结算"之间，该条仍是 0 行；②非 park 且全集群都没有等待方（进程死在等待中、
	 * 尚未续跑），那一行仍缺席，与 park 缺陷之前的存量口径一致。
	 *
	 * <h2>为什么<b>不</b>用 {@code woke} 判发不发（独立复审 I-1 / I-2）</h2>
	 * {@code woke} 是<b>本进程</b>有没有等待方，而"这条调用会不会被别处结算"是<b>跨实例</b>事实：
	 * <ul>
	 * <li>I-1：源实例把整批请求帧前移公布后仍在跑前序后端工具；回灌落到另一个实例
	 * （那边 {@code woke=false}）发了一行，源实例随后走到该卡又发一行 ⇒ 两条 end；</li>
	 * <li>I-2：整批 {@code arm} 预登记让"先答第二张卡"命中（{@code woke=true}）因而本侧不发；
	 * 随后循环在第一张卡处 park，{@code clearWaiterLatches} 把那张<b>已唤醒、尚无人 await</b>
	 * 的闸门一并摘掉 ⇒ 该卡永久 0 条 end。</li>
	 * </ul>
	 * 两条都说明"发不发"必须由<b>持久、跨实例</b>的首写者裁决 —— 即
	 * {@code ai:observed:<runId>}（见 {@link SettlementObservationStore}）。
	 * 于是本侧与批循环、续跑认领胜者谁先到谁发，其余自动让位；续跑的"复用结果"分支仍不参与。
	 *
	 * <h2>为什么仍要 {@code park 开启}</h2>
	 * 关掉 park 时"等人"始终占着一条线程（即使决策落在别的实例，它也会在下一拍轮询到"已决"
	 * 并自己结算）——结算权在等待方，决策侧<b>不参与</b>，这是既有口径。
	 * 判据与 {@code SpToolCallingManager} 前移 {@code tool_start} 的那条同源
	 * （{@link #publishesRequestsAheadOfLoop()}）。
	 *
	 * <p>失败只 WARN：帧册/审计是观测面，不得改变决策语义（决策已落库、结局帧已发）。
	 */
	private void settleParkedToolCall(String runId, PendingToolCall pending, String text) {
		try {
			ToolCallEndTrace.emitForSettledEntry(this.settlementObservations, runId, snapshotSessionId(runId), pending,
					text, this.sheetAudit);
		}
		catch (RuntimeException ex) {
			log.warn("park 结算的 tool_call_end 发射失败（不影响决策）runId={} toolCallId={}：{}", runId,
					pending.getToolCallId(), ex.getMessage());
		}
	}

	/**
	 * <b>整轮取消的 park 结算</b>：把本次取消收敛成 {@code CANCELLED} 的
	 * {@code CONFIRM}/{@code FRONTEND} 条目逐条补一行 {@code tool_call_end}。
	 *
	 * <h2>为什么由本类出这个入口（而不是取消侧自己拼一行）</h2>
	 * park 开启时没有驱动线程：批循环早在 {@code RunParkedException} 处穿出，取消侧
	 * （{@code ResilientChatService#cancelTerminal}）只把条目转 {@code CANCELLED} 并补裁决帧 ——
	 * 这条调用的结算行再没有别的发点。结算的<b>载荷与跨实例仲裁</b>只有一份
	 * （{@link ToolCallEndTrace} + {@code ai:observed}），取消侧需要的正是这一份；
	 * 让它自己构造一遍就是"同一调用两种行"的第二来源。故本类提供入口，取消侧只决定<b>时机</b>。
	 *
	 * <h2>边界（刻意不动）</h2>
	 * <ul>
	 * <li><b>只在 park 开启时结算</b>：park 关闭时等待线程还在，它会在下一拍轮询到取消并自行结算
	 * （{@code SpToolCallingManager} 的批循环）—— 这里再发就是第二条；</li>
	 * <li><b>只结算 CANCELLED 的 {@code CONFIRM}/{@code FRONTEND}</b>：{@code BACKEND} 的结算权
	 * 归执行认领（{@code tryClaimExecution}），且它在 {@link ToolCallEndTrace} 里<b>不走仲裁</b>
	 * （{@code requiresArbitration=false}）—— 由取消侧补发会变成无条件一条，故必须在入口挡掉；
	 * 已终态（回灌/超时/放弃）的条目各有自己的发点，取消不是它们的结论；</li>
	 * <li><b>不为计数虚构元数据</b>：审计只认条目自带的 {@code sheetMeta}。确认卡从来没有 ⇒
	 * 取消/超时的计数为 0 是<b>正确</b>口径；本方法只补"结算行"，不碰计数。</li>
	 * </ul>
	 *
	 * <p>幂等：重复取消仍会走到这里（条目还是 CANCELLED），{@code ai:observed} 的首写者仲裁让
	 * 后来者一条都不发；同一次取消只调用一次本方法，冷装配（无仲裁面）也不会重复。
	 *
	 * @param cancelledBefore 取消<b>之前</b>抓取的条目清单（{@code cancelTerminal} 在
	 *                        {@code cancelPendings} 之前取得）；只有同时出现在当前 CANCELLED 集合里的
	 *                        才结算 —— 防止把上一轮已取消的条目再结算一次
	 * @return 走到结算口的条目数（观测/断言用；真正发出的行数由仲裁决定，可能更少）
	 */
	public int settleCancelledToolCalls(String runId, List<PendingToolCall> cancelledBefore) {
		if (runId == null || cancelledBefore == null || cancelledBefore.isEmpty()
				|| !publishesRequestsAheadOfLoop()) {
			return 0;
		}
		Map<String, PendingToolCall> cancelled = new LinkedHashMap<>();
		for (PendingToolCall pending : this.store.pendingsLenient(runId)) {
			if (pending != null && pending.getToolCallId() != null
					&& PendingToolCall.CANCELLED.equals(pending.getStatus())) {
				cancelled.put(pending.getToolCallId(), pending);
			}
		}
		int settled = 0;
		for (PendingToolCall before : cancelledBefore) {
			PendingToolCall entry = before == null ? null : cancelled.get(before.getToolCallId());
			if (entry == null || !settlesOnPark(entry.getKind())) {
				continue;
			}
			settleParkedToolCall(runId, entry, entry.getResultText());
			settled++;
		}
		return settled;
	}

	/**
	 * 挂起后可能被"决策/放弃/取消/认领"在<b>别处</b>结算的两个通道 —— 与
	 * {@link ToolCallEndTrace} 的仲裁口径同源（{@code BACKEND} 由执行认领独占，不占观测位）。
	 */
	private static boolean settlesOnPark(String kind) {
		return PendingToolCall.KIND_CONFIRM.equals(kind) || PendingToolCall.KIND_FRONTEND.equals(kind);
	}

	/**
	 * 死卡兜底（T3-6a 第 4 方案）：{@code woke=false} 且该轮快照仍是 {@code SUSPENDED} 时，
	 * 触发一次 {@link ResumeService#resume(String)} 把"死卡"收敛为真实续跑。
	 *
	 * <h2>为什么这条路径上一定安全（红队实读的结论）</h2>
	 * {@code StartupResumeRunner} 对 {@code resumeable=false} 的轮<b>只 skip</b>（不落终态、不重建等待循环），
	 * {@code ResumeService} 对仍 {@code PENDING} 的外部项返回 {@code PENDING_UNRESOLVED} 也不驱动 ——
	 * 于是"快照 SUSPENDED + 已无进程等待"在重启后是一个<b>稳态</b>。而此刻外部输入刚到，
	 * 条目已经 {@code APPROVED}/{@code REJECTED}/{@code FRONTEND_RESULT}/{@code FRONTEND_CANCELLED}
	 * （都不是 PENDING），续跑会走正常路径（重建历史 → 执行已决工具），死闸门组合天然消失，
	 * 不需要任何"活闸门检测"（原 S3-7 方案作废）。
	 *
	 * <h2>五个前置判据（缺一不触发）</h2>
	 * <ol>
	 * <li>{@code woke=true} → 本进程的等待方已被唤醒，正常续跑路径已成立（{@code WOKE_IN_PROCESS_GATE}）；</li>
	 * <li>{@link #hasActiveLatch(String)} → 本进程仍有该 runId 的活跃等待
	 *     （R4③ 的毫秒窗：{@code saveSuspended} 写完、latch 还没登记时同进程 confirm 也会看到
	 *     {@code woke=false}）—— 触发会双驱动同一轮（{@code ACTIVE_LATCH_IN_PROCESS}）；</li>
	 * <li>快照存在且状态为 {@code SUSPENDED}（{@code SNAPSHOT_NOT_SUSPENDED}）；</li>
	 * <li>{@link ResumeService} 可用（无 key 的降级装配下 provider 返回 null，{@code NO_RESUME_SERVICE}）；</li>
	 * <li><b>抢到续跑权租约</b>（B23L1 批②b）：等价于"全集群没有别的实例在等/在驱动这一轮"。
	 *     抢不到 ⇒ 不派发（{@code LEASE_HELD_ELSEWHERE}）+ 排一次延迟重试；
	 *     无租约设施（兼容构造/降级装配）⇒ 走原逻辑，不带租约派发。</li>
	 * </ol>
	 *
	 * <p><b>触发点是异步的（A9）</b>：动作交给 {@link #resumeTriggerExecutor}（轻量池，
	 * 容量与拒绝策略见 {@code AiProperties.Resume}），HTTP 线程<b>立即返回</b>；
	 * 池饱和时抛 {@code RejectedExecutionException}，此处<b>吞掉</b>并回
	 * {@code EXECUTOR_SATURATED}（外部输入本身已经落库成功，不该因为"续跑没派出去"而报错）。
	 * {@link RunStore#claimExecution} 的原子认领仍是"工具恰好一次"的最终兜底。
	 *
	 * <p>真实续跑结局由 {@link #runResume} 在执行时打 INFO 日志（ACCEPTED /
	 * ALREADY_RUNNING / LEASE_HELD_ELSEWHERE / PENDING_UNRESOLVED / POOL_SATURATED / 异常都留痕）。
	 */
	private ResumeTrigger maybeTriggerResume(String runId, boolean woke) {
		return triggerResume(runId, woke, true);
	}

	/**
	 * 触发判定与派发（{@link #maybeTriggerResume} 与"抢租失败后的延迟重试"共用）。
	 *
	 * @param allowRetry 抢租失败时是否排一次延迟重试。<b>外部触发路径 true</b>（决策/放弃/回灌、
	 * 前端回执到期裁决、重挂自愈对账 —— 启动扫描不走这里，它直接调 {@code ResumeService}）；
	 * <b>重试任务自身 false</b> —— 一次性重试不会自我
	 * 续命（holder 活着就一直重排会让一个死卡永久占用重试线程），也<b>不 rearm</b> 重派发预算
	 * （{@code allowRetry=true} 是 {@link #dispatchRetries} 唯一的清零点：一个外部触发 = 一个周期）
	 */
	private ResumeTrigger triggerResume(String runId, boolean woke, boolean allowRetry) {
		if (allowRetry) {
			// 外部触发入口：开一个新的重派发周期。放在最前面（即便下面因 woke/latch/状态被跳过），
			// 语义仍是"用户/系统的这次明确动作重新拥有完整预算"。调度器内部续派不会走到这里。
			this.dispatchRetries.remove(runId);
		}
		if (woke) {
			return ResumeTrigger.skipped("WOKE_IN_PROCESS_GATE", null);
		}
		if (hasActiveLatch(runId)) {
			log.debug("死卡兜底不触发：本进程仍有该 runId 的活跃等待 runId={}", runId);
			return ResumeTrigger.skipped("ACTIVE_LATCH_IN_PROCESS", null);
		}
		RunSnapshot snapshot = this.store.get(runId);
		if (snapshot == null || !RunSnapshot.SUSPENDED.equals(snapshot.getStatus())) {
			return ResumeTrigger.skipped("SNAPSHOT_NOT_SUSPENDED",
					snapshot == null ? "快照不存在" : "快照状态 " + snapshot.getStatus());
		}
		if (this.resumeServiceProvider == null || this.resumeTriggerExecutor == null) {
			return ResumeTrigger.skipped("NO_RESUME_SERVICE", "本实例未装配续跑通路");
		}
		if (this.resumeServiceProvider.getIfAvailable() == null) {
			return ResumeTrigger.skipped("NO_RESUME_SERVICE", "续跑服务不可用（无 AI key 的降级装配）");
		}
		// ===== B23L1 批②b：抢续跑权租约（方案第 2 条）=====
		// 只有抢到租约的实例才派发续跑：另一实例在同一轮上已经在驱动（或正在等这张卡）时，
		// 这里的第二次派发会双驱动同一轮。抢不到<b>不影响决策落库</b> —— 决策、回执帧、唤醒
		// 都已在本方法之前完成，这里只决定"要不要把续跑派出去"。
		LeaseService leases = leases();
		if (leases == null) {
			// 无租约设施（6/8 参兼容构造、单测、无 Redis 降级装配）：原逻辑派发，不带租约。
			// 单实例仍然正确（进程内互斥）；跨实例由批②a 的 fence 写与 claimExecution 兜底。
			return dispatchResume(runId, snapshot, null, false);
		}
		String leaseKey = this.store.runLeaseKey(runId);
		// 可重入：本进程可能在等待期（或上一次派发/上一次续跑）已持租 —— 此时"释放/交调度器"的职责
		// 不属于本次调用，释放会把正在等/正在驱动的那一任租约删掉。判据取 {@code created}
		// （脚本告诉我们"这一任是不是我建的"），不再用"抢占前是否持租"去猜：后者在并发下发同时
		// 两边都会读到"未持租"（与 ResumeService 同型的缺陷，见该处的注释）。
		LeaseService.AcquireResult acquired = leases.acquireFenced(leaseKey, this.store.fenceKey(runId),
				this.properties.getLease().getTtl(), null);
		if (acquired.outcome() == LeaseService.AcquireOutcome.UNAVAILABLE) {
			// I4：设施不可用 ⇒ 无租约降级（与"没有租约设施"同口径）：派发不带租约，
			// 由 ResumeService 的进程内互斥兜底。把"不知道"当成"别人持有"会让 Redis 抖一下就停止续跑。
			log.warn("续跑权租约不可用，按无租约降级派发 runId={}", runId);
			return dispatchResume(runId, snapshot, null, false);
		}
		LeaseService.Lease lease = acquired.lease();
		if (lease == null) {
			log.info("死卡兜底不派发 runId={} outcome=LEASE_HELD_ELSEWHERE（另一实例持有续跑租约，"
					+ "决策已落库，由它续跑）", runId);
			if (allowRetry) {
				scheduleLeaseRetry(runId);
			}
			return ResumeTrigger.skipped("LEASE_HELD_ELSEWHERE", "另一实例持有续跑租约");
		}
		return dispatchResume(runId, snapshot, lease, acquired.created());
	}

	/** 把续跑动作交给轻量执行器；池饱和时<b>先还租约</b>再如实回报（A9 口径不变）。 */
	private ResumeTrigger dispatchResume(String runId, RunSnapshot snapshot, LeaseService.Lease lease,
			boolean fresh) {
		// I-5 / 4B：在提交线程（决策回灌的 HTTP 线程；租约重试则是调度线程）取好 locale。
		// 工作线程不继承请求上下文 —— 不显式搬运，en 用户的确认门续跑仍会落到 zh 白名单。
		Locale replyLocale = ErrorBodyFactory.requestLocale();
		try {
			this.resumeTriggerExecutor.execute(() -> {
				// 触发池线程没有请求身份（决策来自 HTTP/定时重试两条入口）：按本轮记录身份建立作用域。
				try (RunIdentityScope.Scope ignored = RunIdentityScope.open(
						snapshot == null ? 0L : snapshot.getTenantId(),
						snapshot == null ? 0L : snapshot.getUserId())) {
					LocaleContextHolder.setLocale(replyLocale);
					try {
						runResume(runId, lease, fresh);
					}
					finally {
						LocaleContextHolder.resetLocaleContext();
					}
				}
			});
		}
		catch (RejectedExecutionException ex) {
			releaseLeaseQuietly(lease, runId, fresh);
			log.warn("死卡兜底续跑未派发（触发池饱和）runId={}：{}", runId, ex.getMessage());
			return ResumeTrigger.skipped("EXECUTOR_SATURATED", "续跑触发池已饱和，本次未派发");
		}
		catch (RuntimeException ex) {
			releaseLeaseQuietly(lease, runId, fresh);
			log.warn("死卡兜底续跑派发失败 runId={}：{}", runId, ex.getMessage());
			return ResumeTrigger.skipped("DISPATCH_FAILED", String.valueOf(ex.getMessage()));
		}
		log.info("死卡兜底触发续跑 runId={}（woke=false 且快照 SUSPENDED，已派发，HTTP 立即返回）", runId);
		return ResumeTrigger.dispatched();
	}

	/**
	 * 该结局是否值得<b>有界重派发</b>：都属于"这一拍没有可确认的驱动者，但既不是终态、
	 * 也不是有人接管"。
	 *
	 * <ul>
	 * <li>{@code POOL_SATURATED} / {@code SESSION_BUSY}：驱动权暂时拿不到（续跑池满 / 同会话另一轮在跑）；</li>
	 * <li>{@code CANCEL_STATE_UNKNOWN}：死卡兜底续跑的入口复查读不出取消标志。{@code CancellationRegistry}
	 *     读失败后进入<b>进程级 5s 退避窗</b>，窗内每一拍都返回 {@code UNKNOWN}（A5：不把"没读 Redis"
	 *     伪装成"未取消"）⇒ 续跑 fail-closed 拒绝。窗口自愈，所以"等下一拍"是有意义的；一次失败就永久
	 *     搁置，会让已结算的前端工具（{@code FRONTEND_RESULT}）没有物化路径（W-21）。</li>
	 * </ul>
	 *
	 * <p><b>为什么不需要改延迟</b>：既有节拍 2s→4s→8s…（累计 2s / 6s / 14s…）的第 2 拍（6s）就已越过
	 * 那个 5s 窗，所以这里只扩结局集合，不动 {@link #dispatchRetryDelay} 与上限。
	 *
	 * <p><b>为什么 {@code STATE_UNKNOWN} 不在此列</b>：它看着同义，但产地是 {@code ResumeService}
	 * 入口那个 {@code catch (RuntimeException)}，同时包住"待决条目读失败（瞬时）"与"条目 JSON 反序列化
	 * 失败（{@code IllegalStateException}，永久数据损坏）"—— 后者重试必然全废，只会刷一串 ERROR 与
	 * 无意义续跑。不可确认 ≠ 等一拍就好；W-21 也不需要它。
	 *
	 * <p>不在此列：{@code PROVIDER_GONE}（换模型重跑会让历史说谎）、{@code NOT_FOUND} /
	 * {@code ALREADY_DONE} / {@code ALREADY_CANCELLED}（已收尾）、{@code ALREADY_RUNNING} /
	 * {@code PENDING_UNRESOLVED} / {@code LEASE_HELD_ELSEWHERE}（已有人管）、{@code ACCEPTED}（成功）、
	 * {@code STATE_UNKNOWN}（可能是永久性条目损坏）。
	 */
	private static boolean isRetriableDispatchOutcome(ResumeService.Outcome outcome) {
		return outcome == ResumeService.Outcome.POOL_SATURATED || outcome == ResumeService.Outcome.SESSION_BUSY
				|| outcome == ResumeService.Outcome.CANCEL_STATE_UNKNOWN;
	}

	/**
	 * 派发失败的一次<b>有界</b>重试（{@link #isRetriableDispatchOutcome}：池满 / 会话占用 /
	 * 取消状态不可确认），间隔按<b>指数退避</b>（I3）。
	 *
	 * <p>与 {@link #scheduleLeaseRetry} 的区别：那条救的是"holder 死了但没释放租约"（等 TTL），
	 * 本条救的是"决策已落库、驱动权暂时拿不到"（池满 / 同会话另一轮在跑）—— 两者都用同一个
	 * 一次性调度器，但计数独立、目的不同。
	 *
	 * <p><b>为什么必须指数退避</b>：一次正常轮可以跑到 {@code app.ai.resilience.total-budget}
	 * （300s），固定 {@code 500ms × 3}（1.5s）覆盖不到任何一次真实轮的收尾 ⇒ 用户已批准的卡
	 * 等于"批准了但永久不执行"。本方法按 {@code dispatch-retry-delay} 起、每次翻倍、
	 * {@code dispatch-retry-max-delay} 封顶（缺省 2s→…→30s，15 次 ≈ 360s，覆盖 300s 轮预算）。
	 *
	 * <p><b>边界（按外部触发周期）</b>：{@code max} 次用尽后把计数钉在 {@code max+1} 并<b>只打一次</b>
	 * ERROR，本周期内不再排期、不再新建预算 —— 包括同一 run 上并发派发出去的其它链，以及已经排在
	 * 调度器里的后续拍。计数<b>不在这里清零</b>：那是外部触发入口（{@link #triggerResume} 的
	 * {@code allowRetry=true} 调用方）的职责，所以"下一次明确交互/重挂会重新计数"仍然成立
	 * （启动扫描直接调 {@code ResumeService}，不经过本入口、不重置预算，但会重新驱动这一轮）。
	 *
	 * <p><b>耗尽即给中性降级结局</b>：这一刻同时调 {@link #endUnresolvedStream} —— 给仍挂着的连接
	 * 发一帧可区分的"结局未确认"（{@code unconfirmed=true} / {@code recoverable=true}）并收流。
	 * 在此之前，重挂的 SSE 会一直挂着等一个永远不会到来的终帧（服务端 emitter 无传输超时）。
	 *
	 * <p><b>临时读不出来不算结局</b>：{@link AiStateUnavailableException}（Redis 断连/超时）在重试拍里
	 * 仍然计入同一份预算并继续下一拍（见调度任务里的 catch），否则一次抖动就会把
	 * {@code CANCEL_STATE_UNKNOWN} 的预算链提前终结。
	 */
	private void retryDispatch(String runId) {
		int max = this.properties.getPark().getMaxDispatchRetries();
		if (max <= 0) {
			return;
		}
		java.util.concurrent.atomic.AtomicInteger counter = this.dispatchRetries.computeIfAbsent(runId,
				ignored -> new java.util.concurrent.atomic.AtomicInteger());
		int attempt = counter.incrementAndGet();
		if (attempt > max) {
			// 钉死（不 remove）：并发链/后续拍看到的是同一份"已耗尽"，不会 computeIfAbsent 出
			// 一整套新预算。只有外部新触发才会清掉这条记录。
			counter.set(max + 1);
			if (attempt == max + 1) {
				log.error("本次触发周期的续跑派发在 {} 次有界重试后仍未成功 runId={}（决策已落库；卡保持 "
						+ "SUSPENDED，下一次明确交互/重挂会重新计数；启动扫描直接调 ResumeService，不重置本预算）",
						max, runId);
				endUnresolvedStream(runId, max);
			}
			return;
		}
		Duration delay = dispatchRetryDelay(attempt);
		try {
			this.retryScheduler.schedule(() -> {
				try {
					// 身份读取与重派发同属这一拍：快照读不出来也必须落在下面的 catch 里
					// （否则异常会穿到调度线程，有界周期断链）。
					RunSnapshot snapshot = this.store.get(runId);
					withRunIdentity(snapshot, () -> {
						ResumeTrigger trigger = triggerResume(runId, false, false);
						if (!trigger.triggered()) {
							// 常见于快照已 DONE / 已有人接管：本链到此为止，但不清计数（清零点只在外部触发）。
							log.debug("续跑重派发未派发 runId={} reason={} detail={}", runId, trigger.outcome(),
									trigger.detail());
						}
					});
				}
				catch (AiStateUnavailableException ex) {
					// 这一拍连"快照是什么状态"都读不出来（Redis 断连/超时）：不是结局，是暂时不可确认。
					// 必须留在同一个有界周期里继续下一拍，否则一次抖动就把 CANCEL_STATE_UNKNOWN 的
					// 预算链提前终结，轮次永久停在 SUSPENDED（W-21 的收尾形态）。
					// 只认这一个类型：值损坏等永久故障仍是 IllegalStateException，落到下面的分支不重试。
					log.warn("续跑重派发遇到状态读取暂时不可用，继续本周期下一拍 runId={}：{}", runId, ex.getMessage());
					retryDispatch(runId);
				}
				catch (Exception ex) {
					log.warn("续跑重派发异常 runId={}：{}", runId, ex.getMessage());
				}
			}, delay);
			log.info("续跑派发失败（池满/会话占用/状态不可确认），第 {}/{} 次有界重试已排期 runId={} delay={}ms",
					attempt, max, runId, delay.toMillis());
		}
		catch (Exception ex) {
			log.warn("续跑有界重试未排上 runId={}：{}", runId, ex.getMessage());
		}
	}

	/**
	 * 预算耗尽的<b>中性降级结局</b>：给仍挂在本轮上的连接发<b>一次</b>可区分的"结局未确认"帧，然后收流。
	 *
	 * <h2>为什么要发（而不是只收流）</h2>
	 * 走到这里的事实是：决策/回灌<b>已落库</b>，但这一轮没有任何可确认的驱动方（取消状态读不出来
	 * ⇒ 续跑 fail-closed），有界重派发已经用尽。连接若只是静静挂着，客户端既拿不到终帧，也拿不到
	 * "这一轮为什么停在这里"的任何信号 —— 只能等自己的超时（服务端 emitter 无传输超时）。
	 *
	 * <h2>为什么不是 done / 不是新的错误体系</h2>
	 * <ul>
	 * <li><b>不是终态</b>：不发 {@code done}（含 {@code cancelled:true}）、不写快照、不改条目 ——
	 *     工具是否执行仍然<b>未知</b>（条目原样：{@code APPROVED} / {@code executed=false} /
	 *     {@code outcome=null}）；</li>
	 * <li><b>不入归档</b>：只投递给当下挂着的连接（{@link SseChatEmitter#unresolvedEnding}）。
	 *     归档里写终帧会把这一轮的帧流判死（回放即收尾、取消收尾跳过 {@code done{cancelled:true}}），
	 *     而这一轮其实仍可被显式恢复；</li>
	 * <li><b>复用既有词汇</b>：帧语义 = 中性的"未确认"（{@code unconfirmed=true}），机器码复用
	 *     {@code AI_REDIS_UNAVAILABLE} + {@code reason=CANCEL_STATE_UNKNOWN}（与
	 *     {@code ResumeService} 的取消状态不可确认结局同一口径）。</li>
	 * </ul>
	 *
	 * <p><b>一次</b>由调用方的 {@code attempt == max+1} 保证：同一周期的其它链、以及已排期任务的
	 * 后续拍都只看到"已耗尽"，不会再发第二帧。
	 *
	 * <p>没有连接时什么都不做（没有要解开的等待，也不留归档痕迹）；下一次重挂/显式恢复会重新拥有
	 * 完整预算并重新派发。
	 */
	private void endUnresolvedStream(String runId, int attempts) {
		SseChatEmitter out = this.registry.find(runId);
		if (out == null || out.subscriberCount() == 0) {
			return;
		}
		try {
			if (Boolean.TRUE.equals(out.terminalFrameArchived())) {
				// 归档里已有终帧（另一条路径已收尾）：交给它，不补第二帧
				out.completeSubscriptions();
				return;
			}
			Map<String, Object> extra = new LinkedHashMap<>();
			extra.put("reason", "CANCEL_STATE_UNKNOWN");
			extra.put("unconfirmed", true);
			extra.put("retryable", true);
			extra.put("degraded", true);
			extra.put("recoverable", true);
			extra.put("status", RunSnapshot.SUSPENDED);
			extra.put("attempts", attempts);
			int delivered = out.unresolvedEnding(ErrorCodes.AI_REDIS_UNAVAILABLE, resumeUnresolvedMessage(), extra);
			log.info("续跑派发预算耗尽：已向 {} 条连接发出中性降级结局并收流 runId={}（快照保持 {}，可显式恢复）",
					delivered, runId, RunSnapshot.SUSPENDED);
		}
		catch (RuntimeException ex) {
			log.warn("续跑派发预算耗尽的降级结局投递失败 runId={}（仍收流，不悬挂连接）：{}", runId, ex.getMessage());
			out.completeSubscriptions();
		}
	}

	/** 预算耗尽时用户面的中性短句（双语各一份；不声称成功/失败/取消，也不新增错误码）。 */
	private static String resumeUnresolvedMessage() {
		return ReplyLanguage.current().english()
				? "Your decision was recorded, but this turn could not be resumed just now. The outcome is not "
						+ "confirmed yet and the turn can still be recovered."
				: "决定已记录，但这一轮此刻未能继续执行；结果尚未确认，本轮仍可恢复。";
	}

	/**
	 * 轮终态的进程内清理（N5）：只清本 run 的<b>派发重试计数</b>（{@link #dispatchRetries}）。
	 *
	 * <p>为什么需要：计数按"外部触发周期"保留（耗尽后钉死，见该字段注释），终态之后没有任何
	 * 外部触发会再来清它 —— 不清理就是一个按 runId 常驻的 {@code AtomicInteger}。
	 *
	 * <p><b>幂等且边界刻意很窄</b>：
	 * <ul>
	 * <li>未清理过 / 已清理 / {@code runId=null} 都不报错、不改变其它状态；</li>
	 * <li>只动这一个计数 —— <b>不碰</b>租约（{@code LeaseService} 的 ended 墓碑/epoch）、
	 *     不碰 Redis 帧册、不碰挂起条目与 {@code latches}：终态的权威事实仍是 Redis 那一份，
	 *     进程内清理不得改写"这一轮是否已结束"的判定；</li>
	 * <li>清理只意味着"下一次外部触发重新拥有完整重派发预算"，不会派发、不会唤醒、不会发帧。</li>
	 * </ul>
	 *
	 * <p>调用时机由终态收尾方（{@code CancelFinalizer} / 终态写成功之后）决定，本类不做触发。
	 */
	public void clearRunState(String runId) {
		if (runId == null || runId.isBlank()) {
			return;
		}
		this.dispatchRetries.remove(runId);
	}

	/** 第 {@code attempt} 次重派发的间隔：{@code base × 2^(attempt-1)}，按 {@code max-delay} 封顶。 */
	private Duration dispatchRetryDelay(int attempt) {
		Duration base = this.properties.getPark().getDispatchRetryDelay();
		if (base == null || base.isNegative() || base.isZero()) {
			base = Duration.ofSeconds(2);
		}
		Duration cap = this.properties.getPark().getDispatchRetryMaxDelay();
		if (cap == null || cap.isNegative() || cap.isZero()) {
			cap = Duration.ofSeconds(30);
		}
		long capMs = Math.max(1L, cap.toMillis());
		long delayMs = Math.max(1L, base.toMillis());
		for (int i = 1; i < attempt && delayMs < capMs; i++) {
			delayMs = Math.min(capMs, delayMs * 2);
		}
		return Duration.ofMillis(delayMs);
	}

	/**
	 * 定时重试线程没有请求身份：按本轮记录打开身份作用域后执行一次动作
	 * （快照 0/0 = 旧轮次，无身份可恢复 ⇒ 空作用域，不伪造默认租户）。
	 */
	private static void withRunIdentity(RunSnapshot snapshot, Runnable action) {
		long tenantId = snapshot == null ? 0L : snapshot.getTenantId();
		long userId = snapshot == null ? 0L : snapshot.getUserId();
		try (RunIdentityScope.Scope ignored = RunIdentityScope.open(tenantId, userId)) {
			action.run();
		}
	}

	/**
	 * 抢租失败后的一次性延迟重试（{@code app.ai.lease.retry-delay}，默认 35s = TTL + 5s 余量）。
	 *
	 * <p>救的是"holder 已经死了但它没来得及释放"的场景：TTL 到期后本实例就能接管。
	 * holder 还活着时重试同样无害 —— 抢租仍失败，走 {@code allowRetry=false} 的静默分支，
	 * 不再排下一次（否则一张死卡会永久占用重试线程）。
	 */
	private void scheduleLeaseRetry(String runId) {
		try {
			this.retryScheduler.schedule(() -> {
				try {
					RunSnapshot snapshot = this.store.get(runId);
					withRunIdentity(snapshot, () -> triggerResume(runId, false, false));
				}
				catch (Exception ex) {
					log.warn("续跑租约重试异常 runId={}：{}", runId, ex.getMessage());
				}
			}, this.properties.getLease().getRetryDelay());
		}
		catch (Exception ex) {
			log.warn("续跑租约重试未排上 runId={}：{}", runId, ex.getMessage());
		}
	}

	/**
	 * 触发任务本体：调 {@link ResumeService#resume(String, boolean, LeaseService.Lease)} 并把结局打
	 * INFO 日志留痕（A9 的"饱和/结局可见"）。
	 *
	 * <p><b>租约职责</b>：{@code fresh=true}（本次抢到的）才由这里收尾 —— 结局 {@code ACCEPTED}
	 * 交续租调度器（终态由 {@code RunStore.save} 释放）；其余结局按 {@link ResumeService.Outcome#releasesLease()}
	 * 的<b>归还白名单</b>决定（L-19：{@code IN_PROGRESS} / {@code LEASE_HELD_ELSEWHERE} 有人正在用这一任 ⇒
	 * 不还，其余"确认无人接管"的结局当场还）。{@code fresh=false} 表示租约本来就在本进程手里
	 * （等待方/上一次派发），本次不动它。
	 */
	private void runResume(String runId, LeaseService.Lease lease, boolean fresh) {
		try {
			ResumeService service = this.resumeServiceProvider.getIfAvailable();
			if (service == null) {
				log.info("死卡兜底续跑结局 runId={} outcome=NO_RESUME_SERVICE", runId);
				releaseLeaseQuietly(lease, runId, fresh);
				return;
			}
			// F8 第 3 步：派发（HTTP 线程）与执行（触发池）之间仍有排队窗口 —— 此处再复查一次，
			// 本进程已有活跃等待就放弃，避免与"帧发出后才进入等待"的原循环双驱动同一轮。
			if (hasActiveLatch(runId)) {
				// L-19 同族：这里<b>不还租</b>。排队窗口里出现的等待方（帧已发、await 刚登记）
				// 刚刚以可重入方式拿到了同一任租约 —— 它才是现在管这一轮的人。还了就等于在
				// "等待方持租"的时刻把它的租约删掉（它随后交棒时 fencing 写会退化成 plain SET）。
				log.info("死卡兜底续跑放弃 runId={} outcome=ACTIVE_LATCH_IN_PROCESS（执行时复查到本进程仍有活跃等待；"
						+ "租约留给等待方，不还）", runId);
				return;
			}
			// 续跑原因经 MDC 标注（不改 resume(...) 的调用形状：既有用例的 verify(resume(runId)) 零改动）
			AiTrace.markResumeReason(ResumeService.RESUME_REATTACH);
			ResumeService.ResumeResult result;
			try {
				result = lease == null ? service.resume(runId) : service.resume(runId, false, lease);
			}
			finally {
				AiTrace.clearResumeReason();
			}
			log.info("死卡兜底续跑结局 runId={} outcome={} body={}", runId,
					result == null ? null : result.outcome(), result == null ? null : result.body());
			// park 批：池饱和 / 会话被占 = "决策已落库但这一拍没有驱动者" ⇒ 有界重派发。
			// W-21：取消状态"此刻不可确认"同属这一类（不是终态、也不是有人接管）——
			// 一次失败就永久搁置会让已结算的前端工具没有物化路径（STATE_UNKNOWN 不在其列：可能是
			// 永久性条目损坏，见 isRetriableDispatchOutcome）。
			// 注意：这里<b>不清零</b>计数（成功/终态也不清）—— 清零点只有外部触发入口，
			// 否则耗尽的链会被"某个结局"悄悄续命（见 dispatchRetries 字段注释）。
			if (result != null && isRetriableDispatchOutcome(result.outcome())) {
				retryDispatch(runId);
			}
			if (lease == null) {
				return;
			}
			if (result != null && result.outcome() == ResumeService.Outcome.ACCEPTED) {
				if (fresh) {
					// R2：与 handOffLease 同形的存活判据（快照非终态 ∧ 本进程仍是该 epoch 主人）。
					// 恒真续租在"驱动任务死了却没写终态"时会永久攥着租约，接管方只能干等。
					markDrivingGuarded(lease, runId);
				}
			}
			else if (result != null && result.outcome().releasesLease()) {
				// L-19：与 ResumeService 同一条<b>归还白名单</b>（{@link ResumeService.Outcome#releasesLease}）。
				// IN_PROGRESS = 本进程有胜方在用<b>同一 token</b>，LEASE_HELD_ELSEWHERE = 租约已归别人 ——
				// 两者都<b>不还</b>。派发失败（EXECUTOR_SATURATED）与执行异常仍还租：
				// 那是"确认没人接管"的两条路径（与 ResumeService 池饱和同口径的红线）。
				releaseLeaseQuietly(lease, runId, fresh);
			}
			else if (result == null) {
				log.warn("死卡兜底续跑未返回结局 runId={}（按'有人接管'处理：不还租，最多等 TTL）", runId);
			}
		}
		catch (AiStateUnavailableException ex) {
			// ResumeService 内部同类临时读取失败（审批条目/快照/fence）：同样只是"这一拍不可确认"，
			// 不是结局。放租后留在同一个有界周期里继续 —— 否则轮次停在 SUSPENDED 且无人再驱动。
			log.warn("死卡兜底续跑遇到状态读取暂时不可用 runId={}（已排期本周期下一拍）：{}", runId, ex.getMessage());
			releaseLeaseQuietly(lease, runId, fresh);
			retryDispatch(runId);
		}
		catch (Exception ex) {
			log.error("死卡兜底续跑异常 runId={}", runId, ex);
			releaseLeaseQuietly(lease, runId, fresh);
		}
	}

	/** 还没交棒时归还租约（{@code fresh=false} = 租约不归本次调用管，不动它）。 */
	private void releaseLeaseQuietly(LeaseService.Lease lease, String runId, boolean fresh) {
		if (lease == null || !fresh) {
			return;
		}
		LeaseService leases = leases();
		if (leases == null) {
			return;
		}
		try {
			leases.release(lease);
		}
		catch (Exception ex) {
			log.debug("派发路径释放续跑租约失败 runId={} epoch={}：{}", runId, lease.epoch(), ex.getMessage());
		}
	}

	/**
	 * 本进程是否有该 runId 的活跃等待闸门（T3-6a / A8；只读、不加锁外状态、不改 {@code latches} 语义）。
	 *
	 * <p>判据是<b>前缀</b>而不是 toolCallId：一次挂起可能有多个工具调用（多闸门），
	 * 且"防双驱动"关心的是"这一轮还有人在等"，与具体是哪个 toolCallId 无关。
	 */
	public boolean hasActiveLatch(String runId) {
		if (runId == null) {
			return false;
		}
		String prefix = runId + "|";
		for (String key : this.latches.keySet()) {
			if (key.startsWith(prefix)) {
				return true;
			}
		}
		return false;
	}

	/** 唤醒本进程内正在等待该 toolCallId 的闸门；无等待方返回 false（不报错）。 */
	public boolean signal(String runId, String toolCallId) {
		Latch latch = this.latches.get(key(runId, toolCallId));
		if (latch == null) {
			return false;
		}
		latch.signal();
		return true;
	}

	/**
	 * <b>park 批</b>：人工等待 park 之后本进程没有等待方，主动 stop / 运维催办必须能推动收敛。
	 *
	 * <p>走的就是死卡兜底同一条判定（{@code woke=false}）：快照仍 SUSPENDED 才派发续跑；
	 * 续跑侧会先看到取消标志（{@code ResumeService} 的早退分支）把快照收敛成
	 * {@code CANCELLED} 并取消未决条目 —— 于是"停"不必等下一次用户交互才生效。
	 *
	 * <p>幂等/安全：没有可派发对象（非 SUSPENDED / 无续跑服务 / 租约在别处）时只返回 skipped，
	 * 不改任何状态。
	 */
	public ResumeTrigger triggerResumeForParked(String runId) {
		return triggerResume(runId, false, true);
	}

	/** 供诊断/取证：当前进程内有几个挂起闸门。 */
	public int pendingGates() {
		return this.latches.size();
	}

	private static String key(String runId, String toolCallId) {
		return runId + "|" + toolCallId;
	}

	/** 进程内闸门：只做唤醒，不承载状态。 */
	private static final class Latch {

		private final CountDownLatch latch = new CountDownLatch(1);

		boolean await(long millis) throws InterruptedException {
			return this.latch.await(millis, TimeUnit.MILLISECONDS);
		}

		void signal() {
			this.latch.countDown();
		}

	}

}
