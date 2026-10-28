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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 多步工作流批的“运行上下文推进 / 下一跳动态披露”用例（不连 Redis、不连数据库、不起 Spring 容器）。
 *
 * <p>覆盖四条口径：
 * <ol>
 * <li><b>批间刷新、批内冻结</b>：{@code navigate_to}/{@code create_task}/{@code confirm_step} 成功结算后
 * 按真实结果刷新 {@code RunSnapshot.context}；<b>同一批</b>里后续调用的 scope 判据仍是批首那一份
 * （导航后同批选配置必须 {@code SCOPE_NOT_DISCLOSED}），下一批才按新上下文披露；</li>
 * <li><b>下一跳重算披露面</b>：{@code resolveToolDefinitions} 每跳按<b>当前快照</b>重算
 * {@code ToolRegistry#forContext}；进入 EXPORT 之前不出现 {@code start_export}，进入之后才出现
 * （步骤只认 TaskService 权威值，不认入参自报的 {@code step}）；</li>
 * <li><b>失败降级</b>：刷新失败只保留旧上下文（工具轮照常成功），无 runId/无快照/空 callbacks 时
 * 回落到原委托 —— 绝不把“没有工具”变成“全部工具”。</li>
 * </ol>
 *
 * <p>披露面用<b>真实 {@link ToolRegistry}</b>（注解 scope 参与判定），不是替身；只有领域侧
 * （{@code TaskService}）与存储侧（{@code RunStore}）是替身。
 */
class SpToolCallingManagerDynamicDisclosureTest {

    private static final String RUN_ID = "run-dyn-disclosure";

    /** 起始页面不是向导：任务类工具（含 select/set/confirm/start_export）一律不可见。 */
    private static final AiContext DEFINITIONS_PAGE = AiContext.of("definitions", null, null, null, null);

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

    /** “Redis 里的那一份”快照：每次 {@code get} 给副本、每次 {@code save} 覆盖 —— 漏写就会丢上下文。 */
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

        this.manager = new SpToolCallingManager(this.store, this.registry, this.gate, this.toolRegistry, this.codec,
                this.beacon, this.cancellations, this.limiter, List.of());
        // 既有构造签名不动：刷新器走可选 setter 注入（轻装配面不注入 = 不刷新）
        this.manager.setContextRefresher(new RunContextRefresher(this.taskService));
    }

    @AfterEach
    void tearDown() {
        if (this.registryContext != null) {
            this.registryContext.close();
        }
    }

    // ── A：导航成功 → 批间刷新 → 下一批披露；同批 scope 冻结 ─────────────────

    @Test
    void navigateRefreshesSnapshotAndNextBatchDisclosesWizardTools() {
        seedSnapshot(DEFINITIONS_PAGE);
        this.taskState.set(task(7L, Task.TaskType.EXPORT, "SELECT_DEFS", Task.TaskStatus.ACTIVE));
        frontendSucceeds();
        ToolCallingChatOptions boundary = optionsFor(DEFINITIONS_PAGE);
        // 请求边界按 page:definitions 披露：只有 * 级工具，向导动作一个都没有
        assertThat(definitionNames(this.manager.resolveToolDefinitions(boundary)))
            .containsExactly("navigate_to");

        // 同一批：先导航成功，再选配置 —— 选配置仍按<b>批首</b>冻结的 scope 被拒（不许同批扩权）
        ToolExecutionResult batch = run(boundary,
                call("c1", "navigate_to", "{\"page\":\"export\",\"taskId\":7}"),
                call("c2", "select_definitions", "{\"codes\":[\"CURRENCY\"]}"));

        assertThat(envelopeOf(batch, "c1").outcome()).isEqualTo(ToolOutcome.OK.wire());
        ToolResultEnvelope outOfScope = envelopeOf(batch, "c2");
        assertThat(outOfScope.outcome()).isEqualTo(ToolOutcome.BLOCKED.wire());
        assertThat(outOfScope.code()).isEqualTo(SpToolCallingManager.SCOPE_VIOLATION_CODE);
        // 同批只有一个前端调用真的挂起等回执（navigate_to）；被拒绝的 select_definitions 没有挂起
        verify(this.gate, times(1)).awaitFrontendResult(eq(RUN_ID), any(PendingToolCall.class));

        // 批间：快照上下文按真实任务推进（page/taskId 来自入参，type/step/status 来自 TaskService）
        AiContext refreshed = persistedContext();
        assertThat(refreshed.getPage()).isEqualTo("export");
        assertThat(refreshed.getTaskId()).isEqualTo(7L);
        assertThat(refreshed.getTaskType()).isEqualTo("EXPORT");
        assertThat(refreshed.getStep()).isEqualTo("SELECT_DEFS");
        assertThat(refreshed.getTaskStatus()).isEqualTo("ACTIVE");
        verify(this.store, atLeastOnce()).save(any(RunSnapshot.class));

        // 下一跳：同一份冻结 options，按新上下文重算披露面 —— 选择/推进可见，
        // 但当前是 SELECT_DEFS：条件工具（前端只允许 QUERY_COND 用）与 start_export 仍不出现
        List<String> nextHop = definitionNames(this.manager.resolveToolDefinitions(boundary));
        assertThat(nextHop).contains("navigate_to", "select_definitions", "confirm_step", "check_job_status");
        assertThat(nextHop).doesNotContain("set_condition", "start_export");

        // 下一批（新一批）选择配置：判据换成刷新后的上下文 ⇒ 放行
        ToolExecutionResult selectBatch = run(boundary,
                call("c3", "select_definitions", "{\"codes\":[\"CURRENCY\"]}"));
        assertThat(envelopeOf(selectBatch, "c3").outcome()).isEqualTo(ToolOutcome.OK.wire());

        // 本批新增披露的后端回调不在旧 options 里：执行侧必须用当前 callbacks 副本保证可解析
        ToolExecutionResult backendBatch = run(boundary, call("c4", "check_job_status", "{\"taskId\":7}"));
        ToolResultEnvelope backend = envelopeOf(backendBatch, "c4");
        assertThat(backend.outcome()).isEqualTo(ToolOutcome.OK.wire());
        assertThat(backend.message()).contains("无在途作业");

        // 任务推进到 EXPORT 之后：再导航一次同一任务时按 TaskService 的权威步骤刷新 —— 此时才出现
        // start_export（正控：证明重算真的是按上下文算的，不是"总是带上"；confirm_step 的同类刷新见
        // confirmStepRefreshesStepAndDisclosesStartExportOnlyAfterExport）
        this.taskState.set(task(7L, Task.TaskType.EXPORT, "EXPORT", Task.TaskStatus.ACTIVE));
        ToolExecutionResult stepBatch = run(boundary,
                call("c5", "navigate_to", "{\"page\":\"export\",\"taskId\":7}"));
        assertThat(envelopeOf(stepBatch, "c5").outcome()).isEqualTo(ToolOutcome.OK.wire());
        assertThat(persistedContext().getStep()).isEqualTo("EXPORT");
        assertThat(definitionNames(this.manager.resolveToolDefinitions(boundary))).contains("start_export");
    }

    // ── B：无任务导航清旧任务字段 + 换页删 uiRegionCount ──────────────────────

    @Test
    void tasklessNavigateClearsStaleTaskFieldsAndRegionCount() {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("uiRegionCount", 3);
        extra.put("keep", "x");
        seedSnapshot(AiContext.of("export", "EXPORT", "EXPORT", 7L, "ACTIVE", extra));
        frontendSucceeds();
        ToolCallingChatOptions boundary = optionsFor(AiContext.of("export", "EXPORT", "EXPORT", 7L, "ACTIVE",
                extra));

        ToolExecutionResult batch = run(boundary, call("c1", "navigate_to", "{\"page\":\"tasks\"}"));

        assertThat(envelopeOf(batch, "c1").outcome()).isEqualTo(ToolOutcome.OK.wire());
        AiContext refreshed = persistedContext();
        assertThat(refreshed.getPage()).isEqualTo("tasks");
        // 无 taskId 的导航：旧任务的类型/步骤/状态/ID 一律清掉（否则下一页仍在旧任务的披露面里）
        assertThat(refreshed.getTaskId()).isNull();
        assertThat(refreshed.getTaskType()).isNull();
        assertThat(refreshed.getStep()).isNull();
        assertThat(refreshed.getTaskStatus()).isNull();
        // 换页：旧页的 uiRegionCount 不再代表新页；其它 extra 原样保留
        assertThat(refreshed.getExtra()).doesNotContainKey("uiRegionCount");
        assertThat(refreshed.getExtra()).containsEntry("keep", "x");
        verify(this.taskService, never()).findByIdRaw(any());
    }

    // ── C：create_task 提取真实 taskId；刷新失败/无快照/空 callbacks 回落 ──────

    @Test
    void createTaskRefreshUsesRealTaskIdAndDegradesOnRefreshFailure() {
        seedSnapshot(AiContext.of("tasks", null, null, null, null));
        this.taskState.set(task(42L, Task.TaskType.EXPORT, "SELECT_DEFS", Task.TaskStatus.ACTIVE));
        confirmApproves();
        frontendSucceeds();
        ToolCallingChatOptions boundary = optionsFor(AiContext.of("tasks", null, null, null, null));

        ToolExecutionResult created = run(boundary,
                call("c1", "create_task", "{\"type\":\"EXPORT\",\"title\":\"月度导出\"}"));

        assertThat(envelopeOf(created, "c1").outcome()).isEqualTo(ToolOutcome.OK.wire());
        AiContext afterCreate = persistedContext();
        // taskId 来自回执的真实任务号；type/step/status 来自 TaskService 的权威值
        assertThat(afterCreate.getTaskId()).isEqualTo(42L);
        assertThat(afterCreate.getTaskType()).isEqualTo("EXPORT");
        assertThat(afterCreate.getStep()).isEqualTo("SELECT_DEFS");
        assertThat(afterCreate.getTaskStatus()).isEqualTo("ACTIVE");
        // 已有页面事实（任务中心）不按任务类型覆盖
        assertThat(afterCreate.getPage()).isEqualTo("tasks");

        // 刷新失败：TaskService 抛错 ⇒ 保留旧上下文，工具轮照常成功（不许把整轮打穿）
        when(this.taskService.findByIdRaw(9L)).thenThrow(new IllegalStateException("db down"));
        ToolExecutionResult failedRefresh = run(boundary,
                call("c2", "navigate_to", "{\"page\":\"export\",\"taskId\":9}"));

        assertThat(envelopeOf(failedRefresh, "c2").outcome()).isEqualTo(ToolOutcome.OK.wire());
        AiContext kept = persistedContext();
        assertThat(kept.getTaskId()).isEqualTo(42L);
        assertThat(kept.getTaskType()).isEqualTo("EXPORT");
        assertThat(kept.getStep()).isEqualTo("SELECT_DEFS");
        assertThat(kept.getPage()).isEqualTo("tasks");

        // 空 callbacks：回落原委托（空就是空，绝不换成 registry 的全集）
        ToolCallingChatOptions noCallbacks = ToolCallingChatOptions.builder()
            .toolContext(Map.of("runId", RUN_ID))
            .build();
        assertThat(this.manager.resolveToolDefinitions(noCallbacks)).isEmpty();

        // 无快照：同样回落原委托 —— 若误按“当前上下文”重算，会多出一批工具
        ToolCallingChatOptions unknownRun = ToolCallingChatOptions.builder()
            .toolCallbacks(this.toolRegistry.forContext(DEFINITIONS_PAGE))
            .toolContext(Map.of("runId", "run-missing"))
            .build();
        assertThat(definitionNames(this.manager.resolveToolDefinitions(unknownRun)))
            .containsExactly("navigate_to");
    }

    // ── D：confirm_step 推进步骤 → 进入 EXPORT 才披露 start_export ────────────

    /**
     * 步骤推进的刷新口径：{@code confirm_step} 成功结算后按 <b>TaskService 权威 currentStep</b> 刷新，
     * 而不是照抄入参里的 {@code step}；披露面下一批才变 —— 进入 EXPORT 前仍无 {@code start_export}，
     * 进入后才出现。HITL/风险通道不变（{@code start_export} 仍是 DANGER+BACKEND，执行前照样进确认门）。
     */
    @Test
    void confirmStepRefreshesStepAndDisclosesStartExportOnlyAfterExport() {
        AiContext wizard = AiContext.of("export", "EXPORT", "SELECT_DEFS", 7L, "ACTIVE", null);
        seedSnapshot(wizard);
        this.taskState.set(task(7L, Task.TaskType.EXPORT, "SELECT_DEFS", Task.TaskStatus.ACTIVE));
        frontendSucceeds();
        ToolCallingChatOptions boundary = optionsFor(wizard);
        List<String> atSelectDefs = definitionNames(this.manager.resolveToolDefinitions(boundary));
        // SELECT_DEFS：选配置与推进可见；set_condition 不在披露面（前端 allowSteps 只允许 QUERY_COND，
        // 披露了模型就会在错步调用 —— 2026-10-28 步骤披露收口）
        assertThat(atSelectDefs).contains("select_definitions", "confirm_step")
            .doesNotContain("set_condition", "start_export");

        // ① 推进到 QUERY_COND：步骤只认 TaskService（前端已把步骤落库），不从入参猜
        this.taskState.set(task(7L, Task.TaskType.EXPORT, "QUERY_COND", Task.TaskStatus.ACTIVE));
        ToolExecutionResult queryCond = run(boundary, call("c1", "confirm_step", "{}"));

        assertThat(envelopeOf(queryCond, "c1").outcome()).isEqualTo(ToolOutcome.OK.wire());
        AiContext afterQueryCond = persistedContext();
        assertThat(afterQueryCond.getStep()).isEqualTo("QUERY_COND");
        // 条目 args 里没有 taskId ⇒ 沿用当前上下文的那个任务
        assertThat(afterQueryCond.getTaskId()).isEqualTo(7L);
        assertThat(afterQueryCond.getTaskType()).isEqualTo("EXPORT");
        // 条件工具已披露（QUERY_COND 正向披露），但还没到 EXPORT ⇒ start_export 仍不出现
        List<String> atQueryCond = definitionNames(this.manager.resolveToolDefinitions(boundary));
        assertThat(atQueryCond).contains("set_condition", "confirm_step");
        assertThat(atQueryCond).doesNotContain("start_export");

        // ② 模型自报 step=EXPORT，但权威步骤仍是 QUERY_COND ⇒ 不得据此猜成 EXPORT
        ToolExecutionResult guessed = run(boundary, call("c2", "confirm_step", "{\"step\":\"EXPORT\"}"));
        assertThat(envelopeOf(guessed, "c2").outcome()).isEqualTo(ToolOutcome.OK.wire());
        assertThat(persistedContext().getStep()).isEqualTo("QUERY_COND");
        assertThat(definitionNames(this.manager.resolveToolDefinitions(boundary))).doesNotContain("start_export");

        // ③ 真正推进到 EXPORT ⇒ 下一批才披露 start_export
        this.taskState.set(task(7L, Task.TaskType.EXPORT, "EXPORT", Task.TaskStatus.ACTIVE));
        ToolExecutionResult export = run(boundary, call("c3", "confirm_step", "{}"));

        assertThat(envelopeOf(export, "c3").outcome()).isEqualTo(ToolOutcome.OK.wire());
        assertThat(persistedContext().getStep()).isEqualTo("EXPORT");
        assertThat(definitionNames(this.manager.resolveToolDefinitions(boundary))).contains("start_export");

        // ④ 动态披露不等于授权：start_export 仍是 DANGER+BACKEND，执行前照样弹确认门（HITL 不变）
        assertThat(this.toolRegistry.riskOf("start_export")).isEqualTo(ToolMeta.RiskLevel.DANGER);
        assertThat(this.toolRegistry.channelOf("start_export")).isEqualTo(ToolMeta.Channel.BACKEND);
        confirmRejects();
        ToolExecutionResult hitl = run(boundary, call("c4", "start_export", "{\"taskId\":7}"));

        assertThat(envelopeOf(hitl, "c4").outcome()).isEqualTo(ToolOutcome.REJECTED.wire());
        verify(this.gate, times(1)).awaitDecision(eq(RUN_ID), any(PendingToolCall.class));
    }

    // ── 桩与辅助 ────────────────────────────────────────────────────────────

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

        @Tool(name = "create_task", description = "创建任务（确认门）")
        @ToolScope({ "page:tasks", "task:EXPORT", "task:IMPORT" })
        @ToolRisk(ToolMeta.RiskLevel.DANGER)
        @ToolCategory(ToolMeta.Category.W)
        public String createTask(@ToolParam(description = "任务类型") String type,
                @ToolParam(description = "标题", required = false) String title) {
            // 回执照 AiTools#createTask 的形态带真实任务号（#42）
            return "已创建导出任务 #42（标题：" + title + "，当前步骤：SELECT_DEFS）。创建不等于执行：尚未启动任何作业。";
        }

        /**
         * 只在任务上下文披露的后端只读工具（与 {@code check_job_status} 的 {@code task:*} 同形）：
         * 导航前不在请求边界冻结的 options 里，导航后由下一跳重算出来 —— 执行侧必须能解析它。
         */
        @Tool(name = "check_job_status", description = "查询作业状态和进度")
        @ToolScope("task:*")
        @ToolRisk(ToolMeta.RiskLevel.READ)
        @ToolCategory(ToolMeta.Category.R)
        public String checkJobStatus(@ToolParam(description = "任务ID", required = false) Long taskId) {
            return "任务 #" + taskId + "：无在途作业";
        }
    }

    private void seedSnapshot(AiContext context) {
        RunSnapshot snapshot = new RunSnapshot();
        snapshot.setRunId(RUN_ID);
        snapshot.setSessionId("sess-dyn");
        snapshot.setStatus(RunSnapshot.RUNNING);
        snapshot.setContext(context.toMap());
        this.stored = snapshot;
    }

    private AiContext persistedContext() {
        return AiContext.fromMap(this.stored.getContext());
    }

    private void frontendSucceeds() {
        when(this.gate.awaitFrontendResult(eq(RUN_ID), any(PendingToolCall.class))).thenAnswer(invocation -> {
            PendingToolCall pending = invocation.getArgument(1);
            pending.setStatus(PendingToolCall.FRONTEND_RESULT);
            pending.setOutcome(ToolOutcome.OK.wire());
            pending.setResultText("前端已执行：" + pending.getName());
            return pending;
        });
    }

    private void confirmApproves() {
        when(this.gate.awaitDecision(eq(RUN_ID), any(PendingToolCall.class))).thenAnswer(invocation -> {
            PendingToolCall pending = invocation.getArgument(1);
            pending.setStatus(PendingToolCall.APPROVED);
            return pending;
        });
    }

    /** 确认门被用户拒绝（HITL 接线证据：动态披露后的高危工具照样先问人）。 */
    private void confirmRejects() {
        when(this.gate.awaitDecision(eq(RUN_ID), any(PendingToolCall.class))).thenAnswer(invocation -> {
            PendingToolCall pending = invocation.getArgument(1);
            pending.setStatus(PendingToolCall.REJECTED);
            pending.setOutcome(ToolOutcome.REJECTED.wire());
            pending.setResultText("用户拒绝了该操作，工具未执行。");
            return pending;
        });
    }

    private ToolCallingChatOptions optionsFor(AiContext boundary) {
        return ToolCallingChatOptions.builder()
            .toolCallbacks(this.toolRegistry.forContext(boundary))
            .toolContext(Map.of("runId", RUN_ID))
            .build();
    }

    private ToolExecutionResult run(ToolCallingChatOptions options, AssistantMessage.ToolCall... calls) {
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

    private static ToolResultEnvelope envelopeOf(ToolExecutionResult result, String toolCallId) {
        String text = null;
        for (Message message : result.conversationHistory()) {
            if (message instanceof ToolResponseMessage toolMessage) {
                for (ToolResponseMessage.ToolResponse response : toolMessage.getResponses()) {
                    if (toolCallId.equals(response.id())) {
                        text = response.responseData();
                    }
                }
            }
        }
        return ToolResultEnvelope.fromJson(text);
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
