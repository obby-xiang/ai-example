package com.example.configmgr.ai.tool;

import lombok.Data;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * Metadata for a registered tool.
 */
@Data
public class ToolMeta {
    private String name;
    private String displayName; // Chinese display name
    private String[] scopePatterns;
    private RiskLevel riskLevel;
    private Channel channel;
    private ToolCallback callback;

    public enum RiskLevel {
        READ,      // auto-execute, no side effects
        WRITE,     // auto-execute, reversible side effects
        DANGER     // requires HITL confirmation before execution
    }

    /**
     * 执行通道：谁执行这次工具调用。见 {@link ToolChannel}。
     */
    public enum Channel {
        BACKEND,   // 后端可执行（经官方 ToolCallingManager 委托执行）
        FRONTEND   // 副作用在前端，后端挂起等回灌（哨兵桩体永不执行）
    }
}
