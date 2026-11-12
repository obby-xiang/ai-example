package com.example.configmgr.ai.run;

import com.example.configmgr.ai.config.AiConfigService;
import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.config.AiProviderRegistry;
import com.example.configmgr.ai.config.ProviderRuntime;
import com.example.configmgr.ai.conformance.InMemoryRunStore;
import com.example.configmgr.ai.memory.MessageJsonCodec;
import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.ai.slot.TaskSlotResolver;
import com.example.configmgr.ai.tool.AiContext;
import com.example.configmgr.ai.tool.AiToolNames;
import com.example.configmgr.ai.tool.ContextBuilder;
import com.example.configmgr.ai.tool.ToolRegistry;
import com.example.configmgr.ai.web.AiChatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreateTaskCorrectionRemovalTest {

    private static final String SESSION_ID = "s-create-correction-removal";

    private final InMemoryRunStore store = new InMemoryRunStore();

    private final AiProperties properties = new AiProperties();

    private final ChatModel chatModel = mock(ChatModel.class);

    private final ChatClient chatClient = mock(ChatClient.class);

    private final ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);

    private final ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);

    private final AiProviderRegistry providerRegistry = mock(AiProviderRegistry.class);

    private final ChatMemory chatMemory = mock(ChatMemory.class);

    private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

    private final ContextBuilder contextBuilder = mock(ContextBuilder.class);

    private final AiSessionService sessionService = mock(AiSessionService.class);

    private final CancellationRegistry cancellations = mock(CancellationRegistry.class);

    private final ToolActivityBeacon beacon = mock(ToolActivityBeacon.class);

    private final SseChatEmitter emitter = mock(SseChatEmitter.class);

    private final RunRegistry registry = mock(RunRegistry.class);

    private ScheduledExecutorService watchdogExecutor;

    private ResilientChatService service;

    @BeforeEach
    void setUp() {
        this.watchdogExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "create-correction-removal-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        MessageJsonCodec codec = new MessageJsonCodec(new ObjectMapper());
        this.service = new ResilientChatService(this.providerRegistry, this.chatMemory, codec, this.store,
                this.registry, this.toolRegistry, this.contextBuilder, this.sessionService, this.properties,
                this.cancellations, this.beacon, this.watchdogExecutor);
        com.example.configmgr.ai.history.ConversationTranscriptStore transcript =
                mock(com.example.configmgr.ai.history.ConversationTranscriptStore.class);
        when(transcript.append(anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), any(), org.mockito.ArgumentMatchers.anyLong()))
            .thenReturn(true);
        when(transcript.readPage(anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(
                new com.example.configmgr.ai.history.ConversationTranscriptStore.ReadPage(List.of(), 0L, 1L, null,
                        false, false));
        org.springframework.test.util.ReflectionTestUtils.setField(this.service, "transcript", transcript);
        this.service.setTaskSlotResolver(new TaskSlotResolver());
        stubFluentChain();
        when(this.cancellations.cancelState(anyString()))
                .thenReturn(CancellationRegistry.CancelState.NOT_CANCELLED);
        when(this.cancellations.onCancel(anyString(), any())).thenReturn(() -> {
        });
        when(this.chatMemory.get(anyString())).thenReturn(List.of());
        when(this.contextBuilder.buildContextMessage(any())).thenReturn("workspace");
        when(this.sessionService.ensure(anyString())).thenReturn(true);
        when(this.registry.of(anyString())).thenReturn(this.emitter);
        when(this.providerRegistry.target(org.mockito.ArgumentMatchers.nullable(String.class)))
                .thenReturn(Optional.of(new AiConfigService.ChatTarget(
                        new AiConfigService.ResolvedProvider(7L, "deepseek", "http://placeholder",
                                "/chat/completions", "deepseek-flash", "sk-test-0123456789", "fp", false),
                        "deepseek-flash", true)));
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
        when(this.streamSpec.chatResponse()).thenAnswer(invocation -> Flux.just(response("好", "stop")));
        when(this.chatModel.stream(any(Prompt.class)))
                .thenAnswer(invocation -> Flux.just(response("create专用纠正", "stop")));
    }

    @AfterEach
    void tearDown() {
        this.watchdogExecutor.shutdownNow();
    }

    @Test
    void createIntentPlainAnswerFinishesWithoutCreateCorrection() {
        chat("run-create-plain-answer", "帮我创建一个任务");

        verify(this.chatClient).prompt();
        verify(this.chatModel, never()).stream(any(Prompt.class));
        assertThat(this.store.get("run-create-plain-answer").getStatus()).isEqualTo(RunSnapshot.DONE);
        assertThat(this.store.get("run-create-plain-answer").getFinalText()).isEqualTo("好");
        assertThat(AiContext.fromMap(this.store.get("run-create-plain-answer").getContext()).getExtra())
                .doesNotContainKey("createTaskCorrectionUsed");
    }

    @Test
    void ordinaryQuestionsAndStatusQueriesDoNotRequestCreateCorrection() {
        chat("run-chinese-question", "如何创建任务？");
        chat("run-english-question", "How do I create a task?");
        chat("run-status-question", "这个任务创建后的状态怎么样？");

        verify(this.chatClient, times(3)).prompt();
        verify(this.chatModel, never()).stream(any(Prompt.class));
        assertThat(this.store.get("run-chinese-question").getFinalText()).isEqualTo("好");
        assertThat(this.store.get("run-english-question").getFinalText()).isEqualTo("好");
        assertThat(this.store.get("run-status-question").getFinalText()).isEqualTo("好");
    }

    private void chat(String runId, String message) {
        AiChatRequest request = new AiChatRequest(SESSION_ID, message,
                new AiChatRequest.Context("tasks", null, null, null, Map.of()), null, null);
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
        return new ProviderRuntime(7L, "deepseek", "deepseek-flash", "http://placeholder", "/chat/completions",
                "7|fp|u|p", org.springframework.ai.openai.OpenAiChatOptions.builder().model("deepseek-flash").build(),
                null, this.chatModel, this.chatClient);
    }

    private static ChatResponse response(String text, String finishReason) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).build(),
                org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder().finishReason(finishReason)
                        .build())));
    }

}
