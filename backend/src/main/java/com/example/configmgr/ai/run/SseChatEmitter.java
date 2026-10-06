package com.example.configmgr.ai.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

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
 * {@code confirm_*}/{@code frontend_tool_*}/{@code heartbeat}/{@code suspended} →
 * 恰好一个 {@code done} 或 {@code error} 终帧。帧体是 JSON 对象且带 {@code type} 字段
 * （无名 SSE 事件 + 帧内 type），与基座 SSE 形态一致，前端不必改用 addEventListener。
 *
 * <p>
 * 写出失败（客户端断开）只记日志、摘除该订阅者，不打断运行线程；终帧与
 * {@code emitter.complete()} 由调用方（{@code AiController} / {@code ResumeService}）保证。
 */
@Slf4j
public class SseChatEmitter {

	private final String runId;

	private final RunStore store;

	private final ObjectMapper objectMapper;

	private final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();

	public SseChatEmitter(String runId, RunStore store, ObjectMapper objectMapper) {
		this.runId = runId;
		this.store = store;
		this.objectMapper = objectMapper;
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
		frame.put("text", text);
		emit(frame);
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
		frame.put("callback", "POST /api/ai/frontend-tool-result {runId, toolCallId, result}");
		emit(frame);
	}

	public void frontendToolResult(PendingToolCall pending, boolean ok, String result) {
		Map<String, Object> frame = frame("frontend_tool_result");
		frame.put("toolCallId", pending.getToolCallId());
		frame.put("name", pending.getName());
		frame.put("ok", ok);
		frame.put("executed", false);
		frame.put("result", result);
		emit(frame);
	}

	public void done(Map<String, Object> usage, String model) {
		Map<String, Object> frame = frame("done");
		frame.put("runId", this.runId);
		if (usage != null && !usage.isEmpty()) {
			frame.put("usage", usage);
		}
		if (model != null) {
			frame.put("model", model);
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
	 * {@code start} / {@code suspended} / {@code confirm_request} / {@code tool_*} /
	 * {@code confirm_decision} / {@code done} / {@code error} 全部保留 —— 重挂者据此重建
	 * "这一轮发生过什么"，包括已经结束的轮次的终帧。
	 *
	 * @param includeDelta true = 连 delta/heartbeat 一起回放（取证用）
	 * @return 实际发出的帧数
	 */
	public int replayTo(SseEmitter emitter, boolean includeDelta) {
		List<Map<String, Object>> frames = this.store.events(this.runId);
		int sent = 0;
		for (Map<String, Object> frame : frames) {
			String type = String.valueOf(frame.get("type"));
			if (!includeDelta && ("delta".equals(type) || "heartbeat".equals(type))) {
				continue;
			}
			if (!sendTo(emitter, frame)) {
				break;
			}
			sent++;
		}
		return sent;
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

	private void emit(Map<String, Object> frame) {
		frame.putIfAbsent("runId", this.runId);
		this.store.appendEvent(this.runId, frame);
		for (SseEmitter emitter : this.subscribers) {
			sendTo(emitter, frame);
		}
	}

	private boolean sendTo(SseEmitter emitter, Map<String, Object> frame) {
		try {
			emitter.send(this.objectMapper.writeValueAsString(frame));
			return true;
		}
		catch (IOException ex) {
			log.debug("SSE 写出失败（客户端断开？）runId={}: {}", this.runId, ex.getMessage());
			this.subscribers.remove(emitter);
			return false;
		}
		catch (Exception ex) {
			log.debug("SSE 写出异常 runId={}: {}", this.runId, ex.getMessage());
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
