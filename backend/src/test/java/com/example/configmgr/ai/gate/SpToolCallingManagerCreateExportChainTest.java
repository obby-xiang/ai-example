package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolCategory;
import com.example.configmgr.ai.tool.ToolChannel;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolResultEnvelope;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.example.configmgr.ai.tool.ToolRisk;
import com.example.configmgr.ai.tool.ToolScope;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.service.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「创建 → 导出向导」完整链路的精确用例（不连 Redis、不连数据库、不起 Spring 容器）。
 *
 * <p>被测链路（<b>每一步各占一批</b>，即一次 {@code executeToolCalls}）：
 * <ol>
 * <li>{@code create_task}（DANGER ⇒ 确认门，人批准后才执行）——回执里的真实任务号决定上下文；</li>
 * <li>{@code navigate_to(page=export, taskId=...)} —— 进入导出向导；</li>
 * <li>{@code select_definitions(codes=[...])} —— 勾选配置项（前端落库）；</li>
 * <li>{@code confirm_step} —— 步骤推进，<b>只认 TaskService 权威 currentStep</b>；</li>
 * <li>{@code set_condition(conditions=...)} —— 查询条件（前端 {@code setConditions} 落库）。</li>
 * </ol>
 *
 * <p>断言口径：
 * <ul>
 * <li><b>最终上下文 = QUERY_COND</b>：链路走完后快照里的 page/taskId/type/step/status 与任务权威值一致；</li>
 * <li><b>配置与条件真的下发</b>：{@code select_definitions}/{@code set_condition} 到达前端的入参
 * 与模型给出的 JSON 逐字一致（codes / defCode / conditions 原样，没有被改写或丢键）；</li>
 * <li><b>按批串行</b>：五步分属五个不同的批，每批恰好一条工具结果；四条前端回执的顺序 =
 * navigate → select → confirm → set_condition，批与批之间没有并发重叠；</li>
 * <li><b>create/start 的 HITL 不变</b>：{@code create_task} 与 {@code start_export} 仍是
 * DANGER + BACKEND ⇒ 走确认门；拒绝即未执行（{@code executed=false}），放行才执行。</li>
 * </ul>
 *
 * <p>披露面用<b>真实 {@link ToolRegistry}</b>（注解 scope 参与判定），只有领域侧
 * （{@code TaskService}）与存储侧（{@code RunStore}）是替身。
 */
class SpToolCallingManagerCreateExportChainTest {

    private static final String RUN_ID = "run-create-export-chain";

    private static final long TASK_ID = 42L;

    /** 起始页：任务中心（无任务上下文），create_task 在此披露。 */
    private static final AiContext TASKS_PAGE = AiContext.of("tasks", null, null, null, null);

    private final RunStore store = mock(RunStore.class);

    private final RunRegistry registry = mock(RunRegistry.class);

    private final ConfirmGate gate = mock(ConfirmGate.class);

    private final MessageJsonCodec codec = mock(MessageJsonCodec.class);

    private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

    private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

    private final SseChatEmitter emitter = mock(SseChatEmitter.class);

    private final AiProperties properties = new AiProperties();

    private final ToolResultLimiter limiter = new ToolResultLimiter(this.properties);

    private final TaskService taskService = mock(TaskService.class);

    private final AtomicReference<Task> taskState = new AtomicReference<>();

    /** 确认门的裁决序列（按到达顺序消费）：APPROVED / REJECTED。 */
    private final Deque<String> decisions = new ArrayDeque<>();

    /** 每批前端回执的到达顺序与所属批号（批号 = 第几次 executeToolCalls）。 */
    private final List<String> frontendNames = new ArrayList<>();

    private final List<String> frontendArguments = new ArrayList<>();

    private final List<Integer> frontendBatches = new ArrayList<>();

    /** 确认门收到的条目（按到达顺序），用于核对 kind 与 executed。 */
    private final List<PendingToolCall> decided = new ArrayList<>();

    private int batch;

    /** "Redis 里的那一份"快照：每次 {@code get} 给副本、每次 {@code save} 覆盖。 */
    private RunSnapshot stored;

    private AnnotationConfigApplicationContext registryContext;

    private ToolRegistry toolRegistry;

    private SpToolCallingManager manager;

