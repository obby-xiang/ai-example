package com.example.spike.ai;

import java.util.List;

import com.example.spike.tools.ToolRegistry;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.DefaultToolExecutionEligibilityPredicate;
import org.springframework.ai.model.tool.ToolExecutionEligibilityPredicate;

/**
 * 运行时可切换的「工具执行 eligibility」判定：
 *
 * <ul>
 * <li>{@code suppressExternal=false}（默认）：等价官方 {@code DefaultToolExecutionEligibilityPredicate}
 * —— 只要 {@code internalToolExecutionEnabled} 为真且有 tool_calls，就交给 {@code ToolCallingManager}
 * 执行（本 PoC 的自定义管理器在此挂起）；</li>
 * <li>{@code suppressExternal=true}（方案 B）：只要本轮 tool_calls 里含**非后端**工具
 * （前端工具/敏感工具），就返回 false → 官方循环把 {@code assistant(tool_calls)} 原样交回调用方
 * （字节码实测：{@code internalStream} 走 {@code Flux.just(resp)}），续跑权归业务侧。</li>
 * </ul>
 *
 * <p>
 * 注意：predicate 的入参只有 {@code ChatOptions} 与 {@code ChatResponse}，<b>拿不到对话历史</b>，
 * 因此它的判定粒度是“本轮响应”这一级的单值布尔——一轮里同时含后端与外部工具时无法拆分。
 */
public class SpikeEligibilityPredicate implements ToolExecutionEligibilityPredicate {

	private final DefaultToolExecutionEligibilityPredicate delegate = new DefaultToolExecutionEligibilityPredicate();

	private volatile boolean suppressExternal;

	public SpikeEligibilityPredicate(boolean suppressExternal) {
		this.suppressExternal = suppressExternal;
	}

	public boolean isSuppressExternal() {
		return this.suppressExternal;
	}

	public void setSuppressExternal(boolean suppressExternal) {
		this.suppressExternal = suppressExternal;
	}

	@Override
	public boolean test(ChatOptions options, ChatResponse response) {
		if (!this.suppressExternal) {
			return this.delegate.test(options, response);
		}
		if (response == null || !response.hasToolCalls()) {
			return false;
		}
		var output = response.getResult() == null ? null : response.getResult().getOutput();
		if (output == null || output.getToolCalls() == null || output.getToolCalls().isEmpty()) {
			return false;
		}
		List<String> names = output.getToolCalls().stream().map(tc -> tc.name()).toList();
		boolean hasExternal = names.stream().anyMatch(name -> ToolRegistry.kindOf(name) != ToolRegistry.Kind.BACKEND);
		return !hasExternal;
	}

}
