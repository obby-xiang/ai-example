package com.example.configadmin.ai;

import java.util.Map;

/** UI 事件输出通道：工具执行结果向前端推送 ui_event（由 AiRuntimeService 提供实现，桥接 SseEmitter）。 */
@FunctionalInterface
public interface UiEventSink {

    void emit(Map<String, Object> uiEvent);
}