    @BeforeEach
    void setUp() {
        this.registryContext = new AnnotationConfigApplicationContext();
        this.registryContext.registerBean(WizardTools.class);
        this.registryContext.registerBean(ToolRegistry.class);
        this.registryContext.refresh();
        this.toolRegistry = this.registryContext.getBean(ToolRegistry.class);

        when(this.store.get(RUN_ID)).thenAnswer(invocation -> this.stored == null ? null : copyOf(this.stored));
        doAnswer(invocation -> {
            this.stored = copyOf(invocation.getArgument(0));
            return null;
        }).when(this.store).save(any(RunSnapshot.class));
        when(this.store.runKey(RUN_ID)).thenReturn("ai:run:" + RUN_ID);
        when(this.store.pendingKey(RUN_ID)).thenReturn("ai:pending:" + RUN_ID);
        when(this.store.instanceId()).thenReturn("test-instance");
        when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
        when(this.cancellations.cancelState(RUN_ID)).thenReturn(CancellationRegistry.CancelState.NOT_CANCELLED);
        when(this.store.tryClaimExecution(eq(RUN_ID), anyString(), anyString()))
            .thenReturn(new RunStore.ClaimResult(RunStore.ClaimOutcome.WON, 0L, 0L));
        when(this.taskService.findByIdRaw(any())).thenAnswer(invocation -> Optional.ofNullable(this.taskState.get())
            .filter(task -> task.getId() != null && task.getId().equals(invocation.getArgument(0))));

        when(this.gate.awaitFrontendResult(eq(RUN_ID), any(PendingToolCall.class)))
            .thenAnswer(invocation -> {
                PendingToolCall pending = invocation.getArgument(1);
                this.frontendNames.add(pending.getName());
                this.frontendArguments.add(pending.getArguments());
                this.frontendBatches.add(this.batch);
                pending.setStatus(PendingToolCall.FRONTEND_RESULT);
                pending.setOutcome(ToolOutcome.OK.wire());
                pending.setResultText("前端已执行：" + pending.getName());
                return pending;
            });
        when(this.gate.awaitDecision(eq(RUN_ID), any(PendingToolCall.class)))
            .thenAnswer(invocation -> {
                PendingToolCall pending = invocation.getArgument(1);
                this.decided.add(pending);
                if (PendingToolCall.APPROVED.equals(this.decisions.pollFirst())) {
                    pending.setStatus(PendingToolCall.APPROVED);
                }
                else {
                    pending.setStatus(PendingToolCall.REJECTED);
                    pending.setOutcome(ToolOutcome.REJECTED.wire());
                    pending.setResultText("用户拒绝了该操作，工具未执行。");
                }
                return pending;
            });

        this.manager = new SpToolCallingManager(this.store, this.registry, this.gate, this.toolRegistry, this.codec,
                this.beacon, this.cancellations, this.limiter, List.of());
        this.manager.setContextRefresher(new RunContextRefresher(this.taskService));
    }

    @AfterEach
    void tearDown() {
        if (this.registryContext != null) {
            this.registryContext.close();
        }
    }

