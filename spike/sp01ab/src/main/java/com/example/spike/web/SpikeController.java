package com.example.spike.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.spike.bus.EventHub;
import com.example.spike.bus.SessionEvents;
import com.example.spike.config.SpikeProperties;
import com.example.spike.hitl.ConfirmDecision;
import com.example.spike.hitl.Gate;
import com.example.spike.hitl.SessionState;
import com.example.spike.tools.SpikeTools;
import com.example.spike.tools.ToolRegistry;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * PoC 实验接口：用 HTTP 端点模拟“前端”。
 * <ul>
 * <li>POST /api/chat                     ：发起一轮对话（后台线程跑官方循环）；</li>
 * <li>GET  /api/events/{sessionId}       ：SSE 事件流（含挂起期心跳 ping）；</li>
 * <li>POST /api/frontend-tool-result     ：SP-01a 前端工具结果回灌；</li>
 * <li>POST /api/confirm                  ：SP-01b HITL 确认 / 拒绝；</li>
 * <li>GET  /api/events/{sessionId}/timeline：完整事件时序（证据主体）；</li>
 * <li>GET  /api/session/{sessionId}       ：会话挂起态与计数器快照。</li>
 * </ul>
 * 接口只服务本次实验取证，不属于生产契约。
 */
@RestController
@RequestMapping("/api")
public class SpikeController {

	private static final String SYSTEM_PROMPT = """
			你是 PoC 助手，工具由系统提供。规则：
			1. 用户要求做的事，必须调用对应工具，不得自行编造数据或伪造执行结果；
			2. 一条用户消息里包含多件事时，应在同一轮内一次性发起全部相关工具调用；
			3. 工具结果返回后再作答；若某工具被拒绝、取消或超时未执行，如实说明，禁止宣称已执行；
			4. 回复用中文，简洁，先给结论。
			""";

	private final ChatClient chatClient;

	private final EventHub hub;

	private final SpikeProperties properties;

	private final TaskExecutor executor;

	private final ToolCallback[] callbacks;

	private final SpikeTools tools;

	private final String port;

	private final java.util.concurrent.atomic.AtomicReference<Map<String, Object>> lastReply = new java.util.concurrent.atomic.AtomicReference<>(
			Map.of());

	private ChatClient.ChatClientRequestSpec spec(SessionState session, String message) {
		return this.chatClient.prompt()
			.system(SYSTEM_PROMPT)
			.user(message)
			.toolCallbacks(this.callbacks)
			.toolContext(Map.of("sessionId", session.id()));
	}

	public SpikeController(ChatClient spikeChatClient, EventHub hub, SpikeProperties properties,
			@Qualifier("spikeExecutor") TaskExecutor executor, ToolCallback[] spikeToolCallbacks, SpikeTools tools,
			@Value("${server.port}") String port) {
		this.chatClient = spikeChatClient;
		this.hub = hub;
		this.properties = properties;
		this.executor = executor;
		this.callbacks = spikeToolCallbacks;
		this.tools = tools;
		this.port = port;
	}

	// ---------------- 实验主流程 ----------------

	@PostMapping("/chat")
	public ResponseEntity<Map<String, Object>> chat(@RequestBody ChatRequest request) {
		return start(request, false);
	}

	/** 同一链路的流式版本（合流参照实现走的是 stream().chatResponse()）。 */
	@PostMapping("/chat-stream")
	public ResponseEntity<Map<String, Object>> chatStream(@RequestBody ChatRequest request) {
		return start(request, true);
	}

