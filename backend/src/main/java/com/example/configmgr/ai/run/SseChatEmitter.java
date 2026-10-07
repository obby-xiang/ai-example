package com.example.configmgr.ai.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单轮对话的 SSE 帧写出器（S4.2 §3 时序的终端契约）。
 *
 * <p>
 * 与第一棒相比的两点变化（M3 挂起态外置 + ADR-5 reattach 的必然结果）：
 * <ol>
 * <li><b>多订阅者扇出</b>：一帧发给本轮全部订阅者 —— 首轮客户端断开重连后，
 * {@code GET /api/ai/events/{runId}} 可以挂到同一个写出器上继续收流
 * （ADR-5 补记：409 携 runId + reattach 重挂进行中轮的流）；</li>
 * <li><b>每帧同时外置</b>：帧原文进 {@code ai:events:<runId>}（{@link RunStore}），
 * 于是"进程在挂起期死亡"不再连 SSE 时序一起丢，重挂时先回放外置帧再续接实时帧。</li>
 * </ol>
 *
 * <p>
 * 帧类型：{@code start}（首包）→ 若干 {@code delta}/{@code tool_*}/
 * {@code retry}/{@code confirm_*}/{@code frontend_tool_*}/{@code heartbeat}/{@code suspended} →
 * 恰好一个 {@code done}（正常完成或取消：{@code cancelled=true}/{@code false}）或 {@code error} 终帧。帧体是 JSON 对象且带 {@code type} 字段
 * （无名 SSE 事件 + 帧内 type），与基座 SSE 形态一致，前端不必改用 addEventListener。
 *
 * <p>
 * 写出失败（客户端断开）只记日志、摘除该订阅者，不打断运行线程；终帧与
 * {@code emitter.complete()} 由调用方（{@code AiController} / {@code ResumeService}）保证。
 *
 * <p>
 * <b>序号口径（S5c-1）</b>：业务帧带单调 {@code seq}（自 1 起、跨进程从归档续号），
 * 心跳帧<b>不占</b>业务序号，只在独立空间里带 {@code heartbeatSeq} —— 于是"归档末帧"
 * 这个跨进程续号锚点恒等于已发放过的最大业务号，差量重挂不会把重启后的新帧当孤儿丢掉。
 * <b>出帧串行（S5c-3）</b>：取号、落归档、写出在同一把锁内完成，多线程出帧的到达顺序
 * 与 seq 顺序一致。
 */
@Slf4j
public class SseChatEmitter {

	private final String runId;

	private final RunStore store;

	private final ObjectMapper objectMapper;

	private final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();

	/** T6：本轮<b>业务帧</b>的单调序号（首帧起自 1；跨进程/重启后从外置归档续号，见 {@link #nextSeq()}）。 */
	private final AtomicLong seq = new AtomicLong(-1);

	private final Object seqLock = new Object();

	/**
	 * S5c-1：心跳的<b>独立</b>序号空间（自 1 起）。
	 *
	 * <p>
	 * 心跳不取业务 {@code seq}：心跳不落归档（T7），若它占用业务序号，则"归档末帧"这个
	 * 跨进程续号锚点就会落后于实际发放过的号 —— 重启后的新写出器会重用心跳用过的号，
	 * 而该号已在前端的 {@code lastSeq} 覆盖范围内，于是新帧在差量重挂里被判为孤儿而
	 * 永久丢弃（DC-14 §3.6 实测形态）。独立计数保留"挂起期心跳到第几次"的可观测性，
	 * 又与业务时间线彻底解耦。
	 */
	private final AtomicLong heartbeatSeq = new AtomicLong(0);

	/**
	 * 出帧互斥（S5c-3 / S5c-2）：取号 → 落归档 → 写出必须是一次不可分割的动作。
	 *
	 * <p>
	 * 为什么必须有：<b>出帧线程不唯一</b> —— 运行线程在挂起等待里每 2s 发心跳
	 * （{@code ConfirmGate#await}），HTTP 线程在 {@code POST /api/ai/confirm|frontend-tool-result}
	 * 里发决策/回灌帧。不加锁时先取号的线程可能后写出，订阅者看到的到达顺序可以逆序
	 * （实测已复现，见证据文档 S5c-3）。
	 *
	 * <p>
	 * 同一把锁也让 {@link #replayAndAttach} 的"回放 + 挂订阅"成为原子段落（S5c-2），
	 * 消掉 reattach 的交接丢帧窗口。
	 */
	private final Object emitLock = new Object();

