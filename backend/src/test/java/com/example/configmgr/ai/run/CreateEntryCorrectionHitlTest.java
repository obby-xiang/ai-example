package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiConfigService;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.AiProviderRegistry;
import com.example.configmgr.ai.config.ProviderRuntime;
import com.example.configmgr.ai.conformance.FrameWire;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.CreateTaskEvidenceResolver;
import com.example.configmgr.ai.gate.CreateTaskReferenceResolver;
import com.example.configmgr.ai.gate.RunContextRefresher;
import com.example.configmgr.ai.gate.SpToolCallingManager;
import com.example.configmgr.ai.gate.ToolPreflightService;
import com.example.configmgr.ai.history.ConversationTranscriptStore;
import com.example.configmgr.ai.history.SyntheticMessages;
import com.example.configmgr.ai.history.TranscriptEntry;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.slot.SlotDefinition;
import com.example.configmgr.ai.slot.TaskSlotResolver;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.AiToolNames;
import com.example.configmgr.ai.tool.ContextBuilder;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.ai.tool.ToolResultEnvelope;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.example.configmgr.ai.tool.ToolResultUserText;
import com.example.configmgr.ai.tools.AiTools;
import com.example.configmgr.ai.web.AiChatRequest;
import com.example.configmgr.data.entity.ConfigDataRow;
import com.example.configmgr.data.repo.ConfigDataRowRepository;
import com.example.configmgr.data.repo.ConfigStagingRowRepository;
import com.example.configmgr.definition.entity.ConfigDefinition;
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
import org.springframework.ai.chat.messages.ToolResponseMessage;
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

    private static final String CORRECTED_CALL_ID = "call-corrected-entry";

    private static final String UNRESOLVED_ARGUMENTS = "{\"type\":\"EXPORT\",\"defCodes\":[\"cny货币字典\"],\"conditions\":{}}";

    private static final String ARGUMENTS = "{\"type\":\"EXPORT\",\"title\":\"raw title\",\"defCodes\":[],\"conditions\":{\"fields\":[]}}";

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

    private final ConfigDataRowRepository dataRows = mock(ConfigDataRowRepository.class);

    private final OpenAiApi api = mock(OpenAiApi.class);

    private final ToolActivityBeacon beacon = new ToolActivityBeacon();

    private final ConversationTranscriptStore transcript = mock(ConversationTranscriptStore.class);

    private AnnotationConfigApplicationContext toolContext;

    private ScheduledExecutorService watchdogExecutor;

    private ResilientChatService service;

    private SpToolCallingManager manager;

    private ToolPreflightService preflight;

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
        this.preflight = org.mockito.Mockito.spy(new ToolPreflightService(this.jobService, mock(TaskFileRepository.class),
                mock(ConfigStagingRowRepository.class), this.definitionService));
        this.manager = new SpToolCallingManager(this.store, this.registry, this.gate, toolRegistry,
                this.codec, this.beacon, this.cancellations, new ToolResultLimiter(this.properties), List.of(),
                preflight, null, null, null, this.properties);
        CreateTaskReferenceResolver referenceResolver = new CreateTaskReferenceResolver(() -> List.of(
                new SlotDefinition("CURRENCY", "货币字典", List.of(
                        new SlotDefinition.SlotField("code", "货币代码", true, List.of())))), this.mapper,
                this.definitionService, this.dataRows);
        manager.setCreateTaskReferenceResolver(referenceResolver);
        manager.setCreateTaskEvidenceResolver(new CreateTaskEvidenceResolver(() -> List.of(
                new SlotDefinition("CURRENCY", "货币字典", List.of(
                        new SlotDefinition.SlotField("code", "货币代码", true, List.of())))),
                this.mapper, this.definitionService, this.dataRows));
        ConfigDefinition currency = new ConfigDefinition();
        currency.setCode("CURRENCY");
        currency.setName("货币字典");
        currency.setLevel(ConfigDefinition.ConfigLevel.GLOBAL);
        com.example.configmgr.definition.entity.ConfigField code = new com.example.configmgr.definition.entity.ConfigField();
        code.setCode("code");
        code.setLabel("货币代码");
        currency.setFields(List.of(code));
        when(this.definitionService.findByCode("CURRENCY")).thenReturn(currency);
        ConfigDataRow cny = new ConfigDataRow();
        cny.setRowKey("CNY");
        when(this.dataRows.findRow("CURRENCY", "GLOBAL", null, "CNY")).thenReturn(Optional.of(cny));
        when(this.dataRows.findRowsInScopePaged(org.mockito.ArgumentMatchers.eq("CURRENCY"),
                org.mockito.ArgumentMatchers.eq("GLOBAL"), org.mockito.ArgumentMatchers.isNull(),
                any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(cny),
                        org.springframework.data.domain.Pageable.ofSize(51), 1));
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
        ReflectionTestUtils.setField(this.service, "createTaskReferenceResolver", referenceResolver);
        ReflectionTestUtils.setField(this.service, "definitionCatalog", (com.example.configmgr.ai.slot.DefinitionCatalog)
                () -> List.of(new SlotDefinition("CURRENCY", "货币字典", List.of(
                        new SlotDefinition.SlotField("code", "货币代码", true, List.of())))));
        ReflectionTestUtils.setField(this.service, "objectMapper", this.mapper);
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
        when(this.providerRegistry.forReasoningRecovery(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(this.providerRegistry.target(any(), anyString())).thenReturn(Optional.of(new AiConfigService.ChatTarget(
                new AiConfigService.ResolvedProvider(7L, "openai", "http://placeholder", "/chat/completions",
                        "test-model", "sk-test-0123456789", "fp", false), "test-model", true)));
        when(this.providerRegistry.forFrozenRequest(any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyBoolean(), any(), any())).thenReturn(runtime);
        when(this.streamSpec.chatResponse()).thenAnswer(invocation -> Flux.just(response("请先告诉我要导出的配置")));
        when(this.api.chatCompletionStream(any(ChatCompletionRequest.class), any()))
                .thenReturn(Flux.just(toolCallChunkWithText(CALL_ID, AiToolNames.CREATE_TASK, ARGUMENTS,
                                "请先告诉我要导出的配置")),
                        Flux.just(textChunk("已创建任务")));
    }

    @AfterEach
    void tearDown() {
        this.watchdogExecutor.shutdownNow();
        this.toolContext.close();
    }

    @Test
    void unresolvedOriginalSentenceTriggersOneCorrectionAndCanonicalizesBeforeHitl() throws Exception {
        useRealChatClient();
        useEmptyEvidence();
        String original = "{\"type\":\"EXPORT\",\"defCodes\":[\"cny货币字典\"],\"conditions\":{}}";
        String corrected = "{\"type\":\"EXPORT\",\"title\":\"raw title\",\"defCodes\":[\"货币字典\"],"
                + "\"conditions\":{\"fields\":[{\"fieldCode\":\"货币代码\",\"operator\":\"EQ\",\"value\":\"cny\"}]}}";
        when(this.api.chatCompletionStream(any(ChatCompletionRequest.class), any()))
                .thenReturn(Flux.just(toolCallChunk(CALL_ID, AiToolNames.CREATE_TASK, original)),
                        Flux.just(toolCallChunk(CORRECTED_CALL_ID, AiToolNames.CREATE_TASK, corrected)),
                        Flux.just(textChunk("已创建任务")));

        chat();

        RunSnapshot snapshot = this.store.get(RUN_ID);
        assertThat(snapshot.getStatus()).isEqualTo(RunSnapshot.SUSPENDED);
        assertThat(snapshot.getAssistantContent()).isBlank();
        assertThat(AiContext.fromMap(snapshot.getContext()).getExtra())
                .containsEntry("createEntryCorrectionUsed", true);
        assertOriginalUnresolved();
        assertThat(this.store.archivedTypes(RUN_ID)).doesNotContain("tool_result");
        assertThat(snapshot.getMessageJson().toString()).contains(CreateTaskReferenceResolver.UNRESOLVED_CODE);
        PendingToolCall pending = this.store.pending(RUN_ID, CORRECTED_CALL_ID);
        assertThat(pending.getStatus()).isEqualTo(PendingToolCall.PENDING);
        assertThat(pending.getArguments()).contains("\"defCodes\":[\"CURRENCY\"]")
                .contains("\"fieldCode\":\"code\"").contains("\"value\":\"CNY\"");
        assertThat(pending.getPreflight()).containsKey("reportHash");
        assertThat(this.store.archivedTypes(RUN_ID)).contains("confirm_request")
                .allMatch(type -> !type.contains("form"));
        verifyNoInteractions(this.taskService);
        ArgumentCaptor<ChatCompletionRequest> upstream = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        org.mockito.Mockito.verify(this.api, org.mockito.Mockito.times(2))
                .chatCompletionStream(upstream.capture(), any());
        ChatCompletionRequest correction = upstream.getAllValues().get(1);
        assertThat(correction.messages().getLast().content()).contains("cny货币字典")
                .contains("CURRENCY").contains("货币代码").contains("unresolved")
                .contains("createTaskSchema").contains("defCodes")
                .doesNotContain("submit_action_plan");
        assertThat(correction.toolChoice()).isNull();

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
        ConfirmGate.Submission submission = submitDecision(RUN_ID, CORRECTED_CALL_ID, true);

        assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
        assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.DONE);
        org.mockito.Mockito.verify(this.taskService).createIdempotent(Task.TaskType.EXPORT, "raw title", RUN_ID);
        org.mockito.Mockito.verify(this.api, org.mockito.Mockito.times(3))
                .chatCompletionStream(any(ChatCompletionRequest.class), any());
        assertTemporaryMarkerAbsent(this.store.get(RUN_ID));
    }

    @Test
    void resolvedCreateTaskParksDirectlyWithoutCorrection() {
        useRealChatClient();
        when(this.api.chatCompletionStream(any(ChatCompletionRequest.class), any()))
                .thenReturn(Flux.just(toolCallChunk(CALL_ID, AiToolNames.CREATE_TASK, ARGUMENTS)));

        chat();

        RunSnapshot snapshot = this.store.get(RUN_ID);
        assertThat(snapshot.getStatus()).isEqualTo(RunSnapshot.SUSPENDED);
        assertThat(AiContext.fromMap(snapshot.getContext()).getExtra())
                .doesNotContainKey("createEntryCorrectionUsed");
        assertThat(this.store.pending(RUN_ID, CALL_ID).getStatus()).isEqualTo(PendingToolCall.PENDING);
        assertThat(this.store.archivedTypes(RUN_ID)).contains("confirm_request");
        verifyNoInteractions(this.taskService);
        org.mockito.Mockito.verify(this.api).chatCompletionStream(any(ChatCompletionRequest.class), any());
    }

    @Test
    void failedCorrectionStopsWithoutSecondCorrectionCompensationCardOrTask() {
        useRealChatClient();
        useEmptyEvidence();
        String unresolved = "{\"type\":\"EXPORT\",\"defCodes\":[\"cny货币字典\"],\"conditions\":{}}";
        when(this.api.chatCompletionStream(any(ChatCompletionRequest.class), any()))
                .thenReturn(Flux.just(toolCallChunk(CALL_ID, AiToolNames.CREATE_TASK, unresolved)),
                        Flux.just(toolCallChunk("call-corrected", AiToolNames.CREATE_TASK, unresolved)));

        chat();

        RunSnapshot snapshot = this.store.get(RUN_ID);
        assertThat(snapshot.getStatus()).isEqualTo(RunSnapshot.FAILED);
        assertThat(AiContext.fromMap(snapshot.getContext()).getExtra())
                .containsEntry("createEntryCorrectionUsed", true);
        assertThat(this.store.pendingsLenient(RUN_ID)).hasSize(2)
                .allSatisfy(pending -> {
                    assertThat(pending.getCode()).isEqualTo(CreateTaskReferenceResolver.UNRESOLVED_CODE);
                    assertThat(pending.getStatus()).isEqualTo(PendingToolCall.PRECHECK_FAILED);
                    assertThat(pending.isExecuted()).isFalse();
                });
        assertThat(this.store.archivedTypes(RUN_ID)).doesNotContain("confirm_request")
                .allMatch(type -> !type.contains("form"))
                .contains("tool_result");
        assertThat(this.store.pending(RUN_ID, "call-corrected").isTranscriptSegmentRequired()).isTrue();
        verifyNoInteractions(this.taskService);
        org.mockito.Mockito.verify(this.api, org.mockito.Mockito.times(2))
                .chatCompletionStream(any(ChatCompletionRequest.class), any());
    }

    @Test
    void correctedMissingTypeFailsClosedWithoutTypeFormOrNextHop() {
        useRealChatClient();
        useEmptyEvidence();
        com.example.configmgr.ai.form.TaskTypeForm taskTypeForm = mock(com.example.configmgr.ai.form.TaskTypeForm.class);
        this.manager.setTaskTypeForm(taskTypeForm);
        unresolvedThenCorrected(AiToolNames.CREATE_TASK, "{\"defCodes\":[],\"conditions\":{}}");

        chat();

        assertFailedWithoutFollowUp();
        assertOriginalUnresolved();
        PendingToolCall corrected = this.store.pending(RUN_ID, CORRECTED_CALL_ID);
        assertThat(corrected.getStatus()).isEqualTo(PendingToolCall.PRECHECK_FAILED);
        assertThat(corrected.getCode()).isEqualTo(CreateTaskReferenceResolver.FAILED_CODE);
        verifyNoInteractions(taskTypeForm);
    }

    @Test
    void correctedOtherToolFailsClosedWithoutExecutionOrNextHop() {
        this.properties.getResilience().setTotalBudget(java.time.Duration.ofSeconds(2));
        useRealChatClient();
        useEmptyEvidence();
        unresolvedThenCorrected(AiToolNames.LIST_TASKS, "{}");

        chat();

        assertFailedWithoutFollowUp();
        assertOriginalUnresolved();
        PendingToolCall corrected = this.store.pending(RUN_ID, CORRECTED_CALL_ID);
        assertThat(corrected.getStatus()).isEqualTo(PendingToolCall.PRECHECK_FAILED);
        assertThat(corrected.getCode()).isEqualTo(CreateTaskReferenceResolver.FAILED_CODE);
        assertThat(corrected.isExecuted()).isFalse();
    }

    @Test
    void correctedMultipleToolsFailClosedWithoutHitl() {
        this.properties.getResilience().setTotalBudget(java.time.Duration.ofSeconds(2));
        useRealChatClient();
        useEmptyEvidence();
        when(this.api.chatCompletionStream(any(ChatCompletionRequest.class), any()))
                .thenReturn(Flux.just(toolCallChunk(CALL_ID, AiToolNames.CREATE_TASK, UNRESOLVED_ARGUMENTS)),
                        Flux.just(toolCallChunk(List.of(
                                new ChatCompletionMessage.ToolCall(CORRECTED_CALL_ID, "function",
                                        new ChatCompletionMessage.ChatCompletionFunction(AiToolNames.CREATE_TASK, ARGUMENTS)),
                                new ChatCompletionMessage.ToolCall("call-other-tool", "function",
                                        new ChatCompletionMessage.ChatCompletionFunction(AiToolNames.LIST_TASKS, "{}"))))));

        chat();

        assertFailedWithoutFollowUp();
        assertOriginalUnresolved();
        PendingToolCall corrected = this.store.pending(RUN_ID, CORRECTED_CALL_ID);
        assertThat(corrected.getStatus()).isEqualTo(PendingToolCall.PRECHECK_FAILED);
        assertThat(corrected.getCode()).isEqualTo(CreateTaskReferenceResolver.FAILED_CODE);
        PendingToolCall other = this.store.pending(RUN_ID, "call-other-tool");
        assertThat(other.getStatus()).isEqualTo(PendingToolCall.SKIPPED);
        assertThat(other.isExecuted()).isFalse();
    }

    @Test
    void referencesUnresolvedCorrectionWithEmptyConditionsStillFailsClosed() throws Exception {
        useRealChatClient();
        useEmptyEvidence();
        unresolvedThenCorrected(AiToolNames.CREATE_TASK,
                "{\"type\":\"EXPORT\",\"title\":\"raw title\",\"defCodes\":[],\"conditions\":{}}");

        chat();

        assertFailedWithoutFollowUp();
        assertOriginalUnresolved();
        PendingToolCall corrected = this.store.pending(RUN_ID, CORRECTED_CALL_ID);
        assertThat(corrected.getStatus()).isEqualTo(PendingToolCall.PRECHECK_FAILED);
        assertThat(corrected.getCode()).isEqualTo(CreateTaskReferenceResolver.FAILED_CODE);
        assertTemporaryMarkerAbsent(this.store.get(RUN_ID));
    }

    @Test
    void correctedResolutionFailureReturnsDirectWithoutNextHopOrVisibleToolJson() throws Exception {
        this.properties.getResilience().setTotalBudget(java.time.Duration.ofSeconds(2));
        useRealChatClient();
        unresolvedThenCorrected(AiToolNames.CREATE_TASK,
                "{\"type\":\"EXPORT\",\"defCodes\":null,\"conditions\":{}}");

        chat();

        assertFailedWithoutFollowUp();
        assertOriginalUnresolved();
        PendingToolCall corrected = this.store.pending(RUN_ID, CORRECTED_CALL_ID);
        assertThat(corrected.getStatus()).isEqualTo(PendingToolCall.PRECHECK_FAILED);
        assertThat(corrected.getCode()).isEqualTo(CreateTaskReferenceResolver.FAILED_CODE);
        assertThat(this.store.get(RUN_ID).getAssistantContent()).isBlank();
        assertTemporaryMarkerAbsent(this.store.get(RUN_ID));
    }

    @Test
    void correctedResolvedPreflightFailureReturnsDirectWithoutNextHop() throws Exception {
        this.properties.getResilience().setTotalBudget(java.time.Duration.ofSeconds(2));
        useRealChatClient();
        useEmptyEvidence();
        ToolPreflightService.PreflightReport failure = new ToolPreflightService.PreflightReport(false,
                Map.of("type", "EXPORT"), List.of(new ToolPreflightService.PreflightReport.Check(
                        "forced preflight failure", false, "corrected preflight must stop")),
                "forced", "", null, "forced-report");
        org.mockito.Mockito.doReturn(failure).when(this.preflight)
                .inspect(org.mockito.ArgumentMatchers.eq(AiToolNames.CREATE_TASK),
                        org.mockito.ArgumentMatchers.eq(ARGUMENTS), any(AiContext.class));
        unresolvedThenCorrected(AiToolNames.CREATE_TASK, ARGUMENTS);
        FrameWire wire;
        try (com.example.configmgr.ai.tenant.RequestContextUtils.SafeCloseable ignored =
                com.example.configmgr.ai.tenant.RequestContextUtils.useContext(1L, 101L)) {
            wire = FrameWire.attachTo(this.registry.of(RUN_ID));
        }

        chat();

        assertFailedWithoutFollowUp();
        assertOriginalUnresolved();
        PendingToolCall corrected = this.store.pending(RUN_ID, CORRECTED_CALL_ID);
        assertThat(corrected.getStatus()).isEqualTo(PendingToolCall.PRECHECK_FAILED);
        assertThat(corrected.getCode()).isEqualTo(com.example.configmgr.ai.tool.ToolOutcome.CODE_PRECHECK_FAILED);
        assertTemporaryMarkerAbsent(this.store.get(RUN_ID), wire);
    }

    @Test
    void originalSentenceUsesDeterministicEvidenceWithoutCorrection() {
        this.properties.getResilience().setTotalBudget(java.time.Duration.ofSeconds(2));
        useRealChatClient();
        when(this.api.chatCompletionStream(any(ChatCompletionRequest.class), any()))
                .thenReturn(Flux.just(toolCallChunk(CALL_ID, AiToolNames.CREATE_TASK, UNRESOLVED_ARGUMENTS)));

        chatWithMessage("cny货币字典");

        RunSnapshot snapshot = this.store.get(RUN_ID);
        assertThat(snapshot.getStatus()).isEqualTo(RunSnapshot.SUSPENDED);
        assertThat(AiContext.fromMap(snapshot.getContext()).getExtra())
                .doesNotContainKey("createEntryCorrectionUsed");
        PendingToolCall pending = this.store.pending(RUN_ID, CALL_ID);
        assertThat(pending.getStatus()).isEqualTo(PendingToolCall.PENDING);
        assertThat(pending.getArguments()).contains("\"defCodes\":[\"CURRENCY\"]")
                .contains("\"fieldCode\":\"code\"").contains("\"value\":\"CNY\"");
        verifyNoInteractions(this.taskService);
        org.mockito.Mockito.verify(this.api).chatCompletionStream(any(ChatCompletionRequest.class), any());
    }

    @Test
    void correctionDisclosesOnlyCreateTaskAndUsesReasoningRecovery() {
        useRealChatClient();
        useEmptyEvidence();
        com.example.configmgr.ai.config.EmptyLengthReasoningRecovery recovery =
                new com.example.configmgr.ai.config.EmptyLengthReasoningRecovery("low",
                        Map.of("thinking", Map.of("type", "disabled")));
        when(this.providerRegistry.emptyLengthReasoningRecovery(any(), any())).thenReturn(recovery);
        when(this.providerRegistry.forReasoningRecovery(any(), any())).thenAnswer(invocation -> {
            ProviderRuntime base = invocation.getArgument(0);
            OpenAiChatOptions options = OpenAiChatOptions.fromOptions(base.options());
            options.setReasoningEffort("low");
            options.setExtraBody(Map.of("thinking", Map.of("type", "disabled")));
            ReasoningAwareOpenAiChatModel recoveredModel = new ReasoningAwareOpenAiChatModel(base.api(), options,
                    this.manager, RetryTemplate.defaultInstance(), ObservationRegistry.NOOP, 7L, true);
            return new ProviderRuntime(base.providerId(), base.providerCode(), base.modelId(), base.baseUrl(),
                    base.completionsPath(), base.runtimeKey(), options, base.api(), recoveredModel, base.chatClient());
        });
        unresolvedThenCorrected(AiToolNames.CREATE_TASK,
                "{\"type\":\"EXPORT\",\"title\":\"raw title\",\"defCodes\":[],\"conditions\":{\"fields\":[]}}");

        chat();

        assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.SUSPENDED);
        ArgumentCaptor<ChatCompletionRequest> upstream = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        org.mockito.Mockito.verify(this.api, org.mockito.Mockito.times(2))
                .chatCompletionStream(upstream.capture(), any());
        ChatCompletionRequest correction = upstream.getAllValues().get(1);
        assertThat(correction.tools()).singleElement().satisfies(tool ->
                assertThat(tool.getFunction().getName()).isEqualTo(AiToolNames.CREATE_TASK));
        assertThat(correction.extraBody()).containsEntry("thinking", Map.of("type", "disabled"));
        assertThat(correction.toolChoice()).isNull();
        assertThat(correction.messages().getLast().content()).contains("conditions.fields=[]");
        org.mockito.Mockito.verify(this.providerRegistry, org.mockito.Mockito.times(2))
                .forReasoningRecovery(any(), org.mockito.ArgumentMatchers.eq(recovery));
    }

    @Test
    void correctedEmptyConditionsUsesDeterministicEvidenceWithoutThirdCall() {
        useRealChatClient();
        CreateTaskEvidenceResolver evidenceResolver = org.mockito.Mockito.spy(new CreateTaskEvidenceResolver(() -> List.of(
                new SlotDefinition("CURRENCY", "货币字典", List.of(
                        new SlotDefinition.SlotField("code", "货币代码", true, List.of())))),
                this.mapper, this.definitionService, this.dataRows));
        org.mockito.Mockito.doReturn(Optional.empty()).doCallRealMethod()
                .when(evidenceResolver).resolve(anyString(), anyString());
        this.manager.setCreateTaskEvidenceResolver(evidenceResolver);
        unresolvedThenCorrected(AiToolNames.CREATE_TASK,
                "{\"type\":\"EXPORT\",\"title\":\"raw title\",\"defCodes\":[\"CURRENCY\"],\"conditions\":{}}");

        chatWithMessage("cny货币字典");

        assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.SUSPENDED);
        assertOriginalUnresolved();
        PendingToolCall corrected = this.store.pending(RUN_ID, CORRECTED_CALL_ID);
        assertThat(corrected.getStatus()).isEqualTo(PendingToolCall.PENDING);
        assertThat(corrected.getArguments()).contains("\"defCodes\":[\"CURRENCY\"]")
                .contains("\"fieldCode\":\"code\"").contains("\"value\":\"CNY\"");
        verifyNoInteractions(this.taskService);
        org.mockito.Mockito.verify(this.api, org.mockito.Mockito.times(2))
                .chatCompletionStream(any(ChatCompletionRequest.class), any());
    }

    @Test
    void correctedExplicitEmptyConditionFieldsUseDeterministicEvidenceWithoutThirdCall() {
        useRealChatClient();
        CreateTaskEvidenceResolver evidenceResolver = org.mockito.Mockito.spy(new CreateTaskEvidenceResolver(() -> List.of(
                new SlotDefinition("CURRENCY", "货币字典", List.of(
                        new SlotDefinition.SlotField("code", "货币代码", true, List.of())))),
                this.mapper, this.definitionService, this.dataRows));
        org.mockito.Mockito.doReturn(Optional.empty()).doCallRealMethod()
                .when(evidenceResolver).resolve(anyString(), anyString());
        this.manager.setCreateTaskEvidenceResolver(evidenceResolver);
        unresolvedThenCorrected(AiToolNames.CREATE_TASK,
                "{\"type\":\"EXPORT\",\"title\":\"raw title\",\"defCodes\":[\"CURRENCY\"],\"conditions\":{\"fields\":[]}}");

        chatWithMessage("cny货币字典");

        assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.SUSPENDED);
        assertOriginalUnresolved();
        PendingToolCall corrected = this.store.pending(RUN_ID, CORRECTED_CALL_ID);
        assertThat(corrected.getStatus()).isEqualTo(PendingToolCall.PENDING);
        assertThat(corrected.getArguments()).contains("\"defCodes\":[\"CURRENCY\"]")
                .contains("\"fieldCode\":\"code\"").contains("\"value\":\"CNY\"");
        verifyNoInteractions(this.taskService);
        org.mockito.Mockito.verify(this.api, org.mockito.Mockito.times(2))
                .chatCompletionStream(any(ChatCompletionRequest.class), any());
    }

    @Test
    void correctedExplicitEmptyConditionFieldsStillEntersHitl() {
        useRealChatClient();
        unresolvedThenCorrected(AiToolNames.CREATE_TASK,
                "{\"type\":\"EXPORT\",\"title\":\"raw title\",\"defCodes\":[\"CURRENCY\"],\"conditions\":{\"fields\":[]}}");

        chat();

        assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.SUSPENDED);
        assertThat(this.store.pending(RUN_ID, CORRECTED_CALL_ID).getStatus()).isEqualTo(PendingToolCall.PENDING);
        verifyNoInteractions(this.taskService);
        org.mockito.Mockito.verify(this.api, org.mockito.Mockito.times(2))
                .chatCompletionStream(any(ChatCompletionRequest.class), any());
    }

    @Test
    void approvedCorrectionDoesNotMarkLaterNavigateToAsCorrectedBatch() throws Exception {
        useRealChatClient();
        useEmptyEvidence();
        String navigationCall = "call-navigate-after-correction";
        when(this.api.chatCompletionStream(any(ChatCompletionRequest.class), any()))
                .thenReturn(Flux.just(toolCallChunk(CALL_ID, AiToolNames.CREATE_TASK, UNRESOLVED_ARGUMENTS)),
                        Flux.just(toolCallChunk(CORRECTED_CALL_ID, AiToolNames.CREATE_TASK, ARGUMENTS)),
                        Flux.just(toolCallChunk(navigationCall, AiToolNames.NAVIGATE_TO,
                                "{\"page\":\"export\",\"taskId\":42}")));
        FrameWire wire;
        try (com.example.configmgr.ai.tenant.RequestContextUtils.SafeCloseable ignored =
                com.example.configmgr.ai.tenant.RequestContextUtils.useContext(1L, 101L)) {
            wire = FrameWire.attachTo(this.registry.of(RUN_ID));
        }
        chat();
        stubCreatedTask();

        ConfirmGate.Submission submission = submitDecision(RUN_ID, CORRECTED_CALL_ID, true);

        assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
        RunSnapshot snapshot = this.store.get(RUN_ID);
        assertThat(snapshot.getStatus()).isEqualTo(RunSnapshot.SUSPENDED);
        PendingToolCall navigation = this.store.pending(RUN_ID, navigationCall);
        assertThat(navigation.getName()).isEqualTo(AiToolNames.NAVIGATE_TO);
        assertThat(navigation.getStatus()).isEqualTo(PendingToolCall.PENDING);
        assertThat(navigation.getKind()).isEqualTo(PendingToolCall.KIND_FRONTEND);
        org.mockito.Mockito.verify(this.taskService).createIdempotent(Task.TaskType.EXPORT, "raw title", RUN_ID);
        org.mockito.Mockito.verify(this.api, org.mockito.Mockito.times(3))
                .chatCompletionStream(any(ChatCompletionRequest.class), any());
        assertTemporaryMarkerAbsent(snapshot, wire);
    }

    @Test
    void importCreateTaskOmitsConditionsAndPassesPreflightIntoHitl() {
        useRealChatClient();
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
    void rejectedCreateTaskDoesNotCorrectAgainOrExecute() throws Exception {
        useRealChatClient();
        useEmptyEvidence();
        unresolvedThenCorrected(AiToolNames.CREATE_TASK, ARGUMENTS);
        chat();
        assertOriginalUnresolved();
        assertThat(this.store.pending(RUN_ID, CORRECTED_CALL_ID).getStatus()).isEqualTo(PendingToolCall.PENDING);

        ConfirmGate.Submission submission = submitDecision(RUN_ID, CORRECTED_CALL_ID, false);

        assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
        org.mockito.Mockito.verify(this.api, org.mockito.Mockito.times(2))
                .chatCompletionStream(any(ChatCompletionRequest.class), any());
        assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.CANCELLED);
        assertThat(this.store.pending(RUN_ID, CORRECTED_CALL_ID).getStatus()).isEqualTo(PendingToolCall.REJECTED);
        assertThat(this.store.archivedTypes(RUN_ID)).containsOnlyOnce("confirm_request");
        verifyNoInteractions(this.taskService);
        verifyNoInteractions(this.jobService);
        assertThat(this.store.ledger(RUN_ID)).noneMatch(record -> AiToolNames.CREATE_TASK.equals(record.get("name"))
                && Boolean.TRUE.equals(record.get("executed")));
        List<Message> settledHistory = this.codec.deserializeAll(this.store.get(RUN_ID).getMessageJson());
        assertThat(settledHistory).filteredOn(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .allSatisfy(assistant -> assertThat(assistant.getMetadata())
                        .doesNotContainEntry(ResilientChatService.STOPPED_KEY, true));
        assertThat(settledHistory).anyMatch(message -> message instanceof ToolResponseMessage responses
                && responses.getResponses().stream().anyMatch(response -> CORRECTED_CALL_ID.equals(response.id())));
        assertHistoryToolResult(settledHistory, CORRECTED_CALL_ID, "rejected", "REJECTED", "已拒绝，本次未执行。",
                this.store.pending(RUN_ID, CORRECTED_CALL_ID).getResultText());
        assertTemporaryMarkerAbsent(this.store.get(RUN_ID));
        List<Map<String, Object>> frames = FrameWire.normalize(this.store.events(RUN_ID));
        assertThat(frames).filteredOn(frame -> "done".equals(frame.get("type"))).singleElement()
                .satisfies(frame -> assertThat(frame).containsEntry("userStopped", false));
    }

    @Test
    void inlineRejectedCreateTaskDoesNotMarkAssistantStopped() throws Exception {
        this.properties.getPark().setEnabled(false);
        useRealChatClient();
        when(this.api.chatCompletionStream(any(ChatCompletionRequest.class), any()))
                .thenReturn(Flux.just(toolCallChunk(CALL_ID, AiToolNames.CREATE_TASK, ARGUMENTS)));
        FrameWire wire;
        try (com.example.configmgr.ai.tenant.RequestContextUtils.SafeCloseable ignored =
                com.example.configmgr.ai.tenant.RequestContextUtils.useContext(1L, 101L)) {
            wire = FrameWire.attachTo(this.registry.of(RUN_ID));
        }
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "inline-reject-stopped-test");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<?> driving = executor.submit(() -> chat());
            wire.awaitType("confirm_request", 5000L);
            assertThat(submitDecision(RUN_ID, CALL_ID, false).outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
            driving.get(10, TimeUnit.SECONDS);

            assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.CANCELLED);
            assertThat(this.store.pending(RUN_ID, CALL_ID).getStatus()).isEqualTo(PendingToolCall.REJECTED);
            List<Message> settledHistory = this.codec.deserializeAll(this.store.get(RUN_ID).getMessageJson());
            assertThat(settledHistory).filteredOn(AssistantMessage.class::isInstance)
                    .map(AssistantMessage.class::cast)
                    .allSatisfy(assistant -> assertThat(assistant.getMetadata())
                            .doesNotContainEntry(ResilientChatService.STOPPED_KEY, true));
            verifyNoInteractions(this.taskService);
            verifyNoInteractions(this.jobService);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cancelledCreateTaskDoesNotCorrectAgainOrExecute() throws Exception {
        useRealChatClient();
        useEmptyEvidence();
        unresolvedThenCorrected(AiToolNames.CREATE_TASK, ARGUMENTS);
        chat();
        assertOriginalUnresolved();
        assertThat(this.store.pending(RUN_ID, CORRECTED_CALL_ID).getStatus()).isEqualTo(PendingToolCall.PENDING);

        try (com.example.configmgr.ai.tenant.RequestContextUtils.SafeCloseable ignored =
                com.example.configmgr.ai.tenant.RequestContextUtils.useContext(1L, 101L)) {
            this.service.cancelParked(RUN_ID);
        }

        assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.CANCELLED);
        assertThat(this.store.pending(RUN_ID, CORRECTED_CALL_ID).getStatus()).isEqualTo(PendingToolCall.CANCELLED);
        List<Message> settledHistory = this.codec.deserializeAll(this.store.get(RUN_ID).getMessageJson());
        assertThat(settledHistory).anyMatch(message -> message instanceof AssistantMessage assistant
                && Boolean.TRUE.equals(assistant.getMetadata().get(ResilientChatService.STOPPED_KEY)));
        assertHistoryToolResult(settledHistory, CORRECTED_CALL_ID, "cancelled", "RUN_CANCELLED",
                "本轮已停止，该操作未执行。", this.store.pending(RUN_ID, CORRECTED_CALL_ID).getResultText());
        assertThat(this.store.archivedTypes(RUN_ID)).containsOnlyOnce("confirm_request");
        verifyNoInteractions(this.taskService);
        verifyNoInteractions(this.jobService);
        org.mockito.Mockito.verify(this.api, org.mockito.Mockito.times(2))
                .chatCompletionStream(any(ChatCompletionRequest.class), any());
        assertTemporaryMarkerAbsent(this.store.get(RUN_ID));
    }

    @Test
    void frontendCancelledToolKeepsFrontendCancellationInHistory() throws Exception {
        useRealChatClient();
        useEmptyEvidence();
        unresolvedThenCorrected(AiToolNames.CREATE_TASK, ARGUMENTS);
        chat();
        PendingToolCall pending = this.store.pending(RUN_ID, CORRECTED_CALL_ID);
        pending.setStatus(PendingToolCall.FRONTEND_CANCELLED);
        pending.setOutcome(ToolOutcome.CANCELLED.wire());
        pending.setCode(ToolOutcome.CODE_FRONTEND_CANCELLED);
        pending.setResultText("前端原始放弃回执");
        this.store.putPending(RUN_ID, pending);

        try (com.example.configmgr.ai.tenant.RequestContextUtils.SafeCloseable ignored =
                com.example.configmgr.ai.tenant.RequestContextUtils.useContext(1L, 101L)) {
            this.service.cancelParkedForDecision(RUN_ID);
        }

        List<Message> settledHistory = this.codec.deserializeAll(this.store.get(RUN_ID).getMessageJson());
        assertHistoryToolResult(settledHistory, CORRECTED_CALL_ID, "cancelled", "FRONTEND_CANCELLED",
                "已取消，本次操作未完成。", "前端原始放弃回执");
        assertThat(settledHistory).filteredOn(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .allSatisfy(assistant -> assertThat(assistant.getMetadata())
                        .doesNotContainEntry(ResilientChatService.STOPPED_KEY, true));
        verifyNoInteractions(this.taskService);
        verifyNoInteractions(this.jobService);
    }

    @Test
    void missingActionWithEmptyDefinitionsAndConditionsParksForConfirmation() {
        useRealChatClient();
        useEmptyEvidence();
        plainStopThenCorrectedCreateTask(
                "{\"type\":\"EXPORT\",\"title\":\"raw title\",\"defCodes\":[],\"conditions\":{}}");

        chat();

        assertPlainStopCorrectionParked();
        assertThat(this.store.pending(RUN_ID, CORRECTED_CALL_ID).getArguments())
                .contains("\"defCodes\":[]").contains("\"conditions\":{}");
    }

    @Test
    void missingActionWithKnownDefinitionsAndEmptyConditionsParksForConfirmation() {
        useRealChatClient();
        useEmptyEvidence();
        plainStopThenCorrectedCreateTask(
                "{\"type\":\"EXPORT\",\"title\":\"raw title\",\"defCodes\":[\"CURRENCY\"],\"conditions\":{}}");

        chat();

        assertPlainStopCorrectionParked();
        assertThat(this.store.pending(RUN_ID, CORRECTED_CALL_ID).getArguments())
                .contains("\"defCodes\":[\"CURRENCY\"]").contains("\"conditions\":{}");
    }

    @Test
    void rejectedPlainStopCorrectionDoesNotCorrectAgainOrExecute() throws Exception {
        useRealChatClient();
        plainStopThenCorrectedCreateTask();
        chat();
        assertPlainStopCorrectionParked();

        ConfirmGate.Submission submission = submitDecision(RUN_ID, CORRECTED_CALL_ID, false);

        assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
        assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.CANCELLED);
        assertThat(this.store.pending(RUN_ID, CORRECTED_CALL_ID).getStatus()).isEqualTo(PendingToolCall.REJECTED);
        assertThat(this.store.archivedTypes(RUN_ID)).containsOnlyOnce("confirm_request");
        verifyNoInteractions(this.taskService);
        verifyNoInteractions(this.jobService);
        assertThat(this.store.ledger(RUN_ID)).noneMatch(record -> AiToolNames.CREATE_TASK.equals(record.get("name"))
                && Boolean.TRUE.equals(record.get("executed")));
        org.mockito.Mockito.verify(this.api, org.mockito.Mockito.times(2))
                .chatCompletionStream(any(ChatCompletionRequest.class), any());
        assertTemporaryMarkerAbsent(this.store.get(RUN_ID));
    }

    @Test
    void cancelledPlainStopCorrectionDoesNotCorrectAgainOrExecute() throws Exception {
        useRealChatClient();
        plainStopThenCorrectedCreateTask();
        chat();
        assertPlainStopCorrectionParked();

        try (com.example.configmgr.ai.tenant.RequestContextUtils.SafeCloseable ignored =
                com.example.configmgr.ai.tenant.RequestContextUtils.useContext(1L, 101L)) {
            this.service.cancelParked(RUN_ID);
        }

        assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.CANCELLED);
        assertThat(this.store.pending(RUN_ID, CORRECTED_CALL_ID).getStatus()).isEqualTo(PendingToolCall.CANCELLED);
        assertThat(this.store.archivedTypes(RUN_ID)).containsOnlyOnce("confirm_request");
        verifyNoInteractions(this.taskService);
        verifyNoInteractions(this.jobService);
        assertThat(this.store.ledger(RUN_ID)).noneMatch(record -> AiToolNames.CREATE_TASK.equals(record.get("name"))
                && Boolean.TRUE.equals(record.get("executed")));
        org.mockito.Mockito.verify(this.api, org.mockito.Mockito.times(2))
                .chatCompletionStream(any(ChatCompletionRequest.class), any());
        assertTemporaryMarkerAbsent(this.store.get(RUN_ID));
    }

    @Test
    void preflightFailureDoesNotCorrectAgainOrEnterConfirmGate() {
        useRealChatClient();
        String invalid = ARGUMENTS;
        ToolPreflightService.PreflightReport failure = new ToolPreflightService.PreflightReport(false,
                Map.of("type", "EXPORT"), List.of(new ToolPreflightService.PreflightReport.Check(
                        "forced preflight failure", false, "ordinary preflight failure may continue")),
                "forced", "", null, "forced-report");
        org.mockito.Mockito.doReturn(failure).when(this.preflight)
                .inspect(org.mockito.ArgumentMatchers.eq(AiToolNames.CREATE_TASK),
                        org.mockito.ArgumentMatchers.eq(invalid), any(AiContext.class));
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
        useRealChatClient();
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
        useRealChatClient();
        useEmptyEvidence();
        String unresolved = "{\"type\":\"EXPORT\",\"defCodes\":[\"cny货币字典\"],\"conditions\":{}}";
        when(this.api.chatCompletionStream(any(ChatCompletionRequest.class), any()))
                .thenReturn(Flux.just(toolCallChunkWithReasoning(CALL_ID, AiToolNames.CREATE_TASK, unresolved,
                                firstReasoning)),
                        Flux.just(toolCallChunkWithReasoning(CORRECTED_CALL_ID, AiToolNames.CREATE_TASK, ARGUMENTS,
                                "second-reasoning")),
                        Flux.just(textChunk("已创建任务")));

        chat();

        RunSnapshot suspended = this.store.get(RUN_ID);
        assertThat(suspended.getStatus()).isEqualTo(RunSnapshot.SUSPENDED);
        assertOriginalUnresolved();
        assertThat(this.store.pending(RUN_ID, CORRECTED_CALL_ID).getStatus()).isEqualTo(PendingToolCall.PENDING);
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
        ConfirmGate.Submission submission = submitDecision(RUN_ID, CORRECTED_CALL_ID, true);

        assertThat(submission.outcome()).isEqualTo(ConfirmGate.Outcome.ACCEPTED);
        org.mockito.Mockito.verify(this.taskService).createIdempotent(Task.TaskType.EXPORT, "raw title", RUN_ID);
        org.mockito.Mockito.verify(this.api, org.mockito.Mockito.times(3))
                .chatCompletionStream(any(ChatCompletionRequest.class), any());
        List<Message> recovered = this.codec.deserializeAll(this.store.get(RUN_ID).getMessageJson());
        assertThat(recovered).allSatisfy(message -> assertThat(message.getMetadata().toString())
                .doesNotContain("second-reasoning"));
        if (firstReasoning != null) {
            assertThat(recovered).anyMatch(message -> firstReasoning.equals(
                    message.getMetadata().get(ResilientChatService.REASONING_METADATA_KEY)));
        }
        ArgumentCaptor<ChatCompletionRequest> upstream = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        org.mockito.Mockito.verify(this.api, org.mockito.Mockito.times(3))
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
                .thenReturn(Flux.just(textChunk("前驱正文")),
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
        org.mockito.Mockito.verify(this.api, org.mockito.Mockito.times(3))
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

    private void assertHistoryToolResult(List<Message> history, String toolCallId, String outcome, String code,
                                         String userText, String modelText) throws Exception {
        ToolResponseMessage.ToolResponse response = history.stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .flatMap(message -> message.getResponses().stream())
                .filter(item -> toolCallId.equals(item.id()))
                .findFirst().orElseThrow();
        ToolResultEnvelope envelope = this.mapper.readValue(response.responseData(), ToolResultEnvelope.class);
        assertThat(envelope.outcome()).isEqualTo(outcome);
        assertThat(envelope.code()).isEqualTo(code);
        if (modelText != null) {
            assertThat(envelope.message()).isEqualTo(modelText);
        }
        assertThat(ToolResultUserText.userTextCode(response.name(), envelope.code(), envelope.target())).isEqualTo(code);
        assertThat(ToolResultUserText.forUser(response.name(), envelope.code(), envelope.message(), envelope.target()))
                .isEqualTo(userText);
    }

    private void plainStopThenCorrectedCreateTask() {
        plainStopThenCorrectedCreateTask(ARGUMENTS);
    }

    private void plainStopThenCorrectedCreateTask(String arguments) {
        when(this.api.chatCompletionStream(any(ChatCompletionRequest.class), any()))
                .thenReturn(Flux.just(textChunk("首次纯文本")),
                        Flux.just(toolCallChunk(CORRECTED_CALL_ID, AiToolNames.CREATE_TASK, arguments)));
    }

    private void assertPlainStopCorrectionParked() {
        RunSnapshot snapshot = this.store.get(RUN_ID);
        assertThat(snapshot.getStatus()).isEqualTo(RunSnapshot.SUSPENDED);
        assertThat(snapshot.getAssistantContent()).isEqualTo("首次纯文本");
        assertThat(AiContext.fromMap(snapshot.getContext()).getExtra())
                .containsEntry("createEntryCorrectionUsed", true);
        assertThat(this.store.pendingsLenient(RUN_ID)).singleElement().satisfies(pending -> {
            assertThat(pending.getToolCallId()).isEqualTo(CORRECTED_CALL_ID);
            assertThat(pending.getStatus()).isEqualTo(PendingToolCall.PENDING);
        });
        assertThat(this.store.archivedTypes(RUN_ID)).containsOnlyOnce("confirm_request");
        verifyNoInteractions(this.taskService);
        verifyNoInteractions(this.jobService);

        ArgumentCaptor<ChatCompletionRequest> upstream = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        org.mockito.Mockito.verify(this.api, org.mockito.Mockito.times(2))
                .chatCompletionStream(upstream.capture(), any());
        ChatCompletionRequest correction = upstream.getAllValues().get(1);
        assertThat(correction.messages()).anyMatch(message -> message.role() == ChatCompletionMessage.Role.USER
                && "Create an export task".equals(message.content()));
        assertThat(correction.messages().getLast().content()).contains(AiToolNames.CREATE_TASK)
                .contains("createTaskSchema").contains("明确创建").contains("纯问答")
                .doesNotContain("references unresolved").doesNotContain("submit_action_plan");
        assertThat(correction.tools()).singleElement().satisfies(tool ->
                assertThat(tool.getFunction().getName()).isEqualTo(AiToolNames.CREATE_TASK));
        assertThat(correction.toolChoice()).isNull();
    }

    private void unresolvedThenCorrected(String name, String arguments) {
        when(this.api.chatCompletionStream(any(ChatCompletionRequest.class), any()))
                .thenReturn(Flux.just(toolCallChunk(CALL_ID, AiToolNames.CREATE_TASK, UNRESOLVED_ARGUMENTS)),
                        Flux.just(toolCallChunk(CORRECTED_CALL_ID, name, arguments)));
    }

    private void assertOriginalUnresolved() {
        PendingToolCall original = this.store.pending(RUN_ID, CALL_ID);
        assertThat(original.getStatus()).isEqualTo(PendingToolCall.PRECHECK_FAILED);
        assertThat(original.getCode()).isEqualTo(CreateTaskReferenceResolver.UNRESOLVED_CODE);
        assertThat(original.isExecuted()).isFalse();
        assertThat(original.isTranscriptSegmentRequired()).isFalse();
    }

    private void assertTemporaryMarkerAbsent(RunSnapshot snapshot) throws Exception {
        assertThat(AiContext.fromMap(snapshot.getContext()).getExtra()).doesNotContainKey("createEntryCorrection");
        assertThat(snapshot.getMessageJson()).doesNotContain("\"createEntryCorrection\":true");
    }

    private void assertTemporaryMarkerAbsent(RunSnapshot snapshot, FrameWire wire) throws Exception {
        assertTemporaryMarkerAbsent(snapshot);
        assertThat(this.mapper.writeValueAsString(wire.frames())).doesNotContain("\"createEntryCorrection\":true");
    }

    private void assertFailedWithoutFollowUp() {
        assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.FAILED);
        assertThat(this.store.archivedTypes(RUN_ID)).doesNotContain("confirm_request")
                .allMatch(type -> !type.contains("form"));
        verifyNoInteractions(this.taskService);
        org.mockito.Mockito.verify(this.api, org.mockito.Mockito.times(2))
                .chatCompletionStream(any(ChatCompletionRequest.class), any());
    }

    private void useEmptyEvidence() {
        CreateTaskEvidenceResolver resolver = org.mockito.Mockito.mock(CreateTaskEvidenceResolver.class);
        when(resolver.resolve(anyString(), anyString())).thenReturn(Optional.empty());
        this.manager.setCreateTaskEvidenceResolver(resolver);
    }

    private void useRealChatClient() {
        ProviderRuntime runtime = new ProviderRuntime(7L, "openai", "test-model", "http://placeholder",
                "/chat/completions", "7|fp|u|p", OpenAiChatOptions.builder().model("test-model").build(), this.api,
                this.model, ChatClient.builder(this.model).build());
        when(this.providerRegistry.current()).thenReturn(runtime);
        when(this.providerRegistry.tryCurrent()).thenReturn(Optional.of(runtime));
        when(this.providerRegistry.forRequest(any(), any(), any())).thenReturn(runtime);
        when(this.providerRegistry.forFrozenRequest(any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyBoolean(), any(), any())).thenReturn(runtime);
    }

    private void chat() {
        chat(RUN_ID, Map.of());
    }

    private void chatWithMessage(String message) {
        AiChatRequest request = new AiChatRequest(SESSION_ID, message,
                new AiChatRequest.Context("tasks", null, null, null, Map.of()), null, null);
        try (com.example.configmgr.ai.tenant.RequestContextUtils.SafeCloseable ignored =
                com.example.configmgr.ai.tenant.RequestContextUtils.useContext(1L, 101L)) {
            this.service.chat(RUN_ID, request, this.registry.of(RUN_ID));
        }
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
        return submitDecision(runId, CALL_ID, approved);
    }

    private ConfirmGate.Submission submitDecision(String runId, String callId, boolean approved) {
        try (com.example.configmgr.ai.tenant.RequestContextUtils.SafeCloseable ignored =
                com.example.configmgr.ai.tenant.RequestContextUtils.useContext(1L, 101L)) {
            return this.gate.submitDecision(runId, callId, approved, approved ? "approved" : "rejected");
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

    private static ChatCompletionChunk toolCallChunk(List<ChatCompletionMessage.ToolCall> toolCalls) {
        ChatCompletionMessage delta = new ChatCompletionMessage("", ChatCompletionMessage.Role.ASSISTANT, null,
                null, toolCalls, null, null, null, null);
        ChatCompletionChunk.ChunkChoice choice = new ChatCompletionChunk.ChunkChoice(
                ChatCompletionFinishReason.TOOL_CALLS, 0, delta, null);
        return new ChatCompletionChunk("cmpl-create-entry-batch", List.of(choice), 1750000000L, "test-model",
                null, null, "chat.completion.chunk", null);
    }
}
