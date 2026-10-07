package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiKeyPresentCondition;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ContextBuilder;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.web.AiChatRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;

/**
 * 单轮对话编排 + 韧性（S4.2 §2 {@code run/ResilientChatService}，本棒由 {@code AiChatService}
 * 更名演进而来 —— N1 裁决：韧性语义落在同一落点上，不另起一个平行的服务）。
 *
 * <h2>本棒新增的韧性四件套（移植自 SP-01d）</h2>
 * <ol>
 * <li><b>每轮/每次尝试重建请求</b>：尝试之间不复制任何"上一次的请求对象"——
 * {@code attemptStream} 每次被调用都重新组装（ChatClient 路径重读记忆窗口、重挂工具子集），
 * 因此重试不会把上一次的残留状态带进去；</li>
 * <li><b>双超时 + 总预算</b>（{@code app.ai.resilience.first-byte-timeout / inter-event-timeout /
 * total-budget}）：判定在 {@link StreamWatchdog}，首包与事件间分别计时；</li>
 * <li><b>重试三条件</b>：次数未尽 <b>且</b> 预算未耗尽 <b>且</b> 本次尝试"未产出内容
 * 且无副作用"（副作用 = 本次尝试内工具开始执行过 {@link ToolActivityBeacon}，或台账在本次尝试
 * 之后新增了执行记录 —— 后者覆盖"执行完成、结果还没回填"的跨进程窗口）；</li>
 * <li><b>完成帧校验</b>：流"正常"结束却从未出现终帧（带 {@code finishReason} 或 usage 的响应）
 * = 断流（{@code UPSTREAM_STREAM_INCOMPLETE}）—— TCP 静默 FIN 的典型形态。</li>
 * </ol>
 *
 * <h2>两条硬规范（本棒的落地方式）</h2>
 * <ul>
 * <li><b>超时阈值 &gt; 最长工具耗时</b>：看门狗在工具执行期间（{@link ToolActivityBeacon#isActive}）
 * <b>完全不计静默</b>，工具退出后以退出时刻重新计时。配置值本身（默认 90s）可以小于挂起上限
 * （默认 120s）而仍然正确 —— 但"配置值应当直接大于最长工具耗时"这一更朴素的口径
 * 仍列为【待裁决】（见证据文档遗留项）。</li>
 * <li><b>TCP 静默 FIN 按断流处理</b>：见上第 4 点；连接被重置/过早关闭（premature close /
 * reset / EOF）走异常路径，归为 {@code UPSTREAM_CONNECTION_BROKEN}，两者都进同一套重试判定。</li>
 * </ul>
 *
 * <h2>取消（ADR-8 修正③）</h2>
 * 权威态在 Redis（{@link CancellationRegistry#CANCEL_PREFIX}），进程内监听者只加速。
 * 取消的终态是 {@code done{cancelled:true}}：不再重试、不再向上游要一句话，
 * 快照转 {@link RunSnapshot#CANCELLED}，并把"已由用户取消"记进 pending（防止跨进程续跑把它执行掉）。
 *
 * <h2>Q13 一致性风险点（规格 §3 末段）</h2>
 * 记忆窗口（{@code chat:mem:}）与挂起快照（{@code ai:run:}）是两套存储。本类以
 * <b>RunStore 快照为准</b>重建记忆窗口：落定时把快照历史去掉 system 提示后整体覆盖写回，
 * 并做<b>配对保护</b>裁剪（窗口首条不得是孤立的 {@code TOOL}），使"含挂起轮的 tool 配对"
 * 既进快照也进记忆，两边收敛（SM-01）。
 */
@Slf4j
@Service
@Conditional(AiKeyPresentCondition.class)
public class ResilientChatService {

	/** 流式活动心跳的最小间隔（毫秒）：僵尸判据要"有活动痕迹"，但不能把帧刷爆。 */
	public static final long STREAMING_HEARTBEAT_MILLIS = 5000L;

