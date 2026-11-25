package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.form.CreateTaskDetailsForm;
import com.example.configmgr.ai.form.FormPurposes;
import com.example.configmgr.ai.form.GenerativeFormGuard;
import com.example.configmgr.ai.form.GenerativeFormRules;
import com.example.configmgr.ai.gate.ConfirmGate;
import com.example.configmgr.ai.gate.CreateTaskReferenceResolver;
import com.example.configmgr.ai.gate.CreateTaskReferenceResolver.Kind;
import com.example.configmgr.ai.gate.CreateTaskReferenceResolver.Resolution;
import com.example.configmgr.ai.gate.CreateTaskReferenceResolver.Status;
import com.example.configmgr.ai.gate.CreateTaskReferenceResolver.UnresolvedReference;
import com.example.configmgr.ai.gate.GateCalls;
import com.example.configmgr.ai.gate.SpToolCallingManager;
import com.example.configmgr.ai.gate.ToolPreflightService;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.slot.SlotDefinition;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CreateTaskDetailsResumeTest {

    private static final String RUN_ID = "run-create-details-resume";

    private static final String SESSION_ID = "session-create-details-resume";

    private static final String ORIGINAL_CALL = "call-create-original";

    private static final String ENUM_ARGUMENTS = "{\"type\":\"EXPORT\",\"defCodes\":[\"货币字典\"],"
            + "\"conditions\":{\"fields\":[{\"fieldCode\":\"货币代码\",\"operator\":\"EQ\",\"value\":\"usd\"}]}}";

    private static final String FIELD_ARGUMENTS = "{\"type\":\"EXPORT\",\"defCodes\":[\"货币字典\"],"
            + "\"conditions\":{\"fields\":[{\"fieldCode\":\"未知字段\",\"operator\":\"EQ\",\"value\":\"CNY\"}]}}";

    private static final String IN_ARRAY_ARGUMENTS = "{\"type\":\"EXPORT\",\"defCodes\":[\"货币字典\"],"
            + "\"conditions\":{\"fields\":[{\"fieldCode\":\"货币代码\",\"operator\":\"IN\",\"value\":[\"usd\",\"未知\"]}]}}";

    private static final String OWNERLESS_ARGUMENTS = "{\"type\":\"EXPORT\",\"defCodes\":[],"
            + "\"conditions\":{\"fields\":[{\"fieldCode\":\"货币代码\",\"operator\":\"EQ\",\"value\":\"CNY\"}]}}";

    private static final String MULTI_OWNER_ARGUMENTS = "{\"type\":\"EXPORT\",\"defCodes\":[\"货币字典\",\"货币字典\"],"
            + "\"conditions\":{\"fields\":[{\"fieldCode\":\"未知字段\",\"operator\":\"EQ\",\"value\":\"CNY\"}]}}";

    private static final String SYSTEM_TEXT = "PERSONA 行\n\n## 当前工作区状态\n<workspace_state>\n"
            + "页面：任务中心\n</workspace_state>\n";

    private final InMemoryRunStore store = spy(new InMemoryRunStore(true));

    private final ObjectMapper mapper = new ObjectMapper();

    private final MessageJsonCodec codec = new MessageJsonCodec(this.mapper);

    private final RunRegistry registry = mock(RunRegistry.class);

    private final SseChatEmitter emitter = mock(SseChatEmitter.class);

    private final ResilientChatService chatService = mock(ResilientChatService.class);

    private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

    private final ToolPreflightService preflight = mock(ToolPreflightService.class);

    private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

    private final ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);

    private final AiProperties properties = new AiProperties();

    private ConfirmGate gate;

    private CreateTaskDetailsForm detailsForm;

    private SpToolCallingManager manager;

    private ResumeService service;

    private com.example.configmgr.ai.tenant.RequestContextUtils.SafeCloseable identity;

    @BeforeEach
    void setUp() {
        this.identity = com.example.configmgr.ai.tenant.RequestContextUtils.useContext(1L, 101L);
        this.properties.getPark().setEnabled(true);
        when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
        when(this.emitter.transcriptFullText()).thenReturn("");
        when(this.cancellations.cancelState(anyString())).thenReturn(CancellationRegistry.CancelState.NOT_CANCELLED);
        when(this.cancellations.onCancel(anyString(), any())).thenAnswer(invocation -> (AutoCloseable) () -> {
        });
        when(this.toolRegistry.isContextMatched(eq("create_task"), any(AiContext.class))).thenReturn(true);
        when(this.chatService.systemPromptText(any(), anyBoolean())).thenReturn(SYSTEM_TEXT);
        when(this.chatService.workspaceStateText(any())).thenReturn("## 当前工作区状态\n<workspace_state>\n"
                + "页面：任务中心\n</workspace_state>\n");
        when(this.chatService.buildResumedPrompt(eq(RUN_ID), eq(SESSION_ID), any(AiContext.class), any()))
                .thenAnswer(invocation -> new Prompt(invocation.<List<Message>>getArgument(3)));
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(this.executor).execute(any(Runnable.class));

        this.gate = new ConfirmGate(this.store, this.registry, this.properties, this.cancellations,
                mock(ToolActivityBeacon.class), List.of(new GenerativeFormGuard(this.mapper)));
        this.detailsForm = new CreateTaskDetailsForm(this.store, this.registry, this.gate, this.codec, this.properties);
        this.manager = new SpToolCallingManager(this.store, this.registry, this.gate,
                this.toolRegistry, this.codec, mock(ToolActivityBeacon.class), this.cancellations,
                new ToolResultLimiter(this.properties), List.of(), this.preflight);
        this.manager.setCreateTaskReferenceResolver(new CreateTaskReferenceResolver(this::catalog, this.mapper));
        this.manager.setCreateTaskDetailsForm(this.detailsForm);
        this.service = new ResumeService(this.store, this.registry, this.codec, this.chatService, this.manager,
                this.executor, mock(StringRedisTemplate.class), this.properties, this.cancellations,
                new ToolResultLimiter(this.properties));
        this.service.setCreateTaskDetailsForm(this.detailsForm);
    }

    @AfterEach
    void tearDown() {
        this.identity.close();
    }

    @Test
    void submissionReresolvesAndRunsPreflightBeforeCreateHitl() {
        seedSubmitted(ENUM_ARGUMENTS, enumGap("usd"), "{\"enum_value_0_0\":\"USD\"}");
        when(this.preflight.inspect(eq("create_task"), anyString(), any(AiContext.class)))
                .thenReturn(passingReport());

        ResumeService.ResumeResult result = this.service.resume(RUN_ID);

        assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
        assertThat(result.body()).containsEntry("reason", "CREATE_TASK_CONFIRM_PARKED");
        ArgumentCaptor<String> canonical = ArgumentCaptor.forClass(String.class);
        verify(this.preflight).inspect(eq("create_task"), canonical.capture(), any(AiContext.class));
        JsonNode arguments = read(canonical.getValue());
        assertThat(arguments.path("defCodes").get(0).asText()).isEqualTo("CURRENCY");
        assertThat(arguments.path("conditions").path("fields").get(0).path("fieldCode").asText())
                .isEqualTo("code");
        assertThat(arguments.path("conditions").path("fields").get(0).path("value").asText()).isEqualTo("USD");

        PendingToolCall create = this.store.pending(RUN_ID, ORIGINAL_CALL);
        assertThat(create).isNotNull();
        assertThat(create.getToolCallId()).isEqualTo(ORIGINAL_CALL);
        assertThat(create.getKind()).isEqualTo(PendingToolCall.KIND_CONFIRM);
        assertThat(create.getStatus()).isEqualTo(PendingToolCall.PENDING);
        assertThat(create.getArguments()).isEqualTo(canonical.getValue());
        assertThat(create.getPreflight()).containsEntry("reportHash", "create-details-pass");
        assertThat(create.getCreatedTaskId()).isNull();
        assertThat(create.getCreatedJobId()).isNull();
        assertThat(this.store.get(RUN_ID).getInFlightToolCalls()).extracting(PendingToolCall::getToolCallId)
                .containsExactly(ORIGINAL_CALL);
        verify(this.emitter).confirmRequest(any(PendingToolCall.class));
        verify(this.chatService, never()).driveResumed(anyString(), anyString(), any(), any(), any());
        assertThat(this.store.ledger(RUN_ID)).isEmpty();
    }

    @Test
    void cancelDoesNotCreateTaskOrJob() {
        PendingToolCall parked = seedParked(ENUM_ARGUMENTS, enumGap("usd"));
        GateCalls.submit(this.gate, RUN_ID, parked.getToolCallId(), "{}", "unit-test", true);
        clearInvocations(this.emitter);
        stubCancelParked();

        ResumeService.ResumeResult result = this.service.resume(RUN_ID);

        assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ALREADY_CANCELLED);
        assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.CANCELLED);
        verify(this.chatService).cancelParked(RUN_ID);
        verify(this.chatService, never()).driveResumed(anyString(), anyString(), any(), any(), any());
        verifyNoInteractions(this.preflight);
        verify(this.emitter, never()).confirmRequest(any(PendingToolCall.class));
        verify(this.emitter, never()).frontendToolRequest(any(PendingToolCall.class), any());
        assertThat(this.store.pending(RUN_ID, ORIGINAL_CALL)).isNull();
        assertThat(this.store.ledger(RUN_ID)).isEmpty();
        assertThat(this.store.pendingsLenient(RUN_ID)).hasSize(1).allSatisfy(pending -> {
            assertThat(pending.getStatus()).isNotEqualTo(PendingToolCall.PENDING);
            assertThat(pending.getCreatedTaskId()).isNull();
            assertThat(pending.getCreatedJobId()).isNull();
        });
    }

    @Test
    void repeatedResumeDoesNotDuplicateFormOrCreateTask() {
        seedSubmitted(ENUM_ARGUMENTS, enumGap("usd"), "{\"enum_value_0_0\":\"USD\"}");
        when(this.preflight.inspect(eq("create_task"), anyString(), any(AiContext.class)))
                .thenReturn(passingReport());

        ResumeService.ResumeResult first = this.service.resume(RUN_ID);
        ResumeService.ResumeResult repeated = this.service.resume(RUN_ID);

        assertThat(first.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
        assertThat(repeated.outcome()).isEqualTo(ResumeService.Outcome.PENDING_UNRESOLVED);
        assertThat(this.store.pendingsLenient(RUN_ID)).hasSize(2);
        assertThat(this.store.pendingsLenient(RUN_ID).stream()
                .filter(pending -> ORIGINAL_CALL.equals(pending.getToolCallId()))).hasSize(1);
        verify(this.preflight, times(1)).inspect(eq("create_task"), anyString(), any(AiContext.class));
        verify(this.emitter, times(1)).confirmRequest(any(PendingToolCall.class));
        verify(this.chatService, never()).driveResumed(anyString(), anyString(), any(), any(), any());
        assertThat(this.store.ledger(RUN_ID)).isEmpty();
    }

    @Test
    void stillAmbiguousSubmissionStopsBeforeCreateHitl() {
        seedSubmitted(FIELD_ARGUMENTS, new Resolution(Status.UNRESOLVED, FIELD_ARGUMENTS, List.of(
                new UnresolvedReference(Kind.FIELD, 0, 0, "未知字段", List.of())),
                CreateTaskReferenceResolver.UNRESOLVED_CODE), "{\"field_0_0\":\"仍未知\"}");

        ResumeService.ResumeResult result = this.service.resume(RUN_ID);

        assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
        assertThat(result.body()).containsEntry("reason", "CREATE_TASK_DETAILS_PARKED");
        verifyNoInteractions(this.preflight);
        verify(this.emitter, never()).confirmRequest(any(PendingToolCall.class));
        verify(this.chatService, never()).driveResumed(anyString(), anyString(), any(), any(), any());
        assertThat(this.store.pending(RUN_ID, ORIGINAL_CALL)).isNull();
        assertThat(this.store.ledger(RUN_ID)).isEmpty();
        List<PendingToolCall> waiting = this.store.pendingsLenient(RUN_ID).stream()
                .filter(pending -> PendingToolCall.PENDING.equals(pending.getStatus()))
                .toList();
        assertThat(waiting).hasSize(1);
        assertThat(read(waiting.getFirst().getArguments()).path("form").path("purpose").asText())
                .isEqualTo(FormPurposes.CREATE_TASK_DETAILS);
        assertThat(waiting.getFirst().getCreateTaskToolCallId()).isEqualTo(ORIGINAL_CALL);
        assertThat(waiting.getFirst().getCreateTaskArguments()).contains("仍未知");
    }

    @Test
    void inArraySubmissionResumesWithResolvedElementsIntact() {
        Resolution gap = new CreateTaskReferenceResolver(this::catalog, this.mapper).resolve(IN_ARRAY_ARGUMENTS);
        PendingToolCall receipt = seedSubmitted(IN_ARRAY_ARGUMENTS, gap, "{\"enum_value_0_0\":[\"CNY\"]}");
        receipt.setResultText("{\"enum_value_0_0\":[\"CNY\",\"USD\"]}");
        JsonNode ordered = read(CreateTaskDetailsForm.mergeSubmission(receipt))
                .path("conditions").path("fields").get(0).path("value");
        assertThat(ordered).extracting(JsonNode::asText).containsExactly("CNY", "USD");
        when(this.preflight.inspect(eq("create_task"), anyString(), any(AiContext.class)))
                .thenReturn(passingReport());

        ResumeService.ResumeResult result = this.service.resume(RUN_ID);

        assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
        assertThat(result.body()).containsEntry("reason", "CREATE_TASK_CONFIRM_PARKED");
        ArgumentCaptor<String> canonical = ArgumentCaptor.forClass(String.class);
        verify(this.preflight).inspect(eq("create_task"), canonical.capture(), any(AiContext.class));
        JsonNode value = read(canonical.getValue()).path("conditions").path("fields").get(0).path("value");
        assertThat(value.isArray()).isTrue();
        assertThat(value).extracting(JsonNode::asText).containsExactly("CNY");
        assertThat(this.store.pending(RUN_ID, ORIGINAL_CALL).getArguments()).isEqualTo(canonical.getValue());
        assertThat(this.store.ledger(RUN_ID)).isEmpty();
    }

    @Test
    void inArrayCancellationDoesNotCreateTaskOrJob() {
        Resolution gap = new CreateTaskReferenceResolver(this::catalog, this.mapper).resolve(IN_ARRAY_ARGUMENTS);
        PendingToolCall parked = seedParked(IN_ARRAY_ARGUMENTS, gap);
        assertThat(read(parked.getArguments()).path("form").path("fields").get(0).path("type").asText())
                .isEqualTo("multi_select");
        GateCalls.submit(this.gate, RUN_ID, parked.getToolCallId(), "{}", "unit-test", true);
        clearInvocations(this.emitter);
        stubCancelParked();

        ResumeService.ResumeResult result = this.service.resume(RUN_ID);

        assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ALREADY_CANCELLED);
        assertThat(this.store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.CANCELLED);
        verify(this.chatService).cancelParked(RUN_ID);
        verify(this.chatService, never()).driveResumed(anyString(), anyString(), any(), any(), any());
        verifyNoInteractions(this.preflight);
        verify(this.emitter, never()).confirmRequest(any(PendingToolCall.class));
        verify(this.emitter, never()).frontendToolRequest(any(PendingToolCall.class), any());
        assertThat(this.store.pending(RUN_ID, ORIGINAL_CALL)).isNull();
        assertThat(this.store.ledger(RUN_ID)).isEmpty();
        assertThat(this.store.pendingsLenient(RUN_ID)).hasSize(1).allSatisfy(pending -> {
            assertThat(pending.getStatus()).isNotEqualTo(PendingToolCall.PENDING);
            assertThat(pending.getCreatedTaskId()).isNull();
            assertThat(pending.getCreatedJobId()).isNull();
        });
    }

    @Test
    void ownerlessSubmissionInsertsDefinitionAndResumesFieldResolution() {
        Resolution gap = new CreateTaskReferenceResolver(this::catalog, this.mapper).resolve(OWNERLESS_ARGUMENTS);
        seedSubmitted(OWNERLESS_ARGUMENTS, gap, "{\"definition_0\":\"货币字典\",\"field_0_0\":\"货币代码\"}");
        when(this.preflight.inspect(eq("create_task"), anyString(), any(AiContext.class)))
                .thenReturn(passingReport());

        ResumeService.ResumeResult result = this.service.resume(RUN_ID);

        assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
        assertThat(result.body()).containsEntry("reason", "CREATE_TASK_CONFIRM_PARKED");
        ArgumentCaptor<String> canonical = ArgumentCaptor.forClass(String.class);
        verify(this.preflight).inspect(eq("create_task"), canonical.capture(), any(AiContext.class));
        JsonNode arguments = read(canonical.getValue());
        assertThat(arguments.path("defCodes")).extracting(JsonNode::asText).containsExactly("CURRENCY");
        assertThat(arguments.path("conditions").path("fields").get(0).path("fieldCode").asText()).isEqualTo("code");
        assertThat(this.store.ledger(RUN_ID)).isEmpty();
    }

    @Test
    void multipleOwnerSubmissionFailsClosedWithoutAnotherCorrectionForm() {
        Resolution legacyGap = new Resolution(Status.UNRESOLVED, MULTI_OWNER_ARGUMENTS, List.of(
                new UnresolvedReference(Kind.FIELD, -1, 0, "未知字段", List.of())),
                CreateTaskReferenceResolver.UNRESOLVED_CODE);
        seedSubmitted(MULTI_OWNER_ARGUMENTS, legacyGap, "{\"field_unknown_0\":\"货币代码\"}");

        ResumeService.ResumeResult result = this.service.resume(RUN_ID);

        assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
        PendingToolCall create = this.store.pending(RUN_ID, ORIGINAL_CALL);
        assertThat(create).isNotNull();
        assertThat(create.getStatus()).isEqualTo(PendingToolCall.PRECHECK_FAILED);
        assertThat(create.getCode()).isEqualTo(CreateTaskReferenceResolver.FAILED_CODE);
        assertThat(create.isExecuted()).isFalse();
        assertThat(this.store.pendingsLenient(RUN_ID).stream()
                .filter(pending -> PendingToolCall.PENDING.equals(pending.getStatus()))).isEmpty();
        verifyNoInteractions(this.preflight);
        verify(this.emitter, never()).confirmRequest(any(PendingToolCall.class));
        assertThat(this.store.ledger(RUN_ID)).isEmpty();
    }

    @Test
    void initialStorageReadFailureConvergesOriginalCreateWithoutHitl() {
        seedParked(ENUM_ARGUMENTS, enumGap("usd"));
        List<Message> history = this.codec.deserializeAll(this.store.get(RUN_ID).getMessageJson());
        PendingToolCall create = new PendingToolCall();
        create.setToolCallId(ORIGINAL_CALL);
        create.setName("create_task");
        create.setType("function");
        create.setArguments(ENUM_ARGUMENTS);
        create.setKind(PendingToolCall.KIND_CONFIRM);
        doThrow(new IllegalStateException("initial storage read failed")).when(this.store).get(RUN_ID);

        boolean parked = this.detailsForm.parkCreateTaskConfirmation(RUN_ID, create, history);

        assertThat(parked).isFalse();
        PendingToolCall converged = this.store.pending(RUN_ID, ORIGINAL_CALL);
        assertThat(converged.getStatus()).isEqualTo(PendingToolCall.BLOCKED);
        assertThat(converged.getCode()).isEqualTo("HOST_FORM_PARK_FAILED");
        assertThat(converged.isExecuted()).isFalse();
        assertThat(converged.getCreatedTaskId()).isNull();
        assertThat(converged.getCreatedJobId()).isNull();
        verifyNoInteractions(this.preflight);
        verify(this.emitter, never()).confirmRequest(any(PendingToolCall.class));
        assertThat(this.store.ledger(RUN_ID)).isEmpty();
    }

    @Test
    void missingDetailsFormBeanFailsClosedBeforePreflightOrHitl() {
        seedSubmitted(ENUM_ARGUMENTS, enumGap("usd"), "{\"enum_value_0_0\":\"USD\"}");
        when(this.preflight.inspect(eq("create_task"), anyString(), any(AiContext.class)))
                .thenReturn(passingReport());
        this.manager.setCreateTaskDetailsForm(null);

        ResumeService.ResumeResult result = this.service.resume(RUN_ID);

        assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
        PendingToolCall create = this.store.pending(RUN_ID, ORIGINAL_CALL);
        assertThat(create.getStatus()).isEqualTo(PendingToolCall.PRECHECK_FAILED);
        assertThat(create.getCode()).isEqualTo(CreateTaskReferenceResolver.FAILED_CODE);
        assertThat(create.isExecuted()).isFalse();
        assertThat(create.getCreatedTaskId()).isNull();
        assertThat(create.getCreatedJobId()).isNull();
        verifyNoInteractions(this.preflight);
        verify(this.emitter, never()).confirmRequest(any(PendingToolCall.class));
        assertThat(this.store.ledger(RUN_ID)).isEmpty();
    }

    @Test
    void storageFailureConvergesOriginalCreateWithoutUnusablePending() {
        seedSubmitted(ENUM_ARGUMENTS, enumGap("usd"), "{\"enum_value_0_0\":\"USD\"}");
        when(this.preflight.inspect(eq("create_task"), anyString(), any(AiContext.class)))
                .thenReturn(passingReport());
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("storage failed after pending write");
        }).when(this.store).saveSuspended(eq(RUN_ID), any(), any(), anyList(), anyList(), eq(this.codec), any());

        ResumeService.ResumeResult result = this.service.resume(RUN_ID);

        assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
        PendingToolCall create = this.store.pending(RUN_ID, ORIGINAL_CALL);
        assertThat(create.getStatus()).isEqualTo(PendingToolCall.BLOCKED);
        assertThat(create.getCode()).isEqualTo("HOST_FORM_PARK_FAILED");
        assertThat(create.isExecuted()).isFalse();
        assertThat(create.getCreatedTaskId()).isNull();
        assertThat(create.getCreatedJobId()).isNull();
        verify(this.emitter, never()).confirmRequest(any(PendingToolCall.class));
        assertThat(this.store.ledger(RUN_ID)).isEmpty();
    }

    @Test
    void requestPublicationFailureStaysParkedWithoutModelDrive() {
        seedSubmitted(ENUM_ARGUMENTS, enumGap("usd"), "{\"enum_value_0_0\":\"USD\"}");
        when(this.preflight.inspect(eq("create_task"), anyString(), any(AiContext.class)))
                .thenReturn(passingReport());
        doThrow(new IllegalStateException("request publication failed")).when(this.emitter)
                .confirmRequest(any(PendingToolCall.class));

        ResumeService.ResumeResult result = this.service.resume(RUN_ID);

        assertThat(result.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
        assertThat(result.body()).containsEntry("reason", "CREATE_TASK_CONFIRM_PARKED");
        PendingToolCall create = this.store.pending(RUN_ID, ORIGINAL_CALL);
        assertThat(create.getStatus()).isEqualTo(PendingToolCall.PENDING);
        assertThat(create.isExecuted()).isFalse();
        assertThat(this.store.get(RUN_ID).getInFlightToolCalls()).extracting(PendingToolCall::getToolCallId)
                .containsExactly(ORIGINAL_CALL);
        verify(this.chatService, never()).driveResumed(anyString(), anyString(), any(), any(), any());
        assertThat(this.store.ledger(RUN_ID)).isEmpty();
    }

    @Test
    void unchangedAmbiguousSubmissionGetsANewCorrectionForm() {
        Resolution gap = new Resolution(Status.UNRESOLVED, FIELD_ARGUMENTS, List.of(
                new UnresolvedReference(Kind.FIELD, 0, 0, "未知字段", List.of())),
                CreateTaskReferenceResolver.UNRESOLVED_CODE);
        seedSubmitted(FIELD_ARGUMENTS, gap, "{\"field_0_0\":\"仍未知\"}");

        ResumeService.ResumeResult first = this.service.resume(RUN_ID);
        PendingToolCall firstSuccessor = waitingDetailsForm();
        GateCalls.submit(this.gate, RUN_ID, firstSuccessor.getToolCallId(), "{\"field_0_0\":\"仍未知\"}",
                "unit-test");
        ResumeService.ResumeResult second = this.service.resume(RUN_ID);
        PendingToolCall secondSuccessor = waitingDetailsForm();
        ResumeService.ResumeResult duplicate = this.service.resume(RUN_ID);

        assertThat(first.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
        assertThat(second.outcome()).isEqualTo(ResumeService.Outcome.ACCEPTED);
        assertThat(secondSuccessor.getToolCallId()).isNotEqualTo(firstSuccessor.getToolCallId());
        assertThat(read(secondSuccessor.getArguments()).path("form").path("fields").get(0).path("label").asText())
                .isEqualTo("字段 1: 仍未知 — 未找到匹配项");
        assertThat(this.store.pendingsLenient(RUN_ID)).hasSize(3);
        assertThat(duplicate.outcome()).isEqualTo(ResumeService.Outcome.PENDING_UNRESOLVED);
        verifyNoInteractions(this.preflight);
        verify(this.emitter, never()).confirmRequest(any(PendingToolCall.class));
        verify(this.chatService, never()).driveResumed(anyString(), anyString(), any(), any(), any());
        assertThat(this.store.ledger(RUN_ID)).isEmpty();
    }

    private void stubCancelParked() {
        doAnswer(invocation -> {
            RunSnapshot snapshot = this.store.get(RUN_ID);
            snapshot.setStatus(RunSnapshot.CANCELLED);
            this.store.save(snapshot);
            return null;
        }).when(this.chatService).cancelParked(RUN_ID);
    }

    private PendingToolCall waitingDetailsForm() {
        List<PendingToolCall> waiting = this.store.pendingsLenient(RUN_ID).stream()
                .filter(pending -> PendingToolCall.PENDING.equals(pending.getStatus()))
                .toList();
        assertThat(waiting).hasSize(1);
        assertThat(read(waiting.getFirst().getArguments()).path("form").path("purpose").asText())
                .isEqualTo(FormPurposes.CREATE_TASK_DETAILS);
        return waiting.getFirst();
    }

    private PendingToolCall seedSubmitted(String arguments, Resolution resolution, String result) {
        PendingToolCall parked = seedParked(arguments, resolution);
        GateCalls.submit(this.gate, RUN_ID, parked.getToolCallId(), result, "unit-test");
        assertThat(this.store.pending(RUN_ID, parked.getToolCallId()).getStatus())
                .isEqualTo(PendingToolCall.FRONTEND_RESULT);
        return parked;
    }

    private PendingToolCall seedParked(String arguments, Resolution resolution) {
        RunSnapshot snapshot = this.store.seedRun(RUN_ID, SESSION_ID);
        snapshot.setTenantId(1L);
        snapshot.setUserId(101L);
        snapshot.setModelId("test-model");
        snapshot.setContext(AiContext.of("tasks", null, null, null, null).toMap());
        List<Message> history = List.of(new SystemMessage(SYSTEM_TEXT), new UserMessage("create a task"));
        snapshot.setMessageJson(this.codec.serializeAll(history));
        this.store.save(snapshot);
        assertThat(this.detailsForm.parkIfUnresolved(RUN_ID, ORIGINAL_CALL, arguments, resolution, history)).isTrue();
        return this.store.pendingsLenient(RUN_ID).getFirst();
    }

    private static Resolution enumGap(String value) {
        return new Resolution(Status.UNRESOLVED, ENUM_ARGUMENTS, List.of(
                new UnresolvedReference(Kind.ENUM_VALUE, 0, 0, value, List.of("CNY", "USD"))),
                CreateTaskReferenceResolver.UNRESOLVED_CODE);
    }

    private List<SlotDefinition> catalog() {
        return List.of(new SlotDefinition("CURRENCY", "货币字典", List.of(
                new SlotDefinition.SlotField("code", "货币代码", true, List.of("CNY", "USD")))));
    }

    private static ToolPreflightService.PreflightReport passingReport() {
        return new ToolPreflightService.PreflightReport(true, Map.of("type", "EXPORT"), List.of(),
                "", "", null, "create-details-pass");
    }

    private JsonNode read(String json) {
        try {
            return this.mapper.readTree(json);
        }
        catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

}
