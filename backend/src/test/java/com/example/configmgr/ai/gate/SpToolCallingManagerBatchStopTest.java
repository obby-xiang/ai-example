package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolResultEnvelope;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.example.configmgr.common.ConflictException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolExecutionResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1-10 批策略：W/J/P 确定性失败即停，R/H/U/F 失败继续；未执行项补结构化 SKIPPED。
 *
 * <p>用例使用 {@link InMemoryRunStore} 的真实认领/台账语义 + 覆盖 {@code delegateSingle} 的
 * 执行替身（工具异常经 {@code ConflictException} 携带机器码），因此断言落在"结算后的条目真值"
 * 与"回填模型的信封"上，而不是对 mock 假设的自证。
 */
class SpToolCallingManagerBatchStopTest {

    private static final String RUN_ID = "run-batch-stop";

    private static final String SESSION_ID = "s-batch-stop";

    private final InMemoryRunStore store = new InMemoryRunStore();

    private final RunRegistry registry = mock(RunRegistry.class);

    private final ConfirmGate gate = mock(ConfirmGate.class);

    private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

    private final MessageJsonCodec codec = new MessageJsonCodec(new ObjectMapper());

    private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

    private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

    private final SseChatEmitter emitter = mock(SseChatEmitter.class);

    private final AiProperties properties = new AiProperties();

    private final ToolResultLimiter limiter = new ToolResultLimiter(this.properties);

    /** 工具名 → 失败码（执行替身按名抛错；不在表内的工具返回成功文本）。 */
    private final Map<String, String> failures = new LinkedHashMap<>();

    private final SpToolCallingManager manager = new SpToolCallingManager(this.store, this.registry, this.gate,
            this.toolRegistry, this.codec, this.beacon, this.cancellations, this.limiter, List.of()) {

        @Override
        String delegateSingle(Prompt prompt, PendingToolCall pending) {
            String code = SpToolCallingManagerBatchStopTest.this.failures.get(pending.getName());
            if (code != null) {
                throw new ConflictException(code, "作业被业务守卫拒绝");
            }
            return "执行成功：" + pending.getName();
        }
    };

    @BeforeEach
    void setUp() {
        this.store.seedRun(RUN_ID, SESSION_ID);
        when(this.cancellations.isCancelled(RUN_ID)).thenReturn(false);
        when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
        when(this.toolRegistry.matchesContext(anyString(), any(AiContext.class))).thenReturn(true);
        when(this.gate.awaitDecision(eq(RUN_ID), any(PendingToolCall.class))).thenAnswer(invocation -> {
            PendingToolCall pending = invocation.getArgument(1);
            pending.setStatus(PendingToolCall.APPROVED);
            return pending;
        });
        when(this.gate.awaitFrontendResult(eq(RUN_ID), any(PendingToolCall.class))).thenAnswer(invocation -> {
            PendingToolCall pending = invocation.getArgument(1);
            String code = this.failures.get(pending.getName());
            pending.setStatus(PendingToolCall.FRONTEND_RESULT);
            pending.setOutcome(code == null ? ToolOutcome.OK.wire() : ToolOutcome.FAILED.wire());
            pending.setCode(code);
            pending.setResultText(code == null ? "前端已执行" : "前端执行失败：" + code);
            return pending;
        });
    }

    // ── 停走矩阵：W/J/P 停 ──────────────────────────────────────────────────

