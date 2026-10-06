package com.example.spike.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.spike.config.SpikeProperties;
import com.example.spike.tools.ToolRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;

/**
 * 单个工具调用的“结算器”：阻塞式（进程内挂起）与续跑式（重启后只读 Redis）两条路径共用同一段结算逻辑，
 * 差别只在“等待”这一步。
 *
 * <ul>
 * <li><b>阻塞路径</b>（{@link #executeBlocking}）：登记进程内唤醒开关 → 发请求事件 → 阻塞等待，
 * 醒来后<strong>重新从 Redis 读</strong>挂起条目并结算；</li>
 * <li><b>续跑路径</b>（{@link #resolve}）：不等待任何东西，直接按 Redis 里的状态结算；若仍
 * {@code PENDING} 则抛 {@link PendingUnresolvedException}，由调用方保持 SUSPENDED 并退出。</li>
 * </ul>
 *
 * <p>
 * <b>不重放的两道闸</b>：① 后端工具在执行前先查台账（{@code sp02:ledger:<runId>}，跨进程），
 * 已执行过就复用原结果文本、不再执行；② 前端工具/确认门的“请求”只在阻塞路径发出，续跑路径永不重发。
 */
public class ToolCallExecutor {

	private static final Logger log = LoggerFactory.getLogger(ToolCallExecutor.class);

	private final ToolCallingManager delegate;

	private final RunStore store;

	private final RuntimeRegistry registry;

	private final SpikeProperties properties;

	private final ObjectMapper mapper;

	public ToolCallExecutor(ToolCallingManager delegate, RunStore store, RuntimeRegistry registry,
			SpikeProperties properties, ObjectMapper mapper) {
		this.delegate = delegate;
		this.store = store;
		this.registry = registry;
		this.properties = properties;
		this.mapper = mapper;
	}

	public ToolResponseMessage.ToolResponse executeBlocking(String runId, Prompt prompt, PendingToolCall pending) {
		RunRuntime runtime = this.registry.runtime(runId);
		ToolRegistry.Kind kind = ToolRegistry.Kind.valueOf(pending.kind);
		if (kind == ToolRegistry.Kind.BACKEND) {
			// 后端工具不需要等外部输入：直接执行（台账保证不重复执行）
			return resolve(runId, prompt, pending, "blocking");
		}
		int timeout = kind == ToolRegistry.Kind.FRONTEND ? this.properties.getFrontendToolTimeoutSeconds()
				: this.properties.getConfirmTimeoutSeconds();

		// 先登记进程内唤醒开关，再发请求事件：避免“外部输入已到、门还没登记”导致白等到超时
		Gate<Boolean> gate = kind == ToolRegistry.Kind.FRONTEND ? runtime.registerFrontendGate(pending.toolCallId)
				: runtime.registerConfirmGate(pending.toolCallId);
		announcePending(runId, pending);

		gate.await(timeout);
		if (kind == ToolRegistry.Kind.FRONTEND) {
			runtime.clearFrontendGate(pending.toolCallId);
		}
		else {
			runtime.clearConfirmGate(pending.toolCallId);
		}

		PendingToolCall fresh = this.store.pending(runId, pending.toolCallId);
		if (fresh == null) {
			fresh = pending;
		}
		if (PendingToolCall.PENDING.equals(fresh.status)) {
			// 超时：把“未获得输入”这一结局也写进外置状态，语义随工具类别而定
			fresh.status = PendingToolCall.TIMEOUT;
			fresh.resolvedAtMs = System.currentTimeMillis();
			fresh.executed = false;
			fresh.resultText = kind == ToolRegistry.Kind.FRONTEND
					? "前端工具 " + fresh.name + " 未在 " + timeout + " 秒内回传执行结果（前端超时），本次调用未获得数据。"
					: "确认等待超过 " + timeout + " 秒，系统已自动取消该操作（工具未执行）。";
			fresh.reason = kind == ToolRegistry.Kind.FRONTEND ? "FRONTEND_TIMEOUT" : "CONFIRM_TIMEOUT";
			this.store.putPending(runId, fresh);
			Map<String, Object> timeoutEvent = data();
			timeoutEvent.put("toolCallId", fresh.toolCallId);
			timeoutEvent.put("name", fresh.name);
			timeoutEvent.put("status", fresh.status);
			timeoutEvent.put("waitedSeconds", timeout);
			runtime.emit("suspend_timeout", timeoutEvent);
		}
		return resolve(runId, prompt, fresh, "blocking");
	}

