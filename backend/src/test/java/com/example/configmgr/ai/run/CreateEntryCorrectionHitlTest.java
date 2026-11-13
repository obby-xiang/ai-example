package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiConfigService;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.AiProviderRegistry;
import com.example.configmgr.ai.config.ProviderRuntime;
import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.RunContextRefresher;
import com.example.configmgr.ai.gate.SpToolCallingManager;
import com.example.configmgr.ai.gate.ToolPreflightService;
import com.example.configmgr.ai.history.ConversationTranscriptStore;
import com.example.configmgr.ai.history.SyntheticMessages;
import com.example.configmgr.ai.history.TranscriptEntry;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.slot.TaskSlotResolver;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.AiToolNames;
import com.example.configmgr.ai.tool.ContextBuilder;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.example.configmgr.ai.tools.AiTools;
import com.example.configmgr.ai.web.AiChatRequest;
import com.example.configmgr.data.repo.ConfigStagingRowRepository;
import com.example.configmgr.definition.service.DefinitionService;
import com.example.configmgr.job.entity.Job;
import com.example.configmgr.job.service.JobService;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskItem;
import com.example.configmgr.task.repo.TaskFileRepository;
import com.example.configmgr.task.service.TaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.ReasoningAwareOpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionChunk;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionFinishReason;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionMessage;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CreateEntryCorrectionHitlTest {

    private static final String RUN_ID = "run-create-entry-hitl";

    private static final String SESSION_ID = "s-create-entry-hitl";

    private static final String CALL_ID = "call-create-entry";

    private static final String ARGUMENTS = "{\"type\":\"EXPORT\",\"title\":\"raw title\",\"conditions\":{}}";

    private final InMemoryRunStore store = new InMemoryRunStore(true) {
        @Override
        public java.util.Set<String> sessionRunIds(String sessionId) {
            return java.util.Set.of();
        }
    };

    private final ObjectMapper mapper = new ObjectMapper();

    private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

    private final RunRegistry registry = TestRunRegistryFactory.create(this.store, this.mapper, null);

    private final AiProperties properties = new AiProperties();

    private final ChatClient chatClient = mock(ChatClient.class);

    private final ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);

    private final ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);

    private final AiProviderRegistry providerRegistry = mock(AiProviderRegistry.class);

    private final ChatMemory chatMemory = mock(ChatMemory.class);

    private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

    private final AiSessionService sessionService = mock(AiSessionService.class);

    private final TaskService taskService = mock(TaskService.class);

    private final JobService jobService = mock(JobService.class);

    private final DefinitionService definitionService = mock(DefinitionService.class);

    private final OpenAiApi api = mock(OpenAiApi.class);

    private final ToolActivityBeacon beacon = new ToolActivityBeacon();

    private final ConversationTranscriptStore transcript = mock(ConversationTranscriptStore.class);

    private AnnotationConfigApplicationContext toolContext;

    private ScheduledExecutorService watchdogExecutor;

    private ResilientChatService service;

    private ConfirmGate gate;

    private ResumeService resumeService;

    private ReasoningAwareOpenAiChatModel model;

    @BeforeEach
    void setUp() {
        this.properties.getPark().setEnabled(true);
        this.watchdogExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "create-entry-hitl-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        this.toolContext = new AnnotationConfigApplicationContext();
        this.toolContext.getBeanFactory().registerSingleton("definitionService", this.definitionService);
        this.toolContext.getBeanFactory().registerSingleton("taskService", this.taskService);
        this.toolContext.getBeanFactory().registerSingleton("jobService", this.jobService);
        this.toolContext.getBeanFactory().registerSingleton("runStore", this.store);
        this.toolContext.registerBean(AiTools.class);
        this.toolContext.registerBean(ToolRegistry.class);
        this.toolContext.refresh();
        ToolRegistry toolRegistry = this.toolContext.getBean(ToolRegistry.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ResumeService> resumes = mock(ObjectProvider.class);
        when(resumes.getIfAvailable()).thenAnswer(invocation -> this.resumeService);
        this.gate = new ConfirmGate(this.store, this.registry, this.properties, this.cancellations,
                this.beacon, List.of(), resumes, Runnable::run);
        ToolPreflightService preflight = new ToolPreflightService(this.jobService, mock(TaskFileRepository.class),
                mock(ConfigStagingRowRepository.class), this.definitionService);
        SpToolCallingManager manager = new SpToolCallingManager(this.store, this.registry, this.gate, toolRegistry,
                this.codec, this.beacon, this.cancellations, new ToolResultLimiter(this.properties), List.of(),
                preflight, null, null, null, this.properties);
        manager.setContextRefresher(new RunContextRefresher(this.taskService));
        ReflectionTestUtils.setField(manager, "transcript", this.transcript);
        this.model = new ReasoningAwareOpenAiChatModel(this.api,
                OpenAiChatOptions.builder().model("test-model").build(), manager, RetryTemplate.defaultInstance(),
                ObservationRegistry.NOOP, 7L, true);
        this.service = new ResilientChatService(this.providerRegistry, this.chatMemory, this.codec, this.store,
                this.registry, toolRegistry, mock(ContextBuilder.class), this.sessionService, this.properties,
                this.cancellations, this.beacon, this.watchdogExecutor);
        when(this.transcript.append(anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(true);
        when(this.transcript.readPage(anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(ConversationTranscriptStore.ReadPage.EMPTY);
        ReflectionTestUtils.setField(this.service, "transcript", this.transcript);
        this.service.setTaskSlotResolver(new TaskSlotResolver());
        ThreadPoolTaskExecutor suspendExecutor = mock(ThreadPoolTaskExecutor.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(suspendExecutor).execute(any(Runnable.class));
        this.resumeService = new ResumeService(this.store, this.registry, this.codec, this.service, manager,
                suspendExecutor, mock(StringRedisTemplate.class), this.properties, this.cancellations,
                new ToolResultLimiter(this.properties));
        this.resumeService.setContextRefresher(new RunContextRefresher(this.taskService));
        this.resumeService.setTaskSlotResolver(new TaskSlotResolver());
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
        ProviderRuntime runtime = new ProviderRuntime(7L, "openai", "test-model", "http://placeholder",
                "/chat/completions", "7|fp|u|p", OpenAiChatOptions.builder().model("test-model").build(),
                this.api, model, this.chatClient);
        when(this.providerRegistry.current()).thenReturn(runtime);
        when(this.providerRegistry.tryCurrent()).thenReturn(Optional.of(runtime));
        when(this.providerRegistry.forRequest(any(), any(), any())).thenReturn(runtime);
        when(this.providerRegistry.target(any(), anyString())).thenReturn(Optional.of(new AiConfigService.ChatTarget(
                new AiConfigService.ResolvedProvider(7L, "openai", "http://placeholder", "/chat/completions",
                        "test-model", "sk-test-0123456789", "fp", false), "test-model", true)));
        when(this.providerRegistry.forFrozenRequest(any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(runtime);
        when(this.streamSpec.chatResponse()).thenAnswer(invocation -> Flux.just(response("请先告诉我要导出的配置")));
        when(this.api.chatCompletionStream(any(ChatCompletionRequest.class), any()))
                .thenReturn(Flux.just(toolCallChunk(CALL_ID, AiToolNames.CREATE_TASK, ARGUMENTS)),
                        Flux.just(textChunk("已创建任务")));
    }

    @AfterEach
    void tearDown() {
        this.watchdogExecutor.shutdownNow();
        this.toolContext.close();
    }

    @Test
    void correctedCreateTaskPassesRawArgumentsThroughPreflightAndParksBeforeCreation() {
        AiChatRequest request = new AiChatRequest(SESSION_ID, "Create an export task",
                new AiChatRequest.Context("tasks", null, null, null, Map.of()), null, null);
        try (com.example.configmgr.ai.tenant.RequestContextUtils.SafeCloseable ignored =
                com.example.configmgr.ai.tenant.RequestContextUtils.useContext(1L, 101L)) {
            this.service.chat(RUN_ID, request, this.registry.of(RUN_ID));
        }

        RunSnapshot snapshot = this.store.get(RUN_ID);
        assertThat(snapshot.getStatus()).isEqualTo(RunSnapshot.SUSPENDED);
        assertThat(AiContext.fromMap(snapshot.getContext()).getExtra())
                .containsEntry("createEntryCorrectionUsed", true)
                .containsEntry("createEntryCorrectionPrefix", "请先告诉我要导出的配置")
                .doesNotContainKey(CorrectionPrefixes.EXPORT_CORRECTION_PREFIX_KEY);
        PendingToolCall pending = this.store.pending(RUN_ID, CALL_ID);
        assertThat(pending).isNotNull();
        assertThat(pending.getName()).isEqualTo(AiToolNames.CREATE_TASK);
        assertThat(pending.getArguments()).isEqualTo(ARGUMENTS);
        assertThat(pending.getStatus()).isEqualTo(PendingToolCall.PENDING);
        assertThat(pending.getKind()).isEqualTo(PendingToolCall.KIND_CONFIRM);
        assertThat(pending.getPreflight()).containsKey("reportHash");
        assertThat((String) pending.getPreflight().get("conditionSummary")).isNotBlank();
        verifyNoInteractions(this.taskService);
        assertThat(this.store.pendingsLenient(RUN_ID)).singleElement()
                .matches(entry -> !AiToolNames.START_EXPORT.equals(entry.getName()));

        ArgumentCaptor<ChatCompletionRequest> upstream = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        org.mockito.Mockito.verify(this.api).chatCompletionStream(upstream.capture(), any());
        List<ChatCompletionMessage> messages = upstream.getValue().messages();
        assertThat(messages).anyMatch(message -> message.role() == ChatCompletionMessage.Role.USER
                && "Create an export task".equals(message.content()));
        assertThat(messages.getLast().content()).contains(AiToolNames.CREATE_TASK)
                .contains("明确创建")
                .contains("纯问答");
        assertThat(upstream.getValue().tools()).anyMatch(tool ->
                AiToolNames.CREATE_TASK.equals(tool.getFunction().getName()));

        List<Message> modelHistory = this.codec.deserializeAll(snapshot.getMessageJson());
        assertThat(modelHistory).noneMatch(message -> SyntheticMessages.kindOf(message) == SyntheticMessages.Kind.CORRECTION);
        assertThat(modelHistory).filteredOn(message -> message instanceof AssistantMessage assistant
                && "请先告诉我要导出的配置".equals(assistant.getText())).hasSize(1);
        assertThat(snapshot.getInFlightToolCalls()).singleElement().satisfies(call -> {
            assertThat(call.getToolCallId()).isEqualTo(CALL_ID);
            assertThat(call.getArguments()).isEqualTo(ARGUMENTS);
        });

        @SuppressWarnings({ "unchecked", "rawtypes" })
        ArgumentCaptor<List<TranscriptEntry>> appended = ArgumentCaptor.forClass((Class) List.class);
        org.mockito.Mockito.verify(this.transcript, org.mockito.Mockito.atLeastOnce()).append(anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), appended.capture(),
                org.mockito.ArgumentMatchers.anyLong());
        List<TranscriptEntry> entries = appended.getAllValues().stream().flatMap(List::stream).toList();
        assertThat(entries).anyMatch(entry -> "assistant".equals(entry.kind())
                && "请先告诉我要导出的配置".equals(entry.text())
                && entry.toolCalls().stream().anyMatch(call -> CALL_ID.equals(call.id())
                && ARGUMENTS.equals(call.arguments())));
        assertThat(entries).noneMatch(entry -> SyntheticMessages.ORIGIN_SYNTHETIC.equals(entry.origin()));

        Task created = new Task();
        created.setId(42L);
        created.setType(Task.TaskType.EXPORT);
        created.setTitle("raw title");
        created.setCurrentStep("SELECT_DEFS");
        created.setStatus(Task.TaskStatus.ACTIVE);
        created.setItems(List.of());
        when(this.taskService.createIdempotent(Task.TaskType.EXPORT, "raw title", RUN_ID))
                .thenReturn(new TaskService.CreateOutcome(created, false));
        when(this.taskService.findById(42L)).thenReturn(created);
        when(this.taskService.findByIdRaw(42L)).thenReturn(Optional.of(created));

        ConfirmGate.Submission submission;
        try (com.example.configmgr.ai.tenant.RequestContextUtils.SafeCloseable ignored =
                com.example.configmgr.ai.tenant.RequestContextUtils.useContext(1L, 101L)) {
            submission = this.gate.submitDecision(RUN_ID, CALL_ID, true, "approved");
        }

        assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
        RunSnapshot done = this.store.get(RUN_ID);
        assertThat(done.getStatus()).isEqualTo(RunSnapshot.DONE);
        assertThat(AiContext.fromMap(done.getContext()).getExtra())
                .containsEntry("createEntryCorrectionUsed", true)
                .doesNotContainKeys("createEntryCorrectionPrefix", CorrectionPrefixes.EXPORT_CORRECTION_PREFIX_KEY,
                        CorrectionPrefixes.PARKED_FULL_TEXT_KEY, CorrectionPrefixes.PENDING_SEGMENT_KEY);
        org.mockito.Mockito.verify(this.taskService).createIdempotent(Task.TaskType.EXPORT, "raw title", RUN_ID);
        verifyNoInteractions(this.jobService);
        org.mockito.Mockito.verify(this.api, org.mockito.Mockito.times(2))
                .chatCompletionStream(any(ChatCompletionRequest.class), any());
        assertThat(this.store.pendingsLenient(RUN_ID)).noneMatch(entry -> AiToolNames.START_EXPORT.equals(entry.getName()));
    }

    @Test
    void importCreateTaskOmitsConditionsAndPassesPreflightIntoHitl() {
        String arguments = "{\"type\":\"IMPORT\",\"title\":\"raw import title\"}";
        when(this.api.chatCompletionStream(any(ChatCompletionRequest.class), any()))
                .thenReturn(Flux.just(toolCallChunk(CALL_ID, AiToolNames.CREATE_TASK, arguments)),
                        Flux.just(textChunk("已创建导入任务")));

        chat();

        RunSnapshot suspended = this.store.get(RUN_ID);
        assertThat(suspended.getStatus()).isEqualTo(RunSnapshot.SUSPENDED);
        PendingToolCall pending = this.store.pending(RUN_ID, CALL_ID);
        assertThat(pending.getStatus()).isEqualTo(PendingToolCall.PENDING);
        assertThat(pending.getKind()).isEqualTo(PendingToolCall.KIND_CONFIRM);
        assertThat(pending.getArguments()).isEqualTo(arguments);
        assertThat(pending.getArguments()).doesNotContain("conditions");
        assertThat(pending.getPreflight()).containsKey("reportHash");
        verifyNoInteractions(this.taskService);
        verifyNoInteractions(this.jobService);

        Task created = new Task();
        created.setId(42L);
        created.setType(Task.TaskType.IMPORT);
        created.setTitle("raw import title");
        created.setCurrentStep("SELECT_DEFS");
        created.setStatus(Task.TaskStatus.ACTIVE);
        created.setItems(List.of());
        when(this.taskService.createIdempotent(Task.TaskType.IMPORT, "raw import title", RUN_ID))
                .thenReturn(new TaskService.CreateOutcome(created, false));
        when(this.taskService.findById(42L)).thenReturn(created);
        when(this.taskService.findByIdRaw(42L)).thenReturn(Optional.of(created));

        ConfirmGate.Submission submission = submitDecision(true);

        assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
        assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.DONE);
        org.mockito.Mockito.verify(this.taskService).createIdempotent(Task.TaskType.IMPORT, "raw import title", RUN_ID);
        verifyNoInteractions(this.jobService);
        org.mockito.Mockito.verify(this.api, org.mockito.Mockito.times(2))
                .chatCompletionStream(any(ChatCompletionRequest.class), any());
    }

    @Test
    void rejectedCreateTaskDoesNotCorrectAgainOrExecute() {
        chat();

        ConfirmGate.Submission submission = submitDecision(false);

        assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
        assertThat(this.store.pending(RUN_ID, CALL_ID).getStatus()).isEqualTo(PendingToolCall.REJECTED);
        verifyNoInteractions(this.taskService);
        verifyNoInteractions(this.jobService);
        org.mockito.Mockito.verify(this.api, org.mockito.Mockito.times(2))
                .chatCompletionStream(any(ChatCompletionRequest.class), any());
        assertThat(this.store.ledger(RUN_ID)).noneMatch(record -> AiToolNames.CREATE_TASK.equals(record.get("name"))
                && Boolean.TRUE.equals(record.get("executed")));
    }

    @Test
    void cancelledCreateTaskDoesNotCorrectAgainOrExecute() {
        chat();

        try (com.example.configmgr.ai.tenant.RequestContextUtils.SafeCloseable ignored =
                com.example.configmgr.ai.tenant.RequestContextUtils.useContext(1L, 101L)) {
            this.service.cancelParked(RUN_ID);
        }

        assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.CANCELLED);
        assertThat(this.store.pending(RUN_ID, CALL_ID).getStatus()).isEqualTo(PendingToolCall.CANCELLED);
        verifyNoInteractions(this.taskService);
        verifyNoInteractions(this.jobService);
        org.mockito.Mockito.verify(this.api).chatCompletionStream(any(ChatCompletionRequest.class), any());
    }

    @Test
    void preflightFailureDoesNotCorrectAgainOrEnterConfirmGate() {
        String invalid = "{\"type\":\"EXPORT\",\"conditions\":{\"fields\":["
                + "{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"CNY\"}]}}";
        when(this.api.chatCompletionStream(any(ChatCompletionRequest.class), any()))
                .thenReturn(Flux.just(toolCallChunk(CALL_ID, AiToolNames.CREATE_TASK, invalid)),
                        Flux.just(textChunk("预检未通过")));

        chat();

        PendingToolCall pending = this.store.pending(RUN_ID, CALL_ID);
        assertThat(pending.getStatus()).isEqualTo(PendingToolCall.PRECHECK_FAILED);
        assertThat(pending.getCode()).isEqualTo(com.example.configmgr.ai.tool.ToolOutcome.CODE_PRECHECK_FAILED);
        assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.DONE);
        assertThat(this.store.archivedTypes(RUN_ID)).doesNotContain("confirm_request");
        verifyNoInteractions(this.taskService);
        verifyNoInteractions(this.jobService);
        org.mockito.Mockito.verify(this.api, org.mockito.Mockito.times(2))
                .chatCompletionStream(any(ChatCompletionRequest.class), any());
    }

    @Test
    void finalTranscriptReconcilesExistingPendingEntriesWithoutDuplicates() {
        chat();
        @SuppressWarnings({ "unchecked", "rawtypes" })
        ArgumentCaptor<List<TranscriptEntry>> appended = ArgumentCaptor.forClass((Class) List.class);
        org.mockito.Mockito.verify(this.transcript, org.mockito.Mockito.atLeastOnce()).append(anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), appended.capture(),
                org.mockito.ArgumentMatchers.anyLong());
        List<TranscriptEntry> pendingEntries = appended.getAllValues().stream().flatMap(List::stream)
                .filter(entry -> RUN_ID.equals(entry.runId())).toList();
        when(this.transcript.readPage(anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new ConversationTranscriptStore.ReadPage(pendingEntries, pendingEntries.size(), 1L,
                        null, false, false));
        stubCreatedTask();

        ConfirmGate.Submission submission = submitDecision(true);

        assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
        org.mockito.Mockito.verify(this.transcript, org.mockito.Mockito.atLeastOnce()).readPage(anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), any(),
                org.mockito.ArgumentMatchers.anyInt());
        org.mockito.Mockito.verify(this.transcript, org.mockito.Mockito.never()).remove(anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), any(),
                org.mockito.ArgumentMatchers.anyLong());
        for (List<TranscriptEntry> batch : appended.getAllValues()) {
            assertThat(batch).extracting(TranscriptEntry::entryId).doesNotHaveDuplicates();
        }
        List<TranscriptEntry> finalBatch = appended.getAllValues().getLast();
        assertThat(finalBatch).anyMatch(entry -> "assistant".equals(entry.kind())
                && "请先告诉我要导出的配置".equals(entry.text())
                && entry.toolCalls().stream().anyMatch(call -> CALL_ID.equals(call.id())));
        assertThat(finalBatch).noneMatch(entry -> SyntheticMessages.ORIGIN_SYNTHETIC.equals(entry.origin()));
    }

    @Test
    void secondToolReasoningIsDroppedWhenFirstHasNoReasoning() {
        assertSecondToolReasoningIsIsolated(null);
    }

    @Test
    void secondToolReasoningKeepsOnlyFirstReasoning() {
        assertSecondToolReasoningIsIsolated("first-reasoning");
    }

    private void assertSecondToolReasoningIsIsolated(String firstReasoning) {
        when(this.streamSpec.chatResponse()).thenAnswer(invocation -> Flux.just(
                responseWithReasoning("请先告诉我要导出的配置", firstReasoning)));
        when(this.api.chatCompletionStream(any(ChatCompletionRequest.class), any()))
                .thenReturn(Flux.just(toolCallChunkWithReasoning(CALL_ID, AiToolNames.CREATE_TASK, ARGUMENTS,
                                "second-reasoning")),
                        Flux.just(textChunk("已创建任务")));

        chat();

        RunSnapshot suspended = this.store.get(RUN_ID);
        assertThat(suspended.getStatus()).isEqualTo(RunSnapshot.SUSPENDED);
        assertThat(String.valueOf(suspended.getAssistantMetadata())).doesNotContain("second-reasoning");
        if (firstReasoning == null) {
            assertThat(suspended.getAssistantMetadata()).doesNotContainKey(ResilientChatService.REASONING_METADATA_KEY);
        } else {
            assertThat(suspended.getAssistantMetadata()).containsEntry(ResilientChatService.REASONING_METADATA_KEY,
                    firstReasoning);
        }
        verifyNoInteractions(this.taskService);

        @SuppressWarnings({ "unchecked", "rawtypes" })
        ArgumentCaptor<List<TranscriptEntry>> appended = ArgumentCaptor.forClass((Class) List.class);
        org.mockito.Mockito.verify(this.transcript, org.mockito.Mockito.atLeastOnce()).append(anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), appended.capture(),
                org.mockito.ArgumentMatchers.anyLong());
        assertThat(appended.getAllValues().stream().flatMap(List::stream))
                .allSatisfy(entry -> assertThat(String.valueOf(entry.reasoning())).doesNotContain("second-reasoning"));

        stubCreatedTask();
        ConfirmGate.Submission submission = submitDecision(true);

        assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
        org.mockito.Mockito.verify(this.taskService).createIdempotent(Task.TaskType.EXPORT, "raw title", RUN_ID);
        org.mockito.Mockito.verify(this.api, org.mockito.Mockito.times(2))
                .chatCompletionStream(any(ChatCompletionRequest.class), any());
        List<Message> recovered = this.codec.deserializeAll(this.store.get(RUN_ID).getMessageJson());
        assertThat(recovered).allSatisfy(message -> assertThat(message.getMetadata().toString())
                .doesNotContain("second-reasoning"));
        if (firstReasoning != null) {
            assertThat(recovered).anyMatch(message -> firstReasoning.equals(
                    message.getMetadata().get(ResilientChatService.REASONING_METADATA_KEY)));
        }
        ArgumentCaptor<ChatCompletionRequest> upstream = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        org.mockito.Mockito.verify(this.api, org.mockito.Mockito.times(2))
                .chatCompletionStream(upstream.capture(), any());
        assertThat(upstream.getAllValues().stream().flatMap(request -> request.messages().stream()))
                .allSatisfy(message -> assertThat(String.valueOf(message.reasoningContent()))
                        .doesNotContain("second-reasoning"));
        if (firstReasoning != null) {
            assertThat(upstream.getAllValues().stream().flatMap(request -> request.messages().stream()))
                    .anyMatch(message -> firstReasoning.equals(message.reasoningContent()));
        }
    }

    @Test
    void exportCorrectionKeepsOriginalTextAndStartExportHitl() throws Exception {
        this.properties.getPark().setEnabled(false);
        Task ready = new Task();
        ready.setId(42L);
        ready.setType(Task.TaskType.EXPORT);
        ready.setTitle("raw title");
        ready.setCurrentStep("EXPORT");
        ready.setStatus(Task.TaskStatus.ACTIVE);
        TaskItem item = new TaskItem();
        item.setDefCode("CURRENCY");
        item.setStatus("READY");
        ready.setItems(List.of(item));
        when(this.taskService.findById(42L)).thenReturn(ready);
        when(this.taskService.findByIdRaw(42L)).thenReturn(Optional.of(ready));
        when(this.jobService.findTask(42L)).thenReturn(Optional.of(ready));
        when(this.jobService.latestPrecheck(42L)).thenReturn(Optional.empty());
        Job started = new Job();
        started.setId(9L);
        started.setTaskId(42L);
        started.setJobType(Job.JobType.EXPORT);
        started.setStatus(Job.JobStatus.RUNNING);
        when(this.jobService.createAndStart(42L, Job.JobType.EXPORT)).thenReturn(started);
        PendingToolCall created = new PendingToolCall();
        created.setToolCallId("call-create-export");
        created.setName(AiToolNames.CREATE_TASK);
        created.setKind(PendingToolCall.KIND_CONFIRM);
        created.setStatus(PendingToolCall.EXECUTED);
        created.setOutcome(com.example.configmgr.ai.tool.ToolOutcome.OK.wire());
        created.setArguments("{\"type\":\"EXPORT\"}");
        created.setCreatedTaskId(42L);
        created.setExecuted(true);
        created.setResolvedAtMs(System.currentTimeMillis());
        this.store.putPending(RUN_ID, created);
        when(this.streamSpec.chatResponse()).thenAnswer(invocation -> Flux.just(response("准备导出")));
        when(this.api.chatCompletionStream(any(ChatCompletionRequest.class), any()))
                .thenReturn(Flux.just(toolCallChunk("call-start-export", AiToolNames.START_EXPORT,
                                "{\"taskId\":42}")),
                        Flux.just(textChunk("已启动导出")));
        AiChatRequest request = new AiChatRequest(SESSION_ID, "Start the export",
                new AiChatRequest.Context("export", "EXPORT", "EXPORT", 42L, "ACTIVE", Map.of()), null, null);
        FrameWire wire;
        try (com.example.configmgr.ai.tenant.RequestContextUtils.SafeCloseable ignored =
                com.example.configmgr.ai.tenant.RequestContextUtils.useContext(1L, 101L)) {
            wire = FrameWire.attachTo(this.registry.of(RUN_ID));
        }
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "export-correction-test");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<RunSnapshot> driving = executor.submit(() -> {
                try (com.example.configmgr.ai.tenant.RequestContextUtils.SafeCloseable ignored =
                        com.example.configmgr.ai.tenant.RequestContextUtils.useContext(1L, 101L)) {
                    this.service.chat(RUN_ID, request, this.registry.of(RUN_ID));
                    return this.store.get(RUN_ID);
                }
            });
            wire.awaitType("confirm_request", 5000L);
            try (com.example.configmgr.ai.tenant.RequestContextUtils.SafeCloseable ignored =
                    com.example.configmgr.ai.tenant.RequestContextUtils.useContext(1L, 101L)) {
                assertThat(this.gate.submitDecision(RUN_ID, "call-start-export", true, "approved").outcome())
                        .isEqualTo(ConfirmGate.Outcome.ACCEPTED);
            }
            assertThat(driving.get(10, TimeUnit.SECONDS).getStatus()).isEqualTo(RunSnapshot.DONE);
        } finally {
            executor.shutdownNow();
        }

        org.mockito.Mockito.verify(this.jobService).createAndStart(42L, Job.JobType.EXPORT);
        org.mockito.Mockito.verify(this.taskService, org.mockito.Mockito.never())
                .createIdempotent(any(), anyString(), anyString());
        org.mockito.Mockito.verify(this.api, org.mockito.Mockito.times(2))
                .chatCompletionStream(any(ChatCompletionRequest.class), any());
        @SuppressWarnings({ "unchecked", "rawtypes" })
        ArgumentCaptor<List<TranscriptEntry>> appended = ArgumentCaptor.forClass((Class) List.class);
        org.mockito.Mockito.verify(this.transcript, org.mockito.Mockito.atLeastOnce()).append(anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), appended.capture(),
                org.mockito.ArgumentMatchers.anyLong());
        List<TranscriptEntry> entries = appended.getAllValues().stream().flatMap(List::stream).toList();
        assertThat(entries).anyMatch(entry -> "assistant".equals(entry.kind())
                && "准备导出".equals(entry.text())
                && entry.toolCalls().stream().anyMatch(call -> "call-start-export".equals(call.id())));
        assertThat(entries).noneMatch(entry -> SyntheticMessages.ORIGIN_SYNTHETIC.equals(entry.origin()));
    }

    @Test
    void predecessorDoneDoesNotPolluteDirectCreateTaskInNewRun() {
        useRealChatClient();
        String predecessorRun = "run-predecessor-done";
        String currentRun = "run-current-direct-create";
        when(this.api.chatCompletionStream(any(ChatCompletionRequest.class), any()))
                .thenReturn(Flux.just(textChunk("前驱正文")), Flux.just(textChunk("第二跳忽略正文")),
                        Flux.just(toolCallChunkWithText(CALL_ID, AiToolNames.CREATE_TASK, ARGUMENTS, "当前正文")),
                        Flux.just(textChunk("已创建任务")));
        chat(predecessorRun, Map.of());
        RunSnapshot predecessor = this.store.get(predecessorRun);
        assertThat(predecessor.getStatus()).isEqualTo(RunSnapshot.DONE);
        Map<String, Object> reused = new java.util.LinkedHashMap<>(
                AiContext.fromMap(predecessor.getContext()).getExtra());
        reused.put("createEntryCorrectionPrefix", "前驱正文");
        reused.put(CorrectionPrefixes.EXPORT_CORRECTION_PREFIX_KEY, "前驱正文");
        reused.put(CorrectionPrefixes.PARKED_FULL_TEXT_KEY, "前驱正文");
        reused.put(CorrectionPrefixes.PENDING_SEGMENT_KEY, "前驱正文");

        chat(currentRun, reused);

        RunSnapshot suspended = this.store.get(currentRun);
        assertThat(suspended.getStatus()).isEqualTo(RunSnapshot.SUSPENDED);
        assertThat(suspended.getAssistantContent()).isEqualTo("当前正文");
        assertThat(suspended.getInFlightToolCalls()).singleElement().satisfies(call ->
                assertThat(call.getArguments()).isEqualTo(ARGUMENTS));
        stubCreatedTask(currentRun);
        ConfirmGate.Submission submission = submitDecision(currentRun, true);

        assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
        RunSnapshot done = this.store.get(currentRun);
        assertThat(done.getStatus()).isEqualTo(RunSnapshot.DONE);
        assertThat(done.getFinalText()).isEqualTo("已创建任务");
        List<AssistantMessage> toolAssistants = this.codec.deserializeAll(done.getMessageJson()).stream()
                .filter(AssistantMessage.class::isInstance).map(AssistantMessage.class::cast)
                .filter(AssistantMessage::hasToolCalls).toList();
        assertThat(AiContext.fromMap(done.getContext()).getExtra())
                .doesNotContainKeys("createEntryCorrectionUsed", "exportCorrectionUsed", "createEntryCorrectionPrefix",
                        CorrectionPrefixes.EXPORT_CORRECTION_PREFIX_KEY, CorrectionPrefixes.PARKED_FULL_TEXT_KEY,
                        CorrectionPrefixes.PENDING_SEGMENT_KEY);
        assertThat(toolAssistants).singleElement().satisfies(assistant -> {
            assertThat(assistant.getText()).isEqualTo("当前正文");
            assertThat(assistant.getToolCalls()).singleElement().satisfies(call ->
                    assertThat(call.arguments()).isEqualTo(ARGUMENTS));
        });
        org.mockito.Mockito.verify(this.taskService).createIdempotent(Task.TaskType.EXPORT, "raw title", currentRun);
        org.mockito.Mockito.verify(this.api, org.mockito.Mockito.times(4))
                .chatCompletionStream(any(ChatCompletionRequest.class), any());
        @SuppressWarnings({ "unchecked", "rawtypes" })
        ArgumentCaptor<List<TranscriptEntry>> appended = ArgumentCaptor.forClass((Class) List.class);
        org.mockito.Mockito.verify(this.transcript, org.mockito.Mockito.atLeastOnce()).append(anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), appended.capture(),
                org.mockito.ArgumentMatchers.anyLong());
        for (List<TranscriptEntry> batch : appended.getAllValues()) {
            assertThat(batch).extracting(TranscriptEntry::entryId).doesNotHaveDuplicates();
        }
        List<TranscriptEntry> currentEntries = appended.getAllValues().stream().flatMap(List::stream)
                .filter(entry -> currentRun.equals(entry.runId())).toList();
        List<TranscriptEntry> finalEntries = appended.getAllValues().getLast().stream()
                .filter(entry -> currentRun.equals(entry.runId())).toList();
        assertThat(currentEntries).anyMatch(entry -> "assistant".equals(entry.kind()) && !entry.toolCalls().isEmpty()
                && "当前正文".equals(entry.text()));
        assertThat(finalEntries).filteredOn(entry -> "assistant".equals(entry.kind()) && !entry.toolCalls().isEmpty())
                .singleElement().satisfies(entry -> assertThat(entry.toolCalls()).singleElement().satisfies(call ->
                        assertThat(call.arguments()).isEqualTo(ARGUMENTS)));
        assertThat(currentEntries).allSatisfy(entry -> assertThat(String.valueOf(entry.text())).doesNotContain("前驱正文"));
    }

    private void useRealChatClient() {
        ProviderRuntime runtime = new ProviderRuntime(7L, "openai", "test-model", "http://placeholder",
                "/chat/completions", "7|fp|u|p", OpenAiChatOptions.builder().model("test-model").build(), this.api,
                this.model, ChatClient.builder(this.model).build());
        when(this.providerRegistry.current()).thenReturn(runtime);
        when(this.providerRegistry.tryCurrent()).thenReturn(Optional.of(runtime));
        when(this.providerRegistry.forRequest(any(), any(), any())).thenReturn(runtime);
        when(this.providerRegistry.forFrozenRequest(any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(runtime);
    }

    private void chat() {
        chat(RUN_ID, Map.of());
    }

    private void chat(String runId, Map<String, Object> extra) {
        AiChatRequest request = new AiChatRequest(SESSION_ID, "Create an export task",
                new AiChatRequest.Context("tasks", null, null, null, extra), null, null);
        try (com.example.configmgr.ai.tenant.RequestContextUtils.SafeCloseable ignored =
                com.example.configmgr.ai.tenant.RequestContextUtils.useContext(1L, 101L)) {
            this.service.chat(runId, request, this.registry.of(runId));
        }
    }

    private ConfirmGate.Submission submitDecision(boolean approved) {
        return submitDecision(RUN_ID, approved);
    }

    private ConfirmGate.Submission submitDecision(String runId, boolean approved) {
        try (com.example.configmgr.ai.tenant.RequestContextUtils.SafeCloseable ignored =
                com.example.configmgr.ai.tenant.RequestContextUtils.useContext(1L, 101L)) {
            return this.gate.submitDecision(runId, CALL_ID, approved, approved ? "approved" : "rejected");
        }
    }

    private void stubCreatedTask() {
        stubCreatedTask(RUN_ID);
    }

    private void stubCreatedTask(String runId) {
        Task created = new Task();
        created.setId(42L);
        created.setType(Task.TaskType.EXPORT);
        created.setTitle("raw title");
        created.setCurrentStep("SELECT_DEFS");
        created.setStatus(Task.TaskStatus.ACTIVE);
        created.setItems(List.of());
        when(this.taskService.createIdempotent(Task.TaskType.EXPORT, "raw title", runId))
                .thenReturn(new TaskService.CreateOutcome(created, false));
        when(this.taskService.findById(42L)).thenReturn(created);
        when(this.taskService.findByIdRaw(42L)).thenReturn(Optional.of(created));
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

    private static ChatResponse responseWithReasoning(String text, String reasoning) {
        AssistantMessage.Builder assistant = AssistantMessage.builder().content(text);
        if (reasoning != null) {
            assistant.properties(Map.of(ResilientChatService.REASONING_METADATA_KEY, reasoning));
        }
        return new ChatResponse(List.of(new Generation(assistant.build(),
                org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder().finishReason("stop").build())));
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).build(),
                org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder().finishReason("stop").build())));
    }

    private static ChatCompletionChunk textChunk(String text) {
        ChatCompletionMessage delta = new ChatCompletionMessage(text, ChatCompletionMessage.Role.ASSISTANT, null,
                null, null, null, null, null, null);
        ChatCompletionChunk.ChunkChoice choice = new ChatCompletionChunk.ChunkChoice(ChatCompletionFinishReason.STOP,
                0, delta, null);
        return new ChatCompletionChunk("cmpl-create-entry-done", List.of(choice), 1750000000L, "test-model", null,
                null, "chat.completion.chunk", null);
    }

    private static ChatCompletionChunk toolCallChunkWithReasoning(String callId, String name, String arguments,
                                                                  String reasoning) {
        ChatCompletionMessage.ToolCall toolCall = new ChatCompletionMessage.ToolCall(callId, "function",
                new ChatCompletionMessage.ChatCompletionFunction(name, arguments));
        ChatCompletionMessage delta = new ChatCompletionMessage("", ChatCompletionMessage.Role.ASSISTANT, null,
                null, List.of(toolCall), null, null, null, reasoning);
        ChatCompletionChunk.ChunkChoice choice = new ChatCompletionChunk.ChunkChoice(
                ChatCompletionFinishReason.TOOL_CALLS, 0, delta, null);
        return new ChatCompletionChunk("cmpl-create-entry-reasoning", List.of(choice), 1750000000L, "test-model",
                null, null, "chat.completion.chunk", null);
    }

    private static ChatCompletionChunk toolCallChunkWithText(String callId, String name, String arguments, String text) {
        ChatCompletionMessage.ToolCall toolCall = new ChatCompletionMessage.ToolCall(callId, "function",
                new ChatCompletionMessage.ChatCompletionFunction(name, arguments));
        ChatCompletionMessage delta = new ChatCompletionMessage(text, ChatCompletionMessage.Role.ASSISTANT, null,
                null, List.of(toolCall), null, null, null, null);
        ChatCompletionChunk.ChunkChoice choice = new ChatCompletionChunk.ChunkChoice(
                ChatCompletionFinishReason.TOOL_CALLS, 0, delta, null);
        return new ChatCompletionChunk("cmpl-create-entry", List.of(choice), 1750000000L, "test-model", null,
                null, "chat.completion.chunk", null);
    }

    private static ChatCompletionChunk toolCallChunk(String callId, String name, String arguments) {
        return toolCallChunkWithText(callId, name, arguments, "");
    }
}
