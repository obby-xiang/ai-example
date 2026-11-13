package com.example.configmgr.ai.config;

import com.example.configmgr.common.ErrorCodes;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public record EmptyLengthReasoningRecovery(@JsonProperty("reasoningEffort") String reasoningEffort,
        @JsonProperty("requestExtraBody") Map<String, Object> requestExtraBody) {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private static final Set<String> TOKEN_BUDGET_FIELDS = Set.of("maxTokens", "max_tokens", "maxCompletionTokens",
            "max_completion_tokens");

    public EmptyLengthReasoningRecovery {
        requestExtraBody = requestExtraBody == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(requestExtraBody));
    }

    public static EmptyLengthReasoningRecovery parse(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(raw);
            if (root == null || !root.isObject()) {
                throw invalidParam("emptyLengthReasoningRecovery must be a JSON object");
            }
            root.fieldNames().forEachRemaining(name -> {
                if (!"reasoningEffort".equals(name) && !"requestExtraBody".equals(name)) {
                    throw invalidParam("emptyLengthReasoningRecovery contains unknown field: " + name);
                }
            });
            JsonNode effortNode = root.path("reasoningEffort");
            if (root.has("reasoningEffort") && (!effortNode.isTextual() || !StringUtils.hasText(effortNode.textValue()))) {
                throw invalidParam("emptyLengthReasoningRecovery.reasoningEffort must be non-blank text");
            }
            JsonNode extraBodyNode = root.path("requestExtraBody");
            if (root.has("requestExtraBody") && !extraBodyNode.isObject()) {
                throw invalidParam("emptyLengthReasoningRecovery.requestExtraBody must be a JSON object");
            }
            validateExtraBody(extraBodyNode);
            String effort = effortNode.isTextual() ? effortNode.textValue() : null;
            Map<String, Object> extraBody = extraBodyNode.isObject()
                    ? MAPPER.convertValue(extraBodyNode, MAP_TYPE) : Map.of();
            return new EmptyLengthReasoningRecovery(effort, extraBody);
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new AiConfigException(ErrorCodes.INVALID_PARAM, "emptyLengthReasoningRecovery must be a JSON object");
        }
    }

    private static void validateExtraBody(JsonNode extraBody) {
        if (!extraBody.isObject()) {
            return;
        }
        if (extraBody.has("reasoning_effort")) {
            throw invalidParam("requestExtraBody.reasoning_effort conflicts with the standard reasoningEffort field");
        }
        extraBody.fieldNames().forEachRemaining(name -> {
            if (TOKEN_BUDGET_FIELDS.contains(name)) {
                throw invalidParam("Recovery requestExtraBody must not change the frozen token budget: " + name);
            }
        });
    }

    private static AiConfigException invalidParam(String message) {
        return new AiConfigException(ErrorCodes.INVALID_PARAM, message);
    }

    @Override
    public String toString() {
        return "EmptyLengthReasoningRecovery[reasoningEffort=" + this.reasoningEffort + ", requestExtraBodyKeys="
                + this.requestExtraBody.keySet() + "]";
    }

}
