package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.AiProviderRegistry;
import com.example.configmgr.ai.config.ProviderRuntime;
import com.example.configmgr.ai.context.ContextBreakdownService;
import com.example.configmgr.ai.context.ContextCompactor;
import com.example.configmgr.ai.context.ContextEstimator;
import com.example.configmgr.ai.context.ContextMeter;
import com.example.configmgr.ai.context.ContextState;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ContextBuilder;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.web.AiChatRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
public class ResilientChatService {

	/** 流式活动心跳的最小间隔（毫秒）：僵尸判据要"有活动痕迹"，但不能把帧刷爆。 */
	public static final long STREAMING_HEARTBEAT_MILLIS = 5000L;

	/**
	 * 空回复的机器可读码（T3-7 / A12，口径变更）：<b>空文本（去空白后为空）的轮次终态转 FAILED</b>。
	 *
	 * <p>为什么采纳"空回复即失败"：上游静默截断可以产出 0 字符的"成功"终态（实测形态），
	 * 此前 {@link #finish} 对空文本仍置 {@code DONE}，等于把"什么都没答"当成功交付；
	 * 判定规则直接影响用户体验，故轮次级以 FAILED + 本码收尾（终帧为 {@code error}，
	 * 不再是 {@code done}）。明细口径见技术方案 §13.2 #3 的闭环注记。
	 */
	public static final String EMPTY_REPLY_CODE = "EMPTY_REPLY";

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
			10. 步骤推进（confirm_step、navigate_to）只对**下一轮**生效：新步骤或新页面才披露的工具
			    本轮拿不到，硬调会被拒绝执行。因此推进后不要在同一轮里接着调用刚解锁的工具，
			    而是如实汇报"已推进到 X 步骤"，并用一句话引导用户（例如"请回复「继续」完成最后一步"）
			""";

	/**
	 * provider 实例注册表（模型配置专项批①，设计稿 §3.4）。
	 *
	 * <p>
	 * 取代此前注入的 {@code (ChatClient, ChatModel)} 双单例：模型侧不再有"启动期一次成型"的
	 * bean，改为按 {@code RuntimeKey} 从注册表<b>取用</b>（key/base-url/completions-path 变了就
	 * 下次取用重建，模型名变了就轻量重绑）。取用点每次请求现取，因此在飞轮持有旧引用、下一轮
	 * 用新配置 —— 热生效不需要重启。
	 */
	private final AiProviderRegistry providerRegistry;

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

	/**
	 * 上下文压缩统一入口（上下文统计与压缩专项批①）。
	 *
	 * <p>
	 * <b>字段注入</b>而非构造器参数：本类的构造器被三份既有 conformance/run 用例直接
	 * {@code new}（{@code ChatTurnFrameSequenceConformanceTest} 等），改签名等于要求它们跟改
	 * —— 而"既有断言零删改"是本批的门禁。未注入（局部上下文/单测直接 new）时压缩与计量整体缺席，
	 * 帧里也就不带 {@code done.context}（"未装配"与"计量为 0"必须可区分）。
	 */
	@Autowired(required = false)
	private ContextCompactor contextCompactor;

	public ResilientChatService(AiProviderRegistry providerRegistry, ChatMemory chatMemory,
			MessageJsonCodec codec, RunStore store, RunRegistry registry, ToolRegistry toolRegistry,
			ContextBuilder contextBuilder, AiSessionService sessionService, AiProperties properties,
			CancellationRegistry cancellations, ToolActivityBeacon beacon,
			@Qualifier("aiStreamWatchdog") ScheduledExecutorService watchdogExecutor) {
		this.providerRegistry = providerRegistry;
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

		// 模型身份 = 本轮固化进快照的那一对（providerId/modelId）；没有可用 provider 时
		// 控制器早已 503 拦住（这里抛 AiUnavailableException 只是并发改配置的窄窗口兜底）。
		ProviderRuntime runtime = this.providerRegistry.current();

		// 上下文专项批①：装配点同时给出三个分项锚点（system/tools/input）—— 它们不在历史里，
		// 端点重建时无从再算，故在本轮起点就随真值一起落进 ai:ctx:。
		List<ToolCallback> tools = this.toolRegistry.forContext(context);
		int systemTokens = ContextEstimator.estimate(systemText);
		int toolsTokens = ContextEstimator.estimateToolSchemas(tools);
		int inputTokens = ContextEstimator.estimate(request.message());
		Compaction compaction = compactBeforeRequest(runId, sessionId, runtime.modelId(), runtime.providerId(), memory,
				inputTokens, systemTokens, toolsTokens, 0);
		memory = compaction.memory();

		// 与官方 MessageChatMemoryAdvisor.before 的变换逐字对齐（1.1.8 字节码实测）：
		// [首个 SystemMessage] + 记忆窗口 + 其余指令 —— 快照里存的就是模型真正看到的那串消息。
		List<Message> full = new ArrayList<>(memory.size() + instructions.size());
		full.add(instructions.get(0));
		full.addAll(memory);
		full.add(instructions.get(1));
		// 压缩若已发生：快照写的就是压缩后的那一份（快照 === 模型看到的；分叉即 SM-01 同族）
		this.store.create(runId, sessionId, context, full, this.codec, runtime.providerId(), runtime.modelId());

		this.cancellations.register(runId);
		out.start(sessionId);
		// 压缩即时可见：请求前压的，故必在本轮第一个 delta/tool_start 之前（帧类型 15 → 16 的唯一新增）
		compaction.emitIfChanged(out);

		// 格式契约：E2E 的披露取证按 `上下文=(\S+)\s+披露工具=\[` 正则读这一行（scripts/verify-e2e.ps1），
		// 因此 provider/model 只能另起一行，不得插进本行字段之间。
		log.debug("AI 轮次开始 runId={} sessionId={} 会话已存在={} 上下文={} 披露工具={} 首包超时={} 包间超时={} 最大尝试={} 总预算={}",
				runId, sessionId, existed, context.getContextKey(), tools.stream().map(t -> t.getToolDefinition().name()).toList(),
				this.properties.getResilience().getFirstByteTimeout(), this.properties.getResilience().getInterEventTimeout(),
				this.properties.getResilience().getMaxAttempts(), this.properties.getResilience().getTotalBudget());
		log.debug("AI 轮次模型身份 runId={} provider={} model={}", runId, runtime.providerCode(), runtime.modelId());

		try {
			TurnContext turn = new TurnContext(systemTokens, toolsTokens, inputTokens,
					systemTokens + toolsTokens + ContextEstimator.estimateMessages(memory) + inputTokens,
					compaction.limitTokens(), runtime.modelId(), compaction.levelWire(), turnStartedAtMs);
			runAttempts(runId, sessionId, turnStartedAtMs, out, turn, attempt -> {
				log.debug("本轮第 {} 次尝试组装请求 runId={} sessionId={}（每轮重建请求）", attempt, runId, sessionId);
				return Flux.defer(() -> {
					var spec = runtime.chatClient().prompt()
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
		// 续跑也要现取一次实例：在飞期间换了 base-url/key 的话，续跑落在新端点上（与首轮同口径）。
		// 批③ 会改成"按快照的 providerId/modelId 取"，provider 没了就 409 PROVIDER_GONE。
		ProviderRuntime runtime = this.providerRegistry.current();
		this.cancellations.register(runId);
		// 上下文专项批①：续跑侧的分母/分项锚点现算（历史里没有 tools schema，故走披露口径）
		TurnContext turn = resumedTurn(runId, context, history, runtime.modelId(), turnStartedAtMs);
		try {
			runAttempts(runId, sessionId, turnStartedAtMs, out, turn, attempt -> {
				log.debug("续跑第 {} 次尝试重建完整历史 runId={} sessionId={} 历史条数={}", attempt, runId, sessionId,
						history.size());
				Prompt prompt = buildResumedPrompt(runtime, runId, sessionId, context, history);
				return runtime.chatModel().stream(prompt);
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
	 * toolCallbacks 的 Prompt）。此处取不到实例（无可用 provider）时退化为"只有工具选项"的
	 * Prompt：结算挂起项不需要模型名，而这条路在控制器已按 availability 放行之后几乎不可达。
	 */
	public Prompt buildResumedPrompt(String runId, String sessionId, AiContext context, List<Message> history) {
		return buildResumedPrompt(this.providerRegistry.tryCurrent().orElse(null), runId, sessionId, context, history);
	}

	private Prompt buildResumedPrompt(ProviderRuntime runtime, String runId, String sessionId, AiContext context,
			List<Message> history) {
		ChatOptions base = runtime == null ? null : runtime.chatModel().getDefaultOptions();
		OpenAiChatOptions options = base instanceof OpenAiChatOptions openAiOptions
				? OpenAiChatOptions.fromOptions(openAiOptions) : OpenAiChatOptions.builder().build();
		options.setToolCallbacks(this.toolRegistry.forContext(context));
		options.setInternalToolExecutionEnabled(true);
		options.setToolContext(Map.of("runId", runId, "sessionId", sessionId));
		return new Prompt(history, options);
	}

	// ── 尝试循环（双超时 + 三条件重试 + 完成帧校验 + 取消） ────────────────────

	private void runAttempts(String runId, String sessionId, long turnStartedAtMs, SseChatEmitter out,
			TurnContext turn, IntFunction<Flux<ChatResponse>> attemptStream) {
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

				awaitSettled(runId, settled, turnStartedAtMs, config);

				// 硬规范②：正常结束必须有终帧（带 finishReason 的响应）；"安静地"结束 = 断流
				if (!accumulator.sawTerminal()) {
					throw new StreamViolationException(StreamViolationException.INCOMPLETE,
							"上游流已结束但缺少 finishReason 终帧——按断流处理，已收上游事件 "
									+ watchdog.upstreamEvents() + " 个");
				}
				finish(runId, sessionId, accumulator, attempt, watchdog, out, turn);
				return;
			}
			catch (Throwable error) {
				StreamViolationException violation = classify(error, watchdog.upstreamEvents());
				if (StreamViolationException.CANCELLED.equals(violation.getCode())) {
					cancelTerminal(runId, sessionId, accumulator, attempt, watchdog, out, turn);
					return;
				}
				boolean emitted = accumulator.emittedAny();
				// 副作用 = 本次尝试内工具开始执行过，或台账在本次尝试开始后新增了执行记录
				boolean sideEffect = this.beacon.startedCount(runId) > beaconBefore
						|| this.store.ledgerCountSince(runId, attemptStartedAtMs) > 0;
				boolean overBudget = elapsedExcludingGateWait(runId, turnStartedAtMs)
						>= config.getTotalBudget().toMillis();
				boolean hasNext = attempt < maxAttempts;
				boolean retryable = violation.brokenStream() && !emitted && !sideEffect && hasNext && !overBudget;
				log.warn("本轮尝试失败 runId={} attempt={}/{} code={} 已产出={} 副作用={} 超预算={} 可重试={} 上游事件={} 原因={} 末帧序列=[{}]",
						runId, attempt, maxAttempts, violation.getCode(), emitted, sideEffect, overBudget, retryable,
						watchdog.upstreamEvents(), violation.getMessage(), accumulator.tailSummary());
				if (!retryable) {
					fail(runId, sessionId, violation, attempt, emitted, sideEffect, accumulator.text().length(), out);
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

	/**
	 * 有界等待：看门狗负责判定，这里只做最后一道墙钟兜底（避免任何路径下永久阻塞）。
	 *
	 * <p><b>人机预算分离</b>（用户裁决 2026-10-20 第 3 条）：兜底判据同样是"墙钟 − 挂起等待"。
	 * 确认门 / {@code generative_form} 没有等待上限，一次十几分钟的表单填写期间这里必须继续等
	 * —— 否则 300s 一到就被折算成 {@code TOTAL_BUDGET}，把"无超时"变成 300s 的死线。
	 * 故改成<b>短切片轮询</b>：每片复查一次"扣除等待后还剩多少预算"。
	 */
	private void awaitSettled(String runId, CompletableFuture<Void> settled, long turnStartedAtMs,
			AiProperties.Resilience config) {
		long budgetMs = config.getTotalBudget().toMillis();
		while (true) {
			long remain = budgetMs + 1000L - elapsedExcludingGateWait(runId, turnStartedAtMs);
			if (remain <= 0) {
				throw new StreamViolationException(StreamViolationException.TOTAL_BUDGET,
						"本轮总预算 " + config.getTotalBudget() + " 已耗尽（墙钟兜底；人工等待不计入）");
			}
			try {
				settled.get(Math.max(1000L, remain), TimeUnit.MILLISECONDS);
				return;
			}
			catch (TimeoutException ex) {
				// 切片到期：可能是预算真耗尽，也可能只是挂起等待中预算被暂停 —— 回到循环复查
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new StreamViolationException(StreamViolationException.CANCELLED, "运行线程被中断，本轮按取消收尾");
			}
			catch (ExecutionException ex) {
				throw asRuntime(ex.getCause() == null ? ex : ex.getCause());
			}
		}
	}

	/**
	 * 已计入预算的时长（毫秒）= 墙钟 − 本轮累计的挂起等待（含正在进行的等待）。
	 *
	 * <p>为什么不只扣"已结束的等待窗口"：看门狗与兜底判据每一拍都要判，等待没结束时也必须暂停预算。
	 */
	private long elapsedExcludingGateWait(String runId, long turnStartedAtMs) {
		return (System.currentTimeMillis() - turnStartedAtMs) - this.beacon.gateWaitMs(runId);
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
		// 上下文越窗（上下文专项批③）：L3 丢到 pin 下限后仍超时，让上游如实报错并把这条报错认出来。
		// 放在断流判定之前：越窗响应体里也可能带 "Response has been closed" 之类的字眼，
		// 但那不是断流 —— 按断流重试会把同一份超大请求再发一次。
		if (com.example.configmgr.ai.context.ContextOverflow.matches(text, chain, message)) {
			return new StreamViolationException(StreamViolationException.CONTEXT_OVERFLOW,
					"上游拒绝：上下文超出模型窗口（" + message + "）", error);
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

	/**
	 * 成功终局：补最终 assistant 消息 → 快照 DONE → 记忆收敛 → {@code done} 终帧。
	 *
	 * <p><b>T3-7 / A12：空文本不再算成功</b> —— 正文去空白后为空即转 {@link #emptyReply}
	 * （FAILED + {@code EMPTY_REPLY}）。三形态（空串 / 纯空白 / 空文本但本轮用过工具）一律如此：
	 * "用过工具"只说明这一轮做过事，不改变"模型没给出任何正文"这一事实。
	 */
	private void finish(String runId, String sessionId, Accumulator accumulator, int attempts, StreamWatchdog watchdog,
			SseChatEmitter out, TurnContext turn) {
		String text = accumulator.text();
		if (!StringUtils.hasText(text)) {
			emptyReply(runId, sessionId, accumulator, attempts, out);
			return;
		}
		RunSnapshot snapshot = this.store.get(runId);
		List<Message> history = new ArrayList<>();
		if (snapshot != null) {
			history = new ArrayList<>(this.codec.deserializeAll(snapshot.getMessageJson()));
			history.add(AssistantMessage.builder().content(text).build());
			snapshot.setMessageJson(this.codec.serializeAll(history));
			snapshot.setInFlightToolCalls(new ArrayList<>());
			snapshot.setAssistantContent(null);
			snapshot.setFinalText(text);
			snapshot.setStatus(RunSnapshot.DONE);
			snapshot.setCancelled(false);
			snapshot.setAttempts(attempts);
			this.store.save(snapshot);
			convergeMemory(runId, sessionId, history);
			log.debug("AI 轮次结束 runId={} sessionId={} 历史条数={} 尝试次数={} 终帧=done 末帧序列=[{}]", runId, sessionId,
					history.size(), attempts, accumulator.tailSummary());
		}
		Map<String, Object> extra = new LinkedHashMap<>();
		extra.put("cancelled", false);
		extra.put("attempts", attempts);
		extra.put("upstreamEvents", watchdog == null ? 0 : watchdog.upstreamEvents());
		extra.put("terminalSignal", accumulator.terminalSignal());
		// T5：正文段收尾（未开过段则不发 —— 纯工具轮没有正文）
		out.textEnd(text.length());
		// 上下文专项批①：先记账（真值回填 ai:ctx:），再发终帧（done.context 与端点必须同源）
		Map<String, Object> context = contextTelemetry(sessionId, turn, accumulator);
		out.done(accumulator.usage(turn.promptEstimate()), accumulator.model(), extra, context);
	}

	/**
	 * 空回复终局（T3-7 / A12，<b>口径变更</b>）：正文去空白后为空 ⇒ 轮次 FAILED + 机器可读码
	 * {@value #EMPTY_REPLY_CODE}，终帧 {@code error}（此前是 {@code done}）。
	 *
	 * <p>与 {@link #cancelTerminal} 同口径的两点：① 不把空正文写成 assistant 消息
	 * （空消息会污染下一轮记忆）；② 仍以快照现状的历史收敛记忆（本轮已落定的工具配对随之进记忆，
	 * "这一轮开过的东西"不悬空）。已经打开过的正文段（纯空白分片也发 delta，见 {@code consume}）
	 * 先收尾再发终帧 —— 与 {@link #fail} 的帧序契约一致（运行关闭前，它打开的一切都要先关闭）。
	 */
	private void emptyReply(String runId, String sessionId, Accumulator accumulator, int attempts, SseChatEmitter out) {
		String text = accumulator.text();
		String message = "模型本轮未产出任何正文（去空白后为空），按失败收尾。"
				+ "常见成因：上游静默截断产出 0 字符的\"成功\"响应，或模型返回空回复；请重试或换一种问法。";
		log.error("AI 轮次空回复 runId={} sessionId={} attempts={} rawChars={} 末帧序列=[{}]", runId, sessionId, attempts,
				text.length(), accumulator.tailSummary());
		RunSnapshot snapshot = this.store.get(runId);
		if (snapshot != null) {
			List<Message> history = new ArrayList<>(this.codec.deserializeAll(snapshot.getMessageJson()));
			snapshot.setMessageJson(this.codec.serializeAll(history));
			snapshot.setInFlightToolCalls(new ArrayList<>());
			snapshot.setAssistantContent(null);
			snapshot.setFinalText(text);
			snapshot.setStatus(RunSnapshot.FAILED);
			snapshot.setCancelled(false);
			snapshot.setAttempts(attempts);
			snapshot.setError(EMPTY_REPLY_CODE + "：" + message);
			this.store.save(snapshot);
			convergeMemory(runId, sessionId, history);
		}
		out.textEnd(text.length());
		out.error(EMPTY_REPLY_CODE, message);
	}

	/**
	 * 取消终局：<b>不</b>写入半截 assistant 正文（半条消息会污染下一轮记忆），
	 * 历史取快照现状（含本轮已落定的工具配对），快照转 CANCELLED，终帧 {@code done{cancelled:true}}。
	 */
	private void cancelTerminal(String runId, String sessionId, Accumulator accumulator, int attempts,
			StreamWatchdog watchdog, SseChatEmitter out, TurnContext turn) {
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
			convergeMemory(runId, sessionId, history);
		}
		this.store.cancelPendings(runId);
		log.info("AI 轮次取消 runId={} sessionId={} 尝试次数={} 已收上游事件={} 末帧序列=[{}]", runId, sessionId, attempts,
				watchdog == null ? 0 : watchdog.upstreamEvents(), accumulator == null ? "" : accumulator.tailSummary());
		Map<String, Object> extra = new LinkedHashMap<>();
		extra.put("cancelled", true);
		extra.put("attempts", attempts);
		extra.put("upstreamEvents", watchdog == null ? 0 : watchdog.upstreamEvents());
		extra.put("partialChars", accumulator == null ? 0 : accumulator.text().length());
		// T5：取消也收尾正文段（半截正文不写进历史，但"这一段到此为止"必须显式告知前端）
		out.textEnd(accumulator == null ? 0 : accumulator.text().length());
		// 上下文专项批①：取消轮同样如实报占用（半轮的真值仍是真值，est 标注照旧）
		Map<String, Object> context = contextTelemetry(sessionId, turn, accumulator);
		out.done(accumulator == null ? Map.of() : accumulator.usage(turn.promptEstimate()),
				accumulator == null ? null : accumulator.model(), extra, context);
	}

	private void fail(String runId, String sessionId, StreamViolationException violation, int attempts, boolean emitted,
			boolean sideEffect, int partialChars, SseChatEmitter out) {
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
		// S5c-5：失败也是"把这一轮开过的东西关掉"——与 finish / cancelTerminal 同口径地先收正文段
		// 再发终帧。否则"已产出半截正文后上游报错"会让运行以一条仍开着的文本消息收尾
		// （ag-ui 的 open-message-at-run-finished 判据：运行关闭前，它打开的一切都必须先关闭）。
		// 未开过段（纯工具轮 / 未产出正文）时 textEnd 是幂等空操作。
		out.textEnd(partialChars);
		out.error(code, message);
	}

	/** 历史载荷里段级工具状态映射的键名（R-144）：挂在带 {@code toolCalls} 的 ASSISTANT 消息 metadata 上。 */
	public static final String TOOL_CALL_STATES_KEY = "toolCallStates";

	/**
	 * 记忆收敛（规格 §3 末段）：以本轮完整历史整体覆盖写回记忆窗口（去掉两条 system 提示 ——
	 * 系统提示每轮由请求侧重新拼装，写进记忆会导致下一轮重复叠加）。
	 *
	 * <p>R-144：收敛前把 {@code ai:pending:<runId>} 与 {@code ai:ledger:<runId>} 的条目真态按
	 * {@link #TOOL_CALL_STATES_KEY} 携进带 {@code toolCalls} 的 ASSISTANT 消息 metadata
	 * （T1 形态 = 按 toolCallId 的映射，非消息级标量）；只写 history 里真实存在的消息，
	 * 不凭 pendings 凭空造（禁悬空段）。
	 */
	private void convergeMemory(String runId, String sessionId, List<Message> history) {
		List<Message> memoryPortion = history.stream()
			.filter(message -> message.getMessageType() != MessageType.SYSTEM)
			.toList();
		if (memoryPortion.isEmpty()) {
			return;
		}
		RunSnapshot snapshot = this.store.get(runId);
		String snapshotStatus = snapshot == null ? null : snapshot.getStatus();
		Map<String, PendingToolCall> pendingsById = new LinkedHashMap<>();
		for (PendingToolCall pending : this.store.pendings(runId)) {
			if (pending.getToolCallId() != null) {
				pendingsById.put(pending.getToolCallId(), pending);
			}
		}
		List<Message> withStates = new ArrayList<>(memoryPortion.size());
		for (Message message : memoryPortion) {
			if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
				withStates.add(attachToolCallStates(runId, assistant, pendingsById, snapshotStatus));
			}
			else {
				withStates.add(message);
			}
		}
		List<Message> windowed = trimKeepingToolPairs(withStates,
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
	 * R-144：把该 ASSISTANT 消息各 toolCall 的条目真态写成 {@code toolCallStates} 映射并重建消息
	 * （{@link AssistantMessage} 的 metadata 槽为 final 且无 setter ⇒ 用 builder 逐字段保真重建）。
	 * 无任何可写状态的 toolCallId 时原样返回（不产 {@code toolCallStates} 键 —— 旧载荷/无数据语义）。
	 */
	private Message attachToolCallStates(String runId, AssistantMessage assistant,
			Map<String, PendingToolCall> pendingsById, String snapshotStatus) {
		Map<String, Object> states = new LinkedHashMap<>();
		for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
			PendingToolCall pending = pendingsById.get(call.id());
			Map<String, Object> state = resolveToolCallState(runId, pending, snapshotStatus, call.id());
			if (state != null) {
				states.put(call.id(), state);
			}
		}
		if (states.isEmpty()) {
			return assistant;
		}
		Map<String, Object> metadata = new LinkedHashMap<>();
		if (assistant.getMetadata() != null) {
			metadata.putAll(assistant.getMetadata());
		}
		metadata.put(TOOL_CALL_STATES_KEY, states);
		return AssistantMessage.builder()
			.content(assistant.getText())
			.properties(metadata)
			.toolCalls(assistant.getToolCalls())
			.build();
	}

	/**
	 * R-144：单个 toolCallId 的段级状态解析（两状态源定序 + T2 升格判据 + T3 kind 携载）。
	 * 返回 {@code {status, kind?}}；无 pendings 条目时返回 null（不写该 toolCallId 项）。
	 */
	Map<String, Object> resolveToolCallState(String runId, PendingToolCall pending,
			String snapshotStatus, String toolCallId) {
		if (pending == null) {
			return null;
		}
		String status = pending.getStatus();
		if (isTerminalEntryStatus(status)) {
			return toolCallState(pending, status);
		}
		if (PendingToolCall.APPROVED.equals(status)
				&& (pending.isExecuted() || this.store.ledgerFor(runId, toolCallId) != null)) {
			// T2 升格判据：KIND_CONFIRM 的 DANGER 工具执行后条目态永久停留 APPROVED（executeOnce 仅对
			// KIND_BACKEND 置 EXECUTED），但「已批准且已执行」等价于 EXECUTED（executed 标志或台账命中即已执行）。
			return toolCallState(pending, PendingToolCall.EXECUTED);
		}
		if (PendingToolCall.PENDING.equals(status) || PendingToolCall.APPROVED.equals(status)) {
			if (RunSnapshot.isTerminal(snapshotStatus)) {
				String snapshotCode = snapshotTerminalCode(snapshotStatus);
				if (snapshotCode != null) {
					log.warn("历史段状态以轮次终态为准 runId={} toolCallId={} 条目态={} 轮次态={}",
							runId, toolCallId, status, snapshotStatus);
					return toolCallState(pending, snapshotCode);
				}
				log.warn("轮次终态不参与段态推导，回落条目态 runId={} toolCallId={} 条目态={} 轮次态={}",
						runId, toolCallId, status, snapshotStatus);
				return toolCallState(pending, status);
			}
		}
		return toolCallState(pending, status);
	}

	/** 条目级终态（非 PENDING / APPROVED 的 10 个状态码）判定。 */
	private static boolean isTerminalEntryStatus(String status) {
		return PendingToolCall.EXECUTED.equals(status)
			|| PendingToolCall.REJECTED.equals(status)
			|| PendingToolCall.REJECTED_ARGUMENTS.equals(status)
			|| PendingToolCall.TIMEOUT.equals(status)
			|| PendingToolCall.FRONTEND_RESULT.equals(status)
			|| PendingToolCall.FRONTEND_CANCELLED.equals(status)
			|| PendingToolCall.CANCELLED.equals(status)
			|| PendingToolCall.BLOCKED.equals(status)
			|| PendingToolCall.SKIPPED.equals(status)
			|| PendingToolCall.PRECHECK_FAILED.equals(status);
	}

	/**
	 * 轮次终态 → 段级载荷码的最小面映射（决策卡 §1-2 未定义快照→段态表，本卡按最小面写死）：
	 * CANCELLED → cancelled、DONE → succeeded；FAILED / REJECTED 别名不参与段态推导（返回 null）。
	 */
	private static String snapshotTerminalCode(String snapshotStatus) {
		if (RunSnapshot.CANCELLED.equals(snapshotStatus)) {
			return PendingToolCall.CANCELLED;
		}
		if (RunSnapshot.DONE.equals(snapshotStatus)) {
			return RunSnapshot.DONE;
		}
		return null;
	}

	/**
	 * R-144 T1/T3 → R-160 M1：{@code {status, kind?, decision?, decidedAt?, submittedValues?}} 载荷映射。
	 * R-160 M1：决策元数据三字段随历史载荷透传（前端刷新回执卡渲染真值）—— decision 由
	 * <b>条目态</b>派生（APPROVED → approved / REJECTED、REJECTED_ARGUMENTS → rejected；T2 升格
	 * EXECUTED 不抹掉已发生的决策）；decidedAt ← resolvedAtMs；submittedValues ← resultText 原文
	 * （限 kind==FRONTEND；前端 JSON.parse，失败兜底 null）。kind 无数据不写（禁臆造默认值）。
	 */
	private static Map<String, Object> toolCallState(PendingToolCall pending, String status) {
		Map<String, Object> state = new LinkedHashMap<>();
		state.put("status", status);
		// P0-1：历史段状态也带统一信封的 outcome/code（新增键，既有 status/kind 等不动）。
		// M1：未决条目（PENDING/APPROVED）不写 outcome —— 结局尚未产生，写 unknown 会让前端把
		// "等待中"映射成 failed（unknown→failed 的兜底），盖掉仍是 pending 的卡面。
		// 缺键 = 前端按 status 展示；只有已决状态才落 outcome。
		if (!PendingToolCall.PENDING.equals(status) && !PendingToolCall.APPROVED.equals(status)) {
			ToolOutcome persisted = ToolOutcome.fromWire(pending.getOutcome());
			state.put("outcome", (persisted == null ? ToolOutcome.fromStatus(status) : persisted).wire());
		}
		String code = pending.effectiveCode();
		if (code != null) {
			state.put("code", code);
		}
		String kind = pending.getKind();
		if (kind != null && !kind.isBlank()) {
			state.put("kind", kind);
		}
		String entryStatus = pending.getStatus();
		if (PendingToolCall.APPROVED.equals(entryStatus)) {
			state.put("decision", "approved");
		}
		else if (PendingToolCall.REJECTED.equals(entryStatus)
				|| PendingToolCall.REJECTED_ARGUMENTS.equals(entryStatus)) {
			state.put("decision", "rejected");
		}
		if (pending.getResolvedAtMs() > 0) {
			state.put("decidedAt", pending.getResolvedAtMs());
		}
		if (PendingToolCall.KIND_FRONTEND.equals(kind) && pending.getResultText() != null
				&& !pending.getResultText().isBlank()) {
			// 原文透传（非解析后对象）：解析与失败兜底在前端（R-160 M1 口径）
			state.put("submittedValues", pending.getResultText());
		}
		return state;
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
		// T5：正文消息边界三段式（start/content/end）——start 只在首段前补一次，
		// 后续每一片文本都是 content（delta）；end 在轮终态补（见 finish / cancelTerminal / fail）。
		//
		// S5c-4：发帧判据与累计判据必须同源 —— 这里此前用 StringUtils.hasText（"非空白才算内容"），
		// 而 Accumulator 无条件拼接每一个分片。于是模型单独吐一个纯空白分片（空格或换行，
		// 流式分片边界上很常见）时，message_end.chars 会大于 delta 拼接长度，前端渲染的正文
		// 与落定正文（finalText）分家。改为"非空即发"：空白也是正文的一部分，三处口径归一。
		if (text != null && !text.isEmpty()) {
			out.textStart();
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

		/**
		 * 计量器（上下文专项批①）：R1–R6 的规则本体在 {@link ContextMeter}，本类只负责喂它。
		 * 每次 attempt 新建 ⇒ 重试天然不串值（R6）。
		 */
		private final ContextMeter meter = new ContextMeter();

		private final Deque<String> tail = new ArrayDeque<>();

		private volatile String terminalSignal;

		void accept(ChatResponse response) {
			this.lastResponse.set(response);
			this.meter.accept(response);
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

		Map<String, Object> usage(int fallbackEstimate) {
			return this.meter.usage(fallbackEstimate);
		}

		ContextMeter meter() {
			return this.meter;
		}

		String model() {
			String fromMeter = this.meter.model();
			if (fromMeter != null) {
				return fromMeter;
			}
			ChatResponse response = this.lastResponse.get();
			return response != null && response.getMetadata() != null ? response.getMetadata().getModel() : null;
		}

	}

	// ── 上下文专项批①：压缩 / 计量 / 记账 ────────────────────────────────────

	/**
	 * 一轮的上下文装配锚点（批① 新增）。
	 *
	 * @param systemTokens   人设 + 工作区快照的估算（端点重建不从历史算）
	 * @param toolsTokens    本轮披露 schema 的估算
	 * @param inputTokens    本轮输入的估算
	 * @param promptEstimate 本轮请求的整包估算（只在<b>无真值</b>时作为回退展示值）
	 * @param limitTokens    分母（0 = 未知）
	 * @param model          本轮模型名
	 * @param limitTokens    分母（0 = 未知）
	 * @param compactionLevel 本轮压缩级别（{@code none}/{@code L1}/{@code L2}/{@code L3}）：<b>由压缩方显式交接</b>，
	 *                        不靠时刻反推 —— 续跑的压缩发生在 {@code ResumeService} 里（早于本方法），
	 *                        压完再驱动，"压缩时刻 ≥ 轮起点"这条比较必然为假 ⇒ 会恒报 {@code none}（I-1）
	 * @param turnStartedAtMs 轮起点（预算/超时口径用）
	 */
	private record TurnContext(int systemTokens, int toolsTokens, int inputTokens, int promptEstimate, int limitTokens,
			String model, String compactionLevel, long turnStartedAtMs) {
	}

	/**
	 * 压缩结果 + 后续动作（把"压没压、压成什么"与"发不发 context 帧"绑在一个值上，
	 * 避免调用方各自判断而漏发）。
	 */
	private record Compaction(List<Message> memory, int limitTokens, ContextCompactor.Outcome outcome) {

		boolean changed() {
			return this.outcome != null && this.outcome.changed();
		}

		/** 本轮级别（未装配压缩设施 / 未压缩 ⇒ {@code none}）。 */
		String levelWire() {
			return changed() ? this.outcome.levelWire() : "none";
		}

		/** 压缩发生时另发一条 {@code context} 帧（帧类型 15 → 16 的唯一新增；未压缩则一帧不发）。 */
		void emitIfChanged(SseChatEmitter out) {
			if (!changed()) {
				return;
			}
			out.context(ContextCompactor.framePayload(this.outcome));
		}

	}

	/**
	 * 请求前压缩（两个调用点之一；另一处见 {@code ResumeService}）。
	 *
	 * <p>
	 * <b>落库顺序 = 归档 → 记忆 → 快照</b>（与设计稿 §3.5 的"快照在前、记忆在后"相反，理由见
	 * {@link ContextCompactor} 类注的差异段）：{@code store.create} 失败时，残留的是
	 * <b>"记忆已压、快照未建"</b>（旧快照不存在 ⇒ 下次重试重新压，幂等）；反序会残留
	 * <b>"快照已压、记忆未压"</b>，这一轮模型看压缩后的历史、记忆窗口仍是原文，
	 * 续跑与下一轮各自再压一次且 pin 窗口不同 ⇒ 同一会话两条历史线（SM-01 同族）。
	 * 三步都对，"模型看到的 / 快照里的 / 记忆里的"才是同一份。
	 */
	private Compaction compactBeforeRequest(String runId, String sessionId, String model, Long providerId,
			List<Message> memory, int inputTokens, int systemTokens, int toolsTokens, int toolResultsSinceMeasure) {
		if (this.contextCompactor == null) {
			return new Compaction(memory, 0, null);
		}
		int limit = this.contextCompactor.limitTokens(providerId, model);
		if (memory == null || memory.isEmpty()) {
			return new Compaction(memory, limit, null);
		}
		ContextState state = this.contextCompactor.readState(sessionId);
		ContextCompactor.Outcome outcome = this.contextCompactor.compact(new ContextCompactor.Request(sessionId, runId,
				model, providerId, memory, this.codec.serializeAll(memory), state.usedTokens(),
				state.completionTokens(), inputTokens, toolResultsSinceMeasure, systemTokens, toolsTokens,
				protectedToolCallIds(runId, sessionId)));
		if (!outcome.changed()) {
			return new Compaction(memory, limit, outcome);
		}
		try {
			// 记忆整体覆盖写（与 convergeMemory 同一通道）：不写回的话下一轮又读到未压缩的那份。
			// 顺序（记忆先于快照）见方法注：`store.create` 失败时不留"快照已压、记忆未压"组合
			this.chatMemory.clear(sessionId);
			this.chatMemory.add(sessionId, outcome.messages());
		}
		catch (RuntimeException ex) {
			log.warn("压缩后记忆写回失败 sessionId={}：{}（快照仍写压缩后的那一份）", sessionId, ex.getMessage());
		}
		return new Compaction(outcome.messages(), limit, outcome);
	}

	/**
	 * 未决 HITL 条目的 toolCallId 集合（P3 的可执行投影：这些工具结果永不清除）。
	 *
	 * <p>取两处：本轮 runId（首轮通常为空）与会话当前挂起轮（{@code ai:session:current:}）——
	 * 后者覆盖"上一轮挂起未决、用户又发了新消息"这一真实场景。
	 */
	private Set<String> protectedToolCallIds(String runId, String sessionId) {
		Set<String> ids = new LinkedHashSet<>();
		collectPendingIds(ids, runId);
		try {
			String current = this.store.sessionCurrentRunId(sessionId);
			if (current != null && !current.equals(runId)) {
				collectPendingIds(ids, current);
			}
		}
		catch (RuntimeException ex) {
			log.debug("读会话当前轮失败 sessionId={}：{}", sessionId, ex.getMessage());
		}
		return ids;
	}

	private void collectPendingIds(Set<String> ids, String runId) {
		try {
			for (PendingToolCall pending : this.store.pendings(runId)) {
				boolean unresolved = PendingToolCall.PENDING.equals(pending.getStatus())
						|| (PendingToolCall.APPROVED.equals(pending.getStatus()) && !pending.isExecuted());
				if (unresolved && pending.getToolCallId() != null) {
					ids.add(pending.getToolCallId());
				}
			}
		}
		catch (RuntimeException ex) {
			log.debug("读未决条目失败 runId={}：{}", runId, ex.getMessage());
		}
	}

	/**
	 * 本轮披露 schema 的估算（上下文专项批①）。
	 *
	 * <p>暴露给 {@code ResumeService}：续跑侧没有 {@code ToolRegistry}，但它必须与首轮用<b>同一个</b>
	 * {@code tools} 口径 —— 否则两条路径的 {@code done.context} 分项会在同一会话里跳变。
	 */
	public int disclosedToolTokens(AiContext context) {
		return ContextEstimator.estimateToolSchemas(this.toolRegistry.forContext(context));
	}

	/**
	 * 续跑侧的压缩级别交接（I-1）。
	 *
	 * <p>
	 * 为什么需要这张表：续跑的压缩发生在 {@code ResumeService}（早于 {@code driveResumed}），
	 * 而"本轮压没压过"若用 {@code ai:ctx:} 的时刻去反推（压缩时刻 ≥ 轮起点）必然为假 ——
	 * 压完才交棒，轮起点比压缩时刻更晚。因此级别由压缩方<b>显式交接</b>，驱动侧读后即弃。
	 *
	 * <p>容量：一轮一个键、读完即删；即便某轮在交棒前失败，条目也只留到该轮结束（runId 不复用）。
	 */
	private final Map<String, String> compactionLevels = new ConcurrentHashMap<>();

	/**
	 * 记录续跑轮的压缩级别（由 {@code ResumeService} 在压缩成功后、交棒前调用）。
	 *
	 * @param level 实际级别（{@code none}/{@code L1}/{@code L2}/{@code L3}）；{@code null} 按 {@code none}
	 */
	public void noteCompaction(String runId, String level) {
		this.compactionLevels.put(runId, level == null ? "none" : level);
	}

	/**
	 * 丢弃某轮尚未被读取的压缩级别交接（由 {@code ResumeService} 在<b>交棒失败</b>时调用）。
	 *
	 * <p>
	 * 与 {@link #noteCompaction} 的区别是"没有下一手"：交棒被拒（{@code POOL_SATURATED}）这一轮
	 * 不会被驱动，{@code resumedTurn} 永远不会来取，留着只会让"一轮一个键、读完即删"的容量
	 * 论证失真。{@code noteCompaction(runId, null)} 写的是 {@code "none"}（仍占位），不是清除。
	 */
	public void clearCompaction(String runId) {
		this.compactionLevels.remove(runId);
	}

	/** 续跑侧的装配锚点（历史里的 system 即是人设 + 工作区快照的合成体；级别来自一次性交接）。 */
	private TurnContext resumedTurn(String runId, AiContext context, List<Message> history, String model,
			long turnStartedAtMs) {
		int systemTokens = 0;
		for (Message message : history) {
			if (message.getMessageType() == MessageType.SYSTEM) {
				systemTokens += ContextEstimator.estimate(message.getText());
			}
		}
		int toolsTokens = ContextEstimator.estimateToolSchemas(this.toolRegistry.forContext(context));
		int historyTokens = ContextEstimator.estimateMessages(history);
		int limit = this.contextCompactor == null ? 0 : this.contextCompactor.limitTokens(null, model);
		String level = this.compactionLevels.remove(runId);
		if (level == null) {
			level = "none";
		}
		return new TurnContext(systemTokens, toolsTokens, 0, systemTokens + toolsTokens + historyTokens, limit, model,
				level, turnStartedAtMs);
	}

	/**
	 * 轮末记账 + {@code done.context} 载荷（上一轮真值 → 本轮真值）。
	 *
	 * <p>
	 * 判"本轮压过没"用时刻比较：压缩发生在<b>轮起点</b>，故 {@code state.atMs >= turnStartedAtMs}
	 * 等价于"本轮压过"；没有压缩设施的进程返回 null（帧里不出现 {@code context} 键，
	 * "未装配"与"计量为 0" 因此可区分）。
	 */
	private Map<String, Object> contextTelemetry(String sessionId, TurnContext turn, Accumulator accumulator) {
		if (this.contextCompactor == null || turn == null) {
			return null;
		}
		ContextMeter meter = accumulator == null ? new ContextMeter() : accumulator.meter();
		boolean est = !meter.measured();
		int used = est ? Math.max(0, turn.promptEstimate()) : meter.promptTokens();
		int completion = est ? 0 : meter.completionTokens();
		ContextState before = this.contextCompactor.readState(sessionId);
		// 级别由压缩方交接（见 TurnContext#compactionLevel）：时刻反推在续跑轮必然误判为 none（I-1）
		String level = turn.compactionLevel();
		String model = meter.model() != null ? meter.model() : turn.model();
		if (meter.dirty() > 0) {
			// 脏 usage 要留痕：frames/dirty 是"这个数可信到什么程度"的唯一证据
			log.warn("usage 存在回退帧 runId 轮次计量 sessionId={} frames={} dirty={} 已采纳 prompt={}", sessionId,
					meter.frames(), meter.dirty(), used);
		}
		this.contextCompactor.recordTurn(sessionId, model, used, completion, before.turnNo() + 1, est,
				turn.systemTokens(), turn.toolsTokens(), turn.inputTokens());
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("usedTokens", used);
		payload.put("limitTokens", turn.limitTokens() > 0 ? turn.limitTokens() : null);
		payload.put("percent", turn.limitTokens() > 0 ? ContextBreakdownService.percent(used, turn.limitTokens()) : null);
		payload.put("level", level);
		payload.put("est", est);
		payload.put("circuit", before.circuit() == null ? "closed" : before.circuit());
		return payload;
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
		// 确认门已无等待上限（裁决 2026-10-20），故不再报告 confirmTimeout；
		// 前端工具键只对即时执行类前端工具有意义（generative_form 豁免）。
		out.put("frontendToolTimeout", String.valueOf(this.properties.getHitl().getFrontendToolTimeout()));
		out.put("watchdogTickMillis", StreamWatchdog.TICK_MILLIS);
		return out;
	}

	/** 供诊断：本轮的静默锚点与工具活动（看门狗判定的输入，取证用）。 */
	public Map<String, Object> guardState(String runId) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("toolActive", this.beacon.isActive(runId));
		out.put("toolStarted", this.beacon.startedCount(runId));
		out.put("lastToolActivityAtMs", this.beacon.lastActivityAtMs(runId));
		out.put("gateWaitMs", this.beacon.gateWaitMs(runId));
		out.put("cancelled", this.cancellations.isCancelled(runId));
		out.put("cancelPolls", this.cancellations.polls());
		out.put("budget", Duration.ofMillis(this.properties.getResilience().getTotalBudget().toMillis()).toString());
		return out;
	}

}
