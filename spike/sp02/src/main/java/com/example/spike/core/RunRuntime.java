package com.example.spike.core;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 单轮的运行时（进程内）构件：事件时序 + SSE 扇出 + 进程内唤醒开关。
 *
 * <p>
 * 每个事件同时写两份：进程内 timeline（快，供实验读取）与 Redis（{@code sp02:events:<runId>}，
 * 崩溃后仍可读）。SSE 订阅是“前端再挂接”的落点——重启后前端重新订阅即可看到挂起态。
 */
public class RunRuntime {

	private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

	private final String runId;

	private final RunStore store;

	private final List<Map<String, Object>> timeline = Collections.synchronizedList(new ArrayList<>());

	private final AtomicLong seq = new AtomicLong();

	private final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();

	private final Map<String, Gate<Boolean>> frontendGates = new ConcurrentHashMap<>();

	private final Map<String, Gate<Boolean>> confirmGates = new ConcurrentHashMap<>();

	public RunRuntime(String runId, RunStore store) {
		this.runId = runId;
		this.store = store;
	}

	public String runId() {
		return this.runId;
	}

	public Map<String, Object> record(String event, Map<String, Object> data) {
		Map<String, Object> envelope = new LinkedHashMap<>();
		envelope.put("seq", this.seq.incrementAndGet());
		envelope.put("ts", OffsetDateTime.now().format(TS));
		envelope.put("wallMs", System.currentTimeMillis());
		envelope.put("runId", this.runId);
		envelope.put("instanceId", this.store.instanceId());
		envelope.put("event", event);
		envelope.put("data", data == null ? Map.of() : data);
		this.timeline.add(envelope);
		this.store.appendEvent(this.runId, envelope);
		return envelope;
	}

	public void emit(String event, Map<String, Object> data) {
		Map<String, Object> envelope = record(event, data);
		for (SseEmitter emitter : this.subscribers) {
			try {
				emitter.send(SseEmitter.event().name(event).data(envelope));
			}
			catch (Exception ex) {
				this.subscribers.remove(emitter);
				try {
					emitter.completeWithError(ex);
				}
				catch (Exception ignored) {
				}
			}
		}
	}

	public SseEmitter subscribe() {
		SseEmitter emitter = new SseEmitter(0L);
		this.subscribers.add(emitter);
		emitter.onCompletion(() -> this.subscribers.remove(emitter));
		emitter.onTimeout(() -> this.subscribers.remove(emitter));
		emitter.onError(ex -> this.subscribers.remove(emitter));
		return emitter;
	}

	public int subscriberCount() {
		return this.subscribers.size();
	}

	public void completeSubscribers() {
		for (SseEmitter emitter : this.subscribers) {
			try {
				emitter.complete();
			}
			catch (Exception ignored) {
			}
		}
		this.subscribers.clear();
	}

	public List<Map<String, Object>> timeline() {
		synchronized (this.timeline) {
			return new ArrayList<>(this.timeline);
		}
	}

	// ---------------- 唤醒开关（进程内；不承载状态） ----------------

	public Gate<Boolean> frontendGate(String toolCallId) {
		return this.frontendGates.get(toolCallId);
	}

	public Gate<Boolean> registerFrontendGate(String toolCallId) {
		Gate<Boolean> gate = new Gate<>(toolCallId);
		this.frontendGates.put(toolCallId, gate);
		return gate;
	}

	public void clearFrontendGate(String toolCallId) {
		this.frontendGates.remove(toolCallId);
	}

	public Gate<Boolean> registerConfirmGate(String toolCallId) {
		Gate<Boolean> gate = new Gate<>(toolCallId);
		this.confirmGates.put(toolCallId, gate);
		return gate;
	}

	public void clearConfirmGate(String toolCallId) {
		this.confirmGates.remove(toolCallId);
	}

	public Gate<Boolean> confirmGate(String toolCallId) {
		return this.confirmGates.get(toolCallId);
	}

	public String pendingKind() {
		if (!this.frontendGates.isEmpty()) {
			return "frontend-tool:" + this.frontendGates.keySet().iterator().next();
		}
		if (!this.confirmGates.isEmpty()) {
			return "confirm:" + this.confirmGates.keySet().iterator().next();
		}
		return null;
	}

}
