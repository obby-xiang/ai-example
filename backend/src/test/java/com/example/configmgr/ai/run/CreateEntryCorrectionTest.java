package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiConfigService;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.AiProviderRegistry;
import com.example.configmgr.ai.config.ProviderRuntime;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.history.ConversationTranscriptStore;
import com.example.configmgr.ai.history.SyntheticMessages;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.slot.TaskSlotResolver;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.AiToolNames;
import com.example.configmgr.ai.tool.ContextBuilder;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.web.AiChatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreateEntryCorrectionTest {

    private static final String SESSION_ID = "s-create-entry-correction";

    private final InMemoryRunStore store = spy(new InMemoryRunStore() {
        @Override
        public java.util.Set<String> sessionRunIds(String sessionId) {
            return java.util.Set.of();
        }
    });

    private final AiProperties properties = new AiProperties();

    private final ChatModel chatModel = mock(ChatModel.class);

    private final ChatClient chatClient = mock(ChatClient.class);

    private final ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);

    private final ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);

    private final AiProviderRegistry providerRegistry = mock(AiProviderRegistry.class);

    private final ChatMemory chatMemory = mock(ChatMemory.class);

    private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

    private final AiSessionService sessionService = mock(AiSessionService.class);

    private final SseChatEmitter emitter = mock(SseChatEmitter.class);

    private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

    private final ClientRequestRegistry clientRequests = mock(ClientRequestRegistry.class);

    private ScheduledExecutorService watchdogExecutor;

    private ResilientChatService service;

    @BeforeEach
    void setUp() {
        this.watchdogExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "create-entry-correction-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        MessageJsonCodec codec = new MessageJsonCodec(new ObjectMapper());
        this.service = new ResilientChatService(this.providerRegistry, this.chatMemory, codec, this.store,
                mock(RunRegistry.class), this.toolRegistry, mock(ContextBuilder.class), this.sessionService,
                this.properties, this.cancellations, mock(ToolActivityBeacon.class), this.watchdogExecutor);
        ConversationTranscriptStore transcript = mock(ConversationTranscriptStore.class);
        when(transcript.append(anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(true);
        when(transcript.readPage(anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new ConversationTranscriptStore.ReadPage(List.of(), 0L, 1L, null, false, false));
        ReflectionTestUtils.setField(this.service, "transcript", transcript);
        ReflectionTestUtils.setField(this.service, "clientRequests", this.clientRequests);
        this.service.setTaskSlotResolver(new TaskSlotResolver());
        stubFluentChain();
        when(this.cancellations.cancelState(anyString())).thenReturn(CancellationRegistry.CancelState.NOT_CANCELLED);
        when(this.cancellations.onCancel(anyString(), any())).thenReturn(() -> {
        });
        when(this.chatMemory.get(anyString())).thenReturn(List.of());
        when(this.sessionService.ensure(anyString())).thenReturn(true);
        when(this.providerRegistry.target(org.mockito.ArgumentMatchers.nullable(String.class)))
                .thenReturn(Optional.of(new AiConfigService.ChatTarget(
                        new AiConfigService.ResolvedProvider(7L, "openai", "http://placeholder",
                                "/chat/completions", "test-model", "sk-test-0123456789", "fp", false),
                        "test-model", true)));
        when(this.providerRegistry.allowUserParams()).thenReturn(true);
        when(this.providerRegistry.supportsToolCalling(any(), any())).thenReturn(true);
        when(this.providerRegistry.current()).thenReturn(runtime());
        when(this.providerRegistry.tryCurrent()).thenReturn(Optional.of(runtime()));
        when(this.providerRegistry.forRequest(any(), any(), any())).thenReturn(runtime());
        ToolCallback createTask = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(AiToolNames.CREATE_TASK);
        when(createTask.getToolDefinition()).thenReturn(definition);
        when(this.toolRegistry.forContext(any(AiContext.class))).thenReturn(List.of(createTask));
        answers("首次原文", "第二次原文");
    }

    @AfterEach
    void tearDown() {
        this.watchdogExecutor.shutdownNow();
    }

    @Test
    void originalFirstTurnWithoutTaskOrCreateRecordNeedsOneCorrectionForBothTaskTypes() {
        for (String type : List.of("EXPORT", "IMPORT")) {
            AiContext context = AiContext.of("tasks", type, null, null, null,
                    Map.of("createTaskCorrectionUsed", true, "createTaskCorrectionPrefix", "legacy"));

            assertThat(ResilientChatService.needsCreateEntryCorrection(true, context, true, List.of(), List.of(), false))
                    .as("触发只看首轮、披露、任务绑定和创建记录；类型与 legacy 文案键均不参与")
                    .isTrue();
        }
    }

    @Test
    void boundTaskOrAnyCreateTaskRecordSuppressesCorrection() {
        AiContext bound = AiContext.of("export", "EXPORT", "SELECT_DEFS", 42L, "ACTIVE", Map.of());
        assertThat(ResilientChatService.needsCreateEntryCorrection(true, bound, true, List.of(), List.of(), false))
                .isFalse();

        PendingToolCall rejected = pending(PendingToolCall.REJECTED);
        assertThat(ResilientChatService.needsCreateEntryCorrection(true, tasks("EXPORT"), true, List.of(rejected),
                List.of(), false)).isFalse();
        PendingToolCall cancelled = pending(PendingToolCall.CANCELLED);
        assertThat(ResilientChatService.needsCreateEntryCorrection(true, tasks("IMPORT"), true, List.of(cancelled),
                List.of(), false)).isFalse();
        assertThat(ResilientChatService.needsCreateEntryCorrection(true, tasks("IMPORT"), true, List.of(),
                List.of(Map.of("name", AiToolNames.CREATE_TASK)), false)).isFalse();
    }

    @Test
    void resumedUndisclosedOrAlreadyCorrectedTurnSuppressesCorrection() {
        assertThat(ResilientChatService.needsCreateEntryCorrection(false, tasks("EXPORT"), true, List.of(), List.of(),
                false)).isFalse();
        assertThat(ResilientChatService.needsCreateEntryCorrection(true, tasks("IMPORT"), false, List.of(), List.of(),
                false)).isFalse();
        assertThat(ResilientChatService.needsCreateEntryCorrection(true, tasks("EXPORT"), true, List.of(), List.of(),
                true)).isFalse();
    }

    @Test
    void correctionInstructionUsesCurrentSchemaAndPreservesPureQuestionAnswer() {
        String instruction = ResilientChatService.createEntryCorrection();

        assertThat(instruction).contains(AiToolNames.CREATE_TASK)
                .contains("EXPORT")
                .contains("IMPORT")
                .contains("明确创建")
                .contains("纯问答")
                .contains("保持原回答")
                .contains("不得自动批准")
                .doesNotContain("start_export");
    }

    @Test
    void secondPlainAnswerFinishesWithOnlyFirstTextAndSingleCorrection() {
        chat("run-create-plain-twice", "Create an export task");

        verify(this.chatClient).prompt();
        verify(this.chatModel, times(1)).stream(any(Prompt.class));
        RunSnapshot snapshot = this.store.get("run-create-plain-twice");
        assertThat(snapshot.getStatus()).isEqualTo(RunSnapshot.DONE);
        assertThat(snapshot.getFinalText()).isEqualTo("首次原文");
        assertThat(AiContext.fromMap(snapshot.getContext()).getExtra())
                .containsEntry("createEntryCorrectionUsed", true)
                .doesNotContainKey("createTaskCorrectionUsed");
        verify(this.emitter, times(1)).delta("首次原文");
        verify(this.emitter, never()).delta("第二次原文");
        assertThat(this.store.pendingsLenient("run-create-plain-twice")).isEmpty();

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(this.chatModel).stream(prompt.capture());
        List<Message> history = prompt.getValue().getInstructions();
        assertThat(history).anyMatch(message -> message instanceof UserMessage user
                && "Create an export task".equals(user.getText()) && !SyntheticMessages.isSynthetic(user));
        assertThat(history.getLast()).isInstanceOfSatisfying(UserMessage.class, correction -> {
            assertThat(SyntheticMessages.kindOf(correction)).isEqualTo(SyntheticMessages.Kind.CORRECTION);
            assertThat(correction.getText()).contains(AiToolNames.CREATE_TASK);
        });
        ToolCallingChatOptions options = (ToolCallingChatOptions) prompt.getValue().getOptions();
        assertThat(options.getToolCallbacks()).singleElement().satisfies(callback ->
                assertThat(callback.getToolDefinition().name()).isEqualTo(AiToolNames.CREATE_TASK));
    }

    @Test
    void pureQuestionHasNoCreationCardOrDuplicateVisibleAnswer() {
        chat("run-create-question", "How do I create a task?");

        verify(this.chatModel, times(1)).stream(any(Prompt.class));
        RunSnapshot snapshot = this.store.get("run-create-question");
        assertThat(snapshot.getStatus()).isEqualTo(RunSnapshot.DONE);
        assertThat(snapshot.getFinalText()).isEqualTo("首次原文");
        assertThat(this.store.pendingsLenient("run-create-question")).isEmpty();
        verify(this.emitter, times(1)).delta("首次原文");
        verify(this.emitter, never()).delta("第二次原文");
    }

    @Test
    void nonPlainStopDoesNotRequestCreateCorrection() {
        when(this.streamSpec.chatResponse()).thenAnswer(invocation -> Flux.just(response("截断", "length")));

        chat("run-create-length", "Create an import task");

        verify(this.chatModel, never()).stream(any(Prompt.class));
        assertThat(this.store.get("run-create-length").getStatus()).isEqualTo(RunSnapshot.DONE);
        assertThat(this.store.get("run-create-length").getFinalText()).isEqualTo("截断");
        assertThat(this.store.pendingsLenient("run-create-length")).isEmpty();
    }

    @Test
    void cancelledFirstTurnDoesNotRequestCreateCorrection() {
        when(this.cancellations.cancelState(anyString())).thenReturn(CancellationRegistry.CancelState.CANCELLED);

        chat("run-create-cancelled", "Create an export task");

        verify(this.chatClient, never()).prompt();
        verify(this.chatModel, never()).stream(any(Prompt.class));
        assertThat(this.store.pendingsLenient("run-create-cancelled")).isEmpty();
    }

    @Test
    void secondReasoningDoesNotEnterFinalAssistantOrReplay() {
        when(this.streamSpec.chatResponse()).thenAnswer(invocation -> Flux.just(
                responseWithReasoning("首次原文", "stop", "first-reasoning")));
        when(this.chatModel.stream(any(Prompt.class))).thenAnswer(invocation -> Flux.just(
                responseWithReasoning("第二次原文", "stop", "second-reasoning")));

        chat("run-create-second-reasoning", "Create an export task");

        RunSnapshot snapshot = this.store.get("run-create-second-reasoning");
        List<Message> history = new MessageJsonCodec(new ObjectMapper()).deserializeAll(snapshot.getMessageJson());
        assertThat(history).filteredOn(message -> message instanceof AssistantMessage assistant
                && "首次原文".equals(assistant.getText())).singleElement().satisfies(message -> {
            AssistantMessage assistant = (AssistantMessage) message;
            assertThat(assistant.getMetadata()).containsEntry(ResilientChatService.REASONING_METADATA_KEY,
                    "first-reasoning");
            assertThat(assistant.getMetadata().get(ResilientChatService.REASONING_METADATA_KEY).toString())
                    .doesNotContain("second-reasoning");
        });
        assertThat(snapshot.getFinalText()).isEqualTo("首次原文");
    }

    @Test
    void rejectedRecordWithPendingReadFailureDoesNotCorrect() {
        String runId = "run-create-pending-fault";
        this.store.putPending(runId, pending(PendingToolCall.REJECTED));
        doReturn(List.of()).when(this.store).pendingsLenient(runId);
        doThrow(new IllegalStateException("strict pending read failed")).when(this.store).pendings(runId);

        chat(runId, "Create an export task");

        verify(this.chatModel, never()).stream(any(Prompt.class));
        assertThat(this.store.pendingsLenient(runId)).hasSize(0);
        assertThat(this.store.get(runId).getStatus()).isEqualTo(RunSnapshot.DONE);
    }

    @Test
    void cancelledRecordWithPendingReadFailureDoesNotCorrect() {
        String runId = "run-create-cancelled-pending-fault";
        this.store.putPending(runId, pending(PendingToolCall.CANCELLED));
        doReturn(List.of()).when(this.store).pendingsLenient(runId);
        doThrow(new IllegalStateException("strict pending read failed")).when(this.store).pendings(runId);

        chat(runId, "Create an import task");

        verify(this.chatModel, never()).stream(any(Prompt.class));
        assertThat(this.store.get(runId).getStatus()).isEqualTo(RunSnapshot.DONE);
    }

    @Test
    void ledgerReadFailureDoesNotCorrect() {
        String runId = "run-create-ledger-fault";
        doThrow(new IllegalStateException("strict ledger read failed")).when(this.store).ledger(runId);

        chat(runId, "Create an export task");

        verify(this.chatModel, never()).stream(any(Prompt.class));
        assertThat(this.store.get(runId).getStatus()).isEqualTo(RunSnapshot.DONE);
        assertThat(this.store.pendingsLenient(runId)).isEmpty();
    }

    @Test
    void markerReadFailureDoesNotCorrect() {
        String runId = "run-create-marker-fault";
        Map<String, Object> extra = new java.util.LinkedHashMap<>() {
            @Override
            public java.util.Set<Map.Entry<String, Object>> entrySet() {
                throw new IllegalStateException("marker read failed");
            }
        };
        extra.put("createEntryCorrectionUsed", false);
        RunSnapshot snapshot = new RunSnapshot();
        snapshot.setRunId(runId);
        snapshot.setSessionId(SESSION_ID);
        snapshot.setContext(Map.of("page", "tasks", "taskType", "EXPORT", "extra", extra));
        doReturn(snapshot).when(this.store).get(runId);

        Boolean due;
        try (com.example.configmgr.ai.tenant.RequestContextUtils.SafeCloseable ignored =
                com.example.configmgr.ai.tenant.RequestContextUtils.useContext(1L, 101L)) {
            due = ReflectionTestUtils.invokeMethod(this.service, "createEntryCorrectionDue", runId,
                    tasks("EXPORT"), true);
        }

        assertThat(due).isFalse();
        verify(this.chatModel, never()).stream(any(Prompt.class));
    }

    @Test
    void contextAndMarkerReadFailureDoesNotCorrect() {
        String runId = "run-create-context-fault";
        RunSnapshot broken = mock(RunSnapshot.class);
        when(broken.getContext()).thenThrow(new IllegalStateException("context read failed"));
        doReturn(broken).when(this.store).get(runId);

        Boolean due;
        try (com.example.configmgr.ai.tenant.RequestContextUtils.SafeCloseable ignored =
                com.example.configmgr.ai.tenant.RequestContextUtils.useContext(1L, 101L)) {
            due = ReflectionTestUtils.invokeMethod(this.service, "createEntryCorrectionDue", runId,
                    tasks("EXPORT"), true);
        }

        assertThat(due).isFalse();
        verify(this.chatModel, never()).stream(any(Prompt.class));
    }

    @Test
    void cancelAfterFirstStopBeforeMarkerPreventsSecondUpstream() {
        AtomicBoolean cancelled = new AtomicBoolean();
        when(this.cancellations.cancelState(anyString())).thenAnswer(invocation -> cancelled.get()
                ? CancellationRegistry.CancelState.CANCELLED : CancellationRegistry.CancelState.NOT_CANCELLED);
        when(this.streamSpec.chatResponse()).thenAnswer(invocation -> Flux.just(response("首次原文", "stop"))
                .doOnComplete(() -> cancelled.set(true)));

        chat("run-create-cancel-before-marker", "Create an export task");

        verify(this.chatModel, never()).stream(any(Prompt.class));
        assertThat(this.store.get("run-create-cancel-before-marker").getStatus()).isEqualTo(RunSnapshot.CANCELLED);
        assertThat(AiContext.fromMap(this.store.get("run-create-cancel-before-marker").getContext()).getExtra())
                .doesNotContainKey("createEntryCorrectionUsed");
        assertThat(this.store.pendingsLenient("run-create-cancel-before-marker")).isEmpty();
    }

    @Test
    void clientCancelAfterMarkerBeforeStreamPreventsSecondUpstream() {
        String clientRequestId = "req-after-marker";
        AtomicBoolean cancelled = new AtomicBoolean();
        when(this.clientRequests.isCancelled(SESSION_ID, clientRequestId)).thenAnswer(invocation -> cancelled.get());
        doAnswer(invocation -> {
            invocation.callRealMethod();
            RunSnapshot saved = invocation.getArgument(0);
            if (Boolean.TRUE.equals(AiContext.fromMap(saved.getContext()).getExtra().get("createEntryCorrectionUsed"))) {
                cancelled.set(true);
            }
            return null;
        }).when(this.store).save(any(RunSnapshot.class));

        chat("run-create-cancel-after-marker", "Create an export task", clientRequestId);

        verify(this.chatModel, never()).stream(any(Prompt.class));
        assertThat(this.store.get("run-create-cancel-after-marker").getStatus()).isEqualTo(RunSnapshot.CANCELLED);
        assertThat(this.store.pendingsLenient("run-create-cancel-after-marker")).isEmpty();
    }

    private void answers(String first, String second) {
        when(this.streamSpec.chatResponse()).thenAnswer(invocation -> Flux.just(response(first, "stop")));
        when(this.chatModel.stream(any(Prompt.class))).thenAnswer(invocation -> Flux.just(response(second, "stop")));
    }

    private void chat(String runId, String message) {
        chat(runId, message, null);
    }

    private void chat(String runId, String message, String clientRequestId) {
        AiChatRequest request = new AiChatRequest(SESSION_ID, message,
                new AiChatRequest.Context("tasks", null, null, null, Map.of()), null, null, null, clientRequestId);
        try (com.example.configmgr.ai.tenant.RequestContextUtils.SafeCloseable ignored =
                com.example.configmgr.ai.tenant.RequestContextUtils.useContext(1L, 101L)) {
            this.service.chat(runId, request, this.emitter);
        }
    }

    private void stubFluentChain() {
        when(this.chatClient.prompt()).thenReturn(this.requestSpec);
        when(this.requestSpec.system(any(String.class))).thenReturn(this.requestSpec);
        when(this.requestSpec.user(any(String.class))).thenReturn(this.requestSpec);
        when(this.requestSpec.advisors(any(java.util.function.Consumer.class))).thenReturn(this.requestSpec);
        when(this.requestSpec.toolContext(any(Map.class))).thenReturn(this.requestSpec);
        when(this.requestSpec.toolCallbacks(any(List.class))).thenReturn(this.requestSpec);
        when(this.requestSpec.stream()).thenReturn(this.streamSpec);
    }

    private ProviderRuntime runtime() {
        return new ProviderRuntime(7L, "openai", "test-model", "http://placeholder", "/chat/completions",
                "7|fp|u|p", org.springframework.ai.openai.OpenAiChatOptions.builder().model("test-model").build(),
                null, this.chatModel, this.chatClient);
    }

    private static ChatResponse responseWithReasoning(String text, String finishReason, String reasoning) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text)
                .properties(Map.of(ResilientChatService.REASONING_METADATA_KEY, reasoning)).build(),
                org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder().finishReason(finishReason)
                        .build())));
    }

    private static ChatResponse response(String text, String finishReason) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).build(),
                org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder().finishReason(finishReason)
                        .build())));
    }

    private static AiContext tasks(String type) {
        return AiContext.of("tasks", type, null, null, null, Map.of());
    }

    private static PendingToolCall pending(String status) {
        PendingToolCall pending = new PendingToolCall();
        pending.setToolCallId("call-create");
        pending.setName(AiToolNames.CREATE_TASK);
        pending.setStatus(status);
        pending.setArguments("{\"type\":\"EXPORT\",\"conditions\":{}}");
        pending.setOutcome(ToolOutcome.REJECTED.wire());
        return pending;
    }
}
