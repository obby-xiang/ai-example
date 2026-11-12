package com.example.configmgr.ai.tools;

import com.example.configmgr.ai.tool.AiToolNames;
import com.example.configmgr.ai.tool.ToolSchemaNormalizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiToolsToolSchemaTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void setConditionsSchemaIsBatchOnlyAndLegacySetConditionIsAbsent() throws Exception {
        List<ToolCallback> callbacks = List.of(MethodToolCallbackProvider.builder()
                .toolObjects(new AiTools(null, null, null))
                .build()
                .getToolCallbacks());
        List<String> names = new ArrayList<>();
        ToolCallback batch = null;
        for (ToolCallback callback : callbacks) {
            String name = callback.getToolDefinition().name();
            names.add(name);
            if (AiToolNames.SET_CONDITIONS.equals(name)) {
                batch = callback;
            }
        }

        assertThat(names).doesNotContain("set_condition").contains("set_conditions");
        assertThat(batch).isNotNull();
        JsonNode root = this.mapper.readTree(ToolSchemaNormalizer.normalize(
                batch.getToolDefinition().name(), batch.getToolDefinition().inputSchema()));
        assertThat(root.path("properties").fieldNames()).toIterable().containsExactly("items");
        assertThat(root.path("required").get(0).asText()).isEqualTo("items");
        JsonNode items = root.path("properties").path("items");
        assertThat(items.path("type").asText()).isEqualTo("array");
        assertThat(items.path("minItems").asInt()).isEqualTo(1);
        JsonNode item = items.path("items");
        assertThat(item.path("properties").fieldNames()).toIterable().containsExactlyInAnyOrder("defCode", "conditions");
        assertThat(this.mapper.convertValue(item.path("required"), String[].class))
                .containsExactlyInAnyOrder("defCode", "conditions");
        assertThat(item.path("properties").path("conditions").path("properties").fieldNames())
                .toIterable().containsExactlyInAnyOrder("scopeKeys", "fields");
    }
}
