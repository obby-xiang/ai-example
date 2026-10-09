package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiKeyPresentCondition;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.gate.SpToolCallingManager;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Conditional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Collectors;

/**
 * 续跑服务（S4.2 §2 {@code run/ResumeService}，SM-04「重启续跑全链路」的本体）。
 *
 * <h2>硬规范②：必须重建完整历史</h2>
 * 重建的输入一律是 {@link RunSnapshot#getMessageJson()}（模型当时真正看到的那串消息：
 * system ×2 + user + 历轮 {@code assistant(tool_calls)}/{@code tool}），再加上
 * <b>挂起那一轮</b>的 {@code assistant(tool_calls)}（取自快照 in-flight + 待决条目的 id/type/name/arguments）
 * 与按同一顺序拼出的 {@code ToolResponseMessage}。绝不复用官方循环自己那份"只有 assistant+tool"的续跑请求。
 *
 * <h2>补执行恰好一次</h2>
 * 逐条目结算（{@link SpToolCallingManager#resolvePending}）：
 * <ul>
 * <li>台账里已有该 {@code toolCallId} → 复用结果，不再执行（跨进程防重放）；</li>
 * <li>{@code APPROVED 且 executed=false}（SP-02 E2 的"已放行未执行"窗口，例如放行后进程死亡）
 * → 本次<b>补执行</b>，认领与台账都记在新实例名下；</li>
 * <li>{@code executed=true} → 说明上一实例已完成，直接复用其 {@code resultText}；</li>
 * <li>其余一旦有结论的条目（{@code REJECTED/TIMEOUT/FRONTEND_RESULT/FRONTEND_CANCELLED}、
 * 入参被安全闸拒绝的 {@code REJECTED_ARGUMENTS}、越 scope 的 {@code BLOCKED}）
 * → 以"未执行/已回灌"语义回填，<b>不执行</b>。</li>
 * </ul>
 *
 * <h2>准入与互斥</h2>
 * <ul>
 * <li>状态 {@code DONE} → {@code ALREADY_DONE}（重复续跑不发模型请求，幂等）；</li>
 * <li>状态 {@code RUNNING} → 409（无进程在跑却停在 RUNNING = 僵尸轮，见【待裁决】）；</li>
 * <li>仍有 {@code PENDING} 的<b>外部</b>挂起项（确认门/前端工具）→ 409 {@code PENDING_UNRESOLVED}，
 * 不改任何状态、不发模型请求；</li>
 * <li>runId 级续跑互斥锁（SP-02 §6.2 遗留项）：同一 runId 并发续跑只有一个能进来，
 * 其余 {@code RESUME_IN_PROGRESS}；锁在轮终态释放。</li>
 * </ul>
 */
@Slf4j
@Service
@Conditional(AiKeyPresentCondition.class)
public class ResumeService {

	public static final String RESUME_LOCK_PREFIX = "ai:resume:lock:";

	private final RunStore store;

	private final RunRegistry registry;

	private final MessageJsonCodec codec;

	private final ResilientChatService chatService;

	private final SpToolCallingManager toolCallingManager;

	private final ThreadPoolTaskExecutor suspendExecutor;

	private final StringRedisTemplate redis;

	private final AiProperties properties;

	private final CancellationRegistry cancellations;

	/** T2：重建历史的 {@code role:tool} 也要过同一份模型侧上限（口径与首轮一致，不因续跑而变）。 */
	private final ToolResultLimiter limiter;

	/** 进程内续跑互斥（Redis 不可用时仍是最后一道闸）。 */
	private final Map<String, Boolean> resuming = new ConcurrentHashMap<>();

	public ResumeService(RunStore store, RunRegistry registry, MessageJsonCodec codec, ResilientChatService chatService,
			SpToolCallingManager toolCallingManager, @Qualifier("aiRunExecutor") ThreadPoolTaskExecutor suspendExecutor,
			StringRedisTemplate redis, AiProperties properties, CancellationRegistry cancellations,
			ToolResultLimiter limiter) {
		this.store = store;
		this.registry = registry;
		this.codec = codec;
		this.chatService = chatService;
		this.toolCallingManager = toolCallingManager;
		this.suspendExecutor = suspendExecutor;
		this.redis = redis;
		this.properties = properties;
		this.cancellations = cancellations;
		this.limiter = limiter;
	}

	public enum Outcome {

