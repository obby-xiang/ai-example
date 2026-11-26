package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.form.TaskTypeForm;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.slot.DefinitionCatalog;
import com.example.configmgr.ai.slot.SlotDefinition;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.example.configmgr.common.ResourceNotFoundException;
import com.example.configmgr.data.repo.ConfigStagingRowRepository;
import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.entity.ConfigField;
import com.example.configmgr.definition.service.DefinitionService;
import com.example.configmgr.job.service.JobService;
import com.example.configmgr.task.repo.TaskFileRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SpToolCallingManagerPendingArgumentsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private SpToolCallingManager manager;

    private ToolPreflightService preflight;

    private ToolPreflightService preflightProbe;

    private RunStore store;

    private RunRegistry registry;

    private ConfirmGate gate;

    private ToolRegistry toolRegistry;

    private MessageJsonCodec codec;

    private SseChatEmitter emitter;

    @BeforeEach
    void setUp() {
        this.store = mock(RunStore.class);
        this.registry = mock(RunRegistry.class);
        this.emitter = mock(SseChatEmitter.class);
        this.gate = mock(ConfirmGate.class);
        this.toolRegistry = mock(ToolRegistry.class);
        this.codec = mock(MessageJsonCodec.class);
        this.preflightProbe = mock(ToolPreflightService.class);
        this.manager = new SpToolCallingManager(this.store, this.registry, this.gate, this.toolRegistry,
                this.codec, mock(ToolActivityBeacon.class), mock(CancellationRegistry.class),
                new ToolResultLimiter(new AiProperties()), List.of(), this.preflightProbe);
        DefinitionService definitionService = mock(DefinitionService.class);
        when(definitionService.findByCode(anyString())).thenAnswer(invocation -> {
            if ("CURRENCY".equals(invocation.getArgument(0))) {
                return currencyDefinition();
            }
            throw new ResourceNotFoundException("unknown definition");
        });
        this.preflight = new ToolPreflightService(mock(JobService.class), mock(TaskFileRepository.class),
                mock(ConfigStagingRowRepository.class), definitionService);
    }

    @Test
    void argumentsAreByteIdenticalAcrossUserTexts() {
        String original = "{\"type\":\"EXPORT\",\"defCodes\":[],\"openWizard\":false,\"conditions\":{}}";
        String empty = bind(original, "").getArguments();
        assertThat(bind(original, "只创建").getArguments()).isEqualTo(empty);
        assertThat(bind(original, "仅创建").getArguments()).isEqualTo(empty);
        assertThat(bind(original, "不要创建").getArguments()).isEqualTo(empty);
        assertThat(bind(original, "导出CONFIG_CODE").getArguments()).isEqualTo(empty);
        assertThat(bind(original, "create an export task").getArguments()).isEqualTo(empty);
        assertThat(bind(original, "今天天气如何").getArguments()).isEqualTo(empty);
    }

    @Test
    void missingTypeStaysOnTaskTypePath() {
        PendingToolCall pending = bind("{\"defCodes\":[],\"conditions\":{}}", "创建导出任务");
        assertThat(TaskTypeForm.typeMissingOrInvalid(pending.getArguments())).isTrue();
    }

    @Test
    void explicitEmptyDefCodesStayForPostCreateCollection() {
        installResolver(List::of);
        PendingToolCall pending = bind("{\"type\":\"EXPORT\",\"defCodes\":[],\"conditions\":{}}", "普通问答");
        assertThat(read(pending.getArguments()).path("defCodes").isArray()).isTrue();
        assertThat(read(pending.getArguments()).path("defCodes").isEmpty()).isTrue();
        assertThat(pending.getCode()).isNull();
    }

    @Test
    void knownDefCodesPassPreflight() {
        PendingToolCall pending = bind("{\"type\":\"EXPORT\",\"defCodes\":[\"CURRENCY\"],\"conditions\":{}}", "普通问答");
        assertThat(this.preflight.inspect("create_task", pending.getArguments(), AiContext.empty()).pass()).isTrue();
    }

    @Test
    void unknownDefCodesFailPreflight() {
        PendingToolCall pending = bind("{\"type\":\"EXPORT\",\"defCodes\":[\"NOT_REAL\"],\"conditions\":{}}", "普通问答");
        assertThat(this.preflight.inspect("create_task", pending.getArguments(), AiContext.empty()).pass()).isFalse();
    }

    @Test
    void exportWithoutConditionsIsRejectedEvenWhenTextCouldSupplyThem() {
        PendingToolCall pending = bind("{\"type\":\"EXPORT\",\"defCodes\":[]}", "导出CNY货币字典");
        assertThat(this.preflight.inspect("create_task", pending.getArguments(), AiContext.empty()).pass()).isFalse();
    }

    @Test
    void referencesAreCanonicalBeforePreflight() {
        installResolver(() -> List.of(currencySlot()));
        AtomicReference<String> preflightArguments = new AtomicReference<>();
        when(this.preflightProbe.inspect(eq("create_task"), anyString(), any(AiContext.class))).thenAnswer(invocation -> {
            preflightArguments.set(invocation.getArgument(1));
            return failingReport();
        });
        execute("{\"type\":\"EXPORT\",\"defCodes\":[\"货币字典\"],\"conditions\":{\"fields\":[{\"fieldCode\":null,\"operator\":\"EQ\",\"value\":\"CNY\"}]}}");

        JsonNode canonical = read(preflightArguments.get());
        assertThat(canonical.path("defCodes").get(0).asText()).isEqualTo("CURRENCY");
        assertThat(canonical.path("conditions").path("fields").get(0).path("fieldCode").asText()).isEqualTo("code");
        assertThat(canonical.path("conditions").path("fields").get(0).path("value").asText()).isEqualTo("CNY");
        verify(this.preflightProbe).inspect(eq("create_task"), anyString(), any(AiContext.class));
        verify(this.gate, never()).awaitDecision(anyString(), any(PendingToolCall.class));
    }

    @Test
    void unresolvedReferencesDoNotReachPreflightOrHitl() {
        installResolver(() -> List.of(currencySlot()));
        String original = "{\"type\":\"EXPORT\",\"defCodes\":[\"未知字典\"],\"conditions\":{}}";
        execute(original);

        verifyNoInteractions(this.preflightProbe);
        verify(this.gate, never()).awaitDecision(anyString(), any(PendingToolCall.class));
        PendingToolCall pending = capturedPending();
        assertThat(pending.getCode()).isEqualTo(CreateTaskReferenceResolver.UNRESOLVED_CODE);
        assertThat(pending.getStatus()).isEqualTo(PendingToolCall.PRECHECK_FAILED);
        assertThat(pending.getArguments()).isEqualTo(original);
        assertThat(pending.isExecuted()).isFalse();
    }

    @Test
    void unresolvedCreateTaskInMultiToolBatchFailsClosedBeforePreflightOrHitl() {
        installResolver(() -> List.of(currencySlot()));
        when(this.preflightProbe.inspect(eq("create_task"), anyString(), any(AiContext.class)))
                .thenReturn(failingReport());
        String original = "{\"type\":\"EXPORT\",\"defCodes\":[\"未知字典\"],\"conditions\":{}}";

        executeCalls(
                new AssistantMessage.ToolCall("call-create", "function", "create_task", original),
                new AssistantMessage.ToolCall("call-create-2", "function", "create_task", original));

        verifyNoInteractions(this.preflightProbe);
        verify(this.gate, never()).awaitDecision(anyString(), any(PendingToolCall.class));
        ArgumentCaptor<PendingToolCall> captor = ArgumentCaptor.forClass(PendingToolCall.class);
        verify(this.store, atLeastOnce()).putPending(eq("run-pending-arguments"), captor.capture());
        assertThat(captor.getAllValues()).filteredOn(entry -> "create_task".equals(entry.getName()))
                .hasSize(2)
                .extracting(PendingToolCall::getToolCallId)
                .containsExactly("call-create", "call-create-2");
        assertThat(captor.getAllValues()).filteredOn(entry -> "call-create".equals(entry.getToolCallId()))
                .singleElement().satisfies(entry -> {
                    assertThat(entry.getCode()).isEqualTo(CreateTaskReferenceResolver.UNRESOLVED_CODE);
                    assertThat(entry.getStatus()).isEqualTo(PendingToolCall.PRECHECK_FAILED);
                    assertThat(entry.getArguments()).isEqualTo(original);
                    assertThat(entry.isExecuted()).isFalse();
                });
        assertThat(captor.getAllValues()).filteredOn(entry -> "call-create-2".equals(entry.getToolCallId()))
                .singleElement().satisfies(entry -> {
                    assertThat(entry.getCode()).isEqualTo(com.example.configmgr.ai.tool.ToolOutcome.CODE_UPSTREAM_FAILED);
                    assertThat(entry.getStatus()).isEqualTo(PendingToolCall.SKIPPED);
                    assertThat(entry.getArguments()).isEqualTo(original);
                    assertThat(entry.isExecuted()).isFalse();
                });
    }

    @Test
    void unresolvedCreateTaskPreventsAnyOtherActionInBatch() {
        installResolver(() -> List.of(currencySlot()));
        String unresolved = "{\"type\":\"EXPORT\",\"defCodes\":[\"未知字典\"],\"conditions\":{}}";
        String valid = "{\"type\":\"EXPORT\",\"defCodes\":[\"CURRENCY\"],\"conditions\":{}}";
        when(this.preflightProbe.inspect(eq("create_task"), eq(valid), any(AiContext.class)))
                .thenReturn(new ToolPreflightService.PreflightReport(true, Map.of("type", "EXPORT"), List.of(),
                        "valid", "", null, "valid-report"));
        when(this.gate.awaitDecision(anyString(), any(PendingToolCall.class))).thenAnswer(invocation -> {
            PendingToolCall pending = invocation.getArgument(1);
            pending.setStatus(PendingToolCall.REJECTED);
            return pending;
        });

        executeCalls(new AssistantMessage.ToolCall("call-unresolved", "function", "create_task", unresolved),
                new AssistantMessage.ToolCall("call-valid", "function", "create_task", valid));

        verifyNoInteractions(this.preflightProbe);
        verify(this.gate, never()).awaitDecision(anyString(), any(PendingToolCall.class));
        ArgumentCaptor<PendingToolCall> captor = ArgumentCaptor.forClass(PendingToolCall.class);
        verify(this.store, atLeastOnce()).putPending(eq("run-pending-arguments"), captor.capture());
        assertThat(captor.getAllValues()).filteredOn(entry -> "call-valid".equals(entry.getToolCallId()))
                .singleElement().satisfies(entry -> {
                    assertThat(entry.getStatus()).isEqualTo(PendingToolCall.SKIPPED);
                    assertThat(entry.isExecuted()).isFalse();
                });
    }

    @Test
    void unresolvedCreateTaskIsBlockedWithoutCompensationForm() throws Exception {
        CreateTaskReferenceResolver resolver = mock(CreateTaskReferenceResolver.class);
        String original = "{\"type\":\"EXPORT\",\"defCodes\":[\"未知字典\"],\"conditions\":{}}";
        CreateTaskReferenceResolver.Resolution unresolved = new CreateTaskReferenceResolver.Resolution(
                CreateTaskReferenceResolver.Status.UNRESOLVED, original, List.of(
                        new CreateTaskReferenceResolver.UnresolvedReference(CreateTaskReferenceResolver.Kind.DEFINITION,
                                0, -1, "未知字典", List.of())),
                CreateTaskReferenceResolver.UNRESOLVED_CODE);
        when(resolver.resolve(original)).thenReturn(unresolved);
        ReflectionTestUtils.setField(this.manager, "createTaskReferenceResolver", resolver);
        AtomicReference<String> persistedJson = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            persistedJson.set(this.mapper.writeValueAsString(invocation.getArgument(1)));
            return null;
        }).when(this.store).putPending(eq("run-pending-arguments"), any(PendingToolCall.class));

        ToolExecutionResult result = execute(original);

        verify(resolver).resolve(original);
        verifyNoInteractions(this.preflightProbe);
        PendingToolCall persisted = this.mapper.readValue(persistedJson.get(), PendingToolCall.class);
        assertThat(persisted.getCode()).isEqualTo(CreateTaskReferenceResolver.UNRESOLVED_CODE);
        assertThat(persisted.getStatus()).isEqualTo(PendingToolCall.PRECHECK_FAILED);
        assertThat(persisted.isTranscriptSegmentRequired()).isFalse();
        assertThat(result.conversationHistory().toString()).contains(CreateTaskReferenceResolver.UNRESOLVED_CODE);
        verify(this.emitter, never()).toolStart(any(PendingToolCall.class));
        verify(this.emitter, never()).toolResult(any(PendingToolCall.class),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean(), anyString());
    }

    @Test
    void catalogFailureDoesNotReachPreflightOrHitl() {
        installResolver(() -> {
            throw new IllegalStateException("catalog unavailable");
        });
        String original = "{\"type\":\"EXPORT\",\"defCodes\":[\"CURRENCY\"],\"conditions\":{}}";
        execute(original);

        verifyNoInteractions(this.preflightProbe);
        verify(this.gate, never()).awaitDecision(anyString(), any(PendingToolCall.class));
        PendingToolCall pending = capturedPending();
        assertThat(pending.getCode()).isEqualTo(CreateTaskReferenceResolver.FAILED_CODE);
        assertThat(pending.getStatus()).isEqualTo(PendingToolCall.PRECHECK_FAILED);
        assertThat(pending.getArguments()).isEqualTo(original);
        assertThat(pending.isExecuted()).isFalse();
    }

    @Test
    void missingResolverFailsClosedBeforePreflightOrHitl() {
        when(this.preflightProbe.inspect(eq("create_task"), anyString(), any(AiContext.class)))
                .thenReturn(failingReport());
        String original = "{\"type\":\"EXPORT\",\"defCodes\":[\"CURRENCY\"],\"conditions\":{}}";
        execute(original);

        verify(this.preflightProbe, never()).inspect(anyString(), anyString(), any(AiContext.class));
        verify(this.gate, never()).awaitDecision(anyString(), any(PendingToolCall.class));
        PendingToolCall pending = capturedPending();
        assertThat(pending.getCode()).isEqualTo(CreateTaskReferenceResolver.FAILED_CODE);
        assertThat(pending.getStatus()).isEqualTo(PendingToolCall.PRECHECK_FAILED);
        assertThat(pending.getArguments()).isEqualTo(original);
        assertThat(pending.isExecuted()).isFalse();
    }

    @Test
    void missingDefCodesFailClosedBeforePreflightOrHitl() {
        installResolver(List::of);
        assertDefCodesOmissionBlocked("{\"type\":\"EXPORT\",\"conditions\":{}}");
    }

    @Test
    void nullDefCodesFailClosedBeforePreflightOrHitl() {
        installResolver(List::of);
        assertDefCodesOmissionBlocked("{\"type\":\"EXPORT\",\"defCodes\":null,\"conditions\":{}}");
    }

    @Test
    void explicitEmptyDefCodesRetainPostCreateCollectionPath() {
        installResolver(List::of);
        when(this.preflightProbe.inspect(eq("create_task"), anyString(), any(AiContext.class)))
                .thenReturn(failingReport());
        String original = "{\"type\":\"EXPORT\",\"defCodes\":[],\"conditions\":{}}";
        execute(original);

        ArgumentCaptor<String> arguments = ArgumentCaptor.forClass(String.class);
        verify(this.preflightProbe).inspect(eq("create_task"), arguments.capture(), any(AiContext.class));
        assertThat(arguments.getValue()).isEqualTo(original);
        assertThat(read(arguments.getValue()).path("defCodes").isArray()).isTrue();
        assertThat(read(arguments.getValue()).path("defCodes").isEmpty()).isTrue();
        verify(this.gate, never()).awaitDecision(anyString(), any(PendingToolCall.class));
    }

    @Test
    void exportNonEmptyConditionsPassUnchanged() {
        String original = "{\"type\":\"EXPORT\",\"defCodes\":[\"CURRENCY\"],\"conditions\":{\"fields\":[{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"CNY\"}]}}";
        PendingToolCall pending = bind(original, "普通问答");
        assertThat(pending.getArguments()).isEqualTo(original);
        assertThat(this.preflight.inspect("create_task", pending.getArguments(), AiContext.empty()).pass()).isTrue();
    }

    @Test
    void importWithoutConditionsPasses() {
        installResolver(List::of);
        String original = "{\"type\":\"IMPORT\",\"defCodes\":[]}";
        PendingToolCall pending = bind(original, "普通问答");
        assertThat(pending.getArguments()).isEqualTo(original);
        assertThat(pending.getCode()).isNull();
        assertThat(this.preflight.inspect("create_task", pending.getArguments(), AiContext.empty()).pass()).isTrue();
    }

    @Test
    void openWizardDefaultsToTrue() {
        PendingToolCall pending = bind("{\"type\":\"EXPORT\",\"defCodes\":[],\"conditions\":{}}", "普通问答");
        assertThat(RunContextRefresher.openWizard(pending.getArguments())).isTrue();
    }

    @Test
    void openWizardTrueStaysTrue() {
        PendingToolCall pending = bind("{\"type\":\"EXPORT\",\"defCodes\":[],\"openWizard\":true,\"conditions\":{}}", "普通问答");
        assertThat(RunContextRefresher.openWizard(pending.getArguments())).isTrue();
    }

    @Test
    void openWizardFalseStaysFalse() {
        String original = "{\"type\":\"EXPORT\",\"defCodes\":[],\"openWizard\":false,\"conditions\":{}}";
        PendingToolCall pending = bind(original, "普通问答");
        assertThat(pending.getArguments()).isEqualTo(original);
        assertThat(RunContextRefresher.openWizard(pending.getArguments())).isFalse();
    }

    @Test
    void openWizardNonBooleanStaysFalse() {
        PendingToolCall pending = bind("{\"type\":\"EXPORT\",\"defCodes\":[],\"openWizard\":\"yes\",\"conditions\":{}}", "普通问答");
        assertThat(RunContextRefresher.openWizard(pending.getArguments())).isFalse();
    }

    private PendingToolCall bind(String arguments, String userText) {
        installResolver(() -> List.of(currencySlot()));
        PendingToolCall pending = new PendingToolCall();
        pending.setToolCallId("call-create");
        pending.setName("create_task");
        pending.setArguments(arguments);
        ReflectionTestUtils.invokeMethod(this.manager, "bindPendingArguments", "run-pending-arguments", AiContext.empty(),
                List.of(pending));
        return pending;
    }

    private void installResolver(DefinitionCatalog catalog) {
        ReflectionTestUtils.setField(this.manager, "createTaskReferenceResolver",
                new CreateTaskReferenceResolver(catalog, this.mapper));
    }

    private ToolExecutionResult execute(String arguments) {
        return executeCalls(new AssistantMessage.ToolCall("call-create", "function", "create_task", arguments));
    }

    private ToolExecutionResult executeCalls(AssistantMessage.ToolCall... calls) {
        String runId = "run-pending-arguments";
        RunSnapshot snapshot = new RunSnapshot();
        snapshot.setRunId(runId);
        snapshot.setSessionId("session-pending-arguments");
        snapshot.setStatus(RunSnapshot.RUNNING);
        snapshot.setContext(AiContext.empty().toMap());
        when(this.store.get(runId)).thenReturn(snapshot);
        when(this.registry.of(runId)).thenReturn(this.emitter);
        when(this.codec.serializeAll(anyList())).thenReturn(List.of("[]"));
        when(this.toolRegistry.channelOf("create_task")).thenReturn(ToolMeta.Channel.BACKEND);
        when(this.toolRegistry.riskOf("create_task")).thenReturn(ToolMeta.RiskLevel.DANGER);
        when(this.toolRegistry.isContextMatched(eq("create_task"), any(AiContext.class))).thenReturn(true);
        ToolCallingChatOptions options = ToolCallingChatOptions.builder().toolContext(Map.of("runId", runId)).build();
        Prompt prompt = new Prompt(List.of(new UserMessage("structured arguments only")), options);
        AssistantMessage assistant = AssistantMessage.builder().toolCalls(List.of(calls)).build();
        return this.manager.executeToolCalls(prompt, new ChatResponse(List.of(new Generation(assistant))));
    }

    private PendingToolCall capturedPending() {
        ArgumentCaptor<PendingToolCall> captor = ArgumentCaptor.forClass(PendingToolCall.class);
        verify(this.store).putPending(eq("run-pending-arguments"), captor.capture());
        return captor.getValue();
    }

    private void assertDefCodesOmissionBlocked(String original) {
        when(this.preflightProbe.inspect(eq("create_task"), anyString(), any(AiContext.class)))
                .thenReturn(failingReport());
        execute(original);

        verify(this.preflightProbe, never()).inspect(anyString(), anyString(), any(AiContext.class));
        verify(this.gate, never()).awaitDecision(anyString(), any(PendingToolCall.class));
        PendingToolCall pending = capturedPending();
        assertThat(pending.getCode()).isEqualTo(CreateTaskReferenceResolver.FAILED_CODE);
        assertThat(pending.getStatus()).isEqualTo(PendingToolCall.PRECHECK_FAILED);
        assertThat(pending.getArguments()).isEqualTo(original);
        assertThat(pending.isExecuted()).isFalse();
    }

    private static ToolPreflightService.PreflightReport failingReport() {
        return new ToolPreflightService.PreflightReport(false, Map.of("type", "EXPORT"), List.of(
                new ToolPreflightService.PreflightReport.Check("configuration references", false, "stop after capture")),
                "stop after capture", "", null, "reference-test");
    }

    private static SlotDefinition currencySlot() {
        return new SlotDefinition("CURRENCY", "货币字典", List.of(
                new SlotDefinition.SlotField("code", "货币代码", true, List.of("CNY", "USD"))));
    }

    private com.fasterxml.jackson.databind.JsonNode read(String arguments) {
        try {
            return this.mapper.readTree(arguments);
        }
        catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static ConfigDefinition currencyDefinition() {
        ConfigDefinition definition = new ConfigDefinition();
        definition.setCode("CURRENCY");
        definition.setName("货币字典");
        definition.setLevel(ConfigDefinition.ConfigLevel.GLOBAL);
        ConfigField field = new ConfigField();
        field.setDefCode("CURRENCY");
        field.setCode("code");
        field.setLabel("货币代码");
        field.setKey(true);
        field.setFieldType(ConfigField.FieldType.STRING);
        definition.getFields().add(field);
        return definition;
    }
}
