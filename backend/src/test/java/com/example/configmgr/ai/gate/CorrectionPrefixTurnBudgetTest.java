package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.context.ContextEstimator;
import com.example.configmgr.ai.context.ContextLimitResolver;
import com.example.configmgr.ai.context.ContextState;
import com.example.configmgr.ai.context.ContextStateStore;
import com.example.configmgr.ai.context.HardTrim;
import com.example.configmgr.ai.context.ToolResultClearing;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.memory.ReasoningOrigin;
import com.example.configmgr.ai.memory.RunMemoryMerge;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.CorrectionPrefixes;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.StreamViolationException;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolResultEnvelope;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolExecutionResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CorrectionPrefixTurnBudgetTest {

    private static final String RUN_ID = "run-correction-prefix-budget";
    private static final String SESSION_ID = "s-correction-prefix-budget";
    private static final String TOOL = "list_tasks";
    private static final String PREFIX = "前".repeat(149);
    private static final String SUFFIX = "后".repeat(49);

    private final RunStore store = mock(RunStore.class);
    private final RunRegistry registry = mock(RunRegistry.class);
    private final ConfirmGate gate = mock(ConfirmGate.class);
    private final ToolRegistry toolRegistry = mock(ToolRegistry.class);
    private final CancellationRegistry cancellations = mock(CancellationRegistry.class);
    private final SseChatEmitter emitter = mock(SseChatEmitter.class);
    private final ContextStateStore contextStore = mock(ContextStateStore.class);
    private final MessageJsonCodec codec = new MessageJsonCodec(new ObjectMapper());
    private final AiProperties properties = new AiProperties();
    private final ToolResultLimiter limiter = new ToolResultLimiter(this.properties);
    private final AtomicInteger limit = new AtomicInteger();
    private final Map<String, PendingToolCall> pendings = new HashMap<>();
    private final List<String> executed = new ArrayList<>();
    private RunSnapshot snapshot;
    private SpToolCallingManager manager;

    @BeforeEach
    void setUp() {
        this.snapshot = new RunSnapshot();
        this.snapshot.setRunId(RUN_ID);
        this.snapshot.setSessionId(SESSION_ID);
        this.snapshot.setStatus(RunSnapshot.RUNNING);
        this.snapshot.setTenantId(1L);
        this.snapshot.setUserId(1L);
        this.snapshot.setProviderId(1L);
        this.snapshot.setModelId("test-model");
        this.snapshot.setMessageJson(this.codec.serializeAll(correctionHistory()));
        this.snapshot.setContext(AiContext.of("tasks", null, null, null, null,
                Map.of("exportCorrectionPrefix", PREFIX)).toMap());
        this.properties.getContext().setReserveTokens(0);
        this.properties.getContext().setL1Percent(70);
        this.properties.getContext().setL3Percent(95);
        this.properties.getContext().setKeepToolPairs(1);
        setUpStore();
        when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
        when(this.cancellations.cancelState(RUN_ID)).thenReturn(CancellationRegistry.CancelState.NOT_CANCELLED);
        when(this.toolRegistry.isContextMatched(anyString(), any(AiContext.class))).thenReturn(true);
        when(this.toolRegistry.matchesContext(anyString(), any(AiContext.class))).thenReturn(true);
        when(this.toolRegistry.channelOf(TOOL)).thenReturn(ToolMeta.Channel.BACKEND);
        when(this.toolRegistry.riskOf(TOOL)).thenReturn(ToolMeta.RiskLevel.READ);
        when(this.toolRegistry.categoryOf(TOOL)).thenReturn(ToolMeta.Category.R);
        this.manager = new SpToolCallingManager(this.store, this.registry, this.gate, this.toolRegistry,
                this.codec, new ToolActivityBeacon(), this.cancellations, this.limiter, List.of(), null, null, null,
                null, this.properties) {
            @Override
            String delegateSingle(Prompt prompt, PendingToolCall pending) {
                CorrectionPrefixTurnBudgetTest.this.executed.add(pending.getToolCallId());
                return "执行成功：" + pending.getToolCallId();
            }
        };
        this.manager.setContextLimitResolver(new ContextLimitResolver((providerId, modelId) -> this.limit.get()));
    }

    @AfterEach
    void tearDown() {
        this.manager.shutdown();
    }

    @Test
    void consecutiveSuspensionsPersistOriginalHistoryInsteadOfNormalizedPrompt() {
        List<Message> model = CorrectionPrefixes.historyWithSingleCorrectionPrefix(correctionHistory(), PREFIX);
        when(this.toolRegistry.channelOf(TOOL)).thenReturn(ToolMeta.Channel.FRONTEND);
        when(this.gate.awaitFrontendResult(anyString(), any(PendingToolCall.class))).thenAnswer(invocation -> {
            PendingToolCall pending = invocation.getArgument(1);
            pending.setStatus(PendingToolCall.FRONTEND_RESULT);
            pending.setOutcome(ToolOutcome.OK.wire());
            pending.setResultText("前端已执行");
            return pending;
        });
        List<List<Message>> suspendedHistories = new ArrayList<>();
        doAnswer(invocation -> {
            suspendedHistories.add(new ArrayList<>(invocation.getArgument(4)));
            return null;
        }).when(this.store).saveSuspended(anyString(), any(), any(), anyList(), anyList(), any(), any());

        ToolExecutionResult first = this.manager.executeToolCalls(prompt(model), responseWithoutUsage());
        ToolExecutionResult second = this.manager.executeToolCalls(prompt(model), responseWithoutUsage());
        this.snapshot.setContext(AiContext.of("export", "EXPORT", null, null,
                Map.of("exportCorrectionPrefix", PREFIX)).toMap());
        ToolExecutionResult export = this.manager.executeToolCalls(prompt(model), responseWithoutUsage());

        assertThat(suspendedHistories).hasSize(3);
        for (List<Message> suspended : suspendedHistories) {
            assertThat(textOfToolCall(suspended, "create-1")).isEqualTo(PREFIX + SUFFIX);
            assertThat(suspended).anyMatch(message -> message instanceof ToolResponseMessage tool
                    && tool.getResponses().stream().anyMatch(response -> "create-1".equals(response.id())));
            assertThat(suspended).anyMatch(message -> message instanceof ToolResponseMessage tool
                    && tool.getResponses().stream().anyMatch(response -> "old-1".equals(response.id())));
        }
        assertThat(textOfToolCall(first.conversationHistory(), "create-1")).isEqualTo(SUFFIX);
        assertThat(textOfToolCall(second.conversationHistory(), "create-1")).isEqualTo(SUFFIX);
        assertThat(textOfToolCall(export.conversationHistory(), "create-1")).isEqualTo(SUFFIX);
    }

    @Test
    void correctionPrefixIsNotCountedTwiceByTheEstimateWhenPersistingOriginalHistory() {
        List<Message> model = CorrectionPrefixes.historyWithSingleCorrectionPrefix(correctionHistory(), PREFIX);
        AssistantMessage current = currentToolAssistant();
        int original = ContextEstimator.estimateMessages(withCurrentTool(correctionHistory(), current));
        int normalized = ContextEstimator.estimateMessages(withCurrentTool(model, current));
        setLimitBetweenL1(normalized, original);

        ToolExecutionResult result = this.manager.executeToolCalls(prompt(model), responseWithoutUsage());

        assertOriginalPersistedAndNormalizedModel(result);
    }

    @Test
    void correctionPrefixIsNotCountedTwiceByUsageDeltaWhenPersistingOriginalHistory() {
        List<Message> model = CorrectionPrefixes.historyWithSingleCorrectionPrefix(correctionHistory(), PREFIX);
        AssistantMessage current = currentToolAssistant();
        int originalDelta = ContextEstimator.estimateMessages(withCurrentTool(correctionHistory(), current))
                - ContextEstimator.estimateMessages(model) - ContextEstimator.estimateMessage(current);
        int normalizedDelta = ContextEstimator.estimateMessages(withCurrentTool(model, current))
                - ContextEstimator.estimateMessages(model) - ContextEstimator.estimateMessage(current);
        setLimitBetweenL1(5_000 + normalizedDelta, 5_000 + originalDelta);

        ToolExecutionResult result = this.manager.executeToolCalls(prompt(model), responseWithUsage());

        assertOriginalPersistedAndNormalizedModel(result);
    }

    @Test
    void l1ClearsTheSameToolResultsFromModelAndPersistedHistories() {
        List<Message> model = CorrectionPrefixes.historyWithSingleCorrectionPrefix(correctionHistory(), PREFIX);
        int normalized = ContextEstimator.estimateMessages(withCurrentTool(model, currentToolAssistant()));
        setLimitAtRatio(normalized, 0.80d);

        ToolExecutionResult result = this.manager.executeToolCalls(prompt(model), responseWithoutUsage());

        List<Message> persisted = persistedHistory();
        assertThat(oldResultText(result.conversationHistory(), "old-1"))
                .startsWith(ToolResultClearing.PLACEHOLDER_PREFIX);
        assertThat(oldResultText(persisted, "old-1")).startsWith(ToolResultClearing.PLACEHOLDER_PREFIX);
        assertPrefixOriginalAndNormalized(result, persisted);
    }

    @Test
    void hardTrimAndWrapUpAreAppliedToBothModelAndPersistedHistories() {
        this.properties.getContext().setKeepToolPairs(5);
        this.properties.getContext().setKeepRecentTurns(0);
        List<Message> original = hardTrimHistory();
        this.snapshot.setMessageJson(this.codec.serializeAll(original));
        List<Message> model = CorrectionPrefixes.historyWithSingleCorrectionPrefix(original, PREFIX);
        AssistantMessage current = currentToolAssistant();
        List<Message> modelWithCurrent = withCurrentTool(model, current);
        int promptTokens = ContextEstimator.estimateMessages(model);
        int nextEst = ContextEstimator.estimateMessages(modelWithCurrent);
        HardTrim.Plan trim = HardTrim.plan(modelWithCurrent, 0, Set.of(), Set.of(RUN_ID));
        HardTrim.Plan persistedTrim = HardTrim.plan(withCurrentTool(original, current), 0, Set.of(), Set.of(RUN_ID));
        assertThat(trim.changed()).isTrue();
        assertThat(persistedTrim.droppedTurns()).isEqualTo(trim.droppedTurns());
        assertThat(turnStructure(persistedTrim.messages())).isEqualTo(turnStructure(trim.messages()));
        int afterTrim = nextEst - trim.lostTokens();
        setLimitAtRatio(afterTrim, 0.96d);
        assertThat(nextEst).isGreaterThanOrEqualTo(this.limit.get());
        assertThat(afterTrim + ContextEstimator.estimate(SpToolCallingManager.TURN_BUDGET_WRAP_UP))
                .isLessThan(this.limit.get());

        ToolExecutionResult result = this.manager.executeToolCalls(prompt(model), responseWithUsage(promptTokens));

        List<Message> persisted = persistedHistory();
        assertThat(historyText(result.conversationHistory())).doesNotContain("旧轮问题一", "旧轮问题二")
                .contains("创建任务", SpToolCallingManager.TURN_BUDGET_WRAP_UP);
        assertThat(historyText(persisted)).doesNotContain("旧轮问题一", "旧轮问题二")
                .contains("创建任务", SpToolCallingManager.TURN_BUDGET_WRAP_UP);
        assertThat(oldResultText(result.conversationHistory(), "ancient-1")).isNull();
        assertThat(oldResultText(persisted, "ancient-1")).isNull();
        assertThat(persisted.getLast().getText()).isEqualTo(SpToolCallingManager.TURN_BUDGET_WRAP_UP);
        assertThat(result.conversationHistory().getLast().getText())
                .isEqualTo(SpToolCallingManager.TURN_BUDGET_WRAP_UP);
        assertThat(persisted).hasSameSizeAs(result.conversationHistory());
        assertPrefixOriginalAndNormalized(result, persisted);
    }

    @Test
    void trimMappingFailureDoesNotCommitEarlierL1OrAnyOtherBudgetState() {
        this.properties.getContext().setKeepRecentTurns(0);
        List<Message> original = hardTrimHistory();
        this.snapshot.setMessageJson(this.codec.serializeAll(original));
        List<Message> mismatched = new ArrayList<>(original);
        mismatched.set(5, new UserMessage("模型创建任务"));
        List<Message> model = CorrectionPrefixes.historyWithSingleCorrectionPrefix(mismatched, PREFIX);
        PendingToolCall protectedCreate = new PendingToolCall();
        protectedCreate.setToolCallId("create-1");
        protectedCreate.setName("create_task");
        protectedCreate.setKind(PendingToolCall.KIND_CONFIRM);
        protectedCreate.setStatus(PendingToolCall.PENDING);
        this.pendings.put(protectedCreate.getToolCallId(), protectedCreate);
        Set<String> protectedIds = Set.of("create-1");
        AssistantMessage current = currentToolAssistant();
        List<Message> modelWithCurrent = withCurrentTool(model, current);
        int promptTokens = ContextEstimator.estimateMessages(model);
        int nextEst = ContextEstimator.estimateMessages(modelWithCurrent);
        List<Message> originalWithCurrent = withCurrentTool(original, current);
        ToolResultClearing.Plan clearing = ToolResultClearing.plan(modelWithCurrent, 1, protectedIds, Set.of());
        ToolResultClearing.Plan persistedClearing = ToolResultClearing.plan(originalWithCurrent, 1, protectedIds,
                Set.of());
        assertThat(clearing.changed()).isTrue();
        assertThat(persistedClearing.clearedIds()).isEqualTo(clearing.clearedIds());
        HardTrim.Plan modelTrim = HardTrim.plan(clearing.messages(), 0, protectedIds, Set.of(RUN_ID));
        HardTrim.Plan persistedTrim = HardTrim.plan(persistedClearing.messages(), 0, protectedIds, Set.of(RUN_ID));
        assertThat(turnStructure(modelTrim.messages())).isNotEqualTo(turnStructure(persistedTrim.messages()));
        int afterClear = nextEst - clearing.savedTokens();
        this.limit.set(afterClear);
        assertThat(afterClear).isGreaterThanOrEqualTo(this.limit.get());
        when(this.contextStore.read(SESSION_ID)).thenReturn(ContextState.EMPTY);
        this.manager.setContextStateStore(this.contextStore);
        List<String> before = List.copyOf(this.snapshot.getMessageJson());

        assertThatThrownBy(() -> this.manager.executeToolCalls(prompt(model), responseWithUsage(promptTokens)))
                .isInstanceOfSatisfying(StreamViolationException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(StreamViolationException.STATE_UNAVAILABLE));

        verify(this.contextStore, never()).write(anyString(), any(ContextState.class));
        verify(this.contextStore, never()).appendLedger(anyString(), any());
        verify(this.contextStore, never()).appendEvent(anyString(), any());
        verify(this.store, never()).markTurnBudget(anyString(), anyLong(), anyInt());
        verify(this.store, never()).save(any(RunSnapshot.class));
        assertThat(this.snapshot.getMessageJson()).isEqualTo(before);
        assertThat(this.snapshot.getRound()).isZero();
    }

    private void setUpStore() {
        when(this.store.get(RUN_ID)).thenAnswer(invocation -> this.snapshot);
        doAnswer(invocation -> {
            this.snapshot = invocation.getArgument(0);
            return null;
        }).when(this.store).save(any(RunSnapshot.class));
        doAnswer(invocation -> {
            List<PendingToolCall> batch = invocation.getArgument(2);
            for (PendingToolCall pending : batch) {
                this.pendings.put(pending.getToolCallId(), pending);
            }
            return null;
        }).when(this.store).saveSuspended(anyString(), any(), anyList(), anyList(), any(), any());
        when(this.store.pendingLenient(anyString(), anyString()))
                .thenAnswer(invocation -> this.pendings.get(invocation.getArgument(1)));
        when(this.store.pendingsLenient(anyString())).thenAnswer(invocation -> new ArrayList<>(this.pendings.values()));
        when(this.store.pendings(anyString())).thenAnswer(invocation -> new ArrayList<>(this.pendings.values()));
        when(this.store.activeRunIds(SESSION_ID)).thenReturn(Set.of(RUN_ID));
        when(this.store.ledger(RUN_ID)).thenReturn(List.of());
        when(this.store.instanceId()).thenReturn("test-instance");
        when(this.store.isWritePermitted(anyString(), anyString())).thenReturn(true);
        when(this.store.tryClaimExecution(anyString(), anyString(), anyString()))
                .thenReturn(new RunStore.ClaimResult(RunStore.ClaimOutcome.WON, 0L, 0L));
        when(this.store.isExecutionCompletedWithLedger(anyString(), any(PendingToolCall.class), anyString(), anyLong(),
                anyString(), anyBoolean())).thenAnswer(invocation -> {
                    PendingToolCall pending = invocation.getArgument(1);
                    this.pendings.put(pending.getToolCallId(), pending);
                    return true;
                });
    }

    private void setLimitBetweenL1(int normalizedNext, int originalNext) {
        int normalizedFloor = (int) Math.floor(normalizedNext * 100d / 70d) + 1;
        int originalCeiling = (int) Math.floor(originalNext * 100d / 70d);
        assertThat(normalizedFloor).as("反事实语料必须能区分规范化与重复前缀")
                .isLessThanOrEqualTo(originalCeiling);
        this.limit.set((normalizedFloor + originalCeiling) / 2);
        assertThat(normalizedNext / (double) this.limit.get()).as("规范化输入必须低于 L1").isLessThan(0.70d);
        assertThat(originalNext / (double) this.limit.get()).as("重复前缀的反事实必须达到 L1")
                .isGreaterThanOrEqualTo(0.70d).isLessThan(0.95d);
    }

    private void setLimitAtRatio(int nextEst, double ratio) {
        this.limit.set((int) Math.ceil(nextEst / ratio));
        assertThat(nextEst).isLessThan(this.limit.get());
    }

    private List<Message> persistedHistory() {
        return this.codec.deserializeAll(this.snapshot.getMessageJson());
    }

    private void assertOriginalPersistedAndNormalizedModel(ToolExecutionResult result) {
        List<Message> persisted = persistedHistory();
        assertThat(oldResultText(result.conversationHistory(), "old-1")).isEqualTo("旧结果一：任务列表 A");
        assertThat(oldResultText(persisted, "old-1")).isEqualTo("旧结果一：任务列表 A");
        assertPrefixOriginalAndNormalized(result, persisted);
    }

    private void assertPrefixOriginalAndNormalized(ToolExecutionResult result, List<Message> persisted) {
        assertThat(textOfToolCall(persisted, "create-1")).isEqualTo(PREFIX + SUFFIX);
        assertThat(textOfToolCall(result.conversationHistory(), "create-1")).isEqualTo(SUFFIX);
        assertThat(this.executed).containsExactly("c-1");
    }

    private static String historyText(List<Message> history) {
        return history.stream().map(Message::getText).filter(java.util.Objects::nonNull)
                .reduce("", String::concat);
    }

    private static List<String> turnStructure(List<Message> messages) {
        List<String> structure = new ArrayList<>();
        for (Message message : messages) {
            String runId = String.valueOf(RunMemoryMerge.runIdOf(message));
            if (message instanceof UserMessage) {
                structure.add("user:" + runId + ":" + message.getText());
            }
            if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
                for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                    structure.add("call:" + runId + ":" + call.id());
                }
            }
            if (message instanceof ToolResponseMessage toolResponse) {
                for (ToolResponseMessage.ToolResponse response : toolResponse.getResponses()) {
                    structure.add("tool:" + runId + ":" + response.id());
                }
            }
        }
        return structure;
    }

    private static List<Message> correctionHistory() {
        return correctionHistory("旧结果一：任务列表 A", "旧结果二：任务列表 B");
    }

    private static List<Message> correctionHistory(String firstOldResult, String secondOldResult) {
        List<Message> out = new ArrayList<>();
        out.add(new UserMessage("创建任务"));
        out.add(AssistantMessage.builder().content(PREFIX).build());
        out.add(AssistantMessage.builder().content(PREFIX + SUFFIX)
                .toolCalls(List.of(new AssistantMessage.ToolCall("create-1", "function", "create_task", "{}")))
                .build());
        out.add(ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("create-1", "create_task", "已拒绝创建")))
                .build());
        out.add(AssistantMessage.builder()
                .toolCalls(List.of(new AssistantMessage.ToolCall("old-1", "function", TOOL, "{}"),
                        new AssistantMessage.ToolCall("old-2", "function", TOOL, "{}")))
                .build());
        out.add(ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("old-1", TOOL, firstOldResult),
                        new ToolResponseMessage.ToolResponse("old-2", TOOL, secondOldResult)))
                .build());
        return out;
    }

    private static List<Message> hardTrimHistory() {
        List<Message> out = new ArrayList<>();
        out.add(new UserMessage("旧轮问题一"));
        out.add(AssistantMessage.builder()
                .toolCalls(List.of(new AssistantMessage.ToolCall("ancient-1", "function", TOOL, "{}")))
                .build());
        out.add(ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("ancient-1", TOOL, "旧轮结果")))
                .build());
        out.add(new UserMessage("旧轮问题二"));
        out.add(AssistantMessage.builder().content("旧轮回答" + "旧".repeat(1_500)).build());
        out.addAll(correctionHistory("旧结果一：" + "保".repeat(4_000), "旧结果二：" + "保".repeat(4_000)));
        return out;
    }

    private static AssistantMessage currentToolAssistant() {
        AssistantMessage current = AssistantMessage.builder()
                .toolCalls(List.of(new AssistantMessage.ToolCall("c-1", "function", TOOL, "{}")))
                .build();
        return ReasoningOrigin.stamp(current, 1L, "test-model");
    }

    private List<Message> withCurrentTool(List<Message> history, AssistantMessage current) {
        String result = ToolResultEnvelope.of(ToolOutcome.OK, null, "执行成功：c-1", "{}")
                .toModelJson(this.limiter, TOOL);
        List<Message> out = new ArrayList<>(history);
        out.add(current);
        out.add(ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("c-1", TOOL, result)))
                .build());
        return out;
    }

    private static Prompt prompt(List<Message> instructions) {
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolContext(Map.of("runId", RUN_ID))
                .build();
        return new Prompt(instructions, options);
    }

    private static ChatResponse responseWithoutUsage() {
        return response(ChatResponseMetadata.builder().model("test-model").build());
    }

    private static ChatResponse responseWithUsage() {
        return responseWithUsage(5_000);
    }

    private static ChatResponse responseWithUsage(int promptTokens) {
        return response(ChatResponseMetadata.builder().model("test-model")
                .usage(new DefaultUsage(promptTokens, 0, promptTokens)).build());
    }

    private static ChatResponse response(ChatResponseMetadata metadata) {
        return new ChatResponse(List.of(new Generation(currentToolAssistant())), metadata);
    }

    private static String oldResultText(List<Message> history, String toolCallId) {
        for (Message message : history) {
            if (message instanceof ToolResponseMessage toolMessage) {
                for (ToolResponseMessage.ToolResponse response : toolMessage.getResponses()) {
                    if (toolCallId.equals(response.id())) {
                        return response.responseData();
                    }
                }
            }
        }
        return null;
    }

    private static String textOfToolCall(List<Message> history, String toolCallId) {
        for (Message message : history) {
            if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()
                    && assistant.getToolCalls().stream().anyMatch(call -> toolCallId.equals(call.id()))) {
                return assistant.getText();
            }
        }
        throw new AssertionError("找不到 assistant toolCall " + toolCallId);
    }
}