	private static final String SYSTEM_PROMPT = """
			你是一个专业的配置管理 AI 助手，帮助用户完成配置数据的导出、导入和管理工作。

			你的能力：
			- 帮助用户创建和管理快速实施任务（导出/导入配置）
			- 查询配置定义和字段信息
			- 设置查询条件、选择配置项
			- 启动导出、预检查、导入等作业并监控进度
			- 解释检查结果、解答业务疑问

			工作原则：
			1. 始终使用中文回复
			2. 用户明确要求执行某个操作时，直接调用对应工具完成任务，
			   不要只在对话里描述步骤
			3. 你只能使用系统当前披露给你的工具；没有对应工具时，说明原因并给出下一步建议
			4. 工具调用失败时给出清晰的错误说明和建议
			5. 若某个操作被用户拒绝、确认超时或未执行，如实说明，禁止宣称已执行
			6. 主动提示用户下一步可以做什么
			7. 用户要求导出/导入某个配置时，直接驱动工作区完成向导：先打开对应向导，
			   再勾选配置项、按需设置查询条件、逐步推进步骤（这些能力只在任务中心与
			   对应向导中披露）；不要停在只读查询上让用户自己一步步点
			8. 任务中心没有任何任务、或用户要求"新建一个任务"时，用 create_task 建任务
			   （它返回任务 id），再用 navigate_to(page, taskId) 打开该任务的向导继续，
			   不要要求用户自己去界面上创建
			9. 启动导出/检查/导入/发布都会在界面渲染确认卡片，等用户点「确认执行」后才真正执行：
			   发起后如实告诉用户"请在确认卡片上确认"，不要重复发起同一个调用，也不要预先宣称已启动
			""";

	private final ChatClient chatClient;

	private final ChatModel chatModel;

	private final ChatMemory chatMemory;

	private final MessageJsonCodec codec;

	private final RunStore store;

	private final RunRegistry registry;

	private final ToolRegistry toolRegistry;

	private final ContextBuilder contextBuilder;

	private final AiSessionService sessionService;

	private final AiProperties properties;

	private final CancellationRegistry cancellations;

	private final ToolActivityBeacon beacon;

	private final ScheduledExecutorService watchdogExecutor;

	public ResilientChatService(ChatClient chatClient, ChatModel chatModel, ChatMemory chatMemory,
			MessageJsonCodec codec, RunStore store, RunRegistry registry, ToolRegistry toolRegistry,
			ContextBuilder contextBuilder, AiSessionService sessionService, AiProperties properties,
			CancellationRegistry cancellations, ToolActivityBeacon beacon,
			@Qualifier("aiStreamWatchdog") ScheduledExecutorService watchdogExecutor) {
		this.chatClient = chatClient;
		this.chatModel = chatModel;
		this.chatMemory = chatMemory;
		this.codec = codec;
		this.store = store;
		this.registry = registry;
		this.toolRegistry = toolRegistry;
		this.contextBuilder = contextBuilder;
		this.sessionService = sessionService;
		this.properties = properties;
		this.cancellations = cancellations;
		this.beacon = beacon;
		this.watchdogExecutor = watchdogExecutor;
	}

	// ── 首轮：ChatClient + 记忆 Advisor 路径 ────────────────────────────────

