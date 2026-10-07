package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
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
			CancellationRegistry cancellations, ToolActivityBeacon beacon) {
		this.store = store;
		this.registry = registry;
		this.properties = properties;
		this.cancellations = cancellations;
		this.beacon = beacon;
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

		ACCEPTED, DUPLICATE, NOT_FOUND

	}

	/**
	 * 一次外部输入的处理结果。
	 *
	 * @param outcome 结局（ACCEPTED / DUPLICATE / NOT_FOUND）
	 * @param pending 落库后的条目（NOT_FOUND 时为 null）
	 * @param woke 是否唤醒了本进程内正在等待的闸门（false 说明等待方在<b>另一个进程</b>或已死，
	 * 由续跑接手）
	 */
	public record Submission(Outcome outcome, PendingToolCall pending, boolean woke) {
	}

	/** 人工决策：先写 Redis，再发回执帧，最后唤醒（帧先于它唤起的后果）。重复提交按状态幂等拒绝（SP-01d V-d2B）。 */
	public Submission submitDecision(String runId, String toolCallId, boolean approved, String reason) {
		PendingToolCall pending = this.store.pending(runId, toolCallId);
		if (pending == null) {
			return new Submission(Outcome.NOT_FOUND, null, false);
		}
		if (!PendingToolCall.PENDING.equals(pending.getStatus())) {
			return new Submission(Outcome.DUPLICATE, pending, false);
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
		return new Submission(Outcome.ACCEPTED, pending, woke);
	}

	/** 前端工具结果回灌：先写 Redis，再发回灌帧，最后唤醒（同 S5c-6 的口径）。 */
	public Submission submitFrontendResult(String runId, String toolCallId, String result, String source) {
		PendingToolCall pending = this.store.pending(runId, toolCallId);
		if (pending == null) {
			return new Submission(Outcome.NOT_FOUND, null, false);
		}
		if (!PendingToolCall.PENDING.equals(pending.getStatus())) {
			return new Submission(Outcome.DUPLICATE, pending, false);
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
		return new Submission(Outcome.ACCEPTED, pending, woke);
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
