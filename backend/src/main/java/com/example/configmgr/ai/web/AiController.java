package com.example.configmgr.ai.web;

import com.example.configmgr.common.ErrorCodes;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiConfigService;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.context.ContextLimitResolver;
import com.example.configmgr.ai.history.ConversationTranscriptStore;
import com.example.configmgr.ai.history.TranscriptEntries;
import com.example.configmgr.ai.history.TranscriptEntry;
import com.example.configmgr.ai.context.ContextStateStore;
import com.example.configmgr.ai.context.ContextCompactor;
import com.example.configmgr.ai.context.ContextBreakdownService;
import com.example.configmgr.ai.resultview.ToolResultViewProvider;
import com.example.configmgr.ai.resultview.ResultView;
import com.example.configmgr.ai.run.ReplyLanguage;
import com.example.configmgr.ai.run.LeaseService;
import com.example.configmgr.ai.session.SessionPurgeGuard;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.ContextFieldGate;
import com.example.configmgr.ai.gate.InputSanitizer;
import com.example.configmgr.ai.gate.RunContextRefresher;
import com.example.configmgr.ai.feedback.AiFeedback;
import com.example.configmgr.ai.feedback.ExcerptGuard;
import com.example.configmgr.ai.feedback.AiFeedbackRepository;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.observe.AiTrace;
import com.example.configmgr.ai.observe.LogSampling;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.CancelledRunFinalizer;
import com.example.configmgr.ai.run.ClientRequestRegistry;
import com.example.configmgr.ai.run.FrameStore;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.ResilientChatService;
import com.example.configmgr.ai.run.ResumeService;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunLiveness;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SecurityEvents;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.StartupResumeRunner;
import com.example.configmgr.ai.session.SessionGate;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.RunIdentityScope;
import com.example.configmgr.ai.tenant.TenantUser;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.FrontendToolGuard;
import com.example.configmgr.ai.tool.SheetAudit;
import com.example.configmgr.ai.tool.SheetSwitches;
import com.example.configmgr.ai.tool.ToolResultEnvelope;
import com.example.configmgr.ai.tool.ToolResultUserText;
import com.example.configmgr.ai.tool.TruncationContract;
import com.example.configmgr.common.ApiResponse;
import com.example.configmgr.common.AiStateUnavailableException;
import com.example.configmgr.common.ErrorBodyFactory;
import com.example.configmgr.job.service.JobWatchdog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
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
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.NumberUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
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
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Objects;
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
 * <tr><td>{@code POST /api/ai/cancel/client-request}</td><td>停止闭环 additive：按
 * {@code sessionId + clientRequestId} 精确取消（run 未创建则写取消墓碑，随后 /chat 在压缩/建快照/
 * 模型调用前收敛）；不扫描 run、不用 clientRequestId 指定 runId</td></tr>
 * <tr><td>{@code POST /api/ai/confirm}</td><td>确认门决策（放行/拒绝）；重复 toolCallId →
 * 409 DUPLICATE_TOOL_CALL_ID，未知 id → 409 UNKNOWN_TOOL_CALL</td></tr>
 * <tr><td>{@code POST /api/ai/frontend-tool-result}</td><td>前端工具结果回灌（同一套幂等口径）；
 * 被安全闸拒绝 → <b>400</b> 携机器可读码 + 原因清单（挂起不消费，可重试）；
 * <b>结构化结果</b>超 {@code app.ai.result-budget.callback.max-chars} → <b>400
 * FRONTEND_RESULT_TOO_LARGE</b>（不截断、不调 submit、不落库，客户端改小后重试；普通文本超限仍截断 + 标记）；
 * {@code cancelled:true} → 用户取消终态（DC-15）</td></tr>
 * <tr><td>{@code GET /api/ai/events/{runId}}</td><td>reattach：回放该轮状态类帧 + 继续收实时帧
 * （ADR-5 补记 CH-P4）</td></tr>
 * <tr><td>{@code GET /api/ai/runs} / {@code GET /api/ai/runs/{runId}}</td><td>重启后的
 * "发现待续跑轮次"与单轮明细（台账/待决/历史角色）</td></tr>
 * <tr><td>{@code POST /api/ai/runs/{runId}/resume}</td><td>续跑（重建完整历史 → 官方循环）</td></tr>
 * <tr><td>{@code GET /api/ai/history/{sessionId}}</td><td>用户展示历史（transcript，唯一读源）</td></tr>
 * <tr><td>{@code DELETE /api/ai/session/{sessionId}}</td>
 * <td><b>完整会话销毁</b>：删除该会话全部 AI 数据（记忆/run 13 键/
 * 上下文/附件/反馈/会话索引），业务数据不碰；幂等 200，Redis 不可用 503，
 * 有活跃 run 时先取消、其 13 键延后（{@code complete=false} + {@code deferred}），不 409</td></tr>
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

	/**
	 * 归档截断响应头（<b>跨端契约</b>，与 {@code frontend/src/api/sse.ts} 的
	 * {@code ARCHIVE_TRIM_HEADERS} 逐字一致）。
	 *
	 * <p>{@code archive-trimmed} = 本次 reattach 是否跳过了不可回放的区间；
	 * {@code archive-from}/{@code archive-to} = <b>缺口区间</b>（含端点，{@code afterSeq+1} 到
	 * {@code 裁剪低水位-1}），<b>不是</b>回放区间。未截断时三个头都不出现（前端"出现即裁剪"）。
	 * 自定义头必须同时在 CORS 的 exposed 列表里（见 {@code WebConfig#corsConfiguration()}），
	 * 否则兄弟源前端读不到。
	 */
	public static final String HEADER_ARCHIVE_TRIMMED = "archive-trimmed";

	public static final String HEADER_ARCHIVE_FROM = "archive-from";

	public static final String HEADER_ARCHIVE_TO = "archive-to";

	private final AiAvailability availability;

	private final ObjectProvider<ResilientChatService> chatService;

	private final ObjectProvider<ResumeService> resumeService;

	private final ObjectProvider<StartupResumeRunner> startupResumeRunner;

	private final ChatMemory chatMemory;

	private final ChatMemoryRepository chatMemoryRepository;

	private final SessionGate sessionGate;

	private final ConfirmGate confirmGate;

	private final RunStore runStore;

	@Autowired(required = false)
	private ContextStateStore contextStateStore;

	private final RunRegistry runRegistry;

	private final CancellationRegistry cancellations;

	/**
	 * 取消收尾的接棒者（F3）：{@code POST /cancel} 只写旗标时，驱动线程已死/在别的实例就没有
	 * 任何进程内触发点。**字段注入**（构造器签名不变 ⇒ 既有 {@code new AiController(...)} 用例零触碰）；
	 * 未注入（standalone 单测）时退化为"只写旗标 + 事件边界收尾"的旧行为。
	 */
	@Autowired(required = false)
	private CancelledRunFinalizer cancelFinalizer;

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
	 * B23L1 批①：事件日志（{@code archiveMaxSeq} 的真值源）。字段注入保持构造器签名不变；
	 * 生产装配恒为 {@link com.example.configmgr.ai.run.RedisFrameStore}。本类所有读取都已按
	 * "必有"使用（无空判），故这里是<b>必需注入</b>：缺失时启动失败，而不是把轻装配伪装成
	 * 404 / NPE 的语义漂移。
	 */
	@Autowired
	private FrameStore frameStore;

	/**
	 * 安全防护专项批①：输入归一化（{@code /chat} 入口闸门 1）。**字段注入** —— 构造器签名不变 ⇒
	 * 既有 {@code new AiController(...)} 的全部测试零触碰；Spring 装配来自
	 * {@link InputSanitizer}（同一 {@code ai.gate} 包，组件扫描覆盖）。
	 */
	@Autowired
	private InputSanitizer inputSanitizer;

	/**
	 * 防护批③ B2-5：{@code context} 形状闸（白名单化 + 降级不回显）。未注入时退化为"不闸"。
	 */
	@Autowired(required = false)
	private ContextFieldGate contextFieldGate;

	/**
	 * 防护批③ B3-5：安全事件计数（{@code /health.security}）。未注入时 {@code /health} 少这一段。
	 */
	@Autowired(required = false)
	private SecurityEvents securityEvents;

	/**
	 * 会话销毁守卫（销毁专项复审）：{@code /chat} 在创建新 run 前查一次。
	 *
	 * <p>字段注入的理由同本类其余可选组件：构造器签名不变 ⇒ 既有 {@code new AiController(...)}
	 * 单测零触碰。未注入（局部的 standalone 单测）时跳过本闸；生产装配必有该 bean。
	 * 守卫读失败<b>不</b>放行（fail-closed），按 Redis 不可用 503 处理。
	 */
	@Autowired(required = false)
	private SessionPurgeGuard sessionPurgeGuard;

	/**
	 * AI 表格工具治理批：表格开关（{@code /health.sheet} 的配置面）。未注入时少该键。
	 */
	@Autowired(required = false)
	private SheetSwitches sheetSwitches;

	/**
	 * 停止闭环：{@code clientRequestId} ↔ run 的关联/取消墓碑。
	 *
	 * <p>字段注入（构造器签名不变 ⇒ 既有 {@code new AiController(...)} 用例零触碰）；
	 * 未注入（轻装配单测）时 /chat 不带该字段的旧行为逐字不变，新端点回 503（未装配，如实失败）。
	 */
	@Autowired(required = false)
	private ClientRequestRegistry clientRequests;

	/**
	 * AI 表格工具治理批：表格采样/改动计数（{@code /health.sheet.counters}）。未注入时少这一段。
	 */
	@Autowired(required = false)
	private SheetAudit sheetAudit;

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
	private ContextCompactor contextCompactor;

	@Autowired(required = false)
	private ContextBreakdownService contextBreakdownService;

	/**
	 * 用户展示历史（transcript）。字段注入（构造器签名不变 ⇒ 既有单测零触碰）；
	 * 未注入时 {@code /history} 返回 503（展示历史只有一个读源）。
	 */
	@Autowired(required = false)
	private ConversationTranscriptStore transcript;

	/**
	 * L-78（真机 E2E 批② NFEZUI 域 Z-06）：历史面的**结果视图投影器**（与帧面同一批 Bean）。
	 *
	 * <p>`resultView` 分两种通道：实时帧仍由结构化数据源确定性构造；归档历史保留当时的结果原文，
	 * 可解析时才重建视图，解析失败则保留原文，绝不改用当前数据库重算历史。这样不必往
	 * 任何持久面（含 Redis 对话面）补数据，也不会用今天的任务状态改写过去。
	 *
	 * <p>字段注入的理由同既有各组件：构造器签名不变 ⇒ 既有 {@code new AiController(...)} 用例零触碰；
	 * 未注入时历史载荷**不产** {@code resultView} 键（旧形态逐字不变）。
	 */
	@Autowired(required = false)
	private List<ToolResultViewProvider> resultViewProviders = List.of();

	/**
	 * S6 尾项（批3二）：错误体的码级本地化。**字段注入**的理由同既有各组件 —— 构造器签名不变 ⇒
	 * 既有 {@code new AiController(...)} 用例零触碰；未注入时回落调用方中文原文（zh 字节不变）。
	 */
	@Autowired(required = false)
	private ErrorBodyFactory errorBodyFactory;

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
	public ResponseEntity<?> chat(@RequestBody(required = false) AiChatRequest request) {
		ResilientChatService service = this.chatService.getIfAvailable();
		if (!this.availability.isAvailable() || service == null) {
			// AI 日志最小补齐批：这条 return 在 AiTrace.bind 之前 ⇒ 此前日志里一行不留（最大盲区）
			deny(HttpStatus.SERVICE_UNAVAILABLE, this.availability.code(), request == null ? null : request.sessionId(),
					null, "reason", "ai_unavailable");
			return json(HttpStatus.SERVICE_UNAVAILABLE, unavailable());
		}
		ChatPreRead preRead = preReadChat(request);
		if (preRead.rejection() != null) {
			return preRead.rejection();
		}
		ChatInput input = sanitizeChatInput(request);
		if (input.rejection() != null) {
			return input.rejection();
		}
		AiChatRequest effective = effectiveChatRequest(request, preRead.clientRequestId(), input.text());
		ResponseEntity<?> rejection = validateEffectiveChatRequest(effective);
		if (rejection != null) {
			return rejection;
		}
		rejection = sessionPurgeRejection(effective.sessionId());
		if (rejection != null) {
			return rejection;
		}
		String runId = UUID.randomUUID().toString();
		SessionGate.Busy busy = acquireSessionGate(effective.sessionId(), runId);
		if (busy != null) {
			return sessionBusy(effective.sessionId(), busy);
		}
		rejection = claimClientRequest(effective.sessionId(), preRead.clientRequestId(), runId);
		if (rejection != null) {
			return rejection;
		}
		return submitChat(service, effective, input.text(), preRead.clientRequestId(), runId);
	}

	/** 必填校验 → clientRequestId 形状 → Redis 探活 → 重复请求预读，顺序与原入口一致。 */
	private ChatPreRead preReadChat(AiChatRequest request) {
		if (request == null || !StringUtils.isNotBlank(request.sessionId()) || !StringUtils.isNotBlank(request.message())) {
			deny(HttpStatus.BAD_REQUEST, ErrorCodes.BAD_REQUEST, request == null ? null : request.sessionId(), null,
					"reason", "missing_session_or_message");
			return new ChatPreRead(json(HttpStatus.BAD_REQUEST,
					errorBody(ErrorCodes.BAD_REQUEST, "sessionId 与 message 均为必填")), null);
		}
		String clientRequestId = StringUtils.isNotBlank(request.clientRequestId()) ? request.clientRequestId() : null;
		if (clientRequestId != null && !ClientRequestRegistry.isValid(clientRequestId)) {
			deny(HttpStatus.BAD_REQUEST, ErrorCodes.BAD_REQUEST, request.sessionId(), null,
					"reason", "invalid_client_request_id");
			return new ChatPreRead(json(HttpStatus.BAD_REQUEST,
					errorBody(ErrorCodes.BAD_REQUEST, "clientRequestId 形状非法（1..128 位，字母数字 . _ : -）")), null);
		}
		if (!this.redisAvailability.isAvailable()) {
			deny(HttpStatus.SERVICE_UNAVAILABLE, RedisAvailability.code(), request.sessionId(), null,
					"reason", "redis_unavailable");
			return new ChatPreRead(json(HttpStatus.SERVICE_UNAVAILABLE, redisUnavailable()), null);
		}
		ResponseEntity<?> duplicate = duplicateClientRequest(request.sessionId(), clientRequestId);
		return new ChatPreRead(duplicate, clientRequestId);
	}

	/** 已绑定映射直接接回既有轮；预读失败仍由后续原子 claim 作权威判定。 */
	private ResponseEntity<?> duplicateClientRequest(String sessionId, String clientRequestId) {
		if (clientRequestId == null || this.clientRequests == null) {
			return null;
		}
		String boundRunId = null;
		try {
			boundRunId = this.clientRequests.boundRunId(sessionId, clientRequestId);
		}
		catch (RuntimeException ex) {
			if (LogSampling.canAcquire("client-request-pre-read")) {
				log.warn("clientRequestId binding pre-read failed sessionId={}", sessionId, ex);
			}
		}
		if (boundRunId == null) {
			return null;
		}
		deny(HttpStatus.CONFLICT, ErrorCodes.SESSION_BUSY, sessionId, boundRunId,
				"reason", "duplicate_client_request");
		return json(HttpStatus.CONFLICT, duplicateClientRequestBody(sessionId, clientRequestId, boundRunId));
	}

	/** 先归一化再按 UTF-16 码元数验空与长度；超限拒绝而不截断。 */
	private ChatInput sanitizeChatInput(AiChatRequest request) {
		InputSanitizer.Result sanitized = this.inputSanitizer.sanitize(request.message());
		if (sanitized.isChanged()) {
			// 只记长度差与剥离计数：原文可能含敏感数据，日志里不得出现
			log.info("input normalized sessionId={} chars {}->{} (formatRemoved={} controlRemoved={})",
					request.sessionId(), sanitized.originalLength(), sanitized.text().length(), sanitized.removedFormat(),
					sanitized.removedControl());
		}
		if (!StringUtils.isNotBlank(sanitized.text())) {
			deny(HttpStatus.BAD_REQUEST, ErrorCodes.BAD_REQUEST, request.sessionId(), null, "reason",
					"empty_after_sanitize", "chars", sanitized.text().length());
			return new ChatInput(json(HttpStatus.BAD_REQUEST,
					errorBody(ErrorCodes.BAD_REQUEST, "message 归一化后为空（原文仅含不可见字符），请重新输入")), null);
		}
		int maxChars = this.properties.getInput().getMaxChars();
		if (sanitized.text().length() > maxChars) {
			if (this.securityEvents != null) {
				this.securityEvents.inputRejected();
			}
			deny(HttpStatus.BAD_REQUEST, ErrorCodes.MESSAGE_TOO_LONG, request.sessionId(), null,
					"chars", sanitized.text().length(), "limit", maxChars);
			return new ChatInput(json(HttpStatus.BAD_REQUEST, messageTooLong(sanitized.text().length(), maxChars)), null);
		}
		return new ChatInput(null, sanitized.text());
	}

	/** 模型与记忆使用归一文本；展示历史仍携带原始输入。 */
	private AiChatRequest effectiveChatRequest(AiChatRequest request, String clientRequestId, String modelText) {
		ContextFieldGate.Result gated = this.contextFieldGate == null ? null : this.contextFieldGate.sanitize(request.context());
		AiChatRequest.Context effectiveContext = gated == null ? request.context() : gated.context();
		if (gated != null && gated.downgraded() > 0 && this.securityEvents != null) {
			this.securityEvents.contextDowngraded(gated.downgraded());
		}
		return new AiChatRequest(request.sessionId(), modelText, effectiveContext, request.modelId(),
				request.providerId(), request.params(), clientRequestId, request.message());
	}

	private ResponseEntity<?> validateEffectiveChatRequest(AiChatRequest effective) {
		String sessionId = effective.sessionId();
		if (this.aiConfigService != null && StringUtils.isNotBlank(effective.modelId())
				&& (effective.providerId() == null
						|| this.aiConfigService.resolveChatTarget(effective.providerId(), effective.modelId()).isEmpty())) {
			deny(HttpStatus.BAD_REQUEST, ErrorCodes.MODEL_NOT_FOUND, sessionId, null, "model", effective.modelId());
			return json(HttpStatus.BAD_REQUEST, errorBody(ErrorCodes.MODEL_NOT_FOUND,
					"该模型当前不可用（未在可用端点的模型清单里，或端点已停用/密钥不可用）：" + effective.modelId()));
		}
		String paramsRejection = validateParams(effective.params());
		if (paramsRejection != null) {
			deny(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_PARAM, sessionId, null, "reason", paramsRejection);
			return json(HttpStatus.BAD_REQUEST, errorBody(ErrorCodes.INVALID_PARAM, paramsRejection));
		}
		return null;
	}

	/** 已销毁会话不得开新轮；守卫不可读时 fail-closed。 */
	private ResponseEntity<?> sessionPurgeRejection(String sessionId) {
		if (this.sessionPurgeGuard == null) {
			return null;
		}
		boolean purged;
		try {
			purged = this.sessionPurgeGuard.isArmed(sessionId);
		}
		catch (AiStateUnavailableException ex) {
			deny(HttpStatus.SERVICE_UNAVAILABLE, RedisAvailability.code(), sessionId, null, "reason",
					"purge_guard_unreadable");
			return json(HttpStatus.SERVICE_UNAVAILABLE, redisUnavailable());
		}
		if (purged) {
			deny(HttpStatus.GONE, ErrorCodes.SESSION_PURGED, sessionId, null, "action", "NEW_SESSION");
			return json(HttpStatus.GONE, sessionPurged(sessionId));
		}
		return null;
	}

	/** 持有者可知且已判死时才释放旧门；未知持有者不强释放。 */
	private SessionGate.Busy acquireSessionGate(String sessionId, String runId) {
		SessionGate.Busy busy = this.sessionGate.acquire(sessionId, runId);
		if (busy != null && !busy.unknown() && isDeadRound(busy.runId())) {
			if (LogSampling.canAcquire("dead-session-gate")) {
				log.warn("session gate held by dead run, allowing new turn sessionId={} deadRunId={} newRunId={}",
						sessionId, busy.runId(), runId);
			}
			this.sessionGate.releaseDeadHeld(sessionId, busy.runId());
			busy = this.sessionGate.acquire(sessionId, runId);
		}
		return busy;
	}

	private ResponseEntity<?> sessionBusy(String sessionId, SessionGate.Busy busy) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("code", ErrorCodes.SESSION_BUSY);
		body.put("message", localize(ErrorCodes.SESSION_BUSY, "该会话已有一轮对话在进行中，请先处理或重挂该轮"));
		body.put("sessionId", sessionId);
		if (busy.unknown()) {
			body.put("unknown", true);
		}
		else {
			body.put("runId", busy.runId());
			body.put("reattach", "/api/ai/events/" + busy.runId());
		}
		body.put("degraded", busy.degraded());
		deny(HttpStatus.CONFLICT, ErrorCodes.SESSION_BUSY, sessionId, busy.runId(), "heldBy",
				busy.unknown() ? "unknown" : busy.runId(), "degraded", busy.degraded());
		return json(HttpStatus.CONFLICT, body);
	}

	/** 原子绑定请求与 run；重复绑定、提前取消及注册表失败的语义保持原样。 */
	private ResponseEntity<?> claimClientRequest(String sessionId, String clientRequestId, String runId) {
		if (this.clientRequests == null || clientRequestId == null) {
			return null;
		}
		ClientRequestRegistry.Claim claim;
		try {
			claim = this.clientRequests.claim(sessionId, clientRequestId, runId);
		}
		catch (RuntimeException ex) {
			if (LogSampling.canAcquire("client-request-claim")) {
				log.warn("clientRequestId binding failed (Redis unavailable) sessionId={} runId={}", sessionId, runId, ex);
			}
			this.sessionGate.release(sessionId, runId);
			deny(HttpStatus.SERVICE_UNAVAILABLE, RedisAvailability.code(), sessionId, null, "reason",
					"client_request_registry_unavailable");
			return json(HttpStatus.SERVICE_UNAVAILABLE, redisUnavailable());
		}
		if (claim.state() == ClientRequestRegistry.ClaimState.ALREADY_BOUND) {
			this.sessionGate.release(sessionId, runId);
			String existingRunId = claim.runId();
			if (StringUtils.isNotBlank(existingRunId)) {
				deny(HttpStatus.CONFLICT, ErrorCodes.SESSION_BUSY, sessionId, existingRunId,
						"reason", "duplicate_client_request");
				return json(HttpStatus.CONFLICT,
						duplicateClientRequestBody(sessionId, clientRequestId, existingRunId));
			}
			deny(HttpStatus.BAD_REQUEST, ErrorCodes.BAD_REQUEST, sessionId, null, "reason", "client_request_id_reused");
			return json(HttpStatus.BAD_REQUEST,
					errorBody(ErrorCodes.BAD_REQUEST, "clientRequestId 已被另一轮使用，请使用新的请求 id"));
		}
		if (claim.state() == ClientRequestRegistry.ClaimState.CANCELLED) {
			this.cancellations.cancel(runId, this.runStore.instanceId());
			log.info("clientRequestId already cancelled before run creation; turn finishes as cancelled sessionId={} runId={}",
					sessionId, runId);
		}
		return null;
	}

	private ResponseEntity<?> submitChat(ResilientChatService service, AiChatRequest effective, String modelText,
			String clientRequestId, String runId) {
		String sessionId = effective.sessionId();
		SseEmitter emitter = new SseEmitter(NO_TRANSPORT_DEADLINE);
		SseChatEmitter out = this.runRegistry.of(runId);
		out.attach(emitter);
		AiTrace.bind(runId, sessionId);
		AiTrace.event("turn_accepted",
				"context", effective.context() == null || effective.context().page() == null ? "none"
						: effective.context().page(),
				"model", effective.modelId() == null ? "default" : effective.modelId(), "chars", modelText.length());
		Locale replyLocale = ErrorBodyFactory.requestLocale();
		try {
			this.aiRunExecutor.execute(() -> runChatTurn(service, effective, out, replyLocale, runId));
		}
		catch (RejectedExecutionException ex) {
			if (LogSampling.canAcquire("turn-pool-saturated")) {
				log.warn("suspension executor pool saturated, rejecting turn sessionId={} runId={}", sessionId, runId, ex);
			}
			AiTrace.event("turn_rejected", "code", "SUSPEND_POOL_SATURATED");
			this.sessionGate.release(sessionId, runId);
			this.runRegistry.close(runId);
			releaseUnstartedClientRequest(sessionId, clientRequestId, runId);
			return json(HttpStatus.SERVICE_UNAVAILABLE, poolSaturated(runId));
		}
		finally {
			AiTrace.clear();
		}
		return ResponseEntity.ok(emitter);
	}

	private void runChatTurn(ResilientChatService service, AiChatRequest effective, SseChatEmitter out,
			Locale replyLocale, String runId) {
		String sessionId = effective.sessionId();
		LocaleContextHolder.setLocale(replyLocale);
		try {
			service.chat(runId, effective, out);
		}
		catch (Exception ex) {
			handleOutsideTurnException(service, out, sessionId, runId, ex);
		}
		finally {
			LocaleContextHolder.resetLocaleContext();
			this.sessionGate.release(sessionId, runId);
			this.runRegistry.close(runId);
		}
	}

	/** 环外异常仍走取消优先 CAS 与每驱动终帧判据。 */
	private void handleOutsideTurnException(ResilientChatService service, SseChatEmitter out, String sessionId,
			String runId, Exception ex) {
		if (LogSampling.canAcquire("outside-turn-exception")) {
			log.error("uncaught exception in AI turn sessionId={} runId={}", sessionId, runId, ex);
		}
		AiTrace.eventOn(runId, sessionId, "turn_exception", "code", "UNCAUGHT",
				"type", ex.getClass().getSimpleName(), "msg", "Uncaught exception in AI turn");
		writeOutsideTurnFailure(runId, ErrorCodes.AI_RUN_FAILED, () -> {
			service.clearTerminalRunState(runId);
			if (Boolean.TRUE.equals(out.terminalFrameArchivedSinceDriveStart())) {
				log.info("failure terminal frame already archived for this drive; skipping outside-turn error runId={}", runId);
				return;
			}
			out.error(ErrorCodes.AI_RUN_FAILED, localize(ErrorCodes.AI_RUN_FAILED, "AI 服务暂时不可用，请稍后重试。"));
		});
	}

	private void releaseUnstartedClientRequest(String sessionId, String clientRequestId, String runId) {
		if (this.clientRequests == null || clientRequestId == null) {
			return;
		}
		try {
			this.clientRequests.releaseBinding(sessionId, clientRequestId, runId);
		}
		catch (RuntimeException ex) {
			if (LogSampling.canAcquire("client-request-release-unstarted")) {
				log.warn("releasing clientRequestId binding of unstarted turn failed sessionId={}", sessionId, ex);
			}
		}
	}

	private record ChatPreRead(ResponseEntity<?> rejection, String clientRequestId) {
	}

	private record ChatInput(ResponseEntity<?> rejection, String text) {
	}

	/**
	 * <b>环外异常</b>的失败终态写口（批次2 / AR-05）：与 {@code ResilientChatService#fail} /
	 * {@code emptyReply()} <b>共用</b> {@link RunStore#saveTerminalUnlessCancelled} 的同一个原子 CAS。
	 *
	 * <p>三条口径：
	 * <ol>
	 * <li><b>取消优先</b>：当前快照已是取消态（{@code status=CANCELLED} 或 {@code cancelled=true}）
	 *     ⇒ 这一笔不写、返回 {@code false}，调用方不得再发 {@code error} 终帧（取消侧发
	 *     {@code done{cancelled:true}}）；否则一轮会有两条终帧、且"已取消"会被改写成"失败"；</li>
	 * <li><b>已是终态不覆盖</b>：DONE/FAILED/CANCELLED 一律不动（终态先到者赢）；</li>
	 * <li><b>不新增 DB 写入</b>：失败现场落在 Redis 快照、记忆、展示历史与帧归档里 ——
	 *     AR-01 明确禁止为失败现场新增 DB 写入 / 列 / TTL。</li>
	 * </ol>
	 *
	 * @param emitError 失败终帧的发送动作（在<b>同一把</b> per-run 监视器内执行；{@code null} = 不发帧）
	 */
	private void writeOutsideTurnFailure(String runId, String code, Runnable emitError) {
		// N2（返修）：与取消收尾共用同一把 per-run 监视器（经
		// {@link ResilientChatService#finalizeMonitor(String)}），且<b>失败终帧也在锁内发出</b> ——
		// 否则取消的 done{cancelled:true} 可以插在"写完 FAILED"与"锁外 out.error"之间。
		// 没有可用实例（轻装配 / 单测桩）时保持旧口径（单线程语义）。
		ResilientChatService service = this.chatService.getIfAvailable();
		Object monitor = service == null ? null : service.finalizeMonitor(runId);
		if (monitor == null) {
			writeOutsideTurnFailureLocked(runId, code, emitError);
			return;
		}
		synchronized (monitor) {
			writeOutsideTurnFailureLocked(runId, code, emitError);
		}
	}

	private void writeOutsideTurnFailureLocked(String runId, String code, Runnable emitError) {
		if (canWriteOutsideTurnFailureState(runId, code) && emitError != null) {
			emitError.run();
		}
	}

	/** 写失败状态并收敛失败历史（返回"调用方可否发失败终帧"），调用方必须已持有 per-run 监视器。 */
	private boolean canWriteOutsideTurnFailureState(String runId, String code) {
		try {
			RunSnapshot snapshot = this.runStore.get(runId);
			if (snapshot == null) {
				return canEmitOutsideTurnFailureWithoutSnapshot(runId, code);
			}
			if (RunSnapshot.isTerminal(snapshot.getStatus())) {
				return false;
			}
			if (snapshot.isCancelled()) {
				log.info("outside-turn exception leaves failure state unwritten: snapshot cancel flag set (cancel first) runId={}", runId);
				return false;
			}
			String failureMessage = localize(code, "AI 服务暂时不可用，请稍后重试。");
			ResilientChatService service = this.chatService.getIfAvailable();
			List<Message> failureHistory = service == null ? null
					: service.failureHistory(snapshot, snapshot.getAssistantContent(), "", code, failureMessage);
			if (failureHistory != null) {
				snapshot.setMessageJson(new MessageJsonCodec(this.objectMapper).serializeAll(failureHistory));
			}
			snapshot.setStatus(RunSnapshot.FAILED);
			snapshot.setInFlightToolCalls(new ArrayList<>());
			snapshot.setError(code + "：" + failureMessage);
			boolean written = this.runStore.saveTerminalUnlessCancelled(snapshot);
			if (!written) {
				log.info("outside-turn failure state rejected by cancel-first guard runId={} code={} (CANCELLED preserved, no error frame)", runId,
						code);
			}
			else if (service != null && failureHistory != null) {
				service.recordFailureTerminal(snapshot, failureHistory);
			}
			return written;
		}
		catch (RuntimeException writeEx) {
			if (LogSampling.canAcquire("outside-turn-failure-write")) {
				log.warn("outside-turn failure state write failed runId={} (error frame still sent; recovery falls back to snapshot/lease)",
						runId, writeEx);
			}
			return true;
		}
	}

	/** 快照未建时不得裸建失败快照；取消优先，否则只发失败终帧。 */
	private boolean canEmitOutsideTurnFailureWithoutSnapshot(String runId, String code) {
		if (isCancelledFlag(runId)) {
			log.info("outside-turn exception emits no terminal frame: cancel flag set and snapshot absent (cancel first) runId={}", runId);
			return false;
		}
		if (LogSampling.canAcquire("outside-turn-before-snapshot")) {
			log.warn("outside-turn exception before snapshot creation runId={} code={}; not creating snapshot (no naked save), error frame only",
					runId, code);
		}
		return true;
	}

	/**
	 * 本轮参数的取值校验（批③）：越界即 400 {@code INVALID_PARAM}（稳定码在 {@code AiConfigWriter}
	 * 也用于设置页，前端按 code 查同一份文案）。
	 *
	 * <p>
	 * 范围与设置页写路径<b>逐值相同</b>（temperature [0,2] / top_p [0,1] / max_tokens [1,200000]）：
	 * 两条入口同一把尺，否则"设置页存得进、对话发不出"这种割裂就会出现。
	 *
	 * @return null = 通过；否则是给用户看的中文兜底（前端按 code 显示自己的文案）
	 */
	private static String validateParams(AiChatRequest.Params params) {
		if (params == null) {
			return null;
		}
		if (params.temperature() != null) {
			double value = params.temperature();
			if (!Double.isFinite(value) || value < 0d || value > 2d) {
				return "temperature 必须在 [0,2] 之间（当前 = " + value + "）";
			}
		}
		if (params.topP() != null) {
			double value = params.topP();
			if (!Double.isFinite(value) || value < 0d || value > 1d) {
				return "top_p 必须在 [0,1] 之间（当前 = " + value + "）";
			}
		}
		if (params.maxTokens() != null && (params.maxTokens() < 1 || params.maxTokens() > 200_000)) {
			return "max_tokens 必须在 [1,200000] 之间（当前 = " + params.maxTokens() + "）";
		}
		return null;
	}

	// ── 外部输入：确认门 / 前端工具 ────────────────────────────────────────

	/**
	 * 挂起期工作区上下文的回写口（{@code /confirm} 与 {@code /frontend-tool-result} 共用）。
	 *
	 * <h2>为什么把页面事实放在这两条写入口</h2>
	 * 挂起期间用户可以在向导里手动改步骤/勾选，而续跑只读<b>快照</b>里的上下文 —— 这两条入口是
	 * 决策落地前唯一还能拿到"用户此刻看到的页面"的地方。写回发生在
	 * {@link ConfirmGate#submitDecision} 之前，因此同一请求触发的续跑一定读到新值。
	 *
	 * <h2>写入口径</h2>
	 * <ul>
	 * <li><b>页面当前事实优先</b>：{@code page/step/taskId} 与 {@code extra} 里的页面键
	 *     （{@code pageId / selectedDefs / selectionSynced / recentActions / dataVersion / contractVersion}）
	 *     取本次回灌值；换任务或解除绑定必须覆盖旧 run 快照，不能继续操作旧任务。</li>
	 * <li><b>同一任务</b>的 {@code taskType/taskStatus/importMode} 保留服务端已有值，避免重复回灌翻转预检输入；
	 *     换任务时旧任务槽位、创建候选与发布模式作废，再由续跑按新任务权威值重算。</li>
	 * <li><b>可选字段</b>：旧客户端不带 {@code context} ⇒ 不读不写，行为与改造前逐字一致；</li>
	 * <li><b>同一套消毒</b>：{@link ContextFieldGate} 白名单化（未知键丢弃并计数），客户端无法
	 *     伪造服务端槽位或越过终态收窄；</li>
	 * <li><b>只在"决策会被受理"时写</b>：调用方必须已预读待决条目；本方法要求条目存在、
	 *     {@code status=PENDING}、kind 完全匹配（缺失即 409，不放行）、
	 *     且快照状态为 {@code SUSPENDED}。未知/错 kind/DUPLICATE/RUNNING/终态一律<b>不写</b>
	 *     —— 入口拒绝必须保持零副作用，也不能给在跑轮换上下文（整对象写、后写者赢）。</li>
	 * <li><b>失败只 WARN</b>：页面事实是"让续跑更准"的增强，不是决策/授权面 —— 写失败不得让
	 *     用户的确认走不通（续跑会退回旧上下文，与改造前同形）；不新增 HITL、不自动重发。</li>
	 * </ul>
	 */
	private void applyWorkspaceContext(String runId, AiChatRequest.Context raw, PendingToolCall entry,
			String expectedKind) {
		if (raw == null || entry == null || expectedKind == null
				|| (entry.getKind() != null && !expectedKind.equals(entry.getKind()))
				|| !PendingToolCall.PENDING.equals(entry.getStatus())) {
			return;
		}
		AiChatRequest.Context effective = sanitizedWorkspaceContext(raw);
		if (effective == null || !StringUtils.isNotBlank(effective.page())) {
			return;
		}
		try {
			RunSnapshot snapshot = this.runStore.get(runId);
			if (snapshot == null || !RunSnapshot.SUSPENDED.equals(snapshot.getStatus())) {
				return;
			}
			AiContext merged = mergeWorkspaceFacts(AiContext.fromMap(snapshot.getContext()), effective);
			snapshot.setContext(merged.toMap());
			this.runStore.save(snapshot);
			log.info("workspace context written back to snapshot during suspension runId={} page={} taskId={} step={}", runId,
					merged.getPage(), merged.getTaskId(), merged.getStep());
		}
		catch (RuntimeException ex) {
			if (LogSampling.canAcquire("workspace-context-writeback")) {
				log.warn("workspace context write-back during suspension failed (ignored; resume falls back to previous context) runId={}",
						runId, ex);
			}
		}
	}

	/** 客户端可覆盖的页面事实键（换任务时先清掉旧任务作用域，再叠加这些当前页面值）。 */
	private static final List<String> PAGE_FACT_EXTRA_KEYS = List.of("pageId", "selectedDefs", "selectionSynced",
			"recentActions", "dataVersion", "contractVersion");

	/**
	 * 页面事实合并：页面/步骤与任务绑定取客户端当前值；换任务时旧任务槽位、候选和发布模式一并作废。
	 * 同一任务的重复回灌仍保留服务端任务事实与已有发布模式，避免无意翻转预检输入。
	 */
	private static AiContext mergeWorkspaceFacts(AiContext server, AiChatRequest.Context client) {
		Map<String, Object> clientExtra = client.extra() == null ? Map.of() : client.extra();
		boolean taskChanged = server.getTaskId() == null ? client.taskId() != null
				: !server.getTaskId().equals(client.taskId());
		AiContext merged = AiContext.of(client.page(),
				taskChanged ? client.taskType() : server.getTaskType(), client.step(), client.taskId(),
				taskChanged ? client.taskStatus() : server.getTaskStatus(), new LinkedHashMap<>(server.getExtra()));
		if (taskChanged) {
			RunContextRefresher.clearTaskScopedExtra(merged);
			merged.getExtra().remove("importMode");
		}
		Map<String, Object> extra = merged.getExtra();
		for (String key : PAGE_FACT_EXTRA_KEYS) {
			if (clientExtra.containsKey(key)) {
				extra.put(key, clientExtra.get(key));
			}
		}
		Object serverImportMode = extra.get("importMode");
		if (StringUtils.isBlank(Objects.toString(serverImportMode, null))
				&& clientExtra.get("importMode") != null) {
			extra.put("importMode", clientExtra.get("importMode"));
		}
		return merged;
	}

	/** {@code context} 形状闸（与 {@code /chat} 同源；未注入闸门 = 不闸，与改造前同形）。 */
	private AiChatRequest.Context sanitizedWorkspaceContext(AiChatRequest.Context raw) {
		if (raw == null) {
			return null;
		}
		ContextFieldGate.Result gated = this.contextFieldGate == null ? null : this.contextFieldGate.sanitize(raw);
		if (gated != null && gated.downgraded() > 0 && this.securityEvents != null) {
			this.securityEvents.contextDowngraded(gated.downgraded());
		}
		return gated == null ? raw : gated.context();
	}

	@PostMapping("/confirm")
	public ResponseEntity<?> confirm(@RequestBody(required = false) ConfirmRequest request) {
		if (request == null || !StringUtils.isNotBlank(request.runId())
				|| !StringUtils.isNotBlank(request.toolCallId())) {
			return json(HttpStatus.BAD_REQUEST, errorBody(ErrorCodes.BAD_REQUEST, "runId 与 toolCallId 均为必填"));
		}
		ConfirmationReason confirmationReason = confirmationReason(request);
		if (confirmationReason.rejection() != null) {
			return confirmationReason.rejection();
		}
		this.runStore.touchActiveRun(request.runId());
		this.confirmGate.touchSuspendedResources(request.runId());
		PendingToolCall entry = this.runStore.pendingLenient(request.runId(), request.toolCallId());
		PendingToolCall wrongKind = wrongKindEntry(entry, PendingToolCall.KIND_CONFIRM);
		if (wrongKind != null) {
			deny(HttpStatus.CONFLICT, ErrorCodes.TOOL_KIND_MISMATCH, null, request.runId(),
					"kind", wrongKind.getKind(), "expected", PendingToolCall.KIND_CONFIRM, "name", wrongKind.getName());
			return json(HttpStatus.CONFLICT, toolKindMismatch(request.runId(), request.toolCallId(), wrongKind,
					PendingToolCall.KIND_CONFIRM));
		}
		applyWorkspaceContext(request.runId(), request.context(), entry, PendingToolCall.KIND_CONFIRM);
		boolean approved = Boolean.TRUE.equals(request.approved());
		ConfirmGate.Submission submission = submitConfirmation(request, approved, confirmationReason.reason());
		ResponseEntity<?> rejection = confirmationRejection(request, submission);
		return rejection != null ? rejection : confirmationResponse(request, submission, approved);
	}

	/** 原因先归一化再验长；归一后空白仍按 null 使用确认门默认文案。 */
	private ConfirmationReason confirmationReason(ConfirmRequest request) {
		InputSanitizer.Result sanitizedReason = this.inputSanitizer.sanitize(request.reason());
		if (sanitizedReason.isChanged()) {
			log.info("confirmation reason normalized runId={} chars {}->{} (formatRemoved={} controlRemoved={})", request.runId(),
					sanitizedReason.originalLength(), sanitizedReason.text().length(),
					sanitizedReason.removedFormat(), sanitizedReason.removedControl());
		}
		String reason = StringUtils.isNotBlank(sanitizedReason.text()) ? sanitizedReason.text() : null;
		if (reason != null && reason.length() > REASON_MAX_CHARS) {
			return new ConfirmationReason(null, json(HttpStatus.BAD_REQUEST, reasonTooLong(reason.length())));
		}
		return new ConfirmationReason(reason, null);
	}

	private ConfirmGate.Submission submitConfirmation(ConfirmRequest request, boolean approved, String reason) {
		AiTrace.bind(request.runId(), null);
		try {
			return this.confirmGate.submitDecision(request.runId(), request.toolCallId(), approved, reason);
		}
		finally {
			AiTrace.clear();
		}
	}

	private ResponseEntity<?> confirmationRejection(ConfirmRequest request, ConfirmGate.Submission submission) {
		if (submission.outcome() == ConfirmGate.Outcome.NOT_FOUND) {
			return json(HttpStatus.CONFLICT, unknownToolCall(request.runId(), request.toolCallId()));
		}
		if (submission.outcome() == ConfirmGate.Outcome.TOOL_KIND_MISMATCH) {
			return json(HttpStatus.CONFLICT, toolKindMismatch(request.runId(), request.toolCallId(),
					submission.pending(), PendingToolCall.KIND_CONFIRM));
		}
		if (submission.outcome() == ConfirmGate.Outcome.DUPLICATE) {
			return json(HttpStatus.CONFLICT,
					duplicateToolCall(request.runId(), request.toolCallId(), submission.pending().getStatus()));
		}
		return null;
	}

	private ResponseEntity<?> confirmationResponse(ConfirmRequest request, ConfirmGate.Submission submission,
			boolean approved) {
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

	private record ConfirmationReason(String reason, ResponseEntity<?> rejection) {
	}

	@PostMapping("/frontend-tool-result")
	public ResponseEntity<?> frontendToolResult(@RequestBody(required = false) FrontendToolResultRequest request) {
		if (request == null || !StringUtils.isNotBlank(request.runId())
				|| !StringUtils.isNotBlank(request.toolCallId())) {
			return json(HttpStatus.BAD_REQUEST, errorBody(ErrorCodes.BAD_REQUEST, "runId 与 toolCallId 均为必填"));
		}
		this.runStore.touchActiveRun(request.runId());
		this.confirmGate.touchSuspendedResources(request.runId());
		boolean cancelled = Boolean.TRUE.equals(request.cancelled());
		PendingToolCall entry = this.runStore.pendingLenient(request.runId(), request.toolCallId());
		PendingToolCall wrongKind = wrongKindEntry(entry, PendingToolCall.KIND_FRONTEND);
		if (wrongKind != null) {
			deny(HttpStatus.CONFLICT, ErrorCodes.TOOL_KIND_MISMATCH, null, request.runId(),
					"kind", wrongKind.getKind(), "expected", PendingToolCall.KIND_FRONTEND,
					"name", wrongKind.getName(), "cancelled", cancelled);
			return json(HttpStatus.CONFLICT, toolKindMismatch(request.runId(), request.toolCallId(), wrongKind,
					PendingToolCall.KIND_FRONTEND));
		}
		FrontendResultPayload payload = prepareFrontendResult(request, entry, cancelled);
		if (payload.rejection() != null) {
			return payload.rejection();
		}
		applyWorkspaceContext(request.runId(), request.context(), entry, PendingToolCall.KIND_FRONTEND);
		ConfirmGate.Submission submission = submitFrontendResult(request, payload.result(), cancelled);
		ResponseEntity<?> rejection = frontendResultRejection(request, submission);
		return rejection != null ? rejection : frontendResultResponse(request, submission, payload, cancelled);
	}

	/** 结构化超限拒绝；普通文本按原标记与代理对规则截断。 */
	private FrontendResultPayload prepareFrontendResult(FrontendToolResultRequest request, PendingToolCall entry,
			boolean cancelled) {
		int callbackMaxChars = this.properties.getResultBudget().getCallback().getMaxChars();
		String result = request.result();
		int originalChars = result == null ? 0 : result.length();
		if (!cancelled && result != null && originalChars > callbackMaxChars && isStructuredOversize(entry, result)) {
			deny(HttpStatus.BAD_REQUEST, ErrorCodes.FRONTEND_RESULT_TOO_LARGE, null, request.runId(),
					"chars", originalChars, "limit", callbackMaxChars, "name", entry.getName(), "kind", entry.getKind());
			log.info("structured frontend result oversized; rejected without persisting runId={} toolCallId={} tool={} chars={} limit={}",
					request.runId(), request.toolCallId(), entry.getName(), originalChars, callbackMaxChars);
			return new FrontendResultPayload(json(HttpStatus.BAD_REQUEST, frontendResultTooLarge(request.runId(),
					request.toolCallId(), entry, originalChars, callbackMaxChars)), null, false, originalChars, originalChars);
		}
		if (result == null || result.length() <= callbackMaxChars) {
			return new FrontendResultPayload(null, result, false, originalChars, originalChars);
		}
		String notice = "结果过长已截断：原 " + originalChars + " 字符，上限 " + callbackMaxChars
				+ " 字符；strategy=" + TruncationContract.STRATEGY_HEAD;
		String marker = "…（" + notice + "）";
		int budget = Math.max(0, callbackMaxChars - marker.length());
		int end = Math.min(budget, result.length());
		if (end > 0 && Character.isHighSurrogate(result.charAt(end - 1))) {
			end--;
		}
		result = result.substring(0, end) + marker;
		if (this.securityEvents != null) {
			this.securityEvents.payloadTruncated();
		}
		log.info("frontend tool result truncated runId={} toolCallId={} originalChars={} limit={}", request.runId(),
				request.toolCallId(), originalChars, callbackMaxChars);
		return new FrontendResultPayload(null, result, true, originalChars, result.length());
	}

	private ConfirmGate.Submission submitFrontendResult(FrontendToolResultRequest request, String result,
			boolean cancelled) {
		AiTrace.bind(request.runId(), null);
		try {
			Map<String, Object> sheetMeta = SheetAudit.sanitize(request.sheetMeta());
			String uiCode = FrontendToolResultRequest.normalizeCode(request.code());
			return this.confirmGate.submitFrontendResult(request.runId(), request.toolCallId(), result,
					request.source(), cancelled, request.outcome(), sheetMeta, uiCode);
		}
		finally {
			AiTrace.clear();
		}
	}

	private ResponseEntity<?> frontendResultRejection(FrontendToolResultRequest request,
			ConfirmGate.Submission submission) {
		if (submission.outcome() == ConfirmGate.Outcome.NOT_FOUND) {
			return json(HttpStatus.CONFLICT, unknownToolCall(request.runId(), request.toolCallId()));
		}
		if (submission.outcome() == ConfirmGate.Outcome.TOOL_KIND_MISMATCH) {
			return json(HttpStatus.CONFLICT, toolKindMismatch(request.runId(), request.toolCallId(),
					submission.pending(), PendingToolCall.KIND_FRONTEND));
		}
		if (submission.outcome() == ConfirmGate.Outcome.DUPLICATE) {
			return json(HttpStatus.CONFLICT,
					duplicateToolCall(request.runId(), request.toolCallId(), submission.pending().getStatus()));
		}
		if (submission.outcome() == ConfirmGate.Outcome.REJECTED) {
			return json(HttpStatus.BAD_REQUEST, rejectedFrontendResult(request.runId(), request.toolCallId(), submission));
		}
		return null;
	}

	private ResponseEntity<?> frontendResultResponse(FrontendToolResultRequest request,
			ConfirmGate.Submission submission, FrontendResultPayload payload, boolean cancelled) {
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
		body.put("truncated", payload.truncated());
		if (payload.truncated()) {
			body.put(TruncationContract.STRATEGY_FIELD, TruncationContract.STRATEGY_HEAD);
			body.put(TruncationContract.ORIGINAL_CHARS_FIELD, payload.originalChars());
			body.put(TruncationContract.RETURNED_CHARS_FIELD, payload.returnedChars());
			body.put(TruncationContract.NOTICE_CODE_FIELD, TruncationContract.NOTICE_CODE_FRAME_TRUNCATED);
		}
		return ResponseEntity.ok(body);
	}

	private record FrontendResultPayload(ResponseEntity<?> rejection, String result, boolean truncated,
			int originalChars, int returnedChars) {
	}

	// ── reattach / 发现 / 续跑 ──────────────────────────────────────────────

	/**
	 * reattach 的<b>无响应句柄</b>重载（测试/内部直调）：等于"不写 archiveTrimmed 头"的那条路径。
	 *
	 * <p>为什么留这个重载：既有用例直接 {@code controller.events(runId, lastSeq)} 断言状态与语义
	 * （park 收流、准备中、未知轮），加响应头不应迫使它们全部改签名。响应头是<b>纯增量</b>：
	 * 直调路径不写头，HTTP 路径（带 {@link HttpServletResponse}）写头。
	 */
	public ResponseEntity<?> events(String runId, Long lastSeq) {
		return events(runId, lastSeq, null);
	}

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
	 * <b>B23L1 批①</b>：回放源是 {@link FrameStore}（生产 = Redis Stream {@code ai:xevents:}），
	 * 差量/孤儿过滤口径不变；连接层因此无状态 —— 任何实例都能 serve 任意 run 的 reattach。
	 *
	 * <p>
	 * <b>M08 探测</b>：{@code ?probe=1} <b>不</b>走本方法 —— 它由同路径的
	 * {@link #probeEvents} 承接（本方法的 {@code params = "probe!=1"} 明确排除它）。
	 * 两个 mapping 刻意分开：正式通道只产 {@code text/event-stream}、探测只产 {@code application/json}，
	 * 于是"声明 JSON Accept 但没带 probe=1"的请求拿 <b>406</b> 而不是一条真实订阅（订阅 + 回放 + 续期）。
	 */
	@GetMapping(value = "/events/{runId}", params = "probe!=1", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public ResponseEntity<?> events(@PathVariable String runId,
			@RequestParam(required = false) Long lastSeq, HttpServletResponse response) {
		RunSnapshot snapshot = ownedRun(runId);
		ResponseEntity<?> early = prepareReattach(runId, snapshot);
		if (early != null) {
			return early;
		}
		if (this.frameStore.lastSeqIfKnown(runId).isEmpty()) {
			Map<String, Object> body = new LinkedHashMap<>(errorBody(ErrorCodes.AI_REDIS_UNAVAILABLE,
					"AI 运行时依赖的 Redis 暂不可用，请稍后重试。"));
			body.put("degraded", true);
			return json(HttpStatus.SERVICE_UNAVAILABLE, body);
		}
		SseEmitter emitter = new SseEmitter(NO_TRANSPORT_DEADLINE);
		SseChatEmitter out = this.runRegistry.of(runId);
		SseChatEmitter.ReplayResult replay = out.replayAndAttachWithStatus(emitter, lastSeq,
				snapshot == null || isTerminal(snapshot.getStatus()));
		if (log.isDebugEnabled()) {
			log.debug("reattach runId={} status={} lastSeq={} replayedFrames={} truncated={} gap={}..{}", runId,
					snapshot == null ? "none" : snapshot.getStatus(), lastSeq, replay.staged(), replay.truncated(),
					replay.trimmedFromSeq(), replay.trimmedToSeq());
		}
		handleParkedReattach(runId, snapshot, out);
		writeArchiveTrimHeaders(response, replay);
		return ResponseEntity.ok(emitter);
	}

	/** 快照未建时区分准备中/未知/仅余归档；已有快照则按原顺序续期。 */
	private ResponseEntity<?> prepareReattach(String runId, RunSnapshot snapshot) {
		if (snapshot == null) {
			return this.preparingOrUnknown(runId);
		}
		this.runStore.touchActiveRun(snapshot);
		this.confirmGate.touchSuspendedResources(runId);
		return null;
	}

	/** park 重挂只在仍等待输入时收流；已决无驱动时走既有自愈。 */
	private void handleParkedReattach(String runId, RunSnapshot snapshot, SseChatEmitter out) {
		if (snapshot == null || !this.properties.getPark().isEnabled()
				|| !RunSnapshot.SUSPENDED.equals(snapshot.getStatus())) {
			return;
		}
		boolean cancelledPending = isCancelledFlag(runId) && isWaitingForInput(runId);
		if (cancelledPending) {
			handOffCancelledPending(runId);
		}
		int expired = cancelledPending ? 0 : convergeExpiredParkedFrontend(runId);
		if (cancelledPending) {
			out.completeSubscriptions();
		}
		else if (expired > 0 && !isWaitingForInput(runId)) {
			log.info("reattach consumed {} expired frontend entries runId={} (resume dispatched, keeping connection for terminal frame)",
					expired, runId);
		}
		else if (isWaitingForInput(runId)) {
			out.completeSubscriptions();
		}
		else {
			selfHealParkedRun(runId, out);
		}
	}

	private void handOffCancelledPending(String runId) {
		if (this.cancelFinalizer == null) {
			return;
		}
		try {
			boolean handedOff = this.cancelFinalizer.handOff(runId, "CANCEL_REATTACH_PENDING");
			log.info("reattach handed off cancelled pending run runId={} handedOff={}", runId, handedOff);
		}
		catch (RuntimeException ex) {
			if (LogSampling.canAcquire("reattach-cancel-handoff")) {
				log.warn("reattach cancel finalizer hand-off failed runId={}", runId, ex);
			}
		}
	}

	private int convergeExpiredParkedFrontend(String runId) {
		try {
			return this.confirmGate.convergeExpiredParkedFrontend(runId);
		}
		catch (RuntimeException ex) {
			if (LogSampling.canAcquire("reattach-expired-converge")) {
				log.warn("reattach failed to consume expired park entries runId={} (treating as still waiting, subscription kept)",
						runId, ex);
			}
			return 0;
		}
	}

	/** 对账异常必须收流，避免尚未交给容器的 emitter 永久留存。 */
	private void selfHealParkedRun(String runId, SseChatEmitter out) {
		try {
			ConfirmGate.ResumeTrigger healed = this.confirmGate.triggerResumeForParked(runId);
			if (log.isDebugEnabled()) {
				log.debug("reattach self-heal reconcile for decided run without driver runId={} outcome={}", runId,
						healed == null ? null : healed.outcome());
			}
		}
		catch (RuntimeException ex) {
			if (LogSampling.canAcquire("reattach-self-heal")) {
				log.warn("reattach self-heal reconcile failed runId={} (closing subscription, no leak)", runId, ex);
			}
			out.completeSubscriptions();
		}
	}

	private static void writeArchiveTrimHeaders(HttpServletResponse response, SseChatEmitter.ReplayResult replay) {
		if (response != null && replay.truncated()) {
			response.setHeader(HEADER_ARCHIVE_TRIMMED, "true");
			response.setHeader(HEADER_ARCHIVE_FROM, Long.toString(replay.trimmedFromSeq()));
			response.setHeader(HEADER_ARCHIVE_TO, Long.toString(replay.trimmedToSeq()));
		}
	}

	/**
	 * <b>M08：轻量探测（{@code GET /api/ai/events/{runId}?probe=1}）</b> —— 只读状态，零连接。
	 *
	 * <p>为什么需要它：前端断流后要"先探一眼再决定重连还是判死"，而 EventSource 读不到非 200 的
	 * 响应体。此前探测直接打<b>正式</b>端点并声明 {@code Accept: text/event-stream}，于是服务端
	 * 照常建 {@link SseChatEmitter} 订阅、回放整段归档、{@code touchActiveRun} 续期 ——
	 * 一次只想知道状态的探测，产生了一次真实订阅（真机 trace：5 条 EventSource + 5 条 probe fetch）。
	 *
	 * <p><b>独立 mapping</b>（{@code params = "probe=1"}、只产 JSON）：正式通道的
	 * {@link #events} 用 {@code params = "probe!=1"} 排除探测，于是两条通道在协议层就不可互串 ——
	 * {@code probe=1 + Accept: text/event-stream} 与 {@code 无 probe + Accept: application/json}
	 * 都是 <b>406</b>，不会"顺手"进入一条真实订阅。
	 *
	 * <p>本方法因此<b>只做三件只读的事</b>：读快照、按 AR-06 复用 {@link #preparingOrUnknown}
	 * 判"准备中/不存在/降级"、读归档末号。响应形态：
	 * <ul>
	 * <li>202 {@code preparing=true}（已 start、快照未建；带 {@code elapsedMs}/{@code retryAfterMs}）；</li>
	 * <li>404 {@code UNKNOWN_RUN}（归档一帧都没有）；</li>
	 * <li>503 {@code AI_REDIS_UNAVAILABLE} + {@code degraded=true}（归档末号读不出来；快照读失败由
	 *     全局出口同样映射 503）；</li>
	 * <li>200 {@code ready=true}（快照已建，或归档已有终帧 —— 正式通道能立即 serve，前端可重挂）。</li>
	 * </ul>
	 *
	 * <p><b>不得</b>在此创建 {@link SseEmitter}、调 {@link SseChatEmitter#replayAndAttach} /
	 * {@code completeSubscriptions}、或 {@code touchActiveRun}：探测不是"有人在看"，不该延长保留期、
	 * 也不该抢占用正式连接的退避时间线。
	 */
	@GetMapping(value = "/events/{runId}", params = "probe=1", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<?> probeEvents(@PathVariable String runId) {
		return this.probe(runId);
	}

	/**
	 * 交叉产型的<b>统一 406 出口</b>：{@code /events/{runId}} 只有两种表示（正式 SSE / 探测 JSON）。
	 *
	 * <p>两条更具体的 mapping 都带产型条件，因此"只接受 JSON 但没带 {@code probe=1}""带
	 * {@code probe=1} 却只接受 {@code text/event-stream}"这类请求会把它们都排除掉；Spring 自己的
	 * no-match 路径实测回 <b>400</b>（参数不满足的判定先于产型协商），而 HTTP 语义要求
	 * <b>406</b>。本映射不带任何条件，专接这些"两种表示都不接受"的请求：<b>只</b>抛 406，
	 * 绝不建订阅、不回放、不续期。
	 *
	 * <p>正常请求不会落到这里：带 {@code params} 条件的 mapping 在比较时更具体（同一路径下优先）。
	 */
	@GetMapping(value = "/events/{runId}")
	public ResponseEntity<?> eventsNotAcceptable() throws HttpMediaTypeNotAcceptableException {
		throw new HttpMediaTypeNotAcceptableException(
				List.of(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON));
	}

	/** 探测的响应体构造（只读；见 {@link #probeEvents} 的口径）。 */
	private ResponseEntity<?> probe(String runId) {
		RunSnapshot snapshot = ownedRun(runId);
		if (snapshot == null) {
			ResponseEntity<?> early = this.preparingOrUnknown(runId);
			if (early != null) {
				return early;
			}
			// early == null ⇒ 归档里已有终帧（快照被 TTL 先回收）：正式通道会回放终态 ⇒ 探测报就绪
		}
		// 与正式通道的 503 同口径：末号读不出来时不冒充"就绪"（下一拍再探）
		FrameStore frames = this.frameStore;
		if (frames.lastSeqIfKnown(runId).isEmpty()) {
			Map<String, Object> body = new LinkedHashMap<>(errorBody(ErrorCodes.AI_REDIS_UNAVAILABLE,
					"AI 运行时依赖的 Redis 暂不可用，请稍后重试。"));
			body.put("degraded", true);
			return json(HttpStatus.SERVICE_UNAVAILABLE, body);
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ready", true);
		body.put("preparing", false);
		body.put("runId", runId);
		if (snapshot != null) {
			body.put("status", snapshot.getStatus());
		}
		body.put("degraded", false);
		return json(HttpStatus.OK, body);
	}

	/** "准备中"重挂的响应体字段：建议的重试间隔（毫秒）。 */
	private static final long PREPARING_RETRY_AFTER_MS = 1000L;

	/**
	 * <b>AR-06："已 start、快照未建"阶段的重挂判定</b>（{@code runId} 已有、快照还没建立）。
	 *
	 * <p>证据只取<b>事件归档</b>这一份跨实例事实（不新建 Redis 键族、不加状态位）：
	 * <ol>
	 * <li>归档里<b>一帧都没有</b> ⇒ 这确实是一个不存在的轮：404 {@code UNKNOWN_RUN}（既有语义）；</li>
	 * <li>归档末号<b>读不出来</b> ⇒ Redis 抖动：503 {@code AI_REDIS_UNAVAILABLE}（与快照读失败同一降级
	 *     口径，让前端下一拍重试，不冒充"没有这一轮"）；</li>
	 * <li>归档末帧是终帧（{@code done}/{@code error}）⇒ 返回 {@code null}：快照只是被 TTL 先回收了，
	 *     回放比"准备中"诚实（客户端还能拿到真正的终态）；</li>
	 * <li>其余（有 start / 有 stage 心跳、没有终帧）⇒ 202"准备中"：可重试、带<b>已用时间</b>，
	 *     <b>不</b>返回 404、<b>不</b>说"轮不存在"。</li>
	 * </ol>
	 *
	 * <p><b>与取消终态共存</b>：该窗口里收到取消时权威态已是 CANCELLED（Redis 旗标），响应体只加
	 * additive 键 {@code cancelled=true}（"没说"与"没取消"可区分），不写任何快照 —— 取消的终帧
	 * 由驱动侧的取消收尾（{@code done{cancelled:true}}）产出，重挂随后按上面的终帧分支回放它。
	 *
	 * <p><b>与僵尸判定共存</b>：本方法不判死、不回收、不派发 —— 只读归档；已用时如实回给客户端，
	 * 由前端看门狗与既有僵尸/死轮判据各自收敛（不在读侧新增定时器）。
	 *
	 * @return 需要直接返回的响应；{@code null} = 交给常规回放路径
	 */
	private ResponseEntity<?> preparingOrUnknown(String runId) {
		FrameStore frames = this.frameStore;
		OptionalLong last = frames.lastSeqIfKnown(runId);
		if (last.isEmpty()) {
			Map<String, Object> body = new LinkedHashMap<>(errorBody(ErrorCodes.AI_REDIS_UNAVAILABLE,
					"AI 运行时依赖的 Redis 暂不可用，请稍后重试。"));
			body.put("degraded", true);
			return json(HttpStatus.SERVICE_UNAVAILABLE, body);
		}
		if (last.getAsLong() <= 0L) {
			return json(HttpStatus.NOT_FOUND, Map.of("code", ErrorCodes.UNKNOWN_RUN, "runId", runId));
		}
		if (this.hasTerminalArchiveEnd(frames, runId, last.getAsLong())) {
			return null;
		}
		return preparing(runId, this.firstArchivedAtMs(frames, runId));
	}

	/**
	 * "准备中"响应体（202）。
	 *
	 * <p>为什么是 202 而不是 4xx：这不是错误 —— 这一轮正在<b>正常</b>准备（请求前压缩），
	 * 客户端该做的是"带 {@code retryAfterMs} 再挂一次"。因此不占用错误码表（不新增
	 * {@code ErrorCodes} 项），客户端按 {@code preparing=true} 判定。
	 */
	private ResponseEntity<?> preparing(String runId, long startedAtMs) {
		long elapsedMs = startedAtMs > 0L ? Math.max(0L, System.currentTimeMillis() - startedAtMs) : 0L;
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("preparing", true);
		body.put("runId", runId);
		// 已用时间（服务端钟，自归档首帧的 atMs 起算）：客户端只显示真实已用，不伪造进度
		body.put("elapsedMs", elapsedMs);
		body.put("retryAfterMs", PREPARING_RETRY_AFTER_MS);
		body.put("reattach", "/api/ai/events/" + runId);
		body.put("message", preparingMessage());
		body.put("degraded", false);
		if (isCancelledFlag(runId)) {
			body.put("cancelled", true);
		}
		if (log.isDebugEnabled()) {
			log.debug("reattach hit started-without-snapshot phase runId={} elapsedMs={}", runId, elapsedMs);
		}
		return json(HttpStatus.ACCEPTED, body);
	}

	/** "准备中"的用户面短句（双语各一份；不进 messages 键表 —— 它不是错误码）。 */
	private static String preparingMessage() {
		return ReplyLanguage.current().english()
				? "The reply is being prepared. Retry shortly."
				: "这一轮正在准备中（压缩上下文），请稍后重试。";
	}

	/** 取消旗标是否明确为 CANCELLED；读不出来按未取消处理，不阻断调用方原有路径。 */
	private boolean isCancelledFlag(String runId) {
		try {
			return this.cancellations.cancelState(runId) == CancellationRegistry.CancelState.CANCELLED;
		}
		catch (RuntimeException ex) {
			if (LogSampling.canAcquire("cancel-flag-read")) {
				log.warn("reading cancel flag failed runId={} (continuing as not cancelled)", runId, ex);
			}
			return false;
		}
	}

	/** 归档首帧的 {@code atMs}（"准备中"的已用时间零点）；读不到返回 0。 */
	private long firstArchivedAtMs(FrameStore frames, String runId) {
		try {
			List<Map<String, Object>> head = frames.readFrom(runId, 0L, 1);
			if (CollectionUtils.isEmpty(head)) {
				return 0L;
			}
			Object atMs = head.getFirst().get("atMs");
			return atMs instanceof Number number ? number.longValue() : 0L;
		}
		catch (RuntimeException ex) {
			if (LogSampling.canAcquire("archive-head-read")) {
				log.warn("reading first archived frame failed runId={} (reporting elapsedMs=0)", runId, ex);
			}
			return 0L;
		}
	}

	/**
	 * 归档末帧是否终帧（{@code done}/{@code error}）。
	 *
	 * <p>只读末<b>一</b>帧而不是全量扫描（{@code terminalFrameArchived} 会整段读）：终帧之后不得再有
	 * 业务帧是帧序不变量，所以"末帧不是终帧"就等于"没有终帧"。
	 */
	private boolean hasTerminalArchiveEnd(FrameStore frames, String runId, long lastSeq) {
		try {
			List<Map<String, Object>> tail = frames.readFrom(runId, lastSeq - 1L, 1);
			if (CollectionUtils.isEmpty(tail)) {
				return false;
			}
			String type = String.valueOf(tail.getLast().get("type"));
			return "done".equals(type) || "error".equals(type);
		}
		catch (RuntimeException ex) {
			if (LogSampling.canAcquire("archive-tail-read")) {
				log.warn("reading last archived frame failed runId={} (treating as non-terminal, still preparing)", runId, ex);
			}
			return false;
		}
	}

	/**
	 * 该轮是否仍有<b>等人/等前端</b>的条目（PENDING）。{@code true} ⇒ park 可以安全收流；
	 * {@code false} ⇒ 条目已全部结算、续跑即将/正在产出终帧，连接必须保留。
	 *
	 * <p>读失败按"仍在等"处理：状态不可知时回到旧的 park 收流（不把连接悬挂在一个说不清
	 * 状态的轮上），也不冒充"续跑在即"。
	 */
	private boolean isWaitingForInput(String runId) {
		try {
			for (PendingToolCall pending : this.runStore.pendings(runId)) {
				if (PendingToolCall.PENDING.equals(pending.getStatus())) {
					return true;
				}
			}
			return false;
		}
		catch (RuntimeException ex) {
			if (LogSampling.canAcquire("reattach-pending-read")) {
				log.warn("reattach failed to read pending entries runId={} (closing as park, no hanging connection)", runId, ex);
			}
			return true;
		}
	}

	/**
	 * 终态判定（T3-7 / A14）：委托 {@link RunSnapshot#isTerminal(String)} —— 全仓唯一判定函数
	 * （DONE / FAILED / CANCELLED，含 {@code REJECTED} 归一）。本方法保留为 controller 内的语义化别名。
	 */
	private static boolean isTerminal(String status) {
		return RunSnapshot.isTerminal(status);
	}

	/**
	 * 批G · G6：占着会话门的这一轮是否已死（判据本体见 {@link RunLiveness}）。
	 *
	 * <p>
	 * 三路输入都取"活着的证据"：续跑租约（跨实例还有人负责）、本进程写出器（活连接）、
	 * 最近活动（快照写入 + 业务活动；SUSPENDED 时含心跳）。任一为真即判活 —— 宁可让用户多等
	 * 一次 409，也不让同一会话并发两轮。RUNNING 不把心跳算进活动，避免"进程还心跳、业务已卡死"
	 * 的轮被放行成双跑（R1-09）。
	 */
	private boolean isDeadRound(String heldRunId) {
		RunSnapshot snapshot = this.runStore.get(heldRunId);
		boolean leaseHeld = false;
		try {
			LeaseService leases = this.runStore.leaseService();
			// 判死口径必须用严格读取口：isHeld 吞异常返回 false，会把"读不出来"当成"没人持有"
			// ⇒ 仍有租约的活轮被判死放行（同一会话双驱动）。异常由下面 catch 折成 fail-closed。
			leaseHeld = leases != null && leases.isHeldStrict(this.runStore.runLeaseKey(heldRunId));
		}
		catch (Exception ex) {
			// 租约设施不可读 ⇒ 按"可能有人持有"处理（不放行），与 E3 的保守方向相反是因为
			// 这里的错误方向是并发双跑，不能靠乐观兜底
			if (LogSampling.canAcquire("resume-lease-read")) {
				log.warn("reading resume lease failed runId={} (treating lease as held)", heldRunId, ex);
			}
			leaseHeld = true;
		}
		// 空写出器不算活跃：只有真实订阅才保留会话门。
		boolean liveInProcess = this.runRegistry.hasLiveSubscriber(heldRunId);
		String status = snapshot == null ? null : snapshot.getStatus();
		long updatedAtMs = snapshot == null ? 0L : snapshot.getUpdatedAtMs();
		// 两种状态统一看业务活动：RUNNING 的心跳不能养僵尸，SUSPENDED 的读侧 touch 才算活动。
		long lastActivityAtMs = this.runStore.lastActivityAtMs(heldRunId);
		long thresholdMs = this.properties.getResume().getZombieAge().toMillis();
		boolean dead = RunLiveness.isDead(status, updatedAtMs, lastActivityAtMs, leaseHeld, liveInProcess,
				thresholdMs, System.currentTimeMillis());
		if (dead) {
			this.runRegistry.recycleIfIdle(heldRunId);
		}
		return dead;
	}

	// ── 取消（韧性棒：ADR-8 修正③） ────────────────────────────────────────

	/**
	 * 取消一轮对话：{@code POST /api/ai/cancel/{runId}}。
	 *
	 * <p>
	 * 权威态写进 Redis（{@code ai:cancel:{<tenantId>:<userId>:run:<runId>}}，键形见
	 * {@link CancellationRegistry#cancelKey(String)}），因此跨实例/跨进程同样生效；
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
		RunSnapshot snapshot = ownedRun(runId);
		if (snapshot == null && !hasOwnArchive(runId)) {
			// 归属不明 ⇒ 不写任何取消事实（取消会中断那一轮的执行：跨身份写全局旗标 = DoS / 误杀他人轮次）。
			// 快照未建但归档里有本命名空间的帧（"已 start、快照未建"的准备窗口）仍允许取消：
			// 归档键同样按身份分开，能读到就证明是本身份的这一轮。
			log.warn("cancel rejected: runId has no owner or does not exist runId={} (cancel flag not written, no side effects)", runId);
			return json(HttpStatus.NOT_FOUND, Map.of("code", ErrorCodes.UNKNOWN_RUN, "runId", runId, "cancelled",
					false));
		}
		return ResponseEntity.ok(cancelKnownRun(runId, snapshot));
	}

	/**
	 * 停止闭环 additive 端点：{@code POST /api/ai/cancel/client-request}。
	 *
	 * <p>只用**会话 + clientRequestId** 精确定位：映射由 /chat 在创建 run 前写入（按身份隔离的
	 * 会话 HASH），客户端无法用它指定 runId，也不会扫描租户/run 集合。
	 * <ul>
	 * <li>映射为 {@code RUN:<runId>} ⇒ 转成取消墓碑并写该 run 的取消旗标（等同按 runId 取消，
	 *     跨实例生效；挂起轮/运行轮沿用既有收尾交棒）；</li>
	 * <li>映射不存在 ⇒ 写 {@code CANCELLED} 墓碑并 200：随后到达的 /chat 在 claim 处识别，
	 *     在压缩/建快照/模型调用/工具副作用之前收敛为已取消 —— <b>不 404、不放任后台继续跑</b>；</li>
	 * <li>已取消 ⇒ 幂等返回（{@code alreadyCancelled=true}），不重复副作用。</li>
	 * </ul>
	 */
	@PostMapping("/cancel/client-request")
	public ResponseEntity<?> cancelClientRequest(@RequestBody(required = false) ClientRequestCancelRequest request) {
		if (request == null || !StringUtils.isNotBlank(request.sessionId())
				|| !StringUtils.isNotBlank(request.clientRequestId())) {
			return json(HttpStatus.BAD_REQUEST,
					errorBody(ErrorCodes.BAD_REQUEST, "sessionId 与 clientRequestId 均为必填"));
		}
		if (!ClientRequestRegistry.isValid(request.clientRequestId())) {
			return json(HttpStatus.BAD_REQUEST,
					errorBody(ErrorCodes.BAD_REQUEST, "clientRequestId 形状非法（1..128 位，字母数字 . _ : -）"));
		}
		if (this.clientRequests == null) {
			return json(HttpStatus.SERVICE_UNAVAILABLE, redisUnavailable());
		}
		ClientRequestRegistry.CancelOutcome outcome;
		try {
			outcome = this.clientRequests.cancel(request.sessionId(), request.clientRequestId());
		}
		catch (RuntimeException ex) {
			if (LogSampling.canAcquire("cancel-client-request")) {
				log.warn("cancel by clientRequestId failed sessionId={} clientRequestId={}", request.sessionId(),
						request.clientRequestId(), ex);
			}
			return json(HttpStatus.SERVICE_UNAVAILABLE, redisUnavailable());
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("cancelled", true);
		body.put("alreadyCancelled", outcome.alreadyCancelled());
		body.put("clientRequestId", request.clientRequestId());
		body.put("sessionId", request.sessionId());
		body.put("runId", outcome.runId());
		if (outcome.runId() == null) {
			body.put("pendingRun", true);
			body.put("note", "取消墓碑已写入；若该请求随后到达，会在压缩/建快照/模型调用前直接按取消收尾");
			return ResponseEntity.ok(body);
		}
		return cancelBoundClientRequest(outcome, body);
	}

	/** runId 来自本身份映射，沿用同身份取消与合并响应语义。 */
	private ResponseEntity<?> cancelBoundClientRequest(ClientRequestRegistry.CancelOutcome outcome,
			Map<String, Object> body) {
		Map<String, Object> cancelBody;
		try {
			RunSnapshot snapshot = ownedRun(outcome.runId());
			cancelBody = cancelKnownRun(outcome.runId(), snapshot);
		}
		catch (RuntimeException ex) {
			if (LogSampling.canAcquire("cancel-bound-run")) {
				log.warn("cancel of run bound to clientRequestId failed runId={}", outcome.runId(), ex);
			}
			return json(HttpStatus.SERVICE_UNAVAILABLE, redisUnavailable());
		}
		body.putAll(cancelBody);
		body.put("cancelled", true);
		body.put("alreadyCancelled", outcome.alreadyCancelled()
				|| Boolean.TRUE.equals(cancelBody.get("alreadyCancelled")));
		return ResponseEntity.ok(body);
	}

	/**
	 * 同一 {@code clientRequestId} 重复到达时的响应体（additive 标记 + 既有 SESSION_BUSY 形态）。
	 *
	 * <p>为什么沿用 409 SESSION_BUSY：调用方已经有"带 runId 走 events/reattach"的成熟路径；
	 * {@code duplicateRequest=true} + 回显 {@code clientRequestId} 让前端只对**自己这一条重试**
	 * 自动接回，不引入普通 409 的猜测行为。
	 */
	private Map<String, Object> duplicateClientRequestBody(String sessionId, String clientRequestId,
			String runId) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("code", ErrorCodes.SESSION_BUSY);
		body.put("message", localize(ErrorCodes.SESSION_BUSY, "该会话已有一轮对话在进行中，请先处理或重挂该轮"));
		body.put("sessionId", sessionId);
		body.put("runId", runId);
		body.put("reattach", "/api/ai/events/" + runId);
		body.put("duplicateRequest", true);
		body.put("clientRequestId", clientRequestId);
		body.put("degraded", false);
		return body;
	}

	/**
	 * 按 runId 写取消事实并组装返回体（既有 /cancel 与 clientRequestId 取消共用）。
	 *
	 * <p>调用方负责归属判断；本方法只做"写旗标 + 挂起轮/运行轮收尾交棒"。快照可为 null
	 * （"已 start、快照未建"窗口）：那也照常写旗标，绝不因没有快照而让取消不可达。
	 */
	private Map<String, Object> cancelKnownRun(String runId, RunSnapshot snapshot) {
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
		// park 的挂起轮没有等待线程：取消必须触发续跑收敛，而不是只留一个取消标志。
		if (this.properties.getPark().isEnabled() && snapshot != null
				&& RunSnapshot.SUSPENDED.equals(snapshot.getStatus())) {
			ConfirmGate.ResumeTrigger parked = this.confirmGate.triggerResumeForParked(runId);
			body.put("parkedResume", parked == null ? null : parked.outcome());
			body.put("note", "取消以 Redis 标志为准；挂起中的轮已触发一次收敛派发（parkedResume）");
		}
		else {
			boolean handedOff = false;
			if (snapshot != null && !isTerminal(snapshot.getStatus())) {
				// F3：运行中（或 park 关闭的挂起态）没有进程内触发点时，取消收尾必须交棒 ——
				// 否则驱动进程一死，旗标就只是一个没人消费的事实（快照永远 RUNNING，销毁永久重试）。
				// 交棒只做取消收尾（重取租约 + 校验取消事实 + 归档终帧幂等），绝不驱动模型。
				handedOff = this.cancelFinalizer != null
						&& this.cancelFinalizer.handOff(runId, CancelledRunFinalizer.CAUSE_REQUEST);
				body.put("cancelFinalize", handedOff ? "HANDED_OFF" : "IN_PROCESS_OR_UNAVAILABLE");
			}
			body.put("note", handedOff
					? "取消以 Redis 标志为准；本实例已交棒一次取消收尾（重取租约后落 CANCELLED 终态/终帧）"
					: "取消以 Redis 标志为准；运行中的轮次会在事件边界以 done{cancelled:true} 收尾");
		}
		return body;
	}

	/** 取消标志现状（只读，取证用；<b>先核归属</b>：跨身份读是状态探测面）。 */
	@GetMapping("/cancel/{runId}")
	public ResponseEntity<?> cancelStatus(@PathVariable String runId) {
		RunSnapshot snapshot = ownedRun(runId);
		if (snapshot == null && !hasOwnArchive(runId)) {
			// 不区分"不存在"与"不是我的"：回显任何一种事实都属于跨身份状态探测。
			return json(HttpStatus.NOT_FOUND, Map.of("code", ErrorCodes.UNKNOWN_RUN, "runId", runId));
		}
		return ResponseEntity.ok(ApiResponse.ok(this.cancellations.status(runId)));
	}

	/**
	 * 归档里是否有<b>本命名空间</b>的帧（{@code ai:xevents:{<t>:<u>:run:<id>}}）。
	 *
	 * <p>只用于"快照还没建立"的准备窗口：那一轮确实属于当前身份（归档键按身份分开），
	 * 但还没有可核对的记录。归档末号读不出来（Redis 抖动）按<b>无归属</b>处理 ——
	 * 取消/状态查询这类带副作用或探测性的动作宁可不做，也不猜。
	 */
	private boolean hasOwnArchive(String runId) {
		FrameStore frames = this.frameStore;
		if (StringUtils.isBlank(runId)) {
			return false;
		}
		try {
			OptionalLong last = frames.lastSeqIfKnown(runId);
			return last.isPresent() && last.getAsLong() > 0L;
		}
		catch (RuntimeException ex) {
			if (LogSampling.canAcquire("cancel-archive-ownership")) {
				log.warn("reading archive for cancel ownership check failed runId={} (treating as unowned, cancel flag not written)",
						runId, ex);
			}
			return false;
		}
	}

	/**
	 * <b>只带 runId 的接口的归属核对</b>（设计稿「ID 与归属约束」）：runId <b>不是</b>跨租户归属证明，
	 * 因此任何只凭 runId 驱动的端点（事件/探测/取消/待决/续跑/列表单项）都必须把快照读出来、
	 * 核对记录里的 {@code tenantId/userId}，<b>不能只看"键里有这条记录"</b>。
	 *
	 * <p>键面本身已按身份分开（同 runId 不同身份落在不同键上），这里是<b>第二层</b>：
	 * 无身份记录（{@code tenantId=0/userId=0}）同样不算"我的"—— 它不得进入新身份读路径。
	 *
	 * <p>返回 {@code false} 时调用方一律按<b>不存在</b>处理（不回显"这一轮在别人手里"）。
	 *
	 * <h2>无上下文 = fail-closed</h2>
	 * 没有身份上下文时<b>不能</b>放行：无法证明这一轮属于谁，放行等于让无身份记录进入新的
	 * 读/写路径 —— 归属复核就此形同虚设。归属只能由<b>显式作用域</b>（HTTP 头 /
	 * {@link RunIdentityScope}）证明，因此这里返回 {@code false}，调用方按"不存在"处理。
	 */
	private boolean isOwnedByCurrentIdentity(RunSnapshot snapshot) {
		if (snapshot == null) {
			return false;
		}
		TenantUser current = RequestContextUtils.currentOrNull();
		if (current == null) {
			log.warn("ownership recheck rejected: no identity context on this thread runId={} (fail-closed, treated as nonexistent)",
					snapshot.getRunId());
			return false;
		}
		if (!RunIdentityScope.usable(snapshot.getTenantId(), snapshot.getUserId())) {
			return false;
		}
		return current.equals(new TenantUser(snapshot.getTenantId(), snapshot.getUserId()));
	}

	/**
	 * 只带 runId 的端点（续跑/重跑/列表单项）：快照不存在也算"可继续按原语义处理"，
	 * 只有<b>存在但不属于当前身份</b>时才拒绝。
	 */
	private boolean isOwnedOrAbsent(String runId) {
		RunSnapshot snapshot = this.runStore.get(runId);
		return snapshot == null || isOwnedByCurrentIdentity(snapshot);
	}

	/** 只带 runId 的端点：读快照并核归属；他人/无归属/不存在统一返回 {@code null}。 */
	private RunSnapshot ownedRun(String runId) {
		RunSnapshot snapshot = this.runStore.get(runId);
		if (snapshot != null && !isOwnedByCurrentIdentity(snapshot)) {
			TenantUser current = RequestContextUtils.currentOrNull();
			log.warn("cross-identity access rejected runId={} (snapshotOwner={}/{} currentIdentity={}); treated as nonexistent", runId,
					snapshot.getTenantId(), snapshot.getUserId(), current);
			return null;
		}
		return snapshot;
	}

	// ── 启动自动续跑（N3/N8） ───────────────────────────────────────────────

	/**
	 * 最近一次启动扫描的结果，<b>只含当前身份</b>的轮次（含跳过原因）；未开启开关时返回
	 * scanned=false + 原因（该分支不含任何轮次信息，与身份无关）。
	 *
	 * <p>系统面的启动扫描（无身份线程）看到的是全部租户；报告面按调用身份切片 ——
	 * 别人的 runId 既不回显、也不计入 {@code candidates}（见
	 * {@link StartupResumeRunner#lastScanForIdentity}）。缺身份在本端点不可达（身份边界先 400），
	 * runner 侧仍 fail-closed，绝不回全局事实。
	 */
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
		return ApiResponse.ok(runner.lastScanForIdentity(RequestContextUtils.currentOrNull()));
	}

	/**
	 * 手工触发一次启动口径的扫描：<b>只扫当前身份</b>的轮次（身份来自请求头，缺头在边界处 400）。
	 *
	 * <p>为什么仍然保留这个入口：自动扫描每实例只跑一次，于是"进程起来 20 秒后才发现某个轮次
	 * 超龄成僵尸"这种情形需要有人踢一脚。<b>强制重扫</b>（force）语义不变。
	 *
	 * <p>与系统面的分工：应用启动时的自动扫描不经过 HTTP，仍在无身份线程上按每轮记录身份推进；
	 * 本入口不是"全租户运维开关" —— 别人的候选既不续跑、也不进响应（不引入 ops token、
	 * 不扫描租户列表）。
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
		if (service == null || !isOwnedOrAbsent(runId)) {
			return ApiResponse.ok(Map.of("exists", false));
		}
		Map<String, Object> body = new LinkedHashMap<>(service.describe(runId));
		putArchiveRecoverability(body, runId);
		return ApiResponse.ok(body);
	}

	/**
	 * <b>归档窗口完整性</b>（批次2 / Task 4 Step 3）—— 只读，<b>绝不</b>因缺口伪造轮失败。
	 *
	 * <h2>为什么需要它</h2>
	 * 帧归档（{@code ai:xevents}）按窗口裁剪（条数 / 字节预算）。窗口一过，早于窗口的帧就没了 ——
	 * 这属于"<b>不可完整恢复</b>的归档限制"，<b>不是</b>这一轮失败了。旧读侧只有
	 * {@code RedisDegradation.c1.streamReplayGap} 计数与一行 debug 日志，人看不到；
	 * 于是"回放少了一段"很容易被误读成"轮次失败/丢失"。
	 *
	 * <h2>判据与载荷（additive）</h2>
	 * <ul>
	 * <li>{@code archiveFirstSeq}：当前可回放的最小 seq（0 = 一帧都没有）；</li>
	 * <li>{@code archiveReadable}：这次探测是否可信（读失败 ⇒ {@code false}，按"不可证明完整"处理）；</li>
	 * <li>{@code archiveGap}：有帧、但<b>首帧不是 {@code start}</b> ⇒ 归档头部已被裁剪；</li>
	 * <li>{@code recoverable}：{@code "complete"} / {@code "partial"}（前端文案："不可完整恢复"）。</li>
	 * </ul>
	 *
	 * <p><b>本方法不写任何状态</b>（不改快照、不写 FAILED、不发终帧）：缺口只影响"回放是否完整"，
	 * 与"这一轮成没成"是两件事。
	 */
	private void putArchiveRecoverability(Map<String, Object> body, String runId) {
		boolean readable = true;
		boolean gap = false;
		long firstSeq = 0L;
		if (!Boolean.TRUE.equals(body.get("exists"))) {
			return;
		}
		try {
			FrameStore.ReadResult read = this.frameStore.readFromWithStatus(runId, 0L, 1);
			readable = !read.degraded();
			List<Map<String, Object>> frames = read.frames();
			if (CollectionUtils.isNotEmpty(frames)) {
				Map<String, Object> first = frames.getFirst();
				if (first.get("seq") instanceof Number number) {
					firstSeq = number.longValue();
				}
			}
			// <b>权威位 = 后端读归档时是否真跳过了不可回放的区间</b>（Task 2 / I-3）。
			// 旧口径用"首帧不是 start"猜测：跳号（降级重试 / 遗留墙钟 ID）会让它误报，
			// 而窗口按条数裁（不是按号裁）时它又会漏报 —— 猜出来的缺口不能当权威。
			gap = read.truncated();
		}
		catch (RuntimeException ex) {
			readable = false;
			if (LogSampling.canAcquire("archive-integrity-probe")) {
				log.warn("archive integrity probe failed runId={} (treated as not provably complete)", runId, ex);
			}
		}
		body.put("archiveFirstSeq", firstSeq);
		body.put("archiveReadable", readable);
		body.put("archiveTrimmed", gap);
		body.put("archiveGap", gap);
		body.put("recoverable", readable && !gap ? "complete" : "partial");
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
	 *
	 * <p>
	 * <b>AR-10（批次3 补齐，additive）</b>：另透出 {@code snapshotRemainingMs} /
	 * {@code snapshotExpiresAt} —— 由 {@link RunStore#runKeyTtlRemainingMs(String)} 对
	 * {@code ai:run:<runId>} 做 <b>只读</b> {@code PTTL} 推导。这是"卡面剩余时间 / 已到期"的
	 * 唯一权威来源：<b>不改 TTL、不续期、不新增持久化</b>，前端也不必用 session-ttl 常量自算另一套。
	 * 未知（键刚过期 / 读失败 / 无 TTL）时为 {@code null}，前端按"不显示时间"处理。
	 */
	@GetMapping("/runs/current")
	public ResponseEntity<?> runsCurrent(@RequestParam(required = false) String sessionId) {
		if (!StringUtils.isNotBlank(sessionId)) {
			return json(HttpStatus.BAD_REQUEST, errorBody(ErrorCodes.BAD_REQUEST, "sessionId 为必填"));
		}
		String runId = this.runStore.sessionCurrentRunId(sessionId);
		RunSnapshot snapshot = runId == null ? null : this.runStore.get(runId);
		if (runId == null || snapshot == null) {
			return ResponseEntity.ok(ApiResponse.ok(emptyCurrentRun()));
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("found", true);
		body.put("runId", runId);
		body.put("snapshotStatus", snapshot.getStatus());
		this.runStore.touchActiveRun(snapshot);
		this.confirmGate.touchSuspendedResources(runId);
		Long snapshotRemainingMs = this.runStore.runKeyTtlRemainingMs(runId);
		Long snapshotExpiresAt = snapshotRemainingMs == null ? null
				: Long.valueOf(System.currentTimeMillis() + snapshotRemainingMs);
		body.put("snapshotRemainingMs", snapshotRemainingMs);
		body.put("snapshotExpiresAt", snapshotExpiresAt);
		PendingEntriesProjection projection = pendingEntriesProjection(runId, snapshot);
		body.put("pendingEntries", projection.entries());
		body.put("unresolvedExternal", projection.unresolvedExternal());
		body.put("awaitingExternal",
				RunSnapshot.SUSPENDED.equals(snapshot.getStatus()) && !projection.unresolvedExternal().isEmpty());
		return currentRunArchiveResponse(runId, body);
	}

	private PendingEntriesProjection pendingEntriesProjection(String runId, RunSnapshot snapshot) {
		List<PendingToolCall> pendings = this.runStore.pendingsLenient(runId);
		Map<String, Integer> batchIndexes = batchIndexes(snapshot.getInFlightToolCalls());
		List<Map<String, Object>> entries = new ArrayList<>();
		List<String> unresolvedExternal = new ArrayList<>();
		for (PendingToolCall pending : pendings) {
			entries.add(pendingEntry(pending, batchIndexes));
			if (PendingToolCall.PENDING.equals(pending.getStatus())
					&& !PendingToolCall.KIND_BACKEND.equals(pending.getKind())) {
				unresolvedExternal.add(pending.getToolCallId());
			}
		}
		return new PendingEntriesProjection(entries.isEmpty() ? List.of() : entries,
				unresolvedExternal.isEmpty() ? List.of() : unresolvedExternal);
	}

	private static Map<String, Integer> batchIndexes(List<PendingToolCall> batchOrder) {
		Map<String, Integer> batchIndexes = new LinkedHashMap<>();
		for (int i = 0; batchOrder != null && i < batchOrder.size(); i++) {
			PendingToolCall pending = batchOrder.get(i);
			if (pending != null && pending.getToolCallId() != null) {
				batchIndexes.putIfAbsent(pending.getToolCallId(), i);
			}
		}
		return batchIndexes.isEmpty() ? Map.of() : batchIndexes;
	}

	/** req/expiresAt/preflight 等键的形状与空值语义均按恢复协议原样透出。 */
	private static Map<String, Object> pendingEntry(PendingToolCall pending, Map<String, Integer> batchIndexes) {
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("toolCallId", pending.getToolCallId());
		Integer batchIndex = batchIndexes.get(pending.getToolCallId());
		if (batchIndex != null) {
			entry.put("batchIndex", batchIndex);
		}
		entry.put("entryStatus", pending.getStatus());
		entry.put("outcome", pending.effectiveOutcome().wire());
		entry.put("executed", pending.isExecuted());
		if (pending.getExecutedBy() != null) {
			entry.put("executedBy", pending.getExecutedBy());
		}
		entry.put("claimedAtMs", pending.getClaimedAtMs() > 0L ? pending.getClaimedAtMs() : null);
		entry.put("claimedEpoch", pending.getClaimedEpoch() > 0L ? pending.getClaimedEpoch() : null);
		entry.put("kind", pending.getKind());
		entry.put("name", pending.getName());
		entry.put("arguments", pending.getArguments());
		entry.put("req", Boolean.TRUE.equals(pending.getReq()));
		entry.put("reqAtMs", pending.getReqAtMs() > 0L ? pending.getReqAtMs() : null);
		entry.put("expiresAt", pending.getExpiresAt());
		entry.put("atMs", pending.getAtMs());
		entry.put("preflight", pending.getPreflight());
		entry.put("irreversible", pending.isIrreversible());
		return entry;
	}

	private ResponseEntity<?> currentRunArchiveResponse(String runId, Map<String, Object> body) {
		Long maxSeq = archiveMaxSeq(runId);
		if (maxSeq == null) {
			Map<String, Object> error = new LinkedHashMap<>(errorBody(ErrorCodes.AI_REDIS_UNAVAILABLE,
					"AI 运行时依赖的 Redis 暂不可用，请稍后重试。"));
			error.put("degraded", true);
			return json(HttpStatus.SERVICE_UNAVAILABLE, error);
		}
		body.put("archiveMaxSeq", maxSeq);
		return ResponseEntity.ok(ApiResponse.ok(body));
	}

	private record PendingEntriesProjection(List<Map<String, Object>> entries, List<String> unresolvedExternal) {
	}

	/**
	 * 该轮事件日志的已落档末号（前端 {@code restoreSuspendedSnapshot} 拿它当 {@code lastSeq}，
	 * 只收增量、禁全量回放）。
	 *
	 * <p>
	 * B23L1 批①：事件日志真值在 Stream（{@code ai:xevents:}）—— 末号必须问事件日志
	 * （{@link FrameStore#lastSeqIfKnown}；读失败返回 empty 而不是 0），不能另读第二套归档
	 * （前端会把 0 当全量回放锚点）。
	 */
	private Long archiveMaxSeq(String runId) {
		OptionalLong known = this.frameStore.lastSeqIfKnown(runId);
		return known.isPresent() ? known.getAsLong() : null;
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
		// AR-10（additive）：空态没有运行态可言 ⇒ 寿命键恒为 null（"未知"，不是 0）
		body.put("snapshotRemainingMs", null);
		body.put("snapshotExpiresAt", null);
		return body;
	}

	@PostMapping(value = "/runs/{runId}/resume", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<?> resume(@PathVariable String runId) {
		if (!isOwnedOrAbsent(runId)) {
			return json(HttpStatus.NOT_FOUND, errorBody(ErrorCodes.UNKNOWN_RUN, "轮次不存在"));
		}
		ResumeService service = this.resumeService.getIfAvailable();
		if (!this.availability.isAvailable() || service == null) {
			return json(HttpStatus.SERVICE_UNAVAILABLE, unavailable());
		}
		// R4：续跑同样必须"快速失败"（它读快照/台账/待决条目，全是 Redis 操作）
		if (!this.redisAvailability.isAvailable()) {
			return json(HttpStatus.SERVICE_UNAVAILABLE, redisUnavailable());
		}
		ResumeService.ResumeResult result = service.resume(runId, ErrorBodyFactory.requestLocale());
		return switch (result.outcome()) {
			case ACCEPTED -> ResponseEntity.accepted().contentType(MediaType.APPLICATION_JSON).body(result.body());
			case ALREADY_DONE, ALREADY_CANCELLED ->
				ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(result.body());
			case NOT_FOUND -> json(HttpStatus.NOT_FOUND, result.body());
			case POOL_SATURATED, CANCEL_STATE_UNKNOWN, STATE_UNKNOWN ->
				json(HttpStatus.SERVICE_UNAVAILABLE, result.body());
			// 409 家族（B23L1 批②b 显式列出）：续跑权在别的实例手里 = 这一轮已经有主，
			// 不是"服务不可用"（不该 503 让运维误以为要扩容），也不是"没找到"。
			case LEASE_HELD_ELSEWHERE, IN_PROGRESS, ALREADY_RUNNING -> json(HttpStatus.CONFLICT, result.body());
			// 批③：快照的 provider 已删/停用/密钥不可解 —— 不静默换模型续跑（历史与 done.model 不许说谎），
			// 也不 404（轮次存在）：409 + 稳定码 PROVIDER_GONE，前端据此引导"重新发一轮"。
			case PROVIDER_GONE -> json(HttpStatus.CONFLICT, result.body());
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
	 * 错误体 {@code {code, message}}（S6 尾项）：响应形态不变，只有 {@code message} 走 locale ——
	 * 中文（默认）取调用方原文，非中文取 {@code messages_en} 的 {@code error.<CODE>}，缺键回落原文。
	 */
	private Map<String, Object> errorBody(String code, String fallbackMessage) {
		return Map.of("code", code, "message", localize(code, fallbackMessage));
	}

	private String localize(String code, String fallbackMessage) {
		return this.errorBodyFactory == null ? fallbackMessage
				: this.errorBodyFactory.message(code, fallbackMessage, null, ErrorBodyFactory.requestLocale());
	}

	/** 410：已销毁会话继续以同 id 开新轮（复审 #3）。明确提示换新 sessionId，不静默改 id。 */
	private Map<String, Object> sessionPurged(String sessionId) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("code", ErrorCodes.SESSION_PURGED);
		body.put("message", localize(ErrorCodes.SESSION_PURGED,
				"该会话已被销毁，不能继续以同一 sessionId 对话；请新建对话（使用新的 sessionId）后重试"));
		body.put("sessionId", sessionId);
		body.put("action", "NEW_SESSION");
		body.put("note", "会话销毁后旧 sessionId 不可复用；前端「新建对话」会生成新 id。");
		return body;
	}

	/**
	 * 请求被<b>拒绝</b>时的结构化出口（AI 日志最小补齐批）：{@code event=turn_denied}。
	 *
	 * <p>为什么必须有：{@code AiController} 里所有拒绝都发生在 {@link AiTrace#bind} 之前 ——
	 * 既没有 {@code turn_accepted}，也不会有 {@code turn_end}。用户说"点了没反应"时，日志里
	 * 一行可 grep 的痕迹都没有，只能去翻 HTTP 访问日志（而它可能根本不在同一台机器上）。
	 *
	 * <p>锚点口径：{@code sessionId} 能取到就带上；{@code runId} 只有两种情况有 ——
	 * 409 会话忙（<b>占着会话的那一轮</b>的 runId，这才是要看的那条链）与"受理之后才被拒"的路径；
	 * 其余渲染 {@code -}（"这次请求还没有轮次"本身就是信息）。
	 *
	 * <p>字段只记元数据：{@code status}/{@code code} + 各拒绝点自己的少量数字/枚举（长度、上限、模型名）。
	 *
	 * @param status HTTP 状态（同时进事件字段，便于按 4xx/5xx 切片）
	 * @param code 稳定机器码（与响应体同源，不另造一套）
	 * @param sessionId 会话 id（可空）
	 * @param runId 相关轮次（可空；只有 409 与"受理后拒绝"才有）
	 * @param extra 追加的 {@code k,v} 对
	 */
	private void deny(HttpStatus status, String code, String sessionId, String runId, Object... extra) {
		Object[] fields = ArrayUtils.addAll(new Object[] { "status", status.value(), "code", code }, extra);
		AiTrace.eventOn(runId, sessionId, "turn_denied", fields);
	}

	/**
	 * 400 {@code MESSAGE_TOO_LONG}（批① 闸门 2）。稳定码 + 两枚数字 ——
	 * <b>不回显原文</b>（它可能本身就是敏感数据，且长度已足够定位问题）。
	 *
	 * <p>A3-Y7 / I-1：message 走 {@code localize()} —— 中文（默认）逐字不变，非中文取
	 * {@code messages_en} 的码级兜底；{@code length}/{@code limit} 两枚数字仍在结构字段里，
	 * 本地化不吞掉可定位信息。
	 */
	private Map<String, Object> messageTooLong(int length, int limit) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("code", ErrorCodes.MESSAGE_TOO_LONG);
		body.put("message", localize(ErrorCodes.MESSAGE_TOO_LONG,
				"消息长度 " + length + " 字符，超过单条上限 " + limit + " 字符；请拆分或缩短后重发（本接口不做截断）"));
		body.put("length", length);
		body.put("limit", limit);
		return body;
	}

	/**
	 * 400 {@code REASON_TOO_LONG}（防护批③ B2-6）。稳定码 + 两枚数字，<b>不回显原因原文</b>。
	 *
	 * <p>I-1：该码已登记进 {@link ErrorCodes}（A10 R-10），故与其他拒绝出口共用同一本地化机制。
	 */
	private Map<String, Object> reasonTooLong(int length) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("code", ErrorCodes.REASON_TOO_LONG);
		body.put("message", localize(ErrorCodes.REASON_TOO_LONG,
				"拒绝原因 " + length + " 字符，超过上限 " + REASON_MAX_CHARS + " 字符；请缩短后重新提交决策"));
		body.put("length", length);
		body.put("limit", REASON_MAX_CHARS);
		return body;
	}

	/**
	 * 用户展示历史（transcript 是唯一读源；<b>不</b>回读模型记忆窗口）。
	 *
	 * <p>默认读取最近一页；{@code before} 以本页最早条目的稳定顺序号向更早翻页。分页只读取本页 HASH 条目，
	 * 响应带 {@code revision} 与可选 {@code nextCursor}。{@code chat:mem} 只服务模型上下文，
	 * 压缩/窗口改写不会污染这里的原文。
	 */
	@GetMapping("/history/{sessionId}")
	public ResponseEntity<?> history(@PathVariable String sessionId,
			@RequestParam(name = "latest", required = false) Integer latest,
			@RequestParam(name = "before", required = false) Long before) {
		if (this.transcript == null) {
			log.warn("history transcript store not wired sessionId={}", sessionId);
			return json(HttpStatus.SERVICE_UNAVAILABLE, redisUnavailable());
		}
		ConversationTranscriptStore.ReadPage transcriptPage;
		try {
			transcriptPage = this.transcript.readPage(sessionId, before, latest);
		}
		catch (RuntimeException ex) {
			if (LogSampling.canAcquire("history-transcript-read")) {
				log.warn("history transcript read failed sessionId={}", sessionId, ex);
			}
			return json(HttpStatus.SERVICE_UNAVAILABLE, redisUnavailable());
		}
		List<Map<String, Object>> page = transcriptItems(projectableTranscriptEntries(transcriptPage.entries()));
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("sessionId", sessionId);
		body.put("count", page.size());
		body.put("messages", page);
		body.put("total", transcriptPage.total());
		body.put("hasMore", transcriptPage.hasMore());
		body.put("revision", transcriptPage.revision());
		if (transcriptPage.nextCursor() != null) {
			body.put("nextCursor", transcriptPage.nextCursor());
		}
		if (transcriptPage.truncated()) {
			body.put("truncated", true);
		}
		// B3：已提交压缩事实（按 atMs,eventId 升序）；读失败只降级为空事件，不拖垮历史正文。
		body.put("events", historyCompactionEvents(sessionId));
		return ResponseEntity.ok(ApiResponse.ok(body));
	}

	private List<Map<String, Object>> historyCompactionEvents(String sessionId) {
		if (this.contextStateStore == null) {
			return List.of();
		}
		List<Map<String, Object>> raw;
		try {
			raw = this.contextStateStore.events(sessionId);
		}
		catch (RuntimeException ex) {
			if (LogSampling.canAcquire("history-compaction-read")) {
				log.warn("history compaction facts read failed sessionId={} (returning empty events)", sessionId, ex);
			}
			return List.of();
		}
		if (CollectionUtils.isEmpty(raw)) {
			return List.of();
		}
		List<Map<String, Object>> sorted = new ArrayList<>(raw);
		sorted.sort((left, right) -> {
			long leftAt = longOf(left.get("atMs"));
			long rightAt = longOf(right.get("atMs"));
			int byAt = Long.compare(leftAt, rightAt);
			return byAt != 0 ? byAt : String.valueOf(left.get("eventId"))
				.compareTo(String.valueOf(right.get("eventId")));
		});
		List<Map<String, Object>> out = new ArrayList<>(sorted.size());
		for (Map<String, Object> event : sorted) {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("eventId", event.get("eventId"));
			item.put("runId", event.get("runId"));
			item.put("atMs", event.get("atMs"));
			item.put("anchorToolCallId", event.get("anchorToolCallId"));
			out.add(item);
		}
		return out;
	}

	private static long longOf(Object value) {
		if (value instanceof Number number) {
			return number.longValue();
		}
		if (value instanceof String text) {
			return NumberUtils.toLong(StringUtils.trim(text), 0L);
		}
		return 0L;
	}

	/**
	 * B2：transcript 条目 → 既有 history 载荷形状。
	 *
	 * <p>只做形状映射，不做排序/去重（存储读取时已完成 last-wins + turnStartedAtMs 排序）。
	 * 工具结果仍走 {@link #historyToolResponses} 同一份用户面投影（信封解包 / 文案码 / resultView），
	 * 因此展示历史的卡面语义与实时帧同源。
	 */
	private List<Map<String, Object>> transcriptItems(List<TranscriptEntry> entries) {
		List<Map<String, Object>> items = new ArrayList<>(entries.size());
		for (TranscriptEntry entry : entries) {
			Map<String, Object> item = transcriptItem(entry);
			if (MapUtils.isNotEmpty(item)) {
				items.add(item);
			}
		}
		return items.isEmpty() ? List.of() : items;
	}

	/** 只保留能投影成 USER/ASSISTANT/TOOL 的条目（计数与切片都只用它，避免全量建 map）。 */
	private static List<TranscriptEntry> projectableTranscriptEntries(
			List<TranscriptEntry> entries) {
		List<TranscriptEntry> out = new ArrayList<>(entries.size());
		for (TranscriptEntry entry : entries) {
			if (transcriptType(entry) != null) {
				out.add(entry);
			}
		}
		return out.isEmpty() ? List.of() : out;
	}

	private static String transcriptType(TranscriptEntry entry) {
		if (StringUtils.isBlank(entry.kind())) {
			return null;
		}
		String type = entry.kind().toUpperCase();
		return "USER".equals(type) || "ASSISTANT".equals(type) || "TOOL".equals(type) ? type : null;
	}

	private Map<String, Object> transcriptItem(TranscriptEntry entry) {
		String type = transcriptType(entry);
		if (type == null) {
			return Map.of();
		}
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("type", type);
		item.put("text", StringUtils.defaultString(entry.text()));
		if (entry.runId() != null) {
			item.put("runId", entry.runId());
		}
		if (entry.atMs() > 0L) {
			item.put("timestamp", entry.atMs());
		}
		if ("ASSISTANT".equals(type)) {
			addAssistantTranscriptFacts(item, entry);
		}
		if ("TOOL".equals(type)) {
			item.put("toolResponses", List.of(transcriptToolResponse(entry)));
		}
		return item;
	}

	private void addAssistantTranscriptFacts(Map<String, Object> item, TranscriptEntry entry) {
		addAssistantErrorFacts(item, entry);
		if (CollectionUtils.isNotEmpty(entry.toolCalls())) {
			item.put("toolCalls", transcriptToolCalls(entry.toolCalls()));
		}
		if (MapUtils.isNotEmpty(entry.toolCallStates())) {
			item.put("toolCallStates", entry.toolCallStates());
		}
		if (CollectionUtils.isNotEmpty(entry.segments())) {
			item.put("segments", transcriptSegments(entry.segments()));
		}
		addAssistantStateFacts(item, entry);
	}

	private static void addAssistantErrorFacts(Map<String, Object> item, TranscriptEntry entry) {
		if (StringUtils.isNotBlank(entry.errorCode())) {
			item.put(TranscriptEntries.ERROR_CODE_KEY, entry.errorCode());
		}
		if (StringUtils.isNotBlank(entry.errorMessage())) {
			item.put(TranscriptEntries.ERROR_MESSAGE_KEY, entry.errorMessage());
		}
		if (StringUtils.isNotBlank(entry.outcome())) {
			item.put("outcome", entry.outcome());
		}
		if (StringUtils.isNotBlank(entry.errorCardKey())) {
			item.put("errorCardKey", entry.errorCardKey());
		}
		if (Boolean.TRUE.equals(entry.unconfirmed())) {
			item.put("unconfirmed", true);
		}
	}

	private static List<Map<String, Object>> transcriptToolCalls(List<TranscriptEntry.ToolCallView> toolCalls) {
		List<Map<String, Object>> calls = new ArrayList<>(toolCalls.size());
		for (TranscriptEntry.ToolCallView call : toolCalls) {
			Map<String, Object> view = new LinkedHashMap<>();
			view.put("id", call.id());
			view.put("type", call.type());
			view.put("name", call.name());
			view.put("arguments", call.arguments());
			calls.add(view);
		}
		return calls.isEmpty() ? List.of() : calls;
	}

	private List<Map<String, Object>> transcriptSegments(List<TranscriptEntry.SegmentView> entrySegments) {
		List<Map<String, Object>> segments = new ArrayList<>(entrySegments.size());
		for (TranscriptEntry.SegmentView segment : entrySegments) {
			if (!this.properties.getReasoning().isDisplayEnabled() && "reasoning".equals(segment.kind())) {
				continue;
			}
			Map<String, Object> view = new LinkedHashMap<>();
			view.put("kind", segment.kind());
			view.put("text", segment.text());
			view.put("toolCallId", segment.toolCallId());
			view.put("seq", segment.seq());
			segments.add(view);
		}
		return segments.isEmpty() ? List.of() : segments;
	}

	private void addAssistantStateFacts(Map<String, Object> item, TranscriptEntry entry) {
		if (this.properties.getReasoning().isDisplayEnabled() && StringUtils.isNotBlank(entry.reasoning())) {
			item.put(ResilientChatService.REASONING_METADATA_KEY, entry.reasoning());
		}
		if (Boolean.TRUE.equals(entry.stopped())) {
			item.put("stopped", true);
		}
		if (Boolean.TRUE.equals(entry.truncated())) {
			item.put(ResilientChatService.TRUNCATED_KEY, true);
		}
	}

	private Map<String, Object> transcriptToolResponse(TranscriptEntry entry) {
		ToolResponseMessage single = ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse(entry.toolCallId(), entry.name(),
					StringUtils.defaultString(entry.result()))))
			.build();
		List<Map<String, Object>> responses = historyToolResponses(single);
		Map<String, Object> item = !responses.isEmpty() ? responses.getFirst() : new LinkedHashMap<>();
		if (item.isEmpty()) {
			item.put("id", entry.toolCallId());
			item.put("name", entry.name());
			item.put("responseData", entry.result());
		}
		// P2：transcript 只存用户面原文，信封里的 code 与截断事实在写入时另存；
		// 读取时按它们还原 userTextCode / 截断五字段 —— 与 chat:mem 的信封路径同形。
		if (StringUtils.isNotBlank(entry.code())) {
			item.put("responseData", ToolResultUserText.forUser(entry.code(),
					item.get("responseData") == null ? null : String.valueOf(item.get("responseData"))));
			item.put("userTextCode", ToolResultUserText.userTextCode(entry.code()));
		}
		if (Boolean.TRUE.equals(entry.toolTruncated())) {
			item.put(TruncationContract.TRUNCATED_FIELD, true);
			item.put(TruncationContract.NOTICE_CODE_FIELD,
					TruncationContract.NOTICE_CODE_MODEL_VIEW_TRUNCATED);
			if (entry.toolStrategy() != null) {
				item.put(TruncationContract.STRATEGY_FIELD, entry.toolStrategy());
			}
			if (entry.toolOriginalChars() != null) {
				item.put(TruncationContract.ORIGINAL_CHARS_FIELD, entry.toolOriginalChars());
			}
			if (entry.toolReturnedChars() != null) {
				item.put(TruncationContract.RETURNED_CHARS_FIELD, entry.toolReturnedChars());
			}
		}
		return item;
	}

	/**
	 * 历史通道的工具结果（P0-1 信封解包 + N9 用户面摘要）。
	 *
	 * <p>模型记忆里 {@code role:tool} 继续带信封 JSON（续跑与首轮同形），但 {@code /history}
	 * 是<b>回显给人/前端</b>的通道 —— 前端按 {@code responseData} 渲染工具结果原文，
	 * 直接透传信封会让卡片里出现一整段 {@code {"ok":...}}。因此先剥壳取 {@code message}，
	 * 再按 {@code code} 换成用户摘要（预算/步数/上游失败/资源锁/错入口/关闸/越 scope/超时/
	 * 他实例认领）；{@code outcome}/{@code code} 仍由 {@code toolCallStates} 原样回放。
	 * 命中摘要时以同名 {@code userTextCode} 透出原码供前端本地化；非信封结果原样透传，
	 * 模型侧信封原文不受影响（记忆与台账里仍是可排障的文本）。
	 */
	private List<Map<String, Object>> historyToolResponses(ToolResponseMessage message) {
		List<Map<String, Object>> out = new ArrayList<>(message.getResponses().size());
		for (ToolResponseMessage.ToolResponse response : message.getResponses()) {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("id", response.id());
			item.put("name", response.name());
			String code = envelopeCode(response.responseData());
			item.put("responseData", ToolResultUserText.forUser(code,
					historyMessageText(response.responseData())));
			item.put("userTextCode", ToolResultUserText.userTextCode(code));
			// A10 R-15：模型侧视图截断是**持久事实**（信封五字段），历史通道必须原样透出，
			// 否则刷新后截断角标与 locale hover 消失（帧只活在实时流里）。
			Map<String, Object> truncation = historyTruncation(response.responseData());
			if (MapUtils.isNotEmpty(truncation)) {
				item.putAll(truncation);
			}
			Map<String, Object> view = historyResultView(response);
			if (MapUtils.isNotEmpty(view)) {
				item.put("resultView", view);
			}
			out.add(item);
		}
		return out.isEmpty() ? List.of() : out;
	}

	/** 信封里的机器可读码（非信封/无码为 null）；只用于选择用户摘要，不改写原文。 */
	private String envelopeCode(String responseData) {
		String json = ToolResultEnvelope.unwrapModelJson(responseData);
		if (!StringUtils.startsWith(StringUtils.trim(json), "{")) {
			return null;
		}
		try {
			JsonNode node = this.objectMapper.readTree(json);
			if (node == null || !node.isObject() || !isToolResultEnvelope(node)) {
				return null;
			}
			JsonNode code = node.get("code");
			return code == null || code.isNull() ? null : code.asText();
		}
		catch (Exception ex) {
			// 展示增强不得让 /history 500：解析失败按"无码"处理，原文照旧
			if (LogSampling.canAcquire("history-envelope-code-parse")) {
				log.warn("history tool result envelope code parse failed; passing through without code", ex);
			}
			return null;
		}
	}

	/**
	 * 历史通道的截断元数据（A10 R-15）：从持久化的工具结果信封里取回
	 * {@code truncated/strategy/originalChars/returnedChars}，并映射成前端段字段
	 * {@code noticeCode=model_view_truncated}。
	 *
	 * <p>为什么读信封而不是另存一份：模型侧裁剪就发生在
	 * {@code ToolResultEnvelope#toModelJson}，信封是这份事实的唯一持久来源；实时帧的截断字段
	 * 也由同一个 limiter 现算（{@code SseChatEmitter#markModelView}），两侧因此同源。
	 *
	 * <p>只对 {@code truncated=true} 出键：未截断/无信封保持载荷形状逐字不变。
	 * 信封可能带模型侧定界外壳（{@code <tool_result>}），先剥壳再解析。
	 */
	private Map<String, Object> historyTruncation(String responseData) {
		if (StringUtils.isBlank(responseData)) {
			return Map.of();
		}
		try {
			String json = ToolResultEnvelope.unwrapModelJson(responseData);
			if (!StringUtils.startsWith(StringUtils.trim(json), "{")) {
				return Map.of();
			}
			JsonNode node = this.objectMapper.readTree(json);
			if (node == null || !node.isObject() || !isToolResultEnvelope(node)
					|| !node.path(TruncationContract.TRUNCATED_FIELD).asBoolean(false)) {
				return Map.of();
			}
			Map<String, Object> fields = new LinkedHashMap<>();
			fields.put(TruncationContract.TRUNCATED_FIELD, true);
			fields.put(TruncationContract.NOTICE_CODE_FIELD, TruncationContract.NOTICE_CODE_MODEL_VIEW_TRUNCATED);
			JsonNode strategy = node.get(TruncationContract.STRATEGY_FIELD);
			if (strategy != null && strategy.isTextual()) {
				fields.put(TruncationContract.STRATEGY_FIELD, strategy.asText());
			}
			for (String key : List.of(TruncationContract.ORIGINAL_CHARS_FIELD,
					TruncationContract.RETURNED_CHARS_FIELD)) {
				JsonNode value = node.get(key);
				if (value != null && value.isNumber()) {
					fields.put(key, value.asInt());
				}
			}
			return fields;
		}
		catch (Exception ex) {
			// 展示增强不得让 /history 500：解析失败按"无截断元数据"处理
			if (LogSampling.canAcquire("history-truncation-parse")) {
				log.warn("history tool result truncation metadata parse failed; passing through without fields", ex);
			}
			return Map.of();
		}
	}

	/**
	 * L-78：历史面的结果视图（按工具名从注册表重新派生；未声明投影的工具不产该键）。
	 *
	 * <p>三条口径与帧路径（`SpToolCallingManager#resultViewOf`）逐条对齐：
	 * ① 只对**成功结局**出视图 —— 失败/取消的卡面不产视图（"视图是成功结果的投影"）；
	 * ② 认领按工具名，投影器异常一律吞掉（展示增强不得让 {@code /history} 500）；
	 * ③ 返回空 Map 时不写键。
	 *
	 * <p><b>与帧路径唯一的差别</b>（Task 6 / BZ-33）：这里显式标 {@code historical=true}。
	 * 归档是"重放"，不允许再查一次业务库 —— 否则刷新后卡面显示的是今天的任务/数据，
	 * 与同轮 assistant 文本和历史"共 N 条"口径自相矛盾（旧实现每次 {@code /history} 都为每条历史
	 * {@code list_tasks} 重跑一次 {@code taskService.findAll()}）。
	 */
	private Map<String, Object> historyResultView(ToolResponseMessage.ToolResponse response) {
		if (this.resultViewProviders.isEmpty() || response.name() == null || response.id() == null) {
			return Map.of();
		}
		if (!isHistoryOutcomeOk(response.responseData())) {
			return Map.of();
		}
		String resultText = historyMessageText(response.responseData());
		for (ToolResultViewProvider provider : this.resultViewProviders) {
			if (!provider.supports(response.name())) {
				continue;
			}
			try {
				// Task 6 / BZ-33：历史是"重放"，不是"重新查库"。投影器据此改用那次调用的结果原文，
				// 不得用当前业务库改写归档卡面（旧实现每次 /history 都重跑 taskService.findAll()）。
				ResultView view = provider.build(response.id(),
						response.name(), resultText, true);
				return view == null ? Map.of() : view.toMap();
			}
			catch (RuntimeException ex) {
				if (LogSampling.canAcquire("history-result-view-build")) {
					log.warn("history resultView build failed (no view this time, card shape without view) tool={} toolCallId={}",
							response.name(), response.id(), ex);
				}
				return Map.of();
			}
		}
		return Map.of();
	}

	/**
	 * 历史响应的结局是否成功：信封带 {@code ok=false} ⇒ 否；非信封 / 无该键 ⇒ 仅视为"无失败证据"。
	 * 本判据只决定是否产出 resultView，<b>不代表</b>工具结局为成功（工具结局只认 outcome）。
	 */
	private boolean isHistoryOutcomeOk(String responseData) {
		String json = ToolResultEnvelope.unwrapModelJson(responseData);
		if (!StringUtils.startsWith(StringUtils.trim(json), "{")) {
			return true;
		}
		try {
			JsonNode node = this.objectMapper.readTree(json);
			return node == null || !node.isObject() || node.path("ok").asBoolean(true);
		}
		catch (Exception ex) {
			return true;
		}
	}

	/**
	 * 历史工具结果的<b>用户面原文</b>（L-70 收口）：先剥模型侧定界外壳，再解信封取 {@code message}。
	 *
	 * <p>生产记忆里的 {@code responseData} 是 {@code toModelJson} 的产物
	 * （{@code （声明）<tool_result>{…}</tool_result>}）。只做 {@link #unwrapEnvelope} 会因"整串不是以
	 * {@code {} 开头"而原样返回 ⇒ 卡面出现声明与内部信封 JSON。两步固定顺序：外壳 → 信封。</p>
	 */
	private String historyMessageText(String responseData) {
		return unwrapEnvelope(ToolResultEnvelope.unwrapModelJson(responseData));
	}

	/** 信封 JSON → {@code message} 原文；识别不了（非信封/普通 JSON 结果）一律原样返回。 */
	private String unwrapEnvelope(String responseData) {
		if (!StringUtils.startsWith(StringUtils.trim(responseData), "{")) {
			return responseData;
		}
		try {
			JsonNode node = this.objectMapper.readTree(responseData);
			// 三键同现才当信封（outcome + retryable + message）：避免把"恰好也有 outcome/message
			// 字段的普通工具 JSON 结果"误判成信封而吞掉原数据。
			if (node != null && node.isObject() && isToolResultEnvelope(node)) {
				return node.path("message").asText();
			}
		}
		catch (Exception ex) {
			if (LogSampling.canAcquire("history-envelope-unwrap")) {
				log.warn("history tool result is not parseable envelope JSON; passing through raw text", ex);
			}
		}
		return responseData;
	}

	/** 工具结果信封的三键签名（{@code outcome} 文本 + {@code retryable} 布尔 + {@code message} 文本）。 */
	private static boolean isToolResultEnvelope(JsonNode node) {
		return node.path("outcome").isTextual() && node.path("retryable").isBoolean()
				&& node.path("message").isTextual();
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
	 * {@code ai:ctx:} 依然可读）、<b>Redis 不可用 → 503</b>。响应体是<b>裸对象</b>（与 {@code /cancel}
	 * 同姿态），字段见设计稿 §3.4(b)。
	 *
	 * <p>
	 * {@code ?ledger=1} 时附带 {@code ai:ctx:ledger:} 最近 20 条压缩事件（运维面）；默认不带，
	 * 保持每轮/每次刷新都拉的载荷轻。
	 */
	@GetMapping("/context/{sessionId}")
	public ResponseEntity<Map<String, Object>> context(@PathVariable String sessionId,
			@RequestParam(name = "ledger", required = false) Boolean ledger) {
		if (!StringUtils.isNotBlank(sessionId)) {
			return json(HttpStatus.BAD_REQUEST, errorBody(ErrorCodes.BAD_REQUEST, "sessionId 必填"));
		}
		if (!this.redisAvailability.isAvailable()) {
			return json(HttpStatus.SERVICE_UNAVAILABLE, redisUnavailable());
		}
		if (this.contextBreakdownService == null) {
			// R-16：用户面文案不得出现 Java 类名（"ContextBreakdownService 缺失"这类内部实现细节
			// 只留在日志里）；message 与其它 503 同一本地化出口。
			log.warn("/context metering not wired sessionId={} reason=context_breakdown_service_missing", sessionId);
			return json(HttpStatus.SERVICE_UNAVAILABLE, errorBody(ErrorCodes.AI_CONTEXT_UNAVAILABLE,
					"上下文计量暂不可用，请稍后重试"));
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

	/** AI 可用性（N5）：前端据此决定 AI 面板启用/降级提示，无需先发一次对话才知道。 */
	@GetMapping("/health")
	public ResponseEntity<Map<String, Object>> health() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("available", this.availability.isAvailable());
		String availabilityCode = this.availability.isAvailable() ? "AI_AVAILABLE" : this.availability.code();
		body.put("code", availabilityCode);
		addHealthConfiguration(body);
		addHealthRuntime(body);
		addHealthObservability(body);
		ResponseEntity<Map<String, Object>> unavailable = healthUnavailable(body, availabilityCode);
		return unavailable != null ? unavailable : ResponseEntity.ok(body);
	}

	/** 默认模型、上下文上限与默认参数仍按配置快照优先、环境变量回落。 */
	private void addHealthConfiguration(Map<String, Object> body) {
		AiConfigService.ResolvedProvider resolved = this.aiConfigService == null ? null
				: this.aiConfigService.resolveDefault().orElse(null);
		body.put("model", resolved != null ? resolved.modelId()
				: this.environment.getProperty("spring.ai.openai.chat.options.model", "(unset)"));
		body.put("providerId", resolved == null || resolved.providerId() == null ? null : resolved.providerId().toString());
		body.put("defaultModelId", resolved != null ? resolved.modelId() : null);
		ContextLimitResolver.ResolveResult contextLimit = this.contextCompactor == null
				? new ContextLimitResolver.ResolveResult(0, ContextLimitResolver.Source.UNKNOWN)
				: this.contextCompactor.resolveLimit(resolved != null ? resolved.providerId() : null,
						resolved != null ? resolved.modelId() : null);
		int contextLimitTokens = contextLimit.limitTokens();
		body.put("contextLimitTokens", contextLimitTokens > 0 ? contextLimitTokens : null);
		body.put("contextLimitSource", contextLimit.source().wire());
		body.put("compactionOn", this.contextCompactor != null && contextLimitTokens > 0);
		body.put("providers", this.aiConfigService == null ? List.of() : this.aiConfigService.describeProviders());
		AiConfigService.Defaults defaults = this.aiConfigService == null ? null : this.aiConfigService.defaults();
		body.put("allowedParams", defaults == null || !Boolean.FALSE.equals(defaults.allowUserParams()));
		Map<String, Object> defaultParams = new LinkedHashMap<>();
		defaultParams.put("temperature", defaults == null ? null : defaults.temperature());
		defaultParams.put("topP", defaults == null ? null : defaults.topP());
		defaultParams.put("maxTokens", defaults == null ? null : defaults.maxTokens());
		body.put("defaultParams", defaultParams);
	}

	private void addHealthRuntime(Map<String, Object> body) {
		body.put("memoryBackend", this.chatMemoryRepository.getClass().getSimpleName());
		body.put("sessionTtl", String.valueOf(this.properties.getSessionTtl()));
		body.put("frontendToolTimeoutSeconds", this.confirmGate.frontendToolTimeoutSeconds());
		body.put("suspendPoolSize", this.properties.getSuspend().getPoolSize());
		body.put("sseDelivery", sseDeliverySnapshot());
		body.put("activeSuspendGates", this.confirmGate.pendingGates());
		body.put("heldSessions", this.sessionGate.heldSessions());
		body.put("sessionLockRenewals", this.sessionGate.renewals());
		body.put("sessionLockLost", this.sessionGate.lockLost());
		body.put("sessionLockDegraded", this.sessionGate.lockLost() > 0 || this.sessionGate.degradedSessions() > 0);
	}

	/** Redis/租约自检保持现算；未装配的安全与表格指标仍不产键。 */
	private void addHealthObservability(Map<String, Object> body) {
		body.put("resilience", Map.of(
				"firstByteTimeout", String.valueOf(this.properties.getResilience().getFirstByteTimeout()),
				"interEventTimeout", String.valueOf(this.properties.getResilience().getInterEventTimeout()),
				"maxAttempts", this.properties.getResilience().getMaxAttempts(),
				"totalBudget", String.valueOf(this.properties.getResilience().getTotalBudget())));
		body.put("resumeOnStartup", this.properties.getResume().isOnStartup());
		this.redisAvailability.isAvailable();
		body.put("redis", this.redisAvailability.describe());
		body.put("cancellations", Map.of("registeredRuns", this.cancellations.registeredRuns(),
				"redisPolls", this.cancellations.polls(), "degraded", this.cancellations.degraded()));
		body.put("instanceId", this.runStore.instanceId());
		body.put("leaseOk", this.runStore.leaseService() != null && this.runStore.leaseService().leaseOk());
		body.put("checkedAt", Instant.now().toString());
		body.put("jobWatchdog", this.jobWatchdog.metrics());
		if (this.securityEvents != null) {
			body.put("security", this.securityEvents.metrics());
		}
		if (this.sheetSwitches != null) {
			Map<String, Object> sheet = new LinkedHashMap<>(this.sheetSwitches.health());
			if (this.sheetAudit != null) {
				sheet.put("counters", this.sheetAudit.metrics());
			}
			body.put("sheet", sheet);
		}
	}

	private ResponseEntity<Map<String, Object>> healthUnavailable(Map<String, Object> body, String availabilityCode) {
		if (this.availability.isAvailable()) {
			return null;
		}
		body.put("message", localize(availabilityCode, StringUtils.defaultString(this.availability.reason())));
		Map<String, Object> diagnostics = keyDiagnostics(availabilityCode);
		if (!diagnostics.isEmpty()) {
			body.put("keyDiagnostics", diagnostics);
		}
		return json(HttpStatus.SERVICE_UNAVAILABLE, body);
	}

	/**
	 * 可用性 503 的运维诊断（I-1）：变量名与恢复路径只在这里出现，用户面文案不带机制词。
	 *
	 * <p>只列变量名，不含任何值；未知/可用状态返回空 Map（不产键，保持既有载荷形状）。
	 */
	private static Map<String, Object> keyDiagnostics(String code) {
		if (ErrorCodes.AI_KEY_MISSING.equals(code)) {
			return Map.of("envVars", List.of("AI_API_KEY", "DEEPSEEK_API_KEY"));
		}
		if (ErrorCodes.AI_KEY_UNREADABLE.equals(code)) {
			return Map.of("envVars", List.of("AI_SECRET_KEK"));
		}
		if (ErrorCodes.AI_UNAVAILABLE.equals(code)) {
			return Map.of("envVars", List.of("AI_API_KEY", "DEEPSEEK_API_KEY", "AI_SECRET_KEK"));
		}
		return Map.of();
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
	 * #STREAMING_HEARTBEAT_MILLIS} = 5s（T3-5 维持现值，未改）。批次3 起 5s 这一档同时是
	 * <b>请求前压缩阶段</b>（{@code phase=compacting}）的节拍 —— 它由 {@code TurnKeepalive} 的
	 * 独立定时器出帧，故"流式"这一格覆盖"压缩 / 首包等待 / 包间静默 / 慢工具"四类静默。</li>
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
		String code = this.availability.code();
		String reason = this.availability.reason();
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("code", code);
		// A3-Y7 / I-1：与错误体同一本地化出口（zh 逐字不变）。AI_UNAVAILABLE / AI_KEY_MISSING /
		// AI_KEY_UNREADABLE 三档都已在 messages_en 登记，en 不再回落中文原文。
		body.put("message", localize(code, StringUtils.defaultString(reason)));
		return body;
	}

	/**
	 * R4：Redis 不可用的 503 体（带探活事实，便于前端与排障区分"没 key"与"没 Redis"）。
	 *
	 * <p>I-1：用户面的 {@code message} 走 {@code localize()}（{@code AI_REDIS_UNAVAILABLE} 已有 en 文案）；
	 * {@code redis} 子对象是**安全事实面**（available/probes/failures/时间锚点）。R-11 残留收口：
	 * 这里<b>不再带</b> {@code lastError}；{@code /health} 的 {@code redis.lastError} 仅含异常简单类名
	 * 或空 PING 分类，连接细节仅记录在服务端 Throwable 日志中。
	 */
	private Map<String, Object> redisUnavailable() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("code", RedisAvailability.code());
		body.put("message", localize(RedisAvailability.code(), this.redisAvailability.reason()));
		body.put("redis", this.redisAvailability.describeForResponse());
		return body;
	}

	/** 503 挂起池饱和：message 本地化，{@code poolSize}/{@code runId} 仍是结构字段。 */
	private Map<String, Object> poolSaturated(String runId) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("code", ErrorCodes.SUSPEND_POOL_SATURATED);
		body.put("message", localize(ErrorCodes.SUSPEND_POOL_SATURATED,
				"挂起专用线程池已饱和（池容量 = app.ai.suspend.pool-size），请稍后重试"));
		body.put("runId", runId);
		body.put("poolSize", this.properties.getSuspend().getPoolSize());
		return body;
	}

	/**
	 * N8（2026-10-26 批次 6C）：未知/过期的确认条目。
	 *
	 * <p>{@code code} 与 {@code runId}/{@code toolCallId} 仍是结构字段（前端按 {@code code}
	 * 把卡收敛成失效态，排障按 id 对账）；{@code message} 是用户可见文案，<b>不打印内部 ID</b>，
	 * 也不复述"runId/toolCallId"这类实现术语。
	 */
	private Map<String, Object> unknownToolCall(String runId, String toolCallId) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("code", ErrorCodes.UNKNOWN_TOOL_CALL);
		body.put("message", localize(ErrorCodes.UNKNOWN_TOOL_CALL,
				"这轮对话已失效，无法再处理这个操作；请重新提问。"));
		body.put("runId", runId);
		body.put("toolCallId", toolCallId);
		body.put("instanceId", this.runStore.instanceId());
		return body;
	}

	/** 重复提交同样是用户可见文案；{@code code}/{@code status}/{@code duplicate} 保持结构不变。 */
	private Map<String, Object> duplicateToolCall(String runId, String toolCallId, String status) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("code", ErrorCodes.DUPLICATE_TOOL_CALL_ID);
		body.put("message", localize(ErrorCodes.DUPLICATE_TOOL_CALL_ID,
				"这个操作已经处理过了，无需重复提交。"));
		body.put("runId", runId);
		body.put("toolCallId", toolCallId);
		body.put("status", status);
		body.put("duplicate", true);
		body.put("instanceId", this.runStore.instanceId());
		return body;
	}

	/** 错入口条目：kind 缺失或与本入口不符都返回该条目（缺证据不授权，交给闸门回 409）。 */
	private static PendingToolCall wrongKindEntry(PendingToolCall pending, String expectedKind) {
		if (pending == null || pending.isKind(expectedKind)) {
			return null;
		}
		return pending;
	}

	/** 错入口统一 409：状态和条目不被修改，调用方应改用对应接口。 */
	private Map<String, Object> toolKindMismatch(String runId, String toolCallId, PendingToolCall pending,
			String expectedKind) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("code", ErrorCodes.TOOL_KIND_MISMATCH);
		body.put("message", localize(ErrorCodes.TOOL_KIND_MISMATCH,
				"该工具调用不属于本入口（服务端 kind = " + (pending == null ? "未知" : pending.getKind())
						+ "，本接口只接受 " + expectedKind + "）；请改用对应的接口，状态未变"));
		body.put("runId", runId);
		body.put("toolCallId", toolCallId);
		body.put("name", pending == null ? null : pending.getName());
		body.put("kind", pending == null ? null : pending.getKind());
		body.put("expectedKind", expectedKind);
		body.put("status", pending == null ? null : pending.getStatus());
		body.put("accepted", false);
		body.put("duplicate", false);
		body.put("instanceId", this.runStore.instanceId());
		body.put("note", "确认门决策走 /api/ai/confirm（kind=CONFIRM），前端工具回灌走 "
				+ "/api/ai/frontend-tool-result（kind=FRONTEND）；本次请求未改动任何条目");
		return body;
	}

	/**
	 * 超限回灌是否为「结构化结果」（结构化回灌超限拒绝批）。
	 *
	 * <p>只有<b>仍待前端回灌</b>的条目才参与判定：未知 id（{@code null}）、已决条目（重复提交）、
	 * 非前端 kind（确认门条目）一律<b>不接管</b> —— 它们的既有语义是 409 UNKNOWN/DUPLICATE，
	 * 不能被一条新的尺寸判据吞成 400。
	 *
	 * <p>判定本体见 {@link FrontendToolGuard#isStructuredResult}：表格写四件按工具名，其余按
	 * "能解析成 JSON 对象/数组"的形状。普通超长文本（含以 <code>{</code> 开头但不是 JSON 的）
	 * 仍走既有的截断 + 标记路径。
	 *
	 * <p>读条目的是<b>非抛读口</b>（{@code pendingLenient}）：Redis 抖动时退回"只看形状"的旧路径，
	 * 不把一次读失败升级成回灌失败（真正写路径上的 fail-closed 仍在确认门里）。
	 */
	private boolean isStructuredOversize(PendingToolCall pending, String result) {
		if (pending == null || !PendingToolCall.KIND_FRONTEND.equals(pending.getKind())
				|| !PendingToolCall.PENDING.equals(pending.getStatus())) {
			return false;
		}
		return FrontendToolGuard.isStructuredResult(pending.getName(), this.objectMapper, result);
	}

	/**
	 * 400 {@code FRONTEND_RESULT_TOO_LARGE}（结构化回灌超限拒绝批）。
	 *
	 * <p>与 {@code MESSAGE_TOO_LONG} 同一形状：稳定码 + 两枚数字（{@code originalChars} /
	 * {@code limit}），<b>不回显结果原文</b>（结构化结果可能含业务数据）。
	 * 语义补充：{@code truncated=false} 明确"后端一个字符都没落"，
	 * {@code status=PENDING} + {@code retryable=true} 明确"挂起还在等，改小后可重试"。
	 *
	 * <p>I-1：{@code message} 走 {@code localize()} —— 中文逐字取调用方原文，非中文取
	 * {@code messages_en} 的 {@code error.FRONTEND_RESULT_TOO_LARGE}。
	 */
	private Map<String, Object> frontendResultTooLarge(String runId, String toolCallId, PendingToolCall pending,
			int originalChars, int limit) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("code", ErrorCodes.FRONTEND_RESULT_TOO_LARGE);
		body.put("message", localize(ErrorCodes.FRONTEND_RESULT_TOO_LARGE,
				"结构化结果 " + originalChars + " 字符，超过回灌上限 " + limit
						+ " 字符；后端不做截断（截断会切坏 JSON / 表格尾行），请在客户端缩小结果后重试"));
		body.put("runId", runId);
		body.put("toolCallId", toolCallId);
		body.put("name", pending.getName());
		body.put("kind", pending.getKind());
		body.put("status", pending.getStatus());
		body.put("originalChars", originalChars);
		body.put("limit", limit);
		body.put("structured", true);
		body.put("truncated", false);
		body.put("accepted", false);
		body.put("duplicate", false);
		body.put("retryable", true);
		body.put("instanceId", this.runStore.instanceId());
		body.put("note", "结果未落库、挂起未消费（状态仍是 PENDING）：把结果改小后重试；用户放弃请带 cancelled=true");
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
		// GF 优化批 G-A：结构化错误随拒绝体下发（前端按 key 点亮字段、按 code 决定是否可重试）；
		// 人读 reasons 保留（E2E 中文断言与模型错误信封逐字不变）。
		body.put("issues", verdict.issues());
		body.put("retryable", verdict.isRetryable());
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

	/** 确认门 {@code reason} 的字符上限（防护批③ B2-6；与 {@code ai_feedback.reason} 同尺）。 */
	public static final int REASON_MAX_CHARS = 1024;

	/** 挂起项快照（诊断用：确认门/前端工具各自等在哪里）。 */
	@GetMapping("/pending/{runId}")
	public ApiResponse<List<Map<String, Object>>> pending(@PathVariable String runId) {
		if (!isOwnedOrAbsent(runId)) {
			// 他人/无归属的轮次按"没有待决条目"回答：不泄漏这一轮在谁手里
			return ApiResponse.ok(List.of());
		}
		List<Map<String, Object>> items = new ArrayList<>();
		for (PendingToolCall pending : this.runStore.pendingsLenient(runId)) {
			items.add(pending.asMap());
		}
		return ApiResponse.ok(items.isEmpty() ? List.of() : items);
	}

	// ── R-163 T5：赞踩反馈（ai_feedback net-new 表，parts[] 持久化不动） ──

	/**
	 * POST /api/ai/feedback —— 赞踩上报（<b>身份内</b>幂等 upsert：(tenant_id,user_id,message_id) 唯一；
	 * rating=0 = 取消=删行）。请求体 {@code {messageId, sessionId, rating∈1/-1/0, reason?}}；
	 * 校验失败 400 携可读 message。
	 *
	 * <p><b>身份（租户任务 2 收尾组 / V11）</b>：反馈是会话数据 ⇒ 写入固化当前
	 * {@code tenant_id/user_id}、{@code created_by = userId}，查找/删除都限定在本身份内
	 * （跨租户同 messageId 各自一行，不互相覆盖）。<b>无身份 fail-closed</b>：不写、不改、不删，
	 * 400 {@link ErrorCodes#TENANT_IDENTITY_REQUIRED}（生产入口由 {@code TenantIdentityFilter} 保证有身份）。
	 */
	@PostMapping("/feedback")
	public ResponseEntity<?> feedback(@RequestBody Map<String, Object> body) {
		TenantUser identity = RequestContextUtils.currentOrNull();
		if (identity == null) {
			return json(HttpStatus.BAD_REQUEST,
					errorBody(ErrorCodes.TENANT_IDENTITY_REQUIRED, "缺少租户/用户身份上下文，反馈不可跨身份读写"));
		}
		FeedbackInput input = feedbackInput(body);
		if (input.messageId() == null || input.sessionId() == null || input.rating() == null
				|| (input.rating() != 1 && input.rating() != -1 && input.rating() != 0)) {
			return json(HttpStatus.BAD_REQUEST,
					errorBody(ErrorCodes.BAD_REQUEST, "messageId/sessionId 必填，rating ∈ 1/-1/0"));
		}
		if (input.rating() == 0) {
			return cancelFeedback(input.messageId(), identity.tenantId(), identity.userId());
		}
		return upsertFeedback(input, identity);
	}

	private static FeedbackInput feedbackInput(Map<String, Object> body) {
		Object ratingRaw = body.get("rating");
		Integer rating = ratingRaw instanceof Number number ? number.intValue() : null;
		return new FeedbackInput(stringOf(body.get("messageId")), stringOf(body.get("sessionId")), rating,
				stringOf(body.get("reason")), stringOf(body.get("excerpt")));
	}

	private ResponseEntity<?> cancelFeedback(String messageId, long tenantId, long userId) {
		AiFeedback existing = this.aiFeedbackRepository.findByMessageIdAndTenantIdAndUserId(messageId, tenantId, userId);
		if (existing != null) {
			this.aiFeedbackRepository.delete(existing);
		}
		return ResponseEntity.ok(ApiResponse.ok(Map.of("saved", false, "cancelled", existing != null)));
	}

	/** 摘录命中高置信凭据时整条隐去，再按原上限入库。 */
	private ResponseEntity<?> upsertFeedback(FeedbackInput input, TenantUser identity) {
		long tenantId = identity.tenantId();
		long userId = identity.userId();
		AiFeedback entry = this.aiFeedbackRepository.findByMessageIdAndTenantIdAndUserId(input.messageId(), tenantId,
				userId);
		boolean saved = entry == null;
		if (entry == null) {
			entry = new AiFeedback();
			entry.setMessageId(input.messageId());
		}
		entry.setTenantId(tenantId);
		entry.setUserId(userId);
		entry.setCreatedBy(String.valueOf(userId));
		entry.setSessionId(input.sessionId());
		entry.setRating(input.rating());
		if (input.reason() != null) {
			entry.setReason(StringUtils.left(input.reason(), 1024));
		}
		if (input.excerpt() != null) {
			String safeExcerpt = ExcerptGuard.sensitive(input.excerpt()) ? "[已隐去]" : input.excerpt();
			entry.setMessageExcerpt(StringUtils.left(safeExcerpt, 200));
		}
		this.aiFeedbackRepository.save(entry);
		return ResponseEntity.ok(ApiResponse.ok(Map.of("saved", saved, "cancelled", false)));
	}

	private record FeedbackInput(String messageId, String sessionId, Integer rating, String reason, String excerpt) {
	}

	/**
	 * GET /api/ai/feedback —— 设置页「AI 反馈」查看列表（时间/消息摘要/rating/reason，
	 * <b>仅当前租户/用户</b>；上限 100 条，按更新时间倒序）。
	 *
	 * <p>无身份 fail-closed：返回错误信封（{@code TENANT_IDENTITY_REQUIRED}），<b>不</b>回落到"全表"。
	 */
	@GetMapping("/feedback")
	public ApiResponse<List<Map<String, Object>>> feedbackList() {
		TenantUser identity = RequestContextUtils.currentOrNull();
		if (identity == null) {
			return ApiResponse.error(ErrorCodes.TENANT_IDENTITY_REQUIRED, "缺少租户/用户身份上下文，反馈不可跨身份读取");
		}
		List<Map<String, Object>> items = new ArrayList<>();
		for (AiFeedback entry : this.aiFeedbackRepository
			.findTop100ByTenantIdAndUserIdOrderByUpdatedAtDesc(identity.tenantId(), identity.userId())) {
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
		return ApiResponse.ok(items.isEmpty() ? List.of() : items);
	}

	private static String stringOf(Object value) {
		return StringUtils.trimToNull(Objects.toString(value, null));
	}

}