	public void chat(String runId, AiChatRequest request, SseChatEmitter out) {
		String sessionId = request.sessionId();
		AiContext context = toContext(request.context());
		boolean existed = this.sessionService.ensure(sessionId);
		long turnStartedAtMs = System.currentTimeMillis();

		List<Message> memory = this.chatMemory.get(sessionId);
		// 人设与工作区快照合成"一条"系统消息：官方 ChatClient 的 system 文本是单槽位
		// （Spec 内部 systemTextParams 只有一个 key），连续两次 .system() 会覆盖前者 ——
		// 实测：只发第二条时，主提示词整段消失（快照里 system 只剩工作区快照）。
		String systemText = SYSTEM_PROMPT + "\n\n" + this.contextBuilder.buildContextMessage(context);
		List<Message> instructions = List.of(new SystemMessage(systemText), new UserMessage(request.message()));

		// 与官方 MessageChatMemoryAdvisor.before 的变换逐字对齐（1.1.8 字节码实测）：
		// [首个 SystemMessage] + 记忆窗口 + 其余指令 —— 快照里存的就是模型真正看到的那串消息。
		List<Message> full = new ArrayList<>(memory.size() + instructions.size());
		full.add(instructions.get(0));
		full.addAll(memory);
		full.add(instructions.get(1));
		this.store.create(runId, sessionId, context, full, this.codec);

		this.cancellations.register(runId);
		out.start(sessionId);

		List<ToolCallback> tools = this.toolRegistry.forContext(context);
		log.debug("AI 轮次开始 runId={} sessionId={} 会话已存在={} 上下文={} 披露工具={} 首包超时={} 包间超时={} 最大尝试={} 总预算={}",
				runId, sessionId, existed, context.getContextKey(), tools.stream().map(t -> t.getToolDefinition().name()).toList(),
				this.properties.getResilience().getFirstByteTimeout(), this.properties.getResilience().getInterEventTimeout(),
				this.properties.getResilience().getMaxAttempts(), this.properties.getResilience().getTotalBudget());

		try {
			runAttempts(runId, sessionId, turnStartedAtMs, out, attempt -> {
				log.debug("本轮第 {} 次尝试组装请求 runId={} sessionId={}（每轮重建请求）", attempt, runId, sessionId);
				return Flux.defer(() -> {
					var spec = this.chatClient.prompt()
						.system(systemText)
						.user(request.message())
						// 记忆 advisor 的会话键：官方常量（MessageChatMemoryAdvisor 据此读写 Redis）
						.advisors(advisors -> advisors.param(ChatMemory.CONVERSATION_ID, sessionId))
						// 显式 runId 通道：ToolCallingManager 由此拿到外置/挂起所需的轮次 id
						.toolContext(Map.of("runId", runId, "sessionId", sessionId));
					var withTools = tools.isEmpty() ? spec : spec.toolCallbacks(tools);
					return withTools.stream().chatResponse();
				});
			});
		}
		finally {
			this.cancellations.unregister(runId);
			this.beacon.clear(runId);
		}
	}

	// ── 续跑：重建的完整历史 + 官方 ChatModel 路径（由 ResumeService 驱动） ──

	/**
	 * 用重建好的<b>完整历史</b>继续官方循环（硬规范②）。
	 *
	 * <p>
	 * 这里刻意不经 {@code ChatClient}/{@code MessageChatMemoryAdvisor}：历史已经由
	 * {@link ResumeService} 从 RunStore 重建好，再过一遍 advisor 会把记忆窗口重复拼一遍。
	 * 工具执行仍必须走同一个官方扩展点（options 里带 toolCallbacks + toolContext），
	 * 这样续跑后模型新发的工具调用照旧经过确认门/前端挂起（SM-06 / TC-AI-03b）。
	 */
	public void driveResumed(String runId, String sessionId, AiContext context, List<Message> history,
			SseChatEmitter out) {
		long turnStartedAtMs = System.currentTimeMillis();
		this.cancellations.register(runId);
		try {
			runAttempts(runId, sessionId, turnStartedAtMs, out, attempt -> {
				log.debug("续跑第 {} 次尝试重建完整历史 runId={} sessionId={} 历史条数={}", attempt, runId, sessionId,
						history.size());
				Prompt prompt = buildResumedPrompt(runId, sessionId, context, history);
				return this.chatModel.stream(prompt);
			});
		}
		finally {
			this.cancellations.unregister(runId);
			this.beacon.clear(runId);
		}
	}

	/**
	 * 续跑请求与首轮同构：同一模型默认选项 + 同一披露子集 + 同一 toolContext。
	 *
	 * <p>
	 * 也供 {@code ResumeService} 在重建阶段结算挂起项时复用（工具的委托执行需要一个带
	 * toolCallbacks 的 Prompt）。
	 */
	public Prompt buildResumedPrompt(String runId, String sessionId, AiContext context, List<Message> history) {
		ChatOptions base = this.chatModel.getDefaultOptions();
		OpenAiChatOptions options = base instanceof OpenAiChatOptions openAiOptions
				? OpenAiChatOptions.fromOptions(openAiOptions) : OpenAiChatOptions.builder().build();
		options.setToolCallbacks(this.toolRegistry.forContext(context));
		options.setInternalToolExecutionEnabled(true);
		options.setToolContext(Map.of("runId", runId, "sessionId", sessionId));
		return new Prompt(history, options);
	}

