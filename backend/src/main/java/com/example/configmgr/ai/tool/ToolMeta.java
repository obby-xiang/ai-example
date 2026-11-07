package com.example.configmgr.ai.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * Metadata for a registered tool.
 */
@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
public class ToolMeta {
    @JsonProperty("name")
    @ToString.Include
    private String name;
    @JsonProperty("displayName")
    private String displayName; // 兼容字段：不再由 description 截断派生（见 ToolRegistry#applyOverrides）
    /** UI 用 i18n key（`tool.<toolKey>.title`）；前端据此渲染，displayName 只作诊断兜底。 */
    @JsonProperty("titleKey")
    private String titleKey;
    /** UI 用中文标题（来自 YAML `title`；缺省空 ⇒ 前端回落 i18n / 工具名）。 */
    @JsonProperty("title")
    private String title;
    /** 模型侧语言标签（zh / en），与用户 locale 无关。 */
    @JsonProperty("lang")
    private String lang;
    @JsonProperty("scopePatterns")
    private String[] scopePatterns;
    @JsonProperty("riskLevel")
    @ToString.Include
    private RiskLevel riskLevel;
    @JsonProperty("channel")
    @ToString.Include
    private Channel channel;
    @JsonProperty("category")
    @ToString.Include
    private Category category;
    @JsonProperty("callback")
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
