package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S4.4e 用例（裁决⑥）：启动类作业工具（导出/预检查/导入/发布）必须经确认门挂起（FR-5.3）。
 *
 * <p>本用例在<b>无 Redis、无 Spring 容器</b>的条件下走
 * {@link SpToolCallingManager#executeToolCalls} 的真实分支：只把外置状态（{@link RunStore}）、
 * 帧出口（{@link SseChatEmitter}）与确认门（{@link ConfirmGate}）做成桩。
 * 这样锁住的是"风险元数据 → 挂起种类 → 走确认门而不是直接执行"这条链路本身，
 * 而不是某个工具名清单（清单在 {@code ToolRegistryDisclosureTest} 里按元数据断言）。
 *
 * <p>三条断言（对应 FR-5.3 的三种结局都从"先挂起"开始）：
 * <ol>
 * <li>四个启动类工具都被登记为 {@code KIND_CONFIRM}（确认门）；</li>
 * <li>只读/创建类工具仍是 {@code KIND_BACKEND}（直接执行，不给人添确认负担）；</li>
 * <li>确认门判为"拒绝"时，管理器<b>不会</b>去执行工具（{@code claimExecution} 未被调用），
 * 且回填给模型的正是确认门给出的"未执行 + 原因"文本。</li>
 * </ol>
 */
class ConfirmGateWiringTest {

    /** 需要人工确认的启动类工具（FR-5.3 清单：启动导出/检查/导入/发布）。 */
    private static final List<String> GATED_TOOLS = List.of("start_export", "start_precheck", "start_import",
            "start_publish");

    private final RunStore store = mock(RunStore.class);

    private final RunRegistry registry = mock(RunRegistry.class);

    private final ConfirmGate gate = mock(ConfirmGate.class);

    private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

    private final MessageJsonCodec codec = mock(MessageJsonCodec.class);

    private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

    private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

    private final SseChatEmitter emitter = mock(SseChatEmitter.class);

    private final SpToolCallingManager manager = new SpToolCallingManager(this.store, this.registry, this.gate,
            this.toolRegistry, this.codec, this.beacon, this.cancellations, new ToolResultLimiter(new AiProperties()),
            List.of());

    /** B2：确认门三结局的受控工具替身（"放行 → 真的执行"需要官方循环能解析到回调）。 */
    private final FakeStartImportTool fakeStartImport = new FakeStartImportTool();

    /**
     * 默认"上下文匹配"（防线③放行）：本用例关心的是 HITL 判定，
     * 而 {@code ToolRegistry} 是桩，Mockito 的 boolean 默认 false 会让所有工具都被防线③拦下。
     * 防线③本身的行为与判据同源性由 {@code SpToolCallingManagerScopeGuardTest} /
     * {@code ToolRegistryDisclosureTest} 覆盖。
     */
    @org.junit.jupiter.api.BeforeEach
    void defaultScopeGuardAllows() {
        when(this.toolRegistry.matchesContext(anyString(), any(com.example.configmgr.ai.tool.AiContext.class)))
            .thenReturn(true);
    }

    @Test
    void startToolsSuspendAtConfirmGate() {
        for (String tool : GATED_TOOLS) {
            when(this.toolRegistry.channelOf(tool)).thenReturn(ToolMeta.Channel.BACKEND);
            when(this.toolRegistry.riskOf(tool)).thenReturn(ToolMeta.RiskLevel.DANGER);
        }
        when(this.cancellations.isCancelled(RUN_ID)).thenReturn(false);
        when(this.store.get(RUN_ID)).thenReturn(snapshot());
        when(this.store.runKey(RUN_ID)).thenReturn("ai:run:" + RUN_ID);
        when(this.store.pendingKey(RUN_ID)).thenReturn("ai:pending:" + RUN_ID);
        when(this.store.instanceId()).thenReturn("test-instance");
        when(this.registry.of(RUN_ID)).thenReturn(this.emitter);

        // 确认门判"拒绝"：以"未执行 + 原因"回填，且绝不进入执行路径
        when(this.gate.awaitDecision(eq(RUN_ID), any(PendingToolCall.class))).thenAnswer(invocation -> {
            PendingToolCall pending = invocation.getArgument(1);
            pending.setStatus(PendingToolCall.REJECTED);
            pending.setExecuted(false);
            pending.setResultText("用户拒绝了该操作，工具未执行。拒绝原因：先不导出");
            return pending;
        });

        ToolExecutionResult result = this.manager.executeToolCalls(prompt("start_export", "{\"taskId\":7}"),
                response("start_export", "{\"taskId\":7}"));

        // ① 挂起条目带 CONFIRM 种类（外置帧里就是这么发给前端的）
        assertThat(pendingsToldToFrontend()).extracting(PendingToolCall::getKind)
                .containsExactly(PendingToolCall.KIND_CONFIRM);
        // ② 确认门被调用（挂起而不是直接执行）
        verify(this.gate).awaitDecision(eq(RUN_ID), any(PendingToolCall.class));
        // ③ 拒绝 → 未认领执行；回填文本原样进 tool 消息
        verify(this.store, never()).claimExecution(anyString(), anyString(), anyString());
        assertThat(toolText(result)).isEqualTo("用户拒绝了该操作，工具未执行。拒绝原因：先不导出");
    }

    @Test
    void readAndCreateToolsExecuteDirectly() {
        when(this.cancellations.isCancelled(RUN_ID)).thenReturn(false);
        when(this.store.get(RUN_ID)).thenReturn(snapshot());
        when(this.store.runKey(RUN_ID)).thenReturn("ai:run:" + RUN_ID);
        when(this.store.pendingKey(RUN_ID)).thenReturn("ai:pending:" + RUN_ID);
        when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
        when(this.store.ledgerFor(eq(RUN_ID), anyString())).thenReturn(null);
        when(this.store.claimExecution(eq(RUN_ID), anyString(), anyString())).thenReturn(true);
        when(this.store.instanceId()).thenReturn("test-instance");

        for (String tool : List.of("list_tasks", "create_task", "get_workspace_state")) {
            when(this.toolRegistry.channelOf(tool)).thenReturn(ToolMeta.Channel.BACKEND);
            when(this.toolRegistry.riskOf(tool)).thenReturn(ToolMeta.RiskLevel.READ);
        }
        when(this.toolRegistry.riskOf("create_task")).thenReturn(ToolMeta.RiskLevel.WRITE);

        this.manager.executeToolCalls(prompt("create_task", "{\"type\":\"EXPORT\"}"),
                response("create_task", "{\"type\":\"EXPORT\"}"));

        assertThat(pendingsToldToFrontend()).extracting(PendingToolCall::getKind)
                .containsExactly(PendingToolCall.KIND_BACKEND);
        verify(this.gate, never()).awaitDecision(anyString(), any(PendingToolCall.class));
        // 直接执行：认领 + 委托官方执行（作业/任务类工具不必人工确认，任务创建可逆）
        verify(this.store).claimExecution(eq(RUN_ID), anyString(), anyString());
    }

    // ── B2（DC-14）：start_import 确认门三结局（与 start_export 同机制，补放行/超时用例） ──

    /**
     * 放行：确认门返回 {@code APPROVED} → 管理器进入执行路径（认领 + 台账），
     * 且回填模型的是工具本身的输出（不是"未执行"文本）。
     */
    @Test
    void startImportExecutesAfterApproval() {
        stubCommon();
        when(this.toolRegistry.channelOf("start_import")).thenReturn(ToolMeta.Channel.BACKEND);
        when(this.toolRegistry.riskOf("start_import")).thenReturn(ToolMeta.RiskLevel.DANGER);
        when(this.store.ledgerFor(eq(RUN_ID), anyString())).thenReturn(null);
        when(this.store.claimExecution(eq(RUN_ID), anyString(), anyString())).thenReturn(true);
        // 确认门：放行（状态 APPROVED，未执行 —— 执行由循环内的认领负责）
        when(this.gate.awaitDecision(eq(RUN_ID), any(PendingToolCall.class))).thenAnswer(invocation -> {
            PendingToolCall pending = invocation.getArgument(1);
            pending.setStatus(PendingToolCall.APPROVED);
            pending.setExecuted(false);
            return pending;
        });

        ToolExecutionResult result = this.manager.executeToolCalls(prompt("start_import", "{\"taskId\":7}"),
                response("start_import", "{\"taskId\":7}"));

        assertThat(this.fakeStartImport.invocations.get()).as("放行后工具被真实执行").isEqualTo(1);
        verify(this.store).claimExecution(eq(RUN_ID), anyString(), anyString());
        verify(this.store).appendLedger(eq(RUN_ID), any(PendingToolCall.class), anyString());
        assertThat(toolText(result)).contains("导入作业已启动（作业 #4242）");
    }

    /**
     * 超时：确认门返回 {@code TIMEOUT}（自动取消）→ 与拒绝同一路径：不认领、不执行、
     * 回填"未执行"语义的文本（三个结局里唯一由系统而非人给出的那个）。
     */
    @Test
    void startImportIsNotExecutedOnTimeout() {
        stubCommon();
        when(this.toolRegistry.channelOf("start_import")).thenReturn(ToolMeta.Channel.BACKEND);
        when(this.toolRegistry.riskOf("start_import")).thenReturn(ToolMeta.RiskLevel.DANGER);
        when(this.gate.awaitDecision(eq(RUN_ID), any(PendingToolCall.class))).thenAnswer(invocation -> {
            PendingToolCall pending = invocation.getArgument(1);
            pending.setStatus(PendingToolCall.TIMEOUT);
            pending.setExecuted(false);
            pending.setResultText("确认等待超过 120 秒，系统已自动取消该操作（工具未执行）。");
            return pending;
        });

        ToolExecutionResult result = this.manager.executeToolCalls(prompt("start_import", "{\"taskId\":7}"),
                response("start_import", "{\"taskId\":7}"));

        assertThat(this.fakeStartImport.invocations.get()).as("超时后工具绝不执行").isZero();
        verify(this.store, never()).claimExecution(anyString(), anyString(), anyString());
        verify(this.store, never()).appendLedger(anyString(), any(PendingToolCall.class), anyString());
        assertThat(toolText(result)).contains("系统已自动取消该操作（工具未执行）");
    }

    /** 三种结局共用的桩（外置状态/帧出口/取消标志）。 */
    private void stubCommon() {
        when(this.cancellations.isCancelled(RUN_ID)).thenReturn(false);
        when(this.store.get(RUN_ID)).thenReturn(snapshot());
        when(this.store.runKey(RUN_ID)).thenReturn("ai:run:" + RUN_ID);
        when(this.store.pendingKey(RUN_ID)).thenReturn("ai:pending:" + RUN_ID);
        when(this.store.instanceId()).thenReturn("test-instance");
        when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
    }

    private static final String RUN_ID = "run-confirm-wiring";

    private static RunSnapshot snapshot() {
        RunSnapshot snapshot = new RunSnapshot();
        snapshot.setRunId(RUN_ID);
        snapshot.setStatus(RunSnapshot.RUNNING);
        return snapshot;
    }

    private Prompt prompt(String tool, String args) {
        // 官方循环按**名字**从 options.getToolCallbacks() 解析回调 —— 这里挂上受控替身，
        // "放行 → 执行"的用例才能观测到工具真的被调用（其余用例的工具名不在回调集里也不影响判定）
        List<ToolCallback> callbacks = List.of(MethodToolCallbackProvider.builder()
            .toolObjects(this.fakeStartImport)
            .build()
            .getToolCallbacks());
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
            .toolCallbacks(callbacks)
            .toolContext(Map.of("runId", RUN_ID))
            .build();
        return new Prompt(List.of(new UserMessage("hi")), options);
    }

    private static ChatResponse response(String tool, String args) {
        AssistantMessage assistant = AssistantMessage.builder()
            .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", tool, args)))
            .build();
        return new ChatResponse(List.of(new Generation(assistant)));
    }

    @SuppressWarnings("unchecked")
    private List<PendingToolCall> pendingsToldToFrontend() {
        ArgumentCaptor<List<PendingToolCall>> captor = ArgumentCaptor.forClass(List.class);
        verify(this.emitter).suspended(captor.capture(), anyString(), anyString(), anyString());
        return captor.getValue();
    }

    private static String toolText(ToolExecutionResult result) {
        return result.conversationHistory()
            .stream()
            .filter(org.springframework.ai.chat.messages.ToolResponseMessage.class::isInstance)
            .map(org.springframework.ai.chat.messages.ToolResponseMessage.class::cast)
            .flatMap(message -> message.getResponses().stream())
            .map(org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse::responseData)
            .findFirst()
            .orElseThrow();
    }

}
