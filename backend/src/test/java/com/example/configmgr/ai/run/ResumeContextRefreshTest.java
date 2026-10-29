package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.gate.RunContextRefresher;
import com.example.configmgr.ai.gate.SpToolCallingManager;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolCategory;
import com.example.configmgr.ai.tool.ToolChannel;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.example.configmgr.ai.tool.ToolRisk;
import com.example.configmgr.ai.tool.ToolScope;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.service.TaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>park=true 的续跑批必须刷新运行上下文，再按新上下文构造下一跳</b>（Task 1 的续跑侧）。
 *
 * <h2>被钉住的缺陷</h2>
 * 生产 {@code app.ai.park.enabled=true} 时 {@code create_task}/{@code navigate_to}/{@code confirm_step}
 * 都在<b>唯一上下文刷新点</b>（{@code SpToolCallingManager} 执行循环尾部的
 * {@code refreshRunContext}）之前就挂起：执行循环被 {@code RunParkedException} 打断，刷新一次都
 * 跑不到。于是续跑一直用<b>请求边界</b>的旧 {@code context} 构造下一跳 —— 模型永远看不到
 * {@code select_definitions} / {@code set_condition} / {@code start_export} 这些步骤工具。
 *
 * <h2>本用例钉住的三条口径</h2>
 * <ol>
 * <li><b>整批结算后统一刷新</b>：一批里的成功结果按顺序折算成新 {@code AiContext} 并写回快照，
 * 下一跳读到的是<b>刷新后</b>的那一份（页面/任务/步骤都来自真实结果与任务权威值）；</li>
 * <li><b>同一批不扩权</b>：结算用的 {@code Prompt} 与 scope 判据仍是批首那一份 —— 刷新之后
 * 本批<b>不会</b>多出任何工具（判据 = 结算入参 {@code Prompt} 的回调集里没有新披露的工具）；</li>
 * <li><b>刷新后给模型权威工作区状态</b>：交棒历史里那条 SYSTEM 的 {@code <workspace_state>}
 * 段按刷新后的上下文重写（人设/语言等历史内容一字不动，tool_use/tool_result 顺序不变）。</li>
 * </ol>
 *
 * <p>披露面用<b>真实 {@link ToolRegistry}</b>（注解 scope 参与判定）；领域侧（{@code TaskService}）
 * 与驱动侧（{@code ResilientChatService}）是替身 —— 本用例断言的是"续跑给了什么"，不是模型怎么答。
 */
class ResumeContextRefreshTest {

	private static final String RUN_ID = "run-resume-ctx";

	private static final String SESSION_ID = "s-resume-ctx";

	private static final String CALL_CREATE = "call-create";

	private static final String CALL_NAVIGATE = "call-navigate";

	private static final String CALL_STEP = "call-step";

	private InMemoryRunStore store = new InMemoryRunStore();

	private final ObjectMapper mapper = new ObjectMapper();

	private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

	private final RunRegistry registry = mock(RunRegistry.class);

	private final SseChatEmitter emitter = mock(SseChatEmitter.class);

	private final ResilientChatService chatService = mock(ResilientChatService.class);

	private final SpToolCallingManager toolCallingManager = mock(SpToolCallingManager.class);

	private final ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	private final AiProperties properties = new AiProperties();

	private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

	private final ToolResultLimiter limiter = new ToolResultLimiter(this.properties);

	private final TaskService taskService = mock(TaskService.class);

	private final AtomicReference<Task> taskState = new AtomicReference<>();

	private AnnotationConfigApplicationContext registryContext;

	private ToolRegistry toolRegistry;

	private ResumeService service;