	/** T5：本轮 assistant 正文消息的身份（结果铸为消息 / 消息级渲染去重的判据）。 */
	private final String assistantMessageId;

	/** T5：正文消息边界是否已开（start/content/end 三段式的 start 只发一次）。 */
	private final AtomicBoolean textSegmentOpen = new AtomicBoolean(false);

	public SseChatEmitter(String runId, RunStore store, ObjectMapper objectMapper) {
		this.runId = runId;
		this.store = store;
		this.objectMapper = objectMapper;
		this.assistantMessageId = "assistant-" + runId;
	}

	public String runId() {
		return this.runId;
	}

	/** 挂一个订阅者（首轮响应 / reattach 重挂共用）。 */
	public void attach(SseEmitter emitter) {
		this.subscribers.add(emitter);
		emitter.onCompletion(() -> this.subscribers.remove(emitter));
		emitter.onTimeout(() -> this.subscribers.remove(emitter));
		emitter.onError(ex -> this.subscribers.remove(emitter));
	}

	/**
	 * <b>S5c-2</b>：重挂的原子交接 —— 回放外置帧与挂上订阅者在<b>同一把出帧锁</b>内完成。
	 *
	 * <p>
	 * 为什么必须原子：{@code 回放 → attach} 两段之间若有实时帧写出，那一帧既不在回放快照里、
	 * 也还没被这个订阅者接住 = 丢帧窗口（且 SSE 丢帧不报错，只会静默少一段）。
	 *
	 * @param terminal true = 该轮已终态（回放完即收尾，不再订阅）
	 * @return 实际回放的帧数
	 */
	public int replayAndAttach(SseEmitter emitter, Long lastSeq, boolean terminal) {
		synchronized (this.emitLock) {
			int sent = replayLocked(emitter, false, lastSeq);
			if (terminal) {
				complete(emitter);
			}
			else {
				attach(emitter);
			}
			return sent;
		}
	}

	public int subscriberCount() {
		return this.subscribers.size();
	}

	// ── 帧 ──────────────────────────────────────────────────────────────────

	/** 首包：runId 由服务端生成，前端据此做 reattach。 */
	public void start(String sessionId) {
		Map<String, Object> frame = frame("start");
		frame.put("runId", this.runId);
		frame.put("sessionId", sessionId);
		emit(frame);
	}

	public void delta(String text) {
		Map<String, Object> frame = frame("delta");
		frame.put("messageId", this.assistantMessageId);
		frame.put("text", text);
		emit(frame);
	}

	/**
	 * 消息边界（T5 / R8-2）：正文段（kind={@code text}）或思考段（kind={@code reasoning}）的
	 * start / end 标记，配合中间的 content 帧（{@code delta}）构成三段式生命周期。
	 *
	 * <p>
	 * 为什么需要它：一条 assistant 消息可能"先吐思考、再吐正文、中间夹工具调用"，
	 * 而前端此前只有 content 帧 —— 无法判断两段文本属于同一条消息还是两条
	 * （SP-01ab §7.3 的混合消息渲染痛点）。边界帧 + {@code messageId} 让"哪几帧属于同一条消息"
	 * 成为协议事实，而不是靠客户端猜。
	 *
	 * <p>
	 * 纯增量：老客户端不认识这两个帧类型会忽略它们，content 帧照旧可独立渲染。
	 */
	public void messageBoundary(String phase, String kind, Map<String, Object> extra) {
		Map<String, Object> frame = frame("message_" + phase);
		frame.put("messageId", this.assistantMessageId);
		frame.put("kind", kind);
		if (extra != null) {
			frame.putAll(extra);
		}
		emit(frame);
	}

	/** 正文段 start（同一条消息只开一次；重复调用无副作用）。 */
	public boolean textStart() {
		if (!this.textSegmentOpen.compareAndSet(false, true)) {
			return false;
		}
		messageBoundary("start", "text", null);
		return true;
	}

	/** 正文段 end（带本段字符数，便于前端与 done 帧对账）；未开过段则不发（幂等）。 */
	public boolean textEnd(int chars) {
		if (!this.textSegmentOpen.compareAndSet(true, false)) {
			return false;
		}
		messageBoundary("end", "text", Map.of("chars", chars));
		return true;
	}

