package com.example.spike.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import com.example.spike.ai.SpikeEligibilityPredicate;
import com.example.spike.config.SpikeProperties;
import com.example.spike.core.ExplicitLoopRunner;
import com.example.spike.core.PendingToolCall;
import com.example.spike.core.RunSnapshot;
import com.example.spike.core.RunStore;
import com.example.spike.core.RuntimeRegistry;
import com.example.spike.core.ToolCallExecutor;
import com.example.spike.memory.MessageJsonCodec;
import com.example.spike.tools.SpikeTools;
import com.example.spike.tools.ToolRegistry;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
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
 * SP-02 实验接口（模拟前端 + 运维面，非生产契约）。
 *
 * <p>
 * 关键点：挂起态全部在 Redis，本控制器与运行时对象都不持有跨请求的状态；
 * 续跑端点只做“读 Redis → 重建请求 → 继续跑循环”。
 */
@RestController
@RequestMapping("/api")
public class Sp02Controller {

	private static final String SYSTEM_PROMPT = """
			你是 PoC 助手，工具由系统提供。规则：
			1. 用户要求做的事，必须调用对应工具，不得自行编造数据或伪造执行结果；
			2. 一条用户消息里包含多件事时，应在同一轮内一次性发起全部相关工具调用；
			3. 工具结果返回后再作答；若某工具被拒绝、取消或超时未执行，如实说明，禁止宣称已执行；
			4. 回复用中文，简洁，先给结论。
			""";

	private final ExplicitLoopRunner runner;

	private final RunStore store;

	private final RuntimeRegistry registry;

	private final ToolCallExecutor executor;

	private final MessageJsonCodec codec;

	private final SpikeProperties properties;

	private final SpikeEligibilityPredicate predicate;

	private final ToolCallback[] callbacks;

	private final SpikeTools tools;

	private final ExecutorService executorService;

	private final StringRedisTemplate redis;

	private final String port;

	public Sp02Controller(ExplicitLoopRunner runner, RunStore store, RuntimeRegistry registry,
			ToolCallExecutor executor, MessageJsonCodec codec, SpikeProperties properties,
			SpikeEligibilityPredicate predicate, ToolCallback[] callbacks, SpikeTools tools,
			@Qualifier("sp02Executor") ExecutorService executorService, StringRedisTemplate redis,
			@Value("${server.port}") String port) {
		this.runner = runner;
		this.store = store;
		this.registry = registry;
		this.executor = executor;
		this.codec = codec;
		this.properties = properties;
		this.predicate = predicate;
		this.callbacks = callbacks;
		this.tools = tools;
		this.executorService = executorService;
		this.redis = redis;
		this.port = port;
	}

	// ------------------------------------------------------------------ 实验主流程

