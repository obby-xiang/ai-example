package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.ProviderRuntime;
import com.example.configmgr.ai.history.SyntheticMessages;
import com.example.configmgr.ai.form.CreateTaskDetailsForm;
import com.example.configmgr.ai.intent.ActionIntentParser;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.run.CancellationRegistry;
import com.example.configmgr.ai.run.PendingToolCall;
import com.example.configmgr.ai.run.RunRegistry;
import com.example.configmgr.ai.run.RunSnapshot;
import com.example.configmgr.ai.run.RunStore;
import com.example.configmgr.ai.run.SseChatEmitter;
import com.example.configmgr.ai.run.StreamViolationException;
import com.example.configmgr.ai.run.ToolActivityBeacon;
import com.example.configmgr.ai.slot.DefinitionCatalog;
import com.example.configmgr.ai.slot.SlotDefinition;
import com.example.configmgr.ai.tenant.RequestContextUtils;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.example.configmgr.data.entity.ConfigDataRow;
import com.example.configmgr.data.repo.ConfigDataRowRepository;
import com.example.configmgr.definition.entity.ConfigDefinition;
import com.example.configmgr.definition.service.DefinitionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SpToolCallingManagerActionIntentTest {

    private static final String RUN_ID = "run-action-intent";

    private static final String ORIGINAL = "{\"type\":\"EXPORT\",\"defCodes\":[\"cny货币字典\"],\"conditions\":{}}";

    private static final String PARSED_PLAN = "{\"actions\":[{\"id\":\"a1\",\"tool\":\"create_task\","
            + "\"arguments\":{\"type\":\"EXPORT\",\"defCodes\":[\"货币字典\"],"
            + "\"conditions\":{\"fields\":[{\"fieldCode\":\"货币代码\",\"operator\":\"EQ\",\"value\":\"cny\"}]}},"
            + "\"unresolved\":[],\"dependsOn\":[]}]}";

    private final ObjectMapper mapper = new ObjectMapper();

    private final RunStore store = mock(RunStore.class);

    private final RunRegistry registry = mock(RunRegistry.class);

    private final ConfirmGate gate = mock(ConfirmGate.class);

    private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

    private final ToolPreflightService preflight = mock(ToolPreflightService.class);

    private final SseChatEmitter emitter = mock(SseChatEmitter.class);

    private final ConfigDataRowRepository rows = mock(ConfigDataRowRepository.class);

    private final DefinitionService definitions = mock(DefinitionService.class);

    private final AtomicInteger modelCalls = new AtomicInteger();

    private final AtomicReference<Prompt> modelPrompt = new AtomicReference<>();

    private SpToolCallingManager manager;

    private ChatModel chatModel;

    private AiProperties properties;

    @BeforeEach
    void setUp() {
        this.properties = new AiProperties();
        this.manager = new SpToolCallingManager(this.store, this.registry, this.gate, this.toolRegistry,
                mock(MessageJsonCodec.class), mock(ToolActivityBeacon.class), mock(CancellationRegistry.class),
                new ToolResultLimiter(this.properties), List.of(), this.preflight, null, null, null,
                this.properties, null);
        DefinitionCatalog catalog = () -> List.of(new SlotDefinition("CURRENCY", "货币字典", List.of(
                new SlotDefinition.SlotField("code", "货币代码", true, List.of()))));
        this.manager.setCreateTaskReferenceResolver(new CreateTaskReferenceResolver(catalog, this.mapper,
                this.definitions, this.rows));
        this.manager.setActionIntentParser(new ActionIntentParser(this.mapper, catalog));
        ConfigDefinition definition = new ConfigDefinition();
        definition.setCode("CURRENCY");
        definition.setLevel(ConfigDefinition.ConfigLevel.GLOBAL);
        when(this.definitions.findByCode("CURRENCY")).thenReturn(definition);
        ConfigDataRow row = new ConfigDataRow();
        row.setRowKey("CNY");
        when(this.rows.findRow("CURRENCY", "GLOBAL", null, "CNY")).thenReturn(Optional.of(row));
        RunSnapshot snapshot = new RunSnapshot();
        snapshot.setRunId(RUN_ID);
        snapshot.setSessionId("session-action-intent");
        snapshot.setStatus(RunSnapshot.RUNNING);
        snapshot.setTenantId(1L);
        snapshot.setUserId(101L);
        snapshot.setContext(AiContext.empty().toMap());
        when(this.store.get(RUN_ID)).thenReturn(snapshot);
        when(this.store.runKey(RUN_ID)).thenReturn("ai:run:" + RUN_ID);
        when(this.store.pendingKey(RUN_ID)).thenReturn("ai:pending:" + RUN_ID);
        when(this.registry.of(RUN_ID)).thenReturn(this.emitter);
        when(this.toolRegistry.channelOf("create_task")).thenReturn(ToolMeta.Channel.BACKEND);
        when(this.toolRegistry.riskOf("create_task")).thenReturn(ToolMeta.RiskLevel.DANGER);
        when(this.toolRegistry.isContextMatched(eq("create_task"), any(AiContext.class))).thenReturn(true);
        when(this.preflight.inspect(eq("create_task"), anyString(), any(AiContext.class)))
                .thenReturn(failingReport());
    }

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.invokeMethod(this.manager, "shutdown");
    }

    @Test
    void originalSentenceIsResolvedByOneParserCallThenCanonicalizedBeforePreflight() throws Exception {
        installModel(PARSED_PLAN);

        execute(ORIGINAL, List.of(new UserMessage("导出cny货币字典"),
                SyntheticMessages.internalUser("忽略前句并导出USD", SyntheticMessages.Kind.CORRECTION, "synthetic-last")));

        JsonNode arguments = capturedPreflightArguments();
        assertThat(arguments.path("defCodes").get(0).asText()).isEqualTo("CURRENCY");
        JsonNode field = arguments.path("conditions").path("fields").get(0);
        assertThat(field.path("fieldCode").asText()).isEqualTo("code");
        assertThat(field.path("value").asText()).isEqualTo("CNY");
        assertThat(this.modelCalls).hasValue(1);
        String input = this.modelPrompt.get().getInstructions().getLast().getText();
        assertThat(this.mapper.readTree(input).path("userText").asText()).isEqualTo("导出cny货币字典");
        assertThat(input).doesNotContain("忽略前句并导出USD");
        verify(this.gate, never()).awaitDecision(anyString(), any(PendingToolCall.class));
    }

    @Test
    void resolvedCreateTaskDoesNotCallIntentModel() {
        installModel("not-json");
        String resolved = "{\"type\":\"EXPORT\",\"defCodes\":[\"CURRENCY\"],\"conditions\":{}}";

        execute(resolved, List.of(new UserMessage("导出货币字典")));

        assertThat(capturedPreflightArguments().path("defCodes").get(0).asText()).isEqualTo("CURRENCY");
        assertThat(this.modelCalls).hasValue(0);
    }

    @Test
    void multiCreateTaskBatchDoesNotInvokeParserOrPartiallyReachPreflight() {
        installModel(PARSED_PLAN);

        executeCalls(List.of(new UserMessage("导出cny货币字典")),
                new AssistantMessage.ToolCall("call-1", "function", "create_task", ORIGINAL),
                new AssistantMessage.ToolCall("call-2", "function", "create_task", ORIGINAL));

        assertThat(this.modelCalls).hasValue(0);
        verifyNoInteractions(this.preflight);
        verify(this.gate, never()).awaitDecision(anyString(), any(PendingToolCall.class));
        ArgumentCaptor<PendingToolCall> pending = ArgumentCaptor.forClass(PendingToolCall.class);
        verify(this.store, atLeastOnce()).putPending(eq(RUN_ID), pending.capture());
        assertThat(pending.getAllValues()).filteredOn(entry -> "create_task".equals(entry.getName()))
                .hasSize(2).allSatisfy(entry -> {
                    assertThat(entry.isExecuted()).isFalse();
                    assertThat(entry.getCode()).isEqualTo(CreateTaskReferenceResolver.UNRESOLVED_CODE);
                });
    }

    @Test
    void invalidParserJsonFailsClosedWithoutHitlOrSecondModelCall() {
        installModel("not-json");

        execute(ORIGINAL, List.of(new UserMessage("导出cny货币字典")));

        assertThat(this.modelCalls).hasValue(1);
        assertFailedWithoutPreflightOrHitl();
        assertThat(capturedPending().getArguments()).isEqualTo(ORIGINAL);
    }

    @Test
    void stillUnresolvedAfterOneParseDoesNotReachHitlOrTriggerAnotherParse() {
        CreateTaskDetailsForm detailsForm = spy(new CreateTaskDetailsForm(this.store, this.registry, this.gate,
                mock(MessageJsonCodec.class), this.properties));
        this.manager.setCreateTaskDetailsForm(detailsForm);
        installModel("{\"actions\":[{\"id\":\"a1\",\"tool\":\"create_task\",\"arguments\":"
                + "{\"type\":\"EXPORT\",\"defCodes\":[\"仍未知\"],\"conditions\":{}},"
                + "\"unresolved\":[\"definition\"],\"dependsOn\":[]}]}");

        execute(ORIGINAL, List.of(new UserMessage("导出cny货币字典")));

        assertThat(this.modelCalls).hasValue(1);
        verifyNoInteractions(detailsForm);
        verifyNoInteractions(this.preflight);
        verify(this.gate, never()).awaitDecision(anyString(), any(PendingToolCall.class));
        PendingToolCall pending = capturedPending();
        assertThat(pending.getCode()).isEqualTo(CreateTaskReferenceResolver.FAILED_CODE);
        assertThat(pending.isExecuted()).isFalse();
    }

    @Test
    void modelTimeoutCancelsWorkAndFailsWithoutHitl() throws Exception {
        this.properties.getTool().setExecTimeout(Duration.ofMillis(100));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        this.chatModel = prompt -> {
            this.modelCalls.incrementAndGet();
            started.countDown();
            try {
                new CountDownLatch(1).await();
            }
            catch (InterruptedException ex) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return null;
        };

        execute(ORIGINAL, List.of(new UserMessage("导出cny货币字典")));

        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(this.modelCalls).hasValue(1);
        assertFailedWithoutPreflightOrHitl();
    }

    @Test
    void interruptedWaitCancelsModelAndRestoresCallerInterrupt() throws Exception {
        this.properties.getTool().setExecTimeout(Duration.ofSeconds(30));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch modelInterrupted = new CountDownLatch(1);
        AtomicReference<Thread> caller = new AtomicReference<>();
        AtomicBoolean callerInterrupted = new AtomicBoolean();
        this.chatModel = prompt -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            }
            catch (InterruptedException ex) {
                modelInterrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return null;
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> execution = executor.submit(() -> {
                caller.set(Thread.currentThread());
                try {
                    execute(ORIGINAL, List.of(new UserMessage("导出cny货币字典")));
                }
                finally {
                    callerInterrupted.set(Thread.currentThread().isInterrupted());
                }
            });
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            caller.get().interrupt();
            assertThatThrownByExecution(execution);
            assertThat(modelInterrupted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(callerInterrupted).isTrue();
        }
        finally {
            executor.shutdownNow();
        }
        verifyNoInteractions(this.preflight);
        verify(this.gate, never()).awaitDecision(anyString(), any(PendingToolCall.class));
        verify(this.store, never()).putPending(anyString(), any(PendingToolCall.class));
    }

    @Test
    void realCancellationBeforeParseSkipsModelAndHitl() {
        CancellationRegistry cancellations = installRealCancellationRegistry();
        try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(1L, 101L)) {
            cancellations.register(RUN_ID);
            cancellations.cancel(RUN_ID, "test-cancel-before");
            installModel(PARSED_PLAN);

            assertThatThrownBy(() -> execute(ORIGINAL, List.of(new UserMessage("导出cny货币字典"))))
                    .isInstanceOfSatisfying(StreamViolationException.class,
                            ex -> assertThat(ex.getCode()).isEqualTo(StreamViolationException.CANCELLED));
            assertThat(this.modelCalls).hasValue(0);
            verifyNoInteractions(this.preflight);
            verify(this.gate, never()).awaitDecision(anyString(), any(PendingToolCall.class));
            verify(this.store, never()).putPending(anyString(), any(PendingToolCall.class));
        }
    }

    @Test
    void realCancellationDuringParseCancelsFutureAndClosesListener() throws Exception {
        CancellationRegistry cancellations = installRealCancellationRegistry();
        this.properties.getTool().setExecTimeout(Duration.ofSeconds(2));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch modelInterrupted = new CountDownLatch(1);
        this.chatModel = prompt -> {
            this.modelCalls.incrementAndGet();
            started.countDown();
            try {
                new CountDownLatch(1).await();
            }
            catch (InterruptedException ex) {
                modelInterrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return null;
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(1L, 101L)) {
            cancellations.register(RUN_ID);
            Future<?> execution = executor.submit(() -> {
                try (RequestContextUtils.SafeCloseable workerIdentity = RequestContextUtils.useContext(1L, 101L)) {
                    execute(ORIGINAL, List.of(new UserMessage("导出cny货币字典")));
                }
            });
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            cancellations.cancel(RUN_ID, "test-cancel-during");

            assertThatThrownByExecution(execution);
            assertThat(modelInterrupted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(cancellations.status(RUN_ID)).containsEntry("listeners", 0);
            verifyNoInteractions(this.preflight);
            verify(this.gate, never()).awaitDecision(anyString(), any(PendingToolCall.class));
            verify(this.store, never()).putPending(anyString(), any(PendingToolCall.class));
        }
        finally {
            executor.shutdownNow();
        }
    }

    @Test
    void realCancellationAfterModelReturnRejectsPlanBeforePreflight() {
        this.properties.getTool().setExecTimeout(Duration.ofSeconds(2));
        CancellationRegistry cancellations = spy(installRealCancellationRegistry());
        ReflectionTestUtils.setField(this.manager, "cancellations", cancellations);
        ExecutorService parserExecutor = spy(Executors.newSingleThreadExecutor());
        ReflectionTestUtils.setField(this.manager, "toolTimeoutExecutor", parserExecutor);
        AtomicReference<Future<?>> parserFuture = new AtomicReference<>();
        AtomicBoolean modelReturned = new AtomicBoolean();
        AtomicBoolean futureDelivered = new AtomicBoolean();
        AtomicBoolean cancelTriggered = new AtomicBoolean();
        doAnswer(invocation -> {
            Future<?> future = (Future<?>) invocation.callRealMethod();
            Future<?> observed = new ObservedFuture<>(future, futureDelivered);
            parserFuture.set(observed);
            return observed;
        }).when(parserExecutor).submit(any(java.util.concurrent.Callable.class));
        doAnswer(invocation -> {
            Future<?> future = parserFuture.get();
            if (modelReturned.get() && futureDelivered.get() && future != null && future.isDone()
                    && cancelTriggered.compareAndSet(false, true)) {
                cancellations.cancel(RUN_ID, "test-cancel-after-future");
            }
            return invocation.callRealMethod();
        }).when(cancellations).cancelState(RUN_ID);
        try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(1L, 101L)) {
            cancellations.register(RUN_ID);
            this.chatModel = prompt -> {
                this.modelCalls.incrementAndGet();
                ChatResponse response = parserResponse(PARSED_PLAN);
                modelReturned.set(true);
                return response;
            };

            Throwable thrown = catchThrowable(() -> execute(ORIGINAL, List.of(new UserMessage("导出cny货币字典"))));
            assertThat(thrown).withFailMessage("modelReturned=%s futureDelivered=%s future=%s", modelReturned.get(),
                            futureDelivered.get(), parserFuture.get())
                    .isInstanceOfSatisfying(StreamViolationException.class,
                            ex -> assertThat(ex.getCode()).isEqualTo(StreamViolationException.CANCELLED));
            assertThat(this.modelCalls).hasValue(1);
            assertThat(futureDelivered).as("controlled Future.get must return before cancellation").isTrue();
            assertThat(cancelTriggered).isTrue();
            assertThat(modelReturned).isTrue();
            assertThat(parserFuture.get().isDone()).isTrue();
            assertThat(cancellations.status(RUN_ID)).containsEntry("listeners", 0);
            verifyNoInteractions(this.preflight);
            verify(this.gate, never()).awaitDecision(anyString(), any(PendingToolCall.class));
            verify(this.store, never()).putPending(anyString(), any(PendingToolCall.class));
        }
    }

    @Test
    void parserSystemFailureHasNoBusinessSideEffectsOrModelRetry() {
        AtomicInteger catalogCalls = new AtomicInteger();
        DefinitionCatalog catalog = () -> {
            if (catalogCalls.incrementAndGet() > 1) {
                throw new IllegalStateException("catalog failed");
            }
            return List.of(new SlotDefinition("CURRENCY", "货币字典", List.of(
                    new SlotDefinition.SlotField("code", "货币代码", true, List.of()))));
        };
        this.manager.setCreateTaskReferenceResolver(new CreateTaskReferenceResolver(catalog, this.mapper));
        this.manager.setActionIntentParser(new ActionIntentParser(this.mapper, catalog));
        installModel(PARSED_PLAN);

        execute(ORIGINAL, List.of(new UserMessage("导出cny货币字典")));

        assertThat(this.modelCalls).hasValue(0);
        assertFailedWithoutPreflightOrHitl();
        verifyNoInteractions(this.rows);
        verify(this.emitter, never()).confirmRequest(any(PendingToolCall.class));
        verify(this.emitter, never()).frontendToolRequest(any(PendingToolCall.class), any());
        assertThat(capturedPending().isExecuted()).isFalse();
    }

    private void installModel(String arguments) {
        this.chatModel = prompt -> {
            this.modelCalls.incrementAndGet();
            this.modelPrompt.set(prompt);
            return parserResponse(arguments);
        };
    }

    private static ChatResponse parserResponse(String arguments) {
        AssistantMessage assistant = AssistantMessage.builder().toolCalls(List.of(new AssistantMessage.ToolCall(
                "plan-1", "function", ActionIntentParser.PLANNING_TOOL, arguments))).build();
        return new ChatResponse(List.of(new Generation(assistant)));
    }

    private CancellationRegistry installRealCancellationRegistry() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(null);
        CancellationRegistry cancellations = new CancellationRegistry(redis, this.properties);
        ReflectionTestUtils.setField(this.manager, "cancellations", cancellations);
        return cancellations;
    }

    private void execute(String arguments, List<Message> history) {
        executeCalls(history, new AssistantMessage.ToolCall("call-create", "function", "create_task", arguments));
    }

    private void executeCalls(List<Message> history, AssistantMessage.ToolCall... calls) {
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder().name("create_task")
                .description("创建任务").inputSchema("{\"type\":\"object\",\"properties\":{"
                        + "\"type\":{\"type\":\"string\"},\"defCodes\":{\"type\":\"array\"},"
                        + "\"conditions\":{\"type\":\"object\"}},\"required\":[\"type\",\"defCodes\"]}")
                .build());
        ToolCallingChatOptions options = ToolCallingChatOptions.builder().toolCallbacks(List.of(callback))
                .toolContext(new java.util.LinkedHashMap<>(Map.of("runId", RUN_ID,
                        SpToolCallingManager.TOOL_CONTEXT_PROVIDER_RUNTIME, runtime(this.chatModel))))
                .build();
        AssistantMessage assistant = AssistantMessage.builder().toolCalls(List.of(calls)).build();
        this.manager.executeToolCalls(new Prompt(history, options), new ChatResponse(List.of(new Generation(assistant))));
    }

    private ProviderRuntime runtime(ChatModel model) {
        return new ProviderRuntime(7L, "turn-provider", "turn-model", "http://localhost:0",
                "/v1/chat/completions", "turn-runtime", OpenAiChatOptions.builder().model("turn-model").build(),
                null, model, null);
    }

    private JsonNode capturedPreflightArguments() {
        ArgumentCaptor<String> arguments = ArgumentCaptor.forClass(String.class);
        verify(this.preflight).inspect(eq("create_task"), arguments.capture(), any(AiContext.class));
        try {
            return this.mapper.readTree(arguments.getValue());
        }
        catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private PendingToolCall capturedPending() {
        ArgumentCaptor<PendingToolCall> pending = ArgumentCaptor.forClass(PendingToolCall.class);
        verify(this.store).putPending(eq(RUN_ID), pending.capture());
        return pending.getValue();
    }

    private void assertFailedWithoutPreflightOrHitl() {
        verifyNoInteractions(this.preflight);
        verify(this.gate, never()).awaitDecision(anyString(), any(PendingToolCall.class));
        PendingToolCall pending = capturedPending();
        assertThat(pending.getCode()).isEqualTo(CreateTaskReferenceResolver.FAILED_CODE);
        assertThat(pending.isExecuted()).isFalse();
    }

    private static void assertThatThrownByExecution(Future<?> execution) throws Exception {
        try {
            execution.get(2, TimeUnit.SECONDS);
            throw new AssertionError("cancelled parser wait must not complete normally");
        }
        catch (ExecutionException ex) {
            assertThat(ex.getCause()).isInstanceOf(StreamViolationException.class);
            assertThat(((StreamViolationException) ex.getCause()).getCode())
                    .isEqualTo(StreamViolationException.CANCELLED);
        }
    }

    private static final class ObservedFuture<T> implements Future<T> {

        private final Future<T> delegate;

        private final AtomicBoolean delivered;

        private ObservedFuture(Future<T> delegate, AtomicBoolean delivered) {
            this.delegate = delegate;
            this.delivered = delivered;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return this.delegate.cancel(mayInterruptIfRunning);
        }

        @Override
        public boolean isCancelled() {
            return this.delegate.isCancelled();
        }

        @Override
        public boolean isDone() {
            return this.delegate.isDone();
        }

        @Override
        public T get() throws InterruptedException, ExecutionException {
            T result = this.delegate.get();
            this.delivered.set(true);
            return result;
        }

        @Override
        public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException,
                java.util.concurrent.TimeoutException {
            T result = this.delegate.get(timeout, unit);
            this.delivered.set(true);
            return result;
        }
    }

    private static ToolPreflightService.PreflightReport failingReport() {
        return new ToolPreflightService.PreflightReport(false, Map.of("type","EXPORT"), List.of(
                new ToolPreflightService.PreflightReport.Check("test stop", false, "stop after capture")),
                "stop after capture", "", null, "intent-test");
    }
}