	/** 挂起公告：快照已外置（带 Redis 键名与写入者实例，供前端"可重挂"提示）。 */
	public void suspended(List<PendingToolCall> pendings, String stateKey, String pendingKey, String writtenBy) {
		Map<String, Object> frame = frame("suspended");
		frame.put("runId", this.runId);
		frame.put("toolCalls", pendings.stream().map(PendingToolCall::asMap).toList());
		frame.put("stateKey", stateKey);
		frame.put("pendingKey", pendingKey);
		frame.put("writtenBy", writtenBy);
		emit(frame);
	}

	/** 挂起期心跳：证明"挂起期间连接仍然活着"，也是前端展示倒计时的节拍。 */
	public void heartbeat(Map<String, Object> data) {
		Map<String, Object> frame = frame("heartbeat");
		frame.putAll(data);
		emit(frame);
	}

	public void toolStart(PendingToolCall pending) {
		Map<String, Object> frame = frame("tool_start");
		frame.put("toolCallId", pending.getToolCallId());
		frame.put("name", pending.getName());
		frame.put("kind", pending.getKind());
		frame.put("args", pending.getArguments());
		emit(frame);
	}

	public void toolResult(PendingToolCall pending, boolean ok, boolean reused, String result) {
		Map<String, Object> frame = frame("tool_result");
		frame.put("toolCallId", pending.getToolCallId());
		frame.put("name", pending.getName());
		frame.put("kind", pending.getKind());
		frame.put("ok", ok);
		frame.put("executed", pending.isExecuted());
		frame.put("executedBy", pending.getExecutedBy());
		frame.put("reused", reused);
		frame.put("status", pending.getStatus());
		frame.put("messageId", toolMessageId(pending));
		frame.put("result", result);
		emit(frame);
	}

	public void confirmRequest(PendingToolCall pending, int timeoutSeconds) {
		Map<String, Object> frame = frame("confirm_request");
		frame.put("toolCallId", pending.getToolCallId());
		frame.put("name", pending.getName());
		frame.put("args", pending.getArguments());
		frame.put("summary", pending.getArguments());
		frame.put("timeoutSeconds", timeoutSeconds);
		frame.put("expiresAt", expiresAtEpochMs(timeoutSeconds));
		frame.put("callback", "POST /api/ai/confirm {runId, toolCallId, approved, reason}");
		emit(frame);
	}

	public void confirmDecision(PendingToolCall pending, String decision, String reason, long waitedMs) {
		Map<String, Object> frame = frame("confirm_decision");
		frame.put("toolCallId", pending.getToolCallId());
		frame.put("name", pending.getName());
		frame.put("decision", decision);
		frame.put("approved", PendingToolCall.APPROVED.equals(pending.getStatus()));
		frame.put("status", pending.getStatus());
		frame.put("reason", reason);
		frame.put("waitedMs", waitedMs);
		emit(frame);
	}

	public void frontendToolRequest(PendingToolCall pending, int timeoutSeconds) {
		Map<String, Object> frame = frame("frontend_tool_request");
		frame.put("toolCallId", pending.getToolCallId());
		frame.put("name", pending.getName());
		frame.put("args", pending.getArguments());
		frame.put("timeoutSeconds", timeoutSeconds);
		// T4 同口径（纯增量）：前端工具挂起与确认门共用同一个等待上限，绝对时钟一并给出，
		// 前端不必用本地 "120s 常量" 推断到期时刻。
		frame.put("expiresAt", expiresAtEpochMs(timeoutSeconds));
		frame.put("callback", "POST /api/ai/frontend-tool-result {runId, toolCallId, result}");
		emit(frame);
	}

	public void frontendToolResult(PendingToolCall pending, boolean ok, String result) {
		Map<String, Object> frame = frame("frontend_tool_result");
		frame.put("toolCallId", pending.getToolCallId());
		frame.put("name", pending.getName());
		frame.put("ok", ok);
		frame.put("executed", false);
		frame.put("status", pending.getStatus());
		frame.put("messageId", toolMessageId(pending));
		frame.put("result", result);
		emit(frame);
	}

	/**
	 * 断流重试公告（韧性棒）：本轮第 {@code nextAttempt} 次尝试即将开始 ——
	 * 前端据此展示"上游连接中断，正在重试"，并知道本轮<b>没有</b>产出过内容
	 * （重试三条件保证：已产出即不重试）。
	 */
	public void retry(Map<String, Object> data) {
		Map<String, Object> frame = frame("retry");
		frame.putAll(data);
		emit(frame);
	}

