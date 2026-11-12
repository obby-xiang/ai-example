package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolResultEnvelope;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolExecutionResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpToolCallingManagerJobFactTest {

    private static final String RUN_ID = "run-job-fact";
    private static final String SESSION_ID = "s-job-fact";
    private static final String CALL_ID = "call-job-fact";

    private final InMemoryRunStore store = new InMemoryRunStore() {
        @Override
        public void completeExecution(String runId, PendingToolCall pending, long claimedEpoch, String claimedBy) {
            putPending(runId, pending);
        }
    };
    private final RunRegistry registry = mock(RunRegistry.class);
    private final ToolRegistry toolRegistry = mock(ToolRegistry.class);
    private final CancellationRegistry cancellations = mock(CancellationRegistry.class);
    private final SseChatEmitter emitter = mock(SseChatEmitter.class);
    private final AiProperties properties = new AiProperties();
    private final AtomicInteger executions = new AtomicInteger();
    private final AtomicReference<Long> createdJobId = new AtomicReference<>();
    private final AtomicReference<String> failingTool = new AtomicReference<>();
    private final AtomicReference<CountDownLatch> timeoutStarted = new AtomicReference<>();
    private final AtomicReference<CountDownLatch> timeoutRelease = new AtomicReference<>();
    private final AtomicReference<CountDownLatch> timeoutFinished = new AtomicReference<>();
    private final AtomicReference<AtomicReference<Long>> timedOutHolder = new AtomicReference<>();
    private final AtomicReference<AtomicReference<Long>> ordinaryHolder = new AtomicReference<>();
    private final RequestContextUtils.SafeCloseable identity = RequestContextUtils.useContext(1L, 7L);

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        this.identity.close();
    }

    private final SpToolCallingManager manager = new SpToolCallingManager(this.store, this.registry,
            mock(ConfirmGate.class), this.toolRegistry, new MessageJsonCodec(new ObjectMapper()),
            mock(ToolActivityBeacon.class), this.cancellations, new ToolResultLimiter(this.properties),
            List.of(), null, null, null, null, this.properties) {
        @Override
        String delegateSingle(Prompt prompt, PendingToolCall pending) {
            executions.incrementAndGet();
            Map<String, Object> context = ((ToolCallingChatOptions) prompt.getOptions()).getToolContext();
            AtomicReference<Long> holder = context.get("createdJobId") instanceof AtomicReference<?> raw
                    ? (AtomicReference<Long>) raw : null;
            if (pending.getName().startsWith("start_") && holder != null && timeoutStarted.get() == null) {
                holder.set(jobIdFor(pending.getName()));
            }
            if ("start_export".equals(pending.getName()) && timeoutStarted.get() != null) {
                timedOutHolder.set(holder);
                timeoutStarted.get().countDown();
                try {
                    if (!timeoutRelease.get().await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timeout release was not opened");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    timeoutFinished.get().countDown();
                }
            }
            if ("list_tasks".equals(pending.getName())) {
                ordinaryHolder.set(holder);
            }
            if (pending.getName().equals(failingTool.get())) {
                throw new IllegalStateException("injected tool failure");
            }
            return "导出作业已启动（作业 #4242），进度由页面展示";
        }
    };

    @Test
    void settlementLedgerModelAndReplayCarryTheSameStructuredJobId() {
        prepare();
        ToolExecutionResult first = this.manager.executeToolCalls(prompt(), response());

        PendingToolCall pending = this.store.pending(RUN_ID, CALL_ID);
        assertThat(pending.asMap()).containsEntry("createdJobId", "4242");
        assertThat(this.store.ledger(RUN_ID)).singleElement().satisfies(record ->
                assertThat(record).containsEntry("createdJobId", "4242"));
        ToolResultEnvelope model = ToolResultEnvelope.fromJson(toolResponse(first).responseData());
        assertThat(model.target()).containsEntry("taskId", "7").containsEntry("jobId", "4242");

        this.createdJobId.set(null);
        ToolExecutionResult replayed = this.manager.executeToolCalls(prompt(), response());

        assertThat(this.executions).as("台账重放不得再次创建作业").hasValue(1);
        assertThat(this.store.pending(RUN_ID, CALL_ID).asMap()).containsEntry("createdJobId", "4242");
        assertThat(ToolResultEnvelope.fromJson(toolResponse(replayed).responseData()).target())
                .containsEntry("jobId", "4242");
    }

    @Test
    void successfulToolAfterStartDoesNotInheritItsJobId() {
        prepare();

        executeTools("start_export", "list_tasks");

        assertThat(pending("start_export").asMap()).containsEntry("createdJobId", "4242");
        assertThat(pending("list_tasks").asMap()).containsEntry("createdJobId", null);
    }

    @Test
    void failedStartDoesNotLeakItsHolderAcrossStartsOrIntoFollowingOrdinaryTool() {
        prepare();
        this.failingTool.set("start_precheck");

        executeTools("start_export");
        executeTools("start_precheck");
        executeTools("start_import");
        executeTools("list_tasks");

        assertThat(pending("start_export").asMap()).containsEntry("createdJobId", "4242");
        assertThat(pending("start_precheck").asMap()).containsEntry("createdJobId", null);
        assertThat(pending("start_import").asMap()).containsEntry("createdJobId", "4444");
        assertThat(pending("list_tasks").asMap()).containsEntry("createdJobId", null);
    }

    @Test
    void lateTimeoutWriteStaysInTheOldHolderAndCannotPolluteFollowingTool() throws Exception {
        prepare();
        this.properties.getTool().setExecTimeout(Duration.ofMillis(50));
        this.timeoutStarted.set(new CountDownLatch(1));
        this.timeoutRelease.set(new CountDownLatch(1));
        this.timeoutFinished.set(new CountDownLatch(1));
        executeTools("start_export");
        assertThat(this.timeoutStarted.get().await(2, TimeUnit.SECONDS)).isTrue();

        try {
            executeTools("list_tasks");

            assertThat(pending("list_tasks").asMap()).containsEntry("createdJobId", null);
            assertThat(this.ordinaryHolder.get()).isNull();
        } finally {
            this.timeoutRelease.get().countDown();
            assertThat(this.timeoutFinished.get().await(2, TimeUnit.SECONDS)).isTrue();
        }
        this.timedOutHolder.get().set(4242L);
        assertThat(this.timedOutHolder.get().get()).isEqualTo(4242L);
        assertThat(pending("list_tasks").asMap()).containsEntry("createdJobId", null);
    }

    private void prepare() {
        com.example.configmgr.ai.run.RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
        snapshot.setTenantId(1L);
        snapshot.setUserId(7L);
        this.store.save(snapshot);
        when(this.cancellations.cancelState(RUN_ID)).thenReturn(CancellationRegistry.CancelState.NOT_CANCELLED);
        when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
        when(this.toolRegistry.isContextMatched(anyString(), any(AiContext.class))).thenReturn(true);
        when(this.toolRegistry.channelOf(anyString())).thenReturn(ToolMeta.Channel.BACKEND);
        when(this.toolRegistry.riskOf(anyString())).thenReturn(ToolMeta.RiskLevel.READ);
        when(this.toolRegistry.categoryOf(anyString())).thenReturn(ToolMeta.Category.J);
    }

    private void executeTools(String... names) {
        this.manager.executeToolCalls(prompt(), responseWithTools(names));
    }

    private PendingToolCall pending(String name) {
        return this.store.pending(RUN_ID, callId(name));
    }

    private Prompt prompt() {
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolContext(Map.of("runId", RUN_ID, "sessionId", SESSION_ID))
                .build();
        return new Prompt(List.of(new UserMessage("启动导出")), options);
    }

    private static ChatResponse response() {
        AssistantMessage assistant = AssistantMessage.builder()
                .toolCalls(List.of(new AssistantMessage.ToolCall(CALL_ID, "function", "start_export", "{\"taskId\":7}")))
                .build();
        return new ChatResponse(List.of(new Generation(assistant)));
    }

    private static ChatResponse responseWithTools(String... names) {
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        for (String name : names) {
            calls.add(new AssistantMessage.ToolCall(callId(name), "function", name, "{\"taskId\":7}"));
        }
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().toolCalls(calls).build())));
    }

    private static String callId(String name) {
        return "call-" + name;
    }

    private static long jobIdFor(String name) {
        return switch (name) {
            case "start_export" -> 4242L;
            case "start_precheck" -> 4343L;
            case "start_import" -> 4444L;
            case "start_publish" -> 4545L;
            default -> throw new IllegalArgumentException(name);
        };
    }

    private static ToolResponseMessage.ToolResponse toolResponse(ToolExecutionResult result) {
        return result.conversationHistory().stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .flatMap(message -> message.getResponses().stream())
                .filter(response -> CALL_ID.equals(response.id()))
                .findFirst()
                .orElseThrow();
    }
}
