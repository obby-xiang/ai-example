package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.ResumeService;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tool.FrontendToolGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * 确认门状态机（S4.2 §2 {@code gate/ConfirmGate}）：放行 / 拒绝（原因回填）/ 超时自动取消。
 *
 * <h2>三结局</h2>
 * <ul>
 * <li><b>放行</b>：{@code POST /api/ai/confirm {approved:true}} → Redis 里
 * {@code status=APPROVED, executed=false} → 唤醒循环内的等待 → 由循环执行工具并记台账；</li>
 * <li><b>拒绝</b>：{@code status=REJECTED}，结果文本回填"用户拒绝 + 原因"，<b>不执行</b>，
 * 官方循环照常继续（模型如实解释）；</li>
 * <li><b>超时</b>：等待超过 {@code app.ai.hitl.timeout}（默认 120s，ADR-2 上限约束）
 * 自动取消：{@code status=TIMEOUT, executed=false}，同样以"未执行"语义回填。</li>
 * </ul>
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
 * ① 保活 SSE；② 前端据此显示"等待确认中"；③ 实测证据里"挂起期确实还有帧"由此可证。
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

	/** toolCallId → 进程内唤醒闸门（不承载状态）。 */
	private final Map<String, Latch> latches = new ConcurrentHashMap<>();

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

	public ConfirmGate(RunStore store, RunRegistry registry, AiProperties properties,
			CancellationRegistry cancellations, ToolActivityBeacon beacon, List<FrontendToolGuard> frontendGuards) {
		this(store, registry, properties, cancellations, beacon, frontendGuards, null, null);
	}

	/**
	 * Spring 装配路径（T3-6a）：多出 {@code ObjectProvider<ResumeService>}（惰性，解环）与
	 * 续跑触发的轻量执行器（{@code aiResumeTrigger}，见 {@link #maybeTriggerResume}）。
	 */
	@Autowired
	public ConfirmGate(RunStore store, RunRegistry registry, AiProperties properties,
			CancellationRegistry cancellations, ToolActivityBeacon beacon, List<FrontendToolGuard> frontendGuards,
			ObjectProvider<ResumeService> resumeServiceProvider,
			@Qualifier("aiResumeTrigger") Executor resumeTriggerExecutor) {
		this.store = store;
		this.registry = registry;
		this.properties = properties;
		this.cancellations = cancellations;
		this.beacon = beacon;
		this.frontendGuards = frontendGuards == null ? List.of() : List.copyOf(frontendGuards);
		this.resumeServiceProvider = resumeServiceProvider;
		this.resumeTriggerExecutor = resumeTriggerExecutor;
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

	/** 确认门等待上限（秒）：{@code app.ai.hitl.timeout}。 */
	public int confirmTimeoutSeconds() {
		Duration timeout = this.properties.getHitl().getTimeout();
		return (int) Math.max(1, timeout.toSeconds());
	}

	// ── 等待侧（在官方循环内阻塞） ──────────────────────────────────────────

	/**
	 * 确认门：阻塞等人工决策，返回 Redis 里的<b>最终条目</b>（调用方据此决定执行与否）。
	 */
	public PendingToolCall awaitDecision(String runId, PendingToolCall pending) {
		return await(runId, pending, confirmTimeoutSeconds(), true);
	}

	/**
	 * 前端工具：阻塞等前端回灌结果。等待上限与确认门同用一个键
	 * （{@code app.ai.hitl.timeout}）—— 规格 §4 只定义了这一个"挂起等待上限"键。
	 */
	public PendingToolCall awaitFrontendResult(String runId, PendingToolCall pending) {
		return await(runId, pending, confirmTimeoutSeconds(), false);
	}

	private PendingToolCall await(String runId, PendingToolCall pending, int timeoutSeconds, boolean confirm) {
		String key = key(runId, pending.getToolCallId());
		Latch latch = new Latch();
		this.latches.put(key, latch /* 先登记闸门再发请求，避免"外部输入已到、闸门还没登记"白等到超时 */);
		// 取消的加速通道（ADR-8 修正③）：同进程取消立即解开等待，权威态仍在 ai:cancel:<runId>。
		// 跨进程取消则由下方循环里的 isCancelled 轮询发现（节拍 = 心跳节拍，属"分片边界"）。
		AutoCloseable cancelHandle = this.cancellations.onCancel(runId, latch::signal);
		SseChatEmitter out = this.registry.of(runId);
		if (confirm) {
			out.confirmRequest(pending, timeoutSeconds);
		}
		else {
			out.frontendToolRequest(pending, timeoutSeconds);
		}
		long startedAt = System.currentTimeMillis();
		long deadline = startedAt + timeoutSeconds * 1000L;
		// T2b-D#1：挂起期续期 session→current 索引用的 sessionId（快照可能缺失，取一次缓存并判空）
		String sessionId = snapshotSessionId(runId);
		try {
			while (true) {
				if (this.cancellations.isCancelled(runId)) {
					return cancel(runId, pending, timeoutSeconds, confirm);
				}
				PendingToolCall fresh;
				// S5c-6：与提交侧读同一把锁 —— 提交方在锁内完成"落库 + 发回执帧 + 唤醒"，
				// 因此这里读到的"已决"状态一定伴随着已经写出去的回执帧。
				synchronized (this.submitLock) {
					fresh = this.store.pending(runId, pending.getToolCallId());
				}
				if (fresh != null && fresh.resolved()) {
					// 决策可能来自本进程（闸门唤醒）也可能来自另一实例（Redis 轮询发现）
					return fresh;
				}
				long remain = deadline - System.currentTimeMillis();
				if (remain <= 0) {
					return expire(runId, pending, timeoutSeconds, confirm);
				}
				boolean signalled = latch.await(Math.min(remain, HEARTBEAT_SECONDS * 1000L));
				if (!signalled) {
					// 工具活动脉冲（韧性看门狗据此在挂起期不计静默，硬规范①）
					this.beacon.pulse(runId);
					// T2b-D#1：与心跳同节拍续期 session→current 索引（TTL 对齐会话锁 watchdog）
					if (sessionId != null) {
						this.store.renewSessionCurrent(sessionId, runId);
					}
					out.heartbeat(heartbeatData(runId, pending, confirm, timeoutSeconds,
							(System.currentTimeMillis() - startedAt) / 1000));
				}
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			log.warn("挂起等待被中断 runId={} toolCallId={}", runId, pending.getToolCallId());
			return expire(runId, pending, timeoutSeconds, confirm);
		}
		finally {
			this.latches.remove(key, latch);
			try {
				cancelHandle.close();
			}
			catch (Exception ex) {
				log.debug("注销取消监听者失败 runId={}：{}", runId, ex.getMessage());
			}
		}
	}

	/**
	 * 取消落地：把仍 {@code PENDING} 的挂起条目标为
	 * {@link PendingToolCall#CANCELLED}（未执行），发一帧与人工决策同形的结局帧，
	 * 然后由 {@code SpToolCallingManager} 在事件边界终止整轮（终态 {@code done{cancelled:true}}）。
	 */
	private PendingToolCall cancel(String runId, PendingToolCall pending, int timeoutSeconds, boolean confirm) {
		PendingToolCall fresh = this.store.pending(runId, pending.getToolCallId());
		if (fresh == null) {
			fresh = pending;
		}
		if (PendingToolCall.PENDING.equals(fresh.getStatus())) {
			fresh.setStatus(PendingToolCall.CANCELLED);
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
				confirm ? "confirm" : "frontend-tool", timeoutSeconds);
		return fresh;
	}

	/** 超时自动取消：把"未执行"这一结局也写进外置状态（重启后仍可读）。 */
	private PendingToolCall expire(String runId, PendingToolCall pending, int timeoutSeconds, boolean confirm) {
		PendingToolCall fresh = this.store.pending(runId, pending.getToolCallId());
		if (fresh == null) {
			fresh = pending;
		}
		if (!PendingToolCall.PENDING.equals(fresh.getStatus())) {
			return fresh;
		}
		fresh.setStatus(PendingToolCall.TIMEOUT);
		fresh.setReason(confirm ? "CONFIRM_TIMEOUT" : "FRONTEND_TIMEOUT");
		fresh.setExecuted(false);
		fresh.setResolvedAtMs(System.currentTimeMillis());
		fresh.setResultText(confirm
				? "确认等待超过 " + timeoutSeconds + " 秒，系统已自动取消该操作（工具未执行）。"
				: "前端工具 " + fresh.getName() + " 未在 " + timeoutSeconds + " 秒内回传执行结果（前端超时），本次调用未获得数据。");
		this.store.putPending(runId, fresh);
		// 超时的结局帧与人工决策同形（SP-01ab 实测形态：confirm_decision 的 decision=timeout），
		// 前端不必区分"人拒绝"与"等超时"两套解析路径。
		SseChatEmitter out = this.registry.of(runId);
		if (confirm) {
			out.confirmDecision(fresh, "timeout", fresh.getReason(), timeoutSeconds * 1000L);
		}
		else {
			out.frontendToolResult(fresh, false, fresh.getResultText());
		}
		return fresh;
	}

	private Map<String, Object> heartbeatData(String runId, PendingToolCall pending, boolean confirm,
			int timeoutSeconds, long waitedSeconds) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("runId", runId);
		data.put("suspendKind", confirm ? "confirm" : "frontend-tool");
		data.put("toolCallId", pending.getToolCallId());
		data.put("name", pending.getName());
		data.put("status", pending.getStatus());
		data.put("waitedSeconds", waitedSeconds);
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

	/** 人工决策：先写 Redis，再发回执帧，最后唤醒（帧先于它唤起的后果）。重复提交按状态幂等拒绝（SP-01d V-d2B）。 */
	public Submission submitDecision(String runId, String toolCallId, boolean approved, String reason) {
		PendingToolCall pending = this.store.pending(runId, toolCallId);
		if (pending == null) {
			return new Submission(Outcome.NOT_FOUND, null, false, null);
		}
		if (!PendingToolCall.PENDING.equals(pending.getStatus())) {
			return new Submission(Outcome.DUPLICATE, pending, false, null);
		}
		pending.setStatus(approved ? PendingToolCall.APPROVED : PendingToolCall.REJECTED);
		pending.setReason(reason == null || reason.isBlank()
				? (approved ? "用户在确认门中批准" : "用户在确认门中拒绝") : reason);
		// 决策落库时"一定还没执行"：执行由循环内的认领（claimExecution）负责置位
		pending.setExecuted(false);
		pending.setExecutedBy(null);
		pending.setResolvedAtMs(System.currentTimeMillis());
		if (!approved) {
			pending.setResultText("用户拒绝了该操作，工具未执行。拒绝原因：" + pending.getReason());
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

	/** 前端工具结果回灌（保持既有四参口径：非取消路径）。 */
	public Submission submitFrontendResult(String runId, String toolCallId, String result, String source) {
		return submitFrontendResult(runId, toolCallId, result, source, false);
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
		PendingToolCall pending = this.store.pending(runId, toolCallId);
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
			log.warn("前端工具回灌被安全闸拒绝 runId={} toolCallId={} name={} code={}（状态不变，挂起继续等待）：{}",
					runId, toolCallId, pending.getName(), verdict.code(), verdict.reasons());
			return new Submission(Outcome.REJECTED, pending, false, verdict);
		}
		pending.setStatus(PendingToolCall.FRONTEND_RESULT);
		pending.setReason("source=" + (source == null || source.isBlank() ? "http-post" : source));
		pending.setResultText(result == null ? "" : result);
		pending.setResolvedAtMs(System.currentTimeMillis());
		boolean woke;
		synchronized (this.submitLock) {
			this.store.putPending(runId, pending);
			this.registry.of(runId).frontendToolResult(pending, true, result == null ? "" : result);
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
	 * <p>为什么要有这个终态：前端工具此前只有"回灌结果"与"超时/整轮取消"三个出口 ——
	 * 用户关掉表单时前端<b>没有合法动作</b>，挂起只能悬到 {@code app.ai.hitl.timeout} 才由
	 * {@code FRONTEND_TIMEOUT} 收摊。取消终态把"用户不打算填了"变成一次明确的、立刻的收敛
	 * （且与"没人响应"的超时语义分开，排障时看得清）。
	 */
	private Submission dismiss(String runId, PendingToolCall pending, String source) {
		pending.setStatus(PendingToolCall.FRONTEND_CANCELLED);
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
	 * <h2>四个前置判据（缺一不触发）</h2>
	 * <ol>
	 * <li>{@code woke=true} → 本进程的等待方已被唤醒，正常续跑路径已成立（{@code WOKE_IN_PROCESS_GATE}）；</li>
	 * <li>{@link #hasActiveLatch(String)} → 本进程仍有该 runId 的活跃等待
	 *     （R4③ 的毫秒窗：{@code saveSuspended} 写完、latch 还没登记时同进程 confirm 也会看到
	 *     {@code woke=false}）—— 触发会双驱动同一轮（{@code ACTIVE_LATCH_IN_PROCESS}）；</li>
	 * <li>快照存在且状态为 {@code SUSPENDED}（{@code SNAPSHOT_NOT_SUSPENDED}）；</li>
	 * <li>{@link ResumeService} 可用（无 key 的降级装配下 provider 返回 null，{@code NO_RESUME_SERVICE}）。</li>
	 * </ol>
	 *
	 * <p><b>触发点是异步的（A9）</b>：动作交给 {@link #resumeTriggerExecutor}（轻量池，
	 * 容量与拒绝策略见 {@code AiProperties.Resume}），HTTP 线程<b>立即返回</b>；
	 * 池饱和时抛 {@code RejectedExecutionException}，此处<b>吞掉</b>并回
	 * {@code EXECUTOR_SATURATED}（外部输入本身已经落库成功，不该因为"续跑没派出去"而报错）。
	 * {@link RunStore#claimExecution} 的原子认领仍是"工具恰好一次"的最终兜底。
	 *
	 * <p>真实续跑结局由 {@link #runResume} 在执行时打 INFO 日志（ACCEPTED /
	 * ALREADY_RUNNING / PENDING_UNRESOLVED / POOL_SATURATED / 异常都留痕）。
	 */
	private ResumeTrigger maybeTriggerResume(String runId, boolean woke) {
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
		try {
			this.resumeTriggerExecutor.execute(() -> runResume(runId));
		}
		catch (RejectedExecutionException ex) {
			log.warn("死卡兜底续跑未派发（触发池饱和）runId={}：{}", runId, ex.getMessage());
			return ResumeTrigger.skipped("EXECUTOR_SATURATED", "续跑触发池已饱和，本次未派发");
		}
		catch (RuntimeException ex) {
			log.warn("死卡兜底续跑派发失败 runId={}：{}", runId, ex.getMessage());
			return ResumeTrigger.skipped("DISPATCH_FAILED", String.valueOf(ex.getMessage()));
		}
		log.info("死卡兜底触发续跑 runId={}（woke=false 且快照 SUSPENDED，已派发，HTTP 立即返回）", runId);
		return ResumeTrigger.dispatched();
	}

	/** 触发任务本体：调 {@link ResumeService#resume(String)} 并把结局打 INFO 日志留痕（A9 的"饱和/结局可见"）。 */
	private void runResume(String runId) {
		try {
			ResumeService service = this.resumeServiceProvider.getIfAvailable();
			if (service == null) {
				log.info("死卡兜底续跑结局 runId={} outcome=NO_RESUME_SERVICE", runId);
				return;
			}
			ResumeService.ResumeResult result = service.resume(runId);
			log.info("死卡兜底续跑结局 runId={} outcome={} body={}", runId, result.outcome(), result.body());
		}
		catch (Exception ex) {
			log.error("死卡兜底续跑异常 runId={}", runId, ex);
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