    /**
     * 完整链路：create_task 批准 → navigate_to → select_definitions → confirm_step → set_condition。
     *
     * <p>先单独确认一次 {@code start_export} 的 HITL（拒绝即未执行），再以全新快照跑链路，
     * 于是链路末尾的"最终步骤 = QUERY_COND"不被后置动作污染。
     */
    @Test
    void createToSetConditionChainRunsBatchByBatchAndKeepsCreateStartHitl() {
        // ── 0. start_export 的 HITL 不变：DANGER+BACKEND，人拒即未执行 ──────────────
        AiContext exportReady = AiContext.of("export", "EXPORT", "EXPORT", 7L, "ACTIVE", null);
        seedSnapshot(exportReady);
        this.taskState.set(task(7L, Task.TaskType.EXPORT, "EXPORT", Task.TaskStatus.ACTIVE));
        this.decisions.add(PendingToolCall.REJECTED);

        ToolExecutionResult rejectedStart = run(optionsFor(exportReady),
                call("s1", "start_export", "{\"taskId\":7}"));

        assertThat(envelopeOf(rejectedStart, "s1").outcome()).isEqualTo(ToolOutcome.REJECTED.wire());
        assertThat(envelopeOf(rejectedStart, "s1").message()).contains("用户拒绝了该操作");
        // 拒绝 = 没走前端、没执行；确认门确实被问到（不是静默放行）
        assertThat(this.frontendNames).isEmpty();
        assertThat(this.decided).hasSize(1);
        assertThat(this.decided.get(0).getName()).isEqualTo("start_export");
        assertThat(this.decided.get(0).getKind()).isEqualTo(PendingToolCall.KIND_CONFIRM);
        assertThat(this.decided.get(0).isExecuted()).isFalse();
        assertThat(this.toolRegistry.riskOf("start_export")).isEqualTo(ToolMeta.RiskLevel.DANGER);
        assertThat(this.toolRegistry.channelOf("start_export")).isEqualTo(ToolMeta.Channel.BACKEND);
        assertThat(this.toolRegistry.riskOf("create_task")).isEqualTo(ToolMeta.RiskLevel.DANGER);
        assertThat(this.toolRegistry.channelOf("create_task")).isEqualTo(ToolMeta.Channel.BACKEND);

        // ── 1. create_task：确认门批准后才建任务；任务号进上下文 ─────────────────
        seedSnapshot(TASKS_PAGE);
        this.taskState.set(task(TASK_ID, Task.TaskType.EXPORT, "SELECT_DEFS", Task.TaskStatus.ACTIVE));
        this.decisions.add(PendingToolCall.APPROVED);
        ToolCallingChatOptions boundary = optionsFor(TASKS_PAGE);
        assertThat(definitionNames(this.manager.resolveToolDefinitions(boundary))).contains("create_task");

        ToolExecutionResult created = run(boundary,
                call("c1", "create_task", "{\"type\":\"EXPORT\",\"title\":\"十月导出\"}"));

        singleResponseOf(created, "c1");
        assertThat(envelopeOf(created, "c1").outcome()).isEqualTo(ToolOutcome.OK.wire());
        assertThat(this.decided).hasSize(2);
        assertThat(this.decided.get(1).getName()).isEqualTo("create_task");
        assertThat(this.decided.get(1).getKind()).isEqualTo(PendingToolCall.KIND_CONFIRM);
        assertThat(this.decided.get(1).isExecuted()).isTrue();
        assertThat(this.frontendNames).isEmpty();
        AiContext afterCreate = persistedContext();
        assertThat(afterCreate.getTaskId()).isEqualTo(TASK_ID);
        assertThat(afterCreate.getTaskType()).isEqualTo("EXPORT");
        assertThat(afterCreate.getStep()).isEqualTo("SELECT_DEFS");
        // 创建发生在任务中心 ⇒ 页面事实不被任务类型覆盖
        assertThat(afterCreate.getPage()).isEqualTo("tasks");

        // ── 2. navigate_to：进入导出向导 ────────────────────────────────────────
        ToolExecutionResult navigated = run(boundary,
                call("c2", "navigate_to", "{\"page\":\"export\",\"taskId\":42}"));

        singleResponseOf(navigated, "c2");
        assertThat(envelopeOf(navigated, "c2").outcome()).isEqualTo(ToolOutcome.OK.wire());
        AiContext afterNavigate = persistedContext();
        assertThat(afterNavigate.getPage()).isEqualTo("export");
        assertThat(afterNavigate.getTaskId()).isEqualTo(TASK_ID);
        assertThat(afterNavigate.getStep()).isEqualTo("SELECT_DEFS");
        assertThat(this.frontendNames).containsExactly("navigate_to");

        // ── 3. select_definitions：配置项勾选原样下发、前端回执成功（= 已落库） ──
        String selectArgs = "{\"codes\":[\"CURRENCY\"],\"mode\":\"REPLACE\"}";
        ToolExecutionResult selected = run(boundary, call("c3", "select_definitions", selectArgs));

        singleResponseOf(selected, "c3");
        assertThat(envelopeOf(selected, "c3").outcome()).isEqualTo(ToolOutcome.OK.wire());
        assertThat(this.frontendNames).containsExactly("navigate_to", "select_definitions");
        assertThat(this.frontendArguments.get(1)).isEqualTo(selectArgs);
        // 勾选不推进步骤：仍在选择配置步
        assertThat(persistedContext().getStep()).isEqualTo("SELECT_DEFS");

        // ── 4. confirm_step：步骤只认 TaskService 权威值（前端已落库 QUERY_COND） ─
        this.taskState.set(task(TASK_ID, Task.TaskType.EXPORT, "QUERY_COND", Task.TaskStatus.ACTIVE));
        ToolExecutionResult advanced = run(boundary, call("c4", "confirm_step", "{}"));

        singleResponseOf(advanced, "c4");
        assertThat(envelopeOf(advanced, "c4").outcome()).isEqualTo(ToolOutcome.OK.wire());
        AiContext afterStep = persistedContext();
        assertThat(afterStep.getStep()).isEqualTo("QUERY_COND");
        // 下一批按新步骤重算披露面：条件工具可见，尚未到 EXPORT ⇒ start_export 仍不出现
        assertThat(definitionNames(this.manager.resolveToolDefinitions(boundary)))
            .contains("set_condition")
            .doesNotContain("start_export");

        // ── 5. set_condition：查询条件下发（defCode + conditions 原样） ───────────
        String conditionArgs = "{\"defCode\":\"CURRENCY\",\"conditions\":{\"scopeKeys\":[\"XN\"],"
                + "\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"CNY\"}]}}";
        ToolExecutionResult conditioned = run(boundary, call("c5", "set_condition", conditionArgs));

        singleResponseOf(conditioned, "c5");
        assertThat(envelopeOf(conditioned, "c5").outcome()).isEqualTo(ToolOutcome.OK.wire());
        assertThat(this.frontendArguments.get(3)).isEqualTo(conditionArgs);

        // ── 串行与最终态 ───────────────────────────────────────────────────────
        // 四条前端回执各属一批（批 3..6；批 1 = start_export 的确认门、批 2 = create_task），顺序 = 链路顺序
        assertThat(this.frontendNames)
            .containsExactly("navigate_to", "select_definitions", "confirm_step", "set_condition");
        assertThat(this.frontendBatches).containsExactly(3, 4, 5, 6);

        AiContext finalContext = persistedContext();
        assertThat(finalContext.getPage()).isEqualTo("export");
        assertThat(finalContext.getTaskId()).isEqualTo(TASK_ID);
        assertThat(finalContext.getTaskType()).isEqualTo("EXPORT");
        assertThat(finalContext.getStep()).isEqualTo("QUERY_COND");
        assertThat(finalContext.getTaskStatus()).isEqualTo("ACTIVE");
        // 任务权威值同样停在 QUERY_COND（set_condition 不推进步骤）
        assertThat(this.taskState.get().getCurrentStep()).isEqualTo("QUERY_COND");
        // 上下文写回过沙盒快照：create / navigate / confirm_step 各读一次任务权威值
        verify(this.store, atLeastOnce()).save(any(RunSnapshot.class));
        verify(this.taskService, times(3)).findByIdRaw(TASK_ID);
        // 整条链路只有 create_task 一次人工决策（配置/条件/导航都不进确认门）
        assertThat(this.decided).hasSize(2);
    }