    @ParameterizedTest(name = "上游 {0}（{1}）确定性失败 ⇒ 后续 {2} 被 SKIPPED")
    @CsvSource({
            "fake_w_tool,W,start_export",
            "start_export,J,start_import",
            "start_publish,P,start_export"})
    void writeJobPublishCategoriesHaltTheBatch(String failingTool, String category, String nextTool) {
        backend(failingTool, ToolMeta.Category.valueOf(category), riskOf(failingTool));
        backend(nextTool, ToolMeta.Category.J, ToolMeta.RiskLevel.DANGER);
        this.failures.put(failingTool, "TASK_FINALIZED");

        ToolExecutionResult result = this.manager.executeToolCalls(
                prompt(), response(call("c-1", failingTool, argsOf(failingTool)),
                        call("c-2", nextTool, "{\"taskId\":9}")));

        List<ToolResponseMessage.ToolResponse> responses = responses(result);
        assertThat(responses).hasSize(2);
        ToolResultEnvelope upstream = envelope(responses.get(0));
        ToolResultEnvelope skipped = envelope(responses.get(1));
        assertThat(upstream.outcome()).isEqualTo(ToolOutcome.FAILED.wire());
        assertThat(upstream.retryable()).isFalse();
        assertThat(skipped.outcome()).isEqualTo(ToolOutcome.SKIPPED.wire());
        assertThat(skipped.code()).isEqualTo(ToolOutcome.CODE_UPSTREAM_FAILED);
        assertThat(skipped.ok()).isFalse();
        assertThat(skipped.retryable()).isFalse();
        assertThat(skipped.message()).isEqualTo("未执行：上游 " + failingTool + " 失败（TASK_FINALIZED），本调用已跳过");
        // 跳过项落库为 SKIPPED，且没有执行任何副作用
        PendingToolCall skippedEntry = this.store.pending(RUN_ID, "c-2");
        assertThat(skippedEntry.getStatus()).isEqualTo(PendingToolCall.SKIPPED);
        assertThat(skippedEntry.getOutcome()).isEqualTo(ToolOutcome.SKIPPED.wire());
        assertThat(skippedEntry.getCode()).isEqualTo(ToolOutcome.CODE_UPSTREAM_FAILED);
        assertThat(skippedEntry.isExecuted()).isFalse();
        // 帧照发（ok=false / outcome=skipped / code=UPSTREAM_FAILED）
        verify(this.emitter).toolResult(samePending("c-2"), eq(false), eq(false), eq(skipped.message()));
    }

    // ── 停走矩阵：R/H/U/F 继续 ─────────────────────────────────────────────

    @ParameterizedTest(name = "上游 {0}（{1}）确定性失败 ⇒ 后续作业仍执行（不停）")
    @CsvSource({
            "list_tasks,R",
            "navigate_to,U",
            "download_export_files,F",
            "generative_form,H"})
    void readFormUiDownloadCategoriesKeepGoing(String failingTool, String category) {
        ToolMeta.Category cat = ToolMeta.Category.valueOf(category);
        if (cat == ToolMeta.Category.R) {
            backend(failingTool, cat, ToolMeta.RiskLevel.READ);
        }
        else {
            frontend(failingTool, cat);
        }
        backend("start_export", ToolMeta.Category.J, ToolMeta.RiskLevel.DANGER);
        this.failures.put(failingTool, "UPSTREAM_TOOL_FAILED");

        ToolExecutionResult result = this.manager.executeToolCalls(
                prompt(), response(call("c-1", failingTool, "{\"taskId\":1}"),
                        call("c-2", "start_export", "{\"taskId\":1}")));

        List<ToolResponseMessage.ToolResponse> responses = responses(result);
        assertThat(responses).hasSize(2);
        assertThat(envelope(responses.get(0)).outcome()).isEqualTo(ToolOutcome.FAILED.wire());
        // 不停：第二条不是 SKIPPED，而是真的执行了（成功）
        ToolResultEnvelope second = envelope(responses.get(1));
        assertThat(second.outcome()).isEqualTo(ToolOutcome.OK.wire());
        assertThat(second.message()).isEqualTo("执行成功：start_export");
        assertThat(this.store.pending(RUN_ID, "c-2").getStatus()).isNotEqualTo(PendingToolCall.SKIPPED);
    }

    // ── retryable=true 不停 ───────────────────────────────────────────────

    @Test
    void retryableFailureDoesNotHalt() {
        backend("start_precheck", ToolMeta.Category.J, ToolMeta.RiskLevel.DANGER);
        backend("start_import", ToolMeta.Category.J, ToolMeta.RiskLevel.DANGER);
        this.failures.put("start_precheck", "PRECHECK_NOT_PASSED");

        ToolExecutionResult result = this.manager.executeToolCalls(
                prompt(), response(call("c-1", "start_precheck", "{\"taskId\":7}"),
                        call("c-2", "start_import", "{\"taskId\":7}")));

        List<ToolResponseMessage.ToolResponse> responses = responses(result);
        ToolResultEnvelope first = envelope(responses.get(0));
        assertThat(first.outcome()).isEqualTo(ToolOutcome.FAILED.wire());
        // 契约：PRECHECK_NOT_PASSED 是"改数据后可重试"，retryable=true ⇒ 不停
        assertThat(first.retryable()).isTrue();
        assertThat(envelope(responses.get(1)).outcome()).isEqualTo(ToolOutcome.OK.wire());
        assertThat(this.store.pending(RUN_ID, "c-2").getStatus()).isNotEqualTo(PendingToolCall.SKIPPED);
    }

