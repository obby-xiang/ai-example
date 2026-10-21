package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.gate.SpToolCallingManager;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolResultEnvelope;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

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
 * <li><b>续跑权租约</b>（B23L1 批②b，取代 {@code ai:resume:lock:}）：{@code ai:lease:run:<runId>}
 * 的持有者才是本轮的续跑主人。旧签名自抢（抢不到 ⇒ {@code LEASE_HELD_ELSEWHERE}）；带租约重载
 * 只校验归属（调用方就是持有者）—— 见 {@link #resume(String, boolean, LeaseService.Lease)}。
 * 进程内 {@code resuming} 表保留：同进程并发续跑仍然只有一个能进（{@code IN_PROGRESS}）。</li>
 * </ul>
 */
@Slf4j
@Service
public class ResumeService {

	private final RunStore store;

	private final RunRegistry registry;

	private final MessageJsonCodec codec;

	private final ResilientChatService chatService;

	private final SpToolCallingManager toolCallingManager;

	private final ThreadPoolTaskExecutor suspendExecutor;

	/**
	 * <b>保留仅为构造器兼容，锁已摘除</b>（M5）：{@code ai:resume:lock:} 在批②b 整体并入续跑权租约
	 * （GET-比对-DEL 那条非原子释放会删掉别人的锁）；本字段已无任何读取者，留着是为了不动 10 参
	 * 兼容构造器（{@code ResumeSkipCascadeTest}/{@code ResumeEnvelopeShapeTest} 等既有用例零改动）。
	 * 下一次可以改签名的窗口应直接删掉它。
	 */
	private final StringRedisTemplate redis;

	private final AiProperties properties;

	private final CancellationRegistry cancellations;

	/** T2：重建历史的 {@code role:tool} 也要过同一份模型侧上限（口径与首轮一致，不因续跑而变）。 */
	private final ToolResultLimiter limiter;

	/** 进程内续跑互斥（同进程并发续跑的唯一闸；租约管的是跨实例）。 */
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
		ALREADY_CANCELLED,

		/**
		 * B23L1 批②b：本轮的续跑权租约在<b>别的实例</b>手里（或调用方给的租约已过期）。
		 *
		 * <p>它不是错误：说明那一轮已经有主（在等卡 / 在驱动 / 正在收尾），本实例什么都不该做。
		 * 对外统一 409（{@code AiController#resume} 的 switch）。
		 */
		LEASE_HELD_ELSEWHERE

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
				this.store.ledgerKey(runId), "events", this.store.eventsKey(runId), "claim", this.store.claimKey(runId),
				// B23L1 批②b：fencing 的两个契约键也要能在运维面上直接对上（同一个入口拼键，不另拼字符串）
				"lease", this.store.runLeaseKey(runId), "fence", this.store.fenceKey(runId)));
		return out;
	}

	public ResumeResult resume(String runId) {
		return resume(runId, false);
	}

	/**
	 * 续跑（幂等 + 互斥 + 取消优先 + 可选僵尸接管）—— <b>旧签名</b>：自抢续跑权租约。
	 *
	 * <p>既有调用方（{@code StartupResumeRunner} 的启动/僵尸扫描、{@code AiController} 的运维端点）
	 * <b>零改动</b>即获得 fencing：
	 * <ol>
	 * <li>抢 {@code ai:lease:run:<runId>}；抢不到（且本仓有租约设施）⇒ {@code LEASE_HELD_ELSEWHERE}，
	 * 什么都不动；无租约设施（单测/降级装配）⇒ 走原路径，由进程内互斥兜底；</li>
	 * <li>走带租约重载 {@link #resume(String, boolean, LeaseService.Lease)}；</li>
	 * <li>非 {@code ACCEPTED} ⇒ 当场还租；{@code ACCEPTED} ⇒ 交续租调度器
	 * （终态由 {@code RunStore.save} 释放）。<b>只还自己创建的</b>（{@code AcquireResult#created()}）：
	 * 可重入命中的租约属于等待方或另一个并发调用方，还了就会把别人的续跑权删掉 ——
	 * 这正是"并发同主双抢、败方删掉胜方租约"那个缺陷的根因。</li>
	 * </ol>
	 *
	 * @param forceTakeover 仅由<b>启动自动续跑</b>使用的僵尸接管开关（N3）：为 true 时允许
	 * 接管"状态仍是 {@code RUNNING} 但已超龄"的轮次（上一进程死在模型调用中间留下的僵尸轮）。
	 * 运维端点不传这个开关（{@code false}），保持"RUNNING 一律 409"的保守语义。
	 */
	public ResumeResult resume(String runId, boolean forceTakeover) {
		LeaseService leases = this.store.leaseService();
		boolean fresh = false;
		LeaseService.Lease lease = null;
		if (leases != null) {
			LeaseService.AcquireResult acquired = this.store.acquireRunLease(runId);
			if (acquired.outcome() == LeaseService.AcquireOutcome.HELD_ELSEWHERE) {
				return new ResumeResult(Outcome.LEASE_HELD_ELSEWHERE, Map.of("runId", runId, "resumed", false,
						"reason", "LEASE_HELD_ELSEWHERE", "instanceId", this.store.instanceId()));
			}
			// I4：设施不可用（{@code UNAVAILABLE}）⇒ 走"无租约降级"这条既有路径（进程内互斥放行），
			// 与 leaseService == null 完全同义。把"我不知道有没有主"报成"别人持有"会让一次 Redis 抖动
			// 把启动续跑/运维续跑全部 409 掉。
			// 所有权口径：<b>谁创建谁释放</b>。只有真正建出这一任的调用方才允许交调度器/还租；
			// 命中现值（同主重入）时 created=false —— 用"抢占前 epoch"推断是错的：并发同主双抢时
			// 两边读到同一个前置状态，于是败方（撞 resuming 互斥 → IN_PROGRESS）会去还胜方的租约，
			// 把胜方的 fencing 写打回 plain SET（harness lease-scripts 实锤）。
			lease = acquired.lease();
			fresh = acquired.created();
		}
		ResumeResult result;
		try {
			result = resume(runId, forceTakeover, lease);
		}
		catch (RuntimeException ex) {
			if (fresh) {
				this.store.releaseRunLease(runId);
			}
			throw ex;
		}
		if (fresh) {
			if (result.outcome() == Outcome.ACCEPTED) {
				// I7：续租带上存活判据 —— 本进程仍在续跑（resuming 表里有它）且快照非终态；
				// 驱动任务收尾（finally 里摘除 resuming）后下一拍即停续租并还租，
				// 于是"驱动线程异常退出、快照停在 RUNNING"不会留下永远续租的僵尸租约。
				leases.markDriving(lease, () -> this.resuming.containsKey(runId) && snapshotNotTerminal(runId));
			}
			else {
				this.store.releaseRunLease(runId);
			}
		}
		return result;
	}

	/**
	 * 续跑（<b>带租约重载</b>，B23L1 批②b）：调用方已经是续跑权的主人，本方法<b>不再自抢</b>，
	 * 只校验归属 —— {@code LeaseService#verifyHeld(key, epoch)}（<b>对着 Redis 真值</b>，一次往返）。
	 *
	 * <p>为什么不用"抢"来校验：确认门在等待期就持租（交棒后继续持有），自抢会走可重入短路
	 * （同一个 epoch、恒成功），于是"拿着过期 epoch 的陈旧驱动方"也能进来 —— 那正是 fencing 要拦的。
	 * 为什么也不只比对 {@code heldRunEpoch}（进程内视图）：TTL 到期被别人接管后，视图要等下一次
	 * 续租失败才会清，这个窗口里视图会说谎，而本方法正是"要不要驱动这一轮"的入口。
	 * 对真值校验才能让"租约已被别的实例接管"在入口处变成 {@link Outcome#LEASE_HELD_ELSEWHERE}；
	 * 设施不可用（{@code Hold.UNAVAILABLE}）按无租约降级放行（I4 同口径）。
	 *
	 * <p>租约的收尾职责在<b>调用方</b>：本方法不 {@code markDriving}、不 {@code release}
	 * （确认门在 {@code runResume} 里做；旧签名在自己那一层做）。
	 *
	 * @param lease 调用方持有的租约；{@code null} = 无租约降级（单测/无 Redis 装配），
	 * 此时只由进程内互斥排他 —— 与批②b 之前的语义一致
	 */
	public ResumeResult resume(String runId, boolean forceTakeover, LeaseService.Lease lease) {
		if (lease != null) {
			// I3：归属校验对着 Redis 真值做（一次往返），不用进程内视图 —— 视图在"TTL 已到、续租还没失败"
			// 的窗口里会说谎，而这个判定正是"要不要驱动这一轮"的入口。
			LeaseService leases = this.store.leaseService();
			LeaseService.Hold hold = leases == null ? LeaseService.Hold.UNAVAILABLE
					: leases.verifyHeld(lease.key(), lease.epoch());
			if (hold == LeaseService.Hold.LOST) {
				return new ResumeResult(Outcome.LEASE_HELD_ELSEWHERE, Map.of("runId", runId, "resumed", false,
						// heldEpoch 是进程内视图（可能在"TTL 已到、续租未失败"窗口里滞后），只作排障参考
						"reason", "LEASE_HELD_ELSEWHERE", "staleEpoch", lease.epoch(),
						"heldEpoch", this.store.heldRunEpoch(runId), "instanceId", this.store.instanceId()));
			}
			// UNAVAILABLE：设施不可用 ⇒ 与 I4 同口径放行（本方法仍是"调用方已持租"的语义，
			// 只是这一拍校验不了；后面的杀招是 fence 写与 claimExecution 的 STALE_EPOCH）。
		}
		if (this.resuming.putIfAbsent(runId, Boolean.TRUE) != null) {
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
				// P1-10：续跑与首轮同一批策略 —— W/J/P 确定性失败后，剩余未执行项补 SKIPPED。
				PendingToolCall haltedBy = null;
				for (PendingToolCall item : snapshot.getInFlightToolCalls()) {
					// 以 Redis 的待决条目为准（快照里的 in-flight 副本停留在"挂起那一刻"）
					PendingToolCall fresh = this.store.pending(runId, item.getToolCallId());
					PendingToolCall effective = fresh == null ? item : fresh;
					if (haltedBy != null && isUnsettled(effective)) {
						responses.add(this.toolCallingManager.skipAsUpstreamFailed(runId, effective, haltedBy));
						rebuilt.add(effective.getToolCallId() + ":" + PendingToolCall.SKIPPED + ":not-executed");
						continue;
					}
					String text = this.toolCallingManager.resolvePending(runId, prompt, effective);
					// R6 裁决：标签必须在**结算之后**回读 Redis 才算准 ——
					// executeOnce 的认领/完成是写在 Redis 里的新副本上，传入的 effective 可能仍是旧值
					// （旧实现因此在"本次刚执行完"时显示 reused(null)）。
					PendingToolCall settled = this.store.pending(runId, effective.getToolCallId());
					PendingToolCall source = settled == null ? effective : settled;
					// T2 + P0-1：续跑重建的 role:tool 与首轮同形 —— 统一信封 JSON，message 先裁剪再组装
					// P1-10 对账措辞已由 executeOnce 写进 resultText，此处不再二次加工（重放同形）。
					responses.add(new ToolResponseMessage.ToolResponse(effective.getToolCallId(),
							effective.getName(),
							ToolResultEnvelope.of(source.effectiveOutcome(), source.effectiveCode(), text,
									effective.getArguments()).toModelJson(this.limiter)));
					rebuilt.add(settlementLabel(source));
					if (this.toolCallingManager.haltsBatch(source)) {
						haltedBy = source;
						log.warn("续跑批策略首错即停 runId={} 上游工具={} outcome={} code={}，剩余条目将置 SKIPPED",
								runId, source.getName(), source.effectiveOutcome().wire(), source.effectiveCode());
					}
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
						// 轮终态：完成订阅者 + 摘掉进程内互斥（ADR-5 的"轮终态主动释放"同族语义）。
						// 续跑权租约不在这里还：终态写进 RunStore.save 时 best-effort 释放（同一个
						// 唯一写口），此处再还一次只会在"写终态前"制造一个别人可接管的窗口。
						this.resuming.remove(runId);
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
				this.resuming.remove(runId);
			}
		}
	}

	/**
	 * 交棒后的存活判据（I7）：该轮快照仍存在且<b>非终态</b>。
	 *
	 * <p>与 {@code resuming} 表合用 = "本进程还有续跑任务在跑，且这一轮还没收尾"。读快照失败
	 * （Redis 抖动）按"仍活着"处理：误判为假会提前放掉一个正在跑的轮的续跑权。
	 */
	private boolean snapshotNotTerminal(String runId) {
		try {
			RunSnapshot snapshot = this.store.get(runId);
			return snapshot != null && !RunSnapshot.isTerminal(snapshot.getStatus());
		}
		catch (Exception ex) {
			return true;
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

	/**
	 * 停走级联时"仍未结算"的条目：未执行且状态仍是 {@code PENDING}（等外部输入/后端待执行）
	 * 或 {@code APPROVED}（已放行未执行）。已执行/已有结论的条目不得被覆盖成 SKIPPED。
	 */
	private static boolean isUnsettled(PendingToolCall pending) {
		if (pending == null || pending.isExecuted()) {
			return false;
		}
		String status = pending.getStatus();
		return PendingToolCall.PENDING.equals(status) || PendingToolCall.APPROVED.equals(status);
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

	// ── 互斥：进程内表 + 续跑权租约（B23L1 批②b 起不再用 ai:resume:lock:） ──────
	//
	// 原实现 acquireResumeLock/releaseResumeLock 用 SETNX 拿一把 ai:resume:lock:<runId>，
	// 释放是"GET → Java 比对 → DEL"：比对与删除之间锁可能已过期并被别的实例重抢，于是
	// 删掉别人的锁（同一缺陷族见 LeaseService 类注 L6）。互斥语义整体并入续跑权租约：
	//   · 跨实例 = ai:lease:run:<runId>（epoch 比对在 Lua 里原子完成）；
	//   · 同进程 = 上面这张 resuming 表（租约可重入，挡不住同进程的两次并发续跑）。
	// 全仓 grep 确认 ai:resume:lock: 无外部读者后才摘除（运维文档里的历史叙述保留）。

}
