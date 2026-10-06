package com.example.spike.hitl;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.example.spike.bus.SessionEvents;

/**
 * 会话态：把“官方循环内被阻塞的那次工具执行”与“HTTP 端点送来的外部输入”连起来。
 * - frontendGate：SP-01a 前端工具挂起门（结果由前端 POST 回灌）；
 * - confirmGate ：SP-01b HITL 确认门（人工 approve / reject）；
 * - 计数器：用于证明“工具到底执行没执行”（证据可比对，不靠自述）。
 */
public class SessionState {

	/** 官方 toolContext 未透传时的兜底通道（同一线程内阻塞调用）。 */
	private static final ThreadLocal<SessionState> CURRENT = new ThreadLocal<>();

	private final String id;

	private final SessionEvents events;

	private final AtomicReference<Gate<Map<String, Object>>> frontendGate = new AtomicReference<>();

	private final AtomicReference<Gate<ConfirmDecision>> confirmGate = new AtomicReference<>();

	private final AtomicInteger frontendToolRequests = new AtomicInteger();

	private final AtomicInteger frontendToolResults = new AtomicInteger();

	private final AtomicInteger confirmRequests = new AtomicInteger();

	private final AtomicInteger sensitiveExecuted = new AtomicInteger();

	private final AtomicInteger sensitiveBlocked = new AtomicInteger();

	private final AtomicInteger backendToolExecuted = new AtomicInteger();

	private final Map<String, Object> startInfo = new LinkedHashMap<>();

	private volatile boolean running;

	public SessionState(String id) {
		this.id = id;
		this.events = new SessionEvents(id);
	}

	public String id() {
		return this.id;
	}

	public SessionEvents events() {
		return this.events;
	}

	public boolean isRunning() {
		return this.running;
	}

	public void setRunning(boolean running) {
		this.running = running;
	}

	public Map<String, Object> startInfo() {
		return this.startInfo;
	}

	// ---------- SP-01a 前端工具门 ----------

	public void registerFrontendGate(Gate<Map<String, Object>> gate) {
		this.frontendGate.set(gate);
		this.frontendToolRequests.incrementAndGet();
	}

	public Gate<Map<String, Object>> currentFrontendGate() {
		return this.frontendGate.get();
	}

	public void clearFrontendGate(Gate<Map<String, Object>> gate) {
		this.frontendGate.compareAndSet(gate, null);
	}

	public int frontendToolRequests() {
		return this.frontendToolRequests.get();
	}

	public void markFrontendToolResult() {
		this.frontendToolResults.incrementAndGet();
	}

	public int frontendToolResults() {
		return this.frontendToolResults.get();
	}

	// ---------- SP-01b 确认门 ----------

	public void registerConfirmGate(Gate<ConfirmDecision> gate) {
		this.confirmGate.set(gate);
		this.confirmRequests.incrementAndGet();
	}

	public Gate<ConfirmDecision> currentConfirmGate() {
		return this.confirmGate.get();
	}

	public void clearConfirmGate(Gate<ConfirmDecision> gate) {
		this.confirmGate.compareAndSet(gate, null);
	}

	public int confirmRequests() {
		return this.confirmRequests.get();
	}

	public void markSensitiveExecuted() {
		this.sensitiveExecuted.incrementAndGet();
	}

	public void markSensitiveBlocked() {
		this.sensitiveBlocked.incrementAndGet();
	}

	public int sensitiveExecuted() {
		return this.sensitiveExecuted.get();
	}

	public int sensitiveBlocked() {
		return this.sensitiveBlocked.get();
	}

	public void markBackendToolExecuted() {
		this.backendToolExecuted.incrementAndGet();
	}

	/** 挂起中类型：null=无挂起。心跳帧带上该字段，证明“挂起期间仍有事件”。 */
	public String pendingKind() {
		Gate<Map<String, Object>> fg = this.frontendGate.get();
		if (fg != null && !fg.isDone()) {
			return "frontend-tool:" + fg.toolName();
		}
		Gate<ConfirmDecision> cg = this.confirmGate.get();
		if (cg != null && !cg.isDone()) {
			return "confirm:" + cg.toolName();
		}
		return null;
	}

	public Map<String, Object> snapshot() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("sessionId", this.id);
		out.put("running", this.running);
		out.put("pendingKind", pendingKind());
		Gate<Map<String, Object>> fg = this.frontendGate.get();
		out.put("pendingFrontendGate", fg == null ? null : Map.of("toolCallId", fg.toolCallId(), "toolName", fg.toolName()));
		Gate<ConfirmDecision> cg = this.confirmGate.get();
		out.put("pendingConfirmGate", cg == null ? null : Map.of("toolCallId", cg.toolCallId(), "toolName", cg.toolName()));
		out.put("counters", Map.of("frontendToolRequests", this.frontendToolRequests.get(), "frontendToolResults",
				this.frontendToolResults.get(), "confirmRequests", this.confirmRequests.get(), "sensitiveExecuted",
				this.sensitiveExecuted.get(), "sensitiveBlocked", this.sensitiveBlocked.get(), "backendToolExecuted",
				this.backendToolExecuted.get()));
		out.put("sseSubscribers", this.events.subscriberCount());
		out.put("timelineSize", this.events.timeline().size());
		return out;
	}

	// ---------- ThreadLocal 兜底 ----------

	public static void setCurrent(SessionState state) {
		CURRENT.set(state);
	}

	public static SessionState current() {
		return CURRENT.get();
	}

	public static void clearCurrent() {
		CURRENT.remove();
	}

}
