package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.ResilientChatService;
import com.example.configmgr.ai.run.ResumeService;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
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
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1-10 续跑同策略：续跑遇到 W/J/P 确定性失败同样停，并为剩余未执行项补 SKIPPED；
 * 重放的历史 {@code role:tool} 与首轮同形（统一信封、outcome=skipped）。
 */
class ResumeSkipCascadeTest {

    private static final String RUN_ID = "run-resume-skip";

    private static final String SESSION_ID = "s-resume-skip";

    private final InMemoryRunStore store = new InMemoryRunStore();

    private final ObjectMapper mapper = new ObjectMapper();

    private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

    private final RunRegistry registry = mock(RunRegistry.class);

    private final SseChatEmitter emitter = mock(SseChatEmitter.class);

    private final ResilientChatService chatService = mock(ResilientChatService.class);

    private final ConfirmGate gate = mock(ConfirmGate.class);

    private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

    private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

    private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

    private final AiProperties properties = new AiProperties();

    private final ToolResultLimiter limiter = new ToolResultLimiter(this.properties);

    private final ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    private final SpToolCallingManager toolCallingManager = new SpToolCallingManager(this.store, this.registry,
            this.gate, this.toolRegistry, this.codec, this.beacon, this.cancellations, this.limiter, List.of()) {

        @Override
        String delegateSingle(Prompt prompt, PendingToolCall pending) {
            if ("start_export".equals(pending.getName())) {
                throw new ConflictException("TASK_FINALIZED", "任务已终态，不能再次导出");
            }
            return "执行成功：" + pending.getName();
        }
    };

    private final ResumeService service = new ResumeService(this.store, this.registry, this.codec, this.chatService,
            this.toolCallingManager, this.executor, this.redis, this.properties, this.cancellations, this.limiter);