	@PostMapping("/chat")
	public ResponseEntity<Map<String, Object>> chat(@RequestBody ChatRequest request) {
		if (request.sessionId() == null || request.sessionId().isBlank()) {
			return ResponseEntity.badRequest().body(Map.of("error", "sessionId is required"));
		}
		String mode = request.mode() == null ? "blocking" : request.mode().toLowerCase();
		ExplicitLoopRunner.Form form = formOf(mode);
		String runId = "run-" + request.sessionId() + "-" + UUID.randomUUID().toString().substring(0, 8);

		RunSnapshot snap = this.store.create(runId, request.sessionId(), mode, SYSTEM_PROMPT);
		List<Message> history = new ArrayList<>();
		history.add(new SystemMessage(SYSTEM_PROMPT));
		history.add(new UserMessage(request.message()));
		snap.messageJson = this.codec.serializeAll(history);
		snap.status = RunSnapshot.RUNNING;
		this.store.save(snap);

		com.example.spike.core.RunRuntime runtime = this.registry.runtime(runId);
		runtime.emit("start", startEvent(runId, request, mode, history));

		try {
			this.executorService.execute(() -> drive(runId, history, form, "start"));
		}
		catch (java.util.concurrent.RejectedExecutionException ex) {
			// DC-12：有界池超限快速失败，不排队
			this.store.deleteRun(runId);
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.body(Map.of("error", "SUSPEND_POOL_SATURATED", "runId", runId));
		}

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("runId", runId);
		body.put("sessionId", request.sessionId());
		body.put("mode", mode);
		body.put("accepted", true);
		body.put("toolNames", toolNames());
		body.put("stateKey", this.store.runKey(runId));
		return ResponseEntity.accepted().body(body);
	}
	/**
	 * 续跑：读 Redis 里的挂起态 → 重建 {@code assistant(tool_calls) + role:tool} → 继续跑官方循环。
	 * 幂等：DONE 的轮次不再发模型请求；挂起项未落定则拒绝，保持 SUSPENDED。
	 */
	@PostMapping("/resume/{runId}")
	public ResponseEntity<Map<String, Object>> resume(@PathVariable String runId) {
		RunSnapshot snap = this.store.get(runId);
		if (snap == null) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "unknown runId"));
		}
		List<PendingToolCall> pendings = this.store.pendings(runId);
		List<PendingToolCall> unresolved = pendings.stream()
			.filter(item -> PendingToolCall.PENDING.equals(item.status))
			.toList();

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("runId", runId);
		body.put("mode", snap.mode);
		body.put("statusBeforeResume", snap.status);
		body.put("instanceId", this.store.instanceId());
		body.put("rebuildPreview", rebuildPreview(snap, pendings));

		if (RunSnapshot.DONE.equals(snap.status)) {
			body.put("resumed", false);
			body.put("reason", "ALREADY_DONE");
			return ResponseEntity.ok(body);
		}
		if (RunSnapshot.RUNNING.equals(snap.status)) {
			body.put("resumed", false);
			body.put("reason", "ALREADY_RUNNING");
			return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
		}
		if (!unresolved.isEmpty()) {
			body.put("resumed", false);
			body.put("reason", "PENDING_UNRESOLVED");
			body.put("unresolved", unresolved.stream().map(PendingToolCall::asMap).toList());
			return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
		}

		ExplicitLoopRunner.Form form = formOf(snap.mode);
		List<Message> history = this.codec.deserializeAll(snap.messageJson);
		List<String> rebuilt = new ArrayList<>();
		if (!snap.inFlightToolCalls.isEmpty()) {
			List<Message> crashRound = new ArrayList<>(history);
			AssistantMessage assistant = rebuildAssistant(snap);
			crashRound.add(assistant);
			List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
			var prompt = this.runner.promptFor(runId, crashRound, form);
			for (PendingToolCall item : snap.inFlightToolCalls) {
				// 以 Redis 里的条目为准（快照里的 in-flight 副本可能停留在“未落定”时刻）
				PendingToolCall fresh = this.store.pending(runId, item.toolCallId);
				PendingToolCall effective = fresh == null ? item : fresh;
				responses.add(this.executor.resolve(runId, prompt, effective, "resume-rebuild"));
				String who = !effective.executed ? ":reused"
						: (this.store.instanceId().equals(effective.executedBy) ? ":executed-here"
								: ":executed-by-previous-instance(" + effective.executedBy + ")");
				rebuilt.add(item.toolCallId + ":" + effective.status + who);
			}
			history.add(assistant);
			history.add(ToolResponseMessage.builder().responses(responses).build());
			snap.messageJson = this.codec.serializeAll(history);
			snap.inFlightToolCalls = new ArrayList<>();
			snap.assistantContent = null;
			snap.status = RunSnapshot.RUNNING;
			snap.resumedBy.add(this.store.instanceId());
			this.store.save(snap);
		}
		else {
			snap.resumedBy.add(this.store.instanceId());
			this.store.save(snap);
		}

		body.put("resumed", true);
		body.put("rebuiltTools", rebuilt);
		body.put("promptMessageCount", history.size());
		body.put("promptMessages", describeAll(history));
		body.put("resumedBy", this.store.instanceId());

		try {
			this.executorService.execute(() -> drive(runId, history, form, "resume"));
		}
		catch (java.util.concurrent.RejectedExecutionException ex) {
			body.put("resumed", false);
			body.put("reason", "SUSPEND_POOL_SATURATED");
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
		}
		return ResponseEntity.accepted().body(body);
	}

	private void drive(String runId, List<Message> history, ExplicitLoopRunner.Form form, String trigger) {
		com.example.spike.core.RunRuntime runtime = this.registry.runtime(runId);
		try {
			ExplicitLoopRunner.Outcome outcome = this.runner.run(runId, history, form);
			Map<String, Object> event = new LinkedHashMap<>();
			event.put("runId", runId);
			event.put("trigger", trigger);
			event.put("outcome", outcome.asMap());
			event.put("instanceId", this.store.instanceId());
			runtime.emit("run_finished", event);
		}
		catch (Exception ex) {
			RunSnapshot snap = this.store.get(runId);
			if (snap != null && !RunSnapshot.DONE.equals(snap.status)) {
				snap.status = RunSnapshot.FAILED;
				snap.error = ex.getClass().getName() + ": " + ex.getMessage();
				this.store.save(snap);
			}
			Map<String, Object> event = new LinkedHashMap<>();
			event.put("runId", runId);
			event.put("trigger", trigger);
			event.put("error", ex.getClass().getName());
			event.put("message", String.valueOf(ex.getMessage()));
			runtime.emit("run_error", event);
		}
		finally {
			runtime.completeSubscribers();
		}
	}

	// ------------------------------------------------------------------ 外部输入（前端 / 人）

	@PostMapping("/frontend-tool-result")
	public ResponseEntity<Map<String, Object>> frontendToolResult(@RequestBody FrontendToolResultRequest request) {
		PendingToolCall pending = this.store.pending(request.runId(), request.toolCallId());
		if (pending == null) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(Map.of("error", "unknown runId/toolCallId (无效或已过期)"));
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("runId", request.runId());
		body.put("toolCallId", request.toolCallId());
		body.put("instanceId", this.store.instanceId());
		if (!PendingToolCall.PENDING.equals(pending.status)) {
			body.put("accepted", false);
			body.put("duplicate", true);
			body.put("reason", "ALREADY_RESOLVED");
			body.put("status", pending.status);
			return ResponseEntity.ok(body);
		}
		pending.status = PendingToolCall.FRONTEND_RESULT;
		pending.resultText = request.result();
		pending.reason = "source=" + (request.source() == null ? "http-post" : request.source());
		pending.resolvedAtMs = System.currentTimeMillis();
		this.store.putPending(request.runId(), pending);

		com.example.spike.core.RunRuntime runtime = this.registry.find(request.runId());
		boolean woke = false;
		if (runtime != null && runtime.frontendGate(request.toolCallId()) != null) {
			woke = runtime.frontendGate(request.toolCallId()).complete(Boolean.TRUE);
		}
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("runId", request.runId());
		event.put("toolCallId", request.toolCallId());
		event.put("status", pending.status);
		event.put("wokeInProcessGate", woke);
		event.put("storedBy", this.store.instanceId());
		event.put("stateKey", this.store.hitlKey(request.runId()));
		runtime(request.runId()).emit("frontend_result_stored", event);

		body.put("accepted", true);
		body.put("duplicate", false);
		body.put("status", pending.status);
		body.put("wokeInProcessGate", woke);
		return ResponseEntity.ok(body);
	}

	@PostMapping("/confirm")
	public ResponseEntity<Map<String, Object>> confirm(@RequestBody ConfirmRequest request) {
		PendingToolCall pending = this.store.pending(request.runId(), request.toolCallId());
		if (pending == null) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(Map.of("error", "unknown runId/toolCallId (无效或已过期)"));
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("runId", request.runId());
		body.put("toolCallId", request.toolCallId());
		body.put("instanceId", this.store.instanceId());
		if (!PendingToolCall.PENDING.equals(pending.status)) {
			body.put("accepted", false);
			body.put("duplicate", true);
			body.put("reason", "ALREADY_RESOLVED");
			body.put("status", pending.status);
			return ResponseEntity.ok(body);
		}
		boolean approved = Boolean.TRUE.equals(request.approved());
		pending.status = approved ? PendingToolCall.APPROVED : PendingToolCall.REJECTED;
		pending.reason = request.reason() == null ? (approved ? "用户在确认门中批准" : "用户在确认门中拒绝")
				: request.reason();
		pending.resolvedAtMs = System.currentTimeMillis();
		pending.executed = false;
		pending.resultText = approved ? null : "用户拒绝了该操作，工具未执行。拒绝原因：" + pending.reason;
		this.store.putPending(request.runId(), pending);

		com.example.spike.core.RunRuntime runtime = this.registry.find(request.runId());
		boolean woke = false;
		if (runtime != null && runtime.confirmGate(request.toolCallId()) != null) {
			woke = runtime.confirmGate(request.toolCallId()).complete(Boolean.TRUE);
		}
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("runId", request.runId());
		event.put("toolCallId", request.toolCallId());
		event.put("status", pending.status);
		event.put("approved", approved);
		event.put("reason", pending.reason);
		event.put("executed", false);
		event.put("wokeInProcessGate", woke);
		event.put("storedBy", this.store.instanceId());
		event.put("stateKey", this.store.hitlKey(request.runId()));
		runtime(request.runId()).emit("confirm_decision_stored", event);

		body.put("accepted", true);
		body.put("duplicate", false);
		body.put("status", pending.status);
		body.put("approved", approved);
		body.put("wokeInProcessGate", woke);
		body.put("note", "决策已写入 Redis；是否已执行见 pending.executed / ledger");
		return ResponseEntity.ok(body);
	}

	// ------------------------------------------------------------------ 证据出口

	@GetMapping("/run/{runId}")
	public Map<String, Object> run(@PathVariable String runId) {
		RunSnapshot snap = this.store.get(runId);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("runId", runId);
		if (snap == null) {
			out.put("exists", false);
			return out;
		}
		List<PendingToolCall> pendings = this.store.pendings(runId);
		out.put("exists", true);
		out.put("snapshot", snap.asMap());
		out.put("pending", pendings.stream().map(PendingToolCall::asMap).toList());
		out.put("ledger", this.store.ledger(runId));
		out.put("ledgerCounts", this.store.ledgerCounts(runId));
		out.put("rebuildPreview", rebuildPreview(snap, pendings));
		out.put("historyMessages", describeAll(this.codec.deserializeAll(snap.messageJson)));
		out.put("persistedEvents", this.store.events(runId));
		com.example.spike.core.RunRuntime runtime = this.registry.find(runId);
		out.put("inProcessTimeline", runtime == null ? List.of() : runtime.timeline());
		out.put("instanceId", this.store.instanceId());
		out.put("keys", Map.of("state", this.store.runKey(runId), "pending", this.store.hitlKey(runId), "ledger",
				this.store.ledgerKey(runId), "events", this.store.eventsKey(runId)));
		return out;
	}

	@GetMapping("/runs")
	public Map<String, Object> runs() {
		List<Map<String, Object>> items = new ArrayList<>();
		for (String runId : this.store.allRunIds()) {
			RunSnapshot snap = this.store.get(runId);
			if (snap == null) {
				continue;
			}
			Map<String, Object> item = snap.asMap();
			item.put("pendingCount", this.store.pendings(runId).size());
			items.add(item);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("runs", items);
		out.put("instanceId", this.store.instanceId());
		return out;
	}

	@GetMapping("/events/{runId}")
	public SseEmitter events(@PathVariable String runId) {
		com.example.spike.core.RunRuntime runtime = runtime(runId);
		SseEmitter emitter = runtime.subscribe();
		RunSnapshot snap = this.store.get(runId);
		Map<String, Object> reattach = new LinkedHashMap<>();
		reattach.put("runId", runId);
		reattach.put("instanceId", this.store.instanceId());
		reattach.put("snapshot", snap == null ? null : snap.asMap());
		reattach.put("pending", this.store.pendings(runId).stream().map(PendingToolCall::asMap).toList());
		reattach.put("note", "重启后前端重新订阅即可看到挂起态（状态来源为 Redis，与订阅无关）");
		runtime.emit("reattached", reattach);
		return emitter;
	}

	@GetMapping("/events/{runId}/timeline")
	public Map<String, Object> timeline(@PathVariable String runId) {
		com.example.spike.core.RunRuntime runtime = this.registry.find(runId);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("runId", runId);
		out.put("inProcessTimeline", runtime == null ? List.of() : runtime.timeline());
		out.put("persistedEvents", this.store.events(runId));
		return out;
	}

	@GetMapping("/dev/info")
	public Map<String, Object> info() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("port", this.port);
		out.put("pid", ProcessHandle.current().pid());
		out.put("instanceId", this.store.instanceId());
		out.put("toolKinds", ToolRegistry.all());
		out.put("suppressExternalRounds", this.predicate.isSuppressExternal());
		out.put("frontendToolTimeoutSeconds", this.properties.getFrontendToolTimeoutSeconds());
		out.put("confirmTimeoutSeconds", this.properties.getConfirmTimeoutSeconds());
		out.put("execDelayMsAfterApprove", this.properties.getExecDelayMsAfterApprove());
		out.put("instanceToolCounters", Map.of("calculate", this.tools.calculateCalls(), "get_user_profile_stub_body",
				this.tools.userProfileBodyCalls(), "delete_config", this.tools.deleteConfigCalls()));
		out.put("runIds", this.store.allRunIds());
		return out;
	}

	@PostMapping("/dev/switch")
	public Map<String, Object> switchState(@RequestParam(required = false) Boolean suppressExternal,
			@RequestParam(required = false) Long execDelayMs, @RequestParam(required = false) Integer frontendTimeout,
			@RequestParam(required = false) Integer confirmTimeout) {
		if (suppressExternal != null) {
			this.predicate.setSuppressExternal(suppressExternal);
			this.properties.setSuppressExternalRounds(suppressExternal);
		}
		if (execDelayMs != null) {
			this.properties.setExecDelayMsAfterApprove(execDelayMs);
		}
		if (frontendTimeout != null) {
			this.properties.setFrontendToolTimeoutSeconds(frontendTimeout);
		}
		if (confirmTimeout != null) {
			this.properties.setConfirmTimeoutSeconds(confirmTimeout);
		}
		return info();
	}

	@GetMapping("/dev/tools")
	public List<Map<String, Object>> tools() {
		List<Map<String, Object>> out = new ArrayList<>();
		for (ToolCallback callback : this.callbacks) {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("name", callback.getToolDefinition().name());
			item.put("kind", ToolRegistry.kindOf(callback.getToolDefinition().name()).name());
			item.put("inputSchema", callback.getToolDefinition().inputSchema());
			out.add(item);
		}
		return out;
	}

	/** 清理本实验在 Redis 里的键（收尾用）。 */
	@PostMapping("/dev/cleanup")
	public Map<String, Object> cleanup() {
		List<String> deleted = new ArrayList<>();
		for (String runId : this.store.allRunIds()) {
			for (String key : List.of(this.store.runKey(runId), this.store.hitlKey(runId), this.store.ledgerKey(runId),
					this.store.eventsKey(runId))) {
				this.redis.delete(key);
				deleted.add(key);
			}
		}
		this.redis.delete(RunStore.RUN_INDEX);
		deleted.add(RunStore.RUN_INDEX);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("deleted", deleted);
		out.put("remainingRunIds", this.store.allRunIds());
		return out;
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<Map<String, Object>> handle(Exception ex) {
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
			.body(Map.of("error", ex.getClass().getName(), "message", String.valueOf(ex.getMessage())));
	}

	// ------------------------------------------------------------------ 辅助

	private com.example.spike.core.RunRuntime runtime(String runId) {
		return this.registry.runtime(runId);
	}

	private Map<String, Object> startEvent(String runId, ChatRequest request, String mode, List<Message> history) {
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("runId", runId);
		event.put("sessionId", request.sessionId());
		event.put("mode", mode);
		event.put("message", request.message());
		event.put("toolNames", toolNames());
		event.put("history", describeAll(history));
		event.put("instanceId", this.store.instanceId());
		event.put("suppressExternalRounds", this.predicate.isSuppressExternal());
		event.put("execDelayMsAfterApprove", this.properties.getExecDelayMsAfterApprove());
		return event;
	}

	private ExplicitLoopRunner.Form formOf(String mode) {
		return switch (mode == null ? "blocking" : mode.toLowerCase()) {
			case "explicit" -> ExplicitLoopRunner.Form.EXPLICIT;
			case "predicate" -> ExplicitLoopRunner.Form.PREDICATE;
			default -> ExplicitLoopRunner.Form.BLOCKING;
		};
	}

	private AssistantMessage rebuildAssistant(RunSnapshot snap) {
		List<AssistantMessage.ToolCall> calls = new ArrayList<>();
		for (PendingToolCall item : snap.inFlightToolCalls) {
			calls.add(new AssistantMessage.ToolCall(item.toolCallId, item.type == null ? "function" : item.type,
					item.name, item.arguments));
		}
		return AssistantMessage.builder().content(snap.assistantContent).toolCalls(calls).build();
	}

	/** 续跑将要发出的请求预览（证据用；不执行任何工具）。 */
	private Map<String, Object> rebuildPreview(RunSnapshot snap, List<PendingToolCall> pendings) {
		List<Map<String, Object>> messages = describeAll(this.codec.deserializeAll(snap.messageJson));
		if (!snap.inFlightToolCalls.isEmpty()) {
			Map<String, Object> assistant = new LinkedHashMap<>();
			assistant.put("type", "ASSISTANT");
			assistant.put("content", snap.assistantContent);
			List<Map<String, Object>> calls = new ArrayList<>();
			for (PendingToolCall item : snap.inFlightToolCalls) {
				Map<String, Object> call = new LinkedHashMap<>();
				call.put("id", item.toolCallId);
				call.put("name", item.name);
				call.put("arguments", item.arguments);
				calls.add(call);
			}
			assistant.put("toolCalls", calls);
			messages.add(assistant);

			List<Map<String, Object>> responses = new ArrayList<>();
			for (PendingToolCall item : snap.inFlightToolCalls) {
				PendingToolCall fresh = this.store.pending(snap.runId, item.toolCallId);
				PendingToolCall e = fresh == null ? item : fresh;
				Map<String, Object> response = new LinkedHashMap<>();
				response.put("id", e.toolCallId);
				response.put("name", e.name);
				response.put("status", e.status);
				response.put("executed", e.executed);
				response.put("responseData", e.resultText != null ? e.resultText
						: (PendingToolCall.PENDING.equals(e.status) ? null : e.reason));
				responses.add(response);
			}
			Map<String, Object> tool = new LinkedHashMap<>();
			tool.put("type", "TOOL");
			tool.put("toolResponses", responses);
			messages.add(tool);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("messageCount", messages.size());
		out.put("messages", messages);
		out.put("pending", pendings.stream().map(PendingToolCall::asMap).toList());
		out.put("readyToResume",
				pendings.stream().noneMatch(item -> PendingToolCall.PENDING.equals(item.status)));
		return out;
	}

	private List<Map<String, Object>> describeAll(List<Message> messages) {
		List<Map<String, Object>> out = new ArrayList<>();
		int index = 0;
		for (Message message : messages) {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("index", index++);
			item.put("type", message.getMessageType().name());
			item.put("text", message.getText());
			if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
				List<Map<String, Object>> calls = new ArrayList<>();
				for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
					Map<String, Object> c = new LinkedHashMap<>();
					c.put("id", call.id());
					c.put("name", call.name());
					c.put("arguments", call.arguments());
					calls.add(c);
				}
				item.put("toolCalls", calls);
			}
			if (message instanceof ToolResponseMessage tool) {
				List<Map<String, Object>> responses = new ArrayList<>();
				for (ToolResponseMessage.ToolResponse response : tool.getResponses()) {
					Map<String, Object> r = new LinkedHashMap<>();
					r.put("id", response.id());
					r.put("name", response.name());
					r.put("responseData", response.responseData());
					responses.add(r);
				}
				item.put("toolResponses", responses);
			}
			out.add(item);
		}
		return out;
	}

	private List<String> toolNames() {
		List<String> out = new ArrayList<>();
		for (ToolCallback callback : this.callbacks) {
			out.add(callback.getToolDefinition().name());
		}
		return out;
	}

	public record ChatRequest(String sessionId, String message, String mode) {
	}

	public record FrontendToolResultRequest(String runId, String toolCallId, String result, String source) {
	}

	public record ConfirmRequest(String runId, String toolCallId, Boolean approved, String reason, String source) {
	}

}
