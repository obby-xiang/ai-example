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
    private ToolCallback callback;

    public enum RiskLevel {
        READ,      // auto-execute, no side effects
        WRITE,     // auto-execute, reversible side effects
        DANGER     // requires HITL confirmation before execution
    }
}
