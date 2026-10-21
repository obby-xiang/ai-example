package com.example.configmgr.ai.tool;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * 只换 `getToolDefinition()`、**执行路径原样委托**的工具回调装饰器。
 *
 * <p>
 * 这里最容易踩的坑：{@link ToolCallback} 有<b>两个</b> {@code call} 重载
 * （{@code call(String)} 与 {@code call(String, ToolContext)}）。本仓用 ToolContext 传递
 * {@code runId}/{@code sessionId}（挂起续跑与台账都靠它）。**只覆写单参重载**会编译通过、
 * 单测也可能通过，但被调两参重载时 ToolContext 静默丢失 —— 表现为"工具拿不到 runId"，
 * 是典型的潜伏缺陷。故两个重载都显式委托（E8 用例钉死）。
 */
public final class OverriddenToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final ToolDefinition definition;

    public OverriddenToolCallback(ToolCallback delegate, ToolDefinition definition) {
        this.delegate = delegate;
        this.definition = definition;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return delegate.call(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return delegate.call(toolInput, toolContext);
    }

    /** 原回调（测试断言"执行路径未被替换"用）。 */
    public ToolCallback delegate() {
        return delegate;
    }
}