    // ── 桩与辅助 ────────────────────────────────────────────────────────────

    /**
     * 只声明本用例关心的工具（scope/风险/通道与 {@code AiTools} 的真实注解一致）：
     * 前端体与后端体都是桩，绝不触达任何服务。
     */
    static class WizardTools {

        @Tool(name = "create_task", description = "创建任务（确认门）")
        @ToolScope({ "page:tasks", "task:EXPORT", "task:IMPORT" })
        @ToolRisk(ToolMeta.RiskLevel.DANGER)
        @ToolCategory(ToolMeta.Category.W)
        public String createTask(@ToolParam(description = "任务类型") String type,
                @ToolParam(description = "标题", required = false) String title) {
            // 回执照 AiTools#createTask 的形态带真实任务号
            return "已创建导出任务 #42（标题：" + title + "，当前步骤：SELECT_DEFS）。创建不等于执行：尚未启动任何作业。";
        }

        @Tool(name = "navigate_to", description = "驱动前端页面导航")
        @ToolScope("*")
        @ToolRisk(ToolMeta.RiskLevel.READ)
        @ToolCategory(ToolMeta.Category.U)
        @ToolChannel(ToolMeta.Channel.FRONTEND)
        public String navigateTo(@ToolParam(description = "目标页面") String page,
                @ToolParam(description = "任务ID", required = false) Long taskId) {
            return "前端哨兵桩（永不执行）";
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

        @Tool(name = "confirm_step", description = "确认并推进向导步骤")
        @ToolScope({ "task:EXPORT", "task:IMPORT" })
        @ToolRisk(ToolMeta.RiskLevel.WRITE)
        @ToolCategory(ToolMeta.Category.U)
        @ToolChannel(ToolMeta.Channel.FRONTEND)
        public String confirmStep(@ToolParam(description = "目标步骤", required = false) String step) {
            return "前端哨兵桩（永不执行）";
        }

        @Tool(name = "set_condition", description = "设置查询条件")
        @ToolScope({ "task:EXPORT/QUERY_COND" })
        @ToolRisk(ToolMeta.RiskLevel.WRITE)
        @ToolCategory(ToolMeta.Category.U)
        @ToolChannel(ToolMeta.Channel.FRONTEND)
        public String setCondition(@ToolParam(description = "配置定义编码") String defCode,
                @ToolParam(description = "查询条件对象") String conditions) {
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

    private void seedSnapshot(AiContext context) {
        RunSnapshot snapshot = new RunSnapshot();
        snapshot.setRunId(RUN_ID);
        snapshot.setSessionId("sess-chain");
        snapshot.setStatus(RunSnapshot.RUNNING);
        snapshot.setContext(context.toMap());
        this.stored = snapshot;
    }

    private AiContext persistedContext() {
        return AiContext.fromMap(this.stored.getContext());
    }

    private ToolCallingChatOptions optionsFor(AiContext boundary) {
        return ToolCallingChatOptions.builder()
            .toolCallbacks(this.toolRegistry.forContext(boundary))
            .toolContext(Map.of("runId", RUN_ID))
            .build();
    }

    private ToolExecutionResult run(ToolCallingChatOptions options, AssistantMessage.ToolCall... calls) {
        this.batch++;
        AssistantMessage assistant = AssistantMessage.builder().toolCalls(List.of(calls)).build();
        Prompt prompt = new Prompt(List.of(new UserMessage("hi")), options);
        return this.manager.executeToolCalls(prompt, new ChatResponse(List.of(new Generation(assistant))));
    }

    private static AssistantMessage.ToolCall call(String id, String name, String arguments) {
        return new AssistantMessage.ToolCall(id, "function", name, arguments);
    }

    private static List<String> definitionNames(List<ToolDefinition> definitions) {
        return definitions.stream().map(ToolDefinition::name).toList();
    }

    /** 一批恰好一条工具结果（多一条 = 两步被塞进同一批，串行口径被破坏）。 */
    private static ToolResponseMessage.ToolResponse singleResponseOf(ToolExecutionResult result, String callId) {
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        for (Message message : result.conversationHistory()) {
            if (message instanceof ToolResponseMessage toolMessage) {
                responses.addAll(toolMessage.getResponses());
            }
        }
        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).id()).isEqualTo(callId);
        return responses.get(0);
    }

