package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.form.GenerativeFormRules;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.LeaseService;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.ResumeService;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tool.FrontendToolGuard;
import com.example.configmgr.ai.tool.ToolOutcome;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
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
 * 确定性操作（确认门）与 {@code generative_form} 的等待<b>不再有超时</b>：不再有 240s/120s 的死线，
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
 * <h2>挂起期心跳保活</h2>
 * 等待循环每 {@value #HEARTBEAT_SECONDS} 秒发一帧 {@code heartbeat}（带挂起种类与已等待秒数）：
 * ① 保活 SSE；② 续期轮键与 session 索引；③ 实测证据里"挂起期确实还有帧"由此可证。
 * 心跳<b>不再携带</b>等待上限（确认门/表单卡没有到期时刻，前端据此不武装倒计时）。
 *
 * <h2>刷新恢复口径（L0，2026-10-21 用户直裁）</h2>
 * 一批多调用的请求帧是<b>逐条</b>发的，{@code saveSuspended} 却把整批条目一起外置 ⇒「条目存在」不等于
 * 「卡已送达」。两条口径由此而定：①<b>发帧前先置位</b>（{@link RunStore#markRequestEmitted} 落
 * {@code req=true} + 时刻），前端刷新只为 {@code req=true} 的 {@code CONFIRM}/{@code FRONTEND} 条目建卡；
 * ②<b>已决则不补发</b>（{@link #reserveRequestSlot}）：条目在到达 {@code await} 前已被决策时，跳过请求帧
 * 与挂起断连、直接消费既有决策 —— 否则前端会把已收敛的卡瞬时复活再被收敛。实时路径（发帧与 handler）不受影响。
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
	 * <li>{@code generative_form}：等的是人填表 ⇒ 无上限（与确认门同一路径，按<b>工具名</b>区分）；</li>
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
		if (GenerativeFormRules.TOOL_NAME.equals(pending.getName())) {
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
		LeaseService.Lease lease = leases == null ? null
				: leases.tryAcquire(this.store.runLeaseKey(runId), this.properties.getLease().getTtl()).orElse(null);
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
		if (confirm) {
			// 确认卡不再有到期时刻（裁决第 1 条）：帧里不带 timeoutSeconds/expiresAt，
			// 前端据此不武装倒计时、不把按钮置灰。
			out.confirmRequest(pending);
		}
		else {
			out.frontendToolRequest(pending, timeoutSeconds);
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
		// 一批多 toolCall 的真实发射流（实测）：帧是**逐条**发的 —— suspended 帧一次列出本批全部
		// 条目，随后 SpToolCallingManager 逐个进 await；每个 await 各自发自己那张卡的请求帧，
		// 发完即断，然后阻塞等人。于是第 N 张卡的请求帧是在第 N-1 张卡决策之后、重新连上的
		// 订阅里发出的（重连帧序：决策 → 续跑 → tool_start(cardN) → 请求帧(cardN) → 再次收流）。
		// 任何一张卡的请求帧都落在"该帧 emit 之后"的断连点之后，不会被截断。
		out.completeSubscriptions();
		long startedAt = System.currentTimeMillis();
		// T2b-D#1：挂起期续期 session→current 索引用的 sessionId（快照可能缺失，取一次缓存并判空）
		String sessionId = snapshotSessionId(runId);
		// 人机预算分离（裁决第 3 条）：等待窗口内的时间累计到信标上，总预算判据据此扣除
		this.beacon.beginGateWait(runId);
		try {
			while (true) {
				if (this.cancellations.isCancelled(runId)) {
					return cancel(runId, pending, confirm, startedAt);
				}
				PendingToolCall fresh;
				// S5c-6：与提交侧读同一把锁 —— 提交方在锁内完成"落库 + 发回执帧 + 唤醒"，
				// 因此这里读到的"已决"状态一定伴随着已经写出去的回执帧。
				synchronized (this.submitLock) {
					fresh = this.store.pending(runId, pending.getToolCallId());
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
			PendingToolCall fresh = this.store.pending(runId, pending.getToolCallId());
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
			try {
				cancelHandle.close();
			}
			catch (Exception ex) {
				log.debug("注销取消监听者失败 runId={}：{}", runId, ex.getMessage());
			}
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

	/** 租约键 → runId（键形如 {@code ai:lease:run:<runId>}；不匹配返回 null = 判据退化为恒真）。 */
	private static String runIdOf(LeaseService.Lease lease) {
		String key = lease == null ? null : lease.key();
		String prefix = RunStore.LEASE_RUN_PREFIX;
		return key != null && key.startsWith(prefix) ? key.substring(prefix.length()) : null;
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
			PendingToolCall fresh = this.store.pending(runId, pending.getToolCallId());
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
	 * 取消落地：把仍 {@code PENDING} 的挂起条目标为
	 * {@link PendingToolCall#CANCELLED}（未执行），发一帧与人工决策同形的结局帧，
	 * 然后由 {@code SpToolCallingManager} 在事件边界终止整轮（终态 {@code done{cancelled:true}}）。
	 */
	private PendingToolCall cancel(String runId, PendingToolCall pending, boolean confirm, long startedAt) {
		PendingToolCall fresh = this.store.pending(runId, pending.getToolCallId());
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
		PendingToolCall fresh = this.store.pending(runId, pending.getToolCallId());
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
		return fresh;
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
	 * 人工决策：<b>抢决策 guard</b> → 先写 Redis，再发回执帧，最后唤醒（帧先于它唤起的后果）。
	 * 重复提交按状态幂等拒绝（SP-01d V-d2B）。
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
	public Submission submitDecision(String runId, String toolCallId, boolean approved, String reason) {
		PendingToolCall pending = pendingForWrite(runId, toolCallId);
		if (pending == null) {
			return new Submission(Outcome.NOT_FOUND, null, false, null);
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
		return new Submission(Outcome.ACCEPTED, pending, woke, null, maybeTriggerResume(runId, woke));
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
	 * <li><b>通过</b> → 既有口径：{@link PendingToolCall#FRONTEND_RESULT} + 回灌帧 + 唤醒。</li>
	 * </ol>
	 *
	 * <p>次序刻意是"取消 &gt; 复核"：放弃是用户的兜底出口，不该因为"值不合规"而被挡住
	 * （否则用户关掉一张填错一半的表单也被拒，挂起只能等到超时）。
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
		PendingToolCall pending = pendingForWrite(runId, toolCallId);
		if (pending == null) {
			return new Submission(Outcome.NOT_FOUND, null, false, null);
		}
		if (!PendingToolCall.PENDING.equals(pending.getStatus())) {
			return new Submission(Outcome.DUPLICATE, pending, false, null);
		}
		if (cancelled) {
			return dismiss(runId, pending, source);
		}
		FrontendToolGuard.Verdict verdict = inspectResult(pending, result);
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
		}
		pending.setStatus(PendingToolCall.FRONTEND_RESULT);
		ToolOutcome declared = ToolOutcome.fromWire(outcome);
		pending.setOutcome(declared == null ? ToolOutcome.OK.wire() : declared.wire());
		pending.setCode(null);
		boolean ok = declared == null || declared.ok();
		pending.setReason("source=" + (source == null || source.isBlank() ? "http-post" : source));
		pending.setResultText(result == null ? "" : result);
		pending.setResolvedAtMs(System.currentTimeMillis());
		boolean woke;
		synchronized (this.submitLock) {
			this.store.putPending(runId, pending);
			this.registry.of(runId).frontendToolResult(pending, ok, result == null ? "" : result);
			woke = signal(runId, toolCallId);
		}
		return new Submission(Outcome.ACCEPTED, pending, woke, null, maybeTriggerResume(runId, woke));
	}

	/** 第一个认领该工具名的复核器的回灌判定；没有复核器返回 null（= 该工具不受闸门约束）。 */
	private FrontendToolGuard.Verdict inspectResult(PendingToolCall pending, String result) {
		for (FrontendToolGuard guard : this.frontendGuards) {
			if (guard.supports(pending.getName())) {
				return guard.inspectResult(pending.getArguments(), result);
			}
		}
		return null;
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
	private Submission dismiss(String runId, PendingToolCall pending, String source) {
		// R4：放弃同样是"这张卡的结论"，与决策/回灌争同一把 guard（两个页签同时关表单只落一次）
		RunStore.DecisionClaim claim = this.store.tryRecordDecision(runId, pending.getToolCallId(),
				PendingToolCall.FRONTEND_CANCELLED);
		if (claim != null && !claim.won()) {
			return convergeOnDecided(runId, pending.getToolCallId(), claim.recorded());
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
		log.info("前端工具被用户取消 runId={} toolCallId={} name={}", runId, pending.getToolCallId(), pending.getName());
		return new Submission(Outcome.ACCEPTED, pending, woke, null, maybeTriggerResume(runId, woke));
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
	 * @param allowRetry 抢租失败时是否排一次延迟重试。派发路径 true；<b>重试任务自身 false</b> ——
	 * 一次性重试不会自我续命（holder 活着就一直重排会让一个死卡永久占用重试线程）
	 */
	private ResumeTrigger triggerResume(String runId, boolean woke, boolean allowRetry) {
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
			return dispatchResume(runId, null, false);
		}
		String leaseKey = this.store.runLeaseKey(runId);
		// 可重入：本进程可能在等待期（或上一次派发/上一次续跑）已持租 —— 此时"释放/交调度器"的职责
		// 不属于本次调用，释放会把正在等/正在驱动的那一任租约删掉。判据取 {@code created}
		// （脚本告诉我们"这一任是不是我建的"），不再用"抢占前是否持租"去猜：后者在并发下发同时
		// 两边都会读到"未持租"（与 ResumeService 同型的缺陷，见该处的注释）。
		LeaseService.AcquireResult acquired = leases.acquire(leaseKey, this.properties.getLease().getTtl());
		if (acquired.outcome() == LeaseService.AcquireOutcome.UNAVAILABLE) {
			// I4：设施不可用 ⇒ 无租约降级（与"没有租约设施"同口径）：派发不带租约，
			// 由 ResumeService 的进程内互斥兜底。把"不知道"当成"别人持有"会让 Redis 抖一下就停止续跑。
			log.warn("续跑权租约不可用，按无租约降级派发 runId={}", runId);
			return dispatchResume(runId, null, false);
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
		return dispatchResume(runId, lease, acquired.created());
	}

	/** 把续跑动作交给轻量执行器；池饱和时<b>先还租约</b>再如实回报（A9 口径不变）。 */
	private ResumeTrigger dispatchResume(String runId, LeaseService.Lease lease, boolean fresh) {
		try {
			this.resumeTriggerExecutor.execute(() -> runResume(runId, lease, fresh));
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
					triggerResume(runId, false, false);
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
			ResumeService.ResumeResult result = lease == null ? service.resume(runId)
					: service.resume(runId, false, lease);
			log.info("死卡兜底续跑结局 runId={} outcome={} body={}", runId,
					result == null ? null : result.outcome(), result == null ? null : result.body());
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