	private ResponseEntity<Map<String, Object>> start(ChatRequest request, boolean streaming) {
		if (request.sessionId() == null || request.sessionId().isBlank()) {
			return ResponseEntity.badRequest().body(Map.of("error", "sessionId is required"));
		}
		SessionState session = this.hub.session(request.sessionId());
		if (session.isRunning()) {
			return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "session already running"));
		}
		session.setRunning(true);
		session.startInfo().put("message", request.message());
		session.startInfo().put("streaming", streaming);
		session.startInfo().put("startedAtMillis", System.currentTimeMillis());

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("sessionId", request.sessionId());
		body.put("accepted", true);
		body.put("streaming", streaming);
		body.put("message", request.message());
		body.put("toolNames", toolNames());
		this.executor.execute(() -> run(session, request.message(), streaming));
		return ResponseEntity.accepted().body(body);
	}

	private void run(SessionState session, String message, boolean streaming) {
		long start = System.currentTimeMillis();
		SessionState.setCurrent(session);
		try {
			Map<String, Object> startData = new LinkedHashMap<>();
			startData.put("message", message);
			startData.put("streaming", streaming);
			startData.put("toolNames", toolNames());
			startData.put("frontendToolTimeoutSeconds", this.properties.getFrontendToolTimeoutSeconds());
			startData.put("confirmTimeoutSeconds", this.properties.getConfirmTimeoutSeconds());
			session.events().emit("start", startData);

			String content = streaming ? streamOnce(session, message, start) : callOnce(session, message);

			Map<String, Object> done = new LinkedHashMap<>();
			done.put("content", content);
			done.put("streaming", streaming);
			done.put("elapsedMs", System.currentTimeMillis() - start);
			done.put("failed", false);
			session.events().emit("done", done);
		}
		catch (Exception ex) {
			Map<String, Object> error = new LinkedHashMap<>();
			error.put("error", ex.getClass().getName());
			error.put("message", String.valueOf(ex.getMessage()));
			error.put("elapsedMs", System.currentTimeMillis() - start);
			session.events().emit("error", error);
			Map<String, Object> done = new LinkedHashMap<>();
			done.put("content", "");
			done.put("streaming", streaming);
			done.put("elapsedMs", System.currentTimeMillis() - start);
			done.put("failed", true);
			session.events().emit("done", done);
		}
		finally {
			SessionState.clearCurrent();
			session.setRunning(false);
			flushThenComplete(session.events());
		}
	}

	private String callOnce(SessionState session, String message) {
		ChatResponse response = spec(session, message).call().chatResponse();
		String content = response.getResult() == null || response.getResult().getOutput() == null ? ""
				: response.getResult().getOutput().getText();
		Map<String, Object> reply = new LinkedHashMap<>();
		reply.put("content", content);
		reply.put("model", response.getMetadata() == null ? null : response.getMetadata().getModel());
		reply.put("finishReason",
				response.getResult() == null || response.getResult().getMetadata() == null ? null
						: response.getResult().getMetadata().getFinishReason());
		this.lastReply.set(reply);
		return content;
	}

	/** 流式路径：与合流参照实现同形（subscribeOn(boundedElastic) 后用 blockLast 驱动）。 */
	private String streamOnce(SessionState session, String message, long start) {
		StringBuilder full = new StringBuilder();
		spec(session, message).stream()
			.chatResponse()
			.subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
			.doOnNext(response -> {
				if (response.getResult() == null || response.getResult().getOutput() == null) {
					return;
				}
				String delta = response.getResult().getOutput().getText();
				if (delta != null && !delta.isEmpty()) {
					full.append(delta);
					Map<String, Object> data = new LinkedHashMap<>();
					data.put("content", delta);
					session.events().emit("delta", data);
				}
				Map<String, Object> reply = new LinkedHashMap<>();
				reply.put("content", full.toString());
				reply.put("model", response.getMetadata() == null ? null : response.getMetadata().getModel());
				reply.put("finishReason", response.getResult().getMetadata() == null ? null
						: response.getResult().getMetadata().getFinishReason());
				this.lastReply.set(reply);
			})
			.blockLast();
		Map<String, Object> reply = new LinkedHashMap<>(this.lastReply.get());
		reply.put("streamingMs", System.currentTimeMillis() - start);
		session.events().emit("model_reply", reply);
		return full.toString();
	}

	private void flushThenComplete(SessionEvents events) {
		try {
			Thread.sleep(500);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		events.completeSubscribers();
	}

	// ---------------- 前端通道（模拟前端） ----------------

	@GetMapping("/events/{sessionId}")
	public SseEmitter events(@PathVariable String sessionId) {
		SessionEvents events = this.hub.session(sessionId).events();
		SseEmitter emitter = events.subscribe();
		Map<String, Object> hello = new LinkedHashMap<>();
		hello.put("sessionId", sessionId);
		hello.put("subscribers", events.subscriberCount());
		hello.put("heartbeatMs", this.properties.getHeartbeatMs());
		events.emit("subscribed", hello);
		return emitter;
	}

	@PostMapping("/frontend-tool-result")
	public ResponseEntity<Map<String, Object>> frontendToolResult(@RequestBody FrontendToolResultRequest request) {
		SessionState session = this.hub.find(request.sessionId());
		if (session == null) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "unknown sessionId"));
		}
		Gate<Map<String, Object>> gate = session.currentFrontendGate();
		if (gate == null) {
			return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "no pending frontend tool call"));
		}
		if (request.toolCallId() != null && !request.toolCallId().equals(gate.toolCallId())) {
			return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(Map.of("error", "toolCallId mismatch", "expected", gate.toolCallId()));
		}
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("result", request.result());
		payload.put("source", request.source() == null ? "http-post" : request.source());
		boolean accepted = gate.complete(payload);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("accepted", accepted);
		body.put("toolCallId", gate.toolCallId());
		body.put("toolName", gate.toolName());
		return ResponseEntity.ok(body);
	}

	@PostMapping("/confirm")
	public ResponseEntity<Map<String, Object>> confirm(@RequestBody ConfirmRequest request) {
		SessionState session = this.hub.find(request.sessionId());
		if (session == null) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "unknown sessionId"));
		}
		Gate<ConfirmDecision> gate = session.currentConfirmGate();
		if (gate == null) {
			return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(Map.of("error", "no pending confirm gate (可能已超时自动取消)"));
		}
		if (request.toolCallId() != null && !request.toolCallId().equals(gate.toolCallId())) {
			return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(Map.of("error", "toolCallId mismatch", "expected", gate.toolCallId()));
		}
		boolean approved = Boolean.TRUE.equals(request.approved());
		String reason = request.reason() == null ? (approved ? "用户在确认门中批准" : "用户在确认门中拒绝") : request.reason();
		boolean accepted = gate.complete(new ConfirmDecision(approved, reason,
				request.source() == null ? "http-post" : request.source()));
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("accepted", accepted);
		body.put("approved", approved);
		body.put("toolCallId", gate.toolCallId());
		body.put("toolName", gate.toolName());
		return ResponseEntity.ok(body);
	}

	// ---------------- 证据与自检 ----------------

	@GetMapping("/events/{sessionId}/timeline")
	public Map<String, Object> timeline(@PathVariable String sessionId) {
		SessionState session = this.hub.find(sessionId);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("sessionId", sessionId);
		out.put("exists", session != null);
		out.put("events", session == null ? List.of() : session.events().timeline());
		return out;
	}

	@GetMapping("/session/{sessionId}")
	public Map<String, Object> session(@PathVariable String sessionId) {
		SessionState session = this.hub.find(sessionId);
		return session == null ? Map.of("sessionId", sessionId, "exists", false) : session.snapshot();
	}

	@GetMapping("/dev/info")
	public Map<String, Object> info() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("port", this.port);
		out.put("pid", ProcessHandle.current().pid());
		out.put("toolKinds", ToolRegistry.all());
		out.put("frontendToolTimeoutSeconds", this.properties.getFrontendToolTimeoutSeconds());
		out.put("confirmTimeoutSeconds", this.properties.getConfirmTimeoutSeconds());
		out.put("heartbeatMs", this.properties.getHeartbeatMs());
		out.put("toolCounters", Map.of("calculate", this.tools.calculateCalls(), "get_user_profile_stub_body",
				this.tools.userProfileBodyCalls(), "delete_config", this.tools.deleteConfigCalls()));
		out.put("sessions", new ArrayList<>(sessionIds()));
		return out;
	}

	/** 缩短挂起超时（仅实验用），便于按需压缩等待时间。 */
	@PostMapping("/dev/timeouts")
	public Map<String, Object> timeouts(@RequestParam(required = false) Integer frontend,
			@RequestParam(required = false) Integer confirm) {
		if (frontend != null) {
			this.properties.setFrontendToolTimeoutSeconds(frontend);
		}
		if (confirm != null) {
			this.properties.setConfirmTimeoutSeconds(confirm);
		}
		return Map.of("frontendToolTimeoutSeconds", this.properties.getFrontendToolTimeoutSeconds(),
				"confirmTimeoutSeconds", this.properties.getConfirmTimeoutSeconds());
	}

	@GetMapping("/dev/tools")
	public List<Map<String, Object>> tools() {
		List<Map<String, Object>> out = new ArrayList<>();
		for (ToolCallback callback : this.callbacks) {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("name", callback.getToolDefinition().name());
			item.put("kind", ToolRegistry.kindOf(callback.getToolDefinition().name()).name());
			item.put("description", callback.getToolDefinition().description());
			item.put("inputSchema", callback.getToolDefinition().inputSchema());
			out.add(item);
		}
		return out;
	}

	private List<String> sessionIds() {
		List<String> out = new ArrayList<>();
		this.hub.all().forEach(session -> out.add(session.id()));
		return out;
	}

	private List<String> toolNames() {
		List<String> out = new ArrayList<>();
		for (ToolCallback callback : this.callbacks) {
			out.add(callback.getToolDefinition().name());
		}
		return out;
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<Map<String, Object>> handle(Exception ex) {
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
			.body(Map.of("error", ex.getClass().getName(), "message", String.valueOf(ex.getMessage())));
	}

	public record ChatRequest(String sessionId, String message) {
	}

	public record FrontendToolResultRequest(String sessionId, String toolCallId, String result, String source) {
	}

	public record ConfirmRequest(String sessionId, String toolCallId, Boolean approved, String reason, String source) {
	}

}