	// ── 尝试循环（双超时 + 三条件重试 + 完成帧校验 + 取消） ────────────────────

	private void runAttempts(String runId, String sessionId, long turnStartedAtMs, SseChatEmitter out,
			IntFunction<Flux<ChatResponse>> attemptStream) {
		AiProperties.Resilience config = this.properties.getResilience();
		int maxAttempts = Math.max(1, config.getMaxAttempts());
		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			long attemptStartedAtMs = System.currentTimeMillis();
			long beaconBefore = this.beacon.startedCount(runId);
			Accumulator accumulator = new Accumulator();
			CompletableFuture<Void> settled = new CompletableFuture<>();
			StreamWatchdog watchdog = new StreamWatchdog(runId, this.watchdogExecutor, config, this.beacon,
					this.cancellations, turnStartedAtMs, settled::completeExceptionally);
			Disposable subscription = null;
			try {
				AtomicLong lastStreamingBeatAt = new AtomicLong(System.currentTimeMillis());
				final int attemptNo = attempt;
				subscription = attemptStream.apply(attempt)
					.subscribe(response -> {
						watchdog.onUpstreamEvent();
						// 流式活动心跳（R3 的配套）：模型在"只吐 reasoning、不出正文"的阶段
						// 客户端看不到 delta，于是外置帧里会长时间没有任何新帧 —— 而僵尸判据
						// 依赖"最后一帧的时间"（跨进程唯一可靠的活动证据）。这里每
						// {@value #STREAMING_HEARTBEAT_MILLIS}ms 至多补一帧，使**正在产出的长轮**
						// 恒有活动痕迹（前端也据此显示"模型生成中"；reattach 回放默认跳过 heartbeat，
						// 不会与实时流重复渲染）。
						if (System.currentTimeMillis() - lastStreamingBeatAt.get() >= STREAMING_HEARTBEAT_MILLIS) {
							lastStreamingBeatAt.set(System.currentTimeMillis());
							Map<String, Object> beat = new LinkedHashMap<>();
							beat.put("phase", "streaming");
							beat.put("attempt", attemptNo);
							beat.put("upstreamEvents", watchdog.upstreamEvents());
							out.heartbeat(beat);
						}
						consume(response, out, accumulator);
					}, settled::completeExceptionally, () -> settled.complete(null));
				watchdog.bind(subscription);

				awaitSettled(settled, turnStartedAtMs, config);

				// 硬规范②：正常结束必须有终帧（带 finishReason 的响应）；"安静地"结束 = 断流
				if (!accumulator.sawTerminal()) {
					throw new StreamViolationException(StreamViolationException.INCOMPLETE,
							"上游流已结束但缺少 finishReason 终帧——按断流处理，已收上游事件 "
									+ watchdog.upstreamEvents() + " 个");
				}
				finish(runId, sessionId, accumulator, attempt, watchdog, out);
				return;
			}
			catch (Throwable error) {
				StreamViolationException violation = classify(error, watchdog.upstreamEvents());
				if (StreamViolationException.CANCELLED.equals(violation.getCode())) {
					cancelTerminal(runId, sessionId, accumulator, attempt, watchdog, out);
					return;
				}
				boolean emitted = accumulator.emittedAny();
				// 副作用 = 本次尝试内工具开始执行过，或台账在本次尝试开始后新增了执行记录
				boolean sideEffect = this.beacon.startedCount(runId) > beaconBefore
						|| this.store.ledgerCountSince(runId, attemptStartedAtMs) > 0;
				boolean overBudget = System.currentTimeMillis() - turnStartedAtMs
						>= config.getTotalBudget().toMillis();
				boolean hasNext = attempt < maxAttempts;
				boolean retryable = violation.brokenStream() && !emitted && !sideEffect && hasNext && !overBudget;
				log.warn("本轮尝试失败 runId={} attempt={}/{} code={} 已产出={} 副作用={} 超预算={} 可重试={} 上游事件={} 原因={} 末帧序列=[{}]",
						runId, attempt, maxAttempts, violation.getCode(), emitted, sideEffect, overBudget, retryable,
						watchdog.upstreamEvents(), violation.getMessage(), accumulator.tailSummary());
				if (!retryable) {
					fail(runId, sessionId, violation, attempt, emitted, sideEffect, out);
					return;
				}
				Map<String, Object> retryFrame = new LinkedHashMap<>();
				retryFrame.put("nextAttempt", attempt + 1);
				retryFrame.put("code", violation.getCode());
				retryFrame.put("reason", violation.getMessage());
				retryFrame.put("upstreamEvents", watchdog.upstreamEvents());
				retryFrame.put("elapsedMs", System.currentTimeMillis() - turnStartedAtMs);
				out.retry(retryFrame);
			}
			finally {
				watchdog.close();
				if (subscription != null && !subscription.isDisposed()) {
					subscription.dispose();
				}
			}
		}
	}

	/** 有界等待：看门狗负责判定，这里只做最后一道墙钟兜底（避免任何路径下永久阻塞）。 */
	private void awaitSettled(CompletableFuture<Void> settled, long turnStartedAtMs, AiProperties.Resilience config) {
		long deadline = turnStartedAtMs + config.getTotalBudget().toMillis() + 1000L;
		long remain = Math.max(1000L, deadline - System.currentTimeMillis());
		try {
			settled.get(remain, TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException ex) {
			throw new StreamViolationException(StreamViolationException.TOTAL_BUDGET,
					"本轮总预算 " + config.getTotalBudget() + " 已耗尽（墙钟兜底）");
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new StreamViolationException(StreamViolationException.CANCELLED, "运行线程被中断，本轮按取消收尾");
		}
		catch (ExecutionException ex) {
			throw asRuntime(ex.getCause() == null ? ex : ex.getCause());
		}
	}

	/** 把任意异常归类成带 code 的 {@link StreamViolationException}（断流判定只看 code）。 */
	private StreamViolationException classify(Throwable error, int upstreamEvents) {
		if (error instanceof StreamViolationException violation) {
			return violation;
		}
		String message = describe(error);
		String text = String.valueOf(error.getMessage());
		Throwable cause = error;
		while (cause != null && cause.getMessage() == null) {
			cause = cause.getCause();
		}
		String chain = String.valueOf(cause == null ? "" : cause.getMessage());
		// 空流特例（实测形态）：上游一个事件都没给就安静地结束，Spring AI 的流式聚合会以
		// "conversationId cannot be null" 这类客户端侧异常暴露出来 —— 它本质就是"缺终帧"。
		if (upstreamEvents == 0 && containsAny(text, "conversationId cannot be null")) {
			return new StreamViolationException(StreamViolationException.INCOMPLETE,
					"上游未给出任何事件即结束（" + message + "）——按断流处理", error);
		}
		if (containsAny(text, "PrematureClose", "premature", "reset", "Connection", "closed", "EOF", "Broken pipe",
				"header parser received no bytes", "no bytes", "channel closed", "aborted", "SocketException",
				"ConnectException", "Response has been closed")
				|| containsAny(chain, "PrematureClose", "premature", "reset", "Connection", "closed", "EOF", "Broken pipe",
						"header parser received no bytes", "no bytes", "channel closed", "aborted", "SocketException",
						"ConnectException", "Response has been closed")) {
			return new StreamViolationException(StreamViolationException.CONNECTION_BROKEN, message, error);
		}
		return new StreamViolationException(StreamViolationException.STREAM_ERROR, message, error);
	}

	private static boolean containsAny(String text, String... needles) {
		if (text == null) {
			return false;
		}
		for (String needle : needles) {
			if (text.contains(needle)) {
				return true;
			}
		}
		return false;
	}

	private static RuntimeException asRuntime(Throwable error) {
		return error instanceof RuntimeException runtime ? runtime : new StreamViolationException(
				StreamViolationException.STREAM_ERROR, String.valueOf(error.getMessage()), error);
	}

	// ── 落定 ────────────────────────────────────────────────────────────────

	/** 成功终局：补最终 assistant 消息 → 快照 DONE → 记忆收敛 → {@code done} 终帧。 */
	private void finish(String runId, String sessionId, Accumulator accumulator, int attempts, StreamWatchdog watchdog,
			SseChatEmitter out) {
		String text = accumulator.text();
		RunSnapshot snapshot = this.store.get(runId);
		List<Message> history = new ArrayList<>();
		if (snapshot != null) {
			history = new ArrayList<>(this.codec.deserializeAll(snapshot.getMessageJson()));
			if (StringUtils.hasText(text)) {
				history.add(AssistantMessage.builder().content(text).build());
			}
			snapshot.setMessageJson(this.codec.serializeAll(history));
			snapshot.setInFlightToolCalls(new ArrayList<>());
			snapshot.setAssistantContent(null);
			snapshot.setFinalText(text);
			snapshot.setStatus(RunSnapshot.DONE);
			snapshot.setCancelled(false);
			snapshot.setAttempts(attempts);
			this.store.save(snapshot);
			convergeMemory(sessionId, history);
			log.debug("AI 轮次结束 runId={} sessionId={} 历史条数={} 尝试次数={} 终帧=done 末帧序列=[{}]", runId, sessionId,
					history.size(), attempts, accumulator.tailSummary());
		}
		Map<String, Object> extra = new LinkedHashMap<>();
		extra.put("cancelled", false);
		extra.put("attempts", attempts);
		extra.put("upstreamEvents", watchdog == null ? 0 : watchdog.upstreamEvents());
		extra.put("terminalSignal", accumulator.terminalSignal());
		out.done(accumulator.usage(), accumulator.model(), extra);
	}

	/**
	 * 取消终局：<b>不</b>写入半截 assistant 正文（半条消息会污染下一轮记忆），
	 * 历史取快照现状（含本轮已落定的工具配对），快照转 CANCELLED，终帧 {@code done{cancelled:true}}。
	 */
	private void cancelTerminal(String runId, String sessionId, Accumulator accumulator, int attempts,
			StreamWatchdog watchdog, SseChatEmitter out) {
		RunSnapshot snapshot = this.store.get(runId);
		if (snapshot != null) {
			List<Message> history = new ArrayList<>(this.codec.deserializeAll(snapshot.getMessageJson()));
			snapshot.setInFlightToolCalls(new ArrayList<>());
			snapshot.setAssistantContent(null);
			snapshot.setStatus(RunSnapshot.CANCELLED);
			snapshot.setCancelled(true);
			snapshot.setAttempts(attempts);
			snapshot.setError("AI_RUN_CANCELLED：本轮由用户取消（上游连接已断开，工具未再继续）");
			this.store.save(snapshot);
			convergeMemory(sessionId, history);
		}
		this.store.cancelPendings(runId);
		log.info("AI 轮次取消 runId={} sessionId={} 尝试次数={} 已收上游事件={} 末帧序列=[{}]", runId, sessionId, attempts,
				watchdog == null ? 0 : watchdog.upstreamEvents(), accumulator == null ? "" : accumulator.tailSummary());
		Map<String, Object> extra = new LinkedHashMap<>();
		extra.put("cancelled", true);
		extra.put("attempts", attempts);
		extra.put("upstreamEvents", watchdog == null ? 0 : watchdog.upstreamEvents());
		extra.put("partialChars", accumulator == null ? 0 : accumulator.text().length());
		out.done(accumulator == null ? Map.of() : accumulator.usage(), accumulator == null ? null : accumulator.model(),
				extra);
	}

	private void fail(String runId, String sessionId, StreamViolationException violation, int attempts, boolean emitted,
			boolean sideEffect, SseChatEmitter out) {
		String code = violation.getCode();
		if (sideEffect) {
			code = code + "_AFTER_TOOL_SIDE_EFFECT";
		}
		else if (emitted) {
			code = code + "_AFTER_PARTIAL";
		}
		String message = describe(violation);
		log.error("AI 轮次失败 runId={} sessionId={} attempt={} code={}：{}", runId, sessionId, attempts, code, message);
		RunSnapshot snapshot = this.store.get(runId);
		if (snapshot != null && !RunSnapshot.DONE.equals(snapshot.getStatus())) {
			snapshot.setStatus(RunSnapshot.FAILED);
			snapshot.setAttempts(attempts);
			snapshot.setError(code + "：" + message);
			this.store.save(snapshot);
		}
		out.error(code, message);
	}

	/**
	 * 记忆收敛（规格 §3 末段）：以本轮完整历史整体覆盖写回记忆窗口（去掉两条 system 提示 ——
	 * 系统提示每轮由请求侧重新拼装，写进记忆会导致下一轮重复叠加）。
	 */
	private void convergeMemory(String sessionId, List<Message> history) {
		List<Message> memoryPortion = history.stream()
			.filter(message -> message.getMessageType() != MessageType.SYSTEM)
			.toList();
		if (memoryPortion.isEmpty()) {
			return;
		}
		List<Message> windowed = trimKeepingToolPairs(memoryPortion,
				this.properties.getMemory().getMaxMessages());
		try {
			this.chatMemory.clear(sessionId);
			this.chatMemory.add(sessionId, windowed);
		}
		catch (Exception ex) {
			log.warn("记忆收敛失败 sessionId={}：{}", sessionId, ex.getMessage());
		}
	}

	/**
	 * 窗口裁剪的配对保护（SM-01 的判据）：窗口首条不得是孤立的 {@code TOOL} 消息 ——
	 * 它的 {@code assistant(tool_calls)} 若被裁掉，下一次请求会因"tool 没有对应的 tool_calls"
	 * 被上游拒绝（400）。裁剪点从需要丢弃的位置起向后推到第一个非 {@code TOOL} 消息，
	 * 即"tool 配对整体保留"。
	 */
	private List<Message> trimKeepingToolPairs(List<Message> messages, int maxMessages) {
		if (maxMessages <= 0 || messages.size() <= maxMessages) {
			return messages;
		}
		int drop = messages.size() - maxMessages;
		while (drop < messages.size() && messages.get(drop).getMessageType() == MessageType.TOOL) {
			drop++;
		}
		return new ArrayList<>(messages.subList(drop, messages.size()));
	}

	// ── 流式消费 ────────────────────────────────────────────────────────────

	private void consume(ChatResponse response, SseChatEmitter out, Accumulator accumulator) {
		accumulator.accept(response);
		Generation generation = response.getResult();
		if (generation == null || generation.getOutput() == null) {
			return;
		}
		String text = generation.getOutput().getText();
		if (StringUtils.hasText(text)) {
			out.delta(text);
		}
	}

	/**
	 * 本次尝试的累计状态。
	 *
	 * <p>
	 * {@link #sawTerminal()} 是完成帧校验的唯一判据：<b>上游必须给出过 finishReason</b>
	 * （{@code stop}/{@code tool_calls}）。实测（SM-02 第一轮）证明不能拿"usage 非空"当终帧：
	 * <ul>
	 * <li>Spring AI 的流式聚合对每个分片都挂一个 Usage 对象（0/0/0），只看"非空"会把
	 * 一次只转发了一个 role 分片就静默截断的流判成正常结束；</li>
	 * <li>带工具的一轮里，客户端可见的 usage 可能来自前面的工具轮（聚合器的累计值），
	 * 于是"第二轮被静默截断"同样会被 usage 信号掩盖（SM-02c 实测踩到）。</li>
	 * </ul>
	 * {@link #tailSummary()} 留最后几次响应的形态（finishReason/文本长度/用量），
	 * 供"为什么判成断流/为什么判成正常"在日志里一眼可查。
	 */
	private static final class Accumulator {

		private static final int TAIL_SIZE = 6;

		private final StringBuilder text = new StringBuilder();

		private final AtomicReference<ChatResponse> lastResponse = new AtomicReference<>();

		private final Deque<String> tail = new ArrayDeque<>();

		private volatile String terminalSignal;

		void accept(ChatResponse response) {
			this.lastResponse.set(response);
			Generation generation = response.getResult();
			if (generation != null) {
				String finishReason = generation.getMetadata() == null ? null : generation.getMetadata().getFinishReason();
				if (StringUtils.hasText(finishReason)) {
					this.terminalSignal = "finishReason=" + finishReason;
				}
				if (generation.getOutput() != null) {
					String chunk = generation.getOutput().getText();
					if (chunk != null) {
						this.text.append(chunk);
					}
				}
			}
			record(response, generation);
		}

		/** 末帧形态留档（最多 {@value #TAIL_SIZE} 条）。 */
		private void record(ChatResponse response, Generation generation) {
			var usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
			String descriptor = "finishReason="
					+ (generation == null || generation.getMetadata() == null ? "null" : generation.getMetadata().getFinishReason())
					+ "|textLen=" + (generation == null || generation.getOutput() == null || generation.getOutput().getText() == null
							? -1 : generation.getOutput().getText().length())
					+ "|usage=" + (usage == null ? "null"
							: usage.getPromptTokens() + "/" + usage.getCompletionTokens() + "/" + usage.getTotalTokens());
			if (this.tail.size() >= TAIL_SIZE) {
				this.tail.pollFirst();
			}
			this.tail.addLast(descriptor);
		}

		String tailSummary() {
			return String.join(" ; ", this.tail);
		}

		boolean sawTerminal() {
			return this.terminalSignal != null;
		}

		String terminalSignal() {
			return this.terminalSignal;
		}

		boolean emittedAny() {
			return this.text.length() > 0;
		}

		String text() {
			return this.text.toString();
		}

		Map<String, Object> usage() {
			ChatResponse response = this.lastResponse.get();
			if (response == null || response.getMetadata() == null || response.getMetadata().getUsage() == null) {
				return Map.of();
			}
			var usage = response.getMetadata().getUsage();
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("promptTokens", usage.getPromptTokens());
			out.put("completionTokens", usage.getCompletionTokens());
			out.put("totalTokens", usage.getTotalTokens());
			return out;
		}

		String model() {
			ChatResponse response = this.lastResponse.get();
			return response != null && response.getMetadata() != null ? response.getMetadata().getModel() : null;
		}

	}

	private AiContext toContext(AiChatRequest.Context context) {
		if (context == null) {
			return AiContext.empty();
		}
		return AiContext.of(context.page(), context.taskType(), context.step(), context.taskId(), context.extra());
	}

	/**
	 * 上游错误要留住原始响应体：reasoning 回填缺失等协议错误只有这里能看清（400 的 body 才是证据）。
	 */
	private String describe(Throwable error) {
		Throwable cause = error;
		while (cause != null && !(cause instanceof WebClientResponseException)) {
			cause = cause.getCause();
		}
		if (cause instanceof WebClientResponseException upstream) {
			return "上游返回 HTTP " + upstream.getStatusCode().value() + "：" + upstream.getResponseBodyAsString();
		}
		if (error instanceof IllegalStateException && error.getMessage() != null
				&& error.getMessage().startsWith("Timeout on blocking read")) {
			return "AI 调用超过总预算 " + this.properties.getResilience().getTotalBudget()
					+ " 未完成（双超时阈值待压测裁决）";
		}
		return error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
	}

	/** 供诊断/日志：本类的韧性参数快照。 */
	public Map<String, Object> resilienceSnapshot() {
		AiProperties.Resilience config = this.properties.getResilience();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("firstByteTimeout", String.valueOf(config.getFirstByteTimeout()));
		out.put("interEventTimeout", String.valueOf(config.getInterEventTimeout()));
		out.put("maxAttempts", config.getMaxAttempts());
		out.put("totalBudget", String.valueOf(config.getTotalBudget()));
		out.put("hitlTimeout", String.valueOf(this.properties.getHitl().getTimeout()));
		out.put("watchdogTickMillis", StreamWatchdog.TICK_MILLIS);
		return out;
	}

	/** 供诊断：本轮的静默锚点与工具活动（看门狗判定的输入，取证用）。 */
	public Map<String, Object> guardState(String runId) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("toolActive", this.beacon.isActive(runId));
		out.put("toolStarted", this.beacon.startedCount(runId));
		out.put("lastToolActivityAtMs", this.beacon.lastActivityAtMs(runId));
		out.put("cancelled", this.cancellations.isCancelled(runId));
		out.put("cancelPolls", this.cancellations.polls());
		out.put("budget", Duration.ofMillis(this.properties.getResilience().getTotalBudget().toMillis()).toString());
		return out;
	}

}