    private static ToolResultEnvelope envelopeOf(ToolExecutionResult result, String callId) {
        return ToolResultEnvelope.fromJson(singleResponseOf(result, callId).responseData());
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

    /** 模拟 Redis：{@code get} 给副本、{@code save} 覆盖 —— 上下文没写回去就会在下一跳丢失。 */
    private static RunSnapshot copyOf(RunSnapshot source) {
        RunSnapshot copy = new RunSnapshot();
        copy.setRunId(source.getRunId());
        copy.setSessionId(source.getSessionId());
        copy.setStatus(source.getStatus());
        copy.setRound(source.getRound());
        copy.setAttempts(source.getAttempts());
        copy.setToolSteps(source.getToolSteps());
        copy.setCancelled(source.isCancelled());
        copy.setMessageJson(source.getMessageJson() == null ? new ArrayList<>()
                : new ArrayList<>(source.getMessageJson()));
        copy.setInFlightToolCalls(new ArrayList<>());
        copy.setContext(source.getContext() == null ? new LinkedHashMap<>()
                : new LinkedHashMap<>(source.getContext()));
        copy.setProviderId(source.getProviderId());
        copy.setModelId(source.getModelId());
        copy.setCreatedBy(source.getCreatedBy());
        copy.setCreatedAtMs(source.getCreatedAtMs());
        copy.setBudgetArmedAtMs(source.getBudgetArmedAtMs());
        copy.setBudgetSkippedBatches(source.getBudgetSkippedBatches());
        return copy;
    }

}
