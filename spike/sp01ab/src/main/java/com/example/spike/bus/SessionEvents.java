package com.example.spike.bus;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 单个会话的事件总线：既做时序留档（timeline，证据主体），也做 SSE 扇出（模拟前端接收通道）。
 * 每个事件带单调递增 seq 与本地时间戳，便于事后对齐“挂起窗口”。
 */
public class SessionEvents {

	private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

	private final String sessionId;

	private final List<Map<String, Object>> timeline = Collections.synchronizedList(new ArrayList<>());

	private final AtomicLong seq = new AtomicLong();

	private final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();

	public SessionEvents(String sessionId) {
		this.sessionId = sessionId;
	}

	public List<SseEmitter> subscribers() {
		return this.subscribers;
	}

	/** 仅留档（不推 SSE）。 */
	public Map<String, Object> record(String event, Map<String, Object> data) {
		Map<String, Object> envelope = new LinkedHashMap<>();
		envelope.put("seq", this.seq.incrementAndGet());
		envelope.put("ts", OffsetDateTime.now().format(TS));
		envelope.put("sessionId", this.sessionId);
		envelope.put("event", event);
		envelope.put("data", data == null ? Map.of() : data);
		this.timeline.add(envelope);
		return envelope;
	}

	/** 留档 + 扇出给所有 SSE 订阅者。 */
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

}
