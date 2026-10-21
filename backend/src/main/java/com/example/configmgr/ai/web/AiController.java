package com.example.configmgr.ai.web;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiConfigService;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.AiRateLimiter;
import com.example.configmgr.ai.gate.InputSanitizer;
import com.example.configmgr.ai.feedback.AiFeedback;
import com.example.configmgr.ai.feedback.AiFeedbackRepository;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.memory.RedisChatMemoryRepository;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.FrameStore;
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
import com.example.configmgr.job.service.JobWatchdog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * <tr><td>{@code DELETE /api/ai/history/{sessionId}}</td><td>删该会话的记忆面（前端「新建对话」）；
 * <b>幂等</b>：不存在也 200；不查 AI key，Redis 不可用 503；会话在跑时只报告 {@code runActive}，
 * <b>不 409</b></td></tr>
 * <tr><td>{@code GET /api/ai/health}</td><td>AI 可用性/模型名/记忆后端（供前端降级提示）</td></tr>
 * </table>
 *
 * <p>
 * Q2：无 key 时模型侧 bean 不存在，{@code /chat} 与 {@code /resume} 在<b>开流之前</b>返回
 * 503 {@code AI_UNAVAILABLE}；{@code /health} 亦然。历史的读与删除、确认/回灌的写入不依赖 key，
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

	private final JobWatchdog jobWatchdog;

	/**
	 * R-163 T5：赞踩反馈仓储。**字段注入**（非构造器）——本批后端红线仅限赞踩端点，
	 * 构造器签名不变 ⇒ 既有 AiController 全部测试（@WebMvcTest 等）零触碰。
	 */
	@Autowired
	private AiFeedbackRepository aiFeedbackRepository;

	/**
	 * B23L1 批①：事件日志（{@code archiveMaxSeq} 的真值源）。同为**字段注入** ——
	 * 构造器签名不变 ⇒ 既有 {@code new AiController(...)} 测试零触碰；未注入（局部上下文/
	 * 单测直接 new）时回落 {@link RunStore#lastEventSeq}（兼容期 LIST 口径），语义与 B1 一致。
	 */
	@Autowired(required = false)
	private FrameStore frameStore;

	/**
	 * 安全防护专项批①：输入归一化（{@code /chat} 入口闸门 1）。**字段注入** —— 构造器签名不变 ⇒
	 * 既有 {@code new AiController(...)} 的全部测试零触碰；Spring 装配来自
	 * {@link InputSanitizer}（同一 {@code ai.gate} 包，组件扫描覆盖）。
	 */
	@Autowired
	private InputSanitizer inputSanitizer;

	/**
	 * 安全防护专项批①：{@code /chat} 限流（入口闸门 3）。同为字段注入。
	 */
	@Autowired
	private AiRateLimiter aiRateLimiter;

	/**
	 * 模型配置专项批①：配置快照（{@code /health} 的 additive 扩展面）。同为**字段注入**：
	 * 构造器签名不变 ⇒ 既有 {@code new AiController(...)} 的用例零触碰；未注入时
	 * {@code /health} 的旧键照旧、新键缺省（"无 provider"口径）。
	 */
	@Autowired(required = false)
	private AiConfigService aiConfigService;

	/**
	 * 上下文统计与压缩专项批①：分母取数点 + 端点装配。同为**字段注入**（构造器签名不变 ⇒
	 * 既有 {@code new AiController(...)} 的用例零触碰）；未注入时端点回 503（"未装配"如实失败），
	 * {@code /health} 的 {@code compactionOn} 为 false。
	 */
	@Autowired(required = false)
	private com.example.configmgr.ai.context.ContextCompactor contextCompactor;

	@Autowired(required = false)
	private com.example.configmgr.ai.context.ContextBreakdownService contextBreakdownService;

	public AiController(AiAvailability availability, ObjectProvider<ResilientChatService> chatService,
			ObjectProvider<ResumeService> resumeService, ObjectProvider<StartupResumeRunner> startupResumeRunner,
			ChatMemory chatMemory, ChatMemoryRepository chatMemoryRepository, SessionGate sessionGate,
			ConfirmGate confirmGate, RunStore runStore, RunRegistry runRegistry, CancellationRegistry cancellations,
			RedisAvailability redisAvailability,
			@Qualifier("aiRunExecutor") ThreadPoolTaskExecutor aiRunExecutor, ObjectMapper objectMapper,
			AiProperties properties, Environment environment, JobWatchdog jobWatchdog) {
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
		this.jobWatchdog = jobWatchdog;
	}

	// ── 一轮对话 ────────────────────────────────────────────────────────────

	@PostMapping(value = "/chat", produces = { MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE })
	public ResponseEntity<?> chat(@RequestBody(required = false) AiChatRequest request,
			HttpServletRequest httpRequest) {
		ResilientChatService service = this.chatService.getIfAvailable();
		if (!this.availability.isAvailable() || service == null) {
			return json(HttpStatus.SERVICE_UNAVAILABLE, unavailable());
		}
		// R4：Redis 不可用时在**开流之前**快速失败（≤ 一次 PING 的超时，见 RedisAvailability）。
		// 顺序：必填校验 → Redis 探活 → 限流 → 归一化 → 空拒 → 长度。
		// 必填校验在探活之前（参数错就该报 400，不该被 Redis 状态遮住）；其余三道闸依赖 Redis 计数，
		// 故排在探活之后 —— 由此产生的既定顺序是：Redis 不可用时，超长请求会先撞 503 而不是 400。
		if (request == null || !StringUtils.hasText(request.sessionId()) || !StringUtils.hasText(request.message())) {
			return json(HttpStatus.BAD_REQUEST, Map.of("code", "BAD_REQUEST", "message", "sessionId 与 message 均为必填"));
		}
		if (!this.redisAvailability.isAvailable()) {
			return json(HttpStatus.SERVICE_UNAVAILABLE, redisUnavailable());
		}
		// 批① 闸门 1：限流（§5.1 #3）。**排在归一化/长度之前**（裁决 I2，2026-10-21）：所有 /chat 请求
		// 一律计数 —— 超长/纯不可见字符也是请求，同样消耗额度（防洪优先于"额度洁癖"：否则攻击者可用
		// 超长体绕过限流做免费试探）。同时它在会话门之前：被限流的请求不该占住一个 in-flight 位。
		AiRateLimiter.Decision rate = this.aiRateLimiter.check(request.sessionId(), clientIp(httpRequest));
		if (!rate.allowed()) {
			return rateLimited(rate);
		}
		// 批① 闸门 2：归一化（只归一不拦截，§5.1 #2）。先归一后量长度 —— 量的是"真正进模型的那份"，
		// 零宽填充不该算进用户的额度；长度口径 = UTF-16 码元数，与前端 maxlength 同一把尺。
		InputSanitizer.Result sanitized = this.inputSanitizer.sanitize(request.message());
		if (sanitized.changed()) {
			// 只记长度差与剥离计数：原文可能含敏感数据，日志里不得出现
			log.info("输入归一化生效 sessionId={} 长度 {}→{}（剥离格式字符 {} / 控制字符 {}）",
					request.sessionId(), sanitized.originalLength(), sanitized.text().length(),
					sanitized.removedFormat(), sanitized.removedControl());
		}
		// 归一后为空 = 原文只由不可见字符构成（"零宽填充"式空转）：与"message 必填"同一口径拒掉，
		// 否则会带着一条空 user 消息进模型与记忆窗口。
		if (!StringUtils.hasText(sanitized.text())) {
			return json(HttpStatus.BAD_REQUEST,
					Map.of("code", "BAD_REQUEST", "message", "message 归一化后为空（原文仅含不可见字符），请重新输入"));
		}
		// 批① 闸门 3：长度（§5.1 #1 / 数据最小化 R5）。超限**拒绝不截断**：截断会让模型基于半句话下结论。
		int maxChars = this.properties.getInput().getMaxChars();
		if (sanitized.text().length() > maxChars) {
			return json(HttpStatus.BAD_REQUEST, messageTooLong(sanitized.text().length(), maxChars));
		}

		String sessionId = request.sessionId();
		// 归一后的文本才是进模型/进记忆的那份（原始 request 不可变，故换一个新 record）
		AiChatRequest effective = sanitized.changed()
				? new AiChatRequest(sessionId, sanitized.text(), request.context()) : request;
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

		SseEmitter emitter = new SseEmitter(NO_TRANSPORT_DEADLINE);
		SseChatEmitter out = this.runRegistry.of(runId);
		out.attach(emitter);
		try {
			this.aiRunExecutor.execute(() -> {
				try {
					service.chat(runId, effective, out);
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
		// B23L1 批②b（读侧续期 touchActiveRun，方案第 5 条）：<b>先续命再落决策</b> —— 挂起线程可能早已不在本实例
		// （挂起在 A、决策落在 B），卡的后台数据只能由"有人在读/在写"这件事本身来续。
		// 自带终态判据：轮已终态 / 快照已删 ⇒ 不续期（不养孤儿键）。
		this.runStore.touchActiveRun(request.runId());
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
		body.put("resumeTriggered", submission.resumeTrigger().triggered());
		body.put("resumeOutcome", submission.resumeTrigger().outcome());
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
		// B23L1 批②b：读侧续期（同 /confirm 的口径：先续命再落回灌）
		this.runStore.touchActiveRun(request.runId());
		boolean cancelled = Boolean.TRUE.equals(request.cancelled());
		ConfirmGate.Submission submission = this.confirmGate.submitFrontendResult(request.runId(),
				request.toolCallId(), request.result(), request.source(), cancelled, request.outcome());
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
		body.put("resumeTriggered", submission.resumeTrigger().triggered());
		body.put("resumeOutcome", submission.resumeTrigger().outcome());
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
	 *
	 * <p>
	 * <b>B23L1 批①</b>：回放源改走 {@link FrameStore}（生产 = Redis Stream {@code ai:xevents:}；
	 * 兼容期内部把旧的 {@code ai:events:} LIST 串在前面并按 {@code seq} 去重），
	 * 差量/孤儿过滤口径不变；连接层因此无状态 —— 任何实例都能 serve 任意 run 的 reattach。
	 */
	@GetMapping(value = "/events/{runId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public ResponseEntity<?> events(@PathVariable String runId,
			@RequestParam(required = false) Long lastSeq) {
		RunSnapshot snapshot = this.runStore.get(runId);
		if (snapshot == null) {
			return json(HttpStatus.NOT_FOUND, Map.of("code", "UNKNOWN_RUN", "runId", runId));
		}
		// B23L1 批②b：读侧续期（重挂是最强的"有人还在看这张卡"信号；终态/缺失不续期）
		this.runStore.touchActiveRun(snapshot);
		SseEmitter emitter = new SseEmitter(NO_TRANSPORT_DEADLINE);
		SseChatEmitter out = this.runRegistry.of(runId);
		int replayed = out.replayAndAttach(emitter, lastSeq, isTerminal(snapshot.getStatus()));
		log.debug("reattach runId={} status={} lastSeq={} 回放帧数={}", runId, snapshot.getStatus(), lastSeq, replayed);
		return ResponseEntity.ok(emitter);
	}

	/**
	 * 终态判定（T3-7 / A14）：委托 {@link RunSnapshot#isTerminal(String)} —— 全仓唯一判定函数
	 * （DONE / FAILED / CANCELLED，含 {@code REJECTED} 归一）。本方法保留为 controller 内的语义化别名。
	 */
	private static boolean isTerminal(String status) {
		return RunSnapshot.isTerminal(status);
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
		// 模型配置专项批①：runner 改为无条件装配 ⇒ "runner 在但 AI 不可用" 必须在这里也给出
		// reason=AI_UNAVAILABLE（旧实现该情形下 runner 压根不存在）。runner 自己的 lastScan 也有同一
		// 分支（启动时跳过多），这里再判一次是为了"运行中 provider 被停用"的窗口。
		if (!this.availability.isAvailable()) {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("scanned", false);
			body.put("reason", this.availability.code());
			body.put("message", this.availability.reason());
			return ApiResponse.ok(body);
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
		if (!this.availability.isAvailable()) {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("scanned", false);
			body.put("reason", this.availability.code());
			body.put("message", this.availability.reason());
			return ApiResponse.ok(body);
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

	/**
	 * session 当前挂起轮的快照（T2b-D#2）：{@code GET /api/ai/runs/current?sessionId=}。
	 *
	 * <h2>三分支语义（红队 D1）</h2>
	 * <ul>
	 * <li><b>已超时</b>：等待循环把仍 PENDING 的条目标成 {@code TIMEOUT} → 条目
	 * {@code entryStatus=TIMEOUT}、{@code awaitingExternal=false}；</li>
	 * <li><b>他端已提交</b>：回灌/决策已落库 → 条目 {@code entryStatus=FRONTEND_RESULT} 等
	 * 决态、{@code awaitingExternal=false}；</li>
	 * <li><b>进程已死</b>：索引键 TTL 过期缺失（与会话锁同寿）→ 明确空态
	 * （{@code found:false}）。</li>
	 * </ul>
	 *
	 * <p>
	 * {@code unresolvedExternal} 口径对齐 {@code ResumeService}（status==PENDING 且
	 * kind!=BACKEND 的 toolCallId 列表），据此计算 {@code awaitingExternal}：
	 * 快照 SUSPENDED 但条目全决的"中间态"（D1）不会误判为仍在等待。
	 *
	 * <p>
	 * <b>约束（红队 B2）</b>：索引不承诺同 session 多页签共享 —— 只覆盖
	 * "挂起可重建"场景，以最后一次挂起为准。
	 */
	@GetMapping("/runs/current")
	public ResponseEntity<?> runsCurrent(@RequestParam(required = false) String sessionId) {
		if (!StringUtils.hasText(sessionId)) {
			return json(HttpStatus.BAD_REQUEST, Map.of("code", "BAD_REQUEST", "message", "sessionId 为必填"));
		}
		String runId = this.runStore.sessionCurrentRunId(sessionId);
		RunSnapshot snapshot = runId == null ? null : this.runStore.get(runId);
		if (runId == null || snapshot == null) {
			// 索引缺失（TTL 过期=进程已死）或索引残留但快照已消失：明确空态
			return ResponseEntity.ok(ApiResponse.ok(emptyCurrentRun()));
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("found", true);
		body.put("runId", runId);
		body.put("snapshotStatus", snapshot.getStatus());
		// B23L1 批②b：读侧续期（快照重载，省掉 touch 内部的一次读；终态快照由 RunStore 判掉）
		this.runStore.touchActiveRun(snapshot);
		List<PendingToolCall> pendings = this.runStore.pendings(runId);
		List<Map<String, Object>> pendingEntries = new ArrayList<>();
		List<String> unresolvedExternal = new ArrayList<>();
		for (PendingToolCall pending : pendings) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("toolCallId", pending.getToolCallId());
			entry.put("entryStatus", pending.getStatus());
			entry.put("kind", pending.getKind());
			entry.put("name", pending.getName());
			entry.put("arguments", pending.getArguments());
			// L0（2026-10-21 裁决 A/B）：刷新恢复的两个判据原样透出 ——
			// req = 该条目的请求帧（confirm_request / frontend_tool_request）<b>真的发过</b>，
			// 由 ConfirmGate 在发射前经 RunStore#markRequestEmitted 置位；kind 是<b>三值原文</b>
			// （CONFIRM/FRONTEND/BACKEND），前端只为 CONFIRM/FRONTEND 且 req=true 的 PENDING 条目建卡。
			// 缺 req 键 = 旧实例/旧数据，前端按 false 处理（宁可少一张卡，不可让用户点到未生效的卡）。
			entry.put("req", Boolean.TRUE.equals(pending.getReq()));
			entry.put("reqAtMs", pending.getReqAtMs() > 0L ? pending.getReqAtMs() : null);
			// R-160 M3：透出到期口径。去超时裁决后前端已退役倒计时/「等待人工确认」静态兜底，
			// 快照里的 expiresAt/atMs 只作对账口径（null = 无期限：确认门/表单/旧数据）。
			// 字段保留是兼容与取证需要（前端 utils/parts 仍原样携带，不据此置灰按钮）。
			entry.put("expiresAt", pending.getExpiresAt());
			entry.put("atMs", pending.getAtMs());
			// MAJOR-1（收口）：刷新恢复路径也要带预检报告，否则卡面丢了"批准的是哪一版预检"。
			// 非确认门工具/旧数据为 null ⇒ 键仍出现但值为 null（前端按"没有就不显示预检区"处理）。
			entry.put("preflight", pending.getPreflight());
			pendingEntries.add(entry);
			if (PendingToolCall.PENDING.equals(pending.getStatus())
					&& !PendingToolCall.KIND_BACKEND.equals(pending.getKind())) {
				unresolvedExternal.add(pending.getToolCallId());
			}
		}
		body.put("pendingEntries", pendingEntries);
		body.put("unresolvedExternal", unresolvedExternal);
		body.put("awaitingExternal", RunSnapshot.SUSPENDED.equals(snapshot.getStatus()) && !unresolvedExternal.isEmpty());
		body.put("archiveMaxSeq", archiveMaxSeq(runId));
		return ResponseEntity.ok(ApiResponse.ok(body));
	}

	/**
	 * 该轮事件日志的已落档末号（前端 {@code restoreSuspendedSnapshot} 拿它当 {@code lastSeq}，
	 * 只收增量、禁全量回放）。
	 *
	 * <p>
	 * B23L1 批①：新写只进 Stream（{@code ai:xevents:}），{@code ai:events:} LIST 进只读兼容期 ——
	 * 因此末号必须问事件日志（{@link FrameStore#lastSeq} 内部会把 LIST 尾巴也算进来），
	 * 不能再看 LIST 末帧（那会在迁移后恒为 0，前端于是全量重放）。
	 */
	private long archiveMaxSeq(String runId) {
		FrameStore frames = this.frameStore;
		return frames != null ? frames.lastSeq(runId) : this.runStore.lastEventSeq(runId);
	}

	/** 索引缺失/快照消失时的明确空态（进程已死分支）。 */
	private static Map<String, Object> emptyCurrentRun() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("found", false);
		body.put("runId", null);
		body.put("snapshotStatus", null);
		body.put("pendingEntries", List.of());
		body.put("unresolvedExternal", List.of());
		body.put("awaitingExternal", false);
		body.put("archiveMaxSeq", 0L);
		return body;
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
			// 409 家族（B23L1 批②b 显式列出）：续跑权在别的实例手里 = 这一轮已经有主，
			// 不是"服务不可用"（不该 503 让运维误以为要扩容），也不是"没找到"。
			case LEASE_HELD_ELSEWHERE, IN_PROGRESS, ALREADY_RUNNING -> json(HttpStatus.CONFLICT, result.body());
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

	/**
	 * 400 {@code MESSAGE_TOO_LONG}（批① 闸门 2）。稳定码 + 中文文案 + 两枚数字 ——
	 * <b>不回显原文</b>（它可能本身就是敏感数据，且长度已足够定位问题）。
	 */
	private static Map<String, Object> messageTooLong(int length, int limit) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("code", "MESSAGE_TOO_LONG");
		body.put("message", "消息长度 " + length + " 字符，超过单条上限 " + limit + " 字符；请拆分或缩短后重发（本接口不做截断）");
		body.put("length", length);
		body.put("limit", limit);
		return body;
	}

	/**
	 * 429 {@code RATE_LIMITED}（批① 闸门 3）。带 {@code Retry-After}（秒，向上取整）——
	 * 客户端据此退避，而不是盲目重试把窗口继续打满。
	 */
	private static ResponseEntity<Map<String, Object>> rateLimited(AiRateLimiter.Decision decision) {
		String scopeName = AiRateLimiter.SCOPE_SESSION.equals(decision.scope()) ? "该会话" : "当前来源";
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("code", "RATE_LIMITED");
		body.put("message", scopeName + "对话请求过于频繁：每 " + decision.limit() + " 次/分钟（"
				+ decision.scope() + " 维度）已达上限，请约 " + decision.retryAfterSeconds() + " 秒后重试");
		body.put("scope", decision.scope());
		body.put("used", decision.used());
		body.put("limit", decision.limit());
		body.put("retryAfterSeconds", decision.retryAfterSeconds());
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
				.header("Retry-After", String.valueOf(decision.retryAfterSeconds()))
				.contentType(MediaType.APPLICATION_JSON)
				.body(body);
	}

	/**
	 * 限流用的来源 IP：只认 {@code RemoteAddr}。
	 *
	 * <p>{@code X-Forwarded-For} 可被客户端任意伪造，本仓没有前置代理，读它等于把"算谁的额度"
	 * 交给请求方自己声明。将来若上代理，须在代理层覆写并在此显式放开（M3 事项）。
	 */
	private static String clientIp(HttpServletRequest httpRequest) {
		if (httpRequest == null) {
			return "unknown";
		}
		String remote = httpRequest.getRemoteAddr();
		return StringUtils.hasText(remote) ? remote : "unknown";
	}

	/**
	 * 记忆窗口原文（R-144 起：带 {@code toolCalls} 的 ASSISTANT 条目增透 {@code toolCallStates}
	 * 段级状态映射 —— 旧载荷可缺该字段；缺字段 ⇒ 前端占位兜底，后端只保证「缺字段语义」）。
	 *
	 * <p>R-154 #16 分页：新增 {@code offset}/{@code limit} 可选参数，语义 = <b>记忆窗口内切片</b>
	 * （窗口上限 {@code memory.maxMessages=40}，非无限历史）。默认（不传参）保持全量语义
	 * （{@code count}=总条数、{@code hasMore}=false），不误伤 mirror/reattach 全量路径；
	 * {@code messages} 按窗口序（旧→新）切片，{@code total}=窗口总条数、{@code hasMore}=
	 * 切片之前还有更早条目（offset 越界则空片、hasMore=false）。既有字段 {@code sessionId}/{@code count}/{@code messages} 形状不变。
	 */
	@GetMapping("/history/{sessionId}")
	public ApiResponse<Map<String, Object>> history(@PathVariable String sessionId,
			@RequestParam(name = "offset", required = false) Integer offset,
			@RequestParam(name = "limit", required = false) Integer limit) {
		List<Map<String, Object>> items = new ArrayList<>();
		for (Message message : this.chatMemory.get(sessionId)) {
			items.add(toHistoryItem(message));
		}
		int total = items.size();
		int from = Math.min(total, Math.max(0, offset == null ? 0 : offset));
		int to = limit == null ? total : (int) Math.min((long) total, (long) from + Math.max(0, limit));
		List<Map<String, Object>> page = new ArrayList<>(items.subList(from, to));
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("sessionId", sessionId);
		body.put("count", page.size());
		body.put("messages", page);
		body.put("total", total);
		body.put("hasMore", from > 0 && from < total);
		return ApiResponse.ok(body);
	}

	/** R-154：把单条 {@link Message} 拼成 history 载荷条目（R-144 携 toolCallStates，纯附加）。 */
	private Map<String, Object> toHistoryItem(Message message) {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("type", message.getMessageType().name());
		item.put("text", message.getText());
		if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
			item.put("toolCalls", assistant.getToolCalls());
			Object toolCallStates = assistant.getMetadata().get(ResilientChatService.TOOL_CALL_STATES_KEY);
			if (toolCallStates != null) {
				item.put("toolCallStates", toolCallStates);
			}
		}
		if (message instanceof ToolResponseMessage toolResponse) {
			item.put("toolResponses", historyToolResponses(toolResponse));
		}
		Object timestamp = message.getMetadata().get(MessageJsonCodec.FIELD_TIMESTAMP);
		if (timestamp != null) {
			item.put("timestamp", timestamp);
		}
		return item;
	}

	/**
	 * 历史通道的信封解包（P0-1）：模型记忆里 {@code role:tool} 继续带信封 JSON（续跑与首轮同形），
	 * 但 {@code /history} 是<b>回显给人/前端</b>的通道 —— 前端按 {@code responseData} 渲染工具结果原文，
	 * 直接透传信封会让卡片里出现一整段 {@code {"ok":...}}。因此这里只暴露 {@code message} 字段原文；
	 * 旧数据（不是信封）原样透传，前端历史形态不变。
	 */
	private List<Map<String, Object>> historyToolResponses(ToolResponseMessage message) {
		List<Map<String, Object>> out = new ArrayList<>(message.getResponses().size());
		for (ToolResponseMessage.ToolResponse response : message.getResponses()) {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("id", response.id());
			item.put("name", response.name());
			item.put("responseData", unwrapEnvelope(response.responseData()));
			out.add(item);
		}
		return out;
	}

	/** 信封 JSON → {@code message} 原文；识别不了（旧数据/普通 JSON 结果）一律原样返回。 */
	private String unwrapEnvelope(String responseData) {
		if (responseData == null || !responseData.trim().startsWith("{")) {
			return responseData;
		}
		try {
			JsonNode node = this.objectMapper.readTree(responseData);
			// 三键同现才当信封（outcome + retryable + message）：避免把"恰好也有 outcome/message
			// 字段的普通工具 JSON 结果"误判成信封而吞掉原数据。
			if (node != null && node.isObject() && node.path("outcome").isTextual()
					&& node.path("retryable").isBoolean() && node.path("message").isTextual()) {
				return node.path("message").asText();
			}
		}
		catch (Exception ex) {
			log.debug("历史工具结果不是可解析的信封 JSON，按原文透传：{}", ex.getMessage());
		}
		return responseData;
	}

	/**
	 * 删除该会话的记忆窗口（前端「新建对话」：把旧会话的服务端记忆清掉）。
	 *
	 * <h2>语义（R-100 裁决：与读侧同资源异动词）</h2>
	 * <ul>
	 * <li><b>幂等且不 404</b>：删除不存在的会话、或重复删除，都返回 <b>200</b> —— 键本来就会自然
	 * TTL 过期，"已不存在"是正常终态而非错误（对齐 {@code /cancel} 的 {@code runExists} 风格）；
	 * 前端「新建对话」绝不能因为旧键已过期而报错。</li>
	 * <li><b>不加鉴权</b>（DC-10：本期无认证鉴权，与全仓 AI 端点一致）；<b>不查 AI key</b>
	 * （与 {@code /history} 同类：记忆是纯 Redis 操作，无 key 时 {@code chatMemory} bean 依然存在）。</li>
	 * <li><b>Redis 不可用 → 503</b> {@code AI_REDIS_UNAVAILABLE}（R4 口径：删不掉就如实失败，
	 * 不假装成功）。</li>
	 * <li><b>删除范围 = 只删记忆面</b>：走官方 {@code ChatMemory#clear}（DC-05），
	 * {@code MessageWindowChatMemory} 落到仓储的 {@code deleteByConversationId}
	 * = {@code DEL chat:mem:<id>} + {@code SREM chat:mem:__ids__}。
	 * <b>不删</b> {@code ai:session:<sessionId>}（会话滑动 TTL，{@code AiSessionService} 维护）
	 * 与 {@code ai:run:*}（轮次台账/快照，删了会让重启续跑与取证失去依据）—— 两者各有自己的 TTL。</li>
	 * </ul>
	 *
	 * <h2>会话门：只报告，不阻塞</h2>
	 * 该会话若仍有在跑轮次，该轮终局会做记忆收敛（{@code ResilientChatService#convergeMemory}
	 * 的 {@code clear + add}）把窗口整体写回，故本次删除可能被覆盖。这个事实以
	 * {@code runActive} / {@code activeRunId} 报告并留 WARN 日志，
	 * <b>不返回 409</b> —— 硬拒绝会把「新建对话」变成可能失败的动作，破坏「点了就干净」的心智。
	 * <b>前端顺序不关闭该覆盖</b>（口径订正：复核 F2）：{@code cancel} 只是置取消标志 + 唤醒收尾，
	 * 轮次收盘是异步的 ⇒ 在飞路径下本 DELETE <b>必然先于</b>收盘重写（不是概率竞态，顺序收口
	 * 也缩不小该窗口）；残留由会话 TTL 约束，且旧 id 已被前端丢弃（用户不可见）。
	 *
	 * @param sessionId 会话 id（前端页签级；路径参数，与 {@code GET /history/{sessionId}} 同资源）
	 * @return 裸 {@code Map}（不套 {@code ApiResponse} 信封，与最近的幂等先例 {@code /cancel} 同族）
	 */
	@DeleteMapping("/history/{sessionId}")
	public ResponseEntity<Map<String, Object>> deleteHistory(@PathVariable String sessionId) {
		if (!StringUtils.hasText(sessionId)) {
			return json(HttpStatus.BAD_REQUEST, Map.of("code", "BAD_REQUEST", "message", "sessionId 必填"));
		}
		if (!this.redisAvailability.isAvailable()) {
			return json(HttpStatus.SERVICE_UNAVAILABLE, redisUnavailable());
		}
		// 会话门只读一次：只用于报告（不 acquire、不拒绝）
		String activeRunId = this.sessionGate.currentRunId(sessionId);
		List<Message> existing = this.chatMemory.get(sessionId);
		boolean existed = existing != null && !existing.isEmpty();
		if (activeRunId != null) {
			log.warn("删除会话记忆时该会话仍有在跑轮次（记忆可能被该轮收尾的收敛写回）sessionId={} activeRunId={}",
					sessionId, activeRunId);
		}
		this.chatMemory.clear(sessionId);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("sessionId", sessionId);
		// deleted 恒为 true：幂等语义 = "已处于删除状态"（上面已 clear，故不再复核一次读取）
		body.put("deleted", true);
		body.put("existed", existed);
		body.put("memoryKey", RedisChatMemoryRepository.KEY_PREFIX + sessionId);
		// 判存量只问"这一个 id 在不在索引里"：单次 SISMEMBER（复核 F4），不拉全量索引
		body.put("removedFromIndex", !isConversationIndexed(sessionId));
		body.put("runActive", activeRunId != null);
		body.put("activeRunId", activeRunId);
		body.put("storedBy", this.runStore.instanceId());
		body.put("note", "只删记忆面（chat:mem:<sessionId> 与其会话 id 索引）；不删 ai:session:<sessionId> 与 ai:run:*（两者各有自己的 TTL）。"
				+ "删除不存在的会话同样 200（幂等）。runActive=true 表示该会话仍有在跑轮次，"
				+ "该轮收尾的记忆收敛可能把窗口重新写回同名键。");
		return ResponseEntity.ok(body);
	}

	/**
	 * 会话上下文占用（上下文统计与压缩专项批①；设计稿 §3.4(b)）。
	 *
	 * <h2>为什么必须是一个端点（而不是把七项都塞进 done 帧）</h2>
	 * <ul>
	 * <li><b>刷新恢复</b>：本地 store 是内存态，刷新后 pill 必须由服务端重建；</li>
	 * <li><b>展开分项</b>：七项只在用户点开 popover 时才需要，没必要每轮都发；</li>
	 * <li><b>挂起态</b>：挂起期间没有新帧，占用仍要可读。</li>
	 * </ul>
	 *
	 * <p>
	 * 姿态与 {@code GET /history/{sessionId}} 同族：<b>只读</b>、<b>不查 AI key</b>（无 key 时记忆与
	 * {@code ai:ctx:} 依然可读）、<b>Redis 不可用 → 503</b>。响应体是<b>裸对象</b>（与 {@code /cancel}、
	 * {@code DELETE /history} 同姿态），字段见设计稿 §3.4(b)。
	 *
	 * <p>
	 * {@code ?ledger=1} 时附带 {@code ai:ctx:ledger:} 最近 20 条压缩事件（运维面）；默认不带，
	 * 保持每轮/每次刷新都拉的载荷轻。
	 */
	@GetMapping("/context/{sessionId}")
	public ResponseEntity<Map<String, Object>> context(@PathVariable String sessionId,
			@RequestParam(name = "ledger", required = false) Boolean ledger) {
		if (!StringUtils.hasText(sessionId)) {
			return json(HttpStatus.BAD_REQUEST, Map.of("code", "BAD_REQUEST", "message", "sessionId 必填"));
		}
		if (!this.redisAvailability.isAvailable()) {
			return json(HttpStatus.SERVICE_UNAVAILABLE, redisUnavailable());
		}
		if (this.contextBreakdownService == null) {
			return json(HttpStatus.SERVICE_UNAVAILABLE, Map.of("code", "AI_CONTEXT_UNAVAILABLE",
					"message", "上下文计量未装配（ContextBreakdownService 缺失）"));
		}
		List<Message> memory;
		try {
			memory = this.chatMemory.get(sessionId);
		}
		catch (RuntimeException ex) {
			// 读记忆失败 = Redis 抖动：与 /history 同口径如实 503，不假装"用了 0"
			return json(HttpStatus.SERVICE_UNAVAILABLE, redisUnavailable());
		}
		return ResponseEntity.ok(this.contextBreakdownService.snapshot(sessionId, memory, Boolean.TRUE.equals(ledger)));
	}

	/**
	 * 该会话 id 是否仍在记忆仓储的会话 id 索引内 —— {@code removedFromIndex} 的唯一判据。
	 *
	 * <p>
	 * 生产装配是 {@link RedisChatMemoryRepository}（索引键 {@code chat:mem:__ids__}），走单次
	 * {@code SISMEMBER}。此前的写法是 {@code findConversationIds().contains(sessionId)}，而那个 SPI
	 * 方法的语义面是"列出**全部**存活 id"（{@code SMEMBERS} + 逐 id 一次 {@code EXISTS} + 惰性清理），
	 * 拿它做单点判定会引入 N 倍 Redis 往返（复核 F4）。语义等价：本端点在上面刚做过
	 * {@code chatMemory.clear()}（= {@code DEL} + {@code SREM}），故此刻成员只可能被在跑轮次收尾的
	 * {@code clear + add} 重新写回（那个 {@code add} 同时建数据键），两种写法在此处判定一致。
	 *
	 * <p>
	 * 非 {@link RedisChatMemoryRepository} 的实现（测试桩 / 将来换实现）回落到 SPI 的通用契约面。
	 *
	 * <p>
	 * 口径（复核 F3，登记知悉）：本字段是<b>响应时点</b>事实 —— {@code true} 只表示"此刻已不在索引里"，
	 * 不保证随后不被在跑轮次的收盘重写。
	 */
	private boolean isConversationIndexed(String sessionId) {
		if (this.chatMemoryRepository instanceof RedisChatMemoryRepository redisRepository) {
			return redisRepository.isConversationIndexed(sessionId);
		}
		return this.chatMemoryRepository.findConversationIds().contains(sessionId);
	}

	/** AI 可用性（N5）：前端据此决定 AI 面板启用/降级提示，无需先发一次对话才知道。 */
	@GetMapping("/health")
	public ResponseEntity<Map<String, Object>> health() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("available", this.availability.isAvailable());
		body.put("code", this.availability.isAvailable() ? "AI_AVAILABLE" : this.availability.code());
		// 模型配置专项批①（additive）：model/baseUrl 仍<b>保留</b>同名同语义，值优先取配置快照的
		// 默认目标；快照里没有可用 provider 时回落 env 回读 —— "空表 + 无 env" 下与批①之前逐值相同。
		AiConfigService.ResolvedProvider resolved = this.aiConfigService == null ? null
				: this.aiConfigService.resolveDefault().orElse(null);
		body.put("model", resolved != null ? resolved.modelId()
				: this.environment.getProperty("spring.ai.openai.chat.options.model", "(unset)"));
		body.put("baseUrl", resolved != null ? resolved.baseUrl()
				: this.environment.getProperty("spring.ai.openai.base-url", "(unset)"));
		// 新增：当前默认目标的 provider/model 身份 + 逐 provider 的 key 状态（永不含 key 本身）
		body.put("providerId", resolved != null ? resolved.providerId() : null);
		body.put("defaultModelId", resolved != null ? resolved.modelId() : null);
		// 上下文专项批①（additive，仅观测）：分母的实际取值 + 压缩是否真的会被判。
		// 上限未知（null）时压缩不做比例判定（降级态），故 compactionOn 也随之为 false —— 两者同源。
		int contextLimitTokens = this.contextCompactor == null ? 0
				: this.contextCompactor.limitTokens(resolved != null ? resolved.providerId() : null,
						resolved != null ? resolved.modelId() : null);
		body.put("contextLimitTokens", contextLimitTokens > 0 ? contextLimitTokens : null);
		body.put("compactionOn", this.contextCompactor != null && contextLimitTokens > 0);
		body.put("providers", this.aiConfigService == null ? List.of() : this.aiConfigService.describeProviders());
		body.put("memoryBackend", this.chatMemoryRepository.getClass().getSimpleName());
		body.put("sessionTtl", String.valueOf(this.properties.getSessionTtl()));
		// 确认门自 2026-10-20 起不再超时（等人无上限），故 /health 不再报告 confirmTimeoutSeconds。
		// 与确认门原地对称的观测面仍在：值是前端工具键的折算秒（只对即时执行类前端工具有意义，
		// generative_form 豁免 —— 见 ConfirmGate#suspendWaitSeconds）。
		body.put("frontendToolTimeoutSeconds", this.confirmGate.frontendToolTimeoutSeconds());
		body.put("suspendPoolSize", this.properties.getSuspend().getPoolSize());
		body.put("sseDelivery", sseDeliverySnapshot());
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
		// B23L1 批②b：租约设施自检（additive 字段）。值<b>现算</b>（每次真打一次 PING）——
		// 运维要看的是"现在能不能抢租"，不是"上一次抢的时候行不行"。无租约设施（降级装配）= false。
		body.put("leaseOk", this.runStore.leaseService() != null && this.runStore.leaseService().leaseOk());
		body.put("checkedAt", Instant.now().toString());
		// 作业看门狗观测面（R-96 批 B，Q1）：tracked / judgedDeadTotal / judgedTimeoutTotal / lastScanAt，
		// 与上面 AI 通道的投递池指标同一姿态 —— 作业侧失联/超时收敛在 /health 可观测。
		body.put("jobWatchdog", this.jobWatchdog.metrics());
		if (!this.availability.isAvailable()) {
			body.put("message", this.availability.reason());
			return json(HttpStatus.SERVICE_UNAVAILABLE, body);
		}
		return ResponseEntity.ok(body);
	}

	/**
	 * {@code /health} 的 SSE 投递观测面（T3-5 定值回灌补齐，2026-10-09）。
	 *
	 * <p>
	 * 在 {@link SseChatEmitter#deliveryPoolMetrics()} 的 poolSize/active/queueDepth/evicted 之上
	 * 补两个 T3-5 定值对象（补口依据：`docs/evidence/M2-T3-5-压测证据-DS-V4-Flash.md`
	 * §S2.4 与 §【待裁决】6a —— 此前投递队列容量 256 与心跳 2s/5s 在
	 * {@code /health} 均不可观测）：
	 * <ul>
	 * <li>{@code queueCapacity}：每订阅者<b>实时</b>投递队列容量（单一事实源 =
	 * {@code app.ai.sse.delivery-queue-capacity}；T3-5 定值 256，回放初始批豁免）；</li>
	 * <li>{@code heartbeatIntervalMs}：心跳间隔两值（挂起期 / 流式期），按各自唯一来源常量读出
	 * ——{@code ConfirmGate#HEARTBEAT_SECONDS} = 2s、{@code ResilientChatService
	 * #STREAMING_HEARTBEAT_MILLIS} = 5s（T3-5 维持现值，未改）。</li>
	 * </ul>
	 */
	private Map<String, Object> sseDeliverySnapshot() {
		Map<String, Object> snapshot = new LinkedHashMap<>(SseChatEmitter.deliveryPoolMetrics());
		snapshot.put("queueCapacity", this.properties.getSse().getDeliveryQueueCapacity());
		snapshot.put("heartbeatIntervalMs", Map.of(
				"suspend", ConfirmGate.HEARTBEAT_SECONDS * 1000L,
				"streaming", ResilientChatService.STREAMING_HEARTBEAT_MILLIS));
		return snapshot;
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

	/**
	 * AI 流<b>不设传输层死线</b>：{@code -1L} 交给 Servlet 异步语义 = 永不到期
	 * （Servlet 3.1 §2.3.3.3：超时 ≤ 0 的异步操作不会超时）。
	 *
	 * <p>为什么必须拆掉这条死线：确认门与 {@code generative_form} 的等待<b>没有上限</b>
	 * （用户裁决 2026-10-20 第 1/2 条）—— 一条流可能在等人上挂几十分钟，用
	 * {@code app.ai.resilience.total-budget}（300s）当 emitter 期限会在人点击之前把连接掐断。
	 * 该预算仍是<b>机器预算</b>：判据在 {@code StreamWatchdog}（墙钟 − 挂起等待），
	 * 上游不动 / 首包不来照样触发 {@code StreamViolationException}，与本传输死线是两层。
	 *
	 * <p>卡住的写仍有三条释放路径：客户端断开（{@code onError}/{@code onCompletion}）、
	 * 容器写超时（{@code server.tomcat.connection-timeout} 派生，本配置 120s）、
	 * 以及 {@code SseChatEmitter} 的单帧写预算（{@code app.ai.sse.write-timeout}，15s）。
	 */
	private static final long NO_TRANSPORT_DEADLINE = -1L;

	/** 挂起项快照（诊断用：确认门/前端工具各自等在哪里）。 */
	@GetMapping("/pending/{runId}")
	public ApiResponse<List<Map<String, Object>>> pending(@PathVariable String runId) {
		List<Map<String, Object>> items = new ArrayList<>();
		for (PendingToolCall pending : this.runStore.pendings(runId)) {
			items.add(pending.asMap());
		}
		return ApiResponse.ok(items);
	}

	// ── R-163 T5：赞踩反馈（本批唯一后端面；ai_feedback net-new 表，parts[] 持久化不动） ──

	/**
	 * POST /api/ai/feedback —— 赞踩上报（幂等 upsert：message_id 唯一；rating=0 = 取消=删行）。
	 * 请求体 {@code {messageId, sessionId, rating∈1/-1/0, reason?}}；校验失败 400 携可读 message。
	 * 单用户本地应用（无登录体系），created_by 预留隔离位。
	 */
	@PostMapping("/feedback")
	public ResponseEntity<?> feedback(@RequestBody Map<String, Object> body) {
		String messageId = stringOf(body.get("messageId"));
		String sessionId = stringOf(body.get("sessionId"));
		Object ratingRaw = body.get("rating");
		Integer rating = ratingRaw instanceof Number number ? number.intValue() : null;
		String reason = stringOf(body.get("reason"));
		// R-163 收口修 F2：被评价消息正文快照（前 200 字符，人工复盘可读摘要）
		String excerpt = stringOf(body.get("excerpt"));
		if (messageId == null || sessionId == null || rating == null
				|| (rating != 1 && rating != -1 && rating != 0)) {
			return json(HttpStatus.BAD_REQUEST,
					Map.of("code", "BAD_REQUEST", "message", "messageId/sessionId 必填，rating ∈ 1/-1/0"));
		}
		if (rating == 0) {
			AiFeedback existing = this.aiFeedbackRepository.findByMessageId(messageId);
			if (existing != null) {
				this.aiFeedbackRepository.delete(existing);
			}
			return ResponseEntity.ok(ApiResponse.ok(Map.of("saved", false, "cancelled", existing != null)));
		}
		AiFeedback entry = this.aiFeedbackRepository.findByMessageId(messageId);
		boolean saved;
		if (entry == null) {
			entry = new AiFeedback();
			entry.setMessageId(messageId);
			saved = true;
		}
		else {
			saved = false;
		}
		entry.setSessionId(sessionId);
		entry.setRating(rating);
		if (reason != null) {
			entry.setReason(reason.length() > 1024 ? reason.substring(0, 1024) : reason);
		}
		if (excerpt != null) {
			entry.setMessageExcerpt(excerpt.length() > 200 ? excerpt.substring(0, 200) : excerpt);
		}
		this.aiFeedbackRepository.save(entry);
		return ResponseEntity.ok(ApiResponse.ok(Map.of("saved", saved, "cancelled", false)));
	}

	/**
	 * GET /api/ai/feedback —— 设置页「AI 反馈」查看列表（时间/消息摘要/rating/reason，
	 * 仅当前用户=单用户应用全表；上限 100 条，按更新时间倒序）。
	 */
	@GetMapping("/feedback")
	public ApiResponse<List<Map<String, Object>>> feedbackList() {
		List<Map<String, Object>> items = new ArrayList<>();
		for (AiFeedback entry : this.aiFeedbackRepository.findTop100ByOrderByUpdatedAtDesc()) {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("messageId", entry.getMessageId());
			item.put("sessionId", entry.getSessionId());
			item.put("rating", entry.getRating());
			item.put("reason", entry.getReason());
			item.put("excerpt", entry.getMessageExcerpt());
			item.put("createdAt", entry.getCreatedAt());
			item.put("updatedAt", entry.getUpdatedAt());
			items.add(item);
		}
		return ApiResponse.ok(items);
	}

	private static String stringOf(Object value) {
		if (value == null) {
			return null;
		}
		String s = String.valueOf(value).trim();
		return s.isEmpty() ? null : s;
	}

}