	/**
	 * 终帧：{@code done}。
	 *
	 * @param extra 附加信息（韧性棒：{@code cancelled} / {@code attempts} /
	 * {@code upstreamEvents} / {@code terminalSignal}）；取消终态为 {@code cancelled=true}，
	 * 与"正常完成"共用同一个终帧类型（前端的终态处理不必分两套）。
	 */
	public void done(Map<String, Object> usage, String model, Map<String, Object> extra) {
		Map<String, Object> frame = frame("done");
		frame.put("runId", this.runId);
		if (usage != null && !usage.isEmpty()) {
			frame.put("usage", usage);
		}
		if (model != null) {
			frame.put("model", model);
		}
		if (extra != null) {
			frame.putAll(extra);
		}
		emit(frame);
	}

	public void error(String code, String message) {
		Map<String, Object> frame = frame("error");
		frame.put("runId", this.runId);
		frame.put("code", code);
		frame.put("message", message);
		emit(frame);
	}

	// ── 重挂回放 ────────────────────────────────────────────────────────────

	/**
	 * 把外置帧回放给一个新订阅者（{@code GET /api/ai/events/{runId}} 的第一段）。
	 *
	 * <p>
	 * 默认只回放<b>状态类帧</b>（跳过 {@code delta}/{@code heartbeat}）：挂起中的轮次不产出正文，
	 * 回放与订阅之间没有 delta 竞态，前端也就不会把同一段文本渲染两遍；而
	 * {@code start} / {@code message_*} / {@code suspended} / {@code confirm_request} / {@code tool_*} /
	 * {@code confirm_decision} / {@code done} / {@code error} 全部保留 —— 重挂者据此重建
	 * "这一轮发生过什么"，包括已经结束的轮次的终帧。
	 *
	 * <p>
	 * <b>T6 增量补发 + 孤儿过滤</b>：给了 {@code lastSeq}（前端已知的最大帧序号）时，
	 * 只回放 {@code seq > lastSeq} 的帧 —— 孤儿（seq ≤ lastSeq，前端本地已有）不重发，
	 * 于是长轮次的 reattach 代价从"整轮全部帧"降到"差量"。没有 {@code seq} 的老帧
	 * （升级前写进归档的）无法判定，按"照发"处理（宁可重复，不可丢帧）。
	 *
	 * @param includeDelta true = 连 delta/heartbeat 一起回放（取证用）
	 * @param lastSeq      前端已知的最大 seq；null = 全量回放
	 * @return 实际发出的帧数
	 */
	public int replayTo(SseEmitter emitter, boolean includeDelta, Long lastSeq) {
		synchronized (this.emitLock) {
			return replayLocked(emitter, includeDelta, lastSeq);
		}
	}

	/** 回放本体（调用方持 {@link #emitLock}；见 {@link #replayAndAttach} 的原子交接）。 */
	private int replayLocked(SseEmitter emitter, boolean includeDelta, Long lastSeq) {
		List<Map<String, Object>> frames = this.store.events(this.runId);
		int sent = 0;
		for (Map<String, Object> frame : frames) {
			String type = String.valueOf(frame.get("type"));
			if (!includeDelta && ("delta".equals(type) || "heartbeat".equals(type))) {
				continue;
			}
			if (isOrphan(frame, lastSeq)) {
				continue;
			}
			if (!sendTo(emitter, frame)) {
				break;
			}
			sent++;
		}
		return sent;
	}

	/** 孤儿判定：帧序号不大于前端已知的最大序号 ⇒ 该帧前端已有，不重发。 */
	private static boolean isOrphan(Map<String, Object> frame, Long lastSeq) {
		if (lastSeq == null) {
			return false;
		}
		Object seqValue = frame.get("seq");
		if (seqValue instanceof Number number) {
			return number.longValue() <= lastSeq;
		}
		return false;
	}

	public int persistedFrameCount() {
		return this.store.events(this.runId).size();
	}

	// ── 内部 ────────────────────────────────────────────────────────────────

	private Map<String, Object> frame(String type) {
		Map<String, Object> frame = new LinkedHashMap<>();
		frame.put("type", type);
		return frame;
	}