	@BeforeEach
	void setUp() {
		// 本用例精确模拟 park 开启后的**续跑**（首轮已在挂起处结束，刷新点从未走到）：
		// 因此不需要 park 开关本身（ResumeService 不读它）；开关行为由 FormSlotSilenceTest 覆盖。
		this.registryContext = new AnnotationConfigApplicationContext();
		this.registryContext.registerBean(WizardTools.class);
		this.registryContext.registerBean(ToolRegistry.class);
		this.registryContext.refresh();
		this.toolRegistry = this.registryContext.getBean(ToolRegistry.class);

		when(this.taskService.findByIdRaw(any())).thenAnswer(invocation -> Optional.ofNullable(this.taskState.get())
			.filter(task -> task.getId() != null && task.getId().equals(invocation.getArgument(0))));
		when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
		when(this.cancellations.isCancelled(anyString())).thenReturn(false);
		when(this.cancellations.onCancel(anyString(), any())).thenAnswer(invocation -> (AutoCloseable) () -> {
		});
		when(this.chatService.systemPromptText(any())).thenReturn(SYSTEM_TEXT);
		// 刷新后的工作区段（真实实现在 ContextBuilder；本例只验"哪一段被换成刷新后的那一份"）
		when(this.chatService.workspaceStateText(any())).thenAnswer(invocation -> {
			AiContext refreshed = invocation.getArgument(0);
			return "## 当前工作区状态\n<workspace_state>\n页面：导出向导\n任务: #" + refreshed.getTaskId()
					+ "\n当前步骤: " + refreshed.getStep() + "\n</workspace_state>\n";
		});
		// 续跑重建的 Prompt 按<b>入参 context</b> 披露（= 批首冻结的那一份）：下面据此断言"同批不扩权"
		when(this.chatService.buildResumedPrompt(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), any()))
			.thenAnswer(invocation -> new Prompt(invocation.<List<Message>>getArgument(3),
					ToolCallingChatOptions.builder()
						.toolCallbacks(this.toolRegistry.forContext(invocation.getArgument(2)))
						.build()));
		doAnswer(invocation -> {
			invocation.getArgument(0, Runnable.class).run();
			return null;
		}).when(this.executor).execute(any(Runnable.class));