		ACCEPTED, NOT_FOUND, ALREADY_DONE, ALREADY_RUNNING, PENDING_UNRESOLVED, IN_PROGRESS, POOL_SATURATED,
		ALREADY_CANCELLED

	}

	public record ResumeResult(Outcome outcome, Map<String, Object> body) {
	}

	/** 发现通道：全部仍存活的轮次（重启后"ResumeService 发现 pending"的入口）。 */
	public List<Map<String, Object>> listRuns() {
		List<Map<String, Object>> out = new ArrayList<>();
		for (String runId : this.store.allRunIds()) {
			RunSnapshot snapshot = this.store.get(runId);
			if (snapshot == null) {
				continue;
			}
			List<PendingToolCall> pendings = this.store.pendings(runId);
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("runId", runId);
			item.put("sessionId", snapshot.getSessionId());
			item.put("status", snapshot.getStatus());
			item.put("round", snapshot.getRound());
			item.put("messageCount", snapshot.getMessageJson().size());
			item.put("createdBy", snapshot.getCreatedBy());
			item.put("resumedBy", snapshot.getResumedBy());
			item.put("updatedAtMs", snapshot.getUpdatedAtMs());
			item.put("pending", pendings.stream().map(PendingToolCall::asMap).toList());
			item.put("pendingUnresolved", pendings.stream()
				.filter(pending -> PendingToolCall.PENDING.equals(pending.getStatus()))
				.map(PendingToolCall::getToolCallId)
				.toList());
			item.put("ledgerCounts", this.store.ledgerCounts(runId));
			// 发现态的"可续跑性"判定（启动自动续跑与运维端点共用同一口径）：
			// 取消标志（Redis 为准）> 终态 > 未决外部输入 > 僵尸 RUNNING（超龄）。
			boolean cancelled = this.cancellations.isCancelled(runId);
			long activeAtMs = activeAtMs(snapshot);
			boolean zombie = RunSnapshot.RUNNING.equals(snapshot.getStatus())
					&& System.currentTimeMillis() - activeAtMs > this.properties.getResume().getZombieAge()
						.toMillis();
			item.put("activeAtMs", activeAtMs);
			item.put("activeAgeMs", System.currentTimeMillis() - activeAtMs);
			item.put("zombieAgeThresholdMs", this.properties.getResume().getZombieAge().toMillis());
			List<String> unresolvedExternal = pendings.stream()
				.filter(pending -> PendingToolCall.PENDING.equals(pending.getStatus()))
				.filter(pending -> !PendingToolCall.KIND_BACKEND.equals(pending.getKind()))
				.map(PendingToolCall::getToolCallId)
				.toList();
			item.put("cancelled", cancelled);
			item.put("zombieRunning", zombie);
			// T3-7（A14）：可续跑性组合改用统一终态判定（含 REJECTED 归一），不再散点比对三个字面量
			item.put("resumeable", !cancelled && unresolvedExternal.isEmpty()
					&& !RunSnapshot.isTerminal(snapshot.getStatus())
					&& (zombie || !RunSnapshot.RUNNING.equals(snapshot.getStatus())));
			item.put("unresolvedExternal", unresolvedExternal);
			out.add(item);
		}
		return out;
	}

	/**
	 * 该轮的"活跃时刻"（R3 裁决）：取<b>较新者</b> ——
	 * 快照 {@code updatedAtMs}（工具落定/挂起/终态写入的时刻）与
	 * {@link RunStore#lastEventAtMs(String)}（最后一帧的时刻：delta/heartbeat/tool_* 全在内）。
	 *
	 * <p>
	 * 为什么必须双条件：快照只在"轮次结构变化"时刷新，<b>长流式轮次</b>（一次模型调用吐几十秒正文、
	 * 中途不落定任何工具）会让 {@code updatedAtMs} 长时间不动 —— 只看它就会把正在流式产出的活轮
	 * 判成僵尸并强行接管（第二次执行同一轮）。帧时间戳覆盖了流式与挂起心跳，两者取较新即
	 * "只要有活动就不算僵尸"。
	 */
	public long activeAtMs(RunSnapshot snapshot) {
		long lastEvent = this.store.lastEventAtMs(snapshot.getRunId());
		return Math.max(snapshot.getUpdatedAtMs(), lastEvent);
	}

