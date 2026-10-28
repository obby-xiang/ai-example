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
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.service.TaskService;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.ReasoningAwareOpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionChunk;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionFinishReason;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionMessage;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionRequest;
import org.springframework.ai.openai.api.OpenAiApi.FunctionTool;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.retry.support.RetryTemplate;
import reactor.core.publisher.Flux;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>官方模型循环 × 批间动态披露</b>：第一跳 {@code navigate_to} 成功之后，<b>第二跳上游请求的
 * {@code tools} 数组本身</b>必须换成新披露面（{@code select_definitions}/{@code confirm_step} 出现），
 * 而不是只有 {@link SpToolCallingManager#resolveToolDefinitions} 被单独调用时才对。
 *
 * <p>为什么这条缝值得单独证明：{@code resolveToolDefinitions} 的单测（
 * {@link SpToolCallingManagerDynamicDisclosureTest}）证明的是"按当前快照重算"这一函数；但生产链路上
 * 真正决定上游收到什么的是 Spring AI 1.1.7 官方循环 —— {@code OpenAiChatModel#internalStream} 在
 * 工具批结束后执行 {@code new Prompt(conversationHistory, prompt.getOptions())} 再递归一次，
 * 该 prompt 的 options 仍是<b>请求边界那一份冻结 callbacks</b>。若这条递归路径不再调
 * {@code createRequest}/{@code resolveToolDefinitions}，或 options 被换掉，函数级单测全绿而线上第二跳
 * 依旧只看得见 {@code navigate_to}（模型因此"导航完不会点下一步"）。本用例在<b>真实补丁模型</b>
 * （{@link ReasoningAwareOpenAiChatModel}）+ <b>上游桩</b>（mock {@code OpenAiApi}）+ <b>真实
 * {@link SpToolCallingManager}</b> 的组合上抓两发请求体，断言的是线上真正发出去的那份 {@code tools}。
 *
 * <p>同时守住两条既有口径不变：
 * <ul>
 * <li><b>批内冻结</b>：第一跳的披露面仍是请求边界那份（只有 {@code navigate_to}），导航成功不会让
 * 同一批的工具集合"提前"变宽 —— 放宽只发生在<b>下一跳</b>；</li>
 * <li><b>HITL 不改</b>：{@code navigate_to} 仍是 FRONTEND 通道（挂起等前端回执），披露面变化不碰
 * 确认门；{@code start_export} 在 SELECT_DEFS 步仍未披露。</li>
 * </ul>
 *
 * <p>披露面用<b>真实 {@link ToolRegistry}</b>（注解 scope 参与判定），工具桩直接复用
 * {@link SpToolCallingManagerDynamicDisclosureTest.WizardTools}；只有领域侧（{@code TaskService}）
 * 与存储侧（{@code RunStore}）是替身 —— 与生产同构的最小装配。
 */
class ModelLoopHopToolDisclosureTest {

    private static final String RUN_ID = "run-model-loop-hop";

    private static final String NAV_CALL_ID = "call-nav-1";

    /** 请求边界：定义列表页，任务类工具（select/confirm/…）一律不在披露面。 */
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

    /** “Redis 里的那一份”快照：{@code get} 给副本、{@code save} 覆盖 —— 上下文没写回去第二跳就会丢。 */
    private RunSnapshot stored;

    private AnnotationConfigApplicationContext registryContext;

    private ToolRegistry toolRegistry;

    private SpToolCallingManager manager;

    @BeforeEach
    void setUp() {
        this.registryContext = new AnnotationConfigApplicationContext();
        this.registryContext.registerBean(SpToolCallingManagerDynamicDisclosureTest.WizardTools.class);
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
        this.manager.setContextRefresher(new RunContextRefresher(this.taskService));
    }

    @AfterEach
    void tearDown() {
        if (this.registryContext != null) {
            this.registryContext.close();
        }
    }

    @Test
    void secondHopUpstreamRequestCarriesTheRefreshedToolArray() {
        seedSnapshot(DEFINITIONS_PAGE);
        this.taskState.set(task(7L, Task.TaskType.EXPORT, "SELECT_DEFS", Task.TaskStatus.ACTIVE));
        frontendSucceeds();

        OpenAiApi api = mock(OpenAiApi.class);
        ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        // 上游桩：第一发让模型"调用 navigate_to"，第二发（若有）正常作答 —— 两发都被抓下来
        when(api.chatCompletionStream(captor.capture(), any()))
            .thenReturn(Flux.just(toolCallChunk(NAV_CALL_ID, "navigate_to", "{\"page\":\"export\",\"taskId\":7}")),
                    Flux.just(textChunk("已打开导出页面")));

        ReasoningAwareOpenAiChatModel model = new ReasoningAwareOpenAiChatModel(api,
                OpenAiChatOptions.builder().model("deepseek-flash").build(), this.manager,
                RetryTemplate.defaultInstance(), ObservationRegistry.NOOP, 7L);

        // 请求边界：生产中由 ResilientChatService 组装的 options（冻结 callbacks + runId 通道）
        OpenAiChatOptions boundary = OpenAiChatOptions.builder()
            .model("deepseek-flash")
            .toolCallbacks(this.toolRegistry.forContext(DEFINITIONS_PAGE))
            .toolContext(Map.of("runId", RUN_ID))
            .build();

        assertThat(model.stream(new Prompt(List.of(new UserMessage("把月度导出打开")), boundary))
            .collectList()
            .block()).as("官方循环应完整跑完两跳").isNotNull();

        verify(api, times(2)).chatCompletionStream(any(), any());
        List<ChatCompletionRequest> requests = captor.getAllValues();
        assertThat(requests).as("第一跳 navigate_to → 工具批 → 第二跳复答").hasSize(2);

        // 第一跳：仍是请求边界那份（批内冻结）—— 只有 * 级工具，向导动作一个都没有
        assertThat(toolNames(requests.get(0))).as("批内冻结：导航成功之前不得提前放宽")
            .containsExactly("navigate_to");

        // 第二跳：官方循环自己发出的上游请求 —— tools 数组必须按刷新后的快照重算
        assertThat(toolNames(requests.get(1)))
            .as("第二跳上游请求的 tools 必须已换成新披露面（不是只调了 resolveToolDefinitions）")
            .contains("navigate_to", "select_definitions", "confirm_step", "check_job_status")
            // 步骤仍是 SELECT_DEFS：QUERY_COND 才用的 set_condition 与 EXPORT 专属的 start_export
            // 都不提前出现（正控：按上下文算，不是"总是全带上"；2026-10-28 步骤披露收口）
            .doesNotContain("set_condition", "start_export");

        // 第二跳确实带着第一跳的工具结果：证明这是官方循环的工具后续跳，而不是另起一次请求
        assertThat(requests.get(1).messages())
            .filteredOn(message -> message.role() == ChatCompletionMessage.Role.TOOL)
            .singleElement()
            .satisfies(message -> {
                assertThat(message.toolCallId()).isEqualTo(NAV_CALL_ID);
                assertThat(message.content()).contains("前端已执行：navigate_to");
            });

        // HITL/通道不变：navigate_to 走 FRONTEND 通道挂起等回执（披露面变化不碰确认门）
        verify(this.gate, times(1)).awaitFrontendResult(eq(RUN_ID), any(PendingToolCall.class));
    }

    // ── 桩与辅助 ────────────────────────────────────────────────────────────

    private void seedSnapshot(AiContext context) {
        RunSnapshot snapshot = new RunSnapshot();
        snapshot.setRunId(RUN_ID);
        snapshot.setSessionId("sess-model-loop");
        snapshot.setStatus(RunSnapshot.RUNNING);
        snapshot.setContext(context.toMap());
        this.stored = snapshot;
    }

    /** 前端回执成功（navigate_to 是 FRONTEND 通道：工具体不执行，等前端把结果回灌）。 */
    private void frontendSucceeds() {
        when(this.gate.awaitFrontendResult(eq(RUN_ID), any(PendingToolCall.class))).thenAnswer(invocation -> {
            PendingToolCall pending = invocation.getArgument(1);
            pending.setStatus(PendingToolCall.FRONTEND_RESULT);
            pending.setOutcome(ToolOutcome.OK.wire());
            pending.setResultText("前端已执行：" + pending.getName());
            return pending;
        });
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

    /** 上游请求体里的 {@code tools[].function.name}（线上真正下发的那一份）。 */
    private static List<String> toolNames(ChatCompletionRequest request) {
        List<FunctionTool> tools = request.tools();
        if (tools == null) {
            return List.of();
        }
        return tools.stream().map(tool -> tool.getFunction().getName()).toList();
    }

    /** 一个携带完整工具调用的分片（finish_reason=tool_calls，与 deepseek 的线格式同形）。 */
    private static ChatCompletionChunk toolCallChunk(String callId, String name, String arguments) {
        ChatCompletionMessage.ToolCall toolCall = new ChatCompletionMessage.ToolCall(callId, "function",
                new ChatCompletionMessage.ChatCompletionFunction(name, arguments));
        ChatCompletionMessage delta = new ChatCompletionMessage("", ChatCompletionMessage.Role.ASSISTANT, null,
                null, List.of(toolCall), null, null, null, null);
        return chunk(ChatCompletionFinishReason.TOOL_CALLS, delta);
    }

    /** 一个纯正文终帧（无工具调用 ⇒ 官方循环收尾）。 */
    private static ChatCompletionChunk textChunk(String content) {
        ChatCompletionMessage delta = new ChatCompletionMessage(content, ChatCompletionMessage.Role.ASSISTANT, null,
                null, null, null, null, null, null);
        return chunk(ChatCompletionFinishReason.STOP, delta);
    }

    private static ChatCompletionChunk chunk(ChatCompletionFinishReason finishReason, ChatCompletionMessage delta) {
        ChatCompletionChunk.ChunkChoice choice = new ChatCompletionChunk.ChunkChoice(finishReason, 0, delta, null);
        return new ChatCompletionChunk("cmpl-hop", List.of(choice), 1750000000L, "deepseek-flash", null, null,
                "chat.completion.chunk", null);
    }

    /** 模拟 Redis：{@code get} 给副本、{@code save} 覆盖 —— 上下文没写回去就会在第二跳丢失。 */
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
