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
    private Category category;
    private ToolCallback callback;

    public enum RiskLevel {
        READ,      // auto-execute, no side effects
        WRITE,     // auto-execute, reversible side effects
        DANGER     // requires HITL confirmation before execution
    }

    /**
     * 执行类别（P1-10 批策略，见 {@link ToolCategory}）：R/H/U/F 失败后批次继续，
     * W/J/P 确定性失败（outcome=failed 且 retryable=false）后批次停下并为未执行项补 SKIPPED。
     */
    public enum Category {
        R,  // 纯读
        H,  // 人工交互（生成式表单）
        U,  // 向导/UI 状态
        W,  // 创建写
        J,  // 异步作业
        P,  // 不可逆写（发布）
        F   // 文件下载
    }

    /**
     * 执行通道：谁执行这次工具调用。见 {@link ToolChannel}。
     */
    public enum Channel {
        BACKEND,   // 后端可执行（经官方 ToolCallingManager 委托执行）
        FRONTEND   // 副作用在前端，后端挂起等回灌（哨兵桩体永不执行）
    }
}