	/** 单轮明细（台账 + 待决条目 + 历史角色序列）。 */
	public Map<String, Object> describe(String runId) {
		RunSnapshot snapshot = this.store.get(runId);
		if (snapshot == null) {
			return Map.of("exists", false);
		}
		List<String> roles = this.codec.deserializeAll(snapshot.getMessageJson())
			.stream()
			.map(message -> message.getMessageType().name())
			.collect(Collectors.toList());
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("exists", true);
		out.put("snapshot", snapshot.asMap());
		out.put("context", snapshot.getContext());
		out.put("historyRoles", roles);
		out.put("inFlight", snapshot.getInFlightToolCalls().stream().map(PendingToolCall::asMap).toList());
		out.put("pending", this.store.pendings(runId).stream().map(PendingToolCall::asMap).toList());
		out.put("ledger", this.store.ledger(runId));
		out.put("ledgerCounts", this.store.ledgerCounts(runId));
		out.put("instanceId", this.store.instanceId());
		out.put("keys", Map.of("state", this.store.runKey(runId), "pending", this.store.pendingKey(runId), "ledger",
				this.store.ledgerKey(runId), "events", this.store.eventsKey(runId), "claim", this.store.claimKey(runId)));
		return out;
	}

	public ResumeResult resume(String runId) {
		return resume(runId, false);
	}