    @BeforeEach
    void setUp() {
        when(this.chatService.buildResumedPrompt(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), any()))
                .thenReturn(new Prompt(List.of()));
        when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
        when(this.cancellations.isCancelled(RUN_ID)).thenReturn(false);
        when(this.toolRegistry.matchesContext(anyString(), any(AiContext.class))).thenReturn(true);
        // 续跑锁在"异步驱动任务结束"时才释放；让执行器同步跑完（driveResumed 是替身），
        // 后续 resume 才能看到锁已释放（"再次续跑"用例需要）。
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(this.executor).execute(any(Runnable.class));
        category("start_export", ToolMeta.Category.J);
        category("start_import", ToolMeta.Category.J);
        category("start_publish", ToolMeta.Category.P);
    }

    @Test
    void resumedBatchHaltsOnDeterministicFailureAndMarksRemainingSkipped() {
        seedSuspendedBatch();

        ResumeService.ResumeResult result = this.service.resume(RUN_ID);

        assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
        assertThat(result.body()).containsEntry("resumed", true);
        // 续跑同样为剩余未执行项补 SKIPPED（写库）
        assertThat(this.store.pending(RUN_ID, "c-2").getStatus()).isEqualTo(PendingToolCall.SKIPPED);
        assertThat(this.store.pending(RUN_ID, "c-3").getStatus()).isEqualTo(PendingToolCall.SKIPPED);
        assertThat(this.store.pending(RUN_ID, "c-2").getOutcome()).isEqualTo(ToolOutcome.SKIPPED.wire());
        assertThat(this.store.pending(RUN_ID, "c-3").isExecuted()).isFalse();
        // 上游失败 1 帧 + 跳过项各 1 帧（跳过帧的 result 带"本调用已跳过"）
        verify(this.emitter, times(3)).toolResult(any(PendingToolCall.class), eq(false), eq(false),
                anyString());
        verify(this.emitter, times(2)).toolResult(any(PendingToolCall.class), eq(false), eq(false),
                contains("本调用已跳过"));

        // 重放历史与首轮同形：3 个 toolCallId 各一条 ToolResponse，第二条起 outcome=skipped
        List<Message> history = this.codec.deserializeAll(this.store.get(RUN_ID).getMessageJson());
        ToolResponseMessage toolMessage = history.stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .findFirst()
                .orElseThrow();
        List<ToolResponseMessage.ToolResponse> responses = toolMessage.getResponses();
        assertThat(responses).extracting(ToolResponseMessage.ToolResponse::id)
                .containsExactly("c-1", "c-2", "c-3");
        ToolResultEnvelope upstream = ToolResultEnvelope.fromJson(responses.get(0).responseData());
        assertThat(upstream.outcome()).isEqualTo(ToolOutcome.FAILED.wire());
        for (int i = 1; i < 3; i++) {
            ToolResultEnvelope skipped = ToolResultEnvelope.fromJson(responses.get(i).responseData());
            assertThat(skipped.outcome()).isEqualTo(ToolOutcome.SKIPPED.wire());
            assertThat(skipped.code()).isEqualTo(ToolOutcome.CODE_UPSTREAM_FAILED);
            assertThat(skipped.retryable()).isFalse();
            assertThat(skipped.message()).startsWith("未执行：上游 start_export 失败（TASK_FINALIZED）");
        }
        @SuppressWarnings("unchecked")
        List<String> rebuilt = (List<String>) result.body().get("rebuiltTools");
        assertThat(rebuilt).containsExactly("c-1:APPROVED:executed-here", "c-2:SKIPPED:not-executed",
                "c-3:SKIPPED:not-executed");
    }

    /**
     * M-3（崩溃窗口①）：条目已落库为 {@code PENDING + BACKEND}（进程死在"写条目 → 执行"之间）。
     * 续跑不得把它当 {@code PENDING_UNRESOLVED} 拒绝；它先被补执行，确定性失败后再把
     * 后续未执行项级联为 SKIPPED（与首轮同一策略）。
     */
    @Test
    void pendingBackendCrashWindowStillCascadesToSkipped() {
        seedSuspendedBatch(List.of(backendEntry("c-1", "start_export"),
                backendEntry("c-2", "start_import"), backendEntry("c-3", "start_publish")));

        ResumeService.ResumeResult result = this.service.resume(RUN_ID);

        assertThat(result.outcome()).as("BACKEND 待执行不是未决挂起，不能被 PENDING_UNRESOLVED 挡住")
                .isEqualTo(ResumeService.Outcome.ACCEPTED);
        assertThat(result.body()).containsEntry("resumed", true);
        // 崩溃窗口的正常路径：补执行一次并落定失败
        PendingToolCall first = this.store.pending(RUN_ID, "c-1");
        assertThat(first.getStatus()).isEqualTo(PendingToolCall.EXECUTED);
        assertThat(first.isExecuted()).isTrue();
        assertThat(first.getOutcome()).isEqualTo(ToolOutcome.FAILED.wire());
        assertThat(first.getCode()).isEqualTo("TASK_FINALIZED");
        // 上游失败 ⇒ 后续两个未执行项级联为 SKIPPED
        assertThat(this.store.pending(RUN_ID, "c-2").getStatus()).isEqualTo(PendingToolCall.SKIPPED);
        assertThat(this.store.pending(RUN_ID, "c-3").getStatus()).isEqualTo(PendingToolCall.SKIPPED);
        verify(this.emitter, times(3)).toolResult(any(PendingToolCall.class), eq(false), eq(false),
                anyString());
        verify(this.emitter, times(2)).toolResult(any(PendingToolCall.class), eq(false), eq(false),
                contains("本调用已跳过"));
    }

    /**
     * M-3（崩溃窗口②）：SKIPPED 已写库、快照尚未落盘（in-flight 仍在）时<b>再次续跑</b> ——
     * 已结算条目只复用结果：不重放跳过、不重复发帧、也不重写 {@code resolvedAtMs}。
     */
    @Test
    void secondResumeDoesNotReplaySettledSkipsOrEmitDuplicateFrames() {
        seedSuspendedBatch();
        assertThat(this.service.resume(RUN_ID).outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
        long skippedResolvedAt = this.store.pending(RUN_ID, "c-2").getResolvedAtMs();
        assertThat(skippedResolvedAt).isPositive();
        // 模拟"待决条目已结算、快照未保存"的崩溃：把快照退回续跑前的挂起形态
        RunSnapshot crashed = this.store.get(RUN_ID);
        crashed.setStatus(RunSnapshot.SUSPENDED);
        crashed.setMessageJson(this.codec.serializeAll(List.of(new UserMessage("请执行导入链路"))));
        crashed.setAssistantContent(null);
        crashed.setInFlightToolCalls(new ArrayList<>(List.of(entry("c-1", "start_export"),
                entry("c-2", "start_import"), entry("c-3", "start_publish"))));
        this.store.save(crashed);
        clearInvocations(this.emitter);

        ResumeService.ResumeResult second = this.service.resume(RUN_ID);

        assertThat(second.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
        // 已结算的跳过项只复用结果：第二遍续跑不为其再发任何结局帧
        verify(this.emitter, never()).toolResult(
                org.mockito.ArgumentMatchers.argThat(pending -> pending != null
                        && ("c-2".equals(pending.getToolCallId()) || "c-3".equals(pending.getToolCallId()))),
                anyBoolean(), anyBoolean(), anyString());
        // 跳过状态不被重放覆盖（resolvedAtMs 原值保留 = 没有二次结算）
        assertThat(this.store.pending(RUN_ID, "c-2").getStatus()).isEqualTo(PendingToolCall.SKIPPED);
        assertThat(this.store.pending(RUN_ID, "c-2").getResolvedAtMs()).isEqualTo(skippedResolvedAt);
        // 重放历史仍恰好三条，c-2/c-3 仍是 skipped 信封（没有多发一份）
        List<ToolResponseMessage.ToolResponse> replayed = replayedResponses();
        assertThat(replayed).extracting(ToolResponseMessage.ToolResponse::id)
                .containsExactly("c-1", "c-2", "c-3");
        assertThat(ToolResultEnvelope.fromJson(replayed.get(1).responseData()).outcome())
                .isEqualTo(ToolOutcome.SKIPPED.wire());
        @SuppressWarnings("unchecked")
        List<String> rebuilt = (List<String>) second.body().get("rebuiltTools");
        assertThat(rebuilt).containsExactly("c-1:APPROVED:executed-here", "c-2:SKIPPED:not-executed",
                "c-3:SKIPPED:not-executed");
    }

    /**
     * M-5（跨路等值）：同一份 SKIPPED 条目，续跑重放产出的 {@code role:tool} 信封与首轮
     * {@link SpToolCallingManager#skipAsUpstreamFailed} 的产出<b>逐字节相等</b>。
     *
     * <p>为什么要求字节级：两条路径若字段/措辞/顺序有任何漂移，模型在"首轮"与"续跑后"会看到
     * 两种不同的历史，同样的调用在不同轮次里被解释成不同结局。这里先直接走首轮方法取基线，
     * 再让续跑复用同一落库条目并比对 {@code responseData} 原文。
     */
    @Test
    void replaySkippedEnvelopeIsByteIdenticalToFirstRoundSkip() {
        seedSuspendedBatch();
        PendingToolCall upstream = entry("u-1", "start_export");
        upstream.setStatus(PendingToolCall.EXECUTED);
        upstream.setOutcome(ToolOutcome.FAILED.wire());
        upstream.setCode("TASK_FINALIZED");
        ToolResponseMessage.ToolResponse firstRound = this.toolCallingManager
                .skipAsUpstreamFailed(RUN_ID, this.store.pending(RUN_ID, "c-2"), upstream);
        clearInvocations(this.emitter);

        assertThat(this.service.resume(RUN_ID).outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);

        ToolResponseMessage.ToolResponse replay = replayedResponses().get(1);
        assertThat(replay.id()).isEqualTo("c-2");
        assertThat(replay.responseData()).as("续跑重放的 SKIPPED 信封必须与首轮逐字节相等")
                .isEqualTo(firstRound.responseData());
    }

    private List<ToolResponseMessage.ToolResponse> replayedResponses() {
        List<Message> history = this.codec.deserializeAll(this.store.get(RUN_ID).getMessageJson());
        return history.stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .findFirst()
                .orElseThrow()
                .getResponses();
    }

    private void seedSuspendedBatch() {
        seedSuspendedBatch(List.of(entry("c-1", "start_export"), entry("c-2", "start_import"),
                entry("c-3", "start_publish")));
    }

    private void seedSuspendedBatch(List<PendingToolCall> inFlight) {
        RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
        snapshot.setStatus(RunSnapshot.SUSPENDED);
        snapshot.setContext(AiContext.of("import", "IMPORT", "IMPORT", 7L, null).toMap());
        snapshot.setMessageJson(this.codec.serializeAll(List.of(new UserMessage("请执行导入链路"))));
        snapshot.setInFlightToolCalls(new ArrayList<>(inFlight));
        this.store.save(snapshot);
        for (PendingToolCall pending : inFlight) {
            this.store.seedPending(RUN_ID, pending);
        }
    }

    /** 崩溃窗口形态：条目已写库但从未执行（后端工具无需外部输入，续跑补执行）。 */
    private static PendingToolCall backendEntry(String id, String name) {
        PendingToolCall pending = entry(id, name);
        pending.setKind(PendingToolCall.KIND_BACKEND);
        pending.setStatus(PendingToolCall.PENDING);
        return pending;
    }

    private static PendingToolCall entry(String id, String name) {
        PendingToolCall pending = new PendingToolCall();
        pending.setToolCallId(id);
        pending.setName(name);
        pending.setType("function");
        pending.setArguments("{\"taskId\":7}");
        pending.setKind(PendingToolCall.KIND_CONFIRM);
        // 三个调用都已人工放行、尚未执行（E2 窗口的批量形态）
        pending.setStatus(PendingToolCall.APPROVED);
        pending.setExecuted(false);
        return pending;
    }

    private void category(String name, ToolMeta.Category category) {
        when(this.toolRegistry.categoryOf(name)).thenReturn(category);
        when(this.toolRegistry.channelOf(name)).thenReturn(ToolMeta.Channel.BACKEND);
        when(this.toolRegistry.riskOf(name)).thenReturn(ToolMeta.RiskLevel.DANGER);
    }

}
