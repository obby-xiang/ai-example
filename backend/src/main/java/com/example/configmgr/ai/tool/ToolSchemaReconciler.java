package com.example.configmgr.ai.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * 外置 `inputSchema` 与 Java 方法签名（反射生成）的对账。
 *
 * <p>
 * 为什么必须对账：schema 一旦外置就是双源（YAML 一份、`@ToolParam` 反射一份）。若只改 YAML 的
 * `required` 而方法签名没变，模型看到的是"必填"，执行器看到的却是另一套 —— 失败会出现在
 * 运行时而不是启动时。故在**启动期**比较 property 名/类型与 required 集合，不一致直接失败。
 */
public final class ToolSchemaReconciler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolSchemaReconciler() {
    }

    public static void reconcile(String toolKey, String reflectedJson, String overrideJson) {
        JsonNode reflected = read(toolKey, reflectedJson, "反射");
        JsonNode override = read(toolKey, overrideJson, "YAML");
        Set<String> diffs = new LinkedHashSet<>();
        Set<String> reflectedProps = propertyTypes(reflected);
        Set<String> overrideProps = propertyTypes(override);
        if (!reflectedProps.equals(overrideProps)) {
            diffs.add("properties 不一致：反射=" + reflectedProps + "，YAML=" + overrideProps);
        }
        Set<String> reflectedRequired = required(reflected);
        Set<String> overrideRequired = required(override);
        if (!reflectedRequired.equals(overrideRequired)) {
            diffs.add("required 不一致：反射=" + reflectedRequired + "，YAML=" + overrideRequired);
        }
        if (!diffs.isEmpty()) {
            throw new IllegalStateException("工具 " + toolKey + " 的 inputSchema 与 Java 签名漂移：" + String.join("；", diffs));
        }
    }

    private static JsonNode read(String toolKey, String json, String origin) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("工具 " + toolKey + " 的 " + origin + " schema 不是合法 JSON", e);
        }
    }

    /** `property:type` 集合（排序后比较，与键序无关）。 */
    private static Set<String> propertyTypes(JsonNode schema) {
        Set<String> out = new TreeSet<>();
        JsonNode properties = schema.path("properties");
        properties.fieldNames().forEachRemaining(name -> {
            JsonNode node = properties.path(name);
            String type = node.has("type") ? node.get("type").asText()
                    : node.has("$ref") ? "$ref" : "unknown";
            out.add(name + ":" + type);
        });
        return out;
    }

    private static Set<String> required(JsonNode schema) {
        Set<String> out = new TreeSet<>();
        JsonNode required = schema.path("required");
        if (required.isArray()) {
            required.forEach(node -> out.add(node.asText()));
        }
        return out;
    }
}
