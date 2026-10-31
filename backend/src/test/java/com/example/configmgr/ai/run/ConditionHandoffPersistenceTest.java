package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.context.ContextCompactor;
import com.example.configmgr.ai.context.ContextState;
import com.example.configmgr.ai.context.ContextTrigger;
import com.example.configmgr.ai.form.ConditionsForm;
import com.example.configmgr.ai.gate.RunContextRefresher;
import com.example.configmgr.ai.gate.SpToolCallingManager;
import com.example.configmgr.ai.history.SyntheticMessages;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.slot.DefinitionCatalog;
import com.example.configmgr.ai.slot.SlotDefinition;
import com.example.configmgr.ai.slot.TaskSlotResolver;
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
import com.example.configmgr.task.entity.TaskItem;
import com.example.configmgr.task.service.TaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>条件结构化交棒的标记必须真的落进快照</b>：披露面（{@code forContext}）用的是交棒那份上下文，
 * 而执行面的第三道防线（{@code ToolRegistry#matchesContext}）读的是<b>快照</b>上下文
 * （{@code SpToolCallingManager} 里 {@code AiContext.fromMap(snapshot.getContext())}）。
 * 标记只改内存对象 ⇒ guard 永远看不到，离开 QUERY_COND 的清理也只是改了内存。
 *
 * <p>本类用<b>模拟 Redis 读写边界</b>的存储替身（每次 {@code get} 返回反序列化副本）：
 * 默认的 {@link InMemoryRunStore} 会返回同一个对象，于是"没 save 的改动"看起来也像已落库 ——
 * 正是这个假象让旧用例在缺陷上保持绿色。
 *
 * <p>同时锁住压缩面：条件交棒的合成内部指令<b>不得</b>进压缩输入、也不得随压缩写回快照或记忆，
 * 但本跳交棒时模型仍必须看到它（且真实用户历史一字不动）。
 */
class ConditionHandoffPersistenceTest {

	private static final String RUN_ID = "run-cond-handoff-persist";

	private static final String SESSION_ID = "s-cond-handoff-persist";

	private static final long TASK_ID = 42L;

	private static final String CALL_CONFIRM = "call-confirm";

	private static final String REAL_USER = "帮我按币种筛选后导出";

	private static final String SYSTEM_TEXT = "PERSONA 行\n\n## 当前工作区状态\n<workspace_state>\n页面：任务中心\n"
			+ "</workspace_state>\n";

	/** 每次 get 返回反序列化副本 —— 与 Redis 同边界：不 save 的改动读不回来。 */
	private final InMemoryRunStore store = spy(new RedisLikeStore());

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

	private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

	private final ConditionsForm conditionsForm = mock(ConditionsForm.class);

	private final AtomicReference<Task> taskState = new AtomicReference<>();

	private final DefinitionCatalog catalog = () -> new ArrayList<>(
			List.of(new SlotDefinition("CURRENCY", "币种", List.of())));

	private AnnotationConfigApplicationContext registryContext;

	private ToolRegistry toolRegistry;

	private ResumeService service;

	@BeforeEach
	void setUp() {
		this.properties.getPark().setEnabled(true);
		this.registryContext = new AnnotationConfigApplicationContext();
		this.registryContext.registerBean(WizardTools.class);
		this.registryContext.registerBean(ToolRegistry.class);
		this.registryContext.refresh();
		this.toolRegistry = this.registryContext.getBean(ToolRegistry.class);

		when(this.taskService.findByIdRaw(any())).thenAnswer(invocation -> Optional.ofNullable(this.taskState.get())
			.filter(task -> task.getId() != null && task.getId().equals(invocation.getArgument(0))));
		when(this.taskService.findById(any())).thenAnswer(invocation -> this.taskState.get() != null
				&& this.taskState.get().getId() != null
				&& this.taskState.get().getId().equals(invocation.getArgument(0)) ? this.taskState.get() : null);
		when(this.toolCallingManager.resolvePending(eq(RUN_ID), any(Prompt.class), any(PendingToolCall.class)))
			.thenAnswer(invocation -> invocation.<PendingToolCall>getArgument(2).getResultText());
		when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
		when(this.cancellations.isCancelled(anyString())).thenReturn(false);
		when(this.cancellations.onCancel(anyString(), any())).thenAnswer(invocation -> (AutoCloseable) () -> {
		});
		when(this.chatService.systemPromptText(any())).thenReturn(SYSTEM_TEXT);
		when(this.chatService.workspaceStateText(any())).thenAnswer(invocation -> {
			AiContext context = invocation.getArgument(0);
			return "## 当前工作区状态\n<workspace_state>\n任务: #" + context.getTaskId()
					+ "\n当前步骤: " + context.getStep() + "\n</workspace_state>\n";
		});
		when(this.chatService.buildResumedPrompt(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), any()))
			.thenAnswer(invocation -> new Prompt(invocation.<List<Message>>getArgument(3),
					ToolCallingChatOptions.builder().build()));
		when(this.chatService.replaceMemoryWindow(any(), any(), any(), any())).thenReturn(true);
		doAnswer(invocation -> {
			invocation.getArgument(0, Runnable.class).run();
			return null;
		}).when(this.executor).execute(any(Runnable.class));

		this.service = new ResumeService(this.store, this.registry, this.codec, this.chatService,
				this.toolCallingManager, this.executor, this.redis, this.properties, this.cancellations, this.limiter);
		this.service.setContextRefresher(new RunContextRefresher(this.taskService));
		this.service.setTaskSlotResolver(new TaskSlotResolver(this.catalog, this.taskService, this.mapper));
		// 条件表在真实链路上是同一次交棒前的最后一个宿主动作：这里只复刻"判据成立时写入标记并返回 false"。
		ReflectionTestUtils.setField(this.service, "conditionsForm", this.conditionsForm);
		markHandoffWhenUnknown();
	}

	@AfterEach
	void tearDown() {
		if (this.registryContext != null) {
			this.registryContext.close();
		}
	}

	// ── ① 标记 → 快照 → 执行 guard ────────────────────────────────────────────

	@Test
	void handoffMarkerIsPersistedAndVisibleToTheExecutionGuard() {
		this.taskState.set(taskWithDefs(TASK_ID, "QUERY_COND"));
		seedSuspendedConfirmStep();

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
		// 交棒仍然只发生一次（写标记不引入第二拍驱动）
		verify(this.chatService, times(1)).driveResumed(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), any(),
				eq(this.emitter));

		AiContext persisted = AiContext.fromMap(this.store.get(RUN_ID).getContext());
		assertThat(persisted.getStep()).isEqualTo("QUERY_COND");
		assertThat(persisted.getExtra())
			.as("标记必须随快照持久化（执行 guard 读的是快照上下文）")
			.containsEntry(AiContext.EXTRA_CONDITION_STRUCTURE_HANDOFF, Boolean.TRUE);

		assertThat(this.toolRegistry.matchesContext("confirm_step", persisted))
			.as("条件结构化的四个工具仍可执行").isTrue();
		assertThat(this.toolRegistry.matchesContext("create_task", persisted))
			.as("执行面按快照上下文收窄：create_task 不在交棒面内").isFalse();
		assertThat(this.toolRegistry.forContext(persisted))
			.as("披露面与执行面同判据").noneMatch(callback -> "create_task"
					.equals(callback.getToolDefinition().name()));

		// 合成指令仍只在本跳的交棒视图里，且模型能看到（这是引导模型做结构化的唯一凭据）
		assertThat(historyOfDriveResumed()).filteredOn(ConditionHandoffPersistenceTest::isSyntheticInstruction)
			.hasSize(1);
	}

	// ── ② 离开 QUERY_COND：清理同样要落库，工具面恢复 ────────────────────────

	@Test
	void leavingQueryCondPersistsTheCleanupAndRestoresTheToolFace() {
		// 挂起期用户在向导里推进了步骤：快照上下文还停在 QUERY_COND 且带着已持久化的标记
		this.taskState.set(taskWithDefs(TASK_ID, "EXPORT"));
		Map<String, Object> extra = new LinkedHashMap<>();
		extra.put(AiContext.EXTRA_CONDITION_STRUCTURE_HANDOFF, Boolean.TRUE);
		seedSuspended(List.of(), AiContext.of("export", "EXPORT", "QUERY_COND", TASK_ID, "ACTIVE", extra));

		this.service.resume(RUN_ID);

		AiContext handoff = contextOfDriveResumed();
		assertThat(handoff.getStep()).isEqualTo("EXPORT");
		assertThat(handoff.getExtra()).doesNotContainKey(AiContext.EXTRA_CONDITION_STRUCTURE_HANDOFF);

		AiContext persisted = AiContext.fromMap(this.store.get(RUN_ID).getContext());
		assertThat(persisted.getExtra())
			.as("清理也要落库：否则下一次续跑仍拿旧标记收窄工具面")
			.doesNotContainKey(AiContext.EXTRA_CONDITION_STRUCTURE_HANDOFF);
		assertThat(this.toolRegistry.matchesContext("create_task", persisted))
			.as("离开 QUERY_COND ⇒ 完整工具面恢复").isTrue();
		verify(this.chatService, times(1)).driveResumed(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), any(),
				eq(this.emitter));
	}

	// ── ③ 写回被写门拒绝（取消已先到/租约只读）：不得驱动模型 ─────────────────

	@Test
	void handoffPersistenceRefusalNeverDrivesTheModel() {
		this.taskState.set(taskWithDefs(TASK_ID, "QUERY_COND"));
		seedSuspendedConfirmStep();
		// 第一次（重建后的 RUNNING 落库）放行；第二次（标记写回）被写门拒绝
		doReturn(true).doReturn(false).when(this.store).saveStatusUnlessCancelled(any(RunSnapshot.class));

		ResumeService.ResumeResult result = this.service.resume(RUN_ID);

		assertThat(result.outcome()).as("被写门拒绝 ⇒ typed 结局，不冒充成功")
			.isEqualTo(ResumeService.Outcome.STATE_UNKNOWN);
		verify(this.chatService, never()).driveResumed(anyString(), anyString(), any(AiContext.class), any(),
				any());
	}

	// ── ④ 压缩：合成指令既不可进压缩输入，也不可随写回落到快照/记忆 ──────────

	@Test
	void compactionNeverPersistsTheSyntheticInstructionNorTouchesTheRealUserHistory() {
		this.taskState.set(taskWithDefs(TASK_ID, "QUERY_COND"));
		seedSuspendedConfirmStep();
		ContextCompactor compactor = mock(ContextCompactor.class);
		when(compactor.readState(SESSION_ID)).thenReturn(ContextState.EMPTY);
		when(compactor.compact(any(ContextCompactor.Request.class), any(), any())).thenAnswer(invocation -> {
			ContextCompactor.Request request = invocation.getArgument(0);
			return new ContextCompactor.Outcome(ContextTrigger.Level.L1, new ArrayList<>(request.messages()),
					100, 40, 60, 0, List.of(), "l1_cleared", 0, 40, true, "closed", 0.9, 0, 0, 0, 0);
		});
		ReflectionTestUtils.setField(this.service, "contextCompactor", compactor);

		this.service.resume(RUN_ID);

		ArgumentCaptor<ContextCompactor.Request> request = ArgumentCaptor.forClass(ContextCompactor.Request.class);
		verify(compactor).compact(request.capture(), any(), any());
		assertThat(request.getValue().messages())
			.as("压缩输入不得包含内部指令（否则它会进摘要/记忆）")
			.noneMatch(ConditionHandoffPersistenceTest::isSyntheticInstruction);

		List<Message> persisted = this.codec.deserializeAll(this.store.get(RUN_ID).getMessageJson());
		assertThat(persisted).as("快照历史不得包含内部指令")
			.noneMatch(ConditionHandoffPersistenceTest::isSyntheticInstruction);
		assertThat(persisted).filteredOn(message -> REAL_USER.equals(message.getText()))
			.as("真实用户历史一字不动、不重复").hasSize(1);
		assertThat(persisted.get(1)).as("真实用户消息位置不变").isInstanceOf(UserMessage.class);

		List<Message> handed = historyOfDriveResumed();
		assertThat(handed).filteredOn(ConditionHandoffPersistenceTest::isSyntheticInstruction)
			.as("本跳交棒仍给模型恰好一条内部指令").hasSize(1);
		assertThat(handed.get(handed.size() - 1))
			.as("指令是本跳最后一条（模型紧接着做结构化）").isInstanceOf(UserMessage.class);
	}

	// ── 桩与辅助 ────────────────────────────────────────────────────────────

	/** 与 {@code SyntheticMessages#isSynthetic} 同一判据：导出收尾/条件交棒共用的内部指令前缀。 */
	private static boolean isSyntheticInstruction(Message message) {
		String text = message == null ? null : message.getText();
		return text != null && text.startsWith(SyntheticMessages.EXPORT_CORRECTION_PREFIX);
	}

	/** 交棒首次进入 QUERY_COND 且条件未决定：宿主在交棒上下文里写标记（生产由 ConditionsForm 完成）。 */
	private void markHandoffWhenUnknown() {
		when(this.conditionsForm.parkIfDueAtQueryCond(eq(RUN_ID), any(AiContext.class), any(), eq(false)))
			.thenAnswer(invocation -> {
				AiContext context = invocation.getArgument(1);
				Map<String, Object> extra = new LinkedHashMap<>(context.getExtra());
				extra.put(AiContext.EXTRA_CONDITION_STRUCTURE_HANDOFF, Boolean.TRUE);
				context.setExtra(extra);
				return false;
			});
	}

	private void seedSuspendedConfirmStep() {
		PendingToolCall settled = entry(CALL_CONFIRM, "confirm_step", "{}", PendingToolCall.KIND_FRONTEND,
				PendingToolCall.FRONTEND_RESULT, ToolOutcome.OK.wire());
		seedSuspended(List.of(settled),
				AiContext.of("export", "EXPORT", "SELECT_DEFS", TASK_ID, "ACTIVE", Map.of()));
	}

	private void seedSuspended(List<PendingToolCall> inFlight, AiContext context) {
		RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
		snapshot.setStatus(RunSnapshot.SUSPENDED);
		snapshot.setContext(context.toMap());
		snapshot.setMessageJson(this.codec.serializeAll(
				List.of(new SystemMessage(SYSTEM_TEXT), new UserMessage(REAL_USER))));
		snapshot.setInFlightToolCalls(new ArrayList<>(inFlight));
		this.store.save(snapshot);
		for (PendingToolCall pending : inFlight) {
			this.store.putPending(RUN_ID, pending);
		}
	}

	private AiContext contextOfDriveResumed() {
		ArgumentCaptor<AiContext> nextHop = ArgumentCaptor.forClass(AiContext.class);
		verify(this.chatService, org.mockito.Mockito.atLeastOnce()).driveResumed(eq(RUN_ID), eq(SESSION_ID),
				nextHop.capture(), any(), eq(this.emitter));
		return nextHop.getValue();
	}

	@SuppressWarnings("unchecked")
	private List<Message> historyOfDriveResumed() {
		ArgumentCaptor<List<Message>> history = ArgumentCaptor.forClass(List.class);
		verify(this.chatService, org.mockito.Mockito.atLeastOnce()).driveResumed(eq(RUN_ID), eq(SESSION_ID),
				any(AiContext.class), history.capture(), eq(this.emitter));
		return history.getValue();
	}

	private static PendingToolCall entry(String callId, String name, String arguments, String kind, String status,
			String outcome) {
		PendingToolCall pending = new PendingToolCall();
		pending.setToolCallId(callId);
		pending.setName(name);
		pending.setType("function");
		pending.setArguments(arguments);
		pending.setKind(kind);
		pending.setStatus(status);
		pending.setOutcome(outcome);
		pending.setResultText("前端已执行：" + name);
		pending.setReq(Boolean.TRUE);
		return pending;
	}

	/** 带结构化条目（仅 defCodes，条件未决定）的任务：已知配置的唯一事实来源。 */
	private static Task taskWithDefs(Long id, String step) {
		Task task = new Task();
		task.setId(id);
		task.setType(Task.TaskType.EXPORT);
		task.setCurrentStep(step);
		task.setStatus(Task.TaskStatus.ACTIVE);
		task.setTitle("任务 " + id);
		TaskItem item = new TaskItem();
		item.setTaskId(id);
		item.setDefCode("CURRENCY");
		item.setSortOrder(0);
		task.setItems(new ArrayList<>(List.of(item)));
		return task;
	}

	/**
	 * 内存替身 + <b>Redis 读写边界</b>：读返回反序列化副本，写也只存副本。
	 *
	 * <p>为什么必须有：{@code InMemoryRunStore} 读写返回同一个对象，于是"只改内存、没 save"
	 * 与"已落库"在读侧无法区分、且 save 之后继续改也看不见 —— 条件结构化标记的持久化缺陷
	 * 正是被这个假象掩盖的（真实 Redis 在 save 时就把 JSON 定稿了）。
	 */
	static class RedisLikeStore extends InMemoryRunStore {

		private final ObjectMapper copyMapper = new ObjectMapper();

		@Override
		public RunSnapshot get(String runId) {
			return copy(super.get(runId));
		}

		@Override
		public RunSnapshot get(String runId, long tenantId, long userId) {
			return copy(super.get(runId, tenantId, userId));
		}

		@Override
		public void save(RunSnapshot snapshot) {
			super.save(copy(snapshot));
		}

		private RunSnapshot copy(RunSnapshot snapshot) {
			if (snapshot == null) {
				return null;
			}
			try {
				return this.copyMapper.readValue(this.copyMapper.writeValueAsString(snapshot), RunSnapshot.class);
			}
			catch (Exception ex) {
				throw new IllegalStateException("快照副本反序列化失败", ex);
			}
		}

	}

	/** 只声明本用例关心的披露面（与 {@code AiTools} 的真实注解同源）：工具体永不执行。 */
	static class WizardTools {

		@Tool(name = "confirm_step", description = "确认并推进向导步骤")
		@ToolScope({ "task:EXPORT", "task:IMPORT" })
		@ToolRisk(ToolMeta.RiskLevel.WRITE)
		@ToolCategory(ToolMeta.Category.U)
		@ToolChannel(ToolMeta.Channel.FRONTEND)
		public String confirmStep(@ToolParam(description = "目标步骤", required = false) String step) {
			return "前端哨兵桩（永不执行）";
		}

		@Tool(name = "create_task", description = "创建任务（确认门）")
		@ToolScope({ "page:tasks", "task:EXPORT", "task:IMPORT" })
		@ToolRisk(ToolMeta.RiskLevel.DANGER)
		@ToolCategory(ToolMeta.Category.W)
		public String createTask(@ToolParam(description = "任务类型") String type) {
			return "后端哨兵桩（永不执行）";
		}

	}

}