	/**
	 * 下一帧的单调序号（T6）。
	 *
	 * <p>
	 * 首帧前惰性从外置归档续号（{@link RunStore#lastEventSeq}）：进程重启后由续跑新建的
	 * 写出器<b>不会从 0 重来</b>，否则归档里会出现两段重叠的序号，前端的 {@code lastSeq}
	 * 差量补发就会漏帧。归档为空（全新轮次）则从 1 开始。
	 *
	 * <p>
	 * S5c-1：这条"归档末帧 = 续号锚点"的推理成立的前提是<b>心跳不占业务号</b>
	 * （见 {@link #emit}）—— 否则归档末帧会落后于实际发放的号，重启后就会重号。
	 */
	private long nextSeq() {
		if (this.seq.get() < 0) {
			synchronized (this.seqLock) {
				if (this.seq.get() < 0) {
					this.seq.set(this.store.lastEventSeq(this.runId));
				}
			}
		}
		return this.seq.incrementAndGet();
	}

	private void emit(Map<String, Object> frame) {
		frame.putIfAbsent("runId", this.runId);
		// S5c-3：取号 → 落归档 → 写出在同一把锁内完成 —— 多线程（运行线程的心跳 / HTTP 线程的
		// 决策与回灌）并发出帧时，订阅者看到的先后与 seq 的先后必然一致。
		synchronized (this.emitLock) {
			if (RunStore.HEARTBEAT_TYPE.equals(String.valueOf(frame.get("type")))) {
				// T7：心跳照常广播（下面的 subscribers），但不进归档窗口 —— 只留一个轻量活动戳，
				// 让"这轮最近有过活动"仍可跨进程判定（僵尸判据的输入）。归档侧的同类跳过见
				// RunStore#appendEvent（任何其他调用方直接塞心跳帧也不会占位）。
				// S5c-1：心跳<b>不取业务 seq</b>，只在独立序号空间里计数 —— 于是"归档末帧"
				// （跨进程续号锚点）恒等于已发放过的最大业务号，心跳永远不会把它顶到前面去。
				frame.put("heartbeatSeq", this.heartbeatSeq.incrementAndGet());
				this.store.touchActivity(this.runId);
			}
			else {
				frame.put("seq", nextSeq());
				this.store.appendEvent(this.runId, frame);
			}
			for (SseEmitter emitter : this.subscribers) {
				sendTo(emitter, frame);
			}
		}
	}

	/** T4：挂起等待的绝对到期时刻（= 本帧生成时刻 + 等待上限）。 */
	private static long expiresAtEpochMs(int timeoutSeconds) {
		return System.currentTimeMillis() + Math.max(0, timeoutSeconds) * 1000L;
	}

	/** T5：工具结果消息的身份（结果铸为消息）—— 由 toolCallId 确定性派生，重放/重挂不变。 */
	private static String toolMessageId(PendingToolCall pending) {
		return "tool-" + pending.getToolCallId();
	}

	private boolean sendTo(SseEmitter emitter, Map<String, Object> frame) {
		try {
			emitter.send(this.objectMapper.writeValueAsString(frame));
			return true;
		}
		catch (Exception ex) {
			// Q8② 第二层防护：对端断开的写失败（IOException / AsyncRequestNotUsableException /
			// IllegalStateException: 已完成的 emitter）一律吞没在这里 —— 它是"这一个订阅者没了"，
			// 不是本轮失败。若让它冒泡到 HTTP 栈，全局异常处理器会对一个内容类型已固定为
			// text/event-stream 的响应再写一次 JSON，抛 HttpMessageNotWritableException（日志噪音）。
			log.debug("SSE 写出失败（客户端断开？）runId={} type={}: {}", this.runId, frame.get("type"), ex.getMessage());
			this.subscribers.remove(emitter);
			return false;
		}
	}

	public void complete() {
		for (SseEmitter emitter : this.subscribers) {
			try {
				emitter.complete();
			}
			catch (Exception ex) {
				log.debug("SSE complete 失败 runId={}: {}", this.runId, ex.getMessage());
			}
		}
		this.subscribers.clear();
	}

	/** 完成单个订阅者（reattach 到已终态的轮次：回放完就收尾）。 */
	public void complete(SseEmitter emitter) {
		this.subscribers.remove(emitter);
		try {
			emitter.complete();
		}
		catch (Exception ex) {
			log.debug("SSE complete 失败 runId={}: {}", this.runId, ex.getMessage());
		}
	}

}
