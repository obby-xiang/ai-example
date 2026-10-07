package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.ResilientChatService;
import com.example.configmgr.ai.run.ResumeService;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.StartupResumeRunner;
import com.example.configmgr.ai.session.SessionGate;
import com.example.configmgr.ai.tool.FrontendToolGuard;
import com.example.configmgr.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

/**
 * AI 运行时 HTTP 入口（S4.2 §2 web/AiController）。
 *
 * <table>
 * <caption>端点与语义</caption>
 * <tr><td>{@code POST /api/ai/chat}</td><td>SSE 一轮对话（首包 {@code start}，终帧
 * {@code done}/{@code error}）；同 sessionId 第二轮 → <b>409 携进行中 runId</b>；
 * 挂起池饱和 → <b>503 SUSPEND_POOL_SATURATED</b>；无 key → 503 AI_UNAVAILABLE</td></tr>
 * <tr><td>{@code POST /api/ai/confirm}</td><td>确认门决策（放行/拒绝）；重复 toolCallId →
 * 409 DUPLICATE_TOOL_CALL_ID，未知 id → 409 UNKNOWN_TOOL_CALL</td></tr>
 * <tr><td>{@code POST /api/ai/frontend-tool-result}</td><td>前端工具结果回灌（同一套幂等口径）；
 * 被安全闸拒绝 → <b>400</b> 携机器可读码 + 原因清单（挂起不消费，可重试）；{@code cancelled:true}
 * → 用户取消终态（DC-15）</td></tr>
 * <tr><td>{@code GET /api/ai/events/{runId}}</td><td>reattach：回放该轮状态类帧 + 继续收实时帧
 * （ADR-5 补记 CH-P4）</td></tr>
 * <tr><td>{@code GET /api/ai/runs} / {@code GET /api/ai/runs/{runId}}</td><td>重启后的
 * "发现待续跑轮次"与单轮明细（台账/待决/历史角色）</td></tr>
 * <tr><td>{@code POST /api/ai/runs/{runId}/resume}</td><td>续跑（重建完整历史 → 官方循环）</td></tr>
 * <tr><td>{@code GET /api/ai/history/{sessionId}}</td><td>记忆窗口原文</td></tr>
 * <tr><td>{@code GET /api/ai/health}</td><td>AI 可用性/模型名/记忆后端（供前端降级提示）</td></tr>
 * </table>
 *
 * <p>
 * Q2：无 key 时模型侧 bean 不存在，{@code /chat} 与 {@code /resume} 在<b>开流之前</b>返回
 * 503 {@code AI_UNAVAILABLE}；{@code /health} 亦然。历史的读、确认/回灌的写入不依赖 key，
 * 因此保持可用（它们只读写 Redis）。
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
public class AiController {

	private final AiAvailability availability;

	private final ObjectProvider<ResilientChatService> chatService;

	private final ObjectProvider<ResumeService> resumeService;

	private final ObjectProvider<StartupResumeRunner> startupResumeRunner;

	private final ChatMemory chatMemory;

	private final ChatMemoryRepository chatMemoryRepository;

	private final SessionGate sessionGate;

	private final ConfirmGate confirmGate;

	private final RunStore runStore;

	private final RunRegistry runRegistry;

	private final CancellationRegistry cancellations;

	private final RedisAvailability redisAvailability;

	private final ThreadPoolTaskExecutor aiRunExecutor;

	private final ObjectMapper objectMapper;

	private final AiProperties properties;

	private final Environment environment;

	public AiController(AiAvailability availability, ObjectProvider<ResilientChatService> chatService,
			ObjectProvider<ResumeService> resumeService, ObjectProvider<StartupResumeRunner> startupResumeRunner,
			ChatMemory chatMemory, ChatMemoryRepository chatMemoryRepository, SessionGate sessionGate,
			ConfirmGate confirmGate, RunStore runStore, RunRegistry runRegistry, CancellationRegistry cancellations,
			RedisAvailability redisAvailability,
			@Qualifier("aiRunExecutor") ThreadPoolTaskExecutor aiRunExecutor, ObjectMapper objectMapper,
			AiProperties properties, Environment environment) {
		this.availability = availability;
		this.chatService = chatService;
		this.resumeService = resumeService;
		this.startupResumeRunner = startupResumeRunner;
		this.chatMemory = chatMemory;
		this.chatMemoryRepository = chatMemoryRepository;
		this.sessionGate = sessionGate;
		this.confirmGate = confirmGate;
		this.runStore = runStore;
		this.runRegistry = runRegistry;
		this.cancellations = cancellations;
		this.redisAvailability = redisAvailability;
		this.aiRunExecutor = aiRunExecutor;
		this.objectMapper = objectMapper;
		this.properties = properties;
		this.environment = environment;
	}

	// ── 一轮对话 ────────────────────────────────────────────────────────────

	@PostMapping(value = "/chat", produces = { MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE })
	public ResponseEntity<?> chat(@RequestBody(required = false) AiChatRequest request) {
		ResilientChatService service = this.chatService.getIfAvailable();
		if (!this.availability.isAvailable() || service == null) {
			return json(HttpStatus.SERVICE_UNAVAILABLE, unavailable());
		}
		// R4：Redis 不可用时在**开流之前**快速失败（≤ 一次 PING 的超时，见 RedisAvailability）。
		// 顺序放在参数校验之后：参数错就该报 400，不该被 Redis 状态遮住。
		if (request == null || !StringUtils.hasText(request.sessionId()) || !StringUtils.hasText(request.message())) {
			return json(HttpStatus.BAD_REQUEST, Map.of("code", "BAD_REQUEST", "message", "sessionId 与 message 均为必填"));
		}
		if (!this.redisAvailability.isAvailable()) {
			return json(HttpStatus.SERVICE_UNAVAILABLE, redisUnavailable());
		}

		String sessionId = request.sessionId();
		String runId = UUID.randomUUID().toString();
		SessionGate.Busy busy = this.sessionGate.acquire(sessionId, runId);
		if (busy != null) {
			// ADR-5：409 必须携带进行中轮的 runId，前端据此改走 /api/ai/events/{runId}
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("code", "SESSION_BUSY");
			body.put("message", "该会话已有一轮对话在进行中，请先处理或重挂该轮");
			body.put("sessionId", sessionId);
			body.put("runId", busy.runId());
			body.put("degraded", busy.degraded());
			body.put("reattach", "/api/ai/events/" + busy.runId());
			return json(HttpStatus.CONFLICT, body);
		}

		SseEmitter emitter = new SseEmitter(budgetMillis());
		SseChatEmitter out = this.runRegistry.of(runId);
		out.attach(emitter);
		try {
			this.aiRunExecutor.execute(() -> {
				try {
					service.chat(runId, request, out);
				}
				catch (Exception ex) {
					log.error("AI 轮次未捕获异常 sessionId={} runId={}", sessionId, runId, ex);
					out.error("AI_RUN_FAILED", String.valueOf(ex.getMessage()));
				}
				finally {
					this.sessionGate.release(sessionId, runId);
					this.runRegistry.close(runId);
				}
			});
		}
		catch (RejectedExecutionException ex) {
			log.warn("挂起专用线程池饱和，拒绝 sessionId={} runId={}", sessionId, runId);
			this.sessionGate.release(sessionId, runId);
			this.runRegistry.close(runId);
			return json(HttpStatus.SERVICE_UNAVAILABLE, poolSaturated(runId));
		}
		return ResponseEntity.ok(emitter);
	}

	// ── 外部输入：确认门 / 前端工具 ────────────────────────────────────────

	@PostMapping("/confirm")
	public ResponseEntity<?> confirm(@RequestBody(required = false) ConfirmRequest request) {
		if (request == null || !StringUtils.hasText(request.runId())
				|| !StringUtils.hasText(request.toolCallId())) {
			return json(HttpStatus.BAD_REQUEST, Map.of("code", "BAD_REQUEST", "message", "runId 与 toolCallId 均为必填"));
		}
		boolean approved = Boolean.TRUE.equals(request.approved());
		ConfirmGate.Submission submission = this.confirmGate.submitDecision(request.runId(), request.toolCallId(),
				approved, request.reason());
		if (submission.outcome() == ConfirmGate.Outcome.NOT_FOUND) {
			return json(HttpStatus.CONFLICT, unknownToolCall(request.runId(), request.toolCallId()));
		}
		if (submission.outcome() == ConfirmGate.Outcome.DUPLICATE) {
			return json(HttpStatus.CONFLICT,
					duplicateToolCall(request.runId(), request.toolCallId(), submission.pending().getStatus()));
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("accepted", true);
		body.put("duplicate", false);
		body.put("runId", request.runId());
		body.put("toolCallId", request.toolCallId());
		body.put("status", submission.pending().getStatus());
		body.put("approved", approved);
		body.put("reason", submission.pending().getReason());
		body.put("executed", submission.pending().isExecuted());
		body.put("wokeInProcessGate", submission.woke());
		body.put("storedBy", this.runStore.instanceId());
		body.put("note", "决策已写入 Redis；是否已执行见 executed / GET /api/ai/runs/{runId} 的台账");
		return ResponseEntity.ok(body);
	}

	@PostMapping("/frontend-tool-result")
	public ResponseEntity<?> frontendToolResult(@RequestBody(required = false) FrontendToolResultRequest request) {
		if (request == null || !StringUtils.hasText(request.runId())
				|| !StringUtils.hasText(request.toolCallId())) {
			return json(HttpStatus.BAD_REQUEST, Map.of("code", "BAD_REQUEST", "message", "runId 与 toolCallId 均为必填"));
		}
		boolean cancelled = Boolean.TRUE.equals(request.cancelled());
		ConfirmGate.Submission submission = this.confirmGate.submitFrontendResult(request.runId(),
				request.toolCallId(), request.result(), request.source(), cancelled);
		if (submission.outcome() == ConfirmGate.Outcome.NOT_FOUND) {
			return json(HttpStatus.CONFLICT, unknownToolCall(request.runId(), request.toolCallId()));
		}
		if (submission.outcome() == ConfirmGate.Outcome.DUPLICATE) {
			return json(HttpStatus.CONFLICT,
					duplicateToolCall(request.runId(), request.toolCallId(), submission.pending().getStatus()));
		}
		// DC-15：回灌被安全闸拒绝（如表单填写值不合 schema）→ 400 带机器可读码 + 原因清单；
		// 注意"挂起仍在等待"（状态未变），前端可修正后重试，或带 cancelled=true 放弃。
		if (submission.outcome() == ConfirmGate.Outcome.REJECTED) {
			return json(HttpStatus.BAD_REQUEST,
					rejectedFrontendResult(request.runId(), request.toolCallId(), submission));
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("accepted", true);
		body.put("duplicate", false);
		body.put("runId", request.runId());
		body.put("toolCallId", request.toolCallId());
		body.put("status", submission.pending().getStatus());
		body.put("cancelled", cancelled);
		body.put("executed", false);
		body.put("wokeInProcessGate", submission.woke());
		body.put("storedBy", this.runStore.instanceId());
		return ResponseEntity.ok(body);
	}

	// ── reattach / 发现 / 续跑 ──────────────────────────────────────────────

	/**
	 * 重挂进行中（或刚结束）那一轮的流：先回放外置的状态类帧，再续接实时帧。
	 * 挂起态外置的直接收益 —— 进程死亡后重挂仍能看到"这一轮挂在哪、谁在等什么"。
	 *
	 * <p>
	 * <b>T6 差量补发</b>：可选 {@code ?lastSeq=N} —— 前端带上自己已收到的最大帧序号，
	 * 服务端只补发 {@code seq > N} 的帧（孤儿过滤：本地已有的帧不重发）；不传则全量回放
	 * （老客户端零改动，行为与 S4.2 时期一致）。
	 *
	 * <p>
	 * <b>S5c-2</b>：回放与挂订阅走 {@link SseChatEmitter#replayAndAttach}（同一把出帧锁内完成），
	 * 消掉"回放快照读完、订阅还没挂上"之间丢帧的交接窗口。
	 */
	@GetMapping(value = "/events/{runId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public ResponseEntity<?> events(@PathVariable String runId,
			@RequestParam(required = false) Long lastSeq) {
		RunSnapshot snapshot = this.runStore.get(runId);
		if (snapshot == null) {
			return json(HttpStatus.NOT_FOUND, Map.of("code", "UNKNOWN_RUN", "runId", runId));
		}
		SseEmitter emitter = new SseEmitter(budgetMillis());
		SseChatEmitter out = this.runRegistry.of(runId);
		int replayed = out.replayAndAttach(emitter, lastSeq, isTerminal(snapshot.getStatus()));
		log.debug("reattach runId={} status={} lastSeq={} 回放帧数={}", runId, snapshot.getStatus(), lastSeq, replayed);
		return ResponseEntity.ok(emitter);
	}

	/** 终态判定（含取消）：DONE / FAILED / CANCELLED 三种都不再产帧。 */
	private static boolean isTerminal(String status) {
		return RunSnapshot.DONE.equals(status) || RunSnapshot.FAILED.equals(status)
				|| RunSnapshot.CANCELLED.equals(status);
	}

	// ── 取消（韧性棒：ADR-8 修正③） ────────────────────────────────────────

	/**
	 * 取消一轮对话：{@code POST /api/ai/cancel/{runId}}。
	 *
	 * <p>
	 * 权威态写进 Redis（{@code ai:cancel:<runId>}），因此跨实例/跨进程同样生效；
	 * 同进程时还会当场唤醒挂起闸门与流式看门狗（加速，不改变语义）。
	 * 该轮随即以 {@code done{cancelled:true}} 收尾，快照转 {@code CANCELLED}，
	 * 未决的挂起条目转 {@code CANCELLED}（重启续跑不会把它执行掉）。
	 *
	 * <p>
	 * 幂等：重复取消返回同一事实（{@code alreadyCancelled=true}）。不依赖 AI key
	 * （取消不该因为 key 缺失而不可用）。
	 */
	@PostMapping("/cancel/{runId}")
	public ResponseEntity<?> cancel(@PathVariable String runId) {
		RunSnapshot snapshot = this.runStore.get(runId);
		Map<String, Object> before = this.cancellations.status(runId);
		CancellationRegistry.CancelResult result = this.cancellations.cancel(runId, this.runStore.instanceId());
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("runId", runId);
		body.put("cancelled", true);
		body.put("alreadyCancelled", Boolean.TRUE.equals(before.get("redisFlag"))
				|| Boolean.TRUE.equals(before.get("localFlag")));
		body.put("redisFlagWritten", result.redisWritten());
		body.put("wokeInProcess", result.wokeInProcess());
		body.put("cancelKey", this.cancellations.keyFor(runId));
		body.put("degraded", this.cancellations.degraded());
		body.put("runExists", snapshot != null);
		body.put("statusBeforeCancel", snapshot == null ? null : snapshot.getStatus());
		body.put("storedBy", this.runStore.instanceId());
		body.put("note", "取消以 Redis 标志为准；运行中的轮次会在事件边界以 done{cancelled:true} 收尾");
		return ResponseEntity.ok(body);
	}

	/** 取消标志现状（只读，取证用）。 */
	@GetMapping("/cancel/{runId}")
	public ApiResponse<Map<String, Object>> cancelStatus(@PathVariable String runId) {
		return ApiResponse.ok(this.cancellations.status(runId));
	}

	// ── 启动自动续跑（N3/N8） ───────────────────────────────────────────────

	/** 最近一次启动扫描的结果（含跳过原因）；未开启开关时返回 scanned=false + 原因。 */
	@GetMapping("/startup-resume")
	public ApiResponse<Map<String, Object>> startupResume() {
		StartupResumeRunner runner = this.startupResumeRunner.getIfAvailable();
		if (runner == null) {
			return ApiResponse.ok(Map.of("scanned", false, "reason", "AI_UNAVAILABLE",
					"message", "无 AI key，模型侧未装配，启动自动续跑不参与"));
		}
		return ApiResponse.ok(runner.lastScan());
	}

	/**
	 * 手工触发一次启动口径的扫描（运维用）：<b>强制重扫</b>（自动扫描每实例只跑一次，
	 * 于是"进程起来 20 秒后才发现某个轮次超龄成僵尸"这种情形需要人来踢一脚）。
	 */
	@PostMapping("/startup-resume/scan")
	public ApiResponse<Map<String, Object>> startupResumeScan() {
		StartupResumeRunner runner = this.startupResumeRunner.getIfAvailable();
		if (runner == null) {
			return ApiResponse.ok(Map.of("scanned", false, "reason", "AI_UNAVAILABLE"));
		}
		return ApiResponse.ok(runner.scan(true));
	}

	@GetMapping("/runs")
	public ApiResponse<List<Map<String, Object>>> runs() {
		ResumeService service = this.resumeService.getIfAvailable();
		if (service == null) {
			return ApiResponse.ok(List.of());
		}
		return ApiResponse.ok(service.listRuns());
	}

	@GetMapping("/runs/{runId}")
	public ApiResponse<Map<String, Object>> run(@PathVariable String runId) {
		ResumeService service = this.resumeService.getIfAvailable();
		if (service == null) {
			return ApiResponse.ok(Map.of("exists", false));
		}
		return ApiResponse.ok(service.describe(runId));
	}

	@PostMapping(value = "/runs/{runId}/resume", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<?> resume(@PathVariable String runId) {
		ResumeService service = this.resumeService.getIfAvailable();
		if (!this.availability.isAvailable() || service == null) {
			return json(HttpStatus.SERVICE_UNAVAILABLE, unavailable());
		}
		// R4：续跑同样必须"快速失败"（它读快照/台账/待决条目，全是 Redis 操作）
		if (!this.redisAvailability.isAvailable()) {
			return json(HttpStatus.SERVICE_UNAVAILABLE, redisUnavailable());
		}
		ResumeService.ResumeResult result = service.resume(runId);
		return switch (result.outcome()) {
			case ACCEPTED -> ResponseEntity.accepted().contentType(MediaType.APPLICATION_JSON).body(result.body());
			case ALREADY_DONE, ALREADY_CANCELLED ->
				ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(result.body());
			case NOT_FOUND -> json(HttpStatus.NOT_FOUND, result.body());
			case POOL_SATURATED -> json(HttpStatus.SERVICE_UNAVAILABLE, result.body());
			default -> json(HttpStatus.CONFLICT, result.body());
		};
	}

	// ── 记忆与健康 ──────────────────────────────────────────────────────────

	/**
	 * 该 mapping 声明了两种 produces（SSE 与 JSON），出错分支必须显式指定 JSON 内容类型，
	 * 否则响应体走 SSE 转换器选择并抛 "No converter"（实测 500）。
	 */
	private ResponseEntity<Map<String, Object>> json(HttpStatus status, Map<String, Object> body) {
		return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
	}

	@GetMapping("/history/{sessionId}")
	public ApiResponse<Map<String, Object>> history(@PathVariable String sessionId) {
		List<Map<String, Object>> items = new ArrayList<>();
		for (Message message : this.chatMemory.get(sessionId)) {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("type", message.getMessageType().name());
			item.put("text", message.getText());
			if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
				item.put("toolCalls", assistant.getToolCalls());
			}
			if (message instanceof ToolResponseMessage toolResponse) {
				item.put("toolResponses", toolResponse.getResponses());
			}
			Object timestamp = message.getMetadata().get(MessageJsonCodec.FIELD_TIMESTAMP);
			if (timestamp != null) {
				item.put("timestamp", timestamp);
			}
			items.add(item);
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("sessionId", sessionId);
		body.put("count", items.size());
		body.put("messages", items);
		return ApiResponse.ok(body);
	}

	/** AI 可用性（N5）：前端据此决定 AI 面板启用/降级提示，无需先发一次对话才知道。 */
	@GetMapping("/health")
	public ResponseEntity<Map<String, Object>> health() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("available", this.availability.isAvailable());
		body.put("code", this.availability.isAvailable() ? "AI_AVAILABLE" : this.availability.code());
		body.put("model", this.environment.getProperty("spring.ai.openai.chat.options.model", "(unset)"));
		body.put("baseUrl", this.environment.getProperty("spring.ai.openai.base-url", "(unset)"));
		body.put("memoryBackend", this.chatMemoryRepository.getClass().getSimpleName());
		body.put("sessionTtl", String.valueOf(this.properties.getSessionTtl()));
		body.put("confirmTimeoutSeconds", this.confirmGate.confirmTimeoutSeconds());
		body.put("suspendPoolSize", this.properties.getSuspend().getPoolSize());
		body.put("activeSuspendGates", this.confirmGate.pendingGates());
		body.put("heldSessions", this.sessionGate.heldSessions());
		body.put("sessionLockRenewals", this.sessionGate.renewals());
		body.put("resilience", Map.of(
				"firstByteTimeout", String.valueOf(this.properties.getResilience().getFirstByteTimeout()),
				"interEventTimeout", String.valueOf(this.properties.getResilience().getInterEventTimeout()),
				"maxAttempts", this.properties.getResilience().getMaxAttempts(),
				"totalBudget", String.valueOf(this.properties.getResilience().getTotalBudget())));
		body.put("resumeOnStartup", this.properties.getResume().isOnStartup());
		// 探活态必须"现算"：describe() 只回读上次结果，运维看 /health 时要能立刻看到 Redis 是否可用
		// （代价 = 一次带短超时的 PING，不可用时 ≤ spring.data.redis.timeout）。
		this.redisAvailability.isAvailable();
		body.put("redis", this.redisAvailability.describe());
		body.put("cancellations", Map.of("registeredRuns", this.cancellations.registeredRuns(),
				"redisPolls", this.cancellations.polls(), "degraded", this.cancellations.degraded()));
		body.put("instanceId", this.runStore.instanceId());
		body.put("checkedAt", Instant.now().toString());
		if (!this.availability.isAvailable()) {
			body.put("message", this.availability.reason());
			return json(HttpStatus.SERVICE_UNAVAILABLE, body);
		}
		return ResponseEntity.ok(body);
	}

	// ── 错误体 ──────────────────────────────────────────────────────────────

	private Map<String, Object> unavailable() {
		return Map.of("code", this.availability.code(), "message", this.availability.reason());
	}

	/** R4：Redis 不可用的 503 体（带探活事实，便于前端与排障区分"没 key"与"没 Redis"）。 */
	private Map<String, Object> redisUnavailable() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("code", RedisAvailability.code());
		body.put("message", this.redisAvailability.reason());
		body.put("redis", this.redisAvailability.describe());
		return body;
	}

	private Map<String, Object> poolSaturated(String runId) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("code", "SUSPEND_POOL_SATURATED");
		body.put("message", "挂起专用线程池已饱和（池容量 = app.ai.suspend.pool-size），请稍后重试");
		body.put("runId", runId);
		body.put("poolSize", this.properties.getSuspend().getPoolSize());
		return body;
	}

	private Map<String, Object> unknownToolCall(String runId, String toolCallId) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("code", "UNKNOWN_TOOL_CALL");
		body.put("message", "未知或已过期的 runId/toolCallId");
		body.put("runId", runId);
		body.put("toolCallId", toolCallId);
		body.put("instanceId", this.runStore.instanceId());
		return body;
	}

	private Map<String, Object> duplicateToolCall(String runId, String toolCallId, String status) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("code", "DUPLICATE_TOOL_CALL_ID");
		body.put("message", "该 toolCallId 已有决策或结果，重复提交被拒绝");
		body.put("runId", runId);
		body.put("toolCallId", toolCallId);
		body.put("status", status);
		body.put("duplicate", true);
		body.put("instanceId", this.runStore.instanceId());
		return body;
	}

	/**
	 * 回灌被安全闸拒绝（DC-15）：400 + 机器可读码（如 {@code FORM_RESULT_REJECTED}）+ 原因清单。
	 *
	 * <p>为什么是 400 而不是 409：这不是"重复/过期提交"（那是 409 DUPLICATE/UNKNOWN），
	 * 而是"内容不合前端工具的参数契约" —— 前端据此提示用户改值后重试；
	 * 挂起<b>未被消费</b>（状态仍是 PENDING），所以重试不需要重新发起整轮对话。
	 */
	private Map<String, Object> rejectedFrontendResult(String runId, String toolCallId,
			ConfirmGate.Submission submission) {
		FrontendToolGuard.Verdict verdict = submission.verdict();
		Map<String, Object> body = new LinkedHashMap<>();
		// REJECTED 必带非空 verdict（ConfirmGate 只在"复核不通过"那一条分支产出 REJECTED），故不兜 null
		body.put("code", verdict.code());
		body.put("message", verdict.message());
		body.put("reasons", verdict.reasons());
		body.put("runId", runId);
		body.put("toolCallId", toolCallId);
		body.put("name", submission.pending() == null ? null : submission.pending().getName());
		body.put("status", submission.pending() == null ? null : submission.pending().getStatus());
		body.put("duplicate", false);
		body.put("accepted", false);
		body.put("instanceId", this.runStore.instanceId());
		body.put("note", "回灌被拒：挂起仍在等待（状态未变），修正后可重试；用户放弃请带 cancelled=true");
		return body;
	}

	private long budgetMillis() {
		return this.properties.getResilience().getTotalBudget().toMillis();
	}

	/** 挂起项快照（诊断用：确认门/前端工具各自等在哪里）。 */
	@GetMapping("/pending/{runId}")
	public ApiResponse<List<Map<String, Object>>> pending(@PathVariable String runId) {
		List<Map<String, Object>> items = new ArrayList<>();
		for (PendingToolCall pending : this.runStore.pendings(runId)) {
			items.add(pending.asMap());
		}
		return ApiResponse.ok(items);
	}

}
