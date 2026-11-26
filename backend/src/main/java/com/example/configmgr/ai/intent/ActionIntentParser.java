package com.example.configmgr.ai.intent;

import com.example.configmgr.ai.config.ProviderRuntime;
import com.example.configmgr.ai.slot.DefinitionCatalog;
import com.example.configmgr.ai.tool.AiToolNames;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class ActionIntentParser {

    public static final String PLANNING_TOOL = "submit_action_plan";

    public static final String MULTI_ACTION_UNSUPPORTED = "ACTION_PLAN_MULTI_ACTION_UNSUPPORTED";

    public static final String UNKNOWN_ACTION_TOOL = "ACTION_PLAN_UNKNOWN_TOOL";

    public static final String EMPTY_MODEL_RESPONSE = "ACTION_PLAN_EMPTY_RESPONSE";

    public static final String INVALID_MODEL_RESPONSE = "ACTION_PLAN_INVALID_RESPONSE";

    public static final String TRUNCATED_MODEL_RESPONSE = "ACTION_PLAN_TRUNCATED";

    public static final String INVALID_ACTION_ARGUMENTS = "ACTION_PLAN_INVALID_ARGUMENTS";

    public static final String MODEL_CALL_FAILED = "ACTION_PLAN_MODEL_FAILED";

    private static final Set<String> PLAN_FIELDS = Set.of("actions");

    private static final Set<String> ACTION_FIELDS = Set.of("id", "tool", "arguments", "unresolved", "dependsOn");

    /**
     * Deliberately limited JSON Schema support, not a full JSON Schema validator. Unknown validation
     * keywords and validation-bearing nodes without a type fail closed. Annotation-only nodes are explicitly
     * unconstrained and delegate value validation to the existing business resolver/preflight.
     * Object-form additionalProperties is unsupported and rejected rather than partially enforced.
     */
    private static final Set<String> SUPPORTED_SCHEMA_FIELDS = Set.of("$ref", "$defs", "$id", "$schema",
            "definitions", "type", "properties", "required", "additionalProperties", "items", "enum",
            "description", "title", "default", "examples", "deprecated", "readOnly", "writeOnly");

    private static final Set<String> SCHEMA_VALIDATION_FIELDS = Set.of("$ref", "type", "properties", "required",
            "additionalProperties", "items", "enum");

    private static final Set<String> SCHEMA_ANNOTATION_FIELDS = Set.of("description", "title", "default",
            "examples", "deprecated", "readOnly", "writeOnly");

    private static final String PLANNING_SCHEMA = """
            {"type":"object","properties":{"actions":{"type":"array","minItems":1,"items":{
            "type":"object","properties":{"id":{"type":"string"},"tool":{"type":"string"},
            "arguments":{"type":"object"},"unresolved":{"type":"array","items":{"type":"string"}},
            "dependsOn":{"type":"array","items":{"type":"string"}}},
            "required":["id","tool","arguments","unresolved","dependsOn"],"additionalProperties":false}}},
            "required":["actions"],"additionalProperties":false}
            """;

    private static final String PLANNING_INSTRUCTION = """
            Extract structured action arguments. The latest real user text is the semantic authority;
            originalArguments is only a hint. Use the selected tool description, inputSchema and the
            definition/field/enum catalog. Preserve all targets and conditions; do not invent values.
            Submit exactly one ActionPlan through submit_action_plan. Never execute a business tool.
            This entry point supports only one create_task action; unsupported plans must not be
            disguised as a single action by dropping the others.
            """;

    private final ObjectMapper mapper;

    private final DefinitionCatalog catalog;

    public ActionIntentParser(ObjectMapper mapper, DefinitionCatalog catalog) {
        this.mapper = mapper;
        this.catalog = catalog;
    }

    public Result parse(Request request, ProviderRuntime runtime) {
        if (!canCallModel(request, runtime)) {
            return failure(MODEL_CALL_FAILED);
        }
        ChatResponse response;
        try {
            response = runtime.chatModel().call(planningPrompt(request, runtime));
        }
        catch (Exception ex) {
            log.warn("action intent model call failed tool={}", request.toolDefinition().name(), ex);
            return failure(MODEL_CALL_FAILED);
        }
        return parseResponse(response, request.toolDefinition());
    }

    private Result parseResponse(ChatResponse response, ToolDefinition selectedTool) {
        if (response != null && response.getResults() != null && response.getResults().size() > 1) {
            return failure(INVALID_MODEL_RESPONSE);
        }
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return failure(EMPTY_MODEL_RESPONSE);
        }
        if (hasLengthFinishReason(response)) {
            return failure(TRUNCATED_MODEL_RESPONSE);
        }
        AssistantMessage output = response.getResult().getOutput();
        if (output.getToolCalls() == null || output.getToolCalls().size() != 1) {
            return failure(INVALID_MODEL_RESPONSE);
        }
        AssistantMessage.ToolCall call = output.getToolCalls().getFirst();
        if (!"function".equals(call.type()) || !PLANNING_TOOL.equals(call.name())
                || StringUtils.isBlank(call.arguments())) {
            return failure(INVALID_MODEL_RESPONSE);
        }
        return parsePlan(call.arguments(), selectedTool);
    }

    private Result parsePlan(String json, ToolDefinition selectedTool) {
        try {
            JsonNode root = readStrictJson(json);
            requireShape(root);
            ActionPlan plan = this.mapper.treeToValue(root, ActionPlan.class);
            if (plan.actions().size() != 1) {
                return new Result(Status.UNSUPPORTED, null, MULTI_ACTION_UNSUPPORTED);
            }
            ActionPlan.Action action = plan.actions().getFirst();
            if (!AiToolNames.CREATE_TASK.equals(action.tool()) || !action.tool().equals(selectedTool.name())) {
                return new Result(Status.UNSUPPORTED, null, UNKNOWN_ACTION_TOOL);
            }
            validateActionIds(plan.actions());
            if (!matchesSchema(readStrictJson(selectedTool.inputSchema()), action.arguments())) {
                return failure(INVALID_ACTION_ARGUMENTS);
            }
            return new Result(Status.PARSED, plan, null);
        }
        catch (Exception ex) {
            log.warn("action intent response rejected tool={}", selectedTool.name(), ex);
            return failure(INVALID_MODEL_RESPONSE);
        }
    }

    private Prompt planningPrompt(Request request, ProviderRuntime runtime) throws Exception {
        ObjectNode input = this.mapper.createObjectNode();
        input.put("userText", request.userText());
        input.put("originalArguments", request.originalArguments());
        ObjectNode tool = input.putObject("tool");
        tool.put("name", request.toolDefinition().name());
        tool.put("description", request.toolDefinition().description());
        tool.set("inputSchema", readStrictJson(request.toolDefinition().inputSchema()));
        List<?> definitions = this.catalog.list();
        if (definitions == null) {
            throw new IllegalStateException("definition catalog returned null");
        }
        input.set("definitions", this.mapper.valueToTree(definitions));
        OpenAiChatOptions options = planningOptions(runtime);
        return new Prompt(List.of(new SystemMessage(PLANNING_INSTRUCTION),
                new UserMessage(this.mapper.writeValueAsString(input))), options);
    }

    private OpenAiChatOptions planningOptions(ProviderRuntime runtime) {
        OpenAiChatOptions options = runtime.options() == null ? OpenAiChatOptions.builder().build()
                : OpenAiChatOptions.fromOptions(runtime.options());
        options.setModel(runtime.modelId());
        options.setToolCallbacks(List.of(planningCallback()));
        options.setToolChoice(OpenAiApi.ChatCompletionRequest.ToolChoiceBuilder.function(PLANNING_TOOL));
        options.setToolNames(Set.of());
        options.setInternalToolExecutionEnabled(false);
        options.setToolContext(Map.of());
        return options;
    }

    private static ToolCallback planningCallback() {
        ToolDefinition definition = ToolDefinition.builder().name(PLANNING_TOOL)
                .description("Submit a structured ActionPlan; this callback never executes an action")
                .inputSchema(PLANNING_SCHEMA).build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().build();
            }

            @Override
            public String call(String toolInput) {
                throw new IllegalStateException("planning tool execution is disabled");
            }

            @Override
            public String call(String toolInput, org.springframework.ai.chat.model.ToolContext toolContext) {
                throw new IllegalStateException("planning tool execution is disabled");
            }
        };
    }

    private static boolean canCallModel(Request request, ProviderRuntime runtime) {
        return request != null && request.toolDefinition() != null
                && AiToolNames.CREATE_TASK.equals(request.toolDefinition().name())
                && StringUtils.isNotBlank(request.userText()) && runtime != null && runtime.chatModel() != null
                && StringUtils.isNotBlank(runtime.modelId());
    }

    private static boolean hasLengthFinishReason(ChatResponse response) {
        if (response.getResults() == null) {
            return false;
        }
        for (Generation generation : response.getResults()) {
            if (generation != null && generation.getMetadata() != null
                    && "length".equalsIgnoreCase(generation.getMetadata().getFinishReason())) {
                return true;
            }
        }
        return false;
    }

    private static void requireShape(JsonNode root) {
        if (root == null || !root.isObject() || !hasOnlyFields(root, PLAN_FIELDS)) {
            throw new IllegalArgumentException("ActionPlan must contain only actions");
        }
        JsonNode actions = root.path("actions");
        if (!actions.isArray() || actions.isEmpty()) {
            throw new IllegalArgumentException("ActionPlan actions must be a non-empty array");
        }
        for (JsonNode action : actions) {
            requireActionShape(action);
        }
    }

    private static void requireActionShape(JsonNode action) {
        if (!action.isObject() || !hasOnlyFields(action, ACTION_FIELDS)
                || !ACTION_FIELDS.stream().allMatch(action::has)) {
            throw new IllegalArgumentException("action has an invalid shape");
        }
        if (!action.path("id").isTextual() || StringUtils.isBlank(action.path("id").asText())
                || !action.path("tool").isTextual() || !action.path("arguments").isObject()) {
            throw new IllegalArgumentException("action id/tool/arguments are invalid");
        }
        requireStringArray(action.path("unresolved"));
        requireStringArray(action.path("dependsOn"));
    }

    private static boolean hasOnlyFields(JsonNode node, Set<String> allowed) {
        for (Iterator<String> names = node.fieldNames(); names.hasNext();) {
            if (!allowed.contains(names.next())) {
                return false;
            }
        }
        return true;
    }

    private static void requireStringArray(JsonNode array) {
        if (!array.isArray()) {
            throw new IllegalArgumentException("action references must be arrays");
        }
        for (JsonNode entry : array) {
            if (!entry.isTextual()) {
                throw new IllegalArgumentException("action references must be strings");
            }
        }
    }

    private static void validateActionIds(List<ActionPlan.Action> actions) {
        Set<String> ids = new HashSet<>();
        for (ActionPlan.Action action : actions) {
            if (!ids.add(action.id())) {
                throw new IllegalArgumentException("action ids must be unique");
            }
        }
        for (ActionPlan.Action action : actions) {
            if (action.dependsOn().contains(action.id()) || !ids.containsAll(action.dependsOn())) {
                throw new IllegalArgumentException("action dependency is invalid");
            }
        }
    }

    private JsonNode readStrictJson(String json) throws JsonProcessingException {
        return this.mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(json);
    }

    private static boolean matchesSchema(JsonNode schema, JsonNode arguments) {
        try {
            return schema.isObject() && hasSupportedSchemaFields(schema) && matchesNode(schema, schema, arguments);
        }
        catch (RuntimeException ex) {
            log.warn("action intent schema validation failed", ex);
            return false;
        }
    }

    private static boolean hasSupportedSchemaFields(JsonNode schema) {
        return schema.isObject() && hasOnlyFields(schema, SUPPORTED_SCHEMA_FIELDS)
                && hasSupportedSchemaChildren(schema.path("properties"))
                && hasSupportedSchemaChildren(schema.path("$defs"))
                && hasSupportedSchemaChildren(schema.path("definitions"))
                && isSupportedItemsSchema(schema.path("items"))
                && isSupportedAdditionalPropertiesSchema(schema.path("additionalProperties"));
    }

    private static boolean hasSupportedSchemaChildren(JsonNode container) {
        if (container.isMissingNode()) {
            return true;
        }
        if (!container.isObject()) {
            return false;
        }
        for (JsonNode child : container) {
            if (!hasSupportedSchemaFields(child)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSupportedItemsSchema(JsonNode schema) {
        return schema.isMissingNode() || schema.isObject() && hasSupportedSchemaFields(schema);
    }

    private static boolean isSupportedAdditionalPropertiesSchema(JsonNode schema) {
        return schema.isMissingNode() || schema.isBoolean();
    }

    private static boolean matchesNode(JsonNode root, JsonNode schema, JsonNode value) {
        if (isExplicitlyUnconstrained(schema)) {
            return true;
        }
        if (schema.has("$ref")) {
            String reference = schema.path("$ref").asText();
            if (!reference.startsWith("#/")) {
                return false;
            }
            JsonNode resolved = root.at(reference.substring(1));
            return !resolved.isMissingNode() && matchesNode(root, resolved, value);
        }
        if (!matchesType(schema.path("type"), value) || !matchesEnum(schema.path("enum"), value)) {
            return false;
        }
        return matchesObject(root, schema, value) && matchesArray(root, schema, value);
    }

    private static boolean isExplicitlyUnconstrained(JsonNode schema) {
        if (!SCHEMA_ANNOTATION_FIELDS.stream().anyMatch(schema::has)) {
            return false;
        }
        return SCHEMA_VALIDATION_FIELDS.stream().noneMatch(schema::has);
    }

    private static boolean matchesType(JsonNode type, JsonNode value) {
        if (type.isMissingNode()) {
            return false;
        }
        if (type.isArray()) {
            for (JsonNode candidate : type) {
                if (matchesType(candidate, value)) {
                    return true;
                }
            }
            return false;
        }
        if (!type.isTextual()) {
            return false;
        }
        return switch (type.asText()) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> false;
        };
    }

    private static boolean matchesEnum(JsonNode options, JsonNode value) {
        if (!options.isArray()) {
            return true;
        }
        for (JsonNode option : options) {
            if (option.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesObject(JsonNode root, JsonNode schema, JsonNode value) {
        if (!value.isObject()) {
            return true;
        }
        JsonNode required = schema.path("required");
        if (required.isArray()) {
            for (JsonNode name : required) {
                if (!value.has(name.asText())) {
                    return false;
                }
            }
        }
        JsonNode properties = schema.path("properties");
        for (Iterator<String> names = value.fieldNames(); names.hasNext();) {
            String name = names.next();
            JsonNode property = properties.path(name);
            if (property.isMissingNode() && schema.path("additionalProperties").isBoolean()
                    && !schema.path("additionalProperties").asBoolean()) {
                return false;
            }
            if (property.isObject() && !matchesNode(root, property, value.get(name))) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesArray(JsonNode root, JsonNode schema, JsonNode value) {
        if (!value.isArray()) {
            return true;
        }
        if (!schema.path("items").isObject()) {
            return false;
        }
        for (JsonNode entry : value) {
            if (!matchesNode(root, schema.path("items"), entry)) {
                return false;
            }
        }
        return true;
    }

    private static Result failure(String code) {
        return new Result(Status.FAILED, null, code);
    }

    public record Request(String userText, ToolDefinition toolDefinition, String originalArguments) {
    }

    public enum Status {
        PARSED,
        FAILED,
        UNSUPPORTED
    }

    public record Result(Status status, ActionPlan plan, String failureCode) {
    }
}
