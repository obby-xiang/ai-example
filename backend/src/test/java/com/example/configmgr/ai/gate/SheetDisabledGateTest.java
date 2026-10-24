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
import com.example.configmgr.ai.tool.SheetAudit;
import com.example.configmgr.ai.tool.SheetSwitches;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolResultEnvelope;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.example.configmgr.common.ErrorCodes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolExecutionResult;

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
 * 表格开关关掉后的执行面（AI 表格工具治理方案 2026-10-22 §2.4）。
 *
 * <h2>为什么这条必须有独立用例</h2>
 * 开关的主要出口是"不披露"（模型看不见），但**旧缓存调用**（上游缓存了旧工具列表 / 注入 / 幻觉）
 * 仍可能到达执行段。此时若复用 {@code SCOPE_NOT_DISCLOSED}，前端卡面会渲染成「不在披露范围内」——
 * 用户会以为自己点错了页面，而真相是"管理员把功能关了，切页面没用"。故本类钉死：
 * <ul>
 * <li>码 = {@code SHEET_DISABLED}（不是 {@code SCOPE_NOT_DISCLOSED}）；</li>
 * <li>结局 = {@code failed}（不是 {@code blocked}），文案说清"未读任何数据 / 未做任何修改"；</li>
 * <li>模型侧带可行动提示"不要重试"（开关是进程内绑定，换参数重试必然同样失败）；</li>
 * <li>零副作用：不挂确认门、不认领、不执行。</li>
 * </ul>
 */
class SheetDisabledGateTest {

    private static final String RUN_ID = "run-sheet-disabled";

    private static final String CALL_ID = "call-sheet-1";

    private final RunStore store = mock(RunStore.class);

	{
		// C2：生产读侧已改为 pendingLenient/pendingsLenient；Mockito 桩只需把非抛读口委托给原桩。
		when(this.store.pendingLenient(org.mockito.ArgumentMatchers.anyString(),
				org.mockito.ArgumentMatchers.anyString()))
			.thenAnswer(invocation -> this.store.pending((String) invocation.getArgument(0),
					(String) invocation.getArgument(1)));
		when(this.store.pendingsLenient(org.mockito.ArgumentMatchers.anyString()))
			.thenAnswer(invocation -> this.store.pendings((String) invocation.getArgument(0)));
	}


    private final RunRegistry registry = mock(RunRegistry.class);

    private final ConfirmGate gate = mock(ConfirmGate.class);

    private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

    private final MessageJsonCodec codec = mock(MessageJsonCodec.class);

    private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

    private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

    private final SseChatEmitter emitter = mock(SseChatEmitter.class);

    private final AiProperties properties = new AiProperties();

    private final SheetAudit audit = new SheetAudit(new SheetSwitches(this.properties), mock(RunStore.class));

    private final SpToolCallingManager manager = new SpToolCallingManager(this.store, this.registry, this.gate,
            this.toolRegistry, this.codec, this.beacon, this.cancellations,
            new ToolResultLimiter(this.properties), List.of());

    @BeforeEach
    void wireAudit() {
        this.manager.setSheetAudit(this.audit);
    }

    @BeforeEach
    void stubRun() {
        when(this.cancellations.isCancelled(RUN_ID)).thenReturn(false);
        when(this.store.get(RUN_ID)).thenReturn(snapshot());
        when(this.store.runKey(RUN_ID)).thenReturn("ai:run:" + RUN_ID);
        when(this.store.pendingKey(RUN_ID)).thenReturn("ai:pending:" + RUN_ID);
        when(this.store.instanceId()).thenReturn("test-instance");
        when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
        // 缺省：上下文匹配（防线③放行），各用例再按需把"关闸"那一维打开
        when(this.toolRegistry.matchesContext(anyString(), any(AiContext.class))).thenReturn(true);
        when(this.toolRegistry.sheetDisabled(anyString())).thenReturn(false);
    }

    @Test
    void writeGateClosedBlocksWriteToolWithItsOwnCode() {
        when(this.toolRegistry.matchesContext(eq("replace_values"), any(AiContext.class))).thenReturn(false);
        when(this.toolRegistry.sheetDisabled("replace_values")).thenReturn(true);
        when(this.toolRegistry.channelOf("replace_values")).thenReturn(ToolMeta.Channel.FRONTEND);
        when(this.toolRegistry.riskOf("replace_values")).thenReturn(ToolMeta.RiskLevel.WRITE);

        ToolExecutionResult result = execute("replace_values", "{\"defCode\":\"CURRENCY\"}");

        ToolResultEnvelope envelope = ToolResultEnvelope.fromJson(toolText(result));
        assertThat(envelope.outcome()).isEqualTo(ToolOutcome.FAILED.wire());
        assertThat(envelope.outcome()).isNotEqualTo(ToolOutcome.BLOCKED.wire());
        assertThat(envelope.code()).isEqualTo(ErrorCodes.SHEET_DISABLED).isEqualTo("SHEET_DISABLED");
        assertThat(envelope.ok()).isFalse();
        assertThat(envelope.retryable()).as("开关重试无用 ⇒ 不可重试").isFalse();
        assertThat(envelope.message()).contains("SHEET_DISABLED")
                .contains("表格写入功能已被管理员关闭")
                .contains("本次未做任何修改")
                .contains("不要重试")
                .contains("write-enabled");
        // 零副作用
        verify(this.gate, never()).awaitFrontendResult(anyString(), any(PendingToolCall.class));
        verify(this.store, never()).claimExecution(anyString(), anyString(), anyString());
        // 条目落单列状态：outcome=failed（不是 blocked），码为 SHEET_DISABLED
        PendingToolCall saved = capturedPending();
        assertThat(saved.getStatus()).isEqualTo(PendingToolCall.SHEET_DISABLED);
        assertThat(saved.getOutcome()).isEqualTo(ToolOutcome.FAILED.wire());
        assertThat(saved.effectiveCode()).isEqualTo(ToolOutcome.CODE_SHEET_DISABLED);
        assertThat(saved.effectiveOutcome()).isEqualTo(ToolOutcome.FAILED);
        assertThat(saved.isExecuted()).isFalse();
        assertThat(saved.getStatus()).isNotEqualTo(PendingToolCall.BLOCKED);
        // /health.sheet 的 disabledRejects（应恒 0 的那一项）：关闸拦截必须被计数，
        // 否则"有旧缓存调用"这件事在运维面上永远看不见
        assertThat(this.audit.metrics()).containsEntry("disabledRejects", 1L);
    }