	/** 把“需要外部输入”的挂起项公告出去（阻塞式与调用方驱动式共用同一事件形状）。 */
	public void announcePending(String runId, PendingToolCall pending) {
		ToolRegistry.Kind kind = ToolRegistry.Kind.valueOf(pending.kind);
		if (kind == ToolRegistry.Kind.BACKEND) {
			return;
		}
		int timeout = kind == ToolRegistry.Kind.FRONTEND ? this.properties.getFrontendToolTimeoutSeconds()
				: this.properties.getConfirmTimeoutSeconds();
		Map<String, Object> req = data();
		req.put("toolCallId", pending.toolCallId);
		req.put("name", pending.name);
		req.put("kind", pending.kind);
		req.put("arguments", pending.arguments);
		req.put("timeoutSeconds", timeout);
		req.put("stateKey", this.store.hitlKey(runId));
		req.put("callback", kind == ToolRegistry.Kind.FRONTEND
				? "POST /api/frontend-tool-result {runId, toolCallId, result}"
				: "POST /api/confirm {runId, toolCallId, approved, reason}");
		this.registry.runtime(runId).emit(
				kind == ToolRegistry.Kind.FRONTEND ? "frontend_tool_request" : "confirm_request", req);
	}

	/** 续跑路径的结算：只读外置状态；未落定 → 抛异常（调用方保持 SUSPENDED）。 */
	public ToolResponseMessage.ToolResponse resolve(String runId, Prompt prompt, PendingToolCall pending,
			String path) {
		RunRuntime runtime = this.registry.runtime(runId);
		ToolRegistry.Kind kind = ToolRegistry.Kind.valueOf(pending.kind);

		if (kind == ToolRegistry.Kind.BACKEND) {
			return backendTool(runId, prompt, pending, path);
		}

		if (PendingToolCall.PENDING.equals(pending.status)) {
			throw new PendingUnresolvedException(pending);
		}

		if (kind == ToolRegistry.Kind.FRONTEND) {
			Map<String, Object> event = data();
			event.put("toolCallId", pending.toolCallId);
			event.put("name", pending.name);
			event.put("status", pending.status);
			event.put("executed", false);
			event.put("result", truncate(pending.resultText));
			event.put("path", path);
			event.put("resolvedBy", this.store.instanceId());
			runtime.emit(PendingToolCall.FRONTEND_RESULT.equals(pending.status) ? "frontend_tool_result" : "tool_result",
					event);
			return new ToolResponseMessage.ToolResponse(pending.toolCallId, pending.name, pending.resultText);
		}

		// SENSITIVE
		Map<String, Object> decision = data();
		decision.put("toolCallId", pending.toolCallId);
		decision.put("name", pending.name);
		decision.put("status", pending.status);
		decision.put("reason", pending.reason);
		decision.put("executed", pending.executed);
		decision.put("executedBy", pending.executedBy);
		decision.put("path", path);
		decision.put("resolvedBy", this.store.instanceId());
		runtime.emit("confirm_resolution", decision);

		if (PendingToolCall.APPROVED.equals(pending.status)) {
			return backendTool(runId, prompt, pending, path);
		}
		Map<String, Object> res = data();
		res.put("toolCallId", pending.toolCallId);
		res.put("name", pending.name);
		res.put("ok", false);
		res.put("executed", false);
		res.put("path", path);
		res.put("result", truncate(pending.resultText));
		runtime.emit("tool_result", res);
		return new ToolResponseMessage.ToolResponse(pending.toolCallId, pending.name, pending.resultText);
	}