		this.service = new ResumeService(this.store, this.registry, this.codec, this.chatService,
				this.toolCallingManager, this.executor, this.redis, this.properties, this.cancellations, this.limiter);
		// 可选注入：与生产装配同一个刷新器（轻装配面不注入 = 不刷新，既有用例语义不变）
		this.service.setContextRefresher(new RunContextRefresher(this.taskService));
	}

	/** 换一份存储替身（故障注入用例用同一套装配重建 ResumeService）。 */
	private void useStore(InMemoryRunStore replacement) {
		this.store = replacement;
		this.service = new ResumeService(this.store, this.registry, this.codec, this.chatService,
				this.toolCallingManager, this.executor, this.redis, this.properties, this.cancellations, this.limiter);
		this.service.setContextRefresher(new RunContextRefresher(this.taskService));
	}

	@AfterEach
	void tearDown() {
		if (this.registryContext != null) {
			this.registryContext.close();
		}
	}

	// ── A：整批结算 → 刷新 → 下一跳披露（且本批不扩权）────────────────────────

	@Test
	void createAndNavigateBatchRefreshesContextForTheNextHopWithoutWideningTheSameBatch() {
		this.taskState.set(task(42L, Task.TaskType.EXPORT, "SELECT_DEFS", Task.TaskStatus.ACTIVE));
		seedSuspendedRun(List.of(
				entry(CALL_CREATE, "create_task", "{\"type\":\"EXPORT\",\"title\":\"月度导出\"}",
						PendingToolCall.KIND_CONFIRM, PendingToolCall.APPROVED, null,
						"已创建导出任务 #42（当前步骤：SELECT_DEFS）"),
				entry(CALL_NAVIGATE, "navigate_to", "{\"page\":\"export\",\"taskId\":42}",
						PendingToolCall.KIND_FRONTEND, PendingToolCall.FRONTEND_RESULT, ToolOutcome.OK.wire(),
						"前端已执行：navigate_to")));
		settleApprovedEntries();

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);

		// ① 下一跳拿到的是刷新后的上下文：page/taskId 来自真实结果，type/step/status 来自任务权威值
		ArgumentCaptor<AiContext> nextHop = ArgumentCaptor.forClass(AiContext.class);
		verify(this.chatService).driveResumed(eq(RUN_ID), eq(SESSION_ID), nextHop.capture(), any(), eq(this.emitter));
		AiContext driveContext = nextHop.getValue();
		assertThat(driveContext.getPage()).isEqualTo("export");
		assertThat(driveContext.getTaskId()).isEqualTo(42L);
		assertThat(driveContext.getTaskType()).isEqualTo("EXPORT");
		assertThat(driveContext.getStep()).isEqualTo("SELECT_DEFS");
		assertThat(driveContext.getTaskStatus()).isEqualTo("ACTIVE");
		// 刷新结果同时写回快照：下一次续跑/下一读都按同一份事实走
		assertThat(AiContext.fromMap(this.store.get(RUN_ID).getContext()).getStep()).isEqualTo("SELECT_DEFS");

		// ② 披露面：下一跳的工具集里出现步骤工具，且终止步骤的工具仍不出现
		List<String> nextHopTools = toolNames(driveContext);
		assertThat(nextHopTools).contains("select_definitions", "confirm_step");
		assertThat(nextHopTools).doesNotContain("set_condition", "start_export");

		// ③ 同一批不扩权：结算用的 Prompt 仍是按<b>批首</b>旧上下文披露的那一份
		ArgumentCaptor<Prompt> settlementPrompt = ArgumentCaptor.forClass(Prompt.class);
		verify(this.toolCallingManager, org.mockito.Mockito.times(2))
			.resolvePending(eq(RUN_ID), settlementPrompt.capture(), any(PendingToolCall.class));
		List<String> boundaryTools = toolNames(AiContext.of("tasks", null, null, null, null));
		for (Prompt prompt : settlementPrompt.getAllValues()) {
			assertThat(callbacksOf(prompt))
				.as("刷新只对下一跳生效：本批结算的披露面逐字等于批首（任务中心）那一份")
				.containsExactlyInAnyOrderElementsOf(boundaryTools);
			assertThat(callbacksOf(prompt))
				.doesNotContain("select_definitions", "confirm_step", "set_condition");
		}
	}

	// ── B：连续推进（QUERY_COND → EXPORT）分别披露条件工具与启动工具 ────────────

	@Test
	void consecutiveStepAdvancesDiscloseConditionThenStartExportAfterEachRefresh() {
		AiContext wizard = AiContext.of("export", "EXPORT", "SELECT_DEFS", 42L, "ACTIVE", null);
		seedSuspendedRun(List.of(entry(CALL_STEP, "confirm_step", "{}", PendingToolCall.KIND_FRONTEND,
				PendingToolCall.FRONTEND_RESULT, ToolOutcome.OK.wire(), "前端已执行：confirm_step")));
		this.store.get(RUN_ID).setContext(wizard.toMap());
		this.store.save(this.store.get(RUN_ID));

		// 第一次推进：前端已把任务推进到 QUERY_COND（权威值），刷新后条件工具披露
		this.taskState.set(task(42L, Task.TaskType.EXPORT, "QUERY_COND", Task.TaskStatus.ACTIVE));
		when(this.toolCallingManager.resolvePending(eq(RUN_ID), any(Prompt.class), any(PendingToolCall.class)))
			.thenReturn("已执行");

		assertThat(this.service.resume(RUN_ID).outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
		AiContext afterFirst = contextOfDriveResumed();
		assertThat(afterFirst.getStep()).isEqualTo("QUERY_COND");
		List<String> atQueryCond = toolNames(afterFirst);
		assertThat(atQueryCond).contains("set_condition");
		assertThat(atQueryCond).doesNotContain("start_export");

		// 第二次 park → 续跑：步骤再推到 EXPORT，下一跳才披露 start_export
		repark(wizard.toMap());
		this.taskState.set(task(42L, Task.TaskType.EXPORT, "EXPORT", Task.TaskStatus.ACTIVE));
		assertThat(this.service.resume(RUN_ID).outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
		AiContext afterSecond = contextOfDriveResumed();
		assertThat(afterSecond.getStep()).isEqualTo("EXPORT");
		assertThat(toolNames(afterSecond)).contains("start_export");
	}

	// ── C：刷新后给模型权威工作区状态（SYSTEM 的工作区段重写，其余历史不动）────

	@Test
	void refreshedWorkspaceStateReplacesTheSystemSnapshotInPlace() {
		this.taskState.set(task(42L, Task.TaskType.EXPORT, "SELECT_DEFS", Task.TaskStatus.ACTIVE));
		seedSuspendedRun(List.of(entry(CALL_NAVIGATE, "navigate_to",
				"{\"page\":\"export\",\"taskId\":42}", PendingToolCall.KIND_FRONTEND,
				PendingToolCall.FRONTEND_RESULT, ToolOutcome.OK.wire(), "前端已执行：navigate_to")));
		settleApprovedEntries();

		this.service.resume(RUN_ID);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Message>> history = ArgumentCaptor.forClass(List.class);
		verify(this.chatService).driveResumed(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), history.capture(),
				eq(this.emitter));
		List<Message> handed = history.getValue();
		// 系统段仍在最前、仍只有一条：重写的是段内文本，不是消息序列（tool_use/tool_result 配对与顺序不变）
		assertThat(handed.get(0)).isInstanceOf(SystemMessage.class);
		assertThat(handed.get(1)).isInstanceOf(UserMessage.class);
		assertThat(handed.get(2)).isInstanceOf(AssistantMessage.class);
		assertThat(handed.get(3).getMessageType().name()).isEqualTo("TOOL");
		String systemText = handed.get(0).getText();
		assertThat(systemText).contains("PERSONA 行");
		assertThat(systemText).as("工作区段换成刷新后的权威状态").contains("当前步骤: SELECT_DEFS");
		assertThat(systemText).as("旧页面的工作区段不得残留").doesNotContain("页面：任务中心");
	}

	// ── D：刷新写回/重读故障只 WARN，不打断续跑（fail-safe 保持批首上下文）────

	/**
	 * 刷新把新上下文写回快照（`setContext → save → get`）时，Redis 抛错<b>不得</b>穿出续跑：
	 * 刷新是"让下一跳更准"的增强，不是这一轮的判定面。两条口径同时钉住：
	 * <ol>
	 * <li><b>不打断</b>：`resume()` 仍返回 ACCEPTED，后续交棒照常发生（工具轮不因观测面失败而丢）；</li>
	 * <li><b>不扩披露面、不分叉</b>：未经核实的写一律按<b>批首</b>上下文交棒，且内存快照被显式恢复 ——
	 * 否则 `setContext` 已经把内存改了、写库又失败，落库真值与内存/下一跳会各说一套。</li>
	 * </ol>
	 * 两段分别覆盖"写抛错"与"写成功但重读抛错"（后者同样未获核实）。
	 */
	@Test
	void refreshWriteOrRereadFailureOnlyWarnsAndKeepsTheBatchBoundaryContext() {
		FlakyRunStore flaky = new FlakyRunStore();
		useStore(flaky);
		this.taskState.set(task(42L, Task.TaskType.EXPORT, "SELECT_DEFS", Task.TaskStatus.ACTIVE));
		seedSuspendedRun(List.of(navigateEntry()));
		settleApprovedEntries();

		// ① 写回抛错：续跑必须照常收口，交棒与内存快照都停在批首上下文
		flaky.failSaveOnRefreshedContext = true;
		assertThat(this.service.resume(RUN_ID).outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
		assertBatchBoundaryContext(contextOfDriveResumed());
		assertBatchBoundaryContext(AiContext.fromMap(this.store.get(RUN_ID).getContext()));

		// ② 写回成功但重读抛错：同样只 WARN（写结果未核实 ⇒ 不据此扩披露面）
		repark(AiContext.of("tasks", null, null, null, null).toMap(), navigateEntry());
		flaky.failSaveOnRefreshedContext = false;
		flaky.failRereadAfterRefresh = true;
		assertThat(this.service.resume(RUN_ID).outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
		assertBatchBoundaryContext(contextOfDriveResumed());
		assertBatchBoundaryContext(AiContext.fromMap(this.store.get(RUN_ID).getContext()));
	}

	// ── 桩与辅助 ────────────────────────────────────────────────────────────

	private static final String SYSTEM_TEXT = "PERSONA 行\n\n## 当前工作区状态\n<workspace_state>\n页面：任务中心\n"
			+ "</workspace_state>\n";

	/** 已批准/已回执的条目由 manager 结算（替身模拟：写条目真值并回了结果文本）。 */
	private void settleApprovedEntries() {
		when(this.toolCallingManager.resolvePending(eq(RUN_ID), any(Prompt.class), any(PendingToolCall.class)))
			.thenAnswer(invocation -> {
				PendingToolCall item = invocation.getArgument(2);
				PendingToolCall settled = this.store.pending(RUN_ID, item.getToolCallId());
				if (settled.getOutcome() == null) {
					settled.setStatus(PendingToolCall.EXECUTED);
					settled.setOutcome(ToolOutcome.OK.wire());
					settled.setExecuted(true);
					this.store.putPending(RUN_ID, settled);
				}
				return settled.getResultText();
			});
	}

	private AiContext contextOfDriveResumed() {
		ArgumentCaptor<AiContext> nextHop = ArgumentCaptor.forClass(AiContext.class);
		verify(this.chatService, org.mockito.Mockito.atLeastOnce())
			.driveResumed(eq(RUN_ID), eq(SESSION_ID), nextHop.capture(), any(), eq(this.emitter));
		return nextHop.getValue();
	}

	/** 把快照重新置成"又 park 了一次"（快照历史已由上一轮续跑落定）。 */
	private void repark(Map<String, Object> context) {
		repark(context, entry(CALL_STEP, "confirm_step", "{}", PendingToolCall.KIND_FRONTEND,
				PendingToolCall.FRONTEND_RESULT, ToolOutcome.OK.wire(), "前端已执行：confirm_step"));
	}

	/** 同上，但由调用方指定"这一次挂起的是哪张卡"（故障注入用例要一张真能推进上下文的卡）。 */
	private void repark(Map<String, Object> context, PendingToolCall pending) {
		RunSnapshot snapshot = this.store.get(RUN_ID);
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		snapshot.setContext(context);
		List<PendingToolCall> inFlight = new ArrayList<>();
		this.store.putPending(RUN_ID, pending);
		inFlight.add(pending);
		snapshot.setInFlightToolCalls(inFlight);
		this.store.save(snapshot);
	}

	private void seedSuspendedRun(List<PendingToolCall> inFlight) {
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		snapshot.setContext(AiContext.of("tasks", null, null, null, null).toMap());
		snapshot.setMessageJson(this.codec.serializeAll(List.of(new UserMessage("做一个导出"))));
		snapshot.setInFlightToolCalls(new ArrayList<>(inFlight));
		this.store.save(snapshot);
		for (PendingToolCall pending : inFlight) {
			this.store.putPending(RUN_ID, pending);
		}
	}

	private static PendingToolCall navigateEntry() {
		return entry(CALL_NAVIGATE, "navigate_to", "{\"page\":\"export\",\"taskId\":42}",
				PendingToolCall.KIND_FRONTEND, PendingToolCall.FRONTEND_RESULT, ToolOutcome.OK.wire(),
				"前端已执行：navigate_to");
	}

	/** 批首（任务中心、无任务）上下文的断言：刷新未经核实 ⇒ 任务/步骤一律不得"提前生效"。 */
	private static void assertBatchBoundaryContext(AiContext context) {
		assertThat(context.getPage()).isEqualTo("tasks");
		assertThat(context.getTaskId()).as("未核实的刷新不得扩披露面（taskId 保持批首值）").isNull();
		assertThat(context.getStep()).isNull();
	}

	/**
	 * 故障注入存储：<b>只</b>让"刷新后的那次写/读"抛错 —— 其它读写（状态机、挂起、认领）照常，
	 * 否则用例会把"整条续跑崩了"误当成"刷新失败"，断言就失去意义。
	 */
	static class FlakyRunStore extends InMemoryRunStore {

		/** 当待写快照的上下文已经带 taskId（= 刷新结果）时抛错。 */
		volatile boolean failSaveOnRefreshedContext;

		/** 刷新写回成功之后的<b>第一笔</b> get（= 刷新后的重读）抛错。 */
		volatile boolean failRereadAfterRefresh;

		private volatile boolean refreshedWritten;

		@Override
		public void save(RunSnapshot snapshot) {
			boolean refreshed = snapshot.getContext() != null && snapshot.getContext().get("taskId") != null;
			if (this.failSaveOnRefreshedContext && refreshed) {
				throw new IllegalStateException("redis down (snapshot write)");
			}
			super.save(snapshot);
			if (refreshed) {
				this.refreshedWritten = true;
			}
		}

		@Override
		public RunSnapshot get(String runId) {
			if (this.failRereadAfterRefresh && this.refreshedWritten) {
				this.refreshedWritten = false;
				throw new IllegalStateException("redis down (snapshot read)");
			}
			return super.get(runId);
		}
	}

	private static PendingToolCall entry(String callId, String name, String arguments, String kind, String status,
			String outcome, String resultText) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(callId);
		pending.setName(name);
		pending.setType("function");
		pending.setArguments(arguments);
		pending.setKind(kind);
		pending.setStatus(status);
		pending.setOutcome(outcome);
		pending.setResultText(resultText);
		pending.setReq(Boolean.TRUE);
		return pending;
	}

	private static List<String> callbacksOf(Prompt prompt) {
		if (!(prompt.getOptions() instanceof ToolCallingChatOptions options) || options.getToolCallbacks() == null) {
			return List.of();
		}
		return options.getToolCallbacks().stream()
			.map(callback -> callback.getToolDefinition().name())
			.toList();
	}

	/** 该上下文下真实披露给模型的工具名集合（与请求侧同一个 {@code ToolRegistry#forContext}）。 */
	private List<String> toolNames(AiContext context) {
		return this.toolRegistry.forContext(context).stream()
			.map(callback -> callback.getToolDefinition().name())
			.toList();
	}

	private static Task task(Long id, Task.TaskType type, String step, Task.TaskStatus status) {
		Task task = new Task();
		task.setId(id);
		task.setType(type);
		task.setCurrentStep(step);
		task.setStatus(status);
		task.setTitle("任务 " + id);
		return task;
	}

	/**
	 * 只声明本用例关心的披露面（scope/风险/通道与 {@code AiTools} 的真实注解一致）：
	 * 工具体不触达任何服务，只回显固定文本。
	 */
	static class WizardTools {

		@Tool(name = "navigate_to", description = "驱动前端页面导航")
		@ToolScope("*")
		@ToolRisk(ToolMeta.RiskLevel.READ)
		@ToolCategory(ToolMeta.Category.U)
		@ToolChannel(ToolMeta.Channel.FRONTEND)
		public String navigateTo(@ToolParam(description = "目标页面") String page,
				@ToolParam(description = "任务ID", required = false) Long taskId) {
			return "前端哨兵桩（永不执行）";
		}

		@Tool(name = "create_task", description = "创建任务（确认门）")
		@ToolScope({ "page:tasks", "task:EXPORT", "task:IMPORT" })
		@ToolRisk(ToolMeta.RiskLevel.DANGER)
		@ToolCategory(ToolMeta.Category.W)
		public String createTask(@ToolParam(description = "任务类型") String type,
				@ToolParam(description = "标题", required = false) String title) {
			return "不应被执行";
		}

		@Tool(name = "select_definitions", description = "勾选配置项")
		@ToolScope({ "task:EXPORT/SELECT_DEFS", "task:IMPORT/UPLOAD" })
		@ToolRisk(ToolMeta.RiskLevel.WRITE)
		@ToolCategory(ToolMeta.Category.U)
		@ToolChannel(ToolMeta.Channel.FRONTEND)
		public String selectDefinitions(@ToolParam(description = "配置定义编码列表") String[] codes,
				@ToolParam(description = "选择模式", required = false) String mode) {
			return "前端哨兵桩（永不执行）";
		}

		@Tool(name = "set_condition", description = "设置查询条件")
		@ToolScope({ "task:EXPORT/QUERY_COND" })
		@ToolRisk(ToolMeta.RiskLevel.WRITE)
		@ToolCategory(ToolMeta.Category.U)
		@ToolChannel(ToolMeta.Channel.FRONTEND)
		public String setCondition(@ToolParam(description = "配置定义编码") String defCode) {
			return "前端哨兵桩（永不执行）";
		}

		@Tool(name = "confirm_step", description = "确认并推进向导步骤")
		@ToolScope({ "task:EXPORT", "task:IMPORT" })
		@ToolRisk(ToolMeta.RiskLevel.WRITE)
		@ToolCategory(ToolMeta.Category.U)
		@ToolChannel(ToolMeta.Channel.FRONTEND)
		public String confirmStep(@ToolParam(description = "目标步骤", required = false) String step) {
			return "前端哨兵桩（永不执行）";
		}

		@Tool(name = "start_export", description = "启动导出作业（确认门）")
		@ToolScope({ "page:tasks", "task:EXPORT/EXPORT" })
		@ToolRisk(ToolMeta.RiskLevel.DANGER)
		@ToolCategory(ToolMeta.Category.J)
		public String startExport(@ToolParam(description = "任务ID") Long taskId) {
			return "不应被执行";
		}
	}

}