    @Test
    void readGateClosedSaysNoDataWasRead() {
        when(this.toolRegistry.matchesContext(eq("read_sheet_summary"), any(AiContext.class))).thenReturn(false);
        when(this.toolRegistry.sheetDisabled("read_sheet_summary")).thenReturn(true);
        when(this.toolRegistry.channelOf("read_sheet_summary")).thenReturn(ToolMeta.Channel.FRONTEND);
        when(this.toolRegistry.riskOf("read_sheet_summary")).thenReturn(ToolMeta.RiskLevel.READ);

        ToolResultEnvelope envelope = ToolResultEnvelope.fromJson(
                toolText(execute("read_sheet_summary", "{\"defCode\":\"CURRENCY\"}")));

        assertThat(envelope.code()).isEqualTo("SHEET_DISABLED");
        assertThat(envelope.message()).contains("表格读取功能已被管理员关闭")
                .contains("本次未读任何数据")
                .contains("read-enabled");
    }

    /** 切页面能解决的仍报越 scope：两码不得互相吞并。 */
    @Test
    void scopeViolationKeepsItsOwnCode() {
        when(this.toolRegistry.matchesContext(eq("read_sheet_summary"), any(AiContext.class))).thenReturn(false);
        when(this.toolRegistry.sheetDisabled("read_sheet_summary")).thenReturn(false);

        ToolResultEnvelope envelope = ToolResultEnvelope.fromJson(
                toolText(execute("read_sheet_summary", "{\"defCode\":\"CURRENCY\"}")));

        assertThat(envelope.code()).isEqualTo(SpToolCallingManager.SCOPE_VIOLATION_CODE);
        assertThat(envelope.outcome()).isEqualTo(ToolOutcome.BLOCKED.wire());
    }

    /**
     * 双档工具（normalize_column）在「写开读关」下被挡：措辞必须点名<b>实际关掉的那一侧</b>。
     *
     * <p>旧实现按 {@code SheetSwitches.READ_TOOLS.contains(name)} 二分，双档工具不在读集里 ⇒ 一律按
     * 写侧口径说「表格写入功能已被管理员关闭 … app.ai.sheet.write-enabled」——而管理员此时把读闸关了、
     * 写闸是开的，按文案去查 write-enabled 会查到 true，等于把排障方向指反。
     */
    @Test
    void dualToolNamesTheGateThatIsActuallyClosed() {
        when(this.toolRegistry.matchesContext(eq("normalize_column"), any(AiContext.class))).thenReturn(false);
        when(this.toolRegistry.sheetDisabled("normalize_column")).thenReturn(true);
        when(this.toolRegistry.sheetDisabledGate("normalize_column")).thenReturn("read-enabled");
        when(this.toolRegistry.channelOf("normalize_column")).thenReturn(ToolMeta.Channel.FRONTEND);
        when(this.toolRegistry.riskOf("normalize_column")).thenReturn(ToolMeta.RiskLevel.WRITE);

        ToolResultEnvelope envelope = ToolResultEnvelope.fromJson(
                toolText(execute("normalize_column", "{\"defCode\":\"CURRENCY\"}")));

        assertThat(envelope.code()).isEqualTo("SHEET_DISABLED");
        assertThat(envelope.message()).contains("表格读取功能已被管理员关闭")
                .contains("本次未读任何数据")
                .contains("read-enabled")
                .doesNotContain("write-enabled");
        assertThat(capturedPending().getStatus()).isEqualTo(PendingToolCall.SHEET_DISABLED);
        assertThat(this.audit.metrics()).containsEntry("disabledRejects", 1L);
    }

    // ── 桩与辅助 ────────────────────────────────────────────────────────────

    private static RunSnapshot snapshot() {
        RunSnapshot snapshot = new RunSnapshot();
        snapshot.setRunId(RUN_ID);
        snapshot.setSessionId("s-sheet-disabled");
        snapshot.setStatus(RunSnapshot.RUNNING);
        snapshot.setContext(AiContext.of("export", "EXPORT", "EXPORT", 1L, Map.of()).toMap());
        return snapshot;
    }

    private ToolExecutionResult execute(String tool, String args) {
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
            .toolContext(Map.of("runId", RUN_ID))
            .build();
        Prompt prompt = new Prompt(List.of(new UserMessage("hi")), options);
        AssistantMessage assistant = AssistantMessage.builder()
            .toolCalls(List.of(new AssistantMessage.ToolCall(CALL_ID, "function", tool, args)))
            .build();
        return this.manager.executeToolCalls(prompt, new ChatResponse(List.of(new Generation(assistant))));
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
