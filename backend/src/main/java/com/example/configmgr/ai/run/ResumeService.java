package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiKeyPresentCondition;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.gate.SpToolCallingManager;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.tool.AiContext;
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
 * <li>{@code REJECTED/TIMEOUT/FRONTEND_RESULT} → 以"未执行/已回灌"语义回填，不执行。</li>
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

	private final AiChatService chatService;

	private final SpToolCallingManager toolCallingManager;

	private final ThreadPoolTaskExecutor suspendExecutor;

	private final StringRedisTemplate redis;

	private final AiProperties properties;

	/** 进程内续跑互斥（Redis 不可用时仍是最后一道闸）。 */
	private final Map<String, Boolean> resuming = new ConcurrentHashMap<>();

	public ResumeService(RunStore store, RunRegistry registry, MessageJsonCodec codec, AiChatService chatService,
			SpToolCallingManager toolCallingManager, @Qualifier("aiRunExecutor") ThreadPoolTaskExecutor suspendExecutor,
			StringRedisTemplate redis, AiProperties properties) {
		this.store = store;
		this.registry = registry;
		this.codec = codec;
		this.chatService = chatService;
		this.toolCallingManager = toolCallingManager;
		this.suspendExecutor = suspendExecutor;
		this.redis = redis;
		this.properties = properties;
	}

	public enum Outcome {

		ACCEPTED, NOT_FOUND, ALREADY_DONE, ALREADY_RUNNING, PENDING_UNRESOLVED, IN_PROGRESS, POOL_SATURATED

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
			out.add(item);
		}
		return out;
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

			if (RunSnapshot.DONE.equals(snapshot.getStatus())) {
				body.put("resumed", false);
				body.put("reason", "ALREADY_DONE");
				return new ResumeResult(Outcome.ALREADY_DONE, body);
			}
			if (RunSnapshot.RUNNING.equals(snapshot.getStatus())) {
				body.put("resumed", false);
				body.put("reason", "ALREADY_RUNNING");
				return new ResumeResult(Outcome.ALREADY_RUNNING, body);
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
					responses.add(new ToolResponseMessage.ToolResponse(effective.getToolCallId(),
							effective.getName(), text == null ? "" : text));
					rebuilt.add(effective.getToolCallId() + ":" + effective.getStatus() + ":"
							+ (this.store.instanceId().equals(effective.getExecutedBy()) ? "executed-here"
									: "reused(" + effective.getExecutedBy() + ")"));
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
