package com.example.configmgr.ai.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单轮对话的 SSE 帧写出器（S4.2 §3 时序的终端契约）。
 *
 * <p>
 * 帧类型：{@code start}（首包）→ 若干 {@code delta} → 恰好一个 {@code done} 或 {@code error} 终帧。
 * 帧体是 JSON 对象，带 {@code type} 字段，与基座 SSE 形态一致（无名 SSE 事件 + 帧内 type，
 * 前端不必改用 addEventListener）。
 *
 * <p>
 * 注意：本棒没有"工具开始/结束"帧。官方对话循环把工具执行放在模型调用内部，客户端流<b>看不到</b>
 * 中间的工具调用（S4.2-P1 实测：5 轮共 10 次上游请求，客户端 0 个工具事件）；工具可见性属于
 * 第二棒 {@code gate/SpToolCallingManager} 的钩子职责。
 *
 * <p>
 * 写出失败（客户端断开）只记日志，不打断运行线程；终帧与 {@code emitter.complete()}
 * 由调用方在 finally 中保证。
 */
@Slf4j
public class SseChatEmitter {

	private final SseEmitter emitter;

	private final ObjectMapper objectMapper;

	public SseChatEmitter(SseEmitter emitter, ObjectMapper objectMapper) {
		this.emitter = emitter;
		this.objectMapper = objectMapper;
	}

	/** 首包：runId 由服务端生成，前端据此做 reattach（后续棒次的 /events/{runId}）。 */
	public void start(String runId, String sessionId) {
		Map<String, Object> frame = new LinkedHashMap<>();
		frame.put("type", "start");
		frame.put("runId", runId);
		frame.put("sessionId", sessionId);
		send(frame);
	}

	public void delta(String text) {
		Map<String, Object> frame = new LinkedHashMap<>();
		frame.put("type", "delta");
		frame.put("text", text);
		send(frame);
	}

	public void done(String runId, Map<String, Object> usage, String model) {
		Map<String, Object> frame = new LinkedHashMap<>();
		frame.put("type", "done");
		frame.put("runId", runId);
		if (usage != null && !usage.isEmpty()) {
			frame.put("usage", usage);
		}
		if (model != null) {
			frame.put("model", model);
		}
		send(frame);
	}

	public void error(String code, String message) {
		Map<String, Object> frame = new LinkedHashMap<>();
		frame.put("type", "error");
		frame.put("code", code);
		frame.put("message", message);
		send(frame);
	}

	public void complete() {
		try {
			this.emitter.complete();
		}
		catch (Exception ex) {
			log.debug("SSE complete failed: {}", ex.getMessage());
		}
	}

	private void send(Map<String, Object> frame) {
		try {
			this.emitter.send(this.objectMapper.writeValueAsString(frame));
		}
		catch (IOException ex) {
			log.debug("SSE send failed (client disconnected?): {}", ex.getMessage());
		}
		catch (Exception ex) {
			log.warn("SSE send error: {}", ex.getMessage());
		}
	}

}
