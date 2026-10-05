package com.example.configadmin.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 工具执行结果：回灌模型的文本 + 驱动前端的 UI 事件（AI→业务状态同步）。 */
public class ToolResult {

    private final boolean ok;
    private final String text;
    private final List<Map<String, Object>> uiEvents = new ArrayList<>();

    public ToolResult(boolean ok, String text) {
        this.ok = ok;
        this.text = text;
    }

    public static ToolResult ok(String text) {
        return new ToolResult(true, text);
    }

    public static ToolResult fail(String text) {
        return new ToolResult(false, text);
    }

    public ToolResult event(String type, Map<String, Object> payload) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", type);
        e.putAll(payload);
        uiEvents.add(e);
        return this;
    }

    public ToolResult event(String type, String key, Object value) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(key, value);
        return event(type, payload);
    }

    public boolean isOk() { return ok; }
    public String getText() { return text; }
    public List<Map<String, Object>> getUiEvents() { return uiEvents; }
}
