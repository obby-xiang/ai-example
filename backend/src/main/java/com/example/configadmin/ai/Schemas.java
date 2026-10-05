package com.example.configadmin.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON Schema 构造辅助。 */
public final class Schemas {

    private Schemas() {
    }

    public static Map<String, Object> obj(List<Map<String, Object>> props, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Map<String, Object> p : props) {
            properties.put((String) p.remove("__name__"), p);
        }
        schema.put("properties", properties);
        if (required != null && !required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    public static Map<String, Object> prop(String name, String type, String desc, boolean required) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("__name__", name);
        p.put("type", type);
        p.put("description", desc);
        p.put("required", required);
        return p;
    }

    public static Map<String, Object> str(String name, String desc, boolean required) {
        return prop(name, "string", desc, required);
    }

    public static Map<String, Object> num(String name, String desc, boolean required) {
        return prop(name, "number", desc, required);
    }

    public static Map<String, Object> bool(String name, String desc, boolean required) {
        return prop(name, "boolean", desc, required);
    }

    public static Map<String, Object> strList(String name, String desc, boolean required) {
        Map<String, Object> p = prop(name, "array", desc, required);
        p.put("items", Map.of("type", "string"));
        return p;
    }

    public static Map<String, Object> strEnum(String name, String desc, boolean required, String... values) {
        Map<String, Object> p = prop(name, "string", desc, required);
        p.put("enum", List.of(values));
        return p;
    }

    /** 生成字段定义对象的 schema（create_config_def 用）。 */
    public static Map<String, Object> fieldDefSchema() {
        return obj(List.of(
                str("code", "字段编码（英文字母/数字/下划线）", true),
                str("label", "字段中文名（表头）", true),
                strEnum("type", "字段类型", true, "TEXT", "TEXTAREA", "NUMBER", "BOOLEAN", "DATE", "SELECT", "REFERENCE"),
                bool("required", "是否必填", false),
                strList("options", "下拉选项（type=SELECT 时必填）", false),
                num("min", "最小值（type=NUMBER）", false),
                num("max", "最大值（type=NUMBER）", false),
                str("refDefCode", "引用配置编码（type=REFERENCE）", false),
                str("refFieldCode", "引用字段编码（type=REFERENCE）", false),
                str("defaultValue", "默认值", false)
        ), List.of("code", "label", "type"));
    }
}
