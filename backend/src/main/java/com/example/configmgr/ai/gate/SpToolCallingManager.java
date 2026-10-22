package com.example.configmgr.ai.gate;

import com.example.configmgr.common.ErrorCodes;

import com.example.configmgr.ai.config.AiProperties;

import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.observe.AiTrace;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.StreamViolationException;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.resultview.ResultView;
import com.example.configmgr.ai.resultview.ToolResultViewProvider;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.FrontendToolGuard;
import com.example.configmgr.ai.tool.SheetAudit;
import com.example.configmgr.ai.tool.SheetSwitches;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolResultEnvelope;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.example.configmgr.common.ConflictException;
import com.example.configmgr.common.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 官方循环内的工具执行扩展点（S4.2 §2 {@code gate/SpToolCallingManager}）。
 *
 * <h2>组合而非替换（DC-05 官方能力优先）</h2>
 * 协议、循环、工具 Schema、入参解析全部由 Spring AI 负责
 * （{@code OpenAiChatModel} 内部 isToolExecutionRequired → executeToolCalls → 回填 → 再请求模型）；
 * 本类<b>组合</b>官方 {@link ToolCallingManager#builder() DefaultToolCallingManager}
 * 作为 delegate，只在每一次工具执行处插入四件事：
 * <ul>
 * <li><b>可见性钩子</b>：{@code tool_start} / {@code tool_result} 帧 —— 官方循环在
 * {@code returnDirect=false} 时<b>不把中间工具轮放进客户端流</b>（S4.2-P1 §4.4-3 实测：5 轮 10 次上游
 * 请求，客户端 0 个工具事件），因此工具卡片只能由这里提供；</li>
 * <li><b>防线③执行兜底</b>（FR-5.2 三重防线的第三道，DC-14 T1）：
 * 执行前用 {@link ToolRegistry#matchesContext} 复查"该工具在当前上下文里是否本就被披露"，
 * 越 scope 一律<b>不执行副作用</b>并把结构化错误结果回填模型（{@link #SCOPE_VIOLATION_CODE}）；</li>
 * <li><b>确认门</b>：风险等级 {@code DANGER} 的工具（读基座 {@link ToolRegistry} 的
 * {@code @ToolRisk} 分级）执行前挂起等人工决策（{@link ConfirmGate}）——<b>F5 起先预检再进门</b>：
 * 四个 {@code start_*} 在 {@code saveSuspended} 之前跑 {@link ToolPreflightService}，
 * <b>不通过就不进确认门、不弹卡</b>（结构化 {@code PRECHECK_FAILED} 回给模型），通过则预检报告随帧下发；
 * 预检本身受两道约束：越 scope 者<b>不预检</b>（先过防线③，走既有 {@code SCOPE_NOT_DISCLOSED} 路径），
 * 预检基础设施异常则按 {@code PRECHECK_UNAVAILABLE} <b>安全侧兜底</b>（同样不进门、不放行）；</li>
 * <li><b>前端工具挂起</b>：通道为 {@code FRONTEND} 的工具（{@code @ToolChannel}）不执行后端桩体，
 * 下发 {@code frontend_tool_request} 并阻塞等 {@code POST /api/ai/frontend-tool-result} 回灌；
 * 另有<b>第五件事</b>：声明了复核器的前端工具（{@link FrontendToolGuard}，DC-15）在<b>挂起之前</b>
 * 先过一道入参安全闸 —— 不合法就<b>不挂起、不下发</b>，只把结构化错误结果回填模型
 * （见 {@link #awaitFrontend}）。</li>
 * </ul>
 *
 * <h2>结果的两条通道（DC-14 T2）</h2>
 * 同一个工具结果有两份去向，<b>口径刻意不同</b>：
 * <ul>
 * <li><b>给模型看的</b>：{@code role:tool} 的 {@code responseData} —— 经
 * {@link ToolResultLimiter} 按 {@code app.ai.result-budget.model.max-rows|default-max-chars} 裁剪（防上下文膨胀）；</li>
 * <li><b>给前端/台账看的</b>：{@code tool_result} 帧的 {@code result} 与
 * {@code ai:ledger:<runId>} 的 {@code resultText} —— <b>全文</b>，不裁剪。</li>
 * </ul>
 *
 * <h2>硬规范①：外置先于阻塞</h2>
 * 进入任何等待之前，先把 {@code assistant(tool_calls)} 与每个待决条目写进 Redis
 * （{@link RunStore#saveSuspended}）；进程内的闸门（{@link ConfirmGate} 的 Latch）只做唤醒。
 * 写失败一律抛出 → 由调用方回落官方执行，绝不出现"阻塞住了但 Redis 里没有状态"。
 *
 * <h2>硬规范②：续跑必须重建完整历史（本类也守同一条）</h2>
 * 官方 {@code DefaultToolCallingManager} 返回的 {@code conversationHistory} 是
 * <b>{@code prompt.getInstructions() + [assistant(tool_calls), tool]}</b>
 * （1.1.7 字节码 {@code buildConversationHistoryAfterToolExecution} 实测；1.1.8 该类字节码与之逐字节一致）；
 * 若像 SP-02 的 PoC 那样只返回 {@code [assistant, tool]}，官方循环的下一跳请求就会丢掉
 * system/user —— 这正是规格所称"官方循环自身工具后续跑会丢 system/user"的<b>真正来源是 PoC 实现</b>，
 * 官方实现本身不丢。本类按官方口径拼装，因此：
 * <ul>
 * <li>本轮内每一跳请求都保留 system/user（P1 的 req13/15/17 报文为同一口径：4/6/8 条消息）；</li>
 * <li>{@link RunSnapshot#getMessageJson()} 写的就是模型实际看到的那串历史，续跑据此重建。</li>
 * </ul>
 *
 * <h2>不落地的路径（诚实回落）</h2>
 * 拿不到 {@code runId}（无 toolContext）或 Redis 里没有本轮快照时，挂起态无法外置，
 * 本类<b>原样委托官方执行</b>并留 WARN —— 宁可无 HITL，也不制造"挂起但不可恢复"的死轮。
 */
@Slf4j
@Component
public class SpToolCallingManager implements ToolCallingManager {

	/** 防线③拦截时回填模型的结果文本前缀（结构化错误码，测试与排障据此断言/检索）。 */
	public static final String SCOPE_VIOLATION_CODE = ErrorCodes.SCOPE_NOT_DISCLOSED;

	/**
	 * 工具执行段抛错时 {@code tool_call_end.code} 的取值（AI 日志最小补齐批）。
	 *
	 * <p>只出现在日志面（事件行），不进帧、不进台账 —— 它是"这一行 end 为什么没有真的结局"的解释，
	 * 而不是一个新的对外结局码（帧/台账的结局真值仍由条目自身的 outcome/code 承担）。
	 */
	public static final String CODE_TOOL_EXECUTION_THREW = "TOOL_EXECUTION_THREW";

	/**
	 * 步数到顶时的收尾指令（超时面治理批② 第 2 项；决策看板 {@code :14}）。
	 *
	 * <p>到顶<b>不判整轮失败</b>：把指令追加到历史末尾，让模型用已有信息收尾（部分完成也要说清缺什么）。
	 */
	public static final String STEP_LIMIT_WRAP_UP = "已达到本轮工具调用步数上限（app.ai.resilience.max-tool-steps）。"
			+ "请不要再次调用工具，直接用已经获得的信息给出结论；如有未完成的部分，如实说明哪些未完成以及原因。";

	/**
	 * 工具执行超时监视线程池（超时面治理批② 第 3 项）。
	 *
	 * <p>为什么需要一条独立线程：{@code Future#get(timeout)} 要在<b>调用线程</b>上等到点，
	 * 而桩体必须跑在<b>另一条线程</b>上才能被 {@code cancel(true)} 打断。容量取挂起池缺省值
	 * （{@code app.ai.suspend.pool-size} = 20）：一轮至多一个工具在跑，容量即并发上限。
	 * 队列为 {@link SynchronousQueue}（不排队）+ {@code AbortPolicy}：饱和时退化为"同步执行、无超时"
	 * 并留 WARN —— 宁可少一层兜底，也不让工具调用在队列里无限等。
	 */
	private static final int TOOL_TIMEOUT_THREADS = 20;

	private static final ExecutorService TOOL_TIMEOUT_EXECUTOR = newExecutor();

	private static ExecutorService newExecutor() {
		AtomicInteger counter = new AtomicInteger();
		ThreadPoolExecutor executor = new ThreadPoolExecutor(0, TOOL_TIMEOUT_THREADS, 30L, TimeUnit.SECONDS,
				new SynchronousQueue<>(), runnable -> {
					Thread thread = new Thread(runnable, "ai-tool-timeout-" + counter.incrementAndGet());
					thread.setDaemon(true);
					return thread;
				}, new ThreadPoolExecutor.AbortPolicy());
		return executor;
	}

	private final ToolCallingManager delegate;

	private final RunStore store;

	private final RunRegistry registry;

	private final ConfirmGate gate;

	private final ToolRegistry toolRegistry;

	private final MessageJsonCodec codec;

	private final ToolActivityBeacon beacon;

	private final CancellationRegistry cancellations;

	/** T2：工具结果回填模型前的行数/字符上限（前端帧与台账不受其影响）。 */
	private final ToolResultLimiter limiter;

	/**
	 * 前端工具的入参/回灌复核器（DC-15 安全闸）。<b>按工具名自认领</b>（{@link FrontendToolGuard#supports}），
	 * 因此本类对"哪个工具需要复核"零知识 —— 没声明复核器的前端工具行为一字不变。
	 */
	private final List<FrontendToolGuard> frontendGuards;

	/**
	 * F5 只读预检器（确认门<b>之前</b>问"能不能做"）。null = 不预检 —— 只有单元测试的轻装配面
	 * 会传 null（生产由容器注入唯一实例）；预检只对 {@link ToolPreflightService#supports(String)} 的工具生效。
	 */
	private final ToolPreflightService preflight;

	/**
	 * L1 资源互斥（B23L1 批③b）：执行前的任务级/作业级锁。null = 不取锁 —— 只有单元测试的
	 * 轻装配面会传 null（生产由容器注入唯一实例）。
	 */
	private final ToolResourceLocks resourceLocks;

	/** F2 目标资源硬校验（B23L1 批③b）。null = 不校验（轻装配面）。 */
	private final ToolTargetResolver targetResolver;

	/** UNKNOWN 对账器（B23L1 批③b）：续跑结算前把"未知"尽量收敛成台账里的确定结局。 */
	private final ToolUnknownReconciler unknownReconciler;

	/**
	 * 配置面（超时面治理批②）：{@code app.ai.resilience.max-tool-steps}（步数笼）与
	 * {@code app.ai.tool.exec-timeout}（工具独立超时）。
	 */
	private final AiProperties properties;

	/**
	 * 表格采样/改动审计面（AI 表格工具治理批）。null = 未注入（轻装配面 / 单元测试）——
	 * 此时只是少了计数与台账，日志字段照常发射（字段来自条目自身的 {@code sheetMeta}）。
	 */
	private SheetAudit sheetAudit;

	/**
	 * 结果视图投影器（S6 批 2）：{@code tool_result} 帧的 {@code resultView} 由它们按工具名自认领。
	 *
	 * <p>与 {@code FrontendToolGuard} 同体例：本类对"哪个工具有视图"零知识；空列表 = 一律不出视图
	 * （帧上连 {@code resultView} 键都不出现，旧卡面零变化）。
	 */
	private List<ToolResultViewProvider> resultViewProviders = List.of();

	/** 可选注入（{@code SheetAudit} 是观测面，不是判定面；缺失不影响任何工具的执行语义）。 */
	@Autowired(required = false)
	public void setSheetAudit(SheetAudit audit) {
		this.sheetAudit = audit;
	}

	/** 可选注入（视图投影是展示增强，不是判定面；缺失 = 所有工具走旧卡面）。 */
	@Autowired(required = false)
	public void setResultViewProviders(List<ToolResultViewProvider> providers) {
		this.resultViewProviders = providers == null ? List.of() : List.copyOf(providers);
	}

	public SpToolCallingManager(RunStore store, RunRegistry registry, ConfirmGate gate, ToolRegistry toolRegistry,
			MessageJsonCodec codec, ToolActivityBeacon beacon, CancellationRegistry cancellations,
			ToolResultLimiter limiter, List<FrontendToolGuard> frontendGuards) {
		this(store, registry, gate, toolRegistry, codec, beacon, cancellations, limiter, frontendGuards, null);
	}

	public SpToolCallingManager(RunStore store, RunRegistry registry, ConfirmGate gate, ToolRegistry toolRegistry,
			MessageJsonCodec codec, ToolActivityBeacon beacon, CancellationRegistry cancellations,
			ToolResultLimiter limiter, List<FrontendToolGuard> frontendGuards, ToolPreflightService preflight) {
		this(store, registry, gate, toolRegistry, codec, beacon, cancellations, limiter, frontendGuards, preflight,
				null, null, null);
	}

	public SpToolCallingManager(RunStore store, RunRegistry registry, ConfirmGate gate, ToolRegistry toolRegistry,
			MessageJsonCodec codec, ToolActivityBeacon beacon, CancellationRegistry cancellations,
			ToolResultLimiter limiter, List<FrontendToolGuard> frontendGuards, ToolPreflightService preflight,
			ToolResourceLocks resourceLocks, ToolTargetResolver targetResolver,
			ToolUnknownReconciler unknownReconciler) {
		this(store, registry, gate, toolRegistry, codec, beacon, cancellations, limiter, frontendGuards, preflight,
				resourceLocks, targetResolver, unknownReconciler, new AiProperties());
	}

	@Autowired
	public SpToolCallingManager(RunStore store, RunRegistry registry, ConfirmGate gate, ToolRegistry toolRegistry,
			MessageJsonCodec codec, ToolActivityBeacon beacon, CancellationRegistry cancellations,
			ToolResultLimiter limiter, List<FrontendToolGuard> frontendGuards, ToolPreflightService preflight,
			ToolResourceLocks resourceLocks, ToolTargetResolver targetResolver,
			ToolUnknownReconciler unknownReconciler, AiProperties properties) {
		this.delegate = ToolCallingManager.builder()
			// P0-1：让工具异常浮出到 executeOnce 的 catch（默认 alwaysThrow=false 会把失败吞成
			// "ok=true 的文本"——start_* 的 ConflictException 因此不可见）。包内 ToolExecutionException
			// 保留 cause，catch 侧按 cause 链取 ConflictException/ResourceNotFoundException 的码。
			.toolExecutionExceptionProcessor(DefaultToolExecutionExceptionProcessor.builder().alwaysThrow(true).build())
			.build();
		this.store = store;
		this.registry = registry;
		this.gate = gate;
		this.toolRegistry = toolRegistry;
		this.codec = codec;
		this.beacon = beacon;
		this.cancellations = cancellations;
		this.limiter = limiter;
		this.frontendGuards = frontendGuards == null ? List.of() : List.copyOf(frontendGuards);
		this.preflight = preflight;
		this.resourceLocks = resourceLocks;
		this.targetResolver = targetResolver;
		this.unknownReconciler = unknownReconciler;
		this.properties = properties == null ? new AiProperties() : properties;
	}

	@Override
	public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
		return this.delegate.resolveToolDefinitions(chatOptions);
	}

	@Override
	public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
		AssistantMessage assistant = chatResponse.getResult() == null ? null : chatResponse.getResult().getOutput();
		if (assistant == null || !assistant.hasToolCalls()) {
			return this.delegate.executeToolCalls(prompt, chatResponse);
		}

		String runId = runIdOf(prompt);
		RunSnapshot snapshot = runId == null ? null : this.store.get(runId);
		if (runId == null || snapshot == null) {
			// 仅防御：生产路径恒有 runId（ToolCallingChatOptions.toolContext 由服务层写入），
			// 故此分支不可达。注意 delegate 用的是 alwaysThrow=true —— 一旦真的走到这里，工具异常
			// 会穿出整轮（不落 tool_result 帧、不写台账），因为本类没有可外置的挂起态来做兜底。
			log.warn("工具执行处拿不到本轮 runId/快照（runId={}），挂起态无法外置，回落官方默认执行：{}",
					runId, assistant.getToolCalls().stream().map(AssistantMessage.ToolCall::name).toList());
			return this.delegate.executeToolCalls(prompt, chatResponse);
		}

		SseChatEmitter out = this.registry.of(runId);
		List<PendingToolCall> pendings = newPendings(assistant);
		// 步数笼（超时面治理批② 第 2 项 / 决策看板 :14）：起点来自**外置快照**而不是进程内计数 ——
		// 挂起后的续跑可能落在另一个进程上，进程内计数会被重置（"跨挂起不重置"只有外置才能成立）。
		int maxToolSteps = Math.max(1, this.properties.getResilience().getMaxToolSteps());
		int stepsUsed = snapshot.getToolSteps();
		// 防线③（T1）的判据来源：本轮<b>冻结</b>的工作区上下文（与请求侧披露用的同一份，
		// 见 RunStore#create 写下的 snapshot.context），因此复查不会因上下文漂移而误判。
		AiContext context = AiContext.fromMap(snapshot.getContext());

		// ===== F5：确认门之前先跑只读预检（不通过者根本不进门） =====
		// 用户 2026-10-20 直裁：预检不通过 → 不进确认门、不弹卡；结构化失败回给模型，由 AI 用普通消息
		// 说明原因与建议。因此"不通过"的条目在这里就地结算成 PRECHECK_FAILED（code 同名、
		// retryable=true），既不出现在 suspended 帧里，也不会被 arm 成闸门、不会发 confirm_request。
		List<PendingToolCall> suspended = new ArrayList<>(pendings.size());
		Set<String> preflightBlocked = new HashSet<>();
		for (PendingToolCall pending : pendings) {
			if (this.preflight != null && PendingToolCall.KIND_CONFIRM.equals(pending.getKind())
					&& ToolPreflightService.supports(pending.getName())
					// MINOR-1：防线③先于预检 —— 越 scope 的调用本就不该读任务/产出预检报告，
					// 跳过预检，交给执行循环里的既有 blockOutOfScope（SCOPE_NOT_DISCLOSED）。
					&& this.toolRegistry.matchesContext(pending.getName(), context)) {
				// MINOR-2：预检是挂着上游静默在跑的只读重活（如 IMPORT 的多文件 sha256），
				// 前后打"被动活动锚点"——只刷新看门狗的静默锚点，不计 startedCount（不污染重试判定）。
				this.beacon.markPassiveActivity(runId);
				ToolPreflightService.PreflightReport report;
				try {
					report = this.preflight.inspect(pending.getName(), pending.getArguments());
				}
				catch (RuntimeException ex) {
					// MINOR-4：预检基础设施异常 → 安全侧兜底：不放行、不判"预检未通过"、不进门。
					blockPreflightUnavailable(runId, pending, ex);
					preflightBlocked.add(pending.getToolCallId());
					continue;
				}
				finally {
					this.beacon.markPassiveActivity(runId);
				}
				if (!report.pass()) {
					blockPreflight(runId, pending, report);
					preflightBlocked.add(pending.getToolCallId());
					continue;
				}
				pending.setPreflight(report.asMap());
			}
			suspended.add(pending);
		}

		// ===== 挂起登记：只给「有时限」的前端工具算定 expiresAt（用户裁决 2026-10-20）=====
		// 顺序红线：saveSuspended 先于下方 awaitConfirm/awaitFrontend 发帧（confirm_request /
		// frontend_tool_request），若等发帧后再 set 则不落库 —— 此处算定并 set 后随 saveSuspended
		// 一并持久化；atMs 与 expiresAt 同帧单点取值，前端按 expiresAt − atMs 续算剩余时长。
		// 确认门与 generative_form 现在无上限（等人填/等人决策），故<b>不写</b>到期时刻：
		// 写了一个假的到期点，前端就会按它置灰按钮 —— 上限的口径只有一处（ConfirmGate#suspendWaitSeconds）。
		long suspendedAtMs = System.currentTimeMillis();
		for (PendingToolCall pending : suspended) {
			Integer waitSeconds = this.gate.suspendWaitSeconds(pending);
			if (waitSeconds != null && waitSeconds > 0) {
				pending.setAtMs(suspendedAtMs);
				pending.setExpiresAt(suspendedAtMs + waitSeconds * 1000L);
			}
		}

		// ===== F8：闸门预登记必须早于 suspended 帧 =====
		// 帧一到前端，确认卡就可被点击；此刻若闸门尚未登记，submitDecision 的 signal 返回 false，
		// 死卡兜底会把"等待还没进入"误判成"本进程无人等待"并派发续跑，与原循环双驱动同一轮。
		// CONFIRM 与 FRONTEND 两个通道都经 ConfirmGate.await 登记闸门，故两者一并预登记；
		// 正常路径由 await 的 finally 逐个回收，这里只兜住异常/提前返回（取消、越 scope、
		// 前端入参被拒、saveSuspended 抛错等）留下的未消费闸门，否则 hasActiveLatch 会永真。
		List<String> gateWaitIds = gateWaitToolCallIds(suspended);
		this.gate.arm(runId, gateWaitIds);
		try {
			// ===== 硬规范①：先把 assistant(tool_calls) 与待决条目外置，再进入任何等待 =====
			// 外置的是<b>本批全部</b>条目（含预检未通过的那些，它们已是终态），但帧只公告真正挂起的：
			// 预检未通过的条目不出现在 suspended.toolCalls[] 里，因而不会渲染成确认卡；
			// 全部都不通过时连 suspended 帧都不存在 —— 这正是"不进确认门、不弹卡"在帧面上的形态。
			if (!suspended.isEmpty()) {
				// 步数先落快照再挂起：跨进程续跑读的就是这份累计值（saveSuspended 会重新 get 快照）
				persistToolSteps(runId, stepsUsed);
				this.store.saveSuspended(runId, assistant.getText(), pendings, prompt.getInstructions(), this.codec);
				out.suspended(suspended, this.store.runKey(runId), this.store.pendingKey(runId),
						this.store.instanceId());
			}

			List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>(pendings.size());
			// P1-10 批策略：W/J/P 条目结算后若确定性失败（outcome=failed 且 retryable=false），
			// 本批剩余调用一律不再执行，逐个补结构化 SKIPPED（写库 + 发帧 + 模型 ToolResponse）。
			PendingToolCall haltedBy = null;
			for (PendingToolCall pending : pendings) {
				if (haltedBy != null) {
					responses.add(skipAsUpstreamFailed(runId, pending, haltedBy));
					// 日志：被批策略带过（未执行）的调用补一条结局行 —— 否则它只留下"没有 start"这种痕迹
					traceToolSkipped(runId, snapshot.getSessionId(), pending, ToolOutcome.CODE_UPSTREAM_FAILED);
					continue;
				}
				// 事件边界上的取消检查（ADR-8 修正③：取消在"分片/事件边界"轮询）：
				// 逐个工具执行前先看一次权威标志，命中即终止整轮（终态 done{cancelled:true}）。
				checkCancelled(runId);
				// F5：预检未通过的条目没有确认卡（没有 suspended/confirm_request），只有一张
				// "未执行"的失败工具卡 + 一条结局帧：前端据此显示原因，模型据此向用户解释。
				// 它不在"首错即停"的判据内（retryable=true，见 ToolOutcome#retryable）—— 用户修好可重试。
				if (preflightBlocked.contains(pending.getToolCallId())) {
					String text = frameText(pending.getResultText());
					out.toolStart(pending);
					out.toolResult(pending, false, false, text);
					responses.add(new ToolResponseMessage.ToolResponse(pending.getToolCallId(), pending.getName(),
							modelData(pending, text)));
					traceToolSkipped(runId, snapshot.getSessionId(), pending, pending.effectiveCode());
					continue;
				}
				// 步数笼：到顶后的调用不再执行，结算成 skipped/TOOL_STEP_LIMIT（不判整轮失败）
				if (stepsUsed >= maxToolSteps) {
					// 帧序：先 tool_start 再 tool_result（与预检未通过分支同形，不破帧序契约）
					out.toolStart(pending);
					responses.add(skipAsStepLimit(runId, pending, maxToolSteps));
					traceToolSkipped(runId, snapshot.getSessionId(), pending, ToolOutcome.CODE_TOOL_STEP_LIMIT);
					log.warn("工具步数到顶，本调用不再执行 runId={} tool={} toolCallId={} steps={}/{}", runId,
							pending.getName(), pending.getToolCallId(), stepsUsed, maxToolSteps);
					continue;
				}
				stepsUsed++;
				out.toolStart(pending);
				// 副作用信标（韧性棒）：工具开始执行即留下痕迹 ——
				// ① 让看门狗在工具执行期间不计静默（硬规范①）；② 让"已产出内容前先有副作用"的重试被拦住。
				this.beacon.enter(runId, pending.getName(), pending.getKind());
				// 日志链路批：工具生命周期的左端。这一行之后必然有对应的 tool_call_end（同一 toolCallId）
				AiTrace.eventOn(runId, snapshot.getSessionId(), "tool_call_start", "toolCallId", pending.getToolCallId(),
						"tool", pending.getName(), "kind", pending.getKind(),
						"approval", PendingToolCall.KIND_CONFIRM.equals(pending.getKind())
								|| PendingToolCall.KIND_FRONTEND.equals(pending.getKind()),
						"index", responses.size());
				long toolStartedAtMs = System.currentTimeMillis();
				String text;
				try {
					// ===== 防线③：执行前复查 scope（T1）。越 scope 一律不执行、不认领、不记台账 =====
					// AI 表格工具治理批：同一谓词的两个出口 —— 管理员关闸回 SHEET_DISABLED
					// （切页面无用，只能改配置重启），页面/步骤不对回 SCOPE_NOT_DISCLOSED。
					if (!this.toolRegistry.matchesContext(pending.getName(), context)) {
						text = this.toolRegistry.sheetDisabled(pending.getName())
								? blockSheetDisabled(runId, context, pending)
								: blockOutOfScope(runId, context, pending);
					}
					else {
						text = switch (pending.getKind()) {
							case PendingToolCall.KIND_FRONTEND -> awaitFrontend(runId, pending);
							case PendingToolCall.KIND_CONFIRM -> awaitConfirm(runId, prompt, pending, context);
							default -> executeOnce(runId, prompt, pending, context);
						};
					}
				}
				catch (RuntimeException ex) {
					// 执行段抛错（挂起态外置失败 / 等待被打断 / 执行器异常）：先补 end 行再让异常穿出去
					traceToolAborted(runId, snapshot.getSessionId(), pending, toolStartedAtMs, ex);
					throw ex;
				}
				finally {
					this.beacon.exit(runId, "done");
				}
				// 执行之后再查一次：确认门/前端工具可能正是在等待期间被取消
				try {
					checkCancelled(runId);
				}
				catch (RuntimeException ex) {
					// 取消发生在工具"已经开始"之后：此前只抛异常、不写 end ⇒ 日志里留一条孤儿 start
					traceToolAborted(runId, snapshot.getSessionId(), pending, toolStartedAtMs, ex);
					throw ex;
				}
				// R6 裁决同口径：信封的 outcome/code 必须在**结算之后**回读待决条目 —— 循环里的
				// pending 是等待前那份（status 仍 PENDING、outcome=null），拿它取信封会把成功写成
				// unknown。executeOnce/gate 写的是外置存储里的新副本，故此处重读（缺失则回落原对象）。
				PendingToolCall settled = this.store.pending(runId, pending.getToolCallId());
				PendingToolCall source = settled == null ? pending : settled;
				// 日志链路批：工具生命周期的右端 —— 结局分类沿用条目真值（不另造一套口径）
				// AI 日志最小补齐批：ms 含审批等待，execMs 把它扣掉（"工具慢"与"人在犹豫"必须可分）
				long totalToolMs = System.currentTimeMillis() - toolStartedAtMs;
				// AI 表格工具治理批（2026-10-22 §4.2）：表格工具的采样元数据挂同一行的 additive 字段
				// （8 键；非表格工具一个键都不写 —— "逐键可缺席"）。字段值一律来自条目上的
				// sheetMeta 白名单副本（SheetAudit#sanitize 在入口就丢掉了任何业务值）。
				Map<String, Object> sheetMeta = source.getSheetMeta();
				// 用 add 而不是 List.of：code/kind 允许为 null（成功条目没有 code），List.of 会 NPE
				List<Object> tracePairs = new ArrayList<>();
				tracePairs.add("toolCallId");
				tracePairs.add(pending.getToolCallId());
				tracePairs.add("tool");
				tracePairs.add(pending.getName());
				tracePairs.add("kind");
				tracePairs.add(pending.getKind());
				tracePairs.add("ms");
				tracePairs.add(totalToolMs);
				tracePairs.add("execMs");
				tracePairs.add(execMsOf(source, totalToolMs));
				tracePairs.add("skipped");
				tracePairs.add(false);
				tracePairs.add("ok");
				tracePairs.add(source.effectiveOutcome().ok());
				tracePairs.add("outcome");
				tracePairs.add(source.effectiveOutcome().wire());
				tracePairs.add("code");
				tracePairs.add(source.effectiveCode());
				tracePairs.add("executed");
				tracePairs.add(source.isExecuted());
				tracePairs.add("chars");
				tracePairs.add(text == null ? 0 : text.length());
				if (sheetMeta != null && !sheetMeta.isEmpty()) {
					tracePairs.addAll(SheetAudit.logPairs(sheetMeta));
				}
				AiTrace.eventOn(runId, snapshot.getSessionId(), "tool_call_end", tracePairs.toArray());
				// 台账 + 计数（同一时点；SheetAudit 未注入时只是少了观测面，判定语义不变）
				if (this.sheetAudit != null && sheetMeta != null && !sheetMeta.isEmpty()) {
					this.sheetAudit.record(snapshot.getSessionId(), source);
				}
				// T2：回填模型的是裁剪后的文本；上面的 text 本身（进帧、进台账）保持全文
				// P0-1：模型侧 = 统一信封 JSON（先裁剪 message，再组装，见 ToolResultEnvelope#toModelJson）
				// P1-10：执行中抛出异常的失败，executeOnce 已把对账措辞追加进 resultText
				// （帧/台账/模型三面同一份文本；"结果缺失"这类未执行副作用不追加）。
				responses.add(new ToolResponseMessage.ToolResponse(pending.getToolCallId(), pending.getName(),
						modelData(source, text)));
				// 停走判定放在结算之后：这里看到的 outcome/code 才是执行/确认门写下的真值。
				if (haltsBatch(source)) {
					haltedBy = source;
					log.warn("批策略首错即停 runId={} 上游工具={} outcome={} code={}，本批剩余 {} 个调用置 SKIPPED",
							runId, source.getName(), source.effectiveOutcome().wire(), source.effectiveCode(),
							Math.max(0, pendings.size() - responses.size()));
				}
			}

			ToolResponseMessage toolMessage = ToolResponseMessage.builder().responses(responses).build();
			// ===== 落定本轮：与官方 buildConversationHistoryAfterToolExecution 同构 =====
			List<Message> history = new ArrayList<>(prompt.getInstructions());
			history.add(assistant);
			history.add(toolMessage);
			// 步数到顶 ⇒ 降级「部分完成收尾」：追加一条收尾指令（不抛错、不判整轮失败）
			if (stepsUsed >= maxToolSteps) {
				history.add(new UserMessage(STEP_LIMIT_WRAP_UP));
			}
			persistToolSteps(runId, stepsUsed);
			settle(runId, history);

			return ToolExecutionResult.builder().conversationHistory(history).build();
		}
		finally {
			this.gate.disarm(runId, gateWaitIds);
		}
	}

	/**
	 * 本批会进入 {@link ConfirmGate} 等待的 toolCallId（{@code CONFIRM} 与 {@code FRONTEND} 都经
	 * {@code ConfirmGate.await} 登记闸门，故两者都要预登记 —— F8）。
	 */
	private static List<String> gateWaitToolCallIds(List<PendingToolCall> pendings) {
		List<String> ids = new ArrayList<>(pendings.size());
		for (PendingToolCall pending : pendings) {
			String kind = pending.getKind();
			if ((PendingToolCall.KIND_CONFIRM.equals(kind) || PendingToolCall.KIND_FRONTEND.equals(kind))
					&& pending.getToolCallId() != null) {
				ids.add(pending.getToolCallId());
			}
		}
		return ids;
	}

	// ── 批策略（P1-10） ─────────────────────────────────────────────────────

	/** 执行后失败的 message 追加语（副作用可能已发生，必须人工对账）。 */
	public static final String RECONCILIATION_NOTE = "该操作可能已产生部分副作用，需人工对账。";

	/**
	 * 批次首错即停的判据：类别 W/J/P，且结算后的结局是<b>确定性失败</b>
	 * （{@code outcome=failed} 且 {@code retryable=false}）。{@code retryable=true} 或
	 * {@code unknown} 一律不停 —— 前者还有改数据重试的空间，后者须先对账。
	 */
	public boolean haltsBatch(PendingToolCall pending) {
		if (pending == null) {
			return false;
		}
		ToolMeta.Category category = this.toolRegistry.categoryOf(pending.getName());
		if (category != ToolMeta.Category.W && category != ToolMeta.Category.J
				&& category != ToolMeta.Category.P) {
			return false;
		}
		ToolOutcome outcome = pending.effectiveOutcome();
		return outcome == ToolOutcome.FAILED && !outcome.retryable(pending.effectiveCode());
	}

	/**
	 * 为"已规划未执行"的调用补一条结构化 SKIPPED：写库、发 {@code tool_result} 帧、
	 * 返回回填模型的统一信封（每个 {@code toolCallId} 都必须有对应 ToolResponse）。
	 * 续跑路径与首轮共用本方法，因此重放同形。
	 */
	public ToolResponseMessage.ToolResponse skipAsUpstreamFailed(String runId, PendingToolCall pending,
			PendingToolCall upstream) {
		String message = skippedMessage(upstream.getName(), upstream.effectiveCode());
		pending.setStatus(PendingToolCall.SKIPPED);
		pending.setOutcome(ToolOutcome.SKIPPED.wire());
		pending.setCode(ToolOutcome.CODE_UPSTREAM_FAILED);
		pending.setReason(ToolOutcome.CODE_UPSTREAM_FAILED);
		pending.setExecuted(false);
		pending.setExecutedBy(null);
		pending.setResolvedAtMs(System.currentTimeMillis());
		pending.setResultText(message);
		this.store.putPending(runId, pending);
		this.registry.of(runId).toolResult(pending, false, false, message);
		log.warn("批策略跳过调用 runId={} tool={} toolCallId={}（上游 {} 失败：{}）", runId, pending.getName(),
				pending.getToolCallId(), upstream.getName(), upstream.effectiveCode());
		return new ToolResponseMessage.ToolResponse(pending.getToolCallId(), pending.getName(),
				modelData(pending, message));
	}

	/**
	 * 步数到顶的调用：结算成 {@code skipped/TOOL_STEP_LIMIT}（未执行、不占认领、不记台账），
	 * 帧照发（前端能看到"这步为什么没跑"），回填模型的是统一信封 + 收尾提示。
	 *
	 * <p>为什么是 {@link ToolOutcome#SKIPPED} 而不是 {@code FAILED}：这不是"执行出错"，
	 * 而是"预算用完了"—— 用 failed 会触发批次首错即停（{@link #haltsBatch}），
	 * 把"部分完成"变成"整批作废"，与"降级收尾"的裁决相反。
	 */
	public ToolResponseMessage.ToolResponse skipAsStepLimit(String runId, PendingToolCall pending, int maxSteps) {
		String message = "未执行：本轮工具调用步数已达上限（app.ai.resilience.max-tool-steps = " + maxSteps
				+ "）。请用已经获得的信息给出结论，并说明哪些部分未完成。";
		pending.setStatus(PendingToolCall.SKIPPED);
		pending.setOutcome(ToolOutcome.SKIPPED.wire());
		pending.setCode(ToolOutcome.CODE_TOOL_STEP_LIMIT);
		pending.setReason(ToolOutcome.CODE_TOOL_STEP_LIMIT);
		pending.setExecuted(false);
		pending.setExecutedBy(null);
		pending.setResolvedAtMs(System.currentTimeMillis());
		pending.setResultText(message);
		this.store.putPending(runId, pending);
		this.registry.of(runId).toolResult(pending, false, false, message);
		return new ToolResponseMessage.ToolResponse(pending.getToolCallId(), pending.getName(),
				modelData(pending, message));
	}

	/**
	 * 把累计步数写回外置快照（唯一真值）。
	 *
	 * <p>为什么单独写一次而不是只改 {@code settle()}：挂起路径（{@code saveSuspended}）自己会
	 * {@code get} 一份新快照再保存 —— 只在内存对象上自增，跨进程续跑读到的仍是旧值。
	 */
	private void persistToolSteps(String runId, int steps) {
		RunSnapshot fresh = this.store.get(runId);
		if (fresh == null || fresh.getToolSteps() == steps) {
			return;
		}
		fresh.setToolSteps(steps);
		this.store.save(fresh);
	}

	/** SKIPPED 的人读 message（模型信封/帧 result/续跑重放共用一份措辞）。 */
	private static String skippedMessage(String upstreamName, String upstreamCode) {
		String code = upstreamCode == null || upstreamCode.isBlank()
				? ToolOutcome.CODE_TOOL_EXECUTION_FAILED : upstreamCode;
		return "未执行：上游 " + upstreamName + " 失败（" + code + "），本调用已跳过";
	}

	// ── 三通道 ──────────────────────────────────────────────────────────────

	/**
	 * 工具执行耗时 = 总耗时 − 审批等待（AI 日志最小补齐批）。
	 *
	 * <p>只有"等人"的两类（{@code CONFIRM}/{@code FRONTEND}）才扣等待：扣的是
	 * {@code reqAtMs（请求帧送达）→ resolvedAtMs（决策落地）} 这一段。缺任一端（旧数据 / 未送达的卡）
	 * 就不扣 —— 宁可少扣也不要凭猜造一个数出来。
	 */
	private static long execMsOf(PendingToolCall source, long totalToolMs) {
		long total = Math.max(0L, totalToolMs);
		if (source == null) {
			return total;
		}
		boolean humanWait = PendingToolCall.KIND_CONFIRM.equals(source.getKind())
				|| PendingToolCall.KIND_FRONTEND.equals(source.getKind());
		if (!humanWait) {
			return total;
		}
		long requested = source.getReqAtMs();
		long resolved = source.getResolvedAtMs();
		if (requested <= 0L || resolved <= requested) {
			return total;
		}
		return Math.max(0L, total - (resolved - requested));
	}

	/**
	 * "没有执行"的三条路径（预检拦截 / 步数到顶 / 首错即停的连带跳过）的结局行。
	 *
	 * <p>它们<b>不发 start</b>：成对性只对真实执行成立。若也补 start，"start 没有 end"这个判据就会被
	 * 稀释成永远成对 —— 那正是本批要堵的洞。字段里 {@code skipped=true} 是机器可判的"没跑"。
	 */
	private static void traceToolSkipped(String runId, String sessionId, PendingToolCall pending, String code) {
		AiTrace.eventOn(runId, sessionId, "tool_call_end", "toolCallId", pending.getToolCallId(), "tool",
				pending.getName(), "kind", pending.getKind(), "ms", 0, "execMs", 0, "skipped", true, "ok", false,
				"outcome", pending.effectiveOutcome().wire(), "code", code == null ? "-" : code, "executed", false,
				"chars", 0);
	}

	/**
	 * "已经开始但没走到结算"的结局行：取消（事件边界轮询命中）与执行段异常。
	 *
	 * <p>此前这两条路径只抛异常、不写 end 行 ⇒ 日志里留下一条<b>只有 start 的孤儿调用</b>，
	 * 排查时无从判断"是卡住了还是被取消了"。
	 */
	private void traceToolAborted(String runId, String sessionId, PendingToolCall pending, long startedAtMs,
			RuntimeException error) {
		long ms = Math.max(0L, System.currentTimeMillis() - startedAtMs);
		boolean cancelled = error instanceof StreamViolationException violation
				&& StreamViolationException.CANCELLED.equals(violation.getCode());
		PendingToolCall settled = this.store.pending(runId, pending.getToolCallId());
		PendingToolCall source = settled == null ? pending : settled;
		AiTrace.eventOn(runId, sessionId, "tool_call_end", "toolCallId", pending.getToolCallId(), "tool",
				pending.getName(), "kind", pending.getKind(), "ms", ms, "execMs", ms, "skipped", false, "ok", false,
				"outcome", cancelled ? ToolOutcome.CANCELLED.wire() : ToolOutcome.FAILED.wire(),
				"code", cancelled ? ToolOutcome.CODE_RUN_CANCELLED : CODE_TOOL_EXECUTION_THREW,
				"executed", source.isExecuted(), "chars", 0, "cancelled", cancelled);
	}

	/** 事件边界上的取消检查：命中即抛出，交由 {@code ResilientChatService} 以取消收尾。 */
	private void checkCancelled(String runId) {
		if (this.cancellations.isCancelled(runId)) {
			throw new StreamViolationException(StreamViolationException.CANCELLED,
					"本轮对话已被取消，工具执行在事件边界终止（runId=" + runId + "）");
		}
	}

	/**
	 * F5 预检未通过：就地结算成"未进确认门、未执行"的终态条目（code={@code PRECHECK_FAILED}、
	 * retryable=true），只留一条失败结局帧 —— 确认卡与闸门一概不存在。
	 *
	 * <p><b>为什么状态用 {@link PendingToolCall#PRECHECK_FAILED} 而不是 BLOCKED</b>：BLOCKED 的卡面文案
	 * 是前端写死的"不在披露范围内"（{@code parts.ts} 的 tool_result 分支按 {@code status==='BLOCKED'}
	 * 落阻断口径），预检失败挂上去会显示错误原因；而本状态在 tool_result 帧上落到既有的
	 * {@code outcome=failed} 分支，前端零改动即可显示"失败 + 完整原因"。续跑口径两者一致
	 * （{@link #resolvePending} 对非 PENDING/APPROVED 一律复用 resultText、不执行）。
	 */
	private void blockPreflight(String runId, PendingToolCall pending, ToolPreflightService.PreflightReport report) {
		long now = System.currentTimeMillis();
		String text = report.failureMessage();
		pending.setPreflight(report.asMap());
		pending.setStatus(PendingToolCall.PRECHECK_FAILED);
		pending.setOutcome(ToolOutcome.FAILED.wire());
		pending.setCode(ToolOutcome.CODE_PRECHECK_FAILED);
		pending.setReason(ToolOutcome.CODE_PRECHECK_FAILED);
		pending.setExecuted(false);
		pending.setExecutedBy(null);
		pending.setRequestedAtMs(now);
		pending.setResolvedAtMs(now);
		pending.setResultText(text);
		this.store.putPending(runId, pending);
		log.warn("预检未通过，不进入确认门 runId={} tool={} 未通过项={}（未挂起、未发确认请求、未执行）", runId,
				pending.getName(),
				report.failures().stream().map(ToolPreflightService.PreflightReport.Check::name).toList());
	}

	/**
	 * 预检基础设施不可用（MINOR-4 的<b>安全侧兜底</b>）：{@code preflight.inspect} 自身抛错时，
	 * 既不放行、也不冒充"预检未通过"—— 结算成 {@code code=PRECHECK_UNAVAILABLE} 的可重试失败，
	 * <b>同样不进确认门、不弹卡、不执行</b>。宁可让模型如实说"暂时查不了、请稍后再试"，
	 * 也不能把数据库/文件系统抖动变成一次未经检查的放行。
	 */
	private void blockPreflightUnavailable(String runId, PendingToolCall pending, RuntimeException error) {
		long now = System.currentTimeMillis();
		String text = "预检暂不可用（" + ToolOutcome.CODE_PRECHECK_UNAVAILABLE
				+ "）：预检过程发生内部错误，本次未进入确认门、未执行 —— 这不代表条件已满足。"
				+ "请稍后重试；若持续出现请联系维护人员。";
		pending.setStatus(PendingToolCall.PRECHECK_FAILED);
		pending.setOutcome(ToolOutcome.FAILED.wire());
		pending.setCode(ToolOutcome.CODE_PRECHECK_UNAVAILABLE);
		pending.setReason(ToolOutcome.CODE_PRECHECK_UNAVAILABLE);
		pending.setExecuted(false);
		pending.setExecutedBy(null);
		pending.setRequestedAtMs(now);
		pending.setResolvedAtMs(now);
		pending.setResultText(text);
		this.store.putPending(runId, pending);
		log.error("预检基础设施异常，未进入确认门 runId={} tool={}（未挂起、未发确认请求、未执行）", runId,
				pending.getName(), error);
	}

	/**
	 * 防线③拦截（T1）：工具在当前上下文里未披露（越 scope）——<b>不产生任何副作用</b>
	 * （不认领、不执行、不记台账），只把结构化错误结果回填给模型，让人机都能看见"这次没执行"。
	 *
	 * <p>
	 * 为什么必须回填而不是静默丢弃/抛异常：
	 * <ul>
	 * <li>抛异常 → 整轮以 {@code error} 收尾，一次越权调用会把用户已看到的正文一并废掉（代价过大）；</li>
	 * <li>静默不回填 → 模型看不到任何反馈，可能反复重试同一个越权调用；</li>
	 * <li>回填结构化错误 → 模型读到"未执行 + 原因 + 当前上下文 + 该怎么办"，
	 * 官方循环继续（模型如实解释并改走已披露的工具或提示用户切页面）。</li>
	 * </ul>
	 */
	private String blockOutOfScope(String runId, AiContext context, PendingToolCall pending) {
		String text = SCOPE_VIOLATION_CODE + "：工具 " + pending.getName() + " 未执行 —— 它不在当前工作区上下文"
				+ "（" + describeContext(context) + "）的披露范围内（FR-5.2 三道防线的执行兜底）。"
				+ "请改用系统当前披露给你的工具；若用户确实要做这件事，请提示其先切换到对应的页面/步骤或任务，再重新发起。";
		pending.setStatus(PendingToolCall.BLOCKED);
		pending.setOutcome(ToolOutcome.BLOCKED.wire());
		pending.setCode(SCOPE_VIOLATION_CODE);
		pending.setReason(SCOPE_VIOLATION_CODE);
		pending.setExecuted(false);
		pending.setExecutedBy(null);
		pending.setResolvedAtMs(System.currentTimeMillis());
		pending.setResultText(text);
		this.store.putPending(runId, pending);
		this.registry.of(runId).toolResult(pending, false, false, text);
		log.warn("防线③拦截越 scope 工具调用 runId={} tool={} 上下文={}（未执行、未认领、未记台账）",
				runId, pending.getName(), describeContext(context));
		return text;
	}

	/**
	 * 表格开关拦截（AI 表格工具治理方案 §2.4）：该工具被管理员关闸 ——<b>不执行、不认领、不记台账</b>，
	 * 回填结构化失败（{@code outcome=failed}、{@code code=SHEET_DISABLED}）。
	 *
	 * <p>
	 * 与 {@link #blockOutOfScope} 的三点差异都是刻意的：
	 * <ol>
	 * <li><b>码不同</b>：{@code SHEET_DISABLED} 不复用 {@code SCOPE_NOT_DISCLOSED} —— 后者在前端卡面
	 * 渲染成「不在披露范围内」，会把"功能被关了"误导成"用户点错了页面"；</li>
	 * <li><b>文案不同</b>：读闸关说"未读任何数据"（防模型编造行数），写闸关说"未做任何修改"
	 * （与既有写侧失败文案同句式）；</li>
	 * <li><b>模型侧多一句可行动提示</b>："不要重试；请让用户联系管理员…" —— 开关是进程内一次绑定，
	 * 换参数重试必然同样失败，与"首错即停"同姿态。</li>
	 * </ol>
	 * 状态用 {@link PendingToolCall#SHEET_DISABLED}（单列）而不是 {@code BLOCKED}：前者在
	 * {@code tool_result} 帧上落 {@code outcome=failed} 分支，前端零改动即可显示完整原因。
	 */
	private String blockSheetDisabled(String runId, AiContext context, PendingToolCall pending) {
		String name = pending.getName();
		boolean readSide = SheetSwitches.READ_TOOLS.contains(name);
		String human = readSide ? SheetSwitches.readDisabledText() : SheetSwitches.writeDisabledText();
		String text = ToolOutcome.CODE_SHEET_DISABLED + "：工具 " + name + " 未执行 —— " + human
				+ "（管理员关闭了 app.ai.sheet." + (readSide ? "read-enabled" : "write-enabled")
				+ "；当前上下文 " + describeContext(context) + "）。"
				+ "不要重试；请让用户联系管理员开启表格" + (readSide ? "读取" : "写入")
				+ (readSide ? "，或改用导出后自行查看。" : "，或改用导出后自行编辑。");
		pending.setStatus(PendingToolCall.SHEET_DISABLED);
		pending.setOutcome(ToolOutcome.FAILED.wire());
		pending.setCode(ToolOutcome.CODE_SHEET_DISABLED);
		pending.setReason(ToolOutcome.CODE_SHEET_DISABLED);
		pending.setExecuted(false);
		pending.setExecutedBy(null);
		pending.setResolvedAtMs(System.currentTimeMillis());
		pending.setResultText(text);
		this.store.putPending(runId, pending);
		this.registry.of(runId).toolResult(pending, false, false, text);
		if (this.sheetAudit != null) {
			// 应恒 0；非 0 = 有旧缓存调用或在途残留（方案 §4.4 的告警口径）
			this.sheetAudit.disabledReject();
		}
		log.warn("表格工具被开关拦截 runId={} tool={} 上下文={}（未执行、未认领、未记台账）",
				runId, name, describeContext(context));
		return text;
	}

	/** 上下文的人读形态（错误文本与日志共用）。 */
	private static String describeContext(AiContext context) {
		StringBuilder sb = new StringBuilder();
		sb.append("page=").append(context.getPage() == null || context.getPage().isBlank() ? "未指定" : context.getPage());
		sb.append(", taskType=").append(context.getTaskType() == null ? "无" : context.getTaskType());
		sb.append(", step=").append(context.getStep() == null ? "无" : context.getStep());
		if (context.getTaskId() != null) {
			sb.append(", taskId=").append(context.getTaskId());
		}
		return sb.toString();
	}

	/**
	 * 前端工具：先过入参安全闸（DC-15），再挂起等前端回灌（结局帧由 ConfirmGate 侧发出）。
	 *
	 * <p>闸门（{@link FrontendToolGuard#inspectArguments}）不通过时<b>不挂起</b>：不发
	 * {@code frontend_tool_request}、不占等待上限、前端看不到这份非法入参；结构化错误结果
	 * （机器可读码 + 原因清单）作为该次调用的工具结果回填模型，官方循环照常继续
	 * ——模型读到"哪一处不合规"后可以改一版再试（而不是白等 120s 超时）。
	 */
	private String awaitFrontend(String runId, PendingToolCall pending) {
		FrontendToolGuard.Verdict verdict = inspectFrontendArguments(pending);
		if (verdict != null && !verdict.accepted()) {
			return rejectFrontendArguments(runId, pending, verdict);
		}
		PendingToolCall resolved = this.gate.awaitFrontendResult(runId, pending);
		return resolved.getResultText() == null ? "" : resolved.getResultText();
	}

	/** 第一个认领该工具名的复核器的入参判定；没有复核器返回 null（= 该工具不受闸门约束）。 */
	private FrontendToolGuard.Verdict inspectFrontendArguments(PendingToolCall pending) {
		for (FrontendToolGuard guard : this.frontendGuards) {
			if (guard.supports(pending.getName())) {
				return guard.inspectArguments(pending.getArguments());
			}
		}
		return null;
	}

	/**
	 * 入参被安全闸拒绝（DC-15）：与防线③（{@link #blockOutOfScope}）同一手法 ——
	 * <b>不产生任何副作用</b>（不挂起、不下发前端、不认领、不记台账），只把结构化错误结果
	 * 回填模型，让人机都能看见"这次没执行、为什么"。
	 *
	 * <p>结局帧用 {@code frontend_tool_result}（ok=false）：这次调用走的是前端通道且就此收敛，
	 * 与"前端超时"（{@code FRONTEND_TIMEOUT}）同帧型 —— 前端不必为"没被问到的那次调用"再解析
	 * 一种新帧；待决条目不留在 {@code PENDING}（否则续跑会把它当成"等人/等前端"）。
	 */
	private String rejectFrontendArguments(String runId, PendingToolCall pending, FrontendToolGuard.Verdict verdict) {
		String text = verdict.render();
		pending.setStatus(PendingToolCall.REJECTED_ARGUMENTS);
		pending.setOutcome(ToolOutcome.REJECTED.wire());
		pending.setCode(verdict.code());
		pending.setReason(verdict.code());
		pending.setExecuted(false);
		pending.setExecutedBy(null);
		pending.setResolvedAtMs(System.currentTimeMillis());
		pending.setResultText(text);
		this.store.putPending(runId, pending);
		this.registry.of(runId).frontendToolResult(pending, false, text);
		log.warn("前端工具入参被安全闸拒绝 runId={} tool={} code={}（未挂起、未下发、未执行）：{}",
				runId, pending.getName(), verdict.code(), verdict.reasons());
		return text;
	}

	/** 确认门：放行则执行（经资源锁、认领与台账，恰好一次），拒绝（或历史遗留的 TIMEOUT 记录）以"未执行"语义回填并继续循环。 */
	private String awaitConfirm(String runId, Prompt prompt, PendingToolCall pending, AiContext context) {
		PendingToolCall resolved = this.gate.awaitDecision(runId, pending);
		if (PendingToolCall.APPROVED.equals(resolved.getStatus())) {
			return executeOnce(runId, prompt, resolved, context);
		}
		String text = resolved.getResultText() == null ? "该操作未执行。" : resolved.getResultText();
		this.registry.of(runId).toolResult(resolved, false, false, text);
		return text;
	}

	/** 工具被"别人正在做同类动作"挡下时的固定口径（模型/帧/台账三面同文；禁 emoji）。 */
	public static final String RESOURCE_LOCKED_TEXT = ToolOutcome.CODE_RESOURCE_LOCKED
			+ "：该任务已有作业在执行，本次未执行。请等待该作业收尾后重新发起（资源锁会随对方收尾释放）。";

	/** 工具被"目标资源不成立"挡下时的固定口径（细节由 {@link ToolTargetResolver} 给出）。 */
	public static String targetInvalidText(ToolTargetResolver.Verdict verdict) {
		String detail = verdict == null || verdict.detail() == null || verdict.detail().isBlank() ? "目标资源不成立"
				: verdict.detail();
		return ToolOutcome.CODE_TARGET_INVALID + "：目标资源不存在或不在当前任务内，本次未执行（" + detail
				+ "）。请核对任务 ID 与当前页面/步骤后重新发起。";
	}

	/**
	 * 后端执行（含放行后的敏感工具）：<b>对账/重放 → 目标校验 → 资源锁 → 认领 → 执行 → 台账</b>。
	 *
	 * <ol>
	 * <li><b>对账/重放</b>：{@code ai:idem:<runId>} 命中即回台账原结果（不取锁、不认领、不执行、无副作用）；
	 * 命中的结局是 {@code unknown} 就<b>保持 unknown</b>（B23L1 批③b U4：旧实现把它当成功重放）；</li>
	 * <li><b>目标资源硬校验</b>（F2）：不成立 → {@code blocked/TARGET_INVALID}，<b>不占认领</b>；</li>
	 * <li><b>资源互斥</b>（L1）：任务级 → 作业级；被别人持有 → {@code blocked/RESOURCE_LOCKED}
	 * （不占认领、不抛、不断流）；设施不可用 → 放行 + WARN；</li>
	 * <li><b>原子认领</b>三态：{@code WON} 执行；{@code LOST} 二次对账（命中按重放回原结果，未命中按既有
	 * "已认领"文案）；{@code STALE_FENCE} 独立结论（"写回权已转移"，不再写"由实例 null 认领"）；</li>
	 * <li><b>执行 → appendLedger（带 idemKey）</b>，资源锁在 {@code finally} 归还（只还本次创建的那一任）。</li>
	 * </ol>
	 */
	private String executeOnce(String runId, Prompt prompt, PendingToolCall pending) {
		return executeOnce(runId, prompt, pending, contextOf(runId));
	}

	private String executeOnce(String runId, Prompt prompt, PendingToolCall pending, AiContext context) {
		// ① 对账/重放：命中台账 ⇒ 原结果复用（三面同源），任何副作用都不发生
		Map<String, Object> ledger = ledgerOf(runId, pending.getToolCallId());
		if (ledger != null) {
			return replayLedger(runId, pending, ledger);
		}

		// ② F2：目标资源硬校验（参数指向的任务必须存在、类型对得上、且在同一个工作区任务内）
		if (this.targetResolver != null) {
			ToolTargetResolver.Verdict verdict = this.targetResolver.check(pending.getName(),
					pending.getArguments(), context);
			if (!verdict.valid()) {
				return settleBlocked(runId, pending, ToolOutcome.CODE_TARGET_INVALID, targetInvalidText(verdict));
			}
		}

		// ③ L1：资源互斥（固定顺序：先任务级、后作业级）
		ToolResourceLocks.LockHandle locks = acquireResourceLocks(runId, pending);
		if (locks.heldElsewhere()) {
			return settleBlocked(runId, pending, ToolOutcome.CODE_RESOURCE_LOCKED, RESOURCE_LOCKED_TEXT);
		}
		try {
			return claimAndExecute(runId, prompt, pending);
		}
		finally {
			// 终态与异常路径都必须归还（只放本次创建的那一任；重入没有释放权）
			locks.close();
		}
	}

	/**
	 * 认领（三态）通过后的执行段。
	 *
	 * <p>认领<b>首次即走三态口</b> {@link RunStore#tryClaimExecution}（B23L1 批③b 复审 M-4）：
	 * 一次调用同时拿到结论、epoch 与 fence，不再"先布尔口、失败后再补一次三态口"（那会把
	 * {@code HSETNX} 与 fence 的 {@code GET} 各做两遍）。布尔口 {@link RunStore#claimExecution}
	 * 仍保留给既有装配面/用例，但本路径不再依赖它。
	 *
	 * <p>轻装配面（Mockito 底座只桩了布尔口）从三态口读回 null —— 这不是生产形态（两个实现都返回非 null），
	 * 回落到布尔口只是为了不改动既有的 mock 用例。
	 */
	private String claimAndExecute(String runId, Prompt prompt, PendingToolCall pending) {
		RunStore.ClaimResult claim = this.store.tryClaimExecution(runId, pending.getToolCallId(),
				this.store.instanceId());
		if (claim == null) {
			claim = this.store.claimExecution(runId, pending.getToolCallId(), this.store.instanceId())
					? new RunStore.ClaimResult(RunStore.ClaimOutcome.WON, 0L, 0L)
					: new RunStore.ClaimResult(RunStore.ClaimOutcome.LOST, 0L, 0L);
		}
		if (!claim.won()) {
			if (claim.outcome() == RunStore.ClaimOutcome.STALE_FENCE) {
				long epoch = claim.epoch();
				long fence = claim.fence();
				return settleBlocked(runId, pending, ToolOutcome.CODE_ALREADY_CLAIMED,
						"本轮写回权已转移，本次未执行（本实例持 epoch " + epoch + "，fence 已推进到 " + fence
								+ "，续跑权已被接管）。如需重做，请让用户在接管方完成后重新发起。");
			}
			// I4：认领失败后<b>再对一次台账</b> —— 对方可能恰好在这两步之间写完台账
			Map<String, Object> ledger = ledgerOf(runId, pending.getToolCallId());
			if (ledger != null) {
				return replayLedger(runId, pending, ledger);
			}
			String claimedBy = this.store.claimedBy(runId, pending.getToolCallId());
			String text = "该操作已由实例 " + claimedBy + " 认领执行（进程在其完成前退出）。"
					+ "为避免重复副作用，本次不再执行；如需重做，请让用户重新发起。";
			pending.setOutcome(ToolOutcome.BLOCKED.wire());
			pending.setCode(ToolOutcome.CODE_ALREADY_CLAIMED);
			pending.setExecuted(true);
			pending.setExecutedBy(claimedBy);
			pending.setResultText(text);
			this.store.putPending(runId, pending);
			this.registry.of(runId).toolResult(pending, false, true, frameText(text));
			return text;
		}

		String text;
		boolean ok = true;
		boolean timedOut = false;
		try {
			text = executeWithTimeout(prompt, pending);
			if (text == null) {
				// orElse("") 出口：官方执行器没有回填该 toolCallId 的结果（既非成功也非已知失败）。
				text = "";
				ok = false;
				pending.setOutcome(ToolOutcome.FAILED.wire());
				pending.setCode(ToolOutcome.CODE_TOOL_RESULT_MISSING);
			}
			else {
				pending.setOutcome(ToolOutcome.OK.wire());
				pending.setCode(null);
			}
		}
		catch (ToolExecutionTimeoutException ex) {
			// 超时面治理批② 第 3 项：独立执行超时 ⇒ outcome=timeout / code=TOOL_TIMEOUT / executed=false。
			// 不抛给调用方（轮继续）、不 appendLedger（未判定为"执行过"），但保留对账提示
			// （桩体可能已经产生部分副作用 —— 只是"到点没回来"与"执行成功"必须区分）。
			log.warn("工具执行超时 runId={} tool={} toolCallId={} 上限={}", runId, pending.getName(),
					pending.getToolCallId(), ex.getTimeout());
			ok = false;
			timedOut = true;
			text = ex.getMessage() + "\n" + RECONCILIATION_NOTE;
			pending.setOutcome(ToolOutcome.TIMEOUT.wire());
			pending.setCode(ToolOutcome.CODE_TOOL_TIMEOUT);
		}
		catch (Exception ex) {
			log.warn("工具执行失败 runId={} name={}", runId, pending.getName(), ex);
			ok = false;
			String code = failureCode(ex);
			// P1-10：执行过程中抛错 = 副作用可能已部分发生 —— 对账措辞写进同一份 resultText
			// （帧、台账、模型信封、续跑重放四处同形）。"结果缺失"不是执行中失败，不追加。
			text = "工具执行失败：" + ex.getMessage() + "\n" + RECONCILIATION_NOTE;
			pending.setOutcome(ToolOutcome.FAILED.wire());
			pending.setCode(code);
		}
		if (timedOut) {
			return settleToolTimeout(runId, pending, text);
		}
		this.store.appendLedger(runId, pending, text);

		PendingToolCall fresh = this.store.pending(runId, pending.getToolCallId());
		if (fresh == null) {
			fresh = pending;
		}
		fresh.setOutcome(pending.getOutcome());
		fresh.setCode(pending.getCode());
		fresh.setResultText(text);
		fresh.setExecuted(true);
		fresh.setExecutedBy(this.store.instanceId());
		fresh.setExecutedAtMs(System.currentTimeMillis());
		fresh.setReason(PendingToolCall.KIND_BACKEND.equals(fresh.getKind()) ? "IN_PROCESS_EXECUTION" : fresh.getReason());
		if (PendingToolCall.KIND_BACKEND.equals(fresh.getKind())) {
			fresh.setStatus(PendingToolCall.EXECUTED);
			fresh.setResolvedAtMs(fresh.getExecutedAtMs());
		}
		this.store.putPending(runId, fresh);
		// 无视图时走**原四参调用**（帧调用面与既有用例逐字不变）；有视图才用 additive 五参重载
		ResultView resultView = resultViewOf(fresh, text, ok);
		if (resultView == null) {
			this.registry.of(runId).toolResult(fresh, ok, false, frameText(text));
		}
		else {
			this.registry.of(runId).toolResult(fresh, ok, false, frameText(text), resultView);
		}
		return text;
	}

	/**
	 * 视图投影（S6 批 2）：按工具名自认领，只对<b>成功</b>结局出视图。
	 *
	 * <p>三条硬口径：① 视图是 UI 通道，模型侧读的仍是 {@code resultText}（本方法不参与任何裁剪）；
	 * ② 投影器异常一律吞掉并 WARN —— 展示增强不得让工具结果帧发不出去；③ 未认领 ⇒ null
	 * （帧上不多键，旧卡面逐字不变）。
	 */
	private ResultView resultViewOf(PendingToolCall pending, String text, boolean ok) {
		if (!ok || this.resultViewProviders.isEmpty()) {
			return null;
		}
		for (ToolResultViewProvider provider : this.resultViewProviders) {
			if (!provider.supports(pending.getName())) {
				continue;
			}
			try {
				ResultView view = provider.build(pending.getToolCallId(), pending.getName(), text);
				if (view != null) {
					return view;
				}
			}
			catch (RuntimeException ex) {
				log.warn("resultView 构造失败（本次不出视图，卡面走旧形态）：tool={} toolCallId={}",
						pending.getName(), pending.getToolCallId(), ex);
			}
		}
		return null;
	}

	/**
	 * 带独立超时执行一次工具（超时面治理批② 第 3 项；{@code app.ai.tool.exec-timeout}）。
	 *
	 * <p>形态 = 提交到独立平台线程 + {@code Future#get(timeout)}；到点 {@code cancel(true)} 并抛
	 * {@link ToolExecutionTimeoutException}（由调用方结算成 {@code timeout/TOOL_TIMEOUT}）。
	 * <b>诚实边界</b>：{@code cancel(true)} 只能"请求中断"—— 不响应中断的桩体（纯 CPU 循环、
	 * 不可中断的 IO）会继续在池线程上跑完，只是结果被丢弃；这正是"到点必须收口"的代价，
	 * 也是把超时做成 opt-out（长任务免超时）之前必须先登记的理由。
	 *
	 * <p>线程池饱和（{@code AbortPolicy}）时退化为<b>同步执行、无超时</b> + WARN：宁可少一层兜底，
	 * 也不能让工具调用卡在一个排队等槽位的状态里。
	 */
	private String executeWithTimeout(Prompt prompt, PendingToolCall pending) {
		Duration timeout = this.properties.getTool().getExecTimeout();
		if (timeout == null || timeout.isZero() || timeout.isNegative()) {
			return delegateSingle(prompt, pending);
		}
		Future<String> future;
		try {
			// MDC 不随线程池继承：锚点（runId/sessionId）随任务进池，工具日志才不会变成孤儿行
			future = TOOL_TIMEOUT_EXECUTOR.submit(AiTrace.wrapCallable(() -> delegateSingle(prompt, pending)));
		}
		catch (RejectedExecutionException ex) {
			log.warn("工具超时监视线程池饱和，退化为同步执行（本次无独立超时）tool={} toolCallId={}", pending.getName(),
					pending.getToolCallId());
			return delegateSingle(prompt, pending);
		}
		try {
			return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException ex) {
			future.cancel(true);
			throw new ToolExecutionTimeoutException(timeout, pending.getName());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new StreamViolationException(StreamViolationException.CANCELLED,
					"工具执行等待被中断，本轮按取消收尾（tool=" + pending.getName() + "）");
		}
		catch (ExecutionException ex) {
			Throwable cause = ex.getCause() == null ? ex : ex.getCause();
			if (cause instanceof RuntimeException runtime) {
				throw runtime;
			}
			throw new IllegalStateException(cause);
		}
	}

	/**
	 * 超时结算（{@code executed=false} + 结局帧 + 回填模型）：<b>不写台账</b>、不占重放索引 ——
	 * 台账记的是"执行过且知道结局"的事实，超时的结局是"不知道有没有副作用"，由
	 * {@link #RECONCILIATION_NOTE} 提醒人工对账。
	 */
	private String settleToolTimeout(String runId, PendingToolCall pending, String text) {
		PendingToolCall fresh = this.store.pending(runId, pending.getToolCallId());
		if (fresh == null) {
			fresh = pending;
		}
		fresh.setOutcome(ToolOutcome.TIMEOUT.wire());
		fresh.setCode(ToolOutcome.CODE_TOOL_TIMEOUT);
		fresh.setReason(ToolOutcome.CODE_TOOL_TIMEOUT);
		fresh.setResultText(text);
		fresh.setExecuted(false);
		fresh.setExecutedBy(null);
		fresh.setResolvedAtMs(System.currentTimeMillis());
		fresh.setStatus(PendingToolCall.TIMEOUT);
		this.store.putPending(runId, fresh);
		this.registry.of(runId).toolResult(fresh, false, false, frameText(text));
		log.warn("工具执行超时已结算（executed=false，轮继续）runId={} tool={} toolCallId={}", runId,
				pending.getName(), pending.getToolCallId());
		return text;
	}

	/** 工具执行超时的内部信号（不穿出本类：调用方一律结算成 {@code timeout/TOOL_TIMEOUT}）。 */
	private static final class ToolExecutionTimeoutException extends RuntimeException {

		private final Duration timeout;

		ToolExecutionTimeoutException(Duration timeout, String toolName) {
			super("工具执行超时（app.ai.tool.exec-timeout = " + timeout + "）：" + toolName
					+ " 在时限内没有返回，本次调用已中止（未判定为执行成功）");
			this.timeout = timeout;
		}

		Duration getTimeout() {
			return this.timeout;
		}

	}

	/**
	 * 台账读口：{@code idemFor}（O(1) 索引）优先，未命中回落 {@code ledgerFor}（全表扫描 / 旧数据）。
	 *
	 * <p>为什么两跳都要：真实实现的 {@code idemFor} 内部已经回落过一次，这里的第二跳只保护
	 * <b>轻装配面</b>（只桩了旧读口的单元底座），语义上"台账才是判据"也因此只有一个出口。
	 * 空表按未命中处理 —— 一条台账记录至少带 {@code atMs/toolCallId/name/outcome} 四键。
	 */
	private Map<String, Object> ledgerOf(String runId, String toolCallId) {
		Map<String, Object> indexed = this.store.idemFor(runId, toolCallId);
		if (indexed != null && !indexed.isEmpty()) {
			return indexed;
		}
		Map<String, Object> scanned = this.store.ledgerFor(runId, toolCallId);
		return scanned == null || scanned.isEmpty() ? null : scanned;
	}

	/**
	 * 台账重放（首轮对账命中 / 认领失败后二次对账命中 / 续跑复跑同一 {@code toolCallId}）：三面同源，
	 * <b>不执行任何副作用</b>。
	 *
	 * <p>结局口径（B23L1 批③b U4）：台账里有 {@code outcome} 就原样重放；只有<b>旧台账缺该键</b>
	 * 才按成功复用（历史兼容）。{@code outcome=unknown} 是<b>确定的"未判定"</b>—— 把它写成成功
	 * 会让"执行了但不知道结果"变成"执行成功"，前端的失败/对账提示随之消失。
	 */
	private String replayLedger(String runId, PendingToolCall pending, Map<String, Object> ledger) {
		String reusedText = String.valueOf(ledger.getOrDefault("resultText", ""));
		Object rawOutcome = ledger.get("outcome");
		ToolOutcome replayed = ToolOutcome.fromWire(rawOutcome == null ? null : String.valueOf(rawOutcome));
		boolean legacyLedger = replayed == null; // 旧台账无 outcome 键 ⇒ 保持批②之前的兼容口径
		if (legacyLedger) {
			replayed = ToolOutcome.OK;
		}
		pending.setOutcome(replayed.wire());
		pending.setCode(legacyLedger || ledger.get("code") == null ? null : String.valueOf(ledger.get("code")));
		pending.setExecuted(true);
		Object executedBy = ledger.get("executedBy");
		pending.setExecutedBy(executedBy == null ? this.store.instanceId() : String.valueOf(executedBy));
		pending.setResultText(reusedText);
		this.store.putPending(runId, pending);
		// 帧的 ok 必须是 outcome 的派生值（旧实现恒传 true，会把台账里的失败/未知结局写成成功帧）
		this.registry.of(runId).toolResult(pending, replayed.ok(), true, frameText(reusedText));
		log.info("工具调用命中台账，按原结果重放 runId={} tool={} toolCallId={} outcome={}（未取锁/未认领/未执行）",
				runId, pending.getName(), pending.getToolCallId(), replayed.wire());
		return reusedText;
	}

	/**
	 * 阻断的<b>统一出口</b>（资源锁冲突 / 目标不成立 / 写回权转移）：落库为终态、发一条
	 * {@code ok=false} 的结局帧、回填模型，然后正常返回 —— <b>不 appendLedger、不占认领、不抛异常、
	 * 不动 suspended</b>，同批后续调用照常（{@code haltsBatch} 只认 FAILED）。
	 */
	private String settleBlocked(String runId, PendingToolCall pending, String code, String text) {
		pending.setStatus(PendingToolCall.BLOCKED);
		pending.setOutcome(ToolOutcome.BLOCKED.wire());
		pending.setCode(code);
		pending.setExecuted(false);
		pending.setExecutedBy(null);
		pending.setResolvedAtMs(System.currentTimeMillis());
		pending.setResultText(text);
		this.store.putPending(runId, pending);
		this.registry.of(runId).toolResult(pending, false, false, frameText(text));
		log.warn("工具调用被阻断 runId={} tool={} toolCallId={} code={}（未执行、未占认领、未记台账）", runId,
				pending.getName(), pending.getToolCallId(), code);
		return text;
	}

	/** 抢本次调用所需的资源锁（不可锁的工具/无 taskId ⇒ 空句柄，零往返）。 */
	private ToolResourceLocks.LockHandle acquireResourceLocks(String runId, PendingToolCall pending) {
		if (this.resourceLocks == null || !ToolResourceLocks.supports(pending.getName())) {
			return ToolResourceLocks.LockHandle.none(ToolResourceLocks.LockOutcome.DISABLED);
		}
		Long taskId = ToolTargetResolver.taskIdOf(pending.getArguments());
		if (taskId == null || taskId <= 0L) {
			return ToolResourceLocks.LockHandle.none(ToolResourceLocks.LockOutcome.DISABLED);
		}
		String owner = this.store.instanceId() + ":" + runId + ":" + pending.getToolCallId();
		return this.resourceLocks.acquire(taskId, ToolTargetResolver.jobTypeOf(pending.getName()).name(), owner);
	}

	/** 本轮冻结的工作区上下文（与执行路径用的是同一份快照，见 {@link RunSnapshot#getContext()}）。 */
	private AiContext contextOf(String runId) {
		RunSnapshot snapshot = this.store.get(runId);
		return snapshot == null ? AiContext.empty() : AiContext.fromMap(snapshot.getContext());
	}

	/**
	 * 从异常（可能被官方 {@code ToolExecutionException} 包裹）提取机器可读码：
	 * {@code ConflictException} / {@code ResourceNotFoundException} 的码优先，否则
	 * {@link ToolOutcome#CODE_TOOL_EXECUTION_FAILED}。
	 */
	private static String failureCode(Throwable error) {
		Throwable current = error;
		for (int depth = 0; current != null && depth < 8; depth++) {
			if (current instanceof ConflictException conflict
					&& conflict.getCode() != null && !conflict.getCode().isBlank()) {
				return conflict.getCode();
			}
			if (current instanceof ResourceNotFoundException notFound
					&& notFound.getCode() != null && !notFound.getCode().isBlank()) {
				return notFound.getCode();
			}
			if (current.getCause() == current) {
				break;
			}
			current = current.getCause();
		}
		return ToolOutcome.CODE_TOOL_EXECUTION_FAILED;
	}

	/** 用官方 DefaultToolCallingManager 执行单个工具调用（只借官方执行，不接管协议）。 */
	String delegateSingle(Prompt prompt, PendingToolCall pending) {
		AssistantMessage.ToolCall call = new AssistantMessage.ToolCall(pending.getToolCallId(),
				pending.getType() == null ? "function" : pending.getType(), pending.getName(), pending.getArguments());
		AssistantMessage single = AssistantMessage.builder().toolCalls(List.of(call)).build();
		ToolExecutionResult result = this.delegate.executeToolCalls(prompt,
				new ChatResponse(List.of(new Generation(single))));
		return result.conversationHistory()
			.stream()
			.filter(ToolResponseMessage.class::isInstance)
			.map(ToolResponseMessage.class::cast)
			.flatMap(message -> message.getResponses().stream())
			.filter(response -> pending.getToolCallId() != null && pending.getToolCallId().equals(response.id()))
			.map(ToolResponseMessage.ToolResponse::responseData)
			.findFirst()
			.orElse(null);
	}

	/**
	 * 模型侧的统一信封 JSON（P0-1）：先对 message 跑 {@link ToolResultLimiter#forModel}，
	 * 再组装/序列化（顺序不可交换 —— 先组装后截断会把 JSON 本身截坏）。
	 */
	private String modelData(PendingToolCall pending, String text) {
		return ToolResultEnvelope.of(pending.effectiveOutcome(), pending.effectiveCode(), text,
				pending.getArguments()).toModelJson(this.limiter, pending.getName());
	}

	// ── 辅助 ────────────────────────────────────────────────────────────────

	/**
	 * 续跑阶段结算一个挂起条目（{@code ResumeService} 的重建路径专用，SP-02 §5.5）。
	 *
	 * <ul>
	 * <li>{@code PENDING} + {@code BACKEND}：后端工具不需要外部输入，直接补执行（进程死在
	 * "已写条目、未执行"之间时的正常路径）；</li>
	 * <li>{@code APPROVED}：补执行恰好一次（E2 窗口）；</li>
	 * <li>其余（{@code EXECUTED}/{@code REJECTED}/{@code TIMEOUT}/{@code FRONTEND_RESULT}/
	 * {@code FRONTEND_CANCELLED}/{@code REJECTED_ARGUMENTS}/{@code BLOCKED}/{@code PRECHECK_FAILED}）：
	 * 一律复用 {@code resultText}，<b>不执行</b>。</li>
	 * </ul>
	 *
	 * @return 该条目对应的工具结果文本（用于重建 {@code role:tool} 消息）
	 */
	public String resolvePending(String runId, Prompt prompt, PendingToolCall pending) {
		String status = pending.getStatus();
		boolean shouldExecute = PendingToolCall.APPROVED.equals(status)
				|| (PendingToolCall.PENDING.equals(status) && PendingToolCall.KIND_BACKEND.equals(pending.getKind()))
				|| (PendingToolCall.EXECUTED.equals(status) && !pending.isExecuted());
		if (shouldExecute) {
			return executeOnce(runId, prompt, pending);
		}
		// B23L1 批③b：已终态但结局是 unknown 的条目，先经三跳对账（台账 → 认领 → 资源锁）再回填 ——
		// 台账里有确定结局就采用它（含原文与执行者），否则如实保持 UNKNOWN 并给出"为什么"。
		if (this.unknownReconciler != null && pending.resolved()
				&& pending.effectiveOutcome() == ToolOutcome.UNKNOWN
				&& !PendingToolCall.KIND_FRONTEND.equals(pending.getKind())) {
			return applyReconciliation(runId, pending);
		}
		String text = pending.getResultText() == null ? "" : pending.getResultText();
		log.debug("续跑结算复用结果 runId={} toolCallId={} status={} executedBy={}", runId, pending.getToolCallId(),
				status, pending.getExecutedBy());
		return text;
	}

	/**
	 * 把一次 UNKNOWN 对账的结论写回条目并返回回填文本。
	 *
	 * <p>只有 {@link ToolUnknownReconciler.Reconciliation#adopted()}（台账命中且结局非 unknown）才覆盖
	 * {@code outcome/code/executedBy}；其余情况保持 UNKNOWN，只更新人读文案 —— 不确定性不能被对账器
	 * "猜"成成功。
	 */
	private String applyReconciliation(String runId, PendingToolCall pending) {
		ToolUnknownReconciler.Reconciliation reconciliation = this.unknownReconciler.reconcile(runId, pending);
		if (reconciliation == null) {
			return pending.getResultText() == null ? "" : pending.getResultText();
		}
		String text = reconciliation.text() == null ? "" : reconciliation.text();
		if (reconciliation.adopted()) {
			pending.setOutcome(reconciliation.outcome().wire());
			pending.setCode(reconciliation.code());
			pending.setExecuted(true);
			if (reconciliation.executedBy() != null) {
				pending.setExecutedBy(reconciliation.executedBy());
			}
		}
		pending.setResultText(text);
		this.store.putPending(runId, pending);
		log.info("续跑结算经 UNKNOWN 对账 runId={} toolCallId={} adopted={} outcome={}", runId,
				pending.getToolCallId(), reconciliation.adopted(), pending.effectiveOutcome().wire());
		return text;
	}

	/** 挂起种类由基座元数据决定：通道优先（前端工具不可被后端执行），其次风险等级。 */
	private List<PendingToolCall> newPendings(AssistantMessage assistant) {
		List<PendingToolCall> out = new ArrayList<>();
		for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
			PendingToolCall pending = new PendingToolCall();
			pending.setToolCallId(call.id());
			pending.setName(call.name());
			pending.setType(call.type() == null ? "function" : call.type());
			pending.setArguments(call.arguments());
			pending.setKind(kindOf(call.name()));
			out.add(pending);
		}
		return out;
	}

	private String kindOf(String toolName) {
		if (this.toolRegistry.channelOf(toolName) == ToolMeta.Channel.FRONTEND) {
			return PendingToolCall.KIND_FRONTEND;
		}
		if (this.toolRegistry.riskOf(toolName) == ToolMeta.RiskLevel.DANGER) {
			return PendingToolCall.KIND_CONFIRM;
		}
		return PendingToolCall.KIND_BACKEND;
	}

	/** 落定本轮：历史进快照、in-flight 清空、状态回到 RUNNING（工具已全部结算）。 */
	private void settle(String runId, List<Message> history) {
		RunSnapshot snapshot = this.store.get(runId);
		if (snapshot == null) {
			return;
		}
		snapshot.setMessageJson(this.codec.serializeAll(history));
		snapshot.setInFlightToolCalls(new ArrayList<>());
		snapshot.setAssistantContent(null);
		snapshot.setStatus(RunSnapshot.RUNNING);
		snapshot.setRound(snapshot.getRound() + 1);
		this.store.save(snapshot);
	}

	private String runIdOf(Prompt prompt) {
		if (prompt.getOptions() instanceof ToolCallingChatOptions options && options.getToolContext() != null) {
			Object runId = options.getToolContext().get("runId");
			return runId == null ? null : String.valueOf(runId);
		}
		return null;
	}

	/**
	 * 帧里的结果文本（T2 后<b>不再截断</b>）。
	 *
	 * <p>
	 * S4.2 时期这里是 800 字符截断 —— 与 T2 的"两条通道分离"口径相反（截断属于<b>模型侧</b>的
	 * 上下文成本控制，前端与台账要的是完整结果）。因此本方法现在只做 null 归一；
	 * 模型侧的上限统一由 {@link ToolResultLimiter} 在回填 {@code role:tool} 时施加。
	 */
	private String frameText(String text) {
		return text == null ? "" : text;
	}

	/** 供诊断端点/日志：本管理器确实被装配。 */
	public String describe() {
		return "delegate=" + this.delegate.getClass().getName() + ", instanceId=" + this.store.instanceId();
	}

}