    // ── 未声明类别：fail-open（不停批） ─────────────────────────────────────

    /**
     * 未声明类别（{@code @ToolCategory} 缺失 ⇒ {@code categoryOf=null}）的失败<b>不停批</b>：
     * 停走只对显式声明的 W/J/P 生效，元数据缺失不会把普通工具后面排队的工作一起掐掉。
     * 这是<b>有意</b>的 fail-open（见 {@code ToolRegistry#categoryOf} 的注释），本用例把它锁死：
     * 若有人把默认值改成"未知即停"，这里会立刻变红。
     */
    @Test
    void undeclaredCategoryFailureKeepsTheBatchGoing() {
        // 不调用 backend(...)：刻意让 categoryOf 返回 null（未声明）
        when(this.toolRegistry.channelOf("legacy_write_tool")).thenReturn(ToolMeta.Channel.BACKEND);
        when(this.toolRegistry.riskOf("legacy_write_tool")).thenReturn(ToolMeta.RiskLevel.READ);
        backend("start_export", ToolMeta.Category.J, ToolMeta.RiskLevel.DANGER);
        this.failures.put("legacy_write_tool", "TOOL_EXECUTION_FAILED");

        ToolExecutionResult result = this.manager.executeToolCalls(prompt(),
                response(call("c-1", "legacy_write_tool", "{\"taskId\":1}"),
                        call("c-2", "start_export", "{\"taskId\":2}")));

        List<ToolResponseMessage.ToolResponse> responses = responses(result);
        assertThat(envelope(responses.get(0)).outcome()).isEqualTo(ToolOutcome.FAILED.wire());
        assertThat(envelope(responses.get(1)).outcome()).as("类别未声明 ⇒ 不停批，后续照常执行")
                .isEqualTo(ToolOutcome.OK.wire());
        assertThat(this.store.pending(RUN_ID, "c-2").getStatus()).isNotEqualTo(PendingToolCall.SKIPPED);
    }

    // ── SKIPPED 级联：多个未执行项逐个有结果 ───────────────────────────────

    @Test
    void everyPlannedCallGetsExactlyOneResponseWhenBatchHalts() {
        backend("start_export", ToolMeta.Category.J, ToolMeta.RiskLevel.DANGER);
        backend("start_import", ToolMeta.Category.J, ToolMeta.RiskLevel.DANGER);
        backend("start_publish", ToolMeta.Category.P, ToolMeta.RiskLevel.DANGER);
        this.failures.put("start_export", "JOB_ALREADY_RUNNING");

        ToolExecutionResult result = this.manager.executeToolCalls(prompt(),
                response(call("c-1", "start_export", "{\"taskId\":3}"),
                        call("c-2", "start_import", "{\"taskId\":4}"),
                        call("c-3", "start_publish", "{\"taskId\":5}")));

        List<ToolResponseMessage.ToolResponse> responses = responses(result);
        // 模型侧每个 tool_call_id 都有对应 ToolResponse（数量对齐 + 顺序对齐）
        assertThat(responses).extracting(ToolResponseMessage.ToolResponse::id)
                .containsExactly("c-1", "c-2", "c-3");
        assertThat(envelope(responses.get(0)).outcome()).isEqualTo(ToolOutcome.FAILED.wire());
        for (int i = 1; i < 3; i++) {
            ToolResultEnvelope skipped = envelope(responses.get(i));
            assertThat(skipped.outcome()).isEqualTo(ToolOutcome.SKIPPED.wire());
            assertThat(skipped.code()).isEqualTo(ToolOutcome.CODE_UPSTREAM_FAILED);
            assertThat(skipped.message()).startsWith("未执行：上游 start_export 失败");
            // target 仍从原参数提取（不是 null）
            assertThat(skipped.target()).containsOnlyKeys("taskId");
        }
        assertThat(envelope(responses.get(1)).target()).containsEntry("taskId", 4);
        assertThat(envelope(responses.get(2)).target()).containsEntry("taskId", 5);
        assertThat(this.store.pending(RUN_ID, "c-2").getStatus()).isEqualTo(PendingToolCall.SKIPPED);
        assertThat(this.store.pending(RUN_ID, "c-3").getStatus()).isEqualTo(PendingToolCall.SKIPPED);
        // 两个跳过项各发一帧
        verify(this.emitter, times(2)).toolResult(any(PendingToolCall.class), eq(false), eq(false), contains("本调用已跳过"));
    }

