package com.example.configmgr.ai.runtime;

/**
 * Agent 运行上下文（ThreadLocal）。工具在与 AgentRuntime 相同的虚拟线程中执行，
 * 通过它获取当前 SSE 发射器，以便推送 UI 指令（导航/打开编辑器/下载等）。
 */
public final class AgentRunContext {

    private static final ThreadLocal<SseRunEmitter> EMITTER = new ThreadLocal<>();

    private AgentRunContext() {
    }

    public static void set(SseRunEmitter emitter) {
        EMITTER.set(emitter);
    }

    public static SseRunEmitter get() {
        return EMITTER.get();
    }

    public static void clear() {
        EMITTER.remove();
    }
}
