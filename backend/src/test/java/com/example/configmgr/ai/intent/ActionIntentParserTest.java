package com.example.configmgr.ai.intent;

import com.example.configmgr.ai.config.ProviderRuntime;
import com.example.configmgr.ai.slot.SlotDefinition;
import com.example.configmgr.ai.tools.AiTools;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ActionIntentParserTest {

    private static final String ORIGINAL = "{\"type\":\"EXPORT\",\"defCodes\":[\"cny货币字典\"],\"conditions\":{}}";

    private static final String VALID_PLAN = "{\"actions\":[{\"id\":\"a1\",\"tool\":\"create_task\","
            + "\"arguments\":{\"type\":\"EXPORT\",\"defCodes\":[\"货币字典\"],"
            + "\"conditions\":{\"fields\":[{\"fieldCode\":\"货币代码\",\"operator\":\"EQ\",\"value\":\"cny\"}]}},"
            + "\"unresolved\":[],\"dependsOn\":[]}]}";

    private final ObjectMapper mapper = new ObjectMapper();

    private final AtomicReference<Prompt> capturedPrompt = new AtomicReference<>();

    private ChatModel chatModel;

    @Test
    void parsesOriginalSentenceWithSchemaAndCatalogAsModelContext() throws Exception {
        ActionIntentParser.Result result = parse(VALID_PLAN, null);

        assertThat(result.status()).isEqualTo(ActionIntentParser.Status.PARSED);
        assertThat(result.plan().actions()).hasSize(1);
        ActionPlan.Action action = result.plan().actions().getFirst();
        assertThat(action.id()).isEqualTo("a1");
        assertThat(action.tool()).isEqualTo("create_task");
        assertThat(action.unresolved()).isEmpty();
        assertThat(action.dependsOn()).isEmpty();
        assertThat(action.arguments().path("defCodes").get(0).asText()).isEqualTo("货币字典");
        Prompt prompt = this.capturedPrompt.get();
        String modelInput = prompt.getInstructions().getLast().getText();
        JsonNode input = this.mapper.readTree(modelInput);
        assertThat(input.path("userText").asText()).isEqualTo("导出cny货币字典");
        assertThat(input.path("originalArguments").asText()).isEqualTo(ORIGINAL);
        assertThat(input.path("tool").path("description").asText()).contains("创建任务");
        assertThat(input.path("tool").path("inputSchema").path("properties").has("defCodes")).isTrue();
        assertThat(input.path("definitions").get(0).path("code").asText()).isEqualTo("CURRENCY");
        assertThat(input.path("definitions").get(0).path("fields").get(0).path("optionValues").toString())
                .contains("CNY");
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getOptions();
        assertThat(options.getModel()).isEqualTo("turn-model");
        assertThat(options.getInternalToolExecutionEnabled()).isFalse();
        assertThat(options.getToolContext()).isEmpty();
        assertThat(options.getToolCallbacks()).extracting(callback -> callback.getToolDefinition().name())
                .containsExactly(ActionIntentParser.PLANNING_TOOL);
    }

    @Test
    void acceptsMultipleTargetsAndMultipleConditionsInOneCreateTask() throws Exception {
        String arguments = "{\"type\":\"EXPORT\",\"defCodes\":[\"CURRENCY\",\"LANGUAGE\"],\"conditions\":{}}";
        ActionIntentParser.Result result = parse(plan(arguments), null);

        assertThat(result.status()).isEqualTo(ActionIntentParser.Status.PARSED);
        assertThat(result.plan().actions().getFirst().arguments().path("defCodes"))
                .extracting(JsonNode::asText).containsExactly("CURRENCY", "LANGUAGE");
        ActionIntentParser.Result conditions = parse(plan("{\"type\":\"EXPORT\",\"defCodes\":[\"CURRENCY\"],"
                + "\"conditions\":{\"fields\":["
                + "{\"fieldCode\":\"code\",\"operator\":\"EQ\",\"value\":\"CNY\"},"
                + "{\"fieldCode\":\"code\",\"operator\":\"NE\",\"value\":\"USD\"}]}}"), null);
        assertThat(conditions.status()).isEqualTo(ActionIntentParser.Status.PARSED);
        assertThat(conditions.plan().actions().getFirst().arguments().path("conditions").path("fields")).hasSize(2);
    }

    @Test
    void rejectsMultiActionInsteadOfSelectingTheFirst() throws Exception {
        String arguments = "{\"type\":\"EXPORT\",\"defCodes\":[],\"conditions\":{}}";
        String json = "{\"actions\":[" + action("a1", "create_task", arguments) + ","
                + action("a2", "create_task", arguments) + "]}";

        ActionIntentParser.Result result = parseJson(json, null);

        assertThat(result.status()).isEqualTo(ActionIntentParser.Status.UNSUPPORTED);
        assertThat(result.failureCode()).isEqualTo(ActionIntentParser.MULTI_ACTION_UNSUPPORTED);
        assertThat(result.plan()).isNull();
    }

    @Test
    void rejectsUnknownActionTool() throws Exception {
        String json = "{\"actions\":[" + action("a1", "delete_task", "{}") + "]}";

        ActionIntentParser.Result result = parseJson(json, null);

        assertThat(result.status()).isEqualTo(ActionIntentParser.Status.UNSUPPORTED);
        assertThat(result.failureCode()).isEqualTo(ActionIntentParser.UNKNOWN_ACTION_TOOL);
        assertThat(result.plan()).isNull();
    }

    @Test
    void rejectsEmptyModelResponse() {
        this.chatModel = prompt -> null;

        ActionIntentParser.Result result = parser().parse(request(), runtime(this.chatModel));

        assertThat(result.status()).isEqualTo(ActionIntentParser.Status.FAILED);
        assertThat(result.failureCode()).isEqualTo(ActionIntentParser.EMPTY_MODEL_RESPONSE);
    }

    @Test
    void rejectsMissingPlanningToolCall() {
        this.chatModel = prompt -> new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .content(VALID_PLAN).build())));

        ActionIntentParser.Result result = parser().parse(request(), runtime(this.chatModel));

        assertThat(result.status()).isEqualTo(ActionIntentParser.Status.FAILED);
        assertThat(result.failureCode()).isEqualTo(ActionIntentParser.INVALID_MODEL_RESPONSE);
    }

    @Test
    void rejectsLengthEvenWhenToolArgumentsLookComplete() {
        ActionIntentParser.Result result = parse(VALID_PLAN, "LENGTH");

        assertThat(result.status()).isEqualTo(ActionIntentParser.Status.FAILED);
        assertThat(result.failureCode()).isEqualTo(ActionIntentParser.TRUNCATED_MODEL_RESPONSE);
        assertThat(result.plan()).isNull();
    }

    @Test
    void rejectsMalformedPlanningJson() {
        ActionIntentParser.Result result = parse("{\"actions\":[", null);

        assertThat(result.status()).isEqualTo(ActionIntentParser.Status.FAILED);
        assertThat(result.failureCode()).isEqualTo(ActionIntentParser.INVALID_MODEL_RESPONSE);
    }

    @Test
    void rejectsArgumentsThatViolateCurrentToolSchema() throws Exception {
        ActionIntentParser.Result result = parse(plan("{\"defCodes\":[],\"conditions\":{}}"), null);

        assertThat(result.status()).isEqualTo(ActionIntentParser.Status.FAILED);
        assertThat(result.failureCode()).isEqualTo(ActionIntentParser.INVALID_ACTION_ARGUMENTS);
    }

    @Test
    void unresolvedSchemaReferenceFailsClosed() throws Exception {
        ActionIntentParser.Request request = new ActionIntentParser.Request("导出cny货币字典",
                ToolDefinition.builder().name("create_task").description("创建任务")
                        .inputSchema("{\"type\":\"object\",\"properties\":{\"type\":{\"$ref\":\"#/$defs/Missing\"}},"
                                + "\"required\":[\"type\"]}").build(), ORIGINAL);

        ActionIntentParser.Result result = parseWithRequest(plan("{\"type\":\"EXPORT\",\"defCodes\":[],"
                + "\"conditions\":{}}"), request);

        assertThat(result.status()).isEqualTo(ActionIntentParser.Status.FAILED);
        assertThat(result.failureCode()).isEqualTo(ActionIntentParser.INVALID_ACTION_ARGUMENTS);
    }

    @Test
    void rejectsTrailingContentAfterValidPlan() {
        ActionIntentParser.Result result = parse(VALID_PLAN + " {}", null);

        assertThat(result.status()).isEqualTo(ActionIntentParser.Status.FAILED);
        assertThat(result.failureCode()).isEqualTo(ActionIntentParser.INVALID_MODEL_RESPONSE);
        assertThat(result.plan()).isNull();
    }

    @Test
    void rejectsMultipleChoicesInsteadOfUsingTheFirst() {
        Generation first = response(VALID_PLAN, null).getResult();
        Generation second = response(VALID_PLAN, null).getResult();
        this.chatModel = prompt -> new ChatResponse(List.of(first, second));

        ActionIntentParser.Result result = parser().parse(request(), runtime(this.chatModel));

        assertThat(result.status()).isEqualTo(ActionIntentParser.Status.FAILED);
        assertThat(result.failureCode()).isEqualTo(ActionIntentParser.INVALID_MODEL_RESPONSE);
        assertThat(result.plan()).isNull();
    }

    @Test
    void rejectsUnsupportedSchemaConstraintInsteadOfPretendingValidation() throws Exception {
        ActionIntentParser.Request request = new ActionIntentParser.Request("导出cny货币字典",
                ToolDefinition.builder().name("create_task").description("创建任务")
                        .inputSchema("{\"type\":\"object\",\"properties\":{\"type\":{"
                                + "\"type\":\"string\",\"pattern\":\"^EXPORT$\"}},\"required\":[\"type\"]}")
                        .build(), ORIGINAL);

        ActionIntentParser.Result result = parseWithRequest(plan("{\"type\":\"IMPORT\",\"defCodes\":[]}"), request);

        assertThat(result.status()).isEqualTo(ActionIntentParser.Status.FAILED);
        assertThat(result.failureCode()).isEqualTo(ActionIntentParser.INVALID_ACTION_ARGUMENTS);
    }

    @Test
    void rejectsUnknownSchemaType() throws Exception {
        ActionIntentParser.Request request = requestWithSchema("{\"type\":\"object\",\"properties\":{"
                + "\"type\":{\"type\":\"text\"}},\"required\":[\"type\"]}");

        ActionIntentParser.Result result = parseWithRequest(plan("{\"type\":\"EXPORT\",\"defCodes\":[]}"), request);

        assertThat(result.status()).isEqualTo(ActionIntentParser.Status.FAILED);
        assertThat(result.failureCode()).isEqualTo(ActionIntentParser.INVALID_ACTION_ARGUMENTS);
    }

    @Test
    void rejectsMissingSchemaType() throws Exception {
        ActionIntentParser.Request request = requestWithSchema("{\"type\":\"object\",\"properties\":{"
                + "\"defCodes\":{\"items\":{\"type\":\"string\"}}},\"required\":[\"defCodes\"]}");

        ActionIntentParser.Result result = parseWithRequest(plan("{\"type\":\"EXPORT\",\"defCodes\":[]}"), request);

        assertThat(result.status()).isEqualTo(ActionIntentParser.Status.FAILED);
        assertThat(result.failureCode()).isEqualTo(ActionIntentParser.INVALID_ACTION_ARGUMENTS);
    }

    @Test
    void rejectsNonObjectItemsSchema() throws Exception {
        ActionIntentParser.Request request = requestWithSchema("{\"type\":\"object\",\"properties\":{"
                + "\"defCodes\":{\"type\":\"array\",\"items\":\"string\"}},\"required\":[\"defCodes\"]}");

        ActionIntentParser.Result result = parseWithRequest(plan("{\"type\":\"EXPORT\",\"defCodes\":[]}"), request);

        assertThat(result.status()).isEqualTo(ActionIntentParser.Status.FAILED);
        assertThat(result.failureCode()).isEqualTo(ActionIntentParser.INVALID_ACTION_ARGUMENTS);
    }

    @Test
    void currentCreateTaskSchemaUsesTheDocumentedSubset() {
        ToolDefinition definition = java.util.Arrays.stream(MethodToolCallbackProvider.builder()
                .toolObjects(new AiTools(null, null, null)).build().getToolCallbacks())
                .map(ToolCallback::getToolDefinition).filter(tool -> "create_task".equals(tool.name()))
                .findFirst().orElseThrow();
        this.chatModel = prompt -> response(VALID_PLAN, null);

        ActionIntentParser.Result result = parser().parse(new ActionIntentParser.Request("导出cny货币字典",
                definition, ORIGINAL), runtime(this.chatModel));

        assertThat(result.status()).as("real create_task schema: %s", definition.inputSchema())
                .isEqualTo(ActionIntentParser.Status.PARSED);
    }

    @Test
    void planningCallbackCannotExecuteAnInternalAction() {
        AtomicInteger executions = new AtomicInteger();
        this.chatModel = prompt -> {
            ToolCallingChatOptions options = (ToolCallingChatOptions) prompt.getOptions();
            if (Boolean.TRUE.equals(options.getInternalToolExecutionEnabled())) {
                for (ToolCallback callback : options.getToolCallbacks()) {
                    callback.call("{}", null);
                    executions.incrementAndGet();
                }
            }
            return response(VALID_PLAN, null);
        };

        ActionIntentParser.Result result = parser().parse(request(), runtime(this.chatModel));

        assertThat(result.status()).isEqualTo(ActionIntentParser.Status.PARSED);
        assertThat(executions).hasValue(0);
    }

    @Test
    void modelExceptionFailsClosed() {
        this.chatModel = prompt -> {
            throw new IllegalStateException("upstream unavailable");
        };

        ActionIntentParser.Result result = parser().parse(request(), runtime(this.chatModel));

        assertThat(result.status()).isEqualTo(ActionIntentParser.Status.FAILED);
        assertThat(result.failureCode()).isEqualTo(ActionIntentParser.MODEL_CALL_FAILED);
        assertThat(result.plan()).isNull();
    }

    @Test
    void missingTurnRuntimeFailsWithoutChoosingAGlobalModel() {
        ActionIntentParser.Result result = parser().parse(request(), null);

        assertThat(result.status()).isEqualTo(ActionIntentParser.Status.FAILED);
        assertThat(result.failureCode()).isEqualTo(ActionIntentParser.MODEL_CALL_FAILED);
        assertThat(this.capturedPrompt.get()).isNull();
    }

    private ActionIntentParser.Result parse(String planArguments, String finishReason) {
        return parseJson(planArguments, finishReason);
    }

    private ActionIntentParser.Result parseJson(String planArguments, String finishReason) {
        this.chatModel = prompt -> {
            this.capturedPrompt.set(prompt);
            return response(planArguments, finishReason);
        };
        return parser().parse(request(), runtime(this.chatModel));
    }

    private ActionIntentParser.Result parseWithRequest(String arguments, ActionIntentParser.Request request) {
        this.chatModel = prompt -> response(arguments, null);
        return parser().parse(request, runtime(this.chatModel));
    }

    private ActionIntentParser parser() {
        return new ActionIntentParser(this.mapper, () -> List.of(
                new SlotDefinition("CURRENCY", "货币字典", List.of(
                        new SlotDefinition.SlotField("code", "货币代码", true, List.of("CNY", "USD")))),
                new SlotDefinition("LANGUAGE", "语言字典", List.of(
                        new SlotDefinition.SlotField("code", "语言代码", true, List.of())))));
    }

    private static ActionIntentParser.Request requestWithSchema(String schema) {
        return new ActionIntentParser.Request("导出cny货币字典", ToolDefinition.builder()
                .name("create_task").description("创建任务").inputSchema(schema).build(), ORIGINAL);
    }

    private static ActionIntentParser.Request request() {
        ToolDefinition definition = ToolDefinition.builder().name("create_task").description("创建任务")
                .inputSchema("{\"type\":\"object\",\"properties\":{\"type\":{\"type\":\"string\"},"
                        + "\"defCodes\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},"
                        + "\"conditions\":{\"type\":\"object\"}},\"required\":[\"type\",\"defCodes\"],"
                        + "\"additionalProperties\":true}")
                .build();
        return new ActionIntentParser.Request("导出cny货币字典", definition, ORIGINAL);
    }

    private static ProviderRuntime runtime(ChatModel model) {
        return new ProviderRuntime(7L, "turn-provider", "turn-model", "http://localhost:0",
                "/v1/chat/completions", "turn-runtime", OpenAiChatOptions.builder().model("ignored-default")
                        .temperature(0.2).build(), null, model, null);
    }

    private static ChatResponse response(String arguments, String finishReason) {
        AssistantMessage assistant = AssistantMessage.builder().toolCalls(List.of(new AssistantMessage.ToolCall(
                "plan-1", "function", ActionIntentParser.PLANNING_TOOL, arguments))).build();
        Generation generation = finishReason == null ? new Generation(assistant)
                : new Generation(assistant, ChatGenerationMetadata.builder().finishReason(finishReason).build());
        return new ChatResponse(List.of(generation));
    }

    private static String plan(String arguments) throws com.fasterxml.jackson.core.JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.writeValueAsString(Map.of("actions", List.of(Map.of(
                "id", "a1", "tool", "create_task", "arguments", mapper.readTree(arguments),
                "unresolved", List.of(), "dependsOn", List.of()))));
    }

    private static String action(String id, String tool, String arguments) throws com.fasterxml.jackson.core.JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.writeValueAsString(Map.of("id", id, "tool", tool, "arguments", mapper.readTree(arguments),
                "unresolved", List.of(), "dependsOn", List.of()));
    }

}
