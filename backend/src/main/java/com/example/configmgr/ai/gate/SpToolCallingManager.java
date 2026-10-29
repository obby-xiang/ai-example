package com.example.configmgr.ai.gate;

import com.example.configmgr.common.ErrorCodes;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.context.CompactionFuse;
import com.example.configmgr.ai.context.ContextEstimator;
import com.example.configmgr.ai.context.ContextLimitResolver;
import com.example.configmgr.ai.context.ContextMeter;
import com.example.configmgr.ai.context.ContextState;
import com.example.configmgr.ai.context.ContextStateStore;
import com.example.configmgr.ai.context.ContextTrigger;
import com.example.configmgr.ai.context.HardTrim;
import com.example.configmgr.ai.context.ToolResultClearing;
import com.example.configmgr.ai.context.ToolResultShrink;
import com.example.configmgr.ai.context.TurnBudgetPlan;

import com.example.configmgr.ai.form.GenerativeFormRules;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.memory.ReasoningOrigin;
import com.example.configmgr.ai.observe.AiTrace;
import com.example.configmgr.ai.observe.SettlementObservationStore;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunParkedException;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.StreamViolationException;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.resultview.ResultView;
import com.example.configmgr.ai.resultview.ToolResultViewProvider;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tenant.RunIdentityScope;
import com.example.configmgr.ai.tenant.TenantUser;
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
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 * 作为 delegate，只在每一次工具执行处插入五件事
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
 * <li><b>轮内上下文预算</b>（工具轮内上下文预算批）：每批工具结束、下一次上游调用之前，
 * 用 {@link TurnBudgetPlan} 判一次"下一发请求会不会越窗" —— 达 L1 线只清旧工具结果
 * （本地动作，无模型调用），达收尾线追加收尾指令并<b>武装</b>本轮（此后工具一律不执行），
 * 估算已吃掉整窗时<b>先 settle 已执行记录、再报 {@code CONTEXT_OVERFLOW}</b>，不发注定失败的那一发。
 * 轮首的压缩语义不变（{@code ContextCompactor} 仍是唯一的轮首入口）。</li>
 * </ul>
 *
 * <h2>批间动态披露（多步工作流批）</h2>
 * 披露面有两个时刻，<b>刻意不同</b>：
 * <ul>
 * <li><b>批内冻结</b>：本方法一进来就把 {@code snapshot.context} 读成一份 {@code context}，
 * 同批所有调用的预检/防线③/目标校验都用它 —— 于是"导航成功后同批立刻选配置"仍被
 * {@code SCOPE_NOT_DISCLOSED} 拒绝（一批工具不允许自我扩权）；</li>
 * <li><b>批间刷新</b>：逐条<b>成功结算</b>后由 {@link RunContextRefresher} 把
 * {@code navigate_to}/{@code create_task}/{@code confirm_step} 的真实结果写回快照上下文
 * （导航/建任务更新页面与任务，推进步骤只认任务权威 {@code currentStep}；失败只 WARN、保留旧值）；
 * {@link #resolveToolDefinitions} 每跳按<b>当前快照</b>重算 {@code ToolRegistry#forContext}，
 * 下一批的 scope 判据与披露面才换成新的。执行侧若遇到"本批请求边界之外的新披露回调"，
 * 用当前 callbacks 副本补齐（{@link #executionPrompt}）保证官方委托能解析 ——
 * 这份副本<b>只解决可解析性，不构成授权</b>：能不能执行仍由防线③与各自确认门决定。</li>
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
 * <h2>快照缺失时的执行路径（危险工具不得直执行）</h2>
 * 拿不到 {@code runId}（无 toolContext）或 Redis 里没有本轮快照时，挂起态无法外置。旧实现在这里
 * <b>原样委托官方执行</b>，于是 DANGER 工具会绕过确认门直接执行。现在的口径分两步：
 * <ol>
 * <li><b>先恢复</b>：{@code toolContext} 里的本轮权威执行信封（{@link ToolExecutionEnvelope}）
 *     若存在且由<b>本实例创建</b>，就用它恢复快照（round/步数/预算原值带回，不写 {@code ai:seq}），
 *     随后走正常预检 → {@code SUSPENDED}/pending → {@code confirm_request} → 批准续跑；</li>
 * <li><b>恢复不了才拒绝</b>：危险/前端通道工具一律不执行（{@code blocked}/TOOL_KIND_MISMATCH，
 *     不弹卡、不认领、不写台账），只有 READ + BACKEND 的调用仍可回落官方执行（无副作用）。
 *     宁可少做一次只读查询，也不制造"未经确认的高危副作用"。</li>
 * </ol>
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
	 * 轮内上下文预算达收尾线时的指令（工具轮内上下文预算批）。
	 *
	 * <p>与 {@link #STEP_LIMIT_WRAP_UP} 同形（不判整轮失败：让模型用已有信息作答并说明缺什么），
	 * 但触发条件不同 —— 步数是"调用次数"，本指令是"下一发请求会越窗"。措辞里的
	 * "不要再次调用工具"同时是给模型的软约束；硬约束由 {@code ToolActivityBeacon#budgetArmed}
	 * 承担（armed 后工具一律不执行）。
	 */
	public static final String TURN_BUDGET_WRAP_UP = "本轮上下文已接近模型窗口上限（app.ai.context.limit-tokens）。"
			+ "请不要再次调用工具，直接用已经获得的信息给出结论；如有未完成的部分，如实说明哪些未完成以及原因。";

	/**
	 * 工具执行超时监视线程池（超时面治理批② 第 3 项）。
	 *
	 * <p>为什么需要一条独立线程：{@code Future#get(timeout)} 要在<b>调用线程</b>上等到点，
	 * 而桩体必须跑在<b>另一条线程</b>上才能被 {@code cancel(true)} 打断。容量取挂起池配置
	 * {@code app.ai.suspend.pool-size}（缺省 20）：一轮至多一个工具在跑，容量即并发上限。
	 * 队列为 {@link SynchronousQueue}（不排队）+ {@code AbortPolicy}：容量由
	 * {@code app.ai.suspend.pool-size} 派生（缺省 {@value #TOOL_TIMEOUT_THREADS_DEFAULT}），
	 * 饱和时<b>不执行、也不降级成无超时同步执行</b> —— 明确结算成"本次未执行"
	 * （{@code TOOL_TIMEOUT} 口径，见 {@link #executeWithTimeout}）。宁可让模型知道这一拍没跑，
	 * 也不能让一次调用脱离超时笼（N3）。
	 */
	private static final int TOOL_TIMEOUT_THREADS_DEFAULT = 20;

	/**
	 * 按容量共享的执行器：容量派生自配置，<b>同容量复用</b>（避免每个管理器实例各建一条池）。
	 *
	 * <p>daemon + 核心线程 0 + 30s keepAlive：空闲即回收，不阻止 JVM 退出。
	 */
	private static final Map<Integer, ExecutorService> TOOL_TIMEOUT_EXECUTORS = new ConcurrentHashMap<>();

	/** 配置容量（{@code app.ai.suspend.pool-size}），非法值回落缺省 20。 */
	private static int toolTimeoutThreads(AiProperties properties) {
		int configured = properties == null ? 0 : properties.getSuspend().getPoolSize();
		return configured > 0 ? configured : TOOL_TIMEOUT_THREADS_DEFAULT;
	}

	private static ExecutorService toolTimeoutExecutor(int threads) {
		return TOOL_TIMEOUT_EXECUTORS.computeIfAbsent(threads, SpToolCallingManager::newExecutor);
	}

	private static ExecutorService newExecutor(int threads) {
		AtomicInteger counter = new AtomicInteger();
		ThreadPoolExecutor executor = new ThreadPoolExecutor(0, threads, 30L, TimeUnit.SECONDS,
				new SynchronousQueue<>(), runnable -> {
					Thread thread = new Thread(runnable, "ai-tool-timeout-" + counter.incrementAndGet());
					thread.setDaemon(true);
					return thread;
				}, new ThreadPoolExecutor.AbortPolicy());
		return executor;
	}

	/** 本实例的超时执行器（同容量共享；见 {@link #TOOL_TIMEOUT_EXECUTORS}）。 */
	private final ExecutorService toolTimeoutExecutor;

	/** 本实例的执行器容量（日志与饱和结算文案用）。 */
	private final int toolTimeoutThreads;

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
	 * 轮内上下文预算的分母取数点（工具轮内上下文预算批）。null = 不判轮内预算（轻装配面 / 单测）。
	 *
	 * <p>与轮首压缩<b>同一个</b> {@link ContextLimitResolver}（唯一取数点），因此"显示的上限"
	 * 与"轮内判定的上限"不会各用一套分母（L-89 的教训）。生产由容器注入唯一实例。
	 */
	private ContextLimitResolver contextLimitResolver;

	/** 可选注入：轮内预算的分母。缺失 = 轮内不判（既有单测/轻装配面行为一字不变）。 */
	@Autowired(required = false)
	public void setContextLimitResolver(ContextLimitResolver resolver) {
		this.contextLimitResolver = resolver;
	}

	/**
	 * 轮内 L1 的记账面（压缩台账 / 归档 / 会话状态）。生产 = {@code RunStore}（它实现
	 * {@link ContextStateStore}，三键族与轮首压缩同住一处）。
	 *
	 * <p><b>为什么可选注入</b>：与 {@link #setContextLimitResolver} 同因 —— 本类构造器被多份
	 * conformance/run 用例直接 {@code new}，改签名等于要求它们跟改。轻装配面（单测直接 new）没有
	 * 记账面时，轮内 L1 仍按旧口径只清结果（历史不变、只是少了台账）；<b>生产由容器注入唯一
	 * {@code RunStore}，不存在"漏装配就没有台账"的静默降级</b>。
	 */
	private ContextStateStore contextStateStore;

	/** 可选注入：轮内 L1 的台账/归档/状态。缺失 = 只清结果、不记账（轻装配面）。 */
	@Autowired(required = false)
	public void setContextStateStore(ContextStateStore store) {
		this.contextStateStore = store;
	}

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

	/**
	 * 结算观测位（跨实例恰好一次的仲裁面）。null = 未注入（轻装配/单测）——
	 * 此时每个发点各自发一行，与引入仲裁之前同形（单实例恒等价）。
	 */
	private SettlementObservationStore settlementObservations;

	/** 可选注入（观测面，不是判定面；缺失不影响任何工具的执行语义）。 */
	@Autowired(required = false)
	public void setSettlementObservationStore(SettlementObservationStore observations) {
		this.settlementObservations = observations;
	}

	/** 可选注入（视图投影是展示增强，不是判定面；缺失 = 所有工具走旧卡面）。 */
	@Autowired(required = false)
	public void setResultViewProviders(List<ToolResultViewProvider> providers) {
		this.resultViewProviders = providers == null ? List.of() : List.copyOf(providers);
	}

	/**
	 * 工具批后刷新运行上下文的入口（动态披露批）。null = 不刷新（轻装配面 / 单测）。
	 */
	private RunContextRefresher contextRefresher;

	/** 可选注入：批间上下文刷新。缺失 = 快照上下文不随工具结果推进（既有单测行为一字不变）。 */
	@Autowired(required = false)
	public void setContextRefresher(RunContextRefresher refresher) {
		this.contextRefresher = refresher;
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
		this.toolTimeoutThreads = toolTimeoutThreads(this.properties);
		this.toolTimeoutExecutor = toolTimeoutExecutor(this.toolTimeoutThreads);
	}

	/**
	 * 每跳按<b>当前快照</b>重算披露面（动态披露批的唯一接缝）。
	 *
	 * <p>官方模型循环（{@code ReasoningAwareOpenAiChatModel#createRequest}）在<b>每一发请求</b>前都调
	 * 本方法，而它所带的 {@code chatOptions} 是请求边界那一份（callbacks 冻结）。于是"同一轮里
	 * 导航成功后下一跳能看到向导工具"就取决于这里：读本轮快照的 {@code context}，用
	 * {@code ToolRegistry#forContext} 重算一次，再与冻结集合按<b>名字</b>合并 ——
	 * <ul>
	 * <li>冻结集合的顺序优先（上下文没变时结果与旧口径逐字一致，不击穿 prompt 缓存）；</li>
	 * <li>已不在当前披露面的冻结工具被裁掉；新增披露的追加在后面；</li>
	 * <li>执行侧的 callback 解析另由 {@link #executionPrompt} 兜住（否则新增工具会"看得见、点不动"）。</li>
	 * </ul>
	 *
	 * <h2>回落口径（不能让工具凭空变空/变多）</h2>
	 * 无 options / callbacks 为空 / 无 {@code runId} / 无快照 / 重算结果为空 / 任何异常 ⇒
	 * <b>原样返回委托结果</b>。尤其是"callbacks 为空"：那可能是"本轮不下发任何工具"（模型不支持
	 * 工具调用），把它换成 registry 的全集就是<b>静默扩大授权面</b>。
	 */
	@Override
	public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
		List<ToolDefinition> delegated = this.delegate.resolveToolDefinitions(chatOptions);
		try {
			if (chatOptions == null) {
				return delegated;
			}
			List<ToolCallback> frozen = chatOptions.getToolCallbacks();
			if (frozen == null || frozen.isEmpty()) {
				return delegated;
			}
			// toolNames 的解析面在本类之外（委托的 resolver），重算不了 ⇒ 原样委托
			if (chatOptions.getToolNames() != null && !chatOptions.getToolNames().isEmpty()) {
				return delegated;
			}
			String runId = runIdOf(chatOptions.getToolContext());
			if (runId == null) {
				return delegated;
			}
			RunSnapshot snapshot = this.store.get(runId);
			if (snapshot == null) {
				return delegated;
			}
			List<ToolCallback> current = this.toolRegistry.forContext(AiContext.fromMap(snapshot.getContext()));
			if (current == null || current.isEmpty()) {
				return delegated;
			}
			List<ToolCallback> merged = mergeDisclosed(frozen, current);
			if (merged.isEmpty()) {
				return delegated;
			}
			return merged.stream().map(ToolCallback::getToolDefinition).toList();
		}
		catch (RuntimeException ex) {
			// 披露重算是增强：算不出来就按请求边界那一份继续（执行侧防线③仍按快照上下文复查）
			log.warn("按当前快照重算工具定义失败，回落原委托：{}", ex.getMessage());
			return delegated;
		}
	}

	/**
	 * 冻结集合（名字与顺序）∩ 当前披露面 + 新披露的那些（追加在后）。
	 *
	 * <p>用<b>名字</b>对齐而不是对象身份：请求边界与本次重算拿到的都是 registry 里同一个回调实例，
	 * 但"同一性"不该成为披露口径的隐含前提。
	 */
	private static List<ToolCallback> mergeDisclosed(List<ToolCallback> frozen, List<ToolCallback> current) {
		Map<String, ToolCallback> disclosed = new LinkedHashMap<>();
		for (ToolCallback callback : current) {
			if (callback != null) {
				disclosed.putIfAbsent(toolNameOf(callback), callback);
			}
		}
		List<ToolCallback> merged = new ArrayList<>(frozen.size() + disclosed.size());
		Set<String> kept = new HashSet<>();
		for (ToolCallback callback : frozen) {
			if (callback == null) {
				continue;
			}
			String name = toolNameOf(callback);
			if (disclosed.containsKey(name)) {
				merged.add(callback);
				kept.add(name);
			}
		}
		for (Map.Entry<String, ToolCallback> entry : disclosed.entrySet()) {
			if (kept.add(entry.getKey())) {
				merged.add(entry.getValue());
			}
		}
		return merged;
	}

	private static String toolNameOf(ToolCallback callback) {
		return callback.getToolDefinition().name();
	}

	/** 回调集合的名字序列（顺序敏感的比较用：披露面没变时不得复制 options、不得改序）。 */
	private static List<String> names(List<ToolCallback> callbacks) {
		List<String> names = new ArrayList<>(callbacks.size());
		for (ToolCallback callback : callbacks) {
			names.add(callback == null ? null : toolNameOf(callback));
		}
		return names;
	}

	/**
	 * 执行侧：本次调用的官方委托要能解析到它的 callback。
	 *
	 * <p>官方 {@code DefaultToolCallingManager} 执行单个调用时只从 {@code prompt.getOptions()}
	 * 的 callbacks 里按名字找（找不到才问 resolver，而本类的 delegate 用的是空 resolver）。
	 * 下一跳重算把 {@code check_job_status} 之类的新工具给了模型，但旧 options 里没有它 ——
	 * 不补这一份副本就会"模型看得见、执行时 No ToolCallback found"。
	 *
	 * <p><b>真实语义</b>：这里为当前这次调用合并出一份"可解析回调"副本（与
	 * {@link #mergeDisclosed} 同一口径 —— 随当前披露面变化，旧集合里已不在披露面的回调会被裁掉、
	 * 新披露的会追加），集合本身<b>不是授权</b>：能否执行仍由防线③（{@code matchesContext} 按快照
	 * 上下文复查）与各工具的确认门/HITL 决定。拿不到上下文/异常一律按原 options 执行。
	 */
	private Prompt executionPrompt(Prompt prompt, String toolName) {
		try {
			if (prompt == null || !(prompt.getOptions() instanceof ToolCallingChatOptions options)) {
				return prompt;
			}
			List<ToolCallback> frozen = options.getToolCallbacks() == null ? List.of() : options.getToolCallbacks();
			if (frozen.stream().anyMatch(callback -> callback != null && toolName != null
					&& toolName.equals(toolNameOf(callback)))) {
				return prompt;
			}
			String runId = runIdOf(prompt);
			if (runId == null) {
				return prompt;
			}
			RunSnapshot snapshot = this.store.get(runId);
			if (snapshot == null) {
				return prompt;
			}
			List<ToolCallback> current = this.toolRegistry.forContext(AiContext.fromMap(snapshot.getContext()));
			if (current == null || current.isEmpty()) {
				return prompt;
			}
			List<ToolCallback> merged = mergeDisclosed(frozen, current);
			// 仍解析不到（该工具现在本来就不该被披露）⇒ 保留原 options，让既有失败口径如实回填，
			// 不在这里伪造"可解析"；集合没变化 ⇒ 也不必复制 options
			if (merged.stream().noneMatch(callback -> callback != null && toolName != null
					&& toolName.equals(toolNameOf(callback)))) {
				return prompt;
			}
			if (names(merged).equals(names(frozen))) {
				return prompt;
			}
			ToolCallingChatOptions copy = options.copy();
			if (copy == null) {
				return prompt;
			}
			copy.setToolCallbacks(merged);
			log.info("执行期补齐当前披露的回调（本批请求边界之外的新披露工具）runId={} tool={} callbacks={}", runId,
					toolName, merged.size());
			return new Prompt(prompt.getInstructions(), copy);
		}
		catch (RuntimeException ex) {
			log.warn("执行期补齐工具回调失败，按原 options 执行 tool={}：{}", toolName, ex.getMessage());
			return prompt;
		}
	}

	@Override
	public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
		AssistantMessage assistant = chatResponse.getResult() == null ? null : chatResponse.getResult().getOutput();
		if (assistant == null || !assistant.hasToolCalls()) {
			return this.delegate.executeToolCalls(prompt, chatResponse);
		}

		String runId = runIdOf(prompt);
		RunSnapshot snapshot = runId == null ? null : this.store.get(runId);
		// 本轮权威执行信封：既用于快照恢复，也是租户/用户身份的第二来源（快照读不到时仍能恢复）。
		ToolExecutionEnvelope envelope = ToolExecutionEnvelope.fromToolContext(toolContextOf(prompt));
		if (runId == null || snapshot == null) {
			// ① 先凭本轮权威执行信封恢复（只认本实例创建的信封；不伪造审批/台账/认领，不重置序号）。
			snapshot = recoverSnapshot(envelope, runId);
			if (snapshot != null) {
				runId = snapshot.getRunId();
			}
		}
		// 官方工具线程（Reactor 上游线程）没有请求身份：按记录（快照 → 信封）恢复。
		// 两者都没有身份（旧快照/旧信封）⇒ 空作用域，不伪造默认租户；下游按 fail-closed 处理。
		long tenantId = snapshot != null ? snapshot.getTenantId() : envelope == null ? 0L : envelope.tenantId();
		long userId = snapshot != null ? snapshot.getUserId() : envelope == null ? 0L : envelope.userId();
		try (RunIdentityScope.Scope ignored = RunIdentityScope.open(tenantId, userId)) {
			return executeToolCallsResolved(prompt, chatResponse, runId, snapshot, assistant);
		}
	}

	/**
	 * 工具执行主体（已在<b>本轮身份作用域</b>内；身份只来自快照/执行信封）。
	 */
	private ToolExecutionResult executeToolCallsResolved(Prompt prompt, ChatResponse chatResponse, String runId,
			RunSnapshot snapshot, AssistantMessage assistant) {
		if (runId == null || snapshot == null) {
			// ② 恢复不了：危险/前端工具拒绝直执行，只有 READ+BACKEND 可回落官方执行。
			return executeWithoutSnapshot(prompt, chatResponse, runId, assistant);
		}

		SseChatEmitter out = this.registry.of(runId);
		// 跨供应方推理隔离：给上游产出的 assistant 打中性来源标记（快照的冻结身份）。
		// 此后无论走挂起（saveSuspended 外置 metadata）还是落定（settle 进历史），都带着这份来源；
		// 续跑重建时原样恢复，请求层据此只对同供应方回放 reasoningContent。
		assistant = ReasoningOrigin.stamp(assistant, snapshot.getProviderId(), snapshot.getModelId());
		List<PendingToolCall> pendings = newPendings(assistant);
		// 步数笼（超时面治理批② 第 2 项 / 决策看板 :14）：起点来自**外置快照**而不是进程内计数 ——
		// 挂起后的续跑可能落在另一个进程上，进程内计数会被重置（"跨挂起不重置"只有外置才能成立）。
		int maxToolSteps = Math.max(1, this.properties.getResilience().getMaxToolSteps());
		int stepsUsed = snapshot.getToolSteps();
		// 防线③（T1）的判据来源：本轮<b>冻结</b>的工作区上下文（与请求侧披露用的同一份，
		// 见 RunStore#create 写下的 snapshot.context），因此复查不会因上下文漂移而误判。
		AiContext context = AiContext.fromMap(snapshot.getContext());
		// ===== C13：本批模型侧写入的剩余窗口 =====
		// 配置档（20000 字符/份）是静态上限，不知道这一轮还剩多少窗口：小窗口上四份结果就能吃掉整窗，
		// 而 keep-tool-pairs 把最近几对钉住（轮内 L1 清不掉）⇒ 下一跳必然越窗。故这里先算一份"剩余窗口"，
		// 由 modelData 在<b>唯一</b>模型侧写入口按它收缩（截断元数据/台账全文口径不变）。
		ModelWriteBudget modelBudget = modelWriteBudget(snapshot, prompt, assistant, pendings.size());

		// ===== 轮内上下文预算（工具轮内上下文预算批）：本批已进入收尾（armed） =====
		// armed 的语义只有一条：此后<b>不再产生新副作用</b>。因此预检（只读但重）、确认卡外置、
		// 闸门登记统统不做 —— 直接把这些调用结算成 skipped/TOOL_BUDGET_EXHAUSTED，让模型用
		// 已有信息作答。计数用于"模型反复请求工具"的硬上界（TurnBudgetPlan#MAX_ARMED_BATCHES）。
		boolean budgetArmed = this.beacon.budgetArmed(runId);
		if (budgetArmed) {
			int skipped = this.beacon.markBudgetSkippedBatch(runId);
			// AR-10 第 3 条：武装后拦下的批次计数随快照落库 —— park 恢复后继续累计，
			// 到顶仍按既有上界收尾（`MAX_ARMED_BATCHES` 的输入不因挂起而清零）。
			this.store.markTurnBudget(runId, 0L, skipped);
		}

		// ===== F5：确认门之前先跑只读预检（不通过者根本不进门） =====
		// 用户 2026-10-20 直裁：预检不通过 → 不进确认门、不弹卡；结构化失败回给模型，由 AI 用普通消息
		// 说明原因与建议。因此"不通过"的条目在这里就地结算成 PRECHECK_FAILED（code 同名、
		// retryable=true），既不出现在 suspended 帧里，也不会被 arm 成闸门、不会发 confirm_request。
		List<PendingToolCall> suspended = new ArrayList<>(pendings.size());
		Set<String> preflightBlocked = new HashSet<>();
		Set<String> createTaskBlocked = new HashSet<>();
		Set<String> frontendRejected = new HashSet<>();
		// AR-03 单轮上限 1：同一轮只允许创建一次任务。批内重复在挂起前就拒绝（不弹第二张卡），
		// 跨批重复按台账判定（第一笔执行后 ledger 已有记录）。
		boolean createTaskSeenInBatch = false;
		boolean createTaskChecked = false;
		CreateTaskInRun createTaskPrior = CreateTaskInRun.NONE;
		// 已发过 tool_start 的 toolCallId（park 前置批次 + 执行循环共用）：每张卡恰一条，不重发。
		Set<String> toolStarted = new HashSet<>();
		for (PendingToolCall pending : pendings) {
			if (!budgetArmed && this.preflight != null && PendingToolCall.KIND_CONFIRM.equals(pending.getKind())
					&& ToolPreflightService.supports(pending.getName())
					// MINOR-1：防线③先于预检 —— 越 scope 的调用本就不该读任务/产出预检报告，
					// 跳过预检，交给执行循环里的既有 blockOutOfScope（SCOPE_NOT_DISCLOSED）。
					&& this.toolRegistry.matchesContext(pending.getName(), context)) {
				// MINOR-2：预检是挂着上游静默在跑的只读重活（如 IMPORT 的多文件 sha256），
				// 前后打"被动活动锚点"——只刷新看门狗的静默锚点，不计 startedCount（不污染重试判定）。
				this.beacon.markPassiveActivity(runId);
				ToolPreflightService.PreflightReport report;
				try {
					report = this.preflight.inspect(pending.getName(), pending.getArguments(), context);
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
			// AR-03：create_task 的宿主单轮上限（不依赖模型服从）。第一个照常进确认门；
			// 同批第二个 / 本轮已建过 ⇒ 直接结算成"未创建"并给可读出口，不再弹卡。
			if (!budgetArmed && TOOL_CREATE_TASK.equals(pending.getName())
					&& PendingToolCall.KIND_CONFIRM.equals(pending.getKind())) {
				if (!createTaskChecked) {
					createTaskChecked = true;
					createTaskPrior = createTaskAlreadyInRun(runId);
				}
				if (createTaskSeenInBatch || createTaskPrior.created()) {
					// 先落终态再 continue：本批若因第一张卡 park，第二笔也不会以 PENDING 留在快照里
					// 变成"刷新后多出一张可点的卡"；帧在下方执行循环统一发（不在 suspended 之前）。
					markCreateTaskLimit(runId, pending, createTaskPrior);
					createTaskBlocked.add(pending.getToolCallId());
					continue;
				}
				createTaskSeenInBatch = true;
			}
			// 前端通道条目的入参复核：与确认门预检<b>同一位置</b> —— 必须在 suspended 公告与请求帧之前。
			// 复核对入参是确定性的；放到 await 里再判，"不挂起、不下发"的契约会被
			// publishRequestsForParkedBatch 打破（非法 schema 先拿到 suspended + frontend_tool_request，
			// 随后才被判 REJECTED_ARGUMENTS —— E2E GF6/G-13/X-10 的实测帧序正是如此）。
			if (!budgetArmed && PendingToolCall.KIND_FRONTEND.equals(pending.getKind())) {
				FrontendToolGuard.Verdict verdict = inspectFrontendArguments(pending, context);
				if (verdict != null && !verdict.accepted()) {
					settleFrontendRejected(runId, pending, verdict);
					frontendRejected.add(pending.getToolCallId());
					continue;
				}
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
		if (!budgetArmed) {
			for (PendingToolCall pending : suspended) {
				Integer waitSeconds = this.gate.suspendWaitSeconds(pending);
				if (waitSeconds != null && waitSeconds > 0) {
					pending.setAtMs(suspendedAtMs);
					pending.setExpiresAt(suspendedAtMs + waitSeconds * 1000L);
				}
			}
		}

		// ===== F8：闸门预登记必须早于 suspended 帧 =====
		// 帧一到前端，确认卡就可被点击；此刻若闸门尚未登记，submitDecision 的 signal 返回 false，
		// 死卡兜底会把"等待还没进入"误判成"本进程无人等待"并派发续跑，与原循环双驱动同一轮。
		// CONFIRM 与 FRONTEND 两个通道都经 ConfirmGate.await 登记闸门，故两者一并预登记；
		// 正常路径由 await 的 finally 逐个回收，这里只兜住异常/提前返回（取消、越 scope、
		// 前端入参被拒、saveSuspended 抛错等）留下的未消费闸门，否则 hasActiveLatch 会永真。
		// armed 时本批一个等待都不会发生（见下方跳过分支），故不预登记。
		List<String> gateWaitIds = budgetArmed ? List.of() : gateWaitToolCallIds(suspended);
		this.gate.arm(runId, gateWaitIds);
		try {
			// ===== 硬规范①：先把 assistant(tool_calls) 与待决条目外置，再进入任何等待 =====
			// 外置的是<b>本批全部</b>条目（含预检未通过的那些，它们已是终态），但帧只公告真正挂起的：
			// 预检未通过的条目不出现在 suspended.toolCalls[] 里，因而不会渲染成确认卡；
			// 全部都不通过时连 suspended 帧都不存在 —— 这正是"不进确认门、不弹卡"在帧面上的形态。
			// armed 时没有挂起，也就不外置挂起态（历史由下面的 settle 落定）。
			if (!budgetArmed && !suspended.isEmpty()) {
				// 步数先落快照再挂起：跨进程续跑读的就是这份累计值（saveSuspended 会重新 get 快照）
				persistToolSteps(runId, stepsUsed);
				this.store.saveSuspended(runId, assistant.getText(), pendings, prompt.getInstructions(), this.codec,
						assistant.getMetadata());
				out.suspended(suspended, this.store.runKey(runId), this.store.pendingKey(runId),
						this.store.instanceId());
				// ===== 帧序契约：tool_start 必须先于 request（park 开启时两者都前移）=====
				// C1 把请求帧提前公布是为了"首张卡 park 后其余卡仍可决策"；但 tool_start 仍留在
				// 执行循环里逐张发，真实帧序就变成 suspended → request → tool_start（真模型 G-01 实测）——
				// 前端 tool_start 分支会把已建立的等动作卡复位成只读 running，卡再也提交不了。
				// 因此 park 开启时先把本批每张卡的 tool_start 一并前移，执行循环不再重发（toolStarted）。
				//
				// 语义（裁决记录，独立复审 F2）：tool_start 对<b>每个被接受（进入 suspended）的工具</b>都发，
				// 它是"公告/已排期事件"，<b>不是</b>"已执行"的证明：
				//   · 混合批次（backend_a, confirm_b, backend_c）里，首张卡 park 后本调用就返回，
				//     排在后面的 backend_c 在整段人工等待期间显示 running —— 这是刻意可见的语义，
				//     本批不为此收窄前端交互；
				//   · 续跑用 ResumeService#resolvePending 结算这些条目，<b>不会再发一次 start</b>；
				//   · 若把预发收窄成"只发门卡"，这些工具就一条 start 都没有（旧缺口复现），故不做。
				if (this.gate.publishesRequestsAheadOfLoop()) {
					for (PendingToolCall pending : suspended) {
						emitToolStartOnce(runId, toolStarted, pending);
					}
				}
				// ===== C1：park 前把本批每张卡的请求帧都公布 =====
				// 逐条发（在各卡自己的 await 里）会让 park 后的第二张卡永远收不到请求帧：
				// 它既没 req 也没帧，前端不会渲染，也就永远没人能决策它。
				// park 关闭时本方法内部直接返回（阻塞等待的老口径不变）。
				this.gate.publishRequestsForParkedBatch(runId, suspended);
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
				// 轮内上下文预算：armed 之后新工具<b>一律不执行</b>（副作用到此为止）。
				// 帧照发（前端能看到"这步为什么没跑"），回填模型的是统一信封 + 未执行说明。
				if (budgetArmed) {
					emitToolStartOnce(runId, toolStarted, pending);
					responses.add(skipAsBudgetExhausted(runId, pending));
					traceToolSkipped(runId, snapshot.getSessionId(), pending, ToolOutcome.CODE_TOOL_BUDGET_EXHAUSTED);
					log.warn("轮内上下文预算已耗尽，本调用不再执行 runId={} tool={} toolCallId={}", runId,
							pending.getName(), pending.getToolCallId());
					continue;
				}
				// 挂起前被入参闸门拒掉的条目：没有卡、没有请求帧，只有一张「未执行」的结局帧
				// （与预检未通过同形的两条帧：tool_start → frontend_tool_result）。
				// 例外：槽位放行（FORM_SLOTS_ALREADY_KNOWN）是"没有缺口"而不是"出了错" —— 条目、
				// 日志与模型回执照旧，但<b>一条会产生用户卡片的帧都不发</b>：用户不该为一张"系统已经
				// 知道答案"的表单看见失败卡，也让模型只剩"改调 select_definitions"这一个出口。
				if (frontendRejected.contains(pending.getToolCallId())) {
					String text = frameText(pending.getResultText());
					if (!silentlyReleased(pending)) {
						emitToolStartOnce(runId, toolStarted, pending);
						out.frontendToolResult(pending, false, text);
					}
					responses.add(new ToolResponseMessage.ToolResponse(pending.getToolCallId(), pending.getName(),
							modelData(pending, text, modelBudget)));
					traceToolSkipped(runId, snapshot.getSessionId(), pending, pending.effectiveCode());
					continue;
				}
				// F5：预检未通过的条目没有确认卡（没有 suspended/confirm_request），只有一张
				// "未执行"的失败工具卡 + 一条结局帧：前端据此显示原因，模型据此向用户解释。
				// 它不在"首错即停"的判据内（retryable=true，见 ToolOutcome#retryable）—— 用户修好可重试。
				if (preflightBlocked.contains(pending.getToolCallId())) {
					String text = frameText(pending.getResultText());
					emitToolStartOnce(runId, toolStarted, pending);
					out.toolResult(pending, false, false, text);
					responses.add(new ToolResponseMessage.ToolResponse(pending.getToolCallId(), pending.getName(),
							modelData(pending, text, modelBudget)));
					traceToolSkipped(runId, snapshot.getSessionId(), pending, pending.effectiveCode());
					continue;
				}
				// AR-03：单轮第二次 create_task 直接拒绝创建（未执行、无副作用），给可读原因与出口。
				if (createTaskBlocked.contains(pending.getToolCallId())) {
					emitToolStartOnce(runId, toolStarted, pending);
					responses.add(skipAsCreateTaskLimit(runId, pending, createTaskPrior));
					traceToolSkipped(runId, snapshot.getSessionId(), pending,
							ToolOutcome.CODE_CREATE_TASK_TURN_LIMIT);
					continue;
				}
				// 步数笼：到顶后的调用不再执行，结算成 skipped/TOOL_STEP_LIMIT（不判整轮失败）
				if (stepsUsed >= maxToolSteps) {
					// 帧序：先 tool_start 再 tool_result（与预检未通过分支同形，不破帧序契约）
					emitToolStartOnce(runId, toolStarted, pending);
					responses.add(skipAsStepLimit(runId, pending, maxToolSteps));
					traceToolSkipped(runId, snapshot.getSessionId(), pending, ToolOutcome.CODE_TOOL_STEP_LIMIT);
					log.warn("工具步数到顶，本调用不再执行 runId={} tool={} toolCallId={} steps={}/{}", runId,
							pending.getName(), pending.getToolCallId(), stepsUsed, maxToolSteps);
					continue;
				}
				stepsUsed++;
				emitToolStartOnce(runId, toolStarted, pending);
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
							case PendingToolCall.KIND_FRONTEND -> awaitFrontend(runId, pending, context);
							case PendingToolCall.KIND_CONFIRM -> awaitConfirm(runId, prompt, pending, context);
							default -> executeOnce(runId, prompt, pending, context);
						};
					}
				}
				catch (RuntimeException ex) {
					// park 批：人工等待 park 是控制流，不是工具失败 —— 条目仍 PENDING，
					// 不能写"工具异常"的结局行（否则台账/日志会把"等人在决定"说成"执行炸了"）。
					if (ex instanceof RunParkedException parked) {
						AiTrace.eventOn(runId, snapshot.getSessionId(), "tool_call_park",
								"toolCallId", pending.getToolCallId(), "tool", pending.getName(),
								"kind", pending.getKind(), "parkKind", parked.kind());
						throw ex;
					}
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
				PendingToolCall settled;
				try {
					settled = this.store.pendingLenient(runId, pending.getToolCallId());
				}
				catch (RuntimeException ex) {
					// C2：执行后的日志/信封读取失败不得改变工具结局；回落入参副本。
					log.debug("执行后读待决条目失败，回落入参 runId={} toolCallId={}：{}", runId,
							pending.getToolCallId(), ex.getMessage());
					settled = pending;
				}
				PendingToolCall source = settled == null ? pending : settled;
				// 日志链路批：工具生命周期的右端 —— 结局分类沿用条目真值（不另造一套口径）
				// AI 日志最小补齐批：ms 含审批等待，execMs 把它扣掉（"工具慢"与"人在犹豫"必须可分）
				long totalToolMs = System.currentTimeMillis() - toolStartedAtMs;
				// 载荷构造统一走 ToolCallEndTrace：同一批表格工具的另一半结算发生在 park 之后
				// （决策/认领的胜者），两条路径的键序与取值口径不允许各写一遍。
				// 台账 + 计数与这一行同一时点（SheetAudit 未注入时只是少了观测面，判定语义不变）。
				ToolCallEndTrace.emitOnce(this.settlementObservations, runId, snapshot.getSessionId(), source,
						pending.getToolCallId(), pending.getName(), pending.getKind(), totalToolMs,
						execMsOf(source, totalToolMs), text, this.sheetAudit);
				// 动态披露（多步工作流批）：<b>逐条成功结算之后</b>按真实结果推进本轮上下文。
				// 同批后续调用的 scope 判据仍用上面那份冻结 context（批内不扩权），只有下一批
				// （又一次 executeToolCalls）才会按新上下文重算披露面（resolveToolDefinitions）。
				refreshRunContext(runId, source);
				// T2：回填模型的是裁剪后的文本；上面的 text 本身（进帧、进台账）保持全文
				// P0-1：模型侧 = 统一信封 JSON（先裁剪 message，再组装，见 ToolResultEnvelope#toModelJson）
				// P1-10：执行中抛出异常的失败，executeOnce 已把对账措辞追加进 resultText
				// （帧/台账/模型三面同一份文本；"结果缺失"这类未执行副作用不追加）。
				responses.add(new ToolResponseMessage.ToolResponse(pending.getToolCallId(), pending.getName(),
						modelData(source, text, modelBudget)));
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
			boolean stepWrapped = stepsUsed >= maxToolSteps;
			if (stepWrapped) {
				history.add(new UserMessage(STEP_LIMIT_WRAP_UP));
			}
			// ===== 轮内上下文预算：每批工具结束、下一次上游调用之前判一次 =====
			// 只做本地 L1（清旧工具结果）+ 收尾 + 硬停；不做 L2/L3 摘要调用（见 TurnBudgetPlan 类注）。
			TurnBudgetOutcome budget = applyTurnBudget(runId, snapshot, prompt, chatResponse, assistant, history,
					budgetArmed, stepWrapped);
			history = budget.history();
			persistToolSteps(runId, stepsUsed);
			// 已执行记录全部落定后，才允许越窗失败穿出去。
			settle(runId, history);
			// 取消优先：settle 之后、把历史交回 Spring AI 之前再查一次权威标志 —— 命中即终止本轮，
			// 不再返回可驱动下一跳的历史（工具结果与历史已先落定，已发生的副作用不撤回）。
			checkCancelled(runId);
			if (budget.overflow()) {
				// C13 第 5 项：码按原因分（收尾被无视 = 工具预算耗尽，不是真越窗）。
				// 换码不改语义：调用方已 settle，失败照样是确定性的、可解释的。
				throw new StreamViolationException(budget.decision().failureCode(),
						"轮内上下文预算判定：下一跳请求将超出上下文窗口（nextEst=" + budget.decision().nextEst()
								+ " >= limit=" + budget.decision().limitTokens() + "，reason="
								+ budget.decision().reason() + "），已落定已执行记录后终止本轮");
			}

			return ToolExecutionResult.builder().conversationHistory(history).build();
		}
		finally {
			this.gate.disarm(runId, gateWaitIds);
		}
	}

	/**
	 * {@code tool_start} 的<b>唯一发射口</b>（每张卡恰一条）。
	 *
	 * <p>park 开启时本批的 tool_start 与请求帧都前移到执行循环之前公布（帧序契约：tool_start 先于
	 * request）。执行循环随后还会走到同一条卡（预算/预检/步数/主路径四个分支），必须靠本集合去重 ——
	 * 否则同一张卡会出现两条 tool_start（前端会重复建段/重复动画）。非 park 路径集合恒空，
	 * 行为与改造前逐字一致。
	 */
	private void emitToolStartOnce(String runId, Set<String> toolStarted, PendingToolCall pending) {
		if (toolStarted.add(pending.getToolCallId())) {
			this.registry.of(runId).toolStart(pending);
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
	 * AR-03 单轮第二次 {@code create_task}：结算成 {@code skipped/CREATE_TASK_TURN_LIMIT}
	 * （<b>未创建</b>、不弹第二张卡、不写执行台账），并给模型可读出口。
	 *
	 * @param prior 本轮已创建任务的已知程度（有 id / 任务号不可确认 / 同批尚未结算），
	 *              见 {@link CreateTaskInRun} —— <b>任务号不可确认时绝不编造 {@code #-1}</b>
	 */
	public ToolResponseMessage.ToolResponse skipAsCreateTaskLimit(String runId, PendingToolCall pending,
			CreateTaskInRun prior) {
		String message = markCreateTaskLimit(runId, pending, prior);
		this.registry.of(runId).toolResult(pending, false, false, message);
		return new ToolResponseMessage.ToolResponse(pending.getToolCallId(), pending.getName(),
				modelData(pending, message));
	}

	/** 只落"单轮上限"终态（不发帧）：供 park 前检测使用，保证第二笔不会以 PENDING 留在快照里。 */
	private String markCreateTaskLimit(String runId, PendingToolCall pending, CreateTaskInRun prior) {
		String detail;
		if (prior.idUnknown()) {
			detail = "无法确认本轮是否已创建任务；为避免重复创建，本次未创建。"
					+ "请先用 list_tasks 查看已有任务（如有，用 navigate_to 打开）；需要再建请在新一轮发起。";
		}
		else if (prior.taskId() != null) {
			detail = "本轮已创建任务 #" + prior.taskId() + "，本次未创建。请用 navigate_to(page=..., taskId="
					+ prior.taskId() + ") 打开该任务；需要再建请在新一轮发起。";
		}
		else {
			detail = "本次调用未创建。请先完成本轮已发起的那一次创建（确认卡）；需要再建请在新一轮发起。";
		}
		String message = "未创建（" + ToolOutcome.CODE_CREATE_TASK_TURN_LIMIT + "）：同一轮只允许创建一次任务。" + detail;
		pending.setStatus(PendingToolCall.SKIPPED);
		pending.setOutcome(ToolOutcome.SKIPPED.wire());
		pending.setCode(ToolOutcome.CODE_CREATE_TASK_TURN_LIMIT);
		pending.setReason(ToolOutcome.CODE_CREATE_TASK_TURN_LIMIT);
		pending.setExecuted(false);
		pending.setExecutedBy(null);
		pending.setResolvedAtMs(System.currentTimeMillis());
		pending.setResultText(message);
		this.store.putPending(runId, pending);
		log.warn("单轮 create_task 上限命中，拒绝创建 runId={} toolCallId={} priorTaskId={} idUnknown={}", runId,
				pending.getToolCallId(), prior.taskId(), prior.idUnknown());
		return message;
	}

	/**
	 * 本轮是否已经创建过任务（跨批 / 续跑口径 = 执行台账）。
	 *
	 * <p><b>不编造任务号</b>：台账读不到 / 回执里解析不出 {@code #id} 时返回
	 * {@link CreateTaskInRun#unknown()}，文案只说"无法确认本轮是否已创建任务"，绝不输出
	 * {@code #-1} 这类伪 ID 让用户去导航，也不断言"一定已创建"。
	 */
	private CreateTaskInRun createTaskAlreadyInRun(String runId) {
		List<Map<String, Object>> ledger;
		try {
			ledger = this.store.ledger(runId);
		}
		catch (RuntimeException ex) {
			// fail-closed：查不到台账时宁可拒绝第二次创建，也不能冒"重复建库"的险。
			log.warn("读取单轮 create_task 台账失败，按已创建处理（任务号不可确认）runId={}：{}", runId,
					ex.getMessage());
			return CreateTaskInRun.unknown();
		}
		for (Map<String, Object> record : ledger) {
			if (TOOL_CREATE_TASK.equals(record.get("name"))
					&& ToolOutcome.OK.wire().equals(String.valueOf(record.get("outcome")))) {
				Long taskId = taskIdFromText(String.valueOf(record.get("resultText")));
				return taskId == null ? CreateTaskInRun.unknown() : new CreateTaskInRun(true, taskId, false);
			}
		}
		return CreateTaskInRun.NONE;
	}

	/** 从创建回执里取 {@code #123}（取不到返回 null：调用方不得编造 ID）。实现与刷新器同一份。 */
	private static Long taskIdFromText(String text) {
		return RunContextRefresher.taskIdFromText(text);
	}

	/**
	 * "本轮是否已创建任务"的三态结果（AR-03 单轮上限）。
	 *
	 * @param created  本轮是否已有成功创建
	 * @param taskId   已知任务号（未知/同批未结算时为 null）
	 * @param idUnknown 已有创建但任务号不可确认（台账读失败/回执无 {@code #id}）；
	 *                  文案必须走通用措辞，不得给伪 ID
	 */
	public record CreateTaskInRun(boolean created, Long taskId, boolean idUnknown) {

		/** 未创建（同批第一笔尚未结算也归此态）。 */
		public static final CreateTaskInRun NONE = new CreateTaskInRun(false, null, false);

		/** 已有创建但任务号不可确认。 */
		public static CreateTaskInRun unknown() {
			return new CreateTaskInRun(true, null, true);
		}
	}

	/**
	 * 轮内上下文预算 armed 之后的调用：结算成 {@code skipped/TOOL_BUDGET_EXHAUSTED}
	 * （未执行、不占认领、不记台账），帧照发。
	 *
	 * <p>为什么是 {@link ToolOutcome#SKIPPED} 而不是 {@code FAILED}：与步数笼同一条理由 ——
	 * 这不是"执行出错"，而是"上下文放不下了"。用 failed 会触发批次首错即停，把
	 * "部分完成收尾"变成"整批作废"。
	 */
	public ToolResponseMessage.ToolResponse skipAsBudgetExhausted(String runId, PendingToolCall pending) {
		String message = "未执行：本轮上下文预算已耗尽（工具轮内上下文预算）。"
				+ "请直接用已经获得的信息给出结论，并说明哪些部分未完成。";
		pending.setStatus(PendingToolCall.SKIPPED);
		pending.setOutcome(ToolOutcome.SKIPPED.wire());
		pending.setCode(ToolOutcome.CODE_TOOL_BUDGET_EXHAUSTED);
		pending.setReason(ToolOutcome.CODE_TOOL_BUDGET_EXHAUSTED);
		pending.setExecuted(false);
		pending.setExecutedBy(null);
		pending.setResolvedAtMs(System.currentTimeMillis());
		pending.setResultText(message);
		this.store.putPending(runId, pending);
		this.registry.of(runId).toolResult(pending, false, false, message);
		return new ToolResponseMessage.ToolResponse(pending.getToolCallId(), pending.getName(),
				modelData(pending, message));
	}

	// ── 轮内上下文预算（工具轮内上下文预算批） ───────────────────────────────

	/**
	 * 轮内预算的执行面：判一次 → 按动作执行本地 L1 / 收尾 → 变更后<b>重新估算</b>再判。
	 *
	 * <p><b>为什么变更后要重新估算</b>：真值 {@code usage} 描述的是"上一发请求"，
	 * 清掉工具结果或追加指令之后，上一发的 usage 已不再代表下一发。故每次本地变更都按
	 * <b>省下/新增的估算量</b>递推判据值（而不是丢掉真值重算整包）：清掉 3000 token 的旧结果，
	 * 下一发就少 3000 —— 真值仍是同一个基线的真值。
	 *
	 * <p>至多两轮变更（清一次 + 收尾一次），构造上不会死循环。
	 */
	private TurnBudgetOutcome applyTurnBudget(String runId, RunSnapshot snapshot, Prompt prompt,
			ChatResponse chatResponse, AssistantMessage assistant, List<Message> history, boolean armed,
			boolean stepWrapped) {
		int limit = turnBudgetLimit(snapshot);
		if (limit <= 0) {
			// 降级态（上限未知）：不判比例、不清结果 —— 与轮首压缩同一姿态
			return new TurnBudgetOutcome(history, false, null);
		}
		AiProperties.Context config = this.properties.getContext();
		ContextMeter meter = new ContextMeter();
		meter.accept(chatResponse);
		int before = estimateRequestTokens(prompt, history);
		int delta = Math.max(0, ContextEstimator.estimateMessages(history)
				- ContextEstimator.estimateMessages(prompt.getInstructions())
				- ContextEstimator.estimateMessage(assistant));
		Set<String> protectedIds = pendingToolCallIds(runId).orElse(null);
		// I3：本轮请求的固定前缀（system + tools）—— 记账基线要用<b>它</b>，不是 state 里上一轮的旧值
		int systemTokens = systemTokensOf(prompt);
		int toolsTokens = toolsTokensOf(prompt);
		List<Message> current = history;
		int cleared = 0;
		boolean armedNow = armed;
		TurnBudgetPlan.Decision decision = TurnBudgetPlan.plan(turnBudgetRequest(limit, config, meter, prompt,
				history, delta, armedNow, runId));
		// 判据值（真值优先；无真值时就是整包估算）。之后的每次本地变更都原地递推它。
		int nextEst = decision.nextEst();
		boolean needRejudge = false;
		// C13：越窗之后还有两刀（都属于"只压缩固定历史、只调模型"—— 不重跑任何已执行的工具）：
		//   ① L1 清旧结果（I1：越窗也先试，零代价的一刀排在最前）；
		//   ③ 收窄最新未保护的工具结果（pin 窗口内也允许；HITL 不动、配对不动；至多两轮，
		//      第二轮只为吸收"随后按 95% 规则追加的收尾指令"那几十 token）；
		//   ④ 丢最旧的固定历史轮次（HardTrim 同一套纯函数 + 与轮首 L3 同账：归档/档位/台账）。
		// 做不动就按原判据收尾（真发不出去的上游 400 不该被假装成成功）。
		// L1 记账每批只试一次：失败后 plan 仍会 changed（清除没落地），后续 pass 再试只会重复写
		// 归档/状态，结果注定一样 —— 认一次失败，把机会让给后面的收窄/截断。
		boolean clearAttempted = false;
		int shrunkResults = 0;
		int shrinkRounds = 0;
		int trimmedTurns = 0;
		for (int pass = 0; pass < 6; pass++) {
			if (pass > 0) {
				decision = TurnBudgetPlan.plan(new TurnBudgetPlan.Request(limit, config.getReserveTokens(),
						config.getL1Percent(), config.getL3Percent(), false, 0, 0, nextEst, 0, armedNow,
						this.beacon.budgetSkippedBatches(runId)));
			}
			needRejudge = false;
			boolean overflowNow = decision.action() == TurnBudgetPlan.Action.OVERFLOW
					&& TurnBudgetPlan.REASON_OVERFLOW.equals(decision.reason());
			// ① L1（I1）：达线与<b>越窗</b>都先试"零代价"清旧结果。越窗时若跳过它，就是放着
			// 清得掉、且模型最不需要的旧结果不动，反而去削模型接下来最可能要用的最新结果 ——
			// 同样的 token，代价更高，台账里还少一条"清过旧结果"。
			if ((decision.action() == TurnBudgetPlan.Action.CLEAR_TOOL_RESULTS || overflowNow) && cleared == 0
					&& !clearAttempted && protectedIds != null) {
				List<Message> beforeClear = current;
				ToolResultClearing.Plan plan = ToolResultClearing.plan(current, config.getKeepToolPairs(),
						protectedIds);
				if (plan.changed()) {
					clearAttempted = true;
				}
				if (plan.changed() && recordTurnClear(runId, snapshot, beforeClear, plan, systemTokens, toolsTokens)) {
					current = plan.messages();
					cleared += plan.cleared().size();
					nextEst = Math.max(0, nextEst - plan.savedTokens());
					needRejudge = true;
					continue;
				}
			}
			if (decision.action() == TurnBudgetPlan.Action.CLEAR_TOOL_RESULTS) {
				break;
			}
			if (overflowNow) {
				// ③ 仍超窗：先收窄"最新、未保护"的工具结果（L1 清不掉的 pin 窗口内条目）。
				// 允许两轮：第一轮把判据值压到"窗口 − 1"；若随后按 95% 规则追加了收尾指令、
				// 指令本身又把这一跳顶回窗口外，就再收一次 —— 否则"允许 WRAP_UP/arm"反而会失败。
				if (shrinkRounds < 2 && protectedIds != null) {
					ToolResultShrink.Plan shrink = ToolResultShrink.plan(current, nextEst, limit, protectedIds,
							this.limiter);
					if (shrink.changed()) {
						current = shrink.messages();
						shrunkResults += shrink.shrunk().size();
						shrinkRounds++;
						nextEst = Math.max(0, nextEst - shrink.savedTokens());
						needRejudge = true;
						continue;
					}
				}
				// ④ 仍超窗：压缩固定历史（丢最旧轮），随后这一跳<b>只调模型</b>（工具一个都不重跑）。
				// 与轮首 L3 同账（I5）：先归档 → 再写档位/轮数/lostTokens → 再写台账；
				// 记账失败就<b>不应用</b>这次截断（模型看到的历史绝不因一次 Redis 抖动少一半）。
				if (trimmedTurns == 0) {
					HardTrim.Plan trim = HardTrim.plan(current, config.getKeepRecentTurns(), protectedIds);
					// N-C（与轮首 L3 同口径）：先按<b>裁后估算</b>判 ——
					// after = nextEst − lostTokens；只有 after + 有效预留 <= limit 才允许裁。
					// 裁完仍放不下时，这一刀只丢用户历史、换不回可用请求（下一发照样 400）⇒ 不裁，
					// 原样交给调用方按越窗如实收尾（历史留着还能换窗口/换模型重试）。
					if (trim.changed() && nextEst - trim.lostTokens()
							+ ContextTrigger.effectiveReserve(limit, config.getReserveTokens()) > limit) {
						break;
					}
					if (trim.changed() && recordTurnTrim(runId, snapshot, current, trim, systemTokens, toolsTokens)) {
						current = trim.messages();
						trimmedTurns = trim.droppedTurns();
						nextEst = Math.max(0, nextEst - trim.lostTokens());
						needRejudge = true;
						continue;
					}
				}
				break;
			}
			if (decision.action() == TurnBudgetPlan.Action.WRAP_UP && !armedNow && !stepWrapped) {
				List<Message> wrapped = new ArrayList<>(current);
				wrapped.add(new UserMessage(TURN_BUDGET_WRAP_UP));
				current = wrapped;
				this.beacon.armBudget(runId);
				// AR-10 第 3 条（批次2）：武装事实<b>落轮次快照</b> —— 挂起不清零、恢复继续累计，
				// 保持"本轮预算"语义（不与信标同生共死）。写失败只 WARN，不打断工具循环。
				this.store.markTurnBudget(runId, System.currentTimeMillis(),
						this.beacon.budgetSkippedBatches(runId));
				armedNow = true;
				nextEst += ContextEstimator.estimate(TURN_BUDGET_WRAP_UP);
				needRejudge = true;
				continue;
			}
			break;
		}
		// 变更之后按最终形态再判一次。没有变更时保留原判定：真值口径的结论不该被估算口径覆盖
		// （否则真值说 3000、估算说 7000 时会被误判成越窗，白白掐掉一次本可成功的请求）。
		if (needRejudge) {
			decision = TurnBudgetPlan.plan(new TurnBudgetPlan.Request(limit, config.getReserveTokens(),
					config.getL1Percent(), config.getL3Percent(), false, 0, 0, nextEst, 0, armedNow,
					this.beacon.budgetSkippedBatches(runId)));
		}
		int after = estimateRequestTokens(prompt, current);
		if ((decision.triggered() && !(decision.action() == TurnBudgetPlan.Action.CLEAR_TOOL_RESULTS && cleared == 0))
				|| cleared > 0 || shrunkResults > 0 || trimmedTurns > 0) {
			AiTrace.eventOn(runId, snapshot.getSessionId(), "turn_budget", "action", decision.action().name(),
					"reason", decision.reason(), "ratio", String.format(Locale.ROOT, "%.3f", decision.ratio()),
					"nextEst", decision.nextEst(), "limit", decision.limitTokens(), "reserve",
					decision.reserveTokens(), "before", before, "after", after, "clearedPairs", cleared,
					"shrunkResults", shrunkResults, "trimmedTurns", trimmedTurns, "armed", armedNow, "usage",
					meter.measured());
			log.info("轮内上下文预算 runId={} action={} reason={} ratio={} nextEst={} limit={} clearedPairs={} "
					+ "shrunkResults={} trimmedTurns={} armed={}", runId, decision.action(), decision.reason(),
					String.format(Locale.ROOT, "%.3f", decision.ratio()), decision.nextEst(), limit, cleared,
					shrunkResults, trimmedTurns, armedNow);
		}
		return new TurnBudgetOutcome(current, decision.action() == TurnBudgetPlan.Action.OVERFLOW, decision);
	}

	/**
	 * 轮内 L1 的记账（与轮首压缩 {@code ContextCompactor#compact} 的 L1 收尾<b>同账</b>）。
	 *
	 * <h2>为什么必须记</h2>
	 * 轮内预算先于轮首压缩命中 L1 是常态（{@code keep-tool-pairs} 把最近 N 对都 pin 住，可清的那一对
	 * 往往正好是"本批工具结果"落到历史之后才离开 pin 窗口）。只清不记账的结果是：<b>模型看到的上下文
	 * 真的变了，运维面 {@code ?ledger=1} 与快照 {@code compaction.level} 却仍说"从未压缩"</b> ——
	 * 同一件事两套口径。
	 *
	 * <h2>与轮首的逐项对齐（唯一的差异：谱系已高于 L1 时只记台账/归档、不改状态）</h2>
	 * <ul>
	 * <li>顺序同轮首：<b>先归档 → 再写真值 → 再写台账</b>；任一步失败 ⇒ 本批<b>不清</b>（history 原样）。</li>
	 * <li><b>不宣称原子</b>：三步是三个独立的 Redis 写，没有事务、也没有回滚 —— ②成功而③失败时
	 * {@code ai:ctx:<sid>} 已落 {@code level=L1/atMs} 而台账缺这一条（{@code /context} 说"压过"、
	 * {@code ?ledger=1} 说"没压过"）。这是已知的部分失败窗口，靠下一次清除/轮首压缩收敛；不为此新增
	 * 锁、回滚或新的键协议；</li>
	 * <li>{@code before/after} 用<b>消息估算</b>（{@code ContextEstimator.estimateMessages}），不是含
	 * system/tools 的整包估算 —— 与轮首 {@code ledgerEvent} 同一口径；</li>
	 * <li>{@code level=L1}、{@code turns=0}、{@code lostTokens=0}：L1 只清结果、不折叠轮次（"省了多少"
	 * 看 before−after，不记在 lostTokens 里）；</li>
	 * <li>归档内容 = <b>清除前模型看到的那一份</b>（历史里本就是经 {@code ToolResultLimiter} 限流后的
	 * 文本），不把截断前的上游原文说成"原文"；</li>
	 * <li>归档键轮号 = {@code state.turnNo() + 1}（本轮，尚未由轮末 {@code recordTurn} 推进）；
	 * 沿用轮首的键族与 TTL，不另建轮号协议；</li>
	 * <li><b>只升不降</b>：{@code level} 同时是谱系当前位置（{@code ContextTrigger#advanceTo} 从这里
	 * 往上推一格）。已到 L2/L3 的会话不因一次轮内 L1 回退成 L1（否则下一轮会重爬 L1→L2、摘要重做），
	 * 此时只写台账与归档、不改状态；</li>
	 * <li><b>usage 基线同轮首</b>：写完 {@code level} 立刻把基线置为"清除后规模估算"
	 * （{@code withUsage(after, …, est=true)}，同 {@code ContextCompactor} 的成功路径）。这不是伪造真值：
	 * 它是有 {@code est=true} 标记的临时估算，轮末 {@code recordTurn} 仍用上游真值整份覆盖。
	 * <b>必须写</b>：{@code level} 是 {@code everCompacted()} 的唯一来源，若留着清除前的大的 used，
	 * 下一轮的轮首压缩会被滞回锁（可压缩历史 / (窗口 − 有效预留) ≥ 60%）整轮挡掉 ——
	 * 连只有轮首能做的 L2/L3 也停摆。</li>
	 * </ul>
	 *
	 * <h2>幂等与无法保证的窗口（如实登记）</h2>
	 * 同一批重复调用不会重复记账：清除后的历史里已是占位符，{@link ToolResultClearing#plan} 不再产出
	 * 可清条目（{@code plan.changed()=false}）⇒ 根本进不到本方法（前提是调用方喂的是清除后的历史）。
	 * 同一 run 的并发驱动通常被 run 租约挡住；但上下文台账（{@code appendLedger(sessionId,…)}）没有
	 * run 围栏，已失去租约的僵尸实例仍可能补写一条。同轮<b>多次</b> L1 清除（后续批次又清掉别的对）
	 * 会各记一条事件，这是两件真事；但归档键按轮号命名 ⇒ 后一次会覆盖前一次，下一轮轮首压缩也会
	 * 复用这个键（被覆盖工具结果的原文仍可从 {@code ai:ledger:<runId>} 的工具台账回取）。
	 * 本方法不引入任何新锁，也不承诺跨进程 exactly-once。
	 *
	 * @return 是否允许把清除结果落到历史（{@code false} = 记账失败，本批不清）
	 */
	private boolean recordTurnClear(String runId, RunSnapshot snapshot, List<Message> beforeClear,
			ToolResultClearing.Plan plan, int systemTokens, int toolsTokens) {
		ContextStateStore store = this.contextStateStore;
		if (store == null || snapshot == null || snapshot.getSessionId() == null) {
			// 轻装配面（单测直接 new）：保持"只清结果"的旧口径；生产由容器注入 RunStore
			return true;
		}
		String sessionId = snapshot.getSessionId();
		long now = System.currentTimeMillis();
		try {
			ContextState state = store.read(sessionId);
			if (state == null) {
				state = ContextState.EMPTY;
			}
			int before = ContextEstimator.estimateMessages(beforeClear);
			int after = ContextEstimator.estimateMessages(plan.messages());
			int turnNo = state.turnNo() + 1;
			// ① 归档清除前原文（与轮首同一个键族 / 同一个 TTL；内容 = 模型当时看到的那份）
			store.archive(sessionId, turnNo, this.codec.serializeAll(beforeClear));
			// ② 真值：level 必须被快照看到（否则"清了但显示没压过"）；turns/lostTokens 与轮首 L1 同口径。
			// 只升不降：`level` 同时是谱系的"当前位置"（ContextTrigger#advanceTo 从这里往上推一格），
			// 已经到 L2/L3 的会话不能被一次轮内 L1 回退成 L1 —— 那会让下一轮重新爬 L1→L2（摘要重做、
			// 档位看起来"降级"）。故仅在当前级 ≤ L1 时改写状态；更高的谱系保持原样（台账照记这次 L1 事件）。
			ContextTrigger.Level lineage = ContextTrigger.Level.fromWire(state.level());
			ContextState clearedState = state;
			if (lineage.ordinal() <= ContextTrigger.Level.L1.ordinal()) {
				// 与轮首 ContextCompactor 的成功路径同一口径：level + "清除后规模估算"基线一起写。
				// est=true 明确这是估算；轮末 recordTurn 会用真值覆盖。缺了这一步，下一次轮首压缩会因为
				// 沿用的旧 used 撞上滞回线（hysteresis_locked）而整轮不做（含 L2/L3）。
				// C13 / I3：与轮首压缩的新基线同口径 —— 含 system + tools（上游真值本就含这两项）。
				// 相加的必须是<b>本轮请求</b>的 prefix（附录入参），不是 state 里上一轮的锚点：
				// 工具集/工作区快照变了之后，旧锚点会让基线算错，而锚点自己也一直不刷新。
				int baseline = after + Math.max(0, systemTokens) + Math.max(0, toolsTokens);
				clearedState = state.withCompaction(ContextTrigger.Level.L1, 0, 0, TurnBudgetPlan.REASON_L1, now)
					.withUsage(baseline, state.completionTokens(), state.turnNo(), true, now, snapshot.getModelId(),
							Math.max(0, systemTokens), Math.max(0, toolsTokens), state.inputTokens());
				store.write(sessionId, clearedState);
			}
			// ③ 台账：键集与 ContextCompactor#ledgerEvent 逐键一致
			store.appendLedger(sessionId, turnClearLedgerEvent(before, after, plan, snapshot, now,
					clearedState.circuit()));
			log.info("轮内 L1 清除已记账 runId={} sessionId={} turnNo={} before={} after={} clearedPairs={}",
					runId, sessionId, turnNo, before, after, plan.cleared().size());
			return true;
		}
		catch (RuntimeException ex) {
			// 三步各自独立写、无事务：此刻归档与 ai:ctx:<sid>（level=L1/atMs）可能已落盘，而台账缺一条。
			// 本批不清（history 原样）；残留靠下一次清除/轮首压缩收敛 —— 不在这里做回滚。
			log.warn("轮内 L1 清除未能记账 runId={}：{}（本批不清；归档/状态可能已写、台账缺一条：三步无事务）",
					runId, ex.getMessage());
			return false;
		}
	}

	/**
	 * 轮内<b>硬截断</b>的记账（C13 / I5；与轮首 {@code ContextCompactor} 的 L3 同账）。
	 *
	 * <h2>为什么必须记</h2>
	 * 越窗恢复会真的丢最旧几轮（模型看到的上下文变了）。只发一条 trace 的结果是：{@code ?ledger=1}
	 * 说"从未压缩"，{@code /context} 的 {@code compaction.level/turns/lostTokens} 也不动，
	 * 归档里取不回被丢掉的那几轮 —— 同一件事三套口径，且用户/运维无法追溯"哪些轮没了"。
	 *
	 * <h2>顺序与失败语义（与轮内 L1 逐项对齐）</h2>
	 * <b>先归档 → 再写真值/档位 → 再写台账</b>；任一步抛错 ⇒ 本批<b>不应用</b>这次截断
	 * （{@code false}）。与 L1 一样<b>不宣称原子</b>：三步是三个独立的 Redis 写，②成功而③失败时
	 * {@code ai:ctx:<sid>} 已是 {@code level=L3} 而台账缺这一条（已知的部分失败窗口，靠下一次
	 * 轮首压缩收敛）；不为此新增锁或回滚协议。
	 *
	 * @return 是否允许把截断结果落到历史（{@code false} = 记账失败，历史原样）
	 */
	private boolean recordTurnTrim(String runId, RunSnapshot snapshot, List<Message> beforeTrim, HardTrim.Plan plan,
			int systemTokens, int toolsTokens) {
		ContextStateStore store = this.contextStateStore;
		if (store == null || snapshot == null || snapshot.getSessionId() == null) {
			// 轻装配面（单测直接 new）：保持"只丢轮"的旧口径；生产由容器注入 RunStore
			return true;
		}
		String sessionId = snapshot.getSessionId();
		long now = System.currentTimeMillis();
		// 原状态留一份引用：失败时 best-effort 回写（N1：失败不得留档位）
		ContextState previous = null;
		try {
			ContextState state = store.read(sessionId);
			if (state == null) {
				state = ContextState.EMPTY;
			}
			previous = state;
			int before = ContextEstimator.estimateMessages(beforeTrim);
			int after = ContextEstimator.estimateMessages(plan.messages());
			int turnNo = state.turnNo() + 1;
			// ① 归档压缩前原文（与轮首/轮内 L1 同一个键族与 TTL）
			store.archive(sessionId, turnNo, this.codec.serializeAll(beforeTrim));
			// ② 真值/档位：基线同轮首（含本轮 system + tools）。
			// N1：<b>至多升一级</b> —— 当前 NONE/L1 ⇒ 最多写 L2，已到 L2/L3 保持原级。
			// 为什么不是无条件 L3：`level` 同时是谱系当前位置（advanceTo 从这里往上推一格、只升不降），
			// 写死 L3 之后 ① 之后每轮目标都被钳在 L3（L2 摘要再也不会被选中，摘要能力等于永久关掉）；
			// ② 一次"临时救场"的轮内截断被永久记成"这个会话已经硬截断过"。台账仍如实记本次 L3。
			int baseline = after + Math.max(0, systemTokens) + Math.max(0, toolsTokens);
			ContextTrigger.Level lineage = ContextTrigger.Level.fromWire(state.level());
			ContextTrigger.Level written = lineage.ordinal() <= ContextTrigger.Level.L1.ordinal()
					? ContextTrigger.Level.L2 : lineage;
			ContextState trimmed = state
				.withCompaction(written, plan.droppedTurns(), plan.lostTokens(), TurnBudgetPlan.REASON_TRIM, now)
				.withUsage(baseline, state.completionTokens(), state.turnNo(), true, now, snapshot.getModelId(),
						Math.max(0, systemTokens), Math.max(0, toolsTokens), state.inputTokens());
			store.write(sessionId, trimmed);
			// ③ 台账：键集与 ContextCompactor#ledgerEvent 逐键一致（L3 带 turns/lostTokens）
			store.appendLedger(sessionId,
					turnTrimLedgerEvent(before, after, plan, snapshot, now, trimmed.circuit()));
			log.info("轮内 L3 硬截断已记账 runId={} sessionId={} turnNo={} before={} after={} turns={} lostTokens={}",
					runId, sessionId, turnNo, before, after, plan.droppedTurns(), plan.lostTokens());
			return true;
		}
		catch (RuntimeException ex) {
			// N1：失败不得把"压过"留在状态里 —— 否则这个会话被永久记成"已截断/已到 L2-L3"（且基线被改小），
			// 而历史其实没动。这里 best-effort 把状态写回原值；写回也失败才退化成已知的部分失败窗口。
			try {
				if (previous != null) {
					store.write(sessionId, previous);
				}
			}
			catch (RuntimeException rollback) {
				log.warn("轮内 L3 硬截断失败后状态回写也失败 runId={}：{}（档位可能残留，等下一次轮首压缩收敛）",
						runId, rollback.getMessage());
			}
			log.warn("轮内 L3 硬截断未能记账 runId={}：{}（本次不截断；已尝试回写状态，归档可能已写）", runId,
					ex.getMessage());
			return false;
		}
	}

	/** 轮内 L3 的台账事件（字段与 {@code ContextCompactor#ledgerEvent} 同键同义）。 */
	private static Map<String, Object> turnTrimLedgerEvent(int before, int after, HardTrim.Plan plan,
			RunSnapshot snapshot, long now, String circuit) {
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("level", ContextTrigger.Level.L3.wire());
		event.put("before", before);
		event.put("after", after);
		event.put("turns", plan.droppedTurns());
		event.put("lostTokens", plan.lostTokens());
		event.put("summaryChars", 0);
		event.put("model", snapshot.getModelId());
		event.put("est", false);
		event.put("atMs", now);
		event.put("reason", TurnBudgetPlan.REASON_TRIM);
		event.put("clearedPairs", 0);
		event.put("clearedToolCallIds", List.of());
		event.put("circuit", circuit == null ? CompactionFuse.CLOSED : circuit);
		return event;
	}

	/** 轮内 L1 的台账事件（字段与 {@code ContextCompactor#ledgerEvent} 同键同义）。 */
	private static Map<String, Object> turnClearLedgerEvent(int before, int after, ToolResultClearing.Plan plan,
			RunSnapshot snapshot, long now, String circuit) {
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("level", ContextTrigger.Level.L1.wire());
		event.put("before", before);
		event.put("after", after);
		event.put("turns", 0);
		event.put("lostTokens", 0);
		event.put("summaryChars", 0);
		event.put("model", snapshot.getModelId());
		event.put("est", false);
		event.put("atMs", now);
		event.put("reason", TurnBudgetPlan.REASON_L1);
		event.put("clearedPairs", plan.cleared().size());
		event.put("clearedToolCallIds", plan.clearedIds());
		event.put("circuit", circuit == null ? CompactionFuse.CLOSED : circuit);
		return event;
	}

	/**
	 * 首轮判定输入：真值 {@code usage}（若本跳有）与整包估算一起交给 {@link TurnBudgetPlan}，
	 * 由它按"真值优先"选判据值。
	 */
	private TurnBudgetPlan.Request turnBudgetRequest(int limit, AiProperties.Context config, ContextMeter meter,
			Prompt prompt, List<Message> history, int delta, boolean armed, String runId) {
		return new TurnBudgetPlan.Request(limit, config.getReserveTokens(), config.getL1Percent(),
				config.getL3Percent(), meter.measured(), meter.promptTokens(), meter.completionTokens(),
				estimateRequestTokens(prompt, history), delta, armed, this.beacon.budgetSkippedBatches(runId));
	}

	/**
	 * 本轮分母（唯一取数点：与轮首压缩、{@code /context}、健康检查同一个 resolver）。
	 *
	 * <p>未注入 resolver ⇒ 返回 0（降级态，不判轮内预算）：既有单测/轻装配面因此行为一字不变；
	 * 生产由容器注入，不存在"漏装配就不判"的静默降级（bean 恒存在）。
	 */
	private int turnBudgetLimit(RunSnapshot snapshot) {
		if (this.contextLimitResolver == null || snapshot == null) {
			return 0;
		}
		try {
			return this.contextLimitResolver.resolve(snapshot.getProviderId(), snapshot.getModelId());
		}
		catch (RuntimeException ex) {
			log.warn("轮内上下文预算解析上限失败 runId={}：{}（本批不判）", snapshot.getRunId(), ex.getMessage());
			return 0;
		}
	}

	/** 下一发请求的整包估算：system + tools schema + 历史含本批。 */
	private static int estimateRequestTokens(Prompt prompt, List<Message> history) {
		return systemTokensOf(prompt) + toolsTokensOf(prompt) + ContextEstimator.estimateMessages(history);
	}

	/** 本轮 system（人设 + 工作区快照）估算 —— 取自 instructions 里的 {@link SystemMessage}。 */
	private static int systemTokensOf(Prompt prompt) {
		int system = 0;
		for (Message message : prompt.getInstructions()) {
			if (message instanceof SystemMessage systemMessage) {
				system += ContextEstimator.estimate(systemMessage.getText());
			}
		}
		return system;
	}

	/** 本轮披露工具 schema 估算（与 {@link #estimateRequestTokens} 同一份判定）。 */
	private static int toolsTokensOf(Prompt prompt) {
		return prompt.getOptions() instanceof ToolCallingChatOptions options
				? ContextEstimator.estimateToolSchemas(options.getToolCallbacks()) : 0;
	}

	/** 未决 HITL 条目的 toolCallId（工具结果永不清除：用户还要在卡片上复核"批的是什么"）。 */
	private java.util.Optional<Set<String>> pendingToolCallIds(String runId) {
		try {
			Set<String> ids = new HashSet<>();
			for (PendingToolCall pending : this.store.pendingsLenient(runId)) {
				if (PendingToolCall.PENDING.equals(pending.getStatus()) && pending.getToolCallId() != null) {
					ids.add(pending.getToolCallId());
				}
			}
			return java.util.Optional.of(ids);
		}
		catch (RuntimeException ex) {
			// 读不出来 ⇒ 本次不清（宁可不省 token，也不清掉用户可能正在复核的那一条）
			log.debug("读取未决条目失败 runId={}（本次跳过 L1 清除）：{}", runId, ex.getMessage());
			return java.util.Optional.empty();
		}
	}

	/** 一次轮内预算的结局（历史 + 是否越窗 + 判定）。 */
	private record TurnBudgetOutcome(List<Message> history, boolean overflow, TurnBudgetPlan.Decision decision) {
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
	 *
	 * <p><b>条目已终态时它也是结算发点，故必须参与同一套仲裁</b>（M-2′ 残余）：取消若是在
	 * {@code await} 里被看到的，条目此刻已经是 {@code CANCELLED}，取消收尾
	 * （{@code ConfirmGate#settleCancelledToolCalls}）还会再结同一条 —— 两侧各发一行就成了
	 * "同一 toolCallId 两条 end"。判据复用 {@code ToolCallEndTrace#emitCustomOnce}
	 * （{@code CONFIRM/FRONTEND} 抢 {@code ai:observed} 首写者，{@code BACKEND} 照旧直发、不占位），
	 * <b>载荷与业务语义逐字不变</b>。
	 *
	 * <p><b>条目仍 {@code PENDING} 时不占位</b>：这时 abort 行只是"本次尝试没走完"的诊断，
	 * 没有结算任何结论；占位会把该条之后真正执行成功的结算行挡掉（从"多一行"变成"少一行"）。
	 * 未决条目的取消收尾在别处也不存在（取消会先把它转成 CANCELLED，届时走的是已终态分支）。
	 */
	private void traceToolAborted(String runId, String sessionId, PendingToolCall pending, long startedAtMs,
			RuntimeException error) {
		long ms = Math.max(0L, System.currentTimeMillis() - startedAtMs);
		boolean cancelled = error instanceof StreamViolationException violation
				&& StreamViolationException.CANCELLED.equals(violation.getCode());
		PendingToolCall settled;
		try {
			settled = this.store.pendingLenient(runId, pending.getToolCallId());
		}
		catch (RuntimeException readFailure) {
			// C1：诊断路径不得二次抛（否则原始异常被替换，连 tool_call_end 都写不出）。
			log.debug("traceToolAborted 读待决条目失败，使用入参 runId={} toolCallId={}：{}", runId,
					pending.getToolCallId(), readFailure.getMessage());
			settled = pending;
		}
		if (settled == null) {
			settled = pending;
		}
		PendingToolCall source = settled;
		Runnable line = () -> AiTrace.eventOn(runId, sessionId, "tool_call_end", "toolCallId",
				pending.getToolCallId(), "tool", pending.getName(), "kind", pending.getKind(), "ms", ms, "execMs", ms,
				"skipped", false, "ok", false, "outcome",
				cancelled ? ToolOutcome.CANCELLED.wire() : ToolOutcome.FAILED.wire(), "code",
				cancelled ? ToolOutcome.CODE_RUN_CANCELLED : CODE_TOOL_EXECUTION_THREW, "executed", source.isExecuted(),
				"chars", 0, "cancelled", cancelled);
		if (PendingToolCall.PENDING.equals(source.getStatus())) {
			line.run();
		}
		else {
			ToolCallEndTrace.emitCustomOnce(this.settlementObservations, runId, pending.getToolCallId(),
					pending.getKind(), line);
		}
	}

	/** 事件边界上的取消检查：命中即抛出，交由 {@code ResilientChatService} 以取消收尾。 */
	private void checkCancelled(String runId) {
		CancellationRegistry.CancelState state = this.cancellations.cancelState(runId);
		if (state == CancellationRegistry.CancelState.CANCELLED) {
			throw new StreamViolationException(StreamViolationException.CANCELLED,
					"本轮对话已被取消，工具执行在事件边界终止（runId=" + runId + "）");
		}
		if (state == CancellationRegistry.CancelState.UNKNOWN) {
			// N-1：这是"执行副作用前的最后一道门"，UNKNOWN 必须拒绝执行；但它不是用户取消，
			// 不能用 CANCELLED（否则会被 cancelTerminal 记成“已停止/ALREADY_CANCELLED”）。
			// 用基础设施不可用码走普通失败/可重试路径。
			throw new StreamViolationException(StreamViolationException.STATE_UNAVAILABLE,
					"取消状态未知（Redis 读失败），工具执行在事件边界终止（runId=" + runId + "）");
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
		String text = ToolPreflightService.NOT_APPROVED_NOT_EXECUTED + "（" + ToolOutcome.CODE_PRECHECK_UNAVAILABLE
				+ "）：预检暂不可用，预检过程发生内部错误，本次未进入确认门、未执行 —— 这不代表条件已满足。"
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
		// 「哪侧被关」以开关的真实状态为准；拿不到（未装配 / 未受治理）才回落按工具分类二分。
		String gate = this.toolRegistry.sheetDisabledGate(name);
		boolean readSide = gate == null
				? SheetSwitches.READ_TOOLS.contains(name)
				: "read-enabled".equals(gate);
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
	private String awaitFrontend(String runId, PendingToolCall pending, AiContext context) {
		FrontendToolGuard.Verdict verdict = inspectFrontendArguments(pending, context);
		if (verdict != null && !verdict.accepted()) {
			return rejectFrontendArguments(runId, pending, verdict);
		}
		PendingToolCall resolved = this.gate.awaitFrontendResult(runId, pending);
		return resolved.getResultText() == null ? "" : resolved.getResultText();
	}

	/**
	 * 第一个认领该工具名的复核器的入参判定；没有复核器返回 null（= 该工具不受闸门约束）。
	 *
	 * <p>上下文与入参一起传给复核器（槽位放行批）：生成式表单要按 {@code purpose} 与
	 * {@code AiContext.extra[taskSlots]} 判断"这张表还有没有真实缺口"；其它复核器走
	 * {@link FrontendToolGuard#inspectArguments(String, String, AiContext)} 的默认实现，行为不变。
	 */
	private FrontendToolGuard.Verdict inspectFrontendArguments(PendingToolCall pending, AiContext context) {
		for (FrontendToolGuard guard : this.frontendGuards) {
			if (guard.supports(pending.getName())) {
				// 工具安全边界批：按**真名**复核 —— 只传 json 会让复核器靠"入参形状"猜工具名，
				// 少一个必填键就会落到别的分支被放行（update_rows 漏 set ⇒ 被当成撤销）。
				return guard.inspectArguments(pending.getName(), pending.getArguments(), context);
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
	 *
	 * <p>正常路径（挂起前的预检）只做 {@link #settleFrontendRejected}（写条目 + 日志），帧由
	 * 执行循环按既有帧序发；本方法保留给 {@link #awaitFrontend} 的兜底复查（预检后入参不会变，
	 * 走到这里说明装配被人为改动，仍要收敛成同一结局）。
	 */
	private String rejectFrontendArguments(String runId, PendingToolCall pending, FrontendToolGuard.Verdict verdict) {
		String text = settleFrontendRejected(runId, pending, verdict);
		this.registry.of(runId).frontendToolResult(pending, false, text);
		return text;
	}

	/** 入参拒绝的<b>结算</b>（状态/码/原因/结果文本 + 落库 + WARN）；不发帧、不认领、不记台账。 */
	private String settleFrontendRejected(String runId, PendingToolCall pending, FrontendToolGuard.Verdict verdict) {
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

	/** 创建任务工具名（AR-03：DANGER 确认门 + 宿主幂等 + 单轮上限 1）。 */
	public static final String TOOL_CREATE_TASK = "create_task";

	/** 工具被"别人正在做同类动作"挡下时的固定口径（模型/帧/台账三面同文；禁 emoji）。 */
	public static final String RESOURCE_LOCKED_TEXT = ToolPreflightService.APPROVED_NOT_EXECUTED + "（"
			+ ToolOutcome.CODE_RESOURCE_LOCKED + "）：该任务已有作业在执行，本次未执行。"
			+ "请等待该作业收尾后重新发起（资源锁会随对方收尾释放）。";

	/**
	 * 错入口批准（{@code APPROVED + FRONTEND}）的固定口径：显式 blocked、未执行。
	 *
	 * <p>措辞必须同时说清三件事：<b>哪个码</b>（模型/排障可检索）、<b>没执行</b>（不能说成失败或成功）、
	 * <b>正确出口</b>（前端结果只能经回灌端点回来）。
	 */
	public static final String KIND_MISMATCH_TEXT = ToolOutcome.CODE_TOOL_KIND_MISMATCH
			+ "：该调用是前端通道工具（kind=FRONTEND），后端不执行它的桩体、也不写执行台账；"
			+ "本次未执行（blocked）。前端结果只能经 /api/ai/frontend-tool-result 回灌。";

	/**
	 * {@code kind=null} 的固定口径：<b>既不批准也不执行</b>。
	 *
	 * <p>{@code kind} 是服务端写入的通道事实（CONFIRM / FRONTEND / BACKEND）。它缺失 = 无法证明
	 * 这个调用该走确认门还是前端回灌；按"缺证据不授权"处理，显式归为
	 * {@code blocked}/{@code TOOL_KIND_MISMATCH}，条目<b>不进入</b> APPROVED，也不跑后端桩体。
	 */
	public static final String KIND_UNKNOWN_TEXT = ToolOutcome.CODE_TOOL_KIND_MISMATCH
			+ "：该调用的挂起种类缺失（kind=null），无法确认它该走确认门还是前端通道；"
			+ "本次既不批准也不执行（blocked），未认领、未写台账。请让用户重新发起该操作。";

	/**
	 * 批准后执行前复核 {@code reportHash} 不一致的固定口径：<b>不执行、要求重新确认</b>。
	 *
	 * <p>确认卡上那份预检报告是批准的依据；执行时状态已变（文件被替换、有在途作业、任务改了步骤……）
	 * 就必须让用户看新的报告再决定，不能拿旧报告当授权去执行。
	 */
	public static final String PRECHECK_STALE_TEXT = ToolPreflightService.APPROVED_NOT_EXECUTED + "（"
			+ ErrorCodes.PRECHECK_STALE + "）：确认卡上的预检报告与执行时的状态不一致，本次未执行。"
			+ "请重新发起该操作并要求用户重新确认（新的确认卡会带上最新预检报告）。";

	/** 执行前复核预检报告时基础设施不可用：同样不执行（失败关闭），要求重新发起并确认。 */
	public static final String PRECHECK_REVERIFY_UNAVAILABLE_TEXT = ToolPreflightService.APPROVED_NOT_EXECUTED + "（"
			+ ToolOutcome.CODE_PRECHECK_UNAVAILABLE + "）：执行前无法复核预检报告（预检基础设施异常），"
			+ "本次未执行。请稍后重新发起该操作并重新确认。";

	/**
	 * 快照/runId 缺失且无可信执行信封时的拒绝口径：危险/前端工具<b>不得直执行</b>。
	 *
	 * <p>机器码沿用 {@code TOOL_KIND_MISMATCH}（"这个调用不能在后端直执行通道上跑"），
	 * 措辞说清"没弹卡、没执行"，让模型如实告知用户重新发起，而不是宣称已执行。
	 */
	public static final String UNRECOVERABLE_EXECUTION_TEXT = ToolOutcome.CODE_TOOL_KIND_MISMATCH
			+ "：本轮执行上下文不可恢复（runId/轮次快照缺失，且没有本实例创建的权威执行信封），"
			+ "该危险/前端工具未执行（未弹确认卡、未认领、未写台账）。请如实告知用户这一步没有执行，"
			+ "并请用户重新发起该操作；不要重复调用同一工具。";

	/**
	 * 认领前复核命中取消时的统一文本（与 {@code cancelPendings} / {@code ConfirmGate#cancel} 逐字同源）：
	 * 模型与前端都据此知道"没执行"，而不是"失败"或"未知"。
	 */
	static final String CANCELLED_BEFORE_CLAIM_TEXT = "本轮对话已被用户取消，该工具未执行。";

	/** 工具被"目标资源不成立"挡下时的固定口径（细节由 {@link ToolTargetResolver} 给出）。 */
	public static String targetInvalidText(ToolTargetResolver.Verdict verdict) {
		String detail = verdict == null || verdict.detail() == null || verdict.detail().isBlank() ? "目标资源不成立"
				: verdict.detail();
		return ToolPreflightService.APPROVED_NOT_EXECUTED + "（" + ToolOutcome.CODE_TARGET_INVALID
				+ "）：目标资源不存在或不在当前任务内，本次未执行（" + detail
				+ "）。请核对任务 ID 与当前页面/步骤后重新发起。";
	}

	/**
	 * 后端执行（含放行后的敏感工具）：<b>对账/重放 → 目标校验 → 资源锁 → 认领 → 执行 → 台账</b>。
	 *
	 * <ol>
	 * <li><b>入口 kind 硬闸</b>（入口 kind 校验批）：{@code kind=FRONTEND} 一律不执行（前端通道工具的
	 * 桩体永不由后端跑）⇒ {@code blocked}/{@code TOOL_KIND_MISMATCH}，<b>在台账重放与认领之前</b>；</li>
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
		return executeOnce(runId, prompt, pending, context, false);
	}

	/**
	 * @param settleObservation 本次调用的结算行是否由<b>本方法</b>发（续跑路径 = {@code true}）。
	 * 内联批循环传 {@code false}：它在该条目结算之后统一发一行（并记台账）—— 两处都发会让同一个
	 * {@code toolCallId} 出现两条 {@code tool_call_end}、{@code readCalls} 被记两次。
	 * <b>发点仍然只有一个</b>：{@code tryClaimExecution} 的胜者那一支（见 {@link #claimAndExecute}）。
	 */
	private String executeOnce(String runId, Prompt prompt, PendingToolCall pending, AiContext context,
			boolean settleObservation) {
		// ⓪-a kind 缺失硬闸：无法证明该走确认门/前端通道，既不批准也不执行（不认领、不写台账）。
		if (!pending.hasKind()) {
			log.warn("拒绝后端执行 kind 缺失的条目 runId={} toolCallId={} name={} status={}（显式 blocked，不写台账）",
					runId, pending.getToolCallId(), pending.getName(), pending.getStatus());
			return settleBlocked(runId, pending, ToolOutcome.CODE_TOOL_KIND_MISMATCH, KIND_UNKNOWN_TEXT);
		}
		// ⓪ 入口 kind 硬闸（入口 kind 校验批）：前端通道工具的桩体永不由后端执行。
		// 位置在台账重放与认领<b>之前</b>：既不能"重放"一条历史成功台账把假执行说成成功，
		// 也不能占认领、写新台账。正常路径走不到这里（FRONTEND 走 awaitFrontend），本闸是兜底。
		if (PendingToolCall.KIND_FRONTEND.equals(pending.getKind())) {
			log.warn("拒绝后端执行前端通道工具 runId={} toolCallId={} name={} status={}（显式 blocked，不写台账）",
					runId, pending.getToolCallId(), pending.getName(), pending.getStatus());
			return settleBlocked(runId, pending, ToolOutcome.CODE_TOOL_KIND_MISMATCH, KIND_MISMATCH_TEXT);
		}

		// ① 对账/重放：命中台账 ⇒ 原结果复用（三面同源），任何副作用都不发生
		Map<String, Object> ledger = ledgerOf(runId, pending.getToolCallId());
		if (ledger != null) {
			return replayLedger(runId, pending, ledger);
		}

		// ①.5 确认门执行前复核：批准时看到的预检报告必须仍是当前那份（reportHash 一致），
		// 不一致 ⇒ 不执行并要求重新确认（旧报告不是执行授权）。
		String staleReport = verifyApprovedReport(runId, pending, context);
		if (staleReport != null) {
			return staleReport;
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
			return claimAndExecute(runId, prompt, pending, settleObservation);
		}
		finally {
			// 终态与异常路径都必须归还（只放本次创建的那一任；重入没有释放权）
			locks.close();
		}
	}

	/**
	 * <b>认领前的取消复核</b>（R1）：取消标志已在 ⇒ 放弃执行，并在"可证明未认领未执行"时把条目
	 * 如实收敛为 {@code CANCELLED}。
	 *
	 * <p>为什么放在这里：批准只写决策、执行在续跑；上游的执行前检查（{@code ResumeService} 的
	 * {@code cancelStateOutcome}）与真正的 {@code HSETNX} 之间还隔着台账重放、目标校验、资源锁。
	 * 只修上游检查会留下这个窗口 —— 用户在"已批准、还没跑"时按停止，不可逆工具照样执行。
	 *
	 * <p><b>不碰已认领/已执行</b>：判据交 {@link RunStore#cancellableBeforeExecution}（含跨实例的
	 * 认领表），有认领证据时返回 {@code null} 走既有 claim 失败口径 —— 绝不把真实发生的副作用
	 * 说成"没执行"。
	 *
	 * <p><b>不发结算行</b>：本方法只收敛条目。结算的所有权仍在取消侧（{@code cancelTerminal} 的
	 * {@code settleCancelledToolCalls}）或内联批循环的 abort 行，两侧都经 {@code ai:observed} 仲裁；
	 * 这里补一条会变成第三个来源（同一 toolCallId 两条 end）。
	 *
	 * @return 放弃执行时的工具结果文本；{@code null} = 未命中取消（正常走认领）
	 */
	private String settleIfCancelledBeforeClaim(String runId, PendingToolCall pending) {
		CancellationRegistry.CancelState state;
		try {
			state = this.cancellations.cancelState(runId);
		}
		catch (RuntimeException ex) {
			log.warn("认领前读取取消状态失败，按 UNKNOWN 处理（不执行不可逆工具）runId={} toolCallId={}：{}", runId,
					pending.getToolCallId(), ex.getMessage());
			state = CancellationRegistry.CancelState.UNKNOWN;
		}
		if (state == CancellationRegistry.CancelState.UNKNOWN) {
			// 与相邻的 checkCancelled 同一口径（fail-closed）：读不出来 = 证明不了"未取消" ⇒ 不执行。
			// 条目状态与终帧一概不动 —— 基础设施不可知不是"已取消"，写成 CANCELLED 会发一张假终帧。
			throw new StreamViolationException(StreamViolationException.STATE_UNAVAILABLE,
					"取消状态未知（Redis 读失败），工具执行在认领前终止（runId=" + runId + "）");
		}
		if (state != CancellationRegistry.CancelState.CANCELLED) {
			return null;
		}
		PendingToolCall fresh;
		try {
			fresh = this.store.pendingLenient(runId, pending.getToolCallId());
		}
		catch (RuntimeException ex) {
			fresh = null;
		}
		if (fresh == null) {
			fresh = pending;
		}
		if (this.store.hasExecutionEvidence(runId, fresh)) {
			// 已认领/已执行/认领表已有 → 绝不伪装未执行；交给既有 claim 失败与对账口径。
			return null;
		}
		if (PendingToolCall.CANCELLED.equals(fresh.getStatus())) {
			// 存储里已经是取消终态、且没有任何执行证据（调用方手里是旧的 APPROVED/PENDING 副本）：
			// 不认领、不执行，直接返回既有取消文本。旧口径在这里返回 null ⇒ 掉回 tryClaimExecution，
			// 会把一条已取消的调用真正执行掉。
			String text = fresh.getResultText();
			log.info("认领前发现条目已取消（无执行证据），放弃执行 runId={} tool={} toolCallId={}", runId,
					pending.getName(), pending.getToolCallId());
			return text == null || text.isBlank() ? CANCELLED_BEFORE_CLAIM_TEXT : text;
		}
		if (!PendingToolCall.PENDING.equals(fresh.getStatus())
				&& !PendingToolCall.APPROVED.equals(fresh.getStatus())) {
			return null;
		}
		fresh.setStatus(PendingToolCall.CANCELLED);
		fresh.setOutcome(ToolOutcome.CANCELLED.wire());
		fresh.setCode(ToolOutcome.CODE_RUN_CANCELLED);
		fresh.setReason("RUN_CANCELLED");
		fresh.setExecuted(false);
		fresh.setResolvedAtMs(System.currentTimeMillis());
		fresh.setResultText(CANCELLED_BEFORE_CLAIM_TEXT);
		this.store.putPending(runId, fresh);
		log.info("认领前复核到取消，放弃执行 runId={} tool={} toolCallId={}", runId, pending.getName(),
				pending.getToolCallId());
		return CANCELLED_BEFORE_CLAIM_TEXT;
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
	 *
	 * <p><b>观测面的 exactly-once 就是这里</b>：只有认领胜者真正执行了工具，故只有它发
	 * {@code tool_call_end}（{@code settleObservation=true} 时）。重挂/重试走到认领失败——
	 * 那时不补，否则"重挂一次"就等于"多记一次工具调用"。
	 */
	private String claimAndExecute(String runId, Prompt prompt, PendingToolCall pending, boolean settleObservation) {
		// ===== R1：认领前的最后一道取消复核 =====
		// 上游的执行前检查（ResumeService 的 cancelStateOutcome）与真正的 claim 之间还有一段
		// 台账/目标校验/资源锁的路程；取消恰好落在那里时，只靠上游检查就会"取消后仍执行不可逆工具"。
		// 命中取消 ⇒ 不认领、不执行；条目只在"可证明未认领未执行"时收敛为 CANCELLED
		// （已认领/已执行一律不碰，交给下面的 claim 失败口径）。结算行不在这里补：取消侧与批循环
		// 各按所有权、经 ai:observed 只发一条，本处补发会多一个来源。
		String cancelledBeforeClaim = settleIfCancelledBeforeClaim(runId, pending);
		if (cancelledBeforeClaim != null) {
			return cancelledBeforeClaim;
		}
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
		boolean settleAsUnexecuted = false;
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
			settleAsUnexecuted = true;
			text = ex.getMessage() + "\n" + RECONCILIATION_NOTE;
			pending.setOutcome(ToolOutcome.TIMEOUT.wire());
			pending.setCode(ToolOutcome.CODE_TOOL_TIMEOUT);
		}
		catch (ToolExecutorSaturatedException ex) {
			// N3：执行器饱和 ⇒ <b>本次调用没有开始执行</b>（绝不降级成无超时同步执行）。
			// 结算走既有"未执行"口径：outcome=timeout / code=TOOL_TIMEOUT / executed=false、不写台账；
			// 文案明确说"未执行"，模型据此如实告知用户稍后重试（不能宣称成功或失败）。
			log.warn("工具执行器饱和，本次调用未执行 runId={} tool={} toolCallId={} 容量={}", runId,
					pending.getName(), pending.getToolCallId(), ex.getCapacity());
			ok = false;
			settleAsUnexecuted = true;
			text = ex.getMessage();
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
		if (settleAsUnexecuted) {
			return settleToolTimeout(runId, pending, text, settleObservation);
		}
		this.store.appendLedger(runId, pending, text);

		PendingToolCall fresh;
		try {
			fresh = this.store.pendingLenient(runId, pending.getToolCallId());
		}
		catch (RuntimeException ex) {
			log.debug("结算读待决条目失败，回落入参 runId={} toolCallId={}：{}", runId,
					pending.getToolCallId(), ex.getMessage());
			fresh = pending;
		}
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
		// 续跑侧的唯一发点：认领胜者 = 真的执行过的那一次（内联路径由批循环自己发，settleObservation=false）
		if (settleObservation) {
			ToolCallEndTrace.emitForSettledEntry(this.settlementObservations, runId, sessionIdOf(runId), fresh, text,
					this.sheetAudit);
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
	 * <p>线程池饱和（{@code AbortPolicy}）时<b>禁止退化成无超时同步执行</b>（N3）：那会让"到点收口"
	 * 恰好在最忙的时候失效，且工具会脱离超时笼在调用线程上跑完。饱和直接抛
	 * {@link ToolExecutorSaturatedException}，由调用方按 {@code TOOL_TIMEOUT} 口径结算成
	 * <b>未执行</b>（{@code executed=false}、不写台账、轮继续）。
	 */
	private String executeWithTimeout(Prompt prompt, PendingToolCall pending) {
		Duration timeout = this.properties.getTool().getExecTimeout();
		if (timeout == null || timeout.isZero() || timeout.isNegative()) {
			return delegateSingle(prompt, pending);
		}
		Future<String> future;
		// 身份不随线程池继承：在提交线程抓本轮身份，执行线程重新建立作用域（不把句柄跨线程关闭）
		TenantUser identity = RequestContextUtils.currentOrNull();
		try {
			// MDC 不随线程池继承：锚点（runId/sessionId）随任务进池，工具日志才不会变成孤儿行
			future = this.toolTimeoutExecutor.submit(AiTrace.wrapCallable(() -> {
				try (RunIdentityScope.Scope ignored = RunIdentityScope.open(identity)) {
					return delegateSingle(prompt, pending);
				}
			}));
		}
		catch (RejectedExecutionException ex) {
			log.warn("工具超时执行器饱和（容量 {}），本次调用未执行 tool={} toolCallId={}", this.toolTimeoutThreads,
					pending.getName(), pending.getToolCallId());
			throw new ToolExecutorSaturatedException(pending.getName(), this.toolTimeoutThreads);
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
	 * <b>未执行</b>的结算（超时 / 执行器饱和共用；{@code executed=false} + 结局帧 + 回填模型）：
	 * <b>不写台账</b>、不占重放索引 —— 台账记的是"执行过且知道结局"的事实；超时的结局是
	 * "不知道有没有副作用"，由 {@link #RECONCILIATION_NOTE} 提醒人工对账；执行器饱和则是确定的
	 * "没有开始执行"。两者都按既有 {@code timeout/TOOL_TIMEOUT} 口径收敛（不判整轮失败、轮继续）。
	 *
	 * <p>它也是认领胜者的结算（只有认领成功才会走到这里），故续跑侧同样由这里发
	 * {@code tool_call_end}（{@code settleObservation=true}）。
	 */
	private String settleToolTimeout(String runId, PendingToolCall pending, String text, boolean settleObservation) {
		PendingToolCall fresh;
		try {
			fresh = this.store.pendingLenient(runId, pending.getToolCallId());
		}
		catch (RuntimeException ex) {
			log.debug("结算读待决条目失败，回落入参 runId={} toolCallId={}：{}", runId,
					pending.getToolCallId(), ex.getMessage());
			fresh = pending;
		}
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
		if (settleObservation) {
			ToolCallEndTrace.emitForSettledEntry(this.settlementObservations, runId, sessionIdOf(runId), fresh, text,
					this.sheetAudit);
		}
		log.warn("工具未执行结局已结算（executed=false，轮继续）runId={} tool={} toolCallId={} code={}", runId,
				pending.getName(), pending.getToolCallId(), fresh.getCode());
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
	 * 工具超时执行器饱和的内部信号（N3）：<b>本次调用没有开始执行</b>。
	 *
	 * <p>为什么单独一个类型而不是直接返回文本：饱和是"未执行"的确定性事实，必须走与超时同一套
	 * 结算（{@code executed=false}、不写台账、轮继续），文案里保留 {@code EXECUTOR_SATURATED}
	 * 这个机器可检索词，便于把"这一拍没跑"与"跑超时了"分开排障。
	 */
	private static final class ToolExecutorSaturatedException extends RuntimeException {

		private final int capacity;

		ToolExecutorSaturatedException(String toolName, int capacity) {
			super("未执行（EXECUTOR_SATURATED / " + ToolOutcome.CODE_TOOL_TIMEOUT + "）：工具执行器已饱和（容量 "
					+ capacity + "），" + toolName + " 本次调用没有开始执行、没有产生副作用；请稍后重新发起。");
			this.capacity = capacity;
		}

		int getCapacity() {
			return this.capacity;
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
	 * 阻断的<b>统一出口</b>（资源锁冲突 / 目标不成立 / 写回权转移）：落库为终态、回填模型，然后
	 * 正常返回 —— <b>不 appendLedger、不占认领、不抛异常、不动 suspended</b>，同批后续调用照常
	 * （{@code haltsBatch} 只认 FAILED）。
	 *
	 * <p><b>帧面不保证</b>（RT-21 裁决）：这里只<b>请求</b>一条 {@code ok=false} 的结局帧，是否真的
	 * 落档/扇出由 {@link com.example.configmgr.ai.run.SseChatEmitter} 的写门判定。写回权已转移的
	 * 旧驱动（{@code fence > 本进程 epoch}）会被写门丢弃该帧：它没有续跑权，不得再写共享事件/帧，
	 * <b>不给这份旧驱动任何帧豁免</b>。但"没有共享帧"不等于"结果可以被吞"：{@code blocked} 文本与
	 * 机器可读结局（{@code outcome}/{@code code}）仍由本方法返回，沿原工具调用链进入模型的
	 * {@code ToolResponseMessage}（"本次未执行、需由接管方完成"），不得伪装成成功。
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
		// 动态披露：本批请求边界之外的"新披露工具"不在旧 options 的 callbacks 里，官方委托按名字
		// 找不到就会抛 No ToolCallback found —— 用当前披露面的 callbacks 副本保证可解析（集合可随披露面
		// 变化，但不是授权：能否执行仍由防线③与确认门/HITL 决定）。
		ToolExecutionResult result = this.delegate.executeToolCalls(executionPrompt(prompt, pending.getName()),
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
	 * 模型侧的统一信封 JSON（C13 窗口版）：在配置档之外再按<b>剩余窗口</b>收缩。
	 *
	 * <p>截断判定仍是同一个 {@link ToolResultLimiter}（窗口额度作为第二个上限传入
	 * {@link ToolResultLimiter#forModel(String, String, int)}）：于是 {@code truncated/strategy/
	 * originalChars/returnedChars/notice} 五个字段照旧写进信封，模型仍能看见"这份结果不是全部"；
	 * SSE 帧与执行台账里的 {@code resultText} 仍是全文（双通道口径不动）。
	 *
	 * @param budget 本批剩余窗口（{@code unlimited()} = 不按窗口收缩，与改造前逐字一致）
	 */
	private String modelData(PendingToolCall pending, String text, ModelWriteBudget budget) {
		String modelJson = ToolResultEnvelope
			.of(pending.effectiveOutcome(), pending.effectiveCode(), text, pending.getArguments())
			.toModelJson(this.limiter, pending.getName(), budget.nextCapChars());
		budget.consume(modelJson);
		return modelJson;
	}

	/**
	 * 本批模型侧写入的剩余窗口（C13）。
	 *
	 * <p>算式：{@code 实际窗口 − 有效预留 − system/tools − 已固定的历史 − 本批 assistant(tool_calls)}。
	 * 上限未知（轻装配面/未注入 resolver）⇒ {@link ModelWriteBudget#unlimited()}（行为一字不变）。
	 */
	private ModelWriteBudget modelWriteBudget(RunSnapshot snapshot, Prompt prompt, AssistantMessage assistant,
			int calls) {
		int limit = turnBudgetLimit(snapshot);
		if (limit <= 0 || calls <= 0) {
			return ModelWriteBudget.unlimited();
		}
		int reserve = ContextTrigger.effectiveReserve(limit, this.properties.getContext().getReserveTokens());
		int fixedPrefix = estimateRequestTokens(prompt, prompt.getInstructions())
				+ ContextEstimator.estimateMessage(assistant);
		int remaining = limit - reserve - fixedPrefix;
		if (remaining <= 0) {
			// 窗口已经被固定历史吃满：收缩单份结果救不回来（交给轮内收缩/恢复路径），此处不裁
			return ModelWriteBudget.unlimited();
		}
		return ModelWriteBudget.of(remaining, calls);
	}

	/**
	 * 一批工具结果写进模型侧时的剩余窗口（C13）。
	 *
	 * <p>每份结果按 {@code 剩余 / 本批剩余调用数} 均分：一份结果独占整窗就等于"下一跳必然越窗"，
	 * 而 {@code keep-tool-pairs} 又让轮内 L1 清不掉最近的几对。写入后按实际写出的文本扣减，
	 * 于是同一批里靠后的结果自动拿到更小的额度（顺序确定 ⇒ 可重放）。
	 */
	private static final class ModelWriteBudget {

		private static final ModelWriteBudget UNLIMITED = new ModelWriteBudget(0, 0);

		private int remainingTokens;

		private int callsLeft;

		private ModelWriteBudget(int remainingTokens, int callsLeft) {
			this.remainingTokens = remainingTokens;
			this.callsLeft = callsLeft;
		}

		/** 无窗口信息（不收缩）。 */
		static ModelWriteBudget unlimited() {
			return UNLIMITED;
		}

		static ModelWriteBudget of(int remainingTokens, int calls) {
			return remainingTokens <= 0 || calls <= 0 ? UNLIMITED
					: new ModelWriteBudget(remainingTokens, calls);
		}

		/** 本次写入的字符额度（{@code 0} = 不按窗口收缩）。 */
		int nextCapChars() {
			if (this.callsLeft <= 0) {
				return 0;
			}
			int share = Math.max(1, this.remainingTokens / this.callsLeft);
			return ToolResultLimiter.charsForRemainingTokens(share);
		}

		/** 扣减已写出的模型侧文本（token 估算，与判据同一把尺子）。 */
		void consume(String modelJson) {
			if (this.callsLeft <= 0) {
				return;
			}
			this.remainingTokens = Math.max(0, this.remainingTokens - ContextEstimator.estimate(modelJson));
			this.callsLeft--;
		}

	}

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
	 * <li>{@code APPROVED}：补执行恰好一次（E2 窗口）；<b>但 {@code APPROVED + FRONTEND} 例外</b>
	 * —— 那是历史假批准（人没点过、前端也没执行过），显式结算成 {@code blocked}/
	 * {@code TOOL_KIND_MISMATCH}，<b>不执行后端桩、不写台账</b>（见 {@link #KIND_MISMATCH_TEXT}）；</li>
	 * <li>其余（{@code EXECUTED}/{@code REJECTED}/{@code TIMEOUT}/{@code FRONTEND_RESULT}/
	 * {@code FRONTEND_CANCELLED}/{@code REJECTED_ARGUMENTS}/{@code BLOCKED}/{@code PRECHECK_FAILED}）：
	 * 一律复用 {@code resultText}，<b>不执行</b>。</li>
	 * </ul>
	 *
	 * <h2>观测面：本方法<b>不是</b> {@code tool_call_end} 的发点</h2>
	 * 前端结果/放弃的结算行由写入方（{@code ConfirmGate} 的决策/放弃胜者）发，后端执行的结算行由
	 * {@link #executeOnce} 在<b>认领胜者</b>那一支发 —— 于是"挂起一次 ⇒ 一条 end"与所有权一一对应。
	 * 上面那个"复用 {@code resultText}"的兜底分支刻意不补：重挂/重试会反复经过它，
	 * 在那里记账会把 {@code SheetAudit} 计数放大成"重挂次数"（park 缺陷的反向坑）。
	 *
	 * @return 该条目对应的工具结果文本（用于重建 {@code role:tool} 消息）
	 */
	public String resolvePending(String runId, Prompt prompt, PendingToolCall pending) {
		String status = pending.getStatus();
		// kind 缺失硬闸：无法证明该走哪条通道 ⇒ 不执行（也不说成"失败"或"成功"）。
		// 只拦"本来会执行"的三种态（APPROVED / PENDING / EXECUTED 但 executed=false）；
		// 已结算的历史条目仍复用 resultText，绝不改写既有结局。
		boolean wouldExecute = PendingToolCall.APPROVED.equals(status) || PendingToolCall.PENDING.equals(status)
				|| (PendingToolCall.EXECUTED.equals(status) && !pending.isExecuted());
		if (!pending.hasKind() && wouldExecute) {
			log.warn("续跑发现 kind 缺失的待决条目 runId={} toolCallId={} name={} status={}："
					+ "显式 blocked，不批准、不执行、不写台账", runId, pending.getToolCallId(), pending.getName(), status);
			ensureToolStartBeforeSettlement(runId, pending);
			return settleBlocked(runId, pending, ToolOutcome.CODE_TOOL_KIND_MISMATCH, KIND_UNKNOWN_TEXT);
		}
		// 入口 kind 校验（续跑假批准）：APPROVED + FRONTEND 是"人根本没点过、前端也没执行过"的
		// 假批准（旧代码/错入口留下的数据）。它<b>绝不能</b>进 executeOnce —— 那会把前端工具的
		// 后端哨兵桩跑一遍并写成功台账。这里显式结算成 blocked（不认领、不写台账、executed=false）。
		if (PendingToolCall.APPROVED.equals(status) && PendingToolCall.KIND_FRONTEND.equals(pending.getKind())) {
			log.warn("续跑发现 APPROVED+FRONTEND（历史假批准/错入口决策）runId={} toolCallId={} name={}："
					+ "显式 blocked，不执行后端桩、不写台账", runId, pending.getToolCallId(), pending.getName());
			ensureToolStartBeforeSettlement(runId, pending);
			return settleBlocked(runId, pending, ToolOutcome.CODE_TOOL_KIND_MISMATCH, KIND_MISMATCH_TEXT);
		}
		boolean shouldExecute = PendingToolCall.APPROVED.equals(status)
				|| (PendingToolCall.PENDING.equals(status) && PendingToolCall.KIND_BACKEND.equals(pending.getKind()))
				|| (PendingToolCall.EXECUTED.equals(status) && !pending.isExecuted());
		if (shouldExecute) {
			// 结算侧帧册不变量：本分支的所有出口（台账重放 / 目标校验 / 资源锁 / 认领 / 执行 / 超时）
			// 都会<b>请求</b>发 tool_result（失去续跑权的旧驱动那条会被写门丢弃，见 {@link #settleBlocked}），
			// 而原实例可能死在 tool_start 落档之前（suspend-kill-a）—— 结算之前按归档幂等补一次，
			// 已有则不补；写门同样会拦住旧驱动的补帧。
			ensureToolStartBeforeSettlement(runId, pending);
			return executeOnce(runId, prompt, pending, contextOf(runId), true);
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
	 * 续跑结算前<b>幂等补发</b>缺失的 {@code tool_start}（帧册不变量：每个被执行工具恰一条 start）。
	 *
	 * <p>缺陷形态（真实集群 {@code suspend-kill-a}）：首轮"状态先落、帧后发"，原实例死在
	 * {@code tool_start} 落档之前，归档里就永远没有这一帧；续跑（本方法只被
	 * {@link com.example.configmgr.ai.run.ResumeService} 调用）只发结局帧 ⇒ 永久"有 result、没有 start"。
	 *
	 * <p>为什么挂在这里而不是 {@link #executeOnce}：后者也是<b>首轮</b>执行路径的出口，而首轮
	 * 发帧顺序（{@code suspended → tool_start → 请求帧 → 执行}）由 {@code emitToolStartOnce} 保证、
	 * 不允许被一次归档读改写；本方法是续跑结算的唯一入口，覆盖面相同而工作面最小。
	 *
	 * <p>判据在事件日志（跨实例同一份）——已有该 {@code toolCallId} 的 {@code tool_start} 就一条不补。
	 * 发帧是观测面：失败只 WARN，绝不阻断结算。
	 */
	private void ensureToolStartBeforeSettlement(String runId, PendingToolCall pending) {
		try {
			if (this.registry.of(runId).ensureToolStart(pending)) {
				log.info("续跑补发缺失的 tool_start runId={} toolCallId={} name={}", runId,
						pending.getToolCallId(), pending.getName());
			}
		}
		catch (RuntimeException ex) {
			log.warn("续跑补发 tool_start 失败（不阻断结算）runId={} toolCallId={}：{}", runId,
					pending.getToolCallId(), ex.getMessage());
		}
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

	/**
	 * 续跑结算行的事件锚点：{@code sessionId} 必须显式读一次快照 —— 续跑线程（跨实例唤醒 /
	 * 续跑池）的 MDC 未必装着会话，而 {@code ai:safety:<sessionId>} 台账就挂在这个键上。
	 * 读失败/快照缺失返回 {@code null}，由 {@code AiTrace#eventOn} 回落 MDC。
	 */
	private String sessionIdOf(String runId) {
		try {
			RunSnapshot snapshot = this.store.get(runId);
			return snapshot == null ? null : snapshot.getSessionId();
		}
		catch (RuntimeException ex) {
			log.debug("结算行读取 sessionId 失败 runId={}：{}", runId, ex.getMessage());
			return null;
		}
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
			// 批4-D（用户 2026-10-23 裁决）：不可撤销 = 确认卡 + 复选框。判据是执行类别
			// （Category.P = 不可逆写/发布），不是风险等级 —— DANGER 的 W/J 类工具照旧只弹确认卡。
			pending.setIrreversible(this.toolRegistry.categoryOf(call.name()) == ToolMeta.Category.P);
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

	/**
	 * 该条目的拒绝是否应<b>静默用户可见的工具帧</b>（只此一码）。
	 *
	 * <p>{@code FORM_SLOTS_ALREADY_KNOWN} = 生成式表单想收集的槽位已由任务/工作区或用户明确值确定：
	 * 它是"没有缺口"，不是"输入有错"，拒绝本身零副作用且有明确纠正出口（直接调
	 * {@code select_definitions} / {@code set_condition}）。因此三面照旧、帧面静默：
	 * <ul>
	 * <li>条目：仍是 {@code REJECTED_ARGUMENTS} + 同名 code（审计/幂等/排障）；</li>
	 * <li>日志：{@code settleFrontendRejected} 的 WARN 照旧；</li>
	 * <li>模型：统一信封回执照旧（含码与"下一步"指引）；</li>
	 * <li>用户：{@code tool_start} 与 {@code frontend_tool_result} <b>都不发</b> —— 前端不建段，就不会有卡。</li>
	 * </ul>
	 *
	 * <p>只认这一个码：其它入参拒绝（schema 白名单等）必须让用户看到"这张表为什么没成"。
	 * 工具名同时校验，是为了不让别的工具"借用"这个码绕过可见性。
	 */
	private static boolean silentlyReleased(PendingToolCall pending) {
		return GenerativeFormRules.TOOL_NAME.equals(pending.getName())
				&& ErrorCodes.FORM_SLOTS_ALREADY_KNOWN.equals(pending.effectiveCode());
	}

	/**
	 * 工具批后把<b>运行上下文</b>推进到真实结果（多步工作流批）。
	 *
	 * <p>三层口径：
	 * <ol>
	 * <li><b>只由成功结算触发</b>：判据（工具名 + {@code outcome=OK}）在
	 * {@link RunContextRefresher#next} 里，本方法只负责"读快照 → 算 → 写回"；</li>
	 * <li><b>批内冻结</b>：这里只改<b>外置快照</b>，执行循环里那份 {@code context} 变量一字不动 ——
	 * 同一批里"导航 + 选配置"仍按批首 scope 被拒（{@code SCOPE_NOT_DISCLOSED}），下一批才放行；</li>
	 * <li><b>失败不打断</b>：任何异常（读快照/任务服务/写快照）只 WARN，保留旧上下文，
	 * 工具轮照常返回 —— 刷新是"让下一步更准"的增强，不是本批的判定面。</li>
	 * </ol>
	 */
	private void refreshRunContext(String runId, PendingToolCall settled) {
		RunContextRefresher refresher = this.contextRefresher;
		if (settled == null || !RunContextRefresher.supports(settled.getName())) {
			return;
		}
		if (refresher == null) {
			// 可观测性：本批确有"能推进上下文"的结果，但装配里没有刷新器 —— 下一跳会沿用请求边界的
			// 旧上下文（步骤工具不披露）。这不是"已证根因"，但必须是可检索的一条痕。
			log.warn("工具批后无法刷新运行上下文：未注入 RunContextRefresher runId={} tool={}"
					+ "（下一跳沿用旧上下文，步骤工具可能不披露）", runId, settled.getName());
			return;
		}
		try {
			RunSnapshot fresh = this.store.get(runId);
			if (fresh == null) {
				return;
			}
			AiContext before = AiContext.fromMap(fresh.getContext());
			Optional<AiContext> next = refresher.next(before, settled);
			if (next == null || next.isEmpty()) {
				return;
			}
			AiContext after = next.get();
			if (after.equals(before)) {
				return;
			}
			fresh.setContext(after.toMap());
			this.store.save(fresh);
			log.info("工具批后已刷新运行上下文 runId={} tool={} page={} taskId={} taskType={} step={} status={}", runId,
					settled.getName(), after.getPage(), after.getTaskId(), after.getTaskType(), after.getStep(),
					after.getTaskStatus());
		}
		catch (RuntimeException ex) {
			log.warn("工具批后刷新运行上下文失败，保留旧上下文（不阻断工具轮）runId={} tool={}：{}", runId,
					settled.getName(), ex.getMessage());
		}
	}

	private String runIdOf(Prompt prompt) {
		if (prompt.getOptions() instanceof ToolCallingChatOptions options) {
			return runIdOf(options.getToolContext());
		}
		return null;
	}

	/** 从 {@code toolContext} 取本轮 runId（无则 null：披露重算/执行补全都按"不可判定"回落）。 */
	private static String runIdOf(Map<String, Object> toolContext) {
		if (toolContext == null) {
			return null;
		}
		Object runId = toolContext.get("runId");
		return runId == null ? null : String.valueOf(runId);
	}

	private Map<String, Object> toolContextOf(Prompt prompt) {
		if (prompt.getOptions() instanceof ToolCallingChatOptions options) {
			return options.getToolContext();
		}
		return null;
	}

	/**
	 * 凭本轮权威执行信封恢复轮次快照（快照缺失时的第一选择）。
	 *
	 * <p>三条硬口径（见 {@link ToolExecutionEnvelope}）：
	 * <ul>
	 * <li><b>只认本实例创建者</b>：{@code createdBy} 必须等于本实例 id，runId 也必须对得上 ——
	 * 别的实例的信封不构成"这一轮归我驱动"的证据；</li>
	 * <li><b>只恢复运行事实</b>：不写任何审批/认领/台账；恢复出的快照没有已批准条目；</li>
	 * <li><b>不重置序号/预算/步数/创建时刻</b>：步数、轮次、创建时刻原值入快照；armed/已跳过批次数
	 * 同时回填进程内信标；{@code ai:seq} 只抬到信封的 {@code seqFloor}（只升不降，绝不回拨并发新号）。
	 * 抬号是必须的：快照与序号键可能同时丢失，不抬号会让恢复后的 {@code suspended}/{@code confirm_request}
	 * 从 1 重发，前端带 {@code lastSeq} 重挂时把这些帧当孤儿丢掉（确认卡消失）。</li>
	 * </ul>
	 *
	 * <p>恢复即落库（挂起与续跑都读 {@code ai:run:<runId>}）；落库前再读一次，若并发中已出现快照则直接
	 * 用那一份，避免用较旧的信封覆盖更新的状态。落库复读成功后、任何后续帧之前抬号并复读校验
	 * （抬不上去 ⇒ {@code null}，不发出会重号的帧）。任一环节失败 ⇒ {@code null}（调用方失败关闭）。
	 *
	 * @return 可用于本轮执行的快照；{@code null} = 信封缺失/不可信/恢复失败
	 */
	private RunSnapshot recoverSnapshot(ToolExecutionEnvelope envelope, String runId) {
		if (envelope == null) {
			return null;
		}
		if (!envelope.restorableBy(runId, this.store.instanceId())) {
			log.warn("执行信封不可用于恢复（runId={} 信封 runId={} createdBy={} 本实例={}）：不据此恢复，按失败关闭",
					runId, envelope.runId(), envelope.createdBy(), this.store.instanceId());
			return null;
		}
		RunSnapshot restored = envelope.toSnapshot();
		try {
			RunSnapshot concurrent = this.store.get(restored.getRunId());
			if (concurrent != null) {
				return concurrent;
			}
			this.store.save(restored);
			// 写权不在本实例（续跑权已转移/丢失）时 save 只 WARN 不抛 —— 必须复读确认真的落库，
			// 否则后续 saveSuspended 会以"找不到快照"把整轮打穿；这里宁可判成不可恢复（失败关闭）。
			if (this.store.get(restored.getRunId()) == null) {
				log.warn("凭执行信封恢复未落库 runId={}（无写权限/丢租）：按失败关闭，危险/前端工具不得直执行",
						restored.getRunId());
				return null;
			}
		}
		catch (RuntimeException ex) {
			log.warn("凭执行信封恢复轮次快照失败 runId={}：{}（按失败关闭：危险/前端工具不得直执行）",
					restored.getRunId(), ex.getMessage());
			return null;
		}
		// 序号下界：ai:run 与 ai:seq/归档可能同时丢失；抬到信封的 seqFloor（只升不降）后，
		// 下一帧由 max(归档末帧, ai:seq)+1 / INCR ai:seq 得出，必然 > seqFloor，重挂不会丢卡。
		// 位置在落库复读之后、任何后续帧之前（本方法只在 emit 之前被调用）。
		this.store.ensureIssuedSeqAtLeast(restored.getRunId(), envelope.seqFloor());
		if (envelope.seqFloor() > 0L && this.store.lastIssuedSeq(restored.getRunId()) < envelope.seqFloor()) {
			log.warn("恢复后业务序号下界未生效 runId={} seqFloor={} current={}（按失败关闭：不发出会重号的帧）",
					restored.getRunId(), envelope.seqFloor(), this.store.lastIssuedSeq(restored.getRunId()));
			return null;
		}
		// 预算口径跨 park/恢复不重置：进程内信标按信封原值重新点亮（armed 与已跳过批次数）。
		this.beacon.restoreBudgetArmed(restored.getRunId(), envelope.budgetArmedAtMs());
		this.beacon.restoreBudgetSkippedBatches(restored.getRunId(), envelope.budgetSkippedBatches());
		log.warn("轮次快照缺失，已凭本实例权威执行信封恢复 runId={} createdAtMs={} round={} steps={} "
				+ "budgetArmedAtMs={} skipped={} seqFloor={}（seq 已抬到下界，未伪造审批/台账/认领）",
				restored.getRunId(), restored.getCreatedAtMs(), envelope.round(), envelope.toolSteps(),
				envelope.budgetArmedAtMs(), envelope.budgetSkippedBatches(), envelope.seqFloor());
		return restored;
	}

	/**
	 * 快照/runId 缺失且无可信执行信封时的执行路径：<b>READ + BACKEND 放行，危险/前端工具拒绝</b>。
	 *
	 * <p>放行部分仍走官方执行器（纯只读、无副作用）；被拒部分在模型侧留下结构化的
	 * {@code blocked}/{@code TOOL_KIND_MISMATCH} 结果，历史保持"完整 assistant(tool_calls) + 全量
	 * tool 响应"的形状（官方循环下一跳不会丢消息）。本路径<b>不</b>发帧：没有可外置的挂起态，
	 * 也不该让前端出现一张"看似可点"的确认卡。
	 */
	private ToolExecutionResult executeWithoutSnapshot(Prompt prompt, ChatResponse chatResponse, String runId,
			AssistantMessage assistant) {
		List<AssistantMessage.ToolCall> calls = assistant.getToolCalls();
		List<AssistantMessage.ToolCall> allowed = new ArrayList<>(calls.size());
		List<AssistantMessage.ToolCall> refused = new ArrayList<>(calls.size());
		for (AssistantMessage.ToolCall call : calls) {
			boolean readBackend = this.toolRegistry.channelOf(call.name()) == ToolMeta.Channel.BACKEND
					&& this.toolRegistry.riskOf(call.name()) == ToolMeta.RiskLevel.READ;
			(readBackend ? allowed : refused).add(call);
		}
		if (refused.isEmpty()) {
			log.warn("工具执行处拿不到本轮 runId/快照（runId={}），且无可用执行信封；本批均为 READ+BACKEND，"
					+ "回落官方默认执行：{}", runId, calls.stream().map(AssistantMessage.ToolCall::name).toList());
			return this.delegate.executeToolCalls(prompt, chatResponse);
		}
		log.warn("工具执行处拿不到本轮 runId/快照（runId={}），且无可用执行信封：拒绝危险/前端工具 {}，"
				+ "READ+BACKEND 放行 {}（不弹卡、不认领、不写台账）", runId,
				refused.stream().map(AssistantMessage.ToolCall::name).toList(),
				allowed.stream().map(AssistantMessage.ToolCall::name).toList());

		Map<String, String> allowedData = new LinkedHashMap<>();
		if (!allowed.isEmpty()) {
			AssistantMessage subset = AssistantMessage.builder()
				.content(assistant.getText())
				.properties(assistant.getMetadata() == null ? Map.of() : assistant.getMetadata())
				.toolCalls(allowed)
				.build();
			ToolExecutionResult partial = this.delegate.executeToolCalls(prompt,
					new ChatResponse(List.of(new Generation(subset))));
			for (Message message : partial.conversationHistory()) {
				if (message instanceof ToolResponseMessage toolMessage) {
					for (ToolResponseMessage.ToolResponse response : toolMessage.getResponses()) {
						allowedData.putIfAbsent(response.id(), response.responseData());
					}
				}
			}
		}

		List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>(calls.size());
		for (AssistantMessage.ToolCall call : calls) {
			String data = allowedData.get(call.id());
			if (data != null) {
				responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), data));
				continue;
			}
			responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(),
					ToolResultEnvelope
						.of(ToolOutcome.BLOCKED, ToolOutcome.CODE_TOOL_KIND_MISMATCH, UNRECOVERABLE_EXECUTION_TEXT,
								call.arguments())
						.toModelJson(this.limiter, call.name())));
		}
		List<Message> history = new ArrayList<>(prompt.getInstructions());
		history.add(assistant);
		history.add(ToolResponseMessage.builder().responses(responses).build());
		return ToolExecutionResult.builder().conversationHistory(history).build();
	}

	/**
	 * 确认门执行前的 {@code reportHash} 复核。
	 *
	 * <p>只对"确认门条目 + 预检支持的工具 + 卡上确实带过 reportHash"生效：
	 * 重跑一次只读预检，指纹一致才允许执行；不一致 ⇒ {@code blocked}/{@code PRECHECK_STALE}，
	 * 要求用户重新确认（旧报告不是执行授权）。重跑预检自身失败 ⇒ 同样不执行（{@code PRECHECK_UNAVAILABLE}）。
	 * 旧数据（没有预检报告）没有可比对的指纹，保持既有行为。
	 *
	 * @return 阻断文本；{@code null} = 可继续执行
	 */
	private String verifyApprovedReport(String runId, PendingToolCall pending, AiContext context) {
		if (this.preflight == null || !PendingToolCall.KIND_CONFIRM.equals(pending.getKind())
				|| !ToolPreflightService.supports(pending.getName())) {
			return null;
		}
		Map<String, Object> stored = pending.getPreflight();
		Object storedHash = stored == null ? null : stored.get("reportHash");
		if (storedHash == null || String.valueOf(storedHash).isBlank()) {
			return null;
		}
		ToolPreflightService.PreflightReport fresh;
		try {
			fresh = this.preflight.inspect(pending.getName(), pending.getArguments(), context);
		}
		catch (RuntimeException ex) {
			log.error("执行前复核预检报告失败 runId={} tool={} toolCallId={}：{}（不执行，要求重新确认）", runId,
					pending.getName(), pending.getToolCallId(), ex.getMessage());
			return settleBlocked(runId, pending, ToolOutcome.CODE_PRECHECK_UNAVAILABLE,
					PRECHECK_REVERIFY_UNAVAILABLE_TEXT);
		}
		if (fresh == null) {
			return settleBlocked(runId, pending, ToolOutcome.CODE_PRECHECK_UNAVAILABLE,
					PRECHECK_REVERIFY_UNAVAILABLE_TEXT);
		}
		if (String.valueOf(storedHash).equals(fresh.reportHash())) {
			return null;
		}
		log.warn("确认卡预检报告在执行时已变化 runId={} tool={} toolCallId={} 卡上={} 当前={}（不执行，要求重新确认）",
				runId, pending.getName(), pending.getToolCallId(), storedHash, fresh.reportHash());
		return settleBlocked(runId, pending, ErrorCodes.PRECHECK_STALE, PRECHECK_STALE_TEXT);
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
