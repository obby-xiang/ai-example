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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DC-14 T1/T2 用例（{@code SpToolCallingManager} 的两条新口径，无 Redis、无 Spring 容器）。
 *
 * <ol>
 * <li><b>T1 防线③执行兜底</b>：请求侧没披露（越 scope）的工具调用，即使上游真的发了
 * {@code tool_calls}，也<b>不执行任何副作用</b>（不认领、不执行、不记台账），
 * 只把结构化错误结果（{@link SpToolCallingManager#SCOPE_VIOLATION_CODE}）回填模型；</li>
 * <li><b>T2 两条通道分离</b>：同一个工具结果，回填模型的那一份按
 * {@code app.ai.tool-result.max-rows|max-chars} 截断且带截断标记；进前端帧的那一份是全文。</li>
 * </ol>
 */
class SpToolCallingManagerScopeGuardTest {

    private static final String RUN_ID = "run-scope-guard";

    private static final String TOOL_CALL_ID = "call-guard-1";

    private final RunStore store = mock(RunStore.class);

    private final RunRegistry registry = mock(RunRegistry.class);

    private final ConfirmGate gate = mock(ConfirmGate.class);

    private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

    private final MessageJsonCodec codec = mock(MessageJsonCodec.class);

    private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

    private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

    private final SseChatEmitter emitter = mock(SseChatEmitter.class);

    private final AiProperties properties = new AiProperties();

    private final ToolResultLimiter limiter = new ToolResultLimiter(this.properties);

    private final SpToolCallingManager manager = new SpToolCallingManager(this.store, this.registry, this.gate,
            this.toolRegistry, this.codec, this.beacon, this.cancellations, this.limiter, List.of());

    /**
     * 本类各用例都以"上下文匹配"为默认（防线③放行），越 scope 的用例再单独把判据打回 false ——
     * 否则 Mockito 的 boolean 默认值 false 会让所有工具都被拦，测不到各自关心的路径。
     */
    @org.junit.jupiter.api.BeforeEach
    void defaultScopeGuardAllows() {
        when(this.toolRegistry.matchesContext(anyString(), any(AiContext.class))).thenReturn(true);
    }

    // ── T1 ──────────────────────────────────────────────────────────────────

    @Test
    void outOfScopeToolIsBlockedWithoutAnySideEffect() {
        stubRun();
        // 本轮上下文是任务中心（page:tasks，无 taskType/step）
        when(this.store.get(RUN_ID)).thenReturn(snapshot(AiContext.of("tasks", null, null, null, null)));
        // 防线③判据：DANGER 的 start_import 只在 task:IMPORT/IMPORT 下披露 ⇒ 本轮不匹配
        when(this.toolRegistry.channelOf("start_import")).thenReturn(ToolMeta.Channel.BACKEND);
        when(this.toolRegistry.riskOf("start_import")).thenReturn(ToolMeta.RiskLevel.DANGER);
        when(this.toolRegistry.matchesContext(eq("start_import"), any(AiContext.class))).thenReturn(false);

        ToolExecutionResult result = this.manager.executeToolCalls(prompt("start_import", "{\"taskId\":7}"),
                response("start_import", "{\"taskId\":7}"));

        String modelText = toolText(result);
        // ① 模型收到结构化错误语义（不是"成功"、也不是空文本）
        assertThat(modelText).startsWith(SpToolCallingManager.SCOPE_VIOLATION_CODE);
        assertThat(modelText).contains("未执行").contains("page=tasks");
        // ② 副作用为零：既不挂确认门，也不认领、不执行、不记台账
        verify(this.gate, never()).awaitDecision(anyString(), any(PendingToolCall.class));
        verify(this.store, never()).claimExecution(anyString(), anyString(), anyString());
        verify(this.store, never()).appendLedger(anyString(), any(PendingToolCall.class), anyString());
        // ③ 待决条目落库为 BLOCKED（跨进程续跑不会把它当"待外部输入"，也不会补执行）
        PendingToolCall saved = capturedPending();
        assertThat(saved.getStatus()).isEqualTo(PendingToolCall.BLOCKED);
        assertThat(saved.getReason()).isEqualTo(SpToolCallingManager.SCOPE_VIOLATION_CODE);
        assertThat(saved.isExecuted()).isFalse();
        // ④ 前端帧如实告知"未执行"
        verify(this.emitter).toolResult(any(PendingToolCall.class), eq(false), eq(false), eq(modelText));
    }

    @Test
    void inScopeToolStillGoesThroughConfirmGate() {
        stubRun();
        when(this.store.get(RUN_ID)).thenReturn(snapshot(AiContext.of("import", "IMPORT", "IMPORT", 7L, null)));
        when(this.toolRegistry.channelOf("start_import")).thenReturn(ToolMeta.Channel.BACKEND);
        when(this.toolRegistry.riskOf("start_import")).thenReturn(ToolMeta.RiskLevel.DANGER);
        when(this.toolRegistry.matchesContext(eq("start_import"), any(AiContext.class))).thenReturn(true);
        when(this.gate.awaitDecision(eq(RUN_ID), any(PendingToolCall.class))).thenAnswer(invocation -> {
            PendingToolCall pending = invocation.getArgument(1);
            pending.setStatus(PendingToolCall.REJECTED);
            pending.setExecuted(false);
            pending.setResultText("用户拒绝了该操作，工具未执行。");
            return pending;
        });

        this.manager.executeToolCalls(prompt("start_import", "{\"taskId\":7}"),
                response("start_import", "{\"taskId\":7}"));

        // 上下文匹配 ⇒ 仍走确认门（防线③只拦越权，不改变既有 HITL 语义）
        verify(this.gate).awaitDecision(eq(RUN_ID), any(PendingToolCall.class));
    }

    // ── T2 ──────────────────────────────────────────────────────────────────

    @Test
    void longResultIsTruncatedForModelButUntouchedInFrame() {
        stubRun();
        when(this.toolRegistry.channelOf("list_tasks")).thenReturn(ToolMeta.Channel.BACKEND);
        when(this.toolRegistry.riskOf("list_tasks")).thenReturn(ToolMeta.RiskLevel.READ);
        when(this.toolRegistry.matchesContext(eq("list_tasks"), any(AiContext.class))).thenReturn(true);
        // 走"台账复用"路径：结果文本直接来自台账（无需真的委托官方执行）
        String big = IntStream.rangeClosed(1, 250)
            .mapToObj(i -> "- 第 " + i + " 行：字段值 " + i)
            .reduce((a, b) -> a + "\n" + b)
            .orElseThrow();
        Map<String, Object> ledger = new LinkedHashMap<>();
        ledger.put("resultText", big);
        ledger.put("executedBy", "instance-old");
        when(this.store.ledgerFor(RUN_ID, TOOL_CALL_ID)).thenReturn(ledger);

        ToolExecutionResult result = this.manager.executeToolCalls(prompt("list_tasks", "{}"),
                response("list_tasks", "{}"));

        String modelText = toolText(result);
        // ① 给模型的那一份被截断：只留前 200 行 + 截断标记（原始行数可见）
        assertThat(modelText).contains(ToolResultLimiter.TRUNCATION_MARKER);
        assertThat(modelText).contains("原 250 行");
        assertThat(modelText.lines().count()).isLessThan(big.lines().count());
        assertThat(modelText).doesNotContain("第 250 行");
        // ② 给前端的那一份（帧）是全文，未被裁剪
        ArgumentCaptor<String> frameText = ArgumentCaptor.forClass(String.class);
        verify(this.emitter).toolResult(any(PendingToolCall.class), eq(true), eq(true), frameText.capture());
        assertThat(frameText.getValue()).isEqualTo(big);
        assertThat(frameText.getValue()).doesNotContain(ToolResultLimiter.TRUNCATION_MARKER);
    }

    // ── 桩与辅助 ────────────────────────────────────────────────────────────

    private void stubRun() {
        when(this.cancellations.isCancelled(RUN_ID)).thenReturn(false);
        when(this.store.get(RUN_ID)).thenReturn(snapshot(AiContext.of("export", "EXPORT", "EXPORT", 1L, null)));
        when(this.store.runKey(RUN_ID)).thenReturn("ai:run:" + RUN_ID);
        when(this.store.pendingKey(RUN_ID)).thenReturn("ai:pending:" + RUN_ID);
        when(this.store.instanceId()).thenReturn("test-instance");
        when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
    }

    private static RunSnapshot snapshot(AiContext context) {
        RunSnapshot snapshot = new RunSnapshot();
        snapshot.setRunId(RUN_ID);
        snapshot.setStatus(RunSnapshot.RUNNING);
        snapshot.setContext(context.toMap());
        return snapshot;
    }

    private static Prompt prompt(String tool, String args) {
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
            .toolContext(Map.of("runId", RUN_ID))
            .build();
        return new Prompt(List.of(new UserMessage("hi")), options);
    }

    private static ChatResponse response(String tool, String args) {
        AssistantMessage assistant = AssistantMessage.builder()
            .toolCalls(List.of(new AssistantMessage.ToolCall(TOOL_CALL_ID, "function", tool, args)))
            .build();
        return new ChatResponse(List.of(new Generation(assistant)));
    }

    private PendingToolCall capturedPending() {
        ArgumentCaptor<PendingToolCall> captor = ArgumentCaptor.forClass(PendingToolCall.class);
        verify(this.store).putPending(eq(RUN_ID), captor.capture());
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