    // ── 执行后失败：对账措辞 ────────────────────────────────────────────────

    @Test
    void executedFailureCarriesReconciliationNoteForModelAndReplay() {
        backend("start_export", ToolMeta.Category.J, ToolMeta.RiskLevel.DANGER);
        this.failures.put("start_export", "TASK_FINALIZED");

        ToolExecutionResult result = this.manager.executeToolCalls(prompt(),
                response(call("c-1", "start_export", "{\"taskId\":3}")));

        ToolResultEnvelope failed = envelope(responses(result).get(0));
        assertThat(failed.outcome()).isEqualTo(ToolOutcome.FAILED.wire());
        assertThat(failed.message()).contains(SpToolCallingManager.RECONCILIATION_NOTE);
        // 落库的 resultText 同样带对账措辞 ⇒ 续跑重放与首轮同形
        assertThat(this.store.pending(RUN_ID, "c-1").getResultText())
                .contains(SpToolCallingManager.RECONCILIATION_NOTE);
        assertThat(this.store.pending(RUN_ID, "c-1").isExecuted()).isTrue();
    }

    // ── 辅助 ────────────────────────────────────────────────────────────────

    private static ToolMeta.RiskLevel riskOf(String tool) {
        return tool.startsWith("start_") ? ToolMeta.RiskLevel.DANGER : ToolMeta.RiskLevel.READ;
    }

    private static String argsOf(String tool) {
        return tool.equals("fake_w_tool") ? "{\"type\":\"EXPORT\"}" : "{\"taskId\":9}";
    }

    private void backend(String name, ToolMeta.Category category, ToolMeta.RiskLevel risk) {
        when(this.toolRegistry.categoryOf(name)).thenReturn(category);
        when(this.toolRegistry.channelOf(name)).thenReturn(ToolMeta.Channel.BACKEND);
        when(this.toolRegistry.riskOf(name)).thenReturn(risk);
    }

    private void frontend(String name, ToolMeta.Category category) {
        when(this.toolRegistry.categoryOf(name)).thenReturn(category);
        when(this.toolRegistry.channelOf(name)).thenReturn(ToolMeta.Channel.FRONTEND);
        when(this.toolRegistry.riskOf(name)).thenReturn(ToolMeta.RiskLevel.READ);
    }

    private static Prompt prompt() {
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolContext(Map.of("runId", RUN_ID))
                .build();
        return new Prompt(List.of(new UserMessage("hi")), options);
    }

    private static AssistantMessage.ToolCall call(String id, String name, String args) {
        return new AssistantMessage.ToolCall(id, "function", name, args);
    }

    private static ChatResponse response(AssistantMessage.ToolCall... calls) {
        AssistantMessage assistant = AssistantMessage.builder().toolCalls(List.of(calls)).build();
        return new ChatResponse(List.of(new Generation(assistant)));
    }

    private static List<ToolResponseMessage.ToolResponse> responses(ToolExecutionResult result) {
        List<ToolResponseMessage.ToolResponse> out = new ArrayList<>();
        for (var message : result.conversationHistory()) {
            if (message instanceof ToolResponseMessage toolMessage) {
                out.addAll(toolMessage.getResponses());
            }
        }
        return out;
    }

    private static ToolResultEnvelope envelope(ToolResponseMessage.ToolResponse response) {
        return ToolResultEnvelope.fromJson(response.responseData());
    }

    private static PendingToolCall samePending(String toolCallId) {
        return org.mockito.ArgumentMatchers.argThat(pending -> pending != null
                && toolCallId.equals(pending.getToolCallId()));
    }

}