	/**
	 * 续跑（幂等 + 互斥 + 取消优先 + 可选僵尸接管）。
	 *
	 * @param forceTakeover 仅由<b>启动自动续跑</b>使用的僵尸接管开关（N3）：为 true 时允许
	 * 接管"状态仍是 {@code RUNNING} 但已超龄"的轮次（上一进程死在模型调用中间留下的僵尸轮）。
	 * 运维端点不传这个开关（{@code false}），保持"RUNNING 一律 409"的保守语义。
	 */
	public ResumeResult resume(String runId, boolean forceTakeover) {
		if (!acquireResumeLock(runId)) {
			return new ResumeResult(Outcome.IN_PROGRESS, Map.of("runId", runId, "resumed", false,
					"reason", "RESUME_IN_PROGRESS", "instanceId", this.store.instanceId()));
		}
		boolean handedOff = false;
		try {
			RunSnapshot snapshot = this.store.get(runId);
			if (snapshot == null) {
				return new ResumeResult(Outcome.NOT_FOUND,
						Map.of("runId", runId, "resumed", false, "reason", "UNKNOWN_RUN", "instanceId",
								this.store.instanceId()));
			}
			String sessionId = snapshot.getSessionId();
			List<PendingToolCall> pendings = this.store.pendings(runId);
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("runId", runId);
			body.put("sessionId", sessionId);
			body.put("statusBeforeResume", snapshot.getStatus());
			body.put("instanceId", this.store.instanceId());

			// 取消优先（ADR-8 修正③）：权威取消标志在 Redis，跨进程同样拦得住续跑 ——
			// 否则"用户已取消"的轮次会被重启后的自动续跑重新拾起并执行掉。
			// 位置在统一终态判定<b>之前</b>：即便快照已落 CANCELLED，也要把残留的 PENDING 条目收敛掉。
			if (this.cancellations.isCancelled(runId)) {
				snapshot.setStatus(RunSnapshot.CANCELLED);
				snapshot.setCancelled(true);
				snapshot.setError("AI_RUN_CANCELLED：本轮已由用户取消，续跑被拒绝");
				this.store.save(snapshot);
				this.store.cancelPendings(runId);
				body.put("resumed", false);
				body.put("reason", "ALREADY_CANCELLED");
				return new ResumeResult(Outcome.ALREADY_CANCELLED, body);
			}
			// 统一终态判定（T3-7 / A14）：DONE/FAILED/CANCELLED（+ REJECTED 归一）一律不再续跑。
			// 结局码保持既有对外语义：取消 → ALREADY_CANCELLED；完成 → ALREADY_DONE（reason ALREADY_DONE）；
			// 失败 → ALREADY_DONE（reason ALREADY_FAILED，属既有口径，不动）。
			if (RunSnapshot.isTerminal(snapshot.getStatus())) {
				boolean cancelledStatus = RunSnapshot.CANCELLED.equals(snapshot.getStatus());
				boolean doneStatus = RunSnapshot.DONE.equals(snapshot.getStatus());
				body.put("resumed", false);
				body.put("reason", cancelledStatus ? "ALREADY_CANCELLED"
						: (doneStatus ? "ALREADY_DONE" : "ALREADY_FAILED"));
				return new ResumeResult(cancelledStatus ? Outcome.ALREADY_CANCELLED : Outcome.ALREADY_DONE, body);
			}
			if (RunSnapshot.RUNNING.equals(snapshot.getStatus())) {
				if (!forceTakeover) {
					body.put("resumed", false);
					body.put("reason", "ALREADY_RUNNING");
					return new ResumeResult(Outcome.ALREADY_RUNNING, body);
				}
				// 僵尸接管（N3/R3）：上一进程死在模型调用中间，状态永远停在 RUNNING。
				// 判据由调用方（启动扫描/运维扫描）给出：本实例刚启动 + 活跃时刻已超龄；
				// 活跃时刻 = max(快照 updatedAtMs, 最后一帧 atMs)，因此"还在流式吐字"的活轮不会被接管。
				long activeAtMs = activeAtMs(snapshot);
				log.warn("僵尸轮接管 runId={} sessionId={} 活跃时刻距今 {}ms（快照 {}ms / 末帧 {}ms，阈值 {}）",
						runId, sessionId, System.currentTimeMillis() - activeAtMs,
						System.currentTimeMillis() - snapshot.getUpdatedAtMs(),
						System.currentTimeMillis() - this.store.lastEventAtMs(runId),
						this.properties.getResume().getZombieAge());
				body.put("zombieTakeover", true);
				body.put("activeAtMs", activeAtMs);
				body.put("activeAgeMs", System.currentTimeMillis() - activeAtMs);
			}
			List<PendingToolCall> unresolved = pendings.stream()
				.filter(pending -> PendingToolCall.PENDING.equals(pending.getStatus()))
				.filter(pending -> !PendingToolCall.KIND_BACKEND.equals(pending.getKind()))
				.toList();
			if (!unresolved.isEmpty()) {
				body.put("resumed", false);
				body.put("reason", "PENDING_UNRESOLVED");
				body.put("unresolved", unresolved.stream().map(PendingToolCall::asMap).toList());
				return new ResumeResult(Outcome.PENDING_UNRESOLVED, body);
			}

			AiContext context = AiContext.fromMap(snapshot.getContext());
			List<Message> history = new ArrayList<>(this.codec.deserializeAll(snapshot.getMessageJson()));
			List<String> rebuilt = new ArrayList<>();

			if (!snapshot.getInFlightToolCalls().isEmpty()) {
				// ===== 硬规范②：把挂起那一轮的 assistant(tool_calls) 重新拼回完整历史 =====
				AssistantMessage assistant = rebuildAssistant(snapshot);
				history.add(assistant);
				List<Message> requestHistory = new ArrayList<>(history);
				Prompt prompt = this.chatService.buildResumedPrompt(runId, sessionId, context, requestHistory);
				List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
				for (PendingToolCall item : snapshot.getInFlightToolCalls()) {
					// 以 Redis 的待决条目为准（快照里的 in-flight 副本停留在"挂起那一刻"）
					PendingToolCall fresh = this.store.pending(runId, item.getToolCallId());
					PendingToolCall effective = fresh == null ? item : fresh;
					String text = this.toolCallingManager.resolvePending(runId, prompt, effective);
					// T2：回填模型前过模型侧上限（台账里仍是全文）
					responses.add(new ToolResponseMessage.ToolResponse(effective.getToolCallId(),
							effective.getName(), this.limiter.forModel(text)));
					// R6 裁决：标签必须在**结算之后**回读 Redis 才算准 ——
					// executeOnce 的认领/完成是写在 Redis 里的新副本上，传入的 effective 可能仍是旧值
					// （旧实现因此在"本次刚执行完"时显示 reused(null)）。
					PendingToolCall settled = this.store.pending(runId, effective.getToolCallId());
					rebuilt.add(settlementLabel(settled == null ? effective : settled));
				}
				history.add(ToolResponseMessage.builder().responses(responses).build());
				snapshot.setMessageJson(this.codec.serializeAll(history));
				snapshot.setInFlightToolCalls(new ArrayList<>());
				snapshot.setAssistantContent(null);
				snapshot.setStatus(RunSnapshot.RUNNING);
				snapshot.getResumedBy().add(this.store.instanceId());
				this.store.save(snapshot);
			}
			else {
				snapshot.getResumedBy().add(this.store.instanceId());
				snapshot.setStatus(RunSnapshot.RUNNING);
				this.store.save(snapshot);
			}

			body.put("resumed", true);
			body.put("rebuiltTools", rebuilt);
			body.put("promptMessageCount", history.size());
			body.put("promptRoles", history.stream().map(message -> message.getMessageType().name()).toList());
			body.put("resumedBy", this.store.instanceId());

			SseChatEmitter out = this.registry.of(runId);
			List<Message> resumeHistory = history;
			try {
				this.suspendExecutor.execute(() -> {
					try {
						this.chatService.driveResumed(runId, sessionId, context, resumeHistory, out);
					}
					catch (Exception ex) {
						log.error("续跑未捕获异常 runId={}", runId, ex);
						out.error("AI_RESUME_FAILED", String.valueOf(ex.getMessage()));
					}
					finally {
						// 轮终态：完成订阅者 + 释放续跑锁（ADR-5 的"轮终态主动释放"同族语义）
						releaseResumeLock(runId);
						this.registry.close(runId);
					}
				});
				handedOff = true;
			}
			catch (RejectedExecutionException ex) {
				body.put("resumed", false);
				body.put("reason", "SUSPEND_POOL_SATURATED");
				snapshot.setStatus(RunSnapshot.SUSPENDED);
				this.store.save(snapshot);
				return new ResumeResult(Outcome.POOL_SATURATED, body);
			}
			return new ResumeResult(Outcome.ACCEPTED, body);
		}
		finally {
			if (!handedOff) {
				releaseResumeLock(runId);
			}
		}
	}