	/**
	 * 后端工具（含敏感工具被放行后的真实执行）：执行前先查跨进程台账，已执行过则复用原结果。
	 */
	private ToolResponseMessage.ToolResponse backendTool(String runId, Prompt prompt, PendingToolCall pending,
			String path) {
		RunRuntime runtime = this.registry.runtime(runId);
		Map<String, Object> already = this.store.ledgerFor(runId, pending.toolCallId);
		if (already != null) {
			Map<String, Object> event = data();
			event.put("toolCallId", pending.toolCallId);
			event.put("name", pending.name);
			event.put("kind", pending.kind);
			event.put("executed", true);
			event.put("reused", true);
			event.put("executedBy", already.get("executedBy"));
			event.put("path", path);
			event.put("result", truncate(String.valueOf(already.get("resultText"))));
			runtime.emit("tool_result", event);
			return new ToolResponseMessage.ToolResponse(pending.toolCallId, pending.name,
					String.valueOf(already.get("resultText")));
		}

		// 故障注入窗口：approve 之后、真正执行之前（复现“已放行未执行”时进程死亡）
		long delay = this.properties.getExecDelayMsAfterApprove();
		if (delay > 0 && PendingToolCall.APPROVED.equals(pending.status)) {
			Map<String, Object> marker = data();
			marker.put("toolCallId", pending.toolCallId);
			marker.put("name", pending.name);
			marker.put("delayMs", delay);
			marker.put("status", pending.status);
			marker.put("executed", false);
			marker.put("executedBy", this.store.instanceId());
			runtime.emit("exec_pre_delay", marker);
			try {
				Thread.sleep(delay);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}

		String text;
		boolean ok = true;
		try {
			text = delegateSingle(prompt, pending);
		}
		catch (Exception ex) {
			log.warn("[SP02] tool execution failed name={}", pending.name, ex);
			ok = false;
			text = "工具执行失败：" + ex.getMessage();
		}
		this.store.appendLedger(runId, pending.toolCallId, pending.name, pending.kind, text);

		pending.executed = ok;
		pending.executedBy = this.store.instanceId();
		pending.executedAtMs = System.currentTimeMillis();
		pending.resultText = text;
		if (PendingToolCall.PENDING.equals(pending.status) || PendingToolCall.TIMEOUT.equals(pending.status)) {
			pending.status = PendingToolCall.FRONTEND_RESULT;
			pending.resolvedAtMs = pending.executedAtMs;
			pending.reason = "IN_PROCESS_EXECUTION";
		}
		this.store.putPending(runId, pending);

		Map<String, Object> event = data();
		event.put("toolCallId", pending.toolCallId);
		event.put("name", pending.name);
		event.put("kind", pending.kind);
		event.put("ok", ok);
		event.put("executed", ok);
		event.put("reused", false);
		event.put("executedBy", pending.executedBy);
		event.put("path", path);
		event.put("result", truncate(text));
		runtime.emit("tool_result", event);
		return new ToolResponseMessage.ToolResponse(pending.toolCallId, pending.name, text);
	}

	/** 用官方 DefaultToolCallingManager 执行单个工具调用（只借官方执行，不接管协议）。 */
	private String delegateSingle(Prompt prompt, PendingToolCall pending) {
		AssistantMessage.ToolCall call = new AssistantMessage.ToolCall(pending.toolCallId,
				pending.type == null ? "function" : pending.type, pending.name, pending.arguments);
		AssistantMessage single = AssistantMessage.builder().toolCalls(List.of(call)).build();
		ToolExecutionResult result = this.delegate.executeToolCalls(prompt,
				new ChatResponse(List.of(new Generation(single))));
		return result.conversationHistory()
			.stream()
			.filter(ToolResponseMessage.class::isInstance)
			.map(ToolResponseMessage.class::cast)
			.flatMap(message -> message.getResponses().stream())
			.filter(response -> pending.toolCallId.equals(response.id()))
			.map(ToolResponseMessage.ToolResponse::responseData)
			.findFirst()
			.orElse("");
	}

	public Map<String, Object> parseArgs(String argumentsJson) {
		if (argumentsJson == null || argumentsJson.isBlank()) {
			return new LinkedHashMap<>();
		}
		try {
			return this.mapper.readValue(argumentsJson, new TypeReference<Map<String, Object>>() {
			});
		}
		catch (Exception ex) {
			return Map.of("_raw", argumentsJson);
		}
	}

	public static List<PendingToolCall> newPendings(AssistantMessage assistant, String runId) {
		List<PendingToolCall> out = new ArrayList<>();
		for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
			PendingToolCall item = new PendingToolCall();
			item.toolCallId = call.id();
			item.name = call.name();
			item.type = call.type() == null ? "function" : call.type();
			item.kind = ToolRegistry.kindOf(call.name()).name();
			item.arguments = call.arguments();
			item.status = PendingToolCall.PENDING;
			item.requestedAtMs = System.currentTimeMillis();
			out.add(item);
		}
		return out;
	}

	private String truncate(String text) {
		if (text == null) {
			return "";
		}
		return text.length() > 800 ? text.substring(0, 800) + "…" : text;
	}

	private static Map<String, Object> data() {
		return new LinkedHashMap<>();
	}

}
