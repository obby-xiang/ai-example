package com.example.configmgr.ai.web;

import com.example.configmgr.common.ErrorCodes;

import com.example.configmgr.ai.config.AiAvailability;
import com.example.configmgr.ai.config.AiConfigService;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.RedisAvailability;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.AiRateLimiter;
import com.example.configmgr.ai.gate.ContextFieldGate;
import com.example.configmgr.ai.gate.InputSanitizer;
import com.example.configmgr.ai.feedback.AiFeedback;
import com.example.configmgr.ai.feedback.ExcerptGuard;
import com.example.configmgr.ai.feedback.AiFeedbackRepository;
import com.example.configmgr.ai.history.AiChatHistoryService;
import com.example.configmgr.ai.history.AiChatHistoryService.ChatPage;
import com.example.configmgr.ai.history.AiChatHistoryService.MessageView;
import com.example.configmgr.ai.history.SyntheticMessages;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.observe.AiTrace;
import com.example.configmgr.ai.run.CancellationRegistry;
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
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.StringUtils;
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
 * 被安全闸拒绝 → <b>400</b> 携机器可读码 + 原因清单（挂起不消费，可重试）；
 * <b>结构化结果</b>超 {@code app.ai.result-budget.callback.max-chars} → <b>400
 * FRONTEND_RESULT_TOO_LARGE</b>（不截断、不调 submit、不落库，客户端改小后重试；普通文本超限仍截断 + 标记）；
 * {@code cancelled:true} → 用户取消终态（DC-15）</td></tr>
 * <tr><td>{@code GET /api/ai/events/{runId}}</td><td>reattach：回放该轮状态类帧 + 继续收实时帧
 * （ADR-5 补记 CH-P4）</td></tr>
 * <tr><td>{@code GET /api/ai/runs} / {@code GET /api/ai/runs/{runId}}</td><td>重启后的
 * "发现待续跑轮次"与单轮明细（台账/待决/历史角色）</td></tr>
 * <tr><td>{@code POST /api/ai/runs/{runId}/resume}</td><td>续跑（重建完整历史 → 官方循环）</td></tr>
 * <tr><td>{@code GET /api/ai/history/{sessionId}}</td><td>记忆窗口原文</td></tr>
 * <tr><td>{@code DELETE /api/ai/history/{sessionId}} / {@code DELETE /api/ai/session/{sessionId}}</td>
 * <td><b>完整会话销毁</b>（旧路径保留兼容，统一委托 purge）：删除该会话全部 AI 数据（记忆/run 13 键/
 * 上下文/附件/反馈/完整历史/会话索引），业务数据不碰；幂等 200，Redis 不可用 503，
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
	 * 多实例专项：来源 IP 解析（可信代理网段内的 X-Forwarded-For 才认）。
	 *
	 * <p>字段注入的理由同本类其余可选组件：构造器签名不变 ⇒ 既有 {@code new AiController(...)}
	 * 的单测零触碰；未注入（standalone 单测）时回落 {@code RemoteAddr}，与改造前逐字一致。
	 */
	@Autowired(required = false)
	private com.example.configmgr.config.ClientIpResolver clientIpResolver;

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
	private com.example.configmgr.ai.session.SessionPurgeGuard sessionPurgeGuard;

	/**
	 * AI 表格工具治理批：表格开关（{@code /health.sheet} 的配置面）。未注入时少该键。
	 */
	@Autowired(required = false)
	private SheetSwitches sheetSwitches;

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
	private com.example.configmgr.ai.context.ContextCompactor contextCompactor;

	@Autowired(required = false)
	private com.example.configmgr.ai.context.ContextBreakdownService contextBreakdownService;

	/**
	 * 完整对话历史存储（第二阶段）。字段注入保持构造器与既有 {@code new AiController(...)} 用例零改动；
	 * 未注入（局部上下文）时新端点如实 503，既有 40 条记忆窗口端点不受影响。
	 */
	@Autowired(required = false)
	private AiChatHistoryService aiChatHistoryService;

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
	private List<com.example.configmgr.ai.resultview.ToolResultViewProvider> resultViewProviders = List.of();

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
	public ResponseEntity<?> chat(@RequestBody(required = false) AiChatRequest request,
			HttpServletRequest httpRequest) {
		ResilientChatService service = this.chatService.getIfAvailable();
		if (!this.availability.isAvailable() || service == null) {
			// AI 日志最小补齐批：这条 return 在 AiTrace.bind 之前 ⇒ 此前日志里一行不留（最大盲区）
			deny(HttpStatus.SERVICE_UNAVAILABLE, this.availability.code(), request == null ? null : request.sessionId(),
					null, "reason", "ai_unavailable");
			return json(HttpStatus.SERVICE_UNAVAILABLE, unavailable());
		}
		// R4：Redis 不可用时在**开流之前**快速失败（≤ 一次 PING 的超时，见 RedisAvailability）。
		// 顺序：必填校验 → Redis 探活 → 限流 → 归一化 → 空拒 → 长度。
		// 必填校验在探活之前（参数错就该报 400，不该被 Redis 状态遮住）；其余三道闸依赖 Redis 计数，
		// 故排在探活之后 —— 由此产生的既定顺序是：Redis 不可用时，超长请求会先撞 503 而不是 400。
		if (request == null || !StringUtils.hasText(request.sessionId()) || !StringUtils.hasText(request.message())) {
			deny(HttpStatus.BAD_REQUEST, ErrorCodes.BAD_REQUEST, request == null ? null : request.sessionId(), null,
					"reason", "missing_session_or_message");
			return json(HttpStatus.BAD_REQUEST, errorBody(ErrorCodes.BAD_REQUEST, "sessionId 与 message 均为必填"));
		}
		if (!this.redisAvailability.isAvailable()) {
			deny(HttpStatus.SERVICE_UNAVAILABLE, RedisAvailability.code(), request.sessionId(), null,
					"reason", "redis_unavailable");
			return json(HttpStatus.SERVICE_UNAVAILABLE, redisUnavailable());
		}
		// 批① 闸门 1：限流（§5.1 #3）。**排在归一化/长度之前**（裁决 I2，2026-10-21）：所有 /chat 请求
		// 一律计数 —— 超长/纯不可见字符也是请求，同样消耗额度（防洪优先于"额度洁癖"：否则攻击者可用
		// 超长体绕过限流做免费试探）。同时它在会话门之前：被限流的请求不该占住一个 in-flight 位。
		AiRateLimiter.Decision rate = this.aiRateLimiter.check(request.sessionId(), clientIp(httpRequest));
		if (!rate.allowed()) {
			if (this.securityEvents != null) {
				this.securityEvents.rateLimited();
			}
			deny(HttpStatus.TOO_MANY_REQUESTS, ErrorCodes.RATE_LIMITED, request.sessionId(), null,
					"scope", rate.scope(), "used", rate.used(), "limit", rate.limit(),
					"retryAfterS", rate.retryAfterSeconds());
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
			deny(HttpStatus.BAD_REQUEST, ErrorCodes.BAD_REQUEST, request.sessionId(), null, "reason",
					"empty_after_sanitize", "chars", sanitized.text().length());
			return json(HttpStatus.BAD_REQUEST,
					errorBody(ErrorCodes.BAD_REQUEST, "message 归一化后为空（原文仅含不可见字符），请重新输入"));
		}
		// 批① 闸门 3：长度（§5.1 #1 / 数据最小化 R5）。超限**拒绝不截断**：截断会让模型基于半句话下结论。
		int maxChars = this.properties.getInput().getMaxChars();
		if (sanitized.text().length() > maxChars) {
			if (this.securityEvents != null) {
				this.securityEvents.inputRejected();
			}
			deny(HttpStatus.BAD_REQUEST, ErrorCodes.MESSAGE_TOO_LONG, request.sessionId(), null,
					"chars", sanitized.text().length(), "limit", maxChars);
			return json(HttpStatus.BAD_REQUEST, messageTooLong(sanitized.text().length(), maxChars));
		}

		String sessionId = request.sessionId();
		// 批② 新段（追加在既有五道闸之后，既有闸顺序不变）：
		// context 形状闸 —— 白名单化 + **降级不回显**（用户裁决 2026-10-21 第 1 条：不拒绝请求）。
		// L-99：输入侧 DLP 已删除 —— 归一层（InputSanitizer）之后的文本原样进模型与记忆。
		ContextFieldGate.Result gated = this.contextFieldGate == null ? null : this.contextFieldGate.sanitize(request.context());
		AiChatRequest.Context effectiveContext = gated == null ? request.context() : gated.context();
		if (gated != null && gated.downgraded() > 0) {
			if (this.securityEvents != null) {
				this.securityEvents.contextDowngraded(gated.downgraded());
			}
		}
		String modelText = sanitized.text();
		// 归一后的文本 / 闸后的 context 才是进模型/进记忆的那份（原始 request 不可变，故换一个新 record）
		AiChatRequest effective = new AiChatRequest(sessionId, modelText, effectiveContext, request.modelId(),
				request.params());
		// 批③：模型与参数的<b>存在性</b>校验（纯查快照，不碰上游）。放在限流/长度之后、会话门之前 ——
		// 与既有闸序一致（所有请求都计数、超长先撞），且拒绝不占 in-flight 位。
		// 显式模型不可用 ⇒ 400 MODEL_NOT_FOUND（不静默回落默认模型：用户点了 A 却跑 B 比报错更糟）。
		if (this.aiConfigService != null && StringUtils.hasText(effective.modelId())
				&& this.aiConfigService.resolveChatTarget(effective.modelId()).isEmpty()) {
			deny(HttpStatus.BAD_REQUEST, ErrorCodes.MODEL_NOT_FOUND, sessionId, null,
					"model", effective.modelId());
			return json(HttpStatus.BAD_REQUEST, errorBody(ErrorCodes.MODEL_NOT_FOUND,
					"该模型当前不可用（未在可用端点的模型清单里，或端点已停用/密钥不可用）：" + effective.modelId()));
		}
		String paramsRejection = validateParams(effective.params());
		if (paramsRejection != null) {
			deny(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_PARAM, sessionId, null, "reason", paramsRejection);
			return json(HttpStatus.BAD_REQUEST, errorBody(ErrorCodes.INVALID_PARAM, paramsRejection));
		}
		// 会话销毁守卫（复审 #3）：已销毁的 sessionId 不得再开新轮 —— 否则 run 能建、记忆/历史
		// 却被守卫拒绝写，表现为"能聊天但不留痕"的静默复活。必须在创建 runId / 占会话门之前。
		// 守卫读失败 fail-closed：按 Redis 不可用 503，绝不按"未销毁"放行。
		if (this.sessionPurgeGuard != null) {
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
		}
		String runId = UUID.randomUUID().toString();
		SessionGate.Busy busy = this.sessionGate.acquire(sessionId, runId);
		// 批G · G6：僵尸轮放行 —— 上一轮所在进程死了，会话门却还留着他的 runId 时，用户会永远
		// 409（只能刷新）。判据见 RunLiveness（无租约 + 本进程无活连接 + 活动早已超出僵尸窗口）。
		// RT-35：持有者不可知（{@code busy.unknown()}、runId=null）时没有可判的轮 —— 不拿 null 去读
		// 快照/租约（Redis 抖动时那会把"如实 409"变成异常），也不做任何强释放。
		if (busy != null && !busy.unknown() && isDeadRound(busy.runId())) {
			log.warn("会话门被死轮占着，放行新发送 sessionId={} deadRunId={} newRunId={}", sessionId, busy.runId(), runId);
			// 死轮已判定不再推进：按 runId 强释放，仅当会话门仍属于这个死轮才生效。
			this.sessionGate.releaseDeadHeld(sessionId, busy.runId());
			busy = this.sessionGate.acquire(sessionId, runId);
		}
		if (busy != null) {
			// ADR-5：409 携带进行中轮的 runId，前端据此改走 /api/ai/events/{runId}
			// —— 仅当持有者可知；不可知时不写 runId/reattach（不编造指向不存在流的地址）。
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("code", ErrorCodes.SESSION_BUSY);
			body.put("message", localize(ErrorCodes.SESSION_BUSY, "该会话已有一轮对话在进行中，请先处理或重挂该轮"));
			body.put("sessionId", sessionId);
			if (busy.unknown()) {
				// RT-35：持有者不可知 ⇒ 没有可指向的轮，**不编造** runId 与 reattach
				// （否则对外给出 /api/ai/events/null 这条不存在的流，把调用方粘死在假地址上）。
				// 状态码与 degraded 不变：调用方按既有重试/退避即可恢复（无粘滞、不改状态码语义）。
				body.put("unknown", true);
			}
			else {
				body.put("runId", busy.runId());
				body.put("reattach", "/api/ai/events/" + busy.runId());
			}
			body.put("degraded", busy.degraded());
			// 本轮没有 runId（被拒了）：锚到"占着会话的那一轮"，那才是要看的那条链
			deny(HttpStatus.CONFLICT, ErrorCodes.SESSION_BUSY, sessionId, busy.runId(), "heldBy",
					busy.unknown() ? "unknown" : busy.runId(), "degraded", busy.degraded());
			return json(HttpStatus.CONFLICT, body);
		}

		SseEmitter emitter = new SseEmitter(NO_TRANSPORT_DEADLINE);
		SseChatEmitter out = this.runRegistry.of(runId);
		out.attach(emitter);
		// 日志链路批：HTTP 线程先装锚点 —— 线程池的 TaskDecorator 在 submit 时刻抓快照，一行不带锚点的
		// "已受理/池饱和"日志会让"这一轮的日志到底有没有开"无从判断。
		AiTrace.bind(runId, sessionId);
		AiTrace.event("turn_accepted",
				"context", effective.context() == null || effective.context().page() == null ? "none"
						: effective.context().page(),
				"model", effective.modelId() == null ? "default" : effective.modelId(),
				"chars", modelText.length());
		// 回答语言来源（A3-Y1 / A3-Y7）：与错误体本地化**同一个取值点** ——
		// ErrorBodyFactory.requestLocale() 读 Accept-Language（无头/畸形回落 zh-CN）。
		// 必须在 HTTP 线程取好：LocaleContextHolder 是线程级的，工作线程拿不到请求上下文，
		// 不显式搬运的话 SSE 这条通道的回答语言会恒为中文（A3-Y1 选"随用户语言"后遗症）。
		Locale replyLocale = ErrorBodyFactory.requestLocale();
		try {
			this.aiRunExecutor.execute(() -> {
				LocaleContextHolder.setLocale(replyLocale);
				try {
					service.chat(runId, effective, out);
				}
				catch (Exception ex) {
					log.error("AI 轮次未捕获异常 sessionId={} runId={}", sessionId, runId, ex);
					// 每轮必有终态行：压缩/记忆写/快照建/取模型这些"环外"抛错此前只有一行 ERROR，
					// 没有任何结构化终态 ⇒ 按 runId grep 会看不到收尾。这里补一条终态事件。
					AiTrace.eventOn(runId, sessionId, "turn_exception", "code", "UNCAUGHT",
							"type", ex.getClass().getSimpleName(),
							"msg", ex.getMessage());
					// ===== 批次2 / AR-05：环外异常也走取消优先终态守卫 =====
					// 环外异常（压缩/记忆写/快照建/取模型）此前只发一帧 error、快照留在 RUNNING ⇒
					// 刷新后既无终态也无"可重跑"的依据。这里补写 FAILED，但**必须**与 fail()/emptyReply()
					// 共用 RunStore 的同一个 CAS：取消先到时不得把 CANCELLED 盖成 FAILED，
					// 也不得再发第二条终帧（取消侧的 done{cancelled:true} 才是这一轮的终帧）。
					// N2（返修）：失败终帧也由写口在**同一把** per-run 监视器内发出 —— 只在写快照时
					// 加锁不够，取消的 done{cancelled:true} 可以插在"写完 FAILED"与"锁外发 error"之间。
					writeOutsideTurnFailure(runId, ErrorCodes.AI_RUN_FAILED, () -> {
						// F：终态出口的进程内清理（与 cancel/finish 同一个统一出口；幂等）。
						service.clearTerminalRunState(runId);
						// R-08：用户面只给白名单短句；异常原文（可能含上游 body）已在上一行 ERROR 日志。
						out.error(ErrorCodes.AI_RUN_FAILED,
								localize(ErrorCodes.AI_RUN_FAILED, "AI 服务暂时不可用，请稍后重试。"));
					});
				}
				finally {
					LocaleContextHolder.resetLocaleContext();
					this.sessionGate.release(sessionId, runId);
					this.runRegistry.close(runId);
				}
			});
		}
		catch (RejectedExecutionException ex) {
			log.warn("挂起专用线程池饱和，拒绝 sessionId={} runId={}", sessionId, runId);
			AiTrace.event("turn_rejected", "code", "SUSPEND_POOL_SATURATED");
			this.sessionGate.release(sessionId, runId);
			this.runRegistry.close(runId);
			return json(HttpStatus.SERVICE_UNAVAILABLE, poolSaturated(runId));
		}
		finally {
			AiTrace.clear();
		}
		return ResponseEntity.ok(emitter);
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
	 * <li><b>不新增 DB 写入</b>：失败现场只落在 Redis 快照（{@code ai:run:<runId>}，24h 滑动 TTL）
	 *     与帧归档里 —— AR-01 明确禁止为失败现场新增 DB 写入 / 列 / TTL。</li>
	 * </ol>
	 *
	 * @param emitError 失败终帧的发送动作（在<b>同一把</b> per-run 监视器内执行；{@code null} = 只写状态）
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
		if (writeOutsideTurnFailureState(runId, code) && emitError != null) {
			emitError.run();
		}
	}

	/** 只做状态写（返回"调用方可否发失败终帧"），调用方必须已持有 per-run 监视器。 */
	private boolean writeOutsideTurnFailureState(String runId, String code) {
		try {
			RunSnapshot snapshot = this.runStore.get(runId);
			if (snapshot == null) {
				// ===== AR-06：首帧已发、快照未建（请求前压缩窗口）里抛出 =====
				// 这一段没有快照可写，也<b>禁止</b>为了"有个终态"而新建一份快照（裸 save 会凭空造出
				// 一个"已失败"的轮，盖住"这一轮还没真正开始"的事实）。但也不能因此不发终帧：
				// start 已经发给客户端了，没有终帧就成了"永远等不到结局"的悬挂流。
				// 于是只做两件事：①取消优先（权威取消态已置位 ⇒ 不发 error，取消侧才是终帧）；
				// ②否则补一条 error 终帧（不写任何快照）。
				if (isCancelledFlag(runId)) {
					log.info("环外异常不补终帧 runId={}：取消旗标已置位且快照尚未建立（取消优先）", runId);
					return false;
				}
				log.warn("环外异常发生在快照建立之前 runId={} code={}：不新建快照（禁止裸 save），只补 error 终帧", runId,
						code);
				return true;
			}
			if (RunSnapshot.isTerminal(snapshot.getStatus())) {
				return false;
			}
			if (snapshot.isCancelled()) {
				log.info("环外异常不写失败态 runId={}：快照取消位已置位（取消优先）", runId);
				return false;
			}
			snapshot.setStatus(RunSnapshot.FAILED);
			snapshot.setInFlightToolCalls(new java.util.ArrayList<>());
			snapshot.setError(code + "：" + localize(code, "AI 服务暂时不可用，请稍后重试。"));
			boolean written = this.runStore.saveTerminalUnlessCancelled(snapshot);
			if (!written) {
				log.info("环外异常失败态被取消优先守卫拒写 runId={} code={}（不覆盖 CANCELLED、不发 error 终帧）", runId,
						code);
			}
			return written;
		}
		catch (RuntimeException writeEx) {
			log.warn("环外异常失败态写回失败 runId={}（终帧照发；恢复侧按快照/租约兜底）：{}", runId,
					writeEx.getMessage());
			return true;
		}
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

	@PostMapping("/confirm")
	public ResponseEntity<?> confirm(@RequestBody(required = false) ConfirmRequest request) {
		if (request == null || !StringUtils.hasText(request.runId())
				|| !StringUtils.hasText(request.toolCallId())) {
			return json(HttpStatus.BAD_REQUEST, errorBody(ErrorCodes.BAD_REQUEST, "runId 与 toolCallId 均为必填"));
		}
		// 防护批③ B2-6 / N5：{@code reason} 先归一化，再按<b>归一后</b>的文本过 1024 长度闸 ——
		// 量的是真正落进 pending、并原样回填模型的那份，零宽填充不该把用户挡在门外，
		// 也不该借归一化把超长正文塞进去。归一后为空（原文仅不可见/空白字符）⇒ 按 null 交给确认门，
		// 沿用它的默认「用户在确认门中批准/拒绝」文案。
		InputSanitizer.Result sanitizedReason = this.inputSanitizer.sanitize(request.reason());
		if (sanitizedReason.changed()) {
			// 只记长度差与剥离计数：原因原文可能含敏感数据，日志里不得出现
			log.info("确认原因归一化生效 runId={} 长度 {}→{}（剥离格式字符 {} / 控制字符 {}）", request.runId(),
					sanitizedReason.originalLength(), sanitizedReason.text().length(),
					sanitizedReason.removedFormat(), sanitizedReason.removedControl());
		}
		String reason = StringUtils.hasText(sanitizedReason.text()) ? sanitizedReason.text() : null;
		if (reason != null && reason.length() > REASON_MAX_CHARS) {
			return json(HttpStatus.BAD_REQUEST, reasonTooLong(reason.length()));
		}
		// B23L1 批②b（读侧续期 touchActiveRun，方案第 5 条）：<b>先续命再落决策</b> —— 挂起线程可能早已不在本实例
		// （挂起在 A、决策落在 B），卡的后台数据只能由"有人在读/在写"这件事本身来续。
		// 自带终态判据：轮已终态 / 快照已删 ⇒ 不续期（不养孤儿键）。
		this.runStore.touchActiveRun(request.runId());
		// 同拍续期复核器持有的一次性资源（表单附件登记）：park 期没有心跳，读侧是唯一续期来源
		this.confirmGate.touchSuspendedResources(request.runId());
		// 只接受服务端 kind=CONFIRM；错入口在进闸门前拒绝，不改变任何条目。
		PendingToolCall entry = this.runStore.pendingLenient(request.runId(), request.toolCallId());
		PendingToolCall wrongKind = wrongKindEntry(entry, PendingToolCall.KIND_CONFIRM);
		if (wrongKind != null) {
			deny(HttpStatus.CONFLICT, ErrorCodes.TOOL_KIND_MISMATCH, null, request.runId(),
					"kind", wrongKind.getKind(), "expected", PendingToolCall.KIND_CONFIRM,
					"name", wrongKind.getName());
			return json(HttpStatus.CONFLICT, toolKindMismatch(request.runId(), request.toolCallId(), wrongKind,
					PendingToolCall.KIND_CONFIRM));
		}
		boolean approved = Boolean.TRUE.equals(request.approved());
		ConfirmGate.Submission submission;
		// 日志链路批：决策落在 HTTP 线程上 —— 装锚点，使 approval_decided 与"决策触发的续跑任务"
		// （同一 MDC 快照进池）都能挂回这一轮。
		AiTrace.bind(request.runId(), null);
		try {
			submission = this.confirmGate.submitDecision(request.runId(), request.toolCallId(), approved,
					reason);
		}
		finally {
			AiTrace.clear();
		}
		if (submission.outcome() == ConfirmGate.Outcome.NOT_FOUND) {
			return json(HttpStatus.CONFLICT, unknownToolCall(request.runId(), request.toolCallId()));
		}
		if (submission.outcome() == ConfirmGate.Outcome.TOOL_KIND_MISMATCH) {
			// 入口预读不可用时由闸门判定，仍统一为 409，而不是"已受理"。
			return json(HttpStatus.CONFLICT, toolKindMismatch(request.runId(), request.toolCallId(),
					submission.pending(), PendingToolCall.KIND_CONFIRM));
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
			return json(HttpStatus.BAD_REQUEST, errorBody(ErrorCodes.BAD_REQUEST, "runId 与 toolCallId 均为必填"));
		}
		// B23L1 批②b：读侧续期（同 /confirm 的口径：先续命再落回灌）
		this.runStore.touchActiveRun(request.runId());
		// 同拍续期一次性资源（附件登记）：与 touchActiveRun 同一位置
		this.confirmGate.touchSuspendedResources(request.runId());
		boolean cancelled = Boolean.TRUE.equals(request.cancelled());
		// 只接受服务端 kind=FRONTEND；同一条目供 kind 与结构化判定共用，避免重复读取。
		// kind 先于体积判断；cancelled=true 也先校验，因为取消同样改变状态。
		PendingToolCall entry = this.runStore.pendingLenient(request.runId(), request.toolCallId());
		PendingToolCall wrongKind = wrongKindEntry(entry, PendingToolCall.KIND_FRONTEND);
		if (wrongKind != null) {
			deny(HttpStatus.CONFLICT, ErrorCodes.TOOL_KIND_MISMATCH, null, request.runId(),
					"kind", wrongKind.getKind(), "expected", PendingToolCall.KIND_FRONTEND,
					"name", wrongKind.getName(), "cancelled", cancelled);
			return json(HttpStatus.CONFLICT, toolKindMismatch(request.runId(), request.toolCallId(), wrongKind,
					PendingToolCall.KIND_FRONTEND));
		}
		// 防护批③ B2-7：回灌载荷闸 —— 机器数据超限**截断并标记**（不像用户输入那样 400：
		// 表格摘要本来就可能很长，拒绝回灌会让前端工具永远无法收尾）。
		// G5：上限来自 app.ai.result-budget.callback.max-chars（不再是类内常量），
		// 截断标记计入预算，响应体与工具结果帧共用同一套截断字段（truncated/strategy/
		// originalChars/returnedChars/notice）。
		// 结构化回灌超限拒绝批：**结构化结果**（JSON 对象/数组、表格写结果）是这条闸门的例外 ——
		// substring 截断会切坏 JSON、切掉表格「共 N 格」尾行，落库的会是半截数据。这类超限在
		// 进入确认门**之前**以 400 FRONTEND_RESULT_TOO_LARGE 拒绝：不调 submit、不写条目、
		// pending 原样留着，客户端把结果改小后重试（与 /chat 的超长输入同一姿态：拒绝不截断）。
		int callbackMaxChars = this.properties.getResultBudget().getCallback().getMaxChars();
		String result = request.result();
		int originalChars = result == null ? 0 : result.length();
		if (!cancelled && result != null && originalChars > callbackMaxChars) {
			PendingToolCall pending = entry;
			if (structuredOversize(pending, result)) {
				deny(HttpStatus.BAD_REQUEST, ErrorCodes.FRONTEND_RESULT_TOO_LARGE, null, request.runId(),
						"chars", originalChars, "limit", callbackMaxChars, "name", pending.getName(),
						"kind", pending.getKind());
				log.info("结构化回灌结果超限，拒绝且不落库 runId={} toolCallId={} name={} 原长度={} 上限={}",
						request.runId(), request.toolCallId(), pending.getName(), originalChars, callbackMaxChars);
				return json(HttpStatus.BAD_REQUEST,
						frontendResultTooLarge(request.runId(), request.toolCallId(), pending, originalChars,
								callbackMaxChars));
			}
		}
		boolean truncated = false;
		int returnedChars = originalChars;
		String notice = null;
		if (result != null && result.length() > callbackMaxChars) {
			notice = "结果过长已截断：原 " + originalChars + " 字符，上限 " + callbackMaxChars
					+ " 字符；strategy=" + TruncationContract.STRATEGY_HEAD;
			String marker = "…（" + notice + "）";
			int budget = Math.max(0, callbackMaxChars - marker.length());
			int end = Math.min(budget, result.length());
			if (end > 0 && Character.isHighSurrogate(result.charAt(end - 1))) {
				end--;
			}
			result = result.substring(0, end) + marker;
			truncated = true;
			returnedChars = result.length();
			if (this.securityEvents != null) {
				this.securityEvents.payloadTruncated();
			}
			log.info("前端工具结果超限已截断 runId={} toolCallId={} 原长度={} 上限={}", request.runId(),
					request.toolCallId(), originalChars, callbackMaxChars);
		}
		ConfirmGate.Submission submission;
		// 日志链路批：回灌同样落在 HTTP 线程上（同 /confirm 的口径）
		AiTrace.bind(request.runId(), null);
		try {
			// AI 表格工具治理批：sheetMeta 在<b>入口</b>就过白名单（任何业务值到此为止），
			// 之后才交给确认门；不带 meta 时保持六参调用 —— 旧调用方与既有桩的实参形状逐字不变。
			Map<String, Object> sheetMeta = SheetAudit.sanitize(request.sheetMeta());
			// 批 A（A-14）：机器码在入口归一到白名单形态；非法值丢弃不报 400（additive、可容错）。
			String uiCode = FrontendToolResultRequest.normalizeCode(request.code());
			if (uiCode != null) {
				submission = this.confirmGate.submitFrontendResult(request.runId(), request.toolCallId(), result,
						request.source(), cancelled, request.outcome(), sheetMeta, uiCode);
			}
			else {
				submission = sheetMeta.isEmpty()
						? this.confirmGate.submitFrontendResult(request.runId(), request.toolCallId(), result,
								request.source(), cancelled, request.outcome())
						: this.confirmGate.submitFrontendResult(request.runId(), request.toolCallId(), result,
								request.source(), cancelled, request.outcome(), sheetMeta);
			}
		}
		finally {
			AiTrace.clear();
		}
		if (submission.outcome() == ConfirmGate.Outcome.NOT_FOUND) {
			return json(HttpStatus.CONFLICT, unknownToolCall(request.runId(), request.toolCallId()));
		}
		if (submission.outcome() == ConfirmGate.Outcome.TOOL_KIND_MISMATCH) {
			// 入口预读不可用时由闸门判定，统一映射为 409。
			return json(HttpStatus.CONFLICT, toolKindMismatch(request.runId(), request.toolCallId(),
					submission.pending(), PendingToolCall.KIND_FRONTEND));
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
		body.put("truncated", truncated);
		if (truncated) {
			body.put(TruncationContract.STRATEGY_FIELD, TruncationContract.STRATEGY_HEAD);
			body.put(TruncationContract.ORIGINAL_CHARS_FIELD, originalChars);
			body.put(TruncationContract.RETURNED_CHARS_FIELD, returnedChars);
			body.put(TruncationContract.NOTICE_FIELD, notice);
		}
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
	 *
	 * <p>
	 * <b>M08 探测</b>：{@code ?probe=1} <b>不</b>走本方法 —— 它由同路径的
	 * {@link #probeEvents} 承接（本方法的 {@code params = "probe!=1"} 明确排除它）。
	 * 两个 mapping 刻意分开：正式通道只产 {@code text/event-stream}、探测只产 {@code application/json}，
	 * 于是"声明 JSON Accept 但没带 probe=1"的请求拿 <b>406</b> 而不是一条真实订阅（订阅 + 回放 + 续期）。
	 */
	@GetMapping(value = "/events/{runId}", params = "probe!=1", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public ResponseEntity<?> events(@PathVariable String runId,
			@RequestParam(required = false) Long lastSeq) {
		RunSnapshot snapshot = this.runStore.get(runId);
		if (snapshot == null) {
			// ===== AR-06：已 start、快照未建 =====
			// 首帧（start）已前移到首轮压缩之前 ⇒ 存在这样一段合法窗口：runId 已有、帧面已开、
			// 快照还没建立。此时重挂<b>不得</b>返回 404 / "轮不存在"（那会把正常压缩显示成失败），
			// 而应返回"准备中"（可重试 + 已用时间）。判定只认<b>归档</b>这一份跨实例事实。
			ResponseEntity<?> early = this.preparingOrUnknown(runId);
			if (early != null) {
				return early;
			}
			// early == null ⇒ 归档里已有终帧（快照被 TTL 先回收）：照常回放，让重挂者拿到终态。
		}
		else {
			// B23L1 批②b：读侧续期（重挂是最强的"有人还在看这张卡"信号；终态/缺失不续期）
			this.runStore.touchActiveRun(snapshot);
			// 同拍续期一次性资源（附件登记）：重挂 = 用户刷新回来，park 期没有心跳
			this.confirmGate.touchSuspendedResources(runId);
		}
		// I-1：回放前先确认事件日志末号可读。末号未知时不得进入"按 0 全量回放"（会拿到最旧窗口、
		// 拿不到 done）；明确回 503 + degraded，让前端下一拍重试。
		if (this.frameStore != null && this.frameStore.lastSeqIfKnown(runId).isEmpty()) {
			Map<String, Object> body = new LinkedHashMap<>(errorBody(ErrorCodes.AI_REDIS_UNAVAILABLE,
					"AI 运行时依赖的 Redis 暂不可用，请稍后重试。"));
			body.put("degraded", true);
			return json(HttpStatus.SERVICE_UNAVAILABLE, body);
		}
		SseEmitter emitter = new SseEmitter(NO_TRANSPORT_DEADLINE);
		SseChatEmitter out = this.runRegistry.of(runId);
		// 快照缺失但归档已有终帧 ⇒ 按终态回放（归档里有没有终帧才决定"回放完收尾"还是"留挂"，
		// 见 SseChatEmitter 的 L-143 口径）。
		int replayed = out.replayAndAttach(emitter, lastSeq,
				snapshot == null || isTerminal(snapshot.getStatus()));
		log.debug("reattach runId={} status={} lastSeq={} 回放帧数={}", runId,
				snapshot == null ? "(无快照)" : snapshot.getStatus(), lastSeq, replayed);
		// park 的挂起轮：回放（含请求帧）完成后主动收流，决策/回灌再触发续跑。
		// 只在<b>确实还在等人</b>（仍有 PENDING 条目）时收流：条目已全部结算 ⇒ 决策/回灌已落库、
		// 续跑在即（快照要等驱动方才能翻 RUNNING），此刻收流会把续跑随后产出的 done/error 挡在
		// 连接之外 —— E2E 的标准时序正是「POST 决策/回灌 200 → 立刻 GET /events 收终帧」，
		// 这条竞态就是 GF4 / W-01..W-22 / H-10 / S-11 / T-13 的"done 缺失"。
		if (snapshot != null && this.properties.getPark().isEnabled()
				&& RunSnapshot.SUSPENDED.equals(snapshot.getStatus())) {
			if (stillWaitingForInput(runId)) {
				out.completeSubscriptions();
			}
			else {
				// ===== 已决但无驱动方的自愈对账（H-01）=====
				// 条目全决 + 快照仍 SUSPENDED ⇒ 决策已落库却没有线程在驱动（park 退出窗口内的决策
				// 曾命中预登记闸门导致无派发；续跑派发也可能失败）。重挂是最可靠的"有人在看"信号，
				// 这里走与死卡兜底同一条判定：本进程无闸门才派发、租约/幂等由既有路径保证 ——
				// 已有驱动方时只会得到 ALREADY_RUNNING / LEASE_HELD_ELSEWHERE 的跳过。
				// 对账要读快照（RunStore.get 在 Redis 故障时抛 AiStateUnavailableException），而此刻
				// 订阅已由 replayAndAttach 挂上、emitter 还没交给容器：异常上抛 = 500，且这条 emitter 的
				// onCompletion/onTimeout/onError 永不触发 ⇒ 订阅留到进程结束（hasLiveSubscriber 常真、
				// recycleIfIdle 永不放行、唤醒/回放继续往死订阅排队）。按 stillWaitingForInput 的同口径
				// 就地降级：记 warn + 收流；不补 error 帧 —— 那是归档里的终态帧，会把这轮后续的重挂回放
				// 当成"已终止"而漏掉真正的 done。
				try {
					ConfirmGate.ResumeTrigger healed = this.confirmGate.triggerResumeForParked(runId);
					log.debug("reattach 已决无驱动方的自愈对账 runId={} outcome={}", runId,
							healed == null ? null : healed.outcome());
				}
				catch (RuntimeException ex) {
					log.warn("reattach 自愈对账失败 runId={}（收流，不泄漏订阅）：{}", runId, ex.getMessage());
					out.completeSubscriptions();
				}
			}
		}
		return ResponseEntity.ok(emitter);
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
				java.util.List.of(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON));
	}

	/** 探测的响应体构造（只读；见 {@link #probeEvents} 的口径）。 */
	private ResponseEntity<?> probe(String runId) {
		RunSnapshot snapshot = this.runStore.get(runId);
		if (snapshot == null) {
			ResponseEntity<?> early = this.preparingOrUnknown(runId);
			if (early != null) {
				return early;
			}
			// early == null ⇒ 归档里已有终帧（快照被 TTL 先回收）：正式通道会回放终态 ⇒ 探测报就绪
		}
		// 与正式通道的 503 同口径：末号读不出来时不冒充"就绪"（下一拍再探）
		FrameStore frames = this.frameStore;
		if (frames != null && frames.lastSeqIfKnown(runId).isEmpty()) {
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
		if (frames == null) {
			// 无归档设施（非 Spring 装配/单测）：没有"已 start"的证据，维持既有 404
			return json(HttpStatus.NOT_FOUND, Map.of("code", ErrorCodes.UNKNOWN_RUN, "runId", runId));
		}
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
		if (this.archiveEndsWithTerminal(frames, runId, last.getAsLong())) {
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
		log.debug("reattach 命中\"已 start、快照未建\"阶段 runId={} elapsedMs={}", runId, elapsedMs);
		return json(HttpStatus.ACCEPTED, body);
	}

	/** "准备中"的用户面短句（双语各一份；不进 messages 键表 —— 它不是错误码）。 */
	private static String preparingMessage() {
		return com.example.configmgr.ai.run.ReplyLanguage.current().english()
				? "The reply is being prepared. Retry shortly."
				: "这一轮正在准备中（压缩上下文），请稍后重试。";
	}

	/** 取消旗标（只用于"准备中"响应体的 additive 键；读不出来按未取消处理，不阻断重挂）。 */
	private boolean isCancelledFlag(String runId) {
		try {
			return this.cancellations.cancelState(runId) == CancellationRegistry.CancelState.CANCELLED;
		}
		catch (RuntimeException ex) {
			log.debug("读取取消旗标失败 runId={}（按未取消继续返回准备中）：{}", runId, ex.getMessage());
			return false;
		}
	}

	/** 归档首帧的 {@code atMs}（"准备中"的已用时间零点）；读不到返回 0。 */
	private long firstArchivedAtMs(FrameStore frames, String runId) {
		try {
			List<Map<String, Object>> head = frames.readFrom(runId, 0L, 1);
			if (head == null || head.isEmpty()) {
				return 0L;
			}
			Object atMs = head.get(0).get("atMs");
			return atMs instanceof Number number ? number.longValue() : 0L;
		}
		catch (RuntimeException ex) {
			log.debug("读取归档首帧失败 runId={}（已用时间按 0 上报）：{}", runId, ex.getMessage());
			return 0L;
		}
	}

	/**
	 * 归档末帧是否终帧（{@code done}/{@code error}）。
	 *
	 * <p>只读末<b>一</b>帧而不是全量扫描（{@code terminalFrameArchived} 会整段读）：终帧之后不得再有
	 * 业务帧是帧序不变量，所以"末帧不是终帧"就等于"没有终帧"。
	 */
	private boolean archiveEndsWithTerminal(FrameStore frames, String runId, long lastSeq) {
		try {
			List<Map<String, Object>> tail = frames.readFrom(runId, lastSeq - 1L, 1);
			if (tail == null || tail.isEmpty()) {
				return false;
			}
			String type = String.valueOf(tail.get(tail.size() - 1).get("type"));
			return "done".equals(type) || "error".equals(type);
		}
		catch (RuntimeException ex) {
			log.debug("读取归档末帧失败 runId={}（按非终态继续判定「准备中」）：{}", runId, ex.getMessage());
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
	private boolean stillWaitingForInput(String runId) {
		try {
			for (PendingToolCall pending : this.runStore.pendings(runId)) {
				if (PendingToolCall.PENDING.equals(pending.getStatus())) {
					return true;
				}
			}
			return false;
		}
		catch (RuntimeException ex) {
			log.warn("reattach 读取待决条目失败 runId={}（按 park 收流，不悬挂连接）：{}", runId, ex.getMessage());
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
			com.example.configmgr.ai.run.LeaseService leases = this.runStore.leaseService();
			leaseHeld = leases != null && leases.isHeld(this.runStore.runLeaseKey(heldRunId));
		}
		catch (Exception ex) {
			// 租约设施不可读 ⇒ 按"可能有人持有"处理（不放行），与 E3 的保守方向相反是因为
			// 这里的错误方向是并发双跑，不能靠乐观兜底
			log.debug("读取续跑租约失败 runId={}：{}", heldRunId, ex.getMessage());
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
		// park 的挂起轮没有等待线程：取消必须触发续跑收敛，而不是只留一个取消标志。
		if (this.properties.getPark().isEnabled() && snapshot != null
				&& RunSnapshot.SUSPENDED.equals(snapshot.getStatus())) {
			ConfirmGate.ResumeTrigger parked = this.confirmGate.triggerResumeForParked(runId);
			body.put("parkedResume", parked == null ? null : parked.outcome());
			body.put("note", "取消以 Redis 标志为准；挂起中的轮已触发一次收敛派发（parkedResume）");
		}
		else {
			body.put("note", "取消以 Redis 标志为准；运行中的轮次会在事件边界以 done{cancelled:true} 收尾");
		}
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
		if (this.frameStore == null) {
			readable = false;
		}
		else {
			try {
				FrameStore.ReadResult read = this.frameStore.readFromWithStatus(runId, 0L, 1);
				readable = !read.degraded();
				List<Map<String, Object>> frames = read.frames();
				if (frames != null && !frames.isEmpty()) {
					Map<String, Object> first = frames.get(0);
					if (first.get("seq") instanceof Number number) {
						firstSeq = number.longValue();
					}
					gap = !"start".equals(String.valueOf(first.get("type")));
				}
			}
			catch (RuntimeException ex) {
				readable = false;
				log.debug("归档完整性探测失败 runId={}（按不可证明完整处理）：{}", runId, ex.getMessage());
			}
		}
		body.put("archiveFirstSeq", firstSeq);
		body.put("archiveReadable", readable);
		body.put("archiveGap", gap);
		body.put("recoverable", readable && !gap ? "complete" : "partial");
	}

	/**
	 * <b>仅模型步重跑</b>（AR-05 / 批次2）：{@code POST /api/ai/runs/{runId}/retry-model-step}。
	 *
	 * <h2>契约（前端可直接用）</h2>
	 * <ul>
	 * <li><b>202</b>：已受理 —— {@code {runId, sessionId, retried:true, modelStepOnly:true,
	 *     reason:"MODEL_STEP_RETRY_ACCEPTED", reusedToolLedger:true, appendedUserMessages:0,
	 *     promptMessageCount, promptRoles, retryOfStatus:"FAILED"}}。前端接着走既有
	 *     {@code GET /api/ai/events/{runId}?lastSeq=} 重挂即可（帧流与续跑同一条）；
	 *     <b>不要</b>再追加一条用户消息，也<b>不要</b>把它当成"重试本轮 = 重发消息"；</li>
	 * <li><b>200</b>：已收尾/已取消（幂等空操作，{@code retried:false}）；</li>
	 * <li><b>409</b>：在途（{@code LEASE_HELD_ELSEWHERE} / {@code RESUME_IN_PROGRESS} /
	 *     {@code ALREADY_RUNNING}），或前置失败
	 *     （{@code reason:"RETRY_PRECONDITION_FAILED", code:"ILLEGAL_STATE",
	 *     failedCheck:"NOT_FAILED|CANCELLED|PENDING_TOOLS|PARTIAL_CONTENT|TOOL_PAIRING_INCOMPLETE|MODEL_CHANGED"}）
	 *     —— {@code PARTIAL_CONTENT} 表示"状态未确认"，前端应给中性视图 + 新开一轮，而不是重跑；</li>
	 * <li><b>404</b>：轮次不存在；<b>503</b>：AI/Redis 不可用（可重试）。</li>
	 * </ul>
	 *
	 * <p>语义边界（AR-05）：<b>不</b>做"半截自动替换"，<b>不</b>重执行已结算工具，
	 * <b>不</b>追加用户消息，<b>不</b>换模型，<b>不</b>改历史切片。
	 */
	@PostMapping(value = "/runs/{runId}/retry-model-step", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<?> retryModelStep(@PathVariable String runId) {
		ResumeService service = this.resumeService.getIfAvailable();
		if (!this.availability.isAvailable() || service == null) {
			return json(HttpStatus.SERVICE_UNAVAILABLE, unavailable());
		}
		if (!this.redisAvailability.isAvailable()) {
			return json(HttpStatus.SERVICE_UNAVAILABLE, redisUnavailable());
		}
		ResumeService.ResumeResult result = service.retryModelStep(runId, ErrorBodyFactory.requestLocale());
		return switch (result.outcome()) {
			case ACCEPTED -> ResponseEntity.accepted().contentType(MediaType.APPLICATION_JSON).body(result.body());
			case ALREADY_DONE, ALREADY_CANCELLED ->
				ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(result.body());
			case NOT_FOUND -> json(HttpStatus.NOT_FOUND, result.body());
			case POOL_SATURATED, CANCEL_STATE_UNKNOWN, STATE_UNKNOWN ->
				json(HttpStatus.SERVICE_UNAVAILABLE, result.body());
			default -> json(HttpStatus.CONFLICT, result.body());
		};
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
		if (!StringUtils.hasText(sessionId)) {
			return json(HttpStatus.BAD_REQUEST, errorBody(ErrorCodes.BAD_REQUEST, "sessionId 为必填"));
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
		// 同拍续期一次性资源（附件登记）：刷新恢复是 park 期最强的"有人还在看"信号
		this.confirmGate.touchSuspendedResources(runId);
		// AR-10（批次3 补齐）：24h 只可见化 —— 快照键的**权威**剩余寿命，用 PTTL 只读推导。
		// 不改 TTL、不续期、不延长不缩短、不新增持久化；量在 touch 之后取，反映本次响应后的真实寿命。
		// null = 未知（键刚过期 / 读失败 / 无 TTL）：前端按"不显示时间"处理，不得折算成 0。
		Long snapshotRemainingMs = this.runStore.runKeyTtlRemainingMs(runId);
		Long snapshotExpiresAt = snapshotRemainingMs == null ? null
				: Long.valueOf(System.currentTimeMillis() + snapshotRemainingMs);
		body.put("snapshotRemainingMs", snapshotRemainingMs);
		body.put("snapshotExpiresAt", snapshotExpiresAt);
		// C2：展示/恢复面用非抛读口（guard 读失败不能把整页打成 500）。
		List<PendingToolCall> pendings = this.runStore.pendingsLenient(runId);
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
			// 批4-D（用户 2026-10-23 裁决）：不可撤销位随条目透出 —— 刷新恢复要据此重建复选框
			// （否则刷新后"一点即发"，复选框消失 = 安全回退）。恒给布尔值（false = 不加复选框）。
			entry.put("irreversible", pending.isIrreversible());
			pendingEntries.add(entry);
			if (PendingToolCall.PENDING.equals(pending.getStatus())
					&& !PendingToolCall.KIND_BACKEND.equals(pending.getKind())) {
				unresolvedExternal.add(pending.getToolCallId());
			}
		}
		body.put("pendingEntries", pendingEntries);
		body.put("unresolvedExternal", unresolvedExternal);
		body.put("awaitingExternal", RunSnapshot.SUSPENDED.equals(snapshot.getStatus()) && !unresolvedExternal.isEmpty());
		Long maxSeq = archiveMaxSeq(runId);
		if (maxSeq == null) {
			// I-1：末号读失败不得回 0（前端会把 0 当全量回放锚点）；503 + degraded 让前端重试。
			Map<String, Object> error = new LinkedHashMap<>(errorBody(ErrorCodes.AI_REDIS_UNAVAILABLE,
					"AI 运行时依赖的 Redis 暂不可用，请稍后重试。"));
			error.put("degraded", true);
			return json(HttpStatus.SERVICE_UNAVAILABLE, error);
		}
		body.put("archiveMaxSeq", maxSeq);
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
	private Long archiveMaxSeq(String runId) {
		FrameStore frames = this.frameStore;
		if (frames == null) {
			return this.runStore.lastEventSeq(runId);
		}
		OptionalLong known = frames.lastSeqIfKnown(runId);
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
		Object[] fields = new Object[extra.length + 4];
		fields[0] = "status";
		fields[1] = status.value();
		fields[2] = "code";
		fields[3] = code;
		System.arraycopy(extra, 0, fields, 4, extra.length);
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
	 * 429 {@code RATE_LIMITED}（批① 闸门 3）。带 {@code Retry-After}（秒，向上取整）——
	 * 客户端据此退避，而不是盲目重试把窗口继续打满。
	 *
	 * <p>A3-Y7 / I-1：message 走 {@code localize()}；退避秒数、维度、用量都在结构字段里。
	 */
	private ResponseEntity<Map<String, Object>> rateLimited(AiRateLimiter.Decision decision) {
		String scopeName = AiRateLimiter.SCOPE_SESSION.equals(decision.scope()) ? "该会话" : "当前来源";
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("code", ErrorCodes.RATE_LIMITED);
		body.put("message", localize(ErrorCodes.RATE_LIMITED,
				scopeName + "对话请求过于频繁：每 " + decision.limit() + " 次/分钟（"
						+ decision.scope() + " 维度）已达上限，请约 " + decision.retryAfterSeconds() + " 秒后重试"));
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
	 * 限流用的来源 IP：交给 {@link com.example.configmgr.config.ClientIpResolver}（多实例专项）。
	 *
	 * <p>口径未变的安全前提：**只有 RemoteAddr 落在 {@code app.web.trusted-proxies} 内才看
	 * {@code X-Forwarded-For}**（默认网段为空 ⇒ 只回 RemoteAddr，与改造前逐字一致）。
	 * 无条件读 XFF 等于把"算谁的额度"交给请求方自己声明；而完全读不到 XFF，则 LB 后的
	 * 全站用户会共用同一个 {@code ai:rate:ip:<LB-IP>} 桶 —— 一个用户刷满 60/分即全员 429。
	 *
	 * <p>未装配 resolver（standalone 单测直接 {@code new AiController(...)}）时回落 RemoteAddr。
	 */
	private String clientIp(HttpServletRequest httpRequest) {
		if (httpRequest == null) {
			return "unknown";
		}
		if (this.clientIpResolver != null) {
			return this.clientIpResolver.resolve(httpRequest);
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

	/**
	 * 完整对话历史（第二阶段新增；<b>不改变</b>上面 40 条记忆窗口端点的语义）。
	 *
	 * <p>游标分页：
	 * <ul>
	 * <li>{@code beforeSeq}：向更早翻，返回 seq 倒序（新→旧）；不传 = 从最新开始；</li>
	 * <li>{@code afterSeq}：向更新翻，返回 seq 正序（旧→新）；不传/0 = 从第一条开始；</li>
	 * <li>两者只能二选一；{@code limit} 1..200，默认 50。</li>
	 * </ul>
	 * <p>条目复用现有 {@code /history} 的 {@code toHistoryItem}（同一份文本/工具信封/视图口径），
	 * 额外带 {@code seq/runId/messageId/createdAt} 便于前端做稳定 key 与增量合并。
	 */
	@GetMapping("/history/{sessionId}/messages")
	public ResponseEntity<?> fullHistory(@PathVariable String sessionId,
			@RequestParam(name = "beforeSeq", required = false) Long beforeSeq,
			@RequestParam(name = "afterSeq", required = false) Long afterSeq,
			@RequestParam(name = "limit", required = false) Integer limit) {
		if (this.aiChatHistoryService == null) {
			return json(HttpStatus.SERVICE_UNAVAILABLE,
					errorBody(ErrorCodes.AI_CONTEXT_UNAVAILABLE, "完整历史存储未装配"));
		}
		if (beforeSeq != null && afterSeq != null) {
			throw new IllegalArgumentException("beforeSeq 与 afterSeq 只能二选一");
		}
		int size = limit == null ? AiChatHistoryService.DEFAULT_LIMIT : limit;
		ChatPage page = afterSeq != null
				? this.aiChatHistoryService.pageAfter(sessionId, afterSeq, size)
				: this.aiChatHistoryService.pageBefore(sessionId, beforeSeq, size);
		List<Map<String, Object>> messages = new ArrayList<>(page.items().size());
		for (MessageView view : page.items()) {
			Message message = this.aiChatHistoryService.toMessage(view);
			Map<String, Object> item = toHistoryItem(message);
			item.put("seq", view.seq());
			item.put("runId", view.runId());
			item.put("messageId", view.messageId());
			if (!item.containsKey("stopped") && view.stopped()) {
				item.put("stopped", true);
			}
			if (!item.containsKey("timestamp") && view.createdAt() != null) {
				item.put("timestamp", view.createdAt().toString());
			}
			if (view.createdAt() != null) {
				item.put("createdAt", view.createdAt().toString());
			}
			messages.add(item);
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("sessionId", sessionId);
		body.put("count", messages.size());
		body.put("messages", messages);
		body.put("hasMore", page.hasMore());
		body.put("nextCursor", page.nextCursor());
		body.put("direction", afterSeq != null ? "after" : "before");
		body.put("beforeSeq", beforeSeq);
		body.put("afterSeq", afterSeq);
		return ResponseEntity.ok(ApiResponse.ok(body));
	}

	/**
	 * R-154：把单条 {@link Message} 拼成 history 载荷条目（R-144 携 toolCallStates，纯附加）。
	 *
	 * <p><b>批次2 / AR-01 第 5 条：synthetic 展示过滤（读侧）</b> —— 合成/系统提示（上下文压缩摘要、
	 * 步数收尾、轮内预算收尾）在记忆里是真实消息，但对人不是"用户原话"也不是"助手答复"。
	 * 判据复用 {@link SyntheticMessages#isSynthetic}（<b>与写侧同一份</b>，不许各写一套），
	 * 命中即加 additive 键 {@code synthetic=true}：前端据此按系统消息呈现，
	 * <b>摘要不冒充用户</b>、不显示内部配置键；正文与角色字段一律原样（不改写、不改模型记忆）。
	 *
	 * <p><b>AR-12（只读展示）</b> —— assistant 条目已有的 {@code metadata.reasoningContent}
	 * 以 additive 可选键 {@code reasoningContent} 原样透出（记忆窗口与完整历史两个通道同源）；
	 * <b>不新增表/列/持久化</b>，也不透出 {@code startedAt}/{@code endedAt} 等计时。
	 * synthetic 条目一律跳过本键：那是系统提示，不能借"思考正文"把内部元数据带上用户面。
	 */
	private Map<String, Object> toHistoryItem(Message message) {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("type", message.getMessageType().name());
		item.put("text", message.getText());
		boolean synthetic = SyntheticMessages.isSynthetic(message);
		if (synthetic) {
			item.put(SyntheticMessages.DISPLAY_KEY, true);
		}
		// AR-12（只读展示）：历史 assistant 已有的思考正文只从 metadata.reasoningContent 原样透出，
		// 不新增表/列/持久化，也不透出 startedAt/endedAt 等计时（历史已完成轮不显示耗时、不回算）。
		// 展示开关（app.ai.reasoning.display-enabled，默认 false）关掉时历史不透出该键；metadata 仍照旧落库。
		// synthetic 压缩/收尾条目一律跳过：那是系统提示，不是助手答复，不能借本字段带出内部键。
		if (!synthetic && this.properties.getReasoning().isDisplayEnabled()
				&& message instanceof AssistantMessage reasoningAssistant) {
			Object reasoning = reasoningAssistant.getMetadata().get(ResilientChatService.REASONING_METADATA_KEY);
			if (reasoning instanceof String reasoningText && !reasoningText.isBlank()) {
				item.put(ResilientChatService.REASONING_METADATA_KEY, reasoningText);
			}
		}
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
		// 批G · S4：被用户停止的半截 assistant 正文带 stopped 标记 —— 前端据此渲染「已停止」，
		// 而不是把半句话当完整答案（与取消轮 done{cancelled} 的实时口径同源）。
		if (message instanceof AssistantMessage assistant
				&& Boolean.TRUE.equals(assistant.getMetadata().get(ResilientChatService.STOPPED_KEY))) {
			item.put("stopped", true);
		}
		if (message instanceof AssistantMessage assistant
				&& Boolean.TRUE.equals(assistant.getMetadata().get(ResilientChatService.TRUNCATED_KEY))) {
			item.put(ResilientChatService.TRUNCATED_KEY, true);
		}
		Object timestamp = message.getMetadata().get(MessageJsonCodec.FIELD_TIMESTAMP);
		if (timestamp != null) {
			item.put("timestamp", timestamp);
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
	 * 命中摘要时以同名 {@code userTextCode} 透出原码供前端本地化；旧数据（不是信封）原样透传，
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
			if (truncation != null) {
				item.putAll(truncation);
			}
			Map<String, Object> view = historyResultView(response);
			if (view != null) {
				item.put("resultView", view);
			}
			out.add(item);
		}
		return out;
	}

	/** 信封里的机器可读码（旧数据/非信封为 null）；只用于选择用户摘要，不改写原文。 */
	private String envelopeCode(String responseData) {
		String json = ToolResultEnvelope.unwrapModelJson(responseData);
		if (json == null || !json.trim().startsWith("{")) {
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
			log.debug("历史工具结果信封码解析失败，按无码透传：{}", ex.getMessage());
			return null;
		}
	}

	/**
	 * 历史通道的截断元数据（A10 R-15）：从持久化的工具结果信封里取回
	 * {@code truncated/strategy/originalChars/returnedChars/notice}，并映射成前端段字段
	 * （{@code noticeCode=model_view_truncated}、{@code truncatedReason=notice} 兼容旧客户端）。
	 *
	 * <p>为什么读信封而不是另存一份：模型侧裁剪就发生在
	 * {@code ToolResultEnvelope#toModelJson}，信封是这份事实的唯一持久来源；实时帧的截断字段
	 * 也由同一个 limiter 现算（{@code SseChatEmitter#markModelView}），两侧因此同源。
	 *
	 * <p>只对 {@code truncated=true} 出键：未截断/旧数据（无信封）保持载荷形状逐字不变。
	 * 信封可能带模型侧定界外壳（{@code <tool_result>}），先剥壳再解析。
	 */
	private Map<String, Object> historyTruncation(String responseData) {
		if (responseData == null || responseData.isBlank()) {
			return null;
		}
		try {
			String json = ToolResultEnvelope.unwrapModelJson(responseData);
			if (json == null || !json.trim().startsWith("{")) {
				return null;
			}
			JsonNode node = this.objectMapper.readTree(json);
			if (node == null || !node.isObject() || !isToolResultEnvelope(node)
					|| !node.path(TruncationContract.TRUNCATED_FIELD).asBoolean(false)) {
				return null;
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
			JsonNode notice = node.get(TruncationContract.NOTICE_FIELD);
			if (notice != null && notice.isTextual()) {
				fields.put(TruncationContract.LEGACY_REASON_FIELD, notice.asText());
			}
			return fields;
		}
		catch (Exception ex) {
			// 展示增强不得让 /history 500：解析失败按"无截断元数据"处理（旧载荷语义）
			log.debug("历史工具结果截断元数据解析失败，按无字段透传：{}", ex.getMessage());
			return null;
		}
	}

	/**
	 * L-78：历史面的结果视图（按工具名从注册表重新派生；未声明投影的工具不产该键）。
	 *
	 * <p>三条口径与帧路径（`SpToolCallingManager#resultViewOf`）逐条对齐：
	 * ① 只对**成功结局**出视图 —— 失败/取消的卡面本来就该走旧形态（"视图是成功结果的投影"）；
	 * ② 认领按工具名，投影器异常一律吞掉（展示增强不得让 {@code /history} 500）；
	 * ③ 返回 {@code null} 时不写键 ⇒ 旧载荷形状逐字不变。
	 *
	 * <p><b>与帧路径唯一的差别</b>（Task 6 / BZ-33）：这里显式标 {@code historical=true}。
	 * 归档是"重放"，不允许再查一次业务库 —— 否则刷新后卡面显示的是今天的任务/数据，
	 * 与同轮 assistant 文本和历史"共 N 条"口径自相矛盾（旧实现每次 {@code /history} 都为每条历史
	 * {@code list_tasks} 重跑一次 {@code taskService.findAll()}）。
	 */
	private Map<String, Object> historyResultView(ToolResponseMessage.ToolResponse response) {
		if (this.resultViewProviders.isEmpty() || response.name() == null || response.id() == null) {
			return null;
		}
		if (!historyOutcomeOk(response.responseData())) {
			return null;
		}
		String resultText = historyMessageText(response.responseData());
		for (com.example.configmgr.ai.resultview.ToolResultViewProvider provider : this.resultViewProviders) {
			if (!provider.supports(response.name())) {
				continue;
			}
			try {
				// Task 6 / BZ-33：历史是"重放"，不是"重新查库"。投影器据此改用那次调用的结果原文，
				// 不得用当前业务库改写归档卡面（旧实现每次 /history 都重跑 taskService.findAll()）。
				com.example.configmgr.ai.resultview.ResultView view = provider.build(response.id(),
						response.name(), resultText, true);
				return view == null ? null : view.toMap();
			}
			catch (RuntimeException ex) {
				log.warn("历史 resultView 构造失败（本次不出视图，卡面走旧形态）：tool={} toolCallId={}",
						response.name(), response.id(), ex);
				return null;
			}
		}
		return null;
	}

	/**
	 * 历史响应的结局是否成功：信封带 {@code ok=false} ⇒ 否；旧数据（非信封 / 无该键）⇒ 是
	 * （不是信封就不存在"失败结局"的判据，与帧路径"视图只是展示增强"的取向一致）。
	 */
	private boolean historyOutcomeOk(String responseData) {
		String json = ToolResultEnvelope.unwrapModelJson(responseData);
		if (json == null || !json.trim().startsWith("{")) {
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

	/** 信封 JSON → {@code message} 原文；识别不了（旧数据/普通 JSON 结果）一律原样返回。 */
	private String unwrapEnvelope(String responseData) {
		if (responseData == null || !responseData.trim().startsWith("{")) {
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
			log.debug("历史工具结果不是可解析的信封 JSON，按原文透传：{}", ex.getMessage());
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
			return json(HttpStatus.BAD_REQUEST, errorBody(ErrorCodes.BAD_REQUEST, "sessionId 必填"));
		}
		if (!this.redisAvailability.isAvailable()) {
			return json(HttpStatus.SERVICE_UNAVAILABLE, redisUnavailable());
		}
		if (this.contextBreakdownService == null) {
			// R-16：用户面文案不得出现 Java 类名（"ContextBreakdownService 缺失"这类内部实现细节
			// 只留在日志里）；message 与其它 503 同一本地化出口。
			log.warn("/context 计量未装配 sessionId={}：ContextBreakdownService 缺失", sessionId);
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
		// 批③（additive）：面板据此决定参数 popover 是否可用、初值取什么。
		// allowedParams = ai_setting['allow.userParams']（缺省 true，与设置页同一口径）；
		// defaultParams = 全局默认三值（null = 该项未设置，前端留空）。
		AiConfigService.Defaults defaults = this.aiConfigService == null ? null : this.aiConfigService.defaults();
		body.put("allowedParams", defaults == null || !Boolean.FALSE.equals(defaults.allowUserParams()));
		Map<String, Object> defaultParams = new LinkedHashMap<>();
		defaultParams.put("temperature", defaults == null ? null : defaults.temperature());
		defaultParams.put("topP", defaults == null ? null : defaults.topP());
		defaultParams.put("maxTokens", defaults == null ? null : defaults.maxTokens());
		body.put("defaultParams", defaultParams);
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
		// RT-34 的聚合面（additive）：续期累计丢锁 + 当前仍降级的持有者。两条来源都必须算进来 ——
		// 取门时 Redis 不可用只置 holder.degraded、**不**累加 lockLost，只看计数会把那次降级漏报。
		body.put("sessionLockLost", this.sessionGate.lockLost());
		body.put("sessionLockDegraded", this.sessionGate.lockLost() > 0 || this.sessionGate.degradedSessions() > 0);

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
		// 防护批③ B3-6（additive）：安全事件计数 —— 进程内口径，见 SecurityEvents 类注。
		// 未注入时<b>不写</b>该键（"未装配"与"全 0"必须可区分，与 contextLimitTokens 同姿态）。
		if (this.securityEvents != null) {
			body.put("security", this.securityEvents.metrics());
		}
		// AI 表格工具治理批（additive）：表格开关的<b>有效值</b> + 进程内计数。
		// 为什么必须回显有效值而不是配置原值：前端据此收紧采样/投影上界（min(键值, 硬顶)），
		// 若这里报"配置写 200"而实际生效 20，前端就会以为还能取 200 行（配置面对运行面说谎）。
		// 未装配时不写该键（"未装配"与"全 0"必须可区分，与 security/contextLimitTokens 同姿态）。
		if (this.sheetSwitches != null) {
			Map<String, Object> sheet = new LinkedHashMap<>(this.sheetSwitches.health());
			if (this.sheetAudit != null) {
				sheet.put("counters", this.sheetAudit.metrics());
			}
			body.put("sheet", sheet);
		}
		if (!this.availability.isAvailable()) {
			// I-2 / 4B：/health 的 503 message 与 /chat 同一本地化出口 —— zh 由 ErrorBodyFactory
			// 直接返回 fallback（= availability.reason() 原文，逐字不变），en 取 messages_en。
			body.put("message", localize(availabilityCode,
					this.availability.reason() == null ? "" : this.availability.reason()));
			// I-1：变量名/机制词只留在 /health 诊断面，不进任何用户面 message/卡片/横幅。
			Map<String, Object> diagnostics = keyDiagnostics(availabilityCode);
			if (!diagnostics.isEmpty()) {
				body.put("keyDiagnostics", diagnostics);
			}
			return json(HttpStatus.SERVICE_UNAVAILABLE, body);
		}
		return ResponseEntity.ok(body);
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
		body.put("message", localize(code, reason == null ? "" : reason));
		return body;
	}

	/**
	 * R4：Redis 不可用的 503 体（带探活事实，便于前端与排障区分"没 key"与"没 Redis"）。
	 *
	 * <p>I-1：用户面的 {@code message} 走 {@code localize()}（{@code AI_REDIS_UNAVAILABLE} 已有 en 文案）；
	 * {@code redis} 子对象是**安全事实面**（available/probes/failures/时间锚点）。R-11 残留收口：
	 * 这里<b>不再带</b> {@code lastError}（含异常类名/连接地址）—— 原始诊断只进 WARN 日志与
	 * {@code /health} 的 {@code redis.lastError}。
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

	/** 错入口条目；旧 kind=null 数据放行，未知条目交给闸门回 UNKNOWN。 */
	private static PendingToolCall wrongKindEntry(PendingToolCall pending, String expectedKind) {
		if (pending == null || !pending.hasKind() || pending.isKind(expectedKind)) {
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
	 * <p>判定本体见 {@link FrontendToolGuard#structuredResult}：表格写四件按工具名，其余按
	 * "能解析成 JSON 对象/数组"的形状。普通超长文本（含以 <code>{</code> 开头但不是 JSON 的）
	 * 仍走既有的截断 + 标记路径。
	 *
	 * <p>读条目的是<b>非抛读口</b>（{@code pendingLenient}）：Redis 抖动时退回"只看形状"的旧路径，
	 * 不把一次读失败升级成回灌失败（真正写路径上的 fail-closed 仍在确认门里）。
	 */
	private boolean structuredOversize(PendingToolCall pending, String result) {
		if (pending == null || !PendingToolCall.KIND_FRONTEND.equals(pending.getKind())
				|| !PendingToolCall.PENDING.equals(pending.getStatus())) {
			return false;
		}
		return FrontendToolGuard.structuredResult(pending.getName(), this.objectMapper, result);
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
		body.put("retryable", verdict.retryable());
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
		List<Map<String, Object>> items = new ArrayList<>();
		for (PendingToolCall pending : this.runStore.pendingsLenient(runId)) {
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
					errorBody(ErrorCodes.BAD_REQUEST, "messageId/sessionId 必填，rating ∈ 1/-1/0"));
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
			// 防护批③ B3-8（L-99 裁定下保留）：摘录<b>永久入库</b>（H2），误伤不可逆 ⇒ 命中高置信凭据
			// 即整条替换为 [已隐去]，而不是逐处脱敏（逐处脱敏会把"这条曾被脱敏"的事实一起模糊掉）。
			// 判据来自 {@link ExcerptGuard}：它不是 DLP 管道的一部分，只服务这一个写点（见类注）。
			String safeExcerpt = ExcerptGuard.sensitive(excerpt) ? "[已隐去]" : excerpt;
			entry.setMessageExcerpt(safeExcerpt.length() > 200 ? safeExcerpt.substring(0, 200) : safeExcerpt);
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