	/**
	 * 结算标签（R6 裁决）：{@code <toolCallId>:<status>:<execution>}，其中 execution 取
	 * {@code executed-here}（本次续跑真的执行了）、{@code reused(<实例>) }（复用他实例的执行结果）、
	 * {@code not-executed}（未执行：放行前被取消/拒绝/超时/前端未回灌等）。
	 */
	private String settlementLabel(PendingToolCall pending) {
		String execution;
		if (!pending.isExecuted()) {
			execution = "not-executed";
		}
		else if (this.store.instanceId().equals(pending.getExecutedBy())) {
			execution = "executed-here";
		}
		else {
			execution = "reused(" + pending.getExecutedBy() + ")";
		}
		return pending.getToolCallId() + ":" + pending.getStatus() + ":" + execution;
	}

	/** 从快照重建挂起那一轮的 {@code assistant(tool_calls)}（顺序与 id/type/name/arguments 原样）。 */
	private AssistantMessage rebuildAssistant(RunSnapshot snapshot) {
		List<AssistantMessage.ToolCall> calls = new ArrayList<>();
		for (PendingToolCall item : snapshot.getInFlightToolCalls()) {
			calls.add(new AssistantMessage.ToolCall(item.getToolCallId(),
					item.getType() == null ? "function" : item.getType(), item.getName(), item.getArguments()));
		}
		return AssistantMessage.builder()
			.content(snapshot.getAssistantContent() == null ? "" : snapshot.getAssistantContent())
			.toolCalls(calls)
			.build();
	}

	// ── runId 级续跑互斥锁 ──────────────────────────────────────────────────

	private boolean acquireResumeLock(String runId) {
		if (this.resuming.putIfAbsent(runId, Boolean.TRUE) != null) {
			return false;
		}
		Duration ttl = this.properties.getResilience().getTotalBudget();
		try {
			Boolean acquired = this.redis.opsForValue().setIfAbsent(RESUME_LOCK_PREFIX + runId,
					this.store.instanceId(), ttl);
			if (!Boolean.TRUE.equals(acquired)) {
				this.resuming.remove(runId);
				return false;
			}
		}
		catch (Exception ex) {
			log.warn("续跑互斥锁降级为进程内 runId={}：{}", runId, ex.getMessage());
		}
		return true;
	}

	private void releaseResumeLock(String runId) {
		this.resuming.remove(runId);
		try {
			String value = this.redis.opsForValue().get(RESUME_LOCK_PREFIX + runId);
			if (this.store.instanceId().equals(value)) {
				this.redis.delete(RESUME_LOCK_PREFIX + runId);
			}
		}
		catch (Exception ex) {
			log.debug("释放续跑互斥锁失败 runId={}：{}", runId, ex.getMessage());
		}
	}

}
